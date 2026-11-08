/** The brand mark: a lowercase h on a violet tile, with an orange dot (you, among your people). Flat colours only. */
export function BrandMark({ size = 28 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 48 48" aria-hidden>
      <rect width="48" height="48" rx="14" fill="#7c5cff" />
      <path d="M16.5 12v24M16.5 26a7.5 7.5 0 0 1 15 0v10" fill="none" stroke="#fff" strokeWidth="5.2" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="33" cy="13.5" r="3" fill="#ff8a3d" />
    </svg>
  );
}

export function Wordmark({ className = '' }: { className?: string }) {
  return <span className={`wordmark ${className}`}>Hush</span>;
}
