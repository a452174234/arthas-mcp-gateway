# Feature Specification: portal 后端管理平台

**Feature Branch**: `004-portal-backend-management`

**Created**: 2026-06-25

**Status**: Draft

**Input**: 用户描述："准备开始实现 后端管理平台……你自己明确下之前有哪些是妥协导致没有完成的管理能力，加上异步任务的结果导出能力，为 portal 平台的管理能力范围。"

> **设计来源**：本 spec 由 `superpowers:brainstorming` 沟通收敛，完整决策记录与架构见 [2026-06-25-portal-backend-management-design.md](../../docs/superpowers/specs/2026-06-25-portal-backend-management-design.md)。本特性落地 003 spec 用户故事 3（P3 portal）的**配置管理子集**。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 后端配置 CRUD 管理（Priority: P1）

运维人员经 portal 的 HTTP API 与 CLI，对网关的后端注册表进行**程序化查询/增/删/改**（含静态种子与 003 动态纳管的后端），并查看每后端的健康/熔断状态，无需 SSH 手编 `config/backends.yaml`。

**Why this priority**: 001 以来后端管理一直靠手编 YAML（妥协项，001 spec 假设§110），是管理面最基础、最高频的能力，也是宪法原则三（后端为受管理一等对象）与原则五（暴露已注册后端/健康状况，运维无需读源码）的直接落地。导出能力（US2）依赖任务系统而非后端管理，故 US1 先行。

**Independent Test**: 经 portal CLI 完成「`backends list` 见既有种子 → `backends add` 新增静态后端 → 网关 `list-targets` 30s 内感知 → `backends remove` 删除 → 网关感知移除」全链路，全程不手编 YAML、不直接触达网关配置文件。

**Acceptance Scenarios**:

1. **Given** 网关运行（含若干静态种子 + 003 ensure 纳管的动态后端），**When** 运维经 `GET /admin/backends`（或 `portal backends list`），**Then** 返回全部后端清单，每条含 `name/source(STATIC|DYNAMIC)/state/healthy/breaker` 及 `url/protocol/auth` 等配置字段 + 汇总（total/healthy/unhealthy）。
2. **Given** portal，**When** 经 `POST /admin/backends`（或 `portal backends add`）新增静态后端（name+url+认证），**Then** 写回 `config/backends.yaml`，网关热重载在 30s 内将其纳管（复用 001 SC-002），`list-targets` 可见。
3. **Given** portal，**When** 经 `DELETE /admin/backends/{name}` 删除静态后端，**Then** 从 `backends.yaml` 移除并热重载生效；删除动态后端则调 `DynamicBackendStore.unregister` 即时移除。
4. **Given** portal，**When** 经 `PUT /admin/backends/{name}` 改静态后端的 url/auth/timeouts，**Then** 写回 YAML 并热重载生效；尝试改动态后端 → 拒绝并返回明确错误（动态后端须先删再 ensure）。

---

### User Story 2 - 异步任务结果导出（Priority: P2）

运维或开发者经 portal 的 HTTP API 与 CLI，把网关异步任务（`watch`/`trace`/`stack`/`tt`/`monitor`）的完整结果**导出为 JSON 文件**，供离线分析、归档与分享——补齐 001 以来"仅 task-get 同步返 JSON 文本、无文件导出"的缺口。

**Why this priority**: 用户明确要求的新能力。依赖 US1 之外的任务系统（`TaskStore`），且后端管理（US1）是更基础的管理面，故 P2。

**Independent Test**: 经 portal CLI 完成「触发一次真实 watch 任务 → 轮询至 completed → `portal tasks export <taskId> -o result.json` → 断言导出文件含任务元信息 + 真实诊断帧（hotMethod 的 accessPoint/className/value 等）」。

**Acceptance Scenarios**:

1. **Given** 网关有一个 `completed` 的异步任务，**When** 经 `GET /admin/tasks/{taskId}/export?format=json`（或 `portal tasks export`），**Then** 返回可下载 JSON 文件（`Content-Disposition: attachment`），含 `taskId/tool/target/status/createdAt/completedAt` + `frames[]`（原样来自 TaskStore，不篡改/截断）。
2. **Given** 任务处于 `working` 或 `cancelled` 或不存在，**When** 导出，**Then** 返回明确错误（409/404），不静默返回空。

---

### Edge Cases

- **未知后端名**：GET/PUT/DELETE → 404 + `available[]` 可用后端提示（与 001 路由拒绝一致）。
- **未知/未完成任务导出**：404（未知）/ 409（working/cancelled，仅 completed 可导出）。
- **CRUD 校验失败**：重复 name、非法 URL、缺必填字段、改动态后端 → 400 + 详情。
- **`backends.yaml` 写入失败**（IO 错误、文件锁、磁盘满）→ 500 + 详情，透明记录（宪法原则五），不静默成功。
- **静态与动态后端 name 冲突**：增静态时与既有动态同名 → 拒绝（003 已有冲突检测 I-3，复用）。
- **并发改 YAML**：MVP 管理面单用户，写回串行化（无锁），spec 显式记录此假设。
- **删除 in-flight 后端**：复用 002 `retirementGrace` 宽限切断 in-flight（不破坏既有韧性）。

## Requirements *(mandatory)*

### Functional Requirements

**后端配置 CRUD（US1）**

- **FR-001**: 系统必须提供**独立于诊断面 `/mcp` 的管理 HTTP API**（`/admin/backends`），支持对网关后端注册表的查询、新增、删除、修改；管理面与诊断面隔离，互不影响。
- **FR-002**: `GET /admin/backends` 必须返回全部后端清单，每条含 `name`、`source`（STATIC/DYNAMIC）、`state`、`healthy`、`breaker`、`url`、`protocol`、`auth.mode`、`connectTimeoutMs`、`callTimeoutMs`、`maxConcurrentTasks`，以及 `summary`（total/healthy/unhealthy）——运维无需读源码即可理解后端状态（宪法原则五）。
- **FR-003**: `POST /admin/backends` 增静态后端必须写回 `config/backends.yaml` 并复用 001 热重载在 30s 内纳管（复用 SC-002）；增动态后端必须调 `DynamicBackendStore.register` 即时生效。
- **FR-004**: `DELETE /admin/backends/{name}` 删静态后端必须从 `backends.yaml` 移除并热重载；删动态后端必须调 `DynamicBackendStore.unregister` 即时移除。
- **FR-005**: `PUT /admin/backends/{name}` 改静态后端（url/auth/timeouts）必须写回 YAML 热重载；动态后端不可改（返回明确错误）。
- **FR-006**: 系统必须提供 **CLI**（`portal backends list/get/add/remove/update`），经 HTTP 调用 `/admin` 端点，供人类直接操作（非 agentic）。

**异步任务结果导出（US2）**

- **FR-007**: `GET /admin/tasks/{taskId}/export?format=json` 必须把 `completed` 任务的完整结果（元信息 + 结果帧）作为可下载 JSON 文件返回；结果帧**原样来自 `TaskStore`，不得篡改/摘要/截断**（宪法原则二）。
- **FR-008**: 导出 `working`/`cancelled`/不存在的任务必须返回明确错误（409/404），不静默返回空。
- **FR-009**: 系统必须提供 **CLI**（`portal tasks export <taskId> [-o <file>]`），默认输出 stdout，`-o` 写文件。

**贯穿（错误处理 + 测试）**

- **FR-010**: 所有管理操作错误必须**结构化、显式传播**（HTTP 状态码 + 错误体），不得静默成功（宪法原则五）。
- **FR-011**: 管理面必须以 **TDD** 开发（测试先于实现），契约测试 + 真实环境零桩测试并存（宪法原则四/七）。

### Key Entities *(include if feature involves data)*

- **BackendDto**（管理面响应）：后端配置 + 运行时状态的只读投影——`name`/`source`/`state`/`healthy`/`breaker`/`url`/`protocol`/`auth`/`connectTimeoutMs`/`callTimeoutMs`/`maxConcurrentTasks`。数据源自 `BackendRegistry` + `BackendEntry`（001）。
- **TaskExportDto**（导出响应）：`taskId`/`tool`/`target`/`status`/`createdAt`/`completedAt` + `frames[]`。数据源自 `TaskStore`（001）。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 经 portal API/CLI 增/删/改静态后端后，网关 `list-targets` 在 **30s 内**反映变化（复用 001 SC-002 热重载语义）。
- **SC-002**: 经 portal API/CLI 导出一个 `completed` 的真实 watch 任务，导出文件含**完整、原样**的诊断帧（accessPoint/className/methodName/cost/ts/value），与 `task-get` 结果一致（无篡改）。
- **SC-003**: `GET /admin/backends` 返回的后端状态（healthy/breaker）与 `/actuator/health` details 一致，运维无需读源码（宪法原则五）。
- **SC-004**: 管理面（`/admin`）全量 CRUD + 导出操作期间，诊断面（`/mcp`）的既有 38 工具调用与双侧契约不受影响（回归不破 001/002/003）。

## Assumptions

- 部署在**受控内网**，MVP 管理面**无鉴权**（Noop，复用 001 `GatewayAuthenticator` 语义）；Bearer token 鉴权为演进项。
- 复用既有：001 `BackendRegistry`/`BackendEntry`/`BackendRegistryReloader`（热重载）/`TaskStore`、003 `DynamicBackendStore`（动态注册）；本特性不重建这些，仅以管理面封装暴露。
- **单 Maven 模块**（沿用 003 research.md R1 包级边界），新增 `com.arthas.gateway.admin`（网关侧 REST）+ `com.arthas.gateway.portal`（CLI client）两包；`gateway-core` 零 K8S 依赖不变。
- **单产物双入口**：`arthas-mcp-gateway.jar` 经 picocli 路由——默认 `serve` 起网关（诊断面 + 管理面同 JVM），`portal <sub>` 跑 CLI client（HTTP 调网关 `/admin`，执行后退出）。
- **动态后端 MVP 不持久化**（B 后置）：portal 增/删动态后端即时生效，但网关重启后动态后端丢失（已知限制，须重 ensure）；静态后端因写回 YAML 而持久。
- `backends.yaml` 写回保留既有种子格式与注释，MVP 管理面单用户（写回串行化，无并发锁）。
- 任务导出 MVP 仅 JSON 格式、仅 `completed` 任务、全量不分页（CSV/HTML/流式后置）。
