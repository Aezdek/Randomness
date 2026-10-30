import { openDB } from 'idb';
import type { AccountKeys } from '../crypto/account';
import { concat, sodium } from '../crypto/sodium';
import type { Me } from './types';

/**
 * The signed-in session on this browser. The account's private keys are kept wrapped by a *non-extractable*
 * WebCrypto AES key that lives in IndexedDB: script on this origin can use it but never read it out, and it never
 * leaves this browser profile.
 */
interface StoredSession {
  token: string;
  me: Me;
  wrappedKeys: ArrayBuffer;
  iv: Uint8Array;
  deviceKey: CryptoKey;
}

const DB = 'aezgram-session';

async function store() {
  return openDB(DB, 1, {
    upgrade(db) {
      db.createObjectStore('session');
    },
  });
}

export async function saveSession(token: string, me: Me, keys: AccountKeys) {
  const deviceKey = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plain = concat(keys.signSk, keys.boxSk);
  const wrappedKeys = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, deviceKey, plain as BufferSource);
  sodium.memzero(plain);
  const db = await store();
  await db.put('session', { token, me, wrappedKeys, iv, deviceKey } satisfies StoredSession, 'current');
  db.close();
}

export async function updateStoredMe(me: Me) {
  const db = await store();
  const current = (await db.get('session', 'current')) as StoredSession | undefined;
  if (current) await db.put('session', { ...current, me }, 'current');
  db.close();
}

export async function loadSession(): Promise<{ token: string; me: Me; keys: AccountKeys } | null> {
  const db = await store();
  const current = (await db.get('session', 'current')) as StoredSession | undefined;
  db.close();
  if (!current) return null;
  try {
    const plain = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: current.iv as BufferSource }, current.deviceKey, current.wrappedKeys));
    const signSk = plain.slice(0, 64);
    const boxSk = plain.slice(64, 96);
    sodium.memzero(plain);
    const keys: AccountKeys = {
      signSk,
      boxSk,
      signPk: sodium.crypto_sign_ed25519_sk_to_pk(signSk),
      boxPk: sodium.crypto_scalarmult_base(boxSk),
    };
    return { token: current.token, me: current.me, keys };
  } catch {
    return null;
  }
}

export async function clearSession() {
  const db = await store();
  await db.delete('session', 'current');
  db.close();
}
