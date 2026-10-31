/** Text-story backgrounds. Names are stored in the payload so both clients render the same thing. */
export const BACKGROUNDS: Record<string, string> = {
  sunset: 'linear-gradient(160deg, #7c5cff 0%, #e84393 55%, #ff8a3d 100%)',
  ocean: 'linear-gradient(160deg, #0f2027 0%, #2c5364 50%, #00c2a8 100%)',
  aurora: 'linear-gradient(160deg, #11998e 0%, #38ef7d 100%)',
  midnight: 'linear-gradient(160deg, #0b0b12 0%, #24243e 60%, #3b2a6b 100%)',
  peach: 'linear-gradient(160deg, #ffb88c 0%, #de6262 100%)',
  lilac: 'linear-gradient(160deg, #c471f5 0%, #fa71cd 100%)',
  mono: 'linear-gradient(160deg, #1c1c1f 0%, #3a3a40 100%)',
  lemon: 'linear-gradient(160deg, #f7971e 0%, #ffd200 100%)',
};

export const backgroundOf = (name?: string | null) => BACKGROUNDS[name ?? 'sunset'] ?? BACKGROUNDS.sunset;

export const FONTS: Record<string, { family: string; style?: string; weight?: number; transform?: string; tracking?: string }> = {
  modern: { family: 'var(--font)', weight: 750, tracking: '-0.02em' },
  // Apple's serif (New York) where there is one, a classic book face elsewhere.
  classic: { family: "ui-serif, 'New York', 'Iowan Old Style', Georgia, serif", style: 'italic', weight: 400 },
  strong: { family: 'var(--font)', weight: 900, transform: 'uppercase', tracking: '0.02em' },
  typewriter: { family: 'ui-monospace, SFMono-Regular, Menlo, monospace', weight: 500 },
};
