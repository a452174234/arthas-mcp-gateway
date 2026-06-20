# Quickstart: 代码评审发现修复（端到端验证指南）

**Feature**: 002-code-review-remediation | **Date**: 2026-06-21

> 本文是**可运行的端到端验证指南**，证明 15 项发现的整改达成 spec 的 SC-001~SC-008，且**不影响对外 MCP 行为**（FR-016）。实现细节归属 `tasks.md`（/speckit-tasks 产出）与实现代码。基线运行方式与 [001 quickstart](../001-arthas-mcp-gateway/quickstart.md) 一致，本文聚焦"整改专项验证 + 全量回归"。实体变更见 [data-model.md](./data-model.md)，对外契约点见 [contracts/remediation-invariants.md](./contracts/remediation-invariants.md)。

---

## 1. 前置条件

与 001 一致（JDK 21 / Maven / ≥1 个真实 arthas MCP 后端 + 真实业务服务 / Claude Code / 受控内网）。整改不改变前置条件。

> **真实性硬约束**（CLAUDE.md）：网关自身并发/资源逻辑用真实 JVM 并发原语测，**零桩**；与 arthas 交互遵循驱动分层（可用性走 Claude Code、一致性/契约走官方 SDK client）。

---

## 2. 构建与运行（与 001 一致）

```bash
./mvnw clean verify          # 本地与 CI 同命令
./mvnw spring-boot:run       # 默认 HTTP :8761，端点 /mcp
```

启动后 `/actuator/health` 返回 `{"status":"UP"}`。配置后端映射表 `config/backends.yaml`、接入 Claude Code 方式同 [001 quickstart §2~§3](../001-arthas-mcp-gateway/quickstart.md)。

---

## 3. 整改专项验证场景（每项映射 FR/SC）

> 这些场景证明 15 项发现已修复。网关自身逻辑（A/B/C/D/F）用单测/集成测；与 arthas 交互（E）用真实后端。

### 场景 A · 关闭竞态无僵尸、无槽泄漏（→ FR-001/002，SC-001）

1. 使后台执行池进入关闭/拒绝提交的竞态（如注入一个会在 submit 时抛 `RejectedExecutionException` 的受控执行池，触发**真实失败条件**）。
2. 此时提交异步任务。
3. **期望**：任务**不**长期驻留 WORKING（被 `store.remove` 或 `markFailed`）；已取的 per-target 槽被 `onTerminal` 释放（许可全数回收）；该目标重启后仍可被正常调用。
4. 断言：`AsyncTaskExecutorShutdownRaceTest`。

### 场景 B · 熔断线程安全 + 异步驱动熔断 + 取消不计（→ FR-003/005，SC-002）

1. 默认并发 N>1，对同一目标并发记录连续基础设施失败（受控 client 抛连接异常=**真实失败条件**）。
2. **期望**：达阈值（连续 3 次）熔断如期 OPEN，无"丢失更新"。
3. 对纯异步路径发起不可达调用：**期望**失败计入熔断，后续被隔离。
4. 人为取消一个进行中异步任务：**期望**不 `recordFailure`、不误熔断。
5. 断言：`CircuitBreakerConcurrencyTest`、`BackendEntryInterceptionLayerTest`。

### 场景 C · STATELESS 异步前置拒绝（→ FR-004，SC-003）

1. 配置一个 STATELESS 后端（`protocol: STATELESS`）。
2. 对其调用一个异步类工具（如 `watch`）。
3. **期望**：**立即**收到 INVALID_PARAMS（`reason=stateless_unsupported_async`），不提交后台、不耗尽兜底超时。
4. 对照：对其**同步**类工具调用仍正常（边缘情况）。
5. 断言：`ToolsCallRouterStatelessTest`。

### 场景 D · initialize 原子（→ FR-006）

1. 并发首次路由同一后端（受控 client 计数握手副作用）。
2. **期望**：握手副作用**恰好一次**。
3. 断言：`HttpBackendClientInitializeCasTest`。

### 场景 E · 与真实 arthas 的回归（→ FR-016，SC-008）

> 用真实 arthas MCP 后端 + 真实业务服务，确认整改后**对外行为与修复前逐项一致**。沿用 001 场景 B/C/D/E/F。

1. 工具集：`tools/list` 仍 35 个、每个 arthas 工具带 `target`（场景 A）。
2. 路由：`jvm target=order-service` 结果来自正确 JVM、与直连一致（场景 B）。
3. 异步长任务：`watch` 立即返回 taskId→`task-get` 取最终结果原样（场景 C）。
4. 热重载：增删目标 30s 内生效（场景 D）。
5. 故障隔离：停 `order-service`，对 `payment` 仍正常、对 `order-service` 30s 内明确错误（场景 E）。
6. 多客户端并发互不干扰（场景 F）。
7. 驱动：可用性走 `claude -p --mcp-config target/smoke-mcp-config.json --strict-mcp-config`；一致性/双侧契约走 `./mvnw verify`。

### 场景 F · 健壮性四项（→ FR-007/008/009/010，SC-004/005）

1. **退役不切断**（FR-007）：目标有 in-flight 异步任务时退役，任务能在退役宽限（默认=backendTimeout）内 completed（非 failed）。
2. **null 参数**（FR-008）：调用诊断工具传入含 null 的可选参数，正常转发不报内部错误。断言：`DiagnosticRequestNullArgTest`。
3. **单查 O(1)**（FR-009）：大规模 task 存储下高频 `task-get`，单查不扫全表。断言：`TaskStoreGetReadAmplificationTest`。
4. **全局背压**（FR-010）：多 target 高并发，跨 target 累计 inflight ≤ 全局上限。

### 场景 G · 安全卫生与清理（→ FR-011/012/013/014/015，SC-006/007）

1. **凭据脱敏**（FR-011）：配置带凭据后端，`Auth.toString()` 仅 mode + 掩码。断言：`BackendConfigAuthMaskingTest`。
2. **健康单一事实源**（FR-012）：`list-targets`/`/actuator/health`/路由守卫三处 healthy 一致，改 `isHealthy` 一处联动。
3. **序列化单例**（FR-013）：`McpJson` 单例被三处复用（编译期/结构断言）。
4. **配置拒截断**（FR-014）：`backends.yaml` 写 `maxConcurrentTasks: 5.0` 或超大整数，加载报错且错误信息保留原值。断言：`BackendConfigLoaderParsingTest`。
5. **死代码清除**（FR-015）：`gatewayOwned`/`wireValue`/`REASON_CIRCUIT_OPEN` 已删，编译通过、全套测试全绿。

---

## 4. 测试（真实环境，零桩；TDD）

### 4.1 单元/集成（网关自身逻辑，真实 JVM 并发原语）

```bash
./mvnw verify -Dtest="com.arthas.gateway.**"
```

- 全部**先于实现编写**（TDD 红绿重构，宪法原则七）。
- 关闭竞态/熔断并发/槽 RAII/CAS/读放大——真实执行池、真实线程、真实并发计数，**非 arthas 成功桩**；受控 callable/client 触发**真实失败条件**（抛基础设施异常、拒绝提交、中断）。

### 4.2 双侧契约 + 结果一致性（官方 SDK client 驱动 · 真实 arthas）

```bash
./mvnw verify -Dtest="com.arthas.gateway.**"   # 含 001 既有双侧契约套件
```

- **重跑** 001 全部 S-*/C-*/G-* 断言（FR-016 保持点），须全绿。
- **新增** 针对 P1-2（STATELESS）/P1-3（异步熔断）的契约断言（[contracts/remediation-invariants.md](./contracts/remediation-invariants.md) B 组）。
- 故障用**真实条件**：停真实后端=不可达、错 token=arthas 真实 401、`Thread.sleep`=慢响应、真实发起 6 并发越界=arthas 真实 INVALID_PARAMS。

### 4.3 端到端冒烟（真实 Claude Code · SC-008）

```bash
# 启网关（默认 HTTP :8761）
./mvnw spring-boot:run &

# 真实 Claude Code 经 MCP 验证工具可用性（逐工具冒烟，仅验"能调通"）
claude -p "列出 'arthas-gw' 暴露的全部工具名，仅输出 JSON 数组" \
  --mcp-config target/smoke-mcp-config.json --strict-mcp-config
# → 35 个工具名（与修复前一致）

claude -p "调用 arthas-gw 的 list-targets，原样输出" \
  --mcp-config target/smoke-mcp-config.json --strict-mcp-config \
  --allowedTools "mcp__arthas-gw__*"
# → 每目标 healthy 值与修复前一致
```

> 报告模板：`arthas-mcp-gateway-冒烟测试报告.md`（memory `mcp-smoke-via-claude-p`）。

---

## 5. 逐步验证纪律（用户约束 · FR-017）

每修一项发现，按此顺序推进下一项前必须：

1. 该项的失败测试（红）→ 实现（绿）→ 重构。
2. **既有测试全绿**（`./mvnw verify`，确认未引入回归）。
3. **端到端冒烟全绿**（§4.3，确认对外行为完好）。
4. 任一步失败 → **停下**该修复链，先排查（CLAUDE.md「同一问题连续失败 3 次暂停」）。

推进顺序（契合设计文档 §七）：A 组核心（P1-1→P1-4→execute/admit/invoke/onTerminal→router 委托+STATELESS）→ B 组健壮性（P2-2→P2-1→P2-3→P2-4）→ C 组清理（P3 批量）→ 回归门禁（全套 + 冒烟）。

---

## 6. 排查指引（整改专项）

| 现象 | 检查 |
|---|---|
| 槽泄漏重现（目标被永久拒绝） | 路由器闭包是否还残留手动 `releaseSlot`；`onTerminal` 是否覆盖所有终态路径（场景 A） |
| 熔断"该断不断" | `CircuitBreaker` 三方法是否都已 `synchronized`；异步 invoke 是否真的 `recordFailure`（场景 B） |
| STATELESS 异步仍挂起 | `admit` 是否在提交后台前校验协议（场景 C） |
| in-flight 被退役切断 | `retirementGrace` 是否 ≥ backendTimeout（场景 F.1） |
| null 参数报错 | `DiagnosticRequest` 是否仍用 `Map.copyOf`（应已改 unmodifiableMap+LinkedHashMap）（场景 F.2） |
| 对外报文字段偏差 | 路由器翻译域异常→`McpError` 的 `available`/`retryAfterMs`/`reason`/code 是否逐字一致（FR-016 回归） |
