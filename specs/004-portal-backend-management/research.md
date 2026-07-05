# Research: portal 后端管理平台（004 特性）

> Phase 0 先决研究（宪法原则八）。每条决策援引既有代码证据，列备选与权衡。本文为 `plan.md` 的依据、`tasks.md` 的实施基础。

---

## R1. 入口：picocli 双入口路由（serve / portal）

**决策**：`GatewayApplication.main` 改为 picocli `CommandLine` 路由——
- `serve`（默认，无子命令或显式 `serve`）→ `SpringApplication.run`（启动网关：诊断面 `/mcp` + 管理面 `/admin`）。
- `portal <sub>` → **不启动 Spring**，picocli 执行 CLI 命令（`GatewayAdminClient` HTTP 调 `/admin`），执行后 `System.exit`。

**理由（证据）**：`GatewayApplication` 现状 = `SpringApplication.run(GatewayApplication.class, args)`（`src/main/java/com/arthas/gateway/GatewayApplication.java:20-22`），标准 Spring Boot 入口、无 `CommandLineRunner`。picocli 在 `main` 路由是官方推荐模式（picocli `execute` 返回退出码）。portal CLI 是轻量 HTTP client，无需启动整个 Spring 上下文（启动 Spring 网关只为调 `/admin` 过重）。

**备选（否决）**：
- picocli-spring-boot-starter + `CommandLineRunner`：portal 子命令也须启动 Spring（~2s 启动开销 + 完整上下文），违背 CLI 轻量。
- 手写 `args` 解析：无 `--help`/补全/退出码规范，子命令增多后维护成本高。

**依赖**：`info.picocli:picocli:4.7.6`（Java CLI 标杆，自动 `--help`/man-page/补全/退出码；与 Spring Boot 无冲突）。

---

## R2. backends.yaml 写回：SnakeYAML dump 重写（不保留注释）

**决策**：MVP 静态后端 CRUD 的 POST/PUT/DELETE 用 **SnakeYAML `Yaml.dump` 重写整个 `config/backends.yaml`**（结构化写回），**不保留原文注释**；注释保留列为演进项。

**理由（证据）**：
- `BackendConfigLoader` 已用 **SnakeYAML**（`org.yaml.snakeyaml.Yaml`，Spring Boot 传递带入、无新依赖）`load(in)` 只读解析（`BackendConfigLoader.java:3,59`）。
- SnakeYAML 的 `dump` **不保留注释**（技术事实：SnakeYAML 的 Dumper/Representer 不携带 comment 节点）。
- 热重载读**结构**（version + backends 数组），**不依赖注释**——注释是文档性、非功能性。
- 保留注释需换 `snakeyaml-engine` 或手写 YAML 字符串模板，复杂度高、脆弱，违背 YAGNI。

**spec 假设偏离（宪法原则八如实记录）**：spec 假设原写"写回保留既有种子格式与注释"——经 Phase 0 调研，MVP 调整为"SnakeYAML dump 重写、不保留注释"，spec 假设条目同步修订（见 spec.md 假设段）。

**备选（否决）**：
- `snakeyaml-engine`（保留注释能力更强）：换 YAML 库，与既有 Loader 不一致，引入兼容面。
- 模板化写回（手写 YAML 字符串）：脆弱（缩进/转义/特殊字符），维护成本高。

**写回正确性约束**：
- 写回须保留 `version`（热重载去重所需，`BackendConfigLoader.readVersion`）。
- 写回须保留 `${ENV:default}` 占位符（机密字段不落明文，`BackendConfigLoader.resolvePlaceholder`）——但 dump 会把占位符当字面字符串保留（因 Loader 解析时已展开为值，**写回前须从原始 BackendConfig 重建占位符或保留原值**）。MVP 简化：写回时 token 等机密字段若来自环境占位符，写回其**当前解析值**（受控内网可接受，且 spec 已记此为已知限制）；占位符还原后置。

---

## R3. 动态后端 CRUD 语义（source=DYNAMIC）

**决策**：
- **POST `/admin/backends`（增动态）**：**拒绝**（400）。动态后端由 003 `k8s.ensure-arthas-mcp` 产生（身份 = `{server}-{pod}` 派生），不应手动构造。
- **PUT `/admin/backends/{name}`（改动态）**：**拒绝**（400）。动态后端配置由 ensure 决定，手动改会与 ensure 状态不一致；须先 DELETE 再重新 ensure。
- **DELETE `/admin/backends/{name}`（删动态）**：允许 = `DynamicBackendStore.unregister(name)`（003 已实现，D-UNREG-*）。运维移除失效动态 target 的合理路径。

**理由（证据）**：`DynamicBackendStore`（003 `src/main/java/com/arthas/gateway/backend/DynamicBackendStore.java`）提供 `register/unregister`，强制 `source=DYNAMIC`、冲突检测 I-3。动态后端的身份与配置由 `ArthasProvisioner.ensure` 派生（`{server}-{pod}` + mcpUrl + Bearer），手动 POST/PUT 会破坏 ensure 的纳管一致性。

**备选（否决）**：允许 POST 动态（手动注册任意动态 target）——与 ensure 语义重叠，且 MVP 无独立"手动动态注册"场景，YAGNI。

---

## R4. /admin 与 /mcp 隔离

**决策**：`/admin` 用 Spring Web MVC `@RestController @RequestMapping("/admin")`（`BackendAdminController`/`TaskExportController`）；`/mcp` 由 `spring-ai-mcp-server-webmvc` 注册的 MCP servlet 承载。两者经 Spring 路径路由隔离，互不影响。

**理由（证据）**：诊断面 `/mcp` 由 spring-ai MCP server 装配（`GatewayMcpServerConfig`）；Spring Web MVC 的 `@RestController` 按路径前缀独立路由。`/admin` 不进 MCP 工具集（38 工具静态不变，`InitializeAndToolsListContractTest` 守护），不污染 MCP 协议（宪法原则一）。

**备选（否决）**：把管理面也做成 MCP 工具（`admin.*` 工具）——管理面是人类 CRUD/导出，非诊断、非 agentic，做成 MCP 工具违背"工具集静态 + 诊断聚合"语义（宪法原则二）。

---

## R5. 任务导出序列化（原样透传）

**决策**：`GET /admin/tasks/{taskId}/export` → `TaskStore.get(taskId)` → `GatewayTask` → `TaskExportDto`（Jackson 序列化 JSON，`Content-Disposition: attachment; filename=<taskId>.json`）。`frames[]` **原样来自 `GatewayTask` 的结果 content**，不篡改/摘要/截断（宪法原则二）。仅 `completed` 任务可导出（`working`/`cancelled`/不存在 → 409/404）。

**理由（证据）**：`TaskStore`（`src/main/java/com/arthas/gateway/task/TaskStore.java`）`get(taskId)` 返 `Optional<GatewayTask>`，`GatewayTask` 含 `taskId/status/tool/target/createdAt/completedAt` + 结果帧（`task-get` 已暴露此结构）。Jackson 由 Spring Boot 传递带入（无新依赖）。

**备选（否决）**：直接返 `GatewayTask` JSON（不经 DTO）——DTO 显式契约（字段稳定、版本化）更好，避免内部 record 演化泄漏到导出格式。

---

## R6. CLI HTTP client（portal → /admin）

**决策**：`GatewayAdminClient` 用 **`java.net.http.HttpClient`**（JDK 21 内置）调网关 `/admin`；HTTP 状态码 → picocli 退出码 + 错误信息（`2xx=0`、`4xx=1`、`5xx=2`、连接失败=3）。

**理由**：JDK 内置、零新依赖、支持 HTTP/1.1 连接池；portal CLI 不启 Spring，故不用 `RestClient`（Spring 6 带但需 Spring 上下文）。

**备选（否决）**：
- Spring `RestClient`：需 Spring 上下文（portal 不启）。
- OkHttp/Apache HttpClient：新增依赖，无必要。

**配置**：网关地址经 CLI 参数 `--gateway-url`（默认 `http://localhost:8761`）或环境变量。

---

## R7. 静态后端 CRUD × 热重载协作

**决策**：静态后端 POST/PUT/DELETE → `BackendsYamlWriter` 写回 `config/backends.yaml` → 既有 `BackendConfigWatcher`（WatchService）感知文件变更 → `BackendRegistryReloader` 热重载（复用 001 SC-002，30s 内生效）。portal **不直接调** `BackendRegistry`（文件是 source of truth，绕过会破坏热重载一致性）。

**理由（证据）**：001 已实现完整热重载链（`BackendConfigWatcher` WatchService + `BackendRegistryReloader` diff/复用 + `RegistryHolder` 原子 swap + `RegistryComposer` 合并动态）。portal 写 YAML 即触发既有机制，**零改动**复用。

**并发**：MVP 管理面单用户，`BackendsYamlWriter` 写回串行化（无文件锁）；多 portal 并发改 YAML 不在 MVP 范围（spec 已记此假设）。

**备选（否决）**：portal 直接调 `BackendRegistry.rebuild`（绕过文件）——破坏"文件 = source of truth"，重启后状态不一致。

---

## R8. 鉴权与受控内网（Noop）

**决策**：MVP `/admin` 无鉴权（与 001 `/mcp` 一致，复用 `GatewayAuthenticator` Noop 语义，受控内网部署）。Bearer token 鉴权为演进项（与 001 演进首要项"网关侧认证"一并落地）。

**理由**：001 spec 假设§107"受控内网、MVP 无认证"；管理面虽涉及变更操作，但同受控内网信任域；MVP 不引入鉴权复杂度。

**备选（否决）**：管理面单独 Bearer token filter——MVP 阶段引入 token 管理 + 配置，与 001 鉴权演进项重复，后置统一。

---

## R9. 能力按需开关（Spring 条件装配）

**决策**：后端 CRUD 与任务导出各为独立子包（`admin/backend`、`admin/task`），各自 `@ConditionalOnProperty` 装配——`arthas-gateway.admin.crud.enabled`（默认 `true`）、`arthas-gateway.admin.export.enabled`（默认 `true`）。关闭某开关时该能力的 controller/service/bean 不注册、端点不暴露（404）。

**理由**：用户要求"能力独立模块、可按需组合"。Spring Boot 条件装配是实现此语义的标准机制——零运行时开销、配置驱动、无需 Maven 多模块。两能力独立子包 + 独立开关，使用方可按需启用（如只要导出、不要 CRUD：`arthas-gateway.admin.crud.enabled=false`）。

**备选（否决）**：
- Maven 多模块（`admin-crud`/`admin-export` 各一 jar）：物理隔离更强，但构建复杂度上升（003 R1 原定多模块后置）；MVP 条件装配已满足"按需组合"。
- 全部默认启用不可关：违背"按需组合"诉求。

**实现**：`BackendCrudAutoConfig` / `TaskExportAutoConfig` 各带 `@ConditionalOnProperty(name=..., havingValue="true", matchIfMissing=true)`；开关关闭时该子包的 `@RestController`/`@Service` 不被装配。契约测试断言开关开/关时端点存在/不存在。

---

## 待实测 / 演进项（透明记录）

- **YAML 注释保留**：MVP dump 重写丢注释；演进可换 snakeyaml-engine 或模板化写回。
- **占位符还原**：MVP 写回机密字段为当前解析值；演进可保留 `${ENV}` 占位符。
- **动态后端持久化（B）**：MVP 重启丢失；演进落盘 + 重启恢复。
- **操作审计（D）**：演进加 ensure 历史 + CRUD 审计查询。
- **任务导出多格式/分页**：CSV/HTML/流式后置。
- **管理面鉴权**：Bearer token，与 001 演进项统一。
