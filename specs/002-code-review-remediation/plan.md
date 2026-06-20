# Implementation Plan: 代码评审发现修复

**Branch**: `002-code-review-remediation` | **Date**: 2026-06-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-code-review-remediation/spec.md`

**Note**: 本文件由 `/speckit-plan` 命令填写。架构层决策（深度重构 + 统一拦截层）见 [设计文档](../../docs/superpowers/specs/2026-06-21-code-review-remediation-design.md)；逐项技术决策与备选见 [research.md](./research.md)；先决研究事实（arthas 行为）见 `reference/arthas-docs/03-MCP/`。

## Summary

对 `001-arthas-mcp-gateway` MVP 整库评审的 **15 项发现**（P0×2 / P1×4 / P2×4 / P3×5）做整改，**不影响对外可观测 MCP 行为**、**每步验证既有功能完好**。

技术总路线（设计文档已定）：**深度重构（altitude）**——把"熔断守卫 + 槽管理（RAII）+ 故障分类"下沉为 `BackendEntry` 的**统一拦截层**（`execute`/`admit`/`invoke`/`isHealthy` 四原语），路由器同步/异步两路径变薄、都委托；`AsyncTaskExecutor` 增 `onTerminal` 回调，把槽释放/全局背压释放收归执行器单点保证（结构性消灭 P0-2 槽泄漏）。P2/P3 在此之上做健壮性与清理。无新增运行时依赖（全 JDK 内置 + 复用 Jackson）。

## Technical Context

**Language/Version**: Java 21（与 001 基线一致，不变）。

**Primary Dependencies**: **不变**（沿用 001）。`io.modelcontextprotocol.sdk:mcp-bom:2.0.0` + Spring Boot 4.1.0 + Spring AI `mcp-spring-webmvc` + Actuator。测试 JUnit 5 + AssertJ。**无新增依赖**——`synchronized`/`AtomicBoolean`/`Semaphore`/`ScheduledExecutorService`/`Collections.unmodifiableMap`/`LinkedHashMap` 均 JDK 内置；`McpJson` 复用既有 Jackson。

**Storage**: N/A（与 001 一致，无持久化）。内存态后端注册表 / taskStore / 熔断器状态语义不变。

**Testing**: JUnit 5 + AssertJ。**真实环境、零桩**（宪法原则四/七 + CLAUDE.md 硬约束）。驱动分层（与 001 一致）：网关自身并发/资源逻辑用**真实 JVM 并发原语** + DIP 缝注入受控 callable/client 触发**真实失败条件**（非 arthas 成功桩）；与 arthas 交互——可用性走真实 Claude Code MCP、一致性/双侧契约走官方 MCP Java SDK client；故障用真实条件（停后端/错 token/sleep/6 并发越界）。TDD 红绿重构。

**Target Platform**: 与 001 一致（受控内网 JVM 服务）。

**Project Type**: web-service（沿用 001 单 Maven 模块；本特性**不新增包**，改既有包内类）。

**Performance Goals**:
- 槽/熔断同步开销可忽略（熔断非热路径；`synchronized` 仅护熔断状态机三方法）。
- 单点任务查询 O(1)（FR-009，与存储规模无关）。
- 其余沿用 001（同步转发开销可忽略、失效目标 30s 内明确错误、热重载 30s 内生效、多客户端互不干扰）。

**Constraints**:
- **不影响对外可观测 MCP 行为**（FR-016）：报文结构/字段（`available`/`retryAfterMs`/`reason`）、工具与资源定义、路由、原样透传、热重载、错误传播、健康状态逐项一致。
- 每步 TDD + 既有测试 + 冒烟全绿（FR-017）。
- 无新增运行时依赖；沿用既有构建/格式化/检查设置。

**Scale/Scope**: 15 项发现全修；不动协议契约结构、不动并发上限语义（同步+异步一起挡 5，保持现状）、不做评审驳回候选（REFUTED-1/2/3）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**宪法版本**：v1.2.0。逐原则核验（本特性为整改，对各原则的影响标「保持/强化」）：

| # | 原则 | 状态 | 依据 / 落地方式 |
|---|---|---|---|
| 一 | MCP 规范符合性 | ✅ 保持 | 统一拦截层不触碰 JSON-RPC 帧；`execute`/`invoke` 仅围绕官方 SDK `callTool` 做生命周期包装，不新增协议原语 |
| 二 | 透明无损聚合 | ✅ 保持 | `invoke` 返回 `client.callTool()` 原始 `CallToolResult` 原样透传；错误仍以结构化 `McpError` 传播，字段不变 |
| 三 | 局部故障韧性 | ✅ **强化** | P1-1 熔断线程安全（synchronized）、P1-3 异步驱动熔断、P2-4 全局背压——纯异步负载与集群规模下故障隔离更可靠；per-target 独立不变 |
| 四 | 双侧契约 | ✅ 保持 | 核心 `tools/call` 路径重写后重跑全部双侧契约测试；P1-2（STATELESS 前置拒绝）、P1-3（异步驱动熔断）为评审要求的**预期行为修正**，新增/更新对应契约测试显式断言 |
| 五 | 可观测性 | ✅ 保持 | 结构化日志（tool/target/isError/duration）不变；MCP 错误码与后端错误显式传播不变；P3-2 健康判定单一事实源使仪表盘与实际行为一致 |
| 六 | Java 主力 | ✅ 保持 | 全 Java 21；无引入非 Java 运行时依赖 |
| 七 | 测试驱动 | ✅ 保持 | 每项 TDD 红绿重构；真实性分层（网关自身逻辑用真实并发原语、与 arthas 交互遵循驱动分层），零桩 |
| 八 | 先决研究 | ✅ 保持 | 精读 15 项发现涉及的全部源码逐项核对（research.md §0）；arthas 5 并发上限语义（`DEFAULT_MAX_CONCURRENT_TASK_SESSIONS=5`）已查证 |

**技术与传输约束**：Java 21 ✅；Maven 锁定可复现 ✅；异步非阻塞多路复用（虚拟线程）保持 ✅；后端注册表配置声明（不硬编码）保持 ✅；协议核心优先官方 SDK、不手写帧 ✅。

**质量门禁**：TDD ✅；每步既有测试 + 冒烟全绿 ✅；CI 复现本地构建 ✅；FR-016 回归门禁（全套测试 + 端到端冒烟逐项一致）✅。

**结论**：✅ **门禁通过，无原则冲突**。本特性整体为"强化与保持"，不存在需 Complexity Tracking 记录的违规项。

## Project Structure

### Documentation (this feature)

```text
specs/002-code-review-remediation/
├── plan.md              # 本文件（/speckit-plan 产出）
├── research.md          # Phase 0 产出（/speckit-plan）
├── data-model.md        # Phase 1 产出（/speckit-plan）
├── quickstart.md        # Phase 1 产出（/speckit-plan）
├── contracts/
│   └── remediation-invariants.md   # Phase 1 产出：保持的契约点 + 新增不变量
└── tasks.md             # Phase 2 产出（/speckit-tasks，本命令不创建）
```

### Source Code (repository root)

> 本特性**不新增包、不新增模块**，在 001 既有结构内改既有类。下图标注每项改动落点（`△`=修改、`+`=新增方法/类、`−`=删除死代码）。

```text
src/main/java/com/arthas/gateway/
├── backend/
│   ├── BackendEntry.java           △ 统一拦截层：execute/admit/invoke/isHealthy/releaseSlot(+)
│   ├── BackendClient.java          (不变，接口)
│   ├── HttpBackendClient.java      △ initialize CAS 守卫(P1-4)（AtomicBoolean+）
│   ├── CircuitBreaker.java         △ allowRequest/recordSuccess/recordFailure synchronized(P1-1)
│   ├── Protocol.java               (不变；STATELESS 契约语义由 admit 校验)
│   ├── BackendConfig.java          △ Auth.toString 脱敏(P3-1)
│   ├── BackendConfigLoader.java    △ asInt 拒浮点/超界保留原值(P3-4)；asNullableString→asString 合并(P3-5)
│   └── BackendConfigWatcher.java   △ retirementGrace 默认=backendTimeout(P2-1)；retireAll 用 ScheduledExecutorService
├── handler/
│   ├── ToolsCallRouter.java        △ forwardSync/submitAsync 委托 execute/admit；翻译域异常→McpError；移除手动 releaseSlot(P0-2/P1-2/P1-3)
│   ├── GatewayToolHandlers.java    △ listTargets healthy 委托 BackendEntry.isHealthy(P3-2)
│   ├── DiagnosticRequest.java      △ 防御拷贝容忍 null(P2-2)（unmodifiableMap(LinkedHashMap)）
│   └── McpJson.java                + JSON 序列化单例(P3-3)（handler 包内 static MAPPER）
├── task/
│   ├── AsyncTaskExecutor.java      △ submit 增 onTerminal 参数；全局 Semaphore(P2-4)；外层 RejectedExecution→remove+onTerminal(P0-1/P0-2)；orchestrate 内层 submit 纳入 try(P0-2)
│   └── TaskStore.java              △ get 只判查到那一条(P2-3)
├── obs/
│   └── BackendRegistryHealthIndicator.java  △ healthy 委托 BackendEntry.isHealthy(P3-2)
├── tool/
│   ├── ExposedTool.java            − gatewayOwned()(P3-5 死代码)
│   └── TaskSupport.java            − wireValue()(P3-5 死代码)
└── task/
    └── TaskError.java              − REASON_CIRCUIT_OPEN(P3-5 死代码)

src/main/java/com/arthas/gateway/backend/   (新增域异常类，统一拦截层抛出、路由器翻译)
├── CircuitOpenException.java        + (retryAfterMs)
├── ConcurrencyLimitException.java   + (maxConcurrentTasks)
├── StatelessAsyncException.java     +
└── BackendUnreachableException.java + (cause)

src/test/java/com/arthas/gateway/
├── backend/
│   ├── CircuitBreakerConcurrencyTest.java      + 真实并发记录失败(P1-1/FR-003)
│   ├── HttpBackendClientInitializeCasTest.java + 并发首次握手只一次(P1-4/FR-006)
│   ├── BackendEntryInterceptionLayerTest.java  + execute/admit/invoke 统一拦截(P0/P1-2/P1-3/FR-012)
│   └── BackendConfigAuthMaskingTest.java       + toString 脱敏(P3-1/FR-011)
├── task/
│   ├── AsyncTaskExecutorShutdownRaceTest.java  + 池关闭竞态：无僵尸/无槽泄漏(P0-1/P0-2/FR-001/002)
│   └── TaskStoreGetReadAmplificationTest.java  + get 不扫全表(P2-3/FR-009)
├── handler/
│   ├── ToolsCallRouterStatelessTest.java       + STATELESS 异步前置拒绝(P1-2/FR-004)
│   ├── DiagnosticRequestNullArgTest.java       + null 可选参数(P2-2/FR-008)
│   └── BackendConfigLoaderParsingTest.java     + 浮点/超界报错保留原值(P3-4/FR-014)
└── contract/                       (沿用 001 既有双侧契约套件，新增/更新针对 P1-2/P1-3 的断言)
```

**Structure Decision**: 沿用 001 单 Maven 模块、按职责分包。本特性不新增包结构，新增的 4 个域异常类归入 `backend/`（与抛出方 `BackendEntry` 同包，体现"域异常属后端域"）；`McpJson` 归 `handler/`（与三处消费方同包）。改动严格限定在评审点，不做无关重构（CLAUDE.md「简单性、沿用既有模式」）。

## Complexity Tracking

> Constitution Check 全部通过，无违规项需记录。本表为空。

（无。深度重构属评审点 P1 的根因整治，非超出范围的新增能力——其"复杂度"已由「逐步 TDD + 每步冒烟 + 重写后重跑双侧契约测试」对冲，记录于设计文档 §八风险表，不在此重复。）
