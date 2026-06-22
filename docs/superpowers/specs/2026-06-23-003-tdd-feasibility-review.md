# 003 方案 TDD 可行性评审与整改设计

| 项目 | 内容 |
|------|------|
| 主题 | 评审 `specs/003-k8s-arthas-mcp-launch` 方案是否支持 TDD 迭代开发（含"测试条件不充分"专项核查），并给出整改设计 |
| 日期 | 2026-06-23 |
| 特性 | `specs/003-k8s-arthas-mcp-launch`（设计阶段产出，CLAUDE.md：设计阶段走 brainstorming） |
| 依据 | [003 plan.md](../../../specs/003-k8s-arthas-mcp-launch/plan.md)、[spec.md](../../../specs/003-k8s-arthas-mcp-launch/spec.md)、[research.md](../../../specs/003-k8s-arthas-mcp-launch/research.md) R3/R4/R7、[data-model.md](../../../specs/003-k8s-arthas-mcp-launch/data-model.md)、[contracts/k8s-orchestration-tools-contract.md](../../../specs/003-k8s-arthas-mcp-launch/contracts/k8s-orchestration-tools-contract.md)、[contracts/dynamic-registration-invariants.md](../../../specs/003-k8s-arthas-mcp-launch/contracts/dynamic-registration-invariants.md)、[quickstart.md](../../../specs/003-k8s-arthas-mcp-launch/quickstart.md)、[tasks.md](../../../specs/003-k8s-arthas-mcp-launch/tasks.md)、[主设计 2026-06-22](./2026-06-22-k8s-arthas-mcp-launch-design.md) §八、[K8S 测试环境设计 2026-06-23](./2026-06-23-k8s-test-env-setup-design.md) §1.2、[001 夹具设计 2026-06-20](./2026-06-20-arthas-test-fixture-design.md) §7.1、[宪法](../../../.specify/memory/constitution.md) v1.2.0、[CLAUDE.md](../../../CLAUDE.md) 真实性硬约束 |
| 用户裁决 | 故障用例（K-ENS-4/5/6/7）**整体后置**，本迭代**只走通正常场景**（2026-06-23） |

---

## 一、评审结论（总览）

方案对 TDD **大部分支持，但有一个实打实的缺口正好命中"测试条件不充分"**，另有三个次要关切削弱 TDD 纪律。核查范围：波次 A（纯逻辑）/ 波次 B（真实 K8S 夹具）/ 波次 C（端到端）三层。

- ✅ **波次 A（动态注册层纯逻辑）完全可 TDD**：`Source`/`DynamicBackendStore`/`RegistryComposer`/`OrchestrationRecord` 全纯逻辑、surefire、CI 可跑、红-绿-重构环紧凑。与 001 波次 A 同构（[001 夹具设计 §7.1](./2026-06-20-arthas-test-fixture-design.md)）。
- ✅ **波次 B 正常路径（K-ENS-1/2/3）可 TDD**：测试床 `test-env/k8s/setup.sh` 已供给一个 shell+java+JVM 的真实 `demo-business` pod（[K8S 测试环境设计 §1.2](./2026-06-23-k8s-test-env-setup-design.md)），ensure 正常供给/复用/经网关诊断都能真实跑绿。
- ⚠️ **故障用例夹具未供给、亦无建夹具任务**——见 §二（最大缺口）。
- 🔹 三个次要关切——见 §三。

---

## 二、最大缺口：故障用例"测试条件不充分"（已证实）

### 2.1 证据链

- 契约断言 `K-ENS-4`（无 JVM）/ `K-ENS-5`（无 shell）/ `K-ENS-6`（无 exec→403）/ `K-ENS-7`（绑 loopback 不可达）——见 [contracts/k8s-orchestration-tools-contract.md §5](../../../specs/003-k8s-arthas-mcp-launch/contracts/k8s-orchestration-tools-contract.md)；主设计 [§八](./2026-06-22-k8s-arthas-mcp-launch-design.md) 明确"波次 B 故障用真实条件"。
- 但 K8S 测试环境设计 [§1.2](./2026-06-23-k8s-test-env-setup-design.md) 自己写明：起步覆盖 `K-ENS-1/2/3 + K-ENS-7`（单"正常 pod"）；**"后置（按 TDD 进度逐步补，非本设计）：K-ENS-4/5/6 三类故障用例——无 JVM=同镜像改 CMD sleep、无 shell=补 distroless 镜像、无 exec=补受限 kubeconfig"**。§7/§12 又说 `K-ENS-6` 的受限 kubeconfig"随故障用例后置"。
- [tasks.md](../../../specs/003-k8s-arthas-mcp-launch/tasks.md) 把 `K-ENS-4/5/6/7` 放进 T023/T027 当"红先写"测试，**却没有任何任务去建这些故障夹具**。

### 2.2 TDD 后果

红测试能写，但**到不了绿**（夹具不存在）→ 要么卡住、要么诱惑用桩（违反 [CLAUDE.md](../../../CLAUDE.md) 零桩硬约束）、要么实现期临时手搓夹具（未计划、破坏小步迭代）。这正是"测试条件不充分"。

### 2.3 与 001 的关键差异（为何"沿用 001 波次范式"在此点没继承到位）

[001 夹具设计 §7.1](./2026-06-20-arthas-test-fixture-design.md)：001 的故障用例（停掉这 1 个真实后端、错 token→真实 401）是**对同一个真实夹具的瞬态操作**，几乎零成本；003 的故障用例需要**根本不同形态的 pod/凭证**（无 JVM pod / distroless pod / 受限 kubeconfig / loopback arthas 变体），昂贵且未供给。"沿用波次范式"在故障用例上需要额外补夹具或显式后置——本设计取后者（见 §四）。

---

## 三、次要关切（不阻塞，但削弱 TDD 纪律）

| # | 关切 | 证据 | TDD 影响 |
|---|------|------|---------|
| 1 | **波次 B/C 的绿不在 CI 门禁** | IT 走 `Assume`（CI 跳过、本地手跑），[quickstart §2.3](../../../specs/003-k8s-arthas-mcp-launch/quickstart.md) | TDD"绿必须被验证"无持续守护，回归易在两次本地运行间漏掉 |
| 2 | **波次 B 反馈环慢且带状态** | 单次 ensure = 真实 exec + 上传 arthas + 启动 + 健康检查 + NodePort，10s–60s+，且改集群状态（打 label、建 Service、动态注册） | 无规定测试间隔离/清理 → 可重复红-绿环脆弱 |
| 3 | **契约 IT"红先写"比单测重** | T020/T023/T027 跑起来要整套工具装配 + 真实 k3s | 无法孤立快失败，测试先行仍可行但环粗 |

---

## 四、整改设计（基于用户裁决：只走通正常场景，故障后置）

### 4.1 第 1 节 · 本迭代 TDD 绿色目标边界

**✅ 本迭代"正常场景"绿色目标（in-iteration green）**

| 层 | 测试 | 断言 |
|---|---|---|
| 波次 A 纯逻辑（CI 可跑，无 K8S） | `SourceParsingTest` | D-SOURCE-1 |
| | `DynamicBackendStoreTest` | D-REG-1/2/3/4、D-UNREG-1/2/3 |
| | `RegistryComposerTest` | D-COEXIST-1/2、D-ATOMIC-1、D-VERSION-1 |
| | `OrchestrationRecordTest` | 状态机 `ensuring→ready/reused` |
| 波次 B 正常（真实 k3s + demo pod） | `K8sListToolsContractIT` | K-LP-1、K-LS-1 |
| | `ArthasProvisionerIT` | K-ENS-1（ready）、K-ENS-2（reused）、K-ENS-8（命名派生） |
| | `K8sEnsureContractIT` | K-ENS-3（经网关诊断 = 该 pod JVM）、K-COEXIST-1（热重载不误删） |
| 波次 C | 端到端 | SC-001 正常链路（5 分钟内） |

> **命名辨析（勿混）**：`D-COEXIST-2`（[dynamic-registration-invariants.md §5](../../../specs/003-k8s-arthas-mcp-launch/contracts/dynamic-registration-invariants.md)：静态热重载 + 动态 register **并发** → effective 合法）是**纯逻辑并发不变量**，属波次 A，**保留**；`K-COEXIST-2`（[k8s-orchestration-tools-contract.md §5](../../../specs/003-k8s-arthas-mcp-launch/contracts/k8s-orchestration-tools-contract.md)：动态 target **pod 删除**→隔离）是**真实故障**，本设计**后置**。二者仅编号偶合、语义不同。

**⏸️ 后置到"波次 B-fault"（本迭代不写红测试、不验证）**

- `K-ENS-4`（无 JVM）、`K-ENS-5`（无 shell）、`K-ENS-6`（无 exec→403）、`K-ENS-7`（loopback 不可达半）
- `K-COEXIST-2`（pod 删除→隔离）= `SC-003`（pod 驱逐→30s 隔离）
- `ensure` 的**故障错误映射验证**（`no_jvm`/`no_shell`/`attach_failed`/`health_check_timeout`/`nodeport_alloc_failed`/`k8s_unreachable`/`k8s_forbidden` 的 reason/stage）——正常路径不触发

### 4.2 三处歧义裁决（用户 2026-06-23 确认）

1. **波次 A 冲突拒绝测试（D-REG-2/4 同名拒绝、D-UNREG-2 拒移 STATIC）→ 保留**。它们是动态注册层**设计内校验规则**（[dynamic-registration-invariants.md I-3](../../../specs/003-k8s-arthas-mcp-launch/contracts/dynamic-registration-invariants.md)），非 K8S 运行时故障，纯逻辑、CI 可跑、是 ensure 正常注册的地基。
2. **SC-003（pod 删除→30s 隔离）→ 后置**。属故障韧性场景，依赖真实"删 pod"；其复用的 001 健康监控/熔断代码已存在，后置的只是"对动态 target 的验证"。
3. **`ensure` 故障错误映射代码 → 现在写、但不验证**。`ArthasProvisioner` 实现时**照契约写全**故障分支的结构化错误返回（reason/stage 映射），但本迭代**无测试驱动**。这是对纯 test-first 的**诚实、有记录的偏离**（仅限故障分支），目的：避免后置波次返工；不违背"只走通正常场景"。

### 4.3 第 2 节 · 整改动作清单

**A. `tasks.md` 重新定界**

- **T023 `ArthasProvisionerIT`**：断言收窄到 **K-ENS-1/2/8**（ready/reused/命名派生）+ 正常路径原子性；移除 K-ENS-4/5/6/7-fault。
- **T027 `K8sEnsureContractIT`**：断言收窄到 **K-ENS-3**（经网关诊断 = 该 pod JVM）+ **K-COEXIST-1**（热重载不误删——注册正确性，非故障，留下）；移除 **K-COEXIST-2**（= SC-003，后置）。
- **T025 `ArthasProvisioner`**：实现说明改为"故障分支结构化错误代码照契约写全，但本迭代无测试驱动"（§4.2 裁决 3）。
- **T032 quickstart 验证**：SC-003 验证移出，归后置。
- **新增"波次 B-fault（后置）"小节**：显式列 K-ENS-4/5/6/7-fault + K-COEXIST-2 + SC-003 + ensure 故障映射验证，注明后置理由与触发条件（见 §五）。

**B. 三项次要整改（与故障姿态无关，直接提升 TDD 可行性）**

- **B1 · CI 可跑的结构契约测试（不依赖 k3s）**：新增 `K8sOrchestrationToolsListContractTest`（surefire），断言 `tools/list` = **38** 且 3 个编排工具 inputSchema 合法。红先写、快环、CI 跑——给 T022 工具装配一个不靠真实集群就能红绿的快测（对冲 §三-3）。
- **B2 · 波次 B IT 每测试隔离/清理**：规定每个 ensure IT 的 `@AfterEach` 清理（删 NodePort Service + 注销动态 target + 清 pod label），或类级共享 demo pod + 利用 ensure 幂等在类内摊薄慢启动（对冲 §三-2）。
- **B3 · 绿色双轨**：CI 跑结构测试（B1）+ 波次 A；真实夹具 IT（波次 B/C）本地/flag 手跑；quickstart 固化手跑清单与 `Assume` 守护约定（对冲 §三-1）。

**C. 契约 / spec 同步项**

- [contracts/k8s-orchestration-tools-contract.md §5](../../../specs/003-k8s-arthas-mcp-launch/contracts/k8s-orchestration-tools-contract.md)：K-ENS-4/5/6/7-fault + K-COEXIST-2 标"后置（波次 B-fault）"。
- [quickstart.md](../../../specs/003-k8s-arthas-mcp-launch/quickstart.md)：SC-003、K-ENS-4/5/6/7-fault 移到"后置验证"小节。
- [spec.md](../../../specs/003-k8s-arthas-mcp-launch/spec.md)：边缘情况里的故障项（无 JVM/无 shell/不可达/端口占用/驱逐）标"P1 不验证、后置"；P1 验收场景 1–4（正常链路）保持不变。

---

## 五、后置清单（波次 B-fault，避免遗忘）

本迭代不实现/不验证，留待后续迭代（届时按 [K8S 测试环境设计 §1.2](./2026-06-23-k8s-test-env-setup-design.md) 的"按 TDD 进度逐步补"补夹具）：

| 用例 | 触发条件（真实故障，零桩） | 夹具形态 |
|------|------------------------|---------|
| K-ENS-4 无 JVM | 对无 java 进程的 pod ensure | 同镜像 `CMD ["sleep","infinity"]` 的第 2 个 pod |
| K-ENS-5 无 shell | 对 distroless pod ensure | 新 distroless 镜像 |
| K-ENS-6 无 exec→403 | 用拒 exec 的受限 kubeconfig | 专用 ServiceAccount + 拒 exec Role/RoleBinding + 第 2 kubeconfig |
| K-ENS-7 loopback 不可达 | arthas 绑 `127.0.0.1` | Provisioner 测试钩子强制 `--target-ip 127.0.0.1`（R4 已先期实证） |
| K-COEXIST-2 / SC-003 | 删除目标 pod | 复用 demo pod + `kubectl delete pod` |

> 后置触发：当正常链路稳定、且需演示故障韧性/鲁棒性时启动该波次；其 ensure 故障映射代码已在 §4.2 裁决 3 提前写就，届时只需补红测试到绿。

---

## 六、落地节奏（遵 CLAUDE.md 工作流规范）

1. **本文档**：设计阶段（brainstorming）产出，归档 `docs/superpowers/specs/`。
2. **spec-kit 规划维护**（用户复核本文档后）：落地 §4.3 的 A/B/C 文档改动——`tasks.md` 重新定界 + 新增 B1 测试任务 + 契约/quickstart/spec.md 同步。这些是**规划文档维护**，非功能代码。
3. **功能代码**：仍走 spec-kit SDD（`/speckit-implement`，以 `tasks.md` 为权威任务清单、测试先于实现）。**不调** SuperPower `writing-plans`（CLAUDE.md 白名单禁用）。
4. **后置波次 B-fault**：后续迭代单独启动，补夹具 + 红测试到绿。

---

## 七、与既有文档的关系

- 本设计**不改** 003 的架构决策（模块化单体、单 MCP 端点、kubectl exec 拓扑、3 工具收敛、NodePort、程序化动态注册）——这些已在 [主设计 2026-06-22](./2026-06-22-k8s-arthas-mcp-launch-design.md) 与 [research.md](../../../specs/003-k8s-arthas-mcp-launch/research.md) 定稿。
- 本设计**只调整 TDD 的"绿色目标边界"与测试基建补强**：把故障用例从"本迭代契约断言"改为"后置波次"，并补三项提升 TDD 可行性的次要整改。
- 与 [K8S 测试环境设计 2026-06-23](./2026-06-23-k8s-test-env-setup-design.md) 一致：该设计 §1.2 已把故障夹具标"后置"，本设计把 tasks.md/契约对齐到该结论（消除"契约断言 vs 测试床供给"的脱节）。
