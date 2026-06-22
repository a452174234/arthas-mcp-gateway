# Data Model 增量：K8S 目标 arthas MCP 启动与纳管

**Feature**: 003-k8s-arthas-mcp-launch | **Date**: 2026-06-22

> 本文档定义本特性相对 [001 data-model](../001-arthas-mcp-gateway/data-model.md) 的**增量实体/字段/状态机/校验**。001 既有的 `BackendConfig`/`BackendEntry`/`BackendRegistry`/`RegistryHolder`/`BackendRegistryReloader`/`ExposedTool`/`GatewayTask`/`CircuitBreaker` 等语义**不变**，本文仅记新增与受影响处。决策依据见 [research.md](./research.md)，行为契约见 `contracts/`。

---

## 1. 实体增量总览

| 实体 | 类型 | 职责 | 生命周期 |
|---|---|---|---|
| `BackendConfig.source` | 配置态（字段增量） | 后端来源标记（STATIC/DYNAMIC） | 随 BackendConfig |
| `Source` | enum | `STATIC` \| `DYNAMIC` | — |
| `DynamicBackendStore` | 运行期 | 动态 target 内存态（register/unregister/list） | 进程级，优雅关闭 |
| `RegistryComposer` | 运行期 | static ∪ dynamic 合并 → effective `BackendRegistry`，原子替换 | 进程级 |
| `K8sToolRegistry` | 启动期 | 3 个编排工具的 `ExposedTool`（不可变快照） | 进程级只读 |
| `K8sToolHandlers` | 运行期 | `list-pods`/`list-services`/`ensure-arthas-mcp` 处理器 | 进程级 |
| `OrchestrationRecord` | 运行期 | 一次 ensure 供给的结构化可观测记录（原则五） | 进程级（可加 TTL） |
| `OrchestrationRecordStore` | 运行期 | 供给记录内存态（Map） | 进程级 |

> 既有 `BackendRegistry`/`BackendEntry`/`RegistryHolder`/`BackendRegistryReloader`/`BackendEntryFactory` **语义不变**；`RegistryHolder` 持有的 effective 快照现由 `RegistryComposer` 产出（静态∪动态合并）。

---

## 2. BackendConfig 字段增量：`source`

在 001 `BackendConfig`（name/url/protocol/auth/超时/并发）之上**新增**：

| 字段 | 类型 | 必填 | 说明 / 校验 |
|---|---|---|---|
| `source` | `Source` | 否（缺省 `STATIC`） | `STATIC`（`backends.yaml` 种子）/ `DYNAMIC`（程序化 API 注册）。不参与 equals 的"复用判定"核心但参与可观测（list-targets 区分来源）。**向后兼容**：YAML 不写 source 视为 STATIC。 |

**校验**：`source` 非 null（紧凑构造器缺省 STATIC）；动态注册路径必为 DYNAMIC（由 `DynamicBackendStore.register` 强制）。其余字段校验（name 唯一、url 合法、auth 对应、超时正、并发 ∈[1,5]）复用 001 §2/§11，**不变**。

**`BackendConfigLoader` 增量**：解析 YAML 时读可选 `source`，缺省 STATIC；与 001 解析/校验逻辑其余一致。

---

## 3. Source（enum，新）

| 值 | 含义 |
|---|---|
| `STATIC` | 源自 `config/backends.yaml` 种子，受热重载增删 |
| `DYNAMIC` | 源自程序化 `register` API（`ensure-arthas-mcp` 触发），不受 YAML 热重载直接影响（热重载只重读 static） |

---

## 4. DynamicBackendStore（运行期，动态 target 内存态，新）

程序化写入动态 target 的唯一入口（`ensure-arthas-mcp` 经此注册；`RegistryComposer` 经此读动态集合）。

| 操作 | 说明 |
|---|---|
| `register(BackendConfig cfg)` | 写入/更新一个动态 target（cfg.source 强制 DYNAMIC）；与静态种子名冲突 → 抛 `BackendConfigException`（拒绝，保护静态）；与既有动态同名同 URL → 幂等（健康复用）；同名异 URL → 抛冲突错。写入后触发 `RegistryComposer.compose()` |
| `unregister(String name)` | 移除一个动态 target（仅 DYNAMIC 可移；STATIC 经热重载）；不存在 → 幂等无操作。触发 compose |
| `list()` | 当前动态 `BackendConfig` 不可变快照 |
| `get(String name)` | 取单个（缺失返 empty） |

**线程安全**：内部 `ConcurrentHashMap`；变更后通知 composer 重算（compose 内部用 `RegistryHolder.getAndSet` 原子替换，复用 001 原子性保证）。

---

## 5. RegistryComposer（运行期，静态∪动态合并，新）

把 static 来源（热重载维护）与 dynamic 来源（`DynamicBackendStore`）合并为 effective `BackendRegistry`，经 `RegistryHolder.getAndSet` 原子替换。

| 操作 | 说明 |
|---|---|
| `compose(BackendRegistry staticReg, Collection<BackendConfig> dynamic)` | 合并为新 effective registry：static 的 Entry 全保留 + dynamic 每个 cfg 经 `BackendEntryFactory.create` 新建 Entry（unchanged 者复用旧 Entry，保连接池）。version 取 `max(static.version, dynamicSeq++)` 单调递增。返回新 `BackendRegistry` |
| `swap(BackendRegistry next)` | `RegistryHolder.getAndSet(next)` 原子替换；旧快照的未复用 Entry 异步优雅下线（复用 001 退役宽限语义） |

**触发时机**：
- 静态热重载（`BackendRegistryReloader` 产出新 static registry）→ composer 用新 static + 现有 dynamic 重算。
- 动态 register/unregister → composer 用现有 static + 新 dynamic 重算。

**不变量（复用 001 §3）**：一次 `tools/call` 全程持有固定的 `BackendEntry` 引用；effective registry 替换不影响 in-flight 调用。

> **既有 `BackendRegistryReloader` 改动**：原直接 `RegistryHolder.getAndSet` 改为先交 composer（携当前 dynamic）合并再 swap。diff/复用逻辑不变。

---

## 6. 关系图（在 001 §10 之上）

```text
  config/backends.yaml                         ensure-arthas-mcp 调用
        │ 加载/热重载                                  │ register(cfg)
        ▼                                              ▼
  BackendConfigLoader ──► static Backends        DynamicBackendStore
        │ BackendRegistryReloader (diff)                │
        ▼                                              │
  static BackendRegistry ─────────────┐    ┌───────────┘
                                       ▼    ▼
                                 RegistryComposer ── compose(static, dynamic)
                                       │ effective = static ∪ dynamic
                                       ▼ RegistryHolder.getAndSet（原子替换）
                                 RegistryHolder (AtomicReference)
                                       │ current()
                                       ▼
                                 ToolsCallRouter（gateway-core，零 K8S 感知）

  K8sToolRegistry (启动期, 3 工具) ──► GatewayMcpServerConfig
        │                              （合并 StaticToolRegistry 35 + K8sToolRegistry 3 = 38 specs）
        ▼
  K8sToolHandlers ─┐                   ensure 内部: ArthasProvisioner + NodePortExposer + K8sClientFactory
   list-pods       │  handler 闭包        │
   list-services   │  （不经路由器）        ▼
   ensure-arthas-mcp ──────────────► OrchestrationRecordStore（供给记录, 原则五）
```

---

## 7. K8sToolRegistry 与 3 个编排工具（启动期，新）

3 个 `ExposedTool`，`routingMode=GATEWAY_LOCAL`（无 target 参数；实际不经路由器，handler 自带闭包，见 [research.md R6](./research.md)）。schema/行为见 [contracts/k8s-orchestration-tools-contract.md](./contracts/k8s-orchestration-tools-contract.md)。

| 工具名 | 参数 | 返回 | 处理器 |
|---|---|---|---|
| `k8s.list-pods` | `namespace?`（缺省 default） | pod 清单（名称/命名空间/含可诊断 JVM 标记） | `K8sToolHandlers#listPods`（fabric8 list） |
| `k8s.list-services` | `namespace?` | service 清单 | `K8sToolHandlers#listServices` |
| `k8s.ensure-arthas-mcp` | `server`, `pod`, `namespace?` | `{server}-{pod}`（target 名）；原子幂等供给 | `K8sToolHandlers#ensureArthasMcp` → `ArthasProvisioner` |

**tools/list 并集**：`GatewayMcpServerConfig#mcpToolSpecifications` 合并 StaticToolRegistry（35）+ K8sToolRegistry（3）= **38** specs，注入同一 MCP server（单端点）。capabilities 仍 `tools(listChanged=false)`（工具集静态）。

---

## 8. OrchestrationRecord（运行期，可观测性，新）

一次 `ensure-arthas-mcp` 供给的结构化记录（宪法原则五：网关可被诊断）。

| 字段 | 类型 | 说明 |
|---|---|---|
| `logicalName` | string | `{server}-{pod}`（与注册的 target 名一致） |
| `server` | string | 供给来源服务器名 |
| `pod` | string | 目标 pod 名 |
| `namespace` | string | K8S namespace（缺省 default） |
| `mcpUrl` | string? | 暴露端点 `http://<nodeIP>:<nodePort>`（arthas MCP 根 URL，无 `/mcp`） |
| `serviceRef` | string? | NodePort Service 引用（name/nodePort） |
| `status` | enum | 见 §9 状态机 |
| `error` | object? | 失败原因（failed 时，含 reason/message/阶段） |
| `createdAt` | instant | 供给发起时间（**传入**，非进程内取时——与 001 `GatewayTask.createdAt` 一致，便于测试） |
| `completedAt` | instant? | 完成/失败时间 |

**存储**：`OrchestrationRecordStore`（内存 `ConcurrentHashMap<logicalName, OrchestrationRecord>`，按 logicalName 覆盖最新状态；可加 TTL）。供运维经结构化日志/未来 portal 查询。

---

## 9. OrchestrationRecord 状态机

```text
                  ┌──── 全部子步成功（注入+暴露+健康+注册）─────┐
                  │                                              ▼
  ensuring ───────────────────────────────────────────► ready
     │   │
     │   ├─── 注册表已有且健康（零副作用）──► reused
     │
     └─── 任一子步失败（装失败/attach 超时/NodePort 分配失败/健康检查不过/注册冲突）──► failed
```

| 状态 | 含义 | 终态？ |
|---|---|---|
| `ensuring` | 供给进行中（注入/暴露/健康检查/注册） | 否 |
| `ready` | 新供给完成、已注册且健康 | 是 |
| `reused` | 命中幂等复用（注册表已有且健康） | 是 |
| `failed` | 任一子步失败；**未注册**（不污染注册表） | 是 |

**原子性（设计 §4.1）**：`ensuring→failed` 时**不**写入 `DynamicBackendStore`（不注册）；已打的 pod label / 已建的 Service 作为可清理副作用记录于 `error` 字段供运维追溯。`ensuring→ready` 才注册。

**转换规则**：
- `ensuring→reused`：compose 前查 `DynamicBackendStore` + 健康检查，已存在且健康 → 零副作用返回该 target。
- `ensuring→ready`：全部子步成功，`DynamicBackendStore.register` 完成、composer swap 完成、NodePort 健康检查通过。
- `ensuring→failed`：任一子步抛异常 → 记录失败阶段与原因，不注册。

---

## 10. ensure-arthas-mcp 内部子行为实体（瞬态，编排用）

`ArthasProvisioner.ensure(server, pod, namespace)` 编排的中间产物（非持久实体，记录于此明确语义）：

| 子步 | 产物 | 失败 → |
|---|---|---|
| 查注册表（幂等） | 命中 → reused | — |
| fabric8 exec 进 pod 定位 JVM PID | `pid` | failed（无 JVM/无 java/jps 缺失） |
| 上传/获取 arthas-boot.jar 进 pod | pod 内 `/tmp/arthas-boot.jar` | failed（无 shell/传输失败） |
| exec 启动 arthas MCP（`--attach-only --http-port --target-ip 0.0.0.0`） | arthas agent 注入目标 JVM、服务 mcpPort | failed（attach 超时/端口占用） |
| label pod + create NodePort Service | `serviceRef`、`mcpUrl` | failed（label 冲突/NodePort 分配失败） |
| 内部健康检查（轮询 initialize/listTools） | 就绪确认 | failed（超时未就绪） |
| `DynamicBackendStore.register` + composer swap | effective registry 含新 target | failed（命名冲突） |

**命名派生**：`logicalName = "{server}-{pod}"`（确定性、非人工，spec Q2）。namespace 不进 logicalName（MVP 单集群；多集群后置时再议）。

---

## 11. 校验与边界规则增量

1. **动态注册命名冲突**：动态名 ∩ 静态名 → 拒绝（`BackendConfigException`，保护静态配置）；动态名之间同名同 URL → 幂等复用；同名异 URL → 拒绝。（设计 §6.2）
2. **ensure 原子性**：任一子步失败 → `failed` 且**不注册**（不污染注册表、不半注册）。（设计 §4.1）
3. **热重载不误删动态 target**：`BackendRegistryReloader` 只更新 static 来源；effective 经 composer 合并，动态 target 在热重载后仍存在。（research.md R8）
4. **source 向后兼容**：YAML 不写 `source` 视为 STATIC；既有 `backends.yaml` 零改动即可用。
5. **编排工具不经路由器**：`k8s.*` 工具 handler 闭包直调 `K8sToolHandlers`，`ToolsCallRouter`/`GatewayToolHandlers` 无 `k8s.*` 分支（gateway-core 零 K8S 感知）。
6. **K8S 真实错误显式传播**：无权限（403）/不可达/无 shell/无 JVM → `ensure` 返回结构化错误（INVALID_PARAMS + data.reason），绝不静默成功。（宪法原则五）
7. **诊断对象归属**：`ensure` 经 kubectl exec 进入**目标 pod**注入 arthas、attach **该 pod** 的 JVM PID；诊断结果明确来自被选 pod（spec FR-003、边缘情况"未运行 JVM → 启动失败"）。
8. **tools/list = 38**：35（既有）+ 3（编排），单端点并集；`listChanged=false` 不变。

---

## 12. 受影响的既有实体（语义不变，仅装配路径调整）

- `RegistryHolder`：仍持有 effective `BackendRegistry`、`getAndSet` 原子替换。effective 现由 `RegistryComposer` 产出。
- `BackendRegistryReloader`：diff/复用逻辑不变；swap 前经 composer 合并 dynamic。
- `BackendEntryFactory`：不变；composer 与静态加载共用其 `create`。
- `StaticToolRegistry`：不变（仍 35）；`GatewayMcpServerConfig` 额外合并 `K8sToolRegistry` 的 3 个。
- `GatewayToolHandlers.listTargets`：读 `RegistryHolder.current()`（含动态 target）——**自动**反映动态纳管，无需改动；可选用 source 标记区分来源（增量字段，可选增强）。
