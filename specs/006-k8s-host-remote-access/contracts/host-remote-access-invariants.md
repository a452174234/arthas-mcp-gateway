# Contracts: K8S Host 远程接入与配置热生效（006 不变量）

> 006 可测不变量（INV-*），源自 [design.md §4-7](../../docs/superpowers/specs/2026-07-13-k8s-host-remote-access-design.md) + [spec.md FR](../spec.md)。每条转 `tasks.md` 的 TDD 断言（测试先于实现，宪法原则七）。003/005 既有契约（K-ATOMIC-1/K-ENS-*/INV-K8SHOST-*/INV-LAUNCHER-*）不破（FR-016 回归门禁）。

---

## INV-SSH-*（波1：SSH 引导接入）

### INV-SSH-1（SSH 引导连通）
**Given** K8sHost 配 `ssh`（master IP + root + 密码 + `kubeconfig-remote-path`），**When** 触发 ensure/list-pods，**Then** 网关 SSH 登 master 取 kubeconfig → 构造 fabric8 client → 连 K8S API 成功（list/exec 可用）；用户全程不接触 kubeconfig/证书/token。

### INV-SSH-2（SSH 故障结构化错误）
**Given** SSH 连不上 / 认证失败 / 远端路径不存在 / 内容损坏，**When** 触发，**Then** 分别返 `ssh_unreachable` / `ssh_auth_failed` / `kubeconfig_not_found` / `kubeconfig_invalid`（结构化错误，映射进 `ProvisionException`/ensure failed）。

### INV-SSH-3（kubeconfig 与 ssh 互斥）
**Given** K8sHost 同时配 `kubeconfig` 与 `ssh`（或皆空），**When** 加载，**Then** 校验失败、保留上次有效配置（不半替换，仿 INV-K8SHOST-1）。

### INV-SSH-4（TLS 兜底字段）
**Given** `server-override` 非空，**When** 建 client，**Then** 连 override 地址（替换 kubeconfig 原 server）；**Given** `insecure-skip-tls-verify=true`，**When** 建 client，**Then** 跳过 TLS 证书校验（默认 false 时 SAN 不匹配 → TLS 失败）。

### INV-SSH-5（kubeconfig 模式向后兼容）
**Given** K8sHost 配 `kubeconfig`（本地文件，无 ssh），**When** 加载/ensure，**Then** 行为与 005 完全一致（`buildFromKubeconfig` 不变，003/005 不破）。

---

## INV-HOT-*（波2：全配置热生效）

### INV-HOT-1（热重载增删改立即生效）
**Given** 网关运行中，**When** `config/k8s-hosts.yaml` 变更（手改/portal），**Then** `K8sHostsWatcher` 触发 `K8sHostStore.applyDiff` → 新增 host 立即可路由 / 删除 host 立即不可路由（client 已关）/ 改 host 立即用新配置（缓存已清），全程不重启。

### INV-HOT-2（热重载不破坏在途 ensure）
**Given** host 重建期间有在途 ensure，**When** applyDiff，**Then** 串行同步（不并发重建）+ 路由读无锁，在途调用不被破坏。

### INV-HOT-3（全局参数下次 ensure 读新值）
**Given** k8s 全局参数（arthas-password/version/ensure-timeout/node-port-range/target-ip/mcp-port）变更，**When** 下次 ensure，**Then** 用新值；已 ensure 的 pod 行为不变（幂等不破）。

### INV-HOT-4（配置解析失败回退）
**Given** `config/k8s-hosts.yaml` 解析失败 / 读到半截，**When** reloadOnce，**Then** 回退上次有效配置 + WARN 日志（不崩、不半替换）。

### INV-HOT-5（独立文件 + application.yml 回退）
**Given** `config/k8s-hosts.yaml` 不存在，**When** 启动，**Then** 回退读 `application.yml` 内联 `arthas-gateway.k8s-hosts`（005 兼容，零迁移）。

### INV-HOT-6（三触发源统一管道）
**Given** 配置变更来自手改文件 / portal CRUD / 启动，**When** 触发，**Then** 都经 `K8sHostsWatcher → K8sHostStore.applyDiff` 唯一管道（portal 不绕过直建 client）。

---

## INV-PORTAL-K8S-*（波3：portal 管理）

### INV-PORTAL-K8S-1（portal CRUD 经热重载管道）
**Given** portal 可访问，**When** POST/PUT/DELETE `/admin/k8s-hosts`，**Then** 写 `config/k8s-hosts.yaml` → 经 `K8sHostsWatcher` 热生效（portal 不直接建 client）。

### INV-PORTAL-K8S-2（凭证永不回显）
**Given** GET `/admin/k8s-hosts` 或 `/admin/k8s-hosts/{name}`，**Then** `K8sHostDto` 永不含 password/privateKey/passphrase（脱敏，INV-SECRET-1 升级）。

### INV-PORTAL-K8S-3（未配 SECRET 拒绝明文落盘）
**Given** 未配 `ARTHAS_GATEWAY_SECRET` 环境变量，**When** POST 含 ssh 凭证的 host，**Then** 返 400 `secret_key_not_configured`（不裸明文落盘）。

### INV-PORTAL-K8S-4（凭证加密落盘）
**Given** 配了 SECRET，**When** POST 含 ssh 密码 host，**Then** 密码 AES-GCM 加密落盘 `config/k8s-hosts.yaml`；`K8sHostStore` 加载解密；日志不含明文密码/kubeconfig 内容。

### INV-PORTAL-K8S-5（portal 与诊断面隔离）
**Then** `/admin/k8s-hosts` 与 `/mcp`（38 工具）、`/admin/backends` 隔离（INV-ISOL-1 延伸，能力开关 `arthas-gateway.admin.k8s-hosts.enabled` 默认开）。

---

## INV-DISP-*（波4：显示增强 + bug 修复）

### INV-DISP-1（ensure 后 holder 必含 target）
**Given** ensure 成功纳管，**Then** `RegistryHolder.current()` 必含该 target（名字 `{server}-{pod}`），**不受** `BackendConfigWatcher:190-196` compose 异常吞咽影响（修 bug：异常可见 + holder 兜底）；portal list 经刷新可见。

### INV-DISP-2（前端自动刷新）
**Given** ensure 成功，**When** 前端打开/刷新 backend list，**Then** 看到新行（自动刷新，不强制手动刷新；修 `BackendListView.vue:17-26` 体验 bug）。

### INV-DISP-3（K8S 来源字段）
**Given** K8S 来源 backend（动态纳管 / K8S 种子），**When** GET `/admin/backends`，**Then** `BackendDto` 带 `k8sHost`/`pod`/`namespace`/`sourceDetail`/`ensureStatus` 字段（区分 ssh/kubeconfig/static 来源，消除名字认知错位）。

### INV-DISP-4（凭证脱敏不破）
**Then** `BackendDto` 仍不含 token/username/password（INV-SECRET-1 不破）。

---

## INV-BOUNDARY-*（横切：包边界）

### INV-BOUNDARY-3（零 gateway-core SSH 依赖）
**Then** `SshKubeconfigFetcher`/`K8sHostStore`/`K8sHostsWatcher` 全在 orchestration 包；gateway-core（backend/handler/tool/task/auth/obs）不出现 `com.hierynomus.sshj` / fabric8 import。ArchUnit `PackageBoundaryTest` 守护（INV-BOUNDARY-1/2 延伸）。

---

## 回归门禁（FR-016，不破既有契约）

- 003：K-ATOMIC-1（原子幂等）/ K-ENS-1~12（ensure 行为，含 005 新增 K-ENS-10/11/12）/ SC-001（5min 端到端）。
- 005：INV-K8SHOST-1~5（懒 resolve）/ INV-LAUNCHER-1~5（JDK SPI）。
- 004：INV-SECRET-1（token 脱敏，升级到 ssh 凭证）/ INV-ISOL-1（管理/诊断隔离，延伸到 /admin/k8s-hosts）。
- ArchUnit：INV-BOUNDARY-1/2 + 新 INV-BOUNDARY-3（sshj 守护）。
