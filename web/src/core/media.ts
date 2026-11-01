import { encode as encodeBlurhash } from 'blurhash';
import { chunkCount, cipherLength, DEFAULT_CHUNK, decryptMedia, newFileKey, newHeader, sealChunk } from '../crypto/media';
import { b64, concat, unb64 } from '../crypto/sodium';
import { api, fetchBlob, getToken } from './api';
import { ApiError } from './errors';
import { toast } from './store';
import type { InputAudioTrack } from 'mediabunny';
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
  /** The video had sound this browser can't read (such as an iPhone's Spatial Audio), so it was left out. */
  silent?: boolean;
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

let codecs: Promise<typeof import('mediabunny')> | null = null;
let bundledAac = false;

/** Switches every later AAC encode to the bundled WebAssembly encoder (it takes precedence over the browser's own). */
async function useBundledAac() {
  if (bundledAac) return;
  const { registerAacEncoder } = await import('@mediabunny/aac-encoder');
  registerAacEncoder();
  bundledAac = true;
}

/**
 * Mediabunny (≈1 MB) only loads when someone actually picks a video or records a voice note. Browsers whose
 * WebCodecs can't encode AAC (Firefox, Chromium builds without proprietary codecs) get FFmpeg's AAC encoder as
 * WebAssembly instead, so every client still produces the same H.264/AAC MP4 the Android app plays natively.
 */
function loadCodecs() {
  codecs ??= (async () => {
    const mediabunny = await import('mediabunny');
    if (!(await mediabunny.canEncodeAudio('aac'))) await useBundledAac();
    return mediabunny;
  })();
  codecs.catch(() => (codecs = null));
  return codecs;
}

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
/** iPhone photos (HEIC/HEIF), by type or, since some systems leave the type blank, by name. */
export function isHeic(file: Blob): boolean {
  return /^image\/hei[cf]/i.test(file.type) || (file instanceof File && /\.(heic|heif)$/i.test(file.name));
}

/**
 * Decodes a HEIC/HEIF photo in browsers that can't (Chrome and Firefox on Windows, Android and Linux) with libheif,
 * compiled to WebAssembly and only downloaded the first time someone picks such a photo.
 */
async function decodeHeic(file: Blob): Promise<OffscreenCanvas> {
  const mod = (await import('libheif-js/libheif-wasm/libheif-bundle.mjs')) as { default: unknown };
  const libheif = (typeof mod.default === 'function' ? await (mod.default as () => unknown)() : mod.default) as LibHeif;
  const decoder = new libheif.HeifDecoder();
  const images = decoder.decode(new Uint8Array(await file.arrayBuffer()));
  if (!images?.length) throw new UnsupportedMediaError('That photo can’t be opened.');
  const image = images[0];
  const width = image.get_width();
  const height = image.get_height();
  const canvas = new OffscreenCanvas(width, height);
  const ctx = canvas.getContext('2d')!;
  const pixels = ctx.createImageData(width, height);
  await new Promise<void>((resolve, reject) => image.display(pixels, (done) => (done ? resolve() : reject(new UnsupportedMediaError('That photo can’t be opened.')))));
  ctx.putImageData(pixels, 0, 0);
  for (const img of images) img.free?.();
  return canvas;
}

interface LibHeif {
  HeifDecoder: new () => {
    decode(data: Uint8Array): {
      get_width(): number;
      get_height(): number;
      display(target: ImageData, done: (result: ImageData | null) => void): void;
      free?(): void;
    }[];
  };
}

/** A photo every part of the app can open: HEIC that this browser can't read becomes a JPEG at full quality. */
export async function openablePhoto(file: File): Promise<File> {
  if (!isHeic(file)) return file;
  try {
    (await createImageBitmap(file)).close();
    return file;
  } catch {
    const jpeg = await (await decodeHeic(file)).convertToBlob({ type: 'image/jpeg', quality: 0.95 });
    return new File([jpeg], file.name.replace(/\.(heic|heif)$/i, '') + '.jpg', { type: 'image/jpeg' });
  }
}

export async function prepareImage(
  file: Blob,
  kind: MediaKind,
  edit?: { crop?: { x: number; y: number; width: number; height: number }; filter?: string; vignette?: number },
): Promise<Prepared> {
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
  } catch {
    if (!isHeic(file)) throw new UnsupportedMediaError('This browser can’t open that photo. Try a JPEG, PNG or HEIC, or use the Android app.');
    bitmap = await createImageBitmap(await decodeHeic(file));
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
  const { ALL_FORMATS, BlobSource, BufferTarget, CanvasSink, Conversion, Input, Mp4OutputFormat, Output, Quality, canEncodeAudio, canEncodeVideo } = await loadCodecs();
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS });
  const track = await input.getPrimaryVideoTrack();
  if (!track) throw new UnsupportedMediaError('That file has no video in it.');
  if (!(await track.canDecode())) {
    throw new UnsupportedMediaError(
      `This browser can't read this video's format (${track.codec ?? 'unknown'}; iPhones record HEVC). Try Safari, or Chrome on a Mac, or post it from the Android app.`,
    );
  }
  const duration = await input.computeDuration();
  const end = Math.min(trim?.end ?? duration, (trim?.start ?? 0) + spec.maxSeconds);
  const { width, height } = fitWithin(crop?.width ?? track.displayWidth, crop?.height ?? track.displayHeight, spec.maxEdge);
  if (!(await canEncodeVideo('avc', { width, height, quality: new Quality({ bitrate: spec.bitrate }) }))) {
    throw new UnsupportedMediaError('This browser cannot encode H.264 video. Use Chrome or Edge, or post it from the Android app.');
  }
  // iPhones can record Spatial Audio (Apple's APAC), alongside or instead of plain AAC. Use the first sound track this
  // browser can decode; with none, the video goes up without sound rather than not at all.
  const audioTracks = await input.getAudioTracks();
  let audioTrack: InputAudioTrack | null = null;
  for (const candidate of audioTracks) {
    if (await candidate.canDecode()) {
      audioTrack = candidate;
      break;
    }
  }
  const silent = audioTracks.length > 0 && !audioTrack;
  if (silent) toast("This browser can't read that video's sound (iPhone Spatial Audio, for example), so it goes up without sound.");
  if (audioTrack && !(await canEncodeAudio('aac'))) {
    throw new UnsupportedMediaError('This browser cannot encode audio for video. Use Chrome or Edge, or post it from the Android app.');
  }

  const convert = async (withSound: boolean) => {
    const target = new BufferTarget();
    const output = new Output({ format: new Mp4OutputFormat({ fastStart: 'in-memory' }), target });
    const conversion = await Conversion.init({
      input,
      output,
      video: (t) =>
        t === track
          ? { width, height, fit: 'contain', codec: 'avc', quality: new Quality({ bitrate: spec.bitrate }), frameRate: 30, forceTranscode: true, ...(crop ? { crop } : {}) }
          : { discard: true },
      audio: (t) =>
        t === audioTrack && withSound
          ? { codec: 'aac', quality: new Quality({ bitrate: 128_000 }), numberOfChannels: 2, sampleRate: 48_000, forceTranscode: true }
          : { discard: true },
      trim: { start: trim?.start ?? 0, end },
      showWarnings: false,
    });
    // Mediabunny drops a track it can't handle and carries on; a reel without its picture (or chosen sound) is not one.
    const lost = conversion.discardedTracks.find((d) => d.reason !== 'discarded_by_user');
    if (!conversion.isValid || lost) {
      throw new UnsupportedMediaError(
        `This browser can't convert that video${lost ? ` (${lost.track.type}: ${lost.reason.replaceAll('_', ' ')})` : ''}. Try Safari or Chrome, or post it from the Android app.`,
      );
    }
    conversion.onProgress = (progress) => onProgress?.(progress);
    await conversion.execute();
    return target.buffer!;
  };

  let buffer: ArrayBuffer;
  let soundless = silent;
  try {
    buffer = await convert(true);
  } catch (error) {
    if (error instanceof UnsupportedMediaError || !audioTrack) throw failedToConvert(error);
    // Some browsers' own AAC encoders accept a setup and then fail part-way ("The given encoding is not supported").
    // The bundled encoder doesn't, so try once more with it; and if the sound itself is what fails (an iPhone track
    // the browser claims to read but can't), the video still goes up, without sound, rather than not at all.
    let done: ArrayBuffer | null = null;
    if (!bundledAac) {
      await useBundledAac();
      onProgress?.(0);
      done = await convert(true).catch(() => null);
    }
    if (!done) {
      onProgress?.(0);
      done = await convert(false).catch((e: unknown) => {
        throw failedToConvert(e);
      });
      soundless = true;
      toast("This browser couldn't convert that video's sound, so it goes up without sound.");
    }
    buffer = done;
  }
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
  return { blob, mime: 'video/mp4', width, height, durationMs: Math.round((end - (trim?.start ?? 0)) * 1000), blurhash: poster?.blurhash, poster, silent: soundless };
}

function failedToConvert(error: unknown) {
  if (error instanceof UnsupportedMediaError) return error;
  const reason = error instanceof Error ? error.message : String(error);
  return new UnsupportedMediaError(`This browser couldn't convert that video (${reason}). Try Safari or Chrome, or post it from the Android app.`);
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
  // Big pieces mean fewer round trips through the tunnel; the server says how big it takes (older ones take 8 MB).
  const pieceTarget = Math.min(start.chunkSize, 16 * 1024 * 1024);
  let sent = 0;
  let parts: Uint8Array[] = [header.bytes];
  let buffered = header.bytes.length;

  const put = async (body: Uint8Array) => {
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
    onProgress?.(sent / total);
  };
  // One piece uploads while the next is encrypted: the network never waits for the CPU. Pieces still go in order,
  // and at most two exist at once, so memory stays bounded.
  let inFlight: Promise<void> = Promise.resolve();
  const flush = async () => {
    const body = concat(...parts);
    parts = [];
    buffered = 0;
    await inFlight;
    inFlight = put(body);
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
  await inFlight;
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

/** Voice notes: mono AAC in MP4 (the raw WebM/Opus recording is the fallback; Android plays that too). */
/** Whether a recording has any sound in it (false only when it decodes to silence; true when it can't be told). */
async function audible(blob: Blob): Promise<boolean> {
  try {
    const ctx = new OfflineAudioContext(1, 1, 44_100);
    const audio = await ctx.decodeAudioData(await blob.arrayBuffer());
    const samples = audio.getChannelData(0);
    let peak = 0;
    for (let i = 0; i < samples.length; i += 32) peak = Math.max(peak, Math.abs(samples[i]));
    return peak > 0.002;
  } catch {
    return true;
  }
}

/**
 * Voice notes go out as AAC in MP4, which every browser and phone plays. Recorders that already make that (Safari's,
 * the Android app's) are sent as recorded: converting again adds nothing, and Safari's own audio decoder has turned
 * recordings silent along the way. Others (Chrome and Firefox record Opus) are converted, and the copy is only used
 * if it still has sound in it.
 */
export async function prepareVoice(blob: Blob, durationMs: number): Promise<Prepared> {
  const recorded = { blob, mime: blob.type.split(';')[0] || 'audio/webm', width: 0, height: 0, durationMs };
  if (/^audio\/(mp4|aac|x-m4a|m4a)/.test(blob.type)) return { ...recorded, mime: 'audio/mp4' };
  try {
    const { ALL_FORMATS, BlobSource, BufferTarget, Conversion, Input, Mp4OutputFormat, Output, Quality, canEncodeAudio } = await loadCodecs();
    if (await canEncodeAudio('aac')) {
      const input = new Input({ source: new BlobSource(blob), formats: ALL_FORMATS });
      const target = new BufferTarget();
      const output = new Output({ format: new Mp4OutputFormat({ fastStart: 'in-memory' }), target });
      const conversion = await Conversion.init({ input, output, video: { discard: true }, audio: { codec: 'aac', quality: new Quality({ bitrate: 64_000 }), numberOfChannels: 1, forceTranscode: true }, showWarnings: false });
      if (conversion.isValid) {
        await conversion.execute();
        const converted = new Blob([target.buffer!], { type: 'audio/mp4' });
        if ((await audible(converted)) || !(await audible(blob))) return { blob: converted, mime: 'audio/mp4', width: 0, height: 0, durationMs };
      }
    }
  } catch {
    /* send it as recorded */
  }
  return recorded;
}
