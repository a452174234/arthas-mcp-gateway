# Feature Specification: K8S 编排能力迭代（Service 复用 / K8S 后端配置 / JDK 适配）

**Feature Branch**: `005-k8s-orchestration-iteration`

**Created**: 2026-07-10

**Status**: Draft

**Input**: 用户描述："现在需要迭代功能……① ensure 的 service 改为复用 pod 已关联的现有 service（暴露新端口），而非新建；② 后端配置要考虑 K8S 场景：只配远端 Linux 服务器 IP + pod 关联，运行时刷新业务容器信息；③ 容器里独立部署 JDK，需留适配口子用很少代码配置 JDK 位置 + 启动命令。" → brainstorming 定稿（[2026-07-10-k8s-orchestration-iteration-design.md](../../docs/superpowers/specs/2026-07-10-k8s-orchestration-iteration-design.md)）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 复用业务 Service 暴露 arthas MCP（Priority: P1）

运维的 K8S 业务 pod 通常已有一个业务 Service（如 ClusterIP/NodePort，承载业务流量）。003 的 ensure 总是**新建**一个独立 `arthas-mcp-*` Service，与业务 Service 割裂、造成 Service 膨胀。运维希望：ensure 优先**复用 pod 已关联的现有 Service**，在其上**新增一个 NodePort 端口**（指向 arthas MCP），而非新建独立 Service。

运维在业务 Service 上打标记 `arthas-mcp-gateway/target=<逻辑名>` label，即声明「此 Service 可承载该 target 的 arthas MCP 暴露」；ensure 命中则 patch 该 Service 加端口，未命中（无标记）则回退新建（兼容 003）。

**Why this priority**: 这是用户首要痛点——Service 膨胀 + 与业务 Service 割裂直接影响生产 K8S 卫生；且是后续两点的基础（复用现有 Service 才是真实生产拓扑）。

**Independent Test**: 在 K8S 业务 Service 上打 label，触发 ensure，确认 arthas MCP 经**该现有 Service** 的 NodePort 可达（而非新建独立 Service）。

**Acceptance Scenarios**:

1. **Given** 业务 Service 已打 `arthas-mcp-gateway/target=<logical>` label，**When** ensure 该 target，**Then** exposer 复用该 Service，patch 加一个 NodePort 端口（targetPort=arthas MCP 端口），mcpUrl 经该 Service 可达；**不新建**独立 `arthas-mcp-*` Service。
2. **Given** 命中的 Service 是 ClusterIP 类型，**When** ensure，**Then** 自动 patch 其 type 为 NodePort（运维须知：现有端口随之暴露到节点）+ 加 arthas MCP 端口。
3. **Given** 业务 Service 已含同 targetPort 的端口（重复 ensure），**When** ensure，**Then** 复用既有 NodePort，不重复添加（幂等，K-ENS-2 不破）。
4. **Given** 无任何 Service 带该 label，**When** ensure，**Then** 回退新建独立 `arthas-mcp-*` Service（兼容 003 现状行为）。

---

### User Story 2 - 配置远端 Linux 服务器 + pod 关联（懒 resolve）（Priority: P1）

运维管理生产 K8S 时，希望网关的后端配置直接声明「**远端 Linux 服务器（K8S 集群入口）+ 业务 pod**」，由网关在首次诊断该 target 时**自动 ensure 出 mcpUrl**（懒 resolve + 缓存），而无需预先手动 ensure 或硬编码 mcpUrl。

新增「K8S Host」配置实体（远端 Linux：逻辑名 + kubeconfig 凭证 + namespace）；后端配置引用 host + pod 即为「K8S 模式」（与现有静态 url 模式二选一，共存）。K8S 模式的后端首次被路由时，网关调 ensure 拿 mcpUrl 并缓存。

**Why this priority**: 真实生产 K8S 场景的核心——运维只知「哪台 Linux + 哪个 pod」，不应被迫预先算 mcpUrl 或手动 ensure。懒 resolve 让配置即声明、首次诊断即生效。

**Independent Test**: 在配置中声明一个 K8S Host + 一个 K8S 模式后端（host + pod），首次诊断该 target，确认网关自动 ensure + 纳管 + 诊断成功，mcpUrl 来自 ensure（非配置硬编码）。

**Acceptance Scenarios**:

1. **Given** 配置了 K8S Host（`debian-prod`：kubeconfig + namespace）+ K8S 模式后端（`k8s-host: debian-prod, pod: order-service-abc`），**When** 首次诊断该 target，**Then** 网关懒 resolve（调 ensure 注入 arthas + 暴露 + 纳管），mcpUrl 自动产出，诊断结果来自该 pod JVM。
2. **Given** 同一 K8S 模式 target 已被懒 resolve 过（缓存），**When** 再次诊断，**Then** 直接用缓存 mcpUrl（不重复 ensure，K-ENS-2 幂等）。
3. **Given** K8S 模式后端引用了不存在的 host 名，**When** 首次诊断，**Then** 返明确错误（`unknown_k8s_host`），不静默成功。
4. **Given** 静态 url 后端（url 非空、无 k8sHost），**When** 诊断，**Then** 行为与 001/003 完全一致（懒 resolve 旁路，K8S 改动不破坏静态模式）。
5. **Given** 网关未启用 K8S（无 kubeconfig / resolver 未装配），**When** 诊断 K8S 模式后端，**Then** 返明确错误（`no_k8s_resolver`），提示需配置 K8S。

---

### User Story 3 - 独立 JDK 适配（SPI 扩展点）（Priority: P2）

生产容器里常**独立部署一套 JDK**（非 PATH 默认），关联到独立路径（如 `/opt/jdk-21/bin/java`）。003 的 ensure 硬编码用 `java`/`jps`（假设在 PATH）+ 标准 arthas 启动参数，无法适配此场景。

运维希望留一个**扩展点**，用**很少的代码**（一个实现类）即可定制：JDK 位置（javaPath）+ 完整启动命令模板（覆盖默认 arthas 启动参数）。网关经 SPI（策略接口）装配用户实现，默认实现兼容 003 现状。

**Why this priority**: 适配真实容器 JDK 部署（非 PATH），但属「可定制性」扩展，不影响核心 ensure 链路；默认实现兼容现状，故 P2。

**Independent Test**: 写一个 ArthasLauncher 实现类指定 `/opt/jdk-21/bin/java`，触发 ensure，确认 arthas 经该独立 JDK 启动（而非 PATH 的 java）。

**Acceptance Scenarios**:

1. **Given** 用户写了一个 `ArthasLauncher` 实现类（`@Primary`，指定 `/opt/jdk-21/bin/java` + 完整命令模板），**When** ensure，**Then** arthas 经该独立 JDK 路径启动（而非 PATH 默认 java），命令按用户模板执行。
2. **Given** 用户未提供自定义实现（仅默认 `DefaultArthasLauncher`），**When** ensure，**Then** 行为与 003 完全一致（PATH 的 java/jps + 标准参数，K-ENS-* 不破）。
3. **Given** 自定义 launcher 定位 JVM 或启动 arthas 失败，**When** ensure，**Then** 映射为 `failed@locate_jvm`/`failed@start_arthas`（K-ENS-4/5 分类不破），结构化错误传播。
4. **Given** SPI 接口本身（委托 + 装配优先级），**When** 测试，**Then** 用 test fixture 真实实现（非 mock）验证：ArthasProvisioner 真委托 launcher、`@Primary` 实现覆盖默认。

---

### Edge Cases

- **业务 Service 无 `arthas-mcp-gateway/target` label** → 回退新建独立 Service（US1 兼容）。
- **多个 Service 带同 label**（异常配置）→ 取第一个（K8S list 顺序），日志告警；运维应保证 label 唯一。
- **patch Service 加端口失败**（RBAC 不允许 patch / 端口范围受限）→ `nodeport_alloc_failed`，ensure failed 不注册（K-ATOMIC-1 不破）。
- **BackendConfig 同时填 url 与 k8sHost**（互斥违规）→ 配置加载校验失败，保留旧注册表（不半替换，§11 规则 7）。
- **K8S 模式后端首次路由时 ensure 失败**（pod 不存在 / 无 JVM / 无 shell）→ 懒 resolve 抛 ensure 的结构化错误（no_jvm/no_shell/...），路由器翻译为 INVALID_PARAMS；缓存不命中（下次重试）。
- **懒 resolve 后 pod 重启**（mcpUrl 失效）→ 健康检查/路由失败触发熔断（复用 001/003）；运维可清缓存重新 resolve（后置能力）。
- **自定义 launcher 抛未分类异常** → 包装为 `attach_failed@start_arthas`（含原始异常堆栈，宪法原则五）。
- **零 gateway-core K8S 依赖** → ArchUnit 守护不变（新接口 `BackendResolver` 在 gateway-core，无 fabric8 import）。

## Requirements *(mandatory)*

### Functional Requirements

**Service 复用（US1）**

- **FR-001**: ensure 暴露 arthas MCP 时，必须优先用 labelSelector 查 namespace 内带 `arthas-mcp-gateway/target=<逻辑名>` label 的 **Service**；命中则 patch 该 Service 加 NodePort 端口（targetPort=arthas MCP 端口），不新建独立 Service。
- **FR-002**: 命中的 Service 若是 ClusterIP 类型，必须自动 patch 其 type 为 NodePort（加端口）；运维须知现有端口随之暴露到节点。
- **FR-003**: 命中的 Service 已含同 targetPort 端口时，必须复用既有 NodePort（幂等，不重复添加）。
- **FR-004**: 找不到带 label 的 Service 时，必须回退新建独立 `arthas-mcp-<逻辑名>` Service（兼容 003 现状）。

**K8S 场景后端配置（US2）**

- **FR-005**: 系统必须支持新增「K8S Host」配置实体（逻辑名 + kubeconfig 凭证 + namespace），声明远端 Linux K8S 集群入口；配置位置为 `application.yml` 的 `arthas-gateway.k8s-hosts`。
- **FR-006**: `BackendConfig` 必须支持「K8S 模式」——引用 K8S Host（`k8s-host`）+ pod（`pod`），与静态 `url` 二选一（互斥）；两者皆空或皆有 → 校验失败保留旧注册表。
- **FR-007**: K8S 模式后端首次被路由时，必须经 `BackendResolver` 懒 resolve（调 ensure 出 mcpUrl + 按 logicalName 缓存），用 mcpUrl 建客户端；静态模式旁路（用 config.url）。
- **FR-008**: K8S Host 不存在 / 网关未启用 K8S 时，K8S 模式后端路由必须返明确错误（`unknown_k8s_host` / `no_k8s_resolver`），不静默成功。

**JDK 适配 SPI（US3）**

- **FR-009**: 系统必须提供 `ArthasLauncher` SPI 接口（定位 JVM PID + 启动 arthas），由 `ArthasProvisioner` 委托调用（不再硬编码 java/jps/参数）。
- **FR-010**: 系统必须提供默认实现 `DefaultArthasLauncher`（= 003 现状逻辑：PATH 的 java/jps + 标准 arthas 参数），在用户未提供自定义实现时装配。
- **FR-011**: 用户必须能经一个 `@Primary` 实现类定制 `ArthasLauncher`（javaPath + 完整命令模板），Spring 自动发现并覆盖默认实现。
- **FR-012**: 自定义 launcher 失败（定位/启动）必须映射为 ensure 的结构化错误（`failed@locate_jvm`/`failed@start_arthas`），与 003 故障分类一致。

**回归与约束（横切）**

- **FR-013**: 本次迭代必须不破坏 003 既有行为——ensure 原子幂等（K-ATOMIC-1）、幂等复用（K-ENS-2）、故障分类（K-ENS-4~9）、5 分钟端到端（SC-001）全部继续通过。
- **FR-014**: gateway-core 诊断核心包必须保持零 K8S 依赖（ArchUnit 守护）；新增 `BackendResolver` 接口在 gateway-core 定义，实现在 orchestration 包。
- **FR-015**: SPI 接口（`ArthasLauncher`）的测试必须包含一个 **test fixture 真实实现**（非 mock），验证委托正确性、装配优先级（`@Primary` 覆盖）、接口契约。

### Key Entities

- **K8S Host**（新增配置实体）：一台被管理的远端 Linux K8S 集群入口。属性：逻辑名（唯一）、kubeconfig 凭证路径、默认 namespace。被 BackendConfig 的 K8S 模式引用。
- **BackendConfig 增量**（K8S 模式字段）：`k8s-host`（引用 K8S Host 逻辑名）+ `pod`（关联业务 pod）。与 `url` 互斥。
- **BackendResolver**（新增接口，gateway-core）：懒 resolve 接口——K8S 模式 config → ensure 出 mcpUrl（缓存）；静态模式 → empty。实现在 orchestration。
- **ArthasLauncher**（新增 SPI，orchestration）：arthas 启动策略接口（locatePid + startArthas）；默认实现 DefaultArthasLauncher；用户 @Primary 实现定制。
- **LaunchContext**（SPI 入参）：封装 ensure 子步所需上下文（namespace/pod/exec 工具/mcpPort/targetIp/arthas 版本密码/jar 路径/超时）。
- **既有复用**：`OrchestrationRecord`（ensure 状态机）、`DynamicBackendStore`（动态注册）、`BackendEntry`（统一拦截层，加懒 resolve hook）。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**（Service 复用）：带 `arthas-mcp-gateway/target=<logical>` label 的业务 Service，ensure 后被**复用**（patch 加端口），不新建独立 Service；mcpUrl 经该 Service 可达。
- **SC-002**（懒 resolve）：声明 K8S Host + K8S 模式后端（host + pod），首次诊断即自动 ensure + 纳管 + 诊断成功；mcpUrl 来自 ensure（非配置硬编码）；二次诊断用缓存（不重复 ensure）。
- **SC-003**（JDK 适配）：写一个 `@Primary` `ArthasLauncher` 实现类指定 `/opt/jdk-21/bin/java`，ensure 后 arthas 经该独立 JDK 启动；不写则用默认（PATH）兼容现状。
- **SC-004**（SPI 可验证）：test fixture 真实实现验证委托（ArthasProvisioner 真调 launcher）+ 装配优先级（`@Primary` 覆盖 Default）+ 接口契约（locatePid/startArthas 调用正确）。
- **SC-005**（回归不破）：003 既有契约（K-ATOMIC-1 / K-ENS-2 / K-ENS-4~9 / SC-001 5min 端到端）+ gateway-core 零 K8S 依赖（ArchUnit）全部继续通过。
- **SC-006**（共存）：静态 url 后端与 K8S 模式后端在同一网关共存，各自路由正确、互不干扰。

## Assumptions

- 复用 003 既有架构（orchestration 包、ensure 原子幂等、动态注册、NodePortExposer/ArthasProvisioner）；本特性不重建编排核心，只增量改造 3 个扩展点。
- K8S Host 配置位置 = `application.yml` 的 `arthas-gateway.k8s-hosts`（随网关配置）；增减 host 需重启（热重载后置，YAGNI）。
- ArthasLauncher SPI 经 Spring `@Component` + `@ConditionalOnMissingBean`/`@Primary` 装配（不靠 yml 配置项），用户写一个实现类即可。
- K8S Host 的 kubeconfig 复用 003 的 `test-env/k8s/kubeconfig/k3s-admin.yaml` 模式（root-on-node 派生 admin，gitignored，受控内网）。
- 静态 url 模式后端（001/003 既有）完全兼容——K8S 改动不影响静态模式（url 与 k8sHost 互斥，老配置无 k8sHost = 静态）。
- 业务 Service 的 `arthas-mcp-gateway/target=<logical>` label 由运维预打（声明可承载 arthas 暴露）；不打则回退新建（不强制预标记）。
- 多 K8S Host 场景：每个 Host 据其 kubeconfig 构建独立 KubernetesClient + ArthasProvisioner（MVP 不做 client 复用/池化，后置）。
- patch Service 加端口 / 改 type 需 RBAC 允许（`services/patch`）；测试床 root-admin 凭证充分，生产应建最小权限 ServiceAccount。
- 测试遵循宪法原则七（TDD 真实环境，零桩）：SPI 测试含 test fixture 真实实现（非 Mockito mock）；契约 IT 跑真实 k3s + 真实 pod。
