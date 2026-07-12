# Data Model: K8S Host 远程接入与配置热生效（006）

> 006 新增/增量实体。003/005 既有实体（OrchestrationRecord / DynamicBackendStore / BackendEntry / K8sBackendResolver / BackendResolver）行为基本不变（仅 K8sBackendResolver 改从 store 查 provisioner、BackendConfigWatcher 改 compose 异常处理），不重复——聚焦 006 新增与变更。

---

## 1. 实体总览

| 实体 | 类型 | 职责 | 生命周期 |
|------|------|------|----------|
| `K8sHost`（增量） | 配置态 | 加 `ssh` 子段（SSH 引导接入），与 `kubeconfig` 互斥 | `config/k8s-hosts.yaml`，热重载 |
| `SshBootstrap` | 瞬态 record | `ssh` 子段运行时映射（传给 Fetcher） | 单次建 client |
| `SshKubeconfigFetcher` | 运行期类（新） | SSH 登 master 取 kubeconfig 文本（sshj） | 单例 |
| `HostEntry` | 运行期（新） | host→client/exposer/provisioner 三件套 | K8sHostStore 管理 |
| `K8sHostStore` | 运行期（新） | host 生命周期 `applyDiff`（建/关/重建） | 单例 |
| `K8sHostsWatcher` | 运行期（新） | 监听 `config/k8s-hosts.yaml` → `applyDiff` | 单例 |
| `K8sParams` | 运行期快照（新） | k8s 全局参数可刷新值 | K8sHostStore 持有 |
| `K8sHostSecretCipher` | 运行期（新） | AES-GCM 加密 ssh 凭证 | 单例 |
| `K8sHostDto` / `Create/Update Request` | DTO（新） | portal CRUD（凭证脱敏） | 请求级 |
| `BackendDto`（增量） | DTO | 加 K8S 来源字段 | 请求级 |

---

## 2. K8sHost（增量：ssh 子段）

005 既有 `K8sHost`（`GatewayProperties.java:258-289`：name + kubeconfig + namespace）加 `ssh` 子结构，与 `kubeconfig` **互斥**。

| 字段 | 类型 | 必填 | 说明 / 校验 |
|------|------|------|-------------|
| `name` | string | 是 | host 逻辑名（唯一，BackendConfig.k8sHost 引用）|
| `namespace` | string | 否 | 默认 namespace（缺省 `default`）|
| `kubeconfig` | string? | 静态模式必填 | 本地 kubeconfig 文件路径（005 既有，与 ssh 互斥）|
| `ssh` | Ssh? | SSH 模式必填 | SSH 引导子段（与 kubeconfig 互斥）|

**互斥校验**（紧凑构造器）：`kubeconfig` 与 `ssh` 互斥——皆空 → 错误（"host 须声明 kubeconfig 或 ssh"）；皆有 → 错误（"kubeconfig 与 ssh 互斥"）。

**配置示例**（`config/k8s-hosts.yaml`）：
```yaml
version: 1
hosts:
  - name: corp-prod
    namespace: default
    ssh:
      host: 10.0.1.5
      port: 22
      user: root
      password: ${K8S_SSH_PASSWORD}        # 或加密值（portal 写入）
      kubeconfig-remote-path: /etc/kubernetes/admin.conf
      server-override: https://10.0.1.5:6443
      insecure-skip-tls-verify: false
  - name: local-k3s                         # 005 既有模式保留
    kubeconfig: test-env/k8s/kubeconfig/k3s-admin.yaml
    namespace: default
k8s-params:                                  # 全局参数（可刷新，R6）
  target-ip: 0.0.0.0
  mcp-port: 8563
  arthas-version: "4.3.0"
  arthas-password: ${ARTHAS_PASSWORD}
  ensure-timeout: 5m
  node-port-range: 30000-32767
```

---

## 3. SshBootstrap（新增 record，瞬态）

`GatewayProperties.K8sHost.Ssh` 配置态的运行时映射（传给 `SshKubeconfigFetcher`，剥离 Spring 配置类依赖）。

| 字段 | 类型 | 说明 |
|------|------|------|
| `host` | string | master IP |
| `port` | int | SSH 端口（默认 22）|
| `user` | string | SSH 用户（通常 root）|
| `password` | string? | 密码（已解密；与 privateKey 二选一）|
| `privateKey` | string? | 私钥内容/路径 |
| `passphrase` | string? | 私钥口令 |
| `kubeconfigRemotePath` | string | 远端 kubeconfig 路径（admin.conf / k3s.yaml）|
| `serverOverride` | string? | kubeconfig server 替换值 |
| `insecureSkipTlsVerify` | boolean | 跳过 TLS 校验（默认 false）|

---

## 4. SshKubeconfigFetcher（新增，orchestration）

**职责**：SSH 登 master + `exec cat <path>` 取 kubeconfig 文本。封装 sshj（R1）。

```java
public final class SshKubeconfigFetcher {
    public String fetchKubeconfig(SshBootstrap ssh) { ... }
}
```

- 连接超时 10s + exec 超时 15s（避免悬挂 host 阻塞）。
- 成功 → 返回 kubeconfig 文本；`cat` exitCode≠0 或 stderr 含 "No such file" → `kubeconfig_not_found`。

**SshBootstrapException**（RuntimeException，含 `reason`）：

| reason | 触发 |
|--------|------|
| `ssh_unreachable` | 连不上 host:port（超时/拒绝）|
| `ssh_auth_failed` | 错密码 / 错 key / 口令错 |
| `kubeconfig_not_found` | 远端路径不存在/不可读 |
| `kubeconfig_invalid` | 内容空/解析失败 |

---

## 5. K8sHostStore + HostEntry（新增，orchestration，波2 核心）

### HostEntry（运行期）

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | string | host 逻辑名 |
| `client` | KubernetesClient | fabric8 client（ssh 引导或 kubeconfig 建）|
| `exposer` | NodePortExposer | NodePort 暴露器 |
| `provisioner` | ArthasProvisioner | ensure 供给器 |
| `k8sHost` | K8sHost | 配置（namespace 等）|

实现 `AutoCloseable`（`close()` 关 client 释放连接池）。

### K8sHostStore

| 字段 | 类型 | 说明 |
|------|------|------|
| `byName` | ConcurrentHashMap<String, HostEntry> | hostName → HostEntry（路由读无锁）|
| `params` | volatile K8sParams | 全局参数快照（可刷新）|
| `resolverCache` | 缓存引用 | host 重建时清该 host 的 resolve 缓存 |

**applyDiff(List<K8sHost> desired)**（synchronized，状态转换）：
```
desired 每个 host:
  - byName 无 → 新增：buildFromSsh/buildFromKubeconfig 建 client → new HostEntry → put
  - byName 有 且 配置变了 → 修改：old.close() + 重建 + replace + 清该 host resolve 缓存
byName 中不在 desired 的 host:
  - 删除：entry.close() + remove + 清缓存
刷新 params（k8s-params 变更）
```

**线程安全**：`applyDiff` 同步（重建串行）；路由经 `K8sBackendResolver` 读 `store.get(host).provisioner` 无锁。

---

## 6. K8sHostsWatcher（新增，orchestration）

仿 `BackendConfigWatcher`（001）：

| 方法 | 说明 |
|------|------|
| `watchLoop()` | WatchService 监听 `config/k8s-hosts.yaml` + 防抖去抖 |
| `reloadOnce()` | 解析 yaml（解密 ssh 凭证）→ 调 `store.applyDiff(hosts)` |
| `start()` / `close()` | 生命周期（容器启停）|

解析失败 → 回退上次有效配置 + WARN 日志（INV-HOT-4，不崩）。

---

## 7. K8sParams（新增，全局参数快照）

| 字段 | 类型 | 说明 |
|------|------|------|
| `targetIp` | string | arthas 绑定 IP（0.0.0.0）|
| `mcpPort` | int | pod 内 arthas MCP 端口 |
| `arthasVersion` | string | arthas 版本（4.3.0）|
| `arthasPassword` | string | arthas 鉴权密码 |
| `ensureTimeout` | Duration | ensure 全流程超时 |
| `nodePortRange` | string | NodePort 分配范围 |
| `arthasBootJar` | string | arthas-boot.jar 路径 |

`ArthasProvisioner` 每次 ensure 读 `store.currentParams()`（不缓存启动期值）。变更 → 下次 ensure 用新值（已 ensure 的 pod 不变，幂等）。

---

## 8. K8sHostSecretCipher（新增，admin/k8shost）

**职责**：AES-GCM 加密/解密 ssh 凭证（R7）。

```java
public final class K8sHostSecretCipher {
    public String encrypt(String plain);    // → base64(ciphertext+iv+tag)
    public String decrypt(String enc);      // → plain
    public boolean isConfigured();          // ARTHAS_GATEWAY_SECRET 是否就绪
}
```

- 密钥来自环境变量 `ARTHAS_GATEWAY_SECRET`（256-bit base64）。
- `isConfigured()=false` → portal 写凭证端点返 400 `secret_key_not_configured`。
- 加密落盘到 `config/k8s-hosts.yaml` 的 ssh.password 等；`K8sHostsWatcher.reloadOnce` 加载时解密。

---

## 9. portal DTO（新增，admin/k8shost）

| DTO | 字段 | 备注 |
|-----|------|------|
| `K8sHostDto` | `name/namespace/mode(ssh|kubeconfig)/sshHost/sshUser/kubeconfigRemotePath/connectionStatus` | **无 password/privateKey/passphrase**（INV-PORTAL-K8S-2 脱敏）|
| `CreateK8sHostRequest` | 全字段含 `sshPassword/sshPrivateKey`（明文，仅写入用） | 写入后不回显 |
| `UpdateK8sHostRequest` | 可空字段（null=不改；密码留空=不改） | |

`connectionStatus`：`connected` / `ssh_unreachable` / `ssh_auth_failed` / `kubeconfig_not_found`（来自最近一次 HostEntry 建/重建结果）。

---

## 10. BackendDto（增量：K8S 来源字段）

004 既有 `BackendDto`（part5 §37.4）加可选字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `k8sHost` | string? | host 名（K8S 来源 backend 有值）|
| `pod` | string? | 业务 pod 名 |
| `namespace` | string? | namespace |
| `sourceDetail` | string? | 来源描述（如 `ssh:corp-prod` / `kubeconfig:local-k3s` / `static:url`）|
| `ensureStatus` | string? | `ready`/`reused`/`failed`/`ensuring`（来自 OrchestrationRecordStore）|

投影自 `OrchestrationRecordStore` + host 配置（`BackendAdminService.list` 增强）。仍**不含** token/password（INV-SECRET-1 不破）。

---

## 11. config/k8s-hosts.yaml 格式

```yaml
version: <long>
hosts:
  - name: <string>
    namespace: <string?>
    kubeconfig: <string?>        # 与 ssh 互斥
    ssh:                          # 与 kubeconfig 互斥
      host: <string>
      port: <int>
      user: <string>
      password: <string>         # 加密值（portal）或 ${ENV}（手改）
      privateKey: <string?>
      passphrase: <string?>
      kubeconfigRemotePath: <string>
      serverOverride: <string?>
      insecureSkipTlsVerify: <boolean>
k8s-params:
  targetIp: <string>
  mcpPort: <int>
  arthasVersion: <string>
  arthasPassword: <string>
  ensureTimeout: <duration>
  nodePortRange: <string>
  arthasBootJar: <string>
```

**位置**：`config/k8s-hosts.yaml`（指针 `arthas-gateway.k8s-hosts-file`）。`.gitignore`（含加密凭证）。

---

## 12. 关系图

```
config/k8s-hosts.yaml（或 application.yml 回退）
        │ WatchService
        ▼
K8sHostsWatcher ──reloadOnce(解密凭证)──► K8sHostStore.applyDiff(desired)
                                              │ diff by name
                ┌─────────────────────────────┼─────────────────────────────┐
                ▼                             ▼                             ▼
          新增 host                       修改 host                       删除 host
       buildFromSsh(ssh)              close 旧 + 重建                 close + remove
       buildFromKubeconfig(path)      清 resolve 缓存                清 resolve 缓存
                │                             │
                ▼                             ▼
           HostEntry(client/exposer/provisioner) ──► K8sBackendResolver.get(host)
                                                        │ resolveMcpUrl
                                                        ▼
                                                   ensure → mcpUrl（缓存）

portal /admin/k8s-hosts CRUD ──写──► config/k8s-hosts.yaml ──► K8sHostsWatcher（统一管道）
        │ K8sHostSecretCipher（AES-GCM）加密 ssh 凭证

K8sParams（快照）──► ArthasProvisioner.ensure 每次 read（全局参数热生效）

BackendDto 增量 ◄── BackendAdminService.list ◄── RegistryHolder + OrchestrationRecordStore（K8S 来源投影）
```
