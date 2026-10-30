/// <reference lib="webworker" />
import { derivePasswordKeys, type KdfParams } from '../crypto/password';
import { initSodium } from '../crypto/sodium';

// Argon2id with 64 MiB takes about a second; doing it here keeps the interface responsive.
self.onmessage = async (event: MessageEvent<{ password: string; salt: Uint8Array; params: KdfParams }>) => {
  await initSodium();
  try {
    const { authKey, vaultKey } = derivePasswordKeys(event.data.password, event.data.salt, event.data.params);
    (self as unknown as Worker).postMessage({ authKey, vaultKey });
  } catch (error) {
    (self as unknown as Worker).postMessage({ error: (error as Error).message });
  }
};
