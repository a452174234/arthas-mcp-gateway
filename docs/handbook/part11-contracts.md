# Part 11 · 完整契约参考（全部断言逐条）

> 本部分汇总 4 特性 `contracts/` 的全部断言（S-*/C-*/G-*/K-ENS-*/INV-* 等），逐条说明：断言 ID、含义、验证方式、对应测试。这是「契约字典」——查任一不变量的完整定义。

---

## 第 85 章 服务端契约 S-*（网关↔Claude Code）

> 来源：`specs/001-arthas-mcp-gateway/contracts/server-contract.md`。验证：`InitializeAndToolsListContractTest` / `ToolsCallRoutingContractIT`。

### 85.1 初始化握手 S-INIT

| 断言 | 含义 | 验证 |
|------|------|------|
| **S-INIT-1** | `initialize` 响应含 `protocolVersion`（锁定 `2024-11-25`，兼容 `2024-11-05`） | `InitializeAndToolsListContractTest` 协议版本回显 |
| **S-INIT-2** | `serverInfo.name = "arthas-mcp-gateway"`、`version = "0.1.0"`；`capabilities = 仅 tools(listChanged=false)`，不声明 prompts/resources/logging/completions | 同上；`GatewayMcpServerConfig.gatewayCapabilitiesCustomizer` 锁定 |
| **S-INIT-3** | `instructions = null`（MVP 不提供说明文档） | 同上 |

### 85.2 工具列表 S-TL

| 断言 | 含义 | 验证 |
|------|------|------|
| **S-TL-1** | `tools/list` 返回 **38** 工具（31 arthas + 4 自有 + 3 K8S） | `InitializeAndToolsListContractTest.s_tl_1_toolsCountIs38` |
| **S-TL-2** | 每 arthas 工具 inputSchema 含 `target`（string, required，进 properties.required 与顶层 required） | 同上 |
| **S-TL-3** | 剥离 target 后，schema 逐字等于 arthas 原始 baseline（不改写 arthas 定义，原则二） | 同上 |
| **S-TL-4** | 工具定义不含内部字段 `taskSupport`/`execution`/`routingMode`（不泄漏到协议） | 同上 |
| **S-TL-5** | `nextCursor = null`（工具集静态，无分页） | 同上 |

### 85.3 工具调用 S-CALL

| 断言 | 含义 | 验证 |
|------|------|------|
| **S-CALL-1** | `tools/call` 按 `target` 路由到对应后端，结果原样透传 | `ToolsCallRoutingContractIT.s_call` |
| **S-CALL-2** | `target` 参数被剥离，**不进 backendArgs**（避免后端 INVALID_PARAMS） | 同上 |
| **S-CALL-3** | 多客户端并发调用互不干扰 | `FaultIsolationContractIT.c_iso_1` |

### 85.4 错误传播 S-ERR

| 断言 | 含义 | 验证 |
|------|------|------|
| **S-ERR-1** | `target` 缺失/空/非 String → `INVALID_PARAMS(-32602)` | `DiagnosticRequest.requireTarget` |
| **S-ERR-2** | `target` 不在册 → `INVALID_PARAMS + data.available=[在册目标]` | `ToolsCallRoutingContractIT.s_err_2` |
| **S-ERR-3** | 未知工具名 → `METHOD_NOT_FOUND(-32601)` | 同上 s_err_3 |
| **S-ERR-4** | 后端业务错误（isError=true/McpError）原样透传（不吞为成功） | `BackendEntry.invoke` McpError 分支 |
| **S-ERR-5** | 后端不可达/熔断 → `INVALID_PARAMS + data.{reason=backend_unreachable, retryAfterMs, available}`，30s 内返回（不等后端超时） | `FaultIsolationContractIT.c_cb_1` / `FailedTargetErrorIT` |

---

## 第 86 章 客户端契约 C-*（网关↔arthas 后端）

> 来源：`specs/001-arthas-mcp-gateway/contracts/backend-client-contract.md`。验证：`BackendClientContractIT` / `FaultIsolationContractIT`。

### 86.1 初始化 C-INIT

| 断言 | 含义 | 验证 |
|------|------|------|
| **C-INIT-1** | 首次路由时 `initialize` 握手（懒连接，启动期不连） | `BackendRegistryBootstrap` 构造不连 |
| **C-INIT-2** | 握手幂等（已握手 isInitialized=true，二次不抛） | `BackendClientContractIT.c_init` |
| **C-INIT-3** | 并发首次路由同一后端，恰好一次握手（DCL） | `BackendEntry.initializeOnce` P1-4 |

### 86.2 调用 C-CALL / 结果 C-RESULT

| 断言 | 含义 | 验证 |
|------|------|------|
| **C-CALL-1** | `callTool(name, args)` 转发到后端，args 不含 target | `BackendClientContractIT.c_call` |
| **C-CALL-2** | 出站认证头按 `auth.mode` 注入（BEARER/BASIC/NONE） | `BackendAuthCustomizer` |
| **C-RESULT-1** | 返回的 `CallToolResult`（content/isError/_meta）原样，不篡改 | `BackendClientContractIT.c_result` |
| **C-RESULT-2** | 后端 `isError=true` 原样保留（业务错误不转成功） | `BackendEntry.invoke` recordSuccess |

### 86.3 故障隔离 C-CB / C-LIMIT / C-ISO

| 断言 | 含义 | 验证（真实故障条件） |
|------|------|----------------------|
| **C-CB-1** | 基础设施故障（连接拒绝/超时/initialize 失败/SSE 中断）计入熔断；连 3 次→OPEN；第 4 次立即返 S-ERR-5（`elapsedMs<2000`，不发连接） | `FaultIsolationContractIT.c_cb_1`（`http://127.0.0.1:9` 关闭端口） |
| **C-CB-2** | 业务错误（isError/McpError）不计熔断（连 5 次>阈值仍 CLOSED） | 同上 c_cb_2（ognl 非法表达式） |
| **C-LIMIT-1** | per-target 并发 > `maxConcurrentTasks(5)` → `INVALID_PARAMS + concurrency_limit`（前置限流，不越界打后端） | 同上 c_limit_1（5 watch 持槽 + 第 6 个） |
| **C-ISO-1** | 单后端故障/慢不波及其他（独立连接池/线程） | 同上 c_iso_1（order 占资源时 payment jvm `elapsedMs<10000`） |

### 86.4 认证 C-AUTH

| 断言 | 含义 |
|------|------|
| **C-AUTH-1** | BEARER token = 后端配置 password；BASIC = base64(user:pass) | `BackendAuthCustomizer.forBackend` |

---

## 第 87 章 自有工具契约 G-*（4 工具）

> 来源：`specs/001-arthas-mcp-gateway/contracts/gateway-tools-contract.md`。验证：`GatewayToolsContractTest`。

### 87.1 异步接受 G-ASYNC

| 断言 | 含义 |
|------|------|
| **G-ASYNC-1** | 异步工具（watch/trace/stack/tt/monitor）`tools/call` 立即返 `{taskId, status:"working", _meta:{toolName,target}}`（不阻塞） |

### 87.2 list-targets G-LT

| 断言 | 含义 |
|------|------|
| **G-LT-1** | `list-targets` 返 `{targets:[{name,state,healthy,protocol}], version}`；healthy = `state==ACTIVE && breaker!=OPEN`（单一事实源） |

### 87.3 task-get G-TG

| 断言 | 含义 |
|------|------|
| **G-TG-1** | working → `{taskId, status:"working", toolName, target, createdAt}`（无 result） |
| **G-TG-2** | completed → 含 `result`（原样，isError=true 保留，G-TG-2）；failed 仅基础设施故障（backend_timeout/backend_unreachable） |
| **G-TG-3** | failed → `{taskId, status:"failed", error:{reason, message}}` |
| **G-TG-4** | cancelled → `{taskId, status:"cancelled"}` |
| **G-TG-5** | 未知 taskId → `INVALID_PARAMS` |

### 87.4 task-list G-TL

| 断言 | 含义 |
|------|------|
| **G-TL-1** | `task-list [status?]` 返 tasks 概要列表（可按 status 过滤） |

### 87.5 task-cancel G-TC

| 断言 | 含义 |
|------|------|
| **G-TC-1** | cancel working → `status:"cancelled"`（中断后台） |
| **G-TC-2** | cancel 终态任务 → 幂等返当前状态（不报错） |
| **G-TC-3** | cancel 不计熔断（避免误开） |

---

## 第 88 章 K8S 编排契约 K-*（003）

> 来源：`specs/003-k8s-arthas-mcp-launch/contracts/k8s-orchestration-tools-contract.md` + `dynamic-registration-invariants.md`。验证：`K8sEnsureContractIT` / `ArthasProvisionerIT` / `K8sExternalGatewaySmokeTest`。

### 88.1 ensure 行为 K-ENS

| 断言 | 含义 | 真实故障条件 |
|------|------|--------------|
| **K-ENS-1** | ensure 真实 pod（含 JVM）→ `status:ready` + 可达 mcpUrl + target 进注册表（list-targets 可见，source=DYNAMIC） | 真实 demo-business pod |
| **K-ENS-2** | 重复 ensure（已 ready 且健康）→ `status:reused`，零副作用 | 二次 ensure |
| **K-ENS-3** | 用返回 target 调诊断 → 捕获**该 pod JVM** 真实诊断（与直连一致） | watch/jvm |
| **K-ENS-4** | pod 内无 JVM（jps 无输出）→ `reason=no_jvm, stage=locate_jvm`，未注册 | 无 JVM pod |
| **K-ENS-5** | pod 无 shell（distroless）→ `reason=no_shell` | distroless 镜像 |
| **K-ENS-6** | kubeconfig 无 exec 权限（RBAC 403）→ `reason=k8s_forbidden` | RBAC 限制 |
| **K-ENS-7** | arthas 绑 loopback（`--target-ip 127.0.0.1`）→ NodePort 不可达 → `reason=health_check_timeout` | 注入 loopback + 短超时 |
| **K-ENS-8** | target 命名确定性 `{server}-{pod}` | deriveLogicalName |
| **K-ENS-9** | 动态名 ∩ 静态种子名 → `reason=name_conflict, stage=register` | 同名静态种子 |
| **K-ATOMIC-1** | 任一子步失败 → 不调 register，注册表不含该 target（不半注册） | doProvision try/catch |

### 88.2 动态注册不变量 D-* / I-*

| 断言 | 含义 |
|------|------|
| **D-REG-1** | register 须 source=DYNAMIC |
| **D-REG-2** | 动态名 ∩ 静态种子 → 拒绝（保护静态） |
| **D-REG-4** | 同名异 URL → 拒绝 |
| **D-REG-5** | 同名同 URL 同字段 → 幂等（无回调） |
| **D-COEXIST-1** | 动态 target 存在时模拟静态热重载 → 动态 target 仍在（关键） |
| **D-COEXIST-2** | pod 删除（SC-003）→ 网关 30s 标 unhealthy + 隔离；其他 target 不受影响 |
| **D-ATOMIC-1** | compose 前后 in-flight 调用持有的 Entry 引用不变 |
| **D-VERSION-1** | 同 version 热重载 → unchanged（不重建）；sameEffective → 不 getAndSet |
| **I-1** | 一次 tools/call 全程持固定 BackendEntry 引用，registry 替换不影响 in-flight |
| **I-2** | 热重载不误删动态 target（compose 合并静态∪动态） |
| **I-3** | 动态名 ∩ 静态种子 → 拒绝（命名冲突保护） |

---

## 第 89 章 002 整改不变量（remediation-invariants）

> 来源：`specs/002-code-review-remediation/contracts/remediation-invariants.md`。验证：各整改测试。

### 89.1 资源/关闭竞态（P0）

| 断言 | 含义 |
|------|------|
| **R-P0-1** | 外层 pool.submit 拒绝 → store.remove(taskId) + onTerminal + releaseGlobalInflight（不留僵尸 WORKING） |
| **R-P0-2** | 后台编排/提交失败 → 槽由 onTerminal 释放（不泄漏，目标不锁死） |

### 89.2 熔断/并发（P1）

| 断言 | 含义 |
|------|------|
| **R-P1-1** | 熔断器三方法 synchronized（并发下不丢失更新） |
| **R-P1-2** | STATELESS 后端异步路径前置拒绝（不提交后台） |
| **R-P1-3** | 异步路径驱动熔断；cancel 中断 + 业务错误不计 |
| **R-P1-4** | initialize DCL（并发首次恰好一次握手） |

### 89.3 长生命周期/健壮性（P2）

| 断言 | 含义 |
|------|------|
| **R-P2-1** | 退役宽限 = backendTimeout（11min），in-flight 可完成 |
| **R-P2-2** | DiagnosticRequest 容忍 null value（不抛 NPE） |
| **R-P2-3** | TaskStore.get 不触发全表清理（O(1)） |
| **R-P2-4** | 异步执行全局背压（cap 动态 = 后端数×5） |

### 89.4 安全卫生（P3）

| 断言 | 含义 |
|------|------|
| **R-P3-1** | Auth.toString 脱敏（`****XX`） |
| **R-P3-2** | 健康单一事实源（BackendEntry.isHealthy） |
| **R-P3-3** | McpJson 全局单例 |
| **R-P3-4** | asInt 严格（拒浮点/超界，保留原始值） |
| **R-P3-5** | 无死代码/等价复制 |

---

## 第 90 章 portal 不变量 INV-*（004）

> 来源：`specs/004-portal-backend-management/contracts/admin-invariants.md`。验证：各 portal IT + ArchUnit。

### 90.1 隔离与开关

| 断言 | 含义 |
|------|------|
| **INV-ISOL-1** | `/admin` 不影响 `/mcp` 38 工具（ArchUnit 规则 3 守护诊断核心不依赖 admin） |
| **INV-SWITCH-1** | `crud.enabled=false` → `/admin/backends/*` 404，但 `/admin/tasks/*` + `/mcp` 不受影响 |
| **INV-SWITCH-2** | `export.enabled=false` → `/admin/tasks/*/export` 404，但 `/admin/backends/*` 正常 |
| **INV-LIST-4** | `export.enabled=false` → `/admin/tasks`（列表）与 `/{id}/export`（导出）**同 404** |

### 90.2 任务列表（增量）

| 断言 | 含义 |
|------|------|
| **INV-LIST-1** | `TaskSummaryDto` 禁含 frames（摘要纯） |
| **INV-LIST-2** | `total` = 过滤后、分页前的总数（与 items 分页独立） |
| **INV-LIST-3** | items 按 createdAt 倒序（最新在前） |

### 90.3 CRUD 与导出

| 断言 | 含义 |
|------|------|
| **INV-FILE-1** | 静态 CRUD 经「写 backends.yaml → 热重载」，不直接调 BackendRegistry.rebuild |
| **INV-EXP-1** | 导出 frames 与 task-get 逐字一致（不篡改/截断）；前端 downloadTaskExport 浏览器原生 attachment |
| **INV-DYN-1** | POST/PUT 动态后端 → 400 + `dynamic_backend_not_editable`；DELETE 允许 |
| **INV-ERR-1** | 错误显式传播（结构化 4xx/5xx + reason，不静默 200） |
| **INV-SECRET-1** | BackendDto 仅 authMode，不回显 token/username/password |

### 90.4 前端

| 断言 | 含义 |
|------|------|
| **INV-WEB-1** | 前端内嵌单 JAR、同源 fetch（无 CORS）；SpaConfig forward history 模式 |
| **INV-WEB-2** | 前端仅展示层（fetch+render+download），核心逻辑 Java 后端（ArchUnit 守护） |

---

## 第 91 章 成功标准 SC-*（4 特性）

### 91.1 001 SC

| SC | 含义 |
|----|------|
| **SC-001** | 单一网关地址，≥3 目标各完成一次诊断，结果来自正确目标 |
| **SC-002** | 增删目标 ≤30s 网关感知（热重载） |
| **SC-003** | 单目标不可达 ≤30s 返明确错误；其他目标不受影响 |
| **SC-004** | 多客户端并发互不干扰 |
| **SC-005** | 网关转发结果与直连一致（无篡改） |

### 91.2 003 SC

| SC | 含义 |
|----|------|
| **SC-001** | 给定 K8S，选定 JVM pod，≤5min 完成「启动+暴露+纳管」并诊断该 pod |
| **SC-002** | 全新 Linux 离线脚本构造 K8S，可复现 |
| **SC-003** | 动态 target pod 重启/驱逐 → ≤30s 标 unhealthy + 明确错误；恢复可重纳管 |
| **SC-004** | portal 全链路（加服务器→发现→启→纳管→诊断） |
| **SC-005** | Windows 一键启 arthas + 注册 |
| **SC-006** | 动态 target 与静态共存，命名可区分 |

### 91.3 004 SC

| SC | 含义 |
|----|------|
| **SC-001** | portal CRUD，list-targets ≤30s 感知 |
| **SC-002** | portal 下载 completed 任务，frames 原样与 task-get 一致 |
| **SC-003** | portal 健康与 /actuator/health 一致 |
| **SC-004** | 管理面操作不影响 /mcp 38 工具 |
| **SC-005** | ./mvnw verify 出含前端单 JAR |
| **SC-006**（增量） | portal /tasks 列表/过滤/分页/点项导出端到端 |

---

## 第 92 章 断言 → 测试 → 代码 三向追溯

| 断言 | 测试 | 代码锚点 |
|------|------|----------|
| S-INIT-2 | InitializeAndToolsListContractTest | GatewayMcpServerConfig.gatewayCapabilitiesCustomizer:133 |
| S-TL-1（38） | 同上 s_tl_1 | StaticToolRegistry + K8sToolRegistry |
| S-TL-2（target 注入） | 同上 s_tl_2 | StaticToolRegistry.buildArthasTool:84 |
| S-CALL-1 | ToolsCallRoutingContractIT | ToolsCallRouter.forwardSync:99 |
| S-ERR-2 | 同上 s_err_2 | ToolsCallRouter.resolveTarget:228 |
| C-CB-1 | FaultIsolationContractIT c_cb_1 | CircuitBreaker.recordFailure:106 |
| C-CB-2 | 同上 c_cb_2 | BackendEntry.invoke:156（McpError recordSuccess） |
| C-LIMIT-1 | 同上 c_limit_1 | BackendEntry.admitCore:132 |
| C-ISO-1 | 同上 c_iso_1 | BackendEntryFactory.create（独立三件套） |
| G-TG-2 | GatewayToolsContractTest | BackendEntry.invoke（isError 原样） |
| K-ENS-1 | K8sEnsureContractIT | ArthasProvisioner.ensure:145 |
| K-ENS-7 | 同上（loopback 用例） | ArthasProvisioner.probeHealthy:306 |
| K-ATOMIC-1 | 同上 | ArthasProvisioner.doProvision:181（try/catch） |
| D-COEXIST-1 | RegistryComposerTest | RegistryComposer.compose:47 + BackendConfigWatcher.applyCompose:212 |
| INV-LIST-1 | TaskListContractIT summaryHasNoFrames | TaskSummaryDto:16 |
| INV-LIST-2 | 同上 pagination | TaskListService:61 |
| INV-EXP-1 | TaskExportContractIT | TaskExportService:37 |
| INV-DYN-1 | BackendAdminContractIT | BackendAdminService.update:95 |
| INV-SWITCH-1/2 | AdminCapabilitySwitchTest/IT | @ConditionalOnProperty on Controllers |
| INV-WEB-1 | （构建 + 浏览器） | vite.config.ts + SpaConfig + adminClient.ts |
| 包边界 | PackageBoundaryTest | ArchUnit 3 规则 |

---

> **下一步**：Part 12 运维与开发指南（部署/监控/排障/扩展点）。
