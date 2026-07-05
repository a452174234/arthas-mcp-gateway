import { describe, it, expect, vi, beforeEach } from 'vitest'
import { request, ApiError } from '../../api/adminClient'

// 004 T007：adminClient request 封装（同源 fetch + 结构化错误，admin-invariants INV-ERR-1）。
describe('adminClient request', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('200 JSON → 解析响应体', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({ hello: 'world' }),
    } as unknown as Response))
    const data = await request<{ hello: string }>('/backends')
    expect(data).toEqual({ hello: 'world' })
  })

  it('204 → undefined（无体）', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 204,
      headers: new Headers(),
    } as unknown as Response))
    expect(await request('/x', { method: 'DELETE' })).toBeUndefined()
  })

  it('非 2xx → 抛 ApiError（结构化错误体）', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      statusText: 'Bad Request',
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({ error: 'name 重复', reason: 'duplicate_name' }),
    } as unknown as Response))
    await expect(request('/x', { method: 'POST' })).rejects.toMatchObject({
      name: 'ApiError',
      status: 400,
      message: 'name 重复',
      reason: 'duplicate_name',
    })
    await expect(request('/x')).rejects.toBeInstanceOf(ApiError)
  })

  it('请求路径以 /admin 为前缀（同源）', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({}),
    } as unknown as Response)
    vi.stubGlobal('fetch', fetchMock)
    await request('/backends')
    expect(fetchMock).toHaveBeenCalledWith(
      '/admin/backends',
      expect.objectContaining({ headers: expect.objectContaining({ 'Content-Type': 'application/json' }) }),
    )
  })
})
