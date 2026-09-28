import { tg } from './tg'

export type Says = 'GIVES' | 'WANTS'
export type RangeDto = { min: string; max?: string | null }
export type CounterpartyDto = { shortId: string; name: string; says: Says; amount: string; currency: string; mineToken: string; chatId: number; chatTitle?: string | null }
export type CardDto = {
  shortId: string; mine: boolean; says: Says; amount: string; currency: string; other: string
  approxOther?: string | null; name: string; createdAt: number; expiresAt: number
  token?: string | null; counterparties: CounterpartyDto[]; range?: RangeDto | null
}
export type ChatView = { chatId: number; title?: string | null; base: string; quote: string; rate?: string | null; rateStale: boolean; cards: CardDto[] }
export type BrowseCard = { base: string; quote: string; says: Says; amount: string; currency: string; other: string; approxOther?: string | null; createdAt: number; expiresAt: number; range?: RangeDto | null }
export type BrowseView = { cards: BrowseCard[]; rates: Record<string, string> }
export type PendingDto = { declarerToken: string; mineToken: string; name: string; says: Says; amount: string; currency: string; other: string }
export type MineDto = { token: string; says: Says; amount: string; currency: string; other: string; approxOther?: string | null; base: string; quote: string; chats: { id: number; title?: string | null }[]; counterparties: CounterpartyDto[]; expiresAt: number }
export type MeView = { tolerancePct: number; limit: number; pending: PendingDto[]; mine: MineDto[] }
type Msg = { message: string }

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message) }
}

let initData = tg?.initData ?? ''
export const setInitData = (v: string) => { initData = v }

const unreachable = () => new ApiError(0, 'Could not reach the server. Try again.')

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  let res: Response
  try {
    // Relative (no leading slash): resolves against wherever index.html was served from —
    // the hostname root, or a path prefix (MINIAPP_URL with a path) — matching vite's
    // `base: './'` (see vite.config.ts). A leading '/' would always hit the origin root.
    res = await fetch(`api${path}`, {
      method,
      headers: { Authorization: `tma ${initData}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    })
  } catch {
    throw unreachable()
  }
  if (res.ok) {
    try {
      return (await res.json()) as T
    } catch {
      throw unreachable()
    }
  }
  const json = await res.json().catch(() => ({ message: 'Something went wrong.' }))
  throw new ApiError(res.status, (json as Msg).message)
}

export const api = {
  chat: (id: number) => call<ChatView>('GET', `/chat/${id}`),
  post: (id: number, b: { says: Says; amount: string; currency: string }) => call<Msg>('POST', `/chat/${id}/requests`, b),
  me: () => call<MeView>('GET', '/me'),
  browse: () => call<BrowseView>('GET', '/browse'),
  state: (b: { says: Says; amount: string; currency: string; other: string }) => call<Msg>('POST', '/interests', b),
  cancel: (token: string) => call<Msg>('POST', `/requests/${encodeURIComponent(token)}/cancel`),
  done: (b: { mineToken: string; peerShortId: string }) => call<Msg>('POST', '/done', b),
  confirm: (b: { declarerToken: string; mineToken: string }) => call<Msg>('POST', '/confirm', b),
  refuse: (b: { declarerToken: string; mineToken: string }) => call<Msg>('POST', '/refuse', b),
  tolerance: (pct: number) => call<MeView>('PUT', '/me/tolerance', { pct }),
}
