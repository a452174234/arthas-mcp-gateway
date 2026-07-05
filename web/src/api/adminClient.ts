// 004 portal 前端 → /admin API 客户端（research.md R6：同源 fetch，无 CORS）。
// 仅展示层（宪法原则六 / R13）：CRUD/导出/校验逻辑在后端 Java，前端只 fetch + render + download。

const BASE = '/admin'

/** 管理面结构化错误（HTTP 状态码 + 错误体，admin-invariants INV-ERR-1）。 */
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public reason?: string,
    public available?: string[],
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/** 同源 fetch 封装：JSON 请求/响应、错误结构化传播。 */
export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    ...init,
  })
  if (!res.ok) {
    let body: { error?: string; reason?: string; available?: string[] } = {}
    try {
      body = await res.json()
    } catch {
      /* 非 JSON 错误体 */
    }
    throw new ApiError(res.status, body.error ?? res.statusText, body.reason, body.available)
  }
  if (res.status === 204) return undefined as T
  const ct = res.headers.get('content-type') ?? ''
  if (ct.includes('application/json')) return res.json() as Promise<T>
  return (await res.text()) as unknown as T
}

// ===== 后端配置 CRUD（US1，admin-api-contract §1）=====

export interface BackendDto {
  name: string
  source: 'STATIC' | 'DYNAMIC'
  state: 'ACTIVE' | 'RETIRED'
  healthy: boolean
  breaker: 'OPEN' | 'CLOSED'
  url: string
  protocol: 'STREAMABLE' | 'STATELESS'
  authMode: 'NONE' | 'BEARER' | 'BASIC'
  connectTimeoutMs: number
  callTimeoutMs: number
  maxConcurrentTasks: number
}

export interface BackendSummary {
  total: number
  healthy: number
  unhealthy: number
}

export interface BackendListResponse {
  backends: BackendDto[]
  summary: BackendSummary
}

export interface CreateBackendRequest {
  name: string
  url: string
  protocol?: string
  authMode?: string
  token?: string
  username?: string
  password?: string
  connectTimeoutMs?: number
  callTimeoutMs?: number
  maxConcurrentTasks?: number
}

export interface UpdateBackendRequest {
  url?: string
  authMode?: string
  token?: string
  username?: string
  password?: string
  connectTimeoutMs?: number
  callTimeoutMs?: number
  maxConcurrentTasks?: number
}

function enc(name: string): string {
  return encodeURIComponent(name)
}

export function listBackends(): Promise<BackendListResponse> {
  return request<BackendListResponse>('/backends')
}

export function getBackend(name: string): Promise<BackendDto> {
  return request<BackendDto>(`/backends/${enc(name)}`)
}

export function createBackend(req: CreateBackendRequest): Promise<BackendDto> {
  return request<BackendDto>('/backends', { method: 'POST', body: JSON.stringify(req) })
}

export function updateBackend(name: string, req: UpdateBackendRequest): Promise<BackendDto> {
  return request<BackendDto>(`/backends/${enc(name)}`, { method: 'PUT', body: JSON.stringify(req) })
}

export function deleteBackend(name: string): Promise<void> {
  return request<void>(`/backends/${enc(name)}`, { method: 'DELETE' })
}

// ===== 任务结果导出（US2，admin-api-contract §2）=====

export interface TaskExportDto {
  taskId: string
  tool: string
  target: string
  status: string
  createdAt: string
  completedAt: string
  isError: boolean
  frames: string[]
}

export function exportTask(taskId: string): Promise<TaskExportDto> {
  return request<TaskExportDto>(`/tasks/${enc(taskId)}/export?format=json`)
}

/**
 * 触发浏览器原样下载（GET attachment，不经前端解析/重序列化，admin-invariants INV-EXP-1）。
 * 错误（404/409）由浏览器默认处理；查询态先用 {@link exportTask} 探测并友好提示。
 */
export function downloadTaskExport(taskId: string): void {
  const a = document.createElement('a')
  a.href = `${BASE}/tasks/${enc(taskId)}/export?format=json`
  a.download = `${taskId}.json`
  a.click()
}
