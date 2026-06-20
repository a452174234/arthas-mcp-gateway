# Contracts: 整改不变量与契约点（Phase 1）

**Feature**: 002-code-review-remediation
**Date**: 2026-06-21

> 本特性为**只读评审整改**：对外可观测 MCP 契约须**逐项保持**。本文档分两部分：
> - **A. 保持的契约点**（FR-016）——整改不得改变的行为/报文。
> - **B. 新增/收紧的不变量**——评审要求修复带来的**预期行为修正**（非回归，须在契约测试显式断言）。
>
> 对外 MCP 报文与工具/资源定义的权威契约沿用 [001 contracts/](../../001-arthas-mcp-gateway/contracts/)（`server-contract.md`/`backend-client-contract.md`/`gateway-tools-contract.md`），本文不重复其 S-*/C-*/G-* 断言，只标注本次需**重跑**与**新增**的断言落点。

---

## A. 保持的契约点（不得改变 · FR-016）

### A.1 工具与资源定义（不变）

- `tools/list` 仍返回 **35 个工具**（31 arthas 各带 `target` required + 4 网关自有）；数量、name、description、inputSchema **逐字不变**。
- P3-5 死代码删除（`gatewayOwned`/`wireValue`/`REASON_CIRCUIT_OPEN`）不影响任何对外 schema——这些是无调用点的内部成员。
- **断言落点**：001 `server-contract.md` S-TL-1/2/3（重跑，须仍全绿）。

### A.2 路由语义（不变）

- `target` 参数仍被剥离、永不进 `backendArgs`；按 `target` 路由到对应后端。
- 同步/异步分流（26 SYNC_DIRECT / 1 STREAM_AGGREGATE / 5 ASYNC_TASK / 4 GATEWAY_LOCAL）不变。
- **断言落点**：S-CALL-1、C-CALL-1（重跑）。

### A.3 结果原样透传（不变 · 原则二）

- `invoke` 返回 `client.callTool()` 的原始 `CallToolResult`，不篡改/截断/摘要。
- 后端 `isError=true` 仍原样透传（同步）/ 原样存入 task.result（异步）。
- 后端 JSON-RPC error（含 code/message/data）原样透传。
- **断言落点**：C-RESULT-1/2、G-ASYNC-2（重跑）。

### A.4 错误传播结构（字段不变）

整改后错误仍以结构化 `McpError` 传播，**字段集逐字不变**：

| 错误情形 | code | data 字段 |
|---|---|---|
| 目标熔断中 | `backend_unreachable` | `reason`、`retryAfterMs`、`available`（全部目标名） |
| 目标不可达 | `backend_unreachable` | `reason`、`available` |
| 并发越界 | INVALID_PARAMS(-32602) | `maxConcurrentTasks`、`available` |
| target 缺失/不在册 | INVALID_PARAMS | `available` |
| 未知工具 | INVALID_PARAMS | — |

> 路由器翻译域异常→`McpError` 时，`data.available`/`retryAfterMs`/`reason`/错误码**必须与修复前逐字一致**（这是重构的安全边界）。
- **断言落点**：S-ERR-*、C-CB-1/2、C-ISO-1（重跑，报文逐字段断言）。

### A.5 热重载与健康（语义不变，实现收敛）

- 热重载增删目标、配置版本去重、加载失败保留旧表——语义不变。
- `list-targets` 返回的每目标 `healthy` 字段语义不变（ACTIVE 且非熔断 OPEN = healthy）；P3-2 仅把"三处重复判定"收敛为**单一事实源** `BackendEntry.isHealthy()`，**输出值不变**。
- `/actuator/health` 状态判定不变。
- **断言落点**：G-LT-1、S-ERR-2、HealthIndicator 断言（重跑）。

---

## B. 新增/收紧的不变量（预期行为修正 · 须断言）

> 这些是评审要求修复的行为变化，属**修正**而非回归。每条对应一条 FR 与契约断言。

### B.1 STATELESS 异步前置拒绝（P1-2 / FR-004）

- **变更**：对 STATELESS 后端的**异步类**诊断调用，**前置**返回 INVALID_PARAMS（`reason=stateless_unsupported_async`），不提交后台、不耗尽兜底超时。
- **保持**：STATELESS 后端的**同步**调用仍正常工作（FR-004 边缘情况）。
- **断言**：新增 `ToolsCallRouterStatelessTest`——STATELESS 异步立即收到结构化错误；同步调用正常。

### B.2 异步路径驱动熔断（P1-3 / FR-005）

- **变更**：纯异步负载下后端基础设施不可达，计入熔断 `recordFailure`；达阈值熔断 OPEN，后续调用被隔离。
- **保持**：人为取消（`InterruptedException`）**不计**失败；后端业务错误（`McpError`/`isError=true`）**不计**失败。
- **断言**：新增/更新 `BackendEntryInterceptionLayerTest`——异步 invoke 基础设施异常触发 `recordFailure`；取消中断不触发。

### B.3 熔断线程安全（P1-1 / FR-003）

- **新增不变量**：默认并发 N（N>1）下，多线程并发 `recordFailure`，连续失败计数**原子累加**，达阈值如期 OPEN；不出现"丢失更新导致该断不断"、不出现"HALF_OPEN 放行多个探测"。
- **断言**：新增 `CircuitBreakerConcurrencyTest`——真实多线程并发记录失败，断言熔断在阈值点精确开启。

### B.4 initialize 原子（P1-4 / FR-006）

- **新增不变量**：并发首次路由同一后端，仅一次握手（`initialize` 真正执行一次）。
- **断言**：新增 `HttpBackendClientInitializeCasTest`——并发调用 `initialize`，握手副作用恰好一次。

### B.5 关闭竞态无僵尸/无槽泄漏（P0-1/P0-2 / FR-001/002）

- **新增不变量**：后台池已关闭时提交异步任务——任务**不**长期驻留 WORKING（被 remove 或 markFailed）；已取的槽被释放（目标不锁死）。
- **新增不变量**：内层提交失败（orchestrate）——任务标终态（无僵尸）；槽经 `onTerminal` 释放。
- **断言**：新增 `AsyncTaskExecutorShutdownRaceTest`——关闭池后提交，断言 store 无残留 WORKING、槽许可全数回收。

### B.6 退役不切断 in-flight（P2-1 / FR-007）

- **新增不变量**：目标退役时，其上 in-flight 异步任务能在退役宽限（默认=backendTimeout）内完成，不被强制标 failed。
- **断言**：退役 + in-flight 异步任务场景，断言任务 completed（非 failed）。

### B.7 null 可选参数（P2-2 / FR-008）

- **新增不变量**：含 null value 的合法调用被正常路由转发，不因防御拷贝抛 NPE/内部错误。
- **断言**：新增 `DiagnosticRequestNullArgTest`——`backendArgs` 含 null value，构造与转发正常。

### B.8 单点查询 O(1)（P2-3 / FR-009）

- **新增不变量**：`TaskStore.get(taskId)` 不触发全表清理；单查开销与存储规模无关。
- **断言**：新增 `TaskStoreGetReadAmplificationTest`——大规模存储下 get 不扫全表（如用计数探针断言清理未被触发）。

### B.9 全局背压（P2-4 / FR-010）

- **新增不变量**：跨 target 累计 inflight 有全局上限；超限被拒绝/排队，不无界增长。
- **断言**：多 target 高并发，断言全局 inflight ≤ 上限。

### B.10 凭据脱敏（P3-1 / FR-011）

- **新增不变量**：`BackendConfig.Auth.toString()` 仅含 mode + 掩码，不含明文凭据。
- **断言**：新增 `BackendConfigAuthMaskingTest`——各 mode 下 toString 不含明文 token/username/password。

### B.11 健康单一事实源（P3-2 / FR-012）

- **新增不变量**：`list-targets`/`HealthIndicator`/`admit` 守卫三处健康判定均委托 `BackendEntry.isHealthy()`。
- **断言**：三处对同一后端返回一致的 healthy 值；改 `isHealthy` 一处，三处联动。

### B.12 配置解析拒截断（P3-4 / FR-014）

- **新增不变量**：`asInt` 对浮点/超大整数报错且错误信息保留原始值；`readVersion` 拒浮点。
- **断言**：新增 `BackendConfigLoaderParsingTest`——`5.0`/超 int 范围 Long 报错并保留原值。

### B.13 死代码清除（P3-5 / FR-015）

- **不变量**：`gatewayOwned`/`wireValue`/`REASON_CIRCUIT_OPEN` 已删；`asNullableString` 合并入 `asString`；编译通过、行为不变。
- **断言**：全套既有测试 + 冒烟全绿（无调用点丢失）。

---

## C. 契约测试执行（回归门禁）

| 套件 | 动作 | 来源 |
|---|---|---|
| 001 全部双侧契约（S-*/C-*/G-*） | **重跑**，须全绿（A 组保持点） | 001 `contracts/` |
| 本特性新增单测/契约（B.1~B.13） | **新增**，先于实现编写（TDD） | 本特性 `src/test/...` |
| 官方 conformance-tests 子套件 | 重跑（可选加固） | MCP SDK |
| 端到端冒烟（真实 arthas + 真实业务服务） | **重跑**，行为与修复前逐项一致（SC-008） | `quickstart.md` |

> 任何对外报文字段/行为的偏差即视为 FR-016 回归，须立即停下修复而非继续堆叠（用户约束）。
