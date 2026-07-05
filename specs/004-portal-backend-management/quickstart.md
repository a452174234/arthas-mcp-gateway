# Quickstart：portal 后端管理平台（004 v2 Web 前端 端到端验证）

**Feature**: `004-portal-backend-management` | **Date**: 2026-07-06（v2）

> 004 v2 能力的**端到端验证指南**（后端 `/admin` API + 前端 Vue SPA）。实现细节归 `tasks.md`。契约见 [contracts/](./contracts/)；实体见 [data-model.md](./data-model.md)；决策见 [research.md](./research.md)（R1–R13）。

---

## 0. 前置环境

| 项 | 要求 |
|---|---|
| 构建 | `./mvnw clean verify`——`frontend-maven-plugin` 自动跑 `npm install + vite build`，产物内嵌 `static/`，产出含前端的 **单 JAR**（FR-011；CI 可复现） |
| 网关 | `java -jar arthas-mcp-gateway.jar`（或 `./mvnw spring-boot:run`）起于 `:8761` |
| 真实后端 | 001 双后端夹具（`smoke/gateway-start.sh`）或 003 ensure 纳管的动态 target |
| 真实任务 | 一次 `completed` 的 `watch` 任务（导出验证） |
| 浏览器 | 访问 `http://localhost:8761/` 加载 portal SPA |

> MVP 受控内网、Noop 鉴权（research.md R8）。前端开发期可 `cd web && npm run dev`（Vite HMR + proxy `/admin` → `:8761`）。

---

## 1. 场景 A：后端配置 CRUD（US1，Web UI）

1. 浏览器访问 `http://localhost:8761/` → portal SPA 加载 → 进入「后端管理」页。
2. **列表**（A-LIST-1）：表格显示全部后端（静态种子 + 003 动态），每行 `name/source/state/healthy/breaker` + 健康徽标；`healthy/breaker` 与 `/actuator/health` details 一致（SC-003）；`auth` 仅显 `mode`（脱敏 INV-SECRET-1）。
3. **新增静态后端**（A-ADD-1 / INV-FILE-1）：点「新增」→ 表单填 name/url/protocol/auth → 提交 → 写回 `backends.yaml` → 网关热重载（≤30s）→ 列表刷新可见（`list-targets` 亦可见，复用 SC-002）。
4. **改 / 删**（A-UPD-1 / A-DEL-1）：行内「编辑」改 url（热重载后诊断走新 url）；「删除」静态从 YAML 移除、动态即时 unregister。
5. **动态后端语义**（INV-DYN-1）：动态后端「编辑/新增」禁用或提交返 400（`reason: dynamic_backend_not_editable`）；「删除」允许（unregister）。

---

## 2. 场景 B：异步任务结果导出（US2，Web UI）

1. portal → 「任务导出」页 → 任务列表（taskId/tool/target/status/createdAt）。
2. 选一个 `completed` 任务 → 点「下载」（A-EXP-1 / INV-EXP-1）：浏览器下载 `<taskId>.json`，含 `taskId/tool/target/status/createdAt/completedAt` + `frames[]`；`frames` 与 `task-get`（同 taskId）逐字一致（原样透传，原则二）。
3. **错误**（A-EXP-2）：`working`/`cancelled`/不存在任务点下载 → 友好错误提示（409/404），不静默返空。

---

## 3. 场景 C：能力按需开关（FR-014 / R9）

```yaml
# application.yml
arthas-gateway:
  admin:
    crud:   { enabled: false }   # 关闭后端 CRUD
    export: { enabled: true }    # 保留导出
```
重启网关 → 后端管理页 `/admin/backends/*` 返 404、SPA 显示「CRUD 已禁用」降级；任务导出页正常（INV-SWITCH-1）。反向同理（INV-SWITCH-2）。`/mcp` 诊断面不受影响。

---

## 4. 场景 D：构建一体化（FR-011 / SC-005）

```bash
./mvnw clean verify
# frontend-maven-plugin 跑 npm install + vite build → src/main/resources/static/
# 产出含前端 SPA 的单 JAR；浏览器访问网关根加载 portal
```

---

## 5. 回归对照（不得破 001/002/003）

```bash
./mvnw verify
```

**预期**（INV-ISOL-1 / SC-004）：既有 38 工具 `/mcp` 契约（`InitializeAndToolsListContractTest` 等）、双侧契约、热重载（`HotReloadIT`）、异步任务、K8S 编排（003 `*IT`）全绿；`/admin` + SPA 不影响 `/mcp`。

---

## 6. 验证清单（Done Definition）

- [ ] 场景 A 后端 CRUD（Web UI）：静态写 YAML 热重载（A-ADD-1）、动态不可改可删（INV-DYN-1）、列表健康一致（SC-003）、脱敏（INV-SECRET-1）。
- [ ] 场景 B 任务导出（Web UI）：completed 原样下载（A-EXP-1/INV-EXP-1）、未完成/不存在→409/404（A-EXP-2）。
- [ ] 场景 C 能力开关：crud/export 独立，关闭=404+前端降级（INV-SWITCH-1/2）。
- [ ] 场景 D 构建一体化：`./mvnw verify` 出含前端单 JAR（SC-005）。
- [ ] 回归：001/002/003 + 38 工具契约不破（INV-ISOL-1/SC-004）。
- [ ] gateway-core 零 K8S 依赖不变（003 `PackageBoundaryTest`）。
- [ ] 前端=展示层（核心逻辑 Java 后端，原则六对齐，R13）。
