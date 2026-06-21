# arthas MCP 网关 · 测试操作步骤报告（How I Tested）

> 本报告记录**测试是如何一步步做的**：每一步的**操作**（命令/动作）+ **对应结果**（真实输出）。结果均来自实际运行，原始日志归档于 `target/smoke-*.log|json|txt`。
> 配套的结果导向报告见 `arthas-mcp-gateway-冒烟测试报告.md`。
>
> 测试方式遵循 CLAUDE.md 硬约束：**真实环境零桩**——2 个真实 arthas MCP 后端 + 2 个真实业务 JVM；驱动分层——真实 Claude Code（`claude -p`）验「可用性」，官方 MCP Java SDK client 验「契约与完整请求/响应」。

| 项 | 值 |
|---|---|
| 测试对象 | arthas-mcp-gateway 0.1.0-SNAPSHOT（fat jar `target/arthas-mcp-gateway-0.1.0-SNAPSHOT.jar`） |
| 时间 | 2026-06-20 23:21 – 23:45 |
| 工作目录 | `D:\vibe_Coding\arthas-gateway` |
| 终端 | Git Bash（win32） |

---

## 步骤 0 · 测试目标

1. 本地拉起 **2 个真实 arthas MCP 后端**及各自**真实业务服务**；
2. 验证网关把多目标 arthas 聚合为单一 MCP 服务、按 `target` 路由、结果原样透传；
3. **用真实 Claude Code 注册并实调**该网关；
4. 用官方 SDK client 取**完整请求/响应**；
5. 专项验证 `watch` 异步通道（接受/生命周期/取消/并发/非阻塞）。

---

## 步骤 1 · 环境探测（先摸清依赖与产物）

### 操作

```bash
java -version 2>&1 | head -3
echo "$JAVA_HOME"
claude --version
ls -la target/*.jar
ls target/test-classes/com/arthas/gateway/testfixtures/
ls -la tools/arthas-boot.jar
ls "$HOME/.arthas/lib/"
```

### 结果

```
java version "1.8.0_321"          ← PATH 默认 java 是 JDK 1.8（坑：网关/夹具为 release 21，必须显式 JDK21）
JAVA_HOME=C:\Program Files\Java\jdk1.8.0_321
claude 2.1.183 (Claude Code)      ← Claude Code CLI 可用
target/arthas-mcp-gateway-0.1.0-SNAPSHOT.jar   ← 31MB fat jar 已构建
test-classes: ArthasMcpBackend / DemoBusinessApp / McpClientHarness / OrderService / OrderResult … ← 夹具已编译
tools/arthas-boot.jar  147977 bytes             ← 静态工具文件在位
~/.arthas/lib/4.3.0                              ← arthas 运行时已缓存（attach 会很快）
```

**裁决**：全 JVM 须显式用 `C:\Program Files\Java\jdk-21`（21.0.5）。验证：

```bash
"/c/Program Files/Java/jdk-21/bin/java" -version
# → java version "21.0.5" 2024-10-15 LTS
```

---

## 步骤 2 · 编写冒烟夹具（3 个一次性工具，非生产代码）

为「常驻双后端 + 完整请求/响应」写了 3 个文件（放 `smoke/`，不入 Maven 构建）：

| 文件 | 职责 |
|---|---|
| `smoke/SmokeDemoLauncher.java` | 复用 `ArthasMcpBackend` 起 **2 个真实后端**（order-service / payment），各带一个真实 `DemoBusinessApp` JVM；写出运行时映射表 `config/backends-runtime.yaml`（NONE 认证 + 动态端口），然后常驻。退出经 shutdown hook 销毁子 JVM。 |
| `smoke/SmokeMcpClient.java` | 用官方 MCP SDK client（`McpClientHarness`）连网关 `/mcp`，打印 initialize/tools/list，逐工具 `tools/call` 并打印**完整请求入参与响应**。覆盖同步/聚合/异步/错误用例。 |
| `smoke/SmokeWatchAsync.java` | watch 异步专项：非阻塞测时、生命周期轮询、task-cancel、跨 target 并发。 |

> 复用夹具而非手搓 bash 的原因：arthas attach 需要目标 JVM 的 **Windows 原生 PID**，而 Git Bash 的 `$!` 给的是 MSYS pid 不可用；夹具用 Java `Process.pid()` 拿真 PID（见 `ArthasMcpBackend.runArthasAttach`）。

---

## 步骤 3 · 编译夹具（含一次签名错误修复）

### 操作

```bash
J21="/c/Program Files/Java/jdk-21"
mkdir -p target/smoke-classes
./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/sdk-cp.txt -Dmdep.includeScope=test   # 取 SDK classpath
CP="target/test-classes;$(cat target/sdk-cp.txt)"
"$J21/bin/javac" -cp "$CP" -d target/smoke-classes smoke/SmokeDemoLauncher.java smoke/SmokeMcpClient.java
```

### 结果（首次）

```
smoke\SmokeMcpClient.java:103: 错误: 无法将类 McpClientHarness中的方法 callTool 应用到给定类型
        CallToolResult r = h.callTool(new McpSchema.CallToolRequest(tool, args));
需要: String,Map<String,Object>
找到:    CallToolRequest
1 个错误
```

**修复**：`McpClientHarness.callTool` 签名是 `(String, Map)`，改为 `h.callTool(tool, args)`。重编译：

```bash
"$J21/bin/javac" -cp "$CP" -d target/smoke-classes smoke/SmokeDemoLauncher.java smoke/SmokeMcpClient.java
# → 仅有注解处理器提示（无害），EXIT=0，生成 SmokeDemoLauncher.class / SmokeMcpClient.class
```

---

## 步骤 4 · 拉起双真实后端

### 操作（后台运行，输出重定向到日志）

```bash
"$J21/bin/java" -Dbasedir="D:/vibe_Coding/arthas-gateway" \
  -cp "target/test-classes;target/smoke-classes" \
  com.arthas.gateway.smoke.SmokeDemoLauncher > target/smoke-launcher.log 2>&1 &
# 等待 ~18s（业务服务就绪 + arthas attach）
sleep 18 && cat target/smoke-launcher.log
```

### 结果

```
[launcher] basedir=D:\vibe_Coding\arthas-gateway
[launcher] 启动后端 order-service ...
[launcher] 启动后端 payment ...
=== SMOKE_DEMO_READY ===
order-service  baseUrl=http://127.0.0.1:50242  appPort=50241  mcpPort=50242
payment        baseUrl=http://127.0.0.1:50251  appPort=50250  mcpPort=50251
runtime-config=D:\vibe_Coding\arthas-gateway\config\backends-runtime.yaml
=== 常驻中（kill 进程以退出）===
```

**产物** `config/backends-runtime.yaml`：

```yaml
version: 1
backends:
  - {name: order-service, url: http://127.0.0.1:50242, protocol: STREAMABLE, auth: {mode: NONE}, connectTimeoutMs: 5000, callTimeoutMs: 30000, maxConcurrentTasks: 5}
  - {name: payment,       url: http://127.0.0.1:50251, protocol: STREAMABLE, auth: {mode: NONE}, connectTimeoutMs: 5000, callTimeoutMs: 30000, maxConcurrentTasks: 5}
```

每个后端 = 1 个 `DemoBusinessApp`（JDK HttpServer，`/api/order` + 后台 `hot-loop` 每 ~50ms 触发 `OrderService.hotMethod`）+ 1 个 `arthas-boot.jar` attach（`--http-port`，纯 Java Attach，arthas 4.3.0）。

---

## 步骤 5 · 启动网关（首次失败 → 诊断 → 重启）

### 5.1 操作（首次）

```bash
"$J21/bin/java" -jar target/arthas-mcp-gateway-0.1.0-SNAPSHOT.jar \
  --arthas-gateway.backends-file=config/backends-runtime.yaml > target/smoke-gateway.log 2>&1 &
sleep 16
grep -E "Started GatewayApplication|ERROR" target/smoke-gateway.log
curl -s http://127.0.0.1:8761/actuator/health
```

### 5.1 结果（端口已被占用）

```
APPLICATION FAILED TO START
Description: Web server failed to start. Port 8761 was already in use.
```

> 但 `curl /actuator/health` 却返回了 UP——且**没有 `backendRegistry` 组件**，说明 8761 上有一个**旧网关残留**在跑，我的新实例因端口冲突崩了。

### 5.2 诊断（核验占用者，不臆测）

```bash
netstat -ano | grep 8761
# → TCP 0.0.0.0:8761 LISTENING 146108
tasklist //FI "PID eq 146108"            # → java.exe
powershell -Command "(Get-CimInstance Win32_Process -Filter 'ProcessId=146108').CommandLine"
# → "...jdk-21\bin\java.exe" -XX:TieredStopAtLevel=1 -cp @...spring-boot-....argfile com.arthas.gateway.GatewayApplication
```

确认是 `com.arthas.gateway.GatewayApplication`（此前 `spring-boot:run` 残留），非用户其他进程。

### 5.3 操作（清理 + 重启）

```bash
taskkill //PID 146108 //F      # → 成功: 已终止 PID 为 146108 的进程
sleep 2; netstat -ano | grep 8761   # → 8761 FREE
"$J21/bin/java" -jar target/arthas-mcp-gateway-0.1.0-SNAPSHOT.jar \
  --arthas-gateway.backends-file=config/backends-runtime.yaml > target/smoke-gateway.log 2>&1 &
sleep 16
```

### 5.3 结果

```
Tomcat started on port 8761 (http)
Started GatewayApplication in 2.75 seconds
```

`/actuator/health`：

```json
{"components":{"backendRegistry":{"details":{"backends":{
   "order-service":{"state":"ACTIVE","healthy":true,"protocol":"STREAMABLE","breaker":"CLOSED"},
   "payment":{"state":"ACTIVE","healthy":true,"protocol":"STREAMABLE","breaker":"CLOSED"}},
   "summary":{"total":2,"healthy":2,"unhealthy":0}},"status":"UP"}, ...}}
```

✅ 网关就绪，两后端均 ACTIVE/healthy/breaker CLOSED。

---

## 步骤 6 · 注册 MCP 到 Claude Code

### 操作（写两份配置）

```bash
# 项目级注册（下次开会话提示加载）
cat > .mcp.json <<'EOF'
{ "mcpServers": { "arthas-gw": { "type": "http", "url": "http://127.0.0.1:8761/mcp" } } }
EOF
# claude -p 用的显式配置
cat > target/smoke-mcp-config.json   # 内容同上
```

### 结果

- `.mcp.json`、`target/smoke-mcp-config.json` 均就位。
- 说明：交互会话工具集在启动时固定，无法中途注入新 MCP 工具给当前进程；故「真实 Claude Code 实调」用 **`claude -p`**（同一 CLI、同一鉴权的独立 CC 进程）+ `--mcp-config` + `--allowedTools "mcp__arthas-gw__*"`，即 README 所述冒烟路径。

---

## 步骤 7 · 真实 Claude Code 实调（`claude -p`）

### 7.1 操作①：list-targets

```bash
claude -p "请调用 MCP 工具 arthas-gateway_list-targets，列出网关聚合的全部诊断 target，原样输出返回内容。" \
  --mcp-config target/smoke-mcp-config.json --strict-mcp-config \
  --allowedTools "mcp__arthas-gw__*" --output-format json --max-turns 4
```

### 7.1 结果（`target/smoke-cc-listtargets.json`，关键字段）

```json
{"type":"result","subtype":"success","is_error":false,
 "duration_ms":19576,"num_turns":2,"permission_denials":[],
 "result":"{\"version\":1,\"targets\":[{\"name\":\"order-service\",\"state\":\"ACTIVE\",\"healthy\":true,\"protocol\":\"STREAMABLE\"},{\"name\":\"payment\",\"state\":\"ACTIVE\",\"healthy\":true,\"protocol\":\"STREAMABLE\"}]}"}
```

✅ 真实 Claude Code 经 MCP 调通网关（`num_turns:2`、无权限拒绝），双 target 返回。

### 7.2 操作②：同步诊断组合（jvm / thread / ognl 一次调三个）

```bash
claude -p "依次调用：1) jvm target=order-service  2) thread target=payment  3) ognl target=order-service expression=@com.arthas.gateway.testfixtures.OrderService@hotMethodInvocations()，原样输出各返回内容" \
  --mcp-config target/smoke-mcp-config.json --strict-mcp-config --allowedTools "mcp__arthas-gw__*" --max-turns 8
```

### 7.2 结果（`target/smoke-cc-diag.txt`，节选）

- **jvm → order-service** ✅：`SPEC-VERSION=21`、`VM-VERSION=21.0.5+9-LTS-239`、`INPUT-ARGUMENTS=[-Ddemo.slowMs=0]`、`CLASS-PATH=…target\test-classes`、`THREAD.COUNT=32`、`DEADLOCK-COUNT=0`。
- **thread → payment** ✅：`threadStateCount={RUNNABLE:18,…}`；线程含 `demo-hot-loop`、`HTTP-Dispatcher`、`mcp-keep-alive-scheduler`。
- **ognl → order-service** ⚠：`statusCode:-1, NoSuchMethodException: hotMethodInvocations()`。网关转发成功（`success:true`），表达式失败是测试侧语法问题——`@class@method` 是**静态**访问语法，而该方法是**实例**方法。网关如实透传后端错误（印证业务错误不计熔断）。

---

## 步骤 8 · 官方 MCP SDK client · 逐工具完整请求/响应

### 操作

```bash
# ognl 表达式改为可验证真值 @System@getProperty("demo.slowMs")（目标 JVM 设了 -Ddemo.slowMs=0）
"$J21/bin/javac" -cp "$CP" -d target/smoke-classes smoke/SmokeMcpClient.java
"$J21/bin/java" -Dfile.encoding=UTF-8 -cp "$CP" com.arthas.gateway.smoke.SmokeMcpClient
```

> 运行中首次因「未知 target」抛 `McpError` 未捕获而崩；给 `call()` 加 `catch (McpError)` 后重跑干净通过（`target/smoke-client.log`）。

### 结果（逐工具，REQUEST = 入参，RESPONSE = 网关原样返回）

**initialize / tools/list**：

```
[INIT] protocolVersion=2025-11-25  serverInfo=arthas-gateway 0.1.0
       Capabilities: tools=ToolCapabilities[listChanged=false]   ← 仅声明 tools
[TOOLS/LIST] count=35   ← 31 arthas + 4 自有（arthas-gateway.list-targets/task-get/task-list/task-cancel）
```

| # | 工具 | REQUEST（args） | RESPONSE（结果） |
|---|---|---|---|
| 1 | `arthas-gateway.list-targets` | `{}` | `isError=false`：`{"targets":[{name:order-service,state:ACTIVE,healthy:true,protocol:STREAMABLE},{name:payment,...}]}` |
| 2 | `jvm` | `{"target":"order-service"}` | `isError=false`（4831 字符）：`SPEC-VERSION=21`、`INPUT-ARGUMENTS=[-Ddemo.slowMs=0]`、`CLASS-PATH=…target\test-classes`、`THREAD.COUNT=32` |
| 3 | `thread` | `{"target":"payment"}` | `isError=false`（8946 字符）：`threadStateCount={RUNNABLE:38,…}`，含 `demo-hot-loop`、`HTTP-Dispatcher`、`mcp-keep-alive-scheduler` |
| 4 | `ognl` | `{"target":"order-service","expression":"@java.lang.System@getProperty(\"demo.slowMs\")"}` | `isError=false`：`{"results":[{"type":"ognl","value":"@String[0]"},{"statusCode":0}],"success":true}`（`@String[0]`=字符串"0"，反验目标 JVM） |
| 5 | `dashboard` | `{"target":"order-service"}` | `isError=false`（32635 字符）：`stage=final`，含 `gcInfos`、`memoryInfo.heap/nonheap`、`buffer_pool` |
| 6 | `watch`（异步） | `{"target":"order-service","classPattern":…OrderService,"methodPattern":"hotMethod","numberOfExecutions":5,"timeout":20}` | `isError=false`：`{"status":"working","_meta":{…},"taskId":"t-18b7c0"}` |
| 7 | `arthas-gateway.task-get` | `{"taskId":"t-18b7c0"}` | `isError=false`：`status=completed`，`result` 含 5 帧 `watch`（`OrderResult[orderId=94,price=2921]`…） |
| 8 | `arthas-gateway.task-list` | `{}` | `isError=false`：`{"tasks":[{t-955c91:completed,…},{t-18b7c0:completed,…}]}` |
| 9 | `jvm`（未知 target） | `{"target":"no-such-target"}` | **JSON-RPC error**：`code=-32602, message="target 不在册：no-such-target（见 list-targets 的可用目标）", data={available:[order-service,payment]}` |
| 10 | `jvm`（缺 target） | `{}` | `isError=true`：`"Tool (jvm) input validation failed: … 未找到必填属性 target"` |

✅ 全部 10 例符合预期；两条错误路径区分正确（缺值→schema 层 isError；非法值→路由层 JSON-RPC error）。

---

## 步骤 9 · watch 异步能力专项

### 操作

```bash
"$J21/bin/javac" -cp "$CP" -d target/smoke-classes smoke/SmokeWatchAsync.java   # 含一次方法名笔误 printCall→call 修复
"$J21/bin/java" -Dfile.encoding=UTF-8 -cp "$CP" com.arthas.gateway.smoke.SmokeWatchAsync
```

### 结果（`target/smoke-watch.log`，三组场景）

**[1] 非阻塞 + 生命周期**

```
watch（numberOfExecutions:30）→ {"status":"working","taskId":"t-381b86"}
[1] watch 接受耗时 = 7 ms                       ← 毫秒级立即接受
[1] 同期同步 jvm(payment) 耗时 = 125 ms          ← ≈独立基线 110~400ms，未被 watch 阻塞
[lifecycle] t-381b86 -> working
[lifecycle] t-381b86 -> completed               ← createdAt 15:45:09 → completedAt 15:45:11
# 完成态含 30 帧，每帧 accessPoint=AtExit / cost / value=入参+this+OrderResult[orderId=136,price=4223]
```

**[2] task-cancel**

```
长 watch（numberOfExecutions:500）→ taskId=t-94faf5
cancel 前 task-get → {"status":"working","createdAt":"15:45:11.351…"}
task-cancel       → {"taskId":"t-94faf5","status":"cancelled"}
cancel 后 task-get → {"taskId":"t-94faf5","status":"cancelled"}   ✅ working→cancelled
```

**[3] 跨 target 并发**（两条仅隔 4ms 提交）

```
并发提交 order-service=t-c12e90  payment=t-1c8fab
[lifecycle] t-c12e90 -> working → completed   # OrderService@7950671d，OrderResult[orderId=188,price=5835]
[lifecycle] t-1c8fab -> completed              # OrderService@38a2c86b，OrderResult[orderId=125,price=3882]
# 两实例 hash 不同 ⇒ 并发分别命中不同后端 JVM，互不串扰
task-list → 聚合展示全部历史任务
```

---

## 步骤 10 · 网关侧结构化日志 + 最终健康

### 操作

```bash
grep -E "异步任务已接受|工具调用完成" target/smoke-gateway.log
curl -s http://127.0.0.1:8761/actuator/health
```

### 结果（路由日志，逐次 tool/target/isError/耗时）

```
工具调用完成 tool=jvm        target=order-service isError=false 耗时=363ms
工具调用完成 tool=thread     target=payment       isError=false 耗时=486ms
工具调用完成 tool=ognl       target=order-service isError=false 耗时=114ms
工具调用完成 tool=dashboard  target=order-service isError=false 耗时=6147ms   ← 聚合多帧
异步任务已接受 tool=watch    target=order-service taskId=t-955c91
异步任务已接受 tool=watch    target=payment       taskId=t-1c8fab   ← 与上一条隔 4ms（并发）
```

最终健康：order-service / payment 均 `ACTIVE / healthy / breaker=CLOSED`，summary `{total:2,healthy:2,unhealthy:0}`——错误用例与取消任务后熔断**未误开**（业务/路由错误与取消不计熔断，符合设计）。

---

## 步骤 11 · 测试结论

| 能力 | 验证手段 | 结果 |
|---|---|---|
| MCP 握手 / 35 工具 / 仅 tools 能力 | SDK initialize + tools/list | ✅ |
| `target` 路由（双后端） | Claude Code + SDK | ✅ 分别命中正确 JVM |
| 同步 / 聚合 | jvm/thread/ognl/dashboard | ✅ 原样透传 |
| 异步：立即接受/非阻塞/生命周期/取消/并发 | watch 专项 | ✅ 接受 7ms、同期 jvm 125ms、working→completed、cancel→cancelled、跨 target 并发 |
| 错误：缺 target / 未知 target | SDK | ✅ schema 层 / JSON-RPC -32602 |
| 真实 Claude Code 集成 | `claude -p --mcp-config` | ✅ 实调成功 |
| 熔断不误开 | 错误/取消后 health | ✅ breaker=CLOSED |

**总体**：arthas MCP 网关 MVP 基础功能（含 watch 异步通道）在双真实后端环境下**全部正常通过**。

---

## 附录 · 完整复现命令序列（须 JDK 21）

```bash
J21="/c/Program Files/Java/jdk-21"
CP="target/test-classes;target/smoke-classes;$(cat target/sdk-cp.txt)"

# 0) 取 SDK classpath（一次性）
JAVA_HOME='C:\Program Files\Java\jdk-21' PATH="/c/Program Files/Java/jdk-21/bin:$PATH" \
  ./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/sdk-cp.txt -Dmdep.includeScope=test

# 1) 编译夹具
"$J21/bin/javac" -cp "$CP" -d target/smoke-classes smoke/SmokeDemoLauncher.java smoke/SmokeMcpClient.java smoke/SmokeWatchAsync.java

# 2) 起双真实后端（常驻，写 config/backends-runtime.yaml）
"$J21/bin/java" -Dbasedir="D:/vibe_Coding/arthas-gateway" -cp "target/test-classes;target/smoke-classes" \
  com.arthas.gateway.smoke.SmokeDemoLauncher

# 3) 起网关（另一终端）—— 若 8761 被占：netstat -ano|grep 8761 → taskkill //PID <pid> //F
"$J21/bin/java" -jar target/arthas-mcp-gateway-0.1.0-SNAPSHOT.jar \
  --arthas-gateway.backends-file=config/backends-runtime.yaml

# 4) 真实 Claude Code 实调
claude -p "调用 arthas-gateway_list-targets，原样输出结果" \
  --mcp-config target/smoke-mcp-config.json --strict-mcp-config --allowedTools "mcp__arthas-gw__*"

# 5) SDK client（完整请求/响应）
"$J21/bin/java" -Dfile.encoding=UTF-8 -cp "$CP" com.arthas.gateway.smoke.SmokeMcpClient

# 6) watch 异步专项
"$J21/bin/java" -Dfile.encoding=UTF-8 -cp "$CP" com.arthas.gateway.smoke.SmokeWatchAsync
```
