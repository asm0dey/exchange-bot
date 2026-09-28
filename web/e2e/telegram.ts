import type { Page } from '@playwright/test'
import chat from './fixtures/chat.json' with { type: 'json' }
import me from './fixtures/me.json' with { type: 'json' }
import browse from './fixtures/browse.json' with { type: 'json' }

export const THEMES = {
  light: { bg_color: '#ffffff', secondary_bg_color: '#f0f1f5', text_color: '#0f1419', hint_color: '#6f7780', link_color: '#2481cc', button_color: '#3390ec', button_text_color: '#ffffff', section_separator_color: '#e3e5ea' },
  dark: { bg_color: '#212121', secondary_bg_color: '#181818', text_color: '#f5f5f5', hint_color: '#9a9ea4', link_color: '#6ab3f3', button_color: '#3e88d8', button_text_color: '#ffffff', section_separator_color: '#2e2e2e' },
}
export const NOW = new Date('2026-09-27T12:00:00Z')

/** Installs a stand-in for telegram-web-app.js that records MainButton/Haptic calls on window.__tg. */
export async function fakeTelegram(page: Page, opts: { scheme: 'light' | 'dark'; startParam?: string }) {
  await page.clock.setFixedTime(NOW)
  await page.route('https://telegram.org/js/telegram-web-app.js', (r) => r.fulfill({ contentType: 'text/javascript', body: '' }))
  await page.addInitScript(({ scheme, theme, startParam }) => {
    const calls: string[] = []
    const handlers: Record<string, () => void> = {}
    const btn = (name: string) => ({
      text: '', visible: false, enabled: true,
      setText(t: string) { this.text = t; calls.push(`${name}.text:${t}`) },
      show() { this.visible = true }, hide() { this.visible = false },
      enable() { this.enabled = true }, disable() { this.enabled = false },
      onClick(cb: () => void) { handlers[name] = cb }, offClick() { delete handlers[name] },
      showProgress() {}, hideProgress() {},
    })
    // Set window.Telegram/__tg FIRST: this must survive even though document.documentElement
    // (needed below, for the theme vars) can still be null this early in a fresh document —
    // an addInitScript callback runs before the page has an <html> element, and a thrown
    // TypeError from touching it would otherwise abort the whole script before Telegram is
    // faked at all, silently leaving every test on the "Open this from Telegram." branch.
    ;(window as any).__tg = { calls, click: (n: string) => handlers[n]?.() }
    ;(window as any).Telegram = { WebApp: {
      initData: 'query_id=x&user=%7B%22id%22%3A1%7D&auth_date=1&hash=fake',
      initDataUnsafe: { start_param: startParam },
      colorScheme: scheme, ready() {}, expand() {}, onEvent() {},
      MainButton: btn('main'), BackButton: btn('back'),
      HapticFeedback: { notificationOccurred: (t: string) => calls.push(`haptic:${t}`) },
    } }
    const applyTheme = () => { for (const [k, v] of Object.entries(theme)) document.documentElement.style.setProperty(`--tg-theme-${k.replace(/_/g, '-')}`, v as string) }
    if (document.documentElement) applyTheme()
    else document.addEventListener('DOMContentLoaded', applyTheme, { once: true })
  }, { scheme: opts.scheme, theme: THEMES[opts.scheme], startParam: opts.startParam })
}

/** Serves the fixtures; `overrides` replaces a route's answer for one test. */
export async function mockApi(page: Page, overrides: Record<string, { status: number; body: unknown }> = {}) {
  const sent: { method: string; path: string; body: unknown }[] = []
  await page.route('**/api/**', async (route) => {
    const req = route.request()
    const path = new URL(req.url()).pathname.replace('/api', '')
    sent.push({ method: req.method(), path, body: req.postDataJSON?.() ?? null })
    const o = overrides[`${req.method()} ${path}`]
    if (o) return route.fulfill({ status: o.status, json: o.body })
    if (path.startsWith('/chat/') && req.method() === 'GET') return route.fulfill({ json: chat })
    if (path === '/me') return route.fulfill({ json: me })
    if (path === '/browse') return route.fulfill({ json: browse })
    return route.fulfill({ json: { message: 'OK.' } })
  })
  return sent
}
