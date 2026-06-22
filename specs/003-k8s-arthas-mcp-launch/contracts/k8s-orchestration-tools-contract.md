# 契约：K8S 编排工具（k8s.list-pods / k8s.list-services / k8s.ensure-arthas-mcp）

**Feature**: 003-k8s-arthas-mcp-launch | **Date**: 2026-06-22
**界面角色**：网关对 Claude Code 暴露的 **3 个 K8S 编排工具**（与既有 31 arthas + 4 网关自有共 38 工具同处单 MCP 端点），`routingMode=GATEWAY_LOCAL`（无 target 参数），**handler 自带闭包、不经 `ToolsCallRouter`**（gateway-core 路由零 K8S 感知，[research.md R6](../research.md)）。
**宪法依据**：原则二（编排工具为独立 MCP 面、不污染聚合落点）、原则四（双侧契约）、原则五（错误显式传播）、原则六（K8S 操作走 fabric8 Java API）。
**设计依据**：[设计 §4](../../../docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md)。数据实体见 [data-model §7/§8/§9/§10](../data-model.md)。

> 这 3 个工具对 Claude Code 是**普通 MCP 工具**，走官方 SDK 的标准 `tools/call`。它们**不**转发到 arthas 后端（不诊断），属编排面。`ensure-arthas-mcp` 产出的 target 名供后续**既有**诊断工具的 `target` 参数使用。

---

## 1. `k8s.list-pods`（枚举集群 pod）

**用途**：枚举指定 namespace 的 pod，供 Claude 据上下文推断目标 pod（north-star）/ 供人据清单选定目标 pod（P1）。

**inputSchema**：
```jsonc
{
  "type": "object",
  "properties": {
    "namespace": { "type": "string", "description": "K8S 命名空间，缺省 default" }
  },
  "additionalProperties": false
}
```

**返回**（TextContent 为 JSON）：
```jsonc
{
  "pods": [
    { "name": "order-service-abc", "namespace": "default", "ready": true,
      "hasJvm": true,   // 含可被 arthas attach 的 JVM（探测 java 进程；非保证）
      "hasShell": true  // 含 shell（exec 注入前提）
    }
  ],
  "namespace": "default"
}
```

- `hasJvm`/`hasShell` 标记帮助 Claude/人过滤"可诊断"pod（设计 §4：目标须 shell+java+JVM）。
- K8S API 不可达 / RBAC 不足 → INVALID_PARAMS + `data.reason` ∈ {`k8s_unreachable`, `k8s_forbidden`}（真实错误显式传播，原则五）。

---

## 2. `k8s.list-services`（枚举集群 service）

**用途**：枚举指定 namespace 的 service。

**inputSchema**：
```jsonc
{
  "type": "object",
  "properties": {
    "namespace": { "type": "string", "description": "K8S 命名空间，缺省 default" }
  },
  "additionalProperties": false
}
```

**返回**（TextContent 为 JSON）：
```jsonc
{
  "services": [
    { "name": "order-svc", "namespace": "default", "type": "ClusterIP",
      "clusterIp": "10.96.x.x", "ports": [{"port": 8080, "nodePort": null}] }
  ],
  "namespace": "default"
}
```

---

## 3. `k8s.ensure-arthas-mcp`（幂等供给黑盒，核心）

**用途**：对指定目标 pod **原子幂等**完成：注入 arthas（exec 进 pod、attach 该 pod JVM PID）→ 启动绑 `0.0.0.0` 的 arthas MCP → label pod + 建 NodePort Service 暴露 → 内部健康检查 → 动态注册进网关。对 Claude 是**单步机械黑盒**（无 LLM 决策点，故合并为 1 工具，设计 §4.2）。

**inputSchema**：
```jsonc
{
  "type": "object",
  "properties": {
    "server":    { "type": "string", "description": "Linux 服务器名（逻辑名前缀 + 来源标识）" },
    "pod":       { "type": "string", "description": "目标 pod 名（须含 shell+java+JVM）" },
    "namespace": { "type": "string", "description": "K8S 命名空间，缺省 default" }
  },
  "required": ["server", "pod"],
  "additionalProperties": false
}
```

**行为（幂等、原子）**：
1. 派生 `logicalName = "{server}-{pod}"`。
2. 查 `DynamicBackendStore`：已有且后端健康 → **零副作用复用**，返回该 target（status=reused）。
3. 否则执行供给（ensuring）：
   - fabric8 exec 进 pod：定位 JVM PID（无 JVM → failed）；上传/获取 `arthas-boot.jar`（无 shell/传输失败 → failed）。
   - exec 启动：`java -jar /tmp/arthas-boot.jar <pid> --attach-only --http-port <mcpPort> --target-ip 0.0.0.0 --use-version 4.3.0`（attach 超时/端口占用 → failed）。
   - label pod（`arthas-mcp-gateway/target=<logicalName>`）+ create NodePort Service（selector 匹配该 label、port→mcpPort）；可达 URL = `http://<nodeIP>:<nodePort>`（根 URL，无 `/mcp`）。
   - 内部健康检查：轮询 `<mcpUrl>` 的 initialize/listTools 确认 arthas MCP 就绪（超时未就绪 → failed）。
   - `DynamicBackendStore.register(BackendConfig{source=DYNAMIC, name=logicalName, url=mcpUrl, ...})` + `RegistryComposer` 原子 swap effective registry。
4. 任一子步失败 → **不注册**（status=failed），返回结构化错误。
5. 全部成功 → status=ready。

**返回**（TextContent 为 JSON）：
```jsonc
// ready / reused
{ "target": "prod-order-server-order-service-abc",
  "status": "ready",            // ready（新供给完成）| reused（幂等复用）
  "mcpUrl": "http://192.168.31.92:31234",   // 根 URL，无 /mcp
  "namespace": "default" }
```
```jsonc
// failed（INVALID_PARAMS + data）
{ "target": "prod-order-server-order-service-abc",
  "status": "failed",
  "error": {
    "reason": "no_jvm" | "no_shell" | "attach_failed" | "health_check_timeout"
            | "nodeport_alloc_failed" | "name_conflict" | "k8s_unreachable" | "k8s_forbidden",
    "stage": "locate_jvm" | "install_arthas" | "start_arthas" | "expose_nodeport"
           | "health_check" | "register",
    "message": "..."
  } }
```

**幂等细则**（设计 §4.1）：
- 注册表有 `{server}-{pod}` 且后端健康 → 复用，零副作用。
- 注册表有但后端已死（pod 重启/arthas 挂） → 重供给（重装/重 attach/重暴露），更新条目（status=ready）。
- **原子性**：任一子步失败 → 失败、**不注册**（不污染注册表）。已打的 label/已建的 Service 记录于 `OrchestrationRecord.error` 供清理。

**成功后续**：Claude 用返回的 `target` 调**既有**诊断工具（watch/trace/sc/...）——经 `gateway-core` 既有路由管线，结果原样透传（宪法原则二）。

---

## 4. 端到端时序（Claude 编排，P1 人指定 pod）

```text
人提供：kubeconfig（指向 k3s @ debian）+ 服务器名
  │
[1] Claude ──k8s.list-pods──► 网关 ──► pod 清单（hasJvm/hasShell 过滤可诊断 pod）
[2] 人/Claude 选定目标 pod
[3] Claude ──k8s.ensure-arthas-mcp(server, pod)──► 网关
       └─ 原子幂等供给黑盒（注入+暴露+健康+注册）──► { target, status:"ready" }
[4] Claude ──watch(target={server}-{pod}, ...)──► 网关 ──► 经 gateway-core 路由 ──► 该 pod JVM
```

系统只暴露 [1]/[2 的工具能力] + 既有诊断；**[1]→[3]→[4] 的串联由 Claude 完成**，系统不内建过程式编排（设计 §五）。

---

## 5. 契约测试断言点（编排工具 · 官方 SDK client 驱动 + 真实 k3s/业务 pod，零桩）

| ID | 断言 |
|---|---|
| K-LP-1 | `k8s.list-pods` 返回真实集群 pod 清单（含 hasJvm/hasShell 标记）；namespace 过滤生效 |
| K-LS-1 | `k8s.list-services` 返回真实集群 service 清单 |
| K-ENS-1 | `ensure-arthas-mcp` 对含 JVM 的真实 pod：返回 `status:ready` + 可达 `mcpUrl` + 该 target 进注册表（`list-targets` 可见、source=DYNAMIC） |
| K-ENS-2 | 对 ready target 重复 `ensure` → `status:reused`、零副作用（pod 未重装、Service 未重建） |
| K-ENS-3 | `ensure` 后用返回的 target 调 `watch`/`trace` → 经网关捕获**该 pod JVM** 的真实诊断（与直连 arthas 一致，原样透传） |
| K-ENS-4 | 对**无 JVM** 的 pod `ensure` → INVALID_PARAMS + `reason:no_jvm` + `stage:locate_jvm`，且**未注册**（注册表不含该名） |
| K-ENS-5 | 对**无 shell** 的 pod `ensure` → INVALID_PARAMS + `reason:no_shell`（真实故障条件，非桩） |
| K-ENS-6 | kubeconfig 无 exec 权限 → INVALID_PARAMS + `reason:k8s_forbidden`（真实 403，非桩） |
| K-ENS-7 | arthas MCP 绑 loopback（`--target-ip 127.0.0.1`）→ NodePort 不可达、健康检查超时 → `reason:health_check_timeout`（验证 `0.0.0.0` 要求，[research.md R4](../research.md)） |
| K-ENS-8 | `ensure` 期间 `target` 命名 = `{server}-{pod}`（确定性派生） |
| K-ENS-9 | 动态 target 与静态种子同名 → `reason:name_conflict`（拒绝，保护静态） |
| K-ATOMIC-1 | `ensure` 任一子步失败 → 注册表**不含**该 target（不半注册） |
| K-COEXIST-1 | 动态 target 纳管后，`backends.yaml` 热重载 → 动态 target **仍在**（热重载不误删，[research.md R8](../research.md)） |
| K-COEXIST-2 | 动态 target 不可达（pod 删除）→ `list-targets` 标 unhealthy、对其诊断返明确错误；其他 target 不受影响（复用 001 故障隔离） |

> 全真实环境（真实 k3s @ debian + 真实业务 pod 容器镜像 + 真实 arthas 注入，**零桩**）；可用性冒烟另由真实 Claude Code（`claude -p --mcp-config`）驱动。断言先于实现（TDD，宪法原则七）。波次 B 的 `K8sEnsureContractIT`/`ArthasProvisionerIT` 覆盖 K-ENS-*；波次 A 的 `DynamicBackendStoreTest`/`RegistryComposerTest` 覆盖 K-ENS-9/K-COEXIST-1。
