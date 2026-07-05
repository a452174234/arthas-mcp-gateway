# Research: portal 后端管理平台（004 特性 v2 Web 前端版）

> Phase 0 先决研究（宪法原则八）。v2：CLI → Web 前端重设计。每条决策援引既有代码证据，列备选与权衡。

---

## R1. 入口：浏览器访问网关根加载 SPA（v2 改）

**决策**：portal 入口 = **浏览器访问网关根 URL**（`http://<gateway>/`）→ Spring Boot 服务 `src/main/resources/static/` 内的 SPA（Vue 3）→ SPA 同源 fetch `/admin`。**无 picocli CLI**（v2 废弃，零代码未实施）。

**理由（证据）**：`GatewayApplication` 现状 = `SpringApplication.run`（`GatewayApplication.java:20-22`）。Spring Boot 默认服务 `static/` 资源——前端 `vite build` 产物落入即自动服务，零额外入口代码。v1 的 picocli 双入口路由废弃（用户裁决要 Web 前端）。

**备选（否决）**：
- v1 picocli CLI（废弃，用户要可视化控制台）。
- 前端 + CLI 并存（双套入口工作量，MVP 聚焦前端）。

---

## R2. backends.yaml 写回：SnakeYAML dump 重写（不保留注释）

**决策**：MVP 静态后端 CRUD 用 **SnakeYAML `dump` 重写整个 `config/backends.yaml`**，**不保留原文注释**；注释保留后置。

**理由（证据）**：`BackendConfigLoader` 用 SnakeYAML（`BackendConfigLoader.java:3,59`）`load` 只读；SnakeYAML `dump` 不保留注释（技术事实）；热重载读结构不依赖注释；保留注释需 snakeyaml-engine 或手写模板，复杂、脆弱、YAGNI。

**spec 假设对齐**：spec 假设已记"MVP dump 重写不保留注释"。

**备选（否决）**：snakeyaml-engine（换库不一致）；模板化写回（脆弱）。

---

## R3. 动态后端 CRUD 语义（source=DYNAMIC）

**决策**：动态后端 POST/PUT **拒绝**（400）；DELETE = `DynamicBackendStore.unregister`（003 D-UNREG-*）。

**理由（证据）**：`DynamicBackendStore`（003）提供 `register/unregister`、强制 DYNAMIC、冲突检测 I-3。动态后端身份由 `ArthasProvisioner.ensure` 派生（`{server}-{pod}`），手动增改破坏一致性。

**备选（否决）**：允许 POST 动态（与 ensure 重叠，YAGNI）。

---

## R4. /admin 与 /mcp 隔离

**决策**：`/admin` 用 Spring Web MVC `@RestController @RequestMapping("/admin")`；`/mcp` 由 spring-ai MCP servlet 承载。路径路由隔离，互不影响。

**理由（证据）**：诊断面 `/mcp` 由 spring-ai 装配（`GatewayMcpServerConfig`）；`/admin` 不进 MCP 工具集（38 工具静态，`InitializeAndToolsListContractTest` 守护），不污染 MCP 协议（原则一）。

**备选（否决）**：管理面做成 MCP 工具（违背"工具集静态 + 诊断聚合"，原则二）。

---

## R5. 任务导出序列化（原样透传）

**决策**：`GET /admin/tasks/{taskId}/export` → `TaskStore.get` → `GatewayTask` → `TaskExportDto`（Jackson JSON，`Content-Disposition: attachment`）；frames **原样**，不篡改/截断（原则二）；仅 `completed`（其他 409/404）。

**理由（证据）**：`TaskStore`（`task/TaskStore.java:85`）`get` 返 `Optional<GatewayTask>`；Jackson 由 Spring Boot 传递带入。

**备选（否决）**：直接返 GatewayTask JSON（无 DTO，内部 record 演化会泄漏导出格式）。

---

## R6. 前端 HTTP（同源 fetch，v2 改，原 v1 CLI HttpClient 废弃）

**决策**：前端 SPA 经浏览器 **`fetch('/admin/...')`** 同源调后端（内嵌静态部署，无 CORS）；错误经 HTTP 状态码 → 前端友好提示（toast/inline）。

**理由**：单 JAR 内嵌（R12）保证前端与 `/admin` 同源，避免 CORS 配置。v1 的 `GatewayAdminClient`（java.net.http.HttpClient，portal CLI 用）废弃。

**备选（否决）**：v1 CLI HttpClient（CLI 废弃）；独立 SPA 部署需 CORS（违背单 JAR 简洁）。

---

## R7. 静态后端 CRUD × 热重载协作

**决策**：静态 CRUD → `BackendsYamlWriter` 写回 `config/backends.yaml` → 既有 `BackendConfigWatcher`（WatchService）→ `BackendRegistryReloader` 热重载（复用 001 SC-002，30s）。前端/admin **不直接调** `BackendRegistry`（文件 = source of truth）。

**理由（证据）**：001 已实现完整热重载链；portal 写 YAML 即触发，零改动复用。

**并发**：MVP 单用户，写回串行化。

**备选（否决）**：直接调 `BackendRegistry.rebuild`（破坏文件 source of truth，重启不一致）。

---

## R8. 鉴权（Noop，受控内网）

**决策**：MVP `/admin` 无鉴权（与 001 `/mcp` 一致，受控内网）。Bearer token 后置（与 001 演进首要项统一）。

**备选（否决）**：单独 Bearer token filter（MVP 引入 token 管理，后置统一）。

---

## R9. 能力开关（@ConditionalOnProperty）

**决策**：后端 CRUD 与任务导出各独立子包（`admin/backend`/`admin/task`），各自 `@ConditionalOnProperty`（`arthas-gateway.admin.crud.enabled`/`admin.export.enabled`，默认 `true`）。关闭则后端端点 404、前端页面降级提示。

**理由**：用户要求"能力独立模块、按需组合"；Spring 条件装配零运行时开销、配置驱动、无需 Maven 多模块。

**备选（否决）**：Maven 多模块（构建复杂，003 R1 后置）；不可关（违背按需组合）。

---

## R10. 前端技术栈：Vue 3 + Vite + TypeScript（v2 新增）

**决策**：前端用 **Vue 3 + Vite + TypeScript**（`web/` 目录）。Vue Router 路由、Vite 构建、TS 类型安全。

**理由**：Vue 3 单文件组件适合管理面 MVP（表格 + 表单 + 下载）；Vite 构建快、dev HMR；TS 类型安全；生态成熟。

**备选（否决）**：
- React（更主流，但 Vue 管理面 MVP 更轻）。
- 原生 HTML/JS（无构建链，但交互复杂难维护、无组件化）。

**宪法对齐**：前端 = 展示层（Vue/TS），核心逻辑 Java 后端（R13）。

---

## R11. 前端构建集成 Maven（frontend-maven-plugin，v2 新增）

**决策**：`web/` 前端经 **`frontend-maven-plugin`**（`com.github.eirslett:frontend-maven-plugin`）在 Maven 构建内跑 `npm install + npm run build`，挂 `generate-resources` 阶段；产物落 `src/main/resources/static/`。`./mvnw verify` 一条命令产出含前端的 JAR。

**理由**：CI 可复现（宪法"CI 复现本地构建"）；frontend-maven-plugin 自动下载指定版本 node（无需 CI 预装 node）；与 Maven 生命周期统一。

**备选（否决）**：
- 手动 vite build（CI 需预装 node，违背"可复现"）。
- exec-maven-plugin 调 npm（需 CI 预装 node）。

**约束**：构建需联网下载 node（首次）+ npm 依赖；离线场景需预置（演进）。

---

## R12. 前端内嵌静态（单 JAR，v2 新增）

**决策**：`vite build` 产物落 `src/main/resources/static/`，Spring Boot 默认服务（同源）。dev 期 `vite dev` + proxy `/admin` → `localhost:8761`（HMR 开发体验）。prod 单 JAR 部署、浏览器访问网关根加载 SPA、同源 fetch `/admin`（无 CORS）。

**理由**：保持 003/004 单 JAR 单产物理念；同源避免 CORS；Spring Boot 默认服务 `static/` 零配置。

**备选（否决）**：独立 SPA 部署（CORS + 两产物）；仅开发期独立（生产未定）。

---

## R13. 宪法原则六对齐：前端 = 展示层（v2 新增）

**决策**：前端（Vue/TS）严格定位为**展示层**——仅消费 `/admin` API、渲染 UI、触发下载；**不承载核心管理逻辑**（CRUD 校验/导出序列化/热重载/动态注册全部在 Java `/admin` 后端）。

**理由（宪法原则六）**："非 Java 代码仅作为辅助，不得承载核心功能逻辑。"前端 = 辅助展示层，核心逻辑 Java 后端——符合。前端引入 node/vite 工具链在 plan **Complexity Tracking 显式论证**（前端展示层必需、核心逻辑不依赖、CI 可复现）。

**校验**：ArchUnit 守护"核心逻辑在 Java"（admin 包承载 CRUD/导出逻辑）；前端无独立业务规则（仅 fetch + render）。

---

## 待实测 / 演进项（透明记录）

- **YAML 注释保留**：MVP dump 丢注释；演进 snakeyaml-engine / 模板化。
- **占位符还原**：MVP 写回机密字段当前值；演进保留 `${ENV}`。
- **动态后端持久化（B）**：MVP 重启丢失；演进落盘 + 恢复。
- **操作审计（D）**：演进 ensure 历史 + CRUD 审计。
- **任务导出多格式/可视化**：CSV/HTML/frames 表格图表后置。
- **健康监控 dashboard**：后置。
- **管理面鉴权**：Bearer token，与 001 演进项统一。
- **前端离线构建**：node/npm 离线预置（演进）。
