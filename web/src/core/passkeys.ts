// Passkey sign-in with WebAuthn's PRF extension. The authenticator produces a secret that only it can make again (an
// HMAC under a key inside it, for a fixed input); from that secret come two keys, exactly as from a password:
//   - an auth key the server stores hashed and checks at sign-in, like the password's;
//   - a vault key that never leaves the device and seals a second copy of the account's private keys.
// The server never sees the PRF secret or the vault key, so a passkey is as end to end as the password. Mirrors the
// server's AddPasskeyRequest / PasskeyLoginRequest (shared/protocol Api.kt).
import { openVault, sealVault } from '../crypto/account';
import { b64, initSodium, random, sodium, unb64, utf8 } from '../crypto/sodium';
import { api } from './api';
import { deriveInWorker } from './kdf';
import { getState } from './store';
import type { AuthResponse, PasskeyDto, PreloginResponse } from './types';

/** The fixed PRF input: the same on every device, so a synced passkey gives the same keys everywhere. */
function prfSalt(): Uint8Array {
  return sodium.crypto_generichash(32, utf8('hush passkey prf v1'), null);
}

export function passkeyKeys(prfOutput: Uint8Array) {
  if (prfOutput.length < 32) throw new Error('The passkey gave too short a secret');
  const seed = prfOutput.subarray(0, 32);
  return {
    authKey: sodium.crypto_kdf_derive_from_key(32, 1, 'hushpkau', seed),
    vaultKey: sodium.crypto_kdf_derive_from_key(32, 2, 'hushpkvt', seed),
  };
}

/** A copy in a plain ArrayBuffer, which the WebAuthn types insist on. */
const buf = (bytes: Uint8Array): ArrayBuffer => bytes.slice().buffer as ArrayBuffer;

const b64url = (bytes: ArrayBuffer | Uint8Array) => sodium.to_base64(new Uint8Array(bytes), sodium.base64_variants.URLSAFE_NO_PADDING);

type PrfResults = { prf?: { enabled?: boolean; results?: { first?: ArrayBuffer } } };

/** Whether this browser can make passkeys at all; whether the passkey can do PRF is only known when it is made. */
export function passkeysSupported(): boolean {
  return typeof window !== 'undefined' && !!window.PublicKeyCredential && !!navigator.credentials && window.isSecureContext;
}

async function prfFromAssertion(credentialId?: Uint8Array): Promise<{ id: string; secret: Uint8Array }> {
  const options: PublicKeyCredentialRequestOptions = {
    challenge: buf(random(32)),
    rpId: location.hostname,
    userVerification: 'required',
    timeout: 120_000,
    allowCredentials: credentialId ? [{ type: 'public-key', id: buf(credentialId) }] : [],
    extensions: { prf: { eval: { first: buf(prfSalt()) } } } as AuthenticationExtensionsClientInputs,
  };
  const credential = (await navigator.credentials.get({ publicKey: options })) as PublicKeyCredential | null;
  if (!credential) throw new Error('No passkey was chosen');
  const first = (credential.getClientExtensionResults() as PrfResults).prf?.results?.first;
  if (!first) throw new Error('This passkey can’t be used to sign in to Hush (it has no PRF support). Try a different one, or your password.');
  return { id: b64url(credential.rawId), secret: new Uint8Array(first) };
}

/** Adds a passkey to this account. Needs the password once, so a stolen session can't add itself a way in. */
export async function addPasskey(password: string, name: string): Promise<PasskeyDto> {
  await initSodium();
  const { me, keys } = getState();
  if (!me || !keys) throw new Error('Not signed in');
  const pre = await api.post<PreloginResponse>('/auth/prelogin', { username: me.username });
  const current = await deriveInWorker(password, unb64(pre.salt), { opsLimit: pre.opsLimit, memLimit: pre.memLimit });
  // Checked before the browser makes anything: a refusal afterwards would leave a passkey on the device that never works.
  await api.post('/auth/passkeys/check', { currentAuthKey: b64(current.authKey) });
  const created = (await navigator.credentials.create({
    publicKey: {
      rp: { name: 'Hush', id: location.hostname },
      user: { id: buf(random(16)), name: me.username, displayName: me.username },
      challenge: buf(random(32)),
      pubKeyCredParams: [
        { type: 'public-key', alg: -7 },
        { type: 'public-key', alg: -257 },
      ],
      authenticatorSelection: { residentKey: 'required', userVerification: 'required' },
      timeout: 120_000,
      extensions: { prf: { eval: { first: buf(prfSalt()) } } } as AuthenticationExtensionsClientInputs,
    },
  })) as PublicKeyCredential | null;
  if (!created) throw new Error('No passkey was made');
  const results = created.getClientExtensionResults() as PrfResults;
  if (results.prf?.enabled === false) throw new Error('This passkey can’t be used to sign in to Hush (it has no PRF support). Try a different one.');
  // Some authenticators only give the secret when the passkey is used, not when it is made: use it once.
  const first = results.prf?.results?.first;
  const secret: Uint8Array = first ? new Uint8Array(first) : (await prfFromAssertion(new Uint8Array(created.rawId))).secret;
  const derived = passkeyKeys(secret);
  sodium.memzero(secret);
  const dto = await api.post<PasskeyDto>('/auth/passkeys', {
    currentAuthKey: b64(current.authKey),
    credentialId: b64url(created.rawId),
    name: name.trim().slice(0, 60) || 'Passkey',
    authKey: b64(derived.authKey),
    vault: b64(sealVault(keys, derived.vaultKey)),
  });
  sodium.memzero(derived.vaultKey);
  return dto;
}

/** Signs in with a passkey: no username, the passkey says which account it belongs to. */
export async function passkeySignIn(device: { name: string; platform: string }): Promise<{ response: AuthResponse; keys: ReturnType<typeof openVault> }> {
  await initSodium();
  const { id, secret } = await prfFromAssertion();
  const derived = passkeyKeys(secret);
  sodium.memzero(secret);
  const response = await api.post<AuthResponse>('/auth/passkey', { credentialId: id, authKey: b64(derived.authKey), device });
  const keys = openVault(unb64(response.vault), derived.vaultKey);
  sodium.memzero(derived.vaultKey);
  return { response, keys };
}
