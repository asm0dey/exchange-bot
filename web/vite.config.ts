import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  // Lands where Gradle picks it up as jar resources under static/ (see build.gradle.kts).
  build: { outDir: '../build/web/static', emptyOutDir: true },
  server: { proxy: { '/api': 'http://localhost:8080' } },
  test: { environment: 'node', include: ['src/**/*.test.ts'] },
})
