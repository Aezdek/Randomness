import { expect, test, type APIRequestContext, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The smaller things a chat holds, used by two people against the real server: a file sent and saved (and one whose
 * name tries to pass for something else), a shared list both change, the question of the day (hidden until both have
 * answered), a countdown, and a nudge. At the end none of it is readable on the server's disk.
 *
 * Runs after the other specs on the same server; on a fresh one it sets the server up itself. Needs HUSH_DATA_DIR.
 */

const DATA_DIR = process.env.HUSH_DATA_DIR ?? '';
const SHOTS = process.env.SHOTS_DIR;
const MARK = 'ocelot-teapot-7731';
const PASSWORD = 'marmalade harbour violin';

interface Person {
  ctx: BrowserContext;
  page: Page;
  name: string;
}

async function person(browser: Browser, name: string): Promise<Person> {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 }, colorScheme: 'dark', acceptDownloads: true });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.log(`[${name}] page error: ${e.message}`));
  return { ctx, page, name };
}

async function shot(page: Page, name: string) {
  if (!SHOTS) return;
  await page.waitForTimeout(600);
  await page.screenshot({ path: join(SHOTS, `${name}.png`) });
}

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

async function chatWith(p: Person, other: string) {
  await p.page.goto('/direct');
  await p.page.getByRole('button', { name: 'New message' }).click();
  await p.page.locator('.user-row.result', { hasText: other }).click();
  await p.page.getByRole('button', { name: 'Chat', exact: true }).click();
  await p.page.waitForURL(/\/direct\/.+/);
}

async function more(p: Person, item: RegExp) {
  await p.page.getByRole('button', { name: 'Add to chat' }).click();
  await p.page.getByRole('dialog').getByRole('button', { name: item }).click();
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

test('files, lists, the question of the day, countdowns and nudges', async ({ browser, request }) => {
  const fay = await person(browser, 'fay');
  const gus = await person(browser, 'gus');
  await signUp(fay, await invite(request));
  await signUp(gus, await invite(request));
  await chatWith(fay, 'gus');
  await fay.page.getByPlaceholder('Message…').fill('hello gus');
  await fay.page.keyboard.press('Enter');
  await gus.page.goto('/direct');
  await gus.page.locator('.thread-row', { hasText: 'fay' }).click({ timeout: 30_000 });
  await expect(gus.page.locator('.msg', { hasText: 'hello gus' })).toBeVisible();

  // ------------------------------------------------------------------------------------------------ a file
  const body = `the plan is ${MARK}\n`.repeat(2000);
  await fay.page.getByLabel('Send a file').setInputFiles({ name: 'plan.txt', mimeType: 'text/plain', buffer: Buffer.from(body) });
  const card = gus.page.locator('.msg.theirs .file-card', { hasText: 'plan.txt' });
  await expect(card).toBeVisible({ timeout: 60_000 });
  const [download] = await Promise.all([gus.page.waitForEvent('download'), card.click()]);
  expect(download.suggestedFilename()).toBe('plan.txt');
  expect(readFileSync(await download.path(), 'utf8')).toBe(body);

  // A page dressed up with folders and a direction mark: saved under a plain name, and never opened as a page here.
  await fay.page.getByLabel('Send a file').setInputFiles({
    name: 'holiday‮gpj.html',
    mimeType: 'text/html',
    buffer: Buffer.from('<script>document.title = "pwned"</script>'),
  });
  const odd = gus.page.locator('.msg.theirs .file-card', { hasText: 'holidaygpj.html' });
  await expect(odd).toBeVisible({ timeout: 60_000 });
  const [second] = await Promise.all([gus.page.waitForEvent('download'), odd.click()]);
  expect(second.suggestedFilename()).toBe('holidaygpj.html');
  expect(await gus.page.title()).not.toBe('pwned');
  await shot(gus.page, 'file-cards');

  // ------------------------------------------------------------------------------------------- a shared list
  await more(fay, /Shared list/);
  await fay.page.getByLabel('List name').fill('Groceries');
  await fay.page.getByLabel('First items').fill(`Milk\nEggs ${MARK}`);
  await fay.page.getByRole('button', { name: 'Send list' }).click();
  const gusList = gus.page.locator('.list-card');
  await expect(gusList).toContainText('2 left', { timeout: 30_000 });
  await gusList.getByLabel('Add an item').fill('Bread');
  await gusList.getByLabel('Add an item').press('Enter');
  await gusList.getByRole('checkbox', { name: 'Milk' }).click();
  const fayList = fay.page.locator('.list-card');
  await expect(fayList).toContainText('Bread', { timeout: 30_000 });
  await expect(fayList.getByRole('checkbox', { name: 'Milk' })).toHaveAttribute('aria-checked', 'true');
  await expect(fayList).toContainText('2 left');
  // The changes themselves are not messages anyone reads.
  await expect(fay.page.locator('.msg', { hasText: 'Bread' })).toHaveCount(1);
  await shot(fay.page, 'shared-list');

  // ------------------------------------------------------------------------------- the question of the day
  await more(fay, /Question of the day/);
  await fay.page.getByLabel('Your answer').fill(`Tea, always ${MARK}`);
  await fay.page.getByRole('button', { name: 'Answer', exact: true }).click();
  await expect(fay.page.getByText('Waiting for gus')).toBeVisible({ timeout: 20_000 });
  await fay.page.keyboard.press('Escape');
  await expect(gus.page.locator('.daily-card')).toContainText('once everyone has answered', { timeout: 30_000 });
  await expect(gus.page.locator('.daily-card')).not.toContainText('Tea, always');
  await more(gus, /Question of the day/);
  await gus.page.getByLabel('Your answer').fill('Coffee');
  await gus.page.getByRole('button', { name: 'Answer', exact: true }).click();
  await expect(gus.page.locator('.daily-answers')).toContainText('Tea, always', { timeout: 20_000 });
  await gus.page.keyboard.press('Escape');
  await expect(fay.page.locator('.daily-card', { hasText: 'Coffee' })).toBeVisible({ timeout: 30_000 });
  await shot(gus.page, 'question-of-the-day');

  // --------------------------------------------------------------------------------------------- a countdown
  await more(fay, /Countdown/);
  await fay.page.getByLabel('What for').fill('Trip');
  const d = new Date(Date.now() + 12 * 86_400_000);
  await fay.page.locator('input[type=date]').fill(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`);
  await fay.page.getByRole('button', { name: 'Set countdown' }).click();
  await expect(gus.page.locator('.countdown-bar')).toHaveText(/12 days until Trip/, { timeout: 30_000 });
  await expect(fay.page.locator('.countdown-bar')).toHaveText(/12 days until Trip/);

  // ------------------------------------------------------------------------------------------------- a nudge
  await more(gus, /Nudge/);
  await expect(fay.page.locator('.chat-event', { hasText: 'gus nudged you' })).toBeVisible({ timeout: 30_000 });
  await more(gus, /Nudge/);
  await expect(gus.page.getByText('Give it a moment before nudging again')).toBeVisible();
  await shot(fay.page, 'countdown-and-nudge');

  // -------------------------------------------------------------------------------------------- send later
  // (That it arrives at its time, and not before, is tested against the server's clock in SendLaterTest.)
  await more(fay, /Send later/);
  await fay.page.getByLabel('Message to send later').fill(`see you soon ${MARK}`);
  await fay.page.getByRole('button', { name: 'Send then' }).click();
  const waiting = fay.page.getByRole('list', { name: 'Waiting to be sent' });
  await expect(waiting).toContainText('see you soon', { timeout: 20_000 });
  await expect(gus.page.locator('.msg', { hasText: 'see you soon' })).toHaveCount(0);
  await waiting.getByRole('button', { name: 'Cancel' }).click();
  await expect(waiting).toHaveCount(0);
  await fay.page.keyboard.press('Escape');

  // ------------------------------------------------------------------------------ nothing readable on disk
  await fay.page.waitForTimeout(1500);
  expect(scanForPlaintext(DATA_DIR, MARK)).toEqual([]);
  expect(scanForPlaintext(DATA_DIR, 'Groceries')).toEqual([]);
  expect(scanForPlaintext(DATA_DIR, 'plan.txt')).toEqual([]);
  await fay.ctx.close();
  await gus.ctx.close();
});
