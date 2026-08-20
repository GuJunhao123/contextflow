import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: { proxy: { '/api': 'http://127.0.0.1:8081' } },
  build: {
    outDir: '../src/main/resources/static',
    // Only replace this frontend's assets; preserve unrelated static resources.
    emptyOutDir: false,
  },
})
