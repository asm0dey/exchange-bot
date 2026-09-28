import { expect, test } from '@playwright/test'
import { fakeTelegram, mockApi } from './telegram'
import me from './fixtures/me.json' with { type: 'json' }
import browse from './fixtures/browse.json' with { type: 'json' }

const marko = (page: import('@playwright/test').Page) =>
  page.locator('div.grid', { hasText: 'Wants 950 EUR' }).getByRole('button', { name: 'Give EUR' })

test('take Marko: prefilled 950 EUR, fits, posts the opposite side', async ({ page }) => {
  await fakeTelegram(page, { scheme: 'light', startParam: 'c-1001' })
  const sent = await mockApi(page)
  await page.goto('/')
  await marko(page).click()
  await expect(page.getByLabel('Amount')).toHaveValue('950')
  await expect(page.getByText('760 – 1 187 EUR')).toBeVisible()
  await expect(page.getByText('✓ fits')).toBeVisible()
  await page.evaluate(() => (window as any).__tg.click('main'))
  await expect.poll(() => sent.find((s) => s.method === 'POST')).toMatchObject({
    path: '/chat/-1001/requests', body: { says: 'GIVES', amount: '950', currency: 'EUR' },
  })
})

test('typing 700 no longer fits', async ({ page }) => {
  await fakeTelegram(page, { scheme: 'light', startParam: 'c-1001' }); await mockApi(page); await page.goto('/')
  await marko(page).click()
  await page.getByLabel('Amount').fill('700')
  await expect(page.getByText("doesn't fit")).toBeVisible()
})

test("the toggle re-expresses in RUB and never offers receiving EUR", async ({ page }) => {
  await fakeTelegram(page, { scheme: 'light', startParam: 'c-1001' }); await mockApi(page); await page.goto('/')
  await marko(page).click()
  await page.getByRole('radio', { name: 'I receive' }).click()
  await expect(page.getByLabel('Amount')).toHaveValue('89414')
  await expect(page.getByRole('button', { name: 'EUR', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'RUB', exact: true })).toBeEnabled()
})

test('the main button names the group', async ({ page }) => {
  await fakeTelegram(page, { scheme: 'light', startParam: 'c-1001' }); await mockApi(page); await page.goto('/')
  await marko(page).click()
  await expect.poll(() => page.evaluate(() => (window as any).__tg.calls)).toContain('main.text:Post in Belgrade Expats')
})

test('privately, the main button says all my chats', async ({ page }) => {
  await fakeTelegram(page, { scheme: 'light' }); await mockApi(page); await page.goto('/')
  await page.getByRole('button', { name: 'New request' }).click()
  await expect.poll(() => page.evaluate(() => (window as any).__tg.calls)).toContain('main.text:Post in all my chats')
})

test('cancel, done and confirm call their routes', async ({ page }) => {
  await fakeTelegram(page, { scheme: 'light' })
  const sent = await mockApi(page)
  await page.goto('/')
  await page.getByRole('button', { name: 'Yes, we swapped' }).click()
  await page.getByRole('button', { name: 'Done with' }).click()
  await page.getByRole('button', { name: 'Cancel' }).first().click()
  await expect.poll(() => sent.map((s) => `${s.method} ${s.path}`)).toEqual(expect.arrayContaining([
    'POST /confirm', 'POST /done', 'POST /requests/MINE-TOKEN-i1xxxxxxxxxx/cancel',
  ]))
  expect(sent.find((s) => s.path === '/done')!.body).toEqual({ mineToken: 'MINE-TOKEN-s2xxxxxxxxxx', peerShortId: 'b7' })
})

test('a refusal shows the bot\'s message under the form', async ({ page }) => {
  await fakeTelegram(page, { scheme: 'light', startParam: 'c-1001' })
  await mockApi(page, { 'POST /chat/-1001/requests': { status: 422, body: { message: 'This chat exchanges EUR/RUB, so I can\'t do USD here.' } } })
  await page.goto('/')
  await marko(page).click()
  await page.evaluate(() => (window as any).__tg.click('main'))
  await expect(page.getByRole('alert')).toHaveText(/can't do USD here/)
})

test('an expired session says reopen from Telegram', async ({ page }) => {
  await fakeTelegram(page, { scheme: 'light' })
  await mockApi(page, { 'GET /me': { status: 401, body: { message: 'Reopen from Telegram.' } } })
  await page.goto('/')
  await expect(page.getByText('Reopen from Telegram.')).toBeVisible()
})

test('private New request shows no fit verdict before an amount is typed', async ({ page }) => {
  await fakeTelegram(page, { scheme: 'light' }); await mockApi(page); await page.goto('/')
  await page.getByRole('button', { name: 'New request' }).click()
  await expect(page.getByText('too big')).not.toBeVisible()
  await expect(page.getByText('too small')).not.toBeVisible()
})

test('the group refresh button reloads the chat', async ({ page }) => {
  await fakeTelegram(page, { scheme: 'light', startParam: 'c-1001' })
  const sent = await mockApi(page)
  await page.goto('/')
  await expect(page.getByText('Wants 950 EUR')).toBeVisible()
  const before = sent.filter((s) => s.method === 'GET' && s.path.startsWith('/chat/')).length
  await page.getByRole('button', { name: 'Refresh' }).click()
  await expect.poll(() => sent.filter((s) => s.method === 'GET' && s.path.startsWith('/chat/')).length).toBeGreaterThan(before)
})

test('the private refresh button reloads me and browse, on Mine and on Browse', async ({ page }) => {
  await fakeTelegram(page, { scheme: 'light' })
  const sent = await mockApi(page)
  await page.goto('/')
  await expect(page.getByText('Did it happen?')).toBeVisible()
  const meAndBrowse = () => sent.filter((s) => s.path === '/me' || s.path === '/browse').length
  const before = meAndBrowse()
  await page.getByRole('button', { name: 'Refresh' }).click()
  await expect.poll(meAndBrowse).toBeGreaterThan(before)

  await page.getByRole('tab', { name: 'Browse' }).click()
  const beforeOnBrowse = meAndBrowse()
  await page.getByRole('button', { name: 'Refresh' }).click()
  await expect.poll(meAndBrowse).toBeGreaterThan(beforeOnBrowse)
})

test('resolves relative asset and api URLs correctly when served under a reverse-proxy sub-path', async ({ page }) => {
  // Mirrors the real deployment: MINIAPP_URL has a path (e.g. https://x/exchange/) and the
  // reverse proxy forwards /exchange/* WITHOUT stripping it (see README) — so the browser's
  // address bar, and every relative URL/fetch the SPA resolves against it (vite's
  // `base: './'`, api.ts's `fetch('api...')`), stays under /exchange/. This file's single
  // webServer already builds+previews the app at the root, and a second vite build/preview
  // pinned to a different base would race the first one over the same `emptyOutDir: true`
  // output folder — not cheap. Instead this route rewrite plays the reverse proxy's part: it
  // strips the /exchange prefix right before a request leaves the browser, exactly like the
  // real proxy strips nothing but still lands on this same unprefixed origin, while
  // window.location (and thus every relative resolution inside the SPA) stays under
  // /exchange/ throughout — the same shape of test doubling the real Kotlin server-side
  // prefix routing already gets from MiniAppServerTest.
  await fakeTelegram(page, { scheme: 'light' })
  const apiCalls: string[] = []
  await page.route('**/exchange/**', (route) => {
    const url = new URL(route.request().url())
    const rest = url.pathname.replace(/^\/exchange/, '') || '/'
    if (rest === '/api/me') { apiCalls.push(rest); return route.fulfill({ json: me }) }
    if (rest === '/api/browse') { apiCalls.push(rest); return route.fulfill({ json: browse }) }
    url.pathname = rest
    return route.continue({ url: url.toString() })
  })
  await page.goto('/exchange/')
  await expect(page.getByRole('button', { name: 'New request' })).toBeVisible()
  expect(apiCalls).toEqual(expect.arrayContaining(['/api/me', '/api/browse']))
})
