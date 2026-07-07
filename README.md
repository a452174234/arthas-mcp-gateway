# Arthas MCP 网关

> 聚合多个目标 JVM 的 [arthas](https://arthas.aliyun.com/) 诊断能力，向 [Claude Code](https://docs.claude.com/en/docs/claude-code) 等 MCP 客户端统一暴露为单一 MCP 服务（38 个工具）。

**Feature**: `001-arthas-mcp-gateway`（基础：35 工具诊断聚合）+ `003-k8s-arthas-mcp-launch`（K8S 编排：+3 工具，对指定 pod 一键拉起 arthas MCP + 暴露 + 动态纳管） | **版本**: 0.1.0-SNAPSHOT | **语言/文档**: 中文（技术名词保留英文）

---

## 这是什么

arthas 是 Java 生态最强的在线诊断工具，但每个目标 JVM 各起一个 MCP 端点，对 Claude Code 等客户端而言：要管理多个 server、无法跨目标编排、长任务（`watch`/`trace`/`stack`/`tt`/`monitor`）会阻塞会话。

本网关作为 **MCP 反向代理 + 编排层**：

- **统一入口**：把 N 个目标 JVM 的 arthas 聚合为**一个** MCP server，对 Claude Code 只暴露一个端点。
- **`target` 路由**：每个 arthas 工具注入必填 `target` 参数，网关据此转发到对应后端，结果原样回传。
- **异步长任务**：5 个长耗时工具（`watch`/`trace`/`stack`/`tt`/`monitor`）后台化，立即返回 `taskId`，经 `task-get/list/cancel` 跟踪。
- **故障隔离**：单后端故障不影响其他目标；熔断 + per-target 限流 + 30s 内明确错误。
- **热重载**：改 `config/backends.yaml` 免重启增删目标。
- **K8S 编排**（003）：对指定 K8S pod 一键拉起 arthas MCP（上传 arthas + 启动 + NodePort 暴露 + 动态纳管 + 健康检查），诊断结果明确来自该 pod JVM（[SC-001](./specs/003-k8s-arthas-mcp-launch/spec.md)）。

> **不依赖 arthas 工程源码**：`arthas-boot.jar` 作为静态工具文件置于 `tools/`，经 `java -jar` 使用；不入 Maven 依赖、不构建 reference 源码。

---

## 技术栈

| 项 | 版本/说明 |
|---|---|
| JDK | 21（LTS，虚拟线程承载异步长任务） |
| Spring Boot | 4.1.0 |
| Spring AI | 2.0.0（`spring-ai-starter-mcp-server-webmvc` + `-client`） |
| MCP Java SDK | 2.0.0（`io.modelcontextprotocol.sdk`，官方 SDK，[上游已通过官方 conformance 套件验证](https://github.com/modelcontextprotocol/java-sdk)） |
| 传输 | MVP 仅 Streamable HTTP（`/mcp`，默认端口 8761；stdio 与 HTTP 互斥，stdio 延后） |

---

## 构建与运行

> 📋 **新环境快速启动 + 全面验证 MCP 可用**（含**远端 K8S 调用场景**设计、测试用例分层、能力矩阵）→ 详见 **[docs/getting-started.md](./docs/getting-started.md)**。本节为极简版。

```bash
# 构建（本地与 CI 同命令）—— 含 surefire 单测 + failsafe 真实 arthas 集成测试
./mvnw clean verify

# 启动网关（默认 HTTP :8761，MCP 端点 /mcp）
./mvnw spring-boot:run
```

启动后：

- 健康检查：`GET http://localhost:8761/actuator/health` → `{"status":"UP", ...}`（details 含各后端健康）。
- MCP 端点：`http://localhost:8761/mcp`。

### 配置后端映射表

编辑 `config/backends.yaml`（实体见 [specs/.../data-model.md §2](./specs/001-arthas-mcp-gateway/data-model.md)）：

```yaml
version: 1
backends:
  - name: order-service
    url: http://10.0.0.10:8563/mcp        # arthas MCP 根 URL
    protocol: STREAMABLE
    auth: { mode: NONE }                   # NONE / BEARER / BASIC
    callTimeoutMs: 30000
    maxConcurrentTasks: 5
  - name: payment
    url: http://10.0.0.11:8563/mcp
    protocol: STREAMABLE
    auth: { mode: BEARER, token: ${PAYMENT_TOKEN} }
```

改完保存即生效（热重载，免重启）。

---

## 接入 Claude Code

MVP 为 HTTP server，写一份 MCP 配置（不污染全局）：

```json
{
  "mcpServers": {
    "arthas-gw": { "type": "http", "url": "http://localhost:8761/mcp" }
  }
}
```

```bash
claude -p "列出 arthas-gw 暴露的全部工具名，仅输出 JSON 数组" \
  --mcp-config target/smoke-mcp-config.json --strict-mcp-config
# → 38 个工具名（4 自有 arthas-gateway.* + 31 arthas + 3 K8S 编排 k8s.*；每个 arthas 工具带 target 参数）
```

> Claude Code 把工具句柄中的 `.` 显示为 `_`；`--allowedTools "mcp__arthas-gw__*"` 通配可覆盖全部 38 工具。

---

## 工具集（38 = 35 既有 + 3 K8S 编排）

| 类别 | 数量 | 路由模式 | 示例 |
|---|---|---|---|
| 即时诊断（同步转发） | 26 | `SYNC_DIRECT` | `jvm`/`thread`/`dashboard`/`ognl`/`jad`/`sc`/`heapdump` … |
| 长任务（异步） | 5 | `ASYNC_TASK` | `watch`/`trace`/`stack`/`tt`/`monitor`（立即返 `taskId`） |
| 聚合（同步多帧） | 1 | `STREAM_AGGREGATE` | `dashboard` |
| 网关自有 | 4 | `GATEWAY_LOCAL` | `list-targets`/`task-get`/`task-list`/`task-cancel` |
| K8S 编排（003） | 3 | `GATEWAY_LOCAL` | `k8s.list-pods`/`k8s.list-services`/`k8s.ensure-arthas-mcp`（自带闭包、不经路由器、无 `target` 参数） |

工具静态来自 `src/main/resources/arthas-tools.json`（31 arthas + 4 网关自有 = 35，**不随后端数变化**）；3 个 K8S 编排工具由 `K8sToolRegistry` 程序化注册。诊断类工具带必填 `target` 参数；K8S 编排工具无 `target`（目标由 `server`/`pod` 参数指定）。

---

## 管理面 portal（Web UI，004）

004 落地 003 P3 portal 的**配置管理子集**：浏览器访问网关根加载 Vue 3 SPA（内嵌单 JAR），同源调 `/admin` HTTP API。

- **后端 `/admin` API**（Java）：`/admin/backends` CRUD（静态写 `backends.yaml` 热重载 / 动态 `DynamicBackendStore`）+ `/admin/tasks` 任务列表查询（摘要无 frames + `status`/`tool`/`target` 过滤 + `page`/`size` 分页 + `createdAt` 倒序，FR-015）+ `/admin/tasks/{id}/export`（`completed` 任务原样 JSON 下载，宪法原则二）。能力各自 `@ConditionalOnProperty` 按需开关（`arthas-gateway.admin.crud.enabled` / `export.enabled`，默认开；列表与导出共用 `export.enabled`）。Noop 鉴权（受控内网）。
- **前端 SPA**（`web/`，Vue 3 + Vite + TypeScript）：后端管理页（列表 + 增删改 + 健康徽标 + 凭据脱敏）、任务导出页（最近任务列表浏览 + status 过滤 + 分页 + 点列表项衔接 taskId 导出 + 原样下载；空态/错误态自验证反馈）。前端=**展示层**（核心逻辑 Java 后端，宪法原则六对齐）。`vite build` → `target/classes/static/` 内嵌单 JAR。
- **构建**：`./mvnw verify` 经 `frontend-maven-plugin` 跑 `npm install + build`，产出**含前端 SPA 的单 JAR**（CI 可复现，开发期 `-DskipFrontend=true` 跳前端）。
- **范围**：A 后端配置 CRUD + E 异步任务列表查询（增量）+ C 异步任务结果导出。B 动态持久化 / D 操作审计 / 任务可视化 / 鉴权 后置。

详见 [004 spec](./specs/004-portal-backend-management/spec.md)。

---

## 测试（真实环境，零桩）

**TDD 硬约束**：所有测试先于实现编写；**禁桩**——真实 arthas MCP + 真实业务服务产生真实诊断，故障用真实故障条件（停 JVM=不可达、关闭端口=连接拒绝、错 token=真实 401）。

| 阶段 | 引擎 | 内容 |
|---|---|---|
| `*Test` | surefire | 纯逻辑单测（熔断状态机、限流、解析、健康指标等） |
| `*IT` | failsafe | 真实 arthas + 真实网关（路由、异步、热重载、故障隔离、限流） |

驱动分层（宪法原则四/七）：
- **工具可用性**：真实 Claude Code 走 MCP（逐工具冒烟，仅验"调通"）。
- **结果一致性 + 双侧协议契约**：官方 MCP Java SDK client（确定性断言）。

K8S 编排（003）的真实供给契约测试（`K8sListToolsContractIT`/`ArthasProvisionerIT`/`K8sEnsureContractIT`）在 `test-env/k8s/` 一键幂等起的真实 k3s 集群（debian 服务器）上跑——CI 默认 Assume 跳过、本地手跑；见 [003 Quickstart](./specs/003-k8s-arthas-mcp-launch/quickstart.md)。

端到端验证场景（A–F）与逐工具冒烟见 [001 Quickstart](./specs/001-arthas-mcp-gateway/quickstart.md)。

---

## 可观测性

- **结构化日志**（`ToolsCallRouter`）：每次路由记 `tool/target/isError/耗时`；后端业务错误带 MCP 错误码（显式传播，不吞为静默成功）；基础设施故障/熔断拒绝/并发限流各自结构化日志。
- **Actuator**：`/actuator/health` details 暴露各后端 `{state, healthy, protocol, breaker}` + `{total, healthy, unhealthy}` 汇总（运维无需读源码）。

---

## 项目结构

```
src/main/java/com/arthas/gateway/
├── GatewayApplication.java        # 启动入口
├── backend/                       # 后端配置/注册表/熔断/限流/HTTP 客户端
├── handler/                       # ToolsCallRouter（路由）+ GatewayToolHandlers（自有工具）
├── task/                          # 异步任务执行器 + TaskStore（内存）
├── tool/                          # 工具注册表 + 路由模式
├── config/                        # Spring 装配 + 热重载监听 + Bootstrap + K8sProperties
├── orchestration/                 # K8S 编排（003）：K8sClientFactory/PodExplorer/NodePortExposer/ArthasProvisioner/ToolHandlers（gateway-core 零 K8S 依赖，编排层独立）
├── auth/                          # 后端认证头注入 + 网关侧认证占位（MVP Noop）
└── obs/                           # Actuator 健康指标
specs/001-arthas-mcp-gateway/      # 规格（plan/research/data-model/contracts/quickstart/tasks）
specs/003-k8s-arthas-mcp-launch/   # 003 规格（K8S 编排：plan/research/data-model/contracts/quickstart/tasks）
test-env/k8s/                      # K8S 真实测试床（setup/teardown/Dockerfile.demo/kubeconfig——凭证 gitignored）
tools/                             # arthas-boot.jar（静态工具文件，ensure 时经 fabric8 上传到目标 pod）
```

---

## CI

[`.github/workflows/ci.yml`](./.github/workflows/ci.yml) 跑 `./mvnw clean verify`，复现本地构建（宪法"CI 必须能复现本地构建"）。

---

## 演进项（MVP 之后）

- **网关侧认证**（宪法首要演进项）：`GatewayAuthenticator` 接口缝已就位（MVP Noop 放行，受控内网）；接入 Bearer/API key 时实现该接口 + 请求过滤。
- **后端 401 处理**（C-AUTH-1）：随认证后端夹具落地（当前 NONE 认证夹具无 401 路径，TDD 零桩约束下不提前实装）。
- **stdio 传输 / 双传输对等**（S-DUAL-1）：MVP 仅 HTTP（stdio/HTTP 互斥）；路由层传输无关（单一 `ToolsCallRouter`），对等性由构造保证；stdio 启用后补真机对等测试。
- **官方 conformance 套件接入**：底层 SDK [上游已验证](https://github.com/modelcontextprotocol/java-sdk)；网关协议面由 S-*/C-*/G-* 契约测试覆盖。完整跨语言 harness 接入（指向 `/mcp`）见 [modelcontextprotocol/conformance](https://github.com/modelcontextprotocol/conformance)。
