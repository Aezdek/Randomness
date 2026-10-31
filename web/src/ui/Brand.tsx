/** The brand mark, as an app icon: a blue rounded square with a white keyhole. */
export function BrandMark({ size = 28 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 48 48" aria-hidden>
      <defs>
        <linearGradient id="hush-icon" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#2b95ff" />
          <stop offset="1" stopColor="#0066e0" />
        </linearGradient>
      </defs>
      <rect x="1" y="1" width="46" height="46" rx="11" fill="url(#hush-icon)" />
      <circle cx="24" cy="20" r="6.2" fill="#fff" />
      <path d="M21 23.8h6l2.1 11a1.4 1.4 0 0 1-1.4 1.7h-7.4a1.4 1.4 0 0 1-1.4-1.7z" fill="#fff" />
    </svg>
  );
}

export function Wordmark({ className = '' }: { className?: string }) {
  return <span className={`wordmark ${className}`}>Hush</span>;
}
