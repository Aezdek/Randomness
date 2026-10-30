import { useApp } from '../core/store';
import { useIndex } from '../core/selectors';
import { useMediaUrl } from './Media';

const GRADIENTS = [
  'linear-gradient(135deg,#7c5cff,#e84393)',
  'linear-gradient(135deg,#ff8a3d,#e84393)',
  'linear-gradient(135deg,#00c2a8,#4f8bff)',
  'linear-gradient(135deg,#ffb547,#ff5e62)',
  'linear-gradient(135deg,#8e54e9,#4776e6)',
  'linear-gradient(135deg,#11998e,#38ef7d)',
  'linear-gradient(135deg,#f953c6,#b91d73)',
  'linear-gradient(135deg,#fc4a1a,#f7b733)',
];

function gradientFor(id: string) {
  let hash = 0;
  for (const c of id) hash = (hash * 31 + c.charCodeAt(0)) | 0;
  return GRADIENTS[Math.abs(hash) % GRADIENTS.length];
}

interface AvatarProps {
  userId: string | undefined;
  size?: number;
  presence?: boolean;
  className?: string;
}

export function Avatar({ userId, size = 40, presence, className = '' }: AvatarProps) {
  const index = useIndex();
  const user = useApp((s) => (userId ? s.users[userId] : undefined));
  const online = useApp((s) => (userId ? s.presence[userId] : false));
  const profile = userId ? index.profiles[userId]?.payload : undefined;
  const url = useMediaUrl(profile?.avatar ?? null);
  const label = (profile?.displayName || user?.username || '?').trim();
  return (
    <span className={`avatar ${className}`} style={{ ['--size' as string]: `${size}px`, background: url ? undefined : gradientFor(userId ?? label) }}>
      {url ? <img src={url} alt="" draggable={false} /> : <span className="initial">{label.charAt(0).toUpperCase()}</span>}
      {presence && online && <span className="presence-dot" />}
    </span>
  );
}

export type RingState = 'unseen' | 'seen' | 'close' | 'live' | 'none';

/** An avatar inside the story ring: gradient for unseen, green for Close Friends, grey once seen. */
export function RingAvatar({ userId, size = 56, ring, onClick, label }: { userId: string; size?: number; ring: RingState; onClick?: () => void; label?: string }) {
  const inner = Math.round(size * 0.86);
  const ringClass = ring === 'unseen' ? '' : ring;
  const content = (
    <span className={`ring ${ringClass}`} style={{ ['--ring-size' as string]: `${size}px` }}>
      <Avatar userId={userId} size={inner} />
      {ring === 'live' && <span className="live-pill">LIVE</span>}
    </span>
  );
  return onClick ? (
    <button onClick={onClick} aria-label={label ?? 'Open stories'} style={{ borderRadius: '50%' }}>
      {content}
    </button>
  ) : (
    content
  );
}
