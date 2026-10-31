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
 *
 * Mirrored by android/.../calls/CallManager.kt: same signals, same rules.
 * - Someone signed in on two devices gets every signal on both. Signals carry the sending device, and once a device
 *   answers, everything for that call names it (toDevice): their other devices stop ringing and stay out of it.
 * - The lower user id makes the offers (the Live host always does); a renegotiation from the other side (turning the
 *   camera on in an audio call) is resolved by "perfect negotiation": the lower id wins a collision, the other yields.
 * - A dropped connection is restarted (ICE restart) by the offering side; one that stays down is given up after a while,
 *   so a call never sits frozen, and nobody stays "on a call" that has ended.
 */

export interface Peer {
  userId: string;
  state: 'ringing' | 'connecting' | 'connected' | 'reconnecting' | 'left' | 'declined';
  stream: MediaStream | null;
  /** What they told us: camera and microphone on. Null until they say (older apps never do). */
  camera: boolean | null;
  mic: boolean | null;
  /** Bumped when their tracks change, so the tiles re-render. */
  rev: number;
}

/** The host uploads one stream per viewer, so Live stays small: the host plus 9 is the same mesh size as a call. */
const MAX_LIVE_VIEWERS = 9;
const RING_MS = 45_000;
/** Accepted but never connected: give up on that person. */
const CONNECT_TIMEOUT_MS = 40_000;
/** A connection that drops is restarted after this, and given up if it isn't back within LOST_AFTER_MS. */
const RECONNECT_AFTER_MS = 4_000;
const LOST_AFTER_MS = 30_000;

/** This tab, for this page load. (The browser's generator: libsodium isn't loaded yet when this module is.) */
const DEVICE = Array.from(crypto.getRandomValues(new Uint8Array(9)), (b) => b.toString(16).padStart(2, '0')).join('');

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
  /** Which way the camera faces, where the device says (phones); null on most computers. */
  facing: 'user' | 'environment' | null;
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
  facing: null,
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

function peerOf(s: CallState, userId: string): Peer {
  return s.peers[userId] ?? { userId, state: 'connecting', stream: null, camera: null, mic: null, rev: 0 };
}

function setPeer(userId: string, patch: Partial<Peer>) {
  set((s) => ({ peers: { ...s.peers, [userId]: { ...peerOf(s, userId), ...patch } } }));
}

/** 16:9 at 720p from whichever camera is asked for. Asking for the shape as well as the size keeps phones from
 *  switching to their full-sensor 4:3 mode, which looks zoomed out. */
function cameraConstraints(extra: MediaTrackConstraints = {}): MediaTrackConstraints {
  return { width: { ideal: 1280 }, height: { ideal: 720 }, aspectRatio: { ideal: 16 / 9 }, frameRate: { ideal: 30, max: 30 }, ...extra };
}

class CallManager {
  private pcs = new Map<string, RTCPeerConnection>();
  private pendingIce = new Map<string, RTCIceCandidateInit[]>();
  /** The device each person is in this call on, once they answered. */
  private peerDevice = new Map<string, string>();
  private remote = new Map<string, MediaStream>();
  private makingOffer = new Set<string>();
  private watchdogs = new Map<string, ReturnType<typeof setTimeout>>();
  private restarts = new Map<string, ReturnType<typeof setTimeout>>();
  private ice: TurnResponse | null = null;
  private ring = new Ringtone();
  private participants: string[] = [];
  private ringTimeout: ReturnType<typeof setTimeout> | null = null;
  private connectedOnce = false;
  private liveInvites = new Map<string, string>();

  constructor() {
    // Closing or reloading the tab mid-call hangs up properly, so the others don't wait for a timeout.
    if (typeof window !== 'undefined') {
      window.addEventListener('pagehide', () => {
        const state = useCall.getState();
        if (state.callId && (state.status === 'active' || state.status === 'outgoing')) this.broadcast({ type: 'hangup', callId: state.callId });
      });
    }
  }

  // ------------------------------------------------------------------------------------------------- signalling

  private send(to: string, signal: CallSignal, ring = false) {
    const { me, keys, users } = getState();
    if (!me || !keys) return;
    const self = to === me.id;
    const boxPk = self ? keys.boxPk : users[to] ? unb64(users[to].boxPk) : null;
    if (!boxPk) return;
    const full: CallSignal = {
      ...signal,
      device: DEVICE,
      toDevice: self ? null : signal.toDevice !== undefined ? signal.toDevice : (this.peerDevice.get(to) ?? null),
    };
    const sealed = sealSignal(utf8(JSON.stringify(full)), me.id, to, Date.now(), keys, boxPk);
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

  /** Tells my other devices (they get signals to my own id) that this call was answered or declined here. */
  private tellMyDevices(type: 'accept' | 'decline', callId: string) {
    const me = getState().me?.id;
    if (me) this.send(me, { type, callId });
  }

  onSignal(frame: WsFrame) {
    const { me, keys, users, pins } = getState();
    if (!frame.from || !frame.signal || !me || !keys) return;
    const known: Uint8Array[] = [];
    if (frame.from === me.id) known.push(keys.signPk);
    const sender = users[frame.from];
    if (!sender && frame.from !== me.id) return;
    for (const pk of [sender?.signPk, pins[frame.from]?.signPk, ...(pins[frame.from]?.history ?? [])]) if (pk) known.push(unb64(pk));
    let signal: CallSignal | null = null;
    for (const signPk of known) {
      try {
        const plain = openSignal(
          { sealed: unb64(frame.signal.sealed), signature: unb64(frame.signal.signature), sentAt: frame.signal.sentAt },
          frame.from,
          me.id,
          signPk,
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
    void this.handle(frame.from, signal).catch((e) => console.warn('Call signal failed', e));
  }

  private async handle(from: string, s: CallSignal) {
    const state = useCall.getState();
    const me = getState().me!.id;
    const sameCall = state.callId === s.callId;
    if (s.device === DEVICE) return;
    if (from === me) {
      // Another of my devices answered or declined: this one stops ringing.
      if (sameCall && state.status === 'incoming') this.reset();
      return;
    }
    if (s.toDevice && s.toDevice !== DEVICE) {
      // Meant for another of my devices, so that one answered this call.
      if (sameCall && state.status === 'incoming') this.reset();
      return;
    }
    switch (s.type) {
      case 'invite': {
        if (s.live) {
          toast(`${getState().users[from]?.username} started a live video — open Live from their story ring`);
          this.liveInvites.set(from, s.callId);
          return;
        }
        if (sameCall) return;
        if (state.status !== 'idle') {
          // Busy only when really on a call; ringing for someone else isn't.
          if (state.status === 'active' || state.status === 'outgoing') this.send(from, { type: 'busy', callId: s.callId, toDevice: s.device ?? null });
          return;
        }
        this.peerDevice.clear();
        if (s.device) this.peerDevice.set(from, s.device);
        this.participants = s.participants?.length ? s.participants : [from];
        set({ status: 'incoming', callId: s.callId, threadId: s.threadId ?? null, video: s.video ?? true, from, peers: {} });
        this.ring.start('incoming');
        this.notify(from, s.video ?? true);
        // Unanswered: stop ringing. The caller gives up on its own and logs the missed call.
        this.ringTimeout = setTimeout(() => useCall.getState().status === 'incoming' && useCall.getState().callId === s.callId && this.reset(), RING_MS);
        return;
      }
      case 'accept': {
        if (!sameCall || state.status === 'idle') return;
        if (state.status === 'incoming') {
          // Someone else in a group call answered while this device still rings.
          if (s.device) this.peerDevice.set(from, s.device);
          return;
        }
        const knownDevice = this.peerDevice.get(from);
        if (knownDevice && s.device && knownDevice !== s.device && this.pcs.has(from)) {
          // They answered on a second device as well: that one stands down.
          return this.send(from, { type: 'hangup', callId: s.callId, toDevice: s.device });
        }
        if (s.device) this.peerDevice.set(from, s.device);
        if (state.live?.role === 'host' && !(from in state.peers && state.peers[from].state !== 'left')) {
          const watching = Object.values(state.peers).filter((p) => p.state !== 'left' && p.state !== 'declined').length;
          if (watching >= MAX_LIVE_VIEWERS) return this.send(from, { type: 'busy', callId: s.callId });
        }
        this.ring.stop();
        if (state.status === 'outgoing') set({ status: 'active', startedAt: Date.now() });
        setPeer(from, { state: 'connecting' });
        this.watch(from, CONNECT_TIMEOUT_MS);
        if (!this.participants.includes(from)) this.participants.push(from);
        if (this.offers(from)) await this.offerTo(from);
        else this.send(from, { type: 'join', callId: s.callId });
        return;
      }
      case 'join':
        if (sameCall && state.status === 'active') {
          if (s.device && !this.peerDevice.has(from)) this.peerDevice.set(from, s.device);
          await this.offerTo(from);
        }
        return;
      case 'decline':
      case 'busy': {
        if (!sameCall) return;
        if (state.live?.role === 'viewer' && from === state.live.hostId) {
          toast(`This live video is full (${MAX_LIVE_VIEWERS} viewers)`);
          return this.reset();
        }
        // Only someone still ringing can decline; a stray decline from another of their devices changes nothing.
        if (state.peers[from]?.state !== 'ringing') return;
        setPeer(from, { state: 'declined', stream: null });
        if (s.type === 'busy') toast(`${getState().users[from]?.username} is on another call`);
        this.maybeEnd();
        return;
      }
      case 'offer':
        if (!sameCall || !s.sdp || state.status !== 'active') return;
        if (s.device && !this.peerDevice.has(from)) this.peerDevice.set(from, s.device);
        await this.answerOffer(from, s.sdp);
        return;
      case 'answer': {
        const pc = this.pcs.get(from);
        if (!sameCall || !pc || !s.sdp || pc.signalingState !== 'have-local-offer') return;
        await pc.setRemoteDescription({ type: 'answer', sdp: s.sdp });
        await this.flushIce(from);
        return;
      }
      case 'ice': {
        if (!sameCall || !s.candidate || state.status !== 'active') return;
        const candidate: RTCIceCandidateInit = { candidate: s.candidate, sdpMid: s.sdpMid ?? undefined, sdpMLineIndex: s.sdpMLineIndex ?? undefined };
        const pc = this.pcs.get(from);
        if (pc?.remoteDescription) await pc.addIceCandidate(candidate).catch(() => undefined);
        else this.pendingIce.set(from, [...(this.pendingIce.get(from) ?? []), candidate]);
        return;
      }
      case 'hangup':
        if (!sameCall) return;
        if (state.status === 'incoming') return this.reset();
        this.closePeer(from);
        if (state.live?.role === 'viewer' && from === state.live.hostId) {
          toast('The live video ended');
          return this.reset();
        }
        this.maybeEnd();
        return;
      case 'state':
        if (!sameCall) return;
        setPeer(from, { camera: s.camera ?? null, mic: s.mic ?? null });
        return;
      case 'chat':
        if (!sameCall || !state.live || !s.text) return;
        // The host relays viewers' comments; the original author travels in participants[0].
        this.addComment(state.live.role === 'viewer' ? (s.participants?.[0] ?? from) : from, s.text);
        if (state.live.role === 'host') this.relayLive(from, s);
        return;
      case 'heart':
        if (!sameCall || !state.live) return;
        set((x) => ({ live: x.live && { ...x.live, hearts: x.live.hearts + 1 } }));
        if (state.live.role === 'host') this.relayLive(from, s);
        return;
    }
  }

  /** Whether this side makes the offers to [userId]: the lower id does, and a Live host always does. */
  private offers(userId: string) {
    const live = useCall.getState().live;
    if (live) return live.role === 'host';
    return (getState().me?.id ?? '') < userId;
  }

  private notify(from: string, video: boolean) {
    if (document.visibilityState === 'visible' || typeof Notification === 'undefined' || Notification.permission !== 'granted') return;
    void navigator.serviceWorker?.ready.then((r) =>
      r.showNotification(`@${getState().users[from]?.username} is calling`, { body: video ? 'Video call' : 'Audio call', tag: 'call', requireInteraction: true, data: { url: '/direct' } }),
    );
  }

  /** Takes down the "is calling" notification once the call stops ringing, whichever way. */
  private clearNotification() {
    void navigator.serviceWorker?.ready
      .then((r) => r.getNotifications())
      .then((all) => all.filter((n) => n.tag === 'call' || n.tag.startsWith('call-')).forEach((n) => n.close()))
      .catch(() => undefined);
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
      video: video ? cameraConstraints({ facingMode: 'user' }) : false,
    });
    const facing = stream.getVideoTracks()[0]?.getSettings().facingMode;
    set({ local: stream, cameraOff: !video, facing: facing === 'environment' ? 'environment' : facing === 'user' ? 'user' : null });
    return stream;
  }

  /**
   * Two people get 720p at up to 2.5 Mbps; a bigger mesh sends one stream per person, so each gets less. Scaling is done
   * in the encoder: the camera keeps its 720p 16:9 setup (changing that mid-call made some phones zoom out and stall).
   */
  private async adapt() {
    const count = this.pcs.size + 1;
    const [scale, bitrate] = count <= 2 ? [1, 2_500_000] : count <= 4 ? [2, 800_000] : [3, 400_000];
    for (const pc of this.pcs.values()) {
      for (const sender of pc.getSenders()) {
        if (sender.track?.kind !== 'video') continue;
        const params = sender.getParameters();
        if (!params.encodings?.length) continue;
        params.encodings[0].maxBitrate = bitrate;
        params.encodings[0].scaleResolutionDownBy = scale;
        await sender.setParameters(params).catch(() => undefined);
      }
    }
  }

  private async connection(userId: string): Promise<RTCPeerConnection> {
    let pc = this.pcs.get(userId);
    if (pc) return pc;
    const state = useCall.getState();
    pc = new RTCPeerConnection({ iceServers: await this.iceServers() });
    // Another await above may have made one meanwhile.
    const raced = this.pcs.get(userId);
    if (raced) {
      pc.close();
      return raced;
    }
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
      // Audio and video can arrive as separate streams (the Android app's, for one): collect both into one.
      const stream = this.remote.get(userId) ?? new MediaStream();
      if (!stream.getTracks().includes(e.track)) stream.addTrack(e.track);
      this.remote.set(userId, stream);
      const bump = () => set((x) => ({ peers: { ...x.peers, [userId]: { ...peerOf(x, userId), stream, rev: peerOf(x, userId).rev + 1 } } }));
      e.track.addEventListener('mute', bump);
      e.track.addEventListener('unmute', bump);
      e.track.addEventListener('ended', bump);
      bump();
    };
    pc.onconnectionstatechange = () => this.connectionChanged(userId, pc!);
    void this.adapt();
    return pc;
  }

  private connectionChanged(userId: string, pc: RTCPeerConnection) {
    if (this.pcs.get(userId) !== pc) return;
    switch (pc.connectionState) {
      case 'connected':
        this.connectedOnce = true;
        this.unwatch(userId);
        setPeer(userId, { state: 'connected' });
        this.sendState(userId);
        void this.adapt();
        return;
      case 'disconnected':
        // Often a network change that heals by itself; if not, restart ICE.
        setPeer(userId, { state: 'reconnecting' });
        this.later(userId, RECONNECT_AFTER_MS, () => void this.restart(userId));
        this.watch(userId, LOST_AFTER_MS);
        return;
      case 'failed':
        setPeer(userId, { state: 'reconnecting' });
        void this.restart(userId);
        this.watch(userId, LOST_AFTER_MS);
        return;
      case 'closed':
        this.closePeer(userId);
        return;
    }
  }

  /** ICE restart, from the side that makes offers (the other side's restart arrives as an offer). */
  private async restart(userId: string) {
    const pc = this.pcs.get(userId);
    if (!pc || pc.connectionState === 'connected' || !this.offers(userId)) return;
    await this.offerTo(userId, true).catch(() => undefined);
  }

  /** Gives up on someone who isn't connected by the time this fires. */
  private watch(userId: string, ms: number) {
    this.unwatch(userId);
    this.watchdogs.set(
      userId,
      setTimeout(() => {
        this.watchdogs.delete(userId);
        const pc = this.pcs.get(userId);
        if (pc?.connectionState === 'connected') return;
        const state = useCall.getState();
        if (!state.callId) return;
        toast(`Lost the connection to ${getState().users[userId]?.username ?? 'them'}`, 'error');
        this.send(userId, { type: 'hangup', callId: state.callId });
        this.closePeer(userId);
        this.maybeEnd();
      }, ms),
    );
  }

  private unwatch(userId: string) {
    const t = this.watchdogs.get(userId);
    if (t) clearTimeout(t);
    this.watchdogs.delete(userId);
    const r = this.restarts.get(userId);
    if (r) clearTimeout(r);
    this.restarts.delete(userId);
  }

  private later(userId: string, ms: number, run: () => void) {
    const r = this.restarts.get(userId);
    if (r) clearTimeout(r);
    this.restarts.set(userId, setTimeout(run, ms));
  }

  private async offerTo(userId: string, iceRestart = false) {
    const pc = await this.connection(userId);
    if (pc.signalingState !== 'stable') return;
    this.makingOffer.add(userId);
    try {
      const offer = await pc.createOffer({ iceRestart });
      if (pc.signalingState !== 'stable') return;
      await pc.setLocalDescription(offer);
      this.send(userId, { type: 'offer', callId: useCall.getState().callId!, sdp: offer.sdp });
    } finally {
      this.makingOffer.delete(userId);
    }
  }

  private async answerOffer(userId: string, sdp: string) {
    const pc = await this.connection(userId);
    const collision = this.makingOffer.has(userId) || pc.signalingState !== 'stable';
    if (collision) {
      // Both sides offered at once: the offering side's wins, this side drops its own and answers.
      if (this.offers(userId)) return;
      await pc.setLocalDescription({ type: 'rollback' }).catch(() => undefined);
    }
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

  /** Tells one person, or everyone connected, whether my camera and microphone are on. */
  private sendState(to?: string) {
    const state = useCall.getState();
    if (!state.callId || state.live?.role === 'viewer') return;
    const camera = !state.cameraOff && !!state.local?.getVideoTracks().some((t) => t.readyState === 'live');
    const signal: CallSignal = { type: 'state', callId: state.callId, camera, mic: !state.muted };
    if (to) this.send(to, signal);
    else for (const id of this.pcs.keys()) this.send(id, signal);
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
    this.peerDevice.clear();
    const peers = Object.fromEntries(
      this.participants.filter((id) => id !== me.id).map((id) => [id, { userId: id, state: 'ringing' as const, stream: null, camera: null, mic: null, rev: 0 }]),
    );
    set({ status: 'outgoing', callId, threadId, video, peers, startedAt: null, minimized: false });
    this.ring.start('outgoing');
    this.broadcast({ type: 'invite', callId, threadId, video, participants: this.participants }, true);
    this.ringTimeout = setTimeout(() => this.ringingOver(callId), RING_MS);
  }

  /** Nobody answered in time: hang up. In a call that's under way, stop ringing whoever never answered. */
  private ringingOver(callId: string) {
    const state = useCall.getState();
    if (state.callId !== callId) return;
    if (state.status === 'outgoing') return this.hangup();
    for (const peer of Object.values(state.peers)) {
      if (peer.state !== 'ringing') continue;
      this.send(peer.userId, { type: 'hangup', callId });
      setPeer(peer.userId, { state: 'declined' });
    }
  }

  async accept() {
    const state = useCall.getState();
    if (state.status !== 'incoming' || !state.callId) return;
    this.ring.stop();
    this.clearNotification();
    if (this.ringTimeout) clearTimeout(this.ringTimeout);
    try {
      await this.localMedia(state.video);
    } catch {
      toast('Camera or microphone unavailable', 'error');
      return this.decline();
    }
    this.connectedOnce = false;
    const me = getState().me!.id;
    const peers = Object.fromEntries(
      this.participants.filter((id) => id !== me).map((id) => [id, { userId: id, state: 'connecting' as const, stream: null, camera: null, mic: null, rev: 0 }]),
    );
    set({ status: 'active', startedAt: Date.now(), peers });
    for (const id of Object.keys(peers)) this.watch(id, CONNECT_TIMEOUT_MS);
    this.tellMyDevices('accept', state.callId);
    this.broadcast({ type: 'accept', callId: state.callId });
  }

  decline() {
    const state = useCall.getState();
    if (state.callId && state.from) {
      this.send(state.from, { type: 'decline', callId: state.callId });
      this.tellMyDevices('decline', state.callId);
    }
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
    this.sendState();
  }

  async toggleCamera() {
    const state = useCall.getState();
    const local = state.local;
    if (!local) return;
    const track = local.getVideoTracks()[0];
    if (!track) {
      // An audio call turning video on: add a camera track and renegotiate with everyone.
      try {
        const extra = await navigator.mediaDevices.getUserMedia({ video: cameraConstraints({ facingMode: 'user' }) });
        const fresh = extra.getVideoTracks()[0];
        local.addTrack(fresh);
        for (const pc of this.pcs.values()) pc.addTrack(fresh, local);
        set({ cameraOff: false, video: true, local: new MediaStream(local.getTracks()), facing: fresh.getSettings().facingMode === 'environment' ? 'environment' : 'user' });
        for (const userId of this.pcs.keys()) await this.offerTo(userId);
        void this.adapt();
        this.sendState();
      } catch {
        toast('Camera unavailable', 'error');
      }
      return;
    }
    track.enabled = state.cameraOff;
    set({ cameraOff: !state.cameraOff });
    this.sendState();
  }

  /** The cameras this device has, for the camera menu. */
  async cameras(): Promise<MediaDeviceInfo[]> {
    return (await navigator.mediaDevices.enumerateDevices().catch(() => [])).filter((d) => d.kind === 'videoinput');
  }

  /** Switches to the other camera: front and back on a phone, the next camera on a computer, or the one asked for. */
  async switchCamera(deviceId?: string) {
    const local = useCall.getState().local;
    const old = local?.getVideoTracks()[0];
    if (!local || !old) return;
    const settings = old.getSettings();
    let constraints: MediaTrackConstraints;
    if (deviceId) constraints = cameraConstraints({ deviceId: { exact: deviceId } });
    else if (settings.facingMode) constraints = cameraConstraints({ facingMode: settings.facingMode === 'environment' ? 'user' : 'environment' });
    else {
      const all = await this.cameras();
      if (all.length < 2) return toast('This device has one camera');
      const next = all[(all.findIndex((d) => d.deviceId === settings.deviceId) + 1) % all.length];
      constraints = cameraConstraints({ deviceId: { exact: next.deviceId } });
    }
    const open = () => navigator.mediaDevices.getUserMedia({ video: constraints }).then((s) => s.getVideoTracks()[0]);
    let fresh: MediaStreamTrack;
    try {
      fresh = await open();
    } catch {
      // Many phones can't run two cameras at once: let go of the current one and try again.
      old.stop();
      try {
        fresh = await open();
      } catch {
        toast('Could not switch camera', 'error');
        return;
      }
    }
    fresh.enabled = !useCall.getState().cameraOff;
    for (const pc of this.pcs.values()) for (const sender of pc.getSenders()) if (sender.track === old || (sender.track === null && old.readyState === 'ended')) await sender.replaceTrack(fresh);
    local.removeTrack(old);
    old.stop();
    local.addTrack(fresh);
    const facing = fresh.getSettings().facingMode;
    set({ local: new MediaStream(local.getTracks()), facing: facing === 'environment' ? 'environment' : facing === 'user' ? 'user' : null });
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
    this.peerDevice.clear();
    set({ status: 'active', callId, video: true, startedAt: Date.now(), live: { role: 'host', hostId: me.id, objectId: entity.dto.id, comments: [], hearts: 0 } });
    const followers = Object.values(getState().users).filter((u) => u.followsMe === 'following');
    for (const u of followers) this.send(u.id, { type: 'invite', callId, live: true, participants: [me.id] });
  }

  async joinLive(hostId: string, callIdFromObject?: string) {
    if (useCall.getState().status !== 'idle') return toast('You’re already in a call');
    const callId = callIdFromObject ?? this.liveInvites.get(hostId);
    if (!callId) return toast('That live video has ended');
    this.participants = [hostId];
    this.peerDevice.clear();
    set({
      status: 'active',
      callId,
      video: true,
      startedAt: Date.now(),
      live: { role: 'viewer', hostId, comments: [], hearts: 0 },
      peers: { [hostId]: { userId: hostId, state: 'connecting', stream: null, camera: null, mic: null, rev: 0 } },
    });
    this.watch(hostId, CONNECT_TIMEOUT_MS);
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
    for (const id of this.participants) if (id !== me && id !== from) this.send(id, { ...s, participants: [from], toDevice: undefined });
  }

  private addComment(from: string, text: string) {
    set((x) => ({ live: x.live && { ...x.live, comments: [...x.live.comments.slice(-40), { id: crypto.randomUUID(), from, text }] } }));
  }

  // ---------------------------------------------------------------------------------------------------- cleanup

  private closePeer(userId: string) {
    this.unwatch(userId);
    this.pcs.get(userId)?.close();
    this.pcs.delete(userId);
    this.remote.delete(userId);
    this.pendingIce.delete(userId);
    setPeer(userId, { state: 'left', stream: null });
    void this.adapt();
  }

  private maybeEnd() {
    const state = useCall.getState();
    if (state.status === 'idle' || state.live?.role === 'host') return;
    const live = Object.values(state.peers).some((p) => p.state !== 'left' && p.state !== 'declined');
    if (!live) {
      if (state.callId) this.broadcast({ type: 'hangup', callId: state.callId });
      void this.logCall();
      this.reset();
    }
  }

  private reset() {
    this.ring.stop();
    this.clearNotification();
    if (this.ringTimeout) clearTimeout(this.ringTimeout);
    this.ringTimeout = null;
    for (const id of [...this.watchdogs.keys(), ...this.restarts.keys()]) this.unwatch(id);
    for (const pc of this.pcs.values()) pc.close();
    this.pcs.clear();
    this.pendingIce.clear();
    this.remote.clear();
    this.makingOffer.clear();
    useCall.getState().local?.getTracks().forEach((t) => t.stop());
    this.participants = [];
    set({ ...idle });
  }
}

export const calls = new CallManager();
