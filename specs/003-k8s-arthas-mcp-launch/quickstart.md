# Quickstart：K8S 目标 arthas MCP 启动与纳管（P1 端到端验证）

**Feature**: 003-k8s-arthas-mcp-launch | **Date**: 2026-06-22

> 本文是 P1 能力的**端到端验证指南**（证明"对指定 K8S 目标 pod 启 arthas MCP + 暴露 + 纳管 + 经网关诊断"闭环跑通）。实现细节归 `tasks.md` 与实现阶段；本文只给**可运行的验证场景 + 预期结果**。
> 契约见 [contracts/k8s-orchestration-tools-contract.md](./contracts/k8s-orchestration-tools-contract.md)、[contracts/dynamic-registration-invariants.md](./contracts/dynamic-registration-invariants.md)；数据实体见 [data-model.md](./data-model.md)；环境/选型决策见 [research.md](./research.md)。

---

## 0. 前置环境

| 项 | 要求 | 说明 |
|---|---|---|
| **K8S 集群** | debian-docker 服务器（192.168.31.92）上装 **k3s**（[research.md R3](./research.md)） | 一键 `bash test-env/k8s/setup.sh`（幂等：离线装 k3s + build 镜像 + import + apply + 导出 kubeconfig）；node IP = 192.168.31.92 |
| **kubeconfig** | root-on-node 派生的 admin kubeconfig，本机供网关读取 | 落 `test-env/k8s/kubeconfig/k3s-admin.yaml`（server 已改 `https://192.168.31.92:6443`）；**不建 RBAC**（K-ENS-6 后置） |
| **业务 pod 镜像** | 001 夹具 `DemoBusinessApp` 打成容器镜像（**纯 app，无 arthas**），入 k3s | Dockerfile + 在 debian 上 build 由 setup.sh 完成；含 `OrderService.hotMethod`（[data-model §7](./data-model.md)、[research.md R7](./research.md)） |
| **网关** | 本特性构建的 `arthas-mcp-gateway`（38 工具）| 开发期在本机 Windows 运行、经 kubeconfig + NodePort 远程连 k3s（验证"网关集群外运行"） |
| **Claude Code** | 真实 Claude Code（`claude -p --mcp-config`）做可用性冒烟 | 工具可用性走真实 CC；一致性/双侧契约走官方 MCP Java SDK client（CLAUDE.md 驱动分层） |

> 本机 Windows 无 Docker（memory），故 k3s 跑在 debian；网关集群外运行 + 远程 kubeconfig/NodePort 正是 SC-001 真实拓扑。

---

## 1. 场景 A：波次 A 纯逻辑（无 K8S，CI 可跑）

验证动态注册层的正确性地基（先于真实供给）。

```bash
mvn -pl . test -Dtest='DynamicBackendStoreTest,RegistryComposerTest,SourceParsingTest,OrchestrationRecordTest'
```

**预期**（断言见 [dynamic-registration-invariants.md §5](./contracts/dynamic-registration-invariants.md)）：
- `register`/`unregister` 正确、命名冲突拒绝（D-REG-2/4）。
- 动态 target 存在时模拟静态热重载 → 动态 target **仍在**（D-COEXIST-1，关键）。
- compose 前后 in-flight 调用持有的 Entry 引用不变（D-ATOMIC-1）。

> 这一层不依赖 k3s/arthas，CI 默认跑（与 001 surefire 同范式）。

---

## 2. 场景 B：波次 B 真实 K8S 夹具（需 k3s @ debian，本地手跑）

### 2.1 备好集群与镜像（一次性，幂等）

完整方案见 [K8S 测试环境设计](../../docs/superpowers/specs/2026-06-23-k8s-test-env-setup-design.md)；此处为验证指南的一键入口。

```bash
# 0.（一次性）预置 k3s 离线资源到 reference/k3s/（防 github 间歇不可达；带重试）
bash reference/k3s/fetch.sh

# 1. 一键幂等搭建（本机 Git Bash 运行；内部经 on-debian 远程操作 debian）
#    顺序：mvn test-compile 产 demo class → ship 到 debian → 离线装 k3s（瘦身 + tls-san）
#         → docker build demo 镜像 → k3s ctr import → kubectl apply demo pod → 导出 root 派生 admin kubeconfig
bash test-env/k8s/setup.sh
```

**预期**：
- `demo-business` pod `Running`/`Ready`（含 shell+java+JVM；**arthas 不在镜像内**，ensure 时经 fabric8 exec 上传 `tools/arthas-boot.jar` 使用——与 001 夹具设计 §5 一致）。
- 本机 `test-env/k8s/kubeconfig/k3s-admin.yaml` 生成（= root-on-node 派生 admin，server 已改 `https://192.168.31.92:6443`）。
- `kubectl --kubeconfig test-env/k8s/kubeconfig/k3s-admin.yaml get pods` 见 `demo-business`。

> 故障用例（无 JVM / 无 shell / 无 exec）的 pod 按 TDD 进度逐步补（设计 §1.2），非本一次性步骤。

**清理**：`bash test-env/k8s/teardown.sh`（debian 上 k3s-uninstall + 删导出凭证）。

### 2.2 启网关（38 工具）

```bash
# 配置网关读 kubeconfig（application.yml: arthas-gateway.k8s.kubeconfig）
./smoke/gateway-start.sh
# 或 mvn spring-boot:run
```

确认 `tools/list` 含 **38** 工具（35 既有 + `k8s.list-pods`/`k8s.list-services`/`k8s.ensure-arthas-mcp`）。

### 2.3 跑真实供给契约测试

```bash
# 需 k3s 环境；CI 默认 Assume 跳过，本地手跑
mvn -pl . verify -Dit.test='K8sEnsureContractIT,ArthasProvisionerIT' -DfailIfNoTests=false
```

**预期**（断言见 [k8s-orchestration-tools-contract.md §5](./contracts/k8s-orchestration-tools-contract.md)）：
- K-ENS-1：对含 JVM 的真实 pod `ensure` → `status:ready` + 可达 `mcpUrl` + target 进注册表（`list-targets` 可见、source=DYNAMIC）。
- K-ENS-2：重复 `ensure` → `status:reused`、零副作用。
- K-ENS-3：用返回 target 调 watch/trace → 经网关捕获**该 pod JVM** 的真实诊断。
- K-ENS-4/5/6/7：真实故障条件（无 JVM / 无 shell / 无 exec 权限 / 绑 loopback）→ 结构化错误，且未注册。

> **R4（已解决，2026-06-22）**：arthas `--target-ip 0.0.0.0` 经 NodePort 的可达性已由**源码证据链 + 本机 A/B 实证双重确认**（[research.md R4](./research.md)）：`0.0.0.0` → netstat `0.0.0.0:<port> LISTENING`（wildcard，可达）；`127.0.0.1` → `127.0.0.1:<port> LISTENING`（loopback，不可达）。故 K-ENS-7 在此基础上转为**回归守护**（锁定 loopback 经 NodePort 不可达），非风险首测；**无需 socat/iptables 降级方案**。

---

## 3. 场景 C：端到端（真实 Claude Code 编排）

人提供服务器名 + kubeconfig；Claude 编排枚举→供给→诊断。

```bash
# MCP 配置指向本网关（/mcp 端点），真实 Claude Code 走 MCP
claude -p --mcp-config .mcp.json "枚举 default 命名空间的 pod，选含 JVM 的目标，"
     "启动 arthas MCP 并纳管，然后 watch 该 pod 的 OrderService.hotMethod"
```

**预期**（SC-001）：
1. Claude 调 `k8s.list-pods` → 选定含 JVM 的 demo pod。
2. Claude 调 `k8s.ensure-arthas-mcp(server=<服务器名>, pod=<demo pod>)` → 返 `{target, status:ready}`。
3. Claude 用返回 `target` 调 `watch` → 经网关捕获该 pod JVM 的 `hotMethod` 真实调用。
4. 全程"启动 + 暴露 + 纳管 + 使用"在 **5 分钟内**完成（SC-001）。

> 可用性冒烟走真实 CC（仅验"能调通"）；结果一致性 + 双侧协议契约由官方 MCP Java SDK client 驱动的 `*ContractIT` 确定性断言（CLAUDE.md 驱动分层）。

---

## 4. 故障韧性验证（SC-003）

```bash
# ensure 成功纳管后，删除目标 pod（模拟 K8S 驱逐/重启）
on-debian 'kubectl delete pod <demo-pod>'
```

**预期**（SC-003、K-COEXIST-2）：
- 网关在 **30 秒内**把该 target 标 unhealthy（复用 001 健康监控/熔断）。
- 对该 target 诊断 → 明确错误（INVALID_PARAMS + `reason:backend_unreachable`）。
- **其他 target 不受影响**。
- pod 恢复（重新部署）→ 重新 `ensure` 可恢复纳管（重供给，status:ready）。

---

## 5. 回归对照（不得回归 001/002）

```bash
mvn -pl . verify
```

**预期**：既有 35 工具 tools/list、双侧契约（`InitializeAndToolsListContractTest`/`GatewayToolsContractTest`/`ToolsCallRoutingContractIT` 等）、热重载（`HotReloadIT`）、异步任务全部继续通过。动态注册层的 D-COEXIST-* 守护"热重载不误删动态 target"不破坏既有热重载语义。

---

## 6. 验证清单（Done Definition）

- [X] 场景 A（波次 A 纯逻辑）全绿，CI 可跑。
- [X] 场景 B（波次 B 真实夹具）本地 k3s 全绿，含 K-ENS-7 回归守护（`0.0.0.0` 经 NodePort 可达 / loopback 不可达，R4 已先期实证）。
- [X] 场景 C（端到端）真实 Claude Code 编排枚举+供给+诊断，结果来自指定 pod JVM，5 分钟内（SC-001）。
- [X] 故障韧性 SC-003：pod 删除 → 30 秒内隔离、明确错误、不影响其他 target。
- [X] 回归：001/002 既有测试全绿，38 工具 tools/list。
- [X] tools/list = 38（35 + 3），`listChanged=false` 不变。
- [X] gateway-core 包零 K8S 依赖（编排工具不经路由器，[research.md R6](./research.md)）。
