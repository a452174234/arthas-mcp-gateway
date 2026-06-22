# 最小 K8S 测试环境构建方案 — 设计文档

| 项目 | 内容 |
|------|------|
| 主题 | 在 debian-docker 服务器上构建**最小 k3s 测试集群 + 业务 pod 镜像**的可执行方案，供 003 特性波次 B（真实 K8S 夹具 IT）/ 波次 C（端到端）使用 |
| 日期 | 2026-06-23 |
| 特性 | `specs/003-k8s-arthas-mcp-launch`（测试基建前置，非功能代码） |
| 依据 | [003 设计](./2026-06-22-k8s-arthas-mcp-launch-design.md) §四/§八、[research.md R3](../../../specs/003-k8s-arthas-mcp-launch/research.md)、[quickstart.md §2.1](../../../specs/003-k8s-arthas-mcp-launch/quickstart.md)、[arthas 测试夹具设计](./2026-06-20-arthas-test-fixture-design.md)、[宪法](../../../.specify/memory/constitution.md) v1.2.0 |
| 设计阶段 | SuperPower 头脑风暴产出（CLAUDE.md：设计阶段走 brainstorming） |
| 关键决策 | **在线最小 k3s + 离线 airgap 安装**（资源预置 `reference/k3s/`，防网络波动）；**业务镜像纯 app 无 arthas**（ensure 时上传）；**身份=root-on-node**（不建 K8S RBAC）；**仓库内幂等 setup 脚本**（`test-env/k8s/`） |

---

## 一、背景与范围

### 1.1 问题

003 特性的波次 B/C 需要**真实 K8S 集群 + 真实业务 pod**作为测试夹具（CLAUDE.md「零桩、真实环境」硬约束）。既有 `research.md` R3 已定「debian-docker（192.168.31.92）上的 k3s」为测试集群，但 `quickstart.md` §2.1 仅留了骨架（一行 `curl get.k3s.io | sh -` + 占位的镜像/pod 步骤），不可执行。本文把该骨架明确化为**可执行的最小测试环境构建方案**。

### 1.2 范围（P1 测试基建）

- **在范围内**：① k3s 离线安装脚本（资源预置 + 瘦身 + tls-san）；② demo 业务 pod 镜像构建（Dockerfile + 在 debian 上 build + 入 k3s containerd）；③ demo-pod 清单；④ root-on-node 派生的 admin kubeconfig 导出（供本机网关远程用）；⑤ NodePort 网络可达性验证；⑥ 幂等清理（teardown）。
- **起步覆盖的契约用例**：`K-ENS-1/2/3`（happy/复用/经网关诊断）+ `K-ENS-7`（R4 回归：`0.0.0.0` 经 NodePort 可达）——对应**单个「正常 pod」**（shell+java+JVM 跑 DemoBusinessApp）。
- **后置（按 TDD 进度逐步补，非本设计）**：`K-ENS-4`（无 JVM）/ `K-ENS-5`（无 shell）/ `K-ENS-6`（无 exec 权限）三类故障用例——其 pod 形态：无 JVM=同镜像改 `CMD sleep`、无 shell=补 distroless 镜像、无 exec=补受限 kubeconfig。

### 1.3 与功能代码的隔离

本设计**不碰 003 功能代码**（`orchestration` 包）。产物 `test-env/k8s/` + `reference/k3s/` 属**测试基建**，与 `src/` 完全隔离；既有 001/002 测试不受影响（回归对照照常）。

---

## 二、前置与约束

| 项 | 现状 | 来源 |
|---|---|---|
| **承载服务器** | `debian-docker`（192.168.31.92），Debian 13，Docker 26.1.5，2 核 / 3.8Gi，amd64，SSH key 免密 root 登录（本机 `~/bin/on-debian '<cmd>'`） | memory `debian-docker-ssh-access` |
| **本机** | Windows 11，**无 Docker**；JDK 21 + Maven 可用（`mvn test-compile` 产出 `target/test-classes`） | memory `arthas-no-dependency`、`java-version-jdk21` |
| **网络** | github.com 在本网络**间歇不可达**——k3s 资源须**预置本地、离线安装** | memory `github-com-unreachable` |
| **身份模型** | 访问原语 = **debian 主节点 root 登录**（即 k3s 的 `system:masters` cluster-admin）；**不另建** K8S ServiceAccount/Role。网关集群外用的 kubeconfig = 主节点 root 派生的 admin 凭证（re-point server），不是新建权限 | 用户裁决（2026-06-23） |
| **arthas** | 作静态工具文件 `tools/arthas-boot.jar`（不入 pom、不入镜像）；`ensure` 时 fabric8 `cp` 上传进 pod | design 2026-06-22 §4.1、memory `arthas-no-dependency` |
| **真实性** | 零桩、真实环境（CLAUDE.md 硬约束）——本 env 提供真实集群 + 真实业务 JVM | CLAUDE.md |

> 本机 Windows 无 Docker → **镜像在 debian 上 build**（debian 有 Docker 26.1.5），import 进 k3s containerd；本机只负责 `mvn test-compile` 产出 class + scp 传输。

---

## 三、产物与目录

### 3.1 `test-env/k8s/`（入库的测试基建脚本）

```
test-env/k8s/
├── README.md          # 前置 + 一键用法 + 故障排查 + 与 quickstart/research 衔接
├── Dockerfile         # demo 业务镜像（JDK21 + DemoBusinessApp，无 arthas）
├── demo-pod.yaml      # 单 pod 清单（label/资源/探针/常驻）
├── setup.sh           # 本机编排：mvn 编译 → ship 到 debian → 离线装 k3s + build + import + apply + 导出 kubeconfig
└── teardown.sh        # k3s-uninstall + 清镜像 + 删本机 kubeconfig
```

### 3.2 `reference/k3s/`（预置的 k3s 离线资源，与 `reference/arthas/` 同级）

```
reference/k3s/
├── fetch.sh                            # 一次性下载（带重试，锁版本）；入库
├── k3s-install.sh                      # get.k3s.io 安装脚本副本（小）；入库
├── sha256sums.txt                      # 完整性校验；入库
├── k3s                                 # （.gitignore）k3s 二进制 ~60MB
└── k3s-airgap-images-amd64.tar.gz      # （.gitignore）离线系统镜像 ~180MB
```

- **大二进制 gitignore**（避免仓库膨胀 ~250MB）；`fetch.sh` / `k3s-install.sh` / `sha256sums.txt` 入库以保证可复现/可校验。
- **构建顺序**：先 `reference/k3s/fetch.sh`（联网一次，带重试）→ 再 `test-env/k8s/setup.sh`（全程离线）。

### 3.3 `.gitignore` 增量

```
test-env/k8s/kubeconfig/
reference/k3s/k3s
reference/k3s/k3s-airgap-images-amd64.tar.gz
```

---

## 四、demo 业务镜像（`Dockerfile`）

| 维度 | 决策 |
|---|---|
| **基础镜像** | `eclipse-temurin:21-jdk`（debian 系，自带 `sh`；需 `jps` 定位 JVM PID → JDK 非 JRE）。`hasShell`/`hasJvm` 均满足（design §4 目标须 shell+java+JVM） |
| **镜像内容** | 仅 3 个运行时类：`DemoBusinessApp` + `OrderService` + `OrderResult`（本机 `mvn test-compile` 产出 `target/test-classes/com/arthas/gateway/testifacts/`）。夹具类（`ArthasMcpBackend`/`McpClientHarness`）、`*Test` 类**不进镜像** |
| **arthas** | **不含**——`ensure` 时 fabric8 `cp tools/arthas-boot.jar` 上传（design §4.1）；镜像纯 app |
| **CMD** | `["java","-cp","/app/classes","com.arthas.gateway.testfixtures.DemoBusinessApp","8081"]`——常驻；内置 hot-loop 每 50ms 自触发 `hotMethod`（watch/trace 无需外部触发）；`/actuator/health`、`/api/order` 在容器内 8081 |
| **tag / pullPolicy** | `arthas-gateway/demo-business:local`；`imagePullPolicy: Never`（本地 import，不 pull） |
| **build 地点** | **debian 上**（本机无 Docker）：setup.sh 把 Dockerfile + 3 个 class scp 到 debian → `docker build -t arthas-gateway/demo-business:local .` → `docker save | sudo k3s ctr images import -` |

**Dockerfile（样例）**：
```dockerfile
FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY classes/com/arthas/gateway/testfixtures/ /app/classes/com/arthas/gateway/testfixtures/
EXPOSE 8081
CMD ["java", "-cp", "/app/classes", "com.arthas.gateway.testfixtures.DemoBusinessApp", "8081"]
```
> build context = `Dockerfile` + `classes/com/arthas/gateway/testfixtures/{DemoBusinessApp,OrderService,OrderResult}.class`（setup.sh 在 debian 上组好该目录树）。

---

## 五、`demo-pod.yaml`

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: demo-business
  namespace: default
  labels:
    app: demo-business        # ensure 另打 arthas-mcp-gateway/target=<logicalName>（design §4.1）
spec:
  containers:
  - name: app
    image: arthas-gateway/demo-business:local
    imagePullPolicy: Never
    resources:
      requests: { cpu: 200m, memory: 384Mi }
      limits:   { cpu: 1000m, memory: 1Gi }
    readinessProbe: { httpGet: { path: /actuator/health, port: 8081 }, periodSeconds: 5 }
    livenessProbe:  { httpGet: { path: /actuator/health, port: 8081 }, periodSeconds: 10 }
  restartPolicy: Always
```

- **无 Service**：`ensure` 才建 NodePort Service（暴露 arthas MCP 端口）；业务 8081 仅容器内探针用。hot-loop 自驱动诊断事件，无需外部触发。
- **namespace = `default`**：身份=root-admin，无 RBAC 隔离需求；最简（后续整洁化可挪专用 ns，非必需）。
- **资源**：demo JVM ~384Mi + arthas attach 额外开销；limit 1Gi。2核/3.8Gi 节点经 k3s 瘦身后容纳单 demo pod 充裕（§六）。

---

## 六、k3s 离线安装与瘦身

### 6.1 一次性资源下载 `reference/k3s/fetch.sh`

```bash
#!/usr/bin/env bash
# 锁定单一 k3s 稳定版（amd64）。版本号 + sha256 写入本文件顶部与 sha256sums.txt。
# 当前锁定 v1.35.5+k3s1（2026-06 stable channel，update.k3s.io/v1-release/channels）。
# 升级时显式改 K3S_VERSION + 重跑 fetch.sh 刷 sha256sums.txt。
set -euo pipefail
K3S_VERSION="v1.35.5+k3s1"
ARCH=amd64
BASE="https://github.com/k3s-io/k3s/releases/download/${K3S_VERSION}"
OUT="$(dirname "$0")"

# 断言架构（debian 须 x86_64）
[ "$(uname -m)" = "x86_64" ] || { echo "仅支持 amd64"; exit 1; }

# 带重试下载（github 间歇不可达，memory github-com-unreachable：坏时重试/等几分钟）
retry() { for i in 1 2 3 4 5; do "$@" && return 0; echo "重试 $i..."; sleep 30; done; return 1; }

retry curl -fL "${BASE}/k3s"                             -o "${OUT}/k3s"
retry curl -fL "${BASE}/k3s-airgap-images-${ARCH}.tar.gz" -o "${OUT}/k3s-airgap-images-${ARCH}.tar.gz"
retry curl -fL "https://raw.githubusercontent.com/k3s-io/k3s/${K3S_VERSION}/install.sh" -o "${OUT}/k3s-install.sh"

( cd "${OUT}" && sha256sum k3s k3s-airgap-images-${ARCH}.tar.gz k3s-install.sh > sha256sums.txt )
chmod +x "${OUT}/k3s" "${OUT}/k3s-install.sh"
echo "k3s 离线资源就绪：${OUT}（版本 ${K3S_VERSION}）"
```

### 6.2 离线 airgap 安装（`setup.sh` 核心，经 `on-debian` root SSH）

setup.sh 把 `reference/k3s/*` scp 到 debian `/tmp/k3s-artifacts/`，幂等安装：

```bash
# 0. 校验完整性
on-debian 'cd /tmp/k3s-artifacts && sha256sum -c sha256sums.txt'

# 1. k3s 已装则跳过（幂等）
on-debian 'test -x /usr/local/bin/k3s || {
  # 放二进制
  install -m 755 /tmp/k3s-artifacts/k3s /usr/local/bin/k3s;
  # 放 airgap 系统镜像（flannel/coredns/local-path 等，启动时自动 load）
  mkdir -p /var/lib/rancher/k3s/agent/images &&
    cp /tmp/k3s-artifacts/k3s-airgap-images-amd64.tar.gz /var/lib/rancher/k3s/agent/images/;
  # 离线装（跳过下载），瘦身 + tls-san
  INSTALL_K3S_SKIP_DOWNLOAD=true sh /tmp/k3s-artifacts/k3s-install.sh \
    --disable traefik --disable servicelb --disable metrics-server \
    --tls-san 192.168.31.92;
}'
```

### 6.3 瘦身与 flags 理由

| flag | 理由 |
|---|---|
| `--disable traefik` | 不用 ingress；NodePort 直达，省 ingress controller 内存 |
| `--disable servicelb` | 不用 LoadBalancer（NodePort 即可）；省 ServiceLB pod |
| `--disable metrics-server` | 不依赖指标；省 metrics-server 内存 |
| `--tls-san 192.168.31.92` | API server 证书对本机 Windows 连的 IP 有效（否则 kubeconfig 证书校验失败） |
| 保留 flannel + local-path-provisioner | 基础 pod 网络 + 存储（demo pod 需要） |

> 2核/3.8Gi 经瘦身（省 ~300–500Mi）后容纳 k3s 控制面 + 单 demo pod（limit 1Gi）充裕。
> **不用** `--write-kubeconfig-mode`（保持 `k3s.yaml` 600）；导出经 `sudo cat`（§七）。

---

## 七、身份与 kubeconfig 导出

身份模型：**debian root（主节点）= `system:masters` cluster-admin**；不建 ServiceAccount/Role。k3s 装完，`/etc/rancher/k3s/k3s.yaml`（600，root 可读）即 cluster-admin 凭证。

```bash
# setup.sh 末尾：debian root 读 600 的 k3s.yaml → 改 server → 落本机（gitignore 目录）
on-debian 'sudo cat /etc/rancher/k3s/k3s.yaml' \
  | sed 's|https://127.0.0.1:6443|https://192.168.31.92:6443|' \
  > test-env/k8s/kubeconfig/k3s-admin.yaml
```

- 网关 `arthas-gateway.k8s.kubeconfig`（特性 impl 期加，见 [research.md R2/K8sClientFactory](../../../specs/003-k8s-arthas-mcp-launch/research.md)）指向此文件。
- **安全**：`k3s-admin.yaml` = cluster-admin 凭证 → `.gitignore`，仅本机 + debian 受控内网使用（符合 MVP 受控/无认证假设）。`K-ENS-6`（无 exec→403）随故障用例后置。

---

## 八、网络可达性验证（NodePort：Windows → debian）

- k3s flannel VXLAN 默认；NodePort 30000–32767 在 debian 主网卡监听。
- **setup.sh 末尾自检**（debian 上）：`ss -tlnp | grep :6443`（API up）、`kubectl get pods`（demo Ready）。
- **本机 Windows**（ensure 后）：`Test-NetConnection 192.168.31.92 -Port <nodePort>` 验 NodePort 可达（SC-001「5 分钟内完成 ensure+诊断」的前提）。
- debian 13 默认通常无防火墙规则；若内网防火墙挡，setup.sh 报错并提示开放 30000–32767（受控内网通常不需）。

---

## 九、清理（`teardown.sh`）

```bash
on-debian '/usr/local/bin/k3s-uninstall.sh'   # 卸 k3s + 清容器/镜像/cni（k3s 官方卸载器）
rm -f test-env/k8s/kubeconfig/k3s-admin.yaml  # 删本机凭证
```
幂等：k3s 未装则跳过。

---

## 十、与既有文档/特性衔接

- **`quickstart.md` §2.1**：从骨架改为「一键 `on-debian test-env/k8s/setup.sh`」（前置：先 `reference/k3s/fetch.sh`）+ 预期结果。
- **`research.md` R3**：补「实施细节 = `test-env/k8s/` setup 脚本（在线、debian 已有 Docker）+ `reference/k3s/` 离线资源预置」。
- **不碰 003 功能代码**（orchestration 包）；env 完全隔离。
- **DemoBusinessApp 容器化** = 001 夹具的 K8S 形态（[data-model §7](../../../specs/003-k8s-arthas-mcp-launch/data-model.md)、[research.md R7](../../../specs/003-k8s-arthas-mcp-launch/research.md) 已记），本设计给具体 Dockerfile/manifest。
- **P2（K8S 离线构造脚本）后置**：P2 面向离线/生产从零构造（含离线镜像包打包分发），与本在线 test-env（debian 已有 Docker、在线 fetch 一次）各自演进、不冲突。

---

## 十一、Done Definition（验证清单）

- [ ] `reference/k3s/fetch.sh` 跑通：3 资源齐 + `sha256sum -c` 过。
- [ ] `on-debian test-env/k8s/setup.sh` 幂等跑通：k3s up、demo pod Ready、kubeconfig 导出。
- [ ] setup.sh 离线：安装阶段**不触网**（可临时断网验证 k3s 装+镜像 import+apply）。
- [ ] 本机 `kubectl --kubeconfig test-env/k8s/kubeconfig/k3s-admin.yaml get pods` 见 `demo-business Running`。
- [ ] NodePort 可达：`ensure` 后本机 `Test-NetConnection 192.168.31.92 -Port <nodePort>` 通。
- [ ] `teardown.sh` 清干净（k3s 卸、凭证删、`/tmp/k3s-artifacts` 清）。
- [ ] 不回归：001/002 既有测试不受影响（env 完全隔离）。

---

## 十二、风险与对冲

| 风险 | 对冲 |
|---|---|
| github.com 间歇不可达 → fetch.sh 下载失败 | `retry` 重试 5 次 ×30s；坏时手动等几分钟重跑（memory `github-com-unreachable`）；资源预置后 setup 全程离线 |
| k3s airgap tar 与瘦身组件版本不匹配 | airgap tar 为 k3s 官方同版本打包；`--disable` 组件不 load，无冲突；sha256 校验保证完整 |
| debian 防火墙挡 NodePort | setup.sh 自检 + 报错提示开放 30000–32767（受控内网通常无） |
| 2核/3.8Gi 资源紧张 | 瘦身 traefik/servicelb/metrics-server；单 demo pod limit 1Gi；后续加故障 pod 时复算内存 |
| cluster-admin 凭证泄露 | `test-env/k8s/kubeconfig/` + `reference/k3s/` 大文件 `.gitignore`；仅受控内网 |
| 非 amd64 节点 | fetch.sh `uname -m` 断言；当前 debian 为 amd64 |

---

## 十三、与 spec / plan 的关系

- 本文档是**设计阶段（brainstorming）产出**，记录最小 K8S 测试环境的构建方案。
- **不走** SuperPower `writing-plans`（CLAUDE.md 工作流规范白名单禁用）。本设计的**落地物是 `test-env/k8s/` + `reference/k3s/` 脚本本身**（测试基建，非 spec-kit 特性功能），其结论回灌 `quickstart.md` §2.1 与 `research.md` R3。
- 实施时机：作为 003 波次 B 的**前置**（波次 B 真实夹具 IT 依赖此 env）；按 TDD「测试先于实现」，env 随首批波次 B 用例（`K8sEnsureContractIT`）就绪。
