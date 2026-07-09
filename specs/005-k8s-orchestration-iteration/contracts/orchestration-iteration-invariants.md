# 契约与不变量：K8S 编排能力迭代（005）

> 005 新增/扩展 003 既有契约。003 既有断言（K-ENS-1~9 / K-ATOMIC-1 / D-* / I-*）全部不破（FR-013 回归）；本文聚焦 005 新增的 Service 复用、K8S Host 配置、懒 resolve、ArthasLauncher SPI 的不变量。

---

## 1. Service 复用契约（扩展 K-ENS，US1）

### K-ENS-10 · 优先复用带 label 的现有 Service

`NodePortExposer.expose` 必须先用 labelSelector 查 namespace 内带 `arthas-mcp-gateway/target=<sanitize(logical)>` label 的 **Service**：
- **找到** → patch 该 Service 加 NodePort 端口（targetPort=mcpPort），**不新建**独立 Service。
- **找不到** → 回退新建独立 `arthas-mcp-<sanitize(logical)>` Service（兼容 003，FR-004）。

**断言 K-ENS-10**：业务 Service 预打 `arthas-mcp-gateway/target=<logical>` label → ensure 后该 Service 被 patch（ports 含 mcpPort 的 NodePort），mcpUrl 经该 Service 可达；namespace 内**不出现**新的 `arthas-mcp-*` Service。

### K-ENS-11 · ClusterIP 自动改 NodePort

命中的 Service 若 `spec.type != NodePort`（如 ClusterIP），expose 必须 patch `spec.type=NodePort`（加端口）。运维须知：Service 现有端口随之暴露到节点（日志 WARN）。

**断言 K-ENS-11**：ClusterIP 业务 Service（带 label）→ ensure 后 `spec.type=NodePort` + 加 mcpPort 端口；mcpUrl 经新 NodePort 可达。

### K-ENS-12 · Service patch 幂等

重复 ensure（同 logical）命中同 Service → 已含同 targetPort 端口 → 复用既有 NodePort，不重复添加（K-ENS-2 幂等复用的 Service 侧延伸）。

**断言 K-ENS-12**：二次 ensure 同 target → Service ports 数不变（不重复加端口），nodePort 复用。

---

## 2. K8S Host 配置不变量（US2）

### INV-K8SHOST-1 · url 与 k8sHost 互斥

BackendConfig 的 `url` 与 `k8sHost` 互斥：两者皆空或皆有 → 配置加载校验失败，**保留旧注册表**（不半替换，§11 规则 7）。`k8sHost` 非空时 `pod` 必填。

**断言 INV-K8SHOST-1**：
- `url` + `k8sHost` 皆有 → 加载失败 + 旧注册表保留。
- `k8sHost` 非空 + `pod` 缺 → 加载失败。
- 老 BackendConfig（无 k8sHost）= 静态模式，零迁移兼容。

### INV-K8SHOST-2 · 懒 resolve 缓存幂等

K8sBackendResolver 按 logicalName（`{host}-{pod}`）缓存 mcpUrl；二次 resolve 同 target → 缓存命中，不重复 ensure（K-ENS-2 延伸）。

**断言 INV-K8SHOST-2**：同 K8S 模式 target 二次路由 → resolver 不调 ensure（缓存命中），mcpUrl 与首次一致。

### INV-K8SHOST-3 · host 不存在错误

K8S 模式 backend 引用不存在的 `k8sHost` 名 → 懒 resolve 抛 `unknown_k8s_host`（结构化错误），不静默成功。

**断言 INV-K8SHOST-3**：`k8s-host: nonexistent` → 首次路由返 INVALID_PARAMS + `reason=unknown_k8s_host`。

### INV-K8SHOST-4 · 无 K8S resolver 错误

网关未启用 K8S（无 kubeconfig / resolver 未装配）→ K8S 模式 backend 路由抛 `no_k8s_resolver`（提示需配置 K8S）；静态模式不受影响。

**断言 INV-K8SHOST-4**：无 `arthas-gateway.k8s` 配置 + K8S 模式 backend → 路由返 INVALID_PARAMS + `reason=no_k8s_resolver`；静态 url backend 正常。

### INV-K8SHOST-5 · 静态模式旁路

静态模式 backend（url 非空、无 k8sHost）→ `resolveMcpUrl` 返 `Optional.empty()`，BackendEntry 用 config.url（001/003 行为完全不变）。

**断言 INV-K8SHOST-5**：静态 backend 路由不调 resolver/ensure，行为与 001/003 逐字一致。

---

## 3. ArthasLauncher SPI 不变量（US3）

### INV-LAUNCHER-1 · SPI 委托

`ArthasProvisioner.doProvision` 必须委托 `ArthasLauncher.locatePid` + `startArthas`（不再硬编码 java/jps/参数）。

**断言 INV-LAUNCHER-1**：ArthasProvisioner 不含 `java`/`jps` 命令字符串硬编码；locatePid/startArthas 经 launcher 接口调用（test fixture 验证委托）。

### INV-LAUNCHER-2 · DefaultArthasLauncher 兼容现状

无用户自定义实现时，`DefaultArthasLauncher`（003 现状逻辑外移）装配；行为与 003 逐字一致（K-ENS-4/5 不破）。

**断言 INV-LAUNCHER-2**：仅 DefaultArthasLauncher → ensure 行为 = 003（jps -q / java -jar 标准参数）；K8sEnsureContractIT（003 既有用例）全绿。

### INV-LAUNCHER-3 · @Primary 自定义覆盖

用户 `@Component @Primary ArthasLauncher` 实现自动覆盖 DefaultArthasLauncher（Spring 装配优先级）。

**断言 INV-LAUNCHER-3**：classpath 含 `@Primary` 自定义实现 → ArthasProvisioner 注入的是自定义实现（非 Default）；test fixture（@Primary）覆盖 Default 验证。

### INV-LAUNCHER-4 · LaunchException 映射

`ArthasLauncher.locatePid`/`startArthas` 失败抛 `LaunchException`（含 phase + reason）→ ArthasProvisioner 映射 ensure failed 记录（`failed@locate_jvm`/`failed@start_arthas`），与 003 故障分类一致。

**断言 INV-LAUNCHER-4**：自定义 launcher 抛 LaunchException(attach_failed@start_arthas) → ensure 返 failed + `error.reason=attach_failed, phase=start_arthas`；结构化错误传播。

### INV-LAUNCHER-5 · test fixture 真实实现（TDD）

SPI 测试必须含一个 **test fixture 真实实现**（`TestArthasLauncher`，非 Mockito mock），验证：
- 委托：ArthasProvisioner 真调 launcher.locatePid/startArthas。
- 装配优先级：@Primary fixture 覆盖 Default。
- 接口契约：locatePid 返合法 pid、startArthas 记录 LaunchContext、抛异常映射正确。

**断言 INV-LAUNCHER-5**：`TestArthasLauncher`（implements ArthasLauncher，真实 Java 类）+ `ArthasLauncherSpiTest` 验证上述三点（宪法原则七，零桩）。

---

## 4. 包边界不变量（FR-014，ArchUnit 守护）

### INV-BOUNDARY-1 · BackendResolver 接口零 fabric8 依赖

`BackendResolver` 接口（`backend` 包，gateway-core）**不得 import** `io.fabric8.*` / `io.kubernetes.*` / `com.arthas.gateway.orchestration.*`。ArchUnit 规则 1/2 守护不变。

**断言 INV-BOUNDARY-1**：`backend.BackendResolver` 字节码无 fabric8/orchestration 依赖；ArchUnit `PackageBoundaryTest` 全绿。

### INV-BOUNDARY-2 · K8sBackendResolver 实现在 orchestration

`K8sBackendResolver`（BackendResolver 实现）在 `orchestration` 包（依赖 fabric8 合法）；经 config（组合根）装配为 `Optional<BackendResolver>` 注入 BackendEntry。

**断言 INV-BOUNDARY-2**：`K8sBackendResolver` 在 `orchestration` 包；`BackendEntry`（backend 包）注入 `Optional<BackendResolver>`（接口，非实现）。

---

## 5. 回归契约（FR-013，003 既有不破）

以下 003 契约必须继续通过（回归门禁）：

| 003 契约 | 005 验证 |
|----------|----------|
| K-ATOMIC-1（任一子步失败不注册） | doProvision try/catch 不变（含 launcher 委托异常） |
| K-ENS-2（幂等复用） | 懒 resolve 缓存 + Service patch 幂等（K-ENS-12） |
| K-ENS-4（无 JVM → no_jvm） | DefaultArthasLauncher.locatePid 不变；自定义映射同 reason |
| K-ENS-5（无 shell → no_shell） | classifyExec 不变 |
| K-ENS-6（RBAC 403 → k8s_forbidden） | classifyExec 不变 |
| K-ENS-7（loopback → health_check_timeout） | probeHealthy 不变 |
| K-ENS-8（命名 {server}-{pod}） | deriveLogicalName 不变（懒 resolve 用同逻辑名） |
| K-ENS-9（动态名 ∩ 静态 → name_conflict） | DynamicBackendStore.register 不变 |
| SC-001（5min 端到端） | K8sEnsureContractIT（DefaultArthasLauncher）全绿 |
