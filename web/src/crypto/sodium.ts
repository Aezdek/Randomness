import sodiumModule from 'libsodium-wrappers-sumo';

/**
 * libsodium (WebAssembly). Call `await initSodium()` once at startup; every function in this folder assumes it ran.
 * Byte-for-byte compatible with shared/crypto on Android and the server tests (see crypto-vectors/).
 */
export const sodium = sodiumModule;

let readyPromise: Promise<void> | null = null;

export function initSodium(): Promise<void> {
  readyPromise ??= sodiumModule.ready;
  return readyPromise;
}

export class CryptoError extends Error {}

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export const utf8 = (text: string): Uint8Array => encoder.encode(text);
export const fromUtf8 = (bytes: Uint8Array): string => decoder.decode(bytes);

export function b64(bytes: Uint8Array): string {
  return sodium.to_base64(bytes, sodium.base64_variants.URLSAFE_NO_PADDING);
}

export function unb64(text: string): Uint8Array {
  return sodium.from_base64(text, sodium.base64_variants.URLSAFE_NO_PADDING);
}

export function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

export function u64(value: number | bigint): Uint8Array {
  const out = new Uint8Array(8);
  new DataView(out.buffer).setBigUint64(0, BigInt(value));
  return out;
}

export function u32(value: number): Uint8Array {
  const out = new Uint8Array(4);
  new DataView(out.buffer).setUint32(0, value);
  return out;
}

export const random = (size: number): Uint8Array => sodium.randombytes_buf(size);

export function blake2b(size: number, message: Uint8Array, key: Uint8Array | null = null): Uint8Array {
  return sodium.crypto_generichash(size, message, key);
}

export function equal(a: Uint8Array, b: Uint8Array): boolean {
  return a.length === b.length && sodium.memcmp(a, b);
}

export const toHex = (bytes: Uint8Array): string => sodium.to_hex(bytes);
