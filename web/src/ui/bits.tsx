import { BadgeCheck } from 'lucide-react';
import { motion } from 'motion/react';
import { Fragment, useEffect, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useApp } from '../core/store';

export function Spinner({ size = 22 }: { size?: number }) {
  return <span className="spinner" style={{ width: size, height: size }} role="status" aria-label="Loading" />;
}

export function Center({ children, style }: { children: ReactNode; style?: React.CSSProperties }) {
  return <div style={{ display: 'grid', placeItems: 'center', padding: 48, ...style }}>{children}</div>;
}

export function Empty({ icon, title, children, action }: { icon: ReactNode; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty">
      <div className="glyph">{icon}</div>
      <h3>{title}</h3>
      {children && <p>{children}</p>}
      {action}
    </div>
  );
}

export function Switch({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return <button role="switch" aria-checked={checked} aria-label={label} className="switch" onClick={() => onChange(!checked)} />;
}

export function Tabs<T extends string>({ value, onChange, tabs }: { value: T; onChange: (v: T) => void; tabs: { id: T; label: string; icon: ReactNode }[] }) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((tab) => (
        <button key={tab.id} role="tab" aria-selected={value === tab.id} onClick={() => onChange(tab.id)}>
          {value === tab.id && <motion.div layoutId="tab-indicator" className="indicator" transition={{ type: 'spring', stiffness: 500, damping: 40 }} />}
          {tab.icon}
          <span>{tab.label}</span>
        </button>
      ))}
    </div>
  );
}

export function Segmented<T extends string>({ value, onChange, options, id }: { value: T; onChange: (v: T) => void; options: { id: T; label: string }[]; id: string }) {
  return (
    <div className="segmented" role="tablist">
      {options.map((option) => (
        <button key={option.id} role="tab" aria-selected={value === option.id} onClick={() => onChange(option.id)}>
          {value === option.id && <motion.span layoutId={`seg-${id}`} className="pill" transition={{ type: 'spring', stiffness: 500, damping: 38 }} />}
          {option.label}
        </button>
      ))}
    </div>
  );
}

export function Toasts() {
  const toasts = useApp((s) => s.toasts);
  return (
    <div className="toasts" aria-live="polite">
      {toasts.map((t) => (
        <motion.div key={t.id} className={`toast ${t.tone ?? ''}`} initial={{ opacity: 0, y: 16, scale: 0.96 }} animate={{ opacity: 1, y: 0, scale: 1 }}>
          {t.text}
        </motion.div>
      ))}
    </div>
  );
}

const UNITS: [number, string][] = [
  [60, 's'],
  [60, 'm'],
  [24, 'h'],
  [7, 'd'],
  [52, 'w'],
];

export function ago(at: number, now = Date.now()): string {
  let value = Math.max(0, (now - at) / 1000);
  if (value < 45) return 'now';
  for (const [size, unit] of UNITS) {
    if (value < size) return `${Math.floor(value)}${unit}`;
    value /= size;
  }
  return `${Math.floor(value)}y`;
}

export function longDate(at: number) {
  return new Date(at).toLocaleDateString(undefined, { month: 'long', day: 'numeric', year: new Date(at).getFullYear() === new Date().getFullYear() ? undefined : 'numeric' });
}

export function Ago({ at, className = 'faint' }: { at: number; className?: string }) {
  const [, tick] = useState(0);
  useEffect(() => {
    const timer = setInterval(() => tick((n) => n + 1), 60_000);
    return () => clearInterval(timer);
  }, []);
  return (
    <time className={className} dateTime={new Date(at).toISOString()} title={new Date(at).toLocaleString()}>
      {ago(at)}
    </time>
  );
}

export function Verified({ size = 14 }: { size?: number }) {
  return <BadgeCheck size={size} className="verified" aria-label="Verified" fill="currentColor" stroke="var(--bg)" />;
}

/** Captions and comments: @mentions and #hashtags become links. */
export function RichText({ text }: { text: string }) {
  const parts = text.split(/([@#][\p{L}\p{N}._]{2,30})/u);
  return (
    <>
      {parts.map((part, i) =>
        part.startsWith('@') ? (
          <Link key={i} to={`/u/${part.slice(1).toLowerCase()}`} className="mention" onClick={(e) => e.stopPropagation()}>
            {part}
          </Link>
        ) : part.startsWith('#') ? (
          <Link key={i} to={`/explore/tags/${encodeURIComponent(part.slice(1).toLowerCase())}`} className="mention" onClick={(e) => e.stopPropagation()}>
            {part}
          </Link>
        ) : (
          <Fragment key={i}>{part}</Fragment>
        ),
      )}
    </>
  );
}

export function compact(n: number): string {
  if (n < 1000) return String(n);
  if (n < 1_000_000) return `${(n / 1000).toFixed(n < 10_000 ? 1 : 0).replace(/\.0$/, '')}K`;
  return `${(n / 1_000_000).toFixed(1).replace(/\.0$/, '')}M`;
}
