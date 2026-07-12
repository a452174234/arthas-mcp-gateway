# Research: K8S Host 远程接入与配置热生效（006）

> Phase 0 先决研究（宪法原则八）。003/005 R1-R9 已研 fabric8/kubeconfig/ensure；本文聚焦 006 四波次的新增决策（SSH 引导 / 热生效 / portal / 显示 bug）。每条含**决策 / 理由 / 备选 / 证据**。

---

## R1. SSH 库选型 —— sshj

**决策**：采用 **`com.hierynomus:sshj:0.38.0`**（波1）。

**用法**（取 kubeconfig 文本）：
```java
SSHClient client = new SSHClient();
client.setConnectTimeout(10_000); client.setTimeout(15_000);
client.addHostKeyVerifier(new PromiscuousStrategy());  // 内网信任（或配 known_hosts）
client.connect(host, port);
client.authPassword(user, password);   // 或 client.authPublickey(user, privateKeyPath)
try (Session s = client.startSession()) {
    Command cmd = s.exec("cat " + kubeconfigRemotePath);
    String content = IOUtils.readFully(cmd.getInputStream()).toString();
    cmd.close();
    if (cmd.getExitStatus() != 0) throw ... kubeconfig_not_found;
    return content;
} finally { client.disconnect(); }
```

**理由**：需求仅「password/key 认证 + exec cat 读一个文件」；sshj API 最简洁现代；活跃维护（hierynomus/sshj）；支持 Ed25519/现代密钥交换（依赖 BouncyCastle）；纯 Java（宪法原则六）。

**备选（已否）**：
- **JSch（jcraft 原版）**：2018 后停更，不支持 Ed25519/现代算法 → 否。
- **mwiede/jsch fork**：活跃、API 经典、支持现代算法 → 备选（若 sshj 遇阻可切换，API 风格不同需改 Fetcher）。
- **Apache MINA SSHD**：全功能但体积大（带 BouncyCastle 全家桶）→ 生产过重；**但测试用它做 embedded SSH server**（真实 SSH 协议，非桩），所以 MINA SSHD 进 test scope。

**证据**：sshj GitHub hierynomus/sshj（活跃）；fabric8 已传递 BouncyCastle（复用）。

---

## R2. kubeconfig 远端路径 —— 标准 K8S vs k3s

**决策**：`kubeconfig-remote-path` **可配**；默认 `/etc/kubernetes/admin.conf`（标准 K8S/kubeadm），k3s 用 `/etc/rancher/k3s/k3s.yaml`。

**理由**：用户登的是 master、`kubectl` 能跑 = master 上有现成 admin kubeconfig：
- **kubeadm**：`kubeadm init` 生成 `/etc/kubernetes/admin.conf`（cluster-admin 客户端证书 + key + CA，root 可读）。
- **k3s**：安装生成 `/etc/rancher/k3s/k3s.yaml`（同构 admin 凭证）。
两文件默认 `600 root:root` → 用户给 root SSH 即可读。

**证据**：
- part4 §33.1 setup.sh 第 8 步「导出 root-on-node 派生 admin kubeconfig」（k3s.yaml）。
- kubeadm 官方文档：`/etc/kubernetes/admin.conf` 为 cluster-admin 凭证。
- 用户确认「登的是 master、kubectl 能跑」。

---

## R3. fabric8 buildFromSsh —— 复用 Config.fromKubeconfig

**决策**：`K8sClientFactory.buildFromSsh(SshBootstrap)` = SSH 取 kubeconfig 文本 → `Config.fromKubeconfig(content)` → 可选 override/跳过校验 → `KubernetesClientBuilder().withConfig(config).build()`。

```java
public static KubernetesClient buildFromSsh(SshBootstrap ssh) {
    String content = new SshKubeconfigFetcher().fetchKubeconfig(ssh);  // R1
    Config config = Config.fromKubeconfig(content);
    if (ssh.serverOverride() != null) config.setMasterUrl(ssh.serverOverride());      // R9
    if (ssh.insecureSkipTlsVerify()) {
        config.setTrustCerts(true);
        config.setDisableHostnameVerification(true);
    }
    return new KubernetesClientBuilder().withConfig(config).build();
}
```

**理由**：仅把 005 `buildFromKubeconfig`（`K8sClientFactory.java:53-66`）的「文件读取」换成「SSH 取文本」，`Config.fromKubeconfig` 解析路径完全复用 → 最小侵入、零新鉴权逻辑。

**首取缓存**：`K8sHostStore` 持有 host→client（R4），不每次路由都 SSH；SSH 仅在 host 首建/重建时一次。

**证据**：`K8sClientFactory.java:53-66`（005 现状）；fabric8 `Config.fromKubeconfig` / `setMasterUrl` / `setTrustCerts` / `setDisableHostnameVerification`。

---

## R4. 热重载管道 —— K8sHostsWatcher + K8sHostStore（仿 001）

**决策**：复用 001 已验证的热重载模式：
- **`K8sHostsWatcher`**（orchestration）仿 `BackendConfigWatcher`：`WatchService` 监听 `config/k8s-hosts.yaml` → `watchLoop` → `reloadOnce`（解析 + 防抖去抖）→ 调 `K8sHostStore.applyDiff(newHosts)`。
- **`K8sHostStore`**（orchestration）仿 `DynamicBackendStore`：`ConcurrentHashMap<String, HostEntry>`（hostName → client/exposer/provisioner）+ `synchronized applyDiff(List<K8sHost> desired)`。

**applyDiff 逻辑**（diff by name）：
```
for desired host:
  - current 无 → 新增：buildFromSsh/buildFromKubeconfig 建客户端 → put HostEntry
  - current 有 且 配置变了 → 修改：close 旧客户端 + 重建 + replace + 清 resolver 缓存（该 host 项）
for current host not in desired:
  - 删除：close 客户端（释放连接池）+ remove + 清缓存
```

**线程安全**：`applyDiff` 同步（重建串行）；路由经 `K8sBackendResolver` 读 `store.get(host)` 无锁（`ConcurrentHashMap`）。

**理由**：001 BackendConfigWatcher + DynamicBackendStore 是项目已验证的热重载基建（SC-002），复用而非新发明（YAGNI）。

**证据**：`BackendConfigWatcher.java`（`watchLoop:129` / `reloadOnce:163` / `applyCompose:212`）；`DynamicBackendStore.java`（`register:47` / `unregister:77`）；part2 §11.8-11.9。

---

## R5. K8sHost 配置独立文件 + 回退兼容

**决策**：K8sHost 列表独立到 `config/k8s-hosts.yaml`（`version` + `hosts:[]` + `k8s-params:`）；`application.yml` 只留指针 `arthas-gateway.k8s-hosts-file`（仿 `backends-file`）；**文件不存在 → 回退读 `application.yml` 内联 `arthas-gateway.k8s-hosts`**（005 兼容）。

**理由**：热重载前提是独立可监听文件（`application.yml` 是 Spring `@ConfigurationProperties` 启动绑定，运行时改不重读）。回退保证 005 现状不破（`config/k8s-hosts.yaml` 未创建时仍用 application.yml）。

**证据**：`GatewayProperties.backendsFile`（001 模式）；`config/backends.yaml`；005 `application.yml` 内联 `k8s-hosts: []`（现状）。

---

## R6. k8s 全局参数热生效 —— 不重建 client，下次 ensure 读新值

**决策**：`arthas-password/version/ensure-timeout/node-port-range/target-ip/mcp-port` 做成 `K8sHostStore` 持有的**可刷新 `K8sParams` 快照**；`ArthasProvisioner` 每次 ensure 读 store 当前快照（不缓存启动期值）。参数变更 → 刷新快照 → **下次 ensure 用新值**（已 ensure 的 pod 不变，幂等不破）。

**理由**：这些参数影响 ensure 行为，**不绑 client**（client 只绑 kubeconfig），无需重建——故比 host 级变更轻。

**证据**：`K8sOrchestrationConfig.java:93-96`（ArthasProvisioner 注入 targetIp/arthasBootJar/mcpPort/arthasVersion/arthasPassword）；改这些只影响下次 ensure。

**注意**：`k8s.kubeconfig`（003 单集群默认 client）+ `k8s-hosts`（多 host）二者统一进 `K8sHostStore`（单集群场景 = 一个隐式 host），避免两套 client 管理路径。

---

## R7. root 密码加密 —— AES-GCM + 环境变量密钥

**决策**：`K8sHostSecretCipher`（admin/k8shost）用 **AES-GCM**（`javax.crypto.Cipher`，提供机密性 + 完整性）；密钥来自环境变量 `ARTHAS_GATEWAY_SECRET`（256-bit，base64）。portal 接收明文 → 加密写 `config/k8s-hosts.yaml`；`K8sHostStore` 加载解密。**未配 SECRET → portal 写凭证端点返 400 `secret_key_not_configured`**（强制运维配密钥，不裸明文）。

**理由**：root 权限远比 kubeconfig 敏感；AES-GCM 是 Java 标准认证加密；环境变量密钥避免密钥落盘。

**备选（后置 P2）**：HashiCorp Vault / Kubernetes Secret 外部源；MVP 用环境变量密钥够用（内网）。

**安全补强**：
- `K8sHostDto` **无** password/passphrase/privateKey 字段（INV-PORTAL-K8S-2，永不回显）。
- 日志脱敏：SSH 密码 / 取到的 kubeconfig 内容**永不入日志**（仅记 host 名 + 成功/失败 reason）。
- `config/k8s-hosts.yaml` 建议 `.gitignore`（含加密凭证，不入库）。

**证据**：004 `INV-SECRET-1`（token 脱敏惯例）升级到 SSH 凭证；Java AES-GCM 标准 API。

---

## R8. ensure 不显示 bug 根因 —— bug agent 结论

**决策**：修两点真因（bug agent 已定位，代码层**不过滤** DYNAMIC，有测试断言 `BackendAdminServiceTest.java:97-100`）：

1. **`BackendConfigWatcher:190-196` 动态 compose 异常吞咽**（`catch RuntimeException` 只 `log.error` 不重抛）→ cfg 已进 `DynamicBackendStore.byName`，但 `holder.current()` 没 swap → portal list 读旧 registry 看不到。
   - **修复**：异常可见（WARN + 指标）+ **holder 兜底**（即使 compose 失败，新注册项仍进 holder；或 `BackendAdminService.list` 兜底合并 `DynamicBackendStore.byName`）。
2. **前端 `BackendListView.vue:17-26` 无自动刷新**（仅 `onMounted` 拉一次，无轮询/SSE）→ ensure 成功后须手动刷新。
   - **修复**：加自动刷新（轮询 / ensure 工具返回后提示刷新）。
3. **认知错位**（非 bug）：list 名字 = `{server}-{pod}`（`ArthasProvisioner.java:152-154`），不是 pod/server 单独 → 靠 `BackendDto.sourceDetail`（R 显示增强）缓解。
4. **场景 B**（K8S 种子）：`backends.yaml` 配的 K8S 种子是 `source=STATIC`，真正的 DYNAMIC 行（`{server}-{pod}`）要首次 tools/call 路由后才生成 → sourceDetail 区分。

**证据**：bug agent 报告（`BackendConfigWatcher.java:190-196` / `BackendListView.vue:17-26` / `ArthasProvisioner.java:152-154` / `K8sBackendResolver.java:56-74` / `BackendAdminServiceTest.java:97-100`）。

---

## R9. TLS 兜底字段 —— server-override + insecure-skip-tls-verify

**决策**：两字段各兜底 fabric8 连 K8S 的一个 TLS 环节：

| 字段 | 兜底环节 | 触发 | fabric8 API |
|------|----------|------|-------------|
| `server-override` | 可达性/DNS | kubeconfig server=`127.0.0.1`（k3s 默认）/ VIP / 不可解析域名 | `config.setMasterUrl(override)` |
| `insecure-skip-tls-verify` | TLS 证书 SAN 校验 | 连接地址不在 apiserver 证书 SAN（k3s 默认 SAN 不含外部 IP） | `config.setTrustCerts(true)` + `setDisableHostnameVerification(true)` |

**默认**：`insecure-skip-tls-verify=false`（开启需知中间人风险，仅内网可信场景）。

**公司标准 K8S 场景**：master IP 内网可达 → `server-override` 多半不用；SAN 是否含该 IP 不确定 → `insecure` 留兜底。配得好的集群两者都不碰。

**证据**：part4 §22.1（setup.sh 改 server `127.0.0.1`→`192.168.31.92`）+ §33.1（`--tls-san 192.168.31.92` 才让 LAN 可达）；fabric8 `Config` API。

---

## R10. 测试策略 —— 公司 K8S 够不着，测试床 k3s 验证核心链路

**决策**：
- **SSH 单测**（`SshKubeconfigFetcherTest`）：**Apache MINA SSHD embedded server**（真实 SSH 协议握手，非桩）—— password/key auth + 读文件 + 故障矩阵（错密码 `ssh_auth_failed` / 停 server `ssh_unreachable` / 错路径 `kubeconfig_not_found`）。**不依赖测试床**。
- **契约 IT**（波1/2/3/4 `*IT`，failsafe）：跑**真实测试床 k3s（debian 192.168.31.92）+ 真实 SSH + 真实 pod** —— 验证 SSH→取 k3s.yaml→连 K8S→ensure→诊断 全链路。`kubeconfig-remote-path` 配 `/etc/rancher/k3s/k3s.yaml`；标准 K8S（admin.conf）路径逻辑同，单测覆盖。
- **标准 K8S 真实端到端**：公司内网够不着，延后到公司落地（文档显著声明，非桩覆盖受限）。

**理由**：TDD 真实环境硬约束（宪法原则七 + CLAUDE.md）；测试床是当前唯一可达的真实 K8S（memory `debian-docker-ssh-access`：SSH key 免密 192.168.31.92）；核心链路（SSH→kubeconfig→连）发行版无关，仅路径不同。

**风险**：测试床当前是否在跑（k3s 启动 + demo pod Running）—— 实施波1 IT 时验证；若不可达暂停告知用户（不拿桩冒充）。

**证据**：part4 §33 测试床；005 契约 IT 已用测试床（全绿）；memory `debian-docker-ssh-access` / `fabric8-mock-server`（mock server 仅用于 K8sClient fluent 链单测，非 SSH）。

---

## 决策总览（与 design.md §2 一致）

| R | 决策 | 波次 |
|---|------|------|
| R1 | SSH 库 = sshj | 波1 |
| R2 | kubeconfig 路径可配（admin.conf / k3s.yaml） | 波1 |
| R3 | buildFromSsh 复用 Config.fromKubeconfig + 首取缓存 | 波1 |
| R4 | 热重载仿 BackendConfigWatcher + DynamicBackendStore | 波2 |
| R5 | config/k8s-hosts.yaml 独立 + 回退 application.yml | 波2 |
| R6 | 全局参数热生效（不重建 client，下次 ensure 读新值） | 波2 |
| R7 | root 密码 AES-GCM + 环境变量密钥 | 波3 |
| R8 | ensure 不显示 = compose 异常吞咽 + 前端无刷新（非过滤） | 波4 |
| R9 | TLS 兜底 server-override + insecure-skip-tls-verify | 波1 |
| R10 | 测试床 k3s 验证核心链路 + MINA SSHD embedded 单测 | 全波 |
