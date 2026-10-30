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
enum class PeerState { RINGING, CONNECTING, CONNECTED, LEFT, DECLINED }

data class PeerUi(val userId: String, val state: PeerState, val video: VideoTrack? = null)
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
    private val liveInvites = HashMap<String, String>()
    private var commentSeq = 0L

    private val audio = context.getSystemService(AudioManager::class.java)
    private var ringtone: Ringtone? = null
    private var ringback: Job? = null

    private val me: String? get() = client.state.value.me?.id

    // -------------------------------------------------------------------------------------------------- signalling

    private fun send(to: String, signal: CallSignal, ring: Boolean = false) {
        client.sendSignal(to, signal, ring)
    }

    private fun broadcast(signal: CallSignal, ring: Boolean = false) {
        for (id in participants) if (id != me) send(id, signal, ring)
    }

    fun onSignal(frame: WsFrame) {
        val (from, signal) = client.openSignal(frame) ?: return
        scope.launch { runCatching { handle(from, signal) } }
    }

    private suspend fun handle(from: String, s: CallSignal) {
        val state = _state.value
        val sameCall = state.callId == s.callId
        when (s.type) {
            CallSignalType.INVITE -> {
                if (state.status != CallStatus.IDLE && !sameCall) return send(from, CallSignal(CallSignalType.BUSY, s.callId))
                if (s.live) {
                    liveInvites[from] = s.callId
                    Graph.toast("@${client.state.value.users[from]?.username} started a live video")
                    return
                }
                participants = s.participants.ifEmpty { listOf(from) }
                _state.value = CallUi(status = CallStatus.INCOMING, callId = s.callId, threadId = s.threadId, video = s.video, from = from)
                startRinging(from, s.video)
                ringTimeout = scope.launch {
                    delay(45_000)
                    if (_state.value.status == CallStatus.INCOMING) decline()
                }
            }
            CallSignalType.ACCEPT -> {
                if (!sameCall) return
                val live = state.live
                if (live?.host == true && state.peers[from]?.state.let { it == null || it == PeerState.LEFT }) {
                    val watching = state.peers.values.count { it.state != PeerState.LEFT && it.state != PeerState.DECLINED }
                    if (watching >= MAX_LIVE_VIEWERS) return send(from, CallSignal(CallSignalType.BUSY, s.callId))
                }
                stopRinging()
                if (state.status == CallStatus.OUTGOING) _state.update { it.copy(status = CallStatus.ACTIVE, startedAt = System.currentTimeMillis()) }
                setPeer(from, PeerState.CONNECTING)
                if (from !in participants) participants = participants + from
                if ((me ?: "") < from || live?.host == true) offerTo(from) else send(from, CallSignal(CallSignalType.JOIN, s.callId))
            }
            CallSignalType.JOIN -> if (sameCall) offerTo(from)
            CallSignalType.DECLINE, CallSignalType.BUSY -> {
                if (!sameCall) return
                if (state.live?.host == false && from == state.live.hostId) {
                    Graph.toast("This live video is full ($MAX_LIVE_VIEWERS viewers)")
                    return reset()
                }
                setPeer(from, PeerState.DECLINED)
                if (s.type == CallSignalType.BUSY) Graph.toast("@${client.state.value.users[from]?.username} is on another call")
                maybeEnd()
            }
            CallSignalType.OFFER -> if (sameCall && s.sdp != null) answerOffer(from, s.sdp!!)
            CallSignalType.ANSWER -> {
                val pc = pcs[from] ?: return
                if (!sameCall || s.sdp == null) return
                pc.setRemote(SessionDescription(SessionDescription.Type.ANSWER, s.sdp))
                flushIce(from)
            }
            CallSignalType.ICE -> {
                if (!sameCall || s.candidate == null) return
                val candidate = IceCandidate(s.sdpMid ?: "", s.sdpMLineIndex ?: 0, s.candidate)
                val pc = pcs[from]
                if (pc?.remoteDescription != null) pc.addIceCandidate(candidate) else pendingIce.getOrPut(from) { ArrayList() } += candidate
            }
            CallSignalType.HANGUP -> {
                if (!sameCall) return
                if (state.status == CallStatus.INCOMING) {
                    stopRinging()
                    return reset()
                }
                closePeer(from)
                if (state.live?.host == false && from == state.live.hostId) {
                    Graph.toast("The live video ended")
                    return reset()
                }
                maybeEnd()
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
        _state.update { it.copy(local = videoTrack, cameraOff = videoTrack == null) }
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

    /** 720p for two people, 360p up to four, 240p beyond: a mesh sends one stream per peer. */
    private fun adapt() {
        val count = pcs.size + 1
        val (width, height, bitrate) = when {
            count <= 2 -> Triple(1280, 720, 1_800_000)
            count <= 4 -> Triple(640, 360, 600_000)
            else -> Triple(426, 240, 300_000)
        }
        runCatching { capturer?.changeCaptureFormat(width, height, 30) }
        for (pc in pcs.values) {
            for (sender in pc.senders) {
                if (sender.track()?.kind() != MediaStreamTrack.VIDEO_TRACK_KIND) continue
                val params = sender.parameters
                params.encodings.firstOrNull()?.maxBitrateBps = bitrate
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
            _state.update { s -> s.copy(peers = s.peers + (userId to PeerUi(userId, PeerState.CONNECTED, track))) }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    connectedOnce = true
                    setPeer(userId, PeerState.CONNECTED)
                }
                PeerConnection.PeerConnectionState.FAILED -> pcs[userId]?.restartIce()
                PeerConnection.PeerConnectionState.CLOSED -> scope.launch { closePeer(userId) }
                else -> Unit
            }
        }
    }

    private suspend fun offerTo(userId: String) {
        val pc = connection(userId)
        val offer = pc.create(offer = true)
        pc.setLocal(offer)
        send(userId, CallSignal(CallSignalType.OFFER, _state.value.callId ?: return, sdp = offer.description))
    }

    private suspend fun answerOffer(userId: String, sdp: String) {
        val pc = connection(userId)
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
        ringTimeout = scope.launch {
            delay(45_000)
            if (_state.value.status == CallStatus.OUTGOING) hangup()
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
        CallService.start(context)
        broadcast(CallSignal(CallSignalType.ACCEPT, state.callId))
    }

    fun decline() = scope.launch {
        val state = _state.value
        stopRinging()
        if (state.callId != null && state.from != null) send(state.from, CallSignal(CallSignalType.DECLINE, state.callId))
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
    }

    fun toggleCamera() = scope.launch {
        val track = videoTrack
        if (track == null) {
            runCatching { startCamera(front = true) }.onFailure { return@launch Graph.toast("Camera unavailable", error = true) }
            val fresh = videoTrack ?: return@launch
            for (pc in pcs.values) pc.addTrack(fresh, listOf("s0"))
            for (id in pcs.keys.toList()) offerTo(id)
            _state.update { it.copy(cameraOff = false, video = true, local = fresh) }
            return@launch
        }
        val off = !_state.value.cameraOff
        track.setEnabled(!off)
        _state.update { it.copy(cameraOff = off) }
    }

    fun flip() = scope.launch { capturer?.switchCamera(null) }

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
        openCallScreen()
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
        for (id in participants) if (id != me && id != from) send(id, s.copy(participants = listOf(from)))
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
        pcs.remove(userId)?.dispose()
        _state.update { s -> s.copy(peers = s.peers + (userId to PeerUi(userId, PeerState.LEFT))) }
        adapt()
    }

    private fun maybeEnd() {
        val state = _state.value
        if (state.live?.host == true) return
        val anyone = state.peers.values.any { it.state == PeerState.CONNECTED || it.state == PeerState.CONNECTING || it.state == PeerState.RINGING }
        if (!anyone) {
            logCall()
            reset()
        }
    }

    private fun reset() {
        stopRinging()
        ringTimeout?.cancel()
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
