# Research — K8S 目标 arthas MCP 启动与纳管

> **Phase 0 产出**（`/speckit-plan`，宪法原则八先决研究）。本文档汇总 P1 实施的**技术决策**（决策/理由/备选），解决全部技术未知。
> 架构层决策（模块化单体 + 单 MCP 端点 + kubectl exec 拓扑 + 3 工具收敛 + NodePort）详见 [设计文档](../../docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md)（SuperPower 头脑风暴产出）；本文不重复其架构论述，仅落"实施期技术决策"。
> 输入：[spec](./spec.md)、[设计](../../docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md)、[宪法](../../.specify/memory/constitution.md) v1.2.0、[001 data-model](../001-arthas-mcp-gateway/data-model.md)、[arthas 测试夹具设计](../../docs/superpowers/specs/2026-06-20-arthas-test-fixture-design.md)、既有源码（`backend/*` / `config/GatewayMcpServerConfig` / `handler/ToolsCallRouter` / `tool/StaticToolRegistry`）。

## 0. 先决研究结论（宪法原则八）

精读全部相关既有源码与参考，逐项核对设计文档的九项决策与风险，**全部确认可实施**（无臆测）。关键事实（证据驱动）：

- **既有注册表是不可变快照**：`BackendRegistry` = `record(version, byName Map)`，`Map.copyOf` 防御拷贝；`RegistryHolder` 经 `AtomicReference.getAndSet` 整体替换；`BackendRegistryReloader.reload` 做 diff（unchanged 复用旧 Entry、added/changed 新建、toRetire 下线）。**动态注册不能简单"加方法到 record"**（record 不可变），须引入合并层（见 R8）。
- **既有 MCP 工具装配是单 bean**：`GatewayMcpServerConfig#mcpToolSpecifications` 把 `StaticToolRegistry`（35）的每个 `ExposedTool` 转成 `SyncToolSpecification`（handler 全委托 `ToolsCallRouter.route`）。Spring AI starter 收集此 `List<SyncToolSpecification>` bean 注册到 server。**编排工具可作第二组 specs 合并进同一 bean**（见 R6），无需多 bean 规避 Spring 歧义。
- **`ToolsCallRouter` 按 `routingMode` 分流**：`GATEWAY_LOCAL`（4 自有工具）在 target 解析前委托 `GatewayToolHandlers.handle`（switch 工具名，未知抛 `IllegalStateException`）。编排工具**应绕过路由器**（handler 自带闭包），使 gateway-core 路由零 K8S 感知（设计 §3.2 的内聚性纪律，见 R6）。
- **既有热重载会清空非 YAML 来源**：`BackendRegistryReloader` 只认 `LoadedBackends`（来自 `backends.yaml`）。若动态 target 与静态 target 同存于一个 registry，热重载会**误删动态 target**——故动态 target 须独立存储 + 合并层（见 R8），不能并入 YAML 加载结果。
- **arthas-boot.jar 启动范式已验证**（001 夹具设计 §5）：`java -jar tools/arthas-boot.jar <pid> --attach-only --http-port <port> --target-ip <ip> --use-version 4.3.0`，arthas 4.3.0 MCP 端点为**根 URL**（无 `/mcp`），attach 后 `arthas-boot.jar` 进程 exit 0、arthas agent 常驻目标 JVM 内服务端口。本地用 `--target-ip 127.0.0.1`（loopback）；**K8S 远程经 NodePort 须 `--target-ip 0.0.0.0`**（绑 pod 网络接口）——此项**已由 arthas 4.3.0 源码证据链 + 本机真实 A/B 实证双重确认（见 R4 RESOLVED）**，不再是风险、不阻塞波次 B。
- **本机环境约束**：开发机 Windows 11、**无 Docker**（memory `arthas-no-dependency` 基建）；`debian-docker`（192.168.31.92，Debian 13，Docker 26.1.5，2 核/3.8Gi，SSH key 免密，memory `debian-docker-ssh-access`）是唯一可承载 K8S 的真实资源。

## 1. NEEDS CLARIFICATION 处置

spec 经 `/speckit-clarify` 已澄清 Q1–Q4；设计文档（brainstorming）已收敛 Q4 工具粒度（5→3）、部署拓扑（kubectl exec）、暴露方式（NodePort）、注册机制（程序化 + 静态种子）。spec.md 已按设计 §10 reconcile（FR-002 改述为幂等供给、FR-004/005 降为子行为、增 `OrchestrationRecord` 实体、NodePort 暴露）。

本文档解决的**实施期**技术未知 = R1–R8（下表）。均无 `[NEEDS CLARIFICATION]` 残留。

## 2. 实施期技术决策（R1–R8）

### R1. 构建结构：单 Maven 模块 + 包级边界（Maven 多模块后置 P3）

| 维度 | 决策 |
|------|------|
| **决策** | 单 Maven 模块（沿用 001/002 `arthas-mcp-gateway`），用**包级边界**实现设计的"模块化单体"：新 `com.arthas.gateway.orchestration` 包承载全部 K8S 编排；既有 `backend/handler/config/tool/task/auth/obs` 包（gateway-core）**零 K8S 依赖**。Maven 多模块（gateway-core/orchestration/app/portal）作为**逻辑命名**沿用，**物理拆分后置 P3**。 |
| **理由** | ① 宪法「禁止过早抽象」「小步迭代」——多模块拆分是显著的构建复杂度（parent pom、模块间依赖、测试 classpath 共享、`app` bootstrap 装配），其唯一收益"按需裁剪模块"在 P1 **不行使**（P1 同时需要 gateway-core + orchestration）。② 设计 §3.2 的核心诉求"gateway-core 不含 K8S 编排逻辑、可独立编译/测试"在 P1 由**包级边界 + 单向依赖**即可达成（orchestration → backend 允许；反向禁止），独立编译的增量价值（enforce 依赖方向）在 P1 可由 review + 可选 ArchUnit 守护。③ 从干净的包边界迁移到 Maven 模块在 P3 是机械操作（若届时包边界已被遵守）。④ 既有一百多个类全在扁平包内，迁入 `core.*` 子包是高风险纯机械重构，不符合"小步迭代、每次改动只针对一个关注点"。 |
| **备选（未选）** | **(A) 立即拆 Maven 多模块**：更早 enforce 边界，但 P1 无裁剪需求、徒增构建复杂度，违反禁止过早抽象。**(B) 既包扁平、又把 gateway-core 迁入 `core.*` 子包**：机械重构全库，高风险零收益。**选包级边界**（最小改动、满足 §3.2 诉求、保留 P3 拆模块的清晰路径）。 |
| **强制手段（可选）** | 可加 ArchUnit 测试断言"`com.arthas.gateway.backend..` 不依赖 `com.arthas.gateway.orchestration..`"等单向规则。属 test-scope 轻量依赖，直接服务设计 §3.2 的边界诉求。是否引入留 tasks.md（先 review 把关，ArchUnit 作为可选项）。 |

### R2. K8S 客户端：fabric8 kubernetes-client

| 维度 | 决策 |
|------|------|
| **决策** | `io.fabric8:kubernetes-client`（Apache 2.0）。list pods/services、exec 进 pod、create NodePort Service、（备选）文件上传全走其 fluent Java API。**不 shell-out `kubectl` 二进制**（宪法原则六"K8S 仅辅助、核心逻辑 Java"）。 |
| **理由** | ① fabric8 是 Java K8S 生态事实标准（Red Hat 支持），fluent DSL 对 list/exec/create 最简洁。② exec API（`client.pods().inNamespace(ns).withName(pod).exec(...)`）与文件操作（`redirectOutput`/tar）覆盖 `ensure` 全部需求。③ 纯 Java，符合原则六。④ 与 kubeconfig/in-cluster 两种鉴权均原生支持。 |
| **备选（未选）** | **官方 `io.kubernetes:client-java`**（Kubernetes-sig）：亦合规、功能等价，但 DSL 较 fabric8 啰嗦（exec 尤甚）。**shell-out `kubectl`**：违反原则六（核心编排逻辑落 shell），且依赖目标机装 kubectl、错误处理粗糙——**排除**。fabric8 vs 官方最终选型留波次 B 首测裁决（exec 体验），默认 fabric8。 |
| **版本** | 随 Spring Boot 4.1.0 BOM 兼容的最新稳定 fabric8（`pom.xml` 显式声明版本，锁定可复现构建）。 |

### R3. K8S 测试集群：debian-docker 上的 k3s

| 维度 | 决策 |
|------------|
| **决策** | 真实测试集群 = **debian-docker 服务器（192.168.31.92）上安装 k3s**（轻量 K8S 发行版）。网关开发期在 Windows 运行、经 kubeconfig 远程连接 k3s API + 经 NodePort 远程访问暴露的 arthas MCP（验证"网关可集群外运行"）。 |
| **理由** | ① 本机 Windows 无 Docker，无法本地跑 kind/k3d（设计 §波次 B 候选排除本地项）。② debian-docker 有 Docker 26.1.5 + SSH key 免密（memory），是唯一现成承载点；k3s 单二进制、资源占用低（2 核/3.8Gi 足够）、符合设计"轻量发行版"与原则八先决研究。③ 设计 §波次 B 已列 on-debian 服务器为候选。④ 网关集群外运行 + 远程 kubeconfig/NodePort 正是 SC-001 真实拓扑。⑤ P2（离线构造脚本）顺势 = "在全新 debian 上脚本化装 k3s"，与本决策一致。 |
| **备选（未选）** | **kind/k3d on debian**（容器内跑 K8S）：Docker-in-Docker 套娃，NodePort 路由多层、复杂度高，不如 k3s 直接。**生产 K8S 集群**：超出 MVP 受控实验范围。**k3s on 本机 Windows (WSL2)**：本机无 Docker/WSL 约束未知，弃。 |
| **运维（实施细节）** | 完整方案见 [K8S 测试环境设计](../../docs/superpowers/specs/2026-06-23-k8s-test-env-setup-design.md)：`test-env/k8s/` 幂等 setup 脚本（本机 Git Bash 运行、内部经 `on-debian` 远程操作 debian，debian 已有 Docker 26.1.5）+ `reference/k3s/` 离线资源预置（k3s **v1.35.5+k3s1** airgap，防 github 间歇不可达，memory）。**离线 airgap 装**（`INSTALL_K3S_SKIP_DOWNLOAD=true` + 瘦身 `--disable traefik/servicelb/metrics-server` + `--tls-san 192.168.31.92`）。**身份 = root-on-node 派生 admin kubeconfig**（不建 ServiceAccount/Role；K-ENS-6 后置），落 `test-env/k8s/kubeconfig/k3s-admin.yaml`（server 改 `https://192.168.31.92:6443`，文件 600、`sudo cat` 导出）。一键入口 = `bash reference/k3s/fetch.sh` → `bash test-env/k8s/setup.sh`；清理 = `teardown.sh`。k3s node IP = 192.168.31.92，NodePort 在其上对外可达（内网）。 |

### R4. arthas MCP 远程绑定：`--target-ip 0.0.0.0`（✅ RESOLVED：源码证据链 + 本机 A/B 实证双重确认）

> **状态：已解决（2026-06-22）。** 经 arthas 4.3.0 源码逐级追踪 + 本机真实 A/B 实证，`--target-ip` 的值**直接决定** arthas MCP HTTP 监听 socket 的绑定地址；`0.0.0.0` 产出 wildcard 绑定、NodePort 可达。**不再是风险、不阻塞波次 B**；契约 K-ENS-7 由"风险首测"转为"回归守护"（loopback 经 NodePort 不可达）。

| 维度 | 决策 |
|------|------|
| **决策** | `ensure` 启动 arthas MCP 时用 `--target-ip 0.0.0.0`（绑 pod 所有网络接口），使 NodePort 能路由到容器内 arthas MCP 端口。本地夹具的 `127.0.0.1`（loopback）在 K8S 远程场景**不可达**（NodePort 路由不进 loopback）。 |
| **源码证据链（arthas 4.3.0，逐级数据流）** | ① `Bootstrap.java:522-525`——`--target-ip <ip>` 透传为 agent 参数 `-target-ip <ip>`。② `Arthas.java:68-69`——`-target-ip` 选项值 → `configure.setIp(value)`。③ `ArthasBootstrap.java:457-460`——`new HttpTermServer(configure.getIp(), configure.getHttpPort(), ...)`。④ `HttpTermServer.java:50`——`new NettyWebsocketTtyBootstrap(...).setHost(hostIp).setPort(port)`（hostIp=configure.getIp）。⑤ `NettyWebsocketTtyBootstrap.java:76`——`b.bind(host, port)`（Netty ServerBootstrap 以 host:port 绑定监听）。⑥ **MCP `/mcp` 端点搭乘同一 Netty 管线**：`ArthasBootstrap.java:493` 存 `mcpRequestHandler`；`HttpRequestHandler.java:52` 取之；`HttpRequestHandler.java:80-86` 对 `path==mcpEndpoint` 的请求委托 `mcpRequestHandler.handle(ctx, request)`。故 MCP 端点由绑定 `configure.getIp()` 的 HTTP term server 同一 Netty 服务——**`--target-ip` 直接决定 MCP 监听绑定地址**（`ArthasMcpServer` 构造 `McpServerProperties`/传输层时未设 bindAddress/port，仅产出注入该管线的 handler）。 |
| **本机 A/B 实证（2026-06-22，真实 DemoBusinessApp JVM + 真实 arthas-boot.jar 4.3.0，零桩）** | 以同一夹具分别 attach 两个真实业务 JVM：**A 组** `--target-ip 0.0.0.0 --http-port 39182` → netstat `0.0.0.0:39182 LISTENING` + `[::]:39182 LISTENING`（IPv4/IPv6 双 wildcard，NodePort 可达）；**B 组** `--target-ip 127.0.0.1 --http-port 39184` → netstat `127.0.0.1:39184 LISTENING`（仅 loopback，NodePort 不可达）。**结论：`--target-ip` 值即监听绑定地址，`0.0.0.0` 满足 NodePort 可达性要求。** 实证脚本 [`smoke/r4-bind-test.sh`](../../smoke/r4-bind-test.sh)（A/B 对照，可复现）。 |
| **对 K8S 拓扑的影响** | pod 内 arthas MCP 监听 `0.0.0.0:<mcpPort>` → NodePort Service（selector 命中该 pod、`port→targetPort=mcpPort`）将其映射到 `<nodeIP>:<nodePort>` → 网关经 NodePort 可达。**无需 socat/iptables 转发降级方案**（备选方案已撤销）。 |
| **备选（未选）** | **arthas MCP 独立 pod + 跨 pod attach**：arthas 4.3.0 attach 走 Java Attach API（本机 PID namespace），跨 pod attach 需共享 PID ns/主机网络，复杂且偏离 attach 语义——排除，坚持 exec 注入目标 pod（设计决策 #5）。 |

### R5. NodePort Service selector 策略：label pod + selector

| 维度 | 决策 |
|------------|
| **决策** | `ensure` 为目标 pod 打唯一 label（如 `arthas-mcp-gateway/target=<logicalName>`），再 create 一个 NodePort Service 用 `selector` 匹配该 label、`port→targetPort=mcpPort`。可达 URL = `http://<nodeIP>:<nodePort>`（arthas MCP 根 URL，无 `/mcp`）。 |
| **理由** | ① 业务 pod 通常无唯一 label，直接 selector 无法精确路由到"这一个 pod"。打唯一 label 后 selector 精确命中，且 pod 重启 IP 变化时 selector 自动重解析（Service 持续可达新 pod）。② K8S 原生、最简单。③ NodePort 在固定范围（默认 30000–32767）对外可达，符合"网关集群外运行"。 |
| **备选（未选）** | **headless Service + 手写 Endpoints（指定 pod IP）**：控制更精细，但 pod 重启 IP 变即失效、需重供给——MVP 不选。**复用业务 pod 既有的某个 Service**：业务 Service 端口语义不符、不通用——排除。**hostNetwork + hostPort**：绕过 Service 抽象、端口冲突风险高——排除。 |
| **label 打标副作用** | 对业务 pod 打 label 是写操作（轻量、可逆）。MVP 受控环境可接受；`ensure` 失败时记录已打 label 便于清理（`OrchestrationRecord`）。 |
| **端口选择** | NodePort 由 K8S 在范围内自动分配（避免冲突）；或显式指定（受限范围）。默认自动分配，返回实际 nodePort。 |

### R6. 3 个编排工具的装配：第二组 specs，handler 自带闭包，绕过 ToolsCallRouter

| 维度 | 决策 |
|------------|
| **决策** | `orchestration` 包提供 `K8sToolRegistry`（3 个 `ExposedTool`，`routingMode=GATEWAY_LOCAL` 因无 target 参数）与 `K8sToolHandlers`。`GatewayMcpServerConfig#mcpToolSpecifications` **合并** StaticToolRegistry（35）+ K8sToolRegistry（3）= 38 specs 进**同一个** `List<SyncToolSpecification>` bean。编排工具的 handler 闭包**直接调用 `K8sToolHandlers.handle`**，**不经 `ToolsCallRouter`**。 |
| **理由** | ① 设计 §3.1/§3.2：两组 @Tool 装配进同一 MCP server，tools/list 为并集；gateway-core 路由**零 K8S 感知**。让编排工具绕过路由器（handler 自带闭包），`ToolsCallRouter`/`GatewayToolHandlers` 完全不知 `k8s.*` 工具存在——内聚性纪律落地。② 合并进同一 bean 避免 Spring 多 `List<SyncToolSpecification>` bean 歧义（starter 收集单 bean）。③ 38 工具仍静态 → `listChanged=false` 继续成立（capabilities 锁定不变）。 |
| **备选（未选）** | **(A) 编排工具也走 `ToolsCallRouter`**：需在路由器加 `k8s.*` 分支 → gateway-core 路由器出现 K8S 概念，污染原则二落点——排除。**(B) 路由器按 name 前缀分发到多 handler 注册表**：更通用但过早抽象（禁止过早抽象），3 个工具不值得——选最小改动（第二组 specs + 自带闭包）。**(C) 多个 `List<SyncToolSpecification>` bean + `@Qualifier`**：需验证 starter 是否聚合多 bean，不确定性高——合并单 bean 最稳。 |
| **路由模式** | 编排工具虽标 `GATEWAY_LOCAL`，但**不进 `ToolsCallRouter` 分流**（其 handler 闭包在 bean 构建时即绑定，绕过路由器）。`routingMode` 字段仅供 `ExposedTool` 完整性，实际不参与路由。 |

### R7. 测试波次与真实性硬约束（零桩、真实环境）

| 维度 | 决策 |
|------|------|
| **决策** | 沿用 arthas 测试夹具设计的波次范式 + CLAUDE.md 真实性硬约束：**波次 A（纯逻辑，surefire，无 K8S）**——命名派生 `{server}-{pod}`、`DynamicBackendStore` register/unregister、`RegistryComposer` 静态∪动态合并与命名冲突、`BackendConfig.source` 解析、`OrchestrationRecord` 状态机。**波次 B（真实 K8S 夹具，failsafe `*IT.java`）**——真实 k3s + 真实业务 pod（DemoBusinessApp 打镜像入集群）+ 真实 `ensure`（fabric8 exec 注入 + NodePort 暴露 + 健康检查）+ 真实诊断（触发业务方法 → 经网关 watch/trace 捕获）。**波次 C（端到端）**——人指定 pod → Claude（`claude -p --mcp-config`）编排枚举+供给+诊断 → 结果来自该 pod JVM。 |
| **真实性约束** | 成功路径**不得用桩**模拟 arthas/K8S 成功响应；故障用**真实故障条件**：停 pod=不可达、无 shell pod=真实 ensure 失败、kubeconfig 无 exec 权限=真实 403、arthas 绑 loopback=真实不可达（验证 R4）。驱动分层：可用性走真实 Claude Code MCP（逐工具冒烟）；一致性 + 双侧契约走官方 MCP Java SDK client（确定性断言）。 |
| **DemoBusinessApp 容器化** | 001 夹具 `DemoBusinessApp` 是宿主子进程；K8S 场景须**打成容器镜像**（`jdk-21` 基础镜像 + demo 类）入 k3s。这是波次 B 的夹具增量（见 data-model §7、quickstart）。 |
| **CI 策略** | 波次 A 进 CI（无 K8S 依赖）；波次 B/C 依赖 debian k3s，CI 默认跳过（`Assume` 守护，标注需 k3s 环境本地手跑），与 001 真实夹具 IT 同策略。 |

### R8. 程序化动态注册层：独立动态存储 + 合并层（与热重载共存）

| 维度 | 决策 |
|------------|
| **决策** | 新增 `DynamicBackendStore`（动态 target 内存态，`register(BackendConfig)`/`unregister(name)`/`list()`）与 `RegistryComposer`（把 static store ∪ dynamic store 合并为 effective `BackendRegistry`，经 `RegistryHolder.getAndSet` 原子替换）。**静态热重载**（`BackendRegistryReloader`）只重读 YAML 更新 static store；**动态 register/unregister** 只更新 dynamic store；二者任一变更均触发 `RegistryComposer.compose()` → 原子替换 effective registry。`BackendConfig` 增 `source` 字段（STATIC/DYNAMIC），静态种子缺省 STATIC（向后兼容）。 |
| **理由** | ① 既有 `BackendRegistry` 是不可变 record，"加 register 方法"不成立；不可变快照 + AtomicReference 整体替换是既定架构（001 data-model §4），动态注册须顺应而非破坏。② 关键正确性：热重载（`BackendRegistryReloader`）只认 YAML，若动态 target 并入同一 registry，热重载会**误删动态 target**——故动态 target 须独立 store，effective = 合并视图。③ 设计 §6.2"快照 = 静态种子 ∪ 动态注册，经既有 AtomicReference 原子替换"正是此意；原子替换保留 001 的 in-flight 不变量（热重载并发不串台）。④ `source` 标记让 `list-targets` 可区分来源（设计 §六、边缘情况"命名冲突可区分"）。 |
| **命名冲突策略** | 动态名 ∩ 静态名 → **拒绝注册**并报明确错误（保护静态配置，设计 §6.2）；动态名之间冲突 → 幂等以"健康复用"优先，URL 不同则报错。 |
| **合并复用** | `RegistryComposer` 复用 `BackendRegistryReloader` 的 diff 思路：unchanged（static∪dynamic 中同 name 同 config）复用旧 `BackendEntry`（保连接池/session），减少重连。 |
| **备选（未选）** | **(A) 动态 target 并入 YAML 加载结果**：热重载误删动态 target，正确性缺陷——排除。**(B) 两个 RegistryHolder（static + dynamic），路由时查两个**：路由热路径多一次查询、且熔断/限流 per-entry 语义割裂——选单一 effective registry 更简洁正确。**(C) 把 register/unregister 加到 RegistryHolder**：Holder 职责膨胀（现仅持有快照），破坏单一责任——独立 store + composer 更清晰。 |

## 3. 残留风险（实现期首测裁决）

| 风险 | 对冲（已内置 tasks.md） |
|------|------------------------|
| ~~arthas `--target-ip 0.0.0.0` 远程行为未知（R4，原最大风险）~~ ✅ **已解决** | **2026-06-22 源码证据链 + 本机 A/B 实证双重确认**（见 §R4）：`0.0.0.0` 产出 wildcard 绑定、NodePort 可达；**不再阻塞波次 B**；K-ENS-7 转为回归守护，无需 socat/iptables 降级方案 |
| fabric8 exec/file-upload 在目标 pod（非标准基础镜像）的兼容性 | 波次 B 首测确认 exec + 文件传输；备选 HTTP-serve + pod 内 `wget` 拉 arthas-boot.jar |
| k3s NodePort 在 debian 内网防火墙可达性 | 部署期 `on-debian` 验证 nodeIP:nodePort 可达；SC-001 度量 |
| 动态注册与热重载并发正确性 | 波次 A `RegistryComposerTest` 断言线程安全（AtomicReference 原子替换）+ 热重载不误删动态 target |
| 业务 pod 镜像（DemoBusinessApp）构建/入 k3s | ✅ 方案已定：`test-env/k8s/setup.sh` 幂等完成（本机 mvn 编译 → ship debian → docker build → k3s ctr import → apply demo pod），见 [K8S 测试环境设计](../../docs/superpowers/specs/2026-06-23-k8s-test-env-setup-design.md) §2；镜像纯 app（arthas 不入镜像，ensure 时上传） |

## 4. 与既有特性的复用关系

- **复用 001 全部路由/熔断/限流/健康/异步任务**：动态 target 经 `BackendEntryFactory.create` 生成与静态 target 同构的 `BackendEntry`，诊断复用 `ToolsCallRouter` + `AsyncTaskExecutor`，**零新诊断代码**。
- **复用 001 夹具**：`DemoBusinessApp`/`OrderService` 业务逻辑 → K8S 场景容器化入集群（同一业务方法供 watch/trace 捕获）。
- **复用 001 配置外化**：`GatewayProperties` 增 `k8s.*` 子段（kubeconfig/context/namespace/NodePort 范围/ensure 超时）。
- **回归对照** = 001/002：38 工具 tools/list、既有 35 工具行为、热重载、双侧契约全部不得回归（tasks.md 设回归任务）。
