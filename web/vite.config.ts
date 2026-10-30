import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// The API server (Ktor) runs on :8080 in development; the web app is served from the same origin in production.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', ws: true, changeOrigin: false },
      '/download': 'http://127.0.0.1:8080',
    },
  },
  build: {
    target: 'es2022',
    sourcemap: false,
    assetsInlineLimit: 0,
    rollupOptions: {
      input: { main: 'index.html', sw: 'src/sw/sw.ts' },
      output: {
        entryFileNames: (chunk) => (chunk.name === 'sw' ? 'sw.js' : 'assets/[name]-[hash].js'),
      },
    },
  },
  test: {
    include: ['src/**/*.test.ts'],
    testTimeout: 60_000,
  },
});
