import { verify, sign, type AccountKeys } from './account';
import { CryptoError, b64, blake2b, concat, equal, sodium, utf8 } from './sodium';

/** Server-visible fields of an object; all covered by the author's signature. Mirrors ObjectCrypto.kt. */
export interface ObjectHeader {
  id: string;
  kind: string;
  authorId: string;
  parentId: string | null;
  threadId: string | null;
  audience: string;
  createdAt: number;
  expiresAt: number | null;
  version: number;
  blobIds: string[];
  refIds: string[];
}

export interface SealedObject {
  header: ObjectHeader;
  payload: Uint8Array;
  commitment: Uint8Array;
  signature: Uint8Array;
}

export const newContentKey = (): Uint8Array => sodium.randombytes_buf(32);

export function commitment(contentKey: Uint8Array): Uint8Array {
  return blake2b(32, concat(utf8('aezgram/v1/commit\n'), contentKey));
}

/** Key of a child object (like, comment, message…) from its parent's key. */
export function childKey(parentKey: Uint8Array, childId: string): Uint8Array {
  return blake2b(32, utf8(`aezgram/v1/child\n${childId}`), parentKey);
}

const payloadAd = (id: string, version: number) => utf8(`aezgram/v1/payload\n${id}\n${version}`);

export function encryptPayload(id: string, version: number, plaintext: Uint8Array, key: Uint8Array): Uint8Array {
  const nonce = sodium.randombytes_buf(24);
  return concat(nonce, sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(plaintext, payloadAd(id, version), null, nonce, key));
}

export function decryptPayload(id: string, version: number, payload: Uint8Array, key: Uint8Array): Uint8Array {
  if (payload.length < 40) throw new CryptoError('payload too short');
  try {
    return sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(null, payload.subarray(24), payloadAd(id, version), payload.subarray(0, 24), key);
  } catch {
    throw new CryptoError(`payload of ${id} did not decrypt`);
  }
}

const sortedJoin = (ids: string[]) => [...ids].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)).join(',');

export function signingInput(header: ObjectHeader, payload: Uint8Array, commit: Uint8Array): Uint8Array {
  return utf8(
    [
      'aezgram/v1/object',
      header.id,
      header.kind,
      header.authorId,
      header.parentId ?? '',
      header.threadId ?? '',
      header.audience,
      String(header.createdAt),
      header.expiresAt == null ? '' : String(header.expiresAt),
      String(header.version),
      sortedJoin(header.blobIds),
      sortedJoin(header.refIds),
      b64(blake2b(32, payload)),
      b64(commit),
    ].join('\n'),
  );
}

export function sealObject(header: ObjectHeader, plaintext: Uint8Array, key: Uint8Array, author: AccountKeys): SealedObject {
  const payload = encryptPayload(header.id, header.version, plaintext, key);
  const commit = commitment(key);
  return { header, payload, commitment: commit, signature: sign(signingInput(header, payload, commit), author.signSk) };
}

/** Verifies signature and key commitment, then decrypts. Throws CryptoError otherwise. */
export function openObject(
  header: ObjectHeader,
  payload: Uint8Array,
  commit: Uint8Array,
  signature: Uint8Array,
  authorSignPk: Uint8Array,
  key: Uint8Array,
): Uint8Array {
  if (!verify(signingInput(header, payload, commit), signature, authorSignPk)) throw new CryptoError(`bad signature on ${header.id}`);
  if (!equal(commitment(key), commit)) throw new CryptoError(`key does not match commitment on ${header.id}`);
  return decryptPayload(header.id, header.version, payload, key);
}

export function wrapKey(key: Uint8Array, recipientBoxPk: Uint8Array): Uint8Array {
  return sodium.crypto_box_seal(key, recipientBoxPk);
}

export function unwrapKey(wrap: Uint8Array, me: AccountKeys, commit: Uint8Array): Uint8Array {
  let key: Uint8Array;
  try {
    key = sodium.crypto_box_seal_open(wrap, me.boxPk, me.boxSk);
  } catch {
    throw new CryptoError('wrap did not open');
  }
  if (!equal(commitment(key), commit)) throw new CryptoError('wrapped key does not match the commitment');
  return key;
}
