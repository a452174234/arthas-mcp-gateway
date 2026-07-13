# Tasks: K8S Host 远程接入与配置热生效（SSH 引导 / 全配置热生效 / portal 管理 / 显示增强）

**Input**: Design documents from `/specs/006-k8s-host-remote-access/`（spec.md / plan.md / research.md R1–R10 / data-model.md / contracts/host-remote-access-invariants.md / quickstart.md）+ `.specify/memory/constitution.md`（v1.2.0）。

**实施范围声明**：本计划实施 006 = US1 SSH 引导（P1，波1）+ US2 全配置热生效（P1，波2）+ US3 portal 管理（P1，波3）+ US4 显示增强+bug（P2，波4）。复用 003/005 既有编排核心；零 gateway-core K8S/SSH 依赖不变（ArchUnit 守护）；003/005 既有契约全部不破（回归门禁）。

**TDD 硬约束（不可妥协，宪法原则七 + CLAUDE.md）**：后端所有功能代码必须 TDD——先写失败测试（红）、再实现至通过（绿）、再重构。本清单中**测试任务一律先于其实现任务**。**真实性硬约束（零桩）**：SSH 单测用 Apache MINA SSHD embedded（真实 SSH 协议）；契约 IT 跑真实测试床 k3s（debian 192.168.31.92）+ 真实 SSH + 真实 pod；故障用真实故障条件（错密码=ssh_auth_failed、停 SSH=ssh_unreachable、错路径=kubeconfig_not_found）。

**波次映射**：Phase 1 = Setup（依赖+配置类）；Phase 2 = Foundational（波1 共享 SshBootstrap/Fetcher）；Phase 3 = US1（SSH 引导，波1，MVP）；Phase 4 = US2（全配置热生效，波2）；Phase 5 = US3（portal 管理，波3）；Phase 6 = US4（显示增强+bug，波4）；Phase 7 = Polish（回归+ArchUnit+文档）。

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: 可并行（不同文件、无未完成任务依赖）
- **[Story]**: 仅 Phase 3/4/5/6 任务带 `[US1]`/`[US2]`/`[US3]`/`[US4]`；Setup/Foundational/Polish 不带
- 每条任务含精确文件路径

## Path Conventions

- 单 Maven 模块，后端 Java 源 `src/main/java/com/arthas/gateway/...`，后端测试 `src/test/java/com/arthas/gateway/...`
- 配置 `src/main/resources/application.yml` + `config/k8s-hosts.yaml`（新增）+ `config/backends.yaml`
- 前端 `web/src/...`

---

## Phase 1: Setup（SSH 依赖 + K8sHost.ssh 配置类 + 配置文件骨架）

**Purpose**：pom 加 sshj/MINA SSHD + K8sHost 加 Ssh 子段 + config/k8s-hosts.yaml 骨架。

- [X] T001 [P] Add `com.hierynomus:sshj:0.38.0`（compile）+ `org.apache.sshd:sshd-core:2.13.x`（test）to `pom.xml`（R1；sshj 生产用，MINA SSHD 测试 embedded server）
- [X] T002 [P] Add `Ssh` 内部类（host/port=22/user/password/privateKey/passphrase/kubeconfigRemotePath/serverOverride/insecureSkipTlsVerify=false）+ `ssh` 字段 to `src/main/java/com/arthas/gateway/config/GatewayProperties.java`（K8sHost 内；kubeconfig 与 ssh 互斥校验，INV-SSH-3）
- [X] T003 [P] Add `arthas-gateway.k8s-hosts-file: config/k8s-hosts.yaml` 指针 to `src/main/resources/application.yml` + 骨架 `config/k8s-hosts.yaml`（version + hosts:[] + k8s-params）+ `config/k8s-hosts.yaml` 进 `.gitignore`（含凭证）

**Checkpoint**: sshj 依赖就位；K8sHost.ssh 可绑定；config/k8s-hosts.yaml 骨架存在。

---

## Phase 2: Foundational（SshBootstrap + SshKubeconfigFetcher，阻塞 US1）

**Purpose**：波1 共享基建——SshBootstrap record + SshKubeconfigFetcher（sshj，MINA SSHD 单测）。

> **TDD（宪法原则七）**：测试先写并确认失败（红），再实现至通过（绿）。

- [X] T004 [P] Write failing `SshBootstrapTest` in `src/test/java/com/arthas/gateway/orchestration/SshBootstrapTest.java`（record 字段全集 + 互斥/必填校验）
- [X] T005 Implement `SshBootstrap` record in `src/main/java/com/arthas/gateway/orchestration/SshBootstrap.java`（host/port/user/password/privateKey/passphrase/kubeconfigRemotePath/serverOverride/insecureSkipTlsVerify；green for T004）
- [X] T006 [P] Write failing `SshKubeconfigFetcherTest` in `src/test/java/com/arthas/gateway/orchestration/SshKubeconfigFetcherTest.java`（**Apache MINA SSHD embedded server**：① password auth + 文件存在 → 返 kubeconfig 文本；② 错密码 → `ssh_auth_failed`；③ 停 server → `ssh_unreachable`；④ 路径不存在 → `kubeconfig_not_found`；⑤ 内容空 → `kubeconfig_invalid`；零桩，真实 SSH 协议）
- [X] T007 Implement `SshKubeconfigFetcher` + `SshBootstrapException`（reason 枚举）in `src/main/java/com/arthas/gateway/orchestration/SshKubeconfigFetcher.java`（sshj：connect 10s + exec cat 15s + 超时；green for T006）

**Checkpoint**: SshBootstrap + SshKubeconfigFetcher 就位（MINA SSHD 真实验证）。US1 可在此之上构建。

---

## Phase 3: User Story 1 - SSH 引导接入（Priority: P1，波1）🎯 MVP

**Goal**：K8sHost 配 ssh → 网关 SSH 登 master 取 kubeconfig → fabric8 client 连 K8S。用户只配 IP+root+密码。

**Independent Test**: 配 K8sHost（ssh: master IP+root+密码+path），调 list-pods，确认网关 SSH 取 kubeconfig → 连 K8S API。

> **TDD + 真实零桩**：契约 IT 真实测试床 k3s + 真实 SSH。

### 测试先于实现

- [X] T008 [P] [US1] Write failing `K8sClientFactoryBuildFromSshTest` in `src/test/java/com/arthas/gateway/orchestration/K8sClientFactoryBuildFromSshTest.java`（① buildFromSsh 返可达 client；② serverOverride 替换 masterUrl；③ insecureSkipTlsVerify=true → trustCerts+disableHostnameVerification；INV-SSH-4）
- [X] T009 [P] [US1] Write failing `SshBootstrapContractIT` in `src/test/java/com/arthas/gateway/orchestration/SshBootstrapContractIT.java`（failsafe *IT，真实测试床 k3s：K8sHost ssh host=192.168.31.92/root/key/path=/etc/rancher/k3s/k3s.yaml → 网关 SSH 取 kubeconfig → 连 K8S → list-pods 返 demo-business；INV-SSH-1）

### 实现

- [X] T010 [US1] Implement `K8sClientFactory.buildFromSsh(SshBootstrap)` in `src/main/java/com/arthas/gateway/orchestration/K8sClientFactory.java`（fetchKubeconfig → Config.fromKubeconfig + 可选 setMasterUrl/trustCerts → KubernetesClientBuilder；既有 buildFromKubeconfig 不变，INV-SSH-5；green for T008）
- [X] T011 [US1] Implement `K8sOrchestrationConfig` 装配分支 in `src/main/java/com/arthas/gateway/config/K8sOrchestrationConfig.java`（backendResolver 循环：ssh→buildFromSsh，kubeconfig→buildFromKubeconfig；K8sHost.Ssh→SshBootstrap 映射；green for T009）

**Checkpoint (US1)**: 配 IP+root+密码 → SSH 引导连 K8S（核心 MVP 可用）。

---

## Phase 4: User Story 2 - 全配置热生效（Priority: P1，波2）

**Goal**：config/k8s-hosts.yaml + K8sHostsWatcher + K8sHostStore（host 生命周期 diff）+ k8s 全局参数热生效。运行时改配置秒级生效，不重启。

**Independent Test**: 运行时改 config/k8s-hosts.yaml（加/删/改 host），确认秒级生效（不重启）。

> **TDD + 真实零桩**：契约 IT 真实测试床 k3s 热重载。

### 测试先于实现

- [X] T012 [P] [US2] Write failing `K8sHostStoreTest` in `src/test/java/com/arthas/gateway/orchestration/K8sHostStoreTest.java`（applyDiff：① 新增 host → 建 client put；② 删除 → close+remove+清缓存；③ 改 ssh 密码 → 重建+清缓存；④ 线程安全 applyDiff 串行；mock SSH fetcher 返固定 kubeconfig 文本验 lifecycle diff，非验 SSH 本身）
- [X] T013 [P] [US2] Write failing `K8sHostsConfigTest` in `src/test/java/com/arthas/gateway/config/K8sHostsConfigTest.java`（① 解析 config/k8s-hosts.yaml（hosts + k8s-params）；② 文件不存在 → 回退 application.yml 内联 k8s-hosts，INV-HOT-5；③ 解析失败 → 回退上次有效，INV-HOT-4）
- [ ] T014 [P] [US2] Write failing `K8sHostHotReloadIT` in `src/test/java/com/arthas/gateway/orchestration/K8sHostHotReloadIT.java`（failsafe *IT，真实测试床：运行时改 config/k8s-hosts.yaml 加/删 host → 秒级生效，新 host 可路由/删 host 不可路由；INV-HOT-1/2）
- [ ] T015 [P] [US2] Write failing `K8sGlobalParamsHotReloadIT` in `src/test/java/com/arthas/gateway/orchestration/K8sGlobalParamsHotReloadIT.java`（failsafe *IT，真实测试床：改 arthas-password → 下次 ensure 用新值，已 ensure 的 pod 不变；INV-HOT-3）

### 实现

- [X] T016 [US2] Implement `K8sHostStore` + `HostEntry`（AutoCloseable）in `src/main/java/com/arthas/gateway/orchestration/K8sHostStore.java`（ConcurrentHashMap byName + synchronized applyDiff 增删改 + K8sParams 快照；green for T012）
- [X] T017 [US2] Implement `K8sHostsWatcher` in `src/main/java/com/arthas/gateway/orchestration/K8sHostsWatcher.java`（仿 BackendConfigWatcher：WatchService + watchLoop + reloadOnce → store.applyDiff；green for T014）
- [X] T018 [US2] Implement `K8sHostsConfig` in `src/main/java/com/arthas/gateway/config/K8sHostsConfig.java`（加载 config/k8s-hosts.yaml + 回退 application.yml + 装配 K8sHostStore/K8sHostsWatcher；green for T013）
- [X] T019 [US2] Refactor `K8sBackendResolver` 从 `K8sHostStore` 查 provisioner（非启动期不可变 Map）+ `K8sOrchestrationConfig` 装配 store in `src/main/java/com/arthas/gateway/orchestration/K8sBackendResolver.java`（green for T014）
- [ ] T020 [US2] Implement `K8sParams` 快照 + `ArthasProvisioner` 每次 ensure 读 `store.currentParams()` in `src/main/java/com/arthas/gateway/orchestration/`（green for T015）

**Checkpoint (US2)**: 所有 K8S 配置变更热生效（不重启）。

---

## Phase 5: User Story 3 - portal 管理（Priority: P1，波3）

**Goal**：/admin/k8s-hosts CRUD 写 config/k8s-hosts.yaml → 复用波2 热重载 + root 密码 AES-GCM 加密 + 前端 /k8s-hosts。

**Independent Test**: portal POST 一个 K8sHost（ssh），确认写文件 → 热重载 → host 立即可路由 + 密码加密 + 不回显。

> **TDD + 真实零桩**：契约 IT 真实测试床 portal → 热重载 → 可路由。

### 测试先于实现

- [X] T021 [P] [US3] Write failing `K8sHostSecretCipherTest` in `src/test/java/com/arthas/gateway/admin/k8shost/K8sHostSecretCipherTest.java`（① encrypt→decrypt 往返；② isConfigured() 随 ENV；③ 未配 ENV → encrypt 抛/返状态；AES-GCM）
- [X] T022 [P] [US3] Write failing `K8sHostAdminControllerTest` in `src/test/java/com/arthas/gateway/admin/k8shost/K8sHostAdminControllerTest.java`（① CRUD 写 k8s-hosts.yaml；② K8sHostDto 无 password/privateKey/passphrase，INV-PORTAL-K8S-2；③ 未配 SECRET → POST 含凭证 400 secret_key_not_configured，INV-PORTAL-K8S-3）
- [ ] T023 [P] [US3] Write failing `K8sHostPortalCrudIT` in `src/test/java/com/arthas/gateway/admin/k8shost/K8sHostPortalCrudIT.java`（failsafe *IT，真实测试床：portal POST ssh host → 写 yaml 加密 → 热重载 → host 可路由；INV-PORTAL-K8S-1/4）

### 实现

- [X] T024 [US3] Implement `K8sHostSecretCipher` in `src/main/java/com/arthas/gateway/admin/k8shost/K8sHostSecretCipher.java`（AES-GCM，密钥来自 ARTHAS_GATEWAY_SECRET；green for T021）
- [X] T025 [US3] Implement `K8sHostsYamlWriter` + `K8sHostAdminService` in `src/main/java/com/arthas/gateway/admin/k8shost/`（写 config/k8s-hosts.yaml version+1 + 凭证加密 → 触发 K8sHostsWatcher 热重载；green for T022）
- [X] T026 [US3] Implement `K8sHostAdminController` + DTO（K8sHostDto 脱敏/Create/Update Request）+ 能力开关 in `src/main/java/com/arthas/gateway/admin/k8shost/`（green for T022）
- [X] T027 [US3] Implement 前端 `/k8s-hosts` 视图 in `web/src/views/K8sHostListView.vue` + `K8sHostForm.vue`（密码 type=password，编辑留空=不改）+ `web/src/router.ts` 路由 + `SpaConfig` 加 `/k8s-hosts` forward + `web/src/api/adminClient.ts` CRUD（green for T023 前端）

**Checkpoint (US3)**: portal 网页管理 K8sHost，改完立即生效 + 密码加密不回显。

---

## Phase 6: User Story 4 - 显示增强 + bug 修复（Priority: P2，波4）

**Goal**：BackendDto 加 K8S 来源字段 + 前端展示 + 修 ensure 后 portal 不显示 bug（compose 异常吞咽 + 前端无自动刷新）。

**Independent Test**: ensure 一个 pod，刷新 portal，确认看到 target（{server}-{pod}）+ K8S 来源字段。

> **TDD + 真实零桩**：契约 IT 真实测试床 ensure → holder 含 target。

### 测试先于实现

- [ ] T028 [P] [US4] Write failing `EnsureVisibleInPortalIT` in `src/test/java/com/arthas/gateway/backend/EnsureVisibleInPortalIT.java`（failsafe *IT，真实测试床：ensure 成功 → RegistryHolder.current() 含 {server}-{pod}，**不受** compose 异常吞咽影响；INV-DISP-1）
- [X] T029 [P] [US4] Write failing `BackendDtoK8sFieldsTest` in `src/test/java/com/arthas/gateway/admin/backend/BackendDtoK8sFieldsTest.java`（K8S 来源 backend DTO 带 k8sHost/pod/namespace/sourceDetail/ensureStatus，INV-DISP-3；仍无 token/password，INV-DISP-4）

### 实现

- [ ] T030 [US4] Fix `BackendConfigWatcher` 动态 compose 异常吞咽 in `src/main/java/com/arthas/gateway/backend/BackendConfigWatcher.java`（:190-196 catch 改为异常可见 WARN + holder 兜底含新注册项；green for T028）
- [X] T031 [US4] Implement `BackendDto` 加 K8S 来源字段 + `BackendAdminService.list` 投影（自 OrchestrationRecordStore + host 配置）in `src/main/java/com/arthas/gateway/admin/backend/`（green for T029）
- [ ] T032 [US4] Implement 前端 `BackendListView.vue` 自动刷新 + K8S 来源列 in `web/src/views/BackendListView.vue`（green for T028 前端 INV-DISP-2）

**Checkpoint (US4)**: ensure 后 portal 可见 + K8S 来源 + 自动刷新。

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**：包边界守护、回归、文档。

- [X] T033 [P] Write failing `PackageBoundaryTest` 扩展 in `src/test/java/com/arthas/gateway/architecture/PackageBoundaryTest.java`（① gateway-core 不依赖 `com.hierynomus.sshj`，INV-BOUNDARY-3；② SshKubeconfigFetcher/K8sHostStore/K8sHostsWatcher 在 orchestration；③ BackendResolver 接口仍零 fabric8/sshj import）
- [ ] T034 [P] Regression guard：`./mvnw verify -DskipFrontend=true` 全绿——003 既有契约（K8sEnsureContractIT/K8sListToolsContractIT/ArthasProvisionerIT）+ 005（K8sBackendResolver/NodePortExposer/CustomLauncher 契约 IT）+ ArchUnit + 001/002/004 既有全绿（FR-016 回归门禁）
- [ ] T035 [P] Update docs：handbook `docs/handbook/part4-k8s.md` + `part5-portal.md` + `part9-decisions.md` 补 006（SSH 引导/热生效/portal/显示 + sshj 决策）+ README（宪法"每项新能力必须有文档"）
- [ ] T036 Run `quickstart.md` validation：场景 A（SSH 引导）+ B（热生效）+ C（portal CRUD）+ D（显示+bug），逐项核对 Done Definition

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**：无依赖；T001 ∥ T002 ∥ T003（独立）
- **Foundational (Phase 2)**：依赖 Setup（T001 sshj + T002 K8sHost.ssh）；**阻塞 US1**
- **US1 (Phase 3，波1 MVP)**：依赖 Foundational（SshBootstrap/Fetcher）
- **US2 (Phase 4，波2)**：依赖 US1（K8sHostStore 复用 buildFromSsh/buildFromKubeconfig + 装配分支）
- **US3 (Phase 5，波3)**：依赖 US2（portal 写文件触发热重载管道）
- **US4 (Phase 6，波4)**：显示增强依赖 US1（K8sHost 实体）；bug 修复（T028/T030）可与 US1 并行（独立链路）
- **Polish (Phase 7)**：依赖 US1+US2+US3+US4 完成

### Within US1（测试先行 → 实现）

1. 测试：T008（单测）+ T009（契约 IT）→ 实现 T010（buildFromSsh）+ T011（装配）

### Within US2（测试先行 → 实现）

1. 测试：T012（Store）+ T013（Config）+ T014（热重载 IT）+ T015（全局参数 IT）→ 实现 T016-T020（Store→Watcher→Config→Resolver→Params）

### Within US3（测试先行 → 实现）

1. 测试：T021（Cipher）+ T022（Controller）+ T023（portal IT）→ 实现 T024-T027（Cipher→Writer/Service→Controller/DTO→前端）

### Within US4（测试先行 → 实现）

1. 测试：T028（可见性 IT）+ T029（DTO 字段）→ 实现 T030（bug fix）+ T031（DTO 投影）+ T032（前端刷新）

### Parallel Opportunities

- Setup：T001 ∥ T002 ∥ T003
- Foundational：T004 ∥ T006（两组独立测试）→ T005 ∥ T007
- US1：T008 ∥ T009（单测 ∥ 契约 IT）
- US2：T012 ∥ T013 ∥ T014 ∥ T015（四组独立测试）；T016-T020 内 Store(T016) ∥ Config(T018) 部分并行
- US3：T021 ∥ T022 ∥ T023（三组独立测试）
- US4：T028 ∥ T029（独立测试）；T030（bug fix）可与 US1 并行启动
- Polish：T033 ∥ T034 ∥ T035（独立）

---

## Implementation Strategy

### MVP First（US1，波1）

1. Phase 1 Setup（sshj + K8sHost.ssh + 配置骨架）
2. Phase 2 Foundational（SshBootstrap + SshKubeconfigFetcher——**CRITICAL，阻塞 US1**）
3. Phase 3 US1（SSH 引导：buildFromSsh + 装配分支）
4. **STOP and VALIDATE**：场景 A 端到端（配 IP+root+密码 → list-pods 成功）
5. Phase 4 US2 → Phase 5 US3 → Phase 6 US4 → Phase 7 Polish

### Incremental Delivery

1. Foundational → SshBootstrap/Fetcher ready
2. US1 → SSH 引导可用（配 IP+root+密码就连，核心 MVP）
3. US2 → 全配置热生效（改配置不重启）
4. US3 → portal 管理可用（网页 CRUD host）
5. US4 → 显示增强 + bug 修复（portal 可见 pod/arthas）
6. Polish → 回归 + ArchUnit + 文档
7. 每波不破坏前一波（回归对照 = 003/005 既有）

---

## Notes

- **TDD 硬约束**：后端所有功能代码测试先于实现（红→绿→重构，宪法原则七）
- **真实性硬约束（零桩）**：SSH 单测用 MINA SSHD embedded（真实协议）；契约 IT 真实测试床 k3s + 真实 SSH + 真实 pod；故障用真实故障条件
- **零 K8S/SSH 依赖守护**：SshKubeconfigFetcher/K8sHostStore/K8sHostsWatcher 在 orchestration；BackendResolver 接口在 gateway-core 无 fabric8/sshj import；ArchUnit T033 守护
- **003/005 回归**：K-ATOMIC-1/K-ENS-1~12/INV-K8SHOST-*/INV-LAUNCHER-* 全部不破（T034 门禁）
- **向后兼容**：kubeconfig 文件模式 + 无 ssh host → 行为 = 005 现状（零迁移）；config/k8s-hosts.yaml 不存在回退 application.yml
- **公司标准 K8S 够不着**：核心链路用测试床 k3s 验证（路径可配兼容 admin.conf）；标准 K8S 真实端到端延后公司落地（文档声明）
- 每个任务或逻辑组完成后提交；任一 checkpoint 可停下独立验证；同一问题连续失败 3 次暂停重评（CLAUDE.md）
