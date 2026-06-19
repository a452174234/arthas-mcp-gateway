# Quickstart: Arthas MCP 网关（端到端验证指南）

**Feature**: 001-arthas-mcp-gateway | **Date**: 2026-06-19

> 本文是**可运行的端到端验证指南**，证明网关按 spec 的 SC-001~SC-005 与用户故事工作。实现细节归属 `tasks.md`（/speckit-tasks 产出）与实现代码，本文不含。线行为承诺见 `contracts/`，实体定义见 `data-model.md`，决策见 `research.md`。

---

## 1. 前置条件

| 项 | 要求 |
|---|---|
| JDK | 21（LTS），构建中 enforce 锁定 |
| 构建工具 | Maven（`mvnw` wrapper 随仓库） |
| 目标 JVM | ≥1 个已运行 arthas 并暴露 MCP 端点的 Java 进程（arthas http console `/mcp`） |
| Claude Code | 用于下游集成验证（或官方 SDK client / MCP inspector 代替） |
| 网络 | 受控内网（MVP 无认证，靠网络隔离；见 spec 假设） |

> 单目标即可验证核心链路；多目标（2~3）用于验证路由/隔离/热重载/并发。

---

## 2. 构建与运行

```bash
# 构建（本地与 CI 同命令，宪法"CI 复现本地构建"）
./mvnw clean verify
```

### 2.1 配置后端映射表

编辑 `config/backends.yaml`（实体见 [data-model.md §2](./data-model.md)）：

```yaml
version: 1
backends:
  - name: order-service
    url: http://10.0.0.10:8563/mcp
    protocol: STREAMABLE
    auth: { mode: NONE }
    callTimeoutMs: 30000
    maxConcurrentTasks: 5
  - name: payment
    url: http://10.0.0.11:8563/mcp
    protocol: STREAMABLE
    auth: { mode: BEARER, token: ${PAYMENT_TOKEN} }
```

### 2.2 启动网关

```bash
# stdio 模式（本地 Claude Code）
./mvnw spring-boot:run -Dspring-boot.run.arguments="--transports.stdio.enabled=true --transports.http.enabled=false"

# 或 HTTP 模式（远程/多客户端）
./mvnw spring-boot:run -Dspring-boot.run.arguments="--transports.http.enabled=true --transports.http.port=8761"
```

---

## 3. 接入 Claude Code

**stdio 模式**：在 Claude Code 的 MCP 配置注册网关为 stdio server，命令指向启动脚本。
**HTTP 模式**：注册为 Streamable HTTP server，URL `http://<gateway-host>:8761/mcp`。

接入后，Claude Code 的 `tools/list` 应见到 **35 个工具**（31 arthas 工具，每个带 `target`；4 网关自有）——验证见 §4 场景 A。

---

## 4. 端到端验证场景

每个场景映射一条成功标准或用户故事，可独立观察通过/失败。

### 场景 A · 工具集静态暴露（→ SC-001 前置、用户故事 1）

1. Claude Code 请求 `tools/list`。
2. **期望**：35 个工具；每个 arthas 工具含 `target` 参数（required）；工具数量**不随**后端数变化。
3. 断言依据：`server-contract.md` S-TL-1/2/3。

### 场景 B · 单网关多目标路由（→ SC-001、SC-005、用户故事 1）

1. 配置 3 个目标（order / payment / inventory）。
2. 经 Claude Code 调 `jvm` 工具，分别 `target=order-service` / `payment` / `inventory`。
3. **期望**：每次结果来自正确目标 JVM，且与直连该 arthas 后端一致（无篡改）。
4. 断言依据：S-CALL-1、C-RESULT-1/2。

### 场景 C · 异步长任务（→ 方案 C、用户故事 1 长任务）

1. 调 `watch` 工具 `target=order-service`（默认参数）。
2. **期望**：**立即**返回 `taskId` + `status:working`（不阻塞）。
3. 调 `task-get <taskId>`：未完成→working；完成→返回最终结果（原样）。
4. 调 `task-list`：见该任务；调 `task-cancel` 可取消。
5. 断言依据：`gateway-tools-contract.md` G-ASYNC-1/2、G-TG-1/2、G-TC-1。

### 场景 D · 热重载免重启（→ SC-002、用户故事 2）

1. 网关运行中、已有 2 目标。
2. 编辑 `backends.yaml` 新增第 3 个目标并保存。
3. **期望**：**30 秒内**（实测通常 <5s）`list-targets` 含新目标，对其诊断成功。
4. 移除某目标并保存：30s 内该目标从 `list-targets` 消失，对其调用返回明确错误。
5. 断言依据：G-LT-1、S-ERR-2。

### 场景 E · 单点故障隔离（→ SC-003、用户故事 3）

1. 使 `order-service` 不可达（停后端 / 断网）。
2. **期望**：对 `target=payment` 的诊断仍正常；对 `target=order-service` 的诊断 **30 秒内**返回明确错误（不超时、不静默成功）。
3. 断言依据：S-ERR-5、C-CB-1、C-ISO-1。

### 场景 F · 多客户端并发（→ SC-004）

1. 多个 Claude Code 客户端同时经同一网关对**不同** target 诊断。
2. **期望**：互不干扰，结果正确归属各自 target；某慢后端不拖慢其他。
3. 断言依据：S-CALL-3、C-ISO-1。

---

## 5. 测试（真实环境，零桩；TDD 真实性硬约束）

测试分两类驱动，**都跑在真实 arthas + 真实业务服务上**（Testcontainers 拉起，故障用真实故障条件，**无 WireMock 桩**）：

### 5.1 工具可用性（Claude Code 驱动）

真实 Claude Code 注册网关为 MCP server → 逐个调用 35 工具（31 arthas 用真实 target + 真实业务数据；4 自有工具合参）→ 断言每个**调用成功**（不报错）。**不做**结果一致性比对（一致性交 SDK 断言）。

### 5.2 结果一致性 + 双侧协议契约（官方 SDK client 驱动）

```bash
# 一致性 A/B（网关 vs 直连目标 arthas）+ 双侧协议契约，全真实环境
./mvnw verify -Dtest="com.arthas.gateway.**"
```

- **结果一致性**（SC-005）：SDK client 分别连 网关 / 直连目标 arthas，同一诊断 A/B 对照，逐字段断言 `CallToolResult` 完全一致。
- **服务端契约**：`server-contract.md` §7 的 S-* 断言。
- **客户端契约**：`backend-client-contract.md` §8 的 C-* 断言（故障用真实条件：停容器/错 token/sleep/6 并发越界）。
- **自有工具契约**：`gateway-tools-contract.md` §6 的 G-* 断言。
- **官方一致性**：可额外跑官方 conformance-tests 子套件。

> 除"网关健康检查"（Actuator `/actuator/health`）外，所有 MCP 调用**不 curl 裸打**——可用性走 Claude Code，一致性/契约走 SDK client。所有测试**先于实现编写**（TDD，宪法原则七），`./mvnw verify` 合并前须全绿。

---

## 6. 排查指引

| 现象 | 检查 |
|---|---|
| Claude Code 看不到工具 | 网关是否启动、传输配置、`tools/list` 是否返回 35（场景 A） |
| 路由到错误目标 | `target` 是否正确、`list-targets` 是否含期望目标、`arguments` 是否含 target（应被剥离，C-CALL-1） |
| 异步任务一直 working | 后端是否可达、后台是否超 11min 标 failed（G-ASYNC-2）、`task-get` 的 error.reason |
| 热重载未生效 | `config/backends.yaml` 路径、WatchService 事件、`version` 是否递增、加载失败是否保留旧表 |
| 401 / 认证失败 | `auth.mode` 与 token/user+pass 是否匹配后端 password（C-AUTH-1） |

详细定位见上游 [问题定位反向索引](../../../reference/arthas-docs/03-MCP/问题定位反向索引.md)。
