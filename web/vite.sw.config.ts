import { defineConfig } from 'vite';

// The service worker ships as one classic script at /sw.js (classic, so every browser can register it).
export default defineConfig({
  publicDir: false,
  build: {
    target: 'es2022',
    outDir: 'dist',
    emptyOutDir: false,
    sourcemap: false,
    lib: {
      entry: 'src/sw/sw.ts',
      formats: ['iife'],
      name: 'aezgramSw',
      fileName: () => 'sw.js',
    },
  },
});
