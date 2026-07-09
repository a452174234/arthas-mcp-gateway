# Implementation Plan: K8S 编排能力迭代（Service 复用 / K8S 后端配置 / JDK 适配）

**Branch**: `005-k8s-orchestration-iteration` | **Date**: 2026-07-10 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-k8s-orchestration-iteration/spec.md`；设计来源 [2026-07-10-k8s-orchestration-iteration-design.md](../../docs/superpowers/specs/2026-07-10-k8s-orchestration-iteration-design.md)（brainstorming 定稿）。

## Summary

003 K8S 编排落地后的 3 点生产适配迭代：① ensure 的 NodePort 暴露从「新建独立 Service」改为「复用带 label 标记的业务 Service（patch type+端口），找不到回退新建」；② 后端配置支持 K8S 场景——新增 K8S Host 配置实体（远端 Linux 入口），BackendConfig 引用 host+pod（K8S 模式，与静态 url 二选一），首次路由懒 resolve（BackendResolver 接口，gateway-core 定义 + orchestration 实现）；③ JDK 适配 SPI（ArthasLauncher 策略接口 + DefaultArthasLauncher 默认 + 用户 @Primary 实现定制 javaPath/完整命令模板 + test fixture 真实实现 TDD）。零 gateway-core K8S 依赖不变（ArchUnit 守护），003 既有契约全部不破。

## Technical Context

**Language/Version**: Java 21（LTS，虚拟线程；maven.compiler.release=21，enforcer `[21,22)`）。

**Primary Dependencies**（沿用 001-004，无新增）：
- Spring Boot 4.1.0（parent）+ Spring AI 2.0.0（`spring-ai-starter-mcp-server-webmvc` + `-client`）
- MCP Java SDK 2.0.0（`io.modelcontextprotocol.sdk`，官方）
- fabric8 kubernetes-client 7.6.1 + commons-compress 1.28.0（003 既有，K8S 编排 + upload）
- ArchUnit 1.3.0（包边界守护）
- 测试：JUnit5 + AssertJ（spring-boot-starter-test）+ mcp-test（官方）

**Storage**: 内存态（TaskStore / DynamicBackendStore / OrchestrationRecordStore / K8sBackendResolver 缓存）+ 配置文件（`application.yml` 的 `arthas-gateway.k8s-hosts` + `config/backends.yaml`）。无 DB。

**Testing**: JUnit5 + AssertJ（surefire 单测）+ failsafe `*IT`（真实 k3s 集成）+ ArchUnit（包边界）+ vitest（前端，本特性不动）。**TDD 真实环境零桩**（宪法原则七）：ArthasLauncher SPI 测试含 test fixture 真实实现（非 mock）。

**Target Platform**: JVM 网关进程（集群外运行，:8761/mcp）+ 远端 K8S 集群（k3s，经 kubeconfig + NodePort）。

**Project Type**: web-service（MCP 网关，单 Maven 模块，内嵌 Vue SPA 不动）。

**Performance Goals**: ensure ≤5min（SC-001 不破）；懒 resolve 首次路由额外开销 = 一次 ensure（后续缓存命中 O(1)）；Service patch 单次 K8S API 调用。

**Constraints**:
- **零 gateway-core K8S 依赖**（ArchUnit 守护，FR-014）：`BackendResolver` 接口在 gateway-core `backend` 包定义（无 fabric8 import），实现在 `orchestration` 包。
- **003 既有契约不破**（FR-013）：K-ATOMIC-1 / K-ENS-2 / K-ENS-4~9 / SC-001 全部继续通过。
- **TDD 真实环境**（宪法原则七）：SPI 测试含 test fixture 真实 Java 实现；契约 IT 跑真实 k3s + 真实 pod。
- 静态 url 模式完全兼容（url 与 k8sHost 互斥；老配置无 k8sHost = 静态模式，零迁移）。

**Scale/Scope**: 单网关多 K8S Host（每 host 独立 kubeconfig + provisioner，MVP 不做 client 复用池化）；K8S 模式 backend 数 = 配置声明量（懒 resolve 按需触发）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 检查 | 结果 |
|------|------|------|
| 一、MCP 规范符合性 | 不改 MCP 协议层（ensure/resolve 是网关内部，不涉 JSON-RPC） | ✓ PASS |
| 二、透明无损聚合 | ensure 仍原样透传诊断结果；Service 复用/懒 resolve 不改路由语义 | ✓ PASS |
| 三、连接生命周期与局部故障韧性 | 懒 resolve 失败 → 结构化错误 + 熔断（复用 001）；Service patch 失败 → ensure failed 不注册（K-ATOMIC-1） | ✓ PASS |
| 四、双侧契约优先 | 005 新增契约（K-ENS-10/11 + INV-K8SHOST/LAUNCHER），TDD 测试先于实现 | ✓ PASS |
| 五、可观测性 | ensure 各步日志 + OrchestrationRecord；懒 resolve 缓存命中/未命中可观测；错误结构化 | ✓ PASS |
| 六、Java 主力 | ArthasLauncher SPI 是 Java 接口；Default/Custom/Test 实现皆 Java；无新非 Java 工具链 | ✓ PASS |
| 七、TDD | 测试先于实现（spec-kit SDD）；真实环境零桩（含 SPI test fixture） | ✓ PASS |
| 八、Arthas 先决研究 | 003 R1-R8 已研（fabric8/target-ip/commons-compress）；005 research.md 补迭代决策 | ✓ PASS |

**技术约束**：Java 21 + Maven（可复现）+ 官方 MCP SDK + fabric8（K8S 仅辅助）。**无违反**。

## Project Structure

### Documentation (this feature)

```text
specs/005-k8s-orchestration-iteration/
├── plan.md              # 本文件
├── research.md          # Phase 0（迭代决策 R1-R8）
├── data-model.md        # Phase 1（K8sHost/BackendConfig 增量/BackendResolver/ArthasLauncher/LaunchContext）
├── quickstart.md        # Phase 1（复用 Service / K8S host backend / 自定义 launcher 验证场景）
├── contracts/           # Phase 1（K-ENS-10/11 + INV-K8SHOST-* + INV-LAUNCHER-*）
└── tasks.md             # /speckit-tasks 产出（Phase 2）
```

### Source Code（单 Maven 模块，沿用 001-004 包结构）

```text
src/main/java/com/arthas/gateway/
├── backend/                      # gateway-core（零 K8S 依赖）
│   ├── BackendConfig.java        # 增量：加 k8sHost + pod 字段（K8S 模式，与 url 互斥）
│   ├── BackendConfigLoader.java  # 增量：解析 k8sHost/pod + 互斥校验
│   ├── BackendEntry.java         # 增量：initializeOnce 加懒 resolve hook（resolver 拿 mcpUrl 建 client）
│   └── BackendResolver.java      # 新增接口（懒 resolve；gateway-core 定义，无 fabric8）
├── orchestration/                # K8S 编排（依赖 fabric8）
│   ├── ArthasProvisioner.java    # 改造：委托 ArthasLauncher（删 locateJvm/startArthas 私有方法）
│   ├── NodePortExposer.java      # 改造：label Service 查找 + patch type+端口 + 回退新建
│   ├── ArthasLauncher.java       # 新增 SPI 接口（locatePid + startArthas + LaunchContext）
│   ├── DefaultArthasLauncher.java# 新增默认实现（003 现状逻辑外移）
│   └── K8sBackendResolver.java   # 新增 BackendResolver 实现（调 ensure + 缓存 mcpUrl）
├── config/
│   ├── GatewayProperties.java    # 增量：加 List<K8sHost> k8sHosts + K8sHost 内部类
│   └── K8sOrchestrationConfig.java # 增量：按 host 建独立 provisioner + ArthasLauncher 装配（@ConditionalOnMissingBean）
└── admin/ (004，不动)

src/test/java/com/arthas/gateway/
├── orchestration/
│   ├── DefaultArthasLauncherTest.java      # 默认实现 = 003 现状（单测）
│   ├── TestArthasLauncher.java             # test fixture 真实实现（验委托/替换/契约）
│   ├── ArthasLauncherSpiTest.java          # SPI 委托 + @Primary 覆盖（单测）
│   ├── K8sBackendResolverTest.java         # 懒 resolve + 缓存 + 静态旁路（单测）
│   ├── NodePortExposerTest.java            # label 查找 + patch + 回退（单测）
│   └── K8sEnsureContractIT.java            # 扩展：label Service + K8S host + 自定义 launcher（真实 k3s）
├── backend/
│   ├── BackendConfigLoaderTest.java        # 扩展：k8sHost/pod 解析 + 互斥校验
│   └── BackendEntryLazyResolveTest.java    # 懒 resolve hook（mock resolver）
└── architecture/PackageBoundaryTest.java   # 扩展：BackendResolver 接口零 fabric8 依赖守护
```

**Structure Decision**: 单 Maven 模块（沿用 003/004 R1 包级边界，不拆多模块）。`BackendResolver` 接口刻意放 `backend` 包（gateway-core），`K8sBackendResolver` 实现在 `orchestration` 包——保持 gateway-core 零 K8S 依赖（ArchUnit 规则 1/2 守护 `backend` 不依赖 `io.fabric8`/`orchestration`）。`config`（组合根）装配两者，不在禁止范围。

## Complexity Tracking

> 3 项偏离"最简"的设计，均有 brainstorming 决策依据（用户明确选择），记录正当理由。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| **ArthasLauncher SPI 策略接口**（非纯配置驱动） | 用户明确要 SPI（实现类定制 javaPath + 完整命令模板）；适配真实容器独立 JDK 部署 | 纯配置驱动（yml 配 javaPath + 模板字符串）被否——用户选 SPI（FR-009/011）；模板字符串解析复杂 + 易出错，实现类更灵活（用户原话"完整命令模板"） |
| **BackendResolver 接口**（gateway-core 定义，非 BackendEntry 直调 ensure） | 保持 gateway-core 零 K8S 依赖（FR-014，ArchUnit 守护）；懒 resolve 需跨层（BackendEntry → resolver → orchestration ensure） | BackendEntry 直接调 ArthasProvisioner 被否——会把 fabric8 依赖引入 gateway-core（违反宪法原则六 + ArchUnit）；接口反转解耦 |
| **K8sHost 独立配置实体**（非 BackendConfig 内嵌） | 用户明确要独立实体（管理远端 Linux 服务器清单，多 backend 复用同 host）；职责分离 | BackendConfig 加 k8s 字段被否——用户选独立实体（FR-005）；内嵌导致每 backend 重复 host 信息 + host 与 backend 耦合 |
| **按 host 建独立 provisioner**（非单 client 多 host 路由） | 每 host 有独立 kubeconfig（不同集群凭证），需独立 KubernetesClient | 单 client + host 路由被否——单 client 只能连一个 kubeconfig；MVP 不做 client 复用池化（YAGNI，后置） |

> 注：Service patch（label 查找 + 改 type + 加端口）虽增加 NodePortExposer 复杂度，但属"复用现有 Service"的必要机制（FR-001/002），无更简替代（K8S Service port patch 是标准 API）；回退新建保证兼容（FR-004）。
