import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'

// The backend owns every rule; the browser never talks to Postgres directly.
const apiProxy = {
  '/api': {
    target: 'http://localhost:8080',
    changeOrigin: true,
  },
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy: apiProxy,
  },
  // `npm run preview` serves the production build with the same proxy.
  preview: {
    port: 4173,
    proxy: apiProxy,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    css: true,
    // Cypress owns the end-to-end scenario; Vitest only covers components.
    exclude: ['node_modules/**', 'cypress/**'],
    // Count every source file, not only the ones a test happens to import.
    coverage: {
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/main.tsx', 'src/**/*.d.ts'],
    },
  },
})
