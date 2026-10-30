/** Photo looks and adjustments, applied with canvas filters when the image is re-encoded. */

export interface Adjustments {
  brightness: number; // -1 … 1
  contrast: number;
  saturation: number;
  warmth: number;
  fade: number; // 0 … 1
  vignette: number; // 0 … 1
}

export const NO_ADJUST: Adjustments = { brightness: 0, contrast: 0, saturation: 0, warmth: 0, fade: 0, vignette: 0 };

export const LOOKS: { id: string; name: string; filter: string }[] = [
  { id: 'original', name: 'Original', filter: '' },
  { id: 'vivid', name: 'Vivid', filter: 'contrast(1.12) saturate(1.38)' },
  { id: 'golden', name: 'Golden', filter: 'sepia(0.22) saturate(1.3) brightness(1.04) hue-rotate(-8deg)' },
  { id: 'coast', name: 'Coast', filter: 'saturate(1.1) hue-rotate(8deg) brightness(1.04) contrast(1.05)' },
  { id: 'dream', name: 'Dream', filter: 'brightness(1.1) contrast(0.88) saturate(1.15)' },
  { id: 'film', name: 'Film', filter: 'sepia(0.18) contrast(0.92) brightness(1.06) saturate(0.85)' },
  { id: 'mono', name: 'Mono', filter: 'grayscale(1) contrast(1.12) brightness(1.04)' },
  { id: 'noir', name: 'Noir', filter: 'grayscale(1) contrast(1.45) brightness(0.92)' },
  { id: 'dusk', name: 'Dusk', filter: 'hue-rotate(-12deg) saturate(1.2) contrast(1.08) brightness(0.96)' },
  { id: 'arctic', name: 'Arctic', filter: 'hue-rotate(14deg) saturate(0.8) brightness(1.1)' },
];

export function filterString(look: string, a: Adjustments): string {
  const base = LOOKS.find((l) => l.id === look)?.filter ?? '';
  const parts = [base];
  if (a.brightness) parts.push(`brightness(${1 + a.brightness * 0.4})`);
  if (a.contrast) parts.push(`contrast(${1 + a.contrast * 0.5})`);
  if (a.saturation) parts.push(`saturate(${1 + a.saturation})`);
  if (a.warmth > 0) parts.push(`sepia(${a.warmth * 0.35})`);
  if (a.warmth < 0) parts.push(`hue-rotate(${a.warmth * -18}deg) saturate(${1 - a.warmth * -0.1})`);
  if (a.fade) parts.push(`contrast(${1 - a.fade * 0.3}) brightness(${1 + a.fade * 0.12})`);
  return parts.filter(Boolean).join(' ');
}

export type Aspect = 'original' | 'square' | 'portrait' | 'landscape';

export const ASPECTS: { id: Aspect; label: string; ratio: number | null }[] = [
  { id: 'original', label: 'Original', ratio: null },
  { id: 'square', label: '1:1', ratio: 1 },
  { id: 'portrait', label: '4:5', ratio: 4 / 5 },
  { id: 'landscape', label: '16:9', ratio: 16 / 9 },
];

/** The crop rectangle in source pixels for an aspect, zoom (≥1) and pan (-1 … 1 on each axis). */
export function cropRect(width: number, height: number, ratio: number | null, zoom: number, panX: number, panY: number) {
  const r = ratio ?? Math.min(1.91, Math.max(0.8, width / height));
  let cw = width;
  let ch = width / r;
  if (ch > height) {
    ch = height;
    cw = height * r;
  }
  cw /= zoom;
  ch /= zoom;
  const x = (width - cw) / 2 + panX * ((width - cw) / 2);
  const y = (height - ch) / 2 + panY * ((height - ch) / 2);
  return { x: Math.round(x), y: Math.round(y), width: Math.round(cw), height: Math.round(ch) };
}
