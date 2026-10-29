import { defineConfig } from 'vite';

export default defineConfig({
  base: './',
  build: {
    target: 'es2017',
  },
  esbuild: {
    jsx: 'automatic',
    jsxImportSource: 'react',
  },
  server: {
    host: '127.0.0.1',
    port: 4173,
  },
});
