# Tasks: K8S 目标 arthas MCP 启动与纳管

**Input**: Design documents from `/specs/003-k8s-arthas-mcp-launch/`

**Prerequisites**: plan.md（权威技术栈/结构）、spec.md（用户故事与优先级）、research.md（R1–R8 实施期决策）、data-model.md（实体增量）、contracts/（编排工具契约 + 动态注册不变量）、quickstart.md（端到端验证指南）、`.specify/memory/constitution.md`（宪法 v1.2.0）。

**实施范围声明**：本计划**仅实施 P1 = 用户故事 1**（US1，MVP）。P2（离线构造脚本）/ P3（portal）/ P4（Windows agent）后置，不在本任务清单内（参照 001「集群整体后置」先例）。US1 的供给机制与 north-star 共享，差异仅在"谁指定目标"（P1 人指定 / north-star LLM 推断）。

**TDD 硬约束（不可妥协，宪法原则七 + CLAUDE.md）**：模板"Tests OPTIONAL"在此**被项目宪法推翻**——所有功能代码**必须** TDD：先写失败测试（红）、再实现至通过（绿）、再重构。本清单中**测试任务一律先于其实现任务**出现（测试→实现对成组推进）。**真实性硬约束（零桩）**：成功路径不得用桩模拟 arthas/K8S 成功；故障用真实故障条件。波次 A（纯逻辑 surefire，CI 可跑）/ 波次 B（真实 k3s 夹具 failsafe `*IT.java`，CI 默认 Assume 跳过、本地手跑）/ 波次 C（端到端真实 Claude Code）。

**波次映射**：Phase 2 Foundational = 波次 A（动态注册层纯逻辑地基，阻塞 US1）；Phase 3 US1 = 波次 B + C（K8S 编排层 + 3 工具 + 真实供给 + 端到端）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成任务依赖）
- **[Story]**: 仅 Phase 3（US1）任务带 `[US1]`；Setup/Foundational/Polish 不带
- 每条任务含精确文件路径

## Path Conventions

- 单 Maven 模块，Java 源 `src/main/java/com/arthas/gateway/...`，测试 `src/test/java/com/arthas/gateway/...`
- 配置 `src/main/resources/`（`application.yml`）+ 仓库根 `config/backends.yaml`
- K8S 测试床脚本在仓库根 `reference/k3s/`、`test-env/k8s/`（新增）

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 构建依赖、配置外化与 K8S 真实测试床（波次 B/C 的前置基建）

- [X] T001 [P] Add `io.fabric8:kubernetes-client` dependency to `pom.xml`（锁定与 Spring Boot 4.1.0 BOM 兼容的最新稳定版，显式声明版本保证可复现构建；research.md R2）
- [X] T002 [P] Add `arthas-gateway.k8s.*` config section to `src/main/resources/application.yml`（kubeconfig 路径、context、namespace、NodePort 范围、ensure 超时；默认指向 `test-env/k8s/kubeconfig/k3s-admin.yaml`）
- [X] T003 [P] Add `K8sProperties`（k8s 子段绑定）to `src/main/java/com/arthas/gateway/config/GatewayProperties.java`（kubeconfig/context/namespace/nodePortRange/ensureTimeout 字段）
- [X] T004 [P] Document optional `source: STATIC` field in `config/backends.yaml`（向后兼容：缺省即 STATIC；既有种子零改动可用，data-model §2）
- [X] T005 [P] Create `reference/k3s/fetch.sh`（离线预置 k3s v1.35.5+k3s1 airgap 资源到 `reference/k3s/`；带重试防 github 间歇不可达，memory `github-com-unreachable`；research.md R3）
- [X] T006 [P] Create `test-env/k8s/Dockerfile.demo`（纯 app 镜像：jdk-21 基础镜像 + 001 夹具 `DemoBusinessApp` 编译产物；**arthas 不入镜像**，ensure 时上传；research.md R7）
- [X] T007 [P] Create `test-env/k8s/demo-pod.yaml`（`demo-business` pod 清单：含 shell+java+JVM，供 ensure 经 fabric8 exec 注入）
- [X] T008 Create `test-env/k8s/setup.sh`（幂等一键：`mvn test-compile` 产 demo class → ship debian → 离线装 k3s 瘦身 + tls-san → docker build demo 镜像 → k3s ctr import → apply demo pod → 导出 root-on-node 派生 admin kubeconfig 到 `test-env/k8s/kubeconfig/k3s-admin.yaml`，server 改 `https://192.168.31.92:6443`，文件 600）（依赖 T005/T006/T007）
- [X] T009 Create `test-env/k8s/teardown.sh`（debian 上 `k3s-uninstall` + 删导出凭证；幂等清理）（依赖 T008）
- [X] T010 [P] Verify test-bed bootstrap：运行 `reference/k3s/fetch.sh` + `test-env/k8s/setup.sh`，确认 `demo-business` pod `Running`/`Ready` 且本机 `k3s-admin.yaml` 可 `kubectl get pods`（波次 B/C 前置门禁）

**Checkpoint**: 依赖就位、配置外化完成、K8S 真实测试床可一键幂等起/拆——波次 B/C 测试可运行

---

## Phase 2: Foundational (Blocking Prerequisites — 波次 A 纯逻辑动态注册层)

**Purpose**: 动态注册层正确性地基（`ensure` 注册子行为的阻塞前置；纯逻辑、无 K8S、CI 可跑）。**⚠️ CRITICAL**：US1 的 ensure 供给依赖此层（register 进 `DynamicBackendStore` + `RegistryComposer` 合并），须先全绿。

> **TDD（宪法原则七）**：下列测试任务先写并确认失败（红），再实现至通过（绿）。断言 ID 见 `contracts/dynamic-registration-invariants.md §5`。

- [X] T011 [P] Write failing `SourceParsingTest` in `src/test/java/com/arthas/gateway/backend/SourceParsingTest.java`（断言 D-SOURCE-1：YAML 缺省 `source` → STATIC、显式 `source: STATIC` → STATIC、动态注册路径强制 DYNAMIC）
- [X] T012 Implement `Source` enum + `BackendConfig.source` 字段 + `BackendConfigLoader` source 解析 in `src/main/java/com/arthas/gateway/backend/Source.java`、`src/main/java/com/arthas/gateway/backend/BackendConfig.java`、`src/main/java/com/arthas/gateway/backend/BackendConfigLoader.java`（紧凑构造器缺省 STATIC，向后兼容；green for T011）
- [X] T013 [P] Write failing `OrchestrationRecordTest` in `src/test/java/com/arthas/gateway/orchestration/OrchestrationRecordTest.java`（断言 data-model §9 状态机：`ensuring→ready/reused/failed` 转换、`failed` 不注册原子性、`createdAt` 传入非进程取时）
- [X] T014 Implement `OrchestrationRecord` + `OrchestrationRecordStore` in `src/main/java/com/arthas/gateway/orchestration/OrchestrationRecord.java`、`src/main/java/com/arthas/gateway/orchestration/OrchestrationRecordStore.java`（内存态 `ConcurrentHashMap<logicalName, record>`；green for T013）
- [X] T015 Write failing `DynamicBackendStoreTest` in `src/test/java/com/arthas/gateway/backend/DynamicBackendStoreTest.java`（断言 D-REG-1..4、D-UNREG-1..3：register/unregister、与静态种子同名拒绝、同名同 URL 幂等、同名异 URL 拒绝、仅 DYNAMIC 可移、不存在幂等）（依赖 T012）
- [X] T016 Implement `DynamicBackendStore` in `src/main/java/com/arthas/gateway/backend/DynamicBackendStore.java`（`ConcurrentHashMap`、强制 source=DYNAMIC、冲突检测 I-3、变更触发 `RegistryComposer.compose()`；green for T015）（依赖 T012）
- [X] T017 Write failing `RegistryComposerTest` in `src/test/java/com/arthas/gateway/backend/RegistryComposerTest.java`（断言 D-COEXIST-1/2、D-ATOMIC-1、D-VERSION-1 + 不变量 I-1..I-7：热重载不误删动态 target、原子替换 in-flight 不串台、version 去重）（依赖 T016）
- [X] T018 Implement `RegistryComposer` + adjust `BackendRegistryReloader` to compose(static∪dynamic) before `RegistryHolder.getAndSet` in `src/main/java/com/arthas/gateway/backend/RegistryComposer.java`、`src/main/java/com/arthas/gateway/backend/BackendRegistryReloader.java`（复用 reloader diff/复用逻辑，仅 swap 前合并 dynamic；既有 `BackendRegistryReloaderTest`/`HotReloadIT` 须继续通过；green for T017）（依赖 T016）

**Checkpoint**: 动态注册层全绿（`mvn -pl . test -Dtest='DynamicBackendStoreTest,RegistryComposerTest,SourceParsingTest,OrchestrationRecordTest'`），US1 编排层可在此之上构建

---

## Phase 3: User Story 1 - 对指定 K8S 目标启动 arthas MCP + service 暴露 + 网关纳管 + 经网关诊断 (Priority: P1) 🎯 MVP

**Goal**: 人提供 Linux 服务器名 + kubeconfig → Claude 经 3 个编排 MCP 工具（`k8s.list-pods`/`k8s.list-services`/幂等 `k8s.ensure-arthas-mcp`）+ 既有诊断工具，编排"枚举→供给→暴露→纳管→诊断"全链路；结果明确来自所选 pod 的 JVM（SC-001）。

**Independent Test**: 在 debian k3s 集群选一个运行 JVM 的 `demo-business` pod，触发 ensure → 网关 `list-targets` 新增该 target（source=DYNAMIC）→ 经网关 watch/trace 该 target 返回该 pod JVM 真实诊断（SC-001，5 分钟内）。

> **TDD（宪法原则七）+ 真实性硬约束（零桩）**：波次 B 契约 IT 先写（红，引用尚未存在的行为），再实现至通过（绿）。IT 由官方 MCP Java SDK client 驱动（确定性断言），走真实 k3s + 真实业务 pod + 真实 arthas 注入（**零桩**），CI 默认 Assume 跳过、本地手跑（quickstart §2.3）。断言 ID 见 `contracts/k8s-orchestration-tools-contract.md §5`。

### 波次 B：K8S 编排层 + 3 工具（真实 k3s 夹具）

- [X] T019 [US1] Implement `K8sClientFactory` in `src/main/java/com/arthas/gateway/orchestration/K8sClientFactory.java`（kubeconfig → fabric8 `KubernetesClient`；enabling plumbing，由下游 IT 覆盖）
- [X] T020 [P] [US1] Write failing `K8sListToolsContractIT` in `src/test/java/com/arthas/gateway/orchestration/K8sListToolsContractIT.java`（断言 K-LP-1、K-LS-1：真实集群 pod/service 清单、namespace 过滤、`hasJvm`/`hasShell` 标记）
- [X] T021 [US1] Implement `K8sPodExplorer` in `src/main/java/com/arthas/gateway/orchestration/K8sPodExplorer.java`（fabric8 list pods/services、探测 hasJvm/hasShell）（依赖 T019）
- [X] T022 [US1] Implement `K8sToolHandlers`（listPods/listServices 完整 + ensureArthasMcp 占位待接 Provisioner）+ `K8sToolRegistry`（3 个 `ExposedTool`，`routingMode=GATEWAY_LOCAL`，handler 自带闭包不经路由器）+ merge into `GatewayMcpServerConfig#mcpToolSpecifications`（35+3=**38** specs）in `src/main/java/com/arthas/gateway/orchestration/K8sToolHandlers.java`、`src/main/java/com/arthas/gateway/orchestration/K8sToolRegistry.java`、`src/main/java/com/arthas/gateway/config/GatewayMcpServerConfig.java`（green for list tools K-LP-1/K-LS-1；`listChanged=false` 不变）（依赖 T021）
- [X] T023 [P] [US1] Write failing `ArthasProvisionerIT` in `src/test/java/com/arthas/gateway/orchestration/ArthasProvisionerIT.java`（断言 K-ENS-1/2/4..9、K-ATOMIC-1：对真实 demo pod ensure→ready、重复→reused、无 JVM/无 shell/绑 loopback 等真实故障→结构化错误且未注册、命名派生 `{server}-{pod}`）
- [X] T024 [US1] Implement `NodePortExposer` in `src/main/java/com/arthas/gateway/orchestration/NodePortExposer.java`（label pod `arthas-mcp-gateway/target=<logicalName>` + create NodePort Service selector 命中 → 可达 `mcpUrl=http://<nodeIP>:<nodePort>`，根 URL 无 `/mcp`；research.md R5）（依赖 T019）
- [X] T025 [US1] Implement `ArthasProvisioner` in `src/main/java/com/arthas/gateway/orchestration/ArthasProvisioner.java`（ensure 核心：fabric8 exec 定位 JVM PID → 上传/获取 `arthas-boot.jar` → exec 启动 `--attach-only --http-port --target-ip 0.0.0.0 --use-version 4.3.0` → NodePort 暴露 → 内部健康检查（轮询 initialize/listTools）→ `DynamicBackendStore.register` + composer swap；任一子步失败→failed 不注册；`OrchestrationRecord` 全程记录；green for T023）（依赖 T024、Phase 2）
- [X] T026 [US1] Wire `K8sToolHandlers.ensureArthasMcp` → `ArthasProvisioner`（完成 ensure handler：派生 `{server}-{pod}`、查注册表幂等复用、调用 Provisioner、按契约映射 `reason`/`stage` 结构化错误）in `src/main/java/com/arthas/gateway/orchestration/K8sToolHandlers.java`（依赖 T025）
- [X] T027 [P] [US1] Write failing `K8sEnsureContractIT` in `src/test/java/com/arthas/gateway/orchestration/K8sEnsureContractIT.java`（断言 K-ENS-3、K-COEXIST-1/2 + SC-003：经网关 MCP 端点 ensure→用返回 target 调 watch/trace 捕获该 pod JVM 真实诊断、热重载不误删动态 target、pod 删除→30 秒内隔离且明确错误不影响其他 target；官方 SDK client 驱动）

**Checkpoint (波次 B)**: 真实 k3s 上 `mvn -pl . verify -Dit.test='K8sListToolsContractIT,ArthasProvisionerIT,K8sEnsureContractIT'` 全绿（含 K-ENS-7 回归守护：`0.0.0.0` 经 NodePort 可达 / loopback 不可达，research.md R4）

### 波次 C：端到端（真实 Claude Code 编排）

- [X] T028 [US1] Wave C 端到端验证：真实 Claude Code（`claude -p --mcp-config .mcp.json`）编排 `k8s.list-pods`→选含 JVM 的 demo pod→`k8s.ensure-arthas-mcp`→用返回 target 调 `watch`，确认结果来自该 pod JVM 且全程 5 分钟内（SC-001；可用性冒烟走真实 CC，仅验"能调通"）

**Checkpoint (波次 C)**: SC-001 闭环跑通——"启动 + 暴露 + 纳管 + 使用"经真实 Claude Code 编排完成，结果归属正确

---

## Phase 4: Polish & Cross-Cutting Concerns

**Purpose**: 跨切面加固、回归守护与文档收尾（不破 001/002）

- [X] T029 [P] Regression guard：确认 `tools/list` = **38**（35 既有 + 3 编排）、`listChanged=false`；既有 35 工具行为 + 双侧契约（`InitializeAndToolsListContractTest`/`GatewayToolsContractTest`/`ToolsCallRoutingContractIT`）+ `HotReloadIT` + 异步任务全绿（`mvn -pl . verify`，quickstart §5）
- [X] T030 [P] Add optional ArchUnit boundary test in `src/test/java/com/arthas/gateway/architecture/PackageBoundaryTest.java`（断言 `com.arthas.gateway.backend..`/`handler..`/`config..` 不依赖 `com.arthas.gateway.orchestration..`——gateway-core 零 K8S 感知，research.md R1/R6；test-scope 轻量依赖）
- [X] T031 [P] Verify gateway-core 零 K8S 感知：确认 `ToolsCallRouter`/`GatewayToolHandlers` 无 `k8s.*` 分支、编排工具 handler 闭包不经路由器（research.md R6 内聚性纪律）
- [X] T032 Run `quickstart.md` validation：场景 A（波次 A CI）+ B（真实 k3s）+ C（端到端）+ SC-003（pod 删除故障韧性）+ 回归（§6 Done Definition 逐项核对）
- [X] T033 [P] Update docs：README/quickstart 增 38 工具说明、`arthas-gateway.k8s.*` 配置、`test-env/k8s/` 测试床用法（宪法"每项新能力必须有文档"）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即开始；T008/T009 依赖 T005/T006/T007
- **Foundational (Phase 2 = 波次 A)**: 依赖 Setup 的 fabric8 依赖（T001 仅供 Phase 3，本层实际不依赖 K8S）；**阻塞 US1**——`ensure` 注册子行为依赖此层
- **User Story 1 (Phase 3 = 波次 B + C)**: 依赖 Foundational 全绿 + Setup 测试床（T010）就绪
- **Polish (Phase 4)**: 依赖 US1 完成

### Within Foundational (波次 A)

1. T011/T013 可并行（独立）→ T012/T014 各自 green
2. T015（DynamicBackendStoreTest）依赖 T012（source）→ T016 green
3. T017（RegistryComposerTest）依赖 T016 → T018 green（既有 reloader 测试须回归通过）

### Within US1 (波次 B + C)

1. T019（K8sClientFactory）为所有 fabric8 操作的前置
2. 列举链：T020（IT 红）→ T021（PodExplorer）→ T022（Handlers/Registry/Config 合并 38）→ 列举工具 green
3. 供给链：T023（IT 红）→ T024（NodePortExposer）→ T025（ArthasProvisioner）→ T026（接 ensure handler）→ 供给 green
4. 端到端契约：T027（K8sEnsureContractIT 红）在 T026 后 green
5. 波次 C：T028 端到端验证在全部 green 后执行

### Parallel Opportunities

- Setup：T001–T007、T010 彼此独立（不同文件）可并行；T008/T009 串行
- Foundational：T011 ∥ T013（独立测试/实体）；T012 ∥ T014 可并行实现
- US1：T020 ∥ T023 ∥ T027 三个 IT 可并行先写（红）；T024 ∥ T021 在 T019 后可并行
- Polish：T029–T033 彼此独立可并行

---

## Parallel Example: US1 波次 B 测试先行

```bash
# 并行先写三个失败契约 IT（红）：
Task T020: "K8sListToolsContractIT in src/test/.../orchestration/K8sListToolsContractIT.java"
Task T023: "ArthasProvisionerIT in src/test/.../orchestration/ArthasProvisionerIT.java"
Task T027: "K8sEnsureContractIT in src/test/.../orchestration/K8sEnsureContractIT.java"

# 再按依赖链实现至 green（T019→T021→T022 / T024→T025→T026）
```

## Parallel Example: Foundational 波次 A

```bash
# 并行实现两组独立实体（green）：
Task T012: "Source + BackendConfig.source + BackendConfigLoader"
Task T014: "OrchestrationRecord + OrchestrationRecordStore"
```

---

## Implementation Strategy

### MVP First（仅 US1）

1. Phase 1 Setup（依赖 + 配置 + 测试床）
2. Phase 2 Foundational（波次 A 动态注册层——**CRITICAL，阻塞 US1**）
3. Phase 3 US1（波次 B 编排层 + 3 工具 → 波次 C 端到端）
4. **STOP and VALIDATE**：US1 独立测试（SC-001 闭环 + 回归 001/002）
5. Phase 4 Polish（回归守护 + 边界 + 文档）

### Incremental Delivery（波次递进）

1. Setup + Foundational → 动态注册地基 ready（波次 A CI 全绿）
2. US1 波次 B → 真实 k3s 供给契约全绿（K-ENS-* / K-COEXIST-*）
3. US1 波次 C → 端到端 SC-001 闭环跑通
4. Polish → 38 工具 + 回归不破 + 文档收尾
5. 每个波次增价值且不破坏前一波次（回归对照 = 001/002）

---

## Notes

- **TDD 硬约束**：所有功能代码测试先于实现（红→绿→重构），测试任务一律成对先出现
- **真实性硬约束（零桩）**：成功路径真实 arthas/K8S；故障用真实故障条件（停 pod/无 shell/无 JVM/绑 loopback/无 exec 权限）；CI 对波次 B/C `Assume` 跳过、本地手跑
- **gateway-core 零 K8S 感知**：编排工具 handler 自带闭包、不经 `ToolsCallRouter`（research.md R6）；ArchUnit 守护（T030）
- **P2/P3/P4 不在本清单**：离线构造脚本/portal/Windows agent 后置，参照 001「集群整体后置」先例
- 每个任务或逻辑组完成后提交；任一 checkpoint 可停下独立验证；同一问题连续失败 3 次暂停重评（CLAUDE.md）
