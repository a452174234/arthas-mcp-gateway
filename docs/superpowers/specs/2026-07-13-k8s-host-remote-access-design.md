# K8S Host 远程接入与配置热生效设计（006）

> 日期：2026-07-13
> 归档：新建 `specs/006-k8s-host-remote-access/`（独立特性，单 spec 分波次实施）
> brainstorming 产出（`superpowers:brainstorming`），代码实施走 spec-kit SDD（TDD 真实环境、零桩）
> 基线：005-k8s-orchestration-iteration（已实施，US2 K8sHost + 懒 resolve 已落地）

---

## 1. 背景与目标

005 已实现 K8sHost 配置实体（`name + kubeconfig + namespace`）+ BackendConfig K8S 模式（`k8sHost + pod`，懒 resolve）。但用户实际部署到公司内网标准 K8S 时，暴露 4 个缺口：

1. **用户拿不到 kubeconfig，只拿得到 Linux 主机 root SSH 凭证**。005 的 K8sHost 要求提供 `kubeconfig` 文件路径（`GatewayProperties.K8sHost.kubeconfig`，`GatewayProperties.java:262`），用户「K8S 自身的鉴权（kubeconfig/证书/token）处理不了」，只能给 master 节点的 `root + 密码`。当前网关无法据此连接——这是首要阻塞。
2. **所有 K8S 配置变更都要重启网关才生效**。K8sHost 是 `@ConfigurationProperties` 启动期一次性绑定（`data-model.md §1`：生命周期「application.yml，重启生效」），`K8sOrchestrationConfig.backendResolver`（`:107-129`）启动期按 host 建好不可变 client/provisioner Map。用户明确要求「**所有配置变更都热生效**」——增删改 host、改 ssh 密码、改 arthas 版本等，都要运行时生效，不重启。
3. **portal 不承担 K8sHost 配置**。portal（004）只 CRUD `BackendConfig`（`/admin/backends`，part5 §37），完全不碰 K8sHost。用户要求 portal 能配置 Linux 连接信息（IP/账户/密码等），即 K8sHost 的 CRUD + 热生效触发源之一。
4. **ensure 连上的 pod/arthas 在 portal list 看不到**。用户实测：调 `k8s.ensure-arthas-mcp` 成功纳管后，portal `GET /admin/backends` 列表里看不到该动态 backend（待 bug 排查结论回填 §9.4）。即便可见，`BackendDto`（part5 §37.4）也无 K8S 特有信息（host/pod/namespace/连接来源/ensure 状态），看不出这是哪台主机哪个 pod。

**目标**：把「接入方式 / 配置生效 / 管理入口 / 可观测」四件事做成统一能力——

- 用户只需配 **master IP + root + 密码**，网关 SSH 自动取 admin kubeconfig，免用户处理 K8S 鉴权；
- K8S 配置（K8sHost + k8s 全局参数）**全部热生效**，配置变更（手改文件 / portal / 任何来源）经统一热重载管道触发 host 重建或下次 ensure 读新值；
- portal 成为 K8sHost 的管理面（CRUD + 密码安全），复用热重载管道；
- ensure 纳管的 pod/arthas 在 portal 可见，且带 K8S 来源信息。

### 约束（不可破）

- **复用 003/005 既有架构**：orchestration 包承载 K8S/SSH 编排；ensure 原子幂等（K-ATOMIC-1）、懒 resolve（INV-K8SHOST-*）、Service 复用（K-ENS-10/11/12）全部不破。
- **gateway-core 零 K8S/SSH 依赖不变**（ArchUnit 守护；`BackendResolver` 接口仍在 gateway-core，新增 SSH 相关类全部落 orchestration 包）。
- **向后兼容**：现有 `kubeconfig` 本地文件路径模式（003/005）100% 保留；`config/k8s-hosts.yaml` 不存在时回退读 `application.yml` 内联 `k8s-hosts`。
- **TDD 真实环境、零桩**（宪法原则七 + CLAUDE.md）：SSH 链路用真实 SSH 协议（Apache MINA SSHD embedded server，非桩）；契约 IT 跑真实测试床 k3s（debian 192.168.31.92）+ 真实 SSH + 真实 pod；故障用真实故障条件（错密码/停 SSH/错路径）。
- **root 密码极敏感**：永不裸写 application.yml / 配置文件明文；DTO / 日志永不回显（INV-SECRET-1 升级到 SSH 凭证）。

---

## 2. 决策摘要（brainstorming 结论）

| # | 决策点 | 选择 | 备选（已否） |
|---|--------|------|-------------|
| 1 | 接入凭证来源 | **SSH 引导**：网关 SSH 登 master 取 `/etc/kubernetes/admin.conf`（k3s 取 `/etc/rancher/k3s/k3s.yaml`），构造 fabric8 client | 用户提供 kubeconfig 内联 / 真用 BasicAuth（k3s 不支持）/ SSH 隧道转发（内网互通不需要）|
| 2 | SSH 角色 | **仅一次性引导取 kubeconfig**（内网互通，6443/NodePort 直连，不做隧道） | 长期 SSH 隧道转发 6443 |
| 3 | kubeconfig 取后处理 | **可选 `server-override`**（kubeconfig server 不可达时替换）+ **可选 `insecure-skip-tls-verify`**（SAN 不匹配兜底，默认 false） | 强制集群配好 tls-san / 强制隧道 |
| 4 | SSH 库 | **sshj**（API 简洁现代、活跃、支持 Ed25519、需求匹配） | mwiede/jsch（备选）/ Apache MINA SSHD（过重） |
| 5 | 配置生效 | **全部热生效**：K8sHost + k8s 全局参数，统一热重载管道 | 部分重启生效 |
| 6 | 配置载体 | **独立 `config/k8s-hosts.yaml`**（仿 `backends.yaml` + WatchService），application.yml 只留文件指针 `arthas-gateway.k8s-hosts-file` | application.yml 启动绑定 |
| 7 | host 生命周期 | **`K8sHostStore`**（仿 `DynamicBackendStore`），文件变更 diff 增/删/改 → SSH 建新 client / 关旧 client / 重建 | 启动期不可变 Map |
| 8 | 全局参数热生效 | **下次 ensure 读新值**（arthas-password/version/ensure-timeout/node-port-range/target-ip/mcp-port，不需重建 client） | 重启 |
| 9 | portal 角色 | **CRUD K8sHost**（写 `config/k8s-hosts.yaml` → 复用热重载管道），是热重载的触发源之一 | portal 独立直建 client / 只读 |
| 10 | 密码存储 | **portal 接收明文 → AES-GCM 加密写文件**（密钥来自环境变量 `ARTHAS_GATEWAY_SECRET`），加载解密；回显脱敏 | 裸明文 / Vault（P2） |
| 11 | 显示增强 | **`BackendDto` 加 K8S 字段**（k8sHost/pod/namespace/source-detail/ensure 状态）+ 前端展示；先修 ensure 不显示 bug | 独立 K8S 视图（P2） |
| 12 | 交付组织 | **单 spec-006 分波次**（4 波，每波独立可用、回归不破） | 拆 006/007 两 spec |
| — | 归档 | **新建 006 独立特性** | 005 增量 |

---

## 3. 现状回顾（证据）

### 3.1 当前 K8S 连接方式 = kubeconfig 文件 + TLS 证书（非 SSH / 非账户密码）

```
application.yml: arthas-gateway.k8s-hosts[].kubeconfig (文件路径)
   → K8sClientFactory.buildFromKubeconfig(path)        [K8sClientFactory.java:53-66]
   → Config.fromKubeconfig(content)                    [fabric8 解析, 取 current-context]
   → KubernetesClient ──HTTPS──► 192.168.31.92:6443 (K8S API server)
```

`test-env/k8s/kubeconfig/k3s-admin.yaml` 含 `server: https://192.168.31.92:6443` + `certificate-authority-data` + `client-certificate-data` + `client-key-data`（TLS 双向证书认证，root-on-node 派生 admin 证书，part4 §33.3）。**无 username/password 字段**。网关运行时**不走 SSH**。

### 3.2 K8sHost 启动期加载（重启生效）

`GatewayProperties.K8sHost`（`GatewayProperties.java:258-289`）字段 `name + kubeconfig + namespace`。`K8sOrchestrationConfig.backendResolver`（`:107-129`）启动期遍历 `props.getK8sHosts()`，逐 host `K8sClientFactory.buildFromKubeconfig` 建独立 client + `ArthasProvisioner`，组成 `Map<hostName, ArthasProvisioner>`（不可变）。`@Lazy` + `ObjectProvider`（`:108`）打破启动期环。

### 3.3 portal 不碰 K8sHost

portal `/admin/backends`（part5 §37）只 CRUD `BackendConfig`，`BackendDto`（part5 §37.4）字段无 K8S/host 信息。K8sHost 配置只在 application.yml，portal 完全不感知。

### 3.4 热重载既有基建（复用）

`BackendConfigWatcher`（`backend/BackendConfigWatcher.java`）是 001 的热重载基建：`WatchService` 监听 `backends.yaml` → `watchLoop`（:129）→ `reloadOnce`（:163）解析 → `applyCompose`（:212）经 `RegistryComposer.compose` 合并静态∪动态 → `holder.getAndSet`（:221）→ `retireAll`（:229）回收旧 Entry。spec-006 的 K8sHost 热重载**仿此模式**做 `K8sHostsWatcher` + `K8sHostStore`。

### 3.5 注册表既有结构（复用）

`RegistryHolder.current()`（:34）/ `getAndSet`（:44）、`RegistryComposer.compose`（:47，合并静态∪动态）、`DynamicBackendStore.register/unregister/list/get`（:47/:77/:87/:92）。显示增强在此层投影 K8S 字段。

---

## 4. US1 · SSH 引导接入（波 1 核心）

### 4.1 K8sHost 配置扩展：新增 `ssh` 子段

`GatewayProperties.K8sHost`（`GatewayProperties.java:258-289`）新增 `ssh` 子结构，与现有 `kubeconfig`（本地文件路径）**互斥**：

```yaml
arthas-gateway:
  k8s-hosts:
    - name: corp-prod
      namespace: default
      ssh:                                    # 新增:SSH 引导(与 kubeconfig 互斥)
        host: 10.0.1.5                        # master/control-plane 节点 IP
        port: 22
        user: root
        password: ${K8S_SSH_PASSWORD}         # 环境变量注入,绝不裸写
        # privateKey: ${K8S_SSH_KEY}          # 可选:私钥(与 password 二选一)
        # passphrase: ${K8S_SSH_PASSPHRASE}   # 可选:私钥口令
        kubeconfig-remote-path: /etc/kubernetes/admin.conf    # 标准 K8S;k3s=/etc/rancher/k3s/k3s.yaml
        server-override: https://10.0.1.5:6443  # 可选:kubeconfig server 不可达时替换
        insecure-skip-tls-verify: false         # 可选:SAN 不匹配兜底(默认 false)
    - name: local-k3s                          # 既有模式 100% 保留
      kubeconfig: test-env/k8s/kubeconfig/k3s-admin.yaml
      namespace: default
```

**紧凑构造器校验**（仿 005 `url vs k8sHost` 互斥）：
- `kubeconfig` 与 `ssh` **互斥**——皆空 → 错误（"host 须声明 kubeconfig 或 ssh"）；皆有 → 错误（"kubeconfig 与 ssh 互斥"）。
- `ssh` 非空时 `host` + `user` + `kubeconfig-remote-path` 必填；`password` 与 `privateKey` 至少一项。

`GatewayProperties.K8sHost.Ssh` 内部类（host/port=22/user/password/privateKey/passphrase/kubeconfigRemotePath/serverOverride/insecureSkipTlsVerify）。`password/passphrase/privateKey` 走 Spring `${ENV}` 占位符注入。

### 4.2 SshKubeconfigFetcher（新类，orchestration 包）

单一职责：SSH 登入 + exec `cat <path>` 取 kubeconfig 文本。封装 sshj，便于真实 IT。

```java
// com.arthas.gateway.orchestration（零 gateway-core 依赖）
public final class SshKubeconfigFetcher {
    /** SSH 登入 master,读取远端 kubeconfig 文本。失败抛 SshBootstrapException。 */
    public String fetchKubeconfig(SshBootstrap ssh) { ... }
}
```

- sshj 用法：`new SSHClient().connect(host, port)` → `authPublickey/authPassword` → `startSession().exec("cat " + kubeconfigRemotePath)` → 收 stdout（kubeconfig 文本）。注意 `cat` 兜底（部分 hardened server 关 SFTP，exec cat 兼容性最好）。
- **超时**：连接 10s + exec 15s（避免悬挂 host 阻塞启动或热重载）。
- **异常分类** → `SshBootstrapException(reason)`：连不上 `ssh_unreachable`；认证失败 `ssh_auth_failed`；远端文件不存在/不可读（exitCode≠0 或 stderr 含 "No such file"）`kubeconfig_not_found`；空内容 `kubeconfig_invalid`。

### 4.3 K8sClientFactory 扩展：buildFromSsh

`K8sClientFactory`（`K8sClientFactory.java:53`）新增静态方法：

```java
/** SSH 引导取 kubeconfig → 构造 client(可选 serverOverride / 跳过 TLS 校验)。 */
public static KubernetesClient buildFromSsh(SshBootstrap ssh) {
    String content = new SshKubeconfigFetcher().fetchKubeconfig(ssh);  // SSH 取文本
    Config config = Config.fromKubeconfig(content);
    if (ssh.serverOverride() != null) {
        config.setMasterUrl(ssh.serverOverride());   // 替换不可达 server
    }
    if (ssh.insecureSkipTlsVerify()) {
        config.setTrustCerts(true);
        config.setDisableHostnameVerification(true); // SAN 不匹配兜底
    }
    return new KubernetesClientBuilder().withConfig(config).build();
}
```

- **首取后缓存**：`K8sHostStore`（波 2）持有 host→client，不每次路由都 SSH。SSH 仅在 host 首次建/重建时发生一次。
- 既有 `buildFromKubeconfig(path)`（:53）**完全不变**（向后兼容）。

### 4.4 K8sOrchestrationConfig 分支装配

`K8sOrchestrationConfig.backendResolver`（`:107-129`）循环内加分支：

```java
for (GatewayProperties.K8sHost h : props.getK8sHosts()) {
    KubernetesClient c = (h.getSsh() != null)
            ? K8sClientFactory.buildFromSsh(toSshBootstrap(h.getSsh()))
            : K8sClientFactory.buildFromKubeconfig(h.getKubeconfig());   // 既有路径
    // ...建 exposer + provisioner,放入 provisioners/hosts Map(现状不变)
}
```

> 波 1 此处仍是启动期遍历（重启生效）；波 2 改为 `K8sHostStore` 运行时管理。

### 4.5 TLS 兜底字段的语义（决策 #3）

fabric8 连 K8S 触发两件事：① 连 `server` 地址（可达性）；② 校验证书 SAN 含该地址（TLS 匹配）。两字段各兜底其一：

| 字段 | 兜底环节 | 触发场景 | 代价 |
|------|----------|----------|------|
| `server-override` | 可达性/DNS | kubeconfig server=127.0.0.1（k3s 默认）/ 内部 VIP / 不可解析域名 | 无（仅换地址）|
| `insecure-skip-tls-verify` | TLS 证书校验 | 连接地址不在 apiserver 证书 SAN（k3s 默认 SAN 不含外部 IP，part4 §33.1 需 `--tls-san`）| 不安全（中间人风险），内网可接受 |

**公司标准 K8S 场景**：master IP 内网可达 → `server-override` 多半不用；SAN 是否含该 IP 不确定 → `insecure` 留兜底。配得好的集群两者都不碰。

### 4.6 契约新增

- **INV-SSH-1**：K8sHost 配 `ssh` → 网关 SSH 登 master 取 kubeconfig 成功 → fabric8 client 可连 K8S API（list/exec）。
- **INV-SSH-2**：SSH 连不上 → `ssh_unreachable`；错密码/错 key → `ssh_auth_failed`；远端路径不存在 → `kubeconfig_not_found`（结构化错误，映射进 `ProvisionException`/ensure failed）。
- **INV-SSH-3**：`kubeconfig` 与 `ssh` 互斥校验（皆空/皆有 → 启动/加载失败，保留旧配置表，仿 INV-K8SHOST-1）。
- **INV-SSH-4**：`server-override` 替换 server 后 client 连 override 地址；`insecure-skip-tls-verify=true` 跳过 TLS 校验。

---

## 5. US2 · 配置全部热生效（波 2）

### 5.1 配置载体独立：`config/k8s-hosts.yaml`

K8sHost 列表从 application.yml 内联，改为独立文件 `config/k8s-hosts.yaml`（仿 `config/backends.yaml`）。application.yml 只留指针：

```yaml
arthas-gateway:
  k8s-hosts-file: config/k8s-hosts.yaml   # 仿 backends-file
```

`config/k8s-hosts.yaml` 含 `version` + `hosts: [...]`（每个 host 含 name/namespace/kubeconfig 或 ssh 子段）+ 顶层 `k8s-params:`（全局参数覆盖，见 5.4）。

**向后兼容**：`k8s-hosts-file` 未配或文件不存在 → 回退读 application.yml 内联 `arthas-gateway.k8s-hosts`（005 现状不破）。

### 5.2 K8sHostsWatcher（仿 BackendConfigWatcher）

新增 `K8sHostsWatcher`（orchestration 包）：`WatchService` 监听 `config/k8s-hosts.yaml` → `watchLoop` → `reloadOnce` 解析 → 调 `K8sHostStore.applyDiff(newHosts)` → 触发 host 增删改。复用 001 WatchService 的防抖/去抖逻辑（`BackendConfigWatcher` 已验证模式）。

### 5.3 K8sHostStore（仿 DynamicBackendStore）—— host 生命周期核心

```java
// com.arthas.gateway.orchestration
public final class K8sHostStore implements AutoCloseable {
    private final ConcurrentHashMap<String, HostEntry> byName;  // hostName → client/provisioner/exposer
    // applyDiff:对比新旧 host 列表,分别处理
    public synchronized void applyDiff(List<K8sHost> desired) {
        // 新增:host 在 desired 不在 current → SSH 建 client + provisioner → put
        // 删除:host 在 current 不在 desired → close client(释放连接池) + remove + 清 resolver 缓存
        // 修改:host 配置变了(ssh 密码/path/参数) → close 旧 client + 重建 + replace + 清缓存
    }
}
```

- `HostEntry` 持有 `KubernetesClient + NodePortExposer + ArthasProvisioner`（现状三件套，从 `backendResolver` bean 的局部变量提升为 store 管理的运行时实体）。
- **线程安全**：`applyDiff` 同步（重建期间串行）；`byName` 用 `ConcurrentHashMap`，路由读 `get` 无锁。
- **client 关闭**：删除/修改 host 时 `client.close()`（`AutoCloseable`，释放 fabric8 HTTP 连接池/WebSocket）。
- **`K8sBackendResolver`** 改为从 `K8sHostStore` 查 provisioner（而非启动期不可变 Map）。resolver 的 `cache`（logicalName→mcpUrl）在对应 host 重建时清除该 host 的缓存项。

### 5.4 k8s 全局参数热生效

`arthas-gateway.k8s.*`（kubeconfig/namespace/node-port-range/ensure-timeout/target-ip/arthas-boot-jar/mcp-port/arthas-version/arthas-password）当前启动期绑定。改为**运行时可读 fresh 值**：

- 这些参数影响 **ensure 行为**（arthas 版本/端口/密码/NodePort 范围/超时），不绑 client（client 只绑 kubeconfig）。
- `K8sHostStore` 持有一个可刷新的 `K8sParams` 快照；`ArthasProvisioner` 每次 ensure 读 store 当前快照（不缓存启动期值）。
- 配置变更（`k8s-hosts.yaml` 顶层 `k8s-params` 或 application.yml）→ 刷新快照 → **下次 ensure 用新值**（已 ensure 的 pod 不变，符合幂等语义）。
- `k8s.kubeconfig`（003 单集群默认 client）+ `k8s-hosts`（多 host）二者统一进 store：单集群场景 = 一个隐式 host。

### 5.5 三触发源统一

| 触发源 | 机制 |
|--------|------|
| 手改 `config/k8s-hosts.yaml` | `K8sHostsWatcher` WatchService 监听 → applyDiff |
| portal CRUD（波 3） | 写 `config/k8s-hosts.yaml` → 同一 WatchService 触发 → applyDiff |
| 启动加载 | 读文件（或回退 application.yml）→ 初始 applyDiff |

三源都落 `config/k8s-hosts.yaml`，都经 `K8sHostsWatcher → K8sHostStore.applyDiff`，**唯一热重载管道**。

### 5.6 契约新增

- **INV-HOT-1**：`config/k8s-hosts.yaml` 变更（手改/portal）→ `K8sHostsWatcher` 触发 → `K8sHostStore.applyDiff` → 新增 host 立即可路由；删除 host 立即不可路由（client 已关）；改 host 立即用新配置（缓存已清）。
- **INV-HOT-2**：热重载不破坏既有 host 的在途 ensure（重建串行 + 路由读无锁）。
- **INV-HOT-3**：k8s 全局参数变更 → 下次 ensure 用新值；已 ensure 的 pod 行为不变（幂等不破）。
- **INV-HOT-4**：`config/k8s-hosts.yaml` 不存在/解析失败 → 回退上次有效配置 + 结构化错误日志（不崩，仿 BackendConfigWatcher 容错）。

---

## 6. US3 · portal K8sHost CRUD（波 3）

### 6.1 后端：`/admin/k8s-hosts`（仿 `/admin/backends`）

新增 `admin/k8shost/` 子包：

| 端点 | 说明 |
|------|------|
| `GET /admin/k8s-hosts` | 列出所有 host（含连接状态：client 是否就绪/最近 SSH 错误）|
| `GET /admin/k8s-hosts/{name}` | 单个 host 详情 |
| `POST /admin/k8s-hosts` | 新增 host（含 ssh 子段）|
| `PUT /admin/k8s-hosts/{name}` | 改 host（改 ssh 密码/path 等）|
| `DELETE /admin/k8s-hosts/{name}` | 删 host |

`K8sHostAdminService` 写 `config/k8s-hosts.yaml`（version+1，仿 `BackendsYamlWriter`）→ `K8sHostsWatcher` 自动触发热重载（**portal 不直接建 client，只写文件**，单一管道）。`@ConditionalOnProperty(name="arthas-gateway.admin.k8s-hosts.enabled", havingValue="true", matchIfMissing=true)`（仿 INV-SWITCH-1）。

### 6.2 密码安全（决策 #10）

- **DTO 永不回显**：`K8sHostDto` 的 ssh.password/passphrase/privateKey 字段**不存在**或恒为 `***`（INV-SECRET-1 升级）。
- **写入加密**：portal 接收明文密码 → `K8sHostSecretCipher`（AES-GCM，密钥来自环境变量 `ARTHAS_GATEWAY_SECRET`）加密 → 写 `k8s-hosts.yaml` 加密值；`K8sHostStore` 加载时解密。
- **未设 `ARTHAS_GATEWAY_SECRET`** → portal 写密码端点返 400 `secret_key_not_configured`（强制运维配密钥，不裸明文）。
- **日志脱敏**：SSH 密码 / 取到的 kubeconfig 内容**永不入日志**（加日志过滤器或仅记 host 名）。
- P2：接 Vault / 外部 secret store。

### 6.3 前端：`/k8s-hosts` 视图（仿 `/backends`）

`web/src/views/K8sHostListView.vue` + `K8sHostForm.vue`：表格列 name/namespace/接入方式（kubeconfig/ssh）/host/连接状态/操作；表单含 ssh 子段（password 输入框 type=password，编辑时留空=不改）。`SpaConfig` 加 `/k8s-hosts` forward。

### 6.4 契约新增

- **INV-PORTAL-K8S-1**：portal CRUD 写 `config/k8s-hosts.yaml` → 经 `K8sHostsWatcher` 热生效（portal 不绕过热重载管道直建 client）。
- **INV-PORTAL-K8S-2**：`K8sHostDto` 永不回显 ssh 凭证（password/passphrase/privateKey）。
- **INV-PORTAL-K8S-3**：未配 `ARTHAS_GATEWAY_SECRET` → 写含凭证的 host 返 400（不裸明文落盘）。
- **INV-PORTAL-K8S-4**：portal `/admin/k8s-hosts` 与诊断面 `/mcp`、与 `/admin/backends` 隔离（INV-ISOL-1 延伸）。

---

## 7. US4 · 显示增强 + ensure 不显示 bug 修复（波 4）

### 7.1 BackendDto 加 K8S 字段

`BackendDto`（`admin/backend/dto/BackendDto.java`）增量可选字段：
- `k8sHost`（host 名，K8S 动态/懒 resolve 模式有值）
- `pod`、`namespace`
- `sourceDetail`（如 "ssh:corp-prod" / "kubeconfig:local-k3s" / "static:url"）
- `ensureStatus`（ready/reused/failed/ensuring，来自 `OrchestrationRecordStore`）

`BackendAdminService.list`（part5 §37.2）投影时从 `OrchestrationRecordStore` + host 配置补全这些字段。前端 `BackendListView.vue` 表头加「K8S 来源」「Pod」列。

### 7.2 ensure 不显示 bug —— 排查结论（已回填）

bug 排查 agent（追 ensure→DynamicBackendStore→RegistryComposer→RegistryHolder→portal list）结论：**代码层不过滤动态 backend**——`BackendAdminServiceTest.java:97-100` 有断言证明 STATIC+DYNAMIC 都列出，前端 `BackendListView.vue:72-83` 也无过滤。

"跑过没看到"的真因（按概率）：

1. **list 名字 = `{server}-{pod}`**（`ArthasProvisioner.java:152-154`），不是 `pod` 或 `server` 单独 → 易找错名字（**认知错位，非 bug**，靠 §7.1 sourceDetail 缓解）。
2. **portal 前端不自动刷新**（`BackendListView.vue:17-26` 仅 `onMounted` 拉一次，无轮询/SSE）→ ensure 成功后须手动刷新页面才看到新行（**体验 bug，波 4 修**）。
3. **动态注册后 compose 重算异常被吞**（`BackendConfigWatcher.java:190-196`：`catch RuntimeException` 只 `log.error` 不重抛）→ cfg 已进 `DynamicBackendStore.byName`，但 `holder.current()` 没 swap → portal list 读旧 registry 看不到（**真实鲁棒性 bug，波 4 修**）。
4. **场景 B（backends.yaml 配 K8S 种子）**：种子 `source=STATIC`（YAML 名），真正的 DYNAMIC 行（`{server}-{pod}`）要**首次 tools/call 路由后**才生成（`K8sBackendResolver.java:56-74`）→ 用户期待 DYNAMIC 行但看到 STATIC，或没路由过就没 DYNAMIC 行。

**修复（波 4）**：
- 前端加自动刷新（轮询 / ensure 工具返回后提示刷新）—— 修因 2；
- `BackendConfigWatcher:190-196` compose 异常处理：异常可见（WARN + 指标）+ 确保 holder 即使 compose 失败也含新注册项（或 list 兜底读 `DynamicBackendStore.byName`）—— 修因 3；
- §7.1 显示增强用 sourceDetail 区分 STATIC 种子 / DYNAMIC 纳管 / ssh 来源 —— 缓解因 1/4；
- IT 守护「ensure 成功 → `holder.current()` 含该 target（不受 compose 异常吞咽影响）+ 前端刷新后可见」。

### 7.3 契约新增

- **INV-DISP-1**：ensure 成功纳管 → `RegistryHolder.current()` 必含该 target（不受 `BackendConfigWatcher:190-196` compose 异常吞咽影响）+ portal 前端刷新后可见（前端自动刷新）。
- **INV-DISP-2**：K8S 来源 backend 在 list 带 k8sHost/pod/namespace/sourceDetail 字段。
- **INV-DISP-3**：`BackendDto` 仍不含 token/username/password（INV-SECRET-1 不破）。

---

## 8. SSH 库选型（决策 #4）

| 库 | 维护 | 现代算法(Ed25519) | 体积 | API | 结论 |
|---|---|---|---|---|---|
| **sshj** | 活跃 | ✓ | 中(bouncycastle bcprov) | 简洁现代(`new SSHClient().connect().authPassword().startSession().exec()`) | ✅ **采用** |
| mwiede/jsch | 活跃 fork | ✓ | 小 | JSch 经典 | 备选 |
| Apache MINA SSHD | 活跃 | ✓ | 大 | 全功能(含 SFTP/agent) | 过重(需求仅 exec cat) |

**采用 sshj**：需求仅"password/key 认证 + exec cat 读文件"，sshj API 最简洁、活跃维护、支持现代算法（公司服务器可能 Ed25519）。pom 声明 `com.hierynomus:sshj:0.38.x`（+ bouncycastle 传递依赖）。测试用 Apache MINA SSHD 作 embedded SSH server（真实协议，非桩）。

---

## 9. 错误处理

### 9.1 SSH 引导错误（SshBootstrapException → reason）

| reason | 触发 | 映射 |
|--------|------|------|
| `ssh_unreachable` | 连不上 host:port（超时/拒绝）| ensure failed / host 装配失败 |
| `ssh_auth_failed` | 错密码 / 错 key / key 口令错 | 同上 |
| `kubeconfig_not_found` | 远端路径不存在/不可读（cat exitCode≠0）| 同上 |
| `kubeconfig_invalid` | 内容空 / 解析失败 | 同上 |
| `tls_handshake_failed` | SAN 不匹配且未开 insecure | 同上 |

映射进 `ProvisionException`/`OrchestrationRecord.Error`（沿用 005 结构化形态），ensure failed reason 枚举扩展。

### 9.2 热重载错误

- `config/k8s-hosts.yaml` 解析失败 → 回退上次有效配置 + WARN 日志（不崩，INV-HOT-4）。
- 单个 host SSH 失败 → 该 host 标 failed（client 缺），其余 host 不受影响（隔离，仿 K-COEXIST-2）；路由到该 host → `ssh_unreachable`。
- 加密密码解密失败（密钥不符）→ 该 host 装配失败 + ERROR 日志。

---

## 10. 安全（重点）

- **root 密码极敏感**（决策 #10）：环境变量注入 / portal 加密写文件 / DTO 永不回显 / 日志脱敏。
- **`ARTHAS_GATEWAY_SECRET`**：AES-GCM 主密钥，运维必配；未配则禁用 portal 写凭证端点（INV-PORTAL-K8S-3）。
- **root + admin.conf = cluster-admin**：合规风险显著，文档显著声明；网关持有 cluster-admin 权限，公司合规由用户负责。
- **`insecure-skip-tls-verify`**：默认 false；开启时文档警告中间人风险，仅内网可信场景。

---

## 11. 不变量守护

- **零 gateway-core 的 K8S/SSH 依赖**：`SshKubeconfigFetcher`/`K8sHostStore`/`K8sHostsWatcher` 全部在 orchestration 包；`BackendResolver` 接口仍在 gateway-core 无 fabric8/sshj import。`PackageBoundaryTest`（005 T027）加规则守护 sshj 不进 gateway-core。
- **向后兼容**：`kubeconfig` 本地文件模式不变（003/005 不破）；`config/k8s-hosts.yaml` 不存在回退 application.yml；003 契约 K-ENS-*/K-ATOMIC-1 全绿（回归门禁）。
- **回归**：005 INV-K8SHOST-*/INV-LAUNCHER-*、003 K-ENS-1~12/K-ATOMIC-1、001/002/004 既有全绿（FR-013 回归门禁）。

---

## 12. TDD 测试计划（真实环境、零桩）

> CLAUDE.md 硬约束：真实环境、零桩；故障用真实故障条件。

### 12.1 波 1（SSH 引导）

- **单测 `SshKubeconfigFetcherTest`**：用 **Apache MINA SSHD embedded server**（真实 SSH 协议握手，非桩）—— ① password auth + 文件存在 → 返 kubeconfig 文本；② 错密码 → `ssh_auth_failed`；③ 停 server → `ssh_unreachable`；④ 路径不存在 → `kubeconfig_not_found`。
- **单测 `K8sClientFactoryBuildFromSshTest`**：fetcher 返固定 kubeconfig 文本 → 构造 client（含 serverOverride 替换 / insecure 跳过校验）。
- **契约 IT `SshBootstrapContractIT`**（failsafe *IT，真实测试床）：`config/k8s-hosts.yaml` 配 ssh（host=192.168.31.92, root, key, path=/etc/rancher/k3s/k3s.yaml）→ 网关 SSH 取 kubeconfig → 连 K8S API → list-pods 成功（验证核心链路端到端）。标准 K8S 路径（admin.conf）单测覆盖（路径可配）。

### 12.2 波 2（热生效）

- **单测 `K8sHostStoreTest`**：applyDiff 增/删/改 host → client 建/关/重建（mock SSH fetcher 返 kubeconfig 文本，验证生命周期 diff 逻辑，非验证 SSH 本身——SSH 在波1 已验）。
- **契约 IT `K8sHostHotReloadIT`**（真实测试床）：运行时改 `config/k8s-hosts.yaml`（加/删 host）→ WatchService 触发 → 新 host 可路由 / 删除 host 不可路由（client 已关）。
- **契约 IT `K8sGlobalParamsHotReloadIT`**：改 arthas-password → 下次 ensure 用新密码（真实 k3s + pod）。

### 12.3 波 3（portal）

- **单测 `K8sHostAdminControllerTest`**：CRUD 写 `k8s-hosts.yaml`；DTO 不回显凭证（INV-PORTAL-K8S-2）；未配 SECRET → 400（INV-PORTAL-K8S-3）。
- **契约 IT `K8sHostPortalCrudIT`**（真实测试床）：portal POST host（ssh）→ `k8s-hosts.yaml` 更新 → 热重载 → host 可路由（端到端）。
- **前端 vitest**：K8sHostForm password type=password + 编辑留空不改。

### 12.4 波 4（显示 + bug）

- **契约 IT `EnsureVisibleInPortalIT`**（真实测试床）：ensure 成功 → `RegistryHolder.current()` 含该 target（断言不受 compose 异常吞咽影响）+ `GET /admin/backends` 含该 target（INV-DISP-1，回归守护 bug 修复）+ 前端刷新后可见。
- **单测 `BackendDtoK8sFieldsTest`**：K8S 来源 backend 投影带 k8sHost/pod/sourceDetail（INV-DISP-2），仍无 token（INV-DISP-3）。

### 12.5 回归（FR-013）

`./mvnw verify -DskipFrontend=true` 全绿：003 K-ENS-*/K-ATOMIC-1 + 005 INV-K8SHOST-*/INV-LAUNCHER-* + ArchUnit 包边界（含新增 sshj 守护）+ 001/002/004 既有。

---

## 13. 波次拆分与依赖

| 波次 | 内容 | 依赖 | 产出（独立可用） |
|------|------|------|------------------|
| **波 1·SSH 引导核心** | K8sHost.ssh 子段 + 两个 TLS 兜底字段 + `SshKubeconfigFetcher` + `buildFromSsh` + 装配分支（application.yml 配置，重启生效）| 无 | 「配 master IP+root+密码就连」核心能力可用 |
| **波 2·配置热生效** | `config/k8s-hosts.yaml` + `K8sHostsWatcher` + `K8sHostStore`（host 生命周期 diff）+ k8s 全局参数热生效 | 波 1 | 所有 K8S 配置变更热生效 |
| **波 3·portal 管理** | `/admin/k8s-hosts` CRUD + 密码加密 + 前端 `/k8s-hosts` 视图 | 波 2 | portal 网页管理 host，改完立即生效 |
| **波 4·显示 + bug** | BackendDto 加 K8S 字段 + 前端展示 + 修 ensure 不显示 bug | 波 1（bug 部分可独立）| portal 可见连上的 pod/arthas 及来源 |

每波独立可用、回归不破、可单独验证（每波 ends with `./mvnw verify` 全绿）。bug 修复（波 4）可与波 1 并行启动（独立链路）。

---

## 14. 风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| **公司内网够不着**（开发期无法实测标准 K8S）| 标准 K8S（admin.conf）真实链路无法开发期验证 | 测试床 k3s 验证核心链路（SSH→kubeconfig→连，路径可配兼容 admin.conf）；标准 K8S 真实验证延后到公司落地（文档显著声明） |
| **tls-san 未配** | 网关从外部连 6443 TLS 失败 | `server-override` + `insecure-skip-tls-verify` 兜底；文档指导公司集群配 `--apiserver-cert-extra-sans` |
| **root + admin.conf = cluster-admin 合规** | 公司可能不允许 | 文档显著声明；P2 接 Vault / 改用受限 ServiceAccount（需用户配合建 RBAC） |
| **热重载并发** | 重建 host 时在途 ensure | applyDiff 串行 + 路由读无锁（INV-HOT-2） |
| **SSH 密码泄露** | root 权限泄露 | 加密落盘 + DTO/日志脱敏 + 强制 SECRET 环境变量 |
| **范围大** | 单 spec 周期长 | 4 波增量交付，每波可用 |

---

## 15. 非目标 / 后置（P2+）

- **Vault / 外部 secret store** 集成（MVP 用 AES-GCM + 环境变量）。
- **ServiceAccount Token 模式**（用户建 RBAC，kubeconfig 用 token）——MVP 用 root SSH 取 admin.conf，SA Token 是未来更合规的路径。
- **多 master HA**（control-plane-endpoint 负载均衡）——MVP 单 master IP，HA 后置。
- **portal 独立「K8S 连接」总览视图**（决策 #11 备选，MVP 在 backend list 加列）。
- **物理 Maven 多模块拆分**（gateway-core vs orchestration）——P3，MVP 用包级边界。

---

## 16. 后续（brainstorming 终点）

本设计文档（spec）经用户审阅通过后，转入 **spec-kit SDD** 流程：

1. `/speckit-specify`：基于本设计生成 `specs/006-k8s-host-remote-access/spec.md`（规格，FR-* + 非功能）。
2. `/speckit-plan` + `/speckit-tasks`：生成 `plan.md` / `research.md` / `data-model.md` / `contracts/` / `tasks.md`（按 4 波次拆任务，测试先于实现）。
3. `/speckit-implement`：TDD 红-绿-重构，逐波推进，每波 `./mvnw verify` 全绿。

> 宪法原则七（TDD）由 spec-kit SDD「测试先于实现」落地；本设计的契约（INV-SSH-*/INV-HOT-*/INV-PORTAL-K8S-*/INV-DISP-*）转为 `contracts/` 可测断言。
