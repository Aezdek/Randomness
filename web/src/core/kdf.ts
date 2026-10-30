import type { KdfParams } from '../crypto/password';
import { derivePasswordKeys } from '../crypto/password';

/** Runs Argon2id in a worker (falls back to the main thread where workers are unavailable). */
export function deriveInWorker(password: string, salt: Uint8Array, params: KdfParams): Promise<{ authKey: Uint8Array; vaultKey: Uint8Array }> {
  if (typeof Worker === 'undefined') return Promise.resolve(derivePasswordKeys(password, salt, params));
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL('../workers/kdf.worker.ts', import.meta.url), { type: 'module' });
    worker.onmessage = (event) => {
      worker.terminate();
      if (event.data.error) reject(new Error(event.data.error));
      else resolve(event.data);
    };
    worker.onerror = (event) => {
      worker.terminate();
      reject(new Error(event.message || 'Key derivation failed'));
    };
    worker.postMessage({ password, salt, params });
  });
}
