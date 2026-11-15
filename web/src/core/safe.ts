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
/** A moment a date can be made of (1970 to 9999): outside it, date formatting throws. */
const time = (v: unknown): v is number => finite(v) && v >= 0 && v <= 253_402_300_799_999;
const str = (v: unknown, max: number): string | null => (typeof v === 'string' ? v.slice(0, max) : null);

export interface SafePlan { title: string; at: number; place: string | null }
export function safePlan(raw: unknown): SafePlan | null {
  if (!raw || typeof raw !== 'object') return null;
  const { title, at, place } = raw as Record<string, unknown>;
  const t = str(title, 100)?.trim();
  if (!t || !time(at)) return null;
  return { title: t, at, place: str(place, 100)?.trim() || null };
}

export interface SafeLocation { lat: number; lng: number; accuracy: number | null; until: number; updatedAt: number }
export function safeLocation(raw: unknown): SafeLocation | null {
  if (!raw || typeof raw !== 'object') return null;
  const { lat, lng, accuracy, until, updatedAt } = raw as Record<string, unknown>;
  if (!finite(lat) || !finite(lng) || !time(until) || !time(updatedAt)) return null;
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

// ------------------------------------------------------------------------------------------------ display limits
// Well-formed but outsized: a 200,000-character text, 300 photos in one message, 2,000 hearts. Mirrors Display.kt.

export const MAX_TEXT = 8_000;
export const MAX_PREVIEW = 300;
export const MAX_MEDIA = 10;

/** A message's text as the chat shows it: at most [max] characters, and never more than one empty line in a row. */
export function displayText(text: unknown, max = MAX_TEXT): string {
  const value = typeof text === 'string' ? text : '';
  const cut = value.length > max ? `${value.slice(0, max).trimEnd()}…` : value;
  return cut.replace(/\n{3,}/g, '\n\n');
}

/** How much of a long message shows before "Read more" (mirrors Display.FOLD_CHARS and FOLD_LINES). */
export const FOLD_CHARS = 1_000;
export const FOLD_LINES = 20;

/** The start of a long message, up to FOLD_CHARS characters or FOLD_LINES lines, with an ellipsis. */
export function foldText(text: string): string {
  let end = Math.min(text.length, FOLD_CHARS);
  let lines = 0;
  for (let i = 0; i < end; i++) {
    if (text[i] === '\n' && ++lines >= FOLD_LINES) {
      end = i;
      break;
    }
  }
  return end >= text.length ? text : `${text.slice(0, end).trimEnd()}…`;
}

/** A one-line preview: line breaks become spaces, at most [MAX_PREVIEW] characters. */
export const previewText = (text: unknown): string => displayText(typeof text === 'string' ? text.replace(/\n/g, ' ') : '', MAX_PREVIEW);

/**
 * The first character as a person sees it: 👩‍👩‍👧‍👦, 🇵🇹 or é is one, however many code points it takes. At most 16
 * UTF-16 units, the longest a single emoji needs. Mirrors Display.firstGrapheme.
 */
export function firstGrapheme(text: string): string {
  if (!text) return '';
  let first: string | undefined;
  try {
    first = new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(text)[Symbol.iterator]().next().value?.segment;
  } catch {
    first = Array.from(text)[0];
  }
  return (first ?? '').slice(0, 16);
}

/** What a heart message shows: its first emoji, or [fallback] if it has none. */
export function heartOf(text: unknown, fallback: string): string {
  return firstGrapheme(typeof text === 'string' ? text.trim() : '') || fallback;
}

/** A duration someone else's app reported, as a clock can show it: never negative, never more than a day. */
export const shownDuration = (ms: unknown): number => (typeof ms === 'number' && Number.isFinite(ms) ? Math.min(86_400_000, Math.max(0, ms)) : 0);
