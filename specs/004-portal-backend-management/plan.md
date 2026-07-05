# Implementation Plan: portal 后端管理平台（v2 Web 前端版）

**Branch**: `004-portal-backend-management` | **Date**: 2026-07-06（v2） | **Spec**: [spec.md](./spec.md)

**Input**: `/specs/004-portal-backend-management/spec.md`；设计决策见 [docs/superpowers/specs/2026-06-25-portal-backend-management-design.md](../../docs/superpowers/specs/2026-06-25-portal-backend-management-design.md)（v2）。

> **v2 变更**：v1 CLI/API（picocli）→ **Web 前端**（Vue 3 SPA）。后端 `/admin` HTTP API 保留；CLI/picocli 废弃（零代码未实施）；新增 `web/` 前端项目 + Maven 构建集成。

## Summary

落地 003 P3 portal 的**配置管理子集**：后端 Java `/admin` HTTP API（CRUD + 任务导出，`@ConditionalOnProperty` 按需开关）+ 前端 Vue 3 SPA（`web/`，经 `frontend-maven-plugin` 集成 Maven、`vite build` 内嵌 `static/`、Spring Boot 同源服务）。单 JAR 单产物。复用 001/003 既有（`BackendRegistry`/`DynamicBackendStore`/`TaskStore`/热重载），零侵入诊断面 `/mcp`。

## Technical Context

**Language/Version**: Java 21（LTS，后端，沿用 001/003）+ **TypeScript**（前端 SPA）。

**Primary Dependencies**:
- 后端（沿用）：Spring Boot 4.1.0 + Spring AI 2.0.0 + MCP Java SDK 2.0.0。
- 前端（新增）：**Vue 3 + Vite + TypeScript + Vue Router**（`web/`，经 npm）。
- 构建集成（新增）：**`com.github.eirslett:frontend-maven-plugin`**（Maven 内跑 npm install + build，自动下载 node）。

**Storage**: 文件 `config/backends.yaml`（静态后端，复用 001 热重载）+ in-memory（动态后端 `DynamicBackendStore` / 任务 `TaskStore`，复用）。

**Testing**: 后端 JUnit 5 + AssertJ + surefire（单元/契约）+ failsafe（真实 `*IT`）；前端 **Vitest + Vue Test Utils**（组件）；TDD + 双侧契约 + 真实零桩。

**Target Platform**: 受控内网 JVM 服务（Linux 为主、开发期 Windows）；浏览器访问网关根加载 SPA。

**Project Type**: web-service（`/mcp` 诊断 + `/admin` 管理）+ **web-ui**（Vue SPA 内嵌），单 JAR。

**Performance Goals**: 管理面低频运维操作；诊断面沿用 001。

**Constraints**: 受控内网、Noop 鉴权；单 Maven 模块（003 R1）+ 新增 `web/` 前端目录；**单 JAR 内嵌前端**（同源、无 CORS）；gateway-core 零 K8S 依赖不变；`/admin` + SPA 与 `/mcp` 隔离；任务导出原样透传（原则二）；动态后端 MVP 不持久化（B 后置）。

**Scale/Scope**: 少量后端（沿用 001）；管理面单用户（YAML 写回串行化）。

## Constitution Check

*GATE: 基于 `.specify/memory/constitution.md` v1.2.0。原则六经 v2 论证（前端=展示层）。node/vite 工具链新增见 Complexity Tracking。*

| 原则/约束 | 核查 | 结论 |
|---|---|---|
| 一 MCP 规范符合性 | `/admin` + SPA 是 REST/Web，不影响 `/mcp` MCP 契约；38 工具静态不变 | ✓ PASS |
| 二 透明无损聚合 | 任务导出原样来自 `TaskStore`（FR-006） | ✓ PASS |
| 三 连接生命周期 | 后端 CRUD 复用 `BackendRegistry`/`BackendEntry` + 热重载 + 动态注册 | ✓ PASS |
| 四 双侧契约 | 后端管理 API 契约测试先于实现（FR-013） | ✓ PASS |
| 五 可观测性 | `/admin` + SPA 暴露后端/健康/任务，错误显式传播（FR-008） | ✓ PASS |
| **六 Java 主力** | **前端（Vue/TS）= 展示层**，仅消费 `/admin`、渲染 UI、触发下载；核心逻辑（CRUD/导出/校验/热重载）全在 Java 后端。前端引入 node/vite 见 Complexity Tracking 论证 | ✓ PASS（附论证） |
| 七 TDD | 后端 + 前端测试先于实现（FR-013） | ✓ PASS |
| 八 先决研究 | Phase 0 `research.md` R1–R13（含 Vue/Vite 选型、构建集成、内嵌静态、原则六对齐） | ✓ PASS |
| 技术约束 | Java LTS / Maven 可复现（`./mvnw verify` 出含前端 JAR，FR-011）/ HTTP / 后端注册表 / 官方 SDK | ✓ PASS |

## Project Structure

### Documentation (this feature)

```text
specs/004-portal-backend-management/
├── plan.md / spec.md / research.md（R1–R13）/ data-model.md
├── contracts/（admin-api-contract + admin-invariants）
├── quickstart.md
└── tasks.md（/speckit-tasks）
```

### Source Code（单 Maven 模块 + 新增 `web/` 前端目录）

```text
src/main/java/com/arthas/gateway/
├── GatewayApplication.java        # 既有，无改（Spring Boot 默认服务 static/）
├── admin/                         # 004 后端管理 REST（@ConditionalOnProperty 按需开关）
│   ├── backend/                        # 后端配置 CRUD（admin.crud.enabled）
│   │   ├── BackendAdminController.java
│   │   ├── BackendAdminService.java
│   │   ├── BackendsYamlWriter.java
│   │   ├── BackendCrudAutoConfig.java
│   │   └── dto/BackendDto + 请求载体
│   └── task/                           # 任务导出（admin.export.enabled）
│       ├── TaskExportController.java
│       ├── TaskExportService.java
│       ├── TaskExportAutoConfig.java
│       └── dto/TaskExportDto
├── backend/ handler/ config/ task/ tool/ auth/ obs/ orchestration/  # 既有，复用（零改动）
src/main/resources/static/        # vite build 产物落点（Spring Boot 同源服务 SPA）
web/                              # 004 新增前端项目（Vue 3 + Vite + TS）
├── package.json / vite.config.ts / tsconfig.json
└── src/
    ├── App.vue / main.ts / router.ts
    ├── views/（BackendListView、TaskExportView）
    ├── components/（BackendTable、BackendForm、HealthBadge、DownloadButton）
    ├── api/（adminClient.ts：fetch /admin 封装）
    └── __tests__/（Vitest 组件测试）
src/test/java/com/arthas/gateway/
├── admin/                         # 后端契约/单元（surefire）
└── integration/                   # *IT（failsafe：真实 CRUD/导出 + 浏览器端到端）
```

**Structure Decision**: 单 Maven 模块（003 R1）。
- 后端 `admin` 包：`/admin` REST，同 JVM 调 `BackendRegistry`/`DynamicBackendStore`/`TaskStore`；`backend`/`task` 子包各自 `@ConditionalOnProperty`。
- 前端 `web/`：Vue 3 SPA，`vite build` → `src/main/resources/static/`，Spring Boot 同源服务；`frontend-maven-plugin` 集成 Maven。
- **无 `portal/` CLI 包**（v2 废弃）。
- gateway-core 零 K8S 依赖不变（`PackageBoundaryTest` 继续通过）。

## Complexity Tracking

> 宪法原则六/CLAUDE.md"不私自新增工具链"——前端引入 node/vite 工具链，正当理由论证：

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 引入 node + Vite + Vue 3 前端工具链（非 Java） | 用户裁决 portal 改 Web 前端（可视化控制台）；前端展示层必需 TS/构建链才能做 SPA | 原生 HTML/JS 无构建：MVP 管理面（表格+表单+下载）交互复杂时难维护、无组件化；CLI（v1）被用户否决（要可视化） |
| 前端 = 非核心展示层（原则六对齐） | 核心管理逻辑（CRUD/导出/校验/热重载）全在 Java `/admin`；前端仅 fetch + render + download，不承载业务规则 | 把逻辑放前端违背原则六（核心逻辑必须 Java）；故校验/导出/注册均在后端，前端只展示 |

**论证结论**：前端工具链引入有正当理由（用户要 Web 前端、展示层必需），核心逻辑不依赖前端（Java 后端自洽），`frontend-maven-plugin` 保证 CI 可复现（FR-011）。符合原则六"非 Java 仅作辅助"。
