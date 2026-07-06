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

## 2. 异步任务（`/admin/tasks`）

### GET `/admin/tasks` — 列表查询（增量，FR-015）

- **查询参数（全可选）**：
  - `status`（enum: `WORKING`/`COMPLETED`/`FAILED`/`CANCELLED`）：状态过滤，复用 `TaskStore.list(TaskState)`；缺省=全部
  - `tool`（string）：工具名**精确**匹配（如 `watch`）
  - `target`（string）：target 名**精确**匹配（如 `debian-demo-business`）
  - `page`（int ≥ 0，默认 `0`）：页码（0-based）
  - `size`（int 1..100，默认 `20`）：每页条数；`>100` clamp 100、`<1` 取 1
- **响应 200**：`{ "items": [TaskSummaryDto], "total": N, "page": P, "size": S }`
  - `items`：当前页摘要，按 `createdAt` **倒序**（最新在前）
  - `total`：**过滤后、分页前**的总数（分页元数据独立）
  - `TaskSummaryDto`：`taskId`/`tool`/`target`/`status`/`createdAt`/`completedAt`/`isError`（**无 frames**，INV-LIST-1；`isError` 仅 `COMPLETED` 时据 `result.isError()`，其余态 `false`）
- **行为**：空结果返 200 + `items=[]`/`total=0`（**非 404**）；`page`/`size` 越界 clamp（不报 400）。
- **断言 A-LIST-TASKS-1**：`items` 按 `createdAt` 倒序；`total` = 过滤后总数（与分页独立，INV-LIST-2/3）。
- **断言 A-LIST-TASKS-2**：`status`/`tool`/`target` 组合过滤后，`items` 仅含匹配项，`total` 同步反映。
- **能力开关**：`arthas-gateway.admin.export.enabled`（与 export 共用，关则 404，INV-LIST-4）。

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
