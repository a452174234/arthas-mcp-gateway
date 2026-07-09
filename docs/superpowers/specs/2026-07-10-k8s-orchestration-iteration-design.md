# K8S 编排能力迭代设计（005）

> 日期：2026-07-10
> 归档：新建 `specs/005-k8s-orchestration-iteration/`（独立特性）
> brainstorming 产出，实施走 spec-kit SDD（TDD 真实环境）
> 基线：003-k8s-arthas-mcp-launch（已实施）

## 1. 背景与目标

003 已实现「对指定 K8S pod 一键启 arthas + NodePort 暴露 + 动态纳管」（3 工具 + ensure 原子幂等）。本次迭代解决 003 落地后用户实际使用的 3 个适配缺口：

1. **Service 复用**：003 每次供给都**新建**独立 `arthas-mcp-<logical>` Service，未复用 pod 已关联的业务 Service，造成 Service 膨胀 + 与业务 Service 割裂。
2. **后端配置 K8S 场景**：003 的 `BackendConfig` 只能配静态 `url`（直接 mcpUrl）；用户实际只想配「远端 Linux 服务器（K8S 入口）+ pod」，由网关运行时刷新出 mcpUrl（懒 resolve）。
3. **JDK 适配**：003 的 `ArthasProvisioner.startArthas` 硬编码 `java -jar`（假设 java 在 PATH）、`locateJvm` 硬编码 `jps -q`。容器里常独立部署 JDK（非 PATH），需留适配口子让用户用很少代码配置 JDK 位置 + 完整启动命令。

**目标**：把 Service 模式、后端配置、JDK 启动做成**可配置/可替换**的扩展点（配置驱动 + SPI），用户改很少代码即可适配自身 K8S 环境。

### 约束

- 复用 003 既有架构（orchestration 包、ensure 原子幂等、动态注册、K-ATOMIC-1 不破）。
- **gateway-core 零 K8S 依赖不变**（ArchUnit 守护；新接口 `BackendResolver` 在 gateway-core 定义，实现在 orchestration）。
- TDD 真实环境，零桩（宪法原则七）；SPI 接口测试含 test fixture 真实实现（非 mock）。

## 2. 决策摘要（brainstorming 结论）

| # | 决策点 | 选择 | 备选（已否） |
|---|--------|------|-------------|
| 1a | Service 识别 | **label 标记 Service**（`arthas-mcp-gateway/target=<logical>` on Service，exposer labelSelector 查） | 传入 service 名 / 自动 selector 反查 |
| 1b | ClusterIP 处理 | **自动 patch type=NodePort** + 加端口（运维接受现有端口暴露到节点的风险） | 报错 service_type_not_nodeport |
| 1c | 找不到回退 | **回退新建独立 Service**（兼容 003 现状） | 报错 |
| 2a | 配置形态 | **新增 K8sHost 实体**（远端 Linux：name + kubeconfig + namespace），BackendConfig 引用 host+pod | BackendConfig 加 K8S 字段 |
| 2b | resolve 时机 | **懒 resolve**（首次路由该 target 时 ensure 拿 mcpUrl，按 logicalName 缓存） | 启动时自动 ensure / 手动 ensure |
| 2c | 配置位置 | **application.yml** `arthas-gateway.k8s-hosts`（随网关配置） | 独立 yaml 热重载 |
| 3a | JDK 适配形式 | **策略接口 SPI**（ArthasLauncher） | 纯配置驱动 / 配置+策略 |
| 3b | 命令定制度 | **完整命令模板**（用户实现类自由拼，含 javaPath + 占位符） | 只配路径 |
| 3c | SPI 测试 | **test fixture 真实实现**（TestArthasLauncher，验证委托+替换+契约） | 只测默认实现 / mock |
| — | 归档 | **新建 005 独立特性** | 003 增量 |

## 3. ① Service 模式改造（NodePortExposer）

### 现状（`orchestration/NodePortExposer.java:112-138`）

`ensureNodePortService` 总是**新建** `arthas-mcp-<sanitize(logical)>` NodePort Service（selector=`arthas-mcp-gateway/target=<label>`，label 打在 **pod** 上）。

### 改造：label 标记 Service + patch + 回退

`expose(namespace, pod, logicalName, mcpPort)` 新流程：

```
1. 计算 labelValue = sanitize(logicalName)
2. labelSelector 查 namespace 内 spec.selector 或 metadata.labels 含
   `arthas-mcp-gateway/target=<labelValue>` 的 Service（标记在 Service 上）
   ├─ 找到现有 Service：
   │    ├─ 若 type != NodePort → patch type=NodePort（自动改；运维须知现有端口暴露）
   │    └─ patch spec.ports 加一项 {port: mcpPort, targetPort: mcpPort, nodePort: 自动分配}
   │       （若已含同 targetPort 端口 → 复用其 nodePort，幂等）
   │    → 返回该 Service 的 nodePort
   └─ 找不到 → 回退现状（label pod + 新建 arthas-mcp-<logical> Service）
3. resolveNodeIp() → mcpUrl = http://<nodeIP>:<nodePort>
```

**关键不变量**：
- **幂等**：同 logicalName 重复 ensure → labelSelector 命中已 patch 的 Service → 端口已存在 → 复用 nodePort（K-ENS-2 不破）。
- **label 标记位置变化**：003 label 在 pod（selector 选 pod）；本次新增 label 在 **Service**（标记 Service 关联哪个 logical target）。两者共存不冲突（pod label 供回退新建的 Service selector 用；Service label 供复用查找用）。
- **K-ATOMIC-1 不破**：patch Service 失败 → 抛 `nodeport_alloc_failed` → ensure failed 不注册。

**契约新增**：
- **K-ENS-10**：expose 优先复用带 `arthas-mcp-gateway/target=<logical>` label 的现有 Service（patch type+port）；找不到才新建。
- **K-ENS-11**：复用现有 ClusterIP Service 时自动 patch type=NodePort（运维须知：现有端口随之暴露到节点）。

## 4. ② 后端配置 K8S 场景（K8sHost + BackendResolver 懒 resolve）

### 4.1 K8sHost 配置实体（application.yml）

新增 `arthas-gateway.k8s-hosts`（List），管「远端 Linux K8S 集群入口」：

```yaml
arthas-gateway:
  k8s-hosts:
    - name: debian-prod              # host 逻辑名（BackendConfig 引用）
      kubeconfig: test-env/k8s/kubeconfig/k3s-admin.yaml   # 凭证（gitignored）
      namespace: default             # 默认 namespace
  backends-file: config/backends.yaml
```

`GatewayProperties` 加 `List<K8sHost> k8sHosts`；`K8sHost` 内部类（name + kubeconfig + namespace）。

### 4.2 BackendConfig 加 K8S 模式（与静态 url 二选一）

`config/backends.yaml` 的 BackendConfig 增量：

```yaml
backends:
  - name: order-service
    k8s-host: debian-prod            # K8S 模式：引用 host（与 url 二选一）
    pod: order-service-abc           # 关联业务 pod
    # 无 url = K8S 模式（懒 resolve 出 mcpUrl）
  - name: payment                     # 静态 url 模式（现状不变）
    url: http://10.0.0.11:8563
```

`BackendConfig` record 加 `String k8sHost`（null = 静态模式）+ `String pod`（K8S 模式必填）。紧凑构造器校验：`url` 与 `k8sHost` 互斥（两者皆空或皆有 → 校验失败）；`k8sHost` 非空时 `pod` 必填。

> **不变量**：`equals/hashCode` 仍排除 source（003 既有），但纳入 `k8sHost`/`pod`（同 url 模式 = 同后端）。

### 4.3 BackendResolver 接口（gateway-core，零 K8S 依赖）

```java
// gateway-core（backend 包），orchestration 不感知
public interface BackendResolver {
    /** K8S 模式 config → 懒 resolve 出 mcpUrl；静态模式返 empty。 */
    Optional<String> resolveMcpUrl(BackendConfig config);
}
```

`orchestration` 实现 `K8sBackendResolver`：
- **按 host 建 provisioner**：每个 K8sHost 据其 `kubeconfig` 构建独立 `KubernetesClient` + `ArthasProvisioner`（`Map<hostName, ArthasProvisioner>`，启动期按 `k8s-hosts` 配置装配）。MVP 不做多 client 复用/池化（后置）。
- `resolveMcpUrl(config)`：若 `config.k8sHost()` 非空 → 查 host（不存在 → 抛 `unknown_k8s_host`）→ 取对应 provisioner → 调 `ensure(server=host.name, pod=config.pod, namespace=host.namespace)` → 返 mcpUrl（按 logicalName `{host}-{pod}` 缓存，K-ENS-2 幂等复用）。
- 静态模式（url 非空）→ `Optional.empty()`（BackendEntry 用 config.url，不调 resolver）。

### 4.4 懒 resolve（BackendEntry 首次 invoke）

`BackendEntry.initializeOnce` 改造（统一拦截层，002）：
- 首次 invoke 前，若 `resolver != null && resolver.resolveMcpUrl(config).isPresent()` → 用返回 mcpUrl 建/重建 `HttpBackendClient`（覆盖 config.url）。
- 缓存 resolve 结果（同 logicalName 不重复 ensure）。
- 装配：`BackendEntryFactory` 注入 `Optional<BackendResolver>`（无 K8S 时不装配，K8S 模式 backend 路由时 resolver 为空 → 抛 `no_k8s_resolver` INVALID_PARAMS）。

**零 K8S 依赖守护**：gateway-core（backend 包）只依赖 `BackendResolver` 接口（无 fabric8 import）；`K8sBackendResolver` 实现在 orchestration 包。ArchUnit 规则 1/2 仍守护。

## 5. ③ JDK 适配（ArthasLauncher SPI）

### 5.1 接口定义（orchestration 包）

```java
// orchestration 包
public interface ArthasLauncher {
    /** 定位 JVM PID（默认实现：jps -q | head -1）。 */
    long locatePid(LaunchContext ctx);

    /** 启动 arthas（默认实现：java -jar arthas-boot.jar <pid> --attach-only ...）。 */
    void startArthas(LaunchContext ctx, long pid);

    /** 启动失败（含 reason/message 供 ProvisionException 映射 failed@start_arthas）。 */
    class LaunchException extends RuntimeException {
        final OrchestrationRecord.Error error;
        // ...
    }

    record LaunchContext(
            String namespace, String pod, K8sExec exec,
            int mcpPort, String targetIp, String arthasVersion, String arthasPassword,
            java.nio.file.Path arthasBootJar, Duration attachTimeout,
            Duration locateTimeout) {}
}
```

### 5.2 默认实现 DefaultArthasLauncher（现状逻辑外移）

把 `ArthasProvisioner.locateJvm`（:237-255）+ `startArthas`（:282-301）的现状逻辑外移到 `DefaultArthasLauncher`：
- `locatePid`：`exec.exec(ns, pod, locateTimeout, "sh", "-c", "jps -q 2>/dev/null | grep -E '^[0-9]+$' | head -1")` → 解析 pid。
- `startArthas`：`exec.exec(ns, pod, attachTimeout, "java", "-jar", arthasBootJar, pid, "--attach-only", "--http-port", mcpPort, "--target-ip", targetIp, "--telnet-port", "0", "--use-version", arthasVersion, "--password", arthasPassword)`。

→ K-ENS-* 全部不破（默认行为 = 003 现状）。

### 5.3 用户自定义实现（适配口子）

用户写一个 `@Component` 实现类（实现 `ArthasLauncher`），定制 javaPath + 完整命令模板：

```java
@Component
@Primary                      // 优先于 DefaultArthasLauncher
public class CustomArthasLauncher implements ArthasLauncher {
    private static final String JAVA = "/opt/jdk-21/bin/java";   // 独立 JDK 路径
    private static final String JPS = "/opt/jdk-21/bin/jps";

    @Override
    public long locatePid(LaunchContext ctx) {
        var r = ctx.exec().exec(ctx.namespace(), ctx.pod(), ctx.locateTimeout(),
                "sh", "-c", JPS + " -q 2>/dev/null | grep -E '^[0-9]+$' | head -1");
        return Long.parseLong(r.stdout().trim());
    }

    @Override
    public void startArthas(LaunchContext ctx, long pid) {
        // 完整命令模板自由拼（含 javaPath + 任意参数）
        String cmd = JAVA + " -jar " + ctx.arthasBootJar() + " " + pid
                + " --attach-only --http-port " + ctx.mcpPort()
                + " --target-ip " + ctx.targetIp()
                + " --use-version " + ctx.arthasVersion()
                + " --password " + ctx.arthasPassword();
        ctx.exec().exec(ctx.namespace(), ctx.pod(), ctx.attachTimeout(), "sh", "-c", cmd);
    }
}
```

**装配**：`K8sOrchestrationConfig` 装配 `DefaultArthasLauncher`（`@ConditionalOnMissingBean(ArthasLauncher.class)`）；用户 `@Component @Primary` 实现自动覆盖。`ArthasProvisioner` 注入 `ArthasLauncher`（不再硬编码 locateJvm/startArthas）。

### 5.4 ArthasProvisioner 改造

- 删除私有 `locateJvm`/`startArthas`（逻辑外移到 launcher）。
- `doProvision` 改为：
  ```java
  long pid = arthasLauncher.locatePid(new LaunchContext(...));   // 委托
  installArthas(namespace, pod);                                  // 上传（不变）
  arthasLauncher.startArthas(new LaunchContext(...), pid);        // 委托
  ```
- launcher 抛 `LaunchException` → 映射 `failed@start_arthas`/`failed@locate_jvm`（K-ENS-4/5 不破）。

## 6. 配置增量（application.yml + GatewayProperties）

```yaml
arthas-gateway:
  k8s-hosts:                                     # ② 新增：远端 Linux K8S 入口
    - name: debian-prod
      kubeconfig: test-env/k8s/kubeconfig/k3s-admin.yaml
      namespace: default
  k8s:                                           # 003 既有（不变）
    kubeconfig: test-env/k8s/kubeconfig/k3s-admin.yaml
    namespace: default
    # ...（targetIp/mcpPort/arthasVersion/arthasPassword 等不变，作 ensure 默认）
```

`GatewayProperties`：
- 加 `List<K8sHost> k8sHosts`（新）。
- `K8sHost` 内部类：`String name` + `String kubeconfig` + `String namespace`。
- 不加 launcher 配置项（SPI 经 `@Component` 自动发现，不靠 yml）。

## 7. 数据流（懒 resolve + 委托 launcher）

### 7.1 K8S 模式 backend 首次路由（懒 resolve）

```
Claude: tools/call jvm {target: order-service}
  → ToolsCallRouter.route (SYNC_DIRECT)
  → resolveTarget("order-service") → BackendEntry（config.k8sHost="debian-prod", pod="order-service-abc"）
  → entry.execute
    → admitCore (熔断+取槽)
    → invoke:
        → initializeOnce:
            → resolver.resolveMcpUrl(config)  [K8sBackendResolver]
                → 查 host "debian-prod" → provisioner.ensure("debian-prod", "order-service-abc", host.namespace)
                    → doProvision:
                        → arthasLauncher.locatePid(ctx)          [Default/Custom]
                        → installArthas (上传 arthas-boot.jar)
                        → arthasLauncher.startArthas(ctx, pid)
                        → NodePortExposer.expose                  [label Service patch / 回退新建]
                        → probeHealthy (MCP 握手)
                        → dynamicStore.register(target="debian-prod-order-service-abc", mcpUrl)
                    ← mcpUrl
                ← 缓存 mcpUrl (logicalName)
            → 用 mcpUrl 建 HttpBackendClient（替代 config.url）
        → client.callTool("jvm", {})
    → finally releaseSlot
  ← CallToolResult 原样返
```

后续路由同 target → resolver 缓存命中 → 直接用 mcpUrl（不重复 ensure）。

### 7.2 Service patch 数据流（expose 内部）

```
expose(ns, pod, logicalName, mcpPort)
  → labelSelector 查 Service 带 arthas-mcp-gateway/target=<sanitize(logicalName)>
  ├─ 找到 svc：
  │    → 若 svc.spec.type != NodePort → patch type=NodePort
  │    → 若 ports 不含 targetPort=mcpPort → patch ports.add({port:mcpPort, targetPort:mcpPort})
  │    ← 返回该端口的 nodePort
  └─ 找不到 → 回退：labelPod + ensureNodePortService（003 现状）
  → resolveNodeIp → mcpUrl
```

## 8. 测试（TDD 真实环境 + SPI test fixture）

### 8.1 Service 模式（NodePortExposerTest/IT）
- 找到带 label 的现有 NodePort Service → patch 加端口（幂等：重复 patch 不重建端口）。
- 找到 ClusterIP Service → 自动改 type=NodePort（K-ENS-11）+ 加端口。
- 找不到带 label Service → 回退新建独立 Service（K-ENS-10 回退）。
- 真实 k3s（k3s Service 预打 label 测复用）。

### 8.2 K8S 模式后端（K8sBackendResolverTest/IT）
- K8S 模式 config（k8sHost 非空）→ resolveMcpUrl 调 ensure 返 mcpUrl。
- 缓存：同 logicalName 二次 resolve 不重复 ensure（K-ENS-2）。
- 静态模式 config（url 非空）→ resolveMcpUrl 返 empty（旁路，用 config.url）。
- host 不存在 → `unknown_k8s_host` 错误。
- 无 K8S（resolver 未装配）→ K8S 模式 backend 路由 → `no_k8s_resolver` INVALID_PARAMS。

### 8.3 ArthasLauncher SPI（三件套，零桩真实实现）

| 测试类 | scope | 验证 |
|--------|-------|------|
| `DefaultArthasLauncherTest` | 单测 | 默认实现 = 003 现状逻辑（jps -q / java -jar 标准参数）；K-ENS-4/5 不破 |
| `TestArthasLauncher`（**test fixture 真实实现**） | `src/test/java/.../orchestration/` | 固定返 pid `12345`、startArthas 记录 LaunchContext 参数；验证委托 + 替换 + 契约 |
| `ArthasLauncherSpiTest` | 单测 | `@Primary` TestArthasLauncher 覆盖 Default；ArthasProvisioner.doProvision 真调 launcher.locatePid/startArthas（非硬编码） |
| `K8sEnsureContractIT` 扩展 | 真实 k3s | DefaultArthasLauncher 端到端（SC-001 不破）+ 注入 TestArthasLauncher 验证替换链路 |

**TestArthasLauncher**（test fixture，非 mock）：
```java
public class TestArthasLauncher implements ArthasLauncher {
    public long locatePid(LaunchContext ctx) { return 12345L; }  // 固定
    public void startArthas(LaunchContext ctx, long pid) {
        // 记录调用（验证委托）— 用静态字段或实例字段记录 LaunchContext
        StartArthasProbe.record(ctx, pid);
    }
}
```

### 8.4 配置与装配（K8sHostConfigTest / 装配 IT）
- application.yml `k8s-hosts` 解析 → `List<K8sHost>`。
- `@ConditionalOnMissingBean(ArthasLauncher.class)` → DefaultArthasLauncher 装配；用户 `@Primary` 实现覆盖。
- `Optional<BackendResolver>` 无 K8S 时不装配。

## 9. 文档与归档

- **新建 `specs/005-k8s-orchestration-iteration/`**（spec-kit SDD 全套）：
  - `spec.md`：FR-001~005（Service 复用 / K8sHost / 懒 resolve / ArthasLauncher SPI / test fixture）+ SC-001~003。
  - `research.md`：R1（label Service 复用策略）/ R2（懒 resolve vs 启动 ensure）/ R3（SPI vs 配置驱动）。
  - `data-model.md`：K8sHost 实体 + BackendConfig 增量（k8sHost/pod）+ ArthasLauncher/LaunchContext。
  - `contracts/`：K-ENS-10/11 + INV-K8SHOST-* + INV-LAUNCHER-*。
  - `tasks.md`：TDD 任务块（测试先于实现）。
  - `quickstart.md`：场景（复用业务 Service / 配 K8S host backend / 自定义 launcher）。
- **003 契约更新**：K-ENS-10/11 加入 `003/contracts/k8s-orchestration-tools-contract.md`（Service 复用行为）。
- **README/handbook 更新**：补 005 能力（part4 K8S + part9 决策）。

## 10. 不在本范围（YAGNI）

- **多集群负载均衡**（K8sHost 列表选最优）—— MVP 按 name 引用，不调度。
- **K8sHost 热重载**（增减 host 免重启）—— application.yml 配置，重启生效；热重载后置。
- **Service patch 回滚**（ensure failed 时撤销 type 改动）—— 副作用随 OrchestrationRecord 留存供运维清理（K-ATOMIC-1 不变）。
- **ArthasLauncher 配置化模板**（yml 配命令模板）—— 选 SPI（实现类）已足够灵活，模板字符串后置。
- **Backward-compat 老 BackendConfig 无 k8sHost** —— 静态 url 模式完全兼容（url 与 k8sHost 互斥，老配置无 k8sHost = 静态模式）。
