# Quickstart：portal 后端管理平台（004 端到端验证）

**Feature**: `004-portal-backend-management` | **Date**: 2026-07-06

> 本文是 004 能力的**端到端验证指南**（证明"后端配置 CRUD + 异步任务导出 + 能力按需开关"闭环）。实现细节归 `tasks.md`（`/speckit-tasks` 产出）。契约见 [contracts/admin-api-contract.md](./contracts/admin-api-contract.md) + [contracts/admin-invariants.md](./contracts/admin-invariants.md)；实体见 [data-model.md](./data-model.md)；决策见 [research.md](./research.md)。

---

## 0. 前置环境

| 项 | 要求 |
|---|---|
| 网关 | 本特性构建的 `arthas-mcp-gateway.jar`（38 工具 + 004 `/admin`），`./mvnw spring-boot:run` 或 `java -jar` 起于 `:8761` |
| 真实后端 | 001 双后端夹具（`smoke/gateway-start.sh` 的 `SmokeDemoLauncher`：order-service/payment）或 003 ensure 纳管的动态 target |
| 真实任务 | 一次 `completed` 的 `watch` 任务（用于导出验证） |
| CLI | `java -jar arthas-mcp-gateway.jar portal <sub>`（picocli，调网关 `/admin`） |

> MVP 受控内网、Noop 鉴权（research.md R8）。

---

## 1. 场景 A：后端配置 CRUD（US1）

### 1.1 列表 + 健康视图

```bash
# CLI
java -jar arthas-mcp-gateway.jar portal backends list
# 或 API
curl -s http://localhost:8761/admin/backends
```

**预期**（A-LIST-1）：返回全部后端（静态种子 order-service/payment + 若有 003 动态 target），每条含 `source/state/healthy/breaker` 等；`summary` 计数与列表一致；`healthy/breaker` 与 `/actuator/health` details 一致（SC-003）。`auth` 仅暴露 `mode`、不回显 token（INV-SECRET-1）。

### 1.2 新增静态后端（写 YAML 热重载）

```bash
java -jar arthas-mcp-gateway.jar portal backends add my-svc \
  --url http://10.0.0.20:8563 --protocol STREAMABLE --auth-mode NONE
```

**预期**（A-ADD-1 / INV-FILE-1）：返回 201 + `BackendDto`；`config/backends.yaml` 写入 `my-svc`；网关经热重载（≤30s）`arthas-gateway.list-targets` 可见 `my-svc`（复用 001 SC-002）。

### 1.3 改 / 删

```bash
portal backends update my-svc --url http://10.0.0.21:8563   # A-UPD-1：热重载后诊断走新 url
portal backends remove my-svc                               # A-DEL-1：YAML 移除 + 热重载；list-targets 不再含
```

### 1.4 动态后端语义（INV-DYN-1）

```bash
# 动态后端（003 ensure 产生，source=DYNAMIC）
portal backends add <dynamic-name> ...        # → 400 reason: dynamic_backend_not_editable
portal backends update <dynamic-name> ...     # → 400
portal backends remove <dynamic-name>         # → 204（DynamicBackendStore.unregister 即时移除）
```

---

## 2. 场景 B：异步任务结果导出（US2）

```bash
# 1) 经网关触发一次 watch（产生真实 completed 任务）
#    （经 Claude Code 或 SDK client 调 watch，得 taskId）
# 2) 导出
java -jar arthas-mcp-gateway.jar portal tasks export <taskId> -o result.json
# 或 API
curl -s -OJ http://localhost:8761/admin/tasks/<taskId>/export?format=json
```

**预期**（A-EXP-1 / INV-EXP-1）：导出 JSON 含 `taskId/tool/target/status=completed/createdAt/completedAt` + `frames[]`；`frames` 与 `arthas-gateway.task-get`（同 taskId）结果**逐字一致**（原样透传，宪法原则二）。

**错误路径**（A-EXP-2）：导出 `working`/`cancelled`/不存在任务 → 409 / 404 + 错误体，不返空。

---

## 3. 场景 C：能力按需开关（FR-012 / R9）

```bash
# 关闭后端 CRUD（application.yml: arthas-gateway.admin.crud.enabled=false），重启网关
curl -s http://localhost:8761/admin/backends         # → 404（端点未装配）
curl -s http://localhost:8761/admin/tasks/x/export   # → 仍正常（export 独立开关，INV-SWITCH-1）

# 反向：关闭 export，crud 仍正常（INV-SWITCH-2）
```

---

## 4. 回归对照（不得破 001/002/003）

```bash
./mvnw verify
```

**预期**（INV-ISOL-1 / SC-004）：既有 38 工具 tools/list 契约（`InitializeAndToolsListContractTest` 等）、双侧契约、热重载（`HotReloadIT`）、异步任务、K8S 编排（003 `*IT`）全绿；`/admin` 操作不影响 `/mcp`。

---

## 5. 验证清单（Done Definition）

- [ ] 场景 A 后端 CRUD：静态写 YAML 热重载（A-ADD-1）、动态不可改可删（INV-DYN-1）、列表健康一致（SC-003）、凭据脱敏（INV-SECRET-1）。
- [ ] 场景 B 任务导出：completed 任务原样导出（A-EXP-1/INV-EXP-1）、未完成/不存在→409/404（A-EXP-2）。
- [ ] 场景 C 能力开关：crud/export 独立 `@ConditionalOnProperty`，关闭=404（INV-SWITCH-1/2）。
- [ ] 回归：001/002/003 + 38 工具契约不破（INV-ISOL-1/SC-004）。
- [ ] gateway-core 零 K8S 依赖不变（003 `PackageBoundaryTest` 继续通过）。
