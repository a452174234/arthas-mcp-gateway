# Tasks: portal 后端管理平台（v2 Web 前端版）

**Input**: Design documents from `/specs/004-portal-backend-management/`（v2）

**Prerequisites**: plan.md（v2，含 Complexity Tracking 前端论证）、spec.md（v2）、research.md（R1–R13）、data-model.md、contracts/、quickstart.md、`.specify/memory/constitution.md`（v1.2.0）。

**实施范围声明**：本计划实施 004 v2 全部 = US1（后端 CRUD，P1）+ US2（任务导出，P2），后端 Java `/admin` API + 前端 Vue 3 SPA（内嵌单 JAR）。**v1 的 CLI/picocli 废弃**（零代码未实施，本清单无 CLI 任务）。非目标（可视化/dashboard/B/D/K8S/鉴权）后置。

**TDD 硬约束（不可妥协，宪法原则七 + CLAUDE.md）**：后端 + 前端所有功能代码必须 TDD——先写失败测试（红）、再实现至通过（绿）、再重构。本清单中**测试任务一律先于其实现任务**。**真实性硬约束（零桩）**：后端契约 IT 由 HTTP client 驱动真实网关 + 真实后端/任务；前端组件测试用 Vitest，端到端真实浏览器。

**波次映射**：Phase 2 = 共享基建（前端 SPA 骨架 + admin 条件装配）；Phase 3 = US1（后端 CRUD + 前端管理页）；Phase 4 = US2（后端导出 + 前端导出页）；Phase 5 = Polish。

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: 可并行（不同文件、无未完成任务依赖）
- **[Story]**: 仅 Phase 3/4 任务带 `[US1]`/`[US2]`；Setup/Foundational/Polish 不带
- 每条任务含精确文件路径

## Path Conventions

- 单 Maven 模块，后端 Java 源 `src/main/java/com/arthas/gateway/...`，后端测试 `src/test/java/com/arthas/gateway/...`
- 前端项目根 `web/`（Vue 3 + Vite + TS），前端测试 `web/src/__tests__/`（Vitest）
- 前端构建产物 → `src/main/resources/static/`（Spring Boot 同源服务）
- 配置 `src/main/resources/application.yml`（`arthas-gateway.admin.*`）

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 前端项目初始化 + Maven 构建集成 + 能力开关配置外化

- [X] T001 [P] Initialize `web/` 前端项目（Vue 3 + Vite + TypeScript + Vue Router）in `web/`（`package.json`/`vite.config.ts`/`tsconfig.json`/`src/main.ts`/`src/App.vue`/`src/router.ts` 骨架；vite build outDir → `../src/main/resources/static/`；research.md R10/R12）
- [X] T002 [P] Add `com.github.eirslett:frontend-maven-plugin` to `pom.xml`（挂 `generate-resources` 跑 `npm install + npm run build`，自动下载 node；产物落 `src/main/resources/static/`；FR-011/R11）
- [X] T003 [P] Add `arthas-gateway.admin.*` config section to `src/main/resources/application.yml`（`crud.enabled: true` / `export.enabled: true`，默认开；R9）
- [X] T004 [P] Add `Admin` 子段（`crud.enabled`/`export.enabled` 绑定）to `src/main/java/com/arthas/gateway/config/GatewayProperties.java`

**Checkpoint**: 前端项目就位、Maven 构建集成、能力开关配置外化

---

## Phase 2: Foundational (Blocking Prerequisites — 共享基建)

**Purpose**: 前端 SPA 骨架（路由 + adminClient）+ 后端条件装配骨架。**⚠️ CRITICAL**：US1/US2 均依赖此层。

> **TDD（宪法原则七）**：测试先写并确认失败（红），再实现至通过（绿）。

- [X] T005 [P] Write failing `AdminCapabilitySwitchTest` in `src/test/java/com/arthas/gateway/admin/AdminCapabilitySwitchTest.java`（断言 INV-SWITCH-1/2：`admin.crud.enabled=false` → `/admin/backends/*` 404；`admin.export.enabled=false` → `/admin/tasks/*/export` 404；两者独立、默认开）
- [X] T006 Implement 条件装配骨架 in `src/main/java/com/arthas/gateway/admin/backend/BackendCrudAutoConfig.java`、`src/main/java/com/arthas/gateway/admin/task/TaskExportAutoConfig.java`（`@ConditionalOnProperty(name=..., havingValue="true", matchIfMissing=true)`；green for T005）
- [X] T007 [P] Write failing 前端 SPA 骨架测试 in `web/src/__tests__/App.test.ts`、`web/src/__tests__/api/adminClient.test.ts`（Vitest：根路由加载 App、`adminClient` fetch `/admin` 封装 + 错误传播；research.md R6）
- [X] T008 Implement 前端 SPA 骨架 in `web/src/App.vue`、`web/src/router.ts`、`web/src/api/adminClient.ts`（Vue Router 路由 `/`、`fetch('/admin/...')` 同源封装；green for T007）

**Checkpoint**: 共享基建全绿——US1/US2 可在此之上构建

---

## Phase 3: User Story 1 - 后端配置 CRUD（Priority: P1）🎯 MVP

**Goal**: 后端 `/admin/backends` CRUD API + 前端「后端管理」页（列表/增删改表单/健康徽标/凭据脱敏），静态写 `backends.yaml` 热重载、动态 `DynamicBackendStore`。

**Independent Test**: 浏览器 → portal 后端管理页 → 表单新增静态后端 → 网关 30s 内纳管 → 删除 → 感知移除，不手编 YAML。

> **TDD + 真实零桩**：后端契约 IT 由 HTTP client 驱动真实网关 + 真实后端；前端组件 Vitest。断言见 `contracts/`。

### 后端

- [X] T009 [P] [US1] Write failing `BackendDtoTest` in `src/test/java/com/arthas/gateway/admin/backend/dto/BackendDtoTest.java`（字段全集 + INV-SECRET-1 凭据脱敏：`auth` 仅 `mode`，不回显 token/username/password）
- [X] T010 [US1] Implement `BackendDto` + `CreateBackendRequest` + `UpdateBackendRequest` in `src/main/java/com/arthas/gateway/admin/backend/dto/`（green for T009）
- [X] T011 [P] [US1] Write failing `BackendsYamlWriterTest` in `src/test/java/com/arthas/gateway/admin/backend/BackendsYamlWriterTest.java`（INV-FILE-1：写回 `version`+`backends`、热重载可解析、不保留注释为已知行为；R2）
- [X] T012 [US1] Implement `BackendsYamlWriter` in `src/main/java/com/arthas/gateway/admin/backend/BackendsYamlWriter.java`（SnakeYAML `dump` 重写 `config/backends.yaml`；green for T011）
- [X] T013 [P] [US1] Write failing `BackendAdminServiceTest` in `src/test/java/com/arthas/gateway/admin/backend/BackendAdminServiceTest.java`（静态 POST/PUT/DELETE 经 YamlWriter；动态 POST/PUT 拒绝 INV-DYN-1、DELETE=`DynamicBackendStore.unregister`；name 冲突 400；R3/R7）
- [X] T014 [US1] Implement `BackendAdminService` in `src/main/java/com/arthas/gateway/admin/backend/BackendAdminService.java`（编排 `BackendRegistry`/`DynamicBackendStore`/`BackendsYamlWriter`；green for T013）
- [X] T015 [US1] Implement `BackendAdminController` in `src/main/java/com/arthas/gateway/admin/backend/BackendAdminController.java`（`@RestController @RequestMapping("/admin/backends")` GET 列表/详情、POST、PUT、DELETE + 错误体；装配进 `BackendCrudAutoConfig`）
- [X] T016 [P] [US1] Write failing `BackendAdminContractIT` in `src/test/java/com/arthas/gateway/admin/backend/BackendAdminContractIT.java`（failsafe *IT，真实网关 + 真实后端：A-LIST-1 健康一致 SC-003、A-ADD-1 静态写 YAML 热重载 30s、A-UPD-1/A-DEL-1、INV-DYN-1、INV-ERR-1、INV-SECRET-1）

### 前端

- [X] T017 [P] [US1] Write failing 前端组件测试 in `web/src/__tests__/views/BackendListView.test.ts`、`web/src/__tests__/components/BackendForm.test.ts`（Vitest：列表渲染 + 健康徽标、表单提交触发 adminClient.create、动态后端编辑禁用、错误 inline 提示）
- [X] T018 [US1] Implement 前端后端管理页 in `web/src/views/BackendListView.vue`、`web/src/components/`（`BackendTable.vue`/`BackendForm.vue`/`HealthBadge.vue`）+ 扩 `api/adminClient.ts`（backends CRUD）；green for T017

**Checkpoint (US1)**: 浏览器 → 后端管理页全链路通——静态 CRUD 写 YAML 热重载、动态不可改可删、健康视图、脱敏

---

## Phase 4: User Story 2 - 异步任务结果导出（Priority: P2）

**Goal**: 后端 `/admin/tasks/{id}/export` API + 前端「任务导出」页（任务列表 + 下载），`completed` 任务原样 JSON 下载。

**Independent Test**: 触发真实 watch → completed → portal 任务导出页点下载 → JSON 文件 frames 与 `task-get` 一致（无篡改）。

> **TDD + 真实零桩**：契约 IT 用真实 watch；前端组件 Vitest。

### 后端

- [X] T019 [P] [US2] Write failing `TaskExportDtoTest` in `src/test/java/com/arthas/gateway/admin/task/dto/TaskExportDtoTest.java`（INV-EXP-1：`frames[]` 原样来自 `GatewayTask`，与 task-get 逐字一致）
- [X] T020 [US2] Implement `TaskExportDto` in `src/main/java/com/arthas/gateway/admin/task/dto/TaskExportDto.java`（green for T019）
- [X] T021 [P] [US2] Write failing `TaskExportServiceTest` in `src/test/java/com/arthas/gateway/admin/task/TaskExportServiceTest.java`（`TaskStore.get` → DTO；仅 `completed` 可导出，其他 → 409/404；A-EXP-2）
- [X] T022 [US2] Implement `TaskExportService` in `src/main/java/com/arthas/gateway/admin/task/TaskExportService.java`（原样 frames，宪法原则二；green for T021）
- [X] T023 [US2] Implement `TaskExportController` in `src/main/java/com/arthas/gateway/admin/task/TaskExportController.java`（`GET /admin/tasks/{taskId}/export?format=json` + `Content-Disposition: attachment`；装配进 `TaskExportAutoConfig`）
- [X] T024 [P] [US2] Write failing `TaskExportContractIT` in `src/test/java/com/arthas/gateway/admin/task/TaskExportContractIT.java`（failsafe *IT，真实 watch→completed→export：A-EXP-1 frames 与 task-get 一致 INV-EXP-1、A-EXP-2 →409/404、Content-Disposition attachment）

### 前端

- [X] T025 [P] [US2] Write failing 前端组件测试 in `web/src/__tests__/views/TaskExportView.test.ts`、`web/src/__tests__/components/DownloadButton.test.ts`（Vitest：任务列表渲染、下载触发 `GET /admin/tasks/{id}/export`、错误提示）
- [X] T026 [US2] Implement 前端任务导出页 in `web/src/views/TaskExportView.vue`、`web/src/components/DownloadButton.vue` + 扩 `api/adminClient.ts`（export 下载）；green for T025

**Checkpoint (US2)**: 浏览器 → 任务导出页闭环——completed 原样 JSON 下载、未完成/不存在明确错误

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: 回归守护、包边界、能力开关端到端、浏览器 E2E、文档（不破 001/002/003）

- [X] T027 [P] Regression guard：`./mvnw verify` 全绿——`frontend-maven-plugin` 跑通前端构建（SC-005 含前端 JAR）+ `/mcp` 38 工具契约（`InitializeAndToolsListContractTest` 等）+ 双侧契约 + 热重载（`HotReloadIT`）+ 003 K8S `*IT`；INV-ISOL-1 / SC-004 管理面 + SPA 不影响诊断面
- [X] T028 [P] Add ArchUnit boundary assertions to `src/test/java/com/arthas/gateway/architecture/PackageBoundaryTest.java`（`admin` 不破 gateway-core 边界；gateway-core 零 K8S 依赖不变；核心逻辑在 Java 后端、前端无业务规则——原则六 R13）
- [X] T029 [P] 能力开关端到端 IT：`admin.crud.enabled=false` / `admin.export.enabled=false` 各自 `/admin/*` 404 + 前端降级提示、互不影响、`/mcp` 不受影响（INV-SWITCH-1/2）
- [X] T030 浏览器端到端（真实零桩）：浏览器 → SPA → `/admin` CRUD + 导出全链路（Playwright E2E 或手测脚本，真实网关 + 真实后端/任务）
- [X] T031 Run `quickstart.md` validation：场景 A（CRUD）+ B（导出）+ C（开关）+ D（构建）+ 回归（§6 Done Definition 逐项核对）
- [X] T032 [P] Update docs：README 增 portal Web UI 说明 + `arthas-gateway.admin.*` 配置 + 前端构建（`./mvnw verify` 出含前端单 JAR）+ 能力按需开关（宪法"每项新能力必须有文档"）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖；T001/T002/T003/T004 独立（T002 构建集成依赖 T001 前端项目存在，可同 phase）
- **Foundational (Phase 2)**: 依赖 Setup；**阻塞 US1/US2**
- **US1 (Phase 3)**: 依赖 Foundational（T006 条件装配 / T008 SPA 骨架）；后端先行（T009–T016），前端跟后（T017–T018）
- **US2 (Phase 4)**: 依赖 Foundational；与 US1 独立（可并行，MVP 先 US1）
- **Polish (Phase 5)**: 依赖 US1+US2 完成

### Within US1（后端先行 → 前端跟进）

1. 后端：T009（DTO 测试）→ T010；T011（YamlWriter 测试）→ T012；T013（Service 测试）→ T014 → T015（Controller）；T016（契约 IT）可并行先写
2. 前端：T017（组件测试）→ T018（后端 API 就绪后）

### Within US2

1. 后端：T019 → T020；T021 → T022 → T023；T024（契约 IT）
2. 前端：T025 → T026

### Parallel Opportunities

- Setup：T001 ∥ T003 ∥ T004（独立）；T002 依赖 T001
- Foundational：T005 ∥ T007（后端开关测试 ∥ 前端骨架测试）
- US1：T009 ∥ T011 ∥ T013（后端独立测试）；T016 ∥ T017（IT ∥ 前端测试）
- US2：T019 ∥ T021；T024 ∥ T025
- Polish：T027 ∥ T028 ∥ T029 ∥ T032（独立）

---

## Parallel Example: US1 后端测试先行

```bash
# 并行先写失败测试（红）：
Task T009: "BackendDtoTest in src/test/.../admin/backend/dto/BackendDtoTest.java"
Task T011: "BackendsYamlWriterTest in src/test/.../admin/backend/BackendsYamlWriterTest.java"
Task T013: "BackendAdminServiceTest in src/test/.../admin/backend/BackendAdminServiceTest.java"
Task T016: "BackendAdminContractIT（真实 IT）"

# 再按依赖链实现至 green（T010/T012 → T014 → T015 → 前端 T018）
```

---

## Implementation Strategy

### MVP First（仅 US1）

1. Phase 1 Setup（前端项目 + Maven 集成 + 配置）
2. Phase 2 Foundational（SPA 骨架 + 条件装配——**CRITICAL，阻塞 US1/US2**）
3. Phase 3 US1（后端 CRUD → 前端管理页）
4. **STOP and VALIDATE**：浏览器 → 后端管理页全链路（CRUD + 热重载 + 脱敏）
5. Phase 4 US2 → Phase 5 Polish

### Incremental Delivery

1. Setup + Foundational → 共享基建 ready（SPA 可加载、构建集成跑通）
2. US1 → 后端管理页可用（MVP！管理面基本盘）
3. US2 → 任务导出页可用
4. Polish → 回归 + 包边界 + E2E + 文档
5. 每个波次不破坏前一波次（回归对照 = 001/002/003）

---

## Notes

- **TDD 硬约束**：后端 + 前端所有功能代码测试先于实现（红→绿→重构，宪法原则七）
- **真实性硬约束（零桩）**：后端契约 IT 真实网关 + 真实后端 + 真实任务；前端组件 Vitest、端到端真实浏览器（CLAUDE.md）
- **管理面/诊断面隔离**：`/admin` + SPA 不影响 `/mcp` 38 工具契约（INV-ISOL-1）；`admin` 包是新增，gateway-core 零 K8S 依赖不变
- **能力按需开关**：`admin.crud.enabled`/`admin.export.enabled` 各自 `@ConditionalOnProperty`，关闭=后端 404 + 前端降级（FR-014/R9）
- **前端 = 展示层**（原则六 R13）：核心逻辑 Java 后端、前端仅 fetch + render + download；node/vite 工具链经 `frontend-maven-plugin` 集成（FR-011，CI 可复现）
- 每个任务或逻辑组完成后提交；任一 checkpoint 可停下独立验证；同一问题连续失败 3 次暂停重评（CLAUDE.md）
