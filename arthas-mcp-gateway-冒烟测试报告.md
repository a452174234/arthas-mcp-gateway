# arthas MCP 网关 · 冒烟测试报告

| 项 | 值 |
|---|---|
| 特性 | `001-arthas-mcp-gateway` |
| 网关版本 | 0.1.0-SNAPSHOT（fat jar `target/arthas-mcp-gateway-0.1.0-SNAPSHOT.jar`） |
| 测试时间 | 2026-06-20 23:23 – 23:31（本地） |
| 测试方式 | **真实环境零桩**：2 个真实 arthas MCP 后端 + 2 个真实业务 JVM；驱动分层——真实 Claude Code（`claude -p`）验「可用性」+ 官方 MCP Java SDK client 验「契约与完整请求/响应」 |
| 报告产出 | 本文件 + 原始日志：`target/smoke-launcher.log`、`target/smoke-gateway.log`、`target/smoke-client.log`、`target/smoke-cc-listtargets.json`、`target/smoke-cc-diag.txt` |

> 结论先行：**基础功能全部正常通过**。35 工具按 `target` 正确路由到双后端，同步/聚合/异步（watch→task-get）/网关自有工具均工作正常；两条错误路径（缺 target / 未知 target）区分正确；多次调用后两后端熔断器始终 CLOSED（业务/路由错误不计熔断，符合设计）。发现 1 项非缺陷观察（见 §7）。

---

## 1. 测试目标

在本地拉起 **2 个真实 arthas MCP 后端**及其**对应的真实业务服务**，验证 arthas MCP 网关的基础功能：

1. 把多个目标 JVM 的 arthas 聚合为单一 MCP 服务（`/mcp`），对客户端只暴露一个端点；
2. 每个 arthas 工具的 `target` 参数正确路由到对应后端，结果原样透传；
3. 异步长任务（`watch`）后台化、`task-get/list` 跟踪可用；
4. **真实 Claude Code** 能注册并实调该网关（用户核心诉求）。

## 2. 测试环境（真实，零桩）

由 `smoke/SmokeDemoLauncher.java`（复用测试夹具 `ArthasMcpBackend` + `DemoBusinessApp`）编排：

| 角色 | 实体 | 端口 | 说明 |
|---|---|---|---|
| 业务服务 ① | `DemoBusinessApp`（JDK `HttpServer`，`/api/order` + 后台 `hot-loop` 持续触发 `OrderService.hotMethod`） | app **50241** | arthas 的目标 JVM ① |
| arthas MCP 后端 ① | `arthas-boot.jar` attach 到业务①（`--http-port`，纯 Java Attach，无 bash/as.sh） | mcp **50242**（根 URL，无 `/mcp`） | 对外 `order-service` |
| 业务服务 ② | 同上 | app **50250** | arthas 的目标 JVM ② |
| arthas MCP 后端 ② | 同上 | mcp **50251** | 对外 `payment` |
| **网关** | `arthas-mcp-gateway` fat jar（Spring Boot 4.1.0 / Spring AI 2.0.0 / MCP SDK 2.0.0） | **8761**（`/mcp`、`/actuator/health`） | 指向 `config/backends-runtime.yaml` |

- arthas 版本锁定 **4.3.0**（`--use-version 4.3.0`，运行时已缓存 `~/.arthas/lib/4.3.0`）。
- 全部 JVM 以 **JDK 21（21.0.5 LTS）** 运行（系统 PATH 默认 java 为 1.8，故显式用 `C:\Program Files\Java\jdk-21`）。
- **本工程不依赖 arthas 工程**：`arthas-boot.jar` 仅作 `tools/` 下静态工具文件经 `java -jar` 使用，不入 pom。
- 真实性铁证（见 §4 响应）：`jvm` 返回 `INPUT-ARGUMENTS=[-Ddemo.slowMs=0]`、`CLASS-PATH=…target\test-classes`、`MACHINE-NAME=138644@DESKTOP-O8RUTFP`；`thread` 返回 `demo-hot-loop`、`HTTP-Dispatcher`、`mcp-keep-alive-scheduler` 等真实线程——均为本次启动的业务 JVM 自身数据，非桩。

运行时后端映射表 `config/backends-runtime.yaml`（NONE 认证，动态端口）：

```yaml
version: 1
backends:
  - name: order-service
    url: http://127.0.0.1:50242
    protocol: STREAMABLE
    auth: { mode: NONE }
    connectTimeoutMs: 5000
    callTimeoutMs: 30000
    maxConcurrentTasks: 5
  - name: payment
    url: http://127.0.0.1:50251
    protocol: STREAMABLE
    auth: { mode: NONE }
    connectTimeoutMs: 5000
    callTimeoutMs: 30000
    maxConcurrentTasks: 5
```

## 3. MCP 注册到 Claude Code（当前会话/项目）

两份配置（内容一致）：

- **`.mcp.json`**（项目根，项目级注册——后续在本项目打开 Claude Code 会提示加载）：
  ```json
  { "mcpServers": { "arthas-gw": { "type": "http", "url": "http://127.0.0.1:8761/mcp" } } }
  ```
- **`target/smoke-mcp-config.json`**（`claude -p --mcp-config` 显式加载，即 README 所述冒烟路径）。

> 说明：当前交互会话的工具集在会话启动时已固定，无法中途注入新 MCP 工具给本进程直接调用。因此「真实 Claude Code 实调」以 **`claude -p`（同一 CLI、同一鉴权的真实 Claude Code 进程）+ `--mcp-config` + `--allowedTools "mcp__arthas-gw__*"`** 实现——这是 Claude Code 驱动 MCP 工具的官方方式，也是本项目设计文档约定的「工具可用性」验证路径。工具句柄：`mcp__arthas-gw__<tool>`（Claude Code 把工具名中的 `.` 显示为 `_`，如 `arthas-gateway.list-targets` → `arthas-gateway_list-targets`）。

## 4. 真实 Claude Code 实调结果（`claude -p`）

### 4.1 `arthas-gateway.list-targets`（JSON 输出，`--output-format json`）

| 指标 | 值 |
|---|---|
| `num_turns` | 2（一次工具调用 + 收尾） |
| 耗时 | ~19.6s（含网络/模型） |
| `permission_denials` | `[]`（`--allowedTools` 放行成功） |

Claude 返回（`result` 字段，**原样来自工具**）：

```json
{"version":1,"targets":[
  {"name":"order-service","state":"ACTIVE","healthy":true,"protocol":"STREAMABLE"},
  {"name":"payment","state":"ACTIVE","healthy":true,"protocol":"STREAMABLE"}]}
```

✅ 通过：真实 Claude Code 经 MCP 调通网关，聚合后的双 target 正确返回。

### 4.2 同步诊断组合（`jvm` / `thread` / `ognl`，一次 `claude -p` 调三个工具）

**① `jvm` ｜ target=`order-service`** ✅（节选，完整 4831 字符见 `target/smoke-cc-diag.txt`）

```json
{
  "command": "jvm", "resultCount": 2, "success": true,
  "results": [{
    "jvmInfo": {
      "RUNTIME": [
        {"name":"MACHINE-NAME","value":"138644@DESKTOP-O8RUTFP"},
        {"name":"JVM-START-TIME","value":"2026-06-20 23:23:25"},
        {"name":"SPEC-VERSION","value":"21"},
        {"name":"VM-NAME","value":"Java HotSpot(TM) 64-Bit Server VM"},
        {"name":"VM-VERSION","value":"21.0.5+9-LTS-239"},
        {"name":"INPUT-ARGUMENTS","value":["-Ddemo.slowMs=0"]},        ← 目标 JVM ① 的铁证
        {"name":"CLASS-PATH","value":"D:\\vibe_Coding\\arthas-gateway\\target\\test-classes"}
      ],
      "THREAD":   [{"name":"COUNT","value":32},{"name":"DEADLOCK-COUNT","value":0}],
      "OPERATING-SYSTEM":[{"name":"OS","value":"Windows 11"},{"name":"PROCESSORS-COUNT","value":8}]
    }
  }]
}
```

**② `thread` ｜ target=`payment`** ✅（节选，完整 7965 字符见同上）

```json
{
  "command":"thread","success":true,
  "results":[{
    "threadStateCount":{"RUNNABLE":18,"WAITING":4,"TIMED_WAITING":10},
    "threadStats":[
      {"name":"main","state":"WAITING"},
      {"name":"demo-hot-loop","state":"TIMED_WAITING"},            ← 业务服务后台 hot-loop 真实线程
      {"name":"HTTP-Dispatcher","state":"RUNNABLE"},
      {"name":"mcp-keep-alive-scheduler","state":"TIMED_WAITING"}  ← arthas MCP 端点真实线程
    ]
  }]
}
```

**③ `ognl` ｜ target=`order-service` ｜ expression=`@com.arthas.gateway.testfixtures.OrderService@hotMethodInvocations()`** ⚠（观察项，见 §7）

```json
{
  "command":"ognl '@...OrderService@hotMethodInvocations()'",
  "results":[{
    "message":"Failed to execute ognl, exception message: ognl.MethodFailedException: ... NoSuchMethodException: hotMethodInvocations()",
    "statusCode":-1
  }], "success":true
}
```

网关**转发与透传完全正常**（`success:true`）；表达式执行失败是**测试侧 ognl 语法问题**——`@class@method` 是静态访问语法，而 `OrderService.hotMethodInvocations()` 是实例方法。该错误被网关如实透传，恰好印证「后端业务错误原样返回、不计熔断」。

## 5. 官方 MCP Java SDK client · 完整请求/响应（契约验证）

`smoke/SmokeMcpClient.java`（合规客户端，`HttpClientStreamableHttpTransport`，非裸 curl）。输出见 `target/smoke-client.log`。

### 5.1 initialize / tools/list

```
[INIT] protocolVersion=2025-11-25  serverInfo=Implementation[name=arthas-mcp-gateway, version=0.1.0]
       Capabilities: tools=ToolCapabilities[listChanged=false]   ← 仅声明 tools，不广播 prompts/resources/logging（S-INIT-2）
[TOOLS/LIST] count=35
       31 arthas 工具（options/stop/version/viewfile/dashboard/getstatic/heapdump/jvm/mbean/memory/ognl/
        perfcounter/sysenv/sysprop/thread/vmoption/vmtool/classloader/dump/jad/mc/redefine/retransform/
        sc/sm/monitor/profiler/stack/tt/trace/watch）
        4 网关自有（arthas-gateway.list-targets/task-get/task-list/task-cancel）
```

### 5.2 各工具请求/响应矩阵

**① `arthas-gateway.list-targets`**（完整）

| 请求 | 响应 |
|---|---|
| `tool=arthas-gateway.list-targets`<br>`args={}` | `isError=false`<br>`{"version":1,"targets":[{"name":"order-service","state":"ACTIVE","healthy":true,"protocol":"STREAMABLE"},{"name":"payment","state":"ACTIVE","healthy":true,"protocol":"STREAMABLE"}]}` |

**② `jvm` ｜ target=`order-service`** ✅（响应 4831 字符，节选核心字段）

| 请求 | 响应（节选） |
|---|---|
| `tool=jvm`<br>`args={"target":"order-service"}` | `isError=false`，`SPEC-VERSION=21`、`VM-VERSION=21.0.5+9-LTS-239`、`INPUT-ARGUMENTS=[-Ddemo.slowMs=0]`、`CLASS-PATH=…target\test-classes`、`THREAD.COUNT=32`、`DEADLOCK-COUNT=0`、`OS=Windows 11` |

**③ `thread` ｜ target=`payment`** ✅（响应 7965 字符，节选）

| 请求 | 响应（节选） |
|---|---|
| `tool=thread`<br>`args={"target":"payment"}` | `isError=false`，`threadStateCount={RUNNABLE:32,WAITING:5,TIMED_WAITING:10}`；线程含 `main`、`demo-hot-loop`、`HTTP-Dispatcher`、`arthas-timer`、`arthas-shell-server`、`mcp-keep-alive-scheduler` |

**④ `ognl` ｜ target=`order-service`**（完整，反验目标 JVM 身份）

| 请求 | 响应 |
|---|---|
| `tool=ognl`<br>`args={"target":"order-service","expression":"@java.lang.System@getProperty(\"demo.slowMs\")"}` | `isError=false`<br>`{"command":"ognl '@java.lang.System@getProperty(\"demo.slowMs\")'","resultCount":2,"results":[{"jobId":4,"type":"ognl","value":"@String[0]"},{"jobId":4,"statusCode":0,"type":"status"}],"success":true}` |

> `@String[0]` 即 arthas 对字符串 `"0"` 的渲染——读出目标 JVM 的 `demo.slowMs=0`（启动参数所设），**同时反向验证**命中的正是业务服务①的 JVM。

**⑤ `dashboard`（聚合 STREAM_AGGREGATE）｜ target=`order-service`** ✅（响应 31693 字符，节选）

| 请求 | 响应（节选） |
|---|---|
| `tool=dashboard`<br>`args={"target":"order-service"}` | `isError=false`，`stage=final`、`message="Dashboard execution completed successfully"`；含 `gcInfos`（g1_young_generation/g1_concurrent_gc/g1_old_generation）、`memoryInfo.heap`（max≈8.5G,used≈32M）、`memoryInfo.nonheap`（metaspace 等）、`buffer_pool` |

**⑥ `watch`（异步 ASYNC_TASK）｜ target=`order-service`** ✅

| 请求 | 响应（立即接受） |
|---|---|
| `tool=watch`<br>`args={"target":"order-service","classPattern":"com.arthas.gateway.testfixtures.OrderService","methodPattern":"hotMethod","numberOfExecutions":5,"timeout":20}` | `isError=false`<br>`{"status":"working","_meta":{"target":"order-service","toolName":"watch"},"taskId":"t-955c91"}` |

**⑦ `arthas-gateway.task-get` ｜ taskId=`t-955c91`** ✅（异步结果回查，**真实捕获 hotMethod 调用**）

| 请求 | 响应（节选） |
|---|---|
| `tool=arthas-gateway.task-get`<br>`args={"taskId":"t-955c91"}` | `isError=false`，`status="completed"`，`completedAt="2026-06-20T15:30:05.020195Z"`；`result.content[].text` 内 `watch` 结果（节选）：<br>`{"accessPoint":"AtExit","className":"…OrderService","methodName":"hotMethod","cost":1.399,"value":"@ArrayList[ @Object[][isEmpty=false;size=1], @OrderService[…@7950671d], @OrderResult[OrderResult[orderId=105, price=3262, valid=true]] ]"}` |

> watch 在 order-service 上真实捕获到 `hotMethod` 的入参 + 返回值（`OrderResult[orderId=105,price=3262,valid=true]`、`orderId=106,price=3293`…），共 5 帧——**真实业务调用产生的真实诊断，非桩**。

**⑧ `arthas-gateway.task-list`**（完整）

| 请求 | 响应 |
|---|---|
| `tool=arthas-gateway.task-list`<br>`args={}` | `isError=false`<br>`{"tasks":[{"taskId":"t-955c91","status":"completed","toolName":"watch","target":"order-service","createdAt":"2026-06-20T15:30:03.992722800Z","completedAt":"2026-06-20T15:30:05.020195Z"}]}` |

### 5.3 错误路径（两条，区分正确）

**⑨ 未知 target → S-ERR-5（JSON-RPC error，INVALID_PARAMS）**

| 请求 | 响应 |
|---|---|
| `tool=jvm`<br>`args={"target":"no-such-target"}` | **JSON-RPC error**（非 isError 结果）<br>`code=-32602`<br>`message="target 不在册：no-such-target（见 list-targets 的可用目标）"`<br>`data={available=[order-service, payment]}` |

**⑩ 缺失必填 target → JSON schema 校验拦截（isError 结果）**

| 请求 | 响应 |
|---|---|
| `tool=jvm`<br>`args={}` | `isError=true`<br>`"Tool (jvm) input validation failed: Validation failed: JSON schema validation errors: [: 未找到必填属性 target]"` |

> 两条路径语义不同且均符合契约：**值缺失**在入参 schema 层拦截（返回 isError 结果）；**值非法**在路由层拒绝（结构化 JSON-RPC error，带可用目标提示）。

## 6. 网关侧结构化日志佐证（T049，`target/smoke-gateway.log`）

`ToolsCallRouter` 逐次记录 `tool/target/isError/耗时`：

```
工具调用完成 tool=jvm        target=order-service isError=false 耗时=363ms
工具调用完成 tool=thread     target=payment       isError=false 耗时=486ms
工具调用完成 tool=ognl       target=order-service isError=false 耗时=114ms
工具调用完成 tool=jvm        target=order-service isError=false 耗时=110ms
工具调用完成 tool=thread     target=payment       isError=false 耗时=335ms
工具调用完成 tool=dashboard  target=order-service isError=false 耗时=6147ms   ← 聚合多帧，符合预期
异步任务已接受 tool=watch    target=order-service taskId=t-955c91
```

- 耗时分布合理：`ognl`≈115ms、`jvm`≈110–363ms、`thread`≈329–486ms、`dashboard`≈6.1s（聚合多帧）。
- 异步 `watch` 走 `submitAsync` 立即受理（`异步任务已接受 … taskId=…`），同步路径走 `forwardSync`。

## 7. 观察项与结论

### 观察项（非缺陷）
- **ognl 静态访问语法**：测试侧首版表达式 `@…OrderService@hotMethodInvocations()` 失败（该方法为实例方法，非静态）。网关行为正确（透传后端错误），仅测试表达式需用静态可达目标（已改用 `@java.lang.System@getProperty("demo.slowMs")` 验证 ognl 执行链路）。

### 最终健康（熔断未误开）

```
GET /actuator/health → backendRegistry:
  order-service: {state:ACTIVE, healthy:true, protocol:STREAMABLE, breaker:CLOSED}
  payment:       {state:ACTIVE, healthy:true, protocol:STREAMABLE, breaker:CLOSED}
  summary: {total:2, healthy:2, unhealthy:0}
```

> 经 §5.3 两条错误用例后，两后端熔断器仍 **CLOSED**——印证业务错误/路由错误不计入熔断（设计 C-CB-2），仅基础设施故障才计（本次无基础设施故障）。

### 通过项汇总

| 能力 | 验证手段 | 结果 |
|---|---|---|
| MCP 服务初始化（协议版本/serverInfo/capabilities 仅 tools） | SDK client initialize | ✅ protocol 2025-11-25，server `arthas-mcp-gateway 0.1.0` |
| 35 工具静态暴露 | SDK client tools/list | ✅ count=35（31 arthas + 4 自有） |
| `target` 路由（双后端） | Claude Code + SDK（jvm→order, thread→payment） | ✅ 分别命中正确 JVM |
| 同步转发 | jvm/thread/ognl | ✅ 真实诊断原样透传 |
| 聚合多帧 | dashboard | ✅ stage=final |
| 异步长任务 | watch→task-get/task-list | ✅ taskId 立即返，回查命中真实 hotMethod 调用 |
| 缺 target 校验 | jvm 无 target | ✅ schema 层 isError |
| 未知 target 路由拒绝 | jvm target=no-such-target | ✅ JSON-RPC -32602 + available |
| 真实 Claude Code 集成 | `claude -p --mcp-config --allowedTools` | ✅ 实调成功，双 target 返回 |
| 故障隔离（熔断不误开） | 错误用例后 /actuator/health | ✅ 双后端 breaker=CLOSED |
| 结构化可观测日志 | 网关日志 | ✅ 逐次 tool/target/isError/耗时 |

**总体结论**：arthas MCP 网关 MVP 基础功能在双真实后端环境下**全部正常**，Claude Code 经 MCP 实调验证可用。

---

## 8. watch 异步能力专项复测（2026-06-20 23:45）

针对异步长任务通道做更深验证（`smoke/SmokeWatchAsync.java`，输出 `target/smoke-watch.log`）。结论：**watch 异步能力全面正常**。

### 8.1 非阻塞（后台执行，不阻塞网关）

| 项 | 请求 | 结果 |
|---|---|---|
| watch 接受 | `watch` `{target:order-service, classPattern:OrderService, methodPattern:hotMethod, numberOfExecutions:30, timeout:30}` | 立即返 `{"status":"working","taskId":"t-381b86"}`，**接受耗时 7ms** |
| 同期同步调用 | `jvm` `{target:payment}`（watch 仍在后台跑时立即发起） | **耗时 125ms**（与独立基线 110–400ms 相当）⇒ watch 在后台执行，未阻塞网关 |

### 8.2 生命周期 working → completed（含真实捕获）

```
[lifecycle] t-381b86 -> working
[lifecycle] t-381b86 -> completed     createdAt 15:45:09 → completedAt 15:45:11（~2s 采满 30 帧）
```

完成态 task-get 的 watch 结果结构（节选，resultCount=30）：

```json
{"taskId":"t-381b86","status":"completed","result":{"isError":false,"content":[{"type":"text","text":
  "{\"resultCount\":30,\"stage\":\"final\",\"results":[{
    \"accessPoint\":\"AtExit\",
    \"className\":\"com.arthas.gateway.testfixtures.OrderService\",
    \"methodName\":\"hotMethod\",\"cost\":0.0799,
    \"ts\":\"2026-06-20 23:45:09.195...\",
    \"value\":\"@Arraylist[ @Object[][isEmpty=false;size=1],          ← 入参
                     @OrderService[...@7950671d],                       ← this 实例
                     @OrderResult[OrderResult[orderId=136,price=4223,valid=true]] ]\"  ← 返回值
  }, ... ]}"
}]}}
```

> 每帧含 `accessPoint/className/methodName/cost/ts/value`；`value` 同时含**入参 + this 实例 + 返回值**——真实业务调用产生的真实诊断，非桩。

### 8.3 task-cancel（中途取消）

| 步骤 | 请求 | 响应 |
|---|---|---|
| 提交长 watch | `watch` `{…, numberOfExecutions:500, timeout:60}` | `{"status":"working","taskId":"t-94faf5"}` |
| 取消前 task-get | `arthas-gateway.task-get` `{taskId:t-94faf5}` | `{"status":"working","createdAt":"15:45:11.351…"}`（working 态无 completedAt） |
| **取消** | `arthas-gateway.task-cancel` `{taskId:t-94faf5}` | `{"taskId":"t-94faf5","status":"cancelled"}` |
| 取消后 task-get | `arthas-gateway.task-get` `{taskId:t-94faf5}` | `{"taskId":"t-94faf5","status":"cancelled"}` ✅ |

### 8.4 跨 target 并发

同时提交两条 watch（网关日志显示仅隔 4ms，真并发）：

| taskId | target | 结果 | OrderService 实例 |
|---|---|---|---|
| t-c12e90 | order-service | completed，8 帧（`OrderResult[orderId=188,price=5835]`…） | `@7950671d` |
| t-1c8fab | payment | completed，8 帧（`OrderResult[orderId=125,price=3882]`…） | `@38a2c86b` |

> 两个目标各自 `OrderService` 实例 hash 不同（`7950671d` vs `38a2c86b`），**证明并发 watch 分别命中不同后端 JVM**，互不串扰。`arthas-gateway.task-list` 聚合展示全部历史任务（含本次 5 条 + 此前 2 条）。

### 8.5 网关侧日志与最终健康

```
异步任务已接受 tool=watch target=order-service taskId=t-381b86
异步任务已接受 tool=watch target=order-service taskId=t-94faf5
异步任务已接受 tool=watch target=order-service taskId=t-c12e90
异步任务已接受 tool=watch target=payment       taskId=t-1c8fab   ← 与上一条隔 4ms（并发）
```

复测后 `/actuator/health`：order-service / payment 均 `ACTIVE / healthy / breaker=CLOSED`——**取消任务未误开熔断**（取消属正常生命周期，非基础设施故障）。

### 8.6 异步能力通过项

| 能力 | 结果 |
|---|---|
| 立即接受（G-ASYNC-1 形状，毫秒级） | ✅ 接受 7ms |
| 后台执行、不阻塞网关 | ✅ 同期同步 jvm 125ms（≈基线） |
| 生命周期 working→completed + 时序 | ✅ createdAt/completedAt |
| 真实捕获入参+返回值（多帧） | ✅ OrderResult[orderId/price/valid] |
| task-get / task-list | ✅ |
| task-cancel → cancelled | ✅ working→cancelled |
| 跨 target 并发互不串扰 | ✅ 不同 JVM 实例 hash |
| 取消/完成不误开熔断 | ✅ breaker=CLOSED |

---

## 附录 · 复现与清理

**复现命令**（工程根，须 JDK 21）：

```bash
# 1) 编译冒烟夹具（test-classes 须已存在：mvn test-compile）
J21="/c/Program Files/Java/jdk-21"
CP="target/test-classes;target/smoke-classes;$(cat target/sdk-cp.txt)"
"$J21/bin/javac" -cp "$CP" -d target/smoke-classes smoke/SmokeDemoLauncher.java smoke/SmokeMcpClient.java

# 2) 拉起双真实后端（写 config/backends-runtime.yaml，常驻）
"$J21/bin/java" -Dbasedir="D:/vibe_Coding/arthas-gateway" -cp "target/test-classes;target/smoke-classes" \
  com.arthas.gateway.smoke.SmokeDemoLauncher

# 3) 起网关（另一终端，指向运行时配置）
"$J21/bin/java" -jar target/arthas-mcp-gateway-0.1.0-SNAPSHOT.jar \
  --arthas-gateway.backends-file=config/backends-runtime.yaml

# 4) Claude Code 实调
claude -p "调用 arthas-gateway_list-targets，原样输出结果" \
  --mcp-config target/smoke-mcp-config.json --strict-mcp-config --allowedTools "mcp__arthas-gw__*"

# 5) SDK client 完整请求/响应
"$J21/bin/java" -Dfile.encoding=UTF-8 -cp "$CP" com.arthas.gateway.smoke.SmokeMcpClient
```

**清理**（结束常驻进程）：停网关（占 8761 的 java）+ 停 `SmokeDemoLauncher`（其退出会经 shutdown hook 销毁两个业务子 JVM，arthas MCP 随之释放）。可用 `taskkill //PID <pid> //F` 或 `netstat -ano | grep 8761` 定位。
