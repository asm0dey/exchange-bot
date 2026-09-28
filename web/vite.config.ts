import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  // Relative, not '/': the server can mount the app at the hostname root OR under a path
  // prefix (MINIAPP_URL with a path, e.g. https://example.com/exchange/). An absolute '/'
  // base would always resolve against the origin root and break under a path prefix.
  base: './',
  // Lands where Gradle picks it up as jar resources under static/ (see build.gradle.kts).
  build: { outDir: '../build/web/static', emptyOutDir: true },
  server: { proxy: { '/api': 'http://localhost:8080' } },
  test: { environment: 'node', include: ['src/**/*.test.ts'] },
})
