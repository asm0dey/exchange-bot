import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  // Controls close, not pixel-identical: tolerate font-rendering noise between machines.
  expect: { toHaveScreenshot: { maxDiffPixelRatio: 0.02 } },
  use: { baseURL: 'http://localhost:4173', viewport: { width: 390, height: 844 } },
  // deviceScaleFactor lives here, not in the top-level `use` above: `devices['Desktop Chrome']`
  // sets its own deviceScaleFactor (1), and a project's `use` replaces the top-level value key
  // for key rather than merging underneath it, so a top-level deviceScaleFactor is silently
  // shadowed by the spread. Setting it after the spread is what actually takes effect.
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 } }],
  // Calls the vite binary directly rather than `bun run build && bun run preview`: this config
  // is spawned as a child process of the `webE2e` Gradle task's BunTask, which does not put
  // `bun` itself on PATH for its children (only bun's own script-runner PATH augmentation,
  // which already includes web/node_modules/.bin, reaches this far).
  webServer: { command: 'vite build && vite preview --port 4173 --strictPort', url: 'http://localhost:4173', reuseExistingServer: true },
})
