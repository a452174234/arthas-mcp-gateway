# Part 17 · 宪法、头脑风暴设计、评审报告、配套文档

> 本附录摘录宪法、brainstorming 设计文档（superpowers/specs）、代码评审报告、配套启动/夹具文档。


---

## `.specify/memory/constitution.md`

```markdown
<!--
==============================================================================
同步影响报告（SYNC IMPACT REPORT）—— 修订日志（最新在上）

【v1.2.0 — MINOR，2026-06-19】
版本变更：1.1.0 → 1.2.0
升级依据：MINOR——细化既有原则二的"定义获取机制"，保留"透明无损聚合"核心
意图，未移除或重定义任何原则。
修改的原则：
  - 二、透明无损的聚合：将"定义来源"由"从各后端获取并原样转发"改为
    "对 arthas 源码如实摘抄（静态拷贝）、不从各后端动态发现"；原则核心
    （不改变诊断语义、调用结果原样透传、映射确定性且可被发现）不变。
触发原因：/speckit-clarify 阶段确认——网关对 tool/resource/prompt 的"定义"
  采用摘抄拷贝自 arthas 源码的方式（不从各后端动态发现），"调用"仍按
  target 路由转发到后端执行。该决策与原则二旧表述冲突，故正式修订。
对下游文档的影响：
  - spec.md FR-008 须同步修订（由"忠实转发各后端暴露的定义"改为"取自
    arthas 规范定义、静态、非逐后端动态发现"）；本次 /speckit-clarify 已一并落盘。
  - spec.md 边缘情况"工具集不一致"与相关假设须同步对齐（已落盘）。
  - 原则八（先决研究）更关键：摘抄须如实，须吃透 arthas-mcp-server 的工具/
    资源/提示词定义；原则八表述本身无需改动。
模板更新：
  - .specify/templates/plan-template.md  → ✅ 无需修改（Constitution Check 运行时读取本文件）
  - .specify/templates/spec-template.md  → ✅ 无需修改
  - .specify/templates/tasks-template.md → ✅ 无需修改
延期事项：无。

【v1.1.0 — MINOR，2026-06-19】
版本变更：1.0.0 → 1.1.0
升级依据：MINOR——新增三条核心原则，未移除或重定义任何既有原则。
新增原则：
  - 六、Java 作为主力开发语言（不可妥协）
  - 七、测试驱动开发（不可妥协）
  - 八、Arthas MCP 实现的先决研究
其它修改：将「开发流程与质量门禁」中的 TDD 表述统一指向原则七。
对下游文档的影响：plan.md 的 Constitution Check 须新增对原则六/七/八的核验；
  原则八要求 Phase 0 先决研究产出（成果汇入 research.md），该环节已内置于
  /speckit-plan 工作流，无需改模板。
模板更新：
  - .specify/templates/plan-template.md      → ✅ 无需修改（Constitution Check 运行时读取本文件）
  - .specify/templates/spec-template.md      → ✅ 无需修改
  - .specify/templates/tasks-template.md     → ✅ 无需修改
延期事项：无。

──────────────── 历史记录 ────────────────

【v1.0.0 — 语言本地化（版本不变），2026-06-19】
  将治理文档本地化为中文以符合 CLAUDE.md 语言规范，未改变任何原则语义。

【v1.0.0 — 初始填充，2026-06-19】
版本变更：(未初始化模板) → 1.0.0
升级依据：从空白模板首次填充治理文档。按 SemVer，治理文档的首次发布为 v1.0.0。
修改的原则：
  - [PRINCIPLE_1_NAME] → 一、MCP 规范符合性（不可妥协）
  - [PRINCIPLE_2_NAME] → 二、透明无损的聚合
  - [PRINCIPLE_3_NAME] → 三、连接生命周期与局部故障韧性
  - [PRINCIPLE_4_NAME] → 四、双侧契约优先的测试
  - [PRINCIPLE_5_NAME] → 五、可观测性与可诊断性
新增章节：核心原则（一至五）、技术与传输约束、开发流程与质量门禁、治理
移除章节：无（保留模板骨架）。
模板更新：plan-template / spec-template / tasks-template 均 ✅ 无需修改。
延期事项：无。
==============================================================================
-->

# Arthas MCP 网关宪法

## 核心原则

### 一、MCP 规范符合性（不可妥协）

网关同时在**两个 MCP 接口**上运作——一个是连接 N 个 arthas MCP 后端的上游
客户端，另一个是被 Claude Code 消费的下游服务端。这两个接口都必须符合已发布的
Model Context Protocol 规范。

- 线上传输格式必须是 MCP 所定义的 JSON-RPC 2.0。
- 生命周期必须被正确实现：在每种传输方式上完成 `initialize` → `initialized`、
  能力协商以及有序关闭。
- 协议原语（tools、resources、prompts）必须严格按规范处理；线上不得出现自定义
  原语。
- 项目必须锁定一个具体的 MCP 规范版本；任何偏离都必须是单独、有记录、有正当
  理由的例外，而非逐渐漂移。

**理由：** 网关的唯一价值在于成为一个透明、符合规范的中间层。任何偏离在开发
阶段都不可见，只会在生产环境中表现为 Claude Code 集成失效或 arthas 后端行为
异常。

### 二、透明无损的聚合

网关将多个 arthas 后端多路复用并聚合到一个 MCP 端点之后，且不改变诊断语义。

- 网关向调用方暴露的 tool、resource、prompt 定义来源于对 arthas 源码的如实
  摘抄（静态拷贝），不从各后端动态发现；该定义集必须忠实反映 arthas 的规范
  能力，不做无依据的改写。
- 可以通过命名空间（namespace）和/或目标选择参数来消除不同后端之间相同工具名
  造成的歧义，且必须遵循 MCP 的命名与参数规则。
- 调用结果必须原样透传——网关不得静默地改写、摘要、截断或丢弃结果内容。
- 工具/命名空间到后端的映射必须是确定性的，并且能通过网关自身的 MCP 接口被
  发现。

**理由：** 这些都是诊断数据。一个会篡改输出的网关，会破坏 Claude Code 与运维
人员所赖以工作的那份诊断信息。

### 三、连接生命周期与局部故障韧性

每个 arthas 后端都必须是受管理的一等对象，而不是一个临时的 socket。

- 每个后端都必须经历显式的注册、能力发现、健康监控、带退避的重连以及干净的
  下线。
- 某个后端的故障或不可用必须能优雅降级——该后端的工具变为不可用——而不得影响
  其他后端，也不得导致网关进程崩溃。
- 跨后端的并发调用不得相互阻塞；一个缓慢或卡死的后端不得拖慢网关或其他后端。

**理由：** 网关面向的是多个会动态增删的 JVM/进程。如果一个死掉的后端就能让
所有访问中断，那么这种聚合相比直连毫无价值。

### 四、双侧契约优先的测试

MCP 是作用于两个集成界面上的契约，而这份契约要用测试来保障。

- 测试必须针对网关↔arthas 界面与网关↔Claude Code 界面，分别断言报文符合
  MCP 规范。
- 协议关键行为（initialize 握手、能力协商、工具调用路由、命名空间、错误传播）
  必须有契约/时序测试覆盖，且这些测试要先于它们所验证的实现被编写。
- 任何对线上格式或路由的改动，都必须在本次改动中新增或更新相应的契约测试。

**理由：** 两个集成界面乘上一个不断演进的规范，使得静默回归成为默认结果。
锁定的契约测试是唯一可靠的防线。

### 五、可观测性与可诊断性

作为为诊断而建的基础设施，网关本身必须是可以被诊断的。

- 所有被路由的调用都必须有结构化日志。
- 每一次被路由的调用都必须可追溯：后端标识、目标选择参数、工具名以及结果都
  必须出现在日志记录中。
- MCP 错误码与后端错误必须被显式传播——绝不可被吞掉成静默的成功。
- 网关必须暴露足够的状态（已注册的后端、健康状况），让运维人员无需阅读源码
  就能理解它在做什么。

**理由：** 一个位于 Claude Code 与 arthas 之间的黑盒网关会让双方都变得不可
调试。沉默即缺陷。

### 六、Java 作为主力开发语言（不可妥协）

- 本项目所有核心代码必须以 Java（LTS）实现。
- Java 是本项目唯一的主力开发语言；非 Java 代码（构建脚本、配置、零星辅助
  脚本、文档生成工具等）仅作为辅助，且不得承载任何属于核心功能的逻辑。
- 引入任何非 Java 的运行时依赖或工具链必须给出正当理由，并在 `plan.md` 中
  记录。
- 具体的 Java 版本与构建工具约束见「技术与传输约束」章节。

**理由：** 网关面向 JVM 生态（arthas 本身即为 Java 诊断工具），与上游 arthas
实现同构；统一以 Java 为主力，可保证协议行为、依赖与运维体验的一致性，并避免
多语言带来的额外构建与维护负担。

### 七、测试驱动开发（不可妥协）

- 所有功能代码必须以 TDD（测试驱动开发）方式开发：先写失败的测试（红），再
  实现至通过（绿），再重构——严格遵循红-绿-重构（Red-Green-Refactor）循环。
- 测试必须先于其验证的实现被编写；实现开始前须先观察到测试失败（并经确认）。
- 本原则是全局规范，覆盖全部功能代码；它与原则四（双侧契约优先的测试）相互
  支撑——原则四规定"测什么"（双侧契约），本原则规定"怎么开发"（一律 TDD）。

**理由：** 网关横跨两个不断演进的集成界面，回归风险高；TDD 将"行为正确"内建
进开发流程，是与原则四的契约测试相互支撑的底线纪律。

### 八、Arthas MCP 实现的先决研究

- 在开始任何功能开发之前，必须先研读上游 Arthas MCP 的相关实现：其暴露的 MCP
  工具/资源、传输方式、报文格式、能力协商与实际运行行为。
- 该先决研究必须作为 `plan.md` 的 Phase 0 产出之一被记录（研究成果汇入
  research.md）；研究未完成不得进入实现阶段。
- 当上游实现与本宪法对 MCP 规范的理解出现差异时，必须以"如实对齐上游实际
  行为"为前提，差异及其处理方式必须在文档中显式记录。

**理由：** 网关的价值在于忠实聚合上游 Arthas MCP；不先理解上游实际实现，就
无法保证透明无损的聚合（原则二）与双侧契约一致（原则四）。

## 技术与传输约束

- **语言：** Java LTS（最低 Java 17 LTS）。Java 版本必须在构建中锁定。
- **构建：** 使用 Maven 或 Gradle，构建可复现且已锁定；同一条命令在本地与 CI
  中必须产生一致的构建结果。
- **MCP 传输：** 必须支持 `stdio`（本地 Claude Code 使用）以及符合规范的
  Streamable HTTP 传输；传输方式的选择必须由配置驱动。
- **并发：** 后端多路复用必须具备异步/非阻塞能力，使各后端相互隔离，不受彼此
  延迟影响。
- **后端注册表：** arthas MCP 端点、传输方式与标识必须声明在配置中（文件和/
  或环境变量），不得硬编码。
- **协议核心：** 优先使用官方/参考的 MCP Java SDK 来实现协议核心；不要手写
  JSON-RPC/MCP 的帧处理。

## 开发流程与质量门禁

- 遵循原则七（测试驱动开发）的 TDD 规范：所有功能代码先写失败的测试，再实现
  至通过。
- 合并前，构建、代码检查（lint）与完整测试套件必须全部通过。
- CI 必须能复现本地构建；不得存在"只在我机器上能跑"的步骤。
- 每项新能力都必须有文档；项目 quickstart 必须端到端演示一个可用的 Claude
  Code 集成。
- 提交应当小而内聚；每次改动只针对一个关注点。

## 治理

本宪法是项目最高权威文档。它凌驾于相互冲突的临时实践之上。

- 修订必须遵循本文件的 SemVer 版本规则：
  - **MAJOR（主版本）**：向后不兼容的治理变更——移除某项原则或重新定义某条
    核心规则。
  - **MINOR（次版本）**：新增原则/章节，或实质性扩展某项指导。
  - **PATCH（修订版本）**：澄清、措辞或非语义性的细化。
- 每次修订都必须更新本文件顶部的同步影响报告，并注明对 `plan.md`、`spec.md`
  或 `tasks.md` 的任何迁移影响。
- 每一份 `plan.md` 必须在 Phase 0 调研之前通过 Constitution Check 门禁，并在
  Phase 1 设计之后再次复核。
- 超出这些原则的复杂度必须在 `plan.md` 的 Complexity Tracking（复杂度追踪）
  表中给出正当理由；缺乏正当理由的复杂度属于范围之外。
- 日常开发的运行时指导位于 `CLAUDE.md`；本宪法管原则，`CLAUDE.md` 管日常机制。

**版本**：1.2.0 | **批准日期**：2026-06-19 | **最后修订**：2026-06-19
```


---

## `docs/getting-started.md`

```markdown
# arthas MCP 网关 — 新环境启动指南与全面验证

> 目标：全新 clone 的环境照本文 ① 构建并启动网关、② 跑测试用例快速验证「MCP 是否可用」、③ 重点验证**远端 K8S 调用场景**（网关集群外运行 + kubeconfig + NodePort 路由到 pod JVM）。
>
> **真实性约定**（CLAUDE.md 宪法原则七）：本文每一条命令均在本地真实环境实跑过，输出为实测捕获（非杜撰）；故障类用真实故障条件（停后端/删 pod/错 token），**禁桩**。复现时若输出有偏差，以你本机实际为准并反馈。

---

## 0. 网关能力全景

arthas MCP 网关把「每个目标 JVM 各起一个 arthas MCP 端点」聚合为**单 HTTP MCP 端点**，对 Claude Code 等客户端暴露 38 个工具：

| 能力域 | 来源特性 | 工具 | 说明 |
|--------|----------|------|------|
| 诊断聚合 | 001 | 31 个 arthas 工具（`jvm`/`watch`/`trace`/`stack`/`tt`/`monitor`/`dashboard`/`sc`/…） | 每个带 `target` 参数，网关路由到指定后端 |
| 网关自有 | 001 | 4 个：`arthas-gateway.list-targets`/`task-get`/`task-list`/`task-cancel` | 多目标路由、异步任务系统 |
| 韧性 | 002 | （内建，非工具）熔断/隔离/并发/超时/退避 | 单点故障不扩散 |
| **K8S 编排** | **003（重点）** | 3 个：`k8s.list-pods`/`k8s.list-services`/`k8s.ensure-arthas-mcp` | 远端 K8S pod 注入 arthas + NodePort 暴露 + 动态纳管 |
| portal 管理面 | 004 | `/admin` HTTP API + Vue SPA | 后端 CRUD + 任务列表查询 + 结果导出（Web UI） |

---

## 1. 前置环境

| 项 | 版本/要求 | 验证命令 |
|----|-----------|----------|
| JDK | **21**（LTS，虚拟线程承载异步任务） | `java -version` → `21.x` |
| Maven | 用仓库自带 wrapper（本地 mvn 3.5.3 太老须 `./mvnw`） | `./mvnw -v` |
| Node | **22.x**（前端构建，004 portal；可 `-DskipFrontend=true` 跳过） | `node -v` → `v22.x` |
| Git | 任意 | `git -v` |
| Claude Code | 真实 CC（工具可用性冒烟；驱动分层见 §7） | `claude --version` |
| **远端 K3S**（重点场景） | debian 服务器上装 k3s（见 §6.2） | `kubectl get nodes` |
| OS | 本指南实测于 Windows 11 + Git Bash（命令为 bash 语法） | — |

> 受控内网、MVP **无鉴权**（Noop）；K3S 凭证 `test-env/k8s/kubeconfig/k3s-admin.yaml` **已 gitignore**（root-admin 权限，仅本机受控内网用）。

---

## 2. 构建（产出含前端 SPA 的单 JAR）

```bash
git clone <repo> && cd arthas-mcp-gateway
./mvnw clean verify          # CI 复现：含 frontend-maven-plugin 跑 npm install + vite build
```

**实测产物**：`target/arthas-mcp-gateway-0.1.0-SNAPSHOT.jar`（fat jar，前端 static 内嵌于 `BOOT-INF/classes/static/`）。

> 跳过前端构建（仅验后端 MCP）：`./mvnw clean verify -DskipFrontend=true`。`-DskipFrontend` 不影响后端测试，仅跳 `frontend-maven-plugin`。

**实测全量测试**：`Tests run: 253, Failures: 0, Errors: 0, Skipped: 0/1`（`K8sExternalGatewaySmokeTest` 在无常驻 uber-jar 网关时 `assumeTrue` 跳过，CI 友好）。

---

## 3. 启动场景

### 3.1 场景 A — 本地双后端最小冒烟（5 分钟验 MCP 可用）

`smoke/gateway-start.sh` 一键启动：① 双真实业务后端（`SmokeDemoLauncher` 起 `order-service` + `payment`，含 `OrderService.hotMethod` 自驱动）+ ② 网关（指向 `config/backends-runtime.yaml`）。

```bash
bash smoke/gateway-start.sh
```

**实测输出**（尾部）：
```
"summary":{"total":2,"healthy":2,"unhealthy":0}
[start] ✓ 常驻就绪。MCP 端点 http://127.0.0.1:8761/mcp
       停止： bash smoke/gateway-stop.sh
```

→ 浏览器访问 `http://127.0.0.1:8761/` 加载 portal SPA；MCP 端点 `http://127.0.0.1:8761/mcp`。停止：`bash smoke/gateway-stop.sh`。

> 此场景用**本地双后端**快速验 MCP 链路；**远端 K8S 场景**见 §3.2 与第 6 章（重点）。

### 3.2 场景 B — 远端 K8S 场景（重点，见第 6 章详述）

在场景 A 启动的同一个网关上（`application.yml` 已配 `arthas-gateway.k8s.kubeconfig`），调 `k8s.*` 工具即可编排远端 K3S pod。完整拓扑/配置/验证流程见 **§6 远端 K8S 调用场景设计**。

---

## 4. 验证 MCP 可用 — 最小冒烟集（L1–L3）

> 「MCP 是否可用」= 这三步全过即可判定。每步实测输出如下。

### L1 健康检查（Actuator，唯一允许直连端点）

```bash
curl -s http://127.0.0.1:8761/actuator/health | python -m json.tool
```

实测（节选）：
```json
{
  "status": "UP",
  "components": {
    "backendRegistry": {
      "details": {
        "backends": {
          "order-service": {"state":"ACTIVE","healthy":true,"breaker":"CLOSED"},
          "payment":       {"state":"ACTIVE","healthy":true,"breaker":"CLOSED"}
        },
        "summary": {"total":2,"healthy":2,"unhealthy":0}
      },
      "status": "UP"
    }
  }
}
```

### L2 MCP 协议 — initialize + tools/list = 38

用真实 Claude Code 走 MCP（驱动分层：工具可用性验「能调通」用真实 CC）：

```bash
# 准备 MCP 配置（同源 HTTP，不污染全局）
cat > .tmp-gs-mcp.json <<'EOF'
{ "mcpServers": { "arthas-gw": { "type": "http", "url": "http://127.0.0.1:8761/mcp" } } }
EOF

# 真实 CC 经 MCP 枚举工具（= initialize + tools/list）
claude -p --mcp-config .tmp-gs-mcp.json --permission-mode bypassPermissions \
  "列出 arthas-gw 暴露的全部 MCP 工具名，仅输出按字母排序的 JSON 字符串数组。"
```

实测返回 **38 个工具**：
```
["arthas-gateway_list-targets","arthas-gateway_task-cancel","arthas-gateway_task-get",
 "arthas-gateway_task-list","classloader","dashboard","dump","getstatic","heapdump","jad",
 "jvm","k8s_ensure-arthas-mcp","k8s_list-pods","k8s_list-services","mbean","mc","memory",
 "monitor","ognl","options","perfcounter","profiler","redefine","retransform","sc","sm",
 "stack","stop","sysenv","sysprop","thread","trace","tt","version","viewfile","vmoption",
 "vmtool","watch"]
```
组成 = **4** `arthas-gateway_*`（自有）+ **3** `k8s_*`（003 编排）+ **31** arthas（诊断）= 38。

### L3 单工具真实诊断 — jvm

```bash
claude -p --mcp-config .tmp-gs-mcp.json --permission-mode bypassPermissions \
  "调用 arthas-gw 的 jvm 工具，target=order-service。返回 MACHINE-NAME/VM-VERSION/线程数/堆 used。"
```

实测（节选）：
```
| MACHINE-NAME | 282276@DESKTOP-O8RUTFP |
| VM-VERSION   | 21.0.5+9-LTS-239 (HotSpot 64-Bit Server VM) |
| 线程数       | 37（daemon 34，峰值 37，死锁 0） |
| 堆 used      | 60,529,496 B ≈ 57.7 MB（committed 68 MB / max 7.94 GB） |
```

→ **L1+L2+L3 全过 = MCP 可用 ✓**（结果来自 order-service 真实 JVM，非桩）。

---

## 5. 远端 K8S 调用场景设计（重点章节）

> 这是网关的精华场景（003 SC-001）：**网关在 K8S 集群外运行**，经 kubeconfig + NodePort 编排远端 pod，对 pod 内 JVM 注入 arthas 并诊断。

### 5.1 真实拓扑

```
┌─────────────────────────┐         kubeconfig          ┌──────────────────────────┐
│  本机（Windows / 网关）  │  ────────────────────────►  │  debian 服务器 (K3S node) │
│  arthas-mcp-gateway.jar │   https://192.168.31.92:6443 │  192.168.31.92            │
│  :8761/mcp              │                              │  pod: demo-business       │
│  kubeconfig:            │  ◄──── NodePort 30000-32767 ─│  (JVM 21, OrderService)   │
│  test-env/k8s/kubeconfig│   arthas MCP via NodePort    │  arthas-boot.jar (注入)   │
└─────────────────────────┘                              └──────────────────────────┘
        ▲
        │ MCP (Streamable HTTP)
        ▼
   Claude Code 客户端
```

要点：
- **网关集群外运行**（本机），不进 K8S——验证「集群外客户端 + 远程编排」真实拓扑。
- **kubeconfig**：`test-env/k8s/setup.sh` 从远端 root-on-node 派生 admin 凭证导出到本机（server 已改 `https://192.168.31.92:6443`，**不建 RBAC**，MVP 后置）。
- **NodePort**：ensure 时自动从 `arthas-gateway.k8s.node-port-range=30000-32767` 分配，暴露 pod 内 arthas MCP（`--target-ip 0.0.0.0` → wildcard LISTEN，NodePort 可达；详见 003 research.md R4）。
- **arthas 不在镜像内**：ensure 时经 fabric8 exec 上传 `tools/arthas-boot.jar`（静态文件，入 git）使用。

### 5.2 kubeconfig 准备（一次性，幂等）

```bash
# 0.（一次性）预置 k3s 离线资源（防 github 间歇不可达，带重试）
bash reference/k3s/fetch.sh

# 1. 一键搭建（本机 Git Bash，内部经 on-debian SSH 远程操作 debian）
#    顺序：mvn test-compile 产 demo class → ship 到 debian → 离线装 k3s（瘦身+tls-san）
#         → docker build demo 镜像 → k3s ctr import → kubectl apply demo pod → 导出 kubeconfig
bash test-env/k8s/setup.sh
```

实测预期：
- `demo-business` pod `Running`/`Ready`（含 shell+java+JVM；arthas 不在镜像）。
- 本机 `test-env/k8s/kubeconfig/k3s-admin.yaml` 生成（= root-on-node 派生 admin）。
- `kubectl --kubeconfig test-env/k8s/kubeconfig/k3s-admin.yaml get pods` 见 `demo-business`。

清理：`bash test-env/k8s/teardown.sh`。

### 5.3 启网关（K8S 配置已内建）

`src/main/resources/application.yml` 已含：
```yaml
arthas-gateway:
  k8s:
    kubeconfig: test-env/k8s/kubeconfig/k3s-admin.yaml   # 相对工程根
    namespace: default
    node-port-range: 30000-32767
    ensure-timeout: 5m
```

启动日志会打印 `KubernetesClient 已构建（kubeconfig=...k3s-admin.yaml, master=https://192.168.31.92:6443/）`。场景 A 的 `gateway-start.sh` 同一网关即带 K8S 能力（38 工具含 `k8s_*`）。

### 5.4 验证流程（list-pods → ensure → 远程诊断 → 纳管）

#### ① list-pods — 枚举可诊断 pod

```bash
claude -p --mcp-config .tmp-gs-mcp.json --permission-mode bypassPermissions \
  "调用 arthas-gw 的 k8s_list-pods 工具 namespace=default，返回 pod 名 + ready + hasJvm。"
```

实测：
```
| demo-business | ✅ ready=true | ✅ hasJvm=true（hasShell=true） |
```
→ `demo-business` 含 JVM + shell，可被 arthas 诊断。

#### ② ensure-arthas-mcp — 注入 arthas + NodePort 暴露 + 注册网关

```bash
claude -p --mcp-config .tmp-gs-mcp.json --permission-mode bypassPermissions \
  "调用 k8s_ensure-arthas-mcp，pod=demo-business, server=debian, namespace=default。返回 target/status/mcpUrl。"
```

实测（**原子幂等**：注入 arthas + 起 arthas MCP + NodePort 暴露 + 健康检查 + 动态注册进网关）：
```
| target   | debian-demo-business |
| status   | ready                |
| mcpUrl   | http://192.168.31.92:30415 |   ← NodePort 路由到 pod 内 arthas MCP
| namespace| default              |
```
→ 新 target `debian-demo-business` 进注册表（`source=DYNAMIC`），`arthas-gateway.list-targets` 立即可见。

#### ③ 远程诊断 — 经网关 → NodePort → pod JVM

```bash
claude -p --mcp-config .tmp-gs-mcp.json --permission-mode bypassPermissions \
  "用 target=debian-demo-business 调 jvm，返回 MACHINE-NAME 与 VM-VERSION。"
```

实测：
```
| MACHINE-NAME | 1@demo-business              |   ← pod 内容器真实主机名
| VM-VERSION   | 21.0.11+10-LTS（Eclipse Adoptium）| ← pod JVM 真实版本
旁证：OS=Linux/amd64、JVM-START-TIME=2026-06-22 19:34:58、statusCode=0、success=true
```
→ **远程 pod JVM 诊断完全可达**（结果来自远端 debian 上的 pod，非本机）。

#### ④ 纳管确认

```bash
curl -s http://127.0.0.1:8761/actuator/health | python -m json.tool | grep -A3 debian-demo-business
```
实测 `debian-demo-business` 出现在 `backendRegistry.details.backends`，`state=ACTIVE/healthy=true`。

### 5.5 故障韧性（SC-003，破坏性，按需）

```bash
# 模拟 K8S 驱逐：删 pod（on-debian 经 SSH 远程 kubectl）
ssh root@192.168.31.92 'kubectl delete pod demo-business'
```

预期（003 SC-003，已验证）：
- 网关 **30s 内**把 `debian-demo-business` 标 `healthy=false`（复用 001 健康监控/熔断）。
- 对该 target 诊断 → 结构化错误（INVALID_PARAMS + `reason:backend_unreachable`）。
- **其他 target（payment/order-service）不受影响**（隔离）。
- pod 重新部署后再次 `ensure-arthas-mcp` → `status:ready` 恢复纳管。

> 此步破坏性，文档引用 003 SC-003 实证；首跑可跳过。

---

## 6. 全面测试用例（分层 + 能力矩阵）

### 6.1 测试分层

| 层 | 目的 | 驱动 | 命令 | 实测结果 |
|----|------|------|------|----------|
| **L0** 构建测试 | 编译 + 单测 + 契约 IT 全绿 | `./mvnw verify` | `./mvnw clean verify` | 253 测试 / 0 失败 / 0 错误 |
| **L1** 健康检查 | 网关与后端可达 | Actuator | `curl /actuator/health` | `status:UP`，后端 healthy |
| **L2** MCP 协议 | initialize + tools/list | 真实 CC | `claude -p` 列工具 | 38 工具 |
| **L3** 单工具冒烟 | 每能力「能调通」 | 真实 CC | `claude -p` 调各工具 | jvm/watch/k8s.* 等真实返回 |
| **L4** 契约/一致性 | 双侧协议 + 结果 A/B 一致 | 官方 MCP Java SDK client | `./mvnw verify -Dtest="..contract.."` | `*ContractIT` 全绿 |
| **L5** 故障注入 | 真实故障条件（禁桩） | 真实 CC + 真实故障 | 停后端/删 pod/错 token | 结构化错误 + 熔断 + 隔离 |
| **L6** 端到端 | Claude 编排全链路 | 真实 CC | `claude -p` 多步编排 | SC-001 5min 闭环 |

> 驱动分层（CLAUDE.md）：**工具可用性**用真实 CC（仅验「能调通」）；**结果一致性 + 双侧协议契约**用官方 MCP Java SDK client（确定性断言）。除健康检查外，**不 curl 裸打 MCP**。

### 6.2 能力 → 测试用例矩阵

| 能力 | 验证用例 | 命令（要点） | 期望（实测锚点） |
|------|----------|--------------|------------------|
| 诊断 — `jvm` | L3 单工具 | `jvm target=order-service` | 返 MACHINE-NAME/VM-VERSION/线程/堆 |
| 诊断 — `watch`（异步） | L3 任务系统 | `watch target=... numberOfExecutions=1` | 立即返 `taskId`+`working`，完成→`COMPLETED` |
| 任务系统 | L3 | watch → `task-get`/`task-list` | taskId 可查、`task-list` 含该任务 |
| 多目标路由 | L3 | jvm 分别 `target=order-service`/`payment` | 结果各属正确目标 JVM |
| 热重载 | L3 | 编辑 `backends.yaml` 增/删后端 | ≤30s `list-targets` 反映变化 |
| 单点故障隔离 | L5 | 停 order-service | payment 诊断正常；order 诊断 30s 内明确错误 + 熔断 |
| **K8S list-pods** | L3 | `k8s_list-pods namespace=default` | 列 pod + ready + hasJvm |
| **K8S ensure** | L3 | `k8s_ensure-arthas-mcp pod=demo-business server=debian` | `status:ready` + mcpUrl + 注册 |
| **K8S 远程诊断** | L3 | jvm `target=debian-demo-business` | pod JVM 真实信息（1@demo-business） |
| **K8S 故障韧性** | L5 | 删 demo-business pod | 30s 隔离 + 熔断 + 其他 target 不受影响 |
| portal — 后端 CRUD | L3 | 浏览器 `/backends` 增删改 | 写 YAML 热重载 + 列表刷新 |
| portal — 任务列表 | L3 | 浏览器 `/tasks` 或 `GET /admin/tasks` | 列表摘要 + 过滤 + 分页 + 点项导出 |
| portal — 结果导出 | L3 | `GET /admin/tasks/{id}/export` | 下载 JSON（含 frames 原样） |

### 6.3 故障注入用例（L5，真实故障条件，禁桩）

| 场景 | 真实故障实现 | 期望 |
|------|--------------|------|
| 后端不可达 | 停 order-service 后端 | 诊断 → `backend_unreachable`，30s 内熔断 OPEN |
| 认证失败 | `auth.mode=BEARER` + 错误 token | 诊断 → arthas 真实 401 |
| 慢响应 | 业务方法 `Thread.sleep` | 超阈值 → 超时错误（不计熔断） |
| 并发越界 | 真实发起 >`maxConcurrentTasks` 并发 | arthas 真实 INVALID_PARAMS / 排队 |
| K8S pod 删除 | `kubectl delete pod` | 30s 隔离 + 熔断 + 隔离其他 target |

---

## 7. 排查指引

| 现象 | 检查 |
|------|------|
| Claude Code 看不到工具 | 网关是否起（L1）、`tools/list` 是否 38（L2）、mcp-config URL 是否 `http://127.0.0.1:8761/mcp` |
| `k8s_*` 工具报 kubeconfig 错 | `test-env/k8s/kubeconfig/k3s-admin.yaml` 是否存在、`application.yml` 路径、远端 192.168.31.92:6443 可达 |
| ensure 返失败 | pod 是否 `Ready` + `hasJvm` + `hasShell`（list-pods 看）、`tools/arthas-boot.jar` 是否在 |
| 远程诊断超时 | NodePort 是否可达（`curl http://192.168.31.92:<nodeport>`）、pod 内 arthas 是否 `0.0.0.0` 监听 |
| 异步任务一直 working | 后端可达性、`task-get` 的 `error.reason`、后台是否超 11min 标 failed |
| portal 页面 404（`/backends` reload） | `SpaConfig` 是否注册（Vue Router history 模式 fallback，004 INV-WEB-1） |

详细定位见 `reference/arthas-docs/03-MCP/问题定位反向索引.md`。

---

## 8. 清理

```bash
bash smoke/gateway-stop.sh          # 停本地网关 + 双后端
bash test-env/k8s/teardown.sh       # （远端 K3S）卸载 k3s + 删导出凭证
rm -f .tmp-gs-mcp.json              # 删临时 MCP 配置
```

---

## 附录：实测环境快照（2026-07-08）

| 项 | 值 |
|----|-----|
| 网关 | `arthas-mcp-gateway-0.1.0-SNAPSHOT.jar`（Spring Boot 4.1.0 / Spring AI 2.0.0 / MCP SDK 2.0.0） |
| 本机 | Windows 11 + Git Bash + JDK 21.0.5 |
| 远端 K3S | debian 13（kernel 6.12.73）+ k3s v1.35.5（node 192.168.31.92） |
| 业务 pod | `demo-business`（JVM 21.0.11+10-LTS，Eclipse Adoptium，`OrderService.hotMethod`） |
| 工具数 | 38（4 自有 + 3 K8S + 31 arthas） |
| portal | Vue 3 SPA 内嵌单 JAR，`/backends` + `/tasks`（含任务列表） |
```


---

## `docs/test-fixtures.md`

```markdown
# 真实测试夹具使用指南

> 测试夹具 = 冒烟/集成测试用的**真实业务后端**（真实 arthas MCP + 真实业务 JVM），**非桩**。本文档说明夹具结构、编译方式、使用场景与真实性证据。
>
> **宪法原则七（CLAUDE.md 真实性硬约束）**：每次测试至少启动一个真实 arthas MCP + 一个真实业务服务；诊断数据由触发业务服务真实调用产生；故障类用真实故障条件（停后端=不可达、错 token=arthas 真实 401、`Thread.sleep`=慢响应、真实并发越界）。夹具即承载这套真实性的代码。

---

## 1. 两层夹具结构

### 1.1 核心夹具 — `src/test/java/com/arthas/gateway/testfixtures/`

由 `mvn test-compile` 编到 `target/test-classes/`（标准 Maven 测试编译，无需额外配置）。

| 类 | 职责 |
|----|------|
| `ArthasMcpBackend` | 拉起**一个真实 arthas MCP 后端**（业务 JVM + arthas http console `/mcp`）；冒烟与契约 IT 共用 |
| `DemoBusinessApp` | 真实业务 JVM（含 `OrderService.hotMethod` 自驱动 hotLoop + `/health` + `/order` HTTP 端点） |
| `OrderService` | 真实业务类（`hotMethod` 被 arthas `watch`/`trace` 捕获） |
| `OrderResult` | 业务返回值（`watch` 结果含 `accessPoint`/`className`/`value`） |
| `McpClientHarness` | 官方 MCP Java SDK client 封装（契约 IT / 一致性 A/B 驱动） |
| `FakeBackendClient` | 测试用 backend client（**测试 client，非桩后端**） |

### 1.2 冒烟启动器 — `smoke/`

**不在 `src/`，Maven 不编译**；需手动 `javac` 到 `target/smoke-classes/`。

| 文件 | 职责 |
|------|------|
| `SmokeDemoLauncher.java` | 一键拉起**双真实后端**（order-service + payment）+ 写 `config/backends-runtime.yaml`（动态端口）+ 常驻 |
| `SmokeMcpClient.java` | MCP client 冒烟（连网关调工具） |
| `SmokeWatchAsync.java` | 异步 `watch` 冒烟 |
| `gateway-start.sh` | 一键启动：双后端 + 网关（用 `target/test-classes;target/smoke-classes`） |
| `gateway-stop.sh` | 停全部相关 java 进程 |
| `r4-bind-test.sh` | arthas NodePort 绑定地址实证（003 R4） |

---

## 2. 编译步骤（全新环境复现）

### 2.1 核心夹具（Maven 编译）

```bash
./mvnw test-compile
# 产出 target/test-classes/com/arthas/gateway/testfixtures/*.class
```

> `mvn verify` / `mvn package` 也含 `test-compile`，无需单独跑。

### 2.2 SDK classpath 快照（`target/sdk-cp.txt`）

冒烟启动器 `javac` 需 MCP SDK 依赖路径（`SmokeMcpClient` 引用 `McpSyncClient` 等）：

```bash
./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/sdk-cp.txt
# 产出 target/sdk-cp.txt（~14KB，含 MCP SDK + Spring 依赖路径，分号分隔）
```

### 2.3 冒烟启动器（手动 javac）

```bash
J21="/c/Program Files/Java/jdk-21"          # JDK 21 路径（按你本机调整）
mkdir -p target/smoke-classes
CP="target/test-classes;$(cat target/sdk-cp.txt)"   # Windows/Git Bash 用 ; 分隔（Linux/Mac 用 :）
"$J21/bin/javac" -cp "$CP" -d target/smoke-classes \
  smoke/SmokeDemoLauncher.java smoke/SmokeMcpClient.java smoke/SmokeWatchAsync.java
# 产出 target/smoke-classes/com/arthas/gateway/smoke/*.class
```

> 注：`javac` 会打印「无注解处理器」提示，无害（不影响产物）。

### 2.4 一键启动（自动用上述产物）

```bash
bash smoke/gateway-start.sh
# 内部执行：
#   java -Dbasedir=<ROOT> -cp "target/test-classes;target/smoke-classes" com.arthas.gateway.smoke.SmokeDemoLauncher
#   java -jar target/arthas-mcp-gateway-0.1.0-SNAPSHOT.jar --arthas-gateway.backends-file=config/backends-runtime.yaml
```

⚠️ `gateway-start.sh` 假设 `target/smoke-classes` **已编译**。全新环境首次须先跑 §2.1–2.3。

---

## 3. K8S 场景夹具（ship 到远端 pod 镜像）

K8S 测试床把核心夹具的 **3 个运行时类**（`DemoBusinessApp` + `OrderService` + `OrderResult`）ship 到 debian 服务器，打成 docker 镜像，import k3s，apply 为 `demo-business` pod：

```bash
bash test-env/k8s/setup.sh
# 顺序：mvn test-compile 产 demo class → ship 到 debian → docker build 镜像
#      → k3s ctr import → kubectl apply demo pod → 导出 root 派生 admin kubeconfig
```

要点：
- 镜像**仅 3 个业务类**（不含 `ArthasMcpBackend`/`McpClientHarness`/`*Test`）。
- **arthas 不在镜像**：`k8s.ensure-arthas-mcp` 时经 fabric8 exec 上传 `tools/arthas-boot.jar`（静态文件，入 git）使用。
- 清理：`bash test-env/k8s/teardown.sh`。

---

## 4. 真实性证据（实测，非桩）

`jvm` 工具对夹具后端返回的诊断含**该业务 JVM 自身真实数据**：

| 字段 | 实测值（证明真实） |
|------|---------------------|
| `INPUT-ARGUMENTS` | `[-Ddemo.slowMs=0]`（`DemoBusinessApp` 启动参数） |
| `CLASS-PATH` | `…target/test-classes`（夹具 classpath） |
| `MACHINE-NAME` | `<pid>@<host>`（真实进程 ID + 主机名） |
| `VM-VERSION` | `21.0.5+9-LTS` / `21.0.11+10-LTS`（本机/远端 pod 各自 JVM） |
| `watch` 结果 | `OrderService.hotMethod` 真实 `accessPoint`/`cost`/`value` |

→ 这些数据**只能**来自运行中的真实 JVM，桩无法伪造。

---

## 5. 文件位置总览

| 路径 | 说明 | 入 git |
|------|------|--------|
| `src/test/java/com/arthas/gateway/testfixtures/` | 核心夹具**源码** | ✓ |
| `smoke/` | 冒烟启动器**源码** + 启停脚本 | ✓ |
| `tools/arthas-boot.jar` | 静态 arthas 工具（夹具 + K8S ensure 用） | ✓（例外：非构建产物的工具文件） |
| `target/test-classes/` | `mvn test-compile` 产物 | ✗ gitignored |
| `target/smoke-classes/` | 手动 `javac` 产物 | ✗ gitignored |
| `target/sdk-cp.txt` | SDK classpath 快照 | ✗ gitignored |
| `config/backends-runtime.yaml` | `SmokeDemoLauncher` 每次启动按动态端口重写 | ✗ gitignored |

> 全部产物在 `target/`（gitignored），全新环境照 §2 重编即可复现。源码（夹具 + smoke + arthas-boot.jar）全入库，clone 后即可用。
```


---

## `docs/superpowers/specs/2026-06-20-arthas-test-fixture-design.md`

```markdown
# 设计：真实 arthas MCP 测试夹具（T008 DemoBusinessApp + T009 ArthasMcpBackend）

**Feature**: 001-arthas-mcp-gateway | **Date**: 2026-06-20
**决策来源**: 头脑风暴（CLAUDE.md「方案型研究前必须先头脑风暴」），用户确认 **arthas-boot.jar 启动**路径 + **单后端 TDD 优先、集群单独测**作用域。
**宪法依据**: 原则七（TDD 真实环境、零桩）、原则四（双侧契约）、CLAUDE.md「驱动分层」。

---

## 1. 背景与问题

US1/US2/US3 全部集成测试需「真实 arthas MCP + 真实业务服务 + 真实诊断数据」（宪法硬约束，零桩）。卡点：reference 范式 `ArthasMcpJavaSdkIT` 用 `bash as.sh --attach-only` attach arthas，**显式 `assumeFalse(isWindows())` 在 Windows 跳过**（依赖 bash/as.sh）；本机 Windows 11、无 Docker（memory 约束）。

## 2. 选型：arthas-boot.jar 启动（纯 Java attach）

- `arthas-boot.jar`（`com.taobao.arthas:arthas-boot:4.3.0`）是 `as.sh` 的**纯 Java 等价物**：`java -jar`、无 bash 依赖，Windows 原生可跑。仍是 attach 到真实目标 JVM（Java Attach API），忠于生产 attach 路径。
- 已核实（证据驱动）：
  - `Bootstrap.java` 支持 `--attach-only`/`--http-port`/`--target-ip`/`--arthas-home` + PID 位置参数（reference/arthas/boot）。
  - arthas **4.3.0 已发布**（git tag `arthas-all-4.3.0`，revision=4.3.0），含完整 MCP（auth/streamable/task 测试提交）。
  - reference IT 已证明该 `--http-port` 服务 MCP 协议（`HttpClientStreamableHttpTransport` 连上后 initialize/listTools/callTool 正常）。
- **宪法满足**：真实 arthas MCP + 真实业务服务 + 真实诊断（HTTP/循环触发业务方法 → arthas watch/trace 捕获真实调用）。零桩。

## 3. 测试期架构

```
集成测试 JVM（failsafe，*IT.java）
  ArthasMcpBackend 夹具
   ├─ 启 DemoBusinessApp 子进程 → 取 PID
   ├─ java -jar arthas-boot.jar <pid> --attach-only --http-port <mcpPort> --target-ip 127.0.0.1   （短命，exit 0）
   ├─ 轮询 mcpPort 就绪
   └─ 暴露 baseUrl = http://127.0.0.1:<mcpPort>
  McpClientHarness（T010）── 连网关 或 直连 arthas 后端（仅 baseUrl 不同，做 A/B 一致性）

DemoBusinessApp（T008，独立 JVM，Spring Boot）
  └─ arthas agent 注入后，Netty MCP HTTP 常驻其内 @<mcpPort>
```

## 4. 组件职责

| 组件 | 职责 | 需 arthas |
|---|---|---|
| `DemoBusinessApp`（T008） | 真实业务服务：可被 watch/trace/stack/tt 的业务方法（如 `OrderService.hotMethod`）+ HTTP `/api/order` 触发执行 + 可注入 `Thread.sleep` 模拟慢响应。**技术形态：纯 Java + JDK `com.sun.net.httpserver.HttpServer`**（用户裁决 2026-06-20，替代本表原述「Spring Boot 进程」——对齐 reference `TargetJvmApp` 纯 Java 范式，子进程类路径只需 `target/test-classes`，避免 Spring Boot 子进程 fat jar/classpath 地狱；诊断价值齐全。`main` 后台守护线程持续触发 `hotMethod` 供 arthas watch 抓事件） | 否 |
| `ArthasMcpBackend`（T009） | 编排夹具：启 DemoBusinessApp → 取 PID → 跑 arthas-boot.jar attach → 等端口 → 暴露 `baseUrl`。`AutoCloseable`（优雅关停子进程）。原子单元，可按逻辑名实例化 | 是 |
| `McpClientHarness`（T010，已完成） | 复用：连网关或直连后端 | — |

## 5. 构件获取与生命周期

**构件获取（用户约束 2026-06-20：本工程不依赖 arthas；T009 首测实测 2026-06-20）**：
- **arthas 启动器作为可执行 fat jar（静态工具文件）置于工程 `tools/arthas-boot.jar`**——既不以 Maven 依赖引入（不入 pom），也不依赖 `reference/arthas` 源码构建。该 jar 是官方可执行 launcher（Main-Class=`com.taobao.arthas.boot.Bootstrap`、Implementation-Version=4.3.0、147977 字节），从 `https://arthas.aliyun.com/arthas-boot.jar` 下载——**非** Maven Central 的 `com.taobao.arthas:arthas-boot:4.3.0`（瘦 jar，无 Main-Class 清单，`java -jar` 报"没有主清单属性"，不可用）。T009 ArthasMcpBackend 经 `java -jar tools/arthas-boot.jar <pid> --attach-only --http-port <mcpPort> --target-ip 127.0.0.1 --use-version 4.3.0` 使用（参考 reference `ArthasMcpJavaSdkIT` 的 attach/连接<b>范式</b>，非依赖其代码）。
- arthas 运行时：launcher 首跑经 `--use-version 4.3.0` 自动下载 core 4.3.0 到 `~/.arthas/lib/4.3.0` 缓存（首联网、后离线）。
- **端点裁决（T009 首测 GREEN）**：arthas 4.3.0 MCP 端点为**根 URL** `http://127.0.0.1:<mcpPort>`（无 `/mcp`），SDK 2.0.0 ↔ arthas 4.3.0 握手 `2025-11-25` 互通、Windows 原生 attach 成功——`backend-client-contract.md §1` 的 `/mcp` 假设以实测为准。

**生命周期**：
```
start(logicalName, auth=NONE|BEARER, token?):
  1. 分配空闲端口 mcpPort + appPort
  2. 启 DemoBusinessApp 子进程，轮询就绪（/actuator/health 或端口，30s）
  3. 取 PID（Process.pid()）
  4. java -jar arthas-boot.jar <pid> --attach-only --http-port <mcpPort>
            --target-ip 127.0.0.1 [--arthas-home <home>]   （90s，期望 exit 0）
  5. 轮询 mcpPort 就绪（30s）
  6. （BEARER）配 arthas 认证 token
  7. 暴露 baseUrl
close(): destroy DemoBusinessApp（destroyForcibly 兜底），端口随进程释放
```
超时取自 reference IT 经验（attach 90s / 端口 30s）。

## 6. 作用域：单后端优先，集群整体后置（用户确认）

> **用户决策（2026-06-20）**：集群/多目标能力**整体后置实现**；当前范围 = **网关基础功能（单后端）**。

- **当前范围**：`ArthasMcpBackend`（单后端，1 JVM + arthas）= TDD 主路径。覆盖网关核心路由管线：BackendClient 契约、一致性 A/B、异步任务、故障隔离（停这 1 个后端）、失效 target 错误、认证、target 剥离（S-CALL-2）、并发客户端归属（S-CALL-3）。
- **后置（不在当前计划）**：多目标路由隔离（S-CALL-1：target=A 只打 A、B 零请求）、list-targets 多目标展示、热重载增删到多后端。这些待网关基础功能就绪后单独迭代，届时本地起 2 个 `ArthasMcpBackend`。

`ArthasMcpBackend` 为原子单元（单实例）。T008/T009 组件设计面向单后端，不引入集群编排。

## 7. 接入（当前范围，单后端）

| 故事 | 集成测试（*IT.java → failsafe） | 夹具 |
|---|---|---|
| US1 核心 | T018 BackendClientContractTest、T019 ToolsCallRoutingContractTest（S-CALL-2/3/4）、T020 ResultConsistencyIT | 单后端 + 网关；harness 连双侧 A/B |
| US1 核心 | T029 GatewayToolsContractTest、T030 AsyncTaskTimeoutIT | 单后端，异步任务 |
| US3 | T043 FaultIsolationContractTest、T044 FailedTargetErrorIT | 单后端（停/错 token） |

**后置**（不在当前范围）：S-CALL-1 多目标路由、T037 HotReloadIT、T038 ListTargetsContractTest。

纯逻辑单测（T021 配置解析 / T022 注册表 / T045 熔断状态机 / T047 限流）→ surefire（快、无 arthas），可先行 TDD，不依赖 arthas 夹具。

## 7.1 实现波次（先网关基础功能）

1. **波次 A（纯逻辑，无 arthas，surefire TDD）**：T021 BackendConfig+Loader、T022 BackendRegistry+Holder、T023 BackendAuthCustomizer、T045 CircuitBreaker、T047 BackendEntry 限流。先建网关域层。
2. **波次 B（真实夹具）**：T008 DemoBusinessApp、T009 ArthasMcpBackend（单后端）。
3. **波次 C（真实路由）**：T024 BackendClient 接真实 arthas、ToolsCallRouter 真实路由（替换 Phase 2 占位），US1 核心 + US3 集成测试。
4. **后置**：多目标/集群、热重载。

## 8. 认证（NONE / BEARER）

- `ArthasMcpBackend.start(name, auth, token?)`：BEARER 以 token 启 arthas；harness/网关发 `Authorization: Bearer <token>`。
- 网关 `BackendAuthCustomizer`（T023）从 backends.yaml 取 token 注入头。
- C-AUTH-1（US3）：错 token → arthas 真实 401 + `WWW-Authenticate` → 网关标 target 不可用、返 JSON-RPC error（不透传 HTTP）。
- arthas MCP token 配置机制实现期核实。

## 9. 残留风险（实现期首测裁决）

- arthas attach 走 Java Attach API，Windows 原生支持；reference 跳 Windows 仅因 bash/as.sh——arthas-boot.jar 移除此障碍。
- arthas 个别 Windows 专属能力（vmtool 本地库等）需首测验证；核心诊断（watch/trace/sc/jad/thread）跨平台可用。
- arthas 4.3.0（SDK 0.17.0）↔ 网关 SDK 2.0.0 互操作：协议层握手由首测裁决（T009 真实 attach + initialize）。

## 10. 落地任务（已存在于 tasks.md，本设计为其奠基）

- T008 `DemoBusinessApp`（[P]，单文件）
- T009 `ArthasMcpBackend`（[P]，单文件）
- 此后 US1/US2/US3 集成测试以此夹具为共享地基。
```


---

## `docs/superpowers/specs/2026-06-21-code-review-remediation-design.md`

```markdown
# 代码评审发现修复 — 设计文档

| 项目 | 内容 |
|------|------|
| 主题 | arthas MCP 网关 MVP 业务代码评审（15 项发现）的整改设计 |
| 日期 | 2026-06-21 |
| 特性 | `specs/002-code-review-remediation` |
| 依据 | [评审报告](../../code-review/2026-06-20-business-code-review.md)、[特性 spec](../../../specs/002-code-review-remediation/spec.md)、[宪法](../../../.specify/memory/constitution.md) v1.2.0 |
| 设计阶段 | SuperPower 头脑风暴产出（CLAUDE.md：设计阶段走 brainstorming） |
| 关键决策 | **深度重构（altitude）**：熔断/限流/分类下沉为 `BackendEntry` 统一拦截层，路由器两路径委托 |

---

## 一、背景与约束

评审报告对 `src/main/java/com/arthas/gateway/` 整库审查，提出 15 项发现（P0×2 / P1×4 / P2×4 / P3×5）。本设计给出**修复后应满足的行为**与**采用的技术方案**。

两条全局硬约束（来自用户）贯穿全程：

1. **不影响现有功能**——对外可观测 MCP 行为（工具/资源定义、`target` 路由、结果原样透传、热重载、错误传播、健康状态）与修复前逐项一致。
2. **每完成一步必须验证现有功能完好**——每修一项，先确认既有测试 + 端到端冒烟全绿，再推进下一项。

---

## 二、核心设计决策：深度重构（altitude）

### 2.1 分叉与选择

评审对 P1-1/P1-3/P1-4（熔断线程安全、异步路径不驱动熔断、initialize 非原子）给出两条修复路径：

- **A 最小改动（surgical）**：在现有架构上手术式修——`CircuitBreaker` 方法加 `synchronized`、`initialize` 用 CAS、`submitAsync` 补熔断记录。改动小、回归面小。
- **B 深度重构（altitude）**：把"熔断守卫 + 槽管理 + 故障分类"下沉为 `BackendEntry` 的**统一拦截层**，路由器同步/异步两路径都委托。根治错层、最可维护，但核心调用路径重写、回归面大。

**选择：B（深度重构）。** 经头脑风暴与用户确认采用 B。

### 2.2 为什么选 B（理由）

- **根因一致**：P0-1（僵尸任务）、P0-2（槽泄漏）、P1-1（熔断竞态）、P1-3（异步不驱动熔断）、P3-2（healthy 三处重复）**同源于一个错层**——熔断/槽的"持有者"是 `BackendEntry`，但"操作者"散在 `ToolsCallRouter` 两条路径、且不全。A 路径只能在每条路径上各自打补丁，错层仍在、补丁会继续漂移；B 路径把操作收拢到持有者自身，从结构上消灭这一类"漏操作"。
- **P0-2 的结构性根因**：当前槽的"获取/释放"跨了 submit 边界（路由器取槽、后台闭包的 `finally` 还槽），`pool.submit` 失败时还槽被跳过 → 槽泄漏。B 把槽生命周期交由执行器统一保证（`onTerminal`），占/还同一负责方，**结构上不可能漏**。A 路径只能补 catch，仍依赖人记得补。
- **代价可控**：B 的"回归面大"由**逐步 TDD + 每步冒烟**对冲（约束 2）；核心路径重写后须重跑全部双侧契约测试（宪法原则四）。

> **放弃 A 的代价**：A 改动小，但保留"breaker 被 router 两路径分别手挂"的错层味道，P1-3 的"异步驱动熔断"在 A 下仍要在 `submitAsync` 单独加记录逻辑，与 `forwardSync` 各维护一份分类规则，未来易再次不一致。

### 2.3 不做什么（YAGNI / 不影响功能）

- **不改并发上限语义**：网关 `Semaphore(5)` 现在同步+异步一起挡（arthas 的 5 只挡 task session，网关更严）。此差异是 001 既有选择，**保持不变**（改了就影响行为）。
- **不重写双侧协议契约**：`execute`/`invoke` 返回的是 `client.callTool()` 的原始 `CallToolResult`，原样透传（原则二）；错误仍以结构化 `McpError` 传播（原则五）。对外报文不变。
- **不做 P1-3 的"半开视为降级"等行为扩展**：仅让异步路径**正确驱动既有熔断状态机**，不新增健康判定语义。

---

## 三、目标架构：统一拦截层

### 3.1 现状（错层的根因）

```
现状：breaker 持有者在 BackendEntry，操作者散在 ToolsCallRouter 两路径
  forwardSync : guardCircuit → tryAcquireSlot → call → recordSuccess/Failure → releaseSlot（手挂全套）
  submitAsync : guardCircuit → tryAcquireSlot → submit(executor, 闭包{ call; finally releaseSlot })（缺 record、release 跨边界）
```

### 3.2 目标：`BackendEntry` 成为"受守卫执行"的唯一所有者

收口三类职责：**熔断（synchronized）+ 槽（RAII 配对）+ 故障分类**。对外暴露四个原语。

> **错误边界**：`BackendEntry` **不**依赖 `McpError` 构造与全局注册表。结构化 `McpError` 的 `data.available`（全部目标名）只有路由器（持 `RegistryHolder`）能提供。故 `admit`/`invoke` 抛**域异常**（`CircuitOpenException`、`ConcurrencyLimitException`、`StatelessAsyncException`、`BackendUnreachableException`，各带必要数据如 `retryAfterMs`、`maxConcurrentTasks`、`cause`），由**路由器**捕获并翻译为结构化 `McpError`。这保持 `BackendEntry` 与协议层/注册表解耦。

```java
// 同步：守卫→取槽→调用→分类→释放，全在一个作用域（RAII），槽不可能漏
public CallToolResult execute(String toolName, Map<String,Object> args) {
    admit(toolName);
    try { return invoke(toolName, args); }
    finally { releaseSlot(); }
}

// 异步前置准入：协议校验(P1-2) + 熔断守卫(P1-3 读) + 取槽；槽由执行器经 onTerminal 释放
public void admit(String toolName) {
    if (config().protocol() == Protocol.STATELESS)
        throw new StatelessAsyncException();                          // P1-2
    if (!breaker().allowRequest()) throw new CircuitOpenException(breaker().retryAfterMillis()); // P1-3（读）
    if (!tryAcquireSlot())          throw new ConcurrencyLimitException(config().maxConcurrentTasks());
}

// 真正调后端 + 分类：成功/业务错误→recordSuccess，基础设施故障→recordFailure
public CallToolResult invoke(String toolName, Map<String,Object> args) {
    initializeOnce();                                                // P1-4 CAS
    try {
        CallToolResult r = client().callTool(toolName, args);        // 原样透传（原则二）
        breaker().recordSuccess();                                   // C-CB-2
        return r;
    } catch (McpError e) {                                           // 后端业务错误
        breaker().recordSuccess();                                   // 不计熔断
        throw e;
    } catch (RuntimeException e) {                                   // 基础设施故障
        breaker().recordFailure();                                   // C-CB-1（P1-3 写）
        throw new BackendUnreachableException(e);
    }
}

public boolean isHealthy() {                                         // P3-2 单一事实源
    return state() == BackendState.ACTIVE && breaker().state() != CircuitBreaker.State.OPEN;
}

private void initializeOnce() {                                      // P1-4
    if (initialized.compareAndSet(false, true)) client().initialize();
}
```

`CircuitBreaker.allowRequest()/recordSuccess()/recordFailure()` 全部加 `synchronized` → **P1-1**。

### 3.3 `AsyncTaskExecutor` 增 `onTerminal`，保证终态/提交失败必释放 + 无僵尸

```java
public GatewayTask submit(String tool, String target,
                          Callable<CallToolResult> work, Runnable onTerminal) {
    GatewayTask task = new GatewayTask(taskId, tool, target, clock.get(), clock);
    store.put(task);
    Future<?> sup;
    try {
        sup = pool.submit(() -> orchestrate(task, work, onTerminal));
    } catch (RejectedExecutionException e) {     // 外层 submit 被拒（池已关）
        store.remove(task.taskId());             // P0-1：不留僵尸
        onTerminal.run();                        // P0-2：释放槽
        throw e;
    }
    supervisorFutures.put(task.taskId(), sup);
    return task;
}

private void orchestrate(GatewayTask task, Callable<CallToolResult> work, Runnable onTerminal) {
    try {
        Future<CallToolResult> w = pool.submit(work);              // P0-2：纳入 try
        try { task.markCompleted(w.get(timeoutMs, MS)); }
        catch (TimeoutException t)    { w.cancel(true); task.markFailed(BACKEND_TIMEOUT); }
        catch (ExecutionException e)  { w.cancel(true); task.markFailed(toTaskError(e.getCause())); }
        catch (InterruptedException i){ Thread.currentThread().interrupt(); w.cancel(true);
                                        task.markCancelled(); }      // 取消：invoke 未跑完→不计熔断
    } catch (RejectedExecutionException e) {                        // 内层 submit 被拒
        task.markFailed(BACKEND_UNREACHABLE);                       // P0-1：标终态，无僵尸
    } finally {
        supervisorFutures.remove(task.taskId());
        onTerminal.run();                          // P0-2：任一终态都释放槽
    }
}
```

> 关键：**槽的"占/还"现在都归执行器管**（async）或同作用域（sync），路由器闭包里**不再手动 release**——P0-2 从结构上不可能再发生。

### 3.4 `ToolsCallRouter` 两路径变薄、都委托

```java
private CallToolResult forwardSync(ExposedTool tool, DiagnosticRequest dr) {
    BackendEntry e = resolveTarget(dr);
    long t = nanoTime();
    try {
        CallToolResult r = e.execute(tool.name(), dr.backendArgs()); // 全部生命周期进 execute
        log.info("工具调用完成 tool={} target={} isError={} 耗时={}ms", ...);  // 原则五
        return r;
    } catch (CircuitOpenException c)        { throw backendUnreachableMcpError(dr, "target 熔断中", c.retryAfterMs()); }
      catch (ConcurrencyLimitException c)   { throw concurrencyLimitMcpError(dr, c.maxConcurrentTasks()); }
      catch (StatelessAsyncException c)     { throw invalidParamsMcpError(dr, "stateless_unsupported_async"); }
      catch (BackendUnreachableException c) { throw backendUnreachableMcpError(dr, c.cause()); }
      // McpError（后端业务错误）原样向上抛，不翻译
}

private CallToolResult submitAsync(ExposedTool tool, DiagnosticRequest dr) {
    BackendEntry e = resolveTarget(dr);
    try { e.admit(tool.name()); }                                    // P1-2/熔断/取槽 前置（同上翻译 4 类域异常）
    catch (CircuitOpenException | ConcurrencyLimitException | StatelessAsyncException c) { throw translate(dr, c); }
    GatewayTask task = asyncExecutor.submit(
        tool.name(), dr.target(),
        () -> e.invoke(tool.name(), dr.backendArgs()),               // 后台：分类+记熔断（P1-3）
        e::releaseSlot);                                             // 执行器保证释放
    log.info("异步任务已接受 tool={} target={} taskId={}", ...);
    return asyncAcceptedResponse(task);
}
```

`backendUnreachableMcpError` 等辅助方法在路由器内构造结构化 `McpError`（含 `data.available` = `registry.current().names()`、`retryAfterMs`、`reason`），与现状逐字一致。`GatewayToolHandlers.listTargets` 与 `BackendRegistryHealthIndicator` 的 `healthy` 判定改为委托 `BackendEntry.isHealthy()`（P3-2）。

---

## 四、其余发现的修法

### 4.1 P2 健壮性

| 发现 | 修法 |
|------|------|
| **P2-1** 退役宽限 60s 与异步兜底 11min 脱节 | 退役宽限默认 `= backendTimeout`（配置可覆盖，保证 in-flight 异步可完成）；`retireAll` 的 sleep 线程纳入可追踪 `ScheduledExecutorService`（容器关闭 graceful + `awaitTermination`，不再裸 `Thread.startVirtualThread(sleep)` 堆积） |
| **P2-2** `Map.copyOf` 拒 null 值参数 | `DiagnosticRequest` 防御拷贝改 `Collections.unmodifiableMap(new LinkedHashMap<>(backendArgs))`（容忍 null value，保留不可变性） |
| **P2-3** `TaskStore.get` 单查触发全表清理 | `get` 只判查到的那一条（过期则 `remove(taskId)` 返 empty），全表 `cleanExpired()` 只保留给 `list` 路径，过期清理后台 `cleaner` 兜底 |
| **P2-4** 虚拟线程池无全局背压 | `AsyncTaskExecutor` 增一个全局 `Semaphore`（跨 target 累计上限，配置驱动），`submit` 前 `tryAcquire`、`onTerminal` 时 `release`，与 per-target 槽并列 |

### 4.2 P3 安全卫生与清理

| 发现 | 修法 |
|------|------|
| **P3-1** `Auth` toString 含明文凭据 | `BackendConfig.Auth` 重写 `toString` 脱敏（仅 mode + 凭据掩码） |
| **P3-2** healthy 三处重复 | 抽 `BackendEntry.isHealthy()` 单一事实源，`listTargets`/`HealthIndicator`/`guardCircuit` 三处委托（见 §3.4） |
| **P3-3** `ObjectMapper` 三处重复构造 | 抽 `handler` 包内 `McpJson` 工具类承载全局单例 + `json()` 封装 |
| **P3-4** `asInt` 静默截断浮点/超大整数 | `asInt` 校验 `Number` 为整数类型（`Integer`/`Long`），拒 `Double`/`Float`；`Long` 超 int 范围报错并保留原始值；`readVersion` 同理 |
| **P3-5** 死代码/等价复制 | 删 `ExposedTool.gatewayOwned()`、`TaskError.REASON_CIRCUIT_OPEN`、`TaskSupport.wireValue()`；合并 `asNullableString` 入 `asString` |

---

## 五、宪法保真（原则一/二/三/四/五不变）

- **原则一（MCP 规范符合性）**：`execute`/`invoke` 不触碰 JSON-RPC 帧处理，仅围绕官方 SDK `McpSyncClient` 调用做生命周期包装；不新增自定义协议原语。
- **原则二（透明无损聚合）**：`invoke` 返回 `client.callTool()` 的原始 `CallToolResult`，原样透传、不篡改/截断/摘要。
- **原则三（局部故障韧性）**：统一拦截层强化故障隔离——熔断/槽/分类集中在 `BackendEntry`（per-target 独立），异步路径正确驱动熔断后纯异步负载也能隔离；跨后端并发不相互阻塞（P2-4 全局背压防集群触顶）。
- **原则四（双侧契约）**：核心 `tools/call` 路径重写后须重跑全部双侧契约测试（gateway↔arthas、gateway↔Claude Code）；本次新增/更新对应契约测试（见 §6）。
- **原则五（可观测性）**：结构化日志保留 tool/target/isError/duration；MCP 错误码与后端错误显式传播（熔断 OPEN/并发越界/不可达/STATELESS 拒绝均结构化）。

---

## 六、测试策略（宪法原则七 TDD + CLAUDE.md 真实性硬约束）

**TDD 红绿重构**：每项修复先写失败测试（红），再实现至通过（绿），再重构。

**真实性分层**（对齐 CLAUDE.md「零桩、真实环境」与既有 DIP 缝）：

- **网关自身并发/资源逻辑**（executor 关闭竞态、熔断线程安全、槽 RAII、initialize 原子、读放大）——用**真实 JVM 并发原语**测（真实 `ExecutorService`/虚拟线程/真实并发计数），`AsyncTaskExecutor` 的 DIP 缝（注入 `Callable`）与 `BackendClient` 接口允许注入受控测试双端**触发真实失败条件**（抛基础设施异常、模拟中断），**非 arthas 成功响应桩**。这部分与既有 `AsyncTaskExecutorTest`「注入 callable 测编排契约」一致。
- **与 arthas 交互的行为**——遵循既有驱动分层：工具可用性走**真实 Claude Code MCP**（冒烟）；结果一致性 + 双侧协议契约走**官方 MCP Java SDK client**（确定性断言）；故障用例用**真实故障条件**（停真实后端=不可达、错 token=arthas 真实 401、`Thread.sleep`=慢响应、真实发起 6 并发越界=arthas 真实 INVALID_PARAMS）。
- **逐步验证**：每个可独立提交的小步（如「CircuitBreaker synchronized」「executor onTerminal」「admit STATELESS 校验」「invoke CAS」）完成后，先跑既有测试 + 冒烟确认全绿，再下一步。

---

## 七、推进顺序（契合评审建议 + 逐步验证）

1. **A 组核心**（深度重构）：先 `CircuitBreaker synchronized`（P1-1）→ `initialize CAS`（P1-4）→ `BackendEntry.execute/admit/invoke/isHealthy` + `AsyncTaskExecutor.onTerminal`（P0-1/P0-2/P1-3/P3-2）→ `ToolsCallRouter` 两路径委托 + `admit` 加 STATELESS 校验（P1-2）。每小步 TDD + 冒烟。
2. **B 组健壮性**（P2-2 早修，影响正常调用健壮性）→ P2-1 → P2-3 → P2-4。
3. **C 组清理**（低风险批量收口）：P3-1/3/4/5 一次重构。
4. **回归门禁**：全部完成后跑完整测试套件 + 端到端冒烟，确认对外行为逐项一致。

---

## 八、风险与对冲

| 风险 | 对冲 |
|------|------|
| 核心调用路径重写引入回归 | 逐步 TDD + 每步冒烟；重写后重跑全部双侧契约测试（原则四） |
| `onTerminal` 双重释放（旧闭包残留 release + 执行器 release） | 路由器闭包内**移除**手动 release，槽释放只由执行器 `onTerminal` 负责；单测断言"提交失败时恰好释放一次" |
| 取消中断被误计熔断 | `invoke` 仅在 call 实际执行后分类；取消在 `orchestrate` 层判别（`InterruptedException`），不进 `invoke` 的 recordFailure |
| 异步路径新增熔断记录改变"纯异步负载"行为 | 这正是 P1-3 要求的修复（评审 + 用户均要求修）；行为变化是**修正**而非回归，须在契约测试中显式断言 |

---

## 九、与 spec / plan 的关系

- 本文档是**设计阶段（brainstorming）产出**，记录"深度重构"决策与统一拦截层架构。
- `specs/002-code-review-remediation/research.md`（spec-kit Phase 0）将逐项落地技术决策、引用本文档。
- `plan.md` / `data-model.md` / `contracts/` / `quickstart.md`（spec-kit Phase 1）据此展开。
- 实施走 `/speckit-tasks` → `/speckit-implement`（SDD，测试先于实现）。
```


---

## `docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md`

```markdown
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
```


---

## `docs/superpowers/specs/2026-06-23-003-tdd-feasibility-review.md`

```markdown
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
```


---

## `docs/superpowers/specs/2026-06-23-k8s-test-env-setup-design.md`

```markdown
# 最小 K8S 测试环境构建方案 — 设计文档

| 项目 | 内容 |
|------|------|
| 主题 | 在 debian-docker 服务器上构建**最小 k3s 测试集群 + 业务 pod 镜像**的可执行方案，供 003 特性波次 B（真实 K8S 夹具 IT）/ 波次 C（端到端）使用 |
| 日期 | 2026-06-23 |
| 特性 | `specs/003-k8s-arthas-mcp-launch`（测试基建前置，非功能代码） |
| 依据 | [003 设计](./2026-06-22-k8s-arthas-mcp-launch-design.md) §四/§八、[research.md R3](../../../specs/003-k8s-arthas-mcp-launch/research.md)、[quickstart.md §2.1](../../../specs/003-k8s-arthas-mcp-launch/quickstart.md)、[arthas 测试夹具设计](./2026-06-20-arthas-test-fixture-design.md)、[宪法](../../../.specify/memory/constitution.md) v1.2.0 |
| 设计阶段 | SuperPower 头脑风暴产出（CLAUDE.md：设计阶段走 brainstorming） |
| 关键决策 | **在线最小 k3s + 离线 airgap 安装**（资源预置 `reference/k3s/`，防网络波动）；**业务镜像纯 app 无 arthas**（ensure 时上传）；**身份=root-on-node**（不建 K8S RBAC）；**仓库内幂等 setup 脚本**（`test-env/k8s/`） |

---

## 一、背景与范围

### 1.1 问题

003 特性的波次 B/C 需要**真实 K8S 集群 + 真实业务 pod**作为测试夹具（CLAUDE.md「零桩、真实环境」硬约束）。既有 `research.md` R3 已定「debian-docker（192.168.31.92）上的 k3s」为测试集群，但 `quickstart.md` §2.1 仅留了骨架（一行 `curl get.k3s.io | sh -` + 占位的镜像/pod 步骤），不可执行。本文把该骨架明确化为**可执行的最小测试环境构建方案**。

### 1.2 范围（P1 测试基建）

- **在范围内**：① k3s 离线安装脚本（资源预置 + 瘦身 + tls-san）；② demo 业务 pod 镜像构建（Dockerfile + 在 debian 上 build + 入 k3s containerd）；③ demo-pod 清单；④ root-on-node 派生的 admin kubeconfig 导出（供本机网关远程用）；⑤ NodePort 网络可达性验证；⑥ 幂等清理（teardown）。
- **起步覆盖的契约用例**：`K-ENS-1/2/3`（happy/复用/经网关诊断）+ `K-ENS-7`（R4 回归：`0.0.0.0` 经 NodePort 可达）——对应**单个「正常 pod」**（shell+java+JVM 跑 DemoBusinessApp）。
- **后置（按 TDD 进度逐步补，非本设计）**：`K-ENS-4`（无 JVM）/ `K-ENS-5`（无 shell）/ `K-ENS-6`（无 exec 权限）三类故障用例——其 pod 形态：无 JVM=同镜像改 `CMD sleep`、无 shell=补 distroless 镜像、无 exec=补受限 kubeconfig。

### 1.3 与功能代码的隔离

本设计**不碰 003 功能代码**（`orchestration` 包）。产物 `test-env/k8s/` + `reference/k3s/` 属**测试基建**，与 `src/` 完全隔离；既有 001/002 测试不受影响（回归对照照常）。

---

## 二、前置与约束

| 项 | 现状 | 来源 |
|---|---|---|
| **承载服务器** | `debian-docker`（192.168.31.92），Debian 13，Docker 26.1.5，2 核 / 3.8Gi，amd64，SSH key 免密 root 登录（本机 `~/bin/on-debian '<cmd>'`） | memory `debian-docker-ssh-access` |
| **本机** | Windows 11，**无 Docker**；JDK 21 + Maven 可用（`mvn test-compile` 产出 `target/test-classes`） | memory `arthas-no-dependency`、`java-version-jdk21` |
| **网络** | github.com 在本网络**间歇不可达**——k3s 资源须**预置本地、离线安装** | memory `github-com-unreachable` |
| **身份模型** | 访问原语 = **debian 主节点 root 登录**（即 k3s 的 `system:masters` cluster-admin）；**不另建** K8S ServiceAccount/Role。网关集群外用的 kubeconfig = 主节点 root 派生的 admin 凭证（re-point server），不是新建权限 | 用户裁决（2026-06-23） |
| **arthas** | 作静态工具文件 `tools/arthas-boot.jar`（不入 pom、不入镜像）；`ensure` 时 fabric8 `cp` 上传进 pod | design 2026-06-22 §4.1、memory `arthas-no-dependency` |
| **真实性** | 零桩、真实环境（CLAUDE.md 硬约束）——本 env 提供真实集群 + 真实业务 JVM | CLAUDE.md |

> 本机 Windows 无 Docker → **镜像在 debian 上 build**（debian 有 Docker 26.1.5），import 进 k3s containerd；本机只负责 `mvn test-compile` 产出 class + scp 传输。

---

## 三、产物与目录

### 3.1 `test-env/k8s/`（入库的测试基建脚本）

```
test-env/k8s/
├── README.md          # 前置 + 一键用法 + 故障排查 + 与 quickstart/research 衔接
├── Dockerfile         # demo 业务镜像（JDK21 + DemoBusinessApp，无 arthas）
├── demo-pod.yaml      # 单 pod 清单（label/资源/探针/常驻）
├── setup.sh           # 本机编排：mvn 编译 → ship 到 debian → 离线装 k3s + build + import + apply + 导出 kubeconfig
└── teardown.sh        # k3s-uninstall + 清镜像 + 删本机 kubeconfig
```

### 3.2 `reference/k3s/`（预置的 k3s 离线资源，与 `reference/arthas/` 同级）

```
reference/k3s/
├── fetch.sh                            # 一次性下载（带重试，锁版本）；入库
├── k3s-install.sh                      # get.k3s.io 安装脚本副本（小）；入库
├── sha256sums.txt                      # 完整性校验；入库
├── k3s                                 # （.gitignore）k3s 二进制 ~60MB
└── k3s-airgap-images-amd64.tar.gz      # （.gitignore）离线系统镜像 ~180MB
```

- **大二进制 gitignore**（避免仓库膨胀 ~250MB）；`fetch.sh` / `k3s-install.sh` / `sha256sums.txt` 入库以保证可复现/可校验。
- **构建顺序**：先 `reference/k3s/fetch.sh`（联网一次，带重试）→ 再 `test-env/k8s/setup.sh`（全程离线）。

### 3.3 `.gitignore` 增量

```
test-env/k8s/kubeconfig/
reference/k3s/k3s
reference/k3s/k3s-airgap-images-amd64.tar.gz
```

---

## 四、demo 业务镜像（`Dockerfile`）

| 维度 | 决策 |
|---|---|
| **基础镜像** | `eclipse-temurin:21-jdk`（debian 系，自带 `sh`；需 `jps` 定位 JVM PID → JDK 非 JRE）。`hasShell`/`hasJvm` 均满足（design §4 目标须 shell+java+JVM） |
| **镜像内容** | 仅 3 个运行时类：`DemoBusinessApp` + `OrderService` + `OrderResult`（本机 `mvn test-compile` 产出 `target/test-classes/com/arthas/gateway/testifacts/`）。夹具类（`ArthasMcpBackend`/`McpClientHarness`）、`*Test` 类**不进镜像** |
| **arthas** | **不含**——`ensure` 时 fabric8 `cp tools/arthas-boot.jar` 上传（design §4.1）；镜像纯 app |
| **CMD** | `["java","-cp","/app/classes","com.arthas.gateway.testfixtures.DemoBusinessApp","8081"]`——常驻；内置 hot-loop 每 50ms 自触发 `hotMethod`（watch/trace 无需外部触发）；`/actuator/health`、`/api/order` 在容器内 8081 |
| **tag / pullPolicy** | `arthas-gateway/demo-business:local`；`imagePullPolicy: Never`（本地 import，不 pull） |
| **build 地点** | **debian 上**（本机无 Docker）：setup.sh 把 Dockerfile + 3 个 class scp 到 debian → `docker build -t arthas-gateway/demo-business:local .` → `docker save | sudo k3s ctr images import -` |

**Dockerfile（样例）**：
```dockerfile
FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY classes/com/arthas/gateway/testfixtures/ /app/classes/com/arthas/gateway/testfixtures/
EXPOSE 8081
CMD ["java", "-cp", "/app/classes", "com.arthas.gateway.testfixtures.DemoBusinessApp", "8081"]
```
> build context = `Dockerfile` + `classes/com/arthas/gateway/testfixtures/{DemoBusinessApp,OrderService,OrderResult}.class`（setup.sh 在 debian 上组好该目录树）。

---

## 五、`demo-pod.yaml`

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: demo-business
  namespace: default
  labels:
    app: demo-business        # ensure 另打 arthas-mcp-gateway/target=<logicalName>（design §4.1）
spec:
  containers:
  - name: app
    image: arthas-gateway/demo-business:local
    imagePullPolicy: Never
    resources:
      requests: { cpu: 200m, memory: 384Mi }
      limits:   { cpu: 1000m, memory: 1Gi }
    readinessProbe: { httpGet: { path: /actuator/health, port: 8081 }, periodSeconds: 5 }
    livenessProbe:  { httpGet: { path: /actuator/health, port: 8081 }, periodSeconds: 10 }
  restartPolicy: Always
```

- **无 Service**：`ensure` 才建 NodePort Service（暴露 arthas MCP 端口）；业务 8081 仅容器内探针用。hot-loop 自驱动诊断事件，无需外部触发。
- **namespace = `default`**：身份=root-admin，无 RBAC 隔离需求；最简（后续整洁化可挪专用 ns，非必需）。
- **资源**：demo JVM ~384Mi + arthas attach 额外开销；limit 1Gi。2核/3.8Gi 节点经 k3s 瘦身后容纳单 demo pod 充裕（§六）。

---

## 六、k3s 离线安装与瘦身

### 6.1 一次性资源下载 `reference/k3s/fetch.sh`

```bash
#!/usr/bin/env bash
# 锁定单一 k3s 稳定版（amd64）。版本号 + sha256 写入本文件顶部与 sha256sums.txt。
# 当前锁定 v1.35.5+k3s1（2026-06 stable channel，update.k3s.io/v1-release/channels）。
# 升级时显式改 K3S_VERSION + 重跑 fetch.sh 刷 sha256sums.txt。
set -euo pipefail
K3S_VERSION="v1.35.5+k3s1"
ARCH=amd64
BASE="https://github.com/k3s-io/k3s/releases/download/${K3S_VERSION}"
OUT="$(dirname "$0")"

# 断言架构（debian 须 x86_64）
[ "$(uname -m)" = "x86_64" ] || { echo "仅支持 amd64"; exit 1; }

# 带重试下载（github 间歇不可达，memory github-com-unreachable：坏时重试/等几分钟）
retry() { for i in 1 2 3 4 5; do "$@" && return 0; echo "重试 $i..."; sleep 30; done; return 1; }

retry curl -fL "${BASE}/k3s"                             -o "${OUT}/k3s"
retry curl -fL "${BASE}/k3s-airgap-images-${ARCH}.tar.gz" -o "${OUT}/k3s-airgap-images-${ARCH}.tar.gz"
retry curl -fL "https://raw.githubusercontent.com/k3s-io/k3s/${K3S_VERSION}/install.sh" -o "${OUT}/k3s-install.sh"

( cd "${OUT}" && sha256sum k3s k3s-airgap-images-${ARCH}.tar.gz k3s-install.sh > sha256sums.txt )
chmod +x "${OUT}/k3s" "${OUT}/k3s-install.sh"
echo "k3s 离线资源就绪：${OUT}（版本 ${K3S_VERSION}）"
```

### 6.2 离线 airgap 安装（`setup.sh` 核心，经 `on-debian` root SSH）

setup.sh 把 `reference/k3s/*` scp 到 debian `/tmp/k3s-artifacts/`，幂等安装：

```bash
# 0. 校验完整性
on-debian 'cd /tmp/k3s-artifacts && sha256sum -c sha256sums.txt'

# 1. k3s 已装则跳过（幂等）
on-debian 'test -x /usr/local/bin/k3s || {
  # 放二进制
  install -m 755 /tmp/k3s-artifacts/k3s /usr/local/bin/k3s;
  # 放 airgap 系统镜像（flannel/coredns/local-path 等，启动时自动 load）
  mkdir -p /var/lib/rancher/k3s/agent/images &&
    cp /tmp/k3s-artifacts/k3s-airgap-images-amd64.tar.gz /var/lib/rancher/k3s/agent/images/;
  # 离线装（跳过下载），瘦身 + tls-san
  INSTALL_K3S_SKIP_DOWNLOAD=true sh /tmp/k3s-artifacts/k3s-install.sh \
    --disable traefik --disable servicelb --disable metrics-server \
    --tls-san 192.168.31.92;
}'
```

### 6.3 瘦身与 flags 理由

| flag | 理由 |
|---|---|
| `--disable traefik` | 不用 ingress；NodePort 直达，省 ingress controller 内存 |
| `--disable servicelb` | 不用 LoadBalancer（NodePort 即可）；省 ServiceLB pod |
| `--disable metrics-server` | 不依赖指标；省 metrics-server 内存 |
| `--tls-san 192.168.31.92` | API server 证书对本机 Windows 连的 IP 有效（否则 kubeconfig 证书校验失败） |
| 保留 flannel + local-path-provisioner | 基础 pod 网络 + 存储（demo pod 需要） |

> 2核/3.8Gi 经瘦身（省 ~300–500Mi）后容纳 k3s 控制面 + 单 demo pod（limit 1Gi）充裕。
> **不用** `--write-kubeconfig-mode`（保持 `k3s.yaml` 600）；导出经 `sudo cat`（§七）。

---

## 七、身份与 kubeconfig 导出

身份模型：**debian root（主节点）= `system:masters` cluster-admin**；不建 ServiceAccount/Role。k3s 装完，`/etc/rancher/k3s/k3s.yaml`（600，root 可读）即 cluster-admin 凭证。

```bash
# setup.sh 末尾：debian root 读 600 的 k3s.yaml → 改 server → 落本机（gitignore 目录）
on-debian 'sudo cat /etc/rancher/k3s/k3s.yaml' \
  | sed 's|https://127.0.0.1:6443|https://192.168.31.92:6443|' \
  > test-env/k8s/kubeconfig/k3s-admin.yaml
```

- 网关 `arthas-gateway.k8s.kubeconfig`（特性 impl 期加，见 [research.md R2/K8sClientFactory](../../../specs/003-k8s-arthas-mcp-launch/research.md)）指向此文件。
- **安全**：`k3s-admin.yaml` = cluster-admin 凭证 → `.gitignore`，仅本机 + debian 受控内网使用（符合 MVP 受控/无认证假设）。`K-ENS-6`（无 exec→403）随故障用例后置。

---

## 八、网络可达性验证（NodePort：Windows → debian）

- k3s flannel VXLAN 默认；NodePort 30000–32767 在 debian 主网卡监听。
- **setup.sh 末尾自检**（debian 上）：`ss -tlnp | grep :6443`（API up）、`kubectl get pods`（demo Ready）。
- **本机 Windows**（ensure 后）：`Test-NetConnection 192.168.31.92 -Port <nodePort>` 验 NodePort 可达（SC-001「5 分钟内完成 ensure+诊断」的前提）。
- debian 13 默认通常无防火墙规则；若内网防火墙挡，setup.sh 报错并提示开放 30000–32767（受控内网通常不需）。

---

## 九、清理（`teardown.sh`）

```bash
on-debian '/usr/local/bin/k3s-uninstall.sh'   # 卸 k3s + 清容器/镜像/cni（k3s 官方卸载器）
rm -f test-env/k8s/kubeconfig/k3s-admin.yaml  # 删本机凭证
```
幂等：k3s 未装则跳过。

---

## 十、与既有文档/特性衔接

- **`quickstart.md` §2.1**：从骨架改为「一键 `on-debian test-env/k8s/setup.sh`」（前置：先 `reference/k3s/fetch.sh`）+ 预期结果。
- **`research.md` R3**：补「实施细节 = `test-env/k8s/` setup 脚本（在线、debian 已有 Docker）+ `reference/k3s/` 离线资源预置」。
- **不碰 003 功能代码**（orchestration 包）；env 完全隔离。
- **DemoBusinessApp 容器化** = 001 夹具的 K8S 形态（[data-model §7](../../../specs/003-k8s-arthas-mcp-launch/data-model.md)、[research.md R7](../../../specs/003-k8s-arthas-mcp-launch/research.md) 已记），本设计给具体 Dockerfile/manifest。
- **P2（K8S 离线构造脚本）后置**：P2 面向离线/生产从零构造（含离线镜像包打包分发），与本在线 test-env（debian 已有 Docker、在线 fetch 一次）各自演进、不冲突。

---

## 十一、Done Definition（验证清单）

- [ ] `reference/k3s/fetch.sh` 跑通：3 资源齐 + `sha256sum -c` 过。
- [ ] `on-debian test-env/k8s/setup.sh` 幂等跑通：k3s up、demo pod Ready、kubeconfig 导出。
- [ ] setup.sh 离线：安装阶段**不触网**（可临时断网验证 k3s 装+镜像 import+apply）。
- [ ] 本机 `kubectl --kubeconfig test-env/k8s/kubeconfig/k3s-admin.yaml get pods` 见 `demo-business Running`。
- [ ] NodePort 可达：`ensure` 后本机 `Test-NetConnection 192.168.31.92 -Port <nodePort>` 通。
- [ ] `teardown.sh` 清干净（k3s 卸、凭证删、`/tmp/k3s-artifacts` 清）。
- [ ] 不回归：001/002 既有测试不受影响（env 完全隔离）。

---

## 十二、风险与对冲

| 风险 | 对冲 |
|---|---|
| github.com 间歇不可达 → fetch.sh 下载失败 | `retry` 重试 5 次 ×30s；坏时手动等几分钟重跑（memory `github-com-unreachable`）；资源预置后 setup 全程离线 |
| k3s airgap tar 与瘦身组件版本不匹配 | airgap tar 为 k3s 官方同版本打包；`--disable` 组件不 load，无冲突；sha256 校验保证完整 |
| debian 防火墙挡 NodePort | setup.sh 自检 + 报错提示开放 30000–32767（受控内网通常无） |
| 2核/3.8Gi 资源紧张 | 瘦身 traefik/servicelb/metrics-server；单 demo pod limit 1Gi；后续加故障 pod 时复算内存 |
| cluster-admin 凭证泄露 | `test-env/k8s/kubeconfig/` + `reference/k3s/` 大文件 `.gitignore`；仅受控内网 |
| 非 amd64 节点 | fetch.sh `uname -m` 断言；当前 debian 为 amd64 |

---

## 十三、与 spec / plan 的关系

- 本文档是**设计阶段（brainstorming）产出**，记录最小 K8S 测试环境的构建方案。
- **不走** SuperPower `writing-plans`（CLAUDE.md 工作流规范白名单禁用）。本设计的**落地物是 `test-env/k8s/` + `reference/k3s/` 脚本本身**（测试基建，非 spec-kit 特性功能），其结论回灌 `quickstart.md` §2.1 与 `research.md` R3。
- 实施时机：作为 003 波次 B 的**前置**（波次 B 真实夹具 IT 依赖此 env）；按 TDD「测试先于实现」，env 随首批波次 B 用例（`K8sEnsureContractIT`）就绪。
```


---

## `docs/superpowers/specs/2026-06-25-portal-backend-management-design.md`

```markdown
# portal 后端管理平台设计（004 特性）v2 — Web 前端版

**日期**: 2026-07-06（v2 重设计：CLI → Web 前端）
**特性分支**: `004-portal-backend-management`
**来源**: 003 spec 用户故事 3（P3 portal）后置项的"配置管理"子集
**状态**: 已 brainstorm v2，待 spec-kit SDD 重写

> **v2 变更**：v1 为 CLI/API 版（picocli）。用户裁决改为 **Web 前端**（003 spec"MVP 不含 Web UI"经 brainstorming 推翻——用户要可视化控制台）。CLI 废弃（零代码未实施）。后端 `/admin` HTTP API 保留，前端 SPA 消费。

---

## 一、背景

003 spec 用户故事 3（portal 独立管理后端，P3）在 003 仅占位。本特性落地 P3 的**配置管理子集**，补齐 001/003 遗留的管理面缺口。

### 妥协未完成的管理能力（证据驱动梳理）

| # | 能力 | 现状（妥协） | 缺口 | 证据 |
|---|---|---|---|---|
| A | 后端配置管理面 | 手编 `config/backends.yaml` + 热重载（SC-002），无管理 UI | 运维须 SSH 改 YAML | 001 spec 假设§110；宪法原则三/五 |
| C | 异步任务结果导出 | `TaskStore` 仅 task-get 同步返 JSON 文本 | 长 watch 多帧结果无法离线分析/归档 | `task/TaskStore.java`；001 演进注记 |

**用户裁决**：MVP = A + C。"只做配置管理"——不碰 K8S 编排（list-pods/ensure 仍由 003 `k8s.*` MCP 工具经 Claude Code）。

---

## 二、brainstorming v2 决策记录

| 决策点 | 选定 | 备选（否决理由） |
|---|---|---|
| 交互形态 | **Web 前端 SPA**（废弃 CLI） | v1 CLI（用户裁决要可视化控制台）；CLI+前端并存（双套入口工作量） |
| 前端技术栈 | **Vue 3 + Vite + TypeScript** | React（更主流但 Vue 管理面 MVP 更轻）；原生 HTML/JS（无构建但难维护） |
| 部署形态 | **网关内嵌静态**（单 JAR） | 独立 SPA（CORS + 两产物）；仅开发期独立（生产未定） |
| 集成方式 | 后端 `/admin` HTTP API（前端同源 fetch） | — |
| 持久化 | YAML（`config/backends.yaml` 复用） | DB / in-memory |
| 鉴权 | Noop（受控内网） | Bearer token / Basic |
| MVP 范围 | A 后端 CRUD + C 任务导出下载 | + 任务可视化 / + 健康 dashboard（后置） |

---

## 三、架构（单 JAR 不变）

```
┌──────────────────────────────────────────────────────────┐
│  arthas-mcp-gateway.jar（单产物）                          │
│                                                          │
│  后端 Java（Spring Boot）                                  │
│  ├─ 诊断面 /mcp          （001/003 既有，不动）            │
│  ├─ 管理面 /admin/*      （004 后端，@ConditionalOnProperty）│
│  │   ├─ /admin/backends        CRUD（A）                   │
│  │   └─ /admin/tasks/{id}/export  导出（C）                 │
│  └─ 前端 SPA 静态资源    （vite build → static/，同源服务）  │
│                                                          │
│  浏览器 → 网关根（/）→ SPA → fetch /admin（同源，无 CORS）  │
└──────────────────────────────────────────────────────────┘

dev：vite dev server + proxy /admin → localhost:8761
prod：vite build 产物内嵌 JAR，Spring Boot 服务 SPA + /admin
```

**复用既有**（零侵入）：`BackendRegistry`/`BackendEntry`（001）、`DynamicBackendStore`（003）、`TaskStore`/`GatewayTask`（001）、`BackendRegistryReloader`+WatchService（001 热重载）。

**新增包/目录**：
- `com.arthas.gateway.admin`（网关侧后端 REST）：`backend/`（CRUD）+ `task/`（导出）子包，各自 `@ConditionalOnProperty`。
- `web/`（前端项目根）：Vue 3 + Vite + TS 源码；`vite build` → `src/main/resources/static/`。
- **不再有** `portal/` CLI 包（v2 废弃）。

---

## 四、能力详述

### A. 后端配置 CRUD（含健康视图）

**后端 API**（`/admin/backends`，前端 SPA 消费）：GET 列表/详情、POST 增、PUT 改、DELETE 删。
- 静态后端：写回 `config/backends.yaml` 复用 001 热重载（SC-002，30s）。
- 动态后端：POST/PUT 拒绝（R3）、DELETE=`DynamicBackendStore.unregister`。
- 响应 `BackendDto`：`name/source/state/healthy/breaker/url/protocol/auth.mode/timeouts/maxConcurrent`，凭据脱敏（仅 mode）。

**前端 UI**：后端管理页——列表表格（name/source/state/healthy/breaker）+ 增删改表单 + 健康状态徽标。

### C. 异步任务结果导出

**后端 API**（`/admin/tasks/{taskId}/export?format=json`）：`completed` 任务完整结果（元信息 + frames）作可下载 JSON；frames 原样透传（原则二）；未完成/不存在 → 409/404。

**前端 UI**：任务导出页——任务列表 + 下载按钮（触发 `GET /admin/tasks/{id}/export`，浏览器下载 JSON）。

---

## 五、错误处理

| 场景 | HTTP | 说明 |
|---|---|---|
| 未知后端/task | 404 + `available[]` | 与 001 路由拒绝一致 |
| CRUD 校验失败（重复 name、非法 URL、改动态） | 400 + 详情 | 前端表单校验 + 后端复核 |
| 导出未完成/不存在 | 409 / 404 | — |
| `backends.yaml` 写入失败 | 500 + 详情 | 透明记录（原则五） |

前端统一拦截错误 → 友好提示（toast/inline）。后端错误显式传播，不静默（原则五）。

---

## 六、测试策略

**TDD（宪法原则七）+ 双侧契约（原则四）+ 真实零桩**：

- **后端契约 IT**（HTTP client 断言）：CRUD 各端点形状/状态码/错误码；导出端点形状 + Content-Disposition。真实网关 + 真实后端/任务。
- **前端组件测试**（Vitest + Vue Test Utils）：列表渲染、表单交互、错误提示、下载触发。
- **集成**（真实零桩）：浏览器 → SPA → `/admin` 端到端（CRUD 写 YAML 热重载、动态 register、导出真实 watch 结果）。
- **回归**：001/002/003 + 38 工具契约不破；`/admin` 与 SPA 不影响 `/mcp`；`PackageBoundaryTest` 继续通过。

---

## 七、非目标（MVP 不做）

- portal CLI（v2 废弃）
- 任务结果可视化（frames 表格/图表，后置）
- 健康监控 dashboard（趋势，后置）
- B 动态后端持久化（后置）
- D 操作历史/审计（后置）
- K8S 编排（仍 003 `k8s.*` MCP 工具）
- 管理面鉴权（Noop，演进项）

---

## 八、宪法对齐（关键：原则六）

| 原则 | 对齐 |
|---|---|
| 二 透明无损聚合 | 任务导出原样透传 TaskStore 结果 |
| 三 连接生命周期 | 后端 CRUD 复用 BackendRegistry 一等对象 |
| 四 双侧契约 | 后端管理 API 契约测试先于实现 |
| 五 可观测性 | `/admin` + SPA 暴露后端/健康/任务 |
| **六 Java 主力** | **前端（Vue/TS）= 展示层**，核心管理逻辑（CRUD/导出/校验/热重载）仍在 Java `/admin`；非 Java 仅作展示辅助。node/vite 工具链新增在 plan Complexity Tracking 论证（前端展示必需、核心逻辑不依赖） |
| 七 TDD | 后端 + 前端测试先于实现 |

---

## 九、演进项

- 任务结果可视化（frames 表格/图表）
- 健康/熔断监控 dashboard
- B 动态后端持久化 + 重启恢复
- D 操作历史/审计
- 管理面 Bearer token 鉴权
- 多服务器/多集群
```


---

## `docs/superpowers/specs/2026-07-07-portal-task-list-design.md`

```markdown
# portal 异步任务列表查询设计（004 增量）

> 日期：2026-07-07
> 归档：`specs/004-portal-backend-management/` 增量（不新建特性）
> 状态：设计待审阅
> brainstorming 产出，实施走 spec-kit SDD（更新 004 spec/tasks/contracts）

## 1. 背景与目标

004 portal 当前仅 `GET /admin/tasks/{taskId}/export` 按 taskId 导出单个**已完成**任务结果。运维在浏览器 portal 时无法浏览「所有/最近任务」，必须先知道 taskId 才能查。本增量补「异步任务列表查询」能力：列出任务摘要（不含 frames 的轻量视图），支持过滤 / 分页，前端在 `/tasks` 页加列表区，点列表项衔接现有导出流。

### 约束（来自 CLAUDE.md / 宪法）

- 复用 `com.arthas.gateway.task.TaskStore`（001/003 已有，`list()` / `list(TaskState status)` 已存在），**不引入新存储**。
- 前端=展示层，核心逻辑 Java 后端（宪法原则六）。
- 能力开关由 `application.yml` 配置驱动（`@ConditionalOnProperty`），**不在 portal 前端处理**开关逻辑——前端只是端点 404 时被动降级显示。
- TDD 真实环境，零桩（宪法原则七）：Service 单测可用 mock TaskStore；ContractIT 必须真实 Spring 上下文 + JDK HttpClient 赸实 HTTP 调用。

## 2. 决策摘要（brainstorming 结论）

| # | 决策点 | 选择 | 备选（已否） |
|---|--------|------|-------------|
| 1 | 前端形态 | 复用 `/tasks` 页加「最近任务」列表区，点列表项填 taskId 衔接导出 | 独立 `/tasks/list` 路由（功能分散）；列表为主、导出并入展开（去掉按 id 直查） |
| 2 | 列表契约 | 摘要 7 字段（**无 frames**）+ `createdAt` 倒序 + 标准分页 `?page&size` + `total` | 不截断（响应可能大）；上限截断（无 total，前端难翻页） |
| 3 | 过滤维度 | `status` + `tool` + `target` 三维度可选，默认不传=全部 | 仅 status（不够灵活）；不过滤（看失败需手翻） |
| 4 | 能力开关 | 复用 `arthas-gateway.admin.export.enabled`（与 export 共用，yaml 驱动） | 独立 `task-list.enabled`（YAGNI，无"只许看不许导出"需求） |
| 5 | 特性归档 | 004 增量（更新 004 spec/tasks/contracts） | 新建 `005-portal-task-list`（同属 portal，不值得拆） |

## 3. 后端 API 设计

### `GET /admin/tasks` — 列表查询

- **路径参数**：无
- **查询参数（全可选）**：

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `status` | enum: `COMPLETED`/`WORKING`/`FAILED`/`CANCELLED` | 不传=全部 | 复用 `TaskStore.list(TaskState)` 重载 |
| `tool` | string | 不传=全部 | 工具名**精确**匹配（如 `watch`、`jvm`），stream filter |
| `target` | string | 不传=全部 | target 名**精确**匹配（如 `debian-demo-business`），stream filter |
| `page` | int ≥ 0 | `0` | 页码（0-based） |
| `size` | int 1..100 | `20` | 每页条数；超 100 截断为 100，<1 取 1 |

- **响应 200**：
```json
{
  "items": [TaskSummaryDto],
  "total": 42,
  "page": 0,
  "size": 20
}
```
  - `total`：**过滤后、分页前**的总数（分页元数据一致性 invariant）
  - `items`：当前页的摘要列表，按 `createdAt` 倒序

- **TaskSummaryDto（7 字段 record，无 frames）**：
  `taskId` / `tool` / `target` / `status` / `createdAt` / `completedAt` / `isError`

- **行为**：
  1. `TaskStore.list()`（或 `list(status)` 当传 status）触发惰性清理 + 取快照
  2. stream `filter(tool)` + `filter(target)`
  3. `sorted(createdAt 倒序)`
  4. `total = count()`
  5. `skip(page*size).limit(size)` → items

- **错误**：列表查询不抛业务异常。空结果 → `{"items":[], "total":0, "page":0, "size":20}`（200，非 404）。`size`/`page` 越界由参数归一化处理（不返 400，clamp 到合法区间）。

- **能力开关**：`@ConditionalOnProperty(name="arthas-gateway.admin.export.enabled", havingValue="true", matchIfMissing=true)`，与 `TaskExportController` 共用 → 关闭时 `GET /admin/tasks` 与 `/{taskId}/export` **都 404**。

## 4. 后端组件（复用 + 最小新增）

| 组件 | 路径 | 职责 | 复用/新增 |
|------|------|------|-----------|
| `TaskSummaryDto` | `admin/task/dto/` | 7 字段 record | **新增** |
| `TaskListService` | `admin/task/` | `list(status, tool, target, page, size)` → 过滤/排序/分页 | **新增** |
| `TaskExportController` | `admin/task/` | 加 `@GetMapping`（无 `{taskId}`）收 query 参数 = 列表端点；与 `/{taskId}/export` 并存 | **复用**（已是 `@RequestMapping("/admin/tasks")` + 同开关，一个 controller 管该路径全部端点） |
| `AdminExceptionHandler` | `admin/` | 无需改（列表不抛新异常） | 不动 |
| `TaskStore` | `task/` | `list()` / `list(status)` 已存在 | 不动 |

**单一职责**：`TaskListService` 只管过滤/排序/分页（纯转换），不碰 HTTP；`TaskExportController` 只管 HTTP 绑定 + 开关；`TaskStore` 只管存储 + TTL。三者边界清晰。

## 5. 前端改造

### `TaskExportView.vue`（`/tasks` 页）

- **上方新增「最近任务」列表区**：
  - 进入页面 `onMounted` 自动调 `listTasks({page:0, size:20})` 查第一页
  - 摘要表：列 = `taskId` / `tool` / `target` / 状态徽标（复用 `HealthBadge` 风格，COMPLETED 绿/FAILED 红/WORKING 黄） / `createdAt`
  - 分页控件：上一页 / 下一页 + 当前页码（`page=0` 时禁用上一页；`items.length < size` 时禁用下一页）
  - status 过滤下拉：选项 `全部` / `COMPLETED` / `WORKING` / `FAILED` / `CANCELLED`，切换重查第一页
- **点击列表项** → 自动填入下方 `taskId` 输入框 + 触发 `query()`（无缝衔接现有导出流，复用 `exportTask` + `DownloadButton`）
- **下方保留**现有"按 taskId 查 + 下载"区不变

### `adminClient.ts`

- 新增类型 `TaskSummaryDto` / `TaskSummaryPage { items, total, page, size }`
- 新增 `listTasks(params: {status?, tool?, target?, page?, size?})` → 同源 `GET /admin/tasks`，返 `TaskSummaryPage`

## 6. 测试策略（TDD 红-绿-重构）

| 测试 | 类型 | 覆盖 |
|------|------|------|
| `TaskListServiceTest` | 单测（mock TaskStore） | status/tool/target 过滤组合；createdAt 倒序；page/size 分页；total = 过滤后总数；size clamp 100 |
| `TaskListContractIT` | @SpringBootTest + JDK HttpClient | HTTP `GET /admin/tasks` 各种参数；分页元数据 `total/page/size`；排序断言；空结果 200 |
| `AdminCapabilitySwitchIT` 扩展 | @SpringBootTest | `export.enabled=false` → `/admin/tasks` 也 404（与 export 同命运） |
| 前端 `TaskExportView.test` | vitest + @vue/test-utils | 列表渲染 / 点项填 taskId / 分页交互 / status 过滤切换 |

**真实性约束**：Service 单测 mock TaskStore（边界已验证的存储，mock 其返回的 `List<GatewayTask>`）；ContractIT **真实 Spring 上下文 + 真实 TaskStore Bean + 真实 HTTP**（put 几条真实 GatewayTask 进 store，再 JDK HttpClient 打 `/admin/tasks`），不 mock 网关内部。

## 7. 文档更新（004 增量）

- `specs/004-portal-backend-management/contracts/admin-api-contract.md` 加 **§2.1 `GET /admin/tasks`**（参数/响应/分页/排序）
- `specs/004-portal-backend-management/contracts/admin-invariants.md` 加：
  - **INV-LIST-1**：列表项 `TaskSummaryDto` **禁含 frames**（摘要纯，frames 仅由 `/{taskId}/export` 提供）
  - **INV-LIST-2**：`total` = 过滤后、分页前的总数（与 `items` 分页独立）
  - **INV-LIST-3**：`items` 按 `createdAt` 倒序（最新在前）
  - **INV-LIST-4**：`export.enabled=false` → `/admin/tasks` 与 `/admin/tasks/{id}/export` 同 404
- `specs/004-portal-backend-management/spec.md` 加 FR-015（列表查询）+ SC-005（列表/过滤/分页/点项导出端到端）
- `specs/004-portal-backend-management/tasks.md` 追加任务块（测试先于实现）
- `quickstart.md` 加场景 E：浏览任务列表 → 过滤 → 点项导出

## 8. 不在本范围（YAGNI）

- **模糊匹配** tool/target（MVP 精确匹配足够）
- **多排序字段**（仅 createdAt 倒序）
- **游标分页**（标准 offset 分页足够 MVP 量级）
- **任务统计聚合**（如按 tool 分组计数，留给后续）
- **WebSocket 实时推送**任务状态变化（刷新即可）
- **独立 `task-list.enabled` 开关**（无此细粒度需求）
```


---

## `docs/code-review/2026-06-20-business-code-review.md`

```markdown
# arthas MCP 网关业务代码审查报告

| 项目 | 内容 |
|------|------|
| 审查对象 | arthas MCP 网关 MVP 业务代码 |
| 审查范围 | `src/main/java/com/arthas/gateway/` 全部 38 个业务文件(整库审查,非 diff) |
| 审查日期 | 2026-06-20 |
| 审查力度 | max(10 finder 角度 → 对抗式验证 → sweep) |
| 涉及提交 | `4de3812 feat(001): 实现 arthas MCP 网关 MVP`(无 upstream,整库即本次实现) |
| 结论 | 5 项正确性/并发缺陷、2 项设计代价、1 项健壮性、2 项效率/资源、1 项安全卫生、4 项清理;另 3 项候选经验证驳回 |

---

## 一、执行摘要

整体而言,该 MVP 业务代码质量较高:**并发热点**(`BackendRegistry`/`RegistryHolder` 用 AtomicReference 整体替换 + record 不可变、`GatewayTask` 用 synchronized 终态转换 + volatile 字段、资源关闭普遍用 try-with-resources、错误码与结构化错误集中、注释为中文且描述意图)设计扎实,符合本项目宪法(优先官方 MCP SDK、错误显式传播、故障隔离)。

主要风险集中在 **`AsyncTaskExecutor` 的关闭竞态**(P0,资源泄漏/僵尸任务)与 **熔断器/异步路径的故障隔离一致性**(P1,作者部分已在注释中声明取舍)。本次共报告 **15 项发现**,按严重性分 P0–P3;另记录 **3 项经对抗式验证驳回** 的候选(含 SDK 反编译证据),避免后续重复怀疑。

**重要说明**:本报告标注了若干"作者已在 javadoc/注释中声明接受的取舍"(如 `CircuitBreaker` 非线程安全)。这些仍被报告,因为它们是真实的正确性风险;但报告同时注明其声明上下文,便于决策时权衡"MVP 可接受" vs "默认配置使前提存疑"。

---

## 二、审查范围与方法

### 2.1 审查范围

仅 `src/main/java/com/arthas/gateway/` 业务代码,排除 `src/test`、`src/main/resources`、`reference/`。模块分布:

| 包 | 职责 | 并发敏感性 |
|----|------|-----------|
| `backend/` | 后端配置/加载/监听、注册表、熔断器、HTTP 客户端、认证模式 | 高(注册表、熔断、热加载) |
| `task/` | 异步任务执行器、任务存储、任务状态机 | 高(线程池、任务并发) |
| `handler/` | MCP 工具路由、网关自有工具处理、诊断请求解析 | 中(请求并发) |
| `tool/` | 静态工具注册表、工具元数据 | 低(启动期构建) |
| `auth/` | 网关与后端认证定制 | 低 |
| `config/` | Spring 装配、配置属性、生命周期 | 中(bean 时序) |
| `obs/` | 健康指标 | 低(只读快照) |

### 2.2 审查方法(max effort)

1. **Phase 1 — 10 个独立 finder 角度**(每个至多 8 候选):
   - 正确性(5):逐行扫描、并发安全、跨文件契约追踪、Java/Spring 陷阱、包装/代理正确性。
   - 清理(3):重复实现、冗余复杂度、效率浪费。
   - 深度(1):实现深度(altitude)。
   - 规范(1):CLAUDE.md 一致性。
2. **Phase 2 — 对抗式验证**(3 态:CONFIRMED/PLAUSIBLE/REFUTED):对依赖 SDK 行为/契约的存疑高价值候选拦派独立 verifier,含 SDK jar 反编译取证。
3. **Phase 3 — sweep**:fresh 审查员只找前两阶段未覆盖的缺陷,聚焦尚未充分审查的文件与二线陷阱。
4. **人工把关**:审查员精读 15 个关键文件复核 finder 结论。

### 2.3 验证驳回的候选(详见第四节)

| 候选 | 裁决 | 关键证据 |
|------|------|----------|
| `ToolsCallRouter.forwardSync` 的 `McpError.getJsonRpcError()` 可能 NPE → 误熔断 | REFUTED | SDK `McpError(JSONRPCError)` 构造器对 null 输入先行 NPE,任何成功构造的实例 `jsonRpcError` 必非 null |
| `BackendAuthCustomizer.customize` 用 `header()` 会叠加重复 Authorization 头 | REFUTED | SDK transport 每请求 `Builder.copy()` 新建 builder 且只 customize 一次,transport 自身不设 Authorization |
| `BackendEntry` RETIRED 状态不被 `resolveTarget` 检查 → 退役后端仍可路由 | REFUTED | `resolveTarget` 每次读 `RegistryHolder.current()` 最新 registry,退役 entry 不在新 registry 中,天然不可达 |

---

## 三、发现清单

> 每项含:**位置** `file:line`、**严重性**、**问题描述**、**触发场景**、**修复方向**。

### P0 — 真实可触发,建议尽快修复(并发与资源泄漏)

#### P0-1 异步任务提交失败遗留僵尸 WORKING 任务

- **位置**:`task/AsyncTaskExecutor.java:95-99`
- **严重性**:中(关闭竞态,任务泄漏)
- **问题**:`submit` 中 `store.put(task)`(line 95)先于 `pool.submit(supervisor)`(line 97)。容器关闭竞态下 `pool.submit` 抛 `RejectedExecutionException`,task 已写入 `TaskStore` 且状态为 WORKING,但无 future 驱动。
- **触发场景**:`close()`→`pool.shutdownNow()` 与新的 ASYNC_TASK 调用竞态:`store.put` 成功后 `pool.submit` 抛 `RejectedExecutionException` 向上冒泡;`ToolsCallRouter.submitAsync` 的 catch 会 `releaseSlot`(槽不泄漏),但 `TaskStore` 残留一个永久 WORKING 任务。而 `TaskStore.isExpired`(line 103-105)仅清理终态任务,**WORKING 永不回收** → `task-get` 永远返 working,`task-list` 长期显示僵尸任务,任务无界泄漏。
- **修复方向**:`submit` 内将 `pool.submit(supervisor)` 包入 try-catch,捕获 `RejectedExecutionException` 时回滚 `store.remove(taskId)`(或标记为 FAILED)再向上抛;或在 `TaskStore` 增加 `remove` 语义。确保"任务入存储"与"后台入池"是原子可见的——任一失败都要么不入存储,要么补终态。

#### P0-2 后台编排期提交失败导致并发槽永久泄漏

- **位置**:`task/AsyncTaskExecutor.java:104`(以及 `handler/ToolsCallRouter.java:138-145` 的释放闭包)
- **严重性**:中(target 可被锁死)
- **问题**:`orchestrate` 中 `Future<CallToolResult> worker = pool.submit(backendWork)`(line 104)位于 `try` 块**之外**(try 只包 line 106 的 `worker.get`)。关闭竞态下该 `pool.submit` 抛 `RejectedExecutionException`,异常逃出 `orchestrate`,**backendWork callable 从未被调用** → `ToolsCallRouter.submitAsync` 闭包(line 138-145)的 `finally { entry.releaseSlot(); }` 永不执行。
- **触发场景**:容器关闭时一个已运行的 supervisor 虚拟线程进入 `orchestrate`,line 104 `pool.submit(backendWork)` 因池已 `shutdownNow` 立即抛 `RejectedExecutionException`;`backendWork` 未执行 → `releaseSlot` 不执行。该 target 的 `taskSlots`(`Semaphore`)累积泄漏 5 次后,所有后续请求(同步与异步)被 `concurrency_limit` 永久拒绝,target 彻底锁死。
- **修复方向**:把 `pool.submit(backendWork)` 纳入与 `worker.get` 同一 try,或在外层 catch `RejectedExecutionException` 显式释放槽;更彻底的做法是按 altitude 建议——将"获取/释放槽"封装为 `BackendEntry.withSlot(Callable)`(RAII 风格,acquire 与 release 在同一作用域强制配对),消除跨方法的隐式配对契约。P0-1 与 P0-2 同源(关闭路径未清理已分配资源),建议一并修复。

---

### P1 — 契约违背 / 设计代价 / 并发(需决策)

#### P1-1 熔断器非线程安全,默认配置使"串行化"前提不成立

- **位置**:`backend/CircuitBreaker.java:43-47`(无同步字段)、`27-28`(javadoc 声明)、`107`(`consecutiveFailures++`)、`84-89`(OPEN→HALF_OPEN 转换)
- **严重性**:中(故障隔离降级;**作者已声明取舍**)
- **问题**:5 个状态字段(`state`/`consecutiveFailures`/`consecutiveOpens`/`openedAtNanos`/`currentBackoffNanos`)均无 `volatile`/`synchronized`。javadoc line 27-28 声称"非线程安全——并发由 BackendEntry 的 taskSlots 或调用方串行化保证;MVP 单后端低并发可接受"。但 `BackendEntry.taskSlots` 是 `Semaphore`(允许多线程并发,**非互斥锁**),而 `ToolsCallRouter.forwardSync`(`98-122`)持 slot 后无额外同步地调用 `breaker.allowRequest()`/`recordSuccess()`/`recordFailure()`。
- **触发场景**(默认配置使取舍前提存疑):
  - **默认并发度 = 5**:`BackendConfigLoader.DEFAULT_MAX_CONCURRENT_TASKS = 5`(line 38)、`BackendConfig` 范围 `[1,5]`、`config/backends.yaml` 示例显式 = 5。即默认部署下每后端允许 5 路并发同时操作同一 breaker。
  - **丢失更新**:5 线程并发 `recordFailure` 时 line 107 `consecutiveFailures++` 非原子,连续故障计数可能只涨到 1-2 而不达阈值 3 → **熔断该开不开**,故障隔离失效。
  - **状态撕裂**:无 `volatile`,一线程 OPEN 后其他线程可能仍读到旧 CLOSED 短暂放行。
  - **HALF_OPEN 放多探测**:line 84-89 OPEN→HALF_OPEN 转换非原子,两线程可同时通过放 2 个探测,违反"HALF_OPEN 仅放 1 探测"语义。
- **修复方向**:两条路——
  - (A)接受取舍但使前提成立:把默认 `maxConcurrentTasks` 降为 1,或对 breaker 的公开方法加 `synchronized`(熔断非热路径,开销可接受)。
  - (B)按 altitude 建议,把熔断记录下沉到 `BackendClient`/transport 层统一分类(见 P1-3),router 不再手挂。

#### P1-2 STATELESS 后端未在异步路径校验(契约违背)

- **位置**:`handler/ToolsCallRouter.java:127-152`(`submitAsync` 未读 protocol);契约声明见 `backend/Protocol.java:11`
- **严重性**:中(契约违背)
- **问题**:`Protocol.java:11` 明确声明"决定是否可对该后端发起异步任务(STATELESS 后端的 optional 工具不可走 ASYNC_TASK)"。但 `submitAsync` 全程未读取 `entry.config().protocol()`,任何后端都可被提交异步任务。
- **触发场景**:运维配置一个 STATELESS 后端(无状态、纯 JSON 一来一回),用户对其调用 watch/trace/stack/tt/monitor(routingMode=ASYNC_TASK)→ `submitAsync` 不拦截 → 后台对无状态后端发带任务语义的同步 `tools/call`(期望轮询)→ STATELESS 后端无法承载 → 阻塞至 `backendTimeout`(11min)兜底超时后 `markFailed(backend_timeout)`。
- **修复方向**:`submitAsync` 解算 target 后、提交后台前校验 `entry.config().protocol()`,对 STATELESS 后端前置返 INVALID_PARAMS(结构化错误,reason 可用 `stateless_unsupported_async`)。可补一条契约测试守护。

#### P1-3 异步路径不驱动熔断,纯异步流量绕过故障隔离

- **位置**:`handler/ToolsCallRouter.java:134-135`(注释声明)、`forwardSync` 才 `recordFailure/recordSuccess`
- **严重性**:中(**有意设计**,但有功能代价)
- **问题**:`submitAsync` 注释明确"异步不改熔断",仅同步路径 `forwardSync` 调用 `recordFailure`/`recordSuccess`。注释解释原因(避免 cancel 中断误计 + 单异步失败误熔断拖累同步路径),但代价是**纯异步负载下的故障隔离失效**。
- **触发场景**:
  - 某 target 仅被 watch/trace 等 optional 工具高频调用:后端基础设施不可达 → 异步任务 `markFailed(backend_unreachable)`,但从不 `recordFailure`,熔断器永驻 CLOSED,每次异步任务仍耗满 11min 兜底超时。
  - 反向:若该 target 曾因同步故障进入 OPEN,退避期满后即便异步调用成功也不 `recordSuccess` → HALF_OPEN 永转不回 CLOSED,异步的 `guardCircuit` 守卫持续误拒。
- **修复方向**:与 P1-1 同源——熔断挂在 `BackendEntry` 却只被某条路径驱动,是错层。深层修法是把熔断记录下沉到 `BackendClient`/transport 拦截点统一分类(基础设施故障计、业务错误/cancel 中断不计),router 两条路径统一委托。退一步:异步路径的 `backend_unreachable`(非 cancel)也应 `recordFailure`,仅排除 cancel 中断。

#### P1-4 HttpBackendClient.initialize() 双重检查非原子

- **位置**:`backend/HttpBackendClient.java:46`(`volatile boolean initialized`)、`66-71`(check-then-act)
- **严重性**:中低(后果取决于 SDK `initialize` 幂等性)
- **问题**:`initialize()` 用 `volatile boolean initialized` 做 check-then-act(`if(!initialized){client.initialize(); initialized=true;}`)但非原子。并发首次路由两线程可同时读到 `initialized==false`,都进入 if 体各发一次 MCP initialize 握手。
- **触发场景**:同一 `BackendEntry` 并发到达两个 tools/call(如 list-targets 与 watch 同时首调同后端):两线程均通过 `if(!initialized)` → 各调一次 `client.initialize()`。SDK `McpSyncClient.initialize()` 重复握手可能覆盖 `Mcp-Session-Id` 或被后端拒绝,session 状态紊乱。`initialized` 虽 volatile,但缺 `synchronized`/`AtomicBoolean.compareAndSet` 守卫。
- **修复方向**:`initialize()` 改 `synchronized`(与 DCL 配合,或直接全同步,initialize 非热路径);或用 `AtomicBoolean.compareAndSet(false, true)` 守卫"执行 initialize"的入口,只有 CAS 成功者真正调用。

---

### P2 — 健壮性 / 效率 / 资源

#### P2-1 退役宽限与异步超时脱节,in-flight 异步任务被强制切断

- **位置**:`backend/BackendConfigWatcher.java:152-167`(`retireAll`);默认宽限 60s vs `backendTimeout` 11min
- **严重性**:中(破坏 §3 in-flight 完成承诺 + 资源堆积)
- **问题**:`retireAll` 对每个退役 entry 启动独立虚拟线程 `sleep(retirementGrace=60s)` 后 `client.close()`。但异步任务 `backendTimeout=11min`。退役 target 上若有 in-flight 异步任务,60s 后 `client.close()` 关闭后端会话,async 调用抛异常 → `markFailed(backend_unreachable)`,违反 `data-model.md §3`「in-flight 可完成」承诺。另:这些 sleep 虚拟线程无引用跟踪,`close()` 不回收,高频热重载会堆积。
- **触发场景**:热重载移除某 target 时其上有 3 个 in-flight watch(槽已占):`retireAll` `markRetired` + 60s 后 `client.close()` → 3 个 watch 的 `callTool` 抛 IOException → `markFailed`。用户期望 in-flight watch 完成返回诊断,实际 60s 后强制失败。
- **修复方向**:宽限应 ≥ `backendTimeout`(或按"所有槽归零"作为关 client 信号,而非固定 sleep);sleep 线程改为可追踪的 `ScheduledExecutorService`(容器关闭 graceful shutdown + awaitTermination)。

#### P2-2 诊断请求防御拷贝拒绝 null 值参数

- **位置**:`handler/DiagnosticRequest.java:39`(`Map.copyOf`)、`80`(`LinkedHashMap` 保留 null)
- **严重性**:中低(健壮性)
- **问题**:紧凑构造器 `backendArgs = Map.copyOf(backendArgs)`(line 39)。`Map.copyOf` 对 **null value 抛 NPE**(JDK 语义)。而 `stripTarget`(line 76-83)用 `new LinkedHashMap<>(arguments)` 原样保留 null value。arthas 工具的可选参数(如 watch 的 conditionExpr/timeout)合法可为 null。
- **触发场景**:客户端 tools/call watch 传 `{target:'jvm-1', classExpr:'Foo', conditionExpr:null}`(合法 JSON,null 表示无此可选参数):`stripTarget` 返回 `{classExpr:'Foo', conditionExpr:null}` → line 39 `Map.copyOf` 对 null value 抛 `NullPointerException` → 客户端收 INTERNAL_ERROR(-32603),而非正常路由转发或 INVALID_PARAMS。null 可选参数被错误掩盖为协议层错误。
- **修复方向**:防御拷贝改用容忍 null 的实现(如 `Collections.unmodifiableMap(new LinkedHashMap<>(backendArgs))`),或在 `stripTarget` 阶段过滤 null 值;保留 `Map.copyOf` 的不可变性但单独处理 null。

#### P2-3 TaskStore.get 单点查询触发全表清理(读放大)

- **位置**:`task/TaskStore.java:64-67`(`get` 无条件 `cleanExpired`)、`89-100`(全表 `removeIf`)
- **严重性**:低(O(1) 变 O(N))
- **问题**:`get(taskId)` 每次都调用 `cleanExpired()`,后者 `tasks.entrySet().removeIf` 全表遍历并逐个判 `isExpired`。本应是 O(1) 的单值查找被放大为 O(N)。
- **触发场景**:`task-get`/`task-cancel` 高频调用(如 Claude Code 轮询任务状态)时,每次单查都付全表扫描成本。task 多(异步长任务 + ttl 内终态任务堆积)时累计可观。
- **修复方向**:`get` 路径只对查到的那一条做过期判断(过期则 `remove(taskId)` 返 empty),过期清理交后台周期 `cleaner` 兜底;仅 `list` 路径保留全表清理。

#### P2-4 异步执行器线程池无全局背压

- **位置**:`task/AsyncTaskExecutor.java:72`(`newVirtualThreadPerTaskExecutor()`)
- **严重性**:低(**MVP 单后端掩盖**)
- **问题**:`pool` 为无界虚拟线程池。per-target 有 slot 限流(≤5),但**跨 target 累计并发无约束**。
- **触发场景**:调用方对 N 个 target 各发 5 个 async → N×5 个虚拟线程 + N×5 个 in-flight 后端连接(HttpClient 连接池、arthas JVM attach session)。虚拟线程虽轻,后端连接不轻;集群后端接入后 FD/堆内存可能先于 slot 限流触顶。
- **修复方向**:加全局 `Semaphore` 闸(或共享有界 `ExecutorService`),限制跨 target 累计并发上限。

---

### P3 — 安全卫生 / 清理

#### P3-1 Auth record 默认 toString 含明文凭据

- **位置**:`backend/BackendConfig.java:69`(`Auth` record)
- **严重性**:低(安全卫生,**当前未直接触发**)
- **问题**:`Auth` record(含 `token`/`username`/`password`)自动生成 `toString` 输出明文凭据。当前代码无直接 log 完整 `BackendConfig`(reloader 用 `equals` 比对、日志只打印 `name`),暂未泄漏,但属潜在凭据泄漏点。
- **触发场景**:任何未来调试/异常/堆栈打印 `BackendConfig` 或 `Auth`,凭据即写入日志/可观测系统。
- **修复方向**:重写 `Auth.toString` 脱敏(如仅输出 mode + 凭据掩码),或将凭据从 record 组件改为私有字段。

#### P3-2 healthy 判定三处重复,单一事实源缺失

- **位置**:`handler/GatewayToolHandlers.java:79`(listTargets)、`obs/BackendRegistryHealthIndicator.java`、`handler/ToolsCallRouter.java:155-161`(guardCircuit)
- **严重性**:低(可维护性)
- **问题**:`state==ACTIVE && breaker 未 OPEN` 这一布尔在 listTargets、HealthIndicator、guardCircuit 三处各自内联,无共享抽象。
- **触发场景**:未来若 HALF_OPEN 想视为 degraded、或 RETIRED 想移出 healthy,必须同时改三处——漏改即 health 端点说 healthy 但 router 拒路由(或反之),运维仪表盘与实际行为不一致。
- **修复方向**:抽 `BackendEntry.isHealthy()` 作为单一事实源,三处委托。

#### P3-3 ObjectMapper 三处重复构造

- **位置**:`handler/ToolsCallRouter.java:62`、`handler/GatewayToolHandlers.java:49`、`tool/StaticToolRegistry.java:67`
- **严重性**:低(清理)
- **问题**:`new ObjectMapper()` 在三个类各 new 一份;Jackson 3.x(`tools.jackson.databind`)线程安全,惯例应全局单例。另:`asyncAcceptedResponse` 的 `CallToolResult` 构造与 `GatewayToolHandlers.json()`(line 201-203)逐字重复。
- **修复方向**:抽 `handler` 包内 `McpJson` 工具类承载单例 + `json()` 封装(符合 CLAUDE.md「优先复用既有库」)。

#### P3-4 配置解析静默截断浮点数 / 超大整数错误信息误导

- **位置**:`backend/BackendConfigLoader.java:184`(`asInt`)、`80`(`readVersion`)
- **严重性**:低(配置校验健壮性)
- **问题**:`asInt`/`readVersion` 用 `instanceof Number` 接受浮点数并 `intValue()`/`longValue()` 静默截断;超大 int 截断为负数后错误信息丢失原始值。
- **触发场景**:YAML 写 `version: 1.0` 或 `maxConcurrentTasks: 5.0`(SnakeYAML 解析为 Double)→ 截断静默通过校验;写 `connectTimeoutMs: 2147483648`(超 int 上限)→ `intValue()` 截为 -2147483648 → BackendConfig 报"须为正数,实得 -2147483648"误导排查。
- **修复方向**:`asInt` 校验 `Number` 为整数类型(`Integer`/`Long`)拒浮点(`Double`/`Float`),错误信息保留原始值。

#### P3-5 死代码与等价复制

- **位置**:`tool/ExposedTool.java:35`(`gatewayOwned()`,全库无调用,路由器 line 83 直接裸比较 `routingMode==GATEWAY_LOCAL`)等
- **严重性**:低(清理)
- **问题**:`gatewayOwned()` 定义但无调用;另有 `task/TaskError.java` 的 `REASON_CIRCUIT_OPEN`(自称 US3 future 但已落地未用)、`tool/TaskSupport.java` 的 `wireValue()`(对称臆测产物,仅反向 `fromWire` 被用)、`backend/BackendConfigLoader.java:179` 的 `asNullableString`(与 `asString` 字节码完全等价,且命名误导:暗示不返 null 实际返 null)。
- **修复方向**:删除未用方法/常量,或保留并注释"未用";合并 `asNullableString` 与 `asString`。

---

## 四、验证裁决记录(已驳回候选)

> 记录驳回项及其证据,避免后续重复怀疑;体现"证据驱动,禁止臆测"。

### REFUTED-1 `McpError.getJsonRpcError()` NPE 导致误熔断

- **候选**:`ToolsCallRouter.forwardSync` 的 `catch(McpError e)`(line 109-114)调用 `e.getJsonRpcError().code()/.message()`,若返回 null 则 NPE,被同方法 `catch(RuntimeException)`(line 115)捕获 → `recordFailure` 误熔断,与"业务错误不计熔断"语义相反。
- **裁决**:**REFUTED**。
- **证据**:反编译 SDK `mcp-core-2.0.0.jar` 的 `io.modelcontextprotocol.spec.McpError`:
  - 字段 `jsonRpcError` 为 `private`,无 setter。
  - 唯一公共构造器 `McpError(JSONRPCError)` 第一步 `invokevirtual JSONRPCError.message()`,**对 null 输入先行 NPE**(在构造器内、对象未发布),任何成功构造的实例 `jsonRpcError` 必非 null。
  - `Builder.build()` 强制 `new JSONRPCError(...)`(非 null)传入构造器。
  - 故 `getJsonRpcError()` 不可能返回 null,NPE→误熔断链不可达。
- **备注**:`getJsonRpcError()` 是无 null 防护的 `getfield`,依赖"构造器不变式",属脆弱设计但非当前缺陷。若未来 SDK 新增不带 JSONRPCError 的构造路径,本项可能转为 PLAUSIBLE。

### REFUTED-2 BackendAuthCustomizer 用 `header()` 叠加重复 Authorization 头

- **候选**:`BackendAuthCustomizer.customize`(`auth/BackendAuthCustomizer.java:56`)用 `java.net.http.HttpRequest.Builder.header(AUTHORIZATION, ...)`(追加语义),担心叠加多个 Authorization 头导致后端 401,建议改 `setHeader`。
- **裁决**:**REFUTED**(建议本身可行,但危害不成立)。
- **证据**:
  - JDK `HttpRequest.Builder` **确实同时有** `header`(追加)与 `setHeader`(覆盖),故"改用 setHeader"在 API 层成立(非伪命题)。
  - 反编译 SDK transport:`HttpClientStreamableHttpTransport` 的两条出站路径(sendMessage、reconnect)均**先 `Builder.copy()` 得到全新 builder**,再设 transport 头,最后 `customize` 该 copy——**customizer 在每请求的新 builder 上只调用一次**。
  - transport 自身设的头(`Mcp-Session-Id`/`Accept`/`Content-Type`/`Cache-Control`/`MCP-Protocol-Version`/`Last-Event-ID`)**不含 Authorization**。
  - 故既无"同 builder 多次 customize",也无"与 transport 自带头叠加",重复头不产生。`header` 与 `setHeader` 在此场景行为等价,改 `setHeader` 仅为防御性风格偏好(YAGNI)。

### REFUTED-3 RETIRED 状态不被 resolveTarget 检查导致退役后端仍可路由

- **候选**:`BackendEntry.markRetired`(line 58)仅设 `state=RETIRED`,但 `ToolsCallRouter.resolveTarget`(line 195-204)不检查 `state==ACTIVE`,退役后端仍可被路由,破坏 `data-model.md §3`「新调用不再路由到 RETIRED」。
- **裁决**:**REFUTED**(机制不成立)。
- **证据**:`resolveTarget` 每次 `registry.get(dr.target())`,而 `registry` 是 `RegistryHolder` 字段——其 `get` 读 `current()` 最新 registry。热重载时 `BackendRegistryReloader.reload` 构造的新 registry **只含 unchanged + added/changed entry**,**toRetire entry 不进新 registry**;`holder.getAndSet` 原子替换后,`current()` 不再含退役 entry。故 `resolveTarget` 天然取不到 RETIRED entry,RETIRE 的拦截靠 registry 替换实现而非 state 检查。`state` 字段主要服务于语义标记(in-flight 调用持有的旧 entry 引用仍可完成)。设计自洽。

---

## 五、修复优先级总览

| 优先级 | 编号 | 位置 | 主题 | 工作量 |
|--------|------|------|------|--------|
| **P0** | P0-1 | `AsyncTaskExecutor.java:95-99` | submit 失败遗留僵尸 WORKING 任务 | 小 |
| **P0** | P0-2 | `AsyncTaskExecutor.java:104` | orchestrate 提交失败致槽泄漏 | 小(与 P0-1 同源) |
| **P1** | P1-1 | `CircuitBreaker.java` | 熔断器非线程安全(默认并发 5) | 中 |
| **P1** | P1-2 | `ToolsCallRouter.java:127` | STATELESS 未在异步路径校验 | 小 |
| **P1** | P1-3 | `ToolsCallRouter.java:134` | 异步不驱动熔断(错层) | 中-大 |
| **P1** | P1-4 | `HttpBackendClient.java:66` | initialize 双重检查非原子 | 小 |
| **P2** | P2-1 | `BackendConfigWatcher.java:152` | 退役宽限与异步超时脱节 | 中 |
| **P2** | P2-2 | `DiagnosticRequest.java:39` | 防御拷贝拒绝 null 值参数 | 小 |
| **P2** | P2-3 | `TaskStore.java:64` | get 读放大(O(1)→O(N)) | 小 |
| **P2** | P2-4 | `AsyncTaskExecutor.java:72` | 线程池无全局背压 | 小-中 |
| **P3** | P3-1 | `BackendConfig.java:69` | Auth toString 含明文凭据 | 小 |
| **P3** | P3-2 | `GatewayToolHandlers.java:79` | healthy 三处重复 | 小 |
| **P3** | P3-3 | `ToolsCallRouter.java:62` | ObjectMapper 三份重复 | 小 |
| **P3** | P3-4 | `BackendConfigLoader.java:184` | 浮点静默截断 | 小 |
| **P3** | P3-5 | `ExposedTool.java:35` 等 | 死代码 | 小 |

### 建议推进顺序

1. **P0-1 + P0-2 一并修**:同源(关闭竞态未清理资源),改动小、风险高(可致 target 锁死),优先。
2. **P1-2(STATELESS 校验)**:一行契约修复,改动小、收益明确。
3. **P1-1 + P1-3 + P1-4 一起评估**:三者均围绕"熔断/限流/认证应在哪一层",建议按 altitude 方向统一(下沉到 BackendClient/entry),避免在 router 两条路径分别手挂。
4. **P2 按需**:P2-2(null 参数)影响正常 arthas 调用健壮性,建议早修;P2-1/P2-3/P2-4 可纳入下一轮。
5. **P3 批量清理**:低风险,可一次重构收口(ObjectMapper 单例 + 死代码删除 + 脱敏)。

---

## 六、附录

### 6.1 审查覆盖文件清单

精读(审查员把关):`CircuitBreaker`、`AsyncTaskExecutor`、`TaskStore`、`BackendRegistryReloader`、`HttpBackendClient`、`ToolsCallRouter`、`BackendEntry`、`BackendAuthCustomizer`、`BackendConfigWatcher`、`GatewayMcpServerConfig`、`Protocol`、`BackendConfig`、`BackendConfigLoader`、`GatewayToolHandlers`、`DiagnosticRequest`。

finder 覆盖:全 38 个业务文件。

### 6.2 未发现问题的区域(正向确认)

为避免"未报告即未审查"的误解,以下区域经审查确认设计正确:

- **注册表并发**:`BackendRegistry`(不可变 record + `Map.copyOf`)、`RegistryHolder`(`AtomicReference.getAndSet` 整体替换)、`BackendRegistryReloader`(`IdentityHashMap` 去重复用 entry)——并发安全。
- **任务状态机**:`GatewayTask`(synchronized 终态转换 + volatile 字段,终态转换后 `result`/`error` 发布顺序正确,幂等)——并发安全。
- **资源关闭**:`BackendConfigWatcher.reloadOnce`、`BackendRegistryBootstrap.load`、`StaticToolRegistry.fromClasspath` 均正确 try-with-resources。
- **Optional 使用**:`ToolsCallRouter.resolveTarget`、`GatewayToolHandlers.taskGet/taskCancel` 均先 `isEmpty`/`orElseThrow` 校验。
- **Spring 装配**:`@Primary` 解决 customizer 多候选、`destroyMethod=infer` 生命周期回调齐全、`@ConfigurationProperties` 标准绑定,无循环依赖/装配时序错误。
- **长比较**:`version` 比较用基本类型 `long ==`,无包装缓存陷阱。

### 6.3 备注

- 本报告为只读分析,未修改任何代码。
- 按本项目 `CLAUDE.md`,代码落地需走 spec-kit SDD 流程(测试先于实现);如需落实修复,建议在 `specs/001-arthas-mcp-gateway/tasks.md` 新增对应任务并按 TDD 推进。
```


---

## `docs/code-review/2026-06-21-002-remediation-completion-report.md`

```markdown
# arthas MCP 网关 002 代码评审整改完成报告

| 项 | 值 |
|---|---|
| 特性 | `specs/002-code-review-remediation`(对 001 MVP 整库评审 15 项发现的整改) |
| 评审基线 | [2026-06-20-business-code-review.md](2026-06-20-business-code-review.md)(15 项发现,P0–P3) |
| 设计文档 | `docs/superpowers/specs/2026-06-21-code-review-remediation-design.md` |
| 工作流 | spec-kit SDD(specify → plan → tasks → 实现),测试先于实现(红-绿-重构) |
| 完成日期 | 2026-06-21 |

## 一、结论先行

**15 项发现全部修复并验证通过,对外 MCP 行为零回归(FR-016/FR-017)。**

- **回归门禁**:`./mvnw verify` **全绿**——surefire **182** 个单元/契约测试 + failsafe **20** 个真实后端 IT(9 个 `*IT`,真实 arthas + 真实业务服务,官方 MCP Java SDK client 确定性断言),合计 **202** 测试 0 失败 0 错误 0 跳过,`BUILD SUCCESS`。
- **端到端冒烟(SC-008)**:真实 Claude Code(`claude -p --mcp-config`)经网关调通——`list-targets`(双 target healthy)、`jvm target=order-service`(真实 JVM 诊断)、`watch`→`task-get`(异步 taskId 全流程 + 5 帧真实业务诊断),均非桩。
- **整改原则落实**:统一拦截层(`BackendEntry`)收口熔断/取还槽/分类;域异常与 `McpError`/注册表解耦;全局背压(动态 cap);健康判定/JSON 序列化单一事实源;凭据脱敏;严格配置解析;死代码清除。

> 驱动分层(宪法原则四/七):工具**可用性**用真实 Claude Code 走 MCP;**契约一致性**与**双侧协议**用官方 MCP Java SDK client 确定性断言;非裸 curl。IT 与冒烟均零桩——arthas、业务服务、诊断数据全真实。

## 二、15 项发现逐项映射(发现 → FR → 实现 → 测试 → 验证)

> 下表「修复实现」列为关键改动锚点(文件/机制);「验证测试」列均为真实环境测试(红-绿-重构,无桩冒充成功)。

### P0 — 并发与资源泄漏(US1,已收口)

| # | 发现(评审位置) | FR | 修复实现 | 验证测试 | 状态 |
|---|---|---|---|---|---|
| P0-1 | 异步提交失败遗留僵尸 WORKING 任务(`AsyncTaskExecutor`) | FR-001 | 统一拦截层:提交失败时任务回滚至终态(FAILED),不遗留僵尸;编排与提交纳入同一 try | `AsyncTaskExecutorShutdownRaceTest`(4) | ✅ |
| P0-2 | 编排期提交失败致并发槽永久泄漏(`AsyncTaskExecutor`) | FR-002 | 槽配对收口至 `BackendEntry`(admit 取 / `onTerminal=releaseSlot` 还);路由器闭包不再手动 release;`GlobalConcurrencyLimitException` 时显式释放目标槽 | `AsyncTaskExecutorShutdownRaceTest`(4) | ✅ |

### P1 — 契约违背 / 设计代价 / 并发(US2/US3)

| # | 发现(评审位置) | FR | 修复实现 | 验证测试 | 状态 |
|---|---|---|---|---|---|
| P1-1 | 熔断器非线程安全(`CircuitBreaker`) | FR-003 | 熔断状态读写原子化(并发累加/开启/半开/恢复无丢失更新) | `CircuitBreakerConcurrencyTest`(2)、`CircuitBreakerTest`(10) | ✅ |
| P1-2 | STATELESS 后端未在异步路径校验(`ToolsCallRouter`) | FR-004 | `BackendEntry.admit` 首检协议:STATELESS→`StatelessAsyncException`(路由器翻译 INVALID_PARAMS + `reason=stateless_unsupported_async`),前置拒绝、不提交后台 | `ToolsCallRouterStatelessTest`(2) | ✅ |
| P1-3 | 异步路径不驱动熔断(`ToolsCallRouter`) | FR-005 | 熔断记录下沉至 `BackendEntry.invoke`(同步/异步共用):成功/业务错误 recordSuccess,基础设施故障 recordFailure;**cancel 中断跳过 recordFailure**(检测 `Thread.interrupted()`) | `BackendEntryInterceptionLayerTest`(14) | ✅ |
| P1-4 | `initialize()` 双重检查非原子(`HttpBackendClient`) | FR-006 | `initializeOnce` 上移至拦截层,双检锁(volatile + synchronized):首个持锁者握手,余者等待(裸 CAS 会让 loser 抢跑) | `BackendEntryInterceptionLayerTest`(14) | ✅ |

### P2 — 健壮性 / 效率 / 资源(US4)

| # | 发现(评审位置) | FR | 修复实现 | 验证测试 | 状态 |
|---|---|---|---|---|---|
| P2-1 | 退役宽限与异步超时脱节,in-flight 异步被强制切断(`BackendConfigWatcher`) | FR-007 | 退役宽限默认 = `backendTimeout`(11min,保证 in-flight 完成);裸 `Thread.startVirtualThread(sleep)` 改可追踪 `ScheduledExecutorService`,`close()` 时 `awaitTermination` 优雅回收 | `HotReloadIT`(3) | ✅ |
| P2-2 | 诊断请求防御拷贝拒绝 null 值参数(`DiagnosticRequest`) | FR-008 | 防御拷贝改 `Collections.unmodifiableMap(new LinkedHashMap<>(backendArgs))`(容忍 null value) | `DiagnosticRequestNullArgTest`(1) | ✅ |
| P2-3 | `TaskStore.get` 单点查询触发全表清理(读放大)(`TaskStore`) | FR-009 | `get(taskId)` 仅判查到那一条(O(1));过期→`remove` 返 empty;全表 `cleanExpired()` 仅留 `list` + 后台 cleaner;包私有探针 `containsRawForTest` | `TaskStoreGetReadAmplificationTest`(3) | ✅ |
| P2-4 | 异步执行器无全局背压(`AsyncTaskExecutor`) | FR-010 | 全局背压 `AtomicInteger globalInflight` + 动态 cap(=注册表后端数 × 5,或配置 `arthas-gateway.task.global-max-inflight`);submit 取 / 编排 finally 释放;路由器翻译 INVALID_PARAMS + `reason=global_concurrency_limit` + `globalMaxInflight`/`available` | `GlobalBackpressureTest`(2) | ✅ |

### P3 — 安全卫生 / 清理(US5)

| # | 发现(评审位置) | FR | 修复实现 | 验证测试 | 状态 |
|---|---|---|---|---|---|
| P3-1 | Auth record 默认 toString 含明文凭据(`BackendConfig`) | FR-011 | `Auth.toString` 覆写:仅 mode + 掩码(`****` + 末 2 位;凭据 ≤2 位仅 `****`,不泄露任何明文片段) | `BackendConfigAuthMaskingTest`(4) | ✅ |
| P3-2 | healthy 判定三处重复,单一事实源缺失(`GatewayToolHandlers`/`HealthIndicator`) | FR-012 | `listTargets` 与 `HealthIndicator` 的 healthy 均委托 `BackendEntry.isHealthy()`(`state==ACTIVE && breaker!=OPEN`) | `HealthSingleSourceTest`(2)(钉三处一致) | ✅ |
| P3-3 | ObjectMapper 三处重复构造(`ToolsCallRouter`/`GatewayToolHandlers`/`StaticToolRegistry`) | FR-013 | 抽 `McpJson` 单例(`static final ObjectMapper MAPPER` + `json(Object)` 封装);三处 `new ObjectMapper()` 改委托 | `McpJsonTest`(3) | ✅ |
| P3-4 | 配置解析静默截断浮点 / 超大整数(`BackendConfigLoader`) | FR-014 | `asInt` 仅 Integer/Long(int 范围)通过,拒 Double/Float/超界 Long,错误信息含原始值;`readVersion` 同理拒浮点 | `BackendConfigLoaderParsingTest`(4) | ✅ |
| P3-5 | 死代码与等价复制(`ExposedTool`/`TaskError`/`TaskSupport`/`BackendConfigLoader`) | FR-015 | 删 `ExposedTool.gatewayOwned()`、`TaskError.REASON_CIRCUIT_OPEN`、`TaskSupport.wireValue()`;合并 `asNullableString`→`asString`(字节级等价) | 全量编译 + `./mvnw verify` 全绿 | ✅ |

## 三、对外报文字段逐字一致性(FR-016 核对)

整改**不改变**对外可观测 MCP 行为,错误翻译字段集与修复前逐字保持。由真实后端 IT(官方 SDK client 确定性断言)+ 路由器单测共同守护:

| 错误场景 | 错误码 | data 字段(逐字保持) | 守护测试 |
|---|---|---|---|
| 后端不可达 / 熔断 OPEN | `backend_unreachable` | `target`、`reason`、`available`、`retryAfterMs` | `FaultIsolationContractIT`(4)、`FailedTargetErrorIT`(2) |
| 并发越界(单 target) | INVALID_PARAMS | `target`、`reason`、`maxConcurrentTasks` | `ToolsCallRouterTest`(3) |
| STATELESS 异步(新增拒绝,**新行为**,非回归) | INVALID_PARAMS | `target`、`reason=stateless_unsupported_async`、`available` | `ToolsCallRouterStatelessTest`(2) |
| 全局并发越界(新增背压,**新行为**,非回归) | INVALID_PARAMS | `target`、`reason=global_concurrency_limit`、`globalMaxInflight`、`available` | `GlobalBackpressureTest`(2) |

> STATELESS 拒绝与全局背压是**新增**的结构化错误(原为隐式兜底超时/无保护),属整改新增能力,非 FR-016 回归。其余错误码与字段集与 001 基线逐字一致。

## 四、验证证据

### 4.1 回归门禁 `./mvnw verify`

```
surefire:  Tests run: 182, Failures: 0, Errors: 0, Skipped: 0
failsafe:  Tests run: 20,  Failures: 0, Errors: 0, Skipped: 0   (9 个真实后端 *IT)
BUILD SUCCESS   Total time: 53.322 s
```

9 个 IT(真实 arthas 4.3.0 + 真实业务 JVM + 官方 MCP Java SDK client):
`BackendClientContractIT`、`FaultIsolationContractIT`、`ToolsCallRoutingContractIT`、`ArthasMcpBackendIT`、`AsyncTaskContractIT`、`AsyncTaskTimeoutIT`、`FailedTargetErrorIT`、`HotReloadIT`、`ResultConsistencyIT`。

### 4.2 端到端冒烟 `claude -p`(真实 Claude Code,SC-008)

| 场景 | 工具 | 结果 | 证据(节选) |
|---|---|---|---|
| 网关自有工具 | `arthas-gateway.list-targets` | ✅ `num_turns=2`、`permission_denials=[]` | `{"version":1,"targets":[{"name":"order-service","state":"ACTIVE","healthy":true,"protocol":"STREAMABLE"},{"name":"payment",...}]}` |
| 同步诊断 | `jvm target=order-service` | ✅ 真实 JVM 诊断 | `SPEC-VERSION=21`、`VM-VERSION=21.0.5+9-LTS-239`、`INPUT-ARGUMENTS=[...,-Ddemo.slowMs=0]`(业务服务入参铁证) |
| 异步诊断 | `watch`→`arthas-gateway.task-get` | ✅ taskId 全流程 | step1 `status=working,taskId=t-116700`;step2 `status=completed`;5 帧真实 `hotMethod`(`OrderResult[orderId=176,price=5463,valid=true]`) |

冒烟产物存于 `target/smoke-002-listtargets.json`、`target/smoke-002-jvm.txt`、`target/smoke-002-watch.txt`。

## 五、整改新增/变更的对外能力(非回归,登记备查)

- **STATELESS 异步前置拒绝**(FR-004):无状态后端的异步诊断调用现前置返 INVALID_PARAMS + `reason=stateless_unsupported_async`,不再隐式提交后台阻塞至兜底超时。
- **全局跨 target 背压**(FR-010):异步并发新增全局上限(动态 = 后端数 × 5,可配置),触顶返 INVALID_PARAMS + `reason=global_concurrency_limit` + `globalMaxInflight`。

两者均为评审发现点名的**新增保护**,不破坏任何既有合法调用路径(同步对 STATELESS 仍可用;单 target 限额不变)。

## 六、未纳入本次范围

- 评审第四节「验证裁决记录」的 3 项候选(REFUTED-1/2/3)经对抗式验证驳回,不修复(含 SDK 反编译证据)。
- Git 提交:按用户决策,本次仅工作区文件改动,**不 commit git**。

---

**整改完成**:15/15 发现修复,202 测试全绿,端到端冒烟通过,对外行为零回归。
```

