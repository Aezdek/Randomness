import { generateAccountKeys, openVault, publicKeysOf, sealVault, type AccountKeys } from '../crypto/account';
import { DEFAULT_KDF, newSalt } from '../crypto/password';
import { b64, initSodium, unb64 } from '../crypto/sodium';
import { api, setToken, setUnauthorizedHandler } from './api';
import { LocalDb } from './db';
import { deriveInWorker } from './kdf';
import { clearSession, loadSession, saveSession, updateStoredMe } from './keystore';
import { getState, resetApp, toast, useApp } from './store';
import { engine } from './sync';
import type { AuthResponse, DeviceInfo, Me, PreloginResponse } from './types';
import { realtime } from './ws';

function deviceInfo(): DeviceInfo {
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
  const os = /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS' : /Mac OS/.test(ua) ? 'macOS' : /Windows/.test(ua) ? 'Windows' : /Linux/.test(ua) ? 'Linux' : 'web';
  return { name: `${browser} on ${os}`, platform: 'web' };
}

async function start(token: string, me: Me, keys: AccountKeys) {
  setToken(token);
  setUnauthorizedHandler(() => {
    toast('You were signed out', 'error');
    void signOut(false);
  });
  useApp.setState({ me, keys });
  await engine.open(me.id);
  useApp.setState({ phase: 'ready' });
  realtime.connect();
  void engine.sync().catch((e) => console.warn('sync failed', e));
  void api.get<Me>('/me').then((fresh) => {
    useApp.setState({ me: fresh });
    void updateStoredMe(fresh);
  }).catch(() => undefined);
}

export async function restoreSession() {
  await initSodium();
  const stored = await loadSession();
  if (!stored) {
    resetApp('signedOut');
    return;
  }
  await start(stored.token, stored.me, stored.keys);
}

export async function signUp(invite: string, username: string, password: string) {
  await initSodium();
  const salt = newSalt();
  const { authKey, vaultKey } = await deriveInWorker(password, salt, DEFAULT_KDF);
  const keys = generateAccountKeys();
  const pub = publicKeysOf(keys);
  const response = await api.post<AuthResponse>('/auth/signup', {
    inviteCode: invite.trim(),
    username: username.trim().toLowerCase(),
    salt: b64(salt),
    opsLimit: DEFAULT_KDF.opsLimit,
    memLimit: DEFAULT_KDF.memLimit,
    authKey: b64(authKey),
    signPk: b64(pub.signPk),
    boxPk: b64(pub.boxPk),
    boxPkSig: b64(pub.boxPkSig),
    vault: b64(sealVault(keys, vaultKey)),
    device: deviceInfo(),
  });
  await saveSession(response.token, response.me, keys);
  await start(response.token, response.me, keys);
}

export async function signIn(username: string, password: string) {
  await initSodium();
  const pre = await api.post<PreloginResponse>('/auth/prelogin', { username: username.trim().toLowerCase() });
  const { authKey, vaultKey } = await deriveInWorker(password, unb64(pre.salt), { opsLimit: pre.opsLimit, memLimit: pre.memLimit });
  const response = await api.post<AuthResponse>('/auth/login', { username: username.trim().toLowerCase(), authKey: b64(authKey), device: deviceInfo() });
  const keys = openVault(unb64(response.vault), vaultKey);
  await saveSession(response.token, response.me, keys);
  await start(response.token, response.me, keys);
}

export async function signOut(tellServer = true) {
  const me = getState().me;
  if (tellServer) await api.post('/auth/logout').catch(() => undefined);
  realtime.close();
  engine.close();
  setToken(null);
  await clearSession();
  if (me) await LocalDb.destroy(me.id).catch(() => undefined);
  resetApp('signedOut');
}

/** Password change: same keys, re-sealed under the new password; other devices are signed out. */
export async function changePassword(current: string, next: string) {
  const { me, keys } = getState();
  if (!me || !keys) return;
  const pre = await api.post<PreloginResponse>('/auth/prelogin', { username: me.username });
  const old = await deriveInWorker(current, unb64(pre.salt), { opsLimit: pre.opsLimit, memLimit: pre.memLimit });
  const salt = newSalt();
  const fresh = await deriveInWorker(next, salt, DEFAULT_KDF);
  await api.post('/auth/password', {
    currentAuthKey: b64(old.authKey),
    salt: b64(salt),
    opsLimit: DEFAULT_KDF.opsLimit,
    memLimit: DEFAULT_KDF.memLimit,
    authKey: b64(fresh.authKey),
    vault: b64(sealVault(keys, fresh.vaultKey)),
  });
}

export async function deleteAccount(password: string) {
  const { me } = getState();
  if (!me) return;
  const pre = await api.post<PreloginResponse>('/auth/prelogin', { username: me.username });
  const { authKey } = await deriveInWorker(password, unb64(pre.salt), { opsLimit: pre.opsLimit, memLimit: pre.memLimit });
  await api.post('/me/delete', { authKey: b64(authKey) });
  await signOut(false);
}

export async function updateMe(patch: { isPrivate?: boolean; showActivity?: boolean }) {
  const fresh = await api.patch<Me>('/me', patch);
  useApp.setState({ me: fresh });
  await updateStoredMe(fresh);
  await engine.sync();
  await engine.processWrapTasks();
}

// ---------------------------------------------------------------------------------------------------------- push

export async function registerServiceWorker() {
  if (!('serviceWorker' in navigator)) return;
  try {
    await navigator.serviceWorker.register('/sw.js', { scope: '/' });
  } catch (e) {
    console.warn('service worker registration failed', e);
  }
}

export function pushSupported() {
  return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
}

/** Asks for notification permission and registers a Web Push subscription with the server (VAPID, no third party setup). */
export async function enablePush(): Promise<boolean> {
  if (!pushSupported()) return false;
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') return false;
  const registration = await navigator.serviceWorker.ready;
  const { publicKey } = await api.get<{ publicKey: string }>('/push/vapid');
  const subscription =
    (await registration.pushManager.getSubscription()) ??
    (await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: unb64(publicKey) as BufferSource }));
  const json = subscription.toJSON();
  await api.post('/push/subscriptions', { endpoint: subscription.endpoint, p256dh: json.keys?.p256dh, auth: json.keys?.auth });
  return true;
}

/**
 * Suspected compromise: a brand-new identity. Every content key we can open is re-sealed to the new box key, the old
 * wraps are replaced, and contacts see a key-change warning. Content that leaked before stays leaked.
 */
export async function resetKeys(password: string, onProgress?: (text: string) => void) {
  const { me, keys, entities } = getState();
  if (!me || !keys) return;
  onProgress?.('Checking your password…');
  const pre = await api.post<PreloginResponse>('/auth/prelogin', { username: me.username });
  const old = await deriveInWorker(password, unb64(pre.salt), { opsLimit: pre.opsLimit, memLimit: pre.memLimit });
  onProgress?.('Creating new keys…');
  const fresh = generateAccountKeys();
  const pub = publicKeysOf(fresh);
  const salt = newSalt();
  const derived = await deriveInWorker(password, salt, DEFAULT_KDF);
  onProgress?.('Re-sealing your content…');
  const { wrapKey } = await import('../crypto/object');
  const rewraps = Object.values(entities)
    .filter((e) => e.dto.wrap)
    .map((e) => ({ objectId: e.dto.id, recipientId: me.id, sealed: b64(wrapKey(e.key, fresh.boxPk)) }));
  await api.post('/auth/reset-keys', {
    currentAuthKey: b64(old.authKey),
    salt: b64(salt),
    opsLimit: DEFAULT_KDF.opsLimit,
    memLimit: DEFAULT_KDF.memLimit,
    authKey: b64(derived.authKey),
    vault: b64(sealVault(fresh, derived.vaultKey)),
    signPk: b64(pub.signPk),
    boxPk: b64(pub.boxPk),
    boxPkSig: b64(pub.boxPkSig),
    rewraps,
  });
  const token = (await import('./api')).getToken()!;
  const freshMe = await api.get<Me>('/me');
  await saveSession(token, freshMe, fresh);
  useApp.setState({ keys: fresh, me: freshMe });
}
