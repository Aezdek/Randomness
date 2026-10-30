import { sign, verify, type AccountKeys } from './account';
import { CryptoError, b64, blake2b, sodium, utf8 } from './sodium';

/** Sealed + signed WebSocket messages (call signalling, live chat). Mirrors SignalCrypto.kt. */
export const MAX_SIGNAL_AGE_MS = 120_000;

export interface SealedSignal {
  sealed: Uint8Array;
  signature: Uint8Array;
  sentAt: number;
}

const signingInput = (from: string, to: string, sentAt: number, sealed: Uint8Array) =>
  utf8(`aezgram/v1/signal\n${from}\n${to}\n${sentAt}\n${b64(blake2b(32, sealed))}`);

export function sealSignal(message: Uint8Array, from: string, to: string, sentAt: number, sender: AccountKeys, recipientBoxPk: Uint8Array): SealedSignal {
  const sealed = sodium.crypto_box_seal(message, recipientBoxPk);
  return { sealed, signature: sign(signingInput(from, to, sentAt, sealed), sender.signSk), sentAt };
}

export function openSignal(signal: SealedSignal, from: string, to: string, senderSignPk: Uint8Array, me: AccountKeys, now: number): Uint8Array {
  if (Math.abs(now - signal.sentAt) > MAX_SIGNAL_AGE_MS) throw new CryptoError('stale signal');
  if (!verify(signingInput(from, to, signal.sentAt, signal.sealed), signal.signature, senderSignPk)) throw new CryptoError('bad signal signature');
  try {
    return sodium.crypto_box_seal_open(signal.sealed, me.boxPk, me.boxSk);
  } catch {
    throw new CryptoError('signal did not open');
  }
}
