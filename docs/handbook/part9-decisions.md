# Part 9 · 设计决策全（research.md 决策逐条）

> 本部分汇总 4 特性 `research.md` 的全部技术决策（R1–R13 等）+ brainstorming 设计要点。每条含「决策 + 理由 + 替代方案 + 代码锚点」。

---

## 第 67 章 001 诊断聚合决策

### R1 · 方案 C 异步任务（应用层任务系统）

- **决策**：5 长耗时工具（watch/trace/stack/tt/monitor）后台化，立即返 `taskId`，应用层 `GatewayTask` 状态机 + `TaskStore`，不暴露 arthas 协议层真 task。
- **理由**：arthas 协议层 task 有 `INPUT_REQUIRED` 多档复杂态；应用层任务系统简化为 `WORKING/COMPLETED/FAILED/CANCELLED` 4 态，够用且与 MCP 协议无关。
- **替代方案**：A（同步阻塞，会话挂死）/ B（MCP 协议层 task，复杂且 spec 不稳定）—— 均否。
- **代码**：`AsyncTaskExecutor`（嵌套 submit + 全局背压）+ `GatewayTask`（状态机）+ `TaskStore`（TTL）。

### R2 · target 参数注入与剥离

- **决策**：31 arthas 工具的 inputSchema 注入必填 `target`（required 首位）；`DiagnosticRequest.stripTarget` 在网关剥离，**target 永不进 backendArgs**。
- **理由**：消除「同工具名跨后端歧义」（原则二）；target 永不进后端（避免 arthas 因未知参数返 INVALID_PARAMS）。
- **代码**：`StaticToolRegistry.buildArthasTool`（注入）+ `DiagnosticRequest.stripTarget`（剥离）。

### R3 · 每后端独立 BackendClient（非单例）

- **决策**：`BackendEntryFactory.create` 每次新建 `HttpBackendClient`（独立 `HttpClientStreamableHttpTransport` + 独立 `McpSyncClient` 会话 + 独立 SSE 解析）。
- **理由**：宪法原则三「局部故障韧性」——独立连接池/会话/SSE 使单后端熔断/慢/挂不波及其他。
- **替代方案**：单例 client（共享连接池）—— 故障扩散，否。

### R4 · capabilities 锁定（仅 tools, listChanged=false）

- **决策**：`@Primary McpSyncServerCustomizer` 覆盖 Spring AI starter 默认，锁定「仅 tools、listChanged=false、不声明 prompts/resources/logging/completions」。
- **理由**：工具集静态（启动期固定 38），不应广播 listChanged=true 让客户端重拉；不提供 prompts/resources（MVP 范围）。
- **代码**：`GatewayMcpServerConfig.gatewayCapabilitiesCustomizer`（`:133-144`）。

### R5 · 配置外置 + WatchService 热重载

- **决策**：后端列表**不走 `@ConfigurationProperties`**（与热重载语义冲突），`GatewayProperties` 只持「文件位置」；`BackendConfigLoader` 纯逻辑解析（SnakeYAML）；`BackendConfigWatcher` 监听 + 防抖 + 原子替换。
- **理由**：热重载需运行时重读文件 + 原子切换注册表，`@ConfigurationProperties` 启动期一次性绑定不适用。

### R6 · MVP 仅 Streamable HTTP（stdio 延后）

- **决策**：`spring.ai.mcp.server.protocol=STREAMABLE`，不开 stdio。
- **理由**：Spring AI starter 的 stdio 与 HTTP 互斥（[memory sdk2-vs-spec-divergences]）；MVP 选 HTTP（适配 Claude Code 远程 + portal 同源）。

### R7 · MCP 端点 = 根 URL（无 /mcp 后缀）

- **决策**：`BackendConfig.url` 直接用 `http://host:8563`（arthas MCP 根），非 `/mcp`。
- **理由**：arthas 4.3.0 MCP 端点搭乘 http console 根（T009 实测裁决），加 `/mcp` 后缀反而 404。

### R8 · MVP 无入站认证（Noop）

- **决策**：`NoopGatewayAuthenticator` 恒放行（受控内网假设）；出站认证（连后端）支持 BEARER/BASIC。
- **理由**：MVP 受控内网；入站 Bearer token 列演进项。

### R9 · 虚拟线程承载异步

- **决策**：`AsyncTaskExecutor` / `BackendConfigWatcher` 监听 / retireScheduler 皆虚拟线程（`Thread.ofVirtual()`）。
- **理由**：JDK 21 虚拟线程轻量（每任务一线程，互不阻塞），适配「多后端并发 + 长任务」。

### R10 · registry 原子替换（AtomicReference + 不可变 record）

- **决策**：`RegistryHolder` AtomicReference + `BackendRegistry` 不可变 record + `Map.copyOf`。
- **理由**：一次 `tools/call` 全程持固定 `final BackendEntry` 引用，registry 中途替换不影响 in-flight（热重载/动态注册并发不串台）。

### R11 · 官方 MCP Java SDK（不手写 JSON-RPC）

- **决策**：用 `io.modelcontextprotocol.sdk:mcp` 2.0.0 实现协议核心。
- **理由**：宪法「优先官方 SDK」；手写 JSON-RPC 帧易错且不符合规范。

### R12 · arthas 工具定义静态摘抄（不从后端动态发现）

- **决策**：31 arthas 工具 schema 摘抄自 arthas 4.3.0 源码（`arthas-tools.json`），不从各后端动态发现。
- **理由**：宪法原则二 v1.2.0——静态摘抄保证「忠实反映 arthas 规范能力」，动态发现可能各后端不一致。

---

## 第 68 章 002 整改决策

### 002-1 · 统一拦截层下沉（P1-1/3/4）

- **决策**：原散在 `ToolsCallRouter` 两路径的「熔断守卫 + 取/还槽 + initialize + 故障分类」下沉到 `BackendEntry`（execute/admit/invoke/isHealthy 四原语）。
- **理由**：同步/异步共用同一分类规则（避免异步路径绕过熔断，P1-3）；熔断器线程安全（synchronized，P1-1）；initialize 原子（DCL，P1-4）。
- **代码**：`BackendEntry.java:94-190`。

### 002-2 · 域异常 + 路由器翻译（错误边界分离）

- **决策**：`BackendEntry` 抛**域异常**（`CircuitOpenException`/`ConcurrencyLimitException`/`BackendUnreachableException`/`StatelessAsyncException`/`GlobalConcurrencyLimitException`，不依赖 McpError/注册表）；`ToolsCallRouter` 翻译为结构化 `McpError(INVALID_PARAMS, data{...})`。
- **理由**：`data.available` 需 `RegistryHolder` 才能填，故翻译在路由器；域异常不依赖 McpError/注册表，便于单测。

### 002-3 · 全局背压（P2-4）

- **决策**：`AsyncTaskExecutor` 全局 `AtomicInteger globalInflight`，cap 动态 `max(1, 后端数×5)`。
- **理由**：跨 target 累计并发上限，避免集群规模下后端连接/资源无界增长。

### 002-4 · TaskStore 三路清理（P2-3 读放大）

- **决策**：`get` 单条惰性（O(1) 定向 remove）；`list` 全表惰性；cleaner 守护线程兜底。
- **理由**：避免高频单点查询的 O(N²) 读放大。

### 002-5 · 退役宽限 = backendTimeout（P2-1）

- **决策**：`retirementGrace` 默认 = `backendTimeout`（11min）；裸虚拟线程改 `ScheduledExecutorService`。
- **理由**：保证 in-flight 异步任务（最长 11min）能在 client 关闭前完成。

### 002-6 · null 值容忍（P2-2）

- **决策**：`DiagnosticRequest.backendArgs` 用 `Collections.unmodifiableMap(new LinkedHashMap<>(...))` 而非 `Map.copyOf`。
- **理由**：MCP SDK 反序列化可选参数可能传 `{target:"x",timeout:null}`，`Map.copyOf` 拒 null 抛 NPE 误伤。

### 002-7 · 安全卫生收敛（P3 组）

- **P3-1** Auth.toString 脱敏（`****XX`）。
- **P3-2** 健康单一事实源（`BackendEntry.isHealthy`）。
- **P3-3** `McpJson` 全局单例。
- **P3-4** asInt 严格（拒浮点/超界，保留原始值）。
- **P3-5** 死代码/等价复制清理。

---

## 第 69 章 003 K8S 编排决策（R1–R8）

### R1 · 模块化单体（包级边界，非 Maven 多模块）

- **决策**：P1 用包级边界（`orchestration` 包承载全部 K8S），不拆 Maven 多模块；`config` 组合根装配。
- **理由**：禁止过早抽象；包边界 + ArchUnit 守护即够（物理拆分后置 P3）。

### R2 · fabric8 Kubernetes Client（非 shell-out kubectl）

- **决策**：用 `io.fabric8:kubernetes-client:7.6.1` fluent Java API（list/exec/create），不 shell-out kubectl 二进制。
- **理由**：宪法原则六「K8S 仅辅助、核心逻辑 Java」；fabric8 类型安全 + 不依赖 kubectl 安装。

### R3 · k3s 测试床（轻量发行版）

- **决策**：K8S 测试床用 k3s（轻量，离线 airgap 安装）。
- **理由**：单二进制、资源占用低、离线可装；适配「新机器快速起环境」。

### R4 · `--target-ip 0.0.0.0`（NodePort 可达性）

- **决策**：arthas 启动绑 `--target-ip 0.0.0.0`（非 127.0.0.1），NodePort 才能路由到 pod 内 arthas MCP。
- **理由**：经源码证据链（`Bootstrap.java:522` → ... → `NettyWebsocketTtyBootstrap.java:76` `b.bind(host,port)`）+ 本机 A/B 实证（0.0.0.0=双 wildcard 可达 / 127.0.0.1=loopback 不可达）双重确认。
- **配套**：绑 0.0.0.0 强制鉴权 → `--password` 下发 + Bearer 注入动态后端 + 健康检查。

### R5 · arthas 不在镜像（运行时上传）

- **决策**：demo 镜像仅 3 业务类（`eclipse-temurin:21-jdk`），arthas 不在镜像；ensure 时经 fabric8 `.file().upload()` 上传 `tools/arthas-boot.jar`。
- **理由**：用户约束「本工程不依赖 arthas」（不入镜像、不入 pom）；静态工具文件上传更灵活（arthas 版本可控）。

### R6 · 编排工具绕过 ToolsCallRouter（gateway-core 零 K8S 感知）

- **决策**：3 K8S 工具在 `GatewayMcpServerConfig.mcpToolSpecifications` 注册时自带闭包直调 `K8sToolHandlers.handle`，**不经 ToolsCallRouter**。
- **理由**：`ToolsCallRouter`/`GatewayToolHandlers` 完全不知 `k8s.*` 存在 → gateway-core 零 K8S 依赖（ArchUnit 守护）。

### R7 · kubeconfig = root-on-node 派生 admin（不建 RBAC）

- **决策**：测试床用 root-on-node 派生 admin kubeconfig（不建 ServiceAccount/Role）。
- **理由**：MVP 受控内网 + 测试床；K-ENS-6 RBAC 后置。生产应建最小权限 ServiceAccount。

### R8 · target 逻辑名自动派生（`{server}-{pod}`）

- **决策**：`deriveLogicalName(server, pod)` = `{server}-{pod}`（确定性，非人工命名）。
- **理由**：自动实例化 + 自动命名 + 自动纳管（无人工逐项命名）；确定性便于幂等（同 server+pod → 同 target）。

### 决策：commons-compress 显式声明（fabric8 upload 运行时依赖坑）

- **决策**：`pom.xml:107-111` 显式声明 `org.apache.commons:commons-compress:1.28.0`。
- **理由**：fabric8 `PodUpload` 用其打 tar 流，但 fabric8 声明 optional（不传递）→ uber jar 缺失 → `NoClassDefFoundError`。显式声明使其进 `BOOT-INF/lib`。

### 决策：K8sExec 用 exitCode() 而非 onClose

- **决策**：`K8sExec` 用 `ExecWatch.exitCode()` 的 `CompletableFuture<Integer>` 取真实进程退出码，不用 `ExecListener.onClose(code)`。
- **理由**：onClose 的 code 是 WebSocket 关闭码（1000=NORMAL），非进程退出状态；曾导致误判（成功/失败分类错）。

---

## 第 70 章 004 portal 决策（R1–R13）

### R1 · v2 Web 前端（CLI 废弃）

- **决策**：portal 用 Vue 3 SPA（浏览器），废弃 v1 CLI（picocli）。
- **理由**：用户要求「带 Web 前端」；Web UI 比 CLI 更友好（可视化 CRUD/健康徽标/任务列表）。

### R2 · SnakeYAML dumpAsMap 重写（不保留注释）

- **决策**：`BackendsYamlWriter` 用 `yaml.dumpAsMap(root)` 重写 `backends.yaml`。
- **已知限制**：不保留原文注释；机密字段写回当前解析值（占位符还原后置）。
- **理由**：SnakeYAML dump 不支持保留注释；MVP 接受（注释保留后置）。

### R3 · 动态后端不可手动增改

- **决策**：POST/PUT 动态后端（source=DYNAMIC）一律拒绝（`dynamic_backend_not_editable`）；仅 DELETE（unregister）允许。
- **理由**：动态后端由 003 ensure 产生（身份 `{server}-{pod}` 派生），手动改会破坏一致性；管理只经 ensure/unregister。

### R4 · /admin 与 /mcp 隔离（INV-ISOL-1）

- **决策**：`/admin` REST 走 Spring Web MVC `@RestController`；`/mcp` 走 spring-ai servlet；互不影响。
- **理由**：管理面操作不污染诊断面 MCP 行为（38 工具契约不变）。

### R5 · 任务导出原样（INV-EXP-1）

- **决策**：`TaskExportService.export` 直接取 `result.content()` 的 TextContent.text，不篡改/截断；前端 `downloadTaskExport` 浏览器原生 attachment 下载（不经前端重序列化）。
- **理由**：宪法原则二（结果原样）。

### R6 · 同源 fetch（无 CORS）

- **决策**：前端 SPA 内嵌网关 JAR（`vite build` → `static/`），同源 fetch `/admin`。
- **理由**：无 CORS 预检，简化部署（单 JAR）。

### R7 · 静态 CRUD 经文件热重载（INV-FILE-1）

- **决策**：portal CRUD 一律走「写回 `backends.yaml` → 001 WatchService 热重载」，不直接调 `BackendRegistry.rebuild`。
- **理由**：避免绕过文件导致重启后状态不一致（文件 = source of truth）。

### R8 · MVP Noop 鉴权

- **决策**：portal MVP 无鉴权（受控内网）；Bearer token 演进项。

### R9 · @ConditionalOnProperty 能力开关（默认开，各自独立）

- **决策**：`crud.enabled` / `export.enabled` 各自 `@ConditionalOnProperty(matchIfMissing=true)`，默认开，互不影响。
- **理由**：能力按需组合（R9）；零运行时开销（关则 Controller 不装配 → 404）。

### R10 · Vue 3 + Vite + TypeScript

- **决策**：前端用 Vue 3 + Vite 5 + TypeScript。
- **理由**：Vue 3 组合式 API + Vite 快速构建 + TS 类型安全。

### R11 · frontend-maven-plugin 集成

- **决策**：`frontend-maven-plugin` 在 Maven `generate-resources` 阶段跑 `npm install + build`，下载 node v22.22.0。
- **理由**：CI 无需预装 node；`./mvnw verify` 一条命令出含前端单 JAR（CI 复现）。

### R12 · 前端内嵌单 JAR

- **决策**：`vite.config.ts` `outDir='../target/classes/static'`，产物落 Maven 输出，Spring Boot 打包进 JAR。
- **理由**：单 JAR 单产物部署（宪法「CI 复现」）。

### R13 · 前端 = 展示层（核心逻辑 Java）

- **决策**：前端仅 fetch + render + download；核心 CRUD/导出/校验/热重载逻辑在 Java `/admin`。
- **理由**：宪法原则六（Java 主力）；ArchUnit INV-WEB-2 守护。

### 决策：任务列表与导出共用开关（INV-LIST-4）

- **决策**：`GET /admin/tasks`（列表）与 `GET /admin/tasks/{id}/export`（导出）共用 `export.enabled` 开关。
- **理由**：列表与导出同属「任务」能力，YAGNI（无「只开列表不开导出」需求）。

### 决策：列表摘要无 frames（INV-LIST-1）

- **决策**：`TaskSummaryDto` 仅 7 字段（无 frames）；frames 仅 `TaskExportDto`（导出端点）。
- **理由**：列表轻量（避免大响应）；frames 仅在导出时提供。

### 决策：createdAt 倒序 + 标准分页（INV-LIST-2/3）

- **决策**：列表 `createdAt` 倒序（最新在前）；`?page&size` 标准分页 + `total`（过滤后/分页前）。
- **理由**：运维习惯（最新在前）；分页适配大量任务。

### 决策：SpaConfig history 模式 fallback

- **决策**：`SpaConfig` 把 `/backends`、`/tasks` forward 到 `/index.html`。
- **理由**：Vue Router history 模式 deep link reload 无服务端映射 → 404；forward 让浏览器加载 SPA 后前端解析。

---

## 第 71 章 brainstorming 设计要点

> 设计阶段产出归档于 `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`。关键要点：

### 71.1 001 头脑风暴

- **核心抉择**：聚合 vs 透传 —— 选「透明无损聚合」（原则二）。
- **工具暴露**：静态摘抄 arthas 工具（不从后端动态发现）。
- **target 路由**：注入必填 target 参数。

### 71.2 002 整改设计

- **核心抉择**：熔断/限流应位于哪一层 —— 选「下沉到 BackendEntry 统一拦截层」（同步/异步共用）。
- **15 项发现**：按 P0–P3 分级，每项给修复方向（HOW 留 plan 决策）。

### 71.3 003 K8S 编排设计（`2026-06-22-k8s-arthas-mcp-launch-design.md`）

- **核心抉择（Q4）**：原子工具 vs 过程式编排 —— 选「Claude 是编排者，系统提供原子工具」（3 工具：list-pods/list-services/ensure-arthas-mcp）。
- **工具粒度标准**：LLM 是否需要在两步之间推理。
- **拓扑决策（#5）**：kubectl exec 进入目标 pod 注入 arthas（attach 该 pod JVM PID，非独立 pod）。
- **north-star**：大模型上下文推断目标（P1 人指定 → north-star LLM 推断）。

### 71.4 004 portal 设计（`2026-06-25-portal-backend-management-design.md` v2）

- **核心抉择**：CLI vs Web —— 选「Web 前端」（v2，v1 CLI 废弃）。
- **能力独立模块**：CRUD + 导出（+ 增量列表）各自 `@ConditionalOnProperty` 按需组合。
- **范围**：A CRUD + C 导出 + E 列表（增量）；B 动态持久化 / D 操作审计 / 可视化 / 鉴权后置。

### 71.5 K8S 测试环境设计（`2026-06-23-k8s-test-env-setup-design.md`）

- **离线 airgap**：k3s 离线安装（`INSTALL_K3S_SKIP_DOWNLOAD=true`）。
- **镜像内容**：仅 3 业务类（arthas 运行时上传）。
- **kubeconfig 派生**：root-on-node admin（不建 RBAC）。

### 71.6 portal 任务列表设计（`2026-07-07-portal-task-list-design.md`）

- **前端形态**：复用 /tasks 页加列表区（点项衔接导出）。
- **列表契约**：摘要 + 倒序 + 标准分页。
- **过滤**：status/tool/target 三维度。
- **开关**：复用 export.enabled。

### 71.7 arthas 测试夹具设计（`2026-06-20-arthas-test-fixture-design.md`）

- **真实性**：真实 arthas MCP + 真实业务 JVM（禁桩）。
- **arthas-boot.jar 作静态工具**：`tools/`（不入 pom）。
- **夹具分层**：核心夹具（testfixtures/）+ 冒烟启动器（smoke/）。

---

## 第 72 章 关键技术选型对比

### 72.1 MCP 传输：Streamable HTTP vs stdio

| 维度 | Streamable HTTP | stdio |
|------|-----------------|-------|
| 适配 Claude Code | ✓（远程 + 配置简单） | ✓（本地） |
| portal 同源 | ✓（同 JAR） | ✗ |
| 多客户端 | ✓ | ✗（单进程） |
| Spring AI starter | 与 stdio 互斥 | 与 HTTP 互斥 |
| **MVP 选择** | **✓ HTTP** | 延后 |

### 72.2 K8S 客户端：fabric8 vs shell-out kubectl

| 维度 | fabric8 Java | shell-out kubectl |
|------|--------------|-------------------|
| 类型安全 | ✓ | ✗（字符串） |
| 依赖 kubectl 安装 | 否 | 是 |
| 宪法原则六 | ✓（核心逻辑 Java） | ✗（依赖外部二进制） |
| **选择** | **✓ fabric8** | — |

### 72.3 异步实现：方案 A/B/C

| 方案 | 机制 | 优缺 |
|------|------|------|
| A 同步 | 阻塞调用 | 会话挂死 ✗ |
| B 协议层 task | MCP task（INPUT_REQUIRED） | 复杂 + spec 不稳定 ✗ |
| **C 应用层任务** | **后台化 + taskId + GatewayTask** | **简单 + 协议无关 ✓** |

### 72.4 前端：Vue vs React vs CLI

| 维度 | Vue 3 | React | CLI |
|------|-------|-------|-----|
| 学习曲线 | 低 | 中 | — |
| 生态 | 足够 | 大 | — |
| 内嵌 JAR | ✓（vite build static） | ✓ | — |
| 团队偏好 | ✓ | — | — |
| **MVP 选择** | **✓ Vue 3** | — | 废弃 |

### 72.5 配置热重载：@ConfigurationProperties vs WatchService

| 维度 | @ConfigurationProperties | WatchService |
|------|--------------------------|--------------|
| 启动期绑定 | ✓ | — |
| 运行时重读 | ✗（一次） | ✓ |
| 原子切换 | ✗ | ✓（AtomicReference） |
| **后端列表选择** | ✗（不适用） | **✓ WatchService** |

---

> **下一步**：Part 10 关键类源码摘录（逐行级，事无巨细）。
