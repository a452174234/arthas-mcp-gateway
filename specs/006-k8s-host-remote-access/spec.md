# Feature Specification: K8S Host 远程接入与配置热生效（SSH 引导 / 全配置热生效 / portal 管理 / 显示增强）

**Feature Branch**: `006-k8s-host-remote-access`

**Created**: 2026-07-13

**Status**: Draft

**Input**: 用户描述："spec-005 的 k8s host 配置……是否可以实现在提供了 ip 账户 密码后就可以开始连接对应主机上的 k8s pod 了" + "我只会提供登录 k8s 主机 root 账户的方法，如果 k8s 自身有密码校验我是处理不了的" + "所有的配置变更我都希望他热生效" + "portal 应该也要承担配置 linux 配置信息的任务，当前没有实现" + "ensure 连上的 arthas 在 portal list 应该显示（跑过但没看到）" + "不简化" → brainstorming 定稿（[2026-07-13-k8s-host-remote-access-design.md](../../docs/superpowers/specs/2026-07-13-k8s-host-remote-access-design.md)）。

**范围声明**：本特性 = US1 SSH 引导接入（P1，波1）+ US2 全配置热生效（P1，波2）+ US3 portal 管理（P1，波3）+ US4 显示增强 + bug 修复（P2，波4）。复用 003/005 既有编排核心；零 gateway-core K8S/SSH 依赖不变（ArchUnit 守护）；003/005 既有契约全部不破（回归门禁）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - SSH 引导接入：只配 IP + root + 密码就连 K8S pod（Priority: P1，波1）

运维只有 Linux 主机的 root SSH 凭证（IP + 账户 + 密码），**拿不到也处理不了** K8S 自身的鉴权（kubeconfig / 证书 / token）。005 的 K8sHost 要求提供 kubeconfig 文件路径，用户根本无法满足。希望网关 SSH 登 master、自动取现成的 admin kubeconfig，构造 K8S 客户端——用户全程不碰 K8S 鉴权。

标准 K8S（kubeadm）的 master 节点上 `/etc/kubernetes/admin.conf` 是 root 可读的 cluster-admin kubeconfig（k3s 为 `/etc/rancher/k3s/k3s.yaml`）。网关 SSH 登入读取它即可。SSH 仅做一次性引导取 kubeconfig（公司内网互通，6443/NodePort 直连，不做隧道）。

**Why this priority**: 首要阻塞——不解决"用户只有 root SSH 凭证"，就连不上集群，后续热生效/portal/显示都无从谈起。

**Independent Test**: 配一个 K8sHost（ssh: master IP + root + 密码 + kubeconfig 远端路径），触发 ensure/list-pods，确认网关 SSH 取 kubeconfig 成功并连上 K8S API。

**Acceptance Scenarios**:

1. **Given** K8sHost 配 `ssh`（master IP + root + 密码 + `kubeconfig-remote-path: /etc/kubernetes/admin.conf`），**When** ensure/list-pods，**Then** 网关 SSH 登 master 取 kubeconfig → 构造客户端 → 连 K8S API 成功（list/exec 可用）。
2. **Given** SSH 连不上（host 不可达 / 端口拒绝），**When** 触发，**Then** 返 `ssh_unreachable` 结构化错误。
3. **Given** SSH 认证失败（错密码 / 错 key），**When** 触发，**Then** 返 `ssh_auth_failed`。
4. **Given** 远端 kubeconfig 路径不存在，**When** 触发，**Then** 返 `kubeconfig_not_found`。
5. **Given** kubeconfig 里 server 不可达（如 `127.0.0.1` / 内部 VIP），**When** 配 `server-override`，**Then** 客户端连 override 地址。
6. **Given** apiserver 证书 SAN 不含连接地址，**When** 配 `insecure-skip-tls-verify: true`，**Then** 跳过 TLS 校验连接（默认 false 时 TLS 失败）。
7. **Given** K8sHost 同时配 `kubeconfig` 与 `ssh`（互斥违规），**When** 加载，**Then** 校验失败、保留旧配置（不半替换）。

---

### User Story 2 - 所有 K8S 配置热生效（Priority: P1，波2）

运维不希望任何配置变更重启网关——增删改 K8sHost、改 SSH 密码、改 arthas 版本/端口/超时等，都要运行时立即生效。当前 K8sHost 是启动期一次性绑定（`@ConfigurationProperties`），改配置必须重启。

K8sHost 配置独立到 `config/k8s-hosts.yaml`（仿 `backends.yaml` + 文件监听），任何来源的变更（手改文件 / portal / 启动）经统一热重载管道触发 host 增/删/改。

**Why this priority**: 用户明确"所有配置变更都热生效"。重启生效在内网运维场景体验差、影响在途诊断、阻碍 portal 即改即用。

**Independent Test**: 运行时改 K8S 配置文件（加/删/改 host），确认网关自动重载——新 host 立即可路由、删 host 立即不可用、改 host 立即用新配置，全程不重启。

**Acceptance Scenarios**:

1. **Given** 网关运行中，**When** 新增一个 K8sHost 到配置文件，**Then** 文件监听检测 → SSH 建新客户端 → 新 host 立即可路由（不重启）。
2. **Given** 网关运行中，**When** 删除一个 K8sHost，**Then** 该 host 客户端关闭释放 + 立即不可路由 + 缓存清除。
3. **Given** 网关运行中，**When** 改某 host 的 ssh 密码/路径，**Then** 该 host 客户端重建 + 缓存清除 + 下次路由用新配置。
4. **When** 改 k8s 全局参数（arthas-password/version/ensure-timeout/node-port-range/target-ip/mcp-port），**Then** 下次 ensure 用新值（已 ensure 的 pod 不变，幂等不破）。
5. **Given** 配置文件解析失败，**When** 重载，**Then** 回退上次有效配置 + 结构化错误日志（不崩、不半替换）。
6. **Given** `config/k8s-hosts.yaml` 不存在，**When** 启动，**Then** 回退读 application.yml 内联 `k8s-hosts`（005 兼容）。

---

### User Story 3 - portal 管理 K8sHost（Priority: P1，波3）

运维希望经 portal 网页增删改 K8sHost（含 Linux 连接信息 IP/账户/密码），改完立即生效，不用手改 yaml + 重启。当前 portal（004）只管 BackendConfig，完全不碰 K8sHost。

portal `/admin/k8s-hosts` CRUD 写 `config/k8s-hosts.yaml` → 复用波 2 热重载管道（portal 是热重载的触发源之一，不单独建客户端）。root 密码极敏感：经 portal 接收 → AES-GCM 加密落盘（密钥来自环境变量），DTO 永不回显。

**Why this priority**: portal 是 004 建立的管理面，K8sHost 配置应纳入 portal 统一管理（用户明确"portal 应承担配置 linux 信息的任务"）。

**Independent Test**: 经 portal POST 一个 K8sHost（ssh 子段），确认写入配置文件 → 热重载 → host 立即可路由；ssh 密码加密落盘、回显脱敏。

**Acceptance Scenarios**:

1. **Given** portal 可访问，**When** POST `/admin/k8s-hosts`（含 ssh），**Then** 写入配置文件 → 热重载 → host 立即可路由。
2. **When** GET `/admin/k8s-hosts`，**Then** 返回所有 host（含连接状态），但 ssh password/privateKey/passphrase 永不回显（脱敏）。
3. **Given** 未配 `ARTHAS_GATEWAY_SECRET` 环境变量，**When** POST 含凭证的 host，**Then** 返 400 `secret_key_not_configured`（不裸明文落盘）。
4. **Given** 配了 SECRET，**When** POST 含密码 host，**Then** 密码 AES-GCM 加密落盘 + 加载时解密。
5. **When** DELETE `/admin/k8s-hosts/{name}`，**Then** host 删除 + 客户端关闭 + 立即不可路由。

---

### User Story 4 - 显示增强 + ensure 可见性修复（Priority: P2，波4）

运维在 portal backend list 里要能看到 ensure 连上的 pod/arthas 及其 K8S 来源（哪台 host、哪个 pod）。当前实测 ensure 后 list 看不到（bug），且 BackendDto 无 K8S 来源信息。

排查结论（bug agent）：代码层不过滤动态 backend（有单测断言）。"看不到"真因：① list 名字是 `{server}-{pod}`（认知错位）；② 前端不自动刷新（体验 bug）；③ 动态注册后 compose 重算异常被吞（`BackendConfigWatcher` 真实鲁棒性 bug，holder 没 swap）；④ K8S 种子是 STATIC，DYNAMIC 行要首次路由后才出现。

**Why this priority**: 可观测性（宪法原则五"沉默即缺陷"）+ 用户实测的可见性 bug。P2 因前三波是连接/配置/管理的核心，显示是增强。

**Independent Test**: ensure 一个 pod，刷新 portal backend list，确认看到该 target（`{server}-{pod}`）+ K8S 来源字段。

**Acceptance Scenarios**:

1. **Given** ensure 成功纳管，**When** GET `/admin/backends`，**Then** list 含该 target（名字 `{server}-{pod}`），不受 compose 异常吞咽影响（修 bug）。
2. **Given** ensure 成功，**When** 前端打开/刷新 backend list，**Then** 看到新行（前端自动刷新，修体验 bug）。
3. **Given** K8S 来源 backend，**When** list，**Then** DTO 带 `k8sHost`/`pod`/`namespace`/`sourceDetail` 字段（区分 ssh/kubeconfig 来源）。
4. **Given** list，**Then** DTO 仍不含 token/password（凭证脱敏不破，INV-SECRET-1）。

---

### Edge Cases

- **K8sHost 同时配 kubeconfig 与 ssh**（互斥违规）→ 加载校验失败，保留旧配置（不半替换）。
- **SSH 取到的 kubeconfig 内容空/损坏** → `kubeconfig_invalid` 错误，该 host 装配失败，其余 host 不受影响（隔离）。
- **热重载时在途 ensure** → applyDiff 串行 + 路由读无锁，不破坏在途调用（INV-HOT-2）。
- **删除 host 时该 host 有在途诊断** → 隔离 + 熔断（复用 003 K-COEXIST-2）。
- **公司标准 K8S apiserver 证书 SAN 不含外部 IP** → `insecure-skip-tls-verify` 兜底（默认 false，开启需知中间人风险）。
- **kubeconfig server 是 VIP/域名不可解析** → `server-override` 替换为可达 IP。
- **测试床 k3s（/etc/rancher/k3s/k3s.yaml）vs 标准 K8S（/etc/kubernetes/admin.conf）** → `kubeconfig-remote-path` 可配，兼容两者。
- **root 密码极敏感** → 永不裸写 application.yml / 配置文件明文、永不回显、永不入日志；portal 写入必经加密。
- **配置文件被外部进程部分写入**（读到半截）→ 解析失败 → 回退上次有效配置（INV-HOT-4）。
- **零 gateway-core K8S/SSH 依赖** → ArchUnit 守护 sshj 不进 gateway-core（INV-BOUNDARY 延伸）。

## Requirements *(mandatory)*

### Functional Requirements

**SSH 引导接入（波1）**

- **FR-001**: K8sHost 必须支持 `ssh` 子段（host/port/user/password/privateKey/passphrase/kubeconfig-remote-path/server-override/insecure-skip-tls-verify），与现有 `kubeconfig` 字段**互斥**（皆空/皆有 → 校验失败保留旧配置）。
- **FR-002**: 网关必须能 SSH 登 master、读取远端 kubeconfig 文本（标准 K8S 默认 `/etc/kubernetes/admin.conf`；k3s `/etc/rancher/k3s/k3s.yaml`；路径可配），构造 K8S 客户端连接 K8S API。
- **FR-003**: SSH 连接/认证/取文件失败必须映射为结构化错误（`ssh_unreachable`/`ssh_auth_failed`/`kubeconfig_not_found`/`kubeconfig_invalid`/`tls_handshake_failed`），传播进 ensure 失败记录（沿用 005 结构化形态）。
- **FR-004**: `server-override` 必须能替换 kubeconfig 里不可达的 server 地址；`insecure-skip-tls-verify=true` 必须跳过 TLS 证书校验（默认 false）。
- **FR-005**: 现有 `kubeconfig` 本地文件模式必须 100% 保留（003/005 向后兼容，buildFromKubeconfig 不变）。

**全配置热生效（波2）**

- **FR-006**: K8sHost 配置必须独立到 `config/k8s-hosts.yaml`（仿 `backends.yaml` + 文件监听），application.yml 只留文件指针 `arthas-gateway.k8s-hosts-file`；文件不存在时回退 application.yml 内联 `k8s-hosts`（005 兼容）。
- **FR-007**: 配置文件变更（手改/portal/启动）必须经**统一热重载管道**触发 host 增/删/改——新增 host 建 client、删除 host 关 client 释放连接、改 host 重建 client，全程不重启。
- **FR-008**: k8s 全局参数（arthas-password/version/ensure-timeout/node-port-range/target-ip/mcp-port）变更必须下次 ensure 读新值（不绑 client，无需重建；已 ensure 的 pod 不变）。
- **FR-009**: 配置文件解析失败必须回退上次有效配置 + 结构化错误日志（不崩、不半替换）。

**portal 管理（波3）**

- **FR-010**: portal 必须提供 `/admin/k8s-hosts` CRUD（增删改查 K8sHost，写 `config/k8s-hosts.yaml` 触发热重载），与诊断面 `/mcp`、与 `/admin/backends` 隔离。
- **FR-011**: ssh 凭证（password/privateKey/passphrase）必须经 AES-GCM 加密落盘（密钥来自 `ARTHAS_GATEWAY_SECRET` 环境变量），DTO 永不回显；未配 SECRET 时写凭证端点返 400 `secret_key_not_configured`。
- **FR-012**: portal 必须提供前端 `/k8s-hosts` 视图（表格 + 表单，密码输入 type=password，编辑留空=不改）。

**显示增强 + bug 修复（波4）**

- **FR-013**: `BackendDto` 必须增加 K8S 来源字段（`k8sHost`/`pod`/`namespace`/`sourceDetail`/`ensureStatus`），供 portal 展示。
- **FR-014**: ensure 成功纳管后，注册表当前快照必须含该 target（修复 `BackendConfigWatcher:190-196` 动态 compose 异常吞咽导致的 holder 不更新 bug），portal list 经刷新可见。
- **FR-015**: portal backend list 前端必须支持自动刷新（ensure 后能看到新行，不强制手动刷新）。

**回归与约束（横切）**

- **FR-016**: 本次迭代必须不破坏 003/005 既有行为——ensure 原子幂等（K-ATOMIC-1）、Service 复用（K-ENS-10/11/12）、懒 resolve（INV-K8SHOST-*）、JDK SPI（INV-LAUNCHER-*）、5 分钟端到端（SC-001）全部继续通过。
- **FR-017**: gateway-core 诊断核心包必须保持零 K8S/SSH 依赖（ArchUnit 守护 sshj 不进 gateway-core）；SSH 相关类（SshKubeconfigFetcher/K8sHostStore/K8sHostsWatcher）全在 orchestration 包。
- **FR-018**: 测试必须遵循宪法原则七（TDD 真实环境，零桩）——SSH 链路用真实 SSH 协议（embedded server）+ 契约 IT 跑真实测试床 k3s；故障用真实故障条件（错密码/停 SSH/错路径）。

### Key Entities

- **K8sHost 增量（ssh 子段）**：SSH 引导接入参数（host/port/user/password/privateKey/passphrase/kubeconfig-remote-path/server-override/insecure-skip-tls-verify），与 kubeconfig 互斥。
- **SshKubeconfigFetcher**（新增，orchestration）：SSH 登 master 取 kubeconfig 文本，封装 SSH 库。
- **K8sHostStore**（新增，orchestration）：运行时 host→client/provisioner 生命周期管理，applyDiff 增删改（仿 DynamicBackendStore）。
- **K8sHostsWatcher**（新增，orchestration）：监听 `config/k8s-hosts.yaml` 变更 → 触发 K8sHostStore.applyDiff（仿 BackendConfigWatcher）。
- **K8sHostSecretCipher**（新增）：AES-GCM 加密 ssh 凭证（密钥来自 `ARTHAS_GATEWAY_SECRET`）。
- **K8sHostAdminService/Controller + DTO**（新增，admin/k8shost）：portal CRUD K8sHost。
- **BackendDto 增量**：K8S 来源字段（k8sHost/pod/namespace/sourceDetail/ensureStatus）。
- **既有复用**：`K8sClientFactory`（加 buildFromSsh）、`K8sOrchestrationConfig`（装配分支）、`K8sBackendResolver`（从 store 查 provisioner）、`BackendConfigWatcher`（修 compose 异常吞咽）、`RegistryHolder`/`RegistryComposer`/`DynamicBackendStore`（显示投影）。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**（SSH 引导）：配 K8sHost（ssh: master IP + root + 密码），ensure/list-pods 成功连上 K8S API；用户全程不接触 kubeconfig/证书/token。
- **SC-002**（热生效）：运行时改 K8S 配置文件，新/删/改 host 在文件监听去抖周期内（秒级）生效，全程不重启网关。
- **SC-003**（portal 管理）：经 portal 网页增删改 K8sHost，改完立即生效；ssh 密码加密落盘、永不回显。
- **SC-004**（显示）：ensure 成功后刷新 portal，能看到该 target（`{server}-{pod}`）+ K8S 来源（host/pod）。
- **SC-005**（回归不破）：003 K-ATOMIC-1/K-ENS-1~12 + 005 INV-K8SHOST-*/INV-LAUNCHER-* + ArchUnit（含 sshj 守护）全部继续通过。
- **SC-006**（隔离）：单个 host 的 SSH/连接失败不影响其他 host 和静态后端（隔离 + 熔断，K-COEXIST-2 延伸）。

## Assumptions

- 复用 003/005 既有架构（orchestration 包、ensure 原子幂等、懒 resolve、K8sHost/BackendResolver）；本特性增量扩展接入方式 + 热生效 + portal + 显示，不重建编排核心。
- 用户的 K8S 是标准 K8S（kubeadm），root SSH 登的是 master/control-plane 节点（kubectl 能跑，`/etc/kubernetes/admin.conf` root 可读）；k3s 测试床路径为 `/etc/rancher/k3s/k3s.yaml`（`kubeconfig-remote-path` 可配兼容）。
- 网关与 K8S 集群网络互通（公司内网，6443/NodePort 直连可达，SSH 无防火墙）——SSH 仅一次性引导取 kubeconfig，不做隧道。
- SSH 库用 sshj（API 简洁、活跃、支持 Ed25519，符合宪法原则六 Java 主力）；测试用 Apache MINA SSHD embedded server（真实 SSH 协议，非桩）。
- 开发期公司标准 K8S 够不着，用测试床 k3s 验证核心链路（SSH→kubeconfig→连），标准 K8S 真实端到端延后到公司落地（文档显著声明，非桩覆盖受限）。
- root 密码极敏感：portal 接收 → AES-GCM 加密落盘（密钥 `ARTHAS_GATEWAY_SECRET` 环境变量）；DTO/日志永不回显；P2 接 Vault。
- 热重载复用 001 `BackendConfigWatcher` 的 WatchService 模式；`K8sHostStore` 仿 `DynamicBackendStore`。
- K8sHost 配置独立 `config/k8s-hosts.yaml`；application.yml 留指针 `arthas-gateway.k8s-hosts-file`；文件不存在回退 application.yml 内联（兼容 005）。
- 测试遵循宪法原则七（TDD 真实环境，零桩）+ 原则六（Java 主力，sshj 是 Java 库）+ 原则八（plan Phase 0 先决研究 SSH/kubeconfig/admin.conf 格式）。
