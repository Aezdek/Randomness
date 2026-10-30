import { create } from 'zustand';
import { api } from '../../core/api';
import { deleteObject, profileAudience, publish, sendMessage } from '../../core/compose';
import { Kind, MessageType, type CallSignal, type LivePayload } from '../../core/payloads';
import { getState, toast } from '../../core/store';
import type { TurnResponse, WsFrame } from '../../core/types';
import { realtime } from '../../core/ws';
import { openSignal, sealSignal } from '../../crypto/signal';
import { b64, fromUtf8, random, unb64, utf8 } from '../../crypto/sodium';

/**
 * Peer-to-peer calls (a full mesh, up to 10 people) and Live. Media is WebRTC DTLS-SRTP between browsers/phones. The
 * signalling (SDP with the DTLS fingerprints, ICE candidates) is sealed to each recipient and signed by the sender
 * before the server relays it, so neither the server nor a TURN relay can read or tamper with the call.
 */

export interface Peer {
  userId: string;
  state: 'ringing' | 'connecting' | 'connected' | 'left' | 'declined';
  stream: MediaStream | null;
}

export interface LiveComment {
  id: string;
  from: string;
  text: string;
}

interface CallState {
  status: 'idle' | 'incoming' | 'outgoing' | 'active';
  callId: string | null;
  threadId: string | null;
  video: boolean;
  from: string | null;
  peers: Record<string, Peer>;
  local: MediaStream | null;
  muted: boolean;
  cameraOff: boolean;
  startedAt: number | null;
  minimized: boolean;
  live: null | { role: 'host' | 'viewer'; hostId: string; objectId?: string; comments: LiveComment[]; hearts: number };
  relay: boolean;
}

const idle: CallState = {
  status: 'idle',
  callId: null,
  threadId: null,
  video: true,
  from: null,
  peers: {},
  local: null,
  muted: false,
  cameraOff: false,
  startedAt: null,
  minimized: false,
  live: null,
  relay: false,
};

export const useCall = create<CallState>()(() => ({ ...idle }));

class Ringtone {
  private ctx: AudioContext | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;

  start(pattern: 'incoming' | 'outgoing') {
    this.stop();
    try {
      this.ctx = new AudioContext();
    } catch {
      return;
    }
    const beep = () => {
      const ctx = this.ctx!;
      const tones = pattern === 'incoming' ? [880, 1175] : [440];
      tones.forEach((freq, i) => {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'sine';
        osc.frequency.value = freq;
        const t = ctx.currentTime + i * 0.18;
        gain.gain.setValueAtTime(0, t);
        gain.gain.linearRampToValueAtTime(pattern === 'incoming' ? 0.18 : 0.08, t + 0.02);
        gain.gain.exponentialRampToValueAtTime(0.0001, t + (pattern === 'incoming' ? 0.35 : 1.2));
        osc.connect(gain).connect(ctx.destination);
        osc.start(t);
        osc.stop(t + 1.3);
      });
    };
    beep();
    this.timer = setInterval(beep, pattern === 'incoming' ? 1400 : 3000);
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    void this.ctx?.close().catch(() => undefined);
    this.ctx = null;
  }
}

const set = (patch: Partial<CallState> | ((s: CallState) => Partial<CallState>)) => useCall.setState(patch);

class CallManager {
  private pcs = new Map<string, RTCPeerConnection>();
  private pendingIce = new Map<string, RTCIceCandidateInit[]>();
  private ice: TurnResponse | null = null;
  private ring = new Ringtone();
  private participants: string[] = [];
  private ringTimeout: ReturnType<typeof setTimeout> | null = null;
  private connectedOnce = false;

  // ------------------------------------------------------------------------------------------------- signalling

  private send(to: string, signal: CallSignal, ring = false) {
    const { me, keys, users } = getState();
    const user = users[to];
    if (!me || !keys || !user) return;
    const sealed = sealSignal(utf8(JSON.stringify(signal)), me.id, to, Date.now(), keys, unb64(user.boxPk));
    realtime.send({
      t: 'signal',
      to,
      callId: signal.callId,
      ring,
      signal: { sealed: b64(sealed.sealed), signature: b64(sealed.signature), sentAt: sealed.sentAt },
    });
  }

  private broadcast(signal: CallSignal, ring = false) {
    const me = getState().me?.id;
    for (const id of this.participants) if (id !== me) this.send(id, signal, ring);
  }

  onSignal(frame: WsFrame) {
    const { me, keys, users, pins } = getState();
    if (!frame.from || !frame.signal || !me || !keys) return;
    const sender = users[frame.from];
    if (!sender) return;
    const candidates = [sender.signPk, pins[frame.from]?.signPk, ...(pins[frame.from]?.history ?? [])].filter(Boolean) as string[];
    let signal: CallSignal | null = null;
    for (const signPk of new Set(candidates)) {
      try {
        const plain = openSignal(
          { sealed: unb64(frame.signal.sealed), signature: unb64(frame.signal.signature), sentAt: frame.signal.sentAt },
          frame.from,
          me.id,
          unb64(signPk),
          keys,
          Date.now(),
        );
        signal = JSON.parse(fromUtf8(plain));
        break;
      } catch {
        /* try the next known key */
      }
    }
    if (!signal) return console.warn('Dropped a call signal that did not verify');
    void this.handle(frame.from, signal);
  }

  private async handle(from: string, s: CallSignal) {
    const state = useCall.getState();
    const sameCall = state.callId === s.callId;
    switch (s.type) {
      case 'invite':
        if (state.status !== 'idle' && !sameCall) return this.send(from, { type: 'busy', callId: s.callId });
        if (s.live) {
          toast(`${getState().users[from]?.username} started a live video — open Live from their story ring`);
          set({ live: null });
          this.liveInvites.set(from, s.callId);
          return;
        }
        this.participants = s.participants ?? [from];
        set({ status: 'incoming', callId: s.callId, threadId: s.threadId ?? null, video: s.video ?? true, from, peers: {} });
        this.ring.start('incoming');
        this.notify(from, s.video ?? true);
        this.ringTimeout = setTimeout(() => useCall.getState().status === 'incoming' && this.decline(), 45_000);
        return;
      case 'accept':
        if (!sameCall) return;
        this.ring.stop();
        if (state.status === 'outgoing') set({ status: 'active', startedAt: Date.now() });
        set((x) => ({ peers: { ...x.peers, [from]: { userId: from, state: 'connecting', stream: x.peers[from]?.stream ?? null } } }));
        if (!this.participants.includes(from)) this.participants.push(from);
        if ((getState().me?.id ?? '') < from || state.live?.role === 'host') await this.offerTo(from);
        else this.send(from, { type: 'join', callId: s.callId });
        return;
      case 'join':
        if (sameCall) await this.offerTo(from);
        return;
      case 'decline':
      case 'busy':
        if (!sameCall) return;
        set((x) => ({ peers: { ...x.peers, [from]: { userId: from, state: 'declined', stream: null } } }));
        if (s.type === 'busy') toast(`${getState().users[from]?.username} is on another call`);
        this.maybeEnd();
        return;
      case 'offer':
        if (!sameCall || !s.sdp) return;
        await this.answerOffer(from, s.sdp);
        return;
      case 'answer': {
        const pc = this.pcs.get(from);
        if (!sameCall || !pc || !s.sdp) return;
        await pc.setRemoteDescription({ type: 'answer', sdp: s.sdp });
        await this.flushIce(from);
        return;
      }
      case 'ice': {
        if (!sameCall || !s.candidate) return;
        const candidate: RTCIceCandidateInit = { candidate: s.candidate, sdpMid: s.sdpMid ?? undefined, sdpMLineIndex: s.sdpMLineIndex ?? undefined };
        const pc = this.pcs.get(from);
        if (pc?.remoteDescription) await pc.addIceCandidate(candidate).catch(() => undefined);
        else this.pendingIce.set(from, [...(this.pendingIce.get(from) ?? []), candidate]);
        return;
      }
      case 'hangup':
        if (!sameCall) return;
        if (state.status === 'incoming') {
          this.ring.stop();
          return this.reset();
        }
        this.closePeer(from);
        if (state.live?.role === 'viewer' && from === state.live.hostId) {
          toast('The live video ended');
          return this.reset();
        }
        this.maybeEnd();
        return;
      case 'chat':
        if (!sameCall || !state.live || !s.text) return;
        // The host relays viewers' comments; the original author travels in participants[0].
        this.addComment(state.live.role === 'viewer' ? s.participants?.[0] ?? from : from, s.text);
        if (state.live.role === 'host') this.relayLive(from, s);
        return;
      case 'heart':
        if (!sameCall || !state.live) return;
        set((x) => ({ live: x.live && { ...x.live, hearts: x.live.hearts + 1 } }));
        if (state.live.role === 'host') this.relayLive(from, s);
        return;
    }
  }

  private liveInvites = new Map<string, string>();

  private notify(from: string, video: boolean) {
    if (document.visibilityState === 'visible' || Notification?.permission !== 'granted') return;
    void navigator.serviceWorker?.ready.then((r) =>
      r.showNotification(`@${getState().users[from]?.username} is calling`, { body: video ? 'Video call' : 'Audio call', tag: 'call', requireInteraction: true, data: { url: '/direct' } }),
    );
  }

  // ---------------------------------------------------------------------------------------------------- media

  private async iceServers(): Promise<RTCIceServer[]> {
    this.ice ??= await api.get<TurnResponse>('/turn').catch(() => ({ iceServers: [{ urls: ['stun:stun.cloudflare.com:3478'] }], relay: false }));
    set({ relay: this.ice.relay });
    return this.ice.iceServers.map((s) => ({ urls: s.urls, username: s.username, credential: s.credential }));
  }

  private async localMedia(video: boolean): Promise<MediaStream> {
    const existing = useCall.getState().local;
    if (existing) return existing;
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      video: video ? { width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30 }, facingMode: 'user' } : false,
    });
    set({ local: stream, cameraOff: !video });
    return stream;
  }

  /** 720p for two people, 360p up to four, 240p beyond: a mesh sends one stream per peer. */
  private async adapt() {
    const count = this.pcs.size + 1;
    const [height, bitrate] = count <= 2 ? [720, 1_800_000] : count <= 4 ? [360, 600_000] : [240, 300_000];
    const track = useCall.getState().local?.getVideoTracks()[0];
    await track?.applyConstraints({ height: { ideal: height } }).catch(() => undefined);
    for (const pc of this.pcs.values()) {
      for (const sender of pc.getSenders()) {
        if (sender.track?.kind !== 'video') continue;
        const params = sender.getParameters();
        params.encodings = params.encodings?.length ? params.encodings : [{}];
        params.encodings[0].maxBitrate = bitrate;
        await sender.setParameters(params).catch(() => undefined);
      }
    }
  }

  private async connection(userId: string): Promise<RTCPeerConnection> {
    let pc = this.pcs.get(userId);
    if (pc) return pc;
    const state = useCall.getState();
    pc = new RTCPeerConnection({ iceServers: await this.iceServers() });
    this.pcs.set(userId, pc);
    // Live viewers only receive: they answer the host's offer without sending anything.
    const viewer = state.live?.role === 'viewer';
    if (!viewer && state.local) {
      for (const track of state.local.getTracks()) pc.addTrack(track, state.local);
    }
    pc.onicecandidate = (e) => {
      if (!e.candidate) return;
      this.send(userId, { type: 'ice', callId: useCall.getState().callId!, candidate: e.candidate.candidate, sdpMid: e.candidate.sdpMid, sdpMLineIndex: e.candidate.sdpMLineIndex });
    };
    pc.ontrack = (e) => {
      const stream = e.streams[0] ?? new MediaStream([e.track]);
      set((x) => ({ peers: { ...x.peers, [userId]: { userId, state: 'connected', stream } } }));
    };
    pc.onconnectionstatechange = () => {
      const s = pc!.connectionState;
      if (s === 'connected') {
        this.connectedOnce = true;
        set((x) => ({ peers: { ...x.peers, [userId]: { ...(x.peers[userId] ?? { userId, stream: null }), state: 'connected' } } }));
      } else if (s === 'failed') {
        pc!.restartIce();
      } else if (s === 'closed') {
        this.closePeer(userId);
      }
    };
    void this.adapt();
    return pc;
  }

  private async offerTo(userId: string) {
    const pc = await this.connection(userId);
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    this.send(userId, { type: 'offer', callId: useCall.getState().callId!, sdp: offer.sdp });
  }

  private async answerOffer(userId: string, sdp: string) {
    const pc = await this.connection(userId);
    await pc.setRemoteDescription({ type: 'offer', sdp });
    await this.flushIce(userId);
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    this.send(userId, { type: 'answer', callId: useCall.getState().callId!, sdp: answer.sdp });
  }

  private async flushIce(userId: string) {
    const pc = this.pcs.get(userId);
    for (const c of this.pendingIce.get(userId) ?? []) await pc?.addIceCandidate(c).catch(() => undefined);
    this.pendingIce.delete(userId);
  }

  // ---------------------------------------------------------------------------------------------------- actions

  async start(threadId: string, video: boolean) {
    const { threads, me } = getState();
    const thread = threads[threadId];
    if (!thread || !me) return;
    if (useCall.getState().status !== 'idle') return toast('You’re already in a call');
    try {
      await this.localMedia(video);
    } catch {
      return toast(video ? 'Camera or microphone unavailable' : 'Microphone unavailable', 'error');
    }
    const callId = b64(random(12));
    this.participants = thread.members.map((m) => m.userId);
    this.connectedOnce = false;
    const peers = Object.fromEntries(this.participants.filter((id) => id !== me.id).map((id) => [id, { userId: id, state: 'ringing' as const, stream: null }]));
    set({ status: 'outgoing', callId, threadId, video, peers, startedAt: null, minimized: false });
    this.ring.start('outgoing');
    this.broadcast({ type: 'invite', callId, threadId, video, participants: this.participants }, true);
    this.ringTimeout = setTimeout(() => useCall.getState().status === 'outgoing' && this.hangup(), 45_000);
  }

  async accept() {
    const state = useCall.getState();
    if (state.status !== 'incoming' || !state.callId) return;
    this.ring.stop();
    if (this.ringTimeout) clearTimeout(this.ringTimeout);
    try {
      await this.localMedia(state.video);
    } catch {
      toast('Camera or microphone unavailable', 'error');
      return this.decline();
    }
    this.connectedOnce = false;
    const me = getState().me!.id;
    const peers = Object.fromEntries(this.participants.filter((id) => id !== me).map((id) => [id, { userId: id, state: 'connecting' as const, stream: null }]));
    set({ status: 'active', startedAt: Date.now(), peers });
    this.broadcast({ type: 'accept', callId: state.callId });
  }

  decline() {
    const state = useCall.getState();
    this.ring.stop();
    if (state.callId && state.from) this.send(state.from, { type: 'decline', callId: state.callId });
    this.reset();
  }

  hangup() {
    const state = useCall.getState();
    if (state.callId) this.broadcast({ type: 'hangup', callId: state.callId });
    void this.logCall();
    if (state.live?.role === 'host' && state.live.objectId) void deleteObject(state.live.objectId).catch(() => undefined);
    this.reset();
  }

  /** The caller writes the call into the chat history (encrypted like any message). */
  private async logCall() {
    const state = useCall.getState();
    const me = getState().me?.id;
    if (!state.threadId || !state.callId || state.live || state.from) return;
    const durationMs = state.startedAt && this.connectedOnce ? Date.now() - state.startedAt : 0;
    await sendMessage(state.threadId, {
      type: MessageType.CALL,
      call: { callId: state.callId, video: state.video, durationMs, missed: !this.connectedOnce, participants: this.participants.filter((id) => id !== me) },
    }).catch(() => undefined);
  }

  toggleMute() {
    const muted = !useCall.getState().muted;
    useCall.getState().local?.getAudioTracks().forEach((t) => (t.enabled = !muted));
    set({ muted });
  }

  async toggleCamera() {
    const state = useCall.getState();
    const local = state.local;
    if (!local) return;
    let track = local.getVideoTracks()[0];
    if (!track) {
      try {
        const extra = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 1280 }, height: { ideal: 720 } } });
        track = extra.getVideoTracks()[0];
        local.addTrack(track);
        for (const pc of this.pcs.values()) pc.addTrack(track, local);
        for (const [userId] of this.pcs) await this.offerTo(userId);
        set({ cameraOff: false, video: true, local: new MediaStream(local.getTracks()) });
      } catch {
        toast('Camera unavailable', 'error');
      }
      return;
    }
    track.enabled = state.cameraOff;
    set({ cameraOff: !state.cameraOff });
  }

  async flip() {
    const local = useCall.getState().local;
    const old = local?.getVideoTracks()[0];
    if (!local || !old) return;
    const facing = old.getSettings().facingMode === 'environment' ? 'user' : 'environment';
    try {
      const fresh = (await navigator.mediaDevices.getUserMedia({ video: { facingMode: facing } })).getVideoTracks()[0];
      for (const pc of this.pcs.values()) for (const sender of pc.getSenders()) if (sender.track === old) await sender.replaceTrack(fresh);
      local.removeTrack(old);
      old.stop();
      local.addTrack(fresh);
      set({ local: new MediaStream(local.getTracks()) });
    } catch {
      toast('Could not switch camera', 'error');
    }
  }

  testRing() {
    this.ring.start('incoming');
    toast('🔔 Test ring — notifications reach this device', 'success');
    setTimeout(() => this.ring.stop(), 3500);
  }

  // ------------------------------------------------------------------------------------------------------- live

  /** Live: broadcast to followers. Viewers join peer to peer (up to 9), chat and hearts relay through the host. */
  async startLive() {
    if (useCall.getState().status !== 'idle') return toast('You’re already in a call');
    try {
      await this.localMedia(true);
    } catch {
      return toast('Camera unavailable', 'error');
    }
    const me = getState().me!;
    const callId = b64(random(12));
    const entity = await publish<LivePayload>({
      kind: Kind.LIVE,
      audience: profileAudience(),
      payload: { startedAt: Date.now(), callId },
      expiresAt: Date.now() + 6 * 60 * 60 * 1000,
    }).catch((e) => {
      toast(e.message, 'error');
      return null;
    });
    if (!entity) return this.reset();
    this.participants = [me.id];
    set({ status: 'active', callId, video: true, startedAt: Date.now(), live: { role: 'host', hostId: me.id, objectId: entity.dto.id, comments: [], hearts: 0 } });
    const followers = Object.values(getState().users).filter((u) => u.followsMe === 'following');
    for (const u of followers) this.send(u.id, { type: 'invite', callId, live: true, participants: [me.id] });
  }

  async joinLive(hostId: string, callIdFromObject?: string) {
    if (useCall.getState().status !== 'idle') return toast('You’re already in a call');
    const callId = callIdFromObject ?? this.liveInvites.get(hostId);
    if (!callId) return toast('That live video has ended');
    this.participants = [hostId];
    set({ status: 'active', callId, video: true, startedAt: Date.now(), live: { role: 'viewer', hostId, comments: [], hearts: 0 }, peers: { [hostId]: { userId: hostId, state: 'connecting', stream: null } } });
    this.send(hostId, { type: 'accept', callId });
  }

  sendLive(kind: 'chat' | 'heart', text?: string) {
    const state = useCall.getState();
    if (!state.live || !state.callId) return;
    const signal: CallSignal = { type: kind, callId: state.callId, text };
    if (kind === 'chat' && text) this.addComment(getState().me!.id, text);
    if (kind === 'heart') set((x) => ({ live: x.live && { ...x.live, hearts: x.live.hearts + 1 } }));
    if (state.live.role === 'host') this.broadcast(signal);
    else this.send(state.live.hostId, signal);
  }

  private relayLive(from: string, s: CallSignal) {
    const me = getState().me!.id;
    for (const id of this.participants) if (id !== me && id !== from) this.send(id, { ...s, participants: [from] });
  }

  private addComment(from: string, text: string) {
    set((x) => ({ live: x.live && { ...x.live, comments: [...x.live.comments.slice(-40), { id: crypto.randomUUID(), from, text }] } }));
  }

  // ---------------------------------------------------------------------------------------------------- cleanup

  private closePeer(userId: string) {
    this.pcs.get(userId)?.close();
    this.pcs.delete(userId);
    set((x) => ({ peers: { ...x.peers, [userId]: { userId, state: 'left', stream: null } } }));
    void this.adapt();
  }

  private maybeEnd() {
    const state = useCall.getState();
    if (state.live?.role === 'host') return;
    const live = Object.values(state.peers).some((p) => p.state === 'connected' || p.state === 'connecting' || p.state === 'ringing');
    if (!live) {
      void this.logCall();
      this.reset();
    }
  }

  private reset() {
    this.ring.stop();
    if (this.ringTimeout) clearTimeout(this.ringTimeout);
    for (const pc of this.pcs.values()) pc.close();
    this.pcs.clear();
    this.pendingIce.clear();
    useCall.getState().local?.getTracks().forEach((t) => t.stop());
    this.participants = [];
    set({ ...idle });
  }
}

export const calls = new CallManager();
