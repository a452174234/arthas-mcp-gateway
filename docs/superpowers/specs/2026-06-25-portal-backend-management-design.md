# portal 后端管理平台设计（004 特性）v2 — Web 前端版

**日期**: 2026-07-06（v2 重设计：CLI → Web 前端）
**特性分支**: `004-portal-backend-management`
**来源**: 003 spec 用户故事 3（P3 portal）后置项的"配置管理"子集
**状态**: 已 brainstorm v2，待 spec-kit SDD 重写

> **v2 变更**：v1 为 CLI/API 版（picocli）。用户裁决改为 **Web 前端**（003 spec"MVP 不含 Web UI"经 brainstorming 推翻——用户要可视化控制台）。CLI 废弃（零代码未实施）。后端 `/admin` HTTP API 保留，前端 SPA 消费。

---

## 一、背景

003 spec 用户故事 3（portal 独立管理后端，P3）在 003 仅占位。本特性落地 P3 的**配置管理子集**，补齐 001/003 遗留的管理面缺口。

### 妥协未完成的管理能力（证据驱动梳理）

| # | 能力 | 现状（妥协） | 缺口 | 证据 |
|---|---|---|---|---|
| A | 后端配置管理面 | 手编 `config/backends.yaml` + 热重载（SC-002），无管理 UI | 运维须 SSH 改 YAML | 001 spec 假设§110；宪法原则三/五 |
| C | 异步任务结果导出 | `TaskStore` 仅 task-get 同步返 JSON 文本 | 长 watch 多帧结果无法离线分析/归档 | `task/TaskStore.java`；001 演进注记 |

**用户裁决**：MVP = A + C。"只做配置管理"——不碰 K8S 编排（list-pods/ensure 仍由 003 `k8s.*` MCP 工具经 Claude Code）。

---

## 二、brainstorming v2 决策记录

| 决策点 | 选定 | 备选（否决理由） |
|---|---|---|
| 交互形态 | **Web 前端 SPA**（废弃 CLI） | v1 CLI（用户裁决要可视化控制台）；CLI+前端并存（双套入口工作量） |
| 前端技术栈 | **Vue 3 + Vite + TypeScript** | React（更主流但 Vue 管理面 MVP 更轻）；原生 HTML/JS（无构建但难维护） |
| 部署形态 | **网关内嵌静态**（单 JAR） | 独立 SPA（CORS + 两产物）；仅开发期独立（生产未定） |
| 集成方式 | 后端 `/admin` HTTP API（前端同源 fetch） | — |
| 持久化 | YAML（`config/backends.yaml` 复用） | DB / in-memory |
| 鉴权 | Noop（受控内网） | Bearer token / Basic |
| MVP 范围 | A 后端 CRUD + C 任务导出下载 | + 任务可视化 / + 健康 dashboard（后置） |

---

## 三、架构（单 JAR 不变）

```
┌──────────────────────────────────────────────────────────┐
│  arthas-mcp-gateway.jar（单产物）                          │
│                                                          │
│  后端 Java（Spring Boot）                                  │
│  ├─ 诊断面 /mcp          （001/003 既有，不动）            │
│  ├─ 管理面 /admin/*      （004 后端，@ConditionalOnProperty）│
│  │   ├─ /admin/backends        CRUD（A）                   │
│  │   └─ /admin/tasks/{id}/export  导出（C）                 │
│  └─ 前端 SPA 静态资源    （vite build → static/，同源服务）  │
│                                                          │
│  浏览器 → 网关根（/）→ SPA → fetch /admin（同源，无 CORS）  │
└──────────────────────────────────────────────────────────┘

dev：vite dev server + proxy /admin → localhost:8761
prod：vite build 产物内嵌 JAR，Spring Boot 服务 SPA + /admin
```

**复用既有**（零侵入）：`BackendRegistry`/`BackendEntry`（001）、`DynamicBackendStore`（003）、`TaskStore`/`GatewayTask`（001）、`BackendRegistryReloader`+WatchService（001 热重载）。

**新增包/目录**：
- `com.arthas.gateway.admin`（网关侧后端 REST）：`backend/`（CRUD）+ `task/`（导出）子包，各自 `@ConditionalOnProperty`。
- `web/`（前端项目根）：Vue 3 + Vite + TS 源码；`vite build` → `src/main/resources/static/`。
- **不再有** `portal/` CLI 包（v2 废弃）。

---

## 四、能力详述

### A. 后端配置 CRUD（含健康视图）

**后端 API**（`/admin/backends`，前端 SPA 消费）：GET 列表/详情、POST 增、PUT 改、DELETE 删。
- 静态后端：写回 `config/backends.yaml` 复用 001 热重载（SC-002，30s）。
- 动态后端：POST/PUT 拒绝（R3）、DELETE=`DynamicBackendStore.unregister`。
- 响应 `BackendDto`：`name/source/state/healthy/breaker/url/protocol/auth.mode/timeouts/maxConcurrent`，凭据脱敏（仅 mode）。

**前端 UI**：后端管理页——列表表格（name/source/state/healthy/breaker）+ 增删改表单 + 健康状态徽标。

### C. 异步任务结果导出

**后端 API**（`/admin/tasks/{taskId}/export?format=json`）：`completed` 任务完整结果（元信息 + frames）作可下载 JSON；frames 原样透传（原则二）；未完成/不存在 → 409/404。

**前端 UI**：任务导出页——任务列表 + 下载按钮（触发 `GET /admin/tasks/{id}/export`，浏览器下载 JSON）。

---

## 五、错误处理

| 场景 | HTTP | 说明 |
|---|---|---|
| 未知后端/task | 404 + `available[]` | 与 001 路由拒绝一致 |
| CRUD 校验失败（重复 name、非法 URL、改动态） | 400 + 详情 | 前端表单校验 + 后端复核 |
| 导出未完成/不存在 | 409 / 404 | — |
| `backends.yaml` 写入失败 | 500 + 详情 | 透明记录（原则五） |

前端统一拦截错误 → 友好提示（toast/inline）。后端错误显式传播，不静默（原则五）。

---

## 六、测试策略

**TDD（宪法原则七）+ 双侧契约（原则四）+ 真实零桩**：

- **后端契约 IT**（HTTP client 断言）：CRUD 各端点形状/状态码/错误码；导出端点形状 + Content-Disposition。真实网关 + 真实后端/任务。
- **前端组件测试**（Vitest + Vue Test Utils）：列表渲染、表单交互、错误提示、下载触发。
- **集成**（真实零桩）：浏览器 → SPA → `/admin` 端到端（CRUD 写 YAML 热重载、动态 register、导出真实 watch 结果）。
- **回归**：001/002/003 + 38 工具契约不破；`/admin` 与 SPA 不影响 `/mcp`；`PackageBoundaryTest` 继续通过。

---

## 七、非目标（MVP 不做）

- portal CLI（v2 废弃）
- 任务结果可视化（frames 表格/图表，后置）
- 健康监控 dashboard（趋势，后置）
- B 动态后端持久化（后置）
- D 操作历史/审计（后置）
- K8S 编排（仍 003 `k8s.*` MCP 工具）
- 管理面鉴权（Noop，演进项）

---

## 八、宪法对齐（关键：原则六）

| 原则 | 对齐 |
|---|---|
| 二 透明无损聚合 | 任务导出原样透传 TaskStore 结果 |
| 三 连接生命周期 | 后端 CRUD 复用 BackendRegistry 一等对象 |
| 四 双侧契约 | 后端管理 API 契约测试先于实现 |
| 五 可观测性 | `/admin` + SPA 暴露后端/健康/任务 |
| **六 Java 主力** | **前端（Vue/TS）= 展示层**，核心管理逻辑（CRUD/导出/校验/热重载）仍在 Java `/admin`；非 Java 仅作展示辅助。node/vite 工具链新增在 plan Complexity Tracking 论证（前端展示必需、核心逻辑不依赖） |
| 七 TDD | 后端 + 前端测试先于实现 |

---

## 九、演进项

- 任务结果可视化（frames 表格/图表）
- 健康/熔断监控 dashboard
- B 动态后端持久化 + 重启恢复
- D 操作历史/审计
- 管理面 Bearer token 鉴权
- 多服务器/多集群
