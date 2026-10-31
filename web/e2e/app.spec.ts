import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Three people use Hush end to end against a real server: sign up with invites, profiles, follow requests,
 * posts with photos (encrypted media), likes and comments, a story, Direct messages and a real WebRTC video call
 * (Chromium's fake camera). At the end, nothing written to the server's disk contains plaintext.
 *
 * Needs HUSH_DATA_DIR (to read the first invite and scan for plaintext). SHOTS_DIR saves screenshots.
 */

const DATA_DIR = process.env.HUSH_DATA_DIR ?? '';
const SHOTS = process.env.SHOTS_DIR;
const FIXTURES = resolve(dirname(fileURLToPath(import.meta.url)), 'fixtures');
const MARK = 'zebra-quartz-7731';

interface Person {
  ctx: BrowserContext;
  page: Page;
  name: string;
}

async function person(browser: Browser, name: string, mobile = false, scheme: 'dark' | 'light' = 'dark'): Promise<Person> {
  const ctx = await browser.newContext({
    viewport: mobile ? { width: 390, height: 844 } : { width: 1440, height: 900 },
    deviceScaleFactor: mobile ? 2 : 1,
    isMobile: mobile,
    hasTouch: mobile,
    colorScheme: scheme,
    permissions: ['camera', 'microphone', 'clipboard-read', 'clipboard-write'],
  });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.log(`[${name}] page error: ${e.message}`));
  return { ctx, page, name };
}

async function shot(page: Page, name: string) {
  if (!SHOTS) return;
  await page.waitForTimeout(600);
  await page.screenshot({ path: join(SHOTS, `${name}.png`) });
}

async function signUp(p: Person, invite: string) {
  await p.page.goto(`/?invite=${invite}`);
  await p.page.getByLabel('Invite code').fill(invite);
  await p.page.getByLabel('Username').fill(p.name);
  await p.page.getByLabel('Password', { exact: true }).fill(`${p.name} has a long password`);
  await p.page.getByLabel('Confirm password').fill(`${p.name} has a long password`);
  await p.page.getByRole('button', { name: 'Create account' }).click();
  await expect(p.page.getByRole('navigation', { name: 'Main' }).first()).toBeVisible({ timeout: 30_000 });
}

async function editProfile(p: Person, displayName: string, bio: string) {
  await p.page.goto(`/u/${p.name}`);
  await p.page.getByRole('button', { name: 'Edit profile' }).click();
  await p.page.locator('.edit-profile input[type=file]').setInputFiles(join(FIXTURES, `avatar-${p.name}.jpg`));
  await p.page.getByLabel('Name').fill(displayName);
  await p.page.getByLabel('Bio').fill(bio);
  await expect(p.page.getByRole('button', { name: 'Done' })).toBeEnabled({ timeout: 30_000 });
  await p.page.getByRole('button', { name: 'Done' }).click();
  await expect(p.page.getByText(displayName).first()).toBeVisible();
}

async function newInvite(admin: Person): Promise<string> {
  await admin.page.goto('/admin');
  await admin.page.getByRole('button', { name: 'New invite' }).click();
  // The list also shows invites already used; wait for the fresh one rather than reading whatever is first.
  const code = admin.page.locator('.admin-row').filter({ has: admin.page.locator('b.tabular') }).filter({ hasNotText: 'Used' }).locator('b.tabular').first();
  await expect(code).toHaveText(/^[A-Z0-9]{5}-[A-Z0-9]{5}$/);
  return (await code.textContent())!.trim();
}

async function post(p: Person, files: string[], caption: string): Promise<string> {
  await p.page.getByRole('button', { name: 'Create' }).first().click();
  await p.page.getByRole('button', { name: /^Post/ }).click();
  await p.page.locator('.dropzone input[type=file]').setInputFiles(files.map((f) => join(FIXTURES, f)));
  const head = p.page.locator('.sheet-head');
  await expect(head.getByRole('button', { name: 'Next' })).toBeVisible();
  if (files.length > 1) await shot(p.page, 'create-edit');
  await head.getByRole('button', { name: 'Next' }).click();
  await p.page.getByPlaceholder(/Write a caption/).fill(caption);
  await shot(p.page, 'create-details');
  await head.getByRole('button', { name: 'Share' }).click();
  await p.page.waitForURL(/\/p\//, { timeout: 60_000 });
  return p.page.url().split('/p/')[1];
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

test('three people use Hush end to end', async ({ browser }) => {
  expect(DATA_DIR, 'set HUSH_DATA_DIR').not.toBe('');
  const firstInvite = readFileSync(join(DATA_DIR, 'first-invite.txt'), 'utf8').trim();

  // ------------------------------------------------------------------------------------------- accounts
  const alice = await person(browser, 'alice');
  await alice.page.goto('/');
  await shot(alice.page, 'welcome-desktop');
  await signUp(alice, firstInvite);
  await shot(alice.page, 'home-empty-desktop');
  await editProfile(alice, 'Alice Rivera', 'Sunsets, synthwave and too much coffee ☕ #golden');

  const bob = await person(browser, 'bob', true);
  await signUp(bob, await newInvite(alice));
  await editProfile(bob, 'Bob Okafor', 'Mostly sea pictures 🌊');

  // Bob asks to follow Alice (private by default); Alice confirms.
  await bob.page.goto('/u/alice');
  await bob.page.getByRole('button', { name: 'Follow', exact: true }).click();
  await expect(bob.page.getByRole('button', { name: 'Requested' })).toBeVisible();
  await alice.page.goto('/activity');
  await expect(alice.page.getByText('wants to follow you')).toBeVisible({ timeout: 20_000 });
  await shot(alice.page, 'activity-request');
  await alice.page.getByRole('button', { name: 'Confirm' }).click();

  // Alice follows Bob back (his request too).
  await alice.page.goto('/u/bob');
  await alice.page.getByRole('button', { name: /Follow( back)?/ }).first().click();
  await bob.page.goto('/activity');
  await bob.page.getByRole('button', { name: 'Confirm' }).click();

  // ---------------------------------------------------------------------------------------------- posts
  const caption = `Golden hour from the ridge ${MARK} #golden @bob`;
  const postId = await post(alice, ['sunset.jpg', 'dusk.jpg', 'city.jpg'], caption);
  await shot(alice.page, 'post-page-desktop');
  await alice.page.goto('/');
  await post(alice, ['dawn.jpg'], 'Morning run, cold air, warm light.');

  await bob.page.goto('/');
  await post(bob, ['sea.jpg'], 'Low tide 🌊');
  await bob.page.goto('/');
  await expect(bob.page.getByText('Golden hour from the ridge')).toBeVisible({ timeout: 30_000 });
  await shot(bob.page, 'home-mobile');

  // Bob likes and comments; Alice sees it.
  await bob.page.locator('.post').filter({ hasText: 'Golden hour' }).getByRole('button', { name: 'Like' }).click();
  await bob.page.locator('.post').filter({ hasText: 'Golden hour' }).getByLabel('Add a comment').fill('This light is unreal 😍');
  await bob.page.locator('.post').filter({ hasText: 'Golden hour' }).getByRole('button', { name: 'Post' }).click();
  await alice.page.goto('/');
  await expect(alice.page.getByText('View 1 comment')).toBeVisible({ timeout: 20_000 });
  await shot(alice.page, 'home-desktop');
  await alice.page.locator('.post').filter({ hasText: 'Golden hour' }).getByText('View 1 comment').click();
  await expect(alice.page.getByText('This light is unreal')).toBeVisible();
  await shot(alice.page, 'post-modal-desktop');
  await alice.page.keyboard.press('Escape');

  await alice.page.goto('/u/alice');
  await shot(alice.page, 'profile-desktop');
  await bob.page.goto('/u/alice');
  await shot(bob.page, 'profile-mobile');

  // --------------------------------------------------------------------------------------------- reels
  // A 4K portrait phone clip (rotated H.264 + AAC) must leave the browser as 1080×1920 H.264/AAC: this Chromium has
  // no native AAC encoder, so it also exercises the WebAssembly fallback.
  await alice.page.goto('/');
  await alice.page.getByRole('button', { name: 'Create' }).first().click();
  await alice.page.getByRole('button', { name: /^Reel/ }).click();
  await alice.page.locator('.dropzone input[type=file]').setInputFiles(join(FIXTURES, 'clip-4k-portrait.mp4'));
  const reelHead = alice.page.locator('.sheet-head');
  await reelHead.getByRole('button', { name: 'Next' }).click();
  await alice.page.getByPlaceholder(/Write a caption/).fill(`Ridge timelapse ${MARK}`);
  await reelHead.getByRole('button', { name: 'Share' }).click();
  await alice.page.waitForURL(/\/reels\//, { timeout: 120_000 });
  await bob.page.goto('/reels');
  const reelVideo = bob.page.locator('.reel video').first();
  await expect.poll(() => reelVideo.evaluate((v: HTMLVideoElement) => [v.videoWidth, v.videoHeight]), { timeout: 60_000 }).toEqual([1080, 1920]);
  await reelVideo.evaluate((v: HTMLVideoElement) => v.play());
  await expect
    .poll(() => reelVideo.evaluate((v: HTMLVideoElement & { webkitAudioDecodedByteCount?: number }) => v.webkitAudioDecodedByteCount ?? 0), { timeout: 30_000 })
    .toBeGreaterThan(0);
  await shot(bob.page, 'reels-mobile');

  // ------------------------------------------------------------------------------------------- stories
  await alice.page.goto('/');
  await alice.page.getByRole('button', { name: 'Create' }).first().click();
  await alice.page.getByRole('button', { name: /^Story/ }).click();
  await alice.page.getByRole('button', { name: 'Text story' }).click();
  await alice.page.getByPlaceholder('Start typing…').fill('Who’s up for sunrise tomorrow? 🌄');
  await shot(alice.page, 'story-compose');
  await alice.page.locator('.story-share').getByRole('button', { name: 'Share', exact: true }).click();
  await expect(alice.page.locator('.story-composer')).toBeHidden({ timeout: 30_000 });

  await bob.page.goto('/');
  await bob.page.locator('.tray-item').filter({ hasText: 'alice' }).click();
  await expect(bob.page.getByText('Who’s up for sunrise tomorrow?')).toBeVisible({ timeout: 20_000 });
  await shot(bob.page, 'story-mobile');
  await bob.page.keyboard.press('Escape');

  // -------------------------------------------------------------------------------------------- direct
  await bob.page.goto('/u/alice');
  await bob.page.getByRole('button', { name: 'Message' }).click();
  await bob.page.waitForURL(/\/direct\//);
  await bob.page.locator('#chat-input').fill(`Coffee later? ${MARK}`);
  await bob.page.keyboard.press('Enter');
  await alice.page.goto('/direct');
  await alice.page.locator('.thread-row').first().click();
  await expect(alice.page.locator('.chat-scroll').getByText('Coffee later?')).toBeVisible({ timeout: 20_000 });
  await alice.page.locator('#chat-input').fill('Always ☕ 10am?');
  await alice.page.keyboard.press('Enter');
  await expect(bob.page.locator('.chat-scroll').getByText('Always ☕ 10am?')).toBeVisible({ timeout: 20_000 });
  await shot(alice.page, 'direct-desktop');
  await shot(bob.page, 'direct-mobile');

  // ----------------------------------------------------------------------------------------------- call
  await alice.page.getByRole('button', { name: 'Video call' }).click();
  await expect(bob.page.getByRole('button', { name: 'Accept' })).toBeVisible({ timeout: 20_000 });
  await shot(bob.page, 'call-incoming-mobile');
  await bob.page.getByRole('button', { name: 'Accept' }).click({ force: true }); // it bobs gently while ringing
  await expect(alice.page.locator('.call-tile video')).toHaveCount(1, { timeout: 30_000 });
  await alice.page.waitForFunction(() => {
    const v = document.querySelector('.call-tile video') as HTMLVideoElement | null;
    return !!v && v.readyState >= 2 && v.videoWidth > 0;
  }, undefined, { timeout: 30_000 });
  await shot(alice.page, 'call-desktop');
  await alice.page.getByRole('button', { name: 'Hang up' }).click();
  await expect(bob.page.locator('.call-screen')).toBeHidden({ timeout: 20_000 });
  await expect(alice.page.locator('.chat-scroll').getByText('Video call')).toBeVisible({ timeout: 20_000 });

  // Bob is also signed in on a laptop. Both ring; he answers on his phone; the laptop stops ringing at once and stays
  // out of the call (it used to answer the video offer itself, and decline the running call 45 seconds later).
  const bobLaptop = await person(browser, 'bob-laptop');
  await bobLaptop.page.goto('/');
  await bobLaptop.page.getByLabel('Username').fill('bob');
  await bobLaptop.page.getByLabel('Password', { exact: true }).fill('bob has a long password');
  await bobLaptop.page.getByRole('button', { name: 'Sign in' }).last().click();
  await expect(bobLaptop.page.getByRole('navigation', { name: 'Main' }).first()).toBeVisible({ timeout: 60_000 });
  await alice.page.getByRole('button', { name: 'Video call' }).click();
  await expect(bob.page.getByRole('button', { name: 'Accept' })).toBeVisible({ timeout: 20_000 });
  await expect(bobLaptop.page.getByRole('button', { name: 'Accept' })).toBeVisible({ timeout: 20_000 });
  await bob.page.getByRole('button', { name: 'Accept' }).click({ force: true });
  await expect(bobLaptop.page.locator('.call-screen')).toBeHidden({ timeout: 10_000 });
  await alice.page.waitForFunction(() => {
    const v = document.querySelector('.call-tile video.remote') as HTMLVideoElement | null;
    return !!v && v.readyState >= 2 && v.videoWidth > 0;
  }, undefined, { timeout: 30_000 });
  // Bob's camera off shows his picture instead; back on shows his video again.
  await bob.page.getByRole('button', { name: 'Camera off' }).click();
  await expect(alice.page.locator('.call-tile-avatar')).toBeVisible({ timeout: 10_000 });
  await bob.page.getByRole('button', { name: 'Camera on' }).click();
  await expect(alice.page.locator('.call-tile video.remote')).toBeVisible({ timeout: 10_000 });
  await bob.page.getByRole('button', { name: 'Hang up' }).click();
  await expect(alice.page.locator('.call-screen')).toBeHidden({ timeout: 20_000 });
  await expect(bobLaptop.page.locator('.call-screen')).toBeHidden();
  await bobLaptop.ctx.close();

  // ----------------------------------------------------------------------------- a stranger sees nothing
  const cara = await person(browser, 'cara', false, 'light');
  await signUp(cara, await newInvite(alice));
  await cara.page.goto(`/p/${postId}`);
  await expect(cara.page.getByText('Post unavailable')).toBeVisible({ timeout: 20_000 });
  await cara.page.goto('/u/alice');
  await expect(cara.page.getByText('This account is private')).toBeVisible();
  await shot(cara.page, 'profile-private-light');
  await cara.page.goto('/');
  await shot(cara.page, 'home-empty-light');

  // ------------------------------------------------------------------------------------ remaining screens
  await alice.page.goto('/explore');
  await shot(alice.page, 'explore-desktop');
  await alice.page.goto('/settings/security');
  await shot(alice.page, 'settings-security-desktop');
  // The admin panel lists everyone, with the server's state; members only ever see Settings.
  await alice.page.goto('/admin');
  await expect(alice.page.locator('.admin-row.member').filter({ hasText: '@cara' })).toBeVisible({ timeout: 15_000 });
  await shot(alice.page, 'admin-desktop');
  await bob.page.goto('/settings');
  await expect(bob.page.getByText('Close Friends')).toBeVisible();
  await expect(bob.page.getByText('Admin', { exact: true })).toHaveCount(0);
  await shot(bob.page, 'settings-mobile');
  await alice.page.goto(`/verify/${await bob.page.evaluate(() => '')}`).catch(() => undefined);

  // ------------------------------------------------------------------------------------- no plaintext
  const hits = scanForPlaintext(DATA_DIR, MARK);
  expect(hits, 'the server stored plaintext').toEqual([]);

  await Promise.all([alice.ctx.close(), bob.ctx.close(), cara.ctx.close()]);
});
