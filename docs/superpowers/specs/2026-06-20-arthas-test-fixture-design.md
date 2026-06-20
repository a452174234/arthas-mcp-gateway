# 设计：真实 arthas MCP 测试夹具（T008 DemoBusinessApp + T009 ArthasMcpBackend）

**Feature**: 001-arthas-mcp-gateway | **Date**: 2026-06-20
**决策来源**: 头脑风暴（CLAUDE.md「方案型研究前必须先头脑风暴」），用户确认 **arthas-boot.jar 启动**路径 + **单后端 TDD 优先、集群单独测**作用域。
**宪法依据**: 原则七（TDD 真实环境、零桩）、原则四（双侧契约）、CLAUDE.md「驱动分层」。

---

## 1. 背景与问题

US1/US2/US3 全部集成测试需「真实 arthas MCP + 真实业务服务 + 真实诊断数据」（宪法硬约束，零桩）。卡点：reference 范式 `ArthasMcpJavaSdkIT` 用 `bash as.sh --attach-only` attach arthas，**显式 `assumeFalse(isWindows())` 在 Windows 跳过**（依赖 bash/as.sh）；本机 Windows 11、无 Docker（memory 约束）。

## 2. 选型：arthas-boot.jar 启动（纯 Java attach）

- `arthas-boot.jar`（`com.taobao.arthas:arthas-boot:4.3.0`）是 `as.sh` 的**纯 Java 等价物**：`java -jar`、无 bash 依赖，Windows 原生可跑。仍是 attach 到真实目标 JVM（Java Attach API），忠于生产 attach 路径。
- 已核实（证据驱动）：
  - `Bootstrap.java` 支持 `--attach-only`/`--http-port`/`--target-ip`/`--arthas-home` + PID 位置参数（reference/arthas/boot）。
  - arthas **4.3.0 已发布**（git tag `arthas-all-4.3.0`，revision=4.3.0），含完整 MCP（auth/streamable/task 测试提交）。
  - reference IT 已证明该 `--http-port` 服务 MCP 协议（`HttpClientStreamableHttpTransport` 连上后 initialize/listTools/callTool 正常）。
- **宪法满足**：真实 arthas MCP + 真实业务服务 + 真实诊断（HTTP/循环触发业务方法 → arthas watch/trace 捕获真实调用）。零桩。

## 3. 测试期架构

```
集成测试 JVM（failsafe，*IT.java）
  ArthasMcpBackend 夹具
   ├─ 启 DemoBusinessApp 子进程 → 取 PID
   ├─ java -jar arthas-boot.jar <pid> --attach-only --http-port <mcpPort> --target-ip 127.0.0.1   （短命，exit 0）
   ├─ 轮询 mcpPort 就绪
   └─ 暴露 baseUrl = http://127.0.0.1:<mcpPort>
  McpClientHarness（T010）── 连网关 或 直连 arthas 后端（仅 baseUrl 不同，做 A/B 一致性）

DemoBusinessApp（T008，独立 JVM，Spring Boot）
  └─ arthas agent 注入后，Netty MCP HTTP 常驻其内 @<mcpPort>
```

## 4. 组件职责

| 组件 | 职责 | 需 arthas |
|---|---|---|
| `DemoBusinessApp`（T008） | 真实业务服务：可被 watch/trace/stack/tt 的业务方法（如 `OrderService.hotMethod`）+ HTTP `/api/order` 触发执行 + 可注入 `Thread.sleep` 模拟慢响应。**技术形态：纯 Java + JDK `com.sun.net.httpserver.HttpServer`**（用户裁决 2026-06-20，替代本表原述「Spring Boot 进程」——对齐 reference `TargetJvmApp` 纯 Java 范式，子进程类路径只需 `target/test-classes`，避免 Spring Boot 子进程 fat jar/classpath 地狱；诊断价值齐全。`main` 后台守护线程持续触发 `hotMethod` 供 arthas watch 抓事件） | 否 |
| `ArthasMcpBackend`（T009） | 编排夹具：启 DemoBusinessApp → 取 PID → 跑 arthas-boot.jar attach → 等端口 → 暴露 `baseUrl`。`AutoCloseable`（优雅关停子进程）。原子单元，可按逻辑名实例化 | 是 |
| `McpClientHarness`（T010，已完成） | 复用：连网关或直连后端 | — |

## 5. 构件获取与生命周期

**构件获取（用户约束 2026-06-20：本工程不依赖 arthas；T009 首测实测 2026-06-20）**：
- **arthas 启动器作为可执行 fat jar（静态工具文件）置于工程 `tools/arthas-boot.jar`**——既不以 Maven 依赖引入（不入 pom），也不依赖 `reference/arthas` 源码构建。该 jar 是官方可执行 launcher（Main-Class=`com.taobao.arthas.boot.Bootstrap`、Implementation-Version=4.3.0、147977 字节），从 `https://arthas.aliyun.com/arthas-boot.jar` 下载——**非** Maven Central 的 `com.taobao.arthas:arthas-boot:4.3.0`（瘦 jar，无 Main-Class 清单，`java -jar` 报"没有主清单属性"，不可用）。T009 ArthasMcpBackend 经 `java -jar tools/arthas-boot.jar <pid> --attach-only --http-port <mcpPort> --target-ip 127.0.0.1 --use-version 4.3.0` 使用（参考 reference `ArthasMcpJavaSdkIT` 的 attach/连接<b>范式</b>，非依赖其代码）。
- arthas 运行时：launcher 首跑经 `--use-version 4.3.0` 自动下载 core 4.3.0 到 `~/.arthas/lib/4.3.0` 缓存（首联网、后离线）。
- **端点裁决（T009 首测 GREEN）**：arthas 4.3.0 MCP 端点为**根 URL** `http://127.0.0.1:<mcpPort>`（无 `/mcp`），SDK 2.0.0 ↔ arthas 4.3.0 握手 `2025-11-25` 互通、Windows 原生 attach 成功——`backend-client-contract.md §1` 的 `/mcp` 假设以实测为准。

**生命周期**：
```
start(logicalName, auth=NONE|BEARER, token?):
  1. 分配空闲端口 mcpPort + appPort
  2. 启 DemoBusinessApp 子进程，轮询就绪（/actuator/health 或端口，30s）
  3. 取 PID（Process.pid()）
  4. java -jar arthas-boot.jar <pid> --attach-only --http-port <mcpPort>
            --target-ip 127.0.0.1 [--arthas-home <home>]   （90s，期望 exit 0）
  5. 轮询 mcpPort 就绪（30s）
  6. （BEARER）配 arthas 认证 token
  7. 暴露 baseUrl
close(): destroy DemoBusinessApp（destroyForcibly 兜底），端口随进程释放
```
超时取自 reference IT 经验（attach 90s / 端口 30s）。

## 6. 作用域：单后端优先，集群整体后置（用户确认）

> **用户决策（2026-06-20）**：集群/多目标能力**整体后置实现**；当前范围 = **网关基础功能（单后端）**。

- **当前范围**：`ArthasMcpBackend`（单后端，1 JVM + arthas）= TDD 主路径。覆盖网关核心路由管线：BackendClient 契约、一致性 A/B、异步任务、故障隔离（停这 1 个后端）、失效 target 错误、认证、target 剥离（S-CALL-2）、并发客户端归属（S-CALL-3）。
- **后置（不在当前计划）**：多目标路由隔离（S-CALL-1：target=A 只打 A、B 零请求）、list-targets 多目标展示、热重载增删到多后端。这些待网关基础功能就绪后单独迭代，届时本地起 2 个 `ArthasMcpBackend`。

`ArthasMcpBackend` 为原子单元（单实例）。T008/T009 组件设计面向单后端，不引入集群编排。

## 7. 接入（当前范围，单后端）

| 故事 | 集成测试（*IT.java → failsafe） | 夹具 |
|---|---|---|
| US1 核心 | T018 BackendClientContractTest、T019 ToolsCallRoutingContractTest（S-CALL-2/3/4）、T020 ResultConsistencyIT | 单后端 + 网关；harness 连双侧 A/B |
| US1 核心 | T029 GatewayToolsContractTest、T030 AsyncTaskTimeoutIT | 单后端，异步任务 |
| US3 | T043 FaultIsolationContractTest、T044 FailedTargetErrorIT | 单后端（停/错 token） |

**后置**（不在当前范围）：S-CALL-1 多目标路由、T037 HotReloadIT、T038 ListTargetsContractTest。

纯逻辑单测（T021 配置解析 / T022 注册表 / T045 熔断状态机 / T047 限流）→ surefire（快、无 arthas），可先行 TDD，不依赖 arthas 夹具。

## 7.1 实现波次（先网关基础功能）

1. **波次 A（纯逻辑，无 arthas，surefire TDD）**：T021 BackendConfig+Loader、T022 BackendRegistry+Holder、T023 BackendAuthCustomizer、T045 CircuitBreaker、T047 BackendEntry 限流。先建网关域层。
2. **波次 B（真实夹具）**：T008 DemoBusinessApp、T009 ArthasMcpBackend（单后端）。
3. **波次 C（真实路由）**：T024 BackendClient 接真实 arthas、ToolsCallRouter 真实路由（替换 Phase 2 占位），US1 核心 + US3 集成测试。
4. **后置**：多目标/集群、热重载。

## 8. 认证（NONE / BEARER）

- `ArthasMcpBackend.start(name, auth, token?)`：BEARER 以 token 启 arthas；harness/网关发 `Authorization: Bearer <token>`。
- 网关 `BackendAuthCustomizer`（T023）从 backends.yaml 取 token 注入头。
- C-AUTH-1（US3）：错 token → arthas 真实 401 + `WWW-Authenticate` → 网关标 target 不可用、返 JSON-RPC error（不透传 HTTP）。
- arthas MCP token 配置机制实现期核实。

## 9. 残留风险（实现期首测裁决）

- arthas attach 走 Java Attach API，Windows 原生支持；reference 跳 Windows 仅因 bash/as.sh——arthas-boot.jar 移除此障碍。
- arthas 个别 Windows 专属能力（vmtool 本地库等）需首测验证；核心诊断（watch/trace/sc/jad/thread）跨平台可用。
- arthas 4.3.0（SDK 0.17.0）↔ 网关 SDK 2.0.0 互操作：协议层握手由首测裁决（T009 真实 attach + initialize）。

## 10. 落地任务（已存在于 tasks.md，本设计为其奠基）

- T008 `DemoBusinessApp`（[P]，单文件）
- T009 `ArthasMcpBackend`（[P]，单文件）
- 此后 US1/US2/US3 集成测试以此夹具为共享地基。
