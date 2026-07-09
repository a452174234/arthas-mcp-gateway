# Tasks: K8S 编排能力迭代（Service 复用 / K8S 后端配置 / JDK 适配）

**Input**: Design documents from `/specs/005-k8s-orchestration-iteration/`（spec.md / plan.md / research.md R1–R9 / data-model.md / contracts/orchestration-iteration-invariants.md / quickstart.md）+ `.specify/memory/constitution.md`（v1.2.0）。

**实施范围声明**：本计划实施 005 = US1（Service 复用，P1）+ US2（K8S Host + 懒 resolve，P1）+ US3（JDK SPI 适配，P2）。复用 003 既有编排核心（orchestration 包、ensure 原子幂等、动态注册）；零 gateway-core K8S 依赖不变（ArchUnit 守护）；003 既有契约全部不破（回归门禁）。

**TDD 硬约束（不可妥协，宪法原则七 + CLAUDE.md）**：后端所有功能代码必须 TDD——先写失败测试（红）、再实现至通过（绿）、再重构。本清单中**测试任务一律先于其实现任务**。**真实性硬约束（零桩）**：契约 IT 跑真实 k3s + 真实 pod；ArthasLauncher SPI 测试含 test fixture 真实实现（非 mock）；故障用真实故障条件。

**波次映射**：Phase 2 = 共享基建（ArthasLauncher SPI + BackendResolver 接口，阻塞 US1/US2/US3）；Phase 3 = US1（Service 复用）；Phase 4 = US2（K8S Host + 懒 resolve）；Phase 5 = US3（JDK SPI 实战适配）；Phase 6 = Polish（回归 + ArchUnit + 文档）。

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: 可并行（不同文件、无未完成任务依赖）
- **[Story]**: 仅 Phase 3/4/5 任务带 `[US1]`/`[US2]`/`[US3]`；Setup/Foundational/Polish 不带
- 每条任务含精确文件路径

## Path Conventions

- 单 Maven 模块，后端 Java 源 `src/main/java/com/arthas/gateway/...`，后端测试 `src/test/java/com/arthas/gateway/...`
- 配置 `src/main/resources/application.yml` + `config/backends.yaml`

---

## Phase 1: Setup（K8sHost 配置基建）

**Purpose**：GatewayProperties 加 K8sHost 列表 + application.yml 示例。

- [ ] T001 [P] Add `K8sHost` 内部类（name + kubeconfig + namespace）+ `List<K8sHost> k8sHosts` 字段 to `src/main/java/com/arthas/gateway/config/GatewayProperties.java`（K8sHost 字段校验：name 非空、kubeconfig 非空；R4）
- [ ] T002 [P] Add `arthas-gateway.k8s-hosts` 配置段（注释说明：远端 Linux K8S 入口，重启生效）to `src/main/resources/application.yml`

**Checkpoint**: K8sHost 配置可绑定（启动期加载 List<K8sHost>）。

---

## Phase 2: Foundational（SPI 接口 + 默认实现 + BackendResolver 接口，阻塞 US1/US2/US3）

**Purpose**：抽取 ArthasLauncher SPI + DefaultArthasLauncher（003 现状外移）+ BackendResolver 接口（gateway-core）。**⚠️ CRITICAL**：US1/US2/US3 均依赖此层。

> **TDD（宪法原则七）**：测试先写并确认失败（红），再实现至通过（绿）。

- [ ] T003 [P] Write failing `ArthasLauncherTest` in `src/test/java/com/arthas/gateway/orchestration/ArthasLauncherTest.java`（LaunchContext record 字段全集 + LaunchException 携带 OrchestrationRecord.Error）
- [ ] T004 Implement `ArthasLauncher` SPI 接口 + `LaunchContext` record + `LaunchException` in `src/main/java/com/arthas/gateway/orchestration/ArthasLauncher.java`（locatePid + startArthas 两方法；green for T003）
- [ ] T005 [P] Write failing `DefaultArthasLauncherTest` in `src/test/java/com/arthas/gateway/orchestration/DefaultArthasLauncherTest.java`（locatePid = `jps -q | head -1`、startArthas = `java -jar arthas-boot.jar <pid> --attach-only --http-port/--target-ip/--use-version/--password`；无 PID → LaunchException no_jvm@locate_jvm；非零退出 → attach_failed@start_arthas；INV-LAUNCHER-2 兼容现状）
- [ ] T006 Implement `DefaultArthasLauncher` in `src/main/java/com/arthas/gateway/orchestration/DefaultArthasLauncher.java`（003 既有 `ArthasProvisioner.locateJvm` + `startArthas` 逻辑外移；green for T005）
- [ ] T007 [P] Write failing `BackendResolverTest` in `src/test/java/com/arthas/gateway/backend/BackendResolverTest.java`（接口契约：K8S 模式 config → Optional.of(mcpUrl)；静态模式 → Optional.empty()；实现用 stub 验证接口行为）
- [ ] T008 Implement `BackendResolver` 接口 in `src/main/java/com/arthas/gateway/backend/BackendResolver.java`（`Optional<String> resolveMcpUrl(BackendConfig)`；gateway-core 定义，无 fabric8 import；green for T007）

**Checkpoint**: SPI 接口 + DefaultArthasLauncher（= 003 现状）+ BackendResolver 接口就位。US1/US2/US3 可在此之上构建。

---

## Phase 3: User Story 1 - Service 复用（Priority: P1）🎯 MVP

**Goal**：NodePortExposer 优先复用带 `arthas-mcp-gateway/target` label 的现有 Service（patch type+端口），找不到回退新建（兼容 003）。

**Independent Test**: 业务 Service 打 label → ensure 复用（不新建独立 Service）；无 label → 回退新建。

> **TDD + 真实零桩**：契约 IT 用真实 k3s（业务 Service 预打 label）。

### 测试先于实现

- [ ] T009 [P] [US1] Write failing `NodePortExposerTest` in `src/test/java/com/arthas/gateway/orchestration/NodePortExposerTest.java`（mock KubernetesClient：① labelSelector 查带 label 的 Service；② 命中 NodePort → patch 加端口不新建；③ 命中 ClusterIP → patch type=NodePort + 加端口（K-ENS-11）；④ 已含同 targetPort → 复用 nodePort 幂等（K-ENS-12）；⑤ 找不到 → 回退新建独立 Service（K-ENS-10 回退））
- [ ] T010 [P] [US1] Write failing `NodePortExposerContractIT` in `src/test/java/com/arthas/gateway/orchestration/NodePortExposerContractIT.java`（failsafe *IT，真实 k3s：① 业务 Service 预打 `arthas-mcp-gateway/target=<logical>` label → ensure 复用 patch（K-ENS-10）；② ClusterIP 业务 Service → 自动改 NodePort（K-ENS-11）；③ 重复 ensure → ports 不变（K-ENS-12）；④ 无 label → 回退新建）

### 实现

- [ ] T011 [US1] Implement NodePortExposer.expose 改造 in `src/main/java/com/arthas/gateway/orchestration/NodePortExposer.java`（新增 `findLabeledService(ns, labelValue)` labelSelector 查 + `patchServiceAddNodePort(svc, mcpPort)` patch type+端口 + 命中复用/未命中回退 `ensureNodePortService`（003 现状）；green for T009/T010）

**Checkpoint (US1)**: 业务 Service 打 label → ensure 复用 patch；无 label → 回退新建（兼容）。

---

## Phase 4: User Story 2 - K8S Host + BackendConfig K8S 模式 + 懒 resolve（Priority: P1）

**Goal**：BackendConfig 加 K8S 模式（k8sHost+pod，与 url 互斥）；首次路由懒 resolve（BackendResolver → K8sBackendResolver → ensure + 缓存 mcpUrl）。

**Independent Test**: 配 K8S Host + K8S 模式 backend → 首次诊断自动 ensure + 缓存；静态模式旁路不变。

> **TDD + 真实零桩**：契约 IT 真实 k3s + 真实 pod；懒 resolve 缓存用真实 ensure。

### 测试先于实现

- [ ] T012 [P] [US2] Write failing `BackendConfigLoaderTest` 扩展 in `src/test/java/com/arthas/gateway/backend/BackendConfigLoaderTest.java`（解析 k8sHost/pod 字段；url 与 k8sHost 互斥：皆有/皆空 → 校验失败保留旧表（INV-K8SHOST-1）；k8sHost 非空时 pod 必填）
- [ ] T013 [P] [US2] Write failing `K8sBackendResolverTest` in `src/test/java/com/arthas/gateway/orchestration/K8sBackendResolverTest.java`（mock provisioner：① K8S 模式 → 调 ensure 返 mcpUrl；② 同 logicalName 二次 → 缓存命中不重复 ensure（INV-K8SHOST-2）；③ 静态模式 → Optional.empty() 旁路（INV-K8SHOST-5）；④ host 不存在 → unknown_k8s_host（INV-K8SHOST-3））
- [ ] T014 [P] [US2] Write failing `BackendEntryLazyResolveTest` in `src/test/java/com/arthas/gateway/backend/BackendEntryLazyResolveTest.java`（mock resolver：K8S 模式 → initializeOnce 用 resolveMcpUrl 建 HttpBackendClient（覆盖 config.url）；静态模式/无 resolver → 用 config.url；no_k8s_resolver 场景（INV-K8SHOST-4））
- [ ] T015 [P] [US2] Write failing `K8sBackendResolverContractIT` in `src/test/java/com/arthas/gateway/orchestration/K8sBackendResolverContractIT.java`（failsafe *IT，真实 k3s + application.yml 配 K8S Host + backends.yaml 配 K8S 模式 backend：① 首次诊断 → ensure + 纳管 + 诊断成功（mcpUrl 来自 ensure）；② 二次 → 缓存；③ 静态 backend 旁路；④ 无 K8S 配置 → no_k8s_resolver）

### 实现

- [ ] T016 [US2] Implement `BackendConfig` 加 `k8sHost` + `pod` 字段 + 互斥校验 in `src/main/java/com/arthas/gateway/backend/BackendConfig.java`（紧凑构造器：url 与 k8sHost 互斥；k8sHost 非空 pod 必填；equals/hashCode 纳入 k8sHost/pod；green for T012）
- [ ] T017 [US2] Implement `BackendConfigLoader` 解析 k8sHost/pod in `src/main/java/com/arthas/gateway/backend/BackendConfigLoader.java`（toBackendConfig 加 k8sHost/pod；green for T012）
- [ ] T018 [US2] Implement `K8sBackendResolver` in `src/main/java/com/arthas/gateway/orchestration/K8sBackendResolver.java`（implements BackendResolver；Map<hostName, ArthasProvisioner> + Map<hostName, K8sHost> + ConcurrentHashMap 缓存；resolveMcpUrl 路由 host → ensure → 缓存；green for T013）
- [ ] T019 [US2] Implement `BackendEntry.initializeOnce` 懒 resolve hook in `src/main/java/com/arthas/gateway/backend/BackendEntry.java`（注入 Optional<BackendResolver>；首次握手时 resolveMcpUrl 拿 mcpUrl 覆盖 config.url 建 HttpBackendClient；green for T014）
- [ ] T020 [US2] Implement `BackendEntryFactory` 注入 `Optional<BackendResolver>` in `src/main/java/com/arthas/gateway/backend/BackendEntryFactory.java`（传给 BackendEntry 构造）
- [ ] T021 [US2] Implement `K8sOrchestrationConfig` 装配 K8sBackendResolver in `src/main/java/com/arthas/gateway/config/K8sOrchestrationConfig.java`（按 k8s-hosts 配置建 Map<host, KubernetesClient + ArthasProvisioner + NodePortExposer>；@Bean BackendResolver = K8sBackendResolver（@ConditionalOnMissingBean + K8S 启用时）；green for T015）

**Checkpoint (US2)**: K8S 模式 backend 首次路由自动 ensure + 缓存；静态模式旁路；零 gateway-core K8S 依赖。

---

## Phase 5: User Story 3 - JDK SPI 实战适配（Priority: P2）

**Goal**：ArthasProvisioner 委托 ArthasLauncher（删硬编码）；@Primary 自定义实现覆盖 Default；test fixture 真实实现验证 SPI。

**Independent Test**: 写 @Primary CustomArthasLauncher（指定独立 JDK）→ ensure 用之；Default 兼容现状。

> **TDD + 真实零桩**：test fixture 真实实现（TestArthasLauncher，非 mock）验证委托/替换/契约。

### 测试先于实现

- [ ] T022 [P] [US3] Implement `TestArthasLauncher` test fixture（真实实现，非 mock）in `src/test/java/com/arthas/gateway/orchestration/TestArthasLauncher.java`（locatePid 固定返 pid 12345；startArthas 记录 LaunchContext + pid 到静态/实例探针字段；`@Primary @Component`（测试装配覆盖 Default）；用于 ArthasLauncherSpiTest 验证委托）
- [ ] T023 [P] [US3] Write failing `ArthasLauncherSpiTest` in `src/test/java/com/arthas/gateway/orchestration/ArthasLauncherSpiTest.java`（① ArthasProvisioner.doProvision 真调 launcher.locatePid + startArthas（非硬编码，INV-LAUNCHER-1）；② TestArthasLauncher（@Primary）覆盖 Default（INV-LAUNCHER-3）；③ locatePid 返 12345 + startArthas 收到正确 LaunchContext（契约）；④ TestArthasLauncher 抛 LaunchException → 映射 failed@start_arthas（INV-LAUNCHER-4））
- [ ] T024 [P] [US3] Write failing `CustomLauncherContractIT` in `src/test/java/com/arthas/gateway/orchestration/CustomLauncherContractIT.java`（failsafe *IT，真实 k3s + 测试用 CustomArthasLauncher（指定 pod 内真实 JDK 路径，或 PATH java 验证覆盖链路）：ensure 用 Custom 启动 arthas；Default 兼容（去 Custom 后 = 003 现状））

### 实现

- [ ] T025 [US3] Refactor `ArthasProvisioner` 委托 ArthasLauncher in `src/main/java/com/arthas/gateway/orchestration/ArthasProvisioner.java`（注入 ArthasLauncher；删私有 locateJvm/startArthas；doProvision 改调 `launcher.locatePid(ctx)` + `launcher.startArthas(ctx, pid)`；LaunchException → ProvisionException 映射 failed@locate_jvm/start_arthas；green for T023/T024）
- [ ] T026 [US3] Implement `K8sOrchestrationConfig` 装配 `DefaultArthasLauncher`（@ConditionalOnMissingBean(ArthasLauncher.class)）+ ArthasProvisioner 注入 ArthasLauncher in `src/main/java/com/arthas/gateway/config/K8sOrchestrationConfig.java`（用户 @Primary @Component 自动覆盖；INV-LAUNCHER-3）

**Checkpoint (US3)**: ArthasProvisioner 委托 launcher；@Primary 自定义覆盖 Default；test fixture 验证 SPI 机制。

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**：包边界守护、003 回归、文档。

- [ ] T027 [P] Write failing `PackageBoundaryTest` 扩展 in `src/test/java/com/arthas/gateway/architecture/PackageBoundaryTest.java`（① `backend.BackendResolver` 接口零 fabric8/kubernetes/orchestration 依赖（INV-BOUNDARY-1）；② `backend` 包不依赖 `orchestration`（既有规则强化）；③ K8sBackendResolver 在 orchestration（INV-BOUNDARY-2））
- [ ] T028 [P] Regression guard：`./mvnw verify -DskipFrontend=true` 全绿——003 既有契约（K8sEnsureContractIT/K8sListToolsContractIT/ArthasProvisionerIT/K8sExternalGatewaySmokeTest）+ ArchUnit + 001/002/004 既有全绿（FR-013 回归门禁）
- [ ] T029 [P] Update 003 contracts：加 K-ENS-10/11/12（Service 复用行为）to `specs/003-k8s-arthas-mcp-launch/contracts/k8s-orchestration-tools-contract.md`（指向 005 实现说明）
- [ ] T030 [P] Update docs：README 补 005 能力（Service 复用 / K8S host 配置 / JDK SPI）+ docs/handbook/part4-k8s.md + part9-decisions.md 补 005 决策（宪法"每项新能力必须有文档"）
- [ ] T031 Run `quickstart.md` validation：场景 A（Service 复用）+ B（K8S host 懒 resolve）+ C（自定义 launcher）+ D（回归）+ SPI test fixture，逐项核对 Done Definition

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖；T001 ∥ T002（独立）
- **Foundational (Phase 2)**: 无依赖（独立于 Phase 1）；**阻塞 US1/US2/US3**
- **US1 (Phase 3)**: 依赖 Foundational（NodePortExposer 独立改造，不依赖 BackendResolver/ArthasLauncher）
- **US2 (Phase 4)**: 依赖 Foundational（BackendResolver 接口）+ Setup（K8sHost 配置）；与 US1 独立
- **US3 (Phase 5)**: 依赖 Foundational（ArthasLauncher SPI + DefaultArthasLauncher）；与 US1/US2 独立
- **Polish (Phase 6)**: 依赖 US1+US2+US3 完成

### Within US1（测试先行 → 实现）

1. 测试：T009（单测）+ T010（契约 IT）→ 实现 T011（NodePortExposer 改造）

### Within US2（测试先行 → 实现）

1. 测试：T012（Config）+ T013（Resolver）+ T014（BackendEntry）+ T015（契约 IT）→ 实现 T016-T021（Config → Resolver → BackendEntry → Factory → 装配）

### Within US3（测试先行 → 实现）

1. 测试 fixture：T022（TestArthasLauncher）→ 测试 T023（SPI 单测）+ T024（契约 IT）→ 实现 T025（Provisioner 委托）+ T026（装配）

### Parallel Opportunities

- Setup：T001 ∥ T002
- Foundational：T003 ∥ T005 ∥ T007（三组独立测试）→ T004 ∥ T006 ∥ T008（三个独立实现）
- US1：T009 ∥ T010（单测 ∥ 契约 IT）
- US2：T012 ∥ T013 ∥ T014 ∥ T015（四组独立测试）；T016-T021 内 Config( T016/T017) ∥ Resolver(T018) 部分并行
- US3：T022 ∥ T023 ∥ T024（fixture ∥ 单测 ∥ 契约 IT）
- Polish：T027 ∥ T028 ∥ T029 ∥ T030（独立）

---

## Implementation Strategy

### MVP First（US1 + US2，P1）

1. Phase 1 Setup（K8sHost 配置）
2. Phase 2 Foundational（SPI + 接口——**CRITICAL，阻塞全部**）
3. Phase 3 US1（Service 复用）
4. Phase 4 US2（K8S Host + 懒 resolve）
5. **STOP and VALIDATE**：场景 A + B 端到端（业务 Service 复用 + K8S host backend 懒 resolve）
6. Phase 5 US3（JDK SPI）→ Phase 6 Polish

### Incremental Delivery

1. Foundational → SPI/接口 ready（US1/US2/US3 可并行）
2. US1 → Service 复用可用（运维预打 label 即复用）
3. US2 → K8S 模式 backend 可用（声明 host+pod 即懒 resolve）
4. US3 → JDK 适配可用（写 @Primary 实现即定制）
5. Polish → 回归 + ArchUnit + 文档
6. 每个波次不破坏前一波次（回归对照 = 003/004 既有）

---

## Notes

- **TDD 硬约束**：后端所有功能代码测试先于实现（红→绿→重构，宪法原则七）
- **真实性硬约束（零桩）**：契约 IT 真实 k3s + 真实 pod；ArthasLauncher SPI 测试含 test fixture 真实实现（TestArthasLauncher，非 mock）；故障用真实故障条件
- **零 K8S 依赖守护**：BackendResolver 接口在 gateway-core（无 fabric8），实现在 orchestration；ArchUnit T027 守护
- **003 回归**：K-ATOMIC-1/K-ENS-1~9/SC-001 全部不破（T028 门禁）
- **向后兼容**：静态 url backend + 无 label Service + 无自定义 launcher → 行为 = 003/004 现状（零迁移）
- 每个任务或逻辑组完成后提交；任一 checkpoint 可停下独立验证；同一问题连续失败 3 次暂停重评（CLAUDE.md）
