import { expect, test, type APIRequestContext, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * The small-circle features and the new security ones, used by two people against the real server:
 * a sign-in alert answered "Not me" (the other device is signed out), a passkey added and used to sign in (Chromium's
 * virtual authenticator, with PRF), Notes to self, search across every chat, a plan with answers, live location shared
 * and stopped, and a shared album. At the end nothing they wrote is readable on the server's disk.
 *
 * Runs after app.spec.ts on the same server; on a fresh one it sets the server up itself. Needs HUSH_DATA_DIR.
 */

const DATA_DIR = process.env.HUSH_DATA_DIR ?? '';
const SHOTS = process.env.SHOTS_DIR;
const FIXTURES = resolve(dirname(fileURLToPath(import.meta.url)), 'fixtures');
const MARK = 'quokka-lantern-5519';
const PASSWORD = 'marmalade harbour violin';
// WebAuthn needs a domain, not an IP address: the passkey part runs on localhost.
const BASE = (process.env.HUSH_URL ?? 'http://127.0.0.1:8080').replace('127.0.0.1', 'localhost');

interface Person {
  ctx: BrowserContext;
  page: Page;
  name: string;
}

async function person(browser: Browser, name: string, extra: Parameters<Browser['newContext']>[0] = {}): Promise<Person> {
  const ctx = await browser.newContext({ baseURL: BASE, viewport: { width: 1280, height: 860 }, colorScheme: 'dark', ...extra });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.log(`[${name}] page error: ${e.message}`));
  return { ctx, page, name };
}

async function shot(page: Page, name: string) {
  if (!SHOTS) return;
  await page.waitForTimeout(600);
  await page.screenshot({ path: join(SHOTS, `${name}.png`) });
}

/** An invite made on the server itself (as control.sh does), so this test needs no admin account. */
async function invite(request: APIRequestContext): Promise<string> {
  const state = await (await request.get('/api/v1/setup')).json().catch(() => ({ state: 'ready' }));
  if (state.state === 'setup') {
    const code = readFileSync(join(DATA_DIR, 'setup-code.txt'), 'utf8').trim();
    expect((await request.post('/api/v1/setup', { data: { code, password: 'the server password, set once' } })).status()).toBe(200);
    await expect.poll(() => existsSync(join(DATA_DIR, 'first-invite.txt')), { timeout: 20_000 }).toBe(true);
  }
  const key = readFileSync(join(DATA_DIR, 'local.key'), 'utf8').trim();
  let code = '';
  await expect
    .poll(async () => {
      const r = await request.post('/api/v1/local/invites', { headers: { 'X-Hush-Local': key } });
      code = r.ok() ? (await r.json()).code : '';
      return code;
    }, { timeout: 30_000 })
    .not.toBe('');
  return code;
}

async function signUp(p: Person, code: string) {
  await p.page.goto(`/?invite=${code}`);
  await p.page.getByLabel('Invite code').fill(code);
  await p.page.getByLabel('Username').fill(p.name);
  await p.page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await p.page.getByLabel('Confirm password').fill(PASSWORD);
  await p.page.getByRole('button', { name: 'Create account' }).click();
  await expect(p.page.getByRole('navigation', { name: 'Main' }).first()).toBeVisible({ timeout: 30_000 });
}

async function signIn(p: Person, username: string) {
  await p.page.goto('/');
  await p.page.getByRole('button', { name: 'Sign in' }).first().click();
  await p.page.getByLabel('Username').fill(username);
  await p.page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await p.page.getByRole('button', { name: 'Sign in', exact: true }).last().click();
  await expect(p.page.getByRole('navigation', { name: 'Main' }).first()).toBeVisible({ timeout: 60_000 });
}

/** Opens (or starts) the one-to-one chat with [other]. */
async function chatWith(p: Person, other: string) {
  await p.page.goto('/direct');
  await p.page.getByRole('button', { name: 'New message' }).click();
  await p.page.locator('.user-row.result', { hasText: other }).click();
  await p.page.getByRole('button', { name: 'Chat', exact: true }).click();
  await p.page.waitForURL(/\/direct\/.+/);
}

async function more(p: Person, item: RegExp) {
  await p.page.getByRole('button', { name: 'Add to chat' }).click();
  await p.page.getByRole('button', { name: item }).click();
}

function scanForPlaintext(dir: string, needle: string): string[] {
  const hits: string[] = [];
  const walk = (d: string) => {
    for (const name of readdirSync(d)) {
      const path = join(d, name);
      if (statSync(path).isDirectory()) walk(path);
      else if (readFileSync(path).includes(needle)) hits.push(path);
    }
  };
  walk(dir);
  return hits;
}

test('circle features and sign-in security', async ({ browser, request }) => {
  expect(DATA_DIR, 'set HUSH_DATA_DIR').not.toBe('');

  const dina = await person(browser, 'dina', { permissions: ['geolocation'], geolocation: { latitude: 38.7139, longitude: -9.1394, accuracy: 20 } });
  const eli = await person(browser, 'eli');
  await signUp(dina, await invite(request));
  await signUp(eli, await invite(request));

  // ------------------------------------------------------------------------------ a sign-in that wasn't him
  const intruder = await person(browser, 'eli-elsewhere');
  await signIn(intruder, 'eli');
  await expect(eli.page.getByRole('dialog').getByText('signed in to your account')).toBeVisible({ timeout: 20_000 });
  await shot(eli.page, 'sign-in-alert');
  await eli.page.getByRole('button', { name: 'Not me' }).click();
  await eli.page.waitForURL(/\/settings\/account/);
  // That device is signed out: the next time it talks to the server, it's back at the start.
  await intruder.page.reload();
  await expect(intruder.page.getByRole('button', { name: 'Sign in' }).first()).toBeVisible({ timeout: 20_000 });
  await intruder.ctx.close();

  // ------------------------------------------------------------------------------------------ passkeys
  const cdp = await dina.ctx.newCDPSession(dina.page);
  await cdp.send('WebAuthn.enable');
  const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', {
    options: { protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true, isUserVerified: true, hasPrf: true, automaticPresenceSimulation: true },
  });
  await dina.page.goto('/settings/security');
  await dina.page.getByRole('button', { name: 'Add a passkey' }).click();
  // A wrong password is refused by the server, so a stolen session can't add itself a way in.
  await dina.page.getByPlaceholder('Your password').fill('not my password at all');
  await dina.page.getByRole('button', { name: 'Add passkey' }).click();
  await expect(dina.page.getByText('That isn’t your password')).toBeVisible({ timeout: 20_000 });
  // ...and before the device made anything, so no passkey is left behind that the server never accepted.
  expect((await cdp.send('WebAuthn.getCredentials', { authenticatorId })).credentials).toHaveLength(0);
  await dina.page.getByPlaceholder('Your password').fill(PASSWORD);
  await dina.page.getByLabel('Passkey name').fill('Test passkey');
  await dina.page.getByRole('button', { name: 'Add passkey' }).click();
  await expect(dina.page.getByText('Passkey added')).toBeVisible({ timeout: 30_000 });
  await expect(dina.page.locator('.user-row', { hasText: 'Test passkey' })).toBeVisible();
  expect((await cdp.send('WebAuthn.getCredentials', { authenticatorId })).credentials).toHaveLength(1);
  await shot(dina.page, 'settings-passkeys');
  // Signed out, then back in with the passkey alone: no username, no password, same keys.
  await dina.page.goto('/settings');
  await dina.page.getByRole('button', { name: 'Log out' }).click();
  await dina.page.getByRole('button', { name: 'Sign in' }).first().click();
  await dina.page.getByRole('button', { name: 'Sign in with a passkey' }).click();
  await expect(dina.page.getByRole('navigation', { name: 'Main' }).first()).toBeVisible({ timeout: 30_000 });
  await dina.page.goto('/settings/security');
  await expect(dina.page.locator('.user-row', { hasText: 'Test passkey' }).getByText('Used')).toBeVisible({ timeout: 20_000 });

  // ------------------------------------------------------------------------------------- notes to self
  await dina.page.goto('/direct');
  await dina.page.getByRole('button', { name: /Notes to self/ }).click();
  await dina.page.waitForURL(/\/direct\/.+/);
  await expect(dina.page.getByText('Only you can see this')).toBeVisible();
  await dina.page.locator('#chat-input').fill(`Buy film rolls ${MARK}`);
  await dina.page.keyboard.press('Enter');
  await expect(dina.page.locator('.chat-scroll .msg:not(.outgoing)').getByText(`Buy film rolls ${MARK}`)).toBeVisible({ timeout: 20_000 });

  // ------------------------------------------------------------------------------------------ a plan
  await chatWith(dina, 'eli');
  await more(dina, /^Plan/);
  await dina.page.getByLabel('What’s the plan?').fill(`Dinner at Leo's ${MARK}`);
  await dina.page.getByLabel('Where').fill('Leo’s, Rua Augusta');
  await dina.page.getByRole('button', { name: 'Send plan' }).click();
  await expect(dina.page.locator('.plan-card', { hasText: 'Dinner at Leo' })).toBeVisible({ timeout: 20_000 });
  await eli.page.goto('/direct');
  await eli.page.locator('.thread-row', { hasText: 'dina' }).first().click({ timeout: 20_000 });
  const going = eli.page.locator('.plan-card').getByRole('button', { name: 'Going' });
  await going.click({ timeout: 20_000 });
  await expect(going).toHaveAttribute('aria-pressed', 'true', { timeout: 15_000 });
  await expect(dina.page.locator('.plan-card').getByText('1 going')).toBeVisible({ timeout: 20_000 });
  await shot(dina.page, 'chat-plan');
  // Choosing "Maybe" replaces the answer rather than adding a second one.
  await eli.page.locator('.plan-card').getByRole('button', { name: 'Maybe' }).click();
  await expect(dina.page.locator('.plan-card').getByText('1 maybe')).toBeVisible({ timeout: 20_000 });
  await expect(dina.page.locator('.plan-card').getByText('1 going')).toHaveCount(0);

  // ------------------------------------------------------------------------------ search across chats
  await eli.page.goto('/direct');
  await eli.page.getByLabel('Search chats and messages').fill('leo');
  await expect(eli.page.locator('.search-hit', { hasText: 'Dinner at Leo' })).toBeVisible({ timeout: 10_000 });
  await eli.page.locator('.search-hit', { hasText: 'Dinner at Leo' }).click();
  await eli.page.waitForURL(/\/direct\/.+/);
  await expect(eli.page.locator('.plan-card')).toBeVisible();

  // ---------------------------------------------------------------------------------- live location
  await more(dina, /^Live location/);
  await dina.page.getByRole('radio', { name: '15 minutes' }).click();
  await dina.page.getByRole('button', { name: 'Share', exact: true }).click();
  await expect(dina.page.locator('.location-card', { hasText: 'Live location' })).toBeVisible({ timeout: 20_000 });
  await expect(eli.page.locator('.location-card', { hasText: 'Live location' })).toBeVisible({ timeout: 20_000 });
  await expect(eli.page.locator('.location-card .location-coords')).toHaveText(/38\.71\d*, -9\.13\d*/);
  await shot(eli.page, 'chat-live-location');
  // Moving replaces the one message: the chat shows the new spot, and no second message.
  await dina.ctx.setGeolocation({ latitude: 38.7223, longitude: -9.1393, accuracy: 15 });
  await expect(eli.page.locator('.location-card .location-coords')).toHaveText(/38\.722\d*, -9\.139\d*/, { timeout: 60_000 });
  await expect(eli.page.locator('.location-card')).toHaveCount(1);
  await dina.page.locator('.location-card').getByRole('button', { name: 'Stop sharing' }).click();
  await expect(eli.page.locator('.location-card')).toHaveCount(0, { timeout: 20_000 });

  // ------------------------------------------------------------------------------------- shared album
  await dina.page.goto('/direct');
  await dina.page.getByRole('button', { name: 'New message' }).click();
  await dina.page.getByRole('tab', { name: 'Shared album' }).click();
  await dina.page.locator('.user-row.result', { hasText: 'eli' }).click();
  await dina.page.getByLabel('Album name').fill('Lisbon');
  await dina.page.getByRole('dialog').getByRole('button', { name: 'Create', exact: true }).click();
  await dina.page.waitForURL(/\/direct\/.+/);
  await expect(dina.page.getByText('No photos yet')).toBeVisible({ timeout: 20_000 });
  await dina.page.locator('.chat-composer input[type=file]').setInputFiles([join(FIXTURES, 'city.jpg'), join(FIXTURES, 'sea.jpg')]);
  await expect(dina.page.locator('.album-grid button')).toHaveCount(2, { timeout: 60_000 });
  await eli.page.goto('/direct');
  await eli.page.locator('.thread-row', { hasText: 'Lisbon' }).click({ timeout: 20_000 });
  await expect(eli.page.locator('.album-grid button')).toHaveCount(2, { timeout: 30_000 });
  await eli.page.locator('.album-grid button').first().click();
  await expect(eli.page.locator('.album-viewer-info')).toContainText('dina');
  await shot(eli.page, 'album-viewer');
  await eli.page.keyboard.press('Escape');
  await shot(eli.page, 'album-gallery');

  // --------------------------------------------------------------------------- private notifications
  await eli.page.goto('/settings/notifications');
  await eli.page.getByRole('tab', { name: 'Nothing' }).click();
  await expect(eli.page.getByText('Your server leaves out who it is from too')).toBeVisible({ timeout: 15_000 });

  // ------------------------------------------------------------------------------ nothing readable on disk
  await dina.page.waitForTimeout(1500);
  expect(scanForPlaintext(DATA_DIR, MARK)).toEqual([]);
  expect(scanForPlaintext(DATA_DIR, 'Rua Augusta')).toEqual([]);
  expect(scanForPlaintext(DATA_DIR, 'Lisbon')).toEqual([]);
  await dina.ctx.close();
  await eli.ctx.close();
});
