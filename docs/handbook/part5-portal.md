# Part 5 · 004 portal 管理面实现

> 004 是 003 P3 的 portal 子集（v2 Web 前端版）：浏览器访问网关根加载 Vue 3 SPA（内嵌单 JAR），同源调 `/admin` HTTP API，做后端 CRUD + 任务列表查询 + 结果导出。本部分深入后端 + 前端 + 构建 + 不变量。

---

## 第 36 章 portal 架构

### 36.1 设计定位

- **后端 `/admin` HTTP API**（Java）：CRUD + 任务列表/导出，独立于诊断面 `/mcp`（INV-ISOL-1）。
- **前端 SPA**（Vue 3）：**展示层**（fetch + render + download），核心逻辑在 Java 后端（宪法原则六，INV-WEB-2，ArchUnit 守护）。
- **单 JAR 内嵌**：`vite build` → `target/classes/static/`，Spring Boot 同源服务 SPA + `/admin`（无 CORS，INV-WEB-1）。
- **能力开关**：`@ConditionalOnProperty` 按需启用（crud.enabled / export.enabled），默认开。

### 36.2 包结构（`src/main/java/com/arthas/gateway/admin/`）

| 子包 | 职责 |
|------|------|
| `admin/backend/` | 后端 CRUD：`BackendAdminController`/`BackendAdminService`/`BackendsYamlWriter`/`BackendCrudAutoConfig`/`dto/*`/`exception/*` |
| `admin/task/` | 任务：`TaskExportController`/`TaskExportService`/`TaskListService`/`TaskExportAutoConfig`/`dto/*`/`exception/*` |
| `admin/` | 横切：`AdminExceptionHandler`/`SpaConfig` |

### 36.3 数据流

```
[浏览器] http://localhost:8761/backends
   ▼ Spring Boot 服务 static/index.html（SpaConfig forward /backends → /index.html for history 模式）
[Vue SPA 加载] → onMounted → adminClient.listBackends()
   ▼ 同源 fetch /admin/backends（无 CORS）
[BackendAdminController.list()]
   ▼ BackendAdminService.list()
   ▼ RegistryHolder.current().byName() 投影 → List<BackendDto>
   ▼ summary 计数
   ◄─ {backends: [...], summary: {total, healthy, unhealthy}}
[Vue 渲染表格 + 健康徽标]
```

CRUD（POST/PUT/DELETE）→ `BackendAdminService` → 写 `backends.yaml` → 001 WatchService 热重载（≤30s 纳管）。

---

## 第 37 章 后端配置 CRUD（admin/backend）

### 37.1 BackendAdminController — REST 端点

**文件**：`src/main/java/com/arthas/gateway/admin/backend/BackendAdminController.java:31`

类标 `@RestController @RequestMapping("/admin/backends")` + 能力开关 `@ConditionalOnProperty(name="arthas-gateway.admin.crud.enabled", havingValue="true", matchIfMissing=true)`（`:28-30`）—— 关则 Controller 不注册 → 端点 404（INV-SWITCH-1）。

| 方法 | 路径 | 说明 |
|------|------|------|
| `list()`（`:39-49`） | `GET /admin/backends` | 返回 `{backends: [...], summary: {total, healthy, unhealthy}}` |
| `get(name)`（`:51-54`） | `GET /admin/backends/{name}` | → BackendDto；不存在抛 `BackendNotFoundException` |
| `create(req)`（`:56-59`） | `POST` | 201 + Body |
| `update(name, req)`（`:61-64`） | `PUT /admin/backends/{name}` | 动态后端拒绝 |
| `delete(name)`（`:66-70`） | `DELETE` | 204 No Content |

```java
@GetMapping
public Map<String, Object> list() {
    List<BackendDto> backends = service.list();
    long healthy = backends.stream().filter(BackendDto::healthy).count();
    return Map.of("backends", backends,
            "summary", Map.of("total", backends.size(),
                    "healthy", healthy,
                    "unhealthy", backends.size() - healthy));
}
```

### 37.2 BackendAdminService — CRUD 业务编排

**文件**：`src/main/java/com/arthas/gateway/admin/backend/BackendAdminService.java:38`

依赖：`RegistryHolder`（001 注册表只读视图）、`DynamicBackendStore`（003）、`BackendsYamlWriter`、`GatewayProperties.backendsFile`（`:44-48`）。两个构造器：Spring 用（`:50-54`，自建 `BackendConfigLoader` + `Path.of(props.getBackendsFile())`）、测试用（`:57-64`，可注入 loader + 文件路径，便于 `@TempDir`）。

| 方法 | 逻辑 |
|------|------|
| `list()`（`:66-70`） | 从 `registryHolder.current().byName()` 投影成 `BackendDto` |
| `get(name)`（`:72-76`） | `registryHolder.get(name).orElseThrow(...)`，错误带 `availableNames()` |
| `create(req)`（`:78-90`） | 校验 + 写 YAML（version+1） |
| `update(name, req)`（`:92-106`） | 动态拒绝 + 静态 mergeConfig + 写 YAML |
| `delete(name)`（`:108-120`） | 动态→unregister；静态→过滤写回 |

**create（`:78-90`）**：
```java
public BackendDto create(CreateBackendRequest req) {
    String name = requireNonBlank(req.name(), "name");
    requireNonBlank(req.url(), "url");
    if (registryHolder.current().names().contains(name)) {
        throw new BackendConflictException("name 已存在：" + name, "duplicate_name");
    }
    BackendConfig cfg = toBackendConfig(req, name);  // protocol/authMode 缺省 STREAMABLE/NONE，超时缺省 5000/30000/5
    LoadedBackends loaded = loadCurrent();
    List<BackendConfig> updated = new ArrayList<>(loaded.backends());
    updated.add(cfg);
    writeYaml(loaded.version() + 1, updated);  // 写回 → 001 热重载
    return toDto(cfg, "ACTIVE", false, "CLOSED");  // 新建后尚未健康检查
}
```

**update（`:92-106`）**——动态后端拒绝（INV-DYN-1）：
```java
public BackendDto update(String name, UpdateBackendRequest req) {
    BackendEntry entry = registryHolder.get(name)
        .orElseThrow(() -> new BackendNotFoundException(name, availableNames()));
    if (entry.config().source() == Source.DYNAMIC) {
        throw new BackendConflictException("动态后端不可编辑（须先删再 ensure）", "dynamic_backend_not_editable");
    }
    BackendConfig merged = mergeConfig(entry.config(), req);  // null 字段保留旧值
    LoadedBackends loaded = loadCurrent();
    List<BackendConfig> updated = loaded.backends().stream()
        .map(b -> b.name().equals(name) ? merged : b).toList();
    writeYaml(loaded.version() + 1, updated);
    return toDto(merged, entry.state().name(), entry.isHealthy(), entry.breaker().state().name());
}
```

**delete（`:108-120`）**：
```java
public void delete(String name) {
    Optional<BackendEntry> entry = registryHolder.get(name);
    if (entry.isEmpty()) throw new BackendNotFoundException(name, availableNames());
    if (entry.get().config().source() == Source.DYNAMIC) {
        dynamicStore.unregister(name);  // 即时移除
        return;
    }
    // 静态：从 YAML 移除 → 热重载
    LoadedBackends loaded = loadCurrent();
    List<BackendConfig> updated = loaded.backends().stream()
        .filter(b -> !b.name().equals(name)).toList();
    writeYaml(loaded.version() + 1, updated);
}
```

### 37.3 BackendsYamlWriter — SnakeYAML dump 重写

**文件**：`src/main/java/com/arthas/gateway/admin/backend/BackendsYamlWriter.java:25`

`@Component`，持 `new Yaml()`（`:27`）。

**write（`:30-35`）**：
```java
public void write(Path file, long version, List<BackendConfig> backends) throws IOException {
    Map<String, Object> root = new LinkedHashMap<>();  // 保序
    root.put("version", version);
    root.put("backends", backends.stream().map(this::toMap).toList());
    Files.writeString(file, yaml.dumpAsMap(root), StandardCharsets.UTF_8);
}
```

**已知限制（R2）**：不保留原文注释；机密字段写回解析值（占位符还原后置）。

**toMap（`:37-62`）**—— auth 子 map 只在非 null 时写 token/username/password；**`source` 仅 DYNAMIC 显式写**（`:58-60`），STATIC 缺省（向后兼容旧 YAML）。

### 37.4 DTO（record）

| DTO | 字段 | 备注 |
|-----|------|------|
| `BackendDto`（`dto/BackendDto.java:12`） | `name/source/state/healthy/breaker/url/protocol/authMode/connectTimeoutMs/callTimeoutMs/maxConcurrentTasks` | **仅 authMode，不含 token/username/password**（INV-SECRET-1） |
| `CreateBackendRequest`（`dto/CreateBackendRequest.java:10`） | 全字段，超时用 `Integer`（null = 缺省） | — |
| `UpdateBackendRequest`（`dto/UpdateBackendRequest.java:8`） | 可空字段 null = 不改；`name` 在 path 不在 body | — |

### 37.5 异常

| 异常 | 状态码 | reason | 额外字段 |
|------|--------|--------|----------|
| `BackendNotFoundException`（`exception/BackendNotFoundException.java:8`） | 404 | `backend_not_found` | `name`, `available[]` |
| `BackendConflictException`（`exception/BackendConflictException.java:6`） | 400 | 来自异常（`duplicate_name`/`missing_name`/`dynamic_backend_not_editable`） | — |
| `BackendAdminException`（`exception/BackendAdminException.java:6`） | 500 | `admin_io_error` | — |

### 37.6 BackendCrudAutoConfig — 能力开关壳

**文件**：`src/main/java/com/arthas/gateway/admin/backend/BackendCrudAutoConfig.java:17`

空 `@Configuration` + `@ConditionalOnProperty(... admin.crud.enabled ...)`。Controller 自身已带条件注解，本类为后续 Phase 拓展（如 @Bean 注册）保留位置。

---

## 第 38 章 任务列表与导出（admin/task）

### 38.1 TaskExportController — 列表 + 导出端点

**文件**：`src/main/java/com/arthas/gateway/admin/task/TaskExportController.java:30`

类标 `@RequestMapping("/admin/tasks")` + `@ConditionalOnProperty(... admin.export.enabled ...)`（`:27-29`）—— **列表与导出共用此开关**（INV-LIST-4 / INV-SWITCH-2）。

| 方法 | 路径 | 说明 |
|------|------|------|
| `list(status, tool, target, page, size)`（`:41-49`） | `GET /admin/tasks` | 5 可选 `@RequestParam`，`status` 为 `TaskState` 枚举，page/size 缺省 0/20 |
| `export(taskId, format)`（`:51-60`） | `GET /admin/tasks/{taskId}/export?format=json` | `Content-Disposition: attachment; filename="<taskId>.json"` |

```java
@GetMapping("/{taskId}/export")
public ResponseEntity<TaskExportDto> export(@PathVariable String taskId,
        @RequestParam(name="format", required=false, defaultValue="json") String format) {
    TaskExportDto dto = exportService.export(taskId);
    return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + taskId + ".json\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(dto);
}
```

### 38.2 TaskListService — 过滤/排序/分页/isError 映射

**文件**：`src/main/java/com/arthas/gateway/admin/task/TaskListService.java:29`

常量 `MAX_SIZE = 100`（`:32`）。

**list（`:47-69`）**：
1. **clamp 不报 400**：`safePage=max(0,page)`、`safeSize=min(MAX_SIZE, max(1,size))`（`:48-49`）。
2. 取数：status 非空走 `store.list(status)`、否则 `store.list()`（`:51-53`）。
3. 过滤：`isBlankOrEquals`（`:72-74`）—— null/空白 = 不过滤，否则精确相等。
4. 排序：`createdAt.reversed()` 倒序（`:58`，最新在前）。
5. 分页：`total = filtered.size()`（**过滤后、分页前**，INV-LIST-2）→ `skip(page*size).limit(size)` → 映射 summary（`:61-66`）。

```java
public TaskListPageDto list(TaskState status, String tool, String target, int page, int size) {
    int safePage = Math.max(0, page);
    int safeSize = Math.min(MAX_SIZE, Math.max(1, size));
    List<GatewayTask> all = (status != null) ? store.list(status) : store.list();
    List<GatewayTask> filtered = all.stream()
            .filter(t -> isBlankOrEquals(tool, t.toolName()))
            .filter(t -> isBlankOrEquals(target, t.target()))
            .sorted(Comparator.comparing(GatewayTask::createdAt).reversed())
            .toList();
    long total = filtered.size();
    List<TaskSummaryDto> items = filtered.stream()
            .skip((long) safePage * safeSize).limit(safeSize)
            .map(TaskListService::toSummary).toList();
    return new TaskListPageDto(items, total, safePage, safeSize);
}
```

**toSummary（`:77-91`）**——`isError` 仅 `result != null && result.isError()==TRUE` 时 true（`:78-82`），即 `WORKING/FAILED/CANCELLED`（无 result）一律 false。

### 38.3 TaskExportService — 导出（frames 原样）

**文件**：`src/main/java/com/arthas/gateway/admin/task/TaskExportService.java:22`

**export（`:30-50`）**：
1. 不存在 → `TaskNotFoundException`（`:31-32`）。
2. `status != COMPLETED` → `TaskNotCompletedException`（`:33-35`）。
3. frames 来自 `result.content()` 仅过滤 `TextContent`、取 `.text()`（`:36-40`），**不篡改/不截断**（INV-EXP-1）。
4. 投影为 `TaskExportDto`（`:41-49`）含 `isError = result.isError()`（G-TG-2 业务错误保留）。

```java
CallToolResult result = task.result();
List<String> frames = result.content().stream()
        .filter(c -> c instanceof TextContent)
        .map(c -> ((TextContent) c).text())
        .toList();
return new TaskExportDto(task.taskId(), task.toolName(), task.target(),
        task.status().name(), task.createdAt(), task.completedAt(),
        result.isError(), frames);
```

### 38.4 DTO

| DTO | 字段 | 备注 |
|-----|------|------|
| `TaskSummaryDto`（`dto/TaskSummaryDto.java:16`） | `taskId/tool/target/status/createdAt/completedAt/isError` | **无 frames**（INV-LIST-1） |
| `TaskExportDto`（`dto/TaskExportDto.java:12`） | 含 `frames` | 构造器 `null → List.of()` + `List.copyOf`（防御性不可变，`:22-24`） |
| `TaskListPageDto`（`dto/TaskListPageDto.java:11`） | `items/total/page/size` | items 同 copyOf |

### 38.5 异常

| 异常 | 状态码 | reason | 额外 |
|------|--------|--------|------|
| `TaskNotFoundException`（`exception/TaskNotFoundException.java:6`） | 404 | `task_not_found` | `taskId` |
| `TaskNotCompletedException`（`exception/TaskNotCompletedException.java:6`） | **409** | `task_not_completed` | `taskId`, `status` |

---

## 第 39 章 异常映射与能力开关

### 39.1 AdminExceptionHandler — 全局错误体

**文件**：`src/main/java/com/arthas/gateway/admin/AdminExceptionHandler.java:22`（注：实读路径在 admin 包根，跨 backend/task）

`@RestControllerAdvice` 覆盖 `/admin/backends` 与 `/admin/tasks` 全部异常 → 结构化 `{error, reason, ...}`（INV-ERR-1）：

| 异常 | 状态码 | reason | 额外字段 |
|------|--------|--------|----------|
| `BackendNotFoundException`（`:24-31`） | 404 | `backend_not_found` | `name`, `available[]` |
| `BackendConflictException`（`:33-38`） | 400 | 来自异常 | — |
| `BackendAdminException`（`:40-45`） | 500 | `admin_io_error` | — |
| `TaskNotFoundException`（`:47-53`） | 404 | `task_not_found` | `taskId` |
| `TaskNotCompletedException`（`:55-62`） | **409** | `task_not_completed` | `taskId`, `status` |

```java
@ExceptionHandler(BackendConflictException.class)
public ResponseEntity<Map<String, Object>> conflict(BackendConflictException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
            "error", e.getMessage(), "reason", e.getReason()));
}
```

### 39.2 能力开关语义

| 开关 | 默认 | 关闭时 |
|------|------|-------|
| `arthas-gateway.admin.crud.enabled` | `true` | `/admin/backends/*` 全 404，前端展示 ApiError 降级 |
| `arthas-gateway.admin.export.enabled` | `true` | `/admin/tasks`（列表）与 `/admin/tasks/{id}/export`（导出）**同 404**（INV-LIST-4） |

**独立性**（INV-SWITCH-1/2）：两开关互不影响，也不影响 `/mcp`。

**测试守护**：`AdminCapabilitySwitchTest`（`ApplicationContextRunner` 轻量测 `@ConditionalOnProperty`）+ `AdminCapabilitySwitchIT`（crud=false + export=true）+ `AdminExportSwitchIT`（export=false + crud=true）。

---

## 第 40 章 SpaConfig — Vue Router history 模式 fallback

**文件**：`src/main/java/com/arthas/gateway/admin/SpaConfig.java:18`

```java
@Configuration
public class SpaConfig implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/backends").setViewName("forward:/index.html");
        registry.addViewController("/tasks").setViewName("forward:/index.html");
    }
}
```

**问题**：Vue Router history 模式的 deep link（reload `/backends` 或直链访问）直达 Spring → 无映射 → Whitelabel 404。

**修复**：把已知 SPA 路由 forward 到 `/index.html`，让浏览器加载 SPA 后由 Vue Router 在前端解析。**不影响 `/admin`、`/mcp`、`/actuator`**（API 端点，仍走各自 controller）。

> 新增前端路由需在此补一行 forward（MVP 仅两路由）。

---

## 第 41 章 前端 SPA（Vue 3）

### 41.1 入口与路由

**main.ts**（`web/src/main.ts:6`）：
```ts
createApp(App).use(router).mount('#app')
```

**router.ts**（`web/src/router.ts:6`）：`createWebHistory()`（history 模式，配 `SpaConfig` 后端 forward）；3 路由：
- `/` → redirect `/backends`
- `/backends` → 懒加载 `BackendListView.vue`
- `/tasks` → 懒加载 `TaskExportView.vue`

**App.vue**（`web/src/App.vue`）：顶部导航（RouterLink `/backends`/`/tasks`）+ `<RouterView />`。CSS 变量定义（`--primary:#2563eb` 等）驱动整体配色（深蓝渐变导航 + 白卡片表格 + 绿/红健康徽标）。

### 41.2 adminClient.ts — 同源 fetch（无 CORS）

**文件**：`web/src/api/adminClient.ts`

`BASE = '/admin'`（`:4`），全部请求走同源 fetch（INV-WEB-1）。

- `ApiError`（`:7-17`）：错误结构化，含 `status/reason/available`。
- `request<T>`（`:20-38`）：默认 `Content-Type: application/json`；`!res.ok` → 读 JSON 错误体 → 抛 ApiError；204 → undefined；按 content-type 选 json/text。
- CRUD（`:95-113`）：`listBackends/getBackend/createBackend/updateBackend/deleteBackend`，name 经 `encodeURIComponent`。
- 导出（`:128-141`）：
  - `exportTask(taskId)`：fetch 走 JSON 解析（查询态友好提示）。
  - `downloadTaskExport(taskId)`（`:136-141`）：**动态创建 `<a>` 触发浏览器原生 attachment 下载**，不经前端重序列化（INV-EXP-1）。
- 列表（`:173-182`）：`listTasks(params)`，用 `URLSearchParams` 拼 query string。

```ts
export function downloadTaskExport(taskId: string): void {
  const a = document.createElement('a')
  a.href = `${BASE}/tasks/${enc(taskId)}/export?format=json`
  a.download = `${taskId}.json`
  a.click()
}
```

### 41.3 视图

**BackendListView.vue**（`web/src/views/BackendListView.vue`）：状态 `data/summary/error/showForm/editing`。`onMounted(refresh)`；`openAdd/openEdit/onSubmit/onDelete`。表头：名称/来源/健康/URL/认证/操作。每行：编辑/删除按钮（`data-testid="edit-<name>"/"del-<name>"`），健康走 `<HealthBadge>`。错误态 inline 展示。summary 行显示 `共 N 个（健康 H / 异常 U）`。

**TaskExportView.vue**（`web/src/views/TaskExportView.vue`）—— **双区布局**：
- **列表区**（`:84-128`）：`status` 下拉（全部/WORKING/COMPLETED/FAILED/CANCELLED）+ 任务表（点击行 → 填 taskId 触发查询）+ 分页（prev/next + 页码）。
- **单任务查询区**（`:131-145`）：输入框 + 查询按钮 + 结果卡片（`<DownloadButton>`）。
- **三态自验证**：`listLoading`/`listError`/`tasks.length===0` → 加载中 / 错误 / 暂无任务（`:96-98`）。
- 行 status 走 `status-<lowercase>` 类名着色（COMPLETED 绿、WORKING 黄、FAILED 红、CANCELLED 灰）。

### 41.4 组件

- **BackendForm.vue**（`web/src/components/BackendForm.vue`）：新增/编辑共用。`editing = !!props.initial`、`dynamic = source==='DYNAMIC'`。编辑态禁用 name；动态态：禁用 protocol/auth/maxConcurrent 提交按钮，显示警告「动态后端不可编辑（须先删再 ensure）」（INV-DYN-1 前端降级）。字段：name/url/protocol/authMode/token/maxConcurrentTasks。
- **HealthBadge.vue**（`web/src/components/HealthBadge.vue`）：圆点徽标 + 文案 `健康/异常 · CLOSED/OPEN`，`data-testid="health-ok"/"health-bad"`（SC-003）。
- **DownloadButton.vue**（`web/src/components/DownloadButton.vue`）：单按钮调 `downloadTaskExport(taskId)`（attachment 原样下载）。

---

## 第 42 章 前端构建集成

### 42.1 vite.config.ts — 产物落 Maven 输出

**文件**：`web/vite.config.ts:10-27`

```ts
export default defineConfig({
  build: {
    outDir: '../target/classes/static',   // 产物直接落 Maven 输出（INV-WEB-1）
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {                               // dev HMR
      '/admin': 'http://localhost:8761',
      '/actuator': 'http://localhost:8761',
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
})
```

### 42.2 frontend-maven-plugin — Maven 内跑 npm

**文件**：`pom.xml:224-259`

`com.github.eirslett:frontend-maven-plugin:1.15.1`，`workingDirectory/installDirectory=${project.basedir}/web`、`nodeVersion=v22.22.0`、`skip=${skipFrontend}`（`:228-233`）。4 个 execution：

| execution | 阶段 | 命令 | 说明 |
|-----------|------|------|------|
| `install-node-and-npm` | `generate-resources` | install-node-and-npm | CI 无需预装 node |
| `npm-install` | `generate-resources` | `npm install` | 装 web 依赖 |
| `npm-build` | `generate-resources` | `npm run build` → `vue-tsc --noEmit && vite build` | TS 类型检查 + 构建 |
| `npm-test` | `test` | `npm run test` → `vitest run` | 前端组件测试 |

属性 `skipFrontend=false`（`:34`，开发期可 `-DskipFrontend=true` 跳过）。

→ `./mvnw verify` 一条命令产出含前端 SPA 的单 JAR（CI 可复现，SC-005）。

---

## 第 43 章 portal 不变量对照（12 条）

| 不变量 | 含义 | 实现证据 |
|--------|------|----------|
| **INV-ISOL-1**（管理/诊断隔离） | `/admin` 不影响 `/mcp` 38 工具 | Controller 走 Spring Web MVC；`/mcp` 走 spring-ai servlet；`PackageBoundaryTest` 规则 3 守护诊断核心不依赖 admin |
| **INV-SWITCH-1/2**（独立开关） | 关闭其一不影响另一个、不影响 `/mcp` | `BackendAdminController` 与 `TaskExportController` 各自带 `@ConditionalOnProperty`；`AdminCapabilitySwitchTest` 验证独立性 |
| **INV-LIST-4**（列表与 export 共用开关） | `export.enabled=false` → 列表与导出同 404 | `TaskExportController` 同时承载 list + export，类级条件注解一刀切 |
| **INV-LIST-1**（摘要无 frames） | `TaskSummaryDto` 禁含 frames | `dto/TaskSummaryDto.java:16` 仅 7 字段；frames 仅 `TaskExportDto` |
| **INV-LIST-2**（total = 过滤后/分页前） | total 与分页独立 | `TaskListService` `:61`：`total = filtered.size()` 在 skip/limit 之前 |
| **INV-LIST-3**（createdAt 倒序） | 最新在前 | `TaskListService` `:58`：`Comparator.comparing(GatewayTask::createdAt).reversed()` |
| **INV-EXP-1**（导出原样） | frames 与 task-get 逐字一致 | `TaskExportService` `:37-40`：直接 `result.content()` 取 TextContent.text；前端 `downloadTaskExport` 浏览器原生 attachment |
| **INV-DYN-1**（动态不可改） | POST/PUT 动态 → 400 | `BackendAdminService.update` `:95-98` 抛 `dynamic_backend_not_editable`；前端 `BackendForm` dynamic 时禁用 submit |
| **INV-FILE-1**（静态经文件热重载） | 不直接调 `BackendRegistry.rebuild` | `create/update/delete` 一律走 `writeYaml(version+1)` → 001 WatchService 热重载 |
| **INV-ERR-1**（错误显式传播） | 不静默 200 | `AdminExceptionHandler` 5 个 `@ExceptionHandler` 全部返 4xx/5xx + `{error, reason, ...}` |
| **INV-SECRET-1**（凭据脱敏） | DTO 不回显 token | `BackendDto` 仅 `authMode`，无 token/username/password 字段 |
| **INV-WEB-1**（同源单 JAR） | 浏览器访问根加载 SPA、无 CORS | `vite.config.ts` outDir=`target/classes/static`；`SpaConfig` forward；`adminClient.ts` 同源 `/admin` |
| **INV-WEB-2**（前端展示层） | 核心逻辑 Java 后端 | `PackageBoundaryTest` 规则 3 守护；前端仅 fetch + render + download |

---

## 第 44 章 真实端到端验证（实测）

> 2026-07-08 本地 + K8S 实测捕获。

### 44.1 portal 任务列表（SC-006）

触发 `watch` ×2（target=order-service）→ `curl /admin/tasks`：
```json
{
  "items": [
    {"taskId":"t-c690f1","tool":"watch","target":"order-service","status":"COMPLETED","createdAt":"...","completedAt":"...","isError":false},
    {"taskId":"t-9d083c","tool":"watch","target":"order-service","status":"COMPLETED","...":"...","isError":false}
  ],
  "total": 2, "page": 0, "size": 10
}
```

→ 摘要无 frames（INV-LIST-1）、createdAt 倒序（INV-LIST-3）、total=过滤后（INV-LIST-2）。

### 44.2 浏览器 SPA（Playwright 实测）

- `/tasks` 页列表区：自动查首页 + status 下拉 + 表格 2 行 + 分页（点列表项填 taskId + 查询 frames + 下载）。
- `/backends` 页：order-service + payment（STATIC/ACTIVE/healthy/CLOSED），CRUD 写 YAML 热重载。

---

> **下一步**：Part 6 配置全字段 + 38 工具清单 + 错误码速查 + 文件索引。
