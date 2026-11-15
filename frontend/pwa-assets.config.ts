import {
  defineConfig,
  minimal2023Preset,
} from '@vite-pwa/assets-generator/config'

// The logo's tile colour, filled in behind the icons that must not have transparent corners.
const tile = '#171717'

// `npm run generate:icons` writes the PNG icons and favicon.ico next to the logo. They are
// committed, so a build never depends on sharp's native binaries.
export default defineConfig({
  preset: {
    ...minimal2023Preset,
    // Android cuts maskable icons to its own shape (circle, squircle…) and only promises to keep
    // the middle 80%: the tile fills the square and the trail stays inside that circle.
    maskable: {
      sizes: [512],
      padding: 0.2,
      resizeOptions: { background: tile },
    },
    // iOS rounds the corners itself and would fill transparent ones with black.
    apple: { sizes: [180], padding: 0, resizeOptions: { background: tile } },
  },
  images: ['public/favicon.svg'],
})
