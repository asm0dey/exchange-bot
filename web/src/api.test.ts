import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, setInitData } from './api'

afterEach(() => vi.unstubAllGlobals())

describe('api', () => {
  it('sends initData as a tma Authorization header', async () => {
    const f = vi.fn().mockResolvedValue(new Response('{"tolerancePct":20,"limit":5,"pending":[],"mine":[]}', { status: 200 }))
    vi.stubGlobal('fetch', f)
    setInitData('abc')
    await api.me()
    expect(f.mock.calls[0][1].headers.Authorization).toBe('tma abc')
  })
  it('turns a 422 into an ApiError carrying the service message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"message":"No."}', { status: 422 })))
    await expect(api.cancel('t')).rejects.toMatchObject({ status: 422, message: 'No.' })
  })
  it('keeps the status on a 401', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"message":"Reopen from Telegram."}', { status: 401 })))
    const e = await api.me().catch((x) => x)
    expect(e).toBeInstanceOf(ApiError)
    expect(e.status).toBe(401)
  })
})
