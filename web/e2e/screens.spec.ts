import { expect, test } from '@playwright/test'
import { fakeTelegram, mockApi } from './telegram'

for (const scheme of ['light', 'dark'] as const) {
  test.describe(scheme, () => {
    test('1 group view', async ({ page }) => {
      await fakeTelegram(page, { scheme, startParam: 'c-1001' }); await mockApi(page); await page.goto('/')
      await expect(page.getByText('Wants 950 EUR')).toBeVisible()
      await expect(page).toHaveScreenshot(`1-group-${scheme}.png`)
    })
    test('2 give EUR to Marko', async ({ page }) => {
      await fakeTelegram(page, { scheme, startParam: 'c-1001' }); await mockApi(page); await page.goto('/')
      await page.getByRole('button', { name: 'Give EUR' }).nth(0).click()
      await expect(page.getByText('Marko matches')).toBeVisible()
      await expect(page).toHaveScreenshot(`2-sheet-give-${scheme}.png`)
    })
    test('2b same deal, I receive', async ({ page }) => {
      await fakeTelegram(page, { scheme, startParam: 'c-1001' }); await mockApi(page); await page.goto('/')
      await page.getByRole('button', { name: 'Give EUR' }).nth(0).click()
      await page.getByRole('radio', { name: 'I receive' }).click()
      await expect(page).toHaveScreenshot(`2b-sheet-receive-${scheme}.png`)
    })
    test('3 mine', async ({ page }) => {
      await fakeTelegram(page, { scheme }); await mockApi(page); await page.goto('/')
      await expect(page.getByText('Did it happen?')).toBeVisible()
      await expect(page).toHaveScreenshot(`3-mine-${scheme}.png`)
    })
    test('4 browse', async ({ page }) => {
      await fakeTelegram(page, { scheme }); await mockApi(page); await page.goto('/')
      await page.getByRole('tab', { name: 'Browse' }).click()
      await page.getByRole('button', { name: 'EUR ⇄ RUB' }).click()
      await expect(page).toHaveScreenshot(`4-browse-${scheme}.png`)
    })
    test('5 new request privately', async ({ page }) => {
      await fakeTelegram(page, { scheme }); await mockApi(page); await page.goto('/')
      await page.getByRole('button', { name: 'New request' }).click()
      await page.getByLabel('Amount').fill('500')
      await expect(page.getByText('fits 1 of')).toBeVisible()
      await expect(page).toHaveScreenshot(`5-new-private-${scheme}.png`)
    })
  })
}
