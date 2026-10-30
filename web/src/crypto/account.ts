import { CryptoError, concat, sodium, utf8 } from './sodium';

export interface PublicKeys {
  signPk: Uint8Array;
  boxPk: Uint8Array;
  boxPkSig: Uint8Array;
}

export interface AccountKeys {
  signPk: Uint8Array;
  signSk: Uint8Array;
  boxPk: Uint8Array;
  boxSk: Uint8Array;
}

const VAULT_AD = utf8('aezgram/v1/vault');
const VAULT_VERSION = 1;

const boxKeyMessage = (boxPk: Uint8Array) => concat(utf8('aezgram/v1/boxkey\n'), boxPk);

export function generateAccountKeys(): AccountKeys {
  const sign = sodium.crypto_sign_keypair();
  const box = sodium.crypto_box_keypair();
  return { signPk: sign.publicKey, signSk: sign.privateKey, boxPk: box.publicKey, boxSk: box.privateKey };
}

/** Deterministic keys for test vectors only. */
export function accountKeysFromSeeds(signSeed: Uint8Array, boxSeed: Uint8Array): AccountKeys {
  const sign = sodium.crypto_sign_seed_keypair(signSeed);
  const box = sodium.crypto_box_seed_keypair(boxSeed);
  return { signPk: sign.publicKey, signSk: sign.privateKey, boxPk: box.publicKey, boxSk: box.privateKey };
}

export function publicKeysOf(keys: AccountKeys): PublicKeys {
  return { signPk: keys.signPk, boxPk: keys.boxPk, boxPkSig: sign(boxKeyMessage(keys.boxPk), keys.signSk) };
}

/** The box key must be signed by the identity key, or the server could have swapped in its own. */
export function isSelfSigned(pub: PublicKeys): boolean {
  return verify(boxKeyMessage(pub.boxPk), pub.boxPkSig, pub.signPk);
}

export function sealVault(keys: AccountKeys, vaultKey: Uint8Array): Uint8Array {
  const nonce = sodium.randombytes_buf(24);
  const plain = concat(new Uint8Array([VAULT_VERSION]), keys.signSk, keys.boxSk);
  const sealed = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(plain, VAULT_AD, null, nonce, vaultKey);
  sodium.memzero(plain);
  return concat(nonce, sealed);
}

export function openVault(vault: Uint8Array, vaultKey: Uint8Array): AccountKeys {
  if (vault.length < 40) throw new CryptoError('vault too short');
  let plain: Uint8Array;
  try {
    plain = sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(null, vault.subarray(24), VAULT_AD, vault.subarray(0, 24), vaultKey);
  } catch {
    throw new CryptoError('Wrong password');
  }
  if (plain.length !== 97 || plain[0] !== VAULT_VERSION) throw new CryptoError('unknown vault format');
  const signSk = plain.slice(1, 65);
  const boxSk = plain.slice(65, 97);
  sodium.memzero(plain);
  return {
    signPk: sodium.crypto_sign_ed25519_sk_to_pk(signSk),
    signSk,
    boxPk: sodium.crypto_scalarmult_base(boxSk),
    boxSk,
  };
}

export function sign(message: Uint8Array, signSk: Uint8Array): Uint8Array {
  return sodium.crypto_sign_detached(message, signSk);
}

export function verify(message: Uint8Array, signature: Uint8Array, signPk: Uint8Array): boolean {
  if (signature.length !== 64 || signPk.length !== 32) return false;
  try {
    return sodium.crypto_sign_verify_detached(signature, message, signPk);
  } catch {
    return false;
  }
}
