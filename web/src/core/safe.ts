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
