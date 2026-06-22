# Feature Specification: K8S 目标 arthas MCP 启动与纳管

**Feature Branch**: `003-k8s-arthas-mcp-launch`

**Created**: 2026-06-22

**Status**: Draft

**Input**: 用户描述："新需求需要支持在目标的K8S容器里面启动arthas MCP 并通过service的端口将MCP的IP暴露给外部……构建一套可以基于当前网关，去对指定目标启动一个 MCP 容器并使用的能力。"（完整需求涉及：① K8S 实验环境离线脚本化构造；② 给定 Linux 服务器→发现 pod/service→选 pod 启 arthas MCP→经 service 暴露→网关感知使用；③ portal 管理平台；④ Windows 直启 agent。）

## Clarifications

### Session 2026-06-22

- Q: portal 在架构上如何定位？ → A: **独立 Java 管理后端**，与现有 arthas MCP 网关并列协作——portal 管 Linux 服务器/K8S 编排/配置；网关保持诊断聚合职责不变。
- Q: 本特性范围与分期？ → A: 一个 spec 覆盖全部 4 块，按优先级渐进：P1=②核心（对指定 K8S 目标启动 MCP 容器并经网关使用）、P2=①K8S 实验环境离线脚本化、P3=③portal 管理平台、P4=④Windows 直启 agent。MVP=P1。
- Q: portal 交互形态？ → A: **CLI/API 优先**（Java 后端），MVP 不含 Web UI；Web UI 列为后续增强。
- Q: MVP（P1）阶段，"枚举/启动/暴露/纳管"编排逻辑承载在哪个组件（portal 尚未存在）？ → A: **模块化单体（modular monolith）**——编排核心、portal、网关聚合为**微服务式边界的独立 Java 模块**，但最终编译为**一个完整的可部署包**；通过**依赖管理**（Maven 模块排除 / optional 依赖 / starter 模式）可随时排除不需要的功能模块（默认全量、按需裁剪）。故 P1 即构建可复用的 Java 编排核心模块，portal（P3）为同包内并列模块包裹该核心，而非独立部署的微服务。
- Q: 动态 target 的逻辑名（身份）如何确立？ → A: **自动命名**——命名规则 = **服务器名 + Pod 名**（如 `{server}-{pod}`），非人工命名。系统**支持自动实例化**：触发后系统自动实例化 arthas MCP、按上述规则自动命名并自动纳管，无需人工逐项命名。初始化由管理系统（编排核心/portal）承载；不连接未实例化的 MCP（先实例化再连接）。
- Q（Q2 消歧·自动实例化触发范围）：是人选定后系统自动，还是全自动？ → A: **由大模型（Claude）经上下文推断需要哪个目标并触发自动初始化，非人工选择 pod**；人只提供**初始的目标 Linux 服务器**。即交互模型为：人提供服务器 → 大模型从对话上下文推断所需目标 → 系统自动实例化+自动命名+自动纳管 → 大模型经网关诊断。实现含义：本工程（网关/编排核心）需把"发现/自动实例化/自动纳管"做成**可被 Claude 经 MCP 调用的工具**；"上下文推断"是 Claude 的原生能力，本工程不实现推断逻辑、只提供确定性工具（标准 MCP agentic 模式）。
- Q（Q3·分期定位）："大模型上下文推断+自动初始化"的 agentic 流是 P1 还是后续？ → A: **后续（north-star）**。**P1 MVP = 自动实例化+自动命名（`{服务器名}-{Pod名}`）+自动纳管的"机制"**（给定明确目标 pod 即自动完成，作为垫脚石）；**north-star（后续增强）= 大模型经上下文推断目标并触发自动初始化（取代人工指定目标）**。即：自动供给机制 P1 与 north-star 共用，差异在"谁指定目标"——P1 由人显式指定目标 pod，north-star 由 Claude 推断。故 P1 用户故事 1 仍保留"人指定目标 pod"，仅把"启动/命名/纳管"三步自动化；Q2 消歧所述"非人工选择 pod"系指 north-star 终态。
- Q（Q4·P1 触发界面 + 架构原则）：P1 的"自动供给"如何触发？ → A: **不构建过程式编排模块**——只暴露**最小化的原子 MCP 能力**（细粒度、各自独立可调），**大模型（Claude）是编排者**，从上下文推断、组合原子工具自己把"发现→启动→暴露→纳管→诊断"串起来。据此修正 Q3："启动/命名/纳管三步自动化"实为"由 LLM 编排原子工具完成"，无过程式编排模块。P1 与 north-star 共享此架构，差异仅在目标来源（P1 人指定 / north-star LLM 推断）。编排原子工具属**独立于网关诊断聚合的 MCP 面**（不污染宪法原则二）。
- Q（Q4 收敛·工具粒度，2026-06-22 设计 [brainstorming](../../docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md) §4 确认）：原"原子工具集（粒度待 plan/design 确认）"现已收敛——判断"原子工具该不该合并"的标准 = **LLM 是否需要在两步之间推理**：「枚举 ↔ 选 pod」之间 LLM 要推理 → `list-pods`/`list-services` 保持独立；而「装→启→暴露→注册→健康检查」之间**无 LLM 决策点**、是一条机械的"确保就绪"操作 → 合并为**幂等 `ensure-arthas-mcp`**（含实例化 + service 暴露 + 动态注册 + 内部健康检查）。故编排 MCP 面最终为 **3 个工具**：`k8s.list-pods` / `k8s.list-services` / `k8s.ensure-arthas-mcp`，外加既有网关诊断工具。FR-002 改述为"幂等供给"，FR-004/FR-005 降为 `ensure` 内部子行为（仍是有效需求，但不再是独立工具）。

## 用户场景与测试 *(mandatory)*

### 用户故事 1 - 对指定 K8S 目标启动 arthas MCP 并经网关使用（Priority: P1）

运维或开发人员给定一台已具备 K8S 环境的 Linux 服务器后，系统能枚举该集群里的 pod 与 service；用户选择一个运行 JVM 的目标 pod，系统为其启动一个 arthas MCP 诊断服务，并通过 K8S service 把该 MCP 的端点对外暴露；暴露就绪后，系统将该端点作为一个新的目标后端**动态注册到现有 arthas MCP 网关**，用户随后经网关（用 `target` 指定该目标）即可对所选 pod 的 JVM 执行 arthas 诊断——"启动 + 暴露 + 纳管 + 使用"链路打通。

**实现机制（Q4 澄清）**：上述链路由**大模型（Claude）经网关组合原子 MCP 能力**完成（枚举→实例化→暴露→纳管→诊断），系统**不内建过程式编排流程**；P1 目标 pod 由人（经对话）指定，north-star 由大模型上下文推断。

**为何这个优先级**：这是本特性存在的核心价值——把"对任意指定 K8S 目标按需启动诊断 MCP 并纳入现有网关使用"的闭环跑通。没有它，后续的环境脚本、portal、Windows agent 都失去依托。这也正是需求总结句"基于当前网关，去对指定目标启动一个 MCP 容器并使用的能力"的落地。

**独立测试**：在一台已具备 K8S 的 Linux 服务器上，选择一个运行 JVM 的 pod，触发"启动→暴露→纳管"，确认经网关对该 `target` 的诊断结果来自所选 pod 的 JVM。

**验收场景**：

1. **Given** 一台已具备 K8S 的 Linux 服务器且集群内有运行 JVM 的业务 pod，**When** 用户触发"枚举 pod/service"，**Then** 返回该集群当前 pod 与 service 清单。
2. **Given** 上述清单，**When** 用户选定一个运行 JVM 的目标 pod 并触发"启动 arthas MCP"，**Then** 系统**自动实例化** arthas MCP、按 `{服务器名}-{Pod名}` **自动命名**、并通过 K8S service 暴露一个可访问端点。
3. **Given** arthas MCP 已暴露且就绪，**When** 系统将其动态注册到网关，**Then** 网关 target 列表新增该目标，且经网关对该 target 的诊断调用返回来自所选 pod 的结果（与直连该 arthas MCP 一致，无篡改）。
4. **Given** 该 target 已纳管，**When** 用户经网关对该 target 执行诊断，**Then** 网关按既有 target 路由规则转发并原样返回结果，行为与现有静态 target 一致（宪法原则二）。

---

### 用户故事 2 - 离线脚本化构造最小 K8S 实验环境（Priority: P2）

用户拿到一台全新的 Linux 服务器（仅装好 OS），执行一套**脚本化、可离线**的构造步骤，即可得到一个最小可用的 K8S 实验环境，无需联网逐步拉取；脚本可重复执行，换一台新机器也能快速复现同一环境，为用户故事 1 提供可复现的实验底座。

**为何这个优先级**：用户故事 1 依赖一个可用的 K8S 环境；脚本化、离线、可复现的构造方式让"任意新机器快速起环境"成为可能，是核心能力可演示、可测试的前提。但它可在"已有 K8S"的假设下后置（故 P2 而非 P1）。

**独立测试**：在一台全新 Linux 服务器上执行构造脚本，完成后确认得到一个可用的最小 K8S 环境，并立即用于用户故事 1 的全链路。

**验收场景**：

1. **Given** 一台仅装好 OS 的全新 Linux 服务器，**When** 用户执行构造脚本，**Then** 在可接受的时间内得到一个可用的最小 K8S 实验环境（含可调度 pod 的基本能力），且过程无需联网拉取外部依赖。
2. **Given** 已构造的环境，**When** 用户再次执行同一脚本，**Then** 脚本幂等、不破坏既有状态、不重复污染环境。
3. **Given** 另一台全新 Linux 服务器，**When** 执行同一脚本，**Then** 得到等价的最小 K8S 环境（可复现）。

---

### 用户故事 3 - portal 独立管理后端统一纳管（Priority: P3）

用户通过一个**独立的管理后端（Java，CLI/API）**作为统一入口，管理：(a) Linux 服务器清单、(b) K8S 编排操作（发现 pod/service、启动 arthas MCP、暴露、纳管）、(c) 现有网关的后端配置。无需直接操作 K8S 命令或手改网关配置文件，即可完成"加服务器→发现→启 MCP→网关纳管→诊断"全链路。

**为何这个优先级**：portal 把分散的能力收敛为统一管理面，提升可用性与可治理性；但它建立在 P1 核心能力之上（编排逻辑可被 portal 调用），故 P3。MVP 不含 Web UI（CLI/API 优先）。

**独立测试**：经 portal 的 CLI/API 完成"添加一台 Linux 服务器→枚举其 K8S pod/service→选 pod 启 arthas MCP→网关纳管→经网关诊断"全链路，无需直接触达 K8S 或网关配置文件。

**验收场景**：

1. **Given** portal 已就绪，**When** 用户经 CLI/API 添加一台 Linux 服务器并枚举其 K8S 资源，**Then** 返回该服务器的 pod/service 清单。
2. **Given** 上述清单，**When** 用户经 CLI/API 选 pod 并触发启动 arthas MCP，**Then** 完成启动、暴露、网关纳管，且该 target 经网关可诊断。
3. **Given** portal，**When** 用户经 CLI/API 查询/管理现有网关的后端配置（逻辑名→地址映射），**Then** 配置变更可生效并被网关感知（复用 001 的热重载能力）。

---

### 用户故事 4 - Windows 直启 arthas MCP 并连接（Priority: P4）

在 Windows 开发机上，用户可一键启动一个 arthas MCP（attach 指定 JVM），并将其作为目标后端注册到网关，从而在 Windows 本地也能经网关使用 arthas 诊断，便于开发与调试。

**为何这个优先级**：Windows 直启是开发/调试便利工具，扩展目标来源（本地 JVM），但非核心 K8S 场景，故 P4。

**独立测试**：在 Windows 机器上一键启动 arthas MCP 并注册到网关，确认经网关对该 target 的诊断成功。

**验收场景**：

1. **Given** 一台 Windows 机器运行着目标 JVM，**When** 用户执行一键启动，**Then** 本地启动 arthas MCP 并注册到网关成为一个 target。
2. **Given** 该 target 已纳管，**When** 用户经网关对其诊断，**Then** 返回来自该 Windows 本地 JVM 的结果。

---

### 边缘情况

- 选中的目标 pod 内**未运行 JVM**或 JVM 不可被 arthas attach → 启动失败，返回明确错误（不静默成功，宪法原则五）。
- 目标 JVM **已存在 arthas 会话**或端口冲突 → 明确提示或幂等处理。
- **K8S API 不可达 / RBAC 权限不足** → 连接、枚举或编排失败，返回明确错误。
- 启动的 **arthas MCP pod 未就绪 / 崩溃** → 经 service 暴露但端点不可用，网关健康检查将其隔离并返回明确错误（宪法原则三）。
- **同一目标重复触发启动** → 幂等（复用既有）或明确提示已存在，不重复创建。
- **service 暴露端口被占用 / 端口范围受限** → 明确错误或自动选择可用端口。
- 目标 pod 被 **K8S 驱逐/重启/删除** → 经 service 暴露的端点变化，网关将该 target 标记不可用并返回明确错误，恢复后可重新纳管（宪法原则三）。
- **动态 target 与网关静态 target 命名冲突** → 明确错误或加限定，二者可共存可区分。
- **多 Linux 服务器 / 多集群** → MVP 以单服务器单集群起步，管理面预留多服务器扩展（见假设）。
- **离线构造脚本**在已存在 K8S 的机器上执行 → 幂等不破坏；在版本/架构不满足的机器上 → 明确前置依赖错误。

## 需求 *(mandatory)*

### 功能需求

**核心：目标 pod 启 arthas MCP + service 暴露 + 网关纳管（P1）**

> **架构原则（Q4 澄清）**：本节能力均以**最小化的原子 MCP 能力**形式暴露（细粒度、各自独立可调），由**大模型（Claude）编排组合**完成「启动→暴露→纳管→诊断」链路；系统**不内建过程式编排流程**。目标 pod 的选择在 P1 由人（经对话）指定、在 north-star 由大模型上下文推断。编排原子工具属独立于网关诊断聚合的 MCP 面（不污染宪法原则二）。

- **FR-001**: 系统必须能凭给定 Linux 服务器的访问方式连接其上的 K8S 环境，并枚举该集群的 pod 与 service（`k8s.list-pods` / `k8s.list-services`）。
- **FR-002**: 系统必须暴露「**对指定目标 pod 幂等供给 arthas MCP（`k8s.ensure-arthas-mcp`）**」的原子 MCP 能力——一次调用**原子地**完成：在目标 pod 内实例化 arthas MCP（自动命名 `{服务器名}-{Pod名}`、自动 attach 该 pod 内 JVM）+ 通过 K8S service（NodePort）暴露一个可达端点 + 内部健康检查确认就绪 + 动态注册到网关；任一子步失败则整体失败且不注册（不污染注册表）；已就绪则零副作用复用。目标 pod 的选择由编排者（大模型；P1 经人指定、north-star 经上下文推断）完成，系统不内建过程式选择/启动流程。
- **FR-003**: 启动的 arthas MCP 必须能诊断**被选中目标 pod 内的 JVM**（诊断对象明确归属被选 pod——`ensure` 经 kubectl exec 进入目标 pod 注入 arthas，attach 该 pod 的 JVM PID）。
- **FR-004**: （`ensure` 内部子行为）启动的 arthas MCP 必须通过 K8S service 对外暴露一个可访问的网络端点（经 NodePort 的节点 IP/端口可达）；arthas MCP 须绑 pod 网络接口（`0.0.0.0`）方经 NodePort 远程可达。
- **FR-005**: （`ensure` 内部子行为）arthas MCP 启动并就绪后，系统必须将其作为新的目标后端**动态注册到现有 arthas MCP 网关**，使网关 target 列表新增该目标并立即可用（与 001 FR-009 动态目标更新一致，符合宪法原则二/三）。
- **FR-006**: 经 service 暴露并由网关纳管后，对该 target 的诊断调用必须经网关按 target 路由、结果原样透传（透明无损，宪法原则二）。

**K8S 实验环境离线脚本化（P2）**

- **FR-007**: 必须提供脚本化的方式，在一台给定的 Linux 服务器上**离线**（无需联网拉取外部依赖）构造一个最小可用的 K8S 实验环境，可全新机器复现。
- **FR-008**: 构造脚本必须**幂等可重复**——对已存在环境重新执行不破坏既有状态；并在可接受的时间内完成。

**portal 独立管理后端（P3）**

- **FR-009**: 必须提供一个**独立的管理后端**（与现有网关并列），把上述**原子能力**（发现/实例化/暴露/纳管）以 API+CLI 暴露给**人类直接操作**（非 agentic 入口，与原子 MCP 工具并存），并管理：(a) Linux 服务器清单、(c) 现有网关的后端配置。
- **FR-010**: 管理后端必须以 **API + 命令行（CLI）**方式提供上述能力；MVP 不提供 Web UI。
- **FR-011**: 管理后端的**核心逻辑必须以 Java 实现**（宪法原则六）；K8S 清单/编排脚本仅作辅助，不承载核心功能逻辑。

**Windows 直启 agent（P4）**

- **FR-012**: 必须支持在 Windows 机器上一键启动一个 arthas MCP（attach 指定 JVM），并将其作为目标后端注册到网关，供经网关诊断使用。

**贯穿：可观测与故障韧性（宪法原则三/五）**

- **FR-013**: 动态注册的 target 后端必须纳入网关既有的**健康监控与故障隔离**；该 target 不可达时，网关对其他 target 的诊断不受影响，对该 target 返回明确错误。
- **FR-014**: 所有编排操作（发现、启动、暴露、注册、移除）必须**结构化可追溯**：记录目标 pod、操作类型、结果状态（宪法原则五）。

### 关键实体

- **Linux 主机（Linux Host）**：一台被管理的 Linux 服务器。关键属性：标识、访问方式（凭据/连接）、其上的 K8S 环境。
- **K8S 集群（K8S Cluster）**：Linux 主机上运行的 K8S 环境，含 pod/service 集合。
- **目标 Pod（Target Pod）**：被选中启动 arthas 诊断的业务 pod。关键属性：名称、命名空间、是否运行可诊断 JVM。
- **arthas MCP 实例（arthas MCP Instance）**：为目标 pod 启动的 arthas MCP 诊断服务。关键属性：所属目标 pod、就绪状态。
- **暴露服务（Exposed Service）**：把 arthas MCP 端点对外暴露的 K8S service（**NodePort**）。关键属性：可访问端点（节点 IP + NodePort → pod 内 arthas MCP 端口）、Service selector/label。
- **动态目标后端（Dynamic Target Backend）**：经暴露、被网关动态纳管的 arthas MCP，作为网关 target（与 001 静态配置 target 共存可区分，带 `source=DYNAMIC` 来源标记）。其逻辑名**自动派生** = `{服务器名}-{Pod名}`（确定性、非人工命名）。
- **供给记录（Orchestration Record）**：一次 `ensure-arthas-mcp` 供给的结构化可观测记录（宪法原则五）。关键属性：逻辑名、来源（server/pod/namespace）、暴露端点（mcpUrl/serviceRef）、状态（ensuring/ready/reused/failed）、供给时间。
- **管理后端（Management Portal）**：独立 Java 管理服务，统一管主机/集群/编排/网关配置。

## 成功标准 *(mandatory)*

### 可度量结果

- **SC-001**: 给定一台已具备 K8S 的 Linux 服务器，用户选定一个运行 JVM 的目标 pod 后，在 **5 分钟内**完成"启动 arthas MCP + service 暴露 + 网关动态纳管"，并经网关对该 pod 完成一次诊断，结果来自该 pod 的 JVM。
- **SC-002**: 在一台**全新（仅装好 OS）的 Linux 服务器**上离线执行构造脚本，在可接受的时间内得到可用的最小 K8S 实验环境，并立即用于 SC-001 全链路；同一脚本换一台新机器可复现等价环境。
- **SC-003**: 当某动态 target 对应的 arthas MCP pod 被 K8S 重启/驱逐，网关在 **30 秒内**将其标记为不可用并对调用返回**明确错误**，不影响其他 target；环境恢复后可重新纳管。
- **SC-004**: 经 portal（CLI/API）可完成"添加 Linux 服务器 → 发现 pod/service → 启动 arthas MCP → 网关纳管 → 经网关诊断"**全链路**，期间无需直接操作 K8S 或手改网关配置文件。
- **SC-005**: 在 Windows 机器上可**一键**启动 arthas MCP 并注册到网关，经网关对其诊断成功。
- **SC-006**: 动态纳管的 target 与网关**原有静态 target 共存**，互不干扰，命名可区分；经网关对二者的诊断各自路由正确、结果原样透传。

## 假设

- **实验级/MVP 环境**：单 Linux 服务器、单 K8S 集群、受控内网、无认证（沿用 001 MVP 安全假设）；多服务器/多集群预留接口，不在 MVP 范围。
- 目标 pod 内运行**可被 arthas attach 的 JVM**；部署拓扑已由 [设计](../../docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md) 决策 #5 定为 **kubectl exec 进入目标 pod 注入 arthas**（attach 该 pod 内 JVM PID，非独立 pod）——目标 pod 须含 shell + java + JVM；distroless/ephemeral 无 shell pod 超出 MVP。本 spec 约束"诊断对象 = 被选 pod 的 JVM"与"经 NodePort service 暴露"。
- **复用现有网关（001/002）**作为诊断聚合层；本特性不改变网关对外 MCP 行为（宪法原则一/二），仅新增"动态 target 来源"。
- portal 与网关均为 **Java**（宪法原则六）；K8S 构造脚本/清单为辅助（非核心逻辑）；**Web UI 列为后续增强**，不在 MVP。
- **模块化单体打包**：编排核心、portal、网关聚合为**微服务式边界的独立 Java 模块**，但最终编译为**一个完整的可部署包**；通过**依赖管理**（Maven 模块排除 / optional 依赖 / starter 模式）可随时排除不需要的功能模块（默认全量、按需裁剪）。故 P1 即构建可复用的 Java 编排核心模块，portal（P3）为同包内并列模块包裹该核心，而非独立部署的微服务。
- **north-star（非 MVP）**：终极愿景是"大模型经对话上下文推断所需目标并触发自动初始化"的 agentic 流（人只提供 Linux 服务器，无需人工选 pod）；P1 先交付"自动实例化+自动命名+自动纳管"的供给机制（人显式指定目标 pod），agentic 推断编排留后续增强（见 Clarifications Q3）。
- Windows agent 复用 arthas 与网关客户端能力，作为开发/调试便利工具（语言选型留 plan，倾向 Java CLI 以符原则六）。
- K8S 实验环境采用**轻量发行版**（具体选型留 plan，宪法原则八先决研究：须先研读候选发行版的离线安装、资源占用与 pod/service 行为）。
