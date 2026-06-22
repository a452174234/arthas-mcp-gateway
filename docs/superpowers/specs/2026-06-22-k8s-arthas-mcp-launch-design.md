# K8S 目标 arthas MCP 启动与纳管 — 设计文档

| 项目 | 内容 |
|------|------|
| 主题 | 给定 K8S 目标 pod，在其内拉起 arthas MCP、经 service 暴露、动态纳管到现有网关、并经网关诊断的完整设计 |
| 日期 | 2026-06-22 |
| 特性 | `specs/003-k8s-arthas-mcp-launch` |
| 依据 | [特性 spec](../../../specs/003-k8s-arthas-mcp-launch/spec.md)、[宪法](../../../.specify/memory/constitution.md) v1.2.0、[001 数据模型](../../../specs/001-arthas-mcp-gateway/data-model.md)、[arthas 测试夹具设计](./2026-06-20-arthas-test-fixture-design.md) |
| 设计阶段 | SuperPower 头脑风暴产出（CLAUDE.md：设计阶段走 brainstorming） |
| 关键决策 | **模块化单体 + 单 MCP 端点**（B+模块解耦）；**kubectl exec 拓扑**（arthas 注入目标 pod 的 JVM）；**程序化注册 API + 文件静态种子**（动态 target 入注册表）；**3 个 MCP 工具**（2 发现 + 1 幂等 `ensure-arthas-mcp`）；**NodePort** 暴露 |

---

## 一、背景与范围

### 1.1 问题

现有 arthas MCP 网关（001/002）已能聚合**静态配置**的后端（`config/backends.yaml` 热重载）。新需求：给定一台已具备 K8S 的 Linux 服务器，对**指定目标 pod** 按需启动一个 arthas MCP、对外暴露端点、动态纳管到网关，从而经网关诊断该 pod 内的 JVM——把"启动 + 暴露 + 纳管 + 使用"闭环跑通。

核心张力：**动态 target**（pod 旋生旋灭）如何进入一个只认静态配置文件的网关注册表；以及"谁来编排"——本设计采纳 **原子 MCP 工具 + 大模型编排**（spec Q4），系统不内建过程式编排流程。

### 1.2 范围（P1 MVP）

- **在范围内（P1）**：① 枚举 pod/service；② 对指定 pod 幂等拉起 arthas MCP + 暴露 + 注册（`ensure-arthas-mcp`）；③ 程序化动态注册进网关；④ 经网关诊断（复用 001 路由）。**目标 pod 由人（经对话）指定。**
- **后置（非本特性）**：north-star（大模型经上下文自动推断 pod 并触发全链路）、portal（P3）、Windows agent（P4）、K8S 离线构造脚本（P2）。

> P1 与 north-star **共享供给机制**，差异仅在"谁指定目标"——P1 人指定、north-star LLM 推断（spec Q3）。

---

## 二、核心设计决策（澄清产物汇总）

| # | 决策 | 结论 | 出处 |
|---|------|------|------|
| 1 | 架构形态 | **模块化单体**：编排核心 / portal / 网关聚合为微服务式边界的独立 Java 模块，编译为一个可部署包；依赖管理可裁剪 | spec Q1 |
| 2 | 动态 target 命名 | 自动派生 `{服务器名}-{Pod名}`，确定性、非人工命名 | spec Q2 |
| 3 | 编排者 | 大模型 Claude 组合原子工具；系统不内建过程式编排流程 | spec Q4 |
| 4 | 分期 | P1=供给"机制"（人指定 pod）；north-star=LLM 上下文推断 | spec Q3 |
| 5 | K8S 部署拓扑 | **kubectl exec 进入目标容器**拉起 arthas（注入目标 pod 的 JVM PID）；目标须有 shell+java+JVM | 设计澄清 Q1 |
| 6 | MCP 面归属 | **B + 模块化解耦**：单 MCP 端点（Claude 单入口），但 gateway-core / orchestration 各为独立模块、各自纯粹 | 设计澄清 Q2 |
| 7 | 注册机制 | **B：程序化 `register/unregister` API + `backends.yaml` 静态种子**，合并为统一快照 | 设计澄清 Q3 |
| 8 | 工具粒度 | **3 个工具**：`list-pods` / `list-services` / 幂等 `ensure-arthas-mcp`（合并 instantiate+expose+register+健康检查） | 设计澄清 Q4 |
| 9 | 暴露方式 | **NodePort**（外部可达；网关可集群外运行） | 设计澄清 Q4 |

---

## 三、架构：模块化单体 + 单 MCP 端点（决策 #1/#6）

### 3.1 模块图

```
arthas-gateway-platform/        ← 一个 Spring Boot 应用、一个可部署包、一个 MCP 端点（Claude 单入口）
├── gateway-core/     (模块)    ← 001/002 的 arthas 聚合逻辑，【纯粹】受宪法原则二约束
│     └─ @Tool：31 arthas 路由工具 + 4 网关自有工具（list-targets / task-*）
├── orchestration/    (模块,新) ← K8S 原子能力，【纯粹】不碰 arthas 诊断语义
│     └─ @Tool：k8s.list-pods / k8s.list-services / k8s.ensure-arthas-mcp
├── app/  (bootstrap 模块)      ← 把两组 @Tool 经 Spring DI 装配进同一 MCP server，tools/list = 全集
├── portal/           (模块,后置 P3)
└── （Maven 依赖管理可排除 orchestration/portal，实现裁剪）
```

两组 `@Tool` bean 由 `app` 模块的 Spring 装配进**同一个** MCP server；Claude 看到**一个**端点、一份并集 `tools/list`。编排工具用 `k8s.` 前缀区分类别（呼应原则二允许的 namespace 消歧）。

### 3.2 原则二为何不被破坏（重要，纠正前期措辞）

宪法原则二管的是 **arthas 聚合这条路径**（定义静态摘抄、结果原样透传、映射确定性可发现），**并不禁止同一 MCP 端点还挂非 arthas 工具**。证据：001/002 现状端点上已有 4 个**非 arthas** 网关自有工具（`list-targets` / `task-*`），宪法一直容忍。

故"端点多挂一组编排工具"本身不破原则二。本设计的真正纪律是**内聚性**——靠**模块边界**保证：`gateway-core` 模块内不含任何 K8S 编排逻辑，可独立编译/测试，原则二落在该模块内、不受编排影响；`orchestration` 模块同样不碰 arthas 诊断语义。两模块经 `app` 装配，是**组合**而非**耦合**。

> 动态 target 同样守原则二：诊断调用仍走 `gateway-core` 既有路由管线，结果原样透传；动态 target 与静态 target 在 `list-targets`（读同一注册表）中可发现、命名可区分（§六 source 标记）。

---

## 四、MCP 工具面（3 工具，决策 #8）

| 工具 | 用途 | LLM 是否在调用前后推理 |
|------|------|------------------------|
| `k8s.list-pods` | 枚举集群 pod（返回名称/命名空间/是否含可诊断 JVM） | **是**——north-star 据上下文从中推断目标 |
| `k8s.list-services` | 枚举集群 service | **是** |
| `k8s.ensure-arthas-mcp` | 幂等供给：装 arthas + 启动 + NodePort 暴露 + 注册 + 健康检查；已就绪则复用 | **否**——单步机械操作，对 Claude 是黑盒 |

### 4.1 `k8s.ensure-arthas-mcp` 契约

```
输入：server（服务器名）、pod（目标 pod 名）[、namespace，默认 default]
行为（幂等）：
  1. 查注册表：{server}-{pod} 已注册 且 后端健康？ → 直接复用，返回该 target（零副作用）
  2. 否则：kubectl exec 进 pod
       ├─ 无 arthas 则安装（kubectl cp tools/arthas-boot.jar 进 pod，或 pod 内下载）
       ├─ 定位目标 JVM PID（pod 内 jps / 进程探测）
       ├─ 启 arthas MCP：java -jar arthas-boot.jar <pid> --attach-only
       │     --http-port <mcpPort> --target-ip 0.0.0.0   ← 注：远程可达须绑 0.0.0.0，非 loopback
       ├─ 建 NodePort Service 暴露 mcpPort → 可达 URL = http://<nodeIP>:<nodePort>（arthas MCP 根 URL，无 /mcp）
       ├─ 内部健康检查（轮询 initialize/listTools，确认 arthas MCP 真正就绪）—— 无感，不暴露给 Claude
       └─ 程序化注册 {server}-{pod} → URL 进 BackendRegistry
输出：{server}-{pod}（Claude 之后用它作 target 调诊断工具）
```

**幂等细则**：
- 注册表有 `{server}-{pod}` 且后端健康 → 复用，不重装。
- 注册表有但后端已死（pod 重启 / arthas 挂） → 重供给（重装 / 重 attach / 重暴露），更新注册表条目。
- **原子性**：供给任一子步失败（装失败 / attach 超时 / NodePort 分配失败 / 健康检查不过）→ 返失败，**不注册**（不污染注册表）。

**技术依据**（吸收 arthas 测试夹具设计 §5）：
- arthas 4.3.0 MCP 端点 = **根 URL**（无 `/mcp`），经 `--attach-only --http-port` 启动；arthas-boot.jar 为静态工具文件（`tools/arthas-boot.jar`，不入 pom）。
- **关键差异（本地夹具 vs K8S 远程）**：本地夹具用 `--target-ip 127.0.0.1`（loopback，仅本机）；K8S 经 NodePort 远程可达，arthas MCP **必须绑 `0.0.0.0`**（pod 网络接口），否则 NodePort 路由不到。此项须 plan 首测确认 arthas-boot.jar 的绑定参数实际行为。

### 4.2 与 Q4 的一致性（合并 ≠ 过程式编排）

判断"原子工具该不该合并"的标准 = **LLM 是否需要在两步之间做推理决策**：
- `list-pods` / `list-services` ↔ 「选哪个 pod」之间，**LLM 要推理** → 保持独立。
- `装 → 启 → 暴露 → 注册` 之间，**无 LLM 决策点**，是一条机械的"确保就绪"操作 → 合并成 1 个工具才对。

故宏流程（枚举 → 供给 → 诊断）仍由 Claude 编排；合并只发生在"供给"步骤内部。Q4"系统不内建过程式编排"的精神保留。

---

## 五、端到端流程（Claude 编排）

```
人提供：K8S 集群访问（kubeconfig/context）+ 服务器名
  │
  ▼  （P1 由人指定 pod；north-star 由 Claude 上下文推断）
[1] k8s.list-pods / k8s.list-services          枚举候选（Claude 据上下文选目标 pod）
[2] k8s.ensure-arthas-mcp(server, pod)         幂等供给黑盒：装+启+NodePort暴露+健康检查+注册
                                                  → 返回 {server}-{pod}
[3] watch/trace/sc/...(target={server}-{pod})  经 gateway-core 既有路由管线诊断（复用 001/002）
```

系统只暴露 [1][2] 的原子能力 + 既有诊断工具；**[1]→[2]→[3] 的串联由 Claude 完成**，系统不内建。

---

## 六、数据模型增量（在 001 data-model 之上）

### 6.1 `BackendConfig` 增来源标记

| 新字段 | 类型 | 说明 |
|--------|------|------|
| `source` | enum | `STATIC`（`backends.yaml` 种子）/ `DYNAMIC`（程序化 API 注册）；其余字段不变，复用 001 全部校验 |

### 6.2 `BackendRegistry` 增写入路径

- 新增 `register(BackendConfig)` / `unregister(name)`：程序化写入动态 target。
- 快照 = **静态种子 ∪ 动态注册**，经既有 `AtomicReference` 原子替换（复用 001 热重载的原子性保证：in-flight 调用持有旧 Entry 不受影响，新调用才见新表）。
- **命名冲突策略**：动态注册名若与静态种子名冲突 → 拒绝并报明确错误（保护静态配置）；动态名之间冲突 → 幂等以"健康复用"优先，URL 不同则报错。

### 6.3 `OrchestrationRecord`（新，可观测性 — 原则五）

| 字段 | 说明 |
|------|------|
| `logicalName` | `{server}-{pod}` |
| `server` / `pod` / `namespace` | 供给来源 |
| `mcpUrl` / `serviceRef` | 暴露端点 + NodePort Service 引用 |
| `status` | `ensuring` / `ready` / `reused` / `failed` |
| `createdAt` | 供给时间（传入，非进程内取时） |

记录"谁在哪个 pod 上拉起了 arthas"，供运维追溯（原则五：网关必须可被诊断）。

---

## 七、宪法保真

| 原则 | 本设计如何满足 |
|------|----------------|
| **一 MCP 规范** | 经 NodePort 暴露的仍是标准 MCP（arthas 原生）；网关侧 `app` 装配的 MCP server 遵守 initialize/能力协商/JSON-RPC 2.0 |
| **二 透明无损聚合** | 动态 target 诊断仍走 `gateway-core` 既有路由、结果原样透传；定义仍静态摘抄自 arthas；`gateway-core` 模块不含编排逻辑，原则二落点不受污染（§3.2） |
| **三 局部故障韧性** | 动态 target 纳入既有健康监控/熔断/故障隔离（复用 001）；pod 消亡 → 该 target 隔离，不影响其他 target；`ensure` 的"重供给"修复可恢复纳管 |
| **四 双侧契约** | 动态 target 经网关的诊断须有双侧契约测试覆盖（gateway↔arthas 动态 target、gateway↔Claude Code）；测试先于实现（原则七） |
| **五 可观测性** | `OrchestrationRecord` 结构化追溯供给；诊断调用复用 001 结构化日志（tool/target/isError/duration）；K8S 真实错误（无权限/不可达）显式传播 |
| **六 Java 主力** | 编排核心、网关、portal 均 Java；K8S 清单/脚本仅辅助 |
| **七 TDD** | 波次 A/B/C 全程真实环境、零桩（§八） |
| **八 先决研究** | K8S 轻量发行版、arthas-boot.jar 远程绑定参数、NodePort/Service selector 策略——须 plan Phase 0 先决研究 |

---

## 八、测试策略（宪法原则七 TDD + CLAUDE.md 真实性硬约束）

**真实性约束**（CLAUDE.md「零桩、真实环境」）：成功路径不得用桩模拟 arthas/K8S 成功响应；故障用真实故障条件。沿用 arthas 测试夹具设计的波次范式。

### 波次 A — 纯逻辑（surefire，无 K8S）

- 命名规则 `{server}-{pod}` 派生。
- `BackendRegistry` 写入路径：register/unregister、静态∪动态合并、命名冲突策略。
- 配置解析（`BackendConfig.source`）。

### 波次 B — 真实 K8S 夹具

- **真实轻量集群**：候选 `kind`/`k3d`（本机）或真实 on-debian 服务器（192.168.31.92，经 SSH 免密）。**K8S 发行版由 plan Phase 0 定**（原则八）。
- **真实目标 pod**：含 shell+java+JVM 的业务 pod（可复用 001 `DemoBusinessApp` 打成镜像入集群）。
- **真实 arthas MCP 注入**：`ensure-arthas-mcp` 经 kubectl exec 真实拉起、真实 attach 目标 JVM PID、真实 NodePort 暴露。
- **真实诊断**：触发业务方法 → 经网关 watch/trace 捕获真实调用。
- **驱动分层**：工具可用性走真实 Claude Code MCP（冒烟）；结果一致性 + 双侧契约走官方 MCP Java SDK client（确定性断言）。
- **故障用真实条件**：停 pod=不可达、无 shell pod=真实 ensure 失败、kubeconfig 无 exec 权限=真实 403、arthas MCP 绑 loopback=真实不可达（验证 §4.1 的 `0.0.0.0` 要求）。

### 波次 C — 端到端

- `ensure-arthas-mcp → 诊断` 全链路真实：人指定 pod → Claude 编排枚举+供给+诊断 → 结果来自该 pod JVM。

---

## 九、风险与对冲

| 风险 | 对冲 |
|------|------|
| arthas MCP 远程绑定（`--target-ip 0.0.0.0`）实际行为未知 | plan Phase 0 首测确认 arthas-boot.jar 绑定参数；波次 B 失败用例覆盖"绑 loopback 不可达" |
| NodePort + 单 pod 的 Service selector 策略（业务 pod 无唯一 label） | plan 定 selector 策略（打 label / 显式 targetRef）；记录于 research.md |
| 动态注册与热重载并发 | 复用 001 `AtomicReference` 原子替换；静态∪动态合并的单测断言线程安全 |
| 目标 pod 无 shell/java/JVM（ephemeral/distroless） | 明确 scope 限制（决策 #5）：此类 pod 超出 MVP，`ensure` 返明确失败 |
| 多 JVM pod（PID 选择歧义） | MVP 假设单目标 JVM/pod；多 JVM 场景后置，`ensure` 需增 JVM 选择参数 |
| K8S 客户端选型 | 留 plan Phase 0（官方 `fabric8` / `kubernetes-java`，符原则"优先官方 SDK"） |

---

## 十、与 spec / plan 的关系（含 spec reconcile 待办）

- 本文档是**设计阶段（brainstorming）产出**，记录九项核心决策与架构。
- **实施走 spec-kit SDD**：`/speckit-plan`（Phase 0 先决研究 → research.md）→ `/speckit-tasks`（tasks.md，测试先于实现）→ `/speckit-implement`。**不走** SuperPower `writing-plans`（CLAUDE.md 工作流规范白名单约束）。
- **spec reconcile 待办**（spec Q4「粒度待 plan/design 确认」现已由本设计落实，进入 plan 前须同步 spec.md）：
  - spec Clarifications Q4 的「原子工具集」由 5 工具（list-pods/list-services/instantiate/expose/register）**收敛为 3 工具**（list-pods/list-services/ensure-arthas-mcp）——合并 instantiate+expose+register+健康检查为幂等 `ensure-arthas-mcp`。
  - spec FR-002 措辞须从「实例化的原子能力」改为「幂等供给（ensure-arthas-mcp，含实例化+暴露+注册+健康检查）」；FR-004（service 暴露）/ FR-005（动态注册）降为 `ensure` 内部保证的子行为（仍是有效需求，但不再是独立工具）。
  - spec「关键实体」可补 `OrchestrationRecord`；`backends.yaml` 暴露方式补 NodePort（决策 #9）。
