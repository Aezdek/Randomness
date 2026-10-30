import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { beforeAll, describe, expect, it } from 'vitest';
import { accountKeysFromSeeds, isSelfSigned, openVault, publicKeysOf, sealVault } from '../account';
import { decryptMedia, encryptMedia } from '../media';
import { childKey, commitment, openObject, sealObject, unwrapKey, wrapKey, type ObjectHeader } from '../object';
import { argonInput, derivePasswordKeys } from '../password';
import { safetyCode, safetyPair } from '../safety';
import { openSignal, sealSignal } from '../signal';
import { b64, fromUtf8, initSodium, unb64, utf8 } from '../sodium';

const dir = resolve(__dirname, '../../../../crypto-vectors');

type V = Record<string, Record<string, unknown>>;

function verify(v: V) {
  const pw = v.password as Record<string, string | number>;
  expect(argonInput(pw.password as string)).toBe(pw.argonInput);
  const derived = derivePasswordKeys(pw.password as string, unb64(pw.salt as string), {
    opsLimit: pw.opsLimit as number,
    memLimit: pw.memLimit as number,
  });
  expect(b64(derived.authKey)).toBe(pw.authKey);
  expect(b64(derived.vaultKey)).toBe(pw.vaultKey);

  const acc = v.account as Record<string, string>;
  const account = accountKeysFromSeeds(unb64(acc.signSeed), unb64(acc.boxSeed));
  expect(b64(account.signPk)).toBe(acc.signPk);
  expect(b64(account.boxPk)).toBe(acc.boxPk);
  expect(isSelfSigned({ signPk: account.signPk, boxPk: account.boxPk, boxPkSig: unb64(acc.boxPkSig) })).toBe(true);
  const opened = openVault(unb64(acc.vault), derived.vaultKey);
  expect(b64(opened.signSk)).toBe(b64(account.signSk));
  expect(b64(opened.boxSk)).toBe(b64(account.boxSk));

  const obj = v.object as Record<string, unknown>;
  const header = obj.header as ObjectHeader;
  const key = unwrapKey(unb64(obj.wrap as string), account, unb64(obj.commitment as string));
  expect(b64(key)).toBe(obj.contentKey);
  expect(b64(commitment(key))).toBe(obj.commitment);
  const plain = openObject(header, unb64(obj.payload as string), unb64(obj.commitment as string), unb64(obj.signature as string), account.signPk, key);
  expect(fromUtf8(plain)).toBe(obj.plaintext);

  const child = v.child as Record<string, string>;
  expect(b64(childKey(unb64(child.parentKey), child.childId))).toBe(child.key);

  const media = v.media as Record<string, string>;
  expect(b64(decryptMedia(unb64(media.cipher), unb64(media.fileKey)))).toBe(media.plain);

  const safety = v.safety as Record<string, string>;
  expect(safetyCode(unb64(safety.signPkA))).toBe(safety.codeA);
  expect(safetyPair(unb64(safety.signPkA), unb64(safety.signPkB))).toBe(safety.pair);

  const sig = v.signal as Record<string, string | number>;
  const message = openSignal(
    { sealed: unb64(sig.sealed as string), signature: unb64(sig.signature as string), sentAt: sig.sentAt as number },
    sig.from as string,
    sig.to as string,
    unb64(sig.senderSignPk as string),
    account,
    sig.sentAt as number,
  );
  expect(fromUtf8(message)).toBe(sig.plaintext);
}

function generate(): V {
  const password = 'pässwörd ✓ 🔐';
  const salt = new Uint8Array(16).map((_, i) => (i * 7) & 0xff);
  const params = { opsLimit: 1, memLimit: 8 * 1024 * 1024 };
  const derived = derivePasswordKeys(password, salt, params);
  const signSeed = new Uint8Array(32).fill(1);
  const boxSeed = new Uint8Array(32).fill(2);
  const account = accountKeysFromSeeds(signSeed, boxSeed);
  const other = accountKeysFromSeeds(new Uint8Array(32).fill(3), new Uint8Array(32).fill(4));
  const header: ObjectHeader = {
    id: 'webVectorObject01', kind: 'message', authorId: 'author2', parentId: null, threadId: 'thread9', audience: 'thread',
    createdAt: 1_700_000_000_999, expiresAt: null, version: 1, blobIds: ['zz', 'aa'], refIds: [],
  };
  const contentKey = new Uint8Array(32).map((_, i) => (i * 3) & 0xff);
  const plaintext = '{"type":"text","text":"ünïcødé 👋"}';
  const sealed = sealObject(header, utf8(plaintext), contentKey, account);
  const fileKey = new Uint8Array(32).map((_, i) => (i + 9) & 0xff);
  const plainMedia = new Uint8Array(777).map((_, i) => (i * 31) & 0xff);
  const signal = sealSignal(utf8('{"type":"answer"}'), 'carol', 'dave', 1_700_000_000_500, other, account.boxPk);
  return {
    password: { password, salt: b64(salt), opsLimit: params.opsLimit, memLimit: params.memLimit, argonInput: argonInput(password), authKey: b64(derived.authKey), vaultKey: b64(derived.vaultKey) },
    account: {
      signSeed: b64(signSeed), boxSeed: b64(boxSeed), signPk: b64(account.signPk), signSk: b64(account.signSk), boxPk: b64(account.boxPk),
      boxSk: b64(account.boxSk), boxPkSig: b64(publicKeysOf(account).boxPkSig), vault: b64(sealVault(account, derived.vaultKey)),
    },
    object: {
      header, plaintext, contentKey: b64(contentKey), payload: b64(sealed.payload), commitment: b64(sealed.commitment),
      signature: b64(sealed.signature), wrap: b64(wrapKey(contentKey, account.boxPk)),
    },
    child: { parentKey: b64(contentKey), childId: 'reaction-ß', key: b64(childKey(contentKey, 'reaction-ß')) },
    media: { fileKey: b64(fileKey), chunkSize: 100, plain: b64(plainMedia), cipher: b64(encryptMedia(plainMedia, fileKey, 100)) },
    safety: { signPkA: b64(account.signPk), signPkB: b64(other.signPk), codeA: safetyCode(account.signPk), pair: safetyPair(account.signPk, other.signPk) },
    signal: {
      from: 'carol', to: 'dave', senderSignPk: b64(other.signPk), sentAt: signal.sentAt, sealed: b64(signal.sealed),
      signature: b64(signal.signature), plaintext: '{"type":"answer"}',
    },
  };
}

describe('cross-platform crypto vectors', () => {
  beforeAll(() => initSodium());

  it('opens everything the Kotlin implementation produced', () => {
    verify(JSON.parse(readFileSync(resolve(dir, 'kotlin-v1.json'), 'utf8')));
  });

  it('produces vectors Kotlin can open (and that open here)', () => {
    const file = resolve(dir, 'web-v1.json');
    if (!existsSync(file) || process.env.WRITE_VECTORS === '1') writeFileSync(file, JSON.stringify(generate(), null, 2) + '\n');
    verify(JSON.parse(readFileSync(file, 'utf8')));
  });
});
