/** The brand mark: a soft frame around a keyhole — a camera you hold the key to. */
export function BrandMark({ size = 28 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 48 48" aria-hidden>
      <defs>
        <linearGradient id="ag-grad" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#7c5cff" />
          <stop offset="0.5" stopColor="#e84393" />
          <stop offset="1" stopColor="#ff8a3d" />
        </linearGradient>
      </defs>
      <rect x="3.5" y="3.5" width="41" height="41" rx="15" fill="none" stroke="url(#ag-grad)" strokeWidth="4" />
      <circle cx="24" cy="20.5" r="6" fill="url(#ag-grad)" />
      <path d="M21.2 24.5h5.6l2.2 11.2a1.4 1.4 0 0 1-1.4 1.7h-7.2a1.4 1.4 0 0 1-1.4-1.7z" fill="url(#ag-grad)" />
    </svg>
  );
}

export function Wordmark({ className = '' }: { className?: string }) {
  return <span className={`wordmark ${className}`}>AezGram</span>;
}
