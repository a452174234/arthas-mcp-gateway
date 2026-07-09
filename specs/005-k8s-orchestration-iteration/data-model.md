# Data Model: K8S 编排能力迭代（005）

> 005 新增/增量实体。003 既有实体（OrchestrationRecord / DynamicBackendStore / BackendEntry）行为不变（仅 BackendEntry.initializeOnce 加懒 resolve hook），不在此重复——聚焦 005 的新增与变更。

---

## 1. 实体总览

| 实体 | 类型 | 职责 | 生命周期 |
|------|------|------|----------|
| `K8sHost` | 配置态（新增） | 远端 Linux K8S 集群入口声明（name + kubeconfig + namespace） | application.yml，重启生效 |
| `BackendConfig`（增量） | 配置态 | 加 `k8sHost` + `pod` 字段（K8S 模式，与 url 互斥） | backends.yaml，热重载 |
| `BackendResolver` | 接口（新增，gateway-core） | 懒 resolve：K8S 模式 config → mcpUrl（ensure + 缓存）；静态 → empty | 单例 bean（Optional） |
| `K8sBackendResolver` | 运行期（新增，orchestration） | BackendResolver 实现：按 host 路由 provisioner.ensure + 缓存 | 单例 bean |
| `ArthasLauncher` | SPI 接口（新增，orchestration） | 定位 JVM + 启动 arthas（策略点） | 单例 bean |
| `LaunchContext` | 瞬态（新增） | ArthasLauncher 调用上下文（namespace/pod/exec/各参数） | 单次调用 |
| `DefaultArthasLauncher` | 运行期（新增） | 默认实现（003 现状逻辑外移） | @ConditionalOnMissingBean |
| `ResolveCache` | 运行期（K8sBackendResolver 内） | logicalName → mcpUrl 缓存（懒 resolve 结果） | ConcurrentHashMap |

---

## 2. K8sHost（新增配置态）

远端 Linux K8S 集群入口声明。源自 `application.yml` 的 `arthas-gateway.k8s-hosts`。

| 字段 | 类型 | 必填 | 说明 / 校验 |
|------|------|------|-------------|
| `name` | string | 是 | host 逻辑名（唯一，如 `debian-prod`）；BackendConfig 的 `k8sHost` 引用此名 |
| `kubeconfig` | string | 是 | kubeconfig 文件路径（如 `test-env/k8s/kubeconfig/k3s-admin.yaml`）；须可读 |
| `namespace` | string | 否 | 默认 namespace（缺省 `default`） |

**校验**：name 跨 host 唯一；kubeconfig 文件可读（启动期校验，不可读则该 host 装配失败）。

**配置示例**：
```yaml
arthas-gateway:
  k8s-hosts:
    - name: debian-prod
      kubeconfig: test-env/k8s/kubeconfig/k3s-admin.yaml
      namespace: default
```

---

## 3. BackendConfig（增量：K8S 模式字段）

003 既有 BackendConfig record 加两个可选字段，支持 K8S 模式。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `k8sHost` | string? | K8S 模式必填 | 引用 K8sHost.name（与 `url` 互斥） |
| `pod` | string? | K8S 模式必填 | 业务 pod 名（K8S 模式必填） |
| （既有）`url` | string? | 静态模式必填 | 直接 mcpUrl（与 k8sHost 互斥） |

**互斥校验**（紧凑构造器）：
- `url` 与 `k8sHost` **互斥**——两者皆空 → 错误（"backend 须声明 url 或 k8sHost"）；皆有 → 错误（"url 与 k8sHost 互斥"）。
- `k8sHost` 非空时 `pod` 必填（缺 → 错误）。

**不变量**：
- `equals/hashCode` 纳入 `k8sHost` + `pod`（同 url 模式 + 同 k8s 模式 = 同后端）；仍排除 `source`（003 既有）。
- K8S 模式 backend 的 `url` 字段运行期为 null（懒 resolve 出 mcpUrl 不回写 config，缓存在 resolver）。

**配置示例**：
```yaml
backends:
  - name: order-service           # K8S 模式
    k8s-host: debian-prod
    pod: order-service-abc
  - name: payment                  # 静态模式（现状不变）
    url: http://10.0.0.11:8563
    protocol: STREAMABLE
    auth: { mode: NONE }
```

---

## 4. BackendResolver（新增接口，gateway-core）

```java
// com.arthas.gateway.backend（gateway-core，零 fabric8 依赖）
public interface BackendResolver {
    /**
     * K8S 模式 config → 懒 resolve 出 mcpUrl（调 ensure + 缓存）。
     * 静态模式（config.url() 非空）→ Optional.empty()（BackendEntry 用 config.url）。
     *
     * @return mcpUrl（K8S 模式）；empty（静态模式，旁路）
     * @throws IllegalStateException K8S 模式但 host 不存在 / ensure 失败（结构化错误传播）
     */
    Optional<String> resolveMcpUrl(BackendConfig config);
}
```

**装配**：`Optional<BackendResolver>`（无 K8S 时不装配 → K8S 模式 backend 路由时 BackendEntry 抛 `no_k8s_resolver`）。

---

## 5. K8sBackendResolver（新增实现，orchestration）

BackendResolver 的 K8S 实现。

| 字段 | 类型 | 说明 |
|------|------|------|
| `provisioners` | Map<String, ArthasProvisioner> | 按 hostName → provisioner（启动期按 k8s-hosts 装配，每 host 独立 kubeconfig/client） |
| `hosts` | Map<String, K8sHost> | hostName → K8sHost（查 namespace 等） |
| `cache` | ConcurrentHashMap<String, String> | logicalName → mcpUrl（懒 resolve 缓存） |

**resolveMcpUrl(config)**：
1. config.url() 非空 → `Optional.empty()`（静态模式旁路）。
2. config.k8sHost() 非空 → 查 host（不存在 → 抛 `unknown_k8s_host`）。
3. logicalName = `{host.name}-{config.pod}`。
4. cache.get(logicalName) 命中 → 返缓存 mcpUrl。
5. 未命中 → `provisioners.get(host.name).ensure(host.name, config.pod, host.namespace)` → 拿 mcpUrl → cache.put → 返。
   - ensure failed → 抛结构化错误（no_jvm/no_shell/...）。

---

## 6. ArthasLauncher（新增 SPI，orchestration）

```java
// com.arthas.gateway.orchestration
public interface ArthasLauncher {
    /** 定位 JVM PID（默认实现：jps -q | head -1）。失败抛 LaunchException。 */
    long locatePid(LaunchContext ctx);

    /** 启动 arthas（默认实现：java -jar arthas-boot.jar <pid> --attach-only ...）。失败抛 LaunchException。 */
    void startArthas(LaunchContext ctx, long pid);
}
```

**LaunchContext**（record，瞬态）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `namespace` | string | K8S namespace |
| `pod` | string | 目标 pod 名 |
| `exec` | K8sExec | fabric8 exec 工具（默认实现用；自定义实现可用可不用） |
| `mcpPort` | int | pod 内 arthas MCP 端口 |
| `targetIp` | string | arthas 绑定地址（0.0.0.0） |
| `arthasVersion` | string | arthas 版本（4.3.0） |
| `arthasPassword` | string | arthas 鉴权密码 |
| `arthasBootJar` | Path | 上传后的 pod 内 jar 路径（/tmp/arthas-boot.jar） |
| `attachTimeout` | Duration | 启动超时 |
| `locateTimeout` | Duration | 定位超时 |

**LaunchException**（RuntimeException，含 OrchestrationRecord.Error）：
- 携带 `phase`（locate_jvm/start_arthas）+ `reason`（attach_failed/no_jvm/no_shell/...）+ `message`。
- ArthasProvisioner 捕获 → 映射 ensure failed 记录（K-ENS-4/5 不破）。

---

## 7. DefaultArthasLauncher（新增默认实现）

003 既有 `ArthasProvisioner.locateJvm`（:237-255）+ `startArthas`（:282-301）逻辑外移：

- `locatePid`：`exec.exec(ns, pod, locateTimeout, "sh", "-c", "jps -q 2>/dev/null | grep -E '^[0-9]+$' | head -1")` → 解析 pid（无 PID → LaunchException no_jvm@locate_jvm）。
- `startArthas`：`exec.exec(ns, pod, attachTimeout, "java", "-jar", arthasBootJar, pid, "--attach-only", "--http-port", mcpPort, "--target-ip", targetIp, "--telnet-port", "0", "--use-version", arthasVersion, "--password", arthasPassword)`（非零退出 → LaunchException attach_failed@start_arthas）。

**装配**：`@Component @ConditionalOnMissingBean(ArthasLauncher.class)`（用户未提供时生效）。

---

## 8. BackendEntry（增量：懒 resolve hook）

003/002 既有 BackendEntry.initializeOnce（DCL 握手）加懒 resolve hook：

```java
private void initializeOnce() {
    if (initialized) return;
    synchronized (initLock) {
        if (!initialized) {
            // 005 增量：懒 resolve（K8S 模式 → 用 mcpUrl 覆盖 config.url 建 client）
            String effectiveUrl = resolver
                .flatMap(r -> r.resolveMcpUrl(config))
                .orElse(config.url());  // 静态模式或无 resolver → 用 config.url
            if (effectiveUrl != config.url()) {  // K8S 模式 resolve 出新 url
                this.client = new HttpBackendClient(config.withUrl(effectiveUrl));
            }
            client.initialize();
            initialized = true;
        }
    }
}
```

- 注入 `Optional<BackendResolver> resolver`（无 K8S 时 empty，用 config.url）。
- K8S 模式（resolver 返 mcpUrl）→ 用 mcpUrl 建 HttpBackendClient（替代 config.url）。
- 静态模式（resolver empty 或返 empty）→ 用 config.url（现状不变）。

**不变量**：execute/admit/invoke/isHealthy 不变（统一拦截层，002）；懒 resolve 仅在 initializeOnce 首次握手时触发一次（DCL 保证）。

---

## 9. 懒 resolve 缓存状态（K8sBackendResolver.cache）

| 状态 | 含义 |
|------|------|
| 未命中 | 未 resolve 过（首次路由触发 ensure） |
| 命中 | mcpUrl 已缓存（直接用，不重复 ensure） |

**失效**：MVP 无主动失效（pod 重启 mcpUrl 失效 → 路由失败触发熔断；运维清缓存重新 resolve 后置）。ensure reused 幂等（K-ENS-2）保证重复 resolve 零副作用。

---

## 10. 关系图

```
application.yml (arthas-gateway.k8s-hosts)
        │ 加载
        ▼
   K8sHost ──► K8sOrchestrationConfig（按 host 建 KubernetesClient + ArthasProvisioner + NodePortExposer）
                    │
                    ▼
              K8sBackendResolver（Map<host, provisioner> + cache）
                    │ implements
                    ▲
   BackendResolver（接口，gateway-core）
                    │
                    ▼
   BackendEntry.initializeOnce（懒 resolve hook）
        ▲
        │ 持有
   BackendConfig（k8sHost + pod 或 url）

backends.yaml
        │ 加载/热重载
        ▼
   BackendConfig ──► BackendEntry ──►（K8S 模式）resolveMcpUrl ──► ensure ──► mcpUrl

ArthasProvisioner.ensure
        │ 委托
        ▼
   ArthasLauncher（SPI）
        ├─ DefaultArthasLauncher（@ConditionalOnMissingBean）
        └─ CustomArthasLauncher（用户 @Primary）
              │ locatePid + startArthas
              ▼
         LaunchContext（namespace/pod/exec/...）
```
