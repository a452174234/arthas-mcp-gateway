# portal 后端管理平台设计（004 特性）

**日期**: 2026-06-25
**特性分支**: `004-portal-backend-management`
**来源**: 003 spec 用户故事 3（P3 portal）后置项的"配置管理"子集落地
**状态**: 已 brainstorm，待 spec-kit SDD（spec → plan → tasks → implement）

---

## 一、背景

003 spec 用户故事 3（portal 独立管理后端，P3）在 003 仅作占位（003 实施 P1/US1）。本特性落地 P3 的**配置管理**子集，补齐 001/003 遗留的管理面缺口。

### 妥协未完成的管理能力（证据驱动梳理）

| # | 能力 | 现状（妥协） | 缺口 | 证据 |
|---|---|---|---|---|
| A | 后端配置管理面 | 手编 `config/backends.yaml` + 热重载（SC-002），无 API/CLI | 运维须 SSH 改 YAML | 001 spec 假设§110；宪法原则三/五 |
| C | 异步任务结果导出 | `TaskStore` 仅 task-get 同步返 JSON 文本 | 长 watch 多帧结果无法离线分析/归档 | `task/TaskStore.java`；001 演进注记 |
| B（后置） | 动态后端持久化 | `DynamicBackendStore` in-memory，重启全丢 | ensure 纳管 target 重启即失 | 003 data-model §144 |
| D（后置） | 操作历史/审计 | `OrchestrationRecordStore` in-memory 覆盖、无查询面 | ensure 历史/CRUD 无审计 | 003 data-model §144 |

**用户裁决**：MVP = A + C（B/D 后置）。"只做配置管理"——不碰 K8S 编排（list-pods/ensure 仍由 003 `k8s.*` MCP 工具经 Claude Code）。

---

## 二、brainstorming 决策记录

| 决策点 | 选定 | 备选（否决理由） |
|---|---|---|
| 部署形态 | **单 JAR 多 profile**（picocli 双入口） | 独立双 JAR（需抽共享库/跨进程，MVP 偏重）；同进程管理端点（违背"并列"语义） |
| 集成方式 | **网关 HTTP 管理 API**（portal 远程调 `/admin`） | 同 JVM 直接调用（不可分离）；共享 backends.yaml（仅静态、并发写风险） |
| 持久化 | **YAML**（`config/backends.yaml` 复用） | 内嵌 DB（MVP 偏重）；in-memory（重启丢失） |
| 鉴权 | **Noop**（受控内网，复用 001 语义） | Bearer token / Basic Auth（演进项加） |
| CLI 库 | **picocli**（自动 --help/补全/退出码） | 手写解析（维护成本）；Spring Shell（偏 REPL） |
| MVP 范围 | **A 后端配置 CRUD + C 异步任务导出** | 全选 A+B+C+D（B/D 后置） |

---

## 三、架构

```
┌─────────────────────────────────────────────────────────┐
│  arthas-mcp-gateway.jar（单产物，picocli 路由入口）       │
│                                                         │
│  入口①  java -jar ... (默认 serve)                      │
│    └─ 网关进程（同 JVM）：                               │
│       ├─ 诊断面 /mcp        （001/003 既有，不动）       │
│       └─ 管理面 /admin/*    （004 新增）                 │
│           ├─ /admin/backends       CRUD（A）             │
│           └─ /admin/tasks/{id}/export  导出（C）         │
│                                                         │
│  入口②  java -jar ... portal <sub>                      │
│    └─ CLI client（picocli，HTTP 调入口① 的 /admin）      │
│       ├─ portal backends list/get/add/remove/update     │
│       └─ portal tasks export <taskId> [-o file]         │
└─────────────────────────────────────────────────────────┘
```

**复用既有**（零侵入）：
- 后端注册表：`BackendRegistry` + `BackendEntry`（001）→ 查询/健康视图数据源
- 动态注册：`DynamicBackendStore.register/unregister`（003）→ 动态后端增删
- 热重载：`BackendRegistryReloader`（001）→ 静态后端写回 `backends.yaml` 后自动生效
- 任务存储：`TaskStore`（001）→ 导出数据源

**新增包**（包级边界，沿用 003 单 Maven 模块）：
- `com.arthas.gateway.admin`（网关侧）：REST 控制器（`BackendAdminController`、`TaskExportController`）+ DTO
- `com.arthas.gateway.portal`（CLI 侧）：picocli 命令（`PortalCommand`、`BackendsCommand`、`TasksCommand`）+ HTTP client（`GatewayAdminClient`）

**边界约束**：`admin`/`portal` 包是网关侧新增；`gateway-core`（backend/handler/config/tool/task/auth/obs）零 K8S 依赖不变（003 ArchUnit 守护继续通过）；`/admin` 端点与 `/mcp` 诊断面隔离，互不影响。

---

## 四、能力详述

### A. 后端配置 CRUD（含健康只读视图）

**网关管理端点**（`/admin/backends`）：

| 方法 | 路径 | 行为 | 后端落点 |
|---|---|---|---|
| GET | `/admin/backends` | 列表 + summary | 读 `BackendRegistry` |
| GET | `/admin/backends/{name}` | 单后端详情 | 读 `BackendRegistry` |
| POST | `/admin/backends` | 增（body: name/url/protocol/auth/timeouts/maxConcurrent） | 静态→写 `backends.yaml`；动态→`DynamicBackendStore.register` |
| PUT | `/admin/backends/{name}` | 改（url/auth/timeouts） | 静态→改 `backends.yaml`；动态→拒绝（动态后端不可改，须先删再 ensure） |
| DELETE | `/admin/backends/{name}` | 删 | 静态→从 `backends.yaml` 移除；动态→`DynamicBackendStore.unregister` |

**响应 DTO**（`BackendDto`）：`name`、`source`（STATIC/DYNAMIC）、`state`（ACTIVE/RETIRED）、`healthy`（bool）、`breaker`（CLOSED/OPEN）、`url`、`protocol`、`auth.mode`、`connectTimeoutMs`、`callTimeoutMs`、`maxConcurrentTasks`。

**持久化策略**：
- 静态后端：POST/PUT/DELETE 写回 `config/backends.yaml`，复用 001 `BackendRegistryReloader` 热重载（SC-002，30s 内生效，与 001 一致）。
- 动态后端：POST/DELETE 调 `DynamicBackendStore`（即时生效）；**MVP 不持久化**（B 后置），重启即失——spec 显式记录此限制。
- 静态 YAML 写入须保留既有种子格式与注释（结构化写回，不破坏热重载语义）。

**CLI**：`portal backends list | get <name> | add <name> --url ... | remove <name> | update <name> [--url ...]`。

### C. 异步任务结果导出

**网关管理端点**：
- `GET /admin/tasks/{taskId}/export?format=json` → 返回完整任务结果作可下载 JSON 文件（`Content-Disposition: attachment; filename=<taskId>.json`）。

**导出内容**（`TaskExportDto`）：`taskId`、`tool`、`target`、`status`、`createdAt`、`completedAt` + `frames[]`（watch 的 accessPoint/className/methodName/cost/ts/value 等，**原样来自 TaskStore，宪法原则二"原样透传"**）。

**CLI**：`portal tasks export <taskId> [-o <file>]`（默认 stdout，`-o` 写文件）。

**MVP 限制**：仅 `completed` 任务可导出（working/cancelled 返回 409）；全量 JSON（大结果不分页/流式，后置）；仅 JSON 格式（CSV/HTML 后置）。

---

## 五、错误处理

| 场景 | HTTP | 与既有一致 |
|---|---|---|
| 未知后端 | 404 + `{error, available[]}` | 001 路由拒绝 |
| 未知任务 / 任务未完成 | 404 / 409 | — |
| CRUD 校验失败（重复 name、非法 URL、改动态后端） | 400 + 详情 | — |
| backends.yaml 写入失败（IO/锁） | 500 + 详情 | 透明记录（宪法原则五） |

错误显式传播，不静默成功（宪法原则五）。

---

## 六、测试策略

**TDD（宪法原则七）+ 双侧契约（原则四）+ 真实零桩**：

- **管理面 API 契约测试**（HTTP client 断言）：CRUD 各端点形状、状态码、错误码、DTO 字段；导出端点形状 + Content-Disposition。
- **真实环境**（零桩，failsafe `*IT`）：
  - 后端 CRUD 真实写 `backends.yaml` + 热重载生效（SC-002 30s）；动态后端真实 register/unregister + 网关 `list-targets` 感知。
  - 导出真实 watch 任务结果（先 ensure→watch 产真实任务，再导出，断言 frames 含真实 hotMethod 诊断）。
- **回归守护**：001/002/003 既有测试 + 38 工具契约不破（`InitializeAndToolsListContractTest`）；`/admin` 不影响 `/mcp`；`PackageBoundaryTest`（gateway-core 零 K8S）继续通过；新增 admin/portal 包不破包边界。
- **CLI 测试**：picocli 命令端到端（起真实网关 → CLI 调 /admin → 断言输出）。

---

## 七、非目标（MVP 不做）

- B 动态后端持久化 + 重启恢复（后置）
- D 操作历史/审计查询（后置）
- K8S 编排（list-pods/list-services/ensure，仍 003 `k8s.*` MCP 工具经 Claude Code）
- servers 清单管理（不归 portal）
- Web UI（003 spec 后续增强）
- 管理面鉴权（Noop，演进项）
- 任务导出分页/流式/多格式（后置）

---

## 八、演进项

- B：动态后端落盘 + 重启自动恢复纳管
- D：ensure 历史 + 后端 CRUD 操作审计查询
- 管理面 Bearer token 鉴权
- 任务导出分页/流式/CSV/HTML
- 多服务器/多集群（003 边缘）

---

## 九、与宪法的对齐

| 原则 | 对齐 |
|---|---|
| 二 透明无损聚合 | 任务导出原样透传 TaskStore 结果，不改写 |
| 三 连接生命周期与故障韧性 | 后端 CRUD 复用 BackendRegistry 一等对象管理 |
| 四 双侧契约优先 | 管理 API 契约测试先于实现 |
| 五 可观测性与可诊断性 | 暴露后端/健康/任务，运维无需读源码 |
| 六 Java 主力 | admin/portal 包 Java 实现（picocli 为 Java 库） |
| 七 TDD | 测试先于实现（红-绿-重构） |
