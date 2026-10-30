import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests against a real server (HUSH_URL, default http://127.0.0.1:8080) serving this build.
 * CI starts the server from server/build/install with a fresh data directory; see .github/workflows/hush.yml.
 * Set SHOTS_DIR to also save screenshots of every screen for visual review.
 */
export default defineConfig({
  testDir: 'e2e',
  timeout: 180_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.HUSH_URL ?? 'http://127.0.0.1:8080',
    trace: 'retain-on-failure',
    launchOptions: {
      args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--autoplay-policy=no-user-gesture-required'],
    },
    permissions: ['camera', 'microphone', 'clipboard-read', 'clipboard-write'],
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
