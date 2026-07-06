# Feature Specification: portal 后端管理平台（v2 Web 前端版）

**Feature Branch**: `004-portal-backend-management`

**Created**: 2026-07-06（v2：CLI → Web 前端重设计）

**Status**: Draft

**Input**: 用户描述："准备开始实现 后端管理平台……你自己明确下之前有哪些是妥协导致没有完成的管理能力，加上异步任务的结果导出能力，为 portal 平台的管理能力范围。" → brainstorming v2 裁决：**Web 前端**（非 CLI），落地 003 P3 portal 的配置管理子集。

> **设计来源**：[2026-06-25-portal-backend-management-design.md](../../docs/superpowers/specs/2026-06-25-portal-backend-management-design.md)（v2）。后端 `/admin` HTTP API + 前端 Vue 3 SPA（内嵌单 JAR）。CLI 废弃。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 后端配置 CRUD 管理（Priority: P1）

运维人员经 portal 的 **Web UI**（浏览器访问网关根，SPA），对网关后端注册表进行**可视化查询/增/删/改**（含静态种子与 003 动态纳管的后端），并查看每后端的健康/熔断状态，无需 SSH 手编 `config/backends.yaml`。

**Why this priority**: 001 以来后端管理靠手编 YAML（妥协项，001 spec 假设§110），是管理面最基础、最高频的能力，宪法原则三（后端为受管理一等对象）与原则五（暴露后端/健康状况）的直接落地。

**Independent Test**: 浏览器打开 portal → 后端管理页见既有种子 + 动态后端 → 表单新增静态后端 → 网关 `list-targets` 30s 内感知 → 删除 → 网关感知移除，全程不手编 YAML。

**Acceptance Scenarios**:

1. **Given** 网关运行（静态种子 + 003 动态后端），**When** 运维浏览器访问 portal 后端管理页，**Then** 列表显示全部后端，每条含 `name/source(STATIC|DYNAMIC)/state/healthy/breaker` 等 + 汇总（total/healthy/unhealthy）。
2. **Given** portal，**When** 表单新增静态后端（name+url+认证）提交，**Then** 写回 `config/backends.yaml`，网关热重载 30s 内纳管，列表刷新可见。
3. **Given** portal，**When** 删除静态后端，**Then** YAML 移除并热重载生效；删除动态后端则 `DynamicBackendStore.unregister` 即时移除。
4. **Given** portal，**When** 改静态后端 url/auth/timeouts，**Then** 写回 YAML 热重载；改动态后端 → 拒绝并明确提示（动态须先删再 ensure）。

---

### User Story 2 - 异步任务结果导出（Priority: P2）

运维或开发者经 portal **Web UI**，把网关异步任务（`watch`/`trace`/`stack`/`tt`/`monitor`）的完整结果**导出下载为 JSON 文件**，供离线分析、归档与分享——补齐 001"仅 task-get 同步返 JSON、无文件导出"的缺口。

**Why this priority**: 用户明确要求的新能力；依赖任务系统（`TaskStore`），后端管理（US1）更基础，故 P2。

**Independent Test**: portal 任务导出页 → 选一个 `completed` 的真实 watch 任务 → 点下载 → 得 JSON 文件，含任务元信息 + 真实诊断帧（hotMethod 的 accessPoint/className/value 等）。

**Acceptance Scenarios**:

1. **Given** 网关有 `completed` 异步任务，**When** 经 portal 任务导出页点下载，**Then** 浏览器下载 JSON 文件（`Content-Disposition: attachment`），含 `taskId/tool/target/status/createdAt/completedAt` + `frames[]`（原样来自 TaskStore，不篡改/截断）。
2. **Given** 任务 `working`/`cancelled`/不存在，**When** 下载，**Then** 明确错误提示（409/404），不静默返空。

---

### Edge Cases

- **未知后端名**：GET/PUT/DELETE → 404 + `available[]`（前端友好提示）。
- **未知/未完成任务导出**：404（未知）/ 409（working/cancelled，仅 completed 可导出）。
- **CRUD 校验失败**：重复 name、非法 URL、缺必填、改动态后端 → 400 + 详情（前端表单 inline 校验 + 后端复核）。
- **`backends.yaml` 写入失败**（IO/锁/磁盘满）→ 500 + 详情，透明记录（原则五）。
- **静态与动态 name 冲突**：增静态与既有动态同名 → 拒绝（复用 003 冲突检测 I-3）。
- **并发改 YAML**：MVP 管理面单用户，写回串行化（无锁）。
- **删除 in-flight 后端**：复用 002 `retirementGrace` 宽限切断。
- **前端构建失败/资源缺失**：网关仍可服务 `/admin` + `/mcp`（前端缺失不阻塞后端）；SPA 加载失败显示降级提示。

## Requirements *(mandatory)*

### Functional Requirements

**后端管理 API（US1/US2 共享，前端 SPA 消费）**

- **FR-001**: 系统必须提供**独立于诊断面 `/mcp` 的管理 HTTP API**（`/admin/backends`、`/admin/tasks/{id}/export`），与诊断面隔离、互不影响。
- **FR-002**: `GET /admin/backends` 必须返回全部后端清单（`name/source/state/healthy/breaker/url/protocol/auth.mode/timeouts/maxConcurrent`）+ `summary`——运维无需读源码（原则五）。
- **FR-003**: `POST /admin/backends` 增静态后端必须写回 `config/backends.yaml` + 001 热重载 30s 内纳管（SC-002）；动态后端必须 `DynamicBackendStore.register` 即时生效。
- **FR-004**: `DELETE /admin/backends/{name}` 删静态从 YAML 移除并热重载；删动态调 `unregister` 即时移除。
- **FR-005**: `PUT /admin/backends/{name}` 改静态写 YAML 热重载；动态后端不可改（明确错误）。
- **FR-006**: `GET /admin/tasks/{taskId}/export?format=json` 必须把 `completed` 任务的完整结果（元信息 + frames）作为可下载 JSON 返回；frames **原样来自 TaskStore，不得篡改/摘要/截断**（原则二）。
- **FR-007**: 导出 `working`/`cancelled`/不存在任务必须明确错误（409/404），不静默返空。
- **FR-008**: 所有管理操作错误必须**结构化、显式传播**（HTTP 状态码 + 错误体），不静默成功（原则五）。

**前端 Web UI（Vue 3 SPA）**

- **FR-009**: 系统必须提供 **Web UI**（Vue 3 SPA），经浏览器访问网关根加载，同源 fetch `/admin` API（无 CORS），覆盖后端 CRUD 管理（列表/增删改表单/健康状态）与任务导出下载。
- **FR-010**: 前端 SPA 构建产物必须**内嵌网关 JAR**（`vite build` → `src/main/resources/static/`，Spring Boot 同源服务），保持**单 JAR 单产物**部署（dev 期 vite dev server + proxy）。
- **FR-011**: 前端构建必须集成进 Maven 构建（`frontend-maven-plugin` 跑 `npm install + build`），`./mvnw verify` 一条命令产出含前端的 JAR（CI 可复现，宪法"CI 复现本地构建"）。
- **FR-012**: 前端 = **展示层**（Vue/TypeScript），核心管理逻辑（CRUD/导出/校验/热重载）仍在 Java `/admin` 后端——符合宪法原则六"非 Java 仅作辅助"。前端引入 node/vite 工具链在 plan Complexity Tracking 论证。

**贯穿（测试 + 能力开关）**

- **FR-013**: 后端管理面与前端必须以 **TDD** 开发（测试先于实现），契约测试 + 真实零桩测试并存（宪法原则四/七）。
- **FR-014**: 后端 CRUD 与任务导出必须各自**独立、可按需启用/关闭**（`@ConditionalOnProperty`：`arthas-gateway.admin.crud.enabled`/`admin.export.enabled`，默认 `true`）；关闭某能力时后端端点 404、前端对应页面降级提示，互不影响。
- **FR-015**（增量）：`GET /admin/tasks` 必须返回异步任务**摘要列表**（`taskId/tool/target/status/createdAt/completedAt/isError`，**不含 frames**），支持 `status`/`tool`/`target` 可选过滤 + `page`/`size` 标准分页（响应含 `total`），按 `createdAt` 倒序——运维可浏览/定位任务而非仅按 taskId 导出。开关复用 `arthas-gateway.admin.export.enabled`（关则与 export 同 404，INV-LIST-4）。

### Key Entities

- **BackendDto**（后端响应，只读投影）：`name`/`source`/`state`/`healthy`/`breaker`/`url`/`protocol`/`auth.mode`/`timeouts`/`maxConcurrent`。源 `BackendRegistry`+`BackendEntry`（001）。
- **TaskExportDto**（导出响应）：`taskId`/`tool`/`target`/`status`/`createdAt`/`completedAt` + `frames[]`。源 `TaskStore`/`GatewayTask`（001）。
- **前端组件**（Vue 3）：`BackendListView`/`BackendForm`/`TaskExportView` 等（展示层，不持久化，仅消费 `/admin`）。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 经 portal Web UI 增/删/改静态后端，网关 `list-targets` 30s 内反映变化（复用 001 SC-002）。
- **SC-002**: 经 portal Web UI 下载一个 `completed` 真实 watch 任务，文件含完整、原样的诊断帧（accessPoint/className/methodName/cost/ts/value），与 `task-get` 一致（无篡改）。
- **SC-003**: portal 后端列表的健康/熔断状态与 `/actuator/health` details 一致（原则五）。
- **SC-004**: 管理面（`/admin` + SPA）全量操作期间，诊断面 `/mcp` 既有 38 工具与双侧契约不受影响（回归不破 001/002/003）。
- **SC-005**: `./mvnw verify` 一条命令产出含前端 SPA 的单 JAR，浏览器访问网关根加载 portal（CI 可复现）。
- **SC-006**（增量）：portal `/tasks` 页列表区可浏览异步任务（自动查首页 + status 过滤 + 分页 + 点列表项填 taskId 衔接导出）；列表/过滤/分页/排序经真实 ContractIT + Playwright 端到端自验证。

## Assumptions

- 部署在**受控内网**，MVP 管理面**无鉴权**（Noop）；Bearer token 为演进项。
- 复用既有：001 `BackendRegistry`/`BackendEntry`/`BackendRegistryReloader`/`TaskStore`、003 `DynamicBackendStore`；本特性仅以管理面封装暴露，不重建。
- **单 Maven 模块**（003 R1 包级边界）+ **新增 `web/` 前端项目目录**（Vue 3 + Vite + TS）+ 新增 `admin` 包（后端 REST，下分 `backend`/`task` 子包，各自 `@ConditionalOnProperty`）；**无 portal CLI 包**（v2 废弃）；gateway-core 零 K8S 依赖不变。
- **单 JAR 内嵌前端**：`vite build` → `src/main/resources/static/`，Spring Boot 同源服务 SPA + `/admin`（无 CORS）；dev 期 `vite dev` + proxy `/admin` → `:8761`。
- **node/vite 工具链新增**：经 `frontend-maven-plugin` 统一在 Maven 构建内跑（CI 需 node；宪法"不私自新增工具链"在 plan 论证——前端展示层必需）。
- **动态后端 MVP 不持久化**（B 后置）：portal 增删动态后端即时生效，网关重启丢失（须重 ensure）；静态后端写回 YAML 持久。
- `backends.yaml` 写回用 SnakeYAML `dump` 重写（**不保留原文注释**，注释保留后置；见 research.md R2）；机密字段写回当前解析值（占位符还原后置）。MVP 单用户（写回串行化）。
- 任务导出 MVP 仅 JSON、仅 `completed`、全量不分页（CSV/HTML/可视化后置）。
