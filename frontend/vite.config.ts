/// <reference types="vitest/config" />
import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  server: {
    // Only this machine can reach the dev server, like bootRun on the backend.
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    // Same origin as the API in development, so no CORS setup is needed locally and the session
    // cookie belongs to this origin. /oauth2, /login/oauth2 and /logout are Spring Security's.
    // changeOrigin: false keeps the Host header (the string shorthand would rewrite it), so the
    // API builds http://127.0.0.1:5173/login/oauth2/code/google as Google's redirect URI.
    proxy: Object.fromEntries(
      ['/v1', '/oauth2', '/login/oauth2', '/logout'].map((path) => [
        path,
        { target: 'http://127.0.0.1:8080', changeOrigin: false },
      ]),
    ),
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
})
