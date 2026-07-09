# 重要说明（语言规范）

- **本项目的所有文档描述必须使用中文编写。**

  适用范围包括但不限于：`.specify/memory/constitution.md`、`spec.md`、`plan.md`、`tasks.md`、`research.md`、`data-model.md`、`README`、`quickstart`，以及所有由本项目生成的说明文档与代码注释。

  技术专有名词（如 MCP、arthas、JSON-RPC、Claude Code、stdio 等）可保留英文原文，但所有叙述性、描述性内容必须为中文。

## 工作流规范

> **总原则（职责严格分离）**：SuperPower **仅用于设计阶段的头脑风暴**；**代码实施走 spec-kit 的 SDD（规格驱动开发）流程**，**不得**改用 SuperPower 的实施类技能。

- **设计阶段走 SuperPower 头脑风暴。** 任何涉及“方案设计 / 技术调研 / 报告撰写 / 选型”的工作，动手之前**必须**先调用 `superpowers:brainstorming` 与用户充分沟通，产出归档于 `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`。未完成需求沟通，不得直接进入产出。
- **代码实施走 spec-kit SDD。** 功能代码落地**必须**通过 spec-kit 的 SDD 流程（`/speckit-implement`，以 `specs/<feature>/tasks.md` 为权威任务清单、**测试任务先于实现任务**推进），**不得**改用 SuperPower 的实施类技能。TDD 红-绿-重构（宪法原则七）由 spec-kit SDD 的「测试先于实现」落地——先写失败测试（红），再实现至通过（绿），再重构；不得“先写实现、后补测试”。
- **设计与实施时必须参考 SuperPower 设计文档**：`docs/superpowers/specs/`。

### SuperPower 技能白名单（仅以下允许，其余一律禁用）

`using-superpowers` 是技能发现基建（其“凡事皆用技能”的推力被本白名单约束），工作流技能**仅允许** `superpowers:brainstorming`（设计阶段）`superpowers:test-driven-development`(代码实施阶段)。以下 SuperPower 技能**禁用**，不得调用：

- **实施生命周期类**（与 spec-kit SDD 冲突）：`writing-plans`、`executing-plans`、`subagent-driven-development`、`dispatching-parallel-agents`。
- **Git/分支生命周期类**（git 由用户手动管控、完成由 spec-kit 收尾）：`using-git-worktrees`、`finishing-a-development-branch`。
- **代码评审流**（不在本项目范围）：`requesting-code-review`、`receiving-code-review`。
- **质量纪律类**（精神已由本文件「证据驱动，禁止臆测 / 透明记录 / 同一问题连续失败 3 次暂停」覆盖，无需再借技能）：`systematic-debugging`、`verification-before-completion`。
- **技能创作**（本项目不创作 SuperPower 技能）：`writing-skills`。
- **TDD 必须基于真实环境，禁止用桩冒充成功（不可妥协）。** 测试的真实性是硬约束，具体两条：
  1. **真实环境**：每次测试**必须**至少启动一个**真实的 arthas MCP** 与一个**真实的业务服务**；诊断数据由**触发业务服务的真实调用**产生（如调用业务接口让目标方法执行，再由 arthas 工具捕获真实调用、返回真实诊断），**不得**用桩（WireMock / Mock 等）模拟 arthas 的正常成功响应。故障类用例（401 / 超时 / 熔断 / 并发越界）用**真实故障条件**实现（停掉真实后端=不可达、错误 token=arthas 真实 401、业务方法 `Thread.sleep`=慢响应、真实发起越界并发=arthas 真实 INVALID_PARAMS），同样不得用桩。
  2. **驱动分层**：除"网关健康检查"（Actuator 端点）外，所有对网关的 MCP 调用**不得**用 curl 等方式裸打——工具**可用性**验证用**真实 Claude Code** 走 MCP（逐工具冒烟，仅验"能调通"，不做结果一致性比对）；**结果一致性**与**双侧协议契约**验证用**官方 MCP Java SDK client**（合规 MCP 客户端、走标准 MCP 协议、确定性断言）。本条与宪法原则四（双侧契约）、原则七（TDD）一致。

## 工程实践规范

### 证据驱动，禁止臆测

- 任何结论（行为判断、选型理由、问题归因）**必须**援引代码或文档证据，不得假设或猜测。
- 与宪法原则八一致：实现前先研读既有代码/文档在 ./reference 文件夹中（尤其 arthas MCP 实现），吸收现有经验。
- **查询代码优先用 `codebase-memory-mcp`**：本项目已建知识图谱（节点含调用/使用/语义关系 + 复杂度/循环热点信号）。凡查代码——找定义/实现/引用、追调用者与被调用者、看依赖与调用链、定位架构模块与热点路径——**优先**用 `codebase-memory-mcp`（`search_graph` 全文检索 / `search_code` grep+图增强 / `trace_path` 调用链 / `get_architecture` 架构聚类 / `query_graph` Cypher），而非裸 `grep`/`glob`：图能跨文件去重、按结构重要性排序、还原多跳调用链，效率与完整度优于线性文本搜索。**已知限制**：`search_code` 的原文摘录在 Windows 上以 GBK 解码中文会乱码——查中文文档/注释原文改用 `Read` 工具直接读文件，不走图谱 grep 管道（代码符号为英文，不受影响）。

### 先研究、再规划、后实现

- 编码前先分析上下文与相似实现，确认依赖、输入输出与测试约定。
- 进入实现前完成"充分性自查"：能否定义清晰的接口契约？是否理解技术选型理由？是否识别主要风险（并发 / 边界 / 性能）？是否知道如何验证？
- 实现新特性前，至少参考 3 个相似实现，理解其设计与复用方式。

### 小步迭代，保持可用

- 小步修改，每次变更保持可编译、可验证；每次提交处于可用状态。
- 发现缺陷优先修复，再扩展新功能。
- 同一问题连续失败 3 次必须暂停并重新评估策略，不得盲目重试。

### 简单性与一致性

- 单一责任；禁止过早抽象（重复出现 3 次以上再考虑通用化）；可读性优先，拒绝炫技。
- 沿用项目既有模式、约定、构建系统、测试框架与格式化/检查设置，不私自新增脚本或工具链。
- 优先复用既有库与官方 SDK（宪法：优先官方 MCP Java SDK）。

### 透明记录

- 如实报告执行结果，包括失败与问题，不得掩饰。
- 代码注释与文档使用中文，描述意图、约束与使用方式（与上方语言规范一致）。

<!-- SPECKIT START -->
如需了解本项目当前使用的技术、项目结构、shell 命令与其他重要信息，请阅读当前实现计划：
`specs/005-k8s-orchestration-iteration/plan.md`（特性：K8S 编排能力迭代——① ensure 的 NodePort 暴露从「新建独立 Service」改为「复用带 `arthas-mcp-gateway/target` label 的现有 Service（patch type+端口），找不到回退新建」；② 后端配置 K8S 场景：新增 K8S Host 配置实体（`arthas-gateway.k8s-hosts`，远端 Linux 入口），BackendConfig 加 `k8sHost`+`pod`（K8S 模式，与 url 互斥），首次路由懒 resolve（`BackendResolver` 接口 gateway-core 定义 / `K8sBackendResolver` orchestration 实现）；③ JDK 适配 SPI（`ArthasLauncher` 策略接口 + `DefaultArthasLauncher` 默认 + 用户 `@Primary` 实现定制 javaPath/完整命令模板 + test fixture 真实实现 TDD）；零 gateway-core K8S 依赖不变（ArchUnit 守护），003 既有契约全部不破）。
配套产出：`research.md`（R1–R9）、`data-model.md`（K8sHost/BackendConfig 增量/BackendResolver/ArthasLauncher/LaunchContext）、`contracts/orchestration-iteration-invariants.md`（K-ENS-10/11/12 + INV-K8SHOST-* + INV-LAUNCHER-*）、`quickstart.md`；规格见 `spec.md`；设计决策见 `docs/superpowers/specs/2026-07-10-k8s-orchestration-iteration-design.md`。
上一特性基线（回归对照）：`specs/004-portal-backend-management/`（更早 `specs/003-k8s-arthas-mcp-launch/`、`specs/002-code-review-remediation/`、`specs/001-arthas-mcp-gateway/`）。
<!-- SPECKIT END -->
