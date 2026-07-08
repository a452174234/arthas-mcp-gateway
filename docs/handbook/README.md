# arthas MCP 网关 — 完整技术手册

> 本手册结合 spec（001/002/003/004）与代码实现，事无巨细地描绘 arthas MCP 网关的**技术栈、能力、代码实现细节**。每条结论援引 `file_path:line_number`（可点击），代码片段为当前实现实读摘录。
>
> **版本基线**：0.1.0-SNAPSHOT | JDK 21 | Spring Boot 4.1.0 | Spring AI 2.0.0 | MCP Java SDK 2.0.0 | 最后更新 2026-07-09
>
> **手册组织**（核心解读 Part 1–11 + 工程附录 Part 13–19，共 18 个文件，总约 2.0 MB）：
>
### 核心解读（Part 1–11，逐类逐方法 + 代码片段 + 设计解读）
>
| Part | 文件 | 主题 |
|------|------|------|
| 一 | [part1-overview.md](./part1-overview.md) | 项目概述、设计哲学（宪法 8 原则）、能力全景、技术栈、架构总览 |
| 二 | [part2-core.md](./part2-core.md) | 001 诊断聚合核心：MCP 装配、工具注册、路由、BackendEntry 统一拦截层、注册表热重载、异步任务、自有工具、认证、可观测 |
| 三 | [part3-resilience.md](./part3-resilience.md) | 002 韧性：熔断状态机、三层限流、超时、故障隔离、错误结构化、15 项整改、双侧契约测试、ArchUnit |
| 四 | [part4-k8s.md](./part4-k8s.md) | 003 K8S 编排（**重点**）：远端调用场景、ensure 6 步原子序列、NodePort、动态注册、3 工具契约与故障矩阵、测试床、4 决策 |
| 五 | [part5-portal.md](./part5-portal.md) | 004 portal：后端 CRUD/任务列表/导出、异常/开关、Vue 3 SPA、SpaConfig、frontend-maven-plugin、12 不变量 |
| 六 | [part6-config-appendix.md](./part6-config-appendix.md) | application.yml 全字段、38 工具清单、错误码/reason 速查、关键参数、文件索引 |
| 七 | [part7-tools.md](./part7-tools.md) | 38 工具逐个详解（schema/用法/示例/路由/故障）+ Claude Code 集成示例 |
| 八 | [part8-tests.md](./part8-tests.md) | 测试用例全清单（单测/契约 IT/集成/架构，逐类目的+断言+真实故障条件） |
| 九 | [part9-decisions.md](./part9-decisions.md) | 设计决策全（001/002/003/004 research 决策逐条 + brainstorming 要点 + 选型对比） |
| 十 | [part10-source.md](./part10-source.md) | 关键类源码摘录 + 逐段解读（ToolsCallRouter/BackendEntry/CircuitBreaker/AsyncTaskExecutor/ArthasProvisioner/...） |
| 十一 | [part11-contracts.md](./part11-contracts.md) | 完整契约参考（S-*/C-*/G-*/K-ENS-*/INV-* 全部断言逐条 + 断言→测试→代码三向追溯） |
>
### 工程附录（Part 13–19，完整源码与文档摘录，事无巨细的代码级参考）
>
| Part | 文件 | 主题 |
|------|------|------|
| 十三 | [part13-main-source.md](./part13-main-source.md) | src/main/java 全部主代码完整源码 |
| 十四 | [part14-test-source.md](./part14-test-source.md) | src/test/java 全部测试代码完整源码 |
| 十五 | [part15-config-scripts-web.md](./part15-config-scripts-web.md) | pom/yml 配置 + smoke 脚本 + web 前端源码 |
| 十六 | [part16-specs.md](./part16-specs.md) | 4 特性 spec-kit SDD 全套（spec/plan/research/data-model/contracts/quickstart/tasks） |
| 十七 | [part17-design-docs.md](./part17-design-docs.md) | 宪法 + brainstorming 设计文档 + 代码评审报告 + 启动/夹具文档 |
| 十八 | [part18-reference-k8s.md](./part18-reference-k8s.md) | 上游 arthas 文档摘抄 + K8S 测试床脚本/清单 + k3s 离线脚本 |
| 十九 | [part19-readme-misc.md](./part19-readme-misc.md) | README + CLAUDE.md + 冒烟报告 + spec-kit 模板 + Claude Code 项目记忆 |

> **配套文档**：
> - 新环境启动 + 验证 MCP 可用：[../getting-started.md](../getting-started.md)
> - 真实测试夹具使用：[../test-fixtures.md](../test-fixtures.md)
> - 设计规约：`specs/001..004-*/spec.md` 等（spec-kit SDD 产出）
> - 头脑风暴设计：`docs/superpowers/specs/*.md`

---

## 项目一句话

arthas MCP 网关把「每个目标 JVM 各起一个 arthas MCP 端点」聚合为**单 HTTP MCP 端点**，对 Claude Code 等 MCP 客户端暴露 **38 个工具**（31 arthas 诊断 + 4 网关自有 + 3 K8S 编排），实现「一个入口、按 target 路由、异步长任务不阻塞、单点故障隔离、配置热重载、远端 K8S pod 一键纳管、Web 管理面」的完整能力闭环。
