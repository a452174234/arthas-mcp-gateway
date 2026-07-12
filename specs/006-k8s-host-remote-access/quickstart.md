# Quickstart: K8S Host 远程接入与配置热生效（006 端到端验证）

**Feature**: 006-k8s-host-remote-access | **Date**: 2026-07-13

> 本文是 006 四波次的**端到端验证指南**（证明 SSH 引导 / 全配置热生效 / portal 管理 / 显示增强跑通）。实现细节归 `tasks.md`；契约见 [contracts/](./contracts/)；实体见 [data-model.md](./data-model.md)；决策见 [research.md](./research.md)。前置：测试床 k3s（debian 192.168.31.92）+ SSH root 可达 + demo-business pod Running（`bash test-env/k8s/setup.sh`）。

---

## 0. 前置环境

| 项 | 要求 |
|----|------|
| 测试床 | k3s @ debian（192.168.31.92）+ SSH root 可达（key 免密或密码）+ demo-business pod Running |
| 网关 | `./mvnw clean verify` + `bash smoke/gateway-start.sh`（:8761/mcp）|
| 环境变量 | `ARTHAS_GATEWAY_SECRET`（AES-GCM 密钥，波3 portal 写凭证必需）|
| Claude Code | 真实 CC（`claude -p --mcp-config`）做端到端冒烟 |
| 公司标准 K8S | 够不着——核心链路用测试床 k3s 验证（路径可配兼容 admin.conf）|

---

## 1. 场景 A · SSH 引导接入（波1，INV-SSH-1/2/4）

验证用户只配 master IP + root + 密码，网关 SSH 自动取 kubeconfig 连 K8S。

### A.1 配置 K8sHost（ssh 引导）

`config/k8s-hosts.yaml`：
```yaml
version: 1
hosts:
  - name: debian-ssh
    namespace: default
    ssh:
      host: 192.168.31.92
      port: 22
      user: root
      password: ${K8S_SSH_PASSWORD}                      # 或 key：privateKey
      kubeconfig-remote-path: /etc/rancher/k3s/k3s.yaml  # 测试床 k3s
      # server-override: https://192.168.31.92:6443      # k3s.yaml server=127.0.0.1 时需替换
      insecure-skip-tls-verify: true                     # k3s 默认 SAN 不含 192.168.31.92（测试床 setup 已 --tls-san，可 false）
```

### A.2 启网关 + list-pods（验证 SSH 引导连通）

```bash
export K8S_SSH_PASSWORD=<root 密码>
bash smoke/gateway-start.sh
claude -p --mcp-config .mcp.json --permission-mode bypassPermissions \
  "调用 k8s.list-pods server=debian-ssh namespace=default。返回 pod 清单。"
```

**预期（INV-SSH-1）**：
- 网关日志见「SSH 引导：host=debian-ssh → 取 /etc/rancher/k3s/k3s.yaml → 构造 client → 连 K8S API 成功」。
- list-pods 返回 demo-business pod（hasJvm/hasShell）。
- 用户全程未接触 kubeconfig/证书/token。

### A.3 故障场景（INV-SSH-2）

```bash
# 错密码 → ssh_auth_failed
# 停 SSH 服务 → ssh_unreachable
# kubeconfig-remote-path=/etc/none → kubeconfig_not_found
```
**预期**：返结构化错误（reason 对应），不静默成功。

---

## 2. 场景 B · 全配置热生效（波2，INV-HOT-1/3）

验证运行时改配置，新/删/改 host 秒级生效，不重启。

### B.1 新增 host（热重载）

```bash
# 网关运行中，编辑 config/k8s-hosts.yaml 加一个新 host（或改 namespace）
# 等待 WatchService 去抖周期（秒级）
claude -p ... "调用 k8s.list-pods server=<新 host>"
```
**预期（INV-HOT-1）**：新 host 立即可路由（不重启）；日志见「K8sHostStore.applyDiff：新增 <host> → SSH 建 client」。

### B.2 删除/修改 host

```bash
# 从 yaml 删 host → 该 host 立即不可路由（client 已关，INV-HOT-1）
# 改 host 的 ssh 密码 → client 重建 + 缓存清除 → 下次路由用新配置
```

### B.3 全局参数热生效（INV-HOT-3）

```bash
# 改 k8s-params.arthas-password → 下次 ensure 用新密码（已 ensure 的 pod 不变）
claude -p ... "调用 k8s.ensure-arthas-mcp server=debian-ssh pod=demo-business"
```
**预期**：arthas 用新密码启动；旧 pod 行为不变（幂等）。

---

## 3. 场景 C · portal 管理（波3，INV-PORTAL-K8S-1/2/3/4）

验证经 portal 网页 CRUD K8sHost，改完立即生效 + 密码加密。

### C.1 POST 新 host（经 portal 触发热重载）

```bash
export ARTHAS_GATEWAY_SECRET=<256-bit base64 密钥>
curl -X POST http://localhost:8761/admin/k8s-hosts -H 'Content-Type: application/json' -d '{
  "name": "portal-host",
  "namespace": "default",
  "ssh": {"host":"192.168.31.92","user":"root","password":"<明文>","kubeconfigRemotePath":"/etc/rancher/k3s/k3s.yaml","insecureSkipTlsVerify":true}
}'
```
**预期（INV-PORTAL-K8S-1/4）**：201 + 写 `config/k8s-hosts.yaml`（密码 AES-GCM 加密）→ K8sHostsWatcher 热重载 → portal-host 立即可路由。

### C.2 GET 不回显凭证（INV-PORTAL-K8S-2）

```bash
curl http://localhost:8761/admin/k8s-hosts
```
**预期**：返回 host 列表含 connectionStatus，但 **无 password/privateKey/passphrase** 字段。

### C.3 未配 SECRET 拒绝（INV-PORTAL-K8S-3）

```bash
unset ARTHAS_GATEWAY_SECRET
curl -X POST .../admin/k8s-hosts <含 ssh 密码>
```
**预期**：400 `secret_key_not_configured`（不裸明文落盘）。

---

## 4. 场景 D · 显示增强 + bug 修复（波4，INV-DISP-1/2/3）

验证 ensure 后 portal 可见 + K8S 来源字段 + 自动刷新。

### D.1 ensure 后 list 可见（修 bug，INV-DISP-1）

```bash
claude -p ... "调用 k8s.ensure-arthas-mcp server=debian-ssh pod=demo-business"
curl http://localhost:8761/admin/backends
```
**预期**：list 含 `target=debian-ssh-demo-business`（名字 `{server}-{pod}`），不受 compose 异常吞咽影响（bug 已修）。

### D.2 前端自动刷新（INV-DISP-2）

浏览器开 `http://localhost:8761/backends` → 触发 ensure → **不手动刷新**，预期新行自动出现（前端轮询）。

### D.3 K8S 来源字段（INV-DISP-3）

```bash
curl .../admin/backends | jq '.backends[] | {name, k8sHost, pod, namespace, sourceDetail, ensureStatus}'
```
**预期**：K8S 来源 backend 带 `k8sHost=debian-ssh / pod=demo-business / sourceDetail=ssh:debian-ssh / ensureStatus=ready`；仍无 token/password（INV-DISP-4）。

---

## 5. 验证清单（Done Definition）

- [ ] 场景 A：SSH 引导（INV-SSH-1/2/4）—— 配 IP+root+密码 → list-pods 成功 + 故障结构化错误。
- [ ] 场景 B：全配置热生效（INV-HOT-1/3）—— 运行时增删改 host + 全局参数，秒级生效不重启。
- [ ] 场景 C：portal 管理（INV-PORTAL-K8S-1/2/3/4）—— CRUD 经热重载 + 凭证加密 + 不回显 + 未配 SECRET 400。
- [ ] 场景 D：显示 + bug（INV-DISP-1/2/3/4）—— ensure 后 holder 含 target + 前端自动刷新 + K8S 来源字段。
- [ ] 零 gateway-core SSH 依赖（INV-BOUNDARY-3）—— ArchUnit 守护 sshj 不进 gateway-core。
- [ ] 回归（FR-016）—— 003 K-ATOMIC-1/K-ENS-*/005 INV-K8SHOST/LAUNCHER + ArchUnit 全绿。
