# Part 7 · 38 工具逐个详解

> 本部分对网关暴露的全部 38 个工具逐个详解：分类、参数 schema、用法、示例命令、返回示例、路由模式、故障行为。这是手册的「能力字典」——查任一工具的「怎么调、返回什么、怎么实现的」。

> **通用约定**：所有 arthas 工具（31 个）都注入了必填 `target` 参数（逻辑名，决定路由到哪个后端）；调用时必须提供，缺失返 `INVALID_PARAMS(-32602)`。`target` 在网关被剥离，**永不进 backendArgs**（避免后端 arthas 拒绝未知参数）。网关自有 4 工具与 K8S 3 工具不带 `target`。

---

## 第 51 章 网关自有工具（4 个，`arthas-gateway.*`，GATEWAY_LOCAL）

### 51.1 `arthas-gateway.list-targets`

- **分类**：网关自有 / GATEWAY_LOCAL。
- **参数**：无。
- **用途**：列出网关当前纳管的全部 target（静态种子 + 003 动态纳管），含状态/健康/协议 + 配置版本号。这是「发现可用目标」的入口（Claude 编排时先调它知有哪些 target 可诊断）。
- **实现**：`GatewayToolHandlers.listTargets`（`handler/GatewayToolHandlers.java:67-79`）。遍历 `registry.current().byName()` 投影，`healthy = entry.isHealthy()`（单一事实源）。
- **返回示例**：
```json
{
  "targets": [
    {"name":"order-service","state":"ACTIVE","healthy":true,"protocol":"STREAMABLE"},
    {"name":"payment","state":"ACTIVE","healthy":true,"protocol":"STREAMABLE"},
    {"name":"debian-demo-business","state":"ACTIVE","healthy":true,"protocol":"STREAMABLE"}
  ],
  "version": 3
}
```
- **故障行为**：无（恒返 200 + 当前列表）。熔断 OPEN 的 target 仍列出但 `healthy=false`（SC-003 仍可见但不可诊断）。

### 51.2 `arthas-gateway.task-get`

- **分类**：网关自有 / GATEWAY_LOCAL。
- **参数**：`taskId`（必填，string）。
- **用途**：查单个异步任务的状态 + 结果。异步工具（watch/trace/stack/tt/monitor）立即返 taskId，用此工具轮询/查询最终结果。
- **实现**：`GatewayToolHandlers.taskGet`（`:83-114`）。`store.get(taskId)` 缺失→INVALID_PARAMS；按 status 渲染。
- **返回示例（completed）**：
```json
{
  "taskId": "t-9d083c",
  "status": "completed",
  "toolName": "watch",
  "target": "order-service",
  "createdAt": "2026-07-08T...Z",
  "completedAt": "2026-07-08T...Z",
  "result": {
    "isError": false,
    "content": [{"type":"text","text":"{...arthas watch 结果 JSON...}"}]
  }
}
```
- **返回示例（working）**：`{taskId, status:"working", toolName, target, createdAt}`（无 result/completedAt）。
- **返回示例（failed）**：`{taskId, status:"failed", error:{reason:"backend_timeout", message:"..."}}`。
- **返回示例（cancelled）**：`{taskId, status:"cancelled"}`。
- **故障行为**：未知 taskId → `INVALID_PARAMS`；后端业务错误（isError=true）原样保留在 result（不转 failed，G-TG-2）。

### 51.3 `arthas-gateway.task-list`

- **分类**：网关自有 / GATEWAY_LOCAL。
- **参数**：`status`（可选，enum：WORKING/COMPLETED/FAILED/CANCELLED）。
- **用途**：列全部任务概要（按 status 过滤）。用于「我有哪些任务、状态如何」的概览。
- **实现**：`GatewayToolHandlers.taskList`（`:118-135`）。`store.list(status)` → 概要列表。
- **返回示例**：
```json
{
  "tasks": [
    {"taskId":"t-9d083c","status":"completed","toolName":"watch","target":"order-service","createdAt":"...","completedAt":"..."},
    {"taskId":"t-555daa","status":"completed","toolName":"watch","target":"order-service","createdAt":"...","completedAt":"..."}
  ]
}
```
- **故障行为**：无（恒返 200 + 列表，可能空）。

### 51.4 `arthas-gateway.task-cancel`

- **分类**：网关自有 / GATEWAY_LOCAL。
- **参数**：`taskId`（必填，string）。
- **用途**：取消一个 working 的异步任务（中断后台执行）。终态任务（completed/failed/cancelled）幂等返当前状态。
- **实现**：`GatewayToolHandlers.taskCancel`（`:139-147`）。`executor.cancel(task)` → `markCancelled` + 中断 supervisor → orchestrate 捕获 InterruptedException → worker.cancel(true) 中断后端 HTTP。
- **返回示例**：`{taskId, status:"cancelled"}`（或终态任务的当前状态）。
- **故障行为**：未知 taskId → `INVALID_PARAMS`；取消中断不计熔断（P1-3，避免 cancel 误开）。

---

## 第 52 章 arthas 异步长任务工具（5 个，ASYNC_TASK，每个带 `target`）

> 5 个长耗时工具后台化（虚拟线程），立即返 `taskId + status:working`，会话不阻塞。用 `task-get`/`task-list`/`task-cancel` 跟踪。

### 52.1 `watch`

- **参数**：`target`（必填）+ arthas watch 原生参数（`classPattern`/`methodPattern`/`express`/`conditionExpr`/`numberOfExecutions`/`timeout` 等）。
- **用途**：观察方法调用，命中后返回方法的 accessPoint/cost/returnValue/异常等。最常用的诊断工具（验证某方法是否被调用、返回什么）。
- **示例**：
```bash
claude -p --mcp-config .tmp.json "调用 watch，target=order-service, classPattern=com.arthas.gateway.testfixtures.OrderService, methodPattern=hotMethod, numberOfExecutions=1, timeout=30"
```
- **立即返回**：`{taskId:"t-9d083c", status:"working", _meta:{toolName:"watch", target:"order-service"}}`。
- **最终结果（task-get completed）**：含 arthas watch 命中的 `accessPoint`/`className`/`methodName`/`params`/`cost`/`return`/`ts`。
- **路由**：`ASYNC_TASK` → `submitAsync` → `entry.admit`（STATELESS 校验 + 熔断 + 取槽）→ `asyncExecutor.submit` → 后台 `entry.invoke` → `client.callTool("watch", backendArgs)` 阻塞等 arthas 命中。
- **故障行为**：
  - 后端不可达 → `recordFailure`（计入熔断）+ task 转 failed（`reason=backend_unreachable`）。
  - 后端业务错误（isError=true）→ `recordSuccess` + task completed（原样保留 isError）。
  - 兜底超时（11min）→ task failed（`reason=backend_timeout`）。
  - STATELESS 后端 → 前置 `StatelessAsyncException` → `stateless_unsupported_async`（不提交后台）。
  - per-target 并发越界 → `concurrency_limit`。
  - 全局背压越界 → `global_concurrency_limit`。
  - cancel → task cancelled（不计熔断）。

### 52.2 `trace`

- **参数**：`target` + `classPattern`/`methodPattern`/`conditionExpr`/`numberOfExecutions`/`skipJDKMethod`/`timeout` 等。
- **用途**：追踪方法内部调用链（每层子调用的耗时），定位性能瓶颈。
- **路由/故障**：同 watch（ASYNC_TASK）。
- **示例**：`trace target=order-service classPattern=com.example.Service methodPattern=doWork numberOfExecutions=1`。

### 52.3 `stack`

- **参数**：`target` + `classPattern`/`methodPattern`/`conditionExpr`/`numberOfExecutions`/`timeout` 等。
- **用途**：查看方法被调用时的调用栈（哪些路径调到了此方法）。
- **路由/故障**：同 watch（ASYNC_TASK）。

### 52.4 `tt`（TimeTunnel）

- **参数**：`target` + arthas tt 参数（`-t`/`-i`/`-p`/`--replay`/`classPattern`/`methodPattern` 等）。
- **用途**：时间隧道——记录方法每次调用的入参/出参，可回放（replay）。用于「重现某次调用」。
- **路由/故障**：同 watch（ASYNC_TASK）。

### 52.5 `monitor`

- **参数**：`target` + `classPattern`/`methodPattern`/`conditionExpr`/`-c <周期秒>` 等。
- **用途**：周期统计方法执行成功率/耗时（每 N 秒汇总），长期监控。
- **路由/故障**：同 watch（ASYNC_TASK）。

---

## 第 53 章 K8S 编排工具（3 个，`k8s.*`，自带闭包绕过 ToolsCallRouter）

### 53.1 `k8s.list-pods`

- **参数**：`namespace`（可选，缺省 `default`）。
- **用途**：列 K8S 集群某 namespace 的 pod，含 ready/hasJvm/hasShell 探测。这是 Claude 编排「发现可诊断 pod」的入口。
- **实现**：`K8sPodExplorer.listPods`（`orchestration/K8sPodExplorer.java:55-80`）。fabric8 列 pod + 对每个 Running pod exec `sh -c 'echo __SHELL_OK__; jps -q | head -1'` 探测。
- **示例**：
```bash
claude -p --mcp-config .tmp.json "调用 k8s.list-pods namespace=default"
```
- **返回示例**：
```json
{
  "pods": [
    {"name":"demo-business","namespace":"default","ready":true,"hasJvm":true,"hasShell":true}
  ],
  "namespace": "default"
}
```
- **故障行为**：
  - K8S API 不可达 → `INVALID_PARAMS + reason=k8s_unreachable`。
  - RBAC 403 → `reason=k8s_forbidden`。
  - 单 pod 探测超时（5s）→ 该 pod `hasShell/hasJvm=false`（不阻塞整列）。

### 53.2 `k8s.list-services`

- **参数**：`namespace`（可选，缺省 `default`）。
- **用途**：列 service（含 type/clusterIp/ports 含 NodePort）。辅助编排（看现有 NodePort）。
- **实现**：`K8sPodExplorer.listServices`（`:83-106`）。
- **返回示例**：
```json
{
  "services": [
    {"name":"arthas-mcp-debian-demo-business","namespace":"default","type":"NodePort","clusterIp":"10.43.x.x","ports":[{"port":8563,"nodePort":30415}]}
  ],
  "namespace": "default"
}
```
- **故障行为**：同 list-pods。

### 53.3 `k8s.ensure-arthas-mcp`（核心编排工具）

- **参数**：`server`（必填，Linux 服务器逻辑名，用于派生 target 前缀）、`pod`（必填，目标 pod 名）、`namespace`（可选，缺省 `default`）。
- **用途**：对指定 pod **原子幂等**完成「定位 JVM → 上传 arthas → 启动 arthas MCP（绑 0.0.0.0）→ NodePort 暴露 → MCP 健康检查 → 动态注册到网关」。一次调用即纳管。target 逻辑名 = `{server}-{pod}`（K-ENS-8 自动派生）。
- **实现**：`ArthasProvisioner.ensure`（`orchestration/ArthasProvisioner.java:145-172`）。详见 Part 4 §26。
- **示例**：
```bash
claude -p --mcp-config .tmp.json "调用 k8s.ensure-arthas-mcp server=debian pod=demo-business namespace=default"
```
- **返回（成功 ready）**：
```json
{"target":"debian-demo-business","status":"ready","mcpUrl":"http://192.168.31.92:30415","namespace":"default"}
```
- **返回（幂等 reused）**：`{target, status:"reused", mcpUrl, namespace}`（零副作用）。
- **返回（失败）**：`INVALID_PARAMS + data:{target, status:"failed", error:{reason, stage, message}}`。
- **故障 reason/stage 矩阵**：见 Part 4 §32.2（no_jvm/no_shell/attach_failed/health_check_timeout/nodeport_alloc_failed/name_conflict/k8s_forbidden/k8s_unreachable + locate_jvm/install_arthas/start_arthas/expose_nodeport/health_check/register）。
- **后续**：ensure ready 后，用 `target=<server>-<pod>` 调诊断工具（watch/jvm/...），结果来自该 pod JVM（K-ENS-3）。

---

## 第 54 章 arthas 即时诊断工具（26 个，SYNC_DIRECT + 1 STREAM_AGGREGATE，每个带 `target`）

> 即时工具同步转发，原样返 CallToolResult。下面按功能分组详解。

### 54.1 JVM 信息类

#### `jvm`
- **参数**：`target`（必填）。
- **用途**：返回目标 JVM 的完整运行时信息（RUNTIME/CLASS-LOADING/COMPILATION/GARBAGE-COLLECTORS/MEMORY-MANAGERS/MEMORY/OPERATING-SYSTEM/THREAD/FILE-DESCRIPTOR 等段）。最常用的「确认 target 连对了 + JVM 状态」工具。
- **示例**：`jvm target=order-service`。
- **返回示例（节选）**：
```json
{
  "resultCount": 2,
  "results": [{
    "jvmInfo": {
      "RUNTIME": [
        {"name":"MACHINE-NAME","value":"282276@DESKTOP-O8RUTFP"},
        {"name":"VM-VERSION","value":"21.0.5+9-LTS-239"},
        {"name":"INPUT-ARGUMENTS","value":["-Ddemo.slowMs=0"]},
        {"name":"CLASS-PATH","value":"...target\\test-classes"}
      ],
      "THREAD": [{"name":"COUNT","value":37},{"name":"DEADLOCK-COUNT","value":0}],
      "MEMORY": [{"name":"HEAP-MEMORY-USAGE","value":{"used":60529496,"committed":68091136}}],
      "OPERATING-SYSTEM": [{"name":"OS","value":"Windows 11"}]
    },
    "type":"jvm"
  }]
}
```
- **路由**：`SYNC_DIRECT` → `forwardSync` → `entry.execute`（熔断 + 取槽 + invoke + finally 释放槽）。
- **故障行为**：target 不可达 → `backend_unreachable`；熔断 OPEN → `backend_unreachable + retryAfterMs`；并发越界 → `concurrency_limit`；后端业务错误（isError）原样返。

#### `dashboard`（STREAM_AGGREGATE）
- **参数**：`target` + `i <刷新次数>`（可选）。
- **用途**：arthas 仪表盘（线程/内存/GC/Runtime 实时面板），SSE 多帧聚合。
- **路由**：`STREAM_AGGREGATE`（SDK 聚合多帧）。

#### `thread`
- **参数**：`target` + `id <线程ID>`/`-b`（找阻塞）/`--lockedMonitors`/`state <状态>` 等。
- **用途**：查看线程详情（CPU 占用/死锁检测/阻塞）。
- **示例**：`thread target=order-service -b`（找死锁）。

#### `memory`
- **参数**：`target` + 可选内存区名。
- **用途**：查看各内存区使用（heap/non-heap/code-cache/metaspace）。

#### `heapdump`
- **参数**：`target` + `--live`/`--file <路径>`。
- **用途**：dump 堆到文件（hprof）。生产慎用（大堆耗时长）。

### 54.2 类/方法查看类

#### `sc`（Search Class）
- **参数**：`target` + `classPattern`（必填）+ `-d`（详情）+ `-f <字段>`（查字段）等。
- **用途**：查类（含已加载的类、来源 jar）。
- **示例**：`sc target=order-service -d com.arthas.gateway.testfixtures.OrderService`。

#### `sm`（Search Method）
- **参数**：`target` + `classPattern`/`methodPattern` + `-d`。
- **用途**：查方法（签名/修饰符）。

#### `jad`（反编译）
- **参数**：`target` + `classPattern`/`--source <行>`/`--lineNumbers`。
- **用途**：反编译类（看实际加载的字节码对应的源码，定位「线上代码与本地不一致」）。

#### `dump`
- **参数**：`target` + `classPattern`/`-c <classloader hash>`。
- **用途**：dump class 的 byte（.class）到文件。

#### `classloader`
- **参数**：`target` + `-l`（列）/`-t`（树）/`-c <hash> <类名>`（用某 CL 加载）。
- **用途**：查类加载器层级 + 资源。

#### `mc`（Memory Compiler）
- **参数**：`target` + `-c <hash>`/`--classPath`/`-d <输出>`。
- **用途**：内存编译（动态编译 .java 到字节码，配合 redefine）。

### 54.3 运行时修改类

#### `ognl`（核心）
- **参数**：`target` + `express`（必填，OGNL 表达式）+ `-c <hash>`/`-x <深度>`。
- **用途**：执行 OGNL 表达式（查/改静态属性、调方法、new 对象）。arthas 最灵活的工具。
- **示例**：`ognl target=order-service -x 2 '@java.lang.System@currentTimeMillis()'`。
- **故障行为**：非法表达式 → 后端返 isError（业务错误，不计熔断，C-CB-2）。

#### `getstatic`
- **参数**：`target` + `classPattern` `field`（必填）+ `-x`。
- **用途**：查静态属性。

#### `vmoption`
- **参数**：`target` + `[name]`/`--new`/`--unset`。
- **用途**：查/改 VM 选项（如 GC 参数）。

#### `vmtool`
- **参数**：`target` + `--action <gc/...>`。
- **用途**：VM 工具（强制 GC、查 JVM 实例等）。

#### `sysprop`
- **参数**：`target` + `[name]`/`--new`。
- **用途**：查/改系统属性。

#### `sysenv`
- **参数**：`target` + `[name]`。
- **用途**：查环境变量。

#### `mbean`
- **参数**：`target` + `name`/`--attribute`。
- **用途**：查/操作 JMX MBean。

#### `perfcounter`
- **参数**：`target` + `-d`。
- **用途**：查 JVM 性能计数器。

### 54.4 字节码增强类

#### `redefine`
- **参数**：`target` + `-c <hash>`/`<class文件>`。
- **用途**：热重定义类（用新 .class 替换已加载的类，线上热修复）。**生产慎用**。

#### `retransform`
- **参数**：`target` + 类名。
- **用途**：retransform（回退 redefine 或重新转换）。

#### `profiler`
- **参数**：`target` + `--action start/stop`/`--duration <秒>`/`--format <格式>`。
- **用途**：async-profiler 集成（CPU/内存 profiling，生成火焰图）。

### 54.5 杂项

#### `version`
- **参数**：`target`。
- **用途**：arthas 版本（确认后端 arthas 版本）。

#### `viewfile`
- **参数**：`target` + `path`。
- **用途**：查看 pod/JVM 内文件。

#### `options`
- **参数**：`target` + `[name]`/`--new`。
- **用途**：查/改 arthas 全局选项（如 `json-format`/`unsafe`）。

#### `stop`
- **参数**：`target`。
- **用途**：销毁该 arthas 会话（reset 增强的类）。

---

## 第 55 章 工具调用通用模式

### 55.1 同步工具（jvm/thread/sc/jad/ognl/...）调用流

```
Claude: tools/call jvm {target:"order-service"}
  → ToolsCallRouter.route (SYNC_DIRECT)
  → DiagnosticRequest.parse (剥离 target)
  → resolveTarget("order-service") → BackendEntry
  → entry.execute("jvm", {}) 
    → admitCore (熔断读 + 取槽)
    → invoke: initializeOnce + client.callTool("jvm", {})
    → finally releaseSlot
  ← CallToolResult 原样返
```

### 55.2 异步工具（watch/trace/...）调用流

```
Claude: tools/call watch {target:"order-service", classPattern:..., numberOfExecutions:1}
  → ToolsCallRouter.route (ASYNC_TASK) → submitAsync
  → entry.admit (STATELESS 校验 + 熔断 + 取槽)
  → asyncExecutor.submit (acquireGlobalInflight + store.put + pool.submit orchestrate)
  ← 立即返 {taskId, status:"working"}

后台 orchestrate:
  → worker = pool.submit(entry.invoke("watch", backendArgs))
  → worker.get(11min) — 阻塞等 arthas 命中
  → markCompleted(result) | markFailed(timeout/unreachable) | markCancelled(interrupt)
  → finally: releaseSlot + releaseGlobalInflight

Claude 后续: task-get(taskId) / task-list / task-cancel(taskId)
```

### 55.3 K8S 编排工具（list-pods/ensure）调用流

```
Claude: tools/call k8s.ensure-arthas-mcp {server:"debian", pod:"demo-business"}
  → GatewayMcpServerConfig handler 闭包 (绕过 router)
  → K8sToolHandlers.handle → ensureArthasMcp
  → ArthasProvisioner.ensure
    → deriveLogicalName + 幂等检查
    → doProvision (6 步原子)
  ← {target, status:ready/reused, mcpUrl} 或 INVALID_PARAMS + failed data
```

### 55.4 网关自有工具（list-targets/task-*）调用流

```
Claude: tools/call arthas-gateway.task-get {taskId:"t-xxx"}
  → ToolsCallRouter.route (GATEWAY_LOCAL, 解析前分流)
  → GatewayToolHandlers.handle → taskGet
  → store.get(taskId) + 按 status 渲染
  ← {taskId, status, result/error/...}
```

---

## 第 56 章 工具能力速查矩阵

| 工具 | 分类 | 路由 | target 必填 | 异步 | 典型用途 |
|------|------|------|-------------|------|----------|
| list-targets | 自有 | GATEWAY_LOCAL | 否 | 否 | 列 target |
| task-get | 自有 | GATEWAY_LOCAL | 否 | 否 | 查任务 |
| task-list | 自有 | GATEWAY_LOCAL | 否 | 否 | 列任务 |
| task-cancel | 自有 | GATEWAY_LOCAL | 否 | 否 | 取消任务 |
| watch | arthas | ASYNC_TASK | 是 | 是 | 观察方法 |
| trace | arthas | ASYNC_TASK | 是 | 是 | 追踪调用链 |
| stack | arthas | ASYNC_TASK | 是 | 是 | 调用栈 |
| tt | arthas | ASYNC_TASK | 是 | 是 | 时间隧道 |
| monitor | arthas | ASYNC_TASK | 是 | 是 | 周期监控 |
| jvm | arthas | SYNC_DIRECT | 是 | 否 | JVM 信息 |
| thread | arthas | SYNC_DIRECT | 是 | 否 | 线程详情 |
| dashboard | arthas | STREAM_AGGREGATE | 是 | 否 | 仪表盘 |
| memory | arthas | SYNC_DIRECT | 是 | 否 | 内存区 |
| heapdump | arthas | SYNC_DIRECT | 是 | 否 | 堆 dump |
| sc | arthas | SYNC_DIRECT | 是 | 否 | 查类 |
| sm | arthas | SYNC_DIRECT | 是 | 否 | 查方法 |
| jad | arthas | SYNC_DIRECT | 是 | 否 | 反编译 |
| dump | arthas | SYNC_DIRECT | 是 | 否 | dump class |
| classloader | arthas | SYNC_DIRECT | 是 | 否 | 类加载器 |
| mc | arthas | SYNC_DIRECT | 是 | 否 | 内存编译 |
| ognl | arthas | SYNC_DIRECT | 是 | 否 | OGNL 表达式 |
| getstatic | arthas | SYNC_DIRECT | 是 | 否 | 静态属性 |
| vmoption | arthas | SYNC_DIRECT | 是 | 否 | VM 选项 |
| vmtool | arthas | SYNC_DIRECT | 是 | 否 | VM 工具 |
| sysprop | arthas | SYNC_DIRECT | 是 | 否 | 系统属性 |
| sysenv | arthas | SYNC_DIRECT | 是 | 否 | 环境变量 |
| mbean | arthas | SYNC_DIRECT | 是 | 否 | MBean |
| perfcounter | arthas | SYNC_DIRECT | 是 | 否 | 性能计数器 |
| profiler | arthas | SYNC_DIRECT | 是 | 否 | profiler |
| redefine | arthas | SYNC_DIRECT | 是 | 否 | 重定义类 |
| retransform | arthas | SYNC_DIRECT | 是 | 否 | retransform |
| version | arthas | SYNC_DIRECT | 是 | 否 | arthas 版本 |
| viewfile | arthas | SYNC_DIRECT | 是 | 否 | 查文件 |
| options | arthas | SYNC_DIRECT | 是 | 否 | arthas 选项 |
| stop | arthas | SYNC_DIRECT | 是 | 否 | 销毁会话 |
| k8s.list-pods | K8S | 闭包 | 否 | 否 | 列 pod |
| k8s.list-services | K8S | 闭包 | 否 | 否 | 列 service |
| k8s.ensure-arthas-mcp | K8S | 闭包 | 否 | 否（但内部耗时） | 纳管 pod |

---

## 第 57 章 Claude Code 集成示例

### 57.1 MCP 配置文件

```json
{
  "mcpServers": {
    "arthas-gw": {
      "type": "http",
      "url": "http://127.0.0.1:8761/mcp"
    }
  }
}
```

### 57.2 工具枚举

```bash
claude -p --mcp-config mcp.json --permission-mode bypassPermissions \
  "列出 arthas-gw 暴露的全部 MCP 工具名，仅输出按字母排序的 JSON 字符串数组。"
# → 38 工具数组
```

### 57.3 同步诊断

```bash
claude -p --mcp-config mcp.json --permission-mode bypassPermissions \
  "调用 jvm target=order-service，返回 MACHINE-NAME/VM-VERSION/线程数/堆 used。"
```

### 57.4 异步任务

```bash
claude -p --mcp-config mcp.json --permission-mode bypassPermissions \
  "调用 watch target=order-service classPattern=com.x.OrderService methodPattern=hotMethod numberOfExecutions=1 timeout=30，返回 taskId。"
# → {taskId:"t-xxx", status:"working"}
claude -p --mcp-config mcp.json --permission-mode bypassPermissions \
  "调用 arthas-gateway.task-get taskId=t-xxx，返回最终结果。"
```

### 57.5 K8S 编排（完整 SC-001）

```bash
claude -p --mcp-config mcp.json --permission-mode bypassPermissions \
  "依次：① k8s.list-pods namespace=default；② 选 hasJvm=true 的 pod 调 k8s.ensure-arthas-mcp server=debian pod=<pod>；③ 用返回 target 调 jvm。"
# → list-pods → ensure ready → jvm 远程 pod JVM 诊断
```

---

> **下一步**：Part 8 测试用例全清单（每个 IT/Test 的目的 + 断言 + 真实故障条件）。
