import { sodium, utf8, toHex } from './sodium';

export interface KdfParams {
  opsLimit: number;
  memLimit: number;
}

export const DEFAULT_KDF: KdfParams = { opsLimit: 3, memLimit: 64 * 1024 * 1024 };
export const SALT_BYTES = 16;

/** Lowercase hex of the NFKC-normalised password's UTF-8 bytes: the exact Argon2id input on every platform. */
export function argonInput(password: string): string {
  return toHex(utf8(password.normalize('NFKC')));
}

/** Password → (authKey for the server, vaultKey that never leaves the device). Mirrors PasswordKeys.kt. */
export function derivePasswordKeys(password: string, salt: Uint8Array, params: KdfParams) {
  if (salt.length !== SALT_BYTES) throw new Error('salt must be 16 bytes');
  const master = sodium.crypto_pwhash(
    32,
    argonInput(password),
    salt,
    params.opsLimit,
    params.memLimit,
    sodium.crypto_pwhash_ALG_ARGON2ID13,
  );
  const authKey = sodium.crypto_kdf_derive_from_key(32, 1, 'aezgauth', master);
  const vaultKey = sodium.crypto_kdf_derive_from_key(32, 2, 'aezgvalt', master);
  sodium.memzero(master);
  return { authKey, vaultKey };
}

export const newSalt = (): Uint8Array => sodium.randombytes_buf(SALT_BYTES);
