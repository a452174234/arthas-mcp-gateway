# Data Model: portal 后端管理平台（004 增量）

> 本文定义 004 相对 [001 data-model](../001-arthas-mcp-gateway/data-model.md) 与 [003 data-model](../003-k8s-arthas-mcp-launch/data-model.md) 的**增量**。001 既有 `BackendConfig`/`BackendEntry`/`BackendRegistry`/`RegistryHolder`/`BackendRegistryReloader`/`GatewayTask`/`TaskStore`/`CircuitBreaker` 与 003 `DynamicBackendStore`/`Source`/`OrchestrationRecord` 语义**不变**，本文仅记 004 新增 DTO、请求载体与受影响处。决策见 [research.md](./research.md)。

---

## 1. 新增 DTO（`admin` 包，只读投影 / 请求载体，不持久化）

### 1.1 `BackendDto`（后端配置 CRUD 响应，GET 列表/详情）

只读投影，数据源自 `BackendRegistry` + `BackendEntry`（001）。

| 字段 | 类型 | 来源 | 说明 |
|---|---|---|---|
| `name` | String | BackendConfig.name | 逻辑名（target） |
| `source` | enum `STATIC`\|`DYNAMIC` | BackendConfig.source（003） | STATIC=YAML 种子；DYNAMIC=003 ensure 注册 |
| `state` | enum `ACTIVE`\|`RETIRED` | BackendEntry.state（001） | 运行态 |
| `healthy` | boolean | BackendEntry 健康检查 | 最近一次健康探测 |
| `breaker` | enum `CLOSED`\|`OPEN` | CircuitBreaker（001） | 熔断状态 |
| `url` | String | BackendConfig.url | arthas MCP 根 URL |
| `protocol` | enum `STREAMABLE`\|`STATELESS` | BackendConfig.protocol | |
| `auth` | `{mode, ...}` | BackendConfig.auth | mode 仅暴露（凭据脱敏，不回显 token） |
| `connectTimeoutMs` / `callTimeoutMs` / `maxConcurrentTasks` | int | BackendConfig | |

**列表响应**额外含 `summary: {total, healthy, unhealthy}`。

### 1.2 `TaskExportDto`（任务导出响应）

| 字段 | 类型 | 来源 |
|---|---|---|
| `taskId` / `tool` / `target` / `status` | — | GatewayTask（001） |
| `createdAt` / `completedAt` | Instant | GatewayTask |
| `frames[]` | 原样结果帧 | GatewayTask 结果 content（**原样透传，宪法原则二**） |

### 1.3 请求载体（CRUD 入参）

- `CreateBackendRequest`：`name`/`url`/`protocol`/`auth`/`connectTimeoutMs`/`callTimeoutMs`/`maxConcurrentTasks`（缺省值同 `BackendConfigLoader` 默认）。
- `UpdateBackendRequest`：`url`/`auth`/`connectTimeoutMs`/`callTimeoutMs`/`maxConcurrentTasks`（可空=不改）。

---

## 2. 复用既有（零改动）

| 既有实体 | 复用点 |
|---|---|
| `BackendConfig`（001） | 静态/动态后端配置载体 |
| `BackendEntry`（001） | 注册表条目（config+client+breaker+taskSlots） |
| `BackendRegistry`（001） | 查询/健康视图数据源 |
| `DynamicBackendStore`（003） | 动态后端 DELETE → `unregister` |
| `TaskStore` / `GatewayTask`（001） | 导出数据源 |
| `BackendRegistryReloader` + WatchService（001） | 静态后端写回 YAML 触发热重载（SC-002） |

---

## 3. 状态机（复用 001/003，不新增）

- **后端 state**：`ACTIVE → RETIRED`（001）。CRUD `DELETE` 静态后端经热重载移除；DELETE 动态后端经 `unregister` 即时移除。
- **任务 status**：`WORKING → COMPLETED | CANCELLED | FAILED`（001）。导出**仅 `COMPLETED`** 可导出（`WORKING`/`CANCELLED`/`FAILED` → 409/404）。

---

## 4. 校验规则（CRUD）

- **name 唯一**：静态 + 动态全局唯一（复用 003 冲突检测 I-3）；重复 → 400。
- **url 非空 + 合法**：`http(s)://...`，非法 → 400。
- **动态后端 POST/PUT 拒绝**：动态后端由 003 ensure 产生，不可手动增改（research.md R3）→ 400。
- **DELETE in-flight 后端**：复用 002 `retirementGrace` 宽限切断 in-flight（不破坏既有韧性）。

---

## 5. 配置增量（`application.yml`）

```yaml
arthas-gateway:
  admin:
    crud:
      enabled: true   # 后端配置 CRUD（@ConditionalOnProperty，默认开；关闭则 /admin/backends 不暴露）
    export:
      enabled: true   # 异步任务结果导出（默认开；关闭则 /admin/tasks/*/export 不暴露）
```

`@ConditionalOnProperty(name="arthas-gateway.admin.crud.enabled", havingValue="true", matchIfMissing=true)`；两能力独立开关、互不影响（research.md R9 / spec FR-012）。
