/**
 * Checks on things that come out of other people's posts and messages. Their content is encrypted, so the server
 * never sees it and can't vet it: a modified client could put anything in these fields, and whatever shows them has
 * to be careful instead.
 */

const MEDIA_TYPES = /^(video\/(mp4|webm|quicktime|ogg|x-matroska|3gpp)|audio\/(mp4|mpeg|webm|ogg|aac|wav|x-wav|x-m4a|m4a|flac|3gpp)|image\/(jpeg|png|webp|gif|avif|heic|heif))$/;

/**
 * The type to hand the browser for a decrypted file: photos, video and sound only. Anything else (a page, an SVG, a
 * script) becomes plain bytes, so a file that claims to be HTML is never treated as a page of this site.
 */
export function safeMediaType(mime: unknown): string {
  const type = typeof mime === 'string' ? mime.split(';')[0].trim().toLowerCase() : '';
  return MEDIA_TYPES.test(type) ? type : 'application/octet-stream';
}

/** A media file's id: the SHA-256 of its ciphertext, in hex. */
export const isBlobId = (id: unknown): id is string => typeof id === 'string' && /^[0-9a-f]{64}$/.test(id);

/** A link someone typed, if it's an ordinary web address; `javascript:`, `data:` and the rest are refused. */
export function webLink(raw: string | null | undefined): string | null {
  const text = (raw ?? '').trim();
  if (!text) return null;
  try {
    const url = new URL(/^[a-z][a-z0-9+.-]*:/i.test(text) ? text : `https://${text}`);
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.href : null;
  } catch {
    return null;
  }
}

// ------------------------------------------------------------------------------------------- chat payload shapes
// A message's plan, live location or poll, only if it has the shape the app expects. Another member's modified client
// could send numbers where text goes, or the other way round; shown as-is, that would break the chat for everyone in it.

const finite = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);
const str = (v: unknown, max: number): string | null => (typeof v === 'string' ? v.slice(0, max) : null);

export interface SafePlan { title: string; at: number; place: string | null }
export function safePlan(raw: unknown): SafePlan | null {
  if (!raw || typeof raw !== 'object') return null;
  const { title, at, place } = raw as Record<string, unknown>;
  const t = str(title, 100)?.trim();
  if (!t || !finite(at)) return null;
  return { title: t, at, place: str(place, 100)?.trim() || null };
}

export interface SafeLocation { lat: number; lng: number; accuracy: number | null; until: number; updatedAt: number }
export function safeLocation(raw: unknown): SafeLocation | null {
  if (!raw || typeof raw !== 'object') return null;
  const { lat, lng, accuracy, until, updatedAt } = raw as Record<string, unknown>;
  if (!finite(lat) || !finite(lng) || !finite(until) || !finite(updatedAt)) return null;
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
  return { lat, lng, accuracy: finite(accuracy) && accuracy >= 0 ? accuracy : null, until, updatedAt };
}

export interface SafePoll { question: string; options: string[] }
export function safePoll(raw: unknown): SafePoll | null {
  if (!raw || typeof raw !== 'object') return null;
  const { question, options } = raw as Record<string, unknown>;
  const q = str(question, 140)?.trim();
  if (!q || !Array.isArray(options)) return null;
  const opts = options.slice(0, 6).map((o) => str(o, 60) ?? '').filter(Boolean);
  return opts.length >= 2 ? { question: q, options: opts } : null;
}

/** Text a message carries, or empty when it carries something else. */
export const safeText = (v: unknown, max = 10_000): string => str(v, max) ?? '';
