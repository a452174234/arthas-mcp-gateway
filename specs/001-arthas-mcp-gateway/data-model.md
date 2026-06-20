# Data Model: Arthas MCP 网关（Phase 1）

**Feature**: 001-arthas-mcp-gateway
**Date**: 2026-06-19

> 本文档定义网关运行期的**核心实体、字段、关系、状态机与校验规则**。网关无持久化存储（宪法无要求），所有实体为**运行期内存态**或**配置态**。MCP 协议层实体（Tool/CallToolResult/JSON-RPC error 等）沿用官方 SDK 定义，不在此重复——本文聚焦网关**自有**领域模型。决策依据见 [research.md](./research.md)，行为契约见 `contracts/`。

---

## 1. 实体总览

| 实体 | 类型 | 职责 | 生命周期 |
|---|---|---|---|
| `BackendConfig` | 配置态 | 单后端的声明（逻辑名/地址/认证/超时/并发） | 配置文件，热重载 |
| `BackendEntry` | 运行期 | 单后端的运行对象（client + 熔断 + 限流） | 注册表替换时优雅下线 |
| `BackendRegistry` | 运行期 | 全部后端的不可变快照（逻辑名→Entry） | AtomicReference 原子替换 |
| `ExposedTool` | 启动期 | 对调用方暴露的单个工具（arthas 工具 + target 注入 / 或网关自有工具） | 进程级只读 |
| `GatewayTask` | 运行期 | 一次异步诊断任务（taskId→状态/结果） | TTL 清理 |
| `DiagnosticRequest` | 瞬态 | 一次 tools/call 的解析结果（路由用） | 单次调用 |

---

## 2. BackendConfig（配置态）

单后端声明，源自 `config/backends.yaml`。

| 字段 | 类型 | 必填 | 说明 / 校验 |
|---|---|---|---|
| `name` | string | 是 | 逻辑名，唯一标识（如 `order-service`）；`target` 参数取值即此。注册表内唯一，重复→加载失败保留旧表 |
| `url` | string | 是 | 后端 MCP 端点，复用 arthas http console，形如 `http://host:8563/mcp`；须合法 URL |
| `protocol` | enum | 是 | `STREAMABLE` \| `STATELESS`；决定是否可用 task（STATELESS 不支持）。默认 `STREAMABLE` |
| `auth.mode` | enum | 是 | `BEARER` \| `BASIC` \| `NONE`；MVP 受控内网多为 NONE/BEARER |
| `auth.token` | string | BEARER 时必填 | Bearer token == 后端配置 password（[后端接入契约](../../reference/arthas-docs/03-MCP/后端接入契约.md) §2.3） |
| `auth.username`/`password` | string | BASIC 时必填 | base64(user:pass) |
| `connectTimeoutMs` | int | 否 | TCP + initialize 握手超时，默认 5000 |
| `callTimeoutMs` | int | 否 | 单次同步 `tools/call` 超时，默认 30000（对齐 SC-003） |
| `maxConcurrentTasks` | int | 否 | task 并发上限，默认/硬上限 5（后端约束） |

**校验规则**：name 唯一、url 合法、auth 与 mode 对应（BEARER 须 token / BASIC 须 user+pass）；校验失败→**保留旧注册表**，记 ERROR 日志，不半替换（原子性）。

**配置版本**：`version`（单调递增整数），热重载去重（重复 version 忽略）。

---

## 3. BackendEntry（运行期）

| 字段 | 类型 | 说明 |
|---|---|---|
| `config` | BackendConfig | 不可变声明 |
| `client` | BackendClient | 官方 SDK `HttpClientStreamableHttpTransport` 封装，独立连接池 + 独立 McpClient 会话 + 独立 SSE 解析 |
| `breaker` | CircuitBreaker | 熔断器（CLOSED/OPEN/HALF_OPEN） |
| `taskSlots` | Semaphore | `maxConcurrentTasks`（默认 5）许可；异步任务 acquire/release |
| `state` | enum | `ACTIVE` \| `RETIRED`（热重载移除时标 RETIRED，in-flight 调用可完成） |

**不变量**：一次 `tools/call` 全程持有固定的 `BackendEntry` 引用（final）；registry 替换不影响 in-flight 调用（满足边缘情况"热重载并发不串台"）。

---

## 4. BackendRegistry（运行期，不可变快照）

| 字段 | 类型 | 说明 |
|---|---|---|
| `version` | long | 配置版本号 |
| `byName` | Map<String, BackendEntry> | 逻辑名→Entry |

**操作**：`get(name)`、`names()`；由 `RegistryHolder` 经 `AtomicReference` 持有，热重载时整体替换（`getAndSet` + 异步优雅下线旧 Entry）。

---

## 5. ExposedTool（启动期，对调用方暴露）

两类，统一模型：

### 5.1 arthas 工具（31 个）

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | string | 工具名（如 `watch`），摘抄自 [MCP能力清单](../../reference/arthas-docs/03-MCP/MCP能力清单.md) |
| `description` | string | 摘抄自 `@Tool.description` |
| `inputSchema` | JSON Schema | arthas 原始 schema **逐字拷贝** + 注入 `target`（string, required, 进 properties.required 与顶层 required）；`additionalProperties:false` 不变 |
| `taskSupport` | enum | `forbidden`（27）/ `optional`（5）；照实暴露 |
| `routingMode` | enum（内部） | `SYNC_DIRECT`（26 即时）/ `STREAM_AGGREGATE`（dashboard）/ `ASYNC_TASK`（5 optional，方案 C）；不进协议 |

### 5.2 网关自有工具（4 个）

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | string | `arthas-gateway.list-targets` / `task-get` / `task-list` / `task-cancel` |
| `description` | string | 中文说明用途 |
| `inputSchema` | JSON Schema | 各自参数（见 `contracts/gateway-tools-contract.md`） |
| `routingMode` | enum | `GATEWAY_LOCAL`（不转发后端） |

**总数**：35。`tools/list` 返回不可变快照，启动期构建。

---

## 6. GatewayTask（运行期，应用层异步任务）

方案 C 的核心实体。

| 字段 | 类型 | 说明 |
|---|---|---|
| `taskId` | string | 网关生成，唯一（如 `t-7f3a...`） |
| `status` | enum | 见 §7 状态机 |
| `toolName` | string | 发起任务的原工具（watch/trace/...） |
| `target` | string | 目标逻辑名 |
| `createdAt` | instant | 创建时间（传入，非进程内取时） |
| `completedAt` | instant? | 完成时间 |
| `result` | CallToolResult? | 后端返回的最终结果（completed 时） |
| `error` | object? | 失败原因（failed 时，含 reason/message） |
| `backendFuture` | Future | 后台阻塞等后端路①的句柄（cancel 用） |

**存储**：`TaskStore`（内存 `Map<taskId, GatewayTask>`），TTL 清理（完成后保留可查询，如 1 小时；过期移除）。

**状态查询**：`task-get(taskId)` → 返回 status +（completed）result /（failed）error；`task-list()` → 返回全部任务概要；`task-cancel(taskId)` → 关 future + 标 cancelled。

---

## 7. GatewayTask 状态机

```text
                 ┌───────── 后端返回结果 ─────────┐
                 │                                  ▼
  working ──────────────────► completed
     │
     ├─── 后台超时(11min)/后端错误 ─► failed
     │
     └─── task-cancel 调用 ──────► cancelled
```

| 状态 | 含义 | 终态？ |
|---|---|---|
| `working` | 后台阻塞等后端中 | 否 |
| `completed` | 后端返回，结果已存 result | 是 |
| `failed` | 后台超时/后端错误/熔断 | 是 |
| `cancelled` | 调用方经 task-cancel 取消 | 是 |

**精简说明**：不做 arthas 的 `INPUT_REQUIRED` 多档复杂态（协议层不暴露真 task，无两阶段输入交互）。

**转换规则**：
- `working→completed`：后台 future 正常返回 CallToolResult（**原样存入 result，包括 `isError=true`**——后端业务错误不转为 failed，failed 仅限基础设施故障，对齐宪法原则五"错误显式传播"）。
- `working→failed`：后台超时（>11min）/ 连接错误 / 熔断 OPEN。
- `working→cancelled`：`task-cancel` 调用，关 future（已 in-flight 的后端请求由 callTimeout 兜底回收）。
- 终态不可逆。

---

## 8. CircuitBreaker 状态机（故障隔离）

```text
  CLOSED ──连续 N(=3) 次失败──► OPEN ──退避后──► HALF_OPEN ──探测成功──► CLOSED
                                  ▲                               │
                                  └────────探测失败───────────────┘
```

| 状态 | 行为 |
|---|---|
| `CLOSED` | 正常转发 |
| `OPEN` | 立即返回明确错误（不等 30s），data 含 `reason=backend_unreachable` + `available` 列表 + `retryAfterMs` |
| `HALF_OPEN` | 放 1 个探测请求，成功→CLOSED，失败→OPEN |

**失败计入**：连接拒绝/超时、initialize 失败、读超时、SSE 异常中断。**不计入**：后端业务错误（`isError=true`/INVALID_PARAMS）——那是正常响应（宪法原则五）。退避：base 1s、×2、cap 30s。

---

## 9. DiagnosticRequest（瞬态，路由用）

`tools/call` 解析后的中间对象。

| 字段 | 类型 | 说明 / 校验 |
|---|---|---|
| `toolName` | string | 工具名；未命中注册表 → INVALID_PARAMS(-32602) |
| `target` | string? | 从 arguments 取出并剥离；缺失/空 → INVALID_PARAMS；不在册 → INVALID_PARAMS + data 附可用列表 |
| `backendArgs` | Map | 剥离 target 后的剩余键（原样转发后端） |
| `routingMode` | enum | 由工具的 routingMode 决定（SYNC_DIRECT/STREAM_AGGREGATE/ASYNC_TASK/GATEWAY_LOCAL） |

---

## 10. 关系图

```text
  config/backends.yaml
        │ 加载/热重载
        ▼
  BackendConfig ──► BackendEntry ──┐ (client, breaker, taskSlots)
        │                          │
        ▼                          ▼
  BackendRegistry (AtomicReference) ◄── RegistryHolder

  StaticToolRegistry (启动期) ──► ExposedTool[] (35)
        │
        ▼
  ToolsCallRouter
        │  解析
        ▼
  DiagnosticRequest ──target──► BackendEntry
        │                          │ client.callTool
        │  (ASYNC_TASK)            ▼
        └──► AsyncTaskExecutor ──► 后端(同步路①) ──► GatewayTask (TaskStore)
                                                  ▲
                                  task-get/list/cancel 查询
```

---

## 11. 校验与边界规则汇总

1. `target` 缺失/空/不在册 → INVALID_PARAMS(-32602)；不在册时 data 附当前可用列表（边缘情况）。
2. 未知工具名 → INVALID_PARAMS（对齐 arthas `McpNettyServer.java:398-411`）。
3. `target` 永不进 `backendArgs`（避免后端 INVALID_PARAMS）。
4. 后端 `isError=true` 原样透传（同步）/ 原样存入 task.result（异步）——不吞为成功。
5. 后端 JSON-RPC error 原样透传 code/message/data。
6. per-target task 并发 > `maxConcurrentTasks`(5) → 网关前置限流，返回 INVALID_PARAMS（避免触发后端越界错误）。
7. 配置加载/校验失败 → 保留旧注册表，记 ERROR，不半替换。
8. 热重载替换 registry → in-flight 调用持有旧 Entry 可完成；新调用才见新表。
