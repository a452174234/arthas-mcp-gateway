# Implementation Plan: K8S 目标 arthas MCP 启动与纳管

**Branch**: `003-k8s-arthas-mcp-launch` | **Date**: 2026-06-22 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-k8s-arthas-mcp-launch/spec.md`
**Design**: [2026-06-22-k8s-arthas-mcp-launch-design.md](../../docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md)（SuperPower 头脑风暴产出，九项核心决策）

> **范围声明**：本特性 spec 覆盖 P1–P4 四块渐进能力；**本计划实施范围 = P1（US1）**——对指定 K8S 目标 pod 幂等拉起 arthas MCP + NodePort 暴露 + 动态纳管 + 经网关诊断。P2（离线构造脚本）/ P3（portal）/ P4（Windows agent）后置，参照 001「集群整体后置」的先例不在本计划任务内。P1 的全部供给机制与 north-star 共享，差异仅在"谁指定目标"（P1 人指定 / north-star LLM 推断）。

## Summary

把"对任意指定 K8S 目标 pod 按需启动诊断 MCP 并纳入现有网关使用"的闭环跑通。架构沿用 [设计](../../docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md)：**模块化单体 + 单 MCP 端点**——在现有网关（001/002）之上新增一组独立的 K8S 编排 MCP 能力（`k8s.list-pods` / `k8s.list-services` / 幂等 `k8s.ensure-arthas-mcp`），与既有 35 工具装配进**同一个** MCP server（Claude 单入口、tools/list 为并集）。`ensure-arthas-mcp` 经 **kubectl exec 进入目标 pod 注入 arthas**（attach 该 pod JVM PID）→ 启动绑 `0.0.0.0` 的 arthas MCP → 建 NodePort Service 暴露 → 内部健康检查 → **程序化动态注册**进 `BackendRegistry`（静态种子 ∪ 动态注册的合并快照）。注册后该 target 经 `gateway-core` 既有路由管线诊断，结果原样透传（宪法原则二落点不受污染）。

**关键新增**（在 001/002 之上）：
- **3 个编排 MCP 工具**（新 `orchestration` 包），handler 自带闭包、**不经 `ToolsCallRouter`**，故 gateway-core 路由零 K8S 感知（设计 §3.1/§3.2 的内聚性纪律）。
- **程序化动态注册层**：`BackendConfig.source`（STATIC/DYNAMIC）+ `DynamicBackendStore` + `RegistryComposer`（静态∪动态合并 → 原子替换），与既有热重载共存（热重载不再误删动态 target）。
- **`OrchestrationRecord`**：结构化追溯每次 ensure 供给（宪法原则五）。
- **fabric8 K8S 客户端**：list/exec/create-service 全走 Java API，不 shell-out 到 `kubectl` 二进制（宪法原则六"K8S 仅辅助"）。

## Technical Context

> 全部技术未知在 [research.md](./research.md)（Phase 0）解决；下表为结论摘要。

**Language/Version**: Java 21 LTS（Spring Boot 4.1.0，复用 001/002 既有构建，`pom.xml` 锁定 `maven.compiler.release=21`）。

**Primary Dependencies**:
- 既有（复用）：Spring AI 2.0.0 MCP（server-webmvc + client starter）、官方 MCP Java SDK 2.0.0、Actuator。
- **新增**：`io.fabric8:kubernetes-client`（K8S list/exec/create-service，见 research.md R2）。
- arthas：仍作静态工具文件 `tools/arthas-boot.jar`（不入 pom），`ensure` 经 fabric8 exec `java -jar` 使用（与 001 夹具设计 §5 一致）。

**Storage**: 无持久化（与 001 一致）。`OrchestrationRecord` + `DynamicBackendStore` 为运行期内存态。

**Testing**: JUnit5 + AssertJ（surefire 纯逻辑 + failsafe `*IT.java` 真实夹具）。波次 A（纯逻辑，无 K8S）/ B（真实 K8S 夹具）/ C（端到端）。**零桩**（CLAUDE.md 真实性硬约束）——成功路径不得用桩模拟 arthas/K8S 成功；故障用真实故障条件。驱动分层：可用性走真实 Claude Code MCP（`claude -p --mcp-config`），一致性/双侧契约走官方 MCP Java SDK client。

**Target Platform**: 网关 JVM（Linux/Windows 均可，开发期 Windows）+ 远程 K8S 集群。**测试集群 = debian-docker 服务器（192.168.31.92）上的 k3s**（research.md R3：本机 Windows 无 Docker，debian 有 Docker 26.1.5 + SSH 免密）。

**Project Type**: 模块化单体 web-service（单 Maven 模块 + 包级边界，见下"Project Structure"与 research.md R1）。

**Performance Goals**: SC-001——选定 pod 后 **5 分钟内**完成"ensure + 暴露 + 纳管"并完成一次诊断；SC-003——pod 重启/驱逐后网关 **30 秒内**标记该 target 不可用（复用 001 健康监控/熔断）。

**Constraints**: 受控内网、无认证（沿用 001 MVP 安全假设）；目标 pod 须含 shell+java+JVM；单目标 JVM/pod（多 JVM PID 选择后置）。

**Scale/Scope**: 单 Linux 服务器、单 K8S 集群起步；多服务器/多集群预留接口不在 MVP。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*（宪法 v1.2.0）

| 原则 | 核验 | 结论 |
|------|------|------|
| **一 MCP 规范符合性** | 3 个新工具是标准 MCP 工具（tools/list + tools/call）；NodePort 暴露的仍是标准 arthas MCP；`app` 装配的 server 遵守 initialize/能力协商/JSON-RPC 2.0。工具集仍静态（35+3=38），`listChanged=false` 继续成立 | ✅ 通过 |
| **二 透明无损聚合** | 动态 target 诊断仍走 `gateway-core` 既有 `ToolsCallRouter`、结果原样透传；定义仍静态摘抄自 arthas；编排工具为独立 MCP 面（设计 §3.2）——**gateway-core 包零 K8S 感知**（编排工具 handler 自带闭包、不经路由器） | ✅ 通过（关键落点见 [contracts/dynamic-registration-invariants.md](./contracts/dynamic-registration-invariants.md)） |
| **三 局部故障韧性** | 动态 target 经 `BackendEntryFactory` 创建 `BackendEntry`（含熔断/限流/健康），与静态 target 同等纳管；pod 消亡 → 该 target 熔断隔离，不影响其他；`ensure` 的"重供给"可恢复纳管 | ✅ 通过（复用 001 故障隔离） |
| **四 双侧契约** | 新增契约：3 个编排工具（gateway↔Claude）+ ensure→诊断路径双侧断言；TDD 红-绿-重构，测试先于实现（原则七） | ✅ 通过（[contracts/k8s-orchestration-tools-contract.md](./contracts/k8s-orchestration-tools-contract.md)） |
| **五 可观测性** | `OrchestrationRecord` 结构化追溯供给（logicalName/server/pod/mcpUrl/status/createdAt）；ensure 全程结构化日志（tool/target/status/duration）；K8S 真实错误（无权限/不可达/无 shell）显式传播，绝不静默成功 | ✅ 通过 |
| **六 Java 主力** | 编排核心、网关、注册层全 Java；K8S 操作全走 **fabric8 Java API**（list/exec/create-service），**不 shell-out `kubectl` 二进制**；K8S 清单/脚本仅辅助（P2 离线脚本才出现） | ✅ 通过 |
| **七 TDD** | 波次 A/B/C 全程真实环境、零桩（[research.md R7](./research.md)、设计 §八） | ✅ 通过 |
| **八 先决研究** | Phase 0 先决研究已产出 [research.md](./research.md)：K8S 轻量发行版（k3s）、arthas-boot.jar 远程绑定（`0.0.0.0`，**R4 已源码证据链 + 本机 A/B 实证双重确认、不再为风险**）、NodePort selector 策略、fabric8 选型、动态注册与热重载并发 | ✅ 通过 |

**门禁结论**：无违反项。设计 §3.2 关于"端点多挂编排工具不破原则二"的论证已落地为本计划的包级边界（gateway-core 零 K8S 感知）。Complexity Tracking 无需填写。

## Project Structure

### Documentation (this feature)

```text
specs/003-k8s-arthas-mcp-launch/
├── plan.md              # 本文件
├── spec.md              # /speckit-specify 产出（已按设计 §10 reconcile）
├── research.md          # Phase 0 先决研究（R1–R8 决策）
├── data-model.md        # 数据模型增量（在 001 data-model 之上）
├── quickstart.md        # 端到端验证指南
├── contracts/
│   ├── k8s-orchestration-tools-contract.md   # 3 个编排工具契约 + 断言点
│   └── dynamic-registration-invariants.md    # 动态注册不变量（source/合并/冲突/热重载共存）
└── tasks.md             # /speckit-tasks 产出（不在本命令范围）
```

### Source Code (repository root)

```text
# 单 Maven 模块（research.md R1：包级边界实现"模块化单体"，Maven 多模块后置 P3）
src/main/java/com/arthas/gateway/
├── GatewayApplication.java
├── auth/  config/  handler/  task/  tool/  obs/   # ===== gateway-core（既有，零改动语义）=====
├── backend/                                          # gateway-core 后端域（增量：BackendConfig.source）
│   ├── BackendConfig.java            # 增字段 source: Source
│   ├── BackendConfigLoader.java      # 解析 source（缺省 STATIC）
│   ├── Source.java                   # 新：enum STATIC | DYNAMIC
│   ├── RegistryHolder.java           # 既有（持有 effective 合并快照）
│   ├── DynamicBackendStore.java      # 新：动态 target 内存态（register/unregister/list）
│   └── RegistryComposer.java         # 新：static∪dynamic 合并 → RegistryHolder.getAndSet 原子替换
├── orchestration/                                    # ===== orchestration（新包，纯 K8S，不碰 arthas 路由）=====
│   ├── K8sToolRegistry.java          # 3 个编排工具的 ExposedTool（routingMode=GATEWAY_LOCAL）
│   ├── K8sToolHandlers.java          # list-pods/list-services/ensure-arthas-mcp 处理器
│   ├── K8sClientFactory.java         # kubeconfig → fabric8 KubernetesClient
│   ├── K8sPodExplorer.java           # list pods/services（fabric8）
│   ├── ArthasProvisioner.java        # ensure 核心：exec 注入 + NodePort 暴露 + 健康检查 + 注册
│   ├── NodePortExposer.java          # label pod + create NodePort Service → 可达 URL
│   ├── OrchestrationRecord.java      # 供给记录实体 + 状态机
│   └── OrchestrationRecordStore.java # 内存态供给记录（原则五可观测）
└── config/
    └── GatewayMcpServerConfig.java   # 增量：mcpToolSpecifications 合并 35 + 3（见 data-model §5）

src/main/resources/
├── application.yml                   # 增 arthas-gateway.k8s.* （kubeconfig/context/namespace/端口范围）
└── arthas-tools.json                 # 不动（31 arthas 工具单一事实源）

src/test/java/com/arthas/gateway/
├── backend/                          # 增 DynamicBackendStoreTest / RegistryComposerTest / SourceParsingTest（波次 A）
├── orchestration/                    # 新：ArthasProvisionerIT / K8sEnsureContractIT / OrchestrationRecordTest（波次 B/C）
└── ...                               # 既有测试不动（回归对照 = 001/002）

config/
└── backends.yaml                     # 增量：静态种子可标注 source: STATIC（缺省即 STATIC，向后兼容）

tools/
└── arthas-boot.jar                   # 既有静态工具文件（ensure 经 fabric8 exec 上传/cp 进 pod 使用）
```

**Structure Decision（research.md R1）**：**单 Maven 模块 + 包级边界**实现设计的"模块化单体"——`orchestration` 包承载全部 K8S 编排逻辑，`backend`/`handler`/`config`/`tool` 等 gateway-core 包**零 K8S 依赖**（依赖方向单向：orchestration → backend 允许，反向禁止）。设计的 Maven 多模块（gateway-core/orchestration/app/portal）作为 P1 的**逻辑命名**沿用，其**物理 Maven 模块拆分后置到 P3**（portal 真正需要按需裁剪 orchestration 时再拆）——理由：宪法"禁止过早抽象"+"小步迭代"，且 P1 不行使"模块裁剪"这一多模块唯一收益。从干净的包边界机械迁移到 Maven 模块在 P3 是低风险的。详见 [research.md §1](./research.md)。

## Complexity Tracking

> 无宪法违反项需正当化。设计文档与宪法一致；唯一与设计字面措辞（"Maven 模块"）的偏离已以包级边界落地，理由为**遵循**宪法"禁止过早抽象/小步迭代"（非违反），记录于 [research.md R1](./research.md) 供用户复核。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| （无） | — | — |
