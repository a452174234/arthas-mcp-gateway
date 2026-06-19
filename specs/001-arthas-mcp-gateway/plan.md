# Implementation Plan: Arthas MCP 网关

**Branch**: `001-arthas-mcp-gateway` | **Date**: 2026-06-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-arthas-mcp-gateway/spec.md`

**Note**: 本文件由 `/speckit-plan` 命令填写。所有技术决策的**证据与理由**见 [research.md](./research.md)；上游 arthas 行为事实见 `reference/arthas-docs/03-MCP/` 五篇文档。

## Summary

构建一个 Java 实现的 **arthas MCP 网关**：作为标准 MCP 服务端（stdio + Streamable HTTP）向 Claude Code 等 AI 客户端暴露统一的 arthas 诊断能力，同时作为 MCP 客户端连接并管理**多个** arthas MCP 后端（每个对应一个目标 JVM）。

核心机制：向调用方暴露**静态摘抄自 arthas 源码的 31 个诊断工具**（每个注入 `target` 参数选择目标 JVM）+ **4 个网关自有工具**（`list-targets`/`task-get`/`task-list`/`task-cancel`）；调用按 `target` 路由到对应后端，结果原样透传。长任务（watch/trace/stack/tt/monitor）采用**应用层异步任务**（方案 C）：立即返回 taskId，后台阻塞等后端，调用方经 `task-get` 轮询取结果——全程走官方 MCP Java SDK 的标准 `tools/call`，**不手写任何 JSON-RPC/MCP 帧**。

技术栈：Java 21 + Maven + Spring Boot，官方 `io.modelcontextprotocol.sdk:mcp-bom:2.0.0`（双端）+ Spring AI `mcp-spring-webmvc`（HTTP transport）+ Actuator（可观测性）。详见 [research.md](./research.md)。

## Technical Context

**Language/Version**: Java 21（LTS）。宪法约束最低 Java 17 LTS；选用 21（已就绪，且虚拟线程利于后台异步任务）。构建中 enforce 锁定。

**Primary Dependencies**:
- `io.modelcontextprotocol.sdk:mcp-bom:2.0.0`（官方 MCP Java SDK，BOM 统一版本；`mcp` 便利包 + 客户端 `HttpClientStreamableHttpTransport`）
- `org.springframework.ai:spring-ai-bom`（含 `mcp-spring-webmvc`，提供 Streamable HTTP 服务端 transport；WebFlux/WebMVC 传输已自官方 SDK 迁出至 Spring AI 2.0+）
- Spring Boot（生命周期、配置外化、Actuator 健康检查/metrics）
- 测试：JUnit 5 + AssertJ + 官方 `mcp-test` + conformance-tests 子套件 + Testcontainers（真实 arthas + 真实业务服务，**零桩**）；**无 WireMock**

**Storage**: N/A（无持久化）。运行期内存态：后端注册表（不可变快照 + `AtomicReference`）、taskStore（异步任务 taskId→状态/结果，TTL 清理）。配置文件：后端映射表（`config/backends.yaml`，热重载）。

**Testing**: JUnit 5 + AssertJ。**真实环境，零桩**（Testcontainers 拉起真实 arthas + 真实业务服务；故障用真实条件，无 WireMock）。**驱动分层**：工具可用性用真实 Claude Code（冒烟、不做一致性）；结果一致性 + 双侧协议契约用官方 SDK client（确定性，非 curl 裸 HTTP）。TDD 红绿重构（宪法原则七 + 用户 TDD 真实性硬约束）。详见 [research.md §5](./research.md)。

**Target Platform**: 受控内网部署的 JVM 服务（Linux 为主，开发期 Windows 11）。MVP 无认证，依靠网络隔离；认证为演进首要项。

**Project Type**: web-service（长期运行的 MCP 聚合网关，双传输：stdio 本地 + Streamable HTTP 远程）。

**Performance Goals**:
- 单次同步诊断转发开销可忽略（SC-005：与直连一致）。
- 失效目标调用 30s 内返回明确错误（SC-003）。
- 后端配置热重载 30s 内生效（SC-002）。
- 多客户端并发互不干扰（SC-004），per-target 连接/线程隔离。

**Constraints**:
- 跨后端并发不得相互阻塞（异步非阻塞多路复用，宪法原则三）。
- 协议核心不得手写 JSON-RPC/MCP 帧（宪法"协议核心"约束）。
- 不得静默改写/摘要/截断/吞掉结果与错误（宪法原则二、五）。
- CI 必须能复现本地构建。

**Scale/Scope**: 目标 JVM 从少量起步、逐步增长；MVP 以静态配置满足，架构预留向更大规模演进。单网关聚合多后端，工具数固定 35（不随后端数膨胀）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**宪法版本**：v1.2.0（2026-06-19）。逐原则核验：

| # | 原则 | 状态 | 依据 / 落地方式 |
|---|---|---|---|
| 一 | MCP 规范符合性（不可妥协） | ✅ 通过 | 双端均用官方 SDK 实现 JSON-RPC 2.0；锁定协议 `2025-11-25`；initialize→initialized、能力协商、有序关闭由 SDK 保障；协议层只出现 tools 原语（resource/prompt 恒空返回、不声明自定义原语） |
| 二 | 透明无损的聚合 | ✅ 通过 | 31 工具静态摘抄自 arthas 源码（[MCP能力清单](../../../reference/arthas-docs/03-MCP/MCP能力清单.md)）；`target` 注入消除歧义；调用结果原样透传（不篡改/截断/摘要）；映射确定性且可发现（`list-targets` 工具） |
| 三 | 连接生命周期与局部故障韧性 | ✅ 通过 | per-target 一等对象（注册/能力发现/健康监控/退避重连/干净下线）；熔断降级不波及其他后端；per-target 独立连接池 + 异步非阻塞，互不阻塞 |
| 四 | 双侧契约优先的测试 | ✅ 通过 | 双侧契约测试（research §5.4）先于实现编写（TDD），**全真实环境**（真实 arthas + 业务服务，零桩）；覆盖握手/能力协商/路由/命名空间/错误传播/一致性 A/B；任何线格式或路由改动同步更新契约测试 |
| 五 | 可观测性与可诊断性 | ✅ 通过 | 结构化日志（每次路由调用记录 target/tool/结果状态/耗时，可追溯）；MCP 错误码与后端错误显式传播；Actuator 暴露已注册后端与健康状况 |
| 六 | Java 主力（不可妥协） | ✅ 通过 | 核心代码全 Java 21；构建脚本/配置为辅助；无引入非 Java 运行时依赖 |
| 七 | 测试驱动（不可妥协） | ✅ 通过 | 所有功能代码 TDD 红绿重构；调用 `superpowers:test-driven-development` 落地；**TDD 真实性硬约束**：每测试真实 arthas + 真实业务服务（禁桩模拟成功）、可用性走 Claude Code、一致性/契约走 SDK client（详见 CLAUDE.md 工程实践） |
| 八 | Arthas MCP 先决研究 | ✅ 通过 | Phase 0 已完成：`reference/arthas-docs/03-MCP/` 五篇文档为单一事实源；[research.md](./research.md) 整合；差异已显式记录（如 protocol 2025-11-25 对齐、无状态后端通知限制） |

**技术与传输约束**：Java 21 LTS ✅；Maven 锁定可复现 ✅；stdio + Streamable HTTP 双传输、配置驱动 ✅；异步非阻塞多路复用 ✅；后端注册表配置声明（不硬编码）✅；**协议核心优先官方 SDK、不手写帧** ✅。

**质量门禁**：TDD ✅；构建+lint+测试合并前全绿 ✅；CI 复现本地构建 ✅；每项能力有文档、quickstart 端到端演示 ✅；提交小而内聚 ✅。

**结论**：✅ **门禁通过，无原则冲突**。下方 Complexity Tracking 仅记录"有正当理由的例外"（4 个网关自有工具），非宪法违规。

## Project Structure

### Documentation (this feature)

```text
specs/001-arthas-mcp-gateway/
├── plan.md              # 本文件（/speckit-plan 产出）
├── research.md          # Phase 0 产出（/speckit-plan）
├── data-model.md        # Phase 1 产出（/speckit-plan）
├── quickstart.md        # Phase 1 产出（/speckit-plan）
├── contracts/           # Phase 1 产出（/speckit-plan）
│   ├── server-contract.md     # 网关↔Claude Code 服务端契约
│   ├── backend-client-contract.md  # 网关↔arthas 后端客户端契约
│   └── gateway-tools-contract.md   # 4 个网关自有工具契约
└── tasks.md             # Phase 2 产出（/speckit-tasks，本命令不创建）
```

### Source Code (repository root)

```text
arthas-gateway/
├── pom.xml                       # Maven 根 POM：锁 mcp-bom:2.0.0 + spring-ai-bom + spring-boot
├── config/
│   └── backends.yaml             # 后端映射表（逻辑名→地址/认证/超时/并发），热重载源
├── src/main/java/com/arthas/gateway/
│   ├── GatewayApplication.java            # Spring Boot 入口
│   ├── transport/                         # 双传输装配（stdio + Streamable HTTP），配置驱动
│   ├── handler/                           # GatewayMcpHandler：传输无关的协议核心
│   │   ├── InitializeHandler.java         # initialize/能力协商
│   │   ├── ToolsListHandler.java          # tools/list（返回 35 工具静态快照）
│   │   ├── ToolsCallRouter.java           # tools/call 路由（target 剥离 + 分流同步/异步）
│   │   └── GatewayToolHandlers.java       # list-targets / task-* 自有工具处理
│   ├── tool/                              # 静态工具注册表
│   │   ├── StaticToolRegistry.java        # 31 arthas 工具（schema 摘抄 + target 注入）
│   │   ├── ExposedTool.java               # 暴露给调用方的工具模型
│   │   └── resources/arthas-tools.json    # 31 工具 schema（从源码生成，契约测试比对基准）
│   ├── backend/                           # 后端注册表 + 客户端
│   │   ├── BackendRegistry.java           # 不可变快照（AtomicReference）
│   │   ├── BackendEntry.java              # 单后端：client + 熔断器 + Semaphore(5)
│   │   ├── BackendClient.java             # 官方 SDK HttpClientStreamableHttpTransport 封装
│   │   ├── CircuitBreaker.java            # 熔断 + 退避重连
│   │   ├── RegistryHolder.java            # AtomicReference 持有 + 优雅替换
│   │   └── BackendConfigLoader.java       # YAML 解析 + WatchService 热重载
│   ├── task/                              # 应用层异步任务（方案 C）
│   │   ├── TaskStore.java                 # 内存 taskId→状态/结果，TTL 清理
│   │   ├── AsyncTaskExecutor.java         # 后台阻塞等后端路①（虚拟线程）
│   │   └── TaskState.java                 # working/completed/failed/cancelled
│   ├── auth/                              # 后端认证（Bearer/Basic header 注入），MVP 预留
│   └── obs/                               # 结构化日志 + Actuator 端点（健康/后端状态）
└── src/test/java/com/arthas/gateway/
    ├── contract/
    │   ├── server/                        # 服务端契约（官方 SDK client 驱动）
    │   └── client/                        # 客户端契约（WireMock 模拟 arthas）
    ├── integration/                       # 双传输端到端、热重载、并发隔离
    └── unit/                              # 注册表/熔断/路由/任务状态机单测
```

**Structure Decision**: 单 Maven 模块（web-service）。按职责分包：`transport`（双传输装配）/ `handler`（传输无关协议核心）/ `tool`（静态注册表）/ `backend`（注册表+客户端+熔断+热重载）/ `task`（应用层异步任务）/ `obs`（可观测性）。测试镜像分 `contract`（双侧，宪法原则四）/ `integration` / `unit`。静态 schema 与 classpath 资源 JSON 分离，便于生成与契约比对。

## Complexity Tracking

> 本表记录"超出 arthas 规范集的额外能力"，属宪法治理要求的"有正当理由的例外"，**非宪法原则违规**（Constitution Check 已全部通过）。

| 复杂度项 | 为何需要 | 被否决的更简单方案及其否决理由 |
|---|---|---|
| 4 个网关自有工具（list-targets / task-get / task-list / task-cancel，超出 arthas 规范 31 工具集） | ① FR-009 要求"目标集合变化可被调用方发现"——但 target 是动态值（随热重载变化），不能写进静态 schema enum，故需独立入口 `list-targets`；② 长任务（watch/trace/stack/tt/monitor）需异步生命周期，官方 SDK v2.0.0 GA 不支持 tasks 原语（手写 task 帧违反宪法"不手写帧"），故用 `task-get/list/cancel` 在**应用层**模拟异步——全走标准 tools/call，零手写帧 | ① **纯透明同步转发**（不暴露 task）：长任务 >30s 被网关超时截断、占用连接，且用户明确要求异步能力——否决；② **端到端 task 透传**：违反宪法"不手写 JSON-RPC 帧"硬约束 + SDK 无 task API + 与未来官方实现冲突——否决；③ **target 写进 enum**：与热重载（target 动态增减）冲突——否决 |

**演进注记**：当官方 SDK 合入 tasks 原语（PR #755）后，可把 `task/` 后台分支替换为真 task 转发；4 个自有工具可保留或迁移，架构已预留。
