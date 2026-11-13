/// <reference lib="webworker" />
import sodium from 'libsodium-wrappers-sumo';
import { isBlobId, safeMediaType } from '../core/safe';

/**
 * Hush's service worker:
 * - decrypts streaming video on the fly (`/media/<blobId>`), with Range support, using keys the page hands over in
 *   memory only (never stored);
 * - shows push notifications. Pushes carry no content, only who did what kind of thing.
 */
declare const self: ServiceWorkerGlobalScope;

const HEADER = 32;
const TAG = 16;
const keys = new Map<string, { key: Uint8Array; mime: string; token: string | null }>();
const headers = new Map<string, { chunkSize: number; plainLength: number; noncePrefix: Uint8Array; bytes: Uint8Array }>();

self.addEventListener('install', () => void self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('message', (event) => {
  const data = event.data;
  if (data?.type === 'media-key' && isBlobId(data.blobId) && typeof data.key === 'string') {
    try {
      const key = sodium.from_base64(data.key, sodium.base64_variants.URLSAFE_NO_PADDING);
      keys.set(data.blobId, { key, mime: safeMediaType(data.mime), token: typeof data.token === 'string' ? data.token : null });
    } catch {
      /* not a key: nothing is served for it */
    }
    event.ports[0]?.postMessage('ok');
  }
  // Something was removed (unsent, expired, or no longer shared with us): stop serving its files.
  if (data?.type === 'media-forget' && Array.isArray(data.blobIds)) {
    for (const id of data.blobIds) if (isBlobId(id)) keys.delete(id);
  }
});

const u64 = (n: number) => {
  const b = new Uint8Array(8);
  new DataView(b.buffer).setBigUint64(0, BigInt(n));
  return b;
};

function concat(...parts: Uint8Array[]) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

async function fetchRange(blobId: string, token: string | null, start: number, endExclusive: number) {
  const response = await fetch(`/api/v1/blobs/${blobId}`, {
    headers: { Range: `bytes=${start}-${endExclusive - 1}`, ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  if (!response.ok) throw new Error(`blob ${response.status}`);
  return new Uint8Array(await response.arrayBuffer());
}

async function headerOf(blobId: string, token: string | null) {
  const cached = headers.get(blobId);
  if (cached) return cached;
  const bytes = await fetchRange(blobId, token, 0, HEADER);
  const view = new DataView(bytes.buffer, bytes.byteOffset, HEADER);
  // "AezCrypt" files (see crypto/media.ts), or "AGM1" ones from before the name changed.
  const legacy = String.fromCharCode(...bytes.subarray(0, 4)) === 'AGM1';
  const at = legacy ? 4 : 8;
  const noncePrefix = legacy ? bytes.slice(16, 32) : concat(bytes.slice(20, 32), new Uint8Array(4));
  const header = { chunkSize: view.getUint32(at), plainLength: Number(view.getBigUint64(at + 4)), noncePrefix, bytes: bytes.slice(0, 32) };
  headers.set(blobId, header);
  return header;
}

async function serveMedia(request: Request, blobId: string): Promise<Response> {
  await sodium.ready;
  const entry = keys.get(blobId);
  if (!entry) return new Response('locked', { status: 403 });
  const h = await headerOf(blobId, entry.token);
  const count = h.plainLength === 0 ? 1 : Math.ceil(h.plainLength / h.chunkSize);
  const range = /bytes=(\d+)-(\d*)/.exec(request.headers.get('Range') ?? '');
  const start = range ? Number(range[1]) : 0;
  // Serve at most ~2 MB per request; the media element asks for more as it plays.
  const maxEnd = Math.min(h.plainLength - 1, start + 2 * 1024 * 1024);
  const end = range && range[2] ? Math.min(Number(range[2]), maxEnd) : maxEnd;
  if (h.plainLength === 0 || start >= h.plainLength) {
    return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${h.plainLength}` } });
  }
  const first = Math.floor(start / h.chunkSize);
  const last = Math.floor(end / h.chunkSize);
  const cipherStart = HEADER + first * (h.chunkSize + TAG);
  const lastPlain = last === count - 1 ? h.plainLength - last * h.chunkSize : h.chunkSize;
  const cipherEnd = HEADER + last * (h.chunkSize + TAG) + lastPlain + TAG;
  const cipher = await fetchRange(blobId, entry.token, cipherStart, cipherEnd);
  const plainParts: Uint8Array[] = [];
  let offset = 0;
  for (let i = first; i <= last; i++) {
    const plainLen = i === count - 1 ? h.plainLength - i * h.chunkSize : h.chunkSize;
    const chunk = cipher.subarray(offset, offset + plainLen + TAG);
    offset += plainLen + TAG;
    const ad = concat(h.bytes, u64(i), new Uint8Array([i === count - 1 ? 1 : 0]));
    plainParts.push(sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(null, chunk, ad, concat(h.noncePrefix, u64(i)), entry.key));
  }
  const plain = concat(...plainParts);
  const body = plain.subarray(start - first * h.chunkSize, end - first * h.chunkSize + 1);
  return new Response(body as BodyInit, {
    status: 206,
    headers: {
      'Content-Type': entry.mime,
      'Content-Length': String(body.length),
      'Content-Range': `bytes ${start}-${end}/${h.plainLength}`,
      'Accept-Ranges': 'bytes',
      'Cache-Control': 'no-store',
      ...LOCKED_DOWN,
    },
  });
}

/**
 * What the decrypted bytes are is the sender's word: `mime` came out of their (encrypted) post. So these responses
 * are only ever photos, video or sound (safeMediaType), are never sniffed into something else, and carry a policy
 * under which nothing in them could run even if a browser did show one as a page.
 */
const LOCKED_DOWN = {
  'X-Content-Type-Options': 'nosniff',
  'Content-Security-Policy': "default-src 'none'; sandbox",
  'Cross-Origin-Resource-Policy': 'same-origin',
};

/** `/media/<id>` feeds <video> and <audio> elements. It is not somewhere to go: opened as a page or in a frame, it's refused. */
const asPage = (request: Request) => request.mode === 'navigate' || ['document', 'iframe', 'frame', 'embed', 'object'].includes(request.destination);

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin || !url.pathname.startsWith('/media/')) return;
  const blobId = url.pathname.slice('/media/'.length);
  if (asPage(event.request) || !isBlobId(blobId)) {
    event.respondWith(new Response('Not found', { status: 404, headers: { 'Content-Type': 'text/plain', ...LOCKED_DOWN } }));
    return;
  }
  event.respondWith(serveMedia(event.request, blobId).catch(() => new Response('error', { status: 502, headers: LOCKED_DOWN })));
});

// ---------------------------------------------------------------------------------------------------------- push

interface PushData {
  type: string;
  from?: string | null;
  fromId?: string | null;
  ref?: string | null;
}

function describe(data: PushData): { title: string; body: string; url: string; urgent?: boolean } {
  const who = data.from ? `@${data.from}` : 'Someone';
  switch (data.type) {
    case 'message':
      return { title: who, body: 'Sent you a message', url: data.ref ? `/direct/${data.ref}` : '/direct' };
    case 'reaction':
      return { title: who, body: 'Reacted to your message', url: '/direct' };
    case 'comment':
      return { title: 'New comment', body: `${who} commented on your post`, url: '/activity' };
    case 'like':
      return { title: 'New like', body: `${who} liked your post`, url: '/activity' };
    case 'activity':
      return { title: 'You were mentioned', body: `${who} mentioned you`, url: '/activity' };
    case 'answer':
      return { title: 'New answer', body: `${who} answered your question`, url: '/activity' };
    case 'follow':
      return { title: 'New follower', body: `${who} started following you`, url: '/activity' };
    case 'follow_request':
      return { title: 'Follow request', body: `${who} wants to follow you`, url: '/activity' };
    case 'follow_accepted':
      return { title: 'Request accepted', body: `${who} accepted your follow request`, url: '/activity' };
    case 'call':
      return { title: `${who} is calling`, body: 'Tap to answer in Hush', url: data.fromId ? `/direct?call=${data.fromId}` : '/direct', urgent: true };
    case 'ring_test':
      return { title: 'Test ring', body: 'Notifications reach this device.', url: '/settings/notifications', urgent: true };
    default:
      return { title: 'Hush', body: 'Something new', url: '/' };
  }
}

self.addEventListener('push', (event) => {
  let data: PushData = { type: 'unknown' };
  try {
    data = event.data?.json() ?? data;
  } catch {
    /* keep default */
  }
  const info = describe(data);
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      // A visible tab already shows it in the app.
      if (!info.urgent && windows.some((w) => w.visibilityState === 'visible')) return;
      await self.registration.showNotification(info.title, {
        body: info.body,
        icon: '/icon-192.png',
        badge: '/badge-72.png',
        tag: data.type === 'call' ? `call-${data.ref}` : `hush-${data.type}`,
        requireInteraction: !!info.urgent,
        data: { url: info.url },
      });
      // A call rings for 45 seconds, so its notification shouldn't outlive that (the caller may have hung up long ago).
      if (data.type === 'call') {
        await new Promise((resolve) => setTimeout(resolve, 45_000));
        for (const n of await self.registration.getNotifications({ tag: `call-${data.ref}` })) n.close();
      }
    })(),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = (event.notification.data as { url?: string } | undefined)?.url ?? '/';
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      for (const client of windows) {
        if ('focus' in client) {
          await client.focus();
          client.postMessage({ type: 'navigate', url });
          return;
        }
      }
      await self.clients.openWindow(url);
    })(),
  );
});
