package xyz.aezdek.hush.calls

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.AezClient
import xyz.aezdek.hush.client.Composer
import xyz.aezdek.hush.crypto.B64
import xyz.aezdek.hush.crypto.Sodium
import xyz.aezdek.hush.protocol.CallSignal
import xyz.aezdek.hush.protocol.CallSignalType
import xyz.aezdek.hush.protocol.CallSummary
import xyz.aezdek.hush.protocol.Kind
import xyz.aezdek.hush.protocol.LivePayload
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.MessageType
import xyz.aezdek.hush.protocol.TurnResponse
import xyz.aezdek.hush.protocol.WsFrame
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class CallStatus { IDLE, OUTGOING, INCOMING, ACTIVE }
enum class PeerState { RINGING, CONNECTING, CONNECTED, RECONNECTING, LEFT, DECLINED }

/** [camera] and [mic] are what they told us (null until they say; older apps never do). */
data class PeerUi(val userId: String, val state: PeerState, val video: VideoTrack? = null, val camera: Boolean? = null, val mic: Boolean? = null)
data class LiveComment(val id: Long, val from: String, val text: String)
data class LiveUi(val host: Boolean, val hostId: String, val objectId: String? = null, val comments: List<LiveComment> = emptyList(), val hearts: Int = 0)

data class CallUi(
    val status: CallStatus = CallStatus.IDLE,
    val callId: String? = null,
    val threadId: String? = null,
    val video: Boolean = true,
    val from: String? = null,
    val peers: Map<String, PeerUi> = emptyMap(),
    val local: VideoTrack? = null,
    val muted: Boolean = false,
    val cameraOff: Boolean = false,
    /** The front camera is in use (the self view is mirrored only then). */
    val front: Boolean = true,
    val speaker: Boolean = true,
    val startedAt: Long? = null,
    val live: LiveUi? = null,
    val relay: Boolean = false,
)

/**
 * Peer-to-peer calls (a full mesh, up to 10 people) and Live, interoperable with the web client
 * (web/src/features/calls/calls.ts): the same signal types, the same who-offers rule, the same Live relay. Signals are
 * sealed to each peer and signed by the sender, so the DTLS fingerprints in the SDP are authenticated end to end and
 * a TURN relay only ever sees SRTP ciphertext.
 *
 * Someone signed in on two devices gets every signal on both: signals carry the sending device, and once a device
 * answers, everything for that call names it, so their other devices stop ringing and stay out. A dropped connection
 * is restarted by the offering side and given up if it stays down, so a call never sits frozen.
 */
class CallManager(private val context: Context, private val client: AezClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val _state = MutableStateFlow(CallUi())
    val state: StateFlow<CallUi> = _state

    val egl: EglBase by lazy { EglBase.create() }
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setAudioDeviceModule(
                JavaAudioDeviceModule.builder(context).setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true)
                    .createAudioDeviceModule(),
            )
            .createPeerConnectionFactory()
    }

    private val pcs = HashMap<String, PeerConnection>()
    private val pendingIce = HashMap<String, MutableList<IceCandidate>>()
    private var participants = listOf<String>()
    private var iceServers: List<PeerConnection.IceServer>? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var ringTimeout: Job? = null
    private var connectedOnce = false
    /** The device each person is in this call on, once they answered. */
    private val peerDevice = HashMap<String, String>()
    private val makingOffer = HashSet<String>()
    private val watchdogs = HashMap<String, Job>()
    private val restarts = HashMap<String, Job>()
    private val liveInvites = HashMap<String, String>()
    private var commentSeq = 0L

    private val audio = context.getSystemService(AudioManager::class.java)
    private var ringtone: Ringtone? = null
    private var ringback: Job? = null

    private val me: String? get() = client.state.value.me?.id

    // -------------------------------------------------------------------------------------------------- signalling

    private fun send(to: String, signal: CallSignal, ring: Boolean = false) {
        val self = to == me
        client.sendSignal(to, signal.copy(device = DEVICE, toDevice = if (self) null else signal.toDevice ?: peerDevice[to]), ring)
    }

    private fun broadcast(signal: CallSignal, ring: Boolean = false) {
        for (id in participants) if (id != me) send(id, signal, ring)
    }

    /** Tells my other devices (they get signals sent to my own id) that this call was answered or declined here. */
    private fun tellMyDevices(type: String, callId: String) {
        me?.let { send(it, CallSignal(type, callId)) }
    }

    /** Whether this side makes the offers to [userId]: the lower id does, and a Live host always does. */
    private fun offers(userId: String): Boolean {
        val live = _state.value.live
        return if (live != null) live.host else (me ?: "") < userId
    }

    fun onSignal(frame: WsFrame) {
        val (from, signal) = client.openSignal(frame) ?: return
        scope.launch { runCatching { handle(from, signal) } }
    }

    private suspend fun handle(from: String, s: CallSignal) {
        val state = _state.value
        val sameCall = state.callId == s.callId
        if (s.device == DEVICE) return
        if (from == me) {
            // Another of my devices answered or declined: this one stops ringing.
            if (sameCall && state.status == CallStatus.INCOMING) reset()
            return
        }
        if (s.toDevice != null && s.toDevice != DEVICE) {
            // Meant for another of my devices, so that one answered this call.
            if (sameCall && state.status == CallStatus.INCOMING) reset()
            return
        }
        when (s.type) {
            CallSignalType.INVITE -> {
                if (s.live) {
                    liveInvites[from] = s.callId
                    Graph.toast("@${client.state.value.users[from]?.username} started a live video")
                    return
                }
                if (sameCall) return
                if (state.status != CallStatus.IDLE) {
                    // Busy only when really on a call; ringing for someone else isn't.
                    if (state.status == CallStatus.ACTIVE || state.status == CallStatus.OUTGOING) send(from, CallSignal(CallSignalType.BUSY, s.callId, toDevice = s.device))
                    return
                }
                peerDevice.clear()
                s.device?.let { peerDevice[from] = it }
                participants = s.participants.ifEmpty { listOf(from) }
                _state.value = CallUi(status = CallStatus.INCOMING, callId = s.callId, threadId = s.threadId, video = s.video, from = from)
                startRinging(from, s.video)
                // Unanswered: stop ringing. The caller gives up on its own and logs the missed call.
                ringTimeout = scope.launch {
                    delay(RING_MS)
                    if (_state.value.status == CallStatus.INCOMING && _state.value.callId == s.callId) reset()
                }
            }
            CallSignalType.ACCEPT -> {
                if (!sameCall || state.status == CallStatus.IDLE) return
                if (state.status == CallStatus.INCOMING) {
                    // Someone else in a group call answered while this phone still rings.
                    s.device?.let { peerDevice[from] = it }
                    return
                }
                val known = peerDevice[from]
                if (known != null && s.device != null && known != s.device && pcs.containsKey(from)) {
                    // They answered on a second device as well: that one stands down.
                    return send(from, CallSignal(CallSignalType.HANGUP, s.callId, toDevice = s.device))
                }
                s.device?.let { peerDevice[from] = it }
                val live = state.live
                if (live?.host == true && state.peers[from]?.state.let { it == null || it == PeerState.LEFT }) {
                    val watching = state.peers.values.count { it.state != PeerState.LEFT && it.state != PeerState.DECLINED }
                    if (watching >= MAX_LIVE_VIEWERS) return send(from, CallSignal(CallSignalType.BUSY, s.callId))
                }
                stopRinging()
                if (state.status == CallStatus.OUTGOING) _state.update { it.copy(status = CallStatus.ACTIVE, startedAt = System.currentTimeMillis()) }
                setPeer(from, PeerState.CONNECTING)
                watch(from, CONNECT_TIMEOUT_MS)
                if (from !in participants) participants = participants + from
                if (offers(from)) offerTo(from) else send(from, CallSignal(CallSignalType.JOIN, s.callId))
            }
            CallSignalType.JOIN -> if (sameCall && state.status == CallStatus.ACTIVE) {
                if (s.device != null && from !in peerDevice) peerDevice[from] = s.device!!
                offerTo(from)
            }
            CallSignalType.DECLINE, CallSignalType.BUSY -> {
                if (!sameCall) return
                if (state.live?.host == false && from == state.live.hostId) {
                    Graph.toast("This live video is full ($MAX_LIVE_VIEWERS viewers)")
                    return reset()
                }
                // Only someone still ringing can decline; a stray decline from another of their devices changes nothing.
                if (state.peers[from]?.state != PeerState.RINGING) return
                setPeer(from, PeerState.DECLINED)
                if (s.type == CallSignalType.BUSY) Graph.toast("@${client.state.value.users[from]?.username} is on another call")
                maybeEnd()
            }
            CallSignalType.OFFER -> if (sameCall && s.sdp != null && state.status == CallStatus.ACTIVE) {
                if (s.device != null && from !in peerDevice) peerDevice[from] = s.device!!
                answerOffer(from, s.sdp!!)
            }
            CallSignalType.ANSWER -> {
                val pc = pcs[from] ?: return
                if (!sameCall || s.sdp == null || pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
                pc.setRemote(SessionDescription(SessionDescription.Type.ANSWER, s.sdp))
                flushIce(from)
            }
            CallSignalType.ICE -> {
                if (!sameCall || s.candidate == null || state.status != CallStatus.ACTIVE) return
                val candidate = IceCandidate(s.sdpMid ?: "", s.sdpMLineIndex ?: 0, s.candidate)
                val pc = pcs[from]
                if (pc?.remoteDescription != null) pc.addIceCandidate(candidate) else pendingIce.getOrPut(from) { ArrayList() } += candidate
            }
            CallSignalType.HANGUP -> {
                if (!sameCall) return
                if (state.status == CallStatus.INCOMING) return reset()
                closePeer(from)
                if (state.live?.host == false && from == state.live.hostId) {
                    Graph.toast("The live video ended")
                    return reset()
                }
                maybeEnd()
            }
            CallSignalType.STATE -> if (sameCall) {
                _state.update { st -> st.copy(peers = st.peers + (from to (st.peers[from] ?: PeerUi(from, PeerState.CONNECTING)).copy(camera = s.camera, mic = s.mic))) }
            }
            CallSignalType.CHAT -> {
                if (!sameCall || state.live == null || s.text.isNullOrBlank()) return
                // The host relays viewers' comments; the original author travels in participants[0].
                val author = if (state.live.host) from else s.participants.firstOrNull() ?: from
                addComment(author, s.text!!)
                if (state.live.host) relayLive(from, s)
            }
            CallSignalType.HEART -> {
                if (!sameCall || state.live == null) return
                _state.update { it.copy(live = it.live?.let { l -> l.copy(hearts = l.hearts + 1) }) }
                if (state.live.host) relayLive(from, s)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------- media

    private suspend fun iceServers(): List<PeerConnection.IceServer> {
        iceServers?.let { return it }
        val turn = runCatching { client.api.get<TurnResponse>("/turn") }.getOrNull()
        _state.update { it.copy(relay = turn?.relay == true) }
        val servers = turn?.iceServers?.map { server ->
            PeerConnection.IceServer.builder(server.urls).apply {
                server.username?.let { setUsername(it) }
                server.credential?.let { setPassword(it) }
            }.createIceServer()
        } ?: listOf(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())
        iceServers = servers
        return servers
    }

    private fun localMedia(video: Boolean) {
        if (audioTrack == null) {
            val source = factory.createAudioSource(MediaConstraints())
            audioSource = source
            audioTrack = factory.createAudioTrack("a0", source)
        }
        if (video && videoTrack == null) startCamera(front = true)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(video)
        _state.update { it.copy(local = videoTrack, cameraOff = videoTrack == null, front = true) }
    }

    private fun startCamera(front: Boolean) {
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) == front } ?: enumerator.deviceNames.firstOrNull() ?: return
        val cam = enumerator.createCapturer(name, null)
        val helper = SurfaceTextureHelper.create("capture", egl.eglBaseContext)
        val source = factory.createVideoSource(false)
        cam.initialize(helper, context, source.capturerObserver)
        cam.startCapture(1280, 720, 30)
        capturer = cam
        captureHelper = helper
        videoSource = source
        videoTrack = factory.createVideoTrack("v0", source)
    }

    /**
     * Two people get 720p at up to 2.5 Mbps; a bigger mesh sends one stream per person, so each gets less. Scaling is done
     * in the encoder: the camera keeps capturing 720p (changing its format mid-call stalled some phones).
     */
    private fun adapt() {
        val count = pcs.size + 1
        val (scale, bitrate) = when {
            count <= 2 -> 1.0 to 2_500_000
            count <= 4 -> 2.0 to 800_000
            else -> 3.0 to 400_000
        }
        for (pc in pcs.values) {
            for (sender in pc.senders) {
                if (sender.track()?.kind() != MediaStreamTrack.VIDEO_TRACK_KIND) continue
                val params = sender.parameters
                val encoding = params.encodings.firstOrNull() ?: continue
                encoding.maxBitrateBps = bitrate
                encoding.scaleResolutionDownBy = scale
                sender.setParameters(params)
            }
        }
    }

    private suspend fun connection(userId: String): PeerConnection {
        pcs[userId]?.let { return it }
        val config = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = factory.createPeerConnection(config, Observer(userId)) ?: throw IllegalStateException("WebRTC unavailable")
        pcs[userId] = pc
        // Live viewers only receive: they answer the host's offer without sending anything.
        if (_state.value.live?.host != false) {
            audioTrack?.let { pc.addTrack(it, listOf("s0")) }
            videoTrack?.let { pc.addTrack(it, listOf("s0")) }
        }
        adapt()
        return pc
    }

    private inner class Observer(private val userId: String) : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit

        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            val callId = _state.value.callId ?: return
            send(userId, CallSignal(CallSignalType.ICE, callId, candidate = candidate.sdp, sdpMid = candidate.sdpMid, sdpMLineIndex = candidate.sdpMLineIndex))
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track() as? VideoTrack ?: return
            _state.update { s -> s.copy(peers = s.peers + (userId to (s.peers[userId] ?: PeerUi(userId, PeerState.CONNECTING)).copy(video = track))) }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            scope.launch { connectionChanged(userId, newState) }
        }
    }

    private suspend fun connectionChanged(userId: String, newState: PeerConnection.PeerConnectionState?) {
        if (pcs[userId] == null) return
        when (newState) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                connectedOnce = true
                unwatch(userId)
                setPeer(userId, PeerState.CONNECTED)
                sendState(userId)
                adapt()
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                // Often a network change that heals by itself; if not, restart ICE.
                setPeer(userId, PeerState.RECONNECTING)
                restarts.remove(userId)?.cancel()
                restarts[userId] = scope.launch {
                    delay(RECONNECT_AFTER_MS)
                    restart(userId)
                }
                watch(userId, LOST_AFTER_MS)
            }
            PeerConnection.PeerConnectionState.FAILED -> {
                setPeer(userId, PeerState.RECONNECTING)
                restart(userId)
                watch(userId, LOST_AFTER_MS)
            }
            PeerConnection.PeerConnectionState.CLOSED -> closePeer(userId)
            else -> Unit
        }
    }

    /** ICE restart, from the side that makes offers (the other side's restart arrives as an offer). */
    private suspend fun restart(userId: String) {
        val pc = pcs[userId] ?: return
        if (pc.connectionState() == PeerConnection.PeerConnectionState.CONNECTED || !offers(userId)) return
        pc.restartIce()
        runCatching { offerTo(userId) }
    }

    /** Gives up on someone who isn't connected by the time this fires. */
    private fun watch(userId: String, ms: Long) {
        watchdogs.remove(userId)?.cancel()
        watchdogs[userId] = scope.launch {
            delay(ms)
            watchdogs.remove(userId)
            if (pcs[userId]?.connectionState() == PeerConnection.PeerConnectionState.CONNECTED) return@launch
            val callId = _state.value.callId ?: return@launch
            Graph.toast("Lost the connection to @${client.state.value.users[userId]?.username ?: "them"}", error = true)
            send(userId, CallSignal(CallSignalType.HANGUP, callId))
            closePeer(userId)
            maybeEnd()
        }
    }

    private fun unwatch(userId: String) {
        watchdogs.remove(userId)?.cancel()
        restarts.remove(userId)?.cancel()
    }

    /** Tells one person, or everyone connected, whether my camera and microphone are on. */
    private fun sendState(to: String? = null) {
        val state = _state.value
        val callId = state.callId ?: return
        if (state.live?.host == false) return
        val signal = CallSignal(CallSignalType.STATE, callId, camera = videoTrack != null && !state.cameraOff, mic = !state.muted)
        if (to != null) send(to, signal) else for (id in pcs.keys) send(id, signal)
    }

    private suspend fun offerTo(userId: String) {
        val pc = connection(userId)
        if (pc.signalingState() != PeerConnection.SignalingState.STABLE) return
        makingOffer += userId
        try {
            val offer = pc.create(offer = true)
            if (pc.signalingState() != PeerConnection.SignalingState.STABLE) return
            pc.setLocal(offer)
            send(userId, CallSignal(CallSignalType.OFFER, _state.value.callId ?: return, sdp = offer.description))
        } finally {
            makingOffer -= userId
        }
    }

    private suspend fun answerOffer(userId: String, sdp: String) {
        val pc = connection(userId)
        if (userId in makingOffer || pc.signalingState() != PeerConnection.SignalingState.STABLE) {
            // Both sides offered at once: the offering side's wins, this side drops its own and answers.
            if (offers(userId)) return
            runCatching { pc.setLocal(SessionDescription(SessionDescription.Type.ROLLBACK, "")) }
        }
        pc.setRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))
        flushIce(userId)
        val answer = pc.create(offer = false)
        pc.setLocal(answer)
        send(userId, CallSignal(CallSignalType.ANSWER, _state.value.callId ?: return, sdp = answer.description))
    }

    private fun flushIce(userId: String) {
        val pc = pcs[userId] ?: return
        pendingIce.remove(userId)?.forEach { pc.addIceCandidate(it) }
    }

    // ----------------------------------------------------------------------------------------------------- actions

    fun start(threadId: String, video: Boolean) = scope.launch {
        val thread = client.state.value.threads[threadId] ?: return@launch
        val me = me ?: return@launch
        if (_state.value.status != CallStatus.IDLE) return@launch Graph.toast("You're already in a call")
        runCatching { localMedia(video) }.onFailure { return@launch Graph.toast("Camera or microphone unavailable", error = true) }
        val callId = B64.encode(Sodium.random(12))
        participants = thread.members.map { it.userId }
        connectedOnce = false
        val peers = participants.filter { it != me }.associateWith { PeerUi(it, PeerState.RINGING) }
        _state.update { it.copy(status = CallStatus.OUTGOING, callId = callId, threadId = threadId, video = video, peers = peers, from = null) }
        startRingback()
        broadcast(CallSignal(CallSignalType.INVITE, callId, threadId = threadId, video = video, participants = participants), ring = true)
        CallService.start(context)
        openCallScreen()
        peerDevice.clear()
        ringTimeout = scope.launch {
            delay(RING_MS)
            ringingOver(callId)
        }
    }

    /** Nobody answered in time: hang up. In a call that's under way, stop ringing whoever never answered. */
    private fun ringingOver(callId: String) {
        val state = _state.value
        if (state.callId != callId) return
        if (state.status == CallStatus.OUTGOING) {
            hangup()
            return
        }
        for (peer in state.peers.values) {
            if (peer.state != PeerState.RINGING) continue
            send(peer.userId, CallSignal(CallSignalType.HANGUP, callId))
            setPeer(peer.userId, PeerState.DECLINED)
        }
    }

    fun accept() = scope.launch {
        val state = _state.value
        if (state.status != CallStatus.INCOMING || state.callId == null) return@launch
        stopRinging()
        ringTimeout?.cancel()
        runCatching { localMedia(state.video) }.onFailure {
            Graph.toast("Camera or microphone unavailable", error = true)
            decline()
            return@launch
        }
        connectedOnce = false
        val me = me ?: return@launch
        val peers = participants.filter { it != me }.associateWith { PeerUi(it, PeerState.CONNECTING) }
        _state.update { it.copy(status = CallStatus.ACTIVE, startedAt = System.currentTimeMillis(), peers = peers, local = videoTrack) }
        for (id in peers.keys) watch(id, CONNECT_TIMEOUT_MS)
        CallService.start(context)
        tellMyDevices(CallSignalType.ACCEPT, state.callId)
        broadcast(CallSignal(CallSignalType.ACCEPT, state.callId))
    }

    fun decline() = scope.launch {
        val state = _state.value
        stopRinging()
        if (state.callId != null && state.from != null) {
            send(state.from, CallSignal(CallSignalType.DECLINE, state.callId))
            tellMyDevices(CallSignalType.DECLINE, state.callId)
        }
        reset()
    }

    fun hangup() = scope.launch {
        val state = _state.value
        state.callId?.let { broadcast(CallSignal(CallSignalType.HANGUP, it)) }
        logCall()
        val live = state.live
        if (live?.host == true && live.objectId != null) Graph.scope.launch { runCatching { client.composer.delete(live.objectId) } }
        reset()
    }

    /** The caller writes the call into the chat history (encrypted like any message). */
    private fun logCall() {
        val state = _state.value
        val threadId = state.threadId ?: return
        val callId = state.callId ?: return
        if (state.live != null || state.from != null) return
        val duration = if (state.startedAt != null && connectedOnce) System.currentTimeMillis() - state.startedAt else 0
        val others = participants.filter { it != me }
        Graph.scope.launch {
            runCatching {
                client.composer.sendMessage(threadId, MessagePayload(MessageType.CALL, call = CallSummary(callId, state.video, duration, !connectedOnce, others)))
            }
        }
    }

    fun toggleMute() = scope.launch {
        val muted = !_state.value.muted
        audioTrack?.setEnabled(!muted)
        _state.update { it.copy(muted = muted) }
        sendState()
    }

    fun toggleCamera() = scope.launch {
        val track = videoTrack
        if (track == null) {
            runCatching { startCamera(front = true) }.onFailure { return@launch Graph.toast("Camera unavailable", error = true) }
            val fresh = videoTrack ?: return@launch
            for (pc in pcs.values) pc.addTrack(fresh, listOf("s0"))
            _state.update { it.copy(cameraOff = false, video = true, local = fresh, front = true) }
            for (id in pcs.keys.toList()) offerTo(id)
            adapt()
            sendState()
            return@launch
        }
        val off = !_state.value.cameraOff
        track.setEnabled(!off)
        _state.update { it.copy(cameraOff = off) }
        sendState()
    }

    fun flip() = scope.launch {
        val cam = capturer ?: return@launch
        cam.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                _state.update { it.copy(front = isFrontCamera) }
            }

            override fun onCameraSwitchError(error: String?) {
                Graph.toast("Could not switch camera", error = true)
            }
        })
    }

    fun toggleSpeaker() = scope.launch { setSpeaker(!_state.value.speaker) }

    @Suppress("DEPRECATION")
    private fun setSpeaker(on: Boolean) {
        audio.isSpeakerphoneOn = on
        _state.update { it.copy(speaker = on) }
    }

    fun testRing() = scope.launch {
        startRingtone()
        Graph.toast("🔔 Test ring: notifications reach this phone")
        delay(4000)
        stopRinging()
    }

    // -------------------------------------------------------------------------------------------------------- live

    /** Live: broadcast to followers. Viewers join peer to peer (up to 9); chat and hearts relay through the host. */
    fun startLive() = scope.launch {
        if (_state.value.status != CallStatus.IDLE) return@launch Graph.toast("You're already in a call")
        runCatching { localMedia(true) }.onFailure { return@launch Graph.toast("Camera unavailable", error = true) }
        val me = me ?: return@launch
        val callId = B64.encode(Sodium.random(12))
        val entity = Graph.attempt {
            client.composer.publish(
                Composer.Draft(Kind.LIVE, client.composer.profileAudience(), LivePayload(startedAt = System.currentTimeMillis(), callId = callId),
                    expiresAt = System.currentTimeMillis() + 6 * 60 * 60 * 1000L),
            )
        } ?: return@launch reset()
        participants = listOf(me)
        _state.value = CallUi(status = CallStatus.ACTIVE, callId = callId, video = true, startedAt = System.currentTimeMillis(), local = videoTrack,
            live = LiveUi(host = true, hostId = me, objectId = entity.id))
        CallService.start(context)
        openCallScreen()
        val followers = client.state.value.users.values.filter { it.followsMe == "following" }
        for (user in followers) send(user.id, CallSignal(CallSignalType.INVITE, callId, live = true, participants = listOf(me)))
    }

    fun joinLive(hostId: String, callIdFromObject: String?) = scope.launch {
        if (_state.value.status != CallStatus.IDLE) return@launch Graph.toast("You're already in a call")
        val callId = callIdFromObject ?: liveInvites[hostId] ?: return@launch Graph.toast("That live video has ended")
        participants = listOf(hostId)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(true)
        _state.value = CallUi(status = CallStatus.ACTIVE, callId = callId, video = true, startedAt = System.currentTimeMillis(),
            live = LiveUi(host = false, hostId = hostId), peers = mapOf(hostId to PeerUi(hostId, PeerState.CONNECTING)))
        peerDevice.clear()
        openCallScreen()
        watch(hostId, CONNECT_TIMEOUT_MS)
        send(hostId, CallSignal(CallSignalType.ACCEPT, callId))
    }

    fun sendLive(chat: String?) = scope.launch {
        val state = _state.value
        val live = state.live ?: return@launch
        val callId = state.callId ?: return@launch
        val signal = if (chat != null) CallSignal(CallSignalType.CHAT, callId, text = chat) else CallSignal(CallSignalType.HEART, callId)
        if (chat != null) addComment(me ?: return@launch, chat) else _state.update { it.copy(live = it.live?.let { l -> l.copy(hearts = l.hearts + 1) }) }
        if (live.host) broadcast(signal) else send(live.hostId, signal)
    }

    private fun relayLive(from: String, s: CallSignal) {
        for (id in participants) if (id != me && id != from) send(id, s.copy(participants = listOf(from), device = null, toDevice = null))
    }

    private fun addComment(from: String, text: String) {
        val comment = LiveComment(commentSeq++, from, text)
        _state.update { s -> s.copy(live = s.live?.let { l -> l.copy(comments = (l.comments + comment).takeLast(40)) }) }
    }

    // ----------------------------------------------------------------------------------------------------- ringing

    private fun startRinging(from: String, video: Boolean) {
        startRingtone()
        CallNotifications.showIncoming(context, client.state.value.users[from]?.username ?: "Someone", video)
    }

    private fun startRingtone() {
        runCatching {
            ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                isLooping = true
                play()
            }
        }
        runCatching {
            context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        }
    }

    private fun startRingback() {
        ringback = scope.launch {
            val tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) }.getOrNull() ?: return@launch
            try {
                while (true) {
                    tone.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1200)
                    delay(3000)
                }
            } finally {
                tone.release()
            }
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { context.getSystemService(Vibrator::class.java)?.cancel() }
        ringback?.cancel()
        ringback = null
        CallNotifications.cancelIncoming(context)
    }

    private fun openCallScreen() {
        context.startActivity(Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ----------------------------------------------------------------------------------------------------- cleanup

    private fun setPeer(userId: String, peerState: PeerState) {
        _state.update { s -> s.copy(peers = s.peers + (userId to (s.peers[userId]?.copy(state = peerState) ?: PeerUi(userId, peerState)))) }
    }

    private fun closePeer(userId: String) {
        unwatch(userId)
        pcs.remove(userId)?.dispose()
        pendingIce.remove(userId)
        _state.update { s -> s.copy(peers = s.peers + (userId to PeerUi(userId, PeerState.LEFT))) }
        adapt()
    }

    private fun maybeEnd() {
        val state = _state.value
        if (state.status == CallStatus.IDLE || state.live?.host == true) return
        val anyone = state.peers.values.any { it.state != PeerState.LEFT && it.state != PeerState.DECLINED }
        if (!anyone) {
            state.callId?.let { broadcast(CallSignal(CallSignalType.HANGUP, it)) }
            logCall()
            reset()
        }
    }

    private fun reset() {
        stopRinging()
        ringTimeout?.cancel()
        ringTimeout = null
        for (job in watchdogs.values + restarts.values) job.cancel()
        watchdogs.clear()
        restarts.clear()
        makingOffer.clear()
        for (pc in pcs.values) pc.dispose()
        pcs.clear()
        pendingIce.clear()
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        captureHelper?.dispose()
        captureHelper = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        audioTrack?.dispose()
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        participants = emptyList()
        audio.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = false
        _state.value = CallUi()
        CallService.stop(context)
    }

    companion object {
        /** The host uploads one stream per viewer, so Live stays small: the host plus 9 is the same mesh size as a call. */
        const val MAX_LIVE_VIEWERS = 9
        const val RING_MS = 45_000L
        /** Accepted but never connected: give up on that person. */
        const val CONNECT_TIMEOUT_MS = 40_000L
        /** A connection that drops is restarted after this, and given up if it isn't back within LOST_AFTER_MS. */
        const val RECONNECT_AFTER_MS = 4_000L
        const val LOST_AFTER_MS = 30_000L

        /** This phone, for this run of the app. */
        val DEVICE: String = java.util.UUID.randomUUID().toString().replace("-", "").take(18)
    }
}

// ------------------------------------------------------------------------------------------------ WebRTC helpers

private suspend fun PeerConnection.create(offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
    val observer = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            if (cont.isActive) {
                if (sdp != null) cont.resume(sdp) else cont.resumeWithException(IllegalStateException("No SDP"))
            }
        }
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) {
            if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "createOffer failed"))
        }
        override fun onSetFailure(error: String?) = Unit
    }
    if (offer) createOffer(observer, MediaConstraints()) else createAnswer(observer, MediaConstraints())
}

private suspend fun PeerConnection.setLocal(sdp: SessionDescription) = set(sdp, local = true)
private suspend fun PeerConnection.setRemote(sdp: SessionDescription) = set(sdp, local = false)

private suspend fun PeerConnection.set(sdp: SessionDescription, local: Boolean): Unit = suspendCancellableCoroutine { cont ->
    val observer = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
        override fun onSetSuccess() {
            if (cont.isActive) cont.resume(Unit)
        }
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) {
            if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "setDescription failed"))
        }
    }
    if (local) setLocalDescription(observer, sdp) else setRemoteDescription(observer, sdp)
}
