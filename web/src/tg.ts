import { onMounted, onUnmounted, watch, type Ref } from 'vue'

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

/** Telegram's own primary button, shown while the calling component is mounted. */
export function useMainButton(text: Ref<string>, enabled: Ref<boolean>, onClick: () => void) {
  if (!tg) return
  const b = tg.MainButton
  const sync = () => { b.setText(text.value); enabled.value ? b.enable() : b.disable() }
  onMounted(() => { sync(); b.onClick(onClick); b.show() })
  onUnmounted(() => { b.offClick(onClick); b.hide() })
  watch([text, enabled], sync)
}

export function useBackButton(onBack: () => void) {
  if (!tg) return
  onMounted(() => { tg!.BackButton.onClick(onBack); tg!.BackButton.show() })
  onUnmounted(() => { tg!.BackButton.offClick(onBack); tg!.BackButton.hide() })
}
