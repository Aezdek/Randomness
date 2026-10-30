import { b64, blake2b, concat, unb64, utf8 } from './sodium';

/** 30 digits per identity key; mirrors SafetyNumber.kt. */
export function safetyCode(signPk: Uint8Array): string {
  const hash = blake2b(30, concat(utf8('aezgram/v1/safety\n'), signPk));
  let out = '';
  for (let group = 0; group < 6; group++) {
    let value = 0n;
    for (let i = 0; i < 5; i++) value = (value << 8n) | BigInt(hash[group * 5 + i]);
    out += (value % 100000n).toString().padStart(5, '0');
  }
  return out;
}

export function safetyPair(a: Uint8Array, b: Uint8Array): string {
  return [safetyCode(a), safetyCode(b)].sort().join('');
}

export const groupDigits = (digits: string): string[] => digits.match(/.{1,5}/g) ?? [];

export const qrPayload = (userId: string, signPk: Uint8Array): string => `hush:verify:${userId}:${b64(signPk)}`;

export function parseQr(payload: string): { userId: string; signPk: Uint8Array } | null {
  const parts = payload.split(':');
  if (parts.length !== 4 || parts[0] !== 'hush' || parts[1] !== 'verify') return null;
  try {
    const signPk = unb64(parts[3]);
    return signPk.length === 32 ? { userId: parts[2], signPk } : null;
  } catch {
    return null;
  }
}
