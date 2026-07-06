# 管理面不变量：portal 后端管理平台（004）

> 管理面（`/admin`）必须始终满足的不变量。契约端点见 [admin-api-contract.md](./admin-api-contract.md)。每条配断言 ID，供 TDD 测试锚定（宪法原则四/七）。

## I-1 管理面 / 诊断面隔离

`/admin` REST 端点的存在与调用**不影响**诊断面 `/mcp`：38 工具静态不变、双侧契约（`InitializeAndToolsListContractTest` 等）继续通过。
- **断言 INV-ISOL-1**：全量 CRUD + 导出操作期间，`/mcp` tools/list = 38、`listChanged=false`、watch/jvm 诊断正常。

## I-2 任务导出原样（宪法原则二）

`/admin/tasks/{id}/export` 的 `frames[]` 与 `arthas-gateway.task-get`（同 taskId）的结果**逐字一致**——不篡改、不摘要、不截断、不重排序。
- **断言 INV-EXP-1**：导出 JSON 的 `frames` 与 task-get 响应的对应字段深度相等。

## I-3 动态后端不可手动增改

`source=DYNAMIC` 的后端由 003 `k8s.ensure-arthas-mcp` 产生（身份 `{server}-{pod}` 派生）。`POST`（增动态）/ `PUT`（改动态）**一律拒绝**（400）；仅 `DELETE`（= `DynamicBackendStore.unregister`）允许。
- **断言 INV-DYN-1**：POST/PUT 动态后端 → 400 + `reason: dynamic_backend_not_editable`，且 `DynamicBackendStore` 状态不变。

## I-4 能力开关独立隔离

后端 CRUD 与任务导出经各自 `@ConditionalOnProperty` 独立装配。关闭其一**不影响**另一个，也不影响诊断面。
- **断言 INV-SWITCH-1**：`admin.crud.enabled=false` → `/admin/backends/*` 全 404，但 `/admin/tasks/*/export`（若开）正常、`/mcp` 正常。
- **断言 INV-SWITCH-2**：`admin.export.enabled=false` → `/admin/tasks/*/export` 404，但 `/admin/backends/*`（若开）正常。

## I-5 静态 CRUD 经文件热重载（文件 = source of truth）

静态后端 CRUD **必须**经"写回 `config/backends.yaml` → 既有 `BackendConfigWatcher`/`BackendRegistryReloader` 热重载"路径生效；portal/admin **不直接调** `BackendRegistry.rebuild`（避免绕过文件导致重启后状态不一致）。
- **断言 INV-FILE-1**：POST/PUT/DELETE 静态后端后，`backends.yaml` 文件内容相应变化；网关 `list-targets` 经热重载（≤30s）反映变化，重启后仍一致。

## I-6 错误显式传播（宪法原则五）

所有管理操作错误以**结构化 HTTP 错误体**返回（状态码 + `error`/`reason`），不得静默成功或吞为 200 空体。
- **断言 INV-ERR-1**：未知后端/任务、校验失败、动态不可改、未完成任务导出 → 对应 4xx + 错误体（无静默 200）。

## I-7 凭据脱敏

`BackendDto.auth` 仅暴露 `mode`，**不回显** `token`/`username`/`password`（机密字段脱敏，避免经管理面泄露）。
- **断言 INV-SECRET-1**：GET 列表/详情响应的 `auth` 不含 `token`/`username`/`password` 字段（或显式标记脱敏）。

## I-8 前端同源、展示层定位（v2）

前端 SPA 必须**内嵌网关 JAR、同源服务**（`vite build` → `src/main/resources/static/`，浏览器访问网关根加载），`fetch('/admin/...')` 同源（**无 CORS**）。前端仅展示（fetch + render + download），**不承载核心管理逻辑**（核心在 Java `/admin`，宪法原则六 / research.md R13）。
- **断言 INV-WEB-1**：`./mvnw verify` 产出含前端 static 的单 JAR；浏览器访问网关根加载 SPA、同源 fetch `/admin` 成功（无 CORS 预检）。
- **断言 INV-WEB-2**：核心校验/导出/热重载逻辑在后端 Java（ArchUnit 守护 `admin` 包承载），前端无独立业务规则（仅 fetch + render + download）。

## I-9 异步任务列表（增量，FR-015）

`GET /admin/tasks` 列表查询必须满足：摘要纯（无 frames）、分页元数据一致、排序确定、与导出端点同开关。

- **断言 INV-LIST-1**：`TaskSummaryDto` **禁含 `frames`**（摘要纯；frames 仅由 `/admin/tasks/{id}/export` 提供，INV-EXP-1）。
- **断言 INV-LIST-2**：`total` = 过滤后、分页前的总数（与 `items` 分页独立；`items.length ≤ size`，但 `total` 可大于 `items.length`）。
- **断言 INV-LIST-3**：`items` 按 `createdAt` **倒序**（最新在前）；同 createdAt 顺序不依赖（taskId 去重由 TaskStore 保证）。
- **断言 INV-LIST-4**：`arthas-gateway.admin.export.enabled=false` → `GET /admin/tasks` 与 `GET /admin/tasks/{id}/export` **同 404**（与 export 共用开关，yaml 驱动）。
