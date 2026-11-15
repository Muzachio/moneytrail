/// <reference types="vitest/config" />
import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { VitePWA } from 'vite-plugin-pwa'

// Spring Security's and the API's paths. In development (and `vite preview`) they share the app's
// origin through the proxy below; in production they live on the API's own origin (ADR-0008).
const apiPaths = ['/v1', '/oauth2', '/login/oauth2', '/logout']

// Palette A keeps the interface neutral: a white header on a light grey page.
const header = '#ffffff'
const page = '#fafafa'

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    VitePWA({
      // A new version waits until the person taps Update (UpdatePrompt) instead of reloading the
      // page under them, which would wipe a transaction half typed. It also starts by itself the
      // next time the app opens, once no window still runs the old one.
      registerType: 'prompt',
      // UpdatePrompt registers the service worker, so it can offer the update.
      injectRegister: false,
      // globPatterns below already takes the icons, once.
      includeManifestIcons: false,
      manifest: {
        id: '/',
        name: 'MoneyTrail',
        short_name: 'MoneyTrail',
        description:
          'Personal and shared expense tracking on top of a double-entry ledger.',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        // Android paints its status bar with it: the same white as the header below it.
        theme_color: header,
        // Behind the icon on Android's splash screen, as the page appears.
        background_color: page,
        categories: ['finance'],
        icons: [
          { src: 'pwa-64x64.png', sizes: '64x64', type: 'image/png' },
          { src: 'pwa-192x192.png', sizes: '192x192', type: 'image/png' },
          { src: 'pwa-512x512.png', sizes: '512x512', type: 'image/png' },
          {
            src: 'maskable-icon-512x512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'maskable',
          },
        ],
      },
      workbox: {
        // The app shell only: what the build produced, nothing fetched at run time. The service
        // worker gets no runtime caching rules, so API calls (another origin in production, with
        // financial data) always go to the network as if it were not there.
        globPatterns: ['**/*.{js,css,html,woff2,svg,png,ico}'],
        // Opening any page of the app, also offline, gets index.html and the router takes over…
        navigateFallback: 'index.html',
        // …except the API's paths where they share the origin: Google sign-in is a full page
        // navigation to /oauth2/authorization/google, which must reach Spring Security.
        navigateFallbackDenylist: apiPaths.map(
          (apiPath) => new RegExp(`^${apiPath}(/|$)`),
        ),
      },
    }),
  ],
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
      apiPaths.map((apiPath) => [
        apiPath,
        { target: 'http://127.0.0.1:8080', changeOrigin: false },
      ]),
    ),
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
})
