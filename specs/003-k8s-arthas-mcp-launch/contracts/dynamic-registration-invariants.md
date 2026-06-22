# 契约：程序化动态注册不变量（BackendConfig.source / DynamicBackendStore / RegistryComposer）

**Feature**: 003-k8s-arthas-mcp-launch | **Date**: 2026-06-22
**界面角色**：定义"动态 target 如何进入网关注册表并与静态种子 + 热重载正确共存"的**内部不变量**。非对外 MCP 工具契约（工具契约见 [k8s-orchestration-tools-contract.md](./k8s-orchestration-tools-contract.md)）；本文件约束 `ensure-arthas-mcp` 内部注册子行为 + 与 001 既有热重载的并发正确性。
**宪法依据**：原则二（透明无损聚合——动态 target 与静态 target 路由等价、可发现、可区分）、原则三（局部故障韧性——动态 target 同等纳管）、原则七（TDD）。
**设计依据**：[设计 §6](../../../docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md)。数据实体见 [data-model §2–§5](../data-model.md)，决策见 [research.md R8](../research.md)。

---

## 1. 来源标记（BackendConfig.source）

每个 `BackendConfig` 带 `source ∈ {STATIC, DYNAMIC}`：

| source | 来源 | 受热重载增删？ | 受 register/unregister？ |
|---|---|---|---|
| `STATIC` | `config/backends.yaml` 种子 | 是（`BackendRegistryReloader` 重读 YAML） | 否 |
| `DYNAMIC` | 程序化 API（`ensure-arthas-mcp` 触发） | 否（热重载只重读 static） | 是（`DynamicBackendStore`） |

**向后兼容**：YAML 不写 `source` 视为 STATIC；001 既有 `backends.yaml` **零改动**即可用。

**可发现 + 可区分（原则二）**：`list-targets` 读 `RegistryHolder.current()`（effective = static ∪ dynamic），动态 target 与静态 target 共同可见；可选用 source 标记区分来源（边缘情况"命名冲突可区分"）。

---

## 2. 注册表结构（静态∪动态合并）

```
static 来源（YAML）──── BackendConfigLoader ──► BackendRegistryReloader(diff) ──┐
                                                                                  │
dynamic 来源 ──────── DynamicBackendStore(register/unregister/list) ────────────┤
                                                                                  ▼
                                                              RegistryComposer.compose(static, dynamic)
                                                                                  │ effective = static ∪ dynamic
                                                                                  ▼ RegistryHolder.getAndSet（原子替换）
                                                              RegistryHolder (AtomicReference) ── current() ── ToolsCallRouter
```

**effective registry = 静态快照 ∪ 动态快照**，经 `RegistryHolder.getAndSet` **原子替换**（复用 001 §4 的 AtomicReference 整体替换语义）。

---

## 3. 不变量（I-*）

### I-1 原子替换（复用 001 §3）
effective registry 经 `AtomicReference.getAndSet` 整体替换（非增量修改）。一次 `tools/call` 全程持有固定的 `BackendEntry` 引用——registry 在调用中途被替换不影响 in-flight 调用（**热重载/动态注册并发不串台**）。

### I-2 热重载不误删动态 target（research.md R8 关键正确性）
静态热重载（`BackendRegistryReloader`）**只**重读 YAML、更新 static 来源；compose 用「新 static + 现有 dynamic」重算 effective。故：**热重载后动态 target 仍在注册表**（不会被误删）。反之，动态 register/unregister 只更新 dynamic 来源、不触达 YAML 文件。

### I-3 命名冲突策略（设计 §6.2）
- 动态注册名 ∩ **静态种子名** → **拒绝**注册（抛 `BackendConfigException`），保护静态配置。→ `ensure` 返 `reason:name_conflict`。
- 动态注册名 ∩ **既有动态名**：
  - 同名 **同 URL** 且后端健康 → **幂等复用**（`status:reused`，零副作用）。
  - 同名 **异 URL** → **拒绝**注册（`reason:name_conflict`）。
- 静态种子内部重名 → 001 既有行为（保留旧表、记 ERROR）。

### I-4 ensure 原子性（设计 §4.1）
`ensure-arthas-mcp` 任一子步失败 → **不**调用 `DynamicBackendStore.register`（注册表不含该 target，不半注册）。仅全部子步成功才 register + compose swap。已打的 pod label / 已建的 NodePort Service 作为可清理副作用记录于 `OrchestrationRecord.error`。

### I-5 动态 target 同等纳管（原则三）
动态 target 经 `BackendEntryFactory.create` 生成与静态 target **同构**的 `BackendEntry`（含 `HttpBackendClient` + `CircuitBreaker` + `taskSlots`）。诊断复用 `ToolsCallRouter` + `AsyncTaskExecutor`，熔断/限流/健康/异步任务语义**完全一致**。pod 消亡 → 该 target 熔断隔离，不影响其他 target（K-COEXIST-2）。

### I-6 复用减少重连（复用 001 reloader diff）
`RegistryComposer.compose` 复用 `BackendRegistryReloader` 的 diff 思路：static∪dynamic 中同 name 同 config 的 Entry **复用旧实例**（保连接池/session），仅 added/changed 新建、toRetire 下线。

### I-7 version 单调（去重）
effective registry 的 `version` 单调递增（static.version 与 dynamic 序列号的合成 max）。重复触发 compose（无实际变更）→ 调用方据此跳过 getAndSet（复用 001 §2 version 去重）。

---

## 4. 操作语义

### 4.1 `DynamicBackendStore.register(BackendConfig cfg)`
1. 强制 `cfg.source = DYNAMIC`（非 DYNAMIC 拒绝）。
2. 冲突检测（I-3）：与 static 种子名冲突 / 与既有动态同名异 URL → 抛 `BackendConfigException`。
3. 写入 `ConcurrentHashMap`（覆盖/新增）。
4. 通知 `RegistryComposer.compose()` → 原子 swap effective。

### 4.2 `DynamicBackendStore.unregister(String name)`
1. 仅 DYNAMIC 可移（STATIC 经热重载；试图移 STATIC → 拒绝/无操作）。
2. 从 `ConcurrentHashMap` 移除（不存在 → 幂等无操作）。
3. 通知 compose → 原子 swap（被移 target 的 Entry 进 toRetire 优雅下线，in-flight 可完成）。

### 4.3 `RegistryComposer.compose(staticReg, dynamicCfgs)`
1. 合并：static 的 Entry 全保留 + dynamic 每个 cfg 经 `BackendEntryFactory.create`（unchanged 复用旧 Entry，I-6）。
2. version = 合成单调值（I-7）。
3. 返回新 `BackendRegistry`；调用方 `RegistryHolder.getAndSet`（I-1）+ 异步下线未复用旧 Entry。

### 4.4 静态热重载集成（既有 `BackendRegistryReloader` 调整）
- 原：`reloader.reload` → 直接 `holder.getAndSet`。
- 改：`reloader.reload` 产出新 static registry → 交 `composer.compose(newStatic, dynamicStore.list())` → `holder.getAndSet`。
- diff/复用/下线逻辑**不变**；仅 swap 前多一步合并 dynamic（I-2）。

---

## 5. 契约测试断言点（动态注册层 · surefire 波次 A，纯逻辑零 K8S）

| ID | 断言 |
|---|---|
| D-REG-1 | `register(DYNAMIC cfg)` 后 `RegistryHolder.current()` 含该 target；`list-targets` 可见 |
| D-REG-2 | `register` 与**静态种子同名** → 抛 `BackendConfigException`（拒绝，I-3） |
| D-REG-3 | 同名同 URL 二次 `register` → 幂等（无异常，target 仍在，Entry 复用） |
| D-REG-4 | 同名**异** URL `register` → 抛 `BackendConfigException`（I-3） |
| D-UNREG-1 | `unregister(DYNAMIC)` 后 effective 不含该 target；其 Entry 进优雅下线 |
| D-UNREG-2 | `unregister` 一个 STATIC 名 → 拒绝/无操作（静态只经热重载） |
| D-UNREG-3 | `unregister` 不存在的名 → 幂等无操作 |
| D-COEXIST-1 | 动态 target 存在时，模拟静态 YAML 热重载（`reloader.reload` 新 static）→ **动态 target 仍在** effective（I-2，关键） |
| D-COEXIST-2 | 静态热重载 + 动态 register 并发 → effective 始终为合法 static∪dynamic 合并；无半合并、无丢失（I-1/I-2） |
| D-ATOMIC-1 | compose 前后，一次模拟 `tools/call` 持有的 `BackendEntry` 引用不变（in-flight 不串台，I-1） |
| D-SOURCE-1 | YAML 解析缺省 `source` → STATIC；显式 `source: STATIC` → STATIC；动态注册强制 DYNAMIC |
| D-VERSION-1 | 无变更的重复 compose → 跳过 getAndSet（version 去重，I-7） |

> 波次 A 全程 surefire（纯逻辑、无 K8S、无 arthas），与 001 的 `BackendRegistryTest`/`BackendRegistryReloaderTest` 同范式。**这些不变量是动态纳管正确性的地基，须先于波次 B/C 的真实供给测试通过**（TDD 测试先于实现）。

---

## 6. 与 001 既有语义的兼容性

- `BackendRegistry`/`BackendEntry`/`RegistryHolder`/`BackendRegistryReloader`/`BackendEntryFactory` **语义不变**；仅 `BackendRegistryReloader` 的 swap 前增 composer 合并一步（I-2）。
- `BackendConfig` 增 `source` 字段，缺省 STATIC → 001 既有 YAML / 测试**零改动**通过（向后兼容）。
- `list-targets` 读 `RegistryHolder.current()` 自动反映动态 target，无需改 handler（可在 view 增 source 字段作可选增强）。
- 既有 35 工具、双侧契约、回归测试**不得回归**（D-COEXIST-* 守护并发正确性，既有 `BackendRegistryReloaderTest`/`HotReloadIT` 继续通过）。
