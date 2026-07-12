# Implementation Plan: K8S Host 远程接入与配置热生效（SSH 引导 / 全配置热生效 / portal 管理 / 显示增强）

**Branch**: `006-k8s-host-remote-access` | **Date**: 2026-07-13 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-k8s-host-remote-access/spec.md`；设计来源 [2026-07-13-k8s-host-remote-access-design.md](../../docs/superpowers/specs/2026-07-13-k8s-host-remote-access-design.md)（brainstorming 定稿，4 波次 + 12 决策）。

## Summary

005 K8S 编排落地后的 4 点生产接入迭代（**4 波次增量交付**，每波独立可用 + 回归不破）：① **SSH 引导接入**（波1）——用户只配 master IP + root + 密码，网关 SSH 登 master 取 `/etc/kubernetes/admin.conf`（k3s `/etc/rancher/k3s/k3s.yaml`）构造 fabric8 client，免用户处理 K8S 鉴权；K8sHost 加 `ssh` 子段（含 `server-override` + `insecure-skip-tls-verify` 兜底），与 `kubeconfig` 互斥；SSH 库 **sshj**。② **全配置热生效**（波2）——K8sHost 配置独立 `config/k8s-hosts.yaml`（仿 `backends.yaml` + WatchService），`K8sHostsWatcher` + `K8sHostStore`（host→client/provisioner 生命周期 diff，建/关/重建）+ k8s 全局参数下次 ensure 读新值；三触发源（手改/portal/启动）统一热重载管道。③ **portal 管理**（波3）——`/admin/k8s-hosts` CRUD 写 `k8s-hosts.yaml` 复用热重载 + root 密码 AES-GCM 加密（密钥 `ARTHAS_GATEWAY_SECRET`）+ 前端 `/k8s-hosts` 视图。④ **显示增强 + bug**（波4）——`BackendDto` 加 K8S 来源字段 + 前端展示 + 修 ensure 后 portal 不显示 bug（`BackendConfigWatcher` compose 异常吞咽 + 前端无自动刷新）。零 gateway-core K8S/SSH 依赖不变（ArchUnit 守护 sshj），003/005 既有契约全部不破。

## Technical Context

**Language/Version**: Java 21（LTS，虚拟线程；`maven.compiler.release=21`，enforcer `[21,22)`）。

**Primary Dependencies**（沿用 001-005 + 新增 SSH）：
- Spring Boot 4.1.0（parent）+ Spring AI 2.0.0（`spring-ai-starter-mcp-server-webmvc` + `-client`，沿用）
- MCP Java SDK 2.0.0（`io.modelcontextprotocol.sdk`，官方，沿用）
- fabric8 kubernetes-client 7.6.1 + commons-compress 1.28.0（003 既有，K8S 编排 + upload）
- **新增 `com.hierynomus:sshj:0.39.x`**（波1，SSH 引导取 kubeconfig；Java 库，符合原则六）+ 传递依赖 BouncyCastle（bcprov，Ed25519/现代算法）
- **测试新增 `org.apache.sshd:sshd-core`**（test scope，embedded SSH server，真实协议非桩）
- ArchUnit 1.3.0（包边界，新增 sshj 守护规则）
- 测试：JUnit5 + AssertJ（surefire 单测）+ failsafe `*IT`（真实 k3s）+ vitest（前端波3/4）

**Storage**: 内存态（`K8sHostStore` host→HostEntry + `K8sBackendResolver` 缓存 + `DynamicBackendStore` + `OrchestrationRecordStore`）+ 配置文件（`config/k8s-hosts.yaml` 新增 + `config/backends.yaml` + `application.yml` 指针）。无 DB。

**Testing**: JUnit5 + AssertJ（surefire 单测）+ failsafe `*IT`（真实 k3s + 真实 SSH）+ ArchUnit（包边界含 sshj）+ vitest（前端波3/4）+ **Apache MINA SSHD embedded**（SSH 真实协议，非桩）。**TDD 真实环境零桩**（宪法原则七）。

**Target Platform**: JVM 网关进程（集群外运行，:8761/mcp）+ 远端标准 K8S / k3s 集群（经 SSH 引导取 kubeconfig + NodePort）。

**Project Type**: web-service（MCP 网关，单 Maven 模块，内嵌 Vue SPA 波3/4 增视图）。

**Performance Goals**: SSH 引导一次性（首取缓存，不每次路由都 SSH）；热重载秒级（WatchService 去抖周期内生效）；ensure ≤5min 不破（SC-001）。

**Constraints**:
- **零 gateway-core K8S/SSH 依赖**（FR-017）：`SshKubeconfigFetcher`/`K8sHostStore`/`K8sHostsWatcher` 全在 orchestration 包；ArchUnit 守护 sshj 不进 gateway-core（`backend/handler/tool/task/auth/obs`）。
- **向后兼容**（FR-005/FR-006）：`kubeconfig` 本地文件模式 100% 保留；`config/k8s-hosts.yaml` 不存在回退 `application.yml` 内联 `k8s-hosts`。
- **TDD 真实环境**（宪法原则七）：SSH 链路用 MINA SSHD embedded（真实协议）+ 契约 IT 跑真实 k3s；故障真实条件（错密码/停 SSH/错路径）。
- **root 密码极敏感**（FR-011）：AES-GCM 加密落盘（密钥 `ARTHAS_GATEWAY_SECRET`），DTO/日志永不回显。
- **003/005 契约不破**（FR-016）：K-ATOMIC-1/K-ENS-*/INV-K8SHOST-*/INV-LAUNCHER-* 全绿。

**Scale/Scope**: 单网关多 K8sHost（运行时增删改，`K8sHostStore` 管理生命周期）；SSH 引导支持标准 K8S（admin.conf）+ k3s（k3s.yaml），路径可配。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 检查 | 结果 |
|------|------|------|
| 一、MCP 规范符合性 | 不改 MCP 协议层（SSH 引导/热重载/portal CRUD 是网关内部，不涉 JSON-RPC） | ✓ PASS |
| 二、透明无损聚合 | SSH 接入/热生效不改诊断路由语义；ensure 仍原样透传诊断结果 | ✓ PASS |
| 三、连接生命周期与局部故障韧性 | 单 host SSH/连接失败隔离（不影响其他 host + 静态后端，K-COEXIST-2 延伸）；热重载 applyDiff 串行 + 路由读无锁（INV-HOT-2） | ✓ PASS |
| 四、双侧契约优先 | 006 新增契约（INV-SSH-*/INV-HOT-*/INV-PORTAL-K8S-*/INV-DISP-*），TDD 测试先于实现 | ✓ PASS |
| 五、可观测性 | SSH 各步日志 + 结构化错误（ssh_unreachable/auth_failed/kubeconfig_*）；热重载 diff 可观测；密码/凭证脱敏不入日志 | ✓ PASS |
| 六、Java 主力 | sshj 是 Java 库；K8sHostStore/Watcher/Fetcher/Cipher 皆 Java；前端 Vue（展示层，004 既有，非核心逻辑）；**新依赖 sshj 在本 plan 记录**（符合"引入依赖须记录"） | ✓ PASS |
| 七、TDD | 测试先于实现（spec-kit SDD）；真实环境零桩（MINA SSHD embedded + 真实 k3s + 真实 pod） | ✓ PASS |
| 八、Arthas/K8S 先决研究 | 003/005 R1-R9 已研 fabric8/kubeconfig；006 `research.md` 补 SSH 库选型（sshj vs jsch/MINA）+ admin.conf/k3s.yaml 路径 + BackendConfigWatcher 热重载模式 + 密码加密方案 + ensure-不显示 bug 根因 | ✓ PASS |

**技术约束**：Java 21 + Maven（可复现）+ 官方 MCP SDK + fabric8 + sshj（SSH 仅引导）。**无违反**。

## Project Structure

### Documentation (this feature)

```text
specs/006-k8s-host-remote-access/
├── plan.md              # 本文件
├── research.md          # Phase 0（R1-Rn：sshj 选型/admin.conf 路径/热重载/密码加密/bug 根因）
├── data-model.md        # Phase 1（K8sHost.ssh/SshBootstrap/SshKubeconfigFetcher/K8sHostStore/HostEntry/K8sHostsWatcher/K8sHostSecretCipher/K8sHostAdmin DTO/BackendDto 增量）
├── quickstart.md        # Phase 1（SSH 引导/热生效/portal CRUD/显示 四场景端到端验证）
├── contracts/           # Phase 1（INV-SSH-*/INV-HOT-*/INV-PORTAL-K8S-*/INV-DISP-*）
└── tasks.md             # /speckit-tasks 产出（Phase 2，4 波次）
```

### Source Code（单 Maven 模块，沿用 001-005 包结构）

```text
src/main/java/com/arthas/gateway/
├── orchestration/                # K8S/SSH 编排（依赖 fabric8 + sshj）
│   ├── K8sClientFactory.java        # 改：加 buildFromSsh（SSH 取 kubeconfig → client）
│   ├── SshKubeconfigFetcher.java    # 新增：SSH 登 master 取 kubeconfig 文本（sshj）
│   ├── K8sHostStore.java            # 新增：host→HostEntry 生命周期，applyDiff 增删改（仿 DynamicBackendStore）
│   ├── K8sHostsWatcher.java         # 新增：监听 config/k8s-hosts.yaml → applyDiff（仿 BackendConfigWatcher）
│   ├── K8sBackendResolver.java      # 改：从 K8sHostStore 查 provisioner（非启动期不可变 Map）
│   └── K8sOrchestrationConfig.java  # 改：装配分支（ssh/kubeconfig）+ K8sHostStore bean
├── backend/                      # gateway-core（零 K8S/SSH 依赖）
│   ├── BackendConfigWatcher.java    # 改：动态 compose 异常处理（波4 bug 修复，holder 兜底）
│   └── BackendResolver.java         # 不变（005 接口）
├── config/
│   ├── GatewayProperties.java       # 改：K8sHost 加 ssh 子段 + k8s-hosts-file 指针 + k8s 全局参数可刷新
│   └── K8sHostsConfig.java          # 新增：config/k8s-hosts.yaml 加载 + K8sHostStore 装配（仿 BackendRegistryBootstrap）
├── admin/k8shost/                # 新增（004 portal 模式，波3）
│   ├── K8sHostAdminController.java  # REST CRUD /admin/k8s-hosts
│   ├── K8sHostAdminService.java     # 写 config/k8s-hosts.yaml → 触发热重载
│   ├── K8sHostsYamlWriter.java      # SnakeYAML dump（仿 BackendsYamlWriter）
│   ├── K8sHostSecretCipher.java     # AES-GCM 加密 ssh 凭证（密钥 ARTHAS_GATEWAY_SECRET）
│   └── dto/                         # K8sHostDto（凭证脱敏）/ CreateK8sHostRequest / ...
└── （web 前端波3/4）
    web/src/views/K8sHostListView.vue # 新增：/k8s-hosts 视图（表格+表单）
    web/src/views/BackendListView.vue # 改：自动刷新（波4）

src/test/java/com/arthas/gateway/
├── orchestration/
│   ├── SshKubeconfigFetcherTest.java        # MINA SSHD embedded：password/key auth + 故障矩阵（单测）
│   ├── K8sClientFactoryBuildFromSshTest.java # buildFromSsh + serverOverride + insecure（单测）
│   ├── K8sHostStoreTest.java                # applyDiff 增删改 lifecycle（单测）
│   ├── SshBootstrapContractIT.java          # 真实测试床：SSH 取 k3s.yaml → 连 K8S（波1 契约 IT）
│   ├── K8sHostHotReloadIT.java              # 真实测试床：热重载增删 host（波2 契约 IT）
│   └── K8sGlobalParamsHotReloadIT.java      # 真实测试床：全局参数下次 ensure 读新值（波2）
├── admin/k8shost/
│   ├── K8sHostAdminControllerTest.java      # CRUD + 凭证脱敏 + 未配 SECRET 400（单测）
│   └── K8sHostPortalCrudIT.java             # 真实测试床：portal POST → 热重载 → 可路由（波3 IT）
├── backend/
│   └── EnsureVisibleInPortalIT.java         # 真实测试床：ensure → holder.current() 含 target（波4 bug 守护）
└── architecture/
    └── PackageBoundaryTest.java             # 扩展：sshj 不进 gateway-core（INV-BOUNDARY 延伸）

config/k8s-hosts.yaml              # 新增：K8sHost 列表独立配置文件（波2，仿 backends.yaml）
```

**Structure Decision**: 单 Maven 模块（沿用 001-005）。orchestration 包承载 SSH/K8S 编排；admin/k8shost 仿 004 admin/backend 做 portal CRUD；前端 web/src/views 增 K8sHostListView。零 gateway-core SSH 依赖由 ArchUnit 守护（INV-BOUNDARY 延伸）。

## Complexity Tracking

> 填写因 Constitution Check 边界/复杂度需正当理由的项（宪法治理：超出原则的复杂度须给正当理由）。

| 项 | 为何必需 | 更简替代为何被否 |
|----|----------|------------------|
| 新增 `sshj` 依赖 | 用户只有 root SSH 凭证，无法免 SSH 接入 K8S（design 决策 #1/#4） | jsch 原版停更、不支持现代算法；Apache MINA SSHD 过重（需求仅 exec cat） |
| `K8sHostStore` + `K8sHostsWatcher`（热重载管道） | 用户要求"所有配置热生效"（design 决策 #5/#6/#7） | 重启生效被用户明确否决；复用 001 BackendConfigWatcher 模式（非新发明，YAGNI 合规） |
| root 密码 AES-GCM 加密 | root 权限极敏感，portal 输入须加密落盘（design 决策 #10，INV-PORTAL-K8S-3） | 裸明文不可接受；Vault 后置 P2（MVP 用环境变量密钥） |
| 4 波次增量交付 | 003/004 量级大特性，单 cycle 周期长（design 决策 #12） | 单波全做风险大；每波独立可用 + 回归不破（增量交付，非简化） |
