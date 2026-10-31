import { Camera, Check, Heart, Lock, Maximize2, Mic, MicOff, Minimize2, Phone, PhoneOff, PictureInPicture2, Radio, RefreshCcw, Send, Video, VideoOff } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { useEffect, useRef, useState } from 'react';
import { useIndex } from '../../core/selectors';
import { useApp } from '../../core/store';
import { Avatar } from '../../ui/Avatar';
import { useMediaUrl } from '../../ui/Media';
import { calls, useCall, type Peer } from './calls';

function StreamVideo({ stream, muted, mirror, className = '' }: { stream: MediaStream | null; muted?: boolean; mirror?: boolean; className?: string }) {
  const ref = useRef<HTMLVideoElement>(null);
  useEffect(() => {
    if (ref.current && ref.current.srcObject !== stream) ref.current.srcObject = stream;
  }, [stream]);
  return <video ref={ref} autoPlay playsInline muted={muted} className={`${className} ${mirror ? 'mirror' : ''}`} />;
}

function AvatarBackdrop({ userId }: { userId: string }) {
  const index = useIndex();
  const url = useMediaUrl(index.profiles[userId]?.payload.avatar ?? null);
  return <div className="call-backdrop" style={url ? { backgroundImage: `url(${url})` } : undefined} />;
}

function Timer({ since }: { since: number }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  const s = Math.floor((now - since) / 1000);
  return <span className="tabular">{`${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`}</span>;
}

/** Whether someone's video is worth showing: a live track that's getting frames, and they haven't said it's off. */
function showsVideo(peer: Peer) {
  return peer.camera !== false && !!peer.stream?.getVideoTracks().some((t) => t.readyState === 'live' && !t.muted);
}

function Tile({ peer }: { peer: Peer }) {
  const users = useApp((s) => s.users);
  const video = showsVideo(peer);
  return (
    <div className="call-tile">
      {peer.stream && <StreamVideo stream={peer.stream} className={video ? 'remote' : 'hidden'} />}
      {!video && (
        <div className="call-tile-avatar">
          <Avatar userId={peer.userId} size={88} />
        </div>
      )}
      <span className="call-tile-name">
        {peer.mic === false && <MicOff size={13} />}
        {users[peer.userId]?.username}
        {peer.state !== 'connected' && (
          <em>
            {' '}
            · {peer.state === 'ringing' ? 'ringing' : peer.state === 'declined' ? 'declined' : peer.state === 'left' ? 'left' : peer.state === 'reconnecting' ? 'reconnecting…' : 'connecting'}
          </em>
        )}
      </span>
    </div>
  );
}

/** Picks a camera on a computer; phones just flip between front and back. */
function CameraMenu({ onClose }: { onClose: () => void }) {
  const local = useCall((s) => s.local);
  const [cameras, setCameras] = useState<MediaDeviceInfo[]>([]);
  const current = local?.getVideoTracks()[0]?.getSettings().deviceId;
  useEffect(() => {
    void calls.cameras().then(setCameras);
  }, []);
  return (
    <div className="call-menu" role="menu" onClick={(e) => e.stopPropagation()}>
      <b>Camera</b>
      {cameras.length === 0 && <span className="faint">No camera found</span>}
      {cameras.map((c, i) => (
        <button key={c.deviceId || i} role="menuitemradio" aria-checked={c.deviceId === current} onClick={() => (onClose(), void calls.switchCamera(c.deviceId))}>
          <span>{c.label || `Camera ${i + 1}`}</span>
          {c.deviceId === current && <Check size={16} />}
        </button>
      ))}
    </div>
  );
}

function FloatingHearts({ count }: { count: number }) {
  const [hearts, setHearts] = useState<number[]>([]);
  useEffect(() => {
    if (!count) return;
    setHearts((h) => [...h.slice(-12), count]);
  }, [count]);
  return (
    <div className="hearts">
      <AnimatePresence>
        {hearts.map((id) => (
          <motion.span
            key={id}
            initial={{ y: 0, opacity: 1, x: 0, scale: 0.6 }}
            animate={{ y: -260, opacity: 0, x: (id % 5) * 12 - 24, scale: 1.2 }}
            transition={{ duration: 2.4, ease: 'easeOut' }}
            onAnimationComplete={() => setHearts((h) => h.filter((x) => x !== id))}
          >
            <Heart size={28} fill="#ff3b5c" strokeWidth={0} />
          </motion.span>
        ))}
      </AnimatePresence>
    </div>
  );
}

function LiveOverlay() {
  const live = useCall((s) => s.live)!;
  const peers = useCall((s) => s.peers);
  const users = useApp((s) => s.users);
  const me = useApp((s) => s.me!);
  const [text, setText] = useState('');
  const viewers = Object.values(peers).filter((p) => p.state === 'connected').length;
  return (
    <>
      <div className="live-top">
        <span className="live-badge">
          <Radio size={14} /> LIVE
        </span>
        {live.role === 'host' && <span className="live-viewers">👁 {viewers}</span>}
      </div>
      <div className="live-comments">
        {live.comments.map((c) => (
          <motion.div key={c.id} className="live-comment" initial={{ opacity: 0, x: -10 }} animate={{ opacity: 1, x: 0 }}>
            <Avatar userId={c.from} size={26} />
            <span>
              <b>{c.from === me.id ? me.username : users[c.from]?.username}</b> {c.text}
            </span>
          </motion.div>
        ))}
      </div>
      <FloatingHearts count={live.hearts} />
      <form
        className="live-input"
        onSubmit={(e) => {
          e.preventDefault();
          if (!text.trim()) return;
          calls.sendLive('chat', text.trim());
          setText('');
        }}
      >
        <input placeholder="Comment…" value={text} onChange={(e) => setText(e.target.value)} />
        <button type="submit" className="icon-btn on-media" aria-label="Send">
          <Send size={18} />
        </button>
        <button type="button" className="icon-btn on-media" onClick={() => calls.sendLive('heart')} aria-label="Send a heart">
          <Heart size={20} />
        </button>
      </form>
    </>
  );
}

export function CallHost() {
  const call = useCall();
  const users = useApp((s) => s.users);
  const remote = useRef<HTMLDivElement>(null);
  const bounds = useRef<HTMLDivElement>(null);
  const [selfPos, setSelfPos] = useState({ x: 0, y: 0 });
  const [cameraMenu, setCameraMenu] = useState(false);

  if (call.status === 'idle') return null;

  const name = (id: string | null) => (id ? users[id]?.username ?? 'Someone' : 'Someone');
  const others = Object.values(call.peers).filter((p) => p.state !== 'declined' && p.state !== 'left');
  const connected = others.filter((p) => p.state === 'connected');

  if (call.status === 'incoming') {
    return (
      <motion.div className="call-screen incoming" initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
        {call.from && <AvatarBackdrop userId={call.from} />}
        <div className="call-center">
          <motion.span className="call-pulse" animate={{ scale: [1, 1.08, 1] }} transition={{ repeat: Infinity, duration: 1.6 }}>
            <Avatar userId={call.from ?? undefined} size={128} />
          </motion.span>
          <h2>{name(call.from)}</h2>
          <p>
            <Lock size={13} /> End-to-end encrypted {call.video ? 'video' : 'audio'} call
          </p>
        </div>
        <div className="call-answer">
          <span className="call-answer-btn">
            <button className="round decline" onClick={() => calls.decline()} aria-label="Decline">
              <PhoneOff size={28} />
            </button>
            Decline
          </span>
          <span className="call-answer-btn">
            <motion.button className="round accept" onClick={() => void calls.accept()} aria-label="Accept" animate={{ y: [0, -4, 0] }} transition={{ repeat: Infinity, duration: 1.2 }}>
              {call.video ? <Video size={28} /> : <Phone size={28} />}
            </motion.button>
            Accept
          </span>
        </div>
      </motion.div>
    );
  }

  if (call.minimized && !call.live) {
    const main = connected.find(showsVideo) ?? connected[0];
    return (
      <div className="call-mini-bounds" ref={bounds}>
      <motion.div className="call-mini" drag dragConstraints={bounds} dragElastic={0.1} dragMomentum={false} initial={{ scale: 0.8, opacity: 0 }} animate={{ scale: 1, opacity: 1 }}>
        {main && showsVideo(main) ? <StreamVideo stream={main.stream} /> : <Avatar userId={(main ?? others[0])?.userId} size={64} />}
        <div className="call-mini-bar">
          <button className="icon-btn sm on-media" onClick={() => useCall.setState({ minimized: false })} aria-label="Expand">
            <Maximize2 size={16} />
          </button>
          <button className="icon-btn sm on-media" onClick={() => calls.toggleMute()} aria-label={call.muted ? 'Unmute' : 'Mute'}>
            {call.muted ? <MicOff size={16} /> : <Mic size={16} />}
          </button>
          <button className="icon-btn sm on-media danger" onClick={() => calls.hangup()} aria-label="Hang up">
            <PhoneOff size={16} />
          </button>
        </div>
      </motion.div>
      </div>
    );
  }

  const hasCamera = !!call.local?.getVideoTracks().length;
  const pipTarget = () => remote.current?.querySelector<HTMLVideoElement>('video.remote') ?? null;
  const gridClass = call.live ? 'live' : connected.length <= 1 ? 'solo' : connected.length <= 4 ? 'quad' : 'nine';
  const hostView = call.live?.role === 'host';
  const viewerView = call.live?.role === 'viewer';

  return (
    <motion.div className="call-screen" initial={{ opacity: 0, scale: 0.98 }} animate={{ opacity: 1, scale: 1 }}>
      {others[0] && !connected.length && <AvatarBackdrop userId={others[0].userId} />}
      <div className={`call-grid ${gridClass}`} ref={remote}>
        {viewerView ? (
          others[0] && <Tile peer={others[0]} />
        ) : hostView ? (
          <div className="call-tile">
            <StreamVideo stream={call.local} muted mirror={call.facing !== 'environment'} />
          </div>
        ) : connected.length ? (
          connected.map((p) => <Tile key={p.userId} peer={p} />)
        ) : (
          <div className="call-center">
            <Avatar userId={others[0]?.userId} size={128} />
            <h2>{others.map((p) => name(p.userId)).join(', ')}</h2>
            <p>{call.status === 'outgoing' ? 'Calling…' : 'Connecting…'}</p>
          </div>
        )}
      </div>

      {!call.live && hasCamera && call.local && (
        <motion.div className="call-self" drag dragConstraints={remote} dragMomentum={false} style={{ x: selfPos.x, y: selfPos.y }} onDragEnd={(_, info) => setSelfPos((p) => ({ x: p.x + info.offset.x, y: p.y + info.offset.y }))}>
          <StreamVideo stream={call.local} muted mirror={call.facing !== 'environment'} />
          {call.cameraOff && <div className="call-self-off"><VideoOff size={20} /></div>}
        </motion.div>
      )}

      <header className="call-top">
        <span className="call-lock">
          <Lock size={12} /> End-to-end encrypted{call.relay ? ' · relayed' : ''}
        </span>
        {call.startedAt && call.status === 'active' && <Timer since={call.startedAt} />}
        {!call.live && (
          <button className="icon-btn sm on-media" onClick={() => useCall.setState({ minimized: true })} aria-label="Minimise">
            <Minimize2 size={16} />
          </button>
        )}
      </header>

      {call.live && <LiveOverlay />}

      <div className="call-controls">
        {!viewerView && (
          <>
            <button className={`round ${call.muted ? 'off' : ''}`} onClick={() => calls.toggleMute()} aria-label={call.muted ? 'Unmute' : 'Mute'}>
              {call.muted ? <MicOff size={22} /> : <Mic size={22} />}
            </button>
            <button className={`round ${call.cameraOff ? 'off' : ''}`} onClick={() => void calls.toggleCamera()} aria-label={call.cameraOff ? 'Camera on' : 'Camera off'}>
              {call.cameraOff ? <VideoOff size={22} /> : <Video size={22} />}
            </button>
            {hasCamera && (
              <span className="call-menu-anchor">
                <button
                  className="round"
                  onClick={() => (call.facing ? void calls.switchCamera() : setCameraMenu((v) => !v))}
                  aria-label={call.facing ? 'Flip camera' : 'Choose camera'}
                  aria-expanded={call.facing ? undefined : cameraMenu}
                >
                  {call.facing ? <RefreshCcw size={22} /> : <Camera size={22} />}
                </button>
                {cameraMenu && !call.facing && <CameraMenu onClose={() => setCameraMenu(false)} />}
              </span>
            )}
          </>
        )}
        {'pictureInPictureEnabled' in document && connected.some(showsVideo) && (
          <button className="round" onClick={() => void pipTarget()?.requestPictureInPicture().catch(() => undefined)} aria-label="Picture in picture">
            <PictureInPicture2 size={22} />
          </button>
        )}
        <button className="round decline" onClick={() => calls.hangup()} aria-label={hostView ? 'End live' : 'Hang up'}>
          <PhoneOff size={24} />
        </button>
      </div>
    </motion.div>
  );
}
