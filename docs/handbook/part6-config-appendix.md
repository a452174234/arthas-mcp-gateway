# Part 6 · 配置、工具清单、错误码、参数速查、文件索引

> 本部分是查表附录：`application.yml` 全字段、38 工具完整清单、错误码/reason 速查、关键参数速查、全部文件索引。

---

## 第 45 章 application.yml 全字段

**文件**：`src/main/resources/application.yml`

```yaml
# arthas MCP 网关配置
spring:
  application:
    name: arthas-mcp-gateway
  main:
    banner-mode: console
  # === MCP 服务端（面向 Claude Code），由 Spring AI starter 自动装配 ===
  ai:
    mcp:
      server:
        enabled: true
        name: arthas-mcp-gateway          # serverInfo.name（S-INIT-2）
        version: 0.1.0                    # serverInfo.version
        type: SYNC                        # 同步 server
        protocol: STREAMABLE              # Streamable HTTP（非 SSE/STATELESS）
        stdio: false                      # MVP 不开 stdio（与 HTTP 互斥）
        streamable-http:
          mcp-endpoint: /mcp              # MCP 端点路径

# === Servlet 容器（WebMVC Streamable HTTP 跑在嵌入式 Tomcat） ===
server:
  port: 8761
  address: 0.0.0.0

arthas-gateway:
  backends-file: config/backends.yaml     # 后端映射表（WatchService 热重载）
  task:
    backend-timeout: 11m                  # 异步后台兜底超时（> 后端 10min 上限）
    result-ttl: 1h                        # 已完成任务可查询保留时长
    # global-max-inflight: null           # 未设→动态默认 max(1, 后端数×5)
  # === K8S 编排子段（003） ===
  k8s:
    kubeconfig: test-env/k8s/kubeconfig/k3s-admin.yaml   # kubeconfig（gitignored）
    namespace: default
    node-port-range: 30000-32767
    ensure-timeout: 5m
    # target-ip: 0.0.0.0                  # arthas 绑定地址（NodePort 可达，R4）
    # arthas-boot-jar: tools/arthas-boot.jar
    # mcp-port: 8563
    # arthas-version: 4.3.0
    # arthas-password: arthas-mcp-gateway # 生产应覆盖
  # === portal 管理面能力开关（004） ===
  admin:
    crud:
      enabled: true                       # 后端配置 CRUD（/admin/backends）
    export:
      enabled: true                       # 异步任务结果导出 + 列表（/admin/tasks）

# 宪法原则五：Actuator 暴露网关健康与状态
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always

logging:
  level:
    com.arthas.gateway: INFO
```

### 45.1 字段速查（含默认 + 说明）

| 字段 | 默认 | 来源 | 说明 |
|------|------|------|------|
| `server.port` | `8761` | application.yml | HTTP 端口 |
| `server.address` | `0.0.0.0` | application.yml | 绑定地址 |
| `spring.ai.mcp.server.name` | `arthas-mcp-gateway` | application.yml | serverInfo.name |
| `spring.ai.mcp.server.protocol` | `STREAMABLE` | application.yml | 传输 |
| `spring.ai.mcp.server.streamable-http.mcp-endpoint` | `/mcp` | application.yml | MCP 端点路径 |
| `arthas-gateway.backends-file` | `config/backends.yaml` | GatewayProperties.java | 后端映射表 |
| `arthas-gateway.task.backend-timeout` | `11m` | GatewayProperties.Task | 异步兜底超时 |
| `arthas-gateway.task.result-ttl` | `1h` | GatewayProperties.Task | 任务保留 |
| `arthas-gateway.task.global-max-inflight` | `null`（动态=后端数×5） | GatewayProperties.Task | 全局背压 |
| `arthas-gateway.k8s.kubeconfig` | `test-env/k8s/kubeconfig/k3s-admin.yaml` | GatewayProperties.K8s | kubeconfig |
| `arthas-gateway.k8s.namespace` | `default` | GatewayProperties.K8s | 默认命名空间 |
| `arthas-gateway.k8s.node-port-range` | `30000-32767` | GatewayProperties.K8s | NodePort 范围 |
| `arthas-gateway.k8s.ensure-timeout` | `5m` | GatewayProperties.K8s | ensure 全流程超时 |
| `arthas-gateway.k8s.target-ip` | `0.0.0.0` | GatewayProperties.K8s | arthas 绑定（R4） |
| `arthas-gateway.k8s.arthas-boot-jar` | `tools/arthas-boot.jar` | GatewayProperties.K8s | 静态工具文件 |
| `arthas-gateway.k8s.mcp-port` | `8563` | GatewayProperties.K8s | pod 内 arthas MCP 端口 |
| `arthas-gateway.k8s.arthas-version` | `4.3.0` | GatewayProperties.K8s | arthas 版本 |
| `arthas-gateway.k8s.arthas-password` | `arthas-mcp-gateway` | GatewayProperties.K8s | arthas 鉴权密码（生产应覆盖） |
| `arthas-gateway.admin.crud.enabled` | `true` | GatewayProperties.Admin | CRUD 开关 |
| `arthas-gateway.admin.export.enabled` | `true` | GatewayProperties.Admin | 导出/列表开关 |
| `management.endpoints.web.exposure.include` | `health,info` | application.yml | Actuator 暴露 |
| `management.endpoint.health.show-details` | `always` | application.yml | 健康详情 |

### 45.2 后端映射表（`config/backends.yaml`）

```yaml
version: 1                              # 单调递增，热重载去重
backends:
  - name: order-service                 # 逻辑名（= target 参数取值，唯一）
    url: http://10.0.0.10:8563          # arthas MCP 根 URL（无 /mcp 后缀，T009 实测）
    protocol: STREAMABLE                # STREAMABLE | STATELESS
    auth:
      mode: NONE                        # NONE | BEARER | BASIC
    connectTimeoutMs: 5000              # 默认 5000
    callTimeoutMs: 30000                # 默认 30000（对齐 SC-003）
    maxConcurrentTasks: 5               # ∈ [1,5]（后端硬上限）
  - name: payment
    url: http://10.0.0.11:8563
    protocol: STREAMABLE
    auth:
      mode: BEARER
      token: ${PAYMENT_TOKEN}           # 环境变量占位（机密不入库）
```

---

## 第 46 章 38 工具完整清单

### 46.1 网关自有（4，`arthas-gateway.*`，GATEWAY_LOCAL）

| 工具 | 参数 | 用途 |
|------|------|------|
| `arthas-gateway.list-targets` | 无 | 列全部 target（name/state/healthy/protocol） + version |
| `arthas-gateway.task-get` | `taskId`（必填） | 查单任务状态 + 结果（按 status 分支渲染） |
| `arthas-gateway.task-list` | `status`（可选） | 列任务概要 |
| `arthas-gateway.task-cancel` | `taskId`（必填） | 取消任务（WORKING→CANCELLED，终态幂等） |

### 46.2 arthas 即时诊断（25，SYNC_DIRECT + 1 STREAM_AGGREGATE，每个带 `target`）

| 工具 | 说明 |
|------|------|
| `jvm` | JVM 信息（MACHINE-NAME/VM-VERSION/线程/堆/GC/OS） |
| `thread` | 线程详情（含死锁检测） |
| `dashboard` | 仪表盘（STREAM_AGGREGATE，SSE 多帧聚合） |
| `sc` | 查类（Search Class） |
| `sm` | 查方法（Search Method） |
| `jad` | 反编译类 |
| `ognl` | 执行 OGNL 表达式 |
| `getstatic` | 查静态属性 |
| `setstatic`（如有）/ `memory` | 内存 |
| `heapdump` | 堆 dump |
| `classloader` | 类加载器 |
| `sysprop` | 系统属性 |
| `sysenv` | 环境变量 |
| `vmoption` | VM 选项 |
| `vmtool` | VM 工具（强制 GC 等） |
| `mbean` | MBean |
| `mc` | Memory Compiler |
| `dump` | dump class byte |
| `redefine` | 重定义类 |
| `retransform` | retransform |
| `monitor` | （异步，见下） |
| `options` | arthas 选项 |
| `perfcounter` | 性能计数器 |
| `profiler` | profiler |
| `stack` | （异步，见下） |
| `trace` | （异步，见下） |
| `tt` | （异步，见下） |
| `version` | arthas 版本 |
| `viewfile` | 查看文件 |
| `watch` | （异步，见下） |

> 完整 31 arthas 工具名取自 `src/main/resources/arthas-tools.json`（摘抄自 arthas 4.3.0），工具数清单实测见 `InitializeAndToolsListContractTest`。

### 46.3 arthas 长任务（5，ASYNC_TASK，OPTIONAL，每个带 `target`）

| 工具 | 说明 |
|------|------|
| `watch` | 观察方法调用（命中后返 accessPoint/cost/value） |
| `trace` | 追踪调用链 |
| `stack` | 查调用栈 |
| `tt` | TimeTunnel 时间隧道 |
| `monitor` | 监控方法执行统计 |

→ 立即返 `taskId + status:working`，后台虚拟线程执行，`task-get/list/cancel` 跟踪。

### 46.4 K8S 编排（3，`k8s.*`，自带闭包绕过 ToolsCallRouter）

| 工具 | 必填参数 | 用途 |
|------|----------|------|
| `k8s.list-pods` | `namespace?` | 列 pod（含 hasJvm/hasShell 探测） |
| `k8s.list-services` | `namespace?` | 列 service（含 NodePort） |
| `k8s.ensure-arthas-mcp` | `server`, `pod`, `namespace?` | 原子幂等供给（注入 arthas + NodePort + 注册） |

### 46.5 实测 38 工具名（claude -p tools/list 捕获）

```
arthas-gateway_list-targets, arthas-gateway_task-cancel, arthas-gateway_task-get,
arthas-gateway_task-list, classloader, dashboard, dump, getstatic, heapdump, jad,
jvm, k8s_ensure-arthas-mcp, k8s_list-pods, k8s_list-services, mbean, mc, memory,
monitor, ognl, options, perfcounter, profiler, redefine, retransform, sc, sm,
stack, stop, sysenv, sysprop, thread, trace, tt, version, viewfile, vmoption,
vmtool, watch
```

> 注：Claude Code 把工具名中的 `.` 显示为 `_`（如 `arthas-gateway_list-targets`、`k8s_ensure-arthas-mcp`），实际网关暴露名为 `arthas-gateway.list-targets`、`k8s.ensure-arthas-mcp`（带点）。

---

## 第 47 章 错误码 / reason 速查

### 47.1 JSON-RPC 标准码（McpErrorCodes.java:19）

| 常量 | 值 | 含义 |
|------|-----|------|
| `PARSE_ERROR` | -32700 | JSON 解析错误 |
| `INVALID_REQUEST` | -32600 | 非法请求 |
| `METHOD_NOT_FOUND` | -32601 | 未知工具名 |
| `INVALID_PARAMS` | -32602 | 参数非法（target 缺失/不在册/熔断/限流/...） |
| `INTERNAL_ERROR` | -32603 | 内部错误 |

### 47.2 reason 字段（data.reason，机器可读）

#### 诊断路径（ToolsCallRouter 翻译）

| reason | 触发 | data |
|--------|------|------|
| `backend_unreachable` | target 熔断 OPEN / 不可达 | `target, retryAfterMs, available` |
| `concurrency_limit` | per-target 并发越界 | `target, maxConcurrentTasks` |
| `global_concurrency_limit` | 全局背压越界 | `target, globalMaxInflight, available` |
| `stateless_unsupported_async` | STATELESS 后端异步调用 | `target, available` |
| `unknown_target` | target 不在册 | `target, available` |
| `backend_timeout` | 异步后台兜底超时（task.error.reason） | — |
| `backend_unreachable` | 异步后台不可达（task.error.reason） | — |

#### K8S 编排路径（ensure 失败，K8sToolHandlers）

| reason | 触发 | stage |
|--------|------|-------|
| `no_jvm` | pod 内无 JVM（jps 无输出） | `locate_jvm` |
| `no_shell` | pod 无 shell（distroless） | `locate_jvm`/`install_arthas` |
| `attach_failed` | 上传 arthas 失败 / 启动失败 | `install_arthas`/`start_arthas` |
| `health_check_timeout` | arthas MCP 健康检查超时（含 loopback 不可达） | `health_check` |
| `nodeport_alloc_failed` | NodePort 分配失败 | `expose_nodeport` |
| `name_conflict` | 动态名 ∩ 静态种子名 / 同名异 URL | `register` |
| `k8s_forbidden` | RBAC 403 | `locate_jvm`/`install_arthas`/... |
| `k8s_unreachable` | K8S API 不可达 | — |

#### portal 管理面（AdminExceptionHandler）

| reason | 触发 | 状态码 |
|--------|------|--------|
| `backend_not_found` | 未知后端名 | 404 |
| `duplicate_name` | 新增 name 重复 | 400 |
| `missing_name` / `missing_url` | 必填缺失 | 400 |
| `dynamic_backend_not_editable` | POST/PUT 动态后端 | 400 |
| `admin_io_error` | YAML IO 失败 | 500 |
| `task_not_found` | 未知任务 | 404 |
| `task_not_completed` | 导出未完成任务 | 409 |

---

## 第 48 章 关键参数速查

| 参数 | 值 | 来源 |
|------|-----|------|
| 熔断失败阈值 | 3 次 | `CircuitBreaker.DEFAULT_FAILURE_THRESHOLD` |
| 熔断 base 退避 | 1s | `DEFAULT_BASE_BACKOFF` |
| 熔断 max 退避 | 30s | `DEFAULT_MAX_BACKOFF` |
| HALF_OPEN 探测数 | 1 个 | `allowRequest` HALF_OPEN 分支 |
| per-target 并发上限 | 5（∈[1,5]） | `BackendConfig.maxConcurrentTasks` |
| 全局背压动态默认 | 后端数 × 5 | `TaskInfrastructureConfig` |
| 兜底超时 | 11min | `GatewayProperties.Task.backendTimeout` |
| 退役宽限 | =backendTimeout (11min) | `BackendConfigWatcher` |
| TTL | 1h | `GatewayProperties.Task.resultTtl` |
| 防抖 | 500ms | `BackendConfigWatcher.DEBOUNCE` |
| 连接超时 | 5000ms | `BackendConfigLoader.DEFAULT_CONNECT_TIMEOUT_MS` |
| 调用超时 | 30000ms | `BackendConfigLoader.DEFAULT_CALL_TIMEOUT_MS` |
| K8S exec 探测超时 | 5s | `K8sPodExplorer.PROBE_TIMEOUT` |
| arthas attach 超时 | 300s | `ArthasProvisioner.ATTACH_TIMEOUT` |
| arthas 健康检查超时 | 90s | `ArthasProvisioner.DEFAULT_HEALTH_CHECK_TIMEOUT` |
| ensure 全流程超时 | 5min | `GatewayProperties.K8s.ensureTimeout` |
| NodePort 范围 | 30000-32767 | `GatewayProperties.K8s.nodePortRange` |
| pod 内 arthas MCP 端口 | 8563 | `GatewayProperties.K8s.mcpPort` |
| arthas 版本 | 4.3.0 | `GatewayProperties.K8s.arthasVersion` |
| HTTP 默认端口 | 8761 | `application.yml` |
| MCP 端点 | `/mcp` | `spring.ai.mcp.server.streamable-http.mcp-endpoint` |
| 工具总数 | 38（31 arthas + 4 网关 + 3 K8S） | `InitializeAndToolsListContractTest.S-TL-1` |
| taskId 格式 | `t-` + 6 hex | `AsyncTaskExecutor.defaultTaskIdGenerator` |
| 隔离阈值（SC-003） | 30s | spec SC-003 |
| 热重载感知（SC-002） | ≤30s | spec SC-002 |
| 端到端（SC-001） | 5min | spec SC-001 |
| MCP 规范版本 | `2024-11-05` / `2025-11-25` | `InitializeAndToolsListContractTest.S-INIT-1` |
| 前端 node 版本 | v22.22.0 | `pom.xml` frontend-maven-plugin |

---

## 第 49 章 文件索引

### 49.1 主代码（`src/main/java/com/arthas/gateway/`）

#### config（装配，组合根）
- `config/GatewayProperties.java` — 顶层 `@ConfigurationProperties`
- `config/GatewayMcpServerConfig.java` — MCP 服务端 + 38 工具 + capabilities
- `config/TaskInfrastructureConfig.java` — TaskStore + AsyncTaskExecutor
- `config/BackendRegistryBootstrap.java` — 启动期装配注册表
- `config/BackendConfigWatcherConfig.java` — 热重载装配
- `config/DynamicRegistrationConfig.java` — 动态注册装配（003）
- `config/K8sOrchestrationConfig.java` — K8S 编排装配（003）

#### backend（后端管理，gateway-core）
- `backend/BackendConfig.java` — 不可变配置 record（含 Auth 脱敏）
- `backend/BackendConfigLoader.java` — YAML 解析（纯逻辑，严格整数）
- `backend/BackendEntry.java` — **统一拦截层**（execute/admit/invoke/isHealthy）
- `backend/BackendClient.java` — 客户端契约接口
- `backend/HttpBackendClient.java` — 官方 SDK 封装
- `backend/CircuitBreaker.java` — per-target 熔断状态机
- `backend/BackendRegistry.java` — 不可变快照 record
- `backend/RegistryHolder.java` — AtomicReference 持有者
- `backend/BackendEntryFactory.java` — 构造三件套
- `backend/BackendConfigWatcher.java` — WatchService 热重载
- `backend/BackendRegistryReloader.java` — 静态 diff 纯逻辑
- `backend/RegistryComposer.java` — 静态∪动态合并
- `backend/DynamicBackendStore.java` — 动态 target 入口（003）
- `backend/Protocol.java` / `BackendState.java` / `Source.java` / `AuthMode.java` — 枚举
- 异常：`CircuitOpenException` / `ConcurrencyLimitException` / `StatelessAsyncException` / `BackendUnreachableException` / `BackendConfigException`

#### handler（路由，gateway-core）
- `handler/ToolsCallRouter.java` — tools/call 唯一入口
- `handler/GatewayToolHandlers.java` — 4 自有工具本地处理
- `handler/DiagnosticRequest.java` — 瞬态值对象（target 解析/剥离）
- `handler/McpJson.java` — JSON 全局单例
- `handler/McpErrorCodes.java` — JSON-RPC 错误码常量

#### tool（工具元数据，gateway-core）
- `tool/StaticToolRegistry.java` — 启动期构建 35 工具
- `tool/ExposedTool.java` — 工具不可变 record
- `tool/RoutingMode.java` — 路由模式枚举
- `tool/TaskSupport.java` — 任务支持枚举

#### task（异步任务，gateway-core）
- `task/AsyncTaskExecutor.java` — 方案 C 异步编排（虚拟线程 + 全局背压）
- `task/TaskStore.java` — ConcurrentHashMap + TTL 清理
- `task/GatewayTask.java` — 应用层任务实体
- `task/TaskState.java` — 状态枚举
- `task/TaskError.java` — 错误原因
- `task/GlobalConcurrencyLimitException.java` — 全局背压越界异常

#### auth（认证，gateway-core）
- `auth/BackendAuthCustomizer.java` — 出站头注入
- `auth/GatewayAuthenticator.java` — 入站鉴权接口
- `auth/NoopGatewayAuthenticator.java` — MVP Noop

#### obs（可观测，gateway-core）
- `obs/BackendRegistryHealthIndicator.java` — 健康端点

#### orchestration（K8S 编排，003）
- `orchestration/K8sClientFactory.java` — kubeconfig → fabric8
- `orchestration/K8sExec.java` — exec 同步化
- `orchestration/K8sExecException.java`
- `orchestration/K8sPodExplorer.java` — list-pods + 探测
- `orchestration/ArthasProvisioner.java` — ensure 全流程
- `orchestration/NodePortExposer.java` — NodePort Service
- `orchestration/OrchestrationRecord.java` — 供给记录状态机
- `orchestration/OrchestrationRecordStore.java` — 内存记录
- `orchestration/K8sToolRegistry.java` — 3 工具规格
- `orchestration/K8sToolHandlers.java` — 3 工具处理器
- `orchestration/K8sEnabledCondition.java` — 装配条件

#### admin（portal 后端，004）
- `admin/backend/BackendAdminController.java`
- `admin/backend/BackendAdminService.java`
- `admin/backend/BackendsYamlWriter.java`
- `admin/backend/BackendCrudAutoConfig.java`
- `admin/backend/dto/`（BackendDto / CreateBackendRequest / UpdateBackendRequest）
- `admin/backend/exception/`（BackendNotFoundException / BackendConflictException / BackendAdminException）
- `admin/task/TaskExportController.java`
- `admin/task/TaskExportService.java`
- `admin/task/TaskListService.java`
- `admin/task/TaskExportAutoConfig.java`
- `admin/task/dto/`（TaskExportDto / TaskSummaryDto / TaskListPageDto）
- `admin/task/exception/`（TaskNotFoundException / TaskNotCompletedException）
- `admin/AdminExceptionHandler.java`（全局错误体）
- `admin/SpaConfig.java`（Vue Router fallback）

### 49.2 测试代码（`src/test/java/com/arthas/gateway/`）

#### 契约（双侧）
- `contract/server/InitializeAndToolsListContractTest.java`（S-INIT/S-TL）
- `contract/server/GatewayToolsContractTest.java`（G-*）
- `contract/server/ListTargetsContractTest.java`
- `contract/server/ToolsCallRoutingContractIT.java`（S-CALL/S-ERR）
- `contract/client/BackendClientContractIT.java`（C-INIT/C-CALL）
- `contract/client/FaultIsolationContractIT.java`（C-CB-1/2、C-LIMIT-1、C-ISO-1）

#### 集成
- `integration/ResultConsistencyIT.java`（SC-005 A/B）
- `integration/HotReloadIT.java`（SC-002）
- `integration/AsyncTaskContractIT.java` / `AsyncTaskTimeoutIT.java`
- `integration/FailedTargetErrorIT.java`（SC-003）
- `integration/ArthasMcpBackendIT.java`

#### K8S 编排（003）
- `orchestration/K8sEnsureContractIT.java`
- `orchestration/K8sListToolsContractIT.java`
- `orchestration/ArthasProvisionerIT.java`
- `orchestration/K8sExternalGatewaySmokeTest.java`（SC-001 端到端，assumeTrue 跳过）

#### portal（004）
- `admin/AdminCapabilitySwitchTest.java` / `AdminCapabilitySwitchIT.java`
- `admin/AdminExportSwitchIT.java`（INV-LIST-4）
- `admin/backend/BackendAdminServiceTest.java` / `BackendAdminContractIT.java`
- `admin/backend/dto/BackendDtoTest.java`
- `admin/backend/BackendsYamlWriterTest.java`
- `admin/task/TaskExportServiceTest.java` / `TaskExportContractIT.java`
- `admin/task/TaskListServiceTest.java` / `TaskListContractIT.java`
- `admin/task/dto/TaskSummaryDtoTest.java` / `TaskExportDtoTest.java`

#### 架构
- `architecture/PackageBoundaryTest.java`（gateway-core 零 K8S/admin 依赖）

#### 测试夹具
- `testfixtures/ArthasMcpBackend.java`（拉起真实 arthas MCP 后端）
- `testfixtures/DemoBusinessApp.java`（真实业务 JVM）
- `testfixtures/OrderService.java` / `OrderResult.java`
- `testfixtures/McpClientHarness.java`（官方 SDK client 封装）
- `testfixtures/FakeBackendClient.java`

#### 单测（其余）
- `backend/`（BackendConfigLoaderTest / CircuitBreakerTest / BackendEntryTest / RegistryComposerTest / DynamicBackendStoreTest / ...）
- `handler/`（ToolsCallRouterTest / GatewayToolHandlersTest / DiagnosticRequestTest / ...）
- `tool/`（StaticToolRegistryTest / ...）
- `task/`（GatewayTaskTest / TaskStoreTest / AsyncTaskExecutorTest / AsyncTaskExecutorShutdownRaceTest / TaskStoreGetReadAmplificationTest / GlobalBackpressureTest / ...）

### 49.3 冒烟/脚本
- `smoke/SmokeDemoLauncher.java`（双后端一键拉起）
- `smoke/SmokeMcpClient.java` / `SmokeWatchAsync.java`
- `smoke/gateway-start.sh` / `gateway-stop.sh` / `r4-bind-test.sh`

### 49.4 K8S 测试床（`test-env/k8s/`）
- `setup.sh` / `teardown.sh` / `on-debian`
- `demo-pod.yaml` / `Dockerfile.demo`
- `kubeconfig/k3s-admin.yaml`（gitignored，敏感）

### 49.5 前端（`web/`）
- `package.json` / `vite.config.ts` / `tsconfig.json`
- `src/main.ts` / `App.vue` / `router.ts`
- `src/api/adminClient.ts`
- `src/views/BackendListView.vue` / `TaskExportView.vue`
- `src/components/BackendForm.vue` / `HealthBadge.vue` / `DownloadButton.vue`
- `src/__tests__/`（vitest）

### 49.6 静态工具/资源
- `tools/arthas-boot.jar`（arthas 4.3.0 静态工具文件，入 git 例外）
- `src/main/resources/application.yml`
- `src/main/resources/arthas-tools.json`（31 arthas 工具 schema 摘抄）
- `src/main/resources/static/`（vite build 产物，gitignored）
- `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`（mock-maker-inline，mock final）

### 49.7 配置
- `config/backends.yaml`（种子，入 git）
- `config/backends-runtime.yaml`（SmokeDemoLauncher 动态端口重写，gitignored）
- `config/backends-local.yaml`（本地凭证覆盖，gitignored）

### 49.8 文档
- `README.md`
- `docs/getting-started.md`（新环境启动 + 验证）
- `docs/test-fixtures.md`（测试夹具使用）
- `docs/handbook/`（本手册）
- `docs/superpowers/specs/`（头脑风暴设计）
- `docs/code-review/`（代码评审报告）
- `.specify/memory/constitution.md`（宪法）
- `specs/001..004-*/`（spec-kit SDD 产出：spec/research/plan/tasks/data-model/contracts/quickstart）
- `reference/arthas-docs/`（上游 arthas 文档摘抄）

---

## 第 50 章 治理与流程

### 50.1 宪法版本

`.specify/memory/constitution.md` v1.2.0（2026-06-19）—— 8 条核心原则 + 技术与传输约束 + 开发流程与质量门禁 + 治理。

### 50.2 工作流（CLAUDE.md）

- **设计阶段**：`superpowers:brainstorming` 头脑风暴 → 归档 `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`。
- **实施阶段**：spec-kit SDD（`/speckit-specify` → `/speckit-plan` → `/speckit-tasks` → `/speckit-implement`），**测试先于实现**（TDD 红-绿-重构）。
- **查询代码**：优先 `codebase-memory-mcp`（知识图谱）。

### 50.3 真实性硬约束（CLAUDE.md，不可妥协）

1. **真实环境**：每次测试至少启动一个真实 arthas MCP + 一个真实业务服务；诊断数据由触发业务真实调用产生；**禁桩**（WireMock/Mock 模拟成功响应）。故障类用真实故障条件（停后端/错 token/真实 sleep/真实并发越界）。
2. **驱动分层**：
   - 工具可用性 → 真实 Claude Code 走 MCP（`claude -p --mcp-config`）。
   - 结果一致性 + 双侧协议契约 → 官方 MCP Java SDK client（`McpClientHarness`）。
   - 除网关健康检查（Actuator）外，不裸 curl MCP。

### 50.4 git 分支模型

- `master`（基线）
- `001-arthas-mcp-gateway` / `002-code-review-remediation` / `003-k8s-arthas-mcp-launch` / `004-portal-backend-management`（特性分支，逐特性合并）

---

> **手册完**。如需查具体实现，按「文件索引」定位 → 读对应 `file:line`；如需启动/验证，看 `getting-started.md`；如需理解夹具，看 `test-fixtures.md`；如需设计意图，看 `specs/<feature>/spec.md`。
