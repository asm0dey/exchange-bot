type Btn = {
  setText(t: string): void; show(): void; hide(): void; enable(): void; disable(): void
  onClick(cb: () => void): void; offClick(cb: () => void): void
  showProgress(leaveActive?: boolean): void; hideProgress(): void
}
export type Tg = {
  initData: string
  initDataUnsafe: { start_param?: string }
  colorScheme: 'light' | 'dark'
  ready(): void
  expand(): void
  MainButton: Btn
  BackButton: { show(): void; hide(): void; onClick(cb: () => void): void; offClick(cb: () => void): void }
  HapticFeedback: { notificationOccurred(t: 'success' | 'error' | 'warning'): void }
  onEvent(e: string, cb: () => void): void
}

export const tg: Tg | undefined =
  typeof window !== 'undefined' && (window as any).Telegram?.WebApp?.initData
    ? (window as any).Telegram.WebApp
    : undefined

export function initTelegram() {
  if (!tg) return
  tg.ready()
  tg.expand()
  const scheme = () => document.documentElement.setAttribute('data-scheme', tg!.colorScheme)
  scheme()
  tg.onEvent('themeChanged', scheme)
}

/** `c-1001` opens the group view for chat -1001; anything else is the private view. */
export function startChatId(): number | null {
  const p = tg?.initDataUnsafe.start_param ?? ''
  const m = /^c(-\d+)$/.exec(p)
  return m ? Number(m[1]) : null
}

export function haptic(kind: 'success' | 'error') {
  tg?.HapticFeedback.notificationOccurred(kind)
}

/** Telegram's own primary button. Returns the cleanup, so a component's `$effect` can own it. */
export function mainButton(onClick: () => void): (() => void) | undefined {
  if (!tg) return
  const b = tg.MainButton
  b.onClick(onClick); b.show()
  return () => { b.offClick(onClick); b.hide() }
}

export function syncMainButton(text: string, enabled: boolean) {
  if (!tg) return
  tg.MainButton.setText(text)
  enabled ? tg.MainButton.enable() : tg.MainButton.disable()
}

export function backButton(onBack: () => void): (() => void) | undefined {
  if (!tg) return
  tg.BackButton.onClick(onBack); tg.BackButton.show()
  return () => { tg!.BackButton.offClick(onBack); tg!.BackButton.hide() }
}
