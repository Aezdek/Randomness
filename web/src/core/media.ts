import { encode as encodeBlurhash } from 'blurhash';
import {
  ALL_FORMATS,
  BlobSource,
  BufferTarget,
  CanvasSink,
  Conversion,
  Input,
  Mp4OutputFormat,
  Output,
  Quality,
  canEncodeAudio,
  canEncodeVideo,
} from 'mediabunny';
import { chunkCount, cipherLength, DEFAULT_CHUNK, decryptMedia, newFileKey, newHeader, sealChunk } from '../crypto/media';
import { b64, concat, unb64 } from '../crypto/sodium';
import { api, fetchBlob, getToken } from './api';
import { ApiError } from './errors';
import type { MediaRef } from './payloads';
import type { BlobDto, UploadState } from './types';

/**
 * The media pipeline. The server cannot transcode ciphertext, so every file is normalised here before it is
 * encrypted: photos become ≤2048 px JPEGs (which also drops EXIF and GPS), videos become 1080p30 H.264/AAC MP4 with
 * the index up front (fast start), so every browser and phone can play and seek them.
 */

export interface Prepared {
  blob: Blob;
  mime: string;
  width: number;
  height: number;
  durationMs?: number;
  blurhash?: string;
  poster?: Prepared;
}

export type MediaKind = 'post' | 'reel' | 'story' | 'message' | 'avatar';

const VIDEO_SPEC: Record<MediaKind, { maxEdge: number; bitrate: number; maxSeconds: number }> = {
  post: { maxEdge: 1920, bitrate: 5_000_000, maxSeconds: 600 },
  reel: { maxEdge: 1920, bitrate: 5_000_000, maxSeconds: 180 },
  story: { maxEdge: 1920, bitrate: 3_500_000, maxSeconds: 60 },
  message: { maxEdge: 1280, bitrate: 2_500_000, maxSeconds: 600 },
  avatar: { maxEdge: 640, bitrate: 1_000_000, maxSeconds: 10 },
};

const IMAGE_EDGE: Record<MediaKind, number> = { post: 2048, reel: 1440, story: 1440, message: 2048, avatar: 640 };

export class UnsupportedMediaError extends Error {}

function fitWithin(width: number, height: number, maxEdge: number) {
  const scale = Math.min(1, maxEdge / Math.max(width, height));
  // Even dimensions keep H.264 encoders happy.
  return { width: Math.max(2, Math.round((width * scale) / 2) * 2), height: Math.max(2, Math.round((height * scale) / 2) * 2) };
}

async function blurhashOf(source: CanvasImageSource, width: number, height: number): Promise<string> {
  const w = 32;
  const h = Math.max(1, Math.round((32 * height) / width));
  const canvas = new OffscreenCanvas(w, h);
  const ctx = canvas.getContext('2d')!;
  ctx.drawImage(source, 0, 0, w, h);
  return encodeBlurhash(ctx.getImageData(0, 0, w, h).data, w, h, 4, 3);
}

/** Decode (honouring EXIF orientation), optionally crop/filter, downscale, re-encode as JPEG. */
export async function prepareImage(
  file: Blob,
  kind: MediaKind,
  edit?: { crop?: { x: number; y: number; width: number; height: number }; filter?: string; vignette?: number },
): Promise<Prepared> {
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
  } catch {
    throw new UnsupportedMediaError('This browser cannot open that photo format. Try a JPEG or PNG, or use the Android app.');
  }
  const crop = edit?.crop ?? { x: 0, y: 0, width: bitmap.width, height: bitmap.height };
  const { width, height } = fitWithin(crop.width, crop.height, IMAGE_EDGE[kind]);
  const canvas = new OffscreenCanvas(width, height);
  const ctx = canvas.getContext('2d')!;
  ctx.imageSmoothingQuality = 'high';
  if (edit?.filter) ctx.filter = edit.filter;
  ctx.drawImage(bitmap, crop.x, crop.y, crop.width, crop.height, 0, 0, width, height);
  if (edit?.vignette) {
    ctx.filter = 'none';
    const gradient = ctx.createRadialGradient(width / 2, height / 2, Math.min(width, height) * 0.35, width / 2, height / 2, Math.hypot(width, height) / 2);
    gradient.addColorStop(0, 'rgba(0,0,0,0)');
    gradient.addColorStop(1, `rgba(0,0,0,${0.65 * edit.vignette})`);
    ctx.fillStyle = gradient;
    ctx.fillRect(0, 0, width, height);
  }
  const blob = await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.85 });
  const blurhash = await blurhashOf(canvas, width, height);
  bitmap.close();
  return { blob, mime: 'image/jpeg', width, height, blurhash };
}

/** Transcodes to the shared spec with WebCodecs. Refuses (rather than uploading something unplayable) if it can't. */
export async function prepareVideo(
  file: Blob,
  kind: MediaKind,
  onProgress?: (fraction: number) => void,
  opts: { trim?: { start: number; end: number }; crop?: { left: number; top: number; width: number; height: number } } = {},
): Promise<Prepared> {
  const { trim, crop } = opts;
  const spec = VIDEO_SPEC[kind];
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS });
  const track = await input.getPrimaryVideoTrack();
  if (!track) throw new UnsupportedMediaError('That file has no video in it.');
  const duration = await input.computeDuration();
  const end = Math.min(trim?.end ?? duration, (trim?.start ?? 0) + spec.maxSeconds);
  const { width, height } = fitWithin(crop?.width ?? track.displayWidth, crop?.height ?? track.displayHeight, spec.maxEdge);
  if (!(await canEncodeVideo('avc', { width, height, bitrate: spec.bitrate }))) {
    throw new UnsupportedMediaError('This browser cannot encode H.264 video. Use Chrome or Edge, or post it from the Android app.');
  }
  const hasAudio = !!(await input.getPrimaryAudioTrack());
  if (hasAudio && !(await canEncodeAudio('aac'))) {
    throw new UnsupportedMediaError('This browser cannot encode AAC audio. Use Chrome or Edge, or post it from the Android app.');
  }
  const output = new Output({ format: new Mp4OutputFormat({ fastStart: 'in-memory' }), target: new BufferTarget() });
  const conversion = await Conversion.init({
    input,
    output,
    tracks: 'primary',
    video: { width, height, fit: 'contain', codec: 'avc', quality: new Quality(spec.bitrate), frameRate: 30, forceTranscode: true, ...(crop ? { crop } : {}) },
    audio: { codec: 'aac', quality: new Quality(128_000), numberOfChannels: 2, sampleRate: 48_000, forceTranscode: true },
    trim: { start: trim?.start ?? 0, end },
    showWarnings: false,
  });
  if (!conversion.isValid) throw new UnsupportedMediaError('That video cannot be converted in this browser.');
  conversion.onProgress = (progress) => onProgress?.(progress);
  await conversion.execute();
  const buffer = (output.target as BufferTarget).buffer!;
  const blob = new Blob([buffer], { type: 'video/mp4' });

  // Poster: a frame a little way in, as a JPEG.
  const sink = new CanvasSink(track, { width, height, fit: 'contain' });
  const frame = await sink.getCanvas(Math.min(0.5, (end - (trim?.start ?? 0)) / 2) + (trim?.start ?? 0));
  let poster: Prepared | undefined;
  if (frame) {
    const canvas = frame.canvas as OffscreenCanvas | HTMLCanvasElement;
    const posterBlob =
      'convertToBlob' in canvas
        ? await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.8 })
        : await new Promise<Blob>((resolve) => (canvas as HTMLCanvasElement).toBlob((b) => resolve(b!), 'image/jpeg', 0.8));
    poster = { blob: posterBlob, mime: 'image/jpeg', width, height, blurhash: await blurhashOf(canvas, width, height) };
  }
  return { blob, mime: 'video/mp4', width, height, durationMs: Math.round((end - (trim?.start ?? 0)) * 1000), blurhash: poster?.blurhash, poster };
}

export async function prepare(file: File | Blob, kind: MediaKind, onProgress?: (f: number) => void): Promise<Prepared> {
  if (file.type.startsWith('video/')) return prepareVideo(file, kind, onProgress);
  if (file.type.startsWith('audio/')) return { blob: file, mime: file.type || 'audio/mp4', width: 0, height: 0 };
  return prepareImage(file, kind);
}

/** Encrypts (AGM1) while uploading in resumable pieces; memory stays bounded however large the file is. */
async function uploadEncrypted(blob: Blob, onProgress?: (fraction: number) => void): Promise<{ blobId: string; key: Uint8Array }> {
  const key = newFileKey();
  const header = newHeader(blob.size);
  const total = cipherLength(blob.size);
  const start = await api.post<UploadState>('/uploads', { size: total });
  const pieceTarget = Math.min(start.chunkSize, 4 * 1024 * 1024);
  let sent = 0;
  let parts: Uint8Array[] = [header.bytes];
  let buffered = header.bytes.length;

  const flush = async () => {
    const body = concat(...parts);
    for (let attempt = 0; ; attempt++) {
      try {
        const state = await api.raw<UploadState>('PUT', `/uploads/${start.uploadId}?offset=${sent}`, body);
        sent = state.received;
        break;
      } catch (error) {
        if (attempt >= 4) throw error;
        if (error instanceof ApiError && error.code === 'offset') {
          const state = await api.get<UploadState>(`/uploads/${start.uploadId}`);
          if (state.received === sent + body.length) {
            sent = state.received;
            break;
          }
        }
        await new Promise((r) => setTimeout(r, 1000 * 2 ** attempt));
      }
    }
    parts = [];
    buffered = 0;
    onProgress?.(sent / total);
  };

  const count = chunkCount(blob.size);
  const readSize = DEFAULT_CHUNK * 32;
  for (let base = 0; base < count; base += 32) {
    const slice = new Uint8Array(await blob.slice(base * DEFAULT_CHUNK, Math.min(blob.size, base * DEFAULT_CHUNK + readSize)).arrayBuffer());
    for (let i = base; i < Math.min(count, base + 32); i++) {
      const offset = (i - base) * DEFAULT_CHUNK;
      const sealed = sealChunk(header, i, slice.subarray(offset, Math.min(slice.length, offset + DEFAULT_CHUNK)), key);
      parts.push(sealed);
      buffered += sealed.length;
      if (buffered >= pieceTarget) await flush();
    }
  }
  if (buffered > 0) await flush();
  const done = await api.post<BlobDto>(`/uploads/${start.uploadId}/complete`);
  return { blobId: done.blobId, key };
}

export async function upload(prepared: Prepared, onProgress?: (fraction: number) => void, alt?: string): Promise<MediaRef> {
  const posterRef = prepared.poster ? await upload(prepared.poster) : null;
  const { blobId, key } = await uploadEncrypted(prepared.blob, onProgress);
  return {
    blobId,
    key: b64(key),
    mime: prepared.mime,
    size: prepared.blob.size,
    width: prepared.width,
    height: prepared.height,
    durationMs: prepared.durationMs ?? null,
    blurhash: prepared.blurhash ?? null,
    poster: posterRef,
    alt: alt || null,
  };
}

// ----------------------------------------------------------------------------------------------------- download

const urls = new Map<string, Promise<string>>();
const order: string[] = [];
const MAX_URLS = 300;

/** A blob: URL with the decrypted file. Cached; the browser's HTTP cache keeps the ciphertext. */
export function mediaUrl(ref: MediaRef): Promise<string> {
  const cached = urls.get(ref.blobId);
  if (cached) return cached;
  const promise = (async () => {
    const plain = decryptMedia(await fetchBlob(ref.blobId), unb64(ref.key));
    return URL.createObjectURL(new Blob([plain as BlobPart], { type: ref.mime }));
  })();
  urls.set(ref.blobId, promise);
  order.push(ref.blobId);
  promise.catch(() => urls.delete(ref.blobId));
  while (order.length > MAX_URLS) {
    const evict = order.shift()!;
    urls.get(evict)?.then((u) => URL.revokeObjectURL(u));
    urls.delete(evict);
  }
  return promise;
}

/**
 * Video: if our service worker controls the page, it decrypts on the fly with Range support, so playback starts
 * immediately and seeking works. Otherwise, download and decrypt the whole file.
 */
export async function videoUrl(ref: MediaRef): Promise<string> {
  const controller = navigator.serviceWorker?.controller;
  if (controller) {
    const ack = new MessageChannel();
    const ready = new Promise<void>((resolve) => (ack.port1.onmessage = () => resolve()));
    controller.postMessage({ type: 'media-key', blobId: ref.blobId, key: ref.key, mime: ref.mime, token: getToken() }, [ack.port2]);
    await Promise.race([ready, new Promise((r) => setTimeout(r, 1500))]);
    return `/media/${ref.blobId}`;
  }
  return mediaUrl(ref);
}

export function bytesLabel(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(0)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(1)} GB`;
}

/** Voice notes: AAC in MP4 where the browser can encode it (Android plays WebM/Opus too, as a fallback). */
export async function prepareVoice(blob: Blob, durationMs: number): Promise<Prepared> {
  try {
    if (await canEncodeAudio('aac')) {
      const input = new Input({ source: new BlobSource(blob), formats: ALL_FORMATS });
      const output = new Output({ format: new Mp4OutputFormat({ fastStart: 'in-memory' }), target: new BufferTarget() });
      const conversion = await Conversion.init({ input, output, video: { discard: true }, audio: { codec: 'aac', quality: new Quality(64_000), numberOfChannels: 1, forceTranscode: true }, showWarnings: false });
      if (conversion.isValid) {
        await conversion.execute();
        return { blob: new Blob([(output.target as BufferTarget).buffer!], { type: 'audio/mp4' }), mime: 'audio/mp4', width: 0, height: 0, durationMs };
      }
    }
  } catch {
    /* fall through */
  }
  return { blob, mime: blob.type || 'audio/webm', width: 0, height: 0, durationMs };
}
