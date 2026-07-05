# Implementation Plan: portal 后端管理平台

**Branch**: `004-portal-backend-management` | **Date**: 2026-06-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-portal-backend-management/spec.md`；设计决策见 [docs/superpowers/specs/2026-06-25-portal-backend-management-design.md](../../docs/superpowers/specs/2026-06-25-portal-backend-management-design.md)。

## Summary

落地 003 P3 portal 的**配置管理子集**：单 JAR 双入口（picocli：`serve` 起网关 / `portal` 跑 CLI），网关侧新增 `/admin` HTTP 管理 API（Noop 鉴权、复用 Spring Web MVC），portal CLI 经 HTTP 调 `/admin`。MVP 范围 = **A 后端配置 CRUD**（静态写 `backends.yaml` 热重载 / 动态 `DynamicBackendStore`）+ **C 异步任务结果导出**（`completed` 任务原样 JSON 文件）。复用 001/003 既有（`BackendRegistry`/`DynamicBackendStore`/`TaskStore`/`BackendRegistryReloader`），零侵入诊断面 `/mcp`。

## Technical Context

**Language/Version**: Java 21（LTS，沿用 001/003）。

**Primary Dependencies**: Spring Boot 4.1.0 + Spring AI 2.0.0 + MCP Java SDK 2.0.0（沿用）+ **picocli 4.7.x**（新增，CLI 子命令路由 + 自动 `--help`/补全/退出码）+ Spring Web MVC（`/admin` REST 端点，`spring-boot-starter-web` 已在）。

**Storage**: 文件 `config/backends.yaml`（静态后端，复用 001 热重载 SC-002）+ in-memory（动态后端 `DynamicBackendStore` / 任务 `TaskStore`，复用 003/001）。

**Testing**: JUnit 5 + AssertJ + surefire（单元/契约）+ failsafe（真实 `*IT`）；TDD 红-绿-重构（宪法原则七）+ 双侧契约（原则四）+ 真实零桩（CLAUDE.md）。

**Target Platform**: 受控内网 JVM 服务（Linux 为主、开发期 Windows 11）；CLI 跨平台（picocli）。

**Project Type**: web-service（`/mcp` 诊断面 + `/admin` 管理面）+ cli（`portal` 子命令），**单 JAR 双入口**。

**Performance Goals**: 管理面为低频运维操作，无高吞吐要求；诊断面沿用 001 性能。

**Constraints**: 受控内网、Noop 鉴权（Bearer token 为演进项）；单 Maven 模块（003 research.md R1 包级边界）；gateway-core 零 K8S 依赖不变；`/admin` 与 `/mcp` 隔离互不影响；任务导出原样透传（宪法原则二）；动态后端 MVP 不持久化（B 后置，重启丢失为已知限制）；**能力按需开关**（后端 CRUD / 任务导出各 `@ConditionalOnProperty`，`arthas-gateway.admin.crud.enabled` / `admin.export.enabled`，默认开，可配置关闭）。

**Scale/Scope**: 少量后端（沿用 001 假设）；管理面单用户（`backends.yaml` 写回串行化、无并发锁）。

## Constitution Check

*GATE: 基于 `.specify/memory/constitution.md` v1.2.0。无违反 → Complexity Tracking 无需填。*

| 原则/约束 | 核查 | 结论 |
|---|---|---|
| 一 MCP 规范符合性 | `/admin` 是 REST（非 MCP），不影响 `/mcp` MCP 契约；诊断面 38 工具静态不变 | ✓ PASS（管理面与诊断面隔离） |
| 二 透明无损聚合 | 任务导出原样来自 `TaskStore`，不篡改/摘要/截断（FR-007） | ✓ PASS |
| 三 连接生命周期 | 后端 CRUD 复用 `BackendRegistry`/`BackendEntry` 一等对象 + 热重载 + 动态注册 | ✓ PASS |
| 四 双侧契约优先 | 管理 API 契约测试先于实现（HTTP client 断言形状/错误码，FR-011） | ✓ PASS |
| 五 可观测性与可诊断性 | `/admin` 暴露后端/健康/任务状态，错误显式传播（FR-010） | ✓ PASS |
| 六 Java 主力 | `admin`/`portal` 包 Java 实现；picocli 为 Java 库 | ✓ PASS |
| 七 TDD | 测试先于实现，红-绿-重构（FR-011） | ✓ PASS |
| 八 先决研究 | Phase 0 `research.md`：picocli×Spring Boot 集成、YAML 写回保留格式、动态后端 CRUD 语义、CLI 双入口路由 | ✓ PASS（本 plan Phase 0） |
| 技术约束 | Java LTS / Maven 可复现 / HTTP（复用 Spring Web MVC）/ 后端注册表声明在配置 / 优先官方 SDK | ✓ PASS（沿用既有） |

## Project Structure

### Documentation (this feature)

```text
specs/004-portal-backend-management/
├── plan.md              # 本文件
├── research.md          # Phase 0 产出（R1–Rn 实施期决策）
├── data-model.md        # Phase 1 产出（增量实体/字段/状态机）
├── quickstart.md        # Phase 1 产出（端到端验证指南）
├── contracts/           # Phase 1 产出（管理 API 契约 + 不变量）
└── tasks.md             # Phase 2 产出（/speckit-tasks，非本命令）
```

### Source Code (单 Maven 模块，新增 admin/portal 两包)

```text
src/main/java/com/arthas/gateway/
├── GatewayApplication.java        # 既有；改为 picocli 入口路由（serve / portal）
├── admin/                         # 004 新增（网关侧管理 REST；能力按需开关）
│   ├── backend/                        # 后端配置 CRUD（@ConditionalOnProperty admin.crud.enabled，默认开）
│   │   ├── BackendAdminController.java     # /admin/backends CRUD
│   │   ├── BackendAdminService.java        # 编排：静态写 YAML / 动态 DynamicBackendStore
│   │   ├── BackendsYamlWriter.java         # backends.yaml 结构化写回（SnakeYAML dump）
│   │   ├── BackendCrudAutoConfig.java      # @ConditionalOnProperty 装配
│   │   └── dto/                            # BackendDto / 请求 DTO
│   └── task/                           # 异步任务导出（@ConditionalOnProperty admin.export.enabled，默认开）
│       ├── TaskExportController.java       # /admin/tasks/{id}/export
│       ├── TaskExportService.java          # TaskStore.get → TaskExportDto
│       ├── TaskExportAutoConfig.java       # @ConditionalOnProperty 装配
│       └── dto/                            # TaskExportDto
├── portal/                        # 004 新增（CLI client，HTTP 调 /admin）
│   ├── PortalCommand.java              # picocli 主命令（路由 backends/tasks）
│   ├── BackendsCommand.java            # portal backends list/get/add/remove/update
│   ├── TasksCommand.java               # portal tasks export
│   └── GatewayAdminClient.java         # HTTP client（调 /admin）
├── backend/ handler/ config/ task/ tool/ auth/ obs/ orchestration/  # 既有，复用（零改动）
src/test/java/com/arthas/gateway/
├── admin/                         # 管理面契约/单元测试（surefire）
├── portal/                        # CLI 测试（surefire）
└── integration/                   # *IT（failsafe，真实环境：CRUD 热重载 / 动态注册 / 任务导出）
```

**Structure Decision**: 单 Maven 模块 + 包级边界（沿用 003 research.md R1"模块化单体"）；能力按需组合经 Spring 条件装配实现（见 research.md R9）。
- `admin` 包（网关侧）：`/admin` REST 端点，同 JVM 直接调 `BackendRegistry`/`DynamicBackendStore`/`TaskStore`（零跨进程）；下分 `backend`（CRUD）/`task`（导出）两子包，各自 `@ConditionalOnProperty` 按需开关（`arthas-gateway.admin.crud.enabled` / `admin.export.enabled`，默认开）。
- `portal` 包（CLI 侧）：picocli 命令 + `GatewayAdminClient`（HTTP 调 `/admin`），可远程/跨机器。
- `gateway-core`（backend/handler/config/tool/task/auth/obs）零 K8S 依赖不变（003 `PackageBoundaryTest` 守护继续通过）；admin/portal 不破包边界。

## Complexity Tracking

> 无 Constitution 违反，无需填。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
