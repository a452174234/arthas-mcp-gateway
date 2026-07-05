# 管理 API 契约：portal 后端管理平台（004）

> 网关侧 `/admin` HTTP 管理 API 的端点契约（方法/路径/请求/响应/状态码/错误）。实体见 [data-model.md](../data-model.md)；不变量见 [admin-invariants.md](./admin-invariants.md)。鉴权 MVP = Noop（受控内网，research.md R8）。能力按需开关（`@ConditionalOnProperty`，关闭则端点 404，R9）。

## 1. 后端配置 CRUD（`/admin/backends`）

### GET `/admin/backends` — 列表 + 汇总

- **响应 200**：`{ "backends": [BackendDto], "summary": { "total": N, "healthy": H, "unhealthy": U } }`
- **断言 A-LIST-1**：返回全部后端（静态 + 动态），每条含 `name/source/state/healthy/breaker/url/protocol/auth.mode/timeouts/maxConcurrentTasks`；`summary` 计数与列表一致。

### GET `/admin/backends/{name}` — 详情

- **响应 200**：`BackendDto`
- **错误 404**：`{ "error": "未知后端", "name": "...", "available": [...] }`

### POST `/admin/backends` — 新增

- **请求**：`CreateBackendRequest`（`name`/`url`/`protocol`/`auth`/`connectTimeoutMs`/`callTimeoutMs`/`maxConcurrentTasks`）
- **响应 201**：`BackendDto`
- **行为**：静态后端 → 写回 `config/backends.yaml`（SnakeYAML dump，R2）+ 热重载 30s 内纳管；**动态后端 POST 拒绝**（R3）。
- **错误 400**：`name` 重复 / `url` 非法 / 缺必填 / 动态后端 POST → `{ "error": "...", "reason": "..." }`
- **断言 A-ADD-1**：静态后端新增后，网关 `list-targets` 30s 内可见（复用 SC-002）。
- **断言 A-ADD-2**：动态后端 POST → 400 + `reason: dynamic_backend_not_editable`。

### PUT `/admin/backends/{name}` — 修改

- **请求**：`UpdateBackendRequest`（`url`/`auth`/`connectTimeoutMs`/`callTimeoutMs`/`maxConcurrentTasks`，可空=不改）
- **响应 200**：`BackendDto`
- **行为**：静态后端 → 写回 YAML 热重载；**动态后端 PUT 拒绝**（R3）。
- **错误 400**：动态后端 PUT；404：未知 name。
- **断言 A-UPD-1**：静态后端改 url 后，热重载生效，后续诊断走新 url。

### DELETE `/admin/backends/{name}` — 删除

- **响应 204**：无体
- **行为**：静态后端 → 从 YAML 移除 + 热重载；动态后端 → `DynamicBackendStore.unregister` 即时移除。
- **错误 404**：未知 name。
- **断言 A-DEL-1**：删除后 `list-targets` 不再含该 target（静态经热重载 / 动态即时）。
- **断言 A-DEL-2**：删除 in-flight 后端经 002 `retirementGrace` 宽限切断（不破坏韧性）。

## 2. 异步任务结果导出（`/admin/tasks/{taskId}/export`）

### GET `/admin/tasks/{taskId}/export?format=json` — 导出

- **响应 200**：`application/json` + `Content-Disposition: attachment; filename=<taskId>.json`；体 = `TaskExportDto`（`taskId/tool/target/status/createdAt/completedAt` + `frames[]`）。
- **行为**：`frames[]` **原样来自 `TaskStore.get(taskId)` 的 `GatewayTask` 结果**，不篡改/摘要/截断（宪法原则二）。
- **错误 404**：任务不存在；**409**：任务未 `completed`（`working`/`cancelled`/`failed`）→ `{ "error": "...", "status": "...", "reason": "task_not_completed" }`。
- **断言 A-EXP-1**：导出 `completed` 任务的 `frames` 与 `task-get` 结果一致（无篡改）。
- **断言 A-EXP-2**：导出未完成/不存在任务 → 409/404，不返空体。

## 3. 错误体格式（统一）

```json
{ "error": "<简述>", "reason": "<machine_code>", "name"?: "...", "available"?: [...], "status"?: "..." }
```

错误显式传播、不静默成功（宪法原则五 / FR-010）。

## 4. 能力开关（`@ConditionalOnProperty`）

| 开关 | 默认 | 关闭时 |
|---|---|---|
| `arthas-gateway.admin.crud.enabled` | `true` | `/admin/backends/*` 全部 404 |
| `arthas-gateway.admin.export.enabled` | `true` | `/admin/tasks/*/export` 404 |

两能力独立、互不影响（R9 / FR-012）。

## 5. 鉴权

MVP Noop（受控内网，复用 001 `GatewayAuthenticator` 语义）。Bearer token 鉴权为演进项（research.md R8）。
