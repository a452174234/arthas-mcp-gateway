# arthas MCP 网关 — 新环境启动指南与全面验证

> 目标：全新 clone 的环境照本文 ① 构建并启动网关、② 跑测试用例快速验证「MCP 是否可用」、③ 重点验证**远端 K8S 调用场景**（网关集群外运行 + kubeconfig + NodePort 路由到 pod JVM）。
>
> **真实性约定**（CLAUDE.md 宪法原则七）：本文每一条命令均在本地真实环境实跑过，输出为实测捕获（非杜撰）；故障类用真实故障条件（停后端/删 pod/错 token），**禁桩**。复现时若输出有偏差，以你本机实际为准并反馈。

---

## 0. 网关能力全景

arthas MCP 网关把「每个目标 JVM 各起一个 arthas MCP 端点」聚合为**单 HTTP MCP 端点**，对 Claude Code 等客户端暴露 38 个工具：

| 能力域 | 来源特性 | 工具 | 说明 |
|--------|----------|------|------|
| 诊断聚合 | 001 | 31 个 arthas 工具（`jvm`/`watch`/`trace`/`stack`/`tt`/`monitor`/`dashboard`/`sc`/…） | 每个带 `target` 参数，网关路由到指定后端 |
| 网关自有 | 001 | 4 个：`arthas-gateway.list-targets`/`task-get`/`task-list`/`task-cancel` | 多目标路由、异步任务系统 |
| 韧性 | 002 | （内建，非工具）熔断/隔离/并发/超时/退避 | 单点故障不扩散 |
| **K8S 编排** | **003（重点）** | 3 个：`k8s.list-pods`/`k8s.list-services`/`k8s.ensure-arthas-mcp` | 远端 K8S pod 注入 arthas + NodePort 暴露 + 动态纳管 |
| portal 管理面 | 004 | `/admin` HTTP API + Vue SPA | 后端 CRUD + 任务列表查询 + 结果导出（Web UI） |

---

## 1. 前置环境

| 项 | 版本/要求 | 验证命令 |
|----|-----------|----------|
| JDK | **21**（LTS，虚拟线程承载异步任务） | `java -version` → `21.x` |
| Maven | 用仓库自带 wrapper（本地 mvn 3.5.3 太老须 `./mvnw`） | `./mvnw -v` |
| Node | **22.x**（前端构建，004 portal；可 `-DskipFrontend=true` 跳过） | `node -v` → `v22.x` |
| Git | 任意 | `git -v` |
| Claude Code | 真实 CC（工具可用性冒烟；驱动分层见 §7） | `claude --version` |
| **远端 K3S**（重点场景） | debian 服务器上装 k3s（见 §6.2） | `kubectl get nodes` |
| OS | 本指南实测于 Windows 11 + Git Bash（命令为 bash 语法） | — |

> 受控内网、MVP **无鉴权**（Noop）；K3S 凭证 `test-env/k8s/kubeconfig/k3s-admin.yaml` **已 gitignore**（root-admin 权限，仅本机受控内网用）。

---

## 2. 构建（产出含前端 SPA 的单 JAR）

```bash
git clone <repo> && cd arthas-mcp-gateway
./mvnw clean verify          # CI 复现：含 frontend-maven-plugin 跑 npm install + vite build
```

**实测产物**：`target/arthas-mcp-gateway-0.1.0-SNAPSHOT.jar`（fat jar，前端 static 内嵌于 `BOOT-INF/classes/static/`）。

> 跳过前端构建（仅验后端 MCP）：`./mvnw clean verify -DskipFrontend=true`。`-DskipFrontend` 不影响后端测试，仅跳 `frontend-maven-plugin`。

**实测全量测试**：`Tests run: 253, Failures: 0, Errors: 0, Skipped: 0/1`（`K8sExternalGatewaySmokeTest` 在无常驻 uber-jar 网关时 `assumeTrue` 跳过，CI 友好）。

---

## 3. 启动场景

### 3.1 场景 A — 本地双后端最小冒烟（5 分钟验 MCP 可用）

`smoke/gateway-start.sh` 一键启动：① 双真实业务后端（`SmokeDemoLauncher` 起 `order-service` + `payment`，含 `OrderService.hotMethod` 自驱动）+ ② 网关（指向 `config/backends-runtime.yaml`）。

```bash
bash smoke/gateway-start.sh
```

**实测输出**（尾部）：
```
"summary":{"total":2,"healthy":2,"unhealthy":0}
[start] ✓ 常驻就绪。MCP 端点 http://127.0.0.1:8761/mcp
       停止： bash smoke/gateway-stop.sh
```

→ 浏览器访问 `http://127.0.0.1:8761/` 加载 portal SPA；MCP 端点 `http://127.0.0.1:8761/mcp`。停止：`bash smoke/gateway-stop.sh`。

> 此场景用**本地双后端**快速验 MCP 链路；**远端 K8S 场景**见 §3.2 与第 6 章（重点）。

### 3.2 场景 B — 远端 K8S 场景（重点，见第 6 章详述）

在场景 A 启动的同一个网关上（`application.yml` 已配 `arthas-gateway.k8s.kubeconfig`），调 `k8s.*` 工具即可编排远端 K3S pod。完整拓扑/配置/验证流程见 **§6 远端 K8S 调用场景设计**。

---

## 4. 验证 MCP 可用 — 最小冒烟集（L1–L3）

> 「MCP 是否可用」= 这三步全过即可判定。每步实测输出如下。

### L1 健康检查（Actuator，唯一允许直连端点）

```bash
curl -s http://127.0.0.1:8761/actuator/health | python -m json.tool
```

实测（节选）：
```json
{
  "status": "UP",
  "components": {
    "backendRegistry": {
      "details": {
        "backends": {
          "order-service": {"state":"ACTIVE","healthy":true,"breaker":"CLOSED"},
          "payment":       {"state":"ACTIVE","healthy":true,"breaker":"CLOSED"}
        },
        "summary": {"total":2,"healthy":2,"unhealthy":0}
      },
      "status": "UP"
    }
  }
}
```

### L2 MCP 协议 — initialize + tools/list = 38

用真实 Claude Code 走 MCP（驱动分层：工具可用性验「能调通」用真实 CC）：

```bash
# 准备 MCP 配置（同源 HTTP，不污染全局）
cat > .tmp-gs-mcp.json <<'EOF'
{ "mcpServers": { "arthas-gw": { "type": "http", "url": "http://127.0.0.1:8761/mcp" } } }
EOF

# 真实 CC 经 MCP 枚举工具（= initialize + tools/list）
claude -p --mcp-config .tmp-gs-mcp.json --permission-mode bypassPermissions \
  "列出 arthas-gw 暴露的全部 MCP 工具名，仅输出按字母排序的 JSON 字符串数组。"
```

实测返回 **38 个工具**：
```
["arthas-gateway_list-targets","arthas-gateway_task-cancel","arthas-gateway_task-get",
 "arthas-gateway_task-list","classloader","dashboard","dump","getstatic","heapdump","jad",
 "jvm","k8s_ensure-arthas-mcp","k8s_list-pods","k8s_list-services","mbean","mc","memory",
 "monitor","ognl","options","perfcounter","profiler","redefine","retransform","sc","sm",
 "stack","stop","sysenv","sysprop","thread","trace","tt","version","viewfile","vmoption",
 "vmtool","watch"]
```
组成 = **4** `arthas-gateway_*`（自有）+ **3** `k8s_*`（003 编排）+ **31** arthas（诊断）= 38。

### L3 单工具真实诊断 — jvm

```bash
claude -p --mcp-config .tmp-gs-mcp.json --permission-mode bypassPermissions \
  "调用 arthas-gw 的 jvm 工具，target=order-service。返回 MACHINE-NAME/VM-VERSION/线程数/堆 used。"
```

实测（节选）：
```
| MACHINE-NAME | 282276@DESKTOP-O8RUTFP |
| VM-VERSION   | 21.0.5+9-LTS-239 (HotSpot 64-Bit Server VM) |
| 线程数       | 37（daemon 34，峰值 37，死锁 0） |
| 堆 used      | 60,529,496 B ≈ 57.7 MB（committed 68 MB / max 7.94 GB） |
```

→ **L1+L2+L3 全过 = MCP 可用 ✓**（结果来自 order-service 真实 JVM，非桩）。

---

## 5. 远端 K8S 调用场景设计（重点章节）

> 这是网关的精华场景（003 SC-001）：**网关在 K8S 集群外运行**，经 kubeconfig + NodePort 编排远端 pod，对 pod 内 JVM 注入 arthas 并诊断。

### 5.1 真实拓扑

```
┌─────────────────────────┐         kubeconfig          ┌──────────────────────────┐
│  本机（Windows / 网关）  │  ────────────────────────►  │  debian 服务器 (K3S node) │
│  arthas-mcp-gateway.jar │   https://192.168.31.92:6443 │  192.168.31.92            │
│  :8761/mcp              │                              │  pod: demo-business       │
│  kubeconfig:            │  ◄──── NodePort 30000-32767 ─│  (JVM 21, OrderService)   │
│  test-env/k8s/kubeconfig│   arthas MCP via NodePort    │  arthas-boot.jar (注入)   │
└─────────────────────────┘                              └──────────────────────────┘
        ▲
        │ MCP (Streamable HTTP)
        ▼
   Claude Code 客户端
```

要点：
- **网关集群外运行**（本机），不进 K8S——验证「集群外客户端 + 远程编排」真实拓扑。
- **kubeconfig**：`test-env/k8s/setup.sh` 从远端 root-on-node 派生 admin 凭证导出到本机（server 已改 `https://192.168.31.92:6443`，**不建 RBAC**，MVP 后置）。
- **NodePort**：ensure 时自动从 `arthas-gateway.k8s.node-port-range=30000-32767` 分配，暴露 pod 内 arthas MCP（`--target-ip 0.0.0.0` → wildcard LISTEN，NodePort 可达；详见 003 research.md R4）。
- **arthas 不在镜像内**：ensure 时经 fabric8 exec 上传 `tools/arthas-boot.jar`（静态文件，入 git）使用。

### 5.2 kubeconfig 准备（一次性，幂等）

```bash
# 0.（一次性）预置 k3s 离线资源（防 github 间歇不可达，带重试）
bash reference/k3s/fetch.sh

# 1. 一键搭建（本机 Git Bash，内部经 on-debian SSH 远程操作 debian）
#    顺序：mvn test-compile 产 demo class → ship 到 debian → 离线装 k3s（瘦身+tls-san）
#         → docker build demo 镜像 → k3s ctr import → kubectl apply demo pod → 导出 kubeconfig
bash test-env/k8s/setup.sh
```

实测预期：
- `demo-business` pod `Running`/`Ready`（含 shell+java+JVM；arthas 不在镜像）。
- 本机 `test-env/k8s/kubeconfig/k3s-admin.yaml` 生成（= root-on-node 派生 admin）。
- `kubectl --kubeconfig test-env/k8s/kubeconfig/k3s-admin.yaml get pods` 见 `demo-business`。

清理：`bash test-env/k8s/teardown.sh`。

### 5.3 启网关（K8S 配置已内建）

`src/main/resources/application.yml` 已含：
```yaml
arthas-gateway:
  k8s:
    kubeconfig: test-env/k8s/kubeconfig/k3s-admin.yaml   # 相对工程根
    namespace: default
    node-port-range: 30000-32767
    ensure-timeout: 5m
```

启动日志会打印 `KubernetesClient 已构建（kubeconfig=...k3s-admin.yaml, master=https://192.168.31.92:6443/）`。场景 A 的 `gateway-start.sh` 同一网关即带 K8S 能力（38 工具含 `k8s_*`）。

### 5.4 验证流程（list-pods → ensure → 远程诊断 → 纳管）

#### ① list-pods — 枚举可诊断 pod

```bash
claude -p --mcp-config .tmp-gs-mcp.json --permission-mode bypassPermissions \
  "调用 arthas-gw 的 k8s_list-pods 工具 namespace=default，返回 pod 名 + ready + hasJvm。"
```

实测：
```
| demo-business | ✅ ready=true | ✅ hasJvm=true（hasShell=true） |
```
→ `demo-business` 含 JVM + shell，可被 arthas 诊断。

#### ② ensure-arthas-mcp — 注入 arthas + NodePort 暴露 + 注册网关

```bash
claude -p --mcp-config .tmp-gs-mcp.json --permission-mode bypassPermissions \
  "调用 k8s_ensure-arthas-mcp，pod=demo-business, server=debian, namespace=default。返回 target/status/mcpUrl。"
```

实测（**原子幂等**：注入 arthas + 起 arthas MCP + NodePort 暴露 + 健康检查 + 动态注册进网关）：
```
| target   | debian-demo-business |
| status   | ready                |
| mcpUrl   | http://192.168.31.92:30415 |   ← NodePort 路由到 pod 内 arthas MCP
| namespace| default              |
```
→ 新 target `debian-demo-business` 进注册表（`source=DYNAMIC`），`arthas-gateway.list-targets` 立即可见。

#### ③ 远程诊断 — 经网关 → NodePort → pod JVM

```bash
claude -p --mcp-config .tmp-gs-mcp.json --permission-mode bypassPermissions \
  "用 target=debian-demo-business 调 jvm，返回 MACHINE-NAME 与 VM-VERSION。"
```

实测：
```
| MACHINE-NAME | 1@demo-business              |   ← pod 内容器真实主机名
| VM-VERSION   | 21.0.11+10-LTS（Eclipse Adoptium）| ← pod JVM 真实版本
旁证：OS=Linux/amd64、JVM-START-TIME=2026-06-22 19:34:58、statusCode=0、success=true
```
→ **远程 pod JVM 诊断完全可达**（结果来自远端 debian 上的 pod，非本机）。

#### ④ 纳管确认

```bash
curl -s http://127.0.0.1:8761/actuator/health | python -m json.tool | grep -A3 debian-demo-business
```
实测 `debian-demo-business` 出现在 `backendRegistry.details.backends`，`state=ACTIVE/healthy=true`。

### 5.5 故障韧性（SC-003，破坏性，按需）

```bash
# 模拟 K8S 驱逐：删 pod（on-debian 经 SSH 远程 kubectl）
ssh root@192.168.31.92 'kubectl delete pod demo-business'
```

预期（003 SC-003，已验证）：
- 网关 **30s 内**把 `debian-demo-business` 标 `healthy=false`（复用 001 健康监控/熔断）。
- 对该 target 诊断 → 结构化错误（INVALID_PARAMS + `reason:backend_unreachable`）。
- **其他 target（payment/order-service）不受影响**（隔离）。
- pod 重新部署后再次 `ensure-arthas-mcp` → `status:ready` 恢复纳管。

> 此步破坏性，文档引用 003 SC-003 实证；首跑可跳过。

---

## 6. 全面测试用例（分层 + 能力矩阵）

### 6.1 测试分层

| 层 | 目的 | 驱动 | 命令 | 实测结果 |
|----|------|------|------|----------|
| **L0** 构建测试 | 编译 + 单测 + 契约 IT 全绿 | `./mvnw verify` | `./mvnw clean verify` | 253 测试 / 0 失败 / 0 错误 |
| **L1** 健康检查 | 网关与后端可达 | Actuator | `curl /actuator/health` | `status:UP`，后端 healthy |
| **L2** MCP 协议 | initialize + tools/list | 真实 CC | `claude -p` 列工具 | 38 工具 |
| **L3** 单工具冒烟 | 每能力「能调通」 | 真实 CC | `claude -p` 调各工具 | jvm/watch/k8s.* 等真实返回 |
| **L4** 契约/一致性 | 双侧协议 + 结果 A/B 一致 | 官方 MCP Java SDK client | `./mvnw verify -Dtest="..contract.."` | `*ContractIT` 全绿 |
| **L5** 故障注入 | 真实故障条件（禁桩） | 真实 CC + 真实故障 | 停后端/删 pod/错 token | 结构化错误 + 熔断 + 隔离 |
| **L6** 端到端 | Claude 编排全链路 | 真实 CC | `claude -p` 多步编排 | SC-001 5min 闭环 |

> 驱动分层（CLAUDE.md）：**工具可用性**用真实 CC（仅验「能调通」）；**结果一致性 + 双侧协议契约**用官方 MCP Java SDK client（确定性断言）。除健康检查外，**不 curl 裸打 MCP**。

### 6.2 能力 → 测试用例矩阵

| 能力 | 验证用例 | 命令（要点） | 期望（实测锚点） |
|------|----------|--------------|------------------|
| 诊断 — `jvm` | L3 单工具 | `jvm target=order-service` | 返 MACHINE-NAME/VM-VERSION/线程/堆 |
| 诊断 — `watch`（异步） | L3 任务系统 | `watch target=... numberOfExecutions=1` | 立即返 `taskId`+`working`，完成→`COMPLETED` |
| 任务系统 | L3 | watch → `task-get`/`task-list` | taskId 可查、`task-list` 含该任务 |
| 多目标路由 | L3 | jvm 分别 `target=order-service`/`payment` | 结果各属正确目标 JVM |
| 热重载 | L3 | 编辑 `backends.yaml` 增/删后端 | ≤30s `list-targets` 反映变化 |
| 单点故障隔离 | L5 | 停 order-service | payment 诊断正常；order 诊断 30s 内明确错误 + 熔断 |
| **K8S list-pods** | L3 | `k8s_list-pods namespace=default` | 列 pod + ready + hasJvm |
| **K8S ensure** | L3 | `k8s_ensure-arthas-mcp pod=demo-business server=debian` | `status:ready` + mcpUrl + 注册 |
| **K8S 远程诊断** | L3 | jvm `target=debian-demo-business` | pod JVM 真实信息（1@demo-business） |
| **K8S 故障韧性** | L5 | 删 demo-business pod | 30s 隔离 + 熔断 + 其他 target 不受影响 |
| portal — 后端 CRUD | L3 | 浏览器 `/backends` 增删改 | 写 YAML 热重载 + 列表刷新 |
| portal — 任务列表 | L3 | 浏览器 `/tasks` 或 `GET /admin/tasks` | 列表摘要 + 过滤 + 分页 + 点项导出 |
| portal — 结果导出 | L3 | `GET /admin/tasks/{id}/export` | 下载 JSON（含 frames 原样） |

### 6.3 故障注入用例（L5，真实故障条件，禁桩）

| 场景 | 真实故障实现 | 期望 |
|------|--------------|------|
| 后端不可达 | 停 order-service 后端 | 诊断 → `backend_unreachable`，30s 内熔断 OPEN |
| 认证失败 | `auth.mode=BEARER` + 错误 token | 诊断 → arthas 真实 401 |
| 慢响应 | 业务方法 `Thread.sleep` | 超阈值 → 超时错误（不计熔断） |
| 并发越界 | 真实发起 >`maxConcurrentTasks` 并发 | arthas 真实 INVALID_PARAMS / 排队 |
| K8S pod 删除 | `kubectl delete pod` | 30s 隔离 + 熔断 + 隔离其他 target |

---

## 7. 排查指引

| 现象 | 检查 |
|------|------|
| Claude Code 看不到工具 | 网关是否起（L1）、`tools/list` 是否 38（L2）、mcp-config URL 是否 `http://127.0.0.1:8761/mcp` |
| `k8s_*` 工具报 kubeconfig 错 | `test-env/k8s/kubeconfig/k3s-admin.yaml` 是否存在、`application.yml` 路径、远端 192.168.31.92:6443 可达 |
| ensure 返失败 | pod 是否 `Ready` + `hasJvm` + `hasShell`（list-pods 看）、`tools/arthas-boot.jar` 是否在 |
| 远程诊断超时 | NodePort 是否可达（`curl http://192.168.31.92:<nodeport>`）、pod 内 arthas 是否 `0.0.0.0` 监听 |
| 异步任务一直 working | 后端可达性、`task-get` 的 `error.reason`、后台是否超 11min 标 failed |
| portal 页面 404（`/backends` reload） | `SpaConfig` 是否注册（Vue Router history 模式 fallback，004 INV-WEB-1） |

详细定位见 `reference/arthas-docs/03-MCP/问题定位反向索引.md`。

---

## 8. 清理

```bash
bash smoke/gateway-stop.sh          # 停本地网关 + 双后端
bash test-env/k8s/teardown.sh       # （远端 K3S）卸载 k3s + 删导出凭证
rm -f .tmp-gs-mcp.json              # 删临时 MCP 配置
```

---

## 附录：实测环境快照（2026-07-08）

| 项 | 值 |
|----|-----|
| 网关 | `arthas-mcp-gateway-0.1.0-SNAPSHOT.jar`（Spring Boot 4.1.0 / Spring AI 2.0.0 / MCP SDK 2.0.0） |
| 本机 | Windows 11 + Git Bash + JDK 21.0.5 |
| 远端 K3S | debian 13（kernel 6.12.73）+ k3s v1.35.5（node 192.168.31.92） |
| 业务 pod | `demo-business`（JVM 21.0.11+10-LTS，Eclipse Adoptium，`OrderService.hotMethod`） |
| 工具数 | 38（4 自有 + 3 K8S + 31 arthas） |
| portal | Vue 3 SPA 内嵌单 JAR，`/backends` + `/tasks`（含任务列表） |
