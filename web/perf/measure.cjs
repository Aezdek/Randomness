// Measures the web app against a server seeded by shared/client's PerfSeed (see web/perf/README.md).
// Signs in as Ana on the seeded server and times what feels laggy: first load, opening the long chat, scrolling it,
// typing, live messages arriving, switching screens. Reports long tasks (>50 ms main-thread blocks) per step.
const { chromium } = require('playwright');
const fs = require('fs');
const out = process.argv[2] || '/tmp/perf.json';
const S = process.env.PERF_DIR;
(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  await page.addInitScript(() => {
    window.__long = [];
    new PerformanceObserver((list) => { for (const e of list.getEntries()) window.__long.push(e.duration); }).observe({ type: 'longtask', buffered: true });
    // Frame gaps: how long between animation frames while something happens.
    window.__frames = [];
    let last = performance.now();
    const tick = (t) => { window.__frames.push(t - last); last = t; requestAnimationFrame(tick); };
    requestAnimationFrame(tick);
  });
  const cdp = await context.newCDPSession(page);
  await cdp.send('Profiler.enable');
  await cdp.send('Profiler.setSamplingInterval', { interval: 200 });
  const results = {};
  async function step(name, fn, profile = true) {
    await page.evaluate(() => { window.__long = []; window.__frames = []; });
    if (profile) await cdp.send('Profiler.start');
    const t0 = Date.now();
    await fn();
    const wall = Date.now() - t0;
    const r = await page.evaluate(() => ({ long: window.__long.slice(), frames: window.__frames.slice() }));
    if (profile) {
      const { profile: p } = await cdp.send('Profiler.stop');
      fs.writeFileSync(`${S}/${name}.cpuprofile`, JSON.stringify(p));
    }
    const sorted = r.frames.slice().sort((a, b) => a - b);
    results[name] = {
      wallMs: wall,
      longTasks: r.long.length,
      longTotalMs: Math.round(r.long.reduce((a, b) => a + b, 0)),
      longestMs: Math.round(Math.max(0, ...r.long)),
      framesOver50: r.frames.filter((f) => f > 50).length,
      worstFrameMs: Math.round(sorted[sorted.length - 1] || 0),
    };
    console.log(name, JSON.stringify(results[name]));
  }
  await step('load+signin', async () => {
    await page.goto('http://127.0.0.1:8080/');
    await page.getByRole('button', { name: 'Sign in' }).first().click();
    await page.getByLabel('Username').fill('ana');
    await page.getByLabel('Password', { exact: true }).fill('tangerine kettle orbit');
    await page.getByRole('button', { name: 'Sign in', exact: true }).last().click();
    await page.waitForURL((u) => !u.pathname.startsWith('/welcome'), { timeout: 120000 }).catch(() => {});
    await page.waitForTimeout(8000);
  });
  await step('home-idle', async () => { await page.waitForTimeout(3000); });
  await step('home-scroll', async () => {
    for (let i = 0; i < 20; i++) { await page.mouse.wheel(0, 600); await page.waitForTimeout(100); }
    await page.waitForTimeout(500);
  });
  await step('open-inbox', async () => {
    await page.goto('http://127.0.0.1:8080/direct');
    await page.waitForTimeout(2500);
  });
  const thread = fs.readFileSync(`${S}/seed.env`, 'utf8').match(/thread=(\S+)/)[1];
  await step('open-chat', async () => {
    await page.goto(`http://127.0.0.1:8080/direct/${thread}`);
    await page.getByPlaceholder('Message…').waitFor({ timeout: 60000 });
    await page.waitForTimeout(2500);
  });
  await step('chat-scroll-up', async () => {
    await page.mouse.move(900, 400);
    for (let i = 0; i < 30; i++) { await page.mouse.wheel(0, -800); await page.waitForTimeout(80); }
    await page.waitForTimeout(500);
  });
  await step('typing', async () => {
    const box = page.getByPlaceholder('Message…');
    await box.click();
    await box.pressSequentially('This is me typing a fairly ordinary message to see how it feels', { delay: 30, timeout: 120000 });
    await page.waitForTimeout(300);
  });
  await step('send-5', async () => {
    const box = page.getByPlaceholder('Message…');
    for (let i = 0; i < 5; i++) { await box.fill(`Sent ${i}`); await box.press('Enter'); await page.waitForTimeout(400); }
    await page.waitForTimeout(1000);
  });
  fs.writeFileSync(`${S}/perf-live`, '1');
  await step('live-incoming-10s', async () => { await page.waitForTimeout(10000); });
  fs.unlinkSync(`${S}/perf-live`);
  await step('profile-tabs', async () => {
    await page.goto('http://127.0.0.1:8080/u/bo');
    await page.waitForTimeout(1500);
    for (const t of ['Reels', 'Tagged', 'Posts', 'Reels', 'Posts']) { await page.getByRole('tab', { name: t }).click().catch(() => {}); await page.waitForTimeout(300); }
  });
  await page.screenshot({ path: `${S}/last.png` });
  fs.writeFileSync(out, JSON.stringify(results, null, 1));
  await browser.close();
})();
