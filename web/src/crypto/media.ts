import { CryptoError, concat, sodium, u32, u64, utf8 } from './sodium';

/**
 * AezCrypt chunked media encryption, mirroring MediaCipher.kt:
 *   header  = "AezCrypt" || u32 chunkSize || u64 plainLength || random(12)        (32 bytes)
 *   chunk i = XChaCha20-Poly1305(plain_i, nonce = random(12) || 0(4) || u64 i, ad = header || u64 i || u8 isFinal)
 * Files from before the name changed start with "AGM1" (… || random(16), nonce = random(16) || u64 i) and still open.
 */
export const HEADER_BYTES = 32;
export const DEFAULT_CHUNK = 64 * 1024;
export const TAG_BYTES = 16;
const MAGIC = utf8('AezCrypt');
const LEGACY_MAGIC = utf8('AGM1');

export interface MediaHeader {
  chunkSize: number;
  plainLength: number;
  /** The 16 bytes every chunk nonce starts with (AezCrypt: the 12 random ones and 4 zeros). */
  noncePrefix: Uint8Array;
  bytes: Uint8Array;
}

export const newFileKey = (): Uint8Array => sodium.randombytes_buf(32);

export function chunkCount(plainLength: number, chunkSize = DEFAULT_CHUNK): number {
  return plainLength === 0 ? 1 : Math.ceil(plainLength / chunkSize);
}

export function cipherLength(plainLength: number, chunkSize = DEFAULT_CHUNK): number {
  return HEADER_BYTES + plainLength + chunkCount(plainLength, chunkSize) * TAG_BYTES;
}

export function plainChunkLength(header: MediaHeader, index: number): number {
  const count = chunkCount(header.plainLength, header.chunkSize);
  return index === count - 1 ? header.plainLength - index * header.chunkSize : header.chunkSize;
}

/** [start, endExclusive) of chunk `index` in the ciphertext. */
export function cipherRange(header: MediaHeader, index: number): [number, number] {
  const start = HEADER_BYTES + index * (header.chunkSize + TAG_BYTES);
  return [start, start + plainChunkLength(header, index) + TAG_BYTES];
}

export function parseHeader(bytes: Uint8Array): MediaHeader {
  if (bytes.length < HEADER_BYTES) throw new CryptoError('not an AezCrypt file');
  const legacy = LEGACY_MAGIC.every((b, i) => bytes[i] === b);
  if (!legacy && !MAGIC.every((b, i) => bytes[i] === b)) throw new CryptoError('not an AezCrypt file');
  const view = new DataView(bytes.buffer, bytes.byteOffset, HEADER_BYTES);
  const at = legacy ? 4 : 8;
  const chunkSize = view.getUint32(at);
  const plainLength = Number(view.getBigUint64(at + 4));
  const noncePrefix = legacy ? bytes.slice(16, 32) : concat(bytes.slice(20, 32), new Uint8Array(4));
  return { chunkSize, plainLength, noncePrefix, bytes: bytes.slice(0, HEADER_BYTES) };
}

function chunkAd(header: MediaHeader, index: number): Uint8Array {
  const isFinal = index === chunkCount(header.plainLength, header.chunkSize) - 1 ? 1 : 0;
  return concat(header.bytes, u64(index), new Uint8Array([isFinal]));
}

const nonceFor = (header: MediaHeader, index: number) => concat(header.noncePrefix, u64(index));

export function sealChunk(header: MediaHeader, index: number, plain: Uint8Array, key: Uint8Array): Uint8Array {
  return sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(plain, chunkAd(header, index), null, nonceFor(header, index), key);
}

export function openChunk(header: MediaHeader, index: number, cipher: Uint8Array, key: Uint8Array): Uint8Array {
  const count = chunkCount(header.plainLength, header.chunkSize);
  if (index < 0 || index >= count) throw new CryptoError(`chunk ${index} out of range`);
  if (cipher.length !== plainChunkLength(header, index) + TAG_BYTES) throw new CryptoError(`chunk ${index} has the wrong length`);
  try {
    return sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(null, cipher, chunkAd(header, index), nonceFor(header, index), key);
  } catch {
    throw new CryptoError(`chunk ${index} did not decrypt`);
  }
}

export function newHeader(plainLength: number, chunkSize = DEFAULT_CHUNK): MediaHeader {
  const random = sodium.randombytes_buf(12);
  const bytes = concat(MAGIC, u32(chunkSize), u64(plainLength), random);
  return { chunkSize, plainLength, noncePrefix: concat(random, new Uint8Array(4)), bytes };
}

export function encryptMedia(plain: Uint8Array, key: Uint8Array, chunkSize = DEFAULT_CHUNK): Uint8Array {
  const header = newHeader(plain.length, chunkSize);
  const out = new Uint8Array(cipherLength(plain.length, chunkSize));
  out.set(header.bytes, 0);
  const count = chunkCount(plain.length, chunkSize);
  for (let i = 0; i < count; i++) {
    const start = i * chunkSize;
    const sealed = sealChunk(header, i, plain.subarray(start, Math.min(start + chunkSize, plain.length)), key);
    out.set(sealed, cipherRange(header, i)[0]);
  }
  return out;
}

export function decryptMedia(cipher: Uint8Array, key: Uint8Array): Uint8Array {
  const header = parseHeader(cipher);
  if (cipher.length !== cipherLength(header.plainLength, header.chunkSize)) throw new CryptoError('AezCrypt file has the wrong length');
  const out = new Uint8Array(header.plainLength);
  const count = chunkCount(header.plainLength, header.chunkSize);
  for (let i = 0; i < count; i++) {
    const [start, end] = cipherRange(header, i);
    out.set(openChunk(header, i, cipher.subarray(start, end), key), i * header.chunkSize);
  }
  return out;
}
