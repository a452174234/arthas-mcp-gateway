# Research: K8S 编排能力迭代（005）

> 本文档记录 005 迭代的 9 项技术决策（R1-R9），每条含 Decision / Rationale / Alternatives。来源：brainstorming（[2026-07-10-...-design.md](../../docs/superpowers/specs/2026-07-10-k8s-orchestration-iteration-design.md)）+ 003 既有研究（R1-R8）+ 代码精读。

---

## R1 · Service 复用识别——label 标记 Service

**Decision**：exposer 用 labelSelector 查 namespace 内带 `arthas-mcp-gateway/target=<sanitize(logical)>` label 的 **Service**（标记打在 Service metadata.labels 上，非 pod）。

**Rationale**：
- K8S 原生 labelSelector 高效（API 端过滤，非全量 list）。
- 与 003 既有 pod label 同 key（`arthas-mcp-gateway/target`），概念统一（"标记关联哪个 logical target"），只是位置从 pod 扩展到 Service。
- 运维声明式：在业务 Service 打 label 即声明「可承载该 target 的 arthas 暴露」。

**Alternatives**：
- **传入 service 名**（ensure 加 service 参数）：被否——用户选"label 标记"（声明式优于参数式；service 名易变）。
- **自动 selector 反查**（遍历 Service 测 selector 是否命中 pod）：被否——selector 可能命中多 pod（业务 Service 常用 app=label 选多副本），反向匹配歧义。

## R2 · ClusterIP Service 处理——自动 patch type=NodePort

**Decision**：命中的 Service 若是 ClusterIP，自动 patch `spec.type=NodePort` + 加端口（含 NodePort）。

**Rationale**：用户明确选"自动改 type"（省事；业务 Service 常是 ClusterIP，强制要求 NodePort 增加运维负担）。

**风险与缓解**：
- **风险**：ClusterIP→NodePort 会把 Service 现有端口也暴露到节点 IP（安全）。
- **缓解**：日志 WARN 提示运维（"Service X 类型已改 NodePort，现有端口 N 暴露到节点"）；契约 K-ENS-11 显式声明此行为；生产建议用独立 Service（带 label）承载 arthas 暴露，业务 Service 不打 label。

**Alternatives**：
- **报错 service_type_not_nodeport**（要求运维预改 type）：被否——用户选自动改（不强制运维两步操作）。

## R3 · K8S 模式 mcpUrl resolve 时机——懒 resolve（首次路由）

**Decision**：K8S 模式 backend 首次被路由时，BackendEntry 经 BackendResolver 调 ensure 拿 mcpUrl，按 logicalName 缓存。

**Rationale**：
- 启动快（不阻塞在 K8S 连接 + arthas 注入）。
- 按需（未路由的 K8S backend 不耗 ensure 资源）。
- 缓存命中后 O(1)（K-ENS-2 幂等，二次 ensure 也零副作用，但缓存避免重复 MCP 握手探测）。

**Alternatives**：
- **启动时自动 ensure**：被否——启动慢（N 个 K8S backend × ensure 5min）+ 后端不可达时启动失败。
- **手动 ensure**（k8s.ensure-arthas-mcp 工具，003 现状）：被否——用户要"配置即声明、首次诊断即生效"（懒 resolve），不强制人/LLM 预调 ensure。

## R4 · K8sHost 配置位置——application.yml

**Decision**：`arthas-gateway.k8s-hosts`（List<K8sHost>），随网关配置。

**Rationale**：
- 与现有 GatewayProperties 一致（kubeconfig/backends-file 等都在 yml）。
- host 增减少见（集群入口相对稳定），重启可接受。
- 简单（无需额外 watcher）。

**Alternatives**：
- **独立 config/k8s-hosts.yaml + 热重载**（复用 BackendConfigWatcher 模式）：被否——YAGNI（host 变更低频）；热重载多一层 watcher + 与 BackendConfig 共存复杂。

## R5 · 多 K8S Host——按 host 独立 KubernetesClient + ArthasProvisioner

**Decision**：每个 K8sHost 据其 kubeconfig 构建独立 `KubernetesClient` + `ArthasProvisioner`（`Map<hostName, Provisioner>`，启动期装配）。K8sBackendResolver 按 `config.k8sHost()` 路由到对应 provisioner。

**Rationale**：
- 每 host 有独立 kubeconfig（不同集群凭证），KubernetesClient 绑定单一 kubeconfig。
- 隔离（host A 集群故障不影响 host B）。

**Alternatives**：
- **单 client + host 路由**：被否——单 client 只连一个 kubeconfig；多集群需多 client。
- **client 复用池化**：YAGNI（host 数少，无池化收益），后置。

## R6 · BackendResolver 接口位置——gateway-core 定义

**Decision**：`BackendResolver` 接口在 `backend` 包（gateway-core）定义，`K8sBackendResolver` 实现在 `orchestration` 包。

**Rationale**：
- **零 gateway-core K8S 依赖**（宪法原则六 + ArchUnit）：gateway-core 只依赖接口（无 fabric8 import），实现在 orchestration。
- BackendEntry（gateway-core）需调 resolve（懒拿 mcpUrl），但不应依赖 orchestration/fabric8 → 接口反转。
- 装配在 config（组合根），`Optional<BackendResolver>`（无 K8S 时不装配）。

**Alternatives**：
- **BackendEntry 直接调 ArthasProvisioner**：被否——引入 fabric8 到 gateway-core（违反 ArchUnit）。
- **resolve 在路由器（ToolsCallRouter）**：被否——路由器不应懂 K8S；resolve 是 backend 生命周期的一部分（initializeOnce 时）。

## R7 · JDK 适配形式——ArthasLauncher 策略接口（SPI）

**Decision**：`ArthasLauncher` 接口（locatePid + startArthas + LaunchContext）；DefaultArthasLauncher 默认（003 现状）；用户 @Primary 实现定制。

**Rationale**：
- 用户明确要 SPI（"留适配口子，用很少代码配置"）。
- 完整命令模板自由度（实现类可拼任意 javaPath + 参数，含占位符逻辑）。
- 默认实现兼容 003（无用户实现时行为不变）。
- test fixture 真实实现验证 SPI 机制（委托/替换/契约，TDD 零桩）。

**Alternatives**：
- **纯配置驱动**（yml 配 javaPath + startArgs 模板字符串）：被否——用户选 SPI；模板字符串解析复杂 + 易出错（转义/占位符）+ 不够灵活（无法表达条件逻辑）。
- **配置 + 策略混合**：被否——YAGNI（SPI 已足够；配置项冗余）。

## R8 · SPI 装配——@ConditionalOnMissingBean + @Primary

**Decision**：`K8sOrchestrationConfig` 装配 `DefaultArthasLauncher`（`@ConditionalOnMissingBean(ArthasLauncher.class)`）；用户实现标 `@Component @Primary` 自动覆盖。ArthasProvisioner 注入 `ArthasLauncher`（单一）。

**Rationale**：
- Spring 原生（@Primary 优先级 + @ConditionalOnMissingBean 兜底）。
- 用户零配置（写 @Component 类即生效，无需 yml 指定类名）。
- ArthasProvisioner 不感知具体实现（注入接口）。

**Alternatives**：
- **yml 配 launcher 类名 + 反射实例化**：被否——丢失 Spring 装配（用户实现无法注入其他 bean）；反射复杂。
- **Java SPI（ServiceLoader）**：被否——与 Spring 容器割裂；@Component 更符合项目模式。

## R9 · Service label key——复用 arthas-mcp-gateway/target

**Decision**：Service label key = `arthas-mcp-gateway/target`（与 003 既有 pod label 同 key），value = `<sanitize(logicalName)>`。

**Rationale**：
- 概念统一（pod label + Service label 同语义"关联哪个 logical target"）。
- 不引入新 key（减少运维认知负担）。
- labelSelector 查 `arthas-mcp-gateway/target=<value>` 同时匹配 pod 与 Service（exposer 限定查 Service 即可）。

**Alternatives**：
- **新 key（如 arthas-mcp-gateway/expose-via-service）**：被否——语义重叠（都是 target 关联）；多 key 增负担。

---

## 003 既有研究复用（不重复，仅引用）

- **fabric8 vs kubectl**（003 R2）、**k3s 测试床**（003 R3）、**--target-ip 0.0.0.0**（003 R4）、**arthas 不在镜像**（003 R5）、**编排绕过 ToolsCallRouter**（003 R6）、**kubeconfig root-on-node**（003 R7）、**命名派生 {server}-{pod}**（003 R8）、**commons-compress 依赖坑**、**K8sExec exitCode vs onClose**——均沿用，005 不改这些决策。
