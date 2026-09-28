import { afterEach, describe, expect, it, mock, spyOn } from 'bun:test'
import { api, ApiError, setInitData } from './api'

afterEach(() => mock.restore())

describe('api', () => {
  it('sends initData as a tma Authorization header', async () => {
    const f = spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{"tolerancePct":20,"limit":5,"pending":[],"mine":[]}', { status: 200 }))
    setInitData('abc')
    await api.me()
    expect(new Headers(f.mock.calls[0]?.[1]?.headers).get('Authorization')).toBe('tma abc')
  })
  it('turns a 422 into an ApiError carrying the service message', async () => {
    spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{"message":"No."}', { status: 422 }))
    await expect(api.cancel('t')).rejects.toMatchObject({ status: 422, message: 'No.' })
  })
  it('keeps the status on a 401', async () => {
    spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{"message":"Reopen from Telegram."}', { status: 401 }))
    const e = await api.me().catch((x) => x)
    expect(e).toBeInstanceOf(ApiError)
    expect(e.status).toBe(401)
  })
  it('turns a rejected fetch into a status-0 ApiError, never a 401', async () => {
    spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
    await expect(api.me()).rejects.toMatchObject({ status: 0, message: 'Could not reach the server. Try again.' })
  })
  it('turns a 200 with a non-JSON body into the same status-0 ApiError', async () => {
    spyOn(globalThis, 'fetch').mockResolvedValue(new Response('not json', { status: 200 }))
    await expect(api.me()).rejects.toMatchObject({ status: 0, message: 'Could not reach the server. Try again.' })
  })
})
