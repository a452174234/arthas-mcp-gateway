# Part 13 · 主源码完整摘录（src/main/java）

> 本附录摘录全部主代码源码（每个类完整 + 路径标注），作为事无巨细的代码级参考。


---

## com/arthas/gateway/admin/backend/AdminExceptionHandler.java

**文件**：`src/main/java/com/arthas/gateway/admin/backend/AdminExceptionHandler.java`

```java
package com.arthas.gateway.admin.backend;

import com.arthas.gateway.admin.backend.exception.BackendAdminException;
import com.arthas.gateway.admin.backend.exception.BackendConflictException;
import com.arthas.gateway.admin.backend.exception.BackendNotFoundException;
import com.arthas.gateway.admin.task.exception.TaskNotCompletedException;
import com.arthas.gateway.admin.task.exception.TaskNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 004 管理面异常 → HTTP 映射（admin-api-contract §3 错误体格式，admin-invariants INV-ERR-1）。
 *
 * <p>全局 {@code @RestControllerAdvice}（覆盖 /admin/backends 与 /admin/tasks）。
 * 错误显式传播、结构化（状态码 + error + reason），不静默成功（宪法原则五）。
 */
@RestControllerAdvice
public class AdminExceptionHandler {

    @ExceptionHandler(BackendNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(BackendNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", e.getMessage(),
                "reason", "backend_not_found",
                "name", e.getName(),
                "available", e.getAvailable()));
    }

    @ExceptionHandler(BackendConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(BackendConflictException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", e.getMessage(),
                "reason", e.getReason()));
    }

    @ExceptionHandler(BackendAdminException.class)
    public ResponseEntity<Map<String, Object>> adminError(BackendAdminException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", e.getMessage(),
                "reason", "admin_io_error"));
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<Map<String, Object>> taskNotFound(TaskNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", e.getMessage(),
                "reason", "task_not_found",
                "taskId", e.getTaskId()));
    }

    @ExceptionHandler(TaskNotCompletedException.class)
    public ResponseEntity<Map<String, Object>> taskNotCompleted(TaskNotCompletedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", e.getMessage(),
                "reason", "task_not_completed",
                "taskId", e.getTaskId(),
                "status", e.getStatus()));
    }
}
```


---

## com/arthas/gateway/admin/backend/BackendAdminController.java

**文件**：`src/main/java/com/arthas/gateway/admin/backend/BackendAdminController.java`

```java
package com.arthas.gateway.admin.backend;

import com.arthas.gateway.admin.backend.dto.BackendDto;
import com.arthas.gateway.admin.backend.dto.CreateBackendRequest;
import com.arthas.gateway.admin.backend.dto.UpdateBackendRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 004 后端配置 CRUD REST 端点（admin-api-contract §1，A 能力）。
 *
 * <p>{@code /admin/backends}：GET 列表/详情、POST 增、PUT 改、DELETE 删。
 * {@code @ConditionalOnProperty(admin.crud.enabled, matchIfMissing=true)}：默认开；
 * 关闭时本 Controller 不注册 → 端点 404、前端降级（admin-invariants INV-SWITCH-1）。
 * 与诊断面 {@code /mcp} 隔离（admin-invariants INV-ISOL-1）。
 */
@RestController
@RequestMapping("/admin/backends")
@ConditionalOnProperty(name = "arthas-gateway.admin.crud.enabled", havingValue = "true", matchIfMissing = true)
public class BackendAdminController {

    private final BackendAdminService service;

    public BackendAdminController(BackendAdminService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<BackendDto> backends = service.list();
        long healthy = backends.stream().filter(BackendDto::healthy).count();
        return Map.of(
                "backends", backends,
                "summary", Map.of(
                        "total", backends.size(),
                        "healthy", healthy,
                        "unhealthy", backends.size() - healthy));
    }

    @GetMapping("/{name}")
    public BackendDto get(@PathVariable String name) {
        return service.get(name);
    }

    @PostMapping
    public ResponseEntity<BackendDto> create(@RequestBody CreateBackendRequest req) {
        return ResponseEntity.status(201).body(service.create(req));
    }

    @PutMapping("/{name}")
    public BackendDto update(@PathVariable String name, @RequestBody UpdateBackendRequest req) {
        return service.update(name, req);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        service.delete(name);
        return ResponseEntity.noContent().build();
    }
}
```


---

## com/arthas/gateway/admin/backend/BackendAdminService.java

**文件**：`src/main/java/com/arthas/gateway/admin/backend/BackendAdminService.java`

```java
package com.arthas.gateway.admin.backend;

import com.arthas.gateway.admin.backend.dto.BackendDto;
import com.arthas.gateway.admin.backend.dto.CreateBackendRequest;
import com.arthas.gateway.admin.backend.dto.UpdateBackendRequest;
import com.arthas.gateway.admin.backend.exception.BackendAdminException;
import com.arthas.gateway.admin.backend.exception.BackendConflictException;
import com.arthas.gateway.admin.backend.exception.BackendNotFoundException;
import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendConfigLoader;
import com.arthas.gateway.backend.BackendConfigLoader.LoadedBackends;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.backend.Source;
import com.arthas.gateway.config.GatewayProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 004 后端配置 CRUD 业务编排（admin-api-contract §1，research.md R3/R7）。
 *
 * <p>静态后端：经 {@link BackendsYamlWriter} 写回 {@code config/backends.yaml}（version 递增触发热重载，SC-002）。
 * 动态后端（source=DYNAMIC，003 ensure 产生）：POST/PUT 拒绝（INV-DYN-1）、DELETE = {@link DynamicBackendStore#unregister}。
 *
 * <p>核心逻辑在 Java（宪法原则六）；前端仅消费此服务暴露的 /admin API。
 */
@Service
public class BackendAdminService {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_CALL_TIMEOUT_MS = 30000;
    private static final int DEFAULT_MAX_CONCURRENT = 5;

    private final RegistryHolder registryHolder;
    private final DynamicBackendStore dynamicStore;
    private final BackendsYamlWriter yamlWriter;
    private final BackendConfigLoader loader;
    private final Path backendsFile;

    @Autowired
    public BackendAdminService(RegistryHolder registryHolder, DynamicBackendStore dynamicStore,
                               BackendsYamlWriter yamlWriter, GatewayProperties props) {
        this(registryHolder, dynamicStore, yamlWriter, new BackendConfigLoader(), Path.of(props.getBackendsFile()));
    }

    /** 测试用：注入 loader + backendsFile（便于 @TempDir）。 */
    BackendAdminService(RegistryHolder registryHolder, DynamicBackendStore dynamicStore,
                        BackendsYamlWriter yamlWriter, BackendConfigLoader loader, Path backendsFile) {
        this.registryHolder = registryHolder;
        this.dynamicStore = dynamicStore;
        this.yamlWriter = yamlWriter;
        this.loader = loader;
        this.backendsFile = backendsFile;
    }

    public List<BackendDto> list() {
        return registryHolder.current().byName().values().stream()
                .map(this::toDto)
                .toList();
    }

    public BackendDto get(String name) {
        BackendEntry entry = registryHolder.get(name)
                .orElseThrow(() -> new BackendNotFoundException(name, availableNames()));
        return toDto(entry);
    }

    public BackendDto create(CreateBackendRequest req) {
        String name = requireNonBlank(req.name(), "name");
        requireNonBlank(req.url(), "url");
        if (registryHolder.current().names().contains(name)) {
            throw new BackendConflictException("name 已存在：" + name, "duplicate_name");
        }
        BackendConfig cfg = toBackendConfig(req, name);
        LoadedBackends loaded = loadCurrent();
        List<BackendConfig> updated = new ArrayList<>(loaded.backends());
        updated.add(cfg);
        writeYaml(loaded.version() + 1, updated);
        return toDto(cfg, "ACTIVE", false, "CLOSED");
    }

    public BackendDto update(String name, UpdateBackendRequest req) {
        BackendEntry entry = registryHolder.get(name)
                .orElseThrow(() -> new BackendNotFoundException(name, availableNames()));
        if (entry.config().source() == Source.DYNAMIC) {
            throw new BackendConflictException(
                    "动态后端不可改（须先删再 ensure）：" + name, "dynamic_backend_not_editable");
        }
        BackendConfig merged = mergeConfig(entry.config(), req);
        LoadedBackends loaded = loadCurrent();
        List<BackendConfig> newList = loaded.backends().stream()
                .map(b -> b.name().equals(name) ? merged : b)
                .toList();
        writeYaml(loaded.version() + 1, newList);
        return toDto(merged, "ACTIVE", false, "CLOSED");
    }

    public void delete(String name) {
        BackendEntry entry = registryHolder.get(name)
                .orElseThrow(() -> new BackendNotFoundException(name, availableNames()));
        if (entry.config().source() == Source.DYNAMIC) {
            dynamicStore.unregister(name);
            return;
        }
        LoadedBackends loaded = loadCurrent();
        List<BackendConfig> newList = loaded.backends().stream()
                .filter(b -> !b.name().equals(name))
                .toList();
        writeYaml(loaded.version() + 1, newList);
    }

    private List<String> availableNames() {
        return List.copyOf(registryHolder.current().names());
    }

    private LoadedBackends loadCurrent() {
        try (InputStream in = Files.newInputStream(backendsFile)) {
            return loader.load(in);
        } catch (IOException e) {
            throw new BackendAdminException("读取 backends.yaml 失败：" + backendsFile, e);
        }
    }

    private void writeYaml(long version, List<BackendConfig> backends) {
        try {
            yamlWriter.write(backendsFile, version, backends);
        } catch (IOException e) {
            throw new BackendAdminException("写回 backends.yaml 失败：" + backendsFile, e);
        }
    }

    private BackendDto toDto(BackendEntry entry) {
        BackendConfig cfg = entry.config();
        return toDto(cfg, entry.state().name(), entry.isHealthy(), entry.breaker().state().name());
    }

    private BackendDto toDto(BackendConfig cfg, String state, boolean healthy, String breaker) {
        return new BackendDto(
                cfg.name(),
                cfg.source().name(),
                state,
                healthy,
                breaker,
                cfg.url(),
                cfg.protocol().name(),
                cfg.auth().mode().name(),
                cfg.connectTimeoutMs(),
                cfg.callTimeoutMs(),
                cfg.maxConcurrentTasks());
    }

    private BackendConfig toBackendConfig(CreateBackendRequest req, String name) {
        Protocol protocol = req.protocol() != null
                ? Protocol.valueOf(req.protocol().trim().toUpperCase()) : Protocol.STREAMABLE;
        AuthMode mode = req.authMode() != null
                ? AuthMode.valueOf(req.authMode().trim().toUpperCase()) : AuthMode.NONE;
        BackendConfig.Auth auth = new BackendConfig.Auth(mode, req.token(), req.username(), req.password());
        return new BackendConfig(name, req.url(), protocol, auth,
                req.connectTimeoutMs() != null ? req.connectTimeoutMs() : DEFAULT_CONNECT_TIMEOUT_MS,
                req.callTimeoutMs() != null ? req.callTimeoutMs() : DEFAULT_CALL_TIMEOUT_MS,
                req.maxConcurrentTasks() != null ? req.maxConcurrentTasks() : DEFAULT_MAX_CONCURRENT,
                Source.STATIC);
    }

    private BackendConfig mergeConfig(BackendConfig existing, UpdateBackendRequest req) {
        String url = req.url() != null ? req.url() : existing.url();
        AuthMode mode = req.authMode() != null
                ? AuthMode.valueOf(req.authMode().trim().toUpperCase()) : existing.auth().mode();
        String token = req.token() != null ? req.token() : existing.auth().token();
        String username = req.username() != null ? req.username() : existing.auth().username();
        String password = req.password() != null ? req.password() : existing.auth().password();
        BackendConfig.Auth auth = new BackendConfig.Auth(mode, token, username, password);
        return new BackendConfig(existing.name(), url, existing.protocol(), auth,
                req.connectTimeoutMs() != null ? req.connectTimeoutMs() : existing.connectTimeoutMs(),
                req.callTimeoutMs() != null ? req.callTimeoutMs() : existing.callTimeoutMs(),
                req.maxConcurrentTasks() != null ? req.maxConcurrentTasks() : existing.maxConcurrentTasks(),
                existing.source());
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BackendConflictException(field + " 不可为空", "missing_" + field);
        }
        return value.trim();
    }
}
```


---

## com/arthas/gateway/admin/backend/BackendCrudAutoConfig.java

**文件**：`src/main/java/com/arthas/gateway/admin/backend/BackendCrudAutoConfig.java`

```java
package com.arthas.gateway.admin.backend;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * 004 后端配置 CRUD 能力的条件装配（research.md R9 / spec FR-014）。
 *
 * <p>{@code @ConditionalOnProperty(admin.crud.enabled, matchIfMissing=true)}：默认开；
 * {@code arthas-gateway.admin.crud.enabled=false} 时本配置类不装配 →
 * /admin/backends 端点不暴露（404）、前端降级提示（admin-invariants INV-SWITCH-1）。
 *
 * <p>Phase 3 在此注册 {@code BackendAdminController} 等 bean（@Bean 方法）。
 */
@Configuration
@ConditionalOnProperty(name = "arthas-gateway.admin.crud.enabled", havingValue = "true", matchIfMissing = true)
public class BackendCrudAutoConfig {
}
```


---

## com/arthas/gateway/admin/backend/BackendsYamlWriter.java

**文件**：`src/main/java/com/arthas/gateway/admin/backend/BackendsYamlWriter.java`

```java
package com.arthas.gateway.admin.backend;

import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.Source;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 004 backends.yaml 结构化写回（research.md R2：SnakeYAML dump 重写，不保留原文注释）。
 *
 * <p>静态后端 CRUD（POST/PUT/DELETE）经此写回 {@code config/backends.yaml}，触发既有 WatchService 热重载（R7）。
 * {@code version} 由调用方递增传入（{@code BackendRegistryReloader} 按 version 去重，须变才触发重载）。
 *
 * <p>已知限制（R2）：dump 不保留注释；机密字段写回当前解析值（${ENV} 占位符还原后置）。
 */
@Component
public class BackendsYamlWriter {

    private final Yaml yaml = new Yaml();

    /** 写回 version + backends 到 file（覆盖，UTF-8）。 */
    public void write(Path file, long version, List<BackendConfig> backends) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", version);
        root.put("backends", backends.stream().map(this::toMap).toList());
        Files.writeString(file, yaml.dumpAsMap(root), StandardCharsets.UTF_8);
    }

    private Map<String, Object> toMap(BackendConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", cfg.name());
        m.put("url", cfg.url());
        m.put("protocol", cfg.protocol().name());
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("mode", cfg.auth().mode().name());
        if (cfg.auth().token() != null) {
            auth.put("token", cfg.auth().token());
        }
        if (cfg.auth().username() != null) {
            auth.put("username", cfg.auth().username());
        }
        if (cfg.auth().password() != null) {
            auth.put("password", cfg.auth().password());
        }
        m.put("auth", auth);
        m.put("connectTimeoutMs", cfg.connectTimeoutMs());
        m.put("callTimeoutMs", cfg.callTimeoutMs());
        m.put("maxConcurrentTasks", cfg.maxConcurrentTasks());
        // source：仅 DYNAMIC 显式写（STATIC 缺省，向后兼容）
        if (cfg.source() == Source.DYNAMIC) {
            m.put("source", "DYNAMIC");
        }
        return m;
    }
}
```


---

## com/arthas/gateway/admin/backend/dto/BackendDto.java

**文件**：`src/main/java/com/arthas/gateway/admin/backend/dto/BackendDto.java`

```java
package com.arthas.gateway.admin.backend.dto;

/**
 * 004 后端配置 CRUD 响应 DTO（admin-api-contract §1，data-model §1.1）。
 *
 * <p>后端注册表的只读投影（源 {@code BackendRegistry}+{@code BackendEntry}）。凭据脱敏：
 * <b>仅暴露 {@code authMode}，不含 token/username/password</b>（admin-invariants INV-SECRET-1）。
 *
 * <p>枚举字段用 String（DTO 边界、序列化友好，前端直接消费）：
 * source=STATIC|DYNAMIC、state=ACTIVE|RETIRED、breaker=OPEN|CLOSED、protocol=STREAMABLE|STATELESS、authMode=NONE|BEARER|BASIC。
 */
public record BackendDto(
        String name,
        String source,
        String state,
        boolean healthy,
        String breaker,
        String url,
        String protocol,
        String authMode,
        int connectTimeoutMs,
        int callTimeoutMs,
        int maxConcurrentTasks) {
}
```


---

## com/arthas/gateway/admin/backend/dto/CreateBackendRequest.java

**文件**：`src/main/java/com/arthas/gateway/admin/backend/dto/CreateBackendRequest.java`

```java
package com.arthas.gateway.admin.backend.dto;

/**
 * 004 新增后端请求体（POST /admin/backends）。仅静态后端可手动新增（动态后端由 003 ensure 产生，POST 拒绝，R3）。
 *
 * <p>缺省值（与 {@code BackendConfigLoader} 一致）：protocol=STREAMABLE、authMode=NONE、
 * connectTimeoutMs=5000、callTimeoutMs=30000、maxConcurrentTasks=5。可空字段（Integer/String）= 缺省。
 * name+url 必填，由后端 {@code BackendAdminService} 校验。
 */
public record CreateBackendRequest(
        String name,
        String url,
        String protocol,
        String authMode,
        String token,
        String username,
        String password,
        Integer connectTimeoutMs,
        Integer callTimeoutMs,
        Integer maxConcurrentTasks) {
}
```


---

## com/arthas/gateway/admin/backend/dto/UpdateBackendRequest.java

**文件**：`src/main/java/com/arthas/gateway/admin/backend/dto/UpdateBackendRequest.java`

```java
package com.arthas.gateway.admin.backend.dto;

/**
 * 004 修改后端请求体（PUT /admin/backends/{name}）。name 在 path，不在 body。
 *
 * <p>仅静态后端可改（动态后端 PUT 拒绝，R3）。可空字段（null=不改）。url/auth/超时/并发可部分更新。
 */
public record UpdateBackendRequest(
        String url,
        String authMode,
        String token,
        String username,
        String password,
        Integer connectTimeoutMs,
        Integer callTimeoutMs,
        Integer maxConcurrentTasks) {
}
```


---

## com/arthas/gateway/admin/backend/exception/BackendAdminException.java

**文件**：`src/main/java/com/arthas/gateway/admin/backend/exception/BackendAdminException.java`

```java
package com.arthas.gateway.admin.backend.exception;

/**
 * 004 管理面基础设施错误（admin-api-contract：500），如 backends.yaml 读写 IO 失败。透明记录（宪法原则五）。
 */
public class BackendAdminException extends RuntimeException {

    public BackendAdminException(String message, Throwable cause) {
        super(message, cause);
    }
}
```


---

## com/arthas/gateway/admin/backend/exception/BackendConflictException.java

**文件**：`src/main/java/com/arthas/gateway/admin/backend/exception/BackendConflictException.java`

```java
package com.arthas.gateway.admin.backend.exception;

/**
 * 004 后端操作冲突/校验失败（admin-api-contract：400 + reason）。如 name 重复、改动态后端（INV-DYN-1）。
 */
public class BackendConflictException extends RuntimeException {

    private final String reason;

    public BackendConflictException(String message, String reason) {
        super(message);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
```


---

## com/arthas/gateway/admin/backend/exception/BackendNotFoundException.java

**文件**：`src/main/java/com/arthas/gateway/admin/backend/exception/BackendNotFoundException.java`

```java
package com.arthas.gateway.admin.backend.exception;

import java.util.List;

/**
 * 004 目标后端不存在（admin-api-contract：404 + available[]）。携带当前可用名，便于错误提示。
 */
public class BackendNotFoundException extends RuntimeException {

    private final String name;
    private final List<String> available;

    public BackendNotFoundException(String name, List<String> available) {
        super("未知后端：" + name);
        this.name = name;
        this.available = available == null ? List.of() : List.copyOf(available);
    }

    public String getName() {
        return name;
    }

    public List<String> getAvailable() {
        return available;
    }
}
```


---

## com/arthas/gateway/admin/SpaConfig.java

**文件**：`src/main/java/com/arthas/gateway/admin/SpaConfig.java`

```java
package com.arthas.gateway.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 004 SPA fallback（admin-invariants INV-WEB-1）：Vue Router history 模式的 deep link
 * （reload {@code /backends}、{@code /tasks} 或直接访问）直达 Spring → 无映射 → 404。
 *
 * <p>本配置把已知 SPA 路由（{@code /backends}、{@code /tasks}）forward 到 {@code /index.html}，
 * 让浏览器加载 SPA 后由 Vue Router 在前端解析当前路径。不影响 {@code /admin}、{@code /mcp}、
 * {@code /actuator}（API 端点，仍走各自 controller）。
 *
 * <p>新增前端路由时在此补一行 forward（MVP 仅两路由）。
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/backends").setViewName("forward:/index.html");
        registry.addViewController("/tasks").setViewName("forward:/index.html");
    }
}
```


---

## com/arthas/gateway/admin/task/dto/TaskExportDto.java

**文件**：`src/main/java/com/arthas/gateway/admin/task/dto/TaskExportDto.java`

```java
package com.arthas.gateway.admin.task.dto;

import java.time.Instant;
import java.util.List;

/**
 * 004 异步任务结果导出 DTO（admin-api-contract §2，data-model §1.2）。
 *
 * <p>frames <b>原样来自 {@code GatewayTask.result().content()} 的文本</b>（每个 TextContent.text），
 * 不篡改/摘要/截断（admin-invariants INV-EXP-1 / 宪法原则二）。仅 {@code COMPLETED} 任务有 frames。
 */
public record TaskExportDto(
        String taskId,
        String tool,
        String target,
        String status,
        Instant createdAt,
        Instant completedAt,
        boolean isError,
        List<String> frames) {

    public TaskExportDto {
        frames = frames == null ? List.of() : List.copyOf(frames);
    }
}
```


---

## com/arthas/gateway/admin/task/dto/TaskListPageDto.java

**文件**：`src/main/java/com/arthas/gateway/admin/task/dto/TaskListPageDto.java`

```java
package com.arthas.gateway.admin.task.dto;

import java.util.List;

/**
 * 004 增量 异步任务列表分页响应（admin-api-contract §2 {@code GET /admin/tasks}，FR-015）。
 *
 * <p>{@code items} = 当前页摘要（按 {@code createdAt} 倒序）；{@code total} = 过滤后、分页前的总数
 * （与分页独立，INV-LIST-2）；{@code page}/{@code size} = 归一化后的实际页码/页大小。
 */
public record TaskListPageDto(
        List<TaskSummaryDto> items,
        long total,
        int page,
        int size) {

    public TaskListPageDto {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
```


---

## com/arthas/gateway/admin/task/dto/TaskSummaryDto.java

**文件**：`src/main/java/com/arthas/gateway/admin/task/dto/TaskSummaryDto.java`

```java
package com.arthas.gateway.admin.task.dto;

import java.time.Instant;

/**
 * 004 增量 异步任务摘要 DTO（admin-api-contract §2 {@code GET /admin/tasks}，FR-015）。
 *
 * <p>列表查询的轻量摘要视图——<b>不含 frames</b>（admin-invariants INV-LIST-1）；
 * frames 仅由 {@link TaskExportDto}（{@code /{taskId}/export} 端点）提供。
 *
 * <p>字段：{@code taskId/tool/target/status/createdAt/completedAt/isError}。
 * {@code isError} 仅 {@code COMPLETED} 时据 {@code GatewayTask.result().isError()}
 * （G-TG-2 业务错误原样保留），其余态（WORKING/FAILED/CANCELLED）为 {@code false}
 * ——映射在 {@code TaskListService}，本 DTO 仅承载。
 */
public record TaskSummaryDto(
        String taskId,
        String tool,
        String target,
        String status,
        Instant createdAt,
        Instant completedAt,
        boolean isError) {
}
```


---

## com/arthas/gateway/admin/task/exception/TaskNotCompletedException.java

**文件**：`src/main/java/com/arthas/gateway/admin/task/exception/TaskNotCompletedException.java`

```java
package com.arthas.gateway.admin.task.exception;

/**
 * 004 导出任务未完成（admin-api-contract §2：409）。仅 {@code COMPLETED} 任务可导出。
 */
public class TaskNotCompletedException extends RuntimeException {

    private final String taskId;
    private final String status;

    public TaskNotCompletedException(String taskId, String status) {
        super("任务未完成，不可导出：" + taskId + "（当前状态 " + status + "）");
        this.taskId = taskId;
        this.status = status;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getStatus() {
        return status;
    }
}
```


---

## com/arthas/gateway/admin/task/exception/TaskNotFoundException.java

**文件**：`src/main/java/com/arthas/gateway/admin/task/exception/TaskNotFoundException.java`

```java
package com.arthas.gateway.admin.task.exception;

/**
 * 004 导出目标任务不存在（admin-api-contract §2：404）。
 */
public class TaskNotFoundException extends RuntimeException {

    private final String taskId;

    public TaskNotFoundException(String taskId) {
        super("未知任务：" + taskId);
        this.taskId = taskId;
    }

    public String getTaskId() {
        return taskId;
    }
}
```


---

## com/arthas/gateway/admin/task/TaskExportAutoConfig.java

**文件**：`src/main/java/com/arthas/gateway/admin/task/TaskExportAutoConfig.java`

```java
package com.arthas.gateway.admin.task;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * 004 异步任务结果导出能力的条件装配（research.md R9 / spec FR-014）。
 *
 * <p>{@code @ConditionalOnProperty(admin.export.enabled, matchIfMissing=true)}：默认开；
 * {@code arthas-gateway.admin.export.enabled=false} 时本配置类不装配 →
 * /admin/tasks/{id}/export 端点不暴露（404）、前端降级（admin-invariants INV-SWITCH-2）。
 *
 * <p>Phase 4 在此注册 {@code TaskExportController} 等 bean。
 */
@Configuration
@ConditionalOnProperty(name = "arthas-gateway.admin.export.enabled", havingValue = "true", matchIfMissing = true)
public class TaskExportAutoConfig {
}
```


---

## com/arthas/gateway/admin/task/TaskExportController.java

**文件**：`src/main/java/com/arthas/gateway/admin/task/TaskExportController.java`

```java
package com.arthas.gateway.admin.task;

import com.arthas.gateway.admin.task.dto.TaskExportDto;
import com.arthas.gateway.admin.task.dto.TaskListPageDto;
import com.arthas.gateway.task.TaskState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 004 异步任务管理面 REST 端点（admin-api-contract §2，C 能力）。管 {@code /admin/tasks} 下全部端点：
 *
 * <ul>
 *   <li>{@code GET /admin/tasks}（增量，FR-015）：任务摘要列表（无 frames）+ 过滤 + 分页 + 倒序。</li>
 *   <li>{@code GET /admin/tasks/{taskId}/export}：{@code completed} 任务完整结果（含 frames）下载。</li>
 * </ul>
 *
 * <p>{@code @ConditionalOnProperty(admin.export.enabled, matchIfMissing=true)}：默认开；列表与导出
 * <b>共用此开关</b>，关闭时两者都 404（admin-invariants INV-LIST-4 / INV-SWITCH-2）。
 */
@RestController
@RequestMapping("/admin/tasks")
@ConditionalOnProperty(name = "arthas-gateway.admin.export.enabled", havingValue = "true", matchIfMissing = true)
public class TaskExportController {

    private final TaskExportService exportService;
    private final TaskListService listService;

    public TaskExportController(TaskExportService exportService, TaskListService listService) {
        this.exportService = exportService;
        this.listService = listService;
    }

    /** 004 增量：异步任务列表查询（FR-015 / admin-api-contract §2）。 */
    @GetMapping
    public TaskListPageDto list(
            @RequestParam(name = "status", required = false) TaskState status,
            @RequestParam(name = "tool", required = false) String tool,
            @RequestParam(name = "target", required = false) String target,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        return listService.list(status, tool, target, page, size);
    }

    @GetMapping("/{taskId}/export")
    public ResponseEntity<TaskExportDto> export(
            @PathVariable String taskId,
            @RequestParam(name = "format", required = false, defaultValue = "json") String format) {
        TaskExportDto dto = exportService.export(taskId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + taskId + ".json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(dto);
    }
}
```


---

## com/arthas/gateway/admin/task/TaskExportService.java

**文件**：`src/main/java/com/arthas/gateway/admin/task/TaskExportService.java`

```java
package com.arthas.gateway.admin.task;

import com.arthas.gateway.admin.task.dto.TaskExportDto;
import com.arthas.gateway.admin.task.exception.TaskNotCompletedException;
import com.arthas.gateway.admin.task.exception.TaskNotFoundException;
import com.arthas.gateway.task.GatewayTask;
import com.arthas.gateway.task.TaskState;
import com.arthas.gateway.task.TaskStore;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 004 异步任务结果导出业务（admin-api-contract §2，research.md R5）。
 *
 * <p>从 {@link TaskStore} 取 {@link GatewayTask}，仅 {@code COMPLETED} 任务可导出；frames
 * <b>原样来自 {@code result().content()} 的 TextContent.text</b>（不篡改/截断，admin-invariants INV-EXP-1 / 宪法原则二）。
 */
@Service
public class TaskExportService {

    private final TaskStore store;

    public TaskExportService(TaskStore store) {
        this.store = store;
    }

    public TaskExportDto export(String taskId) {
        GatewayTask task = store.get(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        if (task.status() != TaskState.COMPLETED) {
            throw new TaskNotCompletedException(taskId, task.status().name());
        }
        CallToolResult result = task.result();
        List<String> frames = result.content().stream()
                .filter(c -> c instanceof TextContent)
                .map(c -> ((TextContent) c).text())
                .toList();
        return new TaskExportDto(
                task.taskId(),
                task.toolName(),
                task.target(),
                task.status().name(),
                task.createdAt(),
                task.completedAt(),
                result.isError(),
                frames);
    }
}
```


---

## com/arthas/gateway/admin/task/TaskListService.java

**文件**：`src/main/java/com/arthas/gateway/admin/task/TaskListService.java`

```java
package com.arthas.gateway.admin.task;

import com.arthas.gateway.admin.task.dto.TaskListPageDto;
import com.arthas.gateway.admin.task.dto.TaskSummaryDto;
import com.arthas.gateway.task.GatewayTask;
import com.arthas.gateway.task.TaskState;
import com.arthas.gateway.task.TaskStore;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 004 增量 异步任务列表查询业务（admin-api-contract §2，FR-015）。
 *
 * <p>从 {@link TaskStore} 取任务（{@code status} 非空走 {@link TaskStore#list(TaskState)} 重载，
 * 否则 {@link TaskStore#list()} 全量），按 {@code tool}/{@code target} 精确过滤、{@code createdAt} 倒序、
 * offset 分页，映射为 {@link TaskSummaryDto} 摘要（无 frames，INV-LIST-1）。
 *
 * <p>参数归一化：{@code page<0→0}；{@code size<1→1}、{@code size>100→100}（不报 400，clamp）。
 * {@code tool}/{@code target} 为 {@code null}/空白时不参与过滤（=全部）。
 *
 * <p>{@code isError} 仅 {@code COMPLETED} 时据 {@code result().isError()}（G-TG-2 业务错误原样保留），
 * 其余态（{@code result()==null}）为 {@code false}。
 */
@Service
public class TaskListService {

    /** 单页上限（admin-api-contract §2 size 上限）。 */
    public static final int MAX_SIZE = 100;

    private final TaskStore store;

    public TaskListService(TaskStore store) {
        this.store = store;
    }

    /**
     * @param status 状态过滤（null=全部）
     * @param tool   工具名精确匹配（null/空白=不过滤）
     * @param target target 名精确匹配（null/空白=不过滤）
     * @param page   页码（&lt;0 → 0）
     * @param size   页大小（&lt;1 → 1，&gt;100 → 100）
     */
    public TaskListPageDto list(TaskState status, String tool, String target, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_SIZE, Math.max(1, size));

        List<GatewayTask> all = (status != null)
                ? store.list(status)
                : store.list();

        List<GatewayTask> filtered = all.stream()
                .filter(t -> isBlankOrEquals(tool, t.toolName()))
                .filter(t -> isBlankOrEquals(target, t.target()))
                .sorted(Comparator.comparing(GatewayTask::createdAt).reversed())
                .toList();

        long total = filtered.size();
        List<TaskSummaryDto> items = filtered.stream()
                .skip((long) safePage * safeSize)
                .limit(safeSize)
                .map(TaskListService::toSummary)
                .toList();

        return new TaskListPageDto(items, total, safePage, safeSize);
    }

    /** {@code filter} 为 null/空白 → 不过滤（返 true）；否则精确相等。 */
    private static boolean isBlankOrEquals(String filter, String actual) {
        return filter == null || filter.isBlank() || Objects.equals(filter, actual);
    }

    /** GatewayTask → 摘要 DTO（isError 仅 COMPLETED 有 result 时映射，其余 false）。 */
    private static TaskSummaryDto toSummary(GatewayTask t) {
        boolean isError = false;
        CallToolResult result = t.result();
        if (result != null && Boolean.TRUE.equals(result.isError())) {
            isError = true;
        }
        return new TaskSummaryDto(
                t.taskId(),
                t.toolName(),
                t.target(),
                t.status().name(),
                t.createdAt(),
                t.completedAt(),
                isError);
    }
}
```


---

## com/arthas/gateway/auth/BackendAuthCustomizer.java

**文件**：`src/main/java/com/arthas/gateway/auth/BackendAuthCustomizer.java`

```java
package com.arthas.gateway.auth;

import com.arthas.gateway.backend.BackendConfig;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * 后端认证头注入（T023，data-model.md §2 auth）。
 *
 * <p>实现官方 SDK {@link McpSyncHttpClientRequestCustomizer}（经 transport builder 的
 * {@code httpRequestCustomizer(...)} 注入，<b>非</b>已弃用的 {@code customizeRequest()}）。
 * 在每条发往后端的 HTTP 请求上按后端 auth 模式设置 {@code Authorization} 头：
 * <ul>
 *   <li>NONE：不发头；</li>
 *   <li>BEARER：{@code Authorization: Bearer <token>}；</li>
 *   <li>BASIC：{@code Authorization: Basic <base64(user:pass)>}。</li>
 * </ul>
 *
 * <p>认证头值在构造时按不可变 {@link BackendConfig.Auth} 预计算并缓存（同一后端请求间恒定），
 * {@link #customize} 仅应用之，不依赖 method/uri/body/context。
 */
public final class BackendAuthCustomizer implements McpSyncHttpClientRequestCustomizer {

    private static final String AUTHORIZATION = "Authorization";

    private final String headerValue;

    public BackendAuthCustomizer(BackendConfig.Auth auth) {
        this.headerValue = headerValue(Objects.requireNonNull(auth, "auth 不可为空"));
    }

    /** 工厂：便于调用方从 BackendConfig 直接构造。 */
    public static BackendAuthCustomizer forBackend(BackendConfig config) {
        return new BackendAuthCustomizer(config.auth());
    }

    /** 计算 {@code Authorization} 头值（NONE → null，表示不发头）。纯函数，便于单测。 */
    static String headerValue(BackendConfig.Auth auth) {
        return switch (auth.mode()) {
            case NONE -> null;
            case BEARER -> "Bearer " + auth.token();
            case BASIC -> "Basic " + Base64.getEncoder().encodeToString(
                    (auth.username() + ":" + auth.password()).getBytes(StandardCharsets.UTF_8));
        };
    }

    @Override
    public void customize(HttpRequest.Builder builder, String method, URI uri, String body, McpTransportContext context) {
        if (headerValue != null) {
            builder.header(AUTHORIZATION, headerValue);
        }
    }
}
```


---

## com/arthas/gateway/auth/GatewayAuthenticator.java

**文件**：`src/main/java/com/arthas/gateway/auth/GatewayAuthenticator.java`

```java
package com.arthas.gateway.auth;

import java.util.Map;

/**
 * 网关侧认证接口（T055 演进占位，宪法「认证」演进首要项）。
 *
 * <p>认证<b>调用方</b>是面向 Claude Code 的<b>入站</b> MCP 请求（与 {@link BackendAuthCustomizer} 区分——
 * 后者是网关作为客户端对<b>出站</b> arthas 后端的认证头注入）。
 *
 * <p><b>MVP 不承载逻辑</b>：受控内网、无认证（{@link NoopGatewayAuthenticator} 恒放行）。本接口为未来认证演进
 * （Bearer token / API key / mTLS 等）预留接入缝——届时实现此接口并接入请求过滤（如 servlet filter /
 * MCP 拦截器），MVP 阶段此 seam <b>不</b>接入请求流（{@code authenticate} 在 MVP 下不被调用）。
 *
 * <p>注意：后端侧 401（arthas 拒绝网关的认证头）属 C-AUTH-1，随认证后端夹具一并落地（见
 * {@code BackendClient} javadoc）——与本网关侧认证是两个独立关注点。
 */
public interface GatewayAuthenticator {

    /**
     * 校验入站请求的认证凭证（如 {@code Authorization} 头）。
     *
     * @param headers 入站请求头（只读视图；键大小写不敏感由实现处理）
     * @return {@code true}=放行；{@code false}=拒绝（调用方据此后续返 401 / JSON-RPC error）
     */
    boolean authenticate(Map<String, String> headers);
}
```


---

## com/arthas/gateway/auth/NoopGatewayAuthenticator.java

**文件**：`src/main/java/com/arthas/gateway/auth/NoopGatewayAuthenticator.java`

```java
package com.arthas.gateway.auth;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MVP 默认认证器——恒放行（{@link GatewayAuthenticator} 的 Noop 实现）。
 *
 * <p>对应 MVP 假设：网关部署在<b>受控内网</b>、无入站认证，靠网络隔离保护（见 spec 假设 / quickstart §1）。
 * 作为 {@code @Component} bean 存在于上下文，为未来认证演进提供可注入的默认实现——届时替换为真实认证器
 * （{@code BearerGatewayAuthenticator} 等）即可，无需改动接入点。
 *
 * <p>MVP 阶段<b>无人调用</b> {@link #authenticate}（请求流未接入认证过滤）；本 bean 仅作 DI 缝与演进锚点存在。
 */
@Component
public class NoopGatewayAuthenticator implements GatewayAuthenticator {

    @Override
    public boolean authenticate(Map<String, String> headers) {
        return true; // MVP 受控内网：恒放行（无入站认证逻辑）
    }
}
```


---

## com/arthas/gateway/backend/AuthMode.java

**文件**：`src/main/java/com/arthas/gateway/backend/AuthMode.java`

```java
package com.arthas.gateway.backend;

/**
 * 后端认证模式（data-model.md §2 {@code auth.mode}）。
 *
 * <ul>
 *   <li>{@link #NONE}：无认证（MVP 受控内网常见）。</li>
 *   <li>{@link #BEARER}：Bearer token（token == 后端 password，见
 *       {@code reference/arthas-docs/03-MCP/后端接入契约.md} §2.3）。</li>
 *   <li>{@link #BASIC}：HTTP Basic（{@code base64(user:pass)}）。</li>
 * </ul>
 * 认证头注入由 {@code auth/BackendAuthCustomizer}（T023）按此模式构造。
 */
public enum AuthMode {
    NONE,
    BEARER,
    BASIC
}
```


---

## com/arthas/gateway/backend/BackendClient.java

**文件**：`src/main/java/com/arthas/gateway/backend/BackendClient.java`

```java
package com.arthas.gateway.backend;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

/**
 * 单后端 MCP 客户端契约（data-model.md §3 {@code client}；行为承诺见
 * {@code contracts/backend-client-contract.md}）。
 *
 * <p>网关作为 <b>MCP 客户端</b>，每后端独立一个 {@code BackendClient}（独立连接池 + 独立
 * {@code McpClient} 会话 + 独立 SSE 解析，宪法原则三「局部故障韧性」：互不阻塞）。
 *
 * <p><b>真实实装属波次 C（T024）</b>：官方 SDK {@code HttpClientStreamableHttpTransport} 封装。
 * 本接口在波次 A 提前定义，使 {@link BackendEntry}（T025）与 {@link BackendRegistry}（T022）
 * 可在无真实 arthas 的纯逻辑单测中构建与编排（依赖倒置：定义抽象、延后实装）。
 *
 * <h3>方法语义（契约 §3/§4/§5）</h3>
 * <ul>
 *   <li>{@link #initialize()}：MCP {@code initialize} 握手（C-INIT-1/2/3）——发送 {@code protocolVersion}
 *       2025-11-25、{@code Accept} 含 json+SSE、启用认证带 {@code Authorization}；后端响应
 *       {@code Mcp-Session-Id} 保存后续回带。{@code STATELESS} 后端无 session。幂等：已握手则空操作。</li>
 *   <li>{@link #callTool(String, Map)}：同步 {@code tools/call} 转发（C-CALL-1/2/3、C-RESULT-1/2）。
 *       入参为<b>剥离 {@code target} 后</b>的 {@code backendArgs}；返回后端 {@code CallToolResult}
 *       <b>原样</b>（含 {@code isError=true}，不吞为成功）。dashboard SSE 多帧由实装聚合为一次结果。</li>
 *   <li>{@link #isInitialized()}：是否已握手（list-targets {@code healthy} 判定用）。</li>
 *   <li>{@link #close()}：优雅下线（§7）——Streamable 发 {@code DELETE /mcp} 关 session，或关连接池。</li>
 * </ul>
 *
 * <h3>故障语义（调用方 {@code ToolsCallRouter} 据异常计入熔断，T048 接线 / T043·T044 真实验证）</h3>
 * <ul>
 *   <li>基础设施故障（连接拒绝/超时、initialize 失败、读超时、SSE 中断）：<b>抛运行时异常</b> → 调用方
 *       {@code breaker.recordFailure()}（C-CB-1，T043 C-CB-1 真实 GREEN）。</li>
 *   <li>后端业务错误（{@code isError=true}/INVALID_PARAMS/JSON-RPC error）：<b>正常返回</b> CallToolResult 或
 *       SDK 抛 {@code McpError} → 调用方 {@code breaker.recordSuccess()}，<b>不计入</b>熔断（C-CB-2，
 *       T043 C-CB-2 真实 GREEN；宪法原则五）。</li>
 *   <li>401 + {@code WWW-Authenticate}（C-AUTH-1）：<b>随认证后端夹具一并落地</b>——需真实带认证 arthas
 *       后端 + 错 token 触发<b>真实 401</b>（TDD 硬约束：零桩）。当前 {@code ArthasMcpBackend} 仅 NONE
 *       认证、无 401 路径；落地时由调用方标记 target 不可用、对调用方返 JSON-RPC error（<b>不透传 HTTP</b>）。</li>
 * </ul>
 *
 * <p>{@link #close()} 缩窄为不抛 checked 异常（对齐 SDK {@code McpSyncClient.close()}）。
 */
public interface BackendClient extends AutoCloseable {

    /** 执行 {@code initialize} 握手（C-INIT-1/2/3）。幂等：已握手则空操作。 */
    void initialize();

    /**
     * 同步 {@code tools/call} 转发（C-CALL-1/2/3、C-RESULT-1/2）。
     *
     * @param name      工具名（如 {@code jvm}/{@code watch}）
     * @param arguments <b>剥离 {@code target} 后</b>的剩余键（原样转发后端）
     * @return 后端 {@code CallToolResult}（原样，含 {@code isError=true}）
     */
    McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments);

    /** 是否已完成 initialize 握手（list-targets {@code healthy} 判定用）。 */
    boolean isInitialized();

    /** 优雅下线（§7）：关 session / 连接池。不抛 checked。 */
    @Override
    void close();
}
```


---

## com/arthas/gateway/backend/BackendConfig.java

**文件**：`src/main/java/com/arthas/gateway/backend/BackendConfig.java`

```java
package com.arthas.gateway.backend;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * 单后端配置声明（data-model.md §2）。源自 {@code config/backends.yaml}，不可变值对象。
 *
 * <p>紧凑构造器做<b>单实例不变量校验</b>：name 非空、url 为合法 http(s) URL（含 host）、
 * protocol/auth 非空、超时为正、{@code maxConcurrentTasks} ∈ [1,5]（后端硬上限）。
 * 跨实例校验（name 唯一）由 {@link BackendConfigLoader} 负责。
 * 任意失败抛 {@link BackendConfigException}，以便热重载保留旧表（data-model.md §11 规则 7）。
 *
 * <p><b>{@code source} 来源标记</b>（003 动态纳管增量，data-model §2/§3）：
 * 最后一个组件，缺省 {@link Source#STATIC}。{@link #source()} 参与 {@code list-targets} 可观测
 * （区分静态种子 / 动态注册），但<b>不</b>参与 {@link #equals(Object)}/{@link #hashCode()} 的
 * 复用判定核心（data-model §2：同 name/url/auth/超时/并发、异 source 仍视为同一配置，
 * 以保连接池复用语义稳定）。{@code equals}/{@code hashCode} 显式覆写为排除 source 的 7 字段实现。
 */
public record BackendConfig(
        String name,
        String url,
        Protocol protocol,
        Auth auth,
        int connectTimeoutMs,
        int callTimeoutMs,
        int maxConcurrentTasks,
        Source source) {

    public BackendConfig {
        if (name == null || name.isBlank()) {
            throw new BackendConfigException("后端 name 不可为空");
        }
        Objects.requireNonNull(protocol, "protocol 不可为空");
        Objects.requireNonNull(auth, "auth 不可为空");
        requireHttpUrl(name, url);
        if (connectTimeoutMs <= 0) {
            throw new BackendConfigException(name + ": connectTimeoutMs 须为正数，实得 " + connectTimeoutMs);
        }
        if (callTimeoutMs <= 0) {
            throw new BackendConfigException(name + ": callTimeoutMs 须为正数，实得 " + callTimeoutMs);
        }
        if (maxConcurrentTasks < 1 || maxConcurrentTasks > 5) {
            throw new BackendConfigException(
                    name + ": maxConcurrentTasks 须 ∈ [1,5]（后端硬上限），实得 " + maxConcurrentTasks);
        }
        // source 缺省 STATIC（向后兼容：YAML 不写 source 视为 STATIC；既有 7 参调用点零改动）。
        if (source == null) {
            source = Source.STATIC;
        }
    }

    /**
     * 向后兼容构造器（不含 source）—— {@code source} 缺省 {@link Source#STATIC}。
     *
     * <p>保留 001 既有 7 参调用点（{@code BackendConfigLoader} 及各测试）零改动通过。
     */
    public BackendConfig(String name, String url, Protocol protocol, Auth auth,
                         int connectTimeoutMs, int callTimeoutMs, int maxConcurrentTasks) {
        this(name, url, protocol, auth, connectTimeoutMs, callTimeoutMs, maxConcurrentTasks, Source.STATIC);
    }

    /**
     * 复用判定 equals（data-model §2：排除 {@code source}）。
     *
     * <p>仅比较 7 个复用核心字段（name/url/protocol/auth/超时/并发）。{@code source} 为可观测标记，
     * 不影响"是否同一可复用后端"——故同核心字段、异 source 仍 equal（保连接池复用语义）。
     * 与 {@link #hashCode()} 协同（同字段集）。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BackendConfig that)) {
            return false;
        }
        return connectTimeoutMs == that.connectTimeoutMs
                && callTimeoutMs == that.callTimeoutMs
                && maxConcurrentTasks == that.maxConcurrentTasks
                && Objects.equals(name, that.name)
                && Objects.equals(url, that.url)
                && protocol == that.protocol
                && Objects.equals(auth, that.auth);
    }

    /**
     * 复用判定 hashCode（与 {@link #equals(Object)} 协同，排除 {@code source}）。
     */
    @Override
    public int hashCode() {
        return Objects.hash(name, url, protocol, auth, connectTimeoutMs, callTimeoutMs, maxConcurrentTasks);
    }

    /** 校验 url 为合法 http(s) 且含 host（data-model.md §2：形如 {@code http://host:8563/mcp}）。 */
    private static void requireHttpUrl(String name, String url) {
        if (url == null || url.isBlank()) {
            throw new BackendConfigException(name + ": url 不可为空");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new BackendConfigException(name + ": url 非法 → " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new BackendConfigException(name + ": url 须为 http/https scheme，实得 " + url);
        }
        if (uri.getHost() == null) {
            throw new BackendConfigException(name + ": url 须含 host，实得 " + url);
        }
    }

    /**
     * 后端认证（data-model.md §2 {@code auth}）。
     *
     * <p>紧凑构造器按 mode 校验凭据：BEARER 须 token；BASIC 须 username 与 password；NONE 忽略凭据。
     *
     * <p><b>toString 脱敏</b>(002 整改 P3-1/FR-011):覆写 record 默认 toString,<b>不</b>输出明文凭据——
     * 仅 mode + 掩码({@code ****} + 末 2 位;凭据 ≤2 位则仅 {@code ****},避免短凭据全泄露)。
     * 防止凭据经日志/异常栈泄漏。
     */
    public record Auth(AuthMode mode, String token, String username, String password) {

        public Auth {
            Objects.requireNonNull(mode, "auth.mode 不可为空");
            switch (mode) {
                case BEARER -> {
                    if (token == null || token.isBlank()) {
                        throw new BackendConfigException("BEARER 认证须提供 token");
                    }
                }
                case BASIC -> {
                    if (username == null || username.isBlank()
                            || password == null || password.isBlank()) {
                        throw new BackendConfigException("BASIC 认证须提供 username 与 password");
                    }
                }
                case NONE -> {
                    // 无凭据要求
                }
            }
        }

        @Override
        public String toString() {
            return switch (mode) {
                case BEARER -> "Auth[mode=BEARER, token=" + mask(token) + "]";
                case BASIC -> "Auth[mode=BASIC, username=" + mask(username)
                        + ", password=" + mask(password) + "]";
                case NONE -> "Auth[mode=NONE]";
            };
        }

        /** 凭据掩码:{@code ****} + 末 2 位;为 null 或 ≤2 位时仅 {@code ****}(不泄露任何明文片段)。 */
        private static String mask(String secret) {
            if (secret == null || secret.length() <= 2) {
                return "****";
            }
            return "****" + secret.substring(secret.length() - 2);
        }
    }
}
```


---

## com/arthas/gateway/backend/BackendConfigException.java

**文件**：`src/main/java/com/arthas/gateway/backend/BackendConfigException.java`

```java
package com.arthas.gateway.backend;

/**
 * 后端配置加载/校验失败（data-model.md §11 规则 7）。
 *
 * <p>YAML 解析、字段校验或跨实例校验（name 重复等）失败时抛出。热重载调用方（T039）
 * 捕获后<b>保留旧注册表</b>、记 ERROR、不半替换（原子性）。
 */
public class BackendConfigException extends RuntimeException {

    public BackendConfigException(String message) {
        super(message);
    }

    public BackendConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
```


---

## com/arthas/gateway/backend/BackendConfigLoader.java

**文件**：`src/main/java/com/arthas/gateway/backend/BackendConfigLoader.java`

```java
package com.arthas.gateway.backend;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 {@code config/backends.yaml} 为 {@link BackendConfig} 列表（data-model.md §2/§11，T021）。
 *
 * <p>纯逻辑、不依赖 Spring——后端列表<b>不走</b> {@code @ConfigurationProperties}，以支持
 * {@code WatchService} 热重载（免重启，SC-002；见 {@code GatewayProperties} 说明）。YAML 解析用
 * SnakeYAML（Spring Boot 传递带入，无需新增依赖）。
 *
 * <p>职责：
 * <ol>
 *   <li>读取顶层 {@code version}（单调整数，热重载去重所需，缺失则失败）；</li>
 *   <li>对每个后端应用默认值：{@code protocol=STREAMABLE}、{@code connectTimeoutMs=5000}、
 *       {@code callTimeoutMs=30000}、{@code maxConcurrentTasks=5}；</li>
 *   <li>对机密字段（token/username/password）解析 {@code ${ENV:default}} 占位符（避免明文入库）；</li>
 *   <li>跨实例校验 name 唯一；单后端字段校验委托 {@link BackendConfig} 紧凑构造器。</li>
 * </ol>
 * 任意失败抛 {@link BackendConfigException}。
 */
public final class BackendConfigLoader {

    /** 默认值（data-model.md §2）。 */
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_CALL_TIMEOUT_MS = 30000;
    private static final int DEFAULT_MAX_CONCURRENT_TASKS = 5;

    /** {@code ${VAR}} 或 {@code ${VAR:default}} 占位符（机密字段从环境变量取值，整值匹配）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?}");

    private final Function<String, String> placeholderResolver;

    /** 默认构造：占位符从系统环境变量解析。 */
    public BackendConfigLoader() {
        this(System::getenv);
    }

    /** 注入占位符解析器（测试用，便于不依赖进程级环境变量）。 */
    public BackendConfigLoader(Function<String, String> placeholderResolver) {
        this.placeholderResolver = placeholderResolver;
    }

    /** 解析 YAML 流为 {@code version + 后端列表}。 */
    public LoadedBackends load(InputStream in) {
        Map<String, Object> root;
        try {
            Object loaded = new Yaml().load(in);
            if (loaded == null) {
                throw new BackendConfigException("backends.yaml 为空");
            }
            if (!(loaded instanceof Map<?, ?> m)) {
                throw new BackendConfigException("backends.yaml 顶层须为映射");
            }
            root = toStringKeyed(m);
        } catch (BackendConfigException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BackendConfigException("backends.yaml 解析失败", e);
        }
        return new LoadedBackends(readVersion(root), readBackends(root));
    }

    /**
     * 严格版本号解析(002 整改 P3-4/FR-014):仅 {@link Integer}/{@link Long} 通过,
     * <b>拒浮点</b>(修复前 {@code n.longValue()} 接受 {@code 1.0},掩盖非整数版本号,使热重载去重语义模糊)。
     * 错误信息含原始值便于定位。
     */
    private long readVersion(Map<String, Object> root) {
        Object raw = root.get("version");
        if (raw == null) {
            throw new BackendConfigException("backends.yaml 缺 version 字段（热重载去重所需）");
        }
        if (raw instanceof Integer i) {
            return i.longValue();
        }
        if (raw instanceof Long l) {
            return l;
        }
        throw new BackendConfigException("version 须为整数,实得 " + describe(raw));
    }

    private List<BackendConfig> readBackends(Map<String, Object> root) {
        Object raw = root.get("backends");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new BackendConfigException("backends 须为数组");
        }
        List<BackendConfig> out = new ArrayList<>(list.size());
        Set<String> seen = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                throw new BackendConfigException("backends 元素须为映射");
            }
            BackendConfig cfg = toBackendConfig(toStringKeyed(m));
            if (!seen.add(cfg.name())) {
                throw new BackendConfigException("后端 name 重复：" + cfg.name());
            }
            out.add(cfg);
        }
        return List.copyOf(out);
    }

    private BackendConfig toBackendConfig(Map<String, Object> b) {
        String name = asString(b.get("name"));
        String url = asString(b.get("url"));
        Protocol protocol = b.containsKey("protocol")
                ? parseEnum(Protocol.class, asString(b.get("protocol")), "protocol")
                : Protocol.STREAMABLE;
        BackendConfig.Auth auth = readAuth(b.get("auth"), name);
        int connectTimeoutMs = b.containsKey("connectTimeoutMs")
                ? asInt(b.get("connectTimeoutMs"), "connectTimeoutMs") : DEFAULT_CONNECT_TIMEOUT_MS;
        int callTimeoutMs = b.containsKey("callTimeoutMs")
                ? asInt(b.get("callTimeoutMs"), "callTimeoutMs") : DEFAULT_CALL_TIMEOUT_MS;
        int maxConcurrentTasks = b.containsKey("maxConcurrentTasks")
                ? asInt(b.get("maxConcurrentTasks"), "maxConcurrentTasks") : DEFAULT_MAX_CONCURRENT_TASKS;
        // source 解析（003 增量，data-model §2/§3）：YAML 缺省 STATIC；显式 STATIC/DYNAMIC 直读。
        // 常态下动态 target 不经 YAML（由 DynamicBackendStore.register 强制 DYNAMIC），但 loader 仍须能解析显式值。
        Source source = b.containsKey("source")
                ? parseEnum(Source.class, asString(b.get("source")), name + ".source")
                : Source.STATIC;

        return new BackendConfig(name, url, protocol, auth, connectTimeoutMs, callTimeoutMs, maxConcurrentTasks, source);
    }

    private BackendConfig.Auth readAuth(Object raw, String backendName) {
        if (raw == null) {
            throw new BackendConfigException(backendName + ": 缺 auth 块");
        }
        if (!(raw instanceof Map<?, ?> m)) {
            throw new BackendConfigException(backendName + ": auth 须为映射");
        }
        Map<String, Object> a = toStringKeyed(m);
        if (!a.containsKey("mode")) {
            throw new BackendConfigException(backendName + ": auth.mode 缺失");
        }
        AuthMode mode = parseEnum(AuthMode.class, asString(a.get("mode")), backendName + ".auth.mode");
        String token = resolvePlaceholder(asString(a.get("token")));
        String username = resolvePlaceholder(asString(a.get("username")));
        String password = resolvePlaceholder(asString(a.get("password")));
        // BackendConfig.Auth 紧凑构造器校验 mode→凭据；此处补充 backend 名以便定位
        try {
            return new BackendConfig.Auth(mode, token, username, password);
        } catch (BackendConfigException e) {
            throw new BackendConfigException(backendName + ": " + e.getMessage(), e);
        }
    }

    /** 解析 {@code ${ENV:default}} / {@code ${ENV}}；非占位符原样返回。环境缺失：有默认→默认，无默认→null。 */
    private String resolvePlaceholder(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(value);
        if (!matcher.matches()) {
            return value;
        }
        String key = matcher.group(1);
        String def = matcher.group(2); // null 表示 ${VAR} 无默认值
        String envVal = placeholderResolver.apply(key);
        return envVal != null ? envVal : def;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BackendConfigException(field + " 不可为空");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BackendConfigException(field + " 取值非法：" + value);
        }
    }

    private static String asString(Object raw) {
        return raw == null ? null : String.valueOf(raw).trim();
    }

    /**
     * 严格整数解析(002 整改 P3-4/FR-014):仅 {@link Integer}/{@link Long}(在 int 范围内)通过,
     * <b>拒浮点</b>({@code Double}/{@code Float},修复前 {@code instanceof Number} 静默截断 {@code 5.0→5})、
     * <b>拒超 int 范围</b>的 Long(修复前 {@code n.intValue()} 截断为负/错值),错误信息含<b>原始值</b>便于定位。
     */
    private static int asInt(Object raw, String field) {
        if (raw instanceof Integer i) {
            return i;
        }
        if (raw instanceof Long l) {
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throw new BackendConfigException(field + " 须为 int 范围整数,实得（超界）" + raw);
            }
            return l.intValue();
        }
        throw new BackendConfigException(field + " 须为整数,实得 " + describe(raw));
    }

    /** 描述解析失败的原始值(含类型名,便于定位 YAML 配置错误)。 */
    private static String describe(Object raw) {
        return raw == null ? "null" : raw + "（类型 " + raw.getClass().getSimpleName() + "）";
    }

    private static Map<String, Object> toStringKeyed(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /** 解析结果（{@code version + 后端列表}），作为构建 {@code BackendRegistry}（T022）的输入。不可变。 */
    public record LoadedBackends(long version, List<BackendConfig> backends) {
        public LoadedBackends {
            backends = List.copyOf(backends);
        }
    }
}
```


---

## com/arthas/gateway/backend/BackendConfigWatcher.java

**文件**：`src/main/java/com/arthas/gateway/backend/BackendConfigWatcher.java`

```java
package com.arthas.gateway.backend;

import com.arthas.gateway.backend.BackendConfigLoader.LoadedBackends;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 热重载文件监听器（SC-002，T039/T040）：{@link WatchService} 监听 {@code backends.yaml} 所在目录，
 * 防抖 500ms 后重载 → {@link BackendRegistryReloader} diff（unchanged 复用）→ {@link RegistryHolder#getAndSet}
 * 原子替换 → 旧 Entry {@code markRetired} + 异步 {@code close}（in-flight 可完成，data-model.md §3）。
 *
 * <h3>故障语义（§11 规则 7）</h3>
 * <p>YAML 解析/校验失败（{@link BackendConfigException}）或读取 IO 失败 → <b>保留旧注册表</b>、记 ERROR，
 * 不半替换（原子性）。version 重复（{@link BackendRegistryReloader}）→ 忽略。
 *
 * <h3>生命周期</h3>
 * <p>{@link AutoCloseable}：{@code close()} 关 WatchService + 中断监听虚拟线程。Spring 装配为 @Bean
 * （{@code destroyMethod=infer}）在容器关闭时自动调用。配置文件无父目录/父目录不存在 → 记 WARN 不监听
 * （启动期初值仍由 {@code BackendRegistryBootstrap} 加载生效）。
 */
public final class BackendConfigWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BackendConfigWatcher.class);
    /** 事件防抖静默期：最后一次文件变更后静默此时长才触发重载（避免编辑器多次写盘抖动）。 */
    private static final Duration DEBOUNCE = Duration.ofMillis(500);
    /** 监听线程 poll 间隔（兼作 close 响应粒度）。 */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    /** 容器关闭时等待退役关闭调度器的上限(让已到期任务执行完;未到期 grace 内的在关闭时不再阻塞)。 */
    private static final Duration SHUTDOWN_AWAIT = Duration.ofSeconds(2);

    private final Path configFile;
    private final BackendConfigLoader loader;
    private final BackendRegistryReloader reloader;
    private final RegistryHolder holder;
    private final RegistryComposer composer;
    /**
     * 动态 target store（经 {@link ObjectProvider} 懒解析，规避 watcher↔store 构造期循环依赖：
     * watcher 读 store.list() 合并 dynamic；store 的 onChange 通知 watcher 重算）。运行期（reload/recompose）
     * 解析时 store bean 已就绪。
     */
    private final ObjectProvider<DynamicBackendStore> dynamicStoreProvider;
    private final Duration retirementGrace;
    /**
     * 当前<b>静态</b> registry 快照（仅 static 来源；reloader 据此 diff）。启动期 = holder 初值（bootstrap
     * 加载的 static，此时无动态）。热重载更新此快照；compose 用其 Entry + 动态列表合并为 effective。
     */
    private volatile BackendRegistry staticSnapshot;
    /**
     * 退役关闭调度器(可追踪,002 整改 P2-1/FR-007):替代裸 {@code Thread.startVirtualThread(sleep)} 的
     * fire-and-forget 虚拟线程堆积。{@code close()} 时 {@code shutdown} + {@code awaitTermination} 优雅回收。
     */
    private final ScheduledExecutorService retireScheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    private WatchService watchService;
    private Thread watcherThread;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    /** 默认装配 + 显式退役宽限(002 整改 P2-1/FR-007:生产装配传 backendTimeout=11min)。 */
    public BackendConfigWatcher(Path configFile, BackendEntryFactory factory, RegistryHolder holder,
                                RegistryComposer composer, ObjectProvider<DynamicBackendStore> dynamicStoreProvider,
                                Duration retirementGrace) {
        this(configFile, new BackendConfigLoader(), new BackendRegistryReloader(factory), holder,
                composer, dynamicStoreProvider, retirementGrace);
    }

    /**
     * 全参构造（测试注入 loader/reloader/composer/store/grace）。
     *
     * <p>启动期 {@code staticSnapshot} 取 holder 当前值（bootstrap 加载的 static；此时无动态 target，
     * 故 holder 初值即纯 static）。
     */
    public BackendConfigWatcher(Path configFile, BackendConfigLoader loader, BackendRegistryReloader reloader,
                                RegistryHolder holder, RegistryComposer composer,
                                ObjectProvider<DynamicBackendStore> dynamicStoreProvider, Duration retirementGrace) {
        this.configFile = java.util.Objects.requireNonNull(configFile, "configFile 不可为空");
        this.loader = java.util.Objects.requireNonNull(loader, "loader 不可为空");
        this.reloader = java.util.Objects.requireNonNull(reloader, "reloader 不可为空");
        this.holder = java.util.Objects.requireNonNull(holder, "holder 不可为空");
        this.composer = java.util.Objects.requireNonNull(composer, "composer 不可为空");
        this.dynamicStoreProvider = java.util.Objects.requireNonNull(dynamicStoreProvider, "dynamicStoreProvider 不可为空");
        this.retirementGrace = java.util.Objects.requireNonNull(retirementGrace, "retirementGrace 不可为空");
        this.staticSnapshot = holder.current();
    }

    /**
     * 启动监听（幂等）。配置文件父目录缺失 → 记 WARN 跳过（不影响启动期初值）。
     */
    public synchronized void start() throws IOException {
        Path dir = configFile.getParent();
        if (dir == null) {
            log.warn("配置文件无父目录（{}），跳过热重载监听", configFile);
            return;
        }
        if (!Files.isDirectory(dir)) {
            log.warn("配置文件父目录不存在（{}），跳过热重载监听（启动期初值仍生效）", dir);
            return;
        }
        watchService = FileSystems.getDefault().newWatchService();
        dir.register(watchService,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE);
        watcherThread = Thread.ofVirtual().name("backend-config-watcher").unstarted(() -> watchLoop(dir));
        watcherThread.start();
        log.info("热重载监听已启动：{}", configFile);
    }

    /** 监听循环：收集目标文件事件 → 防抖 → 重载。 */
    private void watchLoop(Path dir) {
        long lastEvent = 0L;
        boolean dirty = false;
        while (!closed.get() && !Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watchService.poll(POLL_INTERVAL.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (ClosedWatchServiceException e) {
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (key != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    Object ctx = event.context();
                    if (ctx instanceof Path changed && dir.resolve(changed).equals(configFile)) {
                        lastEvent = System.nanoTime();
                        dirty = true;
                    }
                }
                key.reset();
            }
            if (dirty && System.nanoTime() - lastEvent >= DEBOUNCE.toNanos()) {
                dirty = false;
                reloadOnce();
            }
        }
    }

    /**
     * 单次重载：加载 → 静态 diff（reloader 对 staticSnapshot）→ 合并动态（composer）→ 原子替换 → 下线。
     * 失败保留旧表。合并动态保证热重载不误删动态 target（I-2）。
     */
    void reloadOnce() {
        BackendRegistry current = holder.current();
        try {
            LoadedBackends loaded;
            try (InputStream in = Files.newInputStream(configFile)) {
                loaded = loader.load(in);
            }
            BackendRegistryReloader.ReloadResult result = reloader.reload(staticSnapshot, loaded);
            if (!result.changed()) {
                log.debug("热重载：static version 重复（{}），忽略", loaded.version());
                return;
            }
            staticSnapshot = result.registry();
            applyCompose();
            log.info("热重载完成：static version {}→{}，effective {} 个 target",
                    current.version(), result.registry().version(), holder.current().size());
        } catch (BackendConfigException e) {
            log.error("热重载失败，保留旧注册表（version={}）：{}", current.version(), e.getMessage());
        } catch (IOException e) {
            log.error("热重载读取失败，保留旧注册表（version={}）：{}", current.version(), configFile, e);
        }
    }

    /**
     * 动态变更触发重算（{@code DynamicBackendStore} register/unregister 的 onChange 回调）：
     * 用当前 staticSnapshot + 最新动态列表 compose → 原子替换 → 下线。仅实际变更才 swap（version 去重）。
     */
    public void recomposeForDynamicChange() {
        try {
            applyCompose();
        } catch (RuntimeException e) {
            log.error("动态变更重算失败，保留旧 effective registry", e);
        }
    }

    /** 当前静态种子名集合（供 {@code DynamicBackendStore} 做 I-3 命名冲突检测）。 */
    public Set<String> staticSnapshotNames() {
        return staticSnapshot.names();
    }

    /** 最新动态 cfg 列表（store 懒解析；运行期已就绪）。 */
    private Collection<BackendConfig> dynamicList() {
        return dynamicStoreProvider.getObject().list();
    }

    /**
     * 合并 staticSnapshot + 动态列表为 effective，原子替换 + 下线未复用 Entry（I-1/I-6）。
     * 无变更 → 跳过 getAndSet（version 去重，D-VERSION-1）。
     */
    private void applyCompose() {
        BackendRegistry previous = holder.current();
        RegistryComposer.ComposeResult composed = composer.compose(previous, staticSnapshot, dynamicList());
        if (!composed.changed()) {
            log.debug("compose 无变更，跳过 swap（version 去重）");
            return;
        }
        holder.getAndSet(composed.registry());
        retireAll(composed.toRetire());
    }

    /**
     * 优雅下线:立即 markRetired(新调用不再路由),延迟 grace 后关 client(让 in-flight 完成)。
     *
     * <p>002 整改 P2-1/FR-007:延迟关闭改由可追踪 {@link #retireScheduler} 调度(替代裸
     * {@code Thread.startVirtualThread(sleep)} 的 fire-and-forget 虚拟线程堆积),{@code close()} 可优雅回收。
     */
    private void retireAll(List<BackendEntry> toRetire) {
        for (BackendEntry entry : toRetire) {
            entry.markRetired();
            retireScheduler.schedule(() -> closeRetiredClient(entry),
                    retirementGrace.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** 退役后端 client 的延迟关闭(由 retireScheduler 在 grace 后触发)。 */
    private void closeRetiredClient(BackendEntry entry) {
        try {
            entry.client().close();
            log.debug("已关闭退役后端 client：{}", entry.config().name());
        } catch (RuntimeException e) {
            log.warn("关闭退役后端 client 失败：{}", entry.config().name(), e);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (watchService != null) {
                try {
                    watchService.close();
                } catch (IOException e) {
                    log.debug("关闭 WatchService 异常", e);
                }
            }
            if (watcherThread != null) {
                watcherThread.interrupt();
            }
            // 退役关闭调度器:shutdown 让已到期的退役关闭任务执行完;awaitTermination 有界等待(未到期 grace
            // 内的任务在容器关闭时不再阻塞——容器停止后由 JVM/OS 回收连接)。优雅回收,不再堆积 fire-and-forget 线程。
            retireScheduler.shutdown();
            try {
                if (!retireScheduler.awaitTermination(SHUTDOWN_AWAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    retireScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                retireScheduler.shutdownNow();
            }
        }
    }
}
```


---

## com/arthas/gateway/backend/BackendEntry.java

**文件**：`src/main/java/com/arthas/gateway/backend/BackendEntry.java`

```java
package com.arthas.gateway.backend;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * 单后端运行对象（data-model.md §3）+ <b>统一拦截层</b>（002 整改 · data-model.md §2）。
 *
 * <p>聚合：{@code config}（不可变声明）+ {@code client}（MCP 客户端契约）+ {@code breaker}（熔断器）+
 * {@code taskSlots}（Semaphore，per-target 限流）+ {@code state}（ACTIVE/RETIRED）+ {@code initialized}
 *（initialize 原子守卫，P1-4）。
 *
 * <p><b>不变量</b>：一次 {@code tools/call} 全程持有<b>固定的</b> BackendEntry 引用（调用方持 {@code final} 引用），
 * registry 原子替换不影响 in-flight 调用（data-model.md §3「热重载并发不串台」边缘情况）。
 *
 * <h3>统一拦截层（002 整改）</h3>
 * <p>原散在 {@code ToolsCallRouter} 两路径的「熔断守卫 + 取/还槽 + initialize + 故障分类」收口到此，
 * 使同步/异步共用同一分类规则（data-model.md §2.2）：
 * <ul>
 *   <li>{@link #execute}：<b>同步入口</b>。{@code admitCore}（熔断读 + 取槽）→ {@code invoke} → {@code finally releaseSlot}
 *       （RAII，同步路径槽结构性不漏，P0-2）。<b>不经 STATELESS 校验</b>（T013：STATELESS 同步仍可用）。</li>
 *   <li>{@link #admit}：<b>异步前置准入</b>（{@code submitAsync} 在 {@code asyncExecutor.submit} 前调用）。
 *       US1 = 熔断读 + 取槽；US3 在首部加 STATELESS 校验（仅异步）。释放唯一由 {@code AsyncTaskExecutor.onTerminal}
 *       负责（路由器闭包不再手动 releaseSlot，P0-2）。</li>
 *   <li>{@link #invoke}：真正调后端 + 故障分类（同步/异步共用，P1-3）。
 *       {@code initializeOnce}(CAS/DCL) → {@code callTool}；成功/业务错误（{@code McpError}/{@code isError=true}）
 *       → {@code recordSuccess}（不计熔断，C-CB-2）；基础设施故障（非 McpError 的 {@code RuntimeException}）
 *       → {@code recordFailure}（C-CB-1）并抛 {@link BackendUnreachableException}。<b>取消中断不计熔断</b>
 *       （检测 {@code Thread.interrupted()} 跳过 recordFailure，P1-3/cancel 方案）。</li>
 *   <li>{@link #isHealthy}：健康单一事实源（US5）：{@code state==ACTIVE && breaker.state()!=OPEN}。</li>
 * </ul>
 *
 * <p><b>错误边界</b>：{@code admitCore}/{@code invoke} 抛<b>域异常</b>（携带 {@code retryAfterMs}/
 * {@code maxConcurrentTasks}/{@code cause}），<b>不</b>依赖 {@code McpError}/注册表；结构化 {@code McpError}
 * （含 {@code data.available}）由 {@code ToolsCallRouter} 翻译（{@code data.available} 需 {@code RegistryHolder}）。
 *
 * @param config  后端声明（不可变）
 * @param client  MCP 客户端契约（真实实装 HttpBackendClient）
 * @param breaker 熔断器（注入时钟，便于无真实时间的单测）
 */
public final class BackendEntry {

    private final BackendConfig config;
    private final BackendClient client;
    private final CircuitBreaker breaker;
    private final Semaphore taskSlots;
    private final Object initLock = new Object();
    private volatile BackendState state = BackendState.ACTIVE;
    private volatile boolean initialized = false;

    public BackendEntry(BackendConfig config, BackendClient client, CircuitBreaker breaker) {
        this.config = Objects.requireNonNull(config, "config 不可为空");
        this.client = Objects.requireNonNull(client, "client 不可为空");
        this.breaker = Objects.requireNonNull(breaker, "breaker 不可为空");
        this.taskSlots = new Semaphore(config.maxConcurrentTasks());
    }

    public BackendConfig config() {
        return config;
    }

    public BackendClient client() {
        return client;
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    /** 当前运行态（ACTIVE=在册可用 / RETIRED=热重载移除中，in-flight 可完成）。 */
    public BackendState state() {
        return state;
    }

    /** US2 热重载移除时标 RETIRED：in-flight 持旧 Entry 可完成，新调用不再路由到此。 */
    public void markRetired() {
        this.state = BackendState.RETIRED;
    }

    /**
     * 同步执行一次完整调用（统一拦截层 · 同步入口，US1 T007）：
     * {@code admitCore}（熔断读 + 取槽）→ {@code invoke}（initialize + callTool + 分类）→ {@code finally releaseSlot}。
     *
     * <p>槽在 {@code admitCore} 取、{@code finally} 还，任意路径（成功/失败/异常）必配对 → 同步路径槽结构性不漏（P0-2）。
     *
     * @throws CircuitOpenException          熔断 OPEN 未满退避（admitCore）
     * @throws ConcurrencyLimitException     并发越界（admitCore）
     * @throws BackendUnreachableException   基础设施故障（invoke）；后端业务错误 {@code McpError} 原样向上抛
     */
    public CallToolResult execute(String toolName, Map<String, Object> backendArgs) {
        admitCore();
        try {
            return invoke(toolName, backendArgs);
        } finally {
            releaseSlot();
        }
    }

    /**
     * 异步前置准入（{@code submitAsync} 在提交后台前调用，US1 T007 + US3 T013）。
     *
     * <p>首检 <b>STATELESS 协议</b>（US3/P1-2）：无状态后端无法承载带任务语义、需轮询的异步诊断调用，
     * 嫡出 {@link StatelessAsyncException}（路由器翻译为 {@code INVALID_PARAMS} +
     * {@code reason=stateless_unsupported_async}）——前置拒绝，<b>不</b>提交后台、不耗兜底超时、不取槽。
     * 随后熔断读 + 取槽（与 {@code execute} 共用 {@link #admitCore}）。
     *
     * <p><b>仅异步路径</b>校验：同步 {@code execute} 直调 {@code admitCore}（不经 STATELESS 检查），
     * 故 STATELESS 后端同步调用仍正常（T013 不变量）。
     * 取得的槽由 {@code AsyncTaskExecutor.onTerminal}（→ {@link #releaseSlot()}）在任务终态/提交失败时释放。
     *
     * @param toolName 工具名（日志/未来可观测用；STATELESS 校验不依赖具体工具名——协议是后端级属性）
     * @throws StatelessAsyncException   后端协议为 STATELESS（仅异步）
     * @throws CircuitOpenException      熔断 OPEN 未满退避
     * @throws ConcurrencyLimitException 并发越界
     */
    public void admit(String toolName) {
        if (config.protocol() == Protocol.STATELESS) {
            throw new StatelessAsyncException();
        }
        admitCore();
    }

    /** 准入核心：熔断守卫读 + 取槽（同步 execute 与异步 admit 共用）。 */
    private void admitCore() {
        if (!breaker.allowRequest()) {
            throw new CircuitOpenException(breaker.retryAfterMillis());
        }
        if (!taskSlots.tryAcquire()) {
            throw new ConcurrencyLimitException(config.maxConcurrentTasks());
        }
    }

    /**
     * 调用后端 + initialize + 故障分类 + 熔断驱动（统一拦截层核心，US1 T007 + US2 T011）。
     *
     * <p>同步/异步共用（P1-3）：返回后端<b>原始</b> {@code CallToolResult}（含 {@code isError=true}，原样透传，原则二）。
     * <ul>
     *   <li>正常返回（含 {@code isError=true} 业务错误）→ {@code recordSuccess}（不计熔断，C-CB-2）。</li>
     *   <li>{@code McpError}（后端 JSON-RPC error）→ {@code recordSuccess} 后<b>原样抛出</b>（不计熔断，C-CB-2）。</li>
     *   <li>非 McpError 的 {@code RuntimeException}（基础设施故障，含 initialize 失败）→
     *       若当前线程<b>中断态</b>（取消导致）<b>跳过</b> recordFailure（避免 cancel 误熔断，P1-3/cancel），
     *       否则 {@code recordFailure}（C-CB-1）后抛 {@link BackendUnreachableException}。</li>
     * </ul>
     */
    public CallToolResult invoke(String toolName, Map<String, Object> backendArgs) {
        try {
            initializeOnce();
            CallToolResult result = client.callTool(toolName, backendArgs);
            breaker.recordSuccess();
            return result;
        } catch (McpError e) {
            breaker.recordSuccess();
            throw e;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                // 取消导致的中断：不计熔断（避免 cancel 误开熔断拖累同步路径）；仍抛供上层标 cancelled/failed
                throw new BackendUnreachableException(e);
            }
            breaker.recordFailure();
            throw new BackendUnreachableException(e);
        }
    }

    /**
     * 幂等 initialize 守卫（P1-4/FR-006，自 HttpBackendClient 上移至拦截层，便于注入测试）。
     *
     * <p>双检锁（volatile + synchronized）：仅首个持锁者真正 {@code client.initialize()}，其余并发调用方<b>等待</b>
     * 其完成后跳过（裸 CAS 会让 loser 抢跑 callTool 导致会话未就绪，故用 DCL 保证「恰好一次 + 其余等待」）。
     * 握手失败时 {@code initialized} 保持 false → 下次调用重试（不毒化 entry）。
     */
    private void initializeOnce() {
        if (initialized) {
            return;
        }
        synchronized (initLock) {
            if (!initialized) {
                client.initialize();
                initialized = true;
            }
        }
    }

    /** 健康单一事实源（US5/FR-012）：{@code ACTIVE} 且熔断非 OPEN。list-targets/HealthIndicator/守卫共用。 */
    public boolean isHealthy() {
        return state == BackendState.ACTIVE && breaker.state() != CircuitBreaker.State.OPEN;
    }

    /**
     * 非阻塞获取一个并发槽（既有，保留供可观测/测试；生产路由已改走 {@link #admit}）。
     *
     * @return true=获槽；false=越界
     */
    public boolean tryAcquireSlot() {
        return taskSlots.tryAcquire();
    }

    /** 释放一个并发槽（RAII finally / 异步 onTerminal 共用）。 */
    public void releaseSlot() {
        taskSlots.release();
    }

    /** 当前可用并发许可数（list-targets / 可观测 / 测试用）。 */
    public int availableSlots() {
        return taskSlots.availablePermits();
    }
}
```


---

## com/arthas/gateway/backend/BackendEntryFactory.java

**文件**：`src/main/java/com/arthas/gateway/backend/BackendEntryFactory.java`

```java
package com.arthas.gateway.backend;

import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * 后端运行对象工厂（T027 装配）：{@link BackendConfig} → {@link BackendEntry}。
 *
 * <p>聚合三件套：
 * <ul>
 *   <li>{@link HttpBackendClient}——真实 MCP 客户端（T024，独立连接池 + 会话 + 认证头）；构造不连，
 *       首次 {@code initialize()}（路由转发时）才建立后端会话。</li>
 *   <li>{@link CircuitBreaker}——熔断器（T045），注入纳秒时钟；Phase 3 初始 CLOSED，Phase 5 T048 接入
 *       {@code allowRequest}/{@code recordSuccess}/{@code recordFailure} 守卫。</li>
 *   <li>{@link BackendEntry}——含 {@code taskSlots}（Semaphore(maxConcurrentTasks)，T047 per-target 限流）。</li>
 * </ul>
 *
 * <p>时钟可注入（测试用固定时钟驱动熔断退避）；默认 {@link System#nanoTime}（单调纳秒）。
 */
@Component
public class BackendEntryFactory {

    private final LongSupplier clock;

    /** 默认系统纳秒时钟。 */
    public BackendEntryFactory() {
        this(System::nanoTime);
    }

    /** 注入时钟（测试驱动熔断退避时间）。 */
    public BackendEntryFactory(LongSupplier clock) {
        this.clock = clock;
    }

    /** 由后端配置构造运行对象（client 不连、breaker=CLOSED、slots=Semaphore(maxConcurrentTasks)）。 */
    public BackendEntry create(BackendConfig config) {
        BackendClient client = new HttpBackendClient(config);
        CircuitBreaker breaker = CircuitBreaker.create(clock);
        return new BackendEntry(config, client, breaker);
    }
}
```


---

## com/arthas/gateway/backend/BackendRegistry.java

**文件**：`src/main/java/com/arthas/gateway/backend/BackendRegistry.java`

```java
package com.arthas.gateway.backend;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 全部后端的不可变快照（data-model.md §4）。
 *
 * <p>含 {@code version}（配置版本号，单调递增，热重载去重）与 {@code byName}（逻辑名→{@link BackendEntry}）。
 * <b>不可变</b>：构造时对入参 Map 做 {@link Map#copyOf} 防御性拷贝，构造后任何对原 Map 的篡改均不泄漏。
 *
 * <p>由 {@link RegistryHolder} 经 {@code AtomicReference} 持有，热重载时<b>整体替换</b>（非增量修改），
 * 保证一次 {@code tools/call} 全程持有固定的 {@code BackendEntry} 引用——registry 替换不影响 in-flight
 * 调用（data-model.md §3 不变量「热重载并发不串台」）。
 *
 * @param version 配置版本号
 * @param byName  逻辑名→后端运行对象（构造时拷贝为不可变 Map）
 */
public record BackendRegistry(long version, Map<String, BackendEntry> byName) {

    public BackendRegistry {
        byName = Map.copyOf(byName); // 防御性不可变拷贝（拒绝 null key/value）
    }

    /** 空注册表（version=0，无后端）：启动期未加载 / 校验失败保留旧的兜底初始态。 */
    public static BackendRegistry empty() {
        return new BackendRegistry(0L, Map.of());
    }

    /** 按逻辑名取后端（缺失返 {@link Optional#empty()}）。 */
    public Optional<BackendEntry> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** 全部逻辑名（不可变视图）。 */
    public Set<String> names() {
        return byName.keySet();
    }

    /** 后端数量。 */
    public int size() {
        return byName.size();
    }
}
```


---

## com/arthas/gateway/backend/BackendRegistryReloader.java

**文件**：`src/main/java/com/arthas/gateway/backend/BackendRegistryReloader.java`

```java
package com.arthas.gateway.backend;

import com.arthas.gateway.backend.BackendConfigLoader.LoadedBackends;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 热重载核心逻辑（data-model.md §11 规则 7/8，T039）：旧 {@link BackendRegistry} + 新
 * {@link LoadedBackends} → 构造新 registry（unchanged 后端<b>复用</b>旧 Entry，保连接池/session）+
 * 待优雅下线的旧 Entry 列表。纯逻辑、无 WatchService / 无线程，便于单测。
 *
 * <h3>diff 规则</h3>
 * <ul>
 *   <li><b>unchanged</b>：name 在新表且 {@link BackendConfig#equals(Object)} 完全相等（含 url/auth/超时/并发）
 *       → <b>复用</b>旧 Entry（同实例，已热连接的 client/breaker/slots 原样保留，免重连）。</li>
 *   <li><b>added / changed</b>：name 不在旧表，或 config 变更（同 name 不同 url 等）→ {@link BackendEntryFactory#create}
 *       新建 Entry（连接按需在首次路由时建立）。</li>
 *   <li><b>toRetire</b>：旧表 Entry 未被复用（name 移除，或 config 变更被新 Entry 取代）→ 进入下线列表，
 *       交调用方 {@code markRetired} + 异步 {@code close}（in-flight 可完成，data-model.md §3 不变量）。</li>
 * </ul>
 *
 * <h3>version 去重</h3>
 * <p>{@code next.version == current.version} → {@link ReloadResult#changed()}=false，不重建
 * （data-model.md §2「version 单调递增，热重载去重，重复忽略」）。调用方据此跳过 getAndSet。
 *
 * <p><b>校验失败不在本类</b>：YAML 解析/校验失败由 {@link BackendConfigLoader} 抛
 * {@link BackendConfigException}，调用方（{@code BackendConfigWatcher}）catch 后<b>保留旧表</b>
 * （§11 规则 7），本类收到的 {@code next} 已是合法加载结果。
 */
public final class BackendRegistryReloader {

    private final BackendEntryFactory factory;

    public BackendRegistryReloader(BackendEntryFactory factory) {
        this.factory = java.util.Objects.requireNonNull(factory, "factory 不可为空");
    }

    /**
     * 由旧 registry 与新加载结果构造重载结果。
     *
     * @param current 当前 registry（启动期或上次重载的快照）
     * @param next    新解析的 {@code version + 后端列表}（已校验合法）
     * @return 重载结果（新 registry + 待下线 Entry + 是否变更）
     */
    public ReloadResult reload(BackendRegistry current, LoadedBackends next) {
        java.util.Objects.requireNonNull(current, "current registry 不可为空");
        java.util.Objects.requireNonNull(next, "next 不可为空");
        if (next.version() == current.version()) {
            // version 重复 → 忽略（不重建、不下线）
            return ReloadResult.unchanged(current);
        }
        Map<String, BackendEntry> byName = new LinkedHashMap<>();
        Set<BackendEntry> reused = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BackendConfig cfg : next.backends()) {
            BackendEntry existing = current.byName().get(cfg.name());
            if (existing != null && existing.config().equals(cfg)) {
                byName.put(cfg.name(), existing); // unchanged → 复用旧 Entry（保连接）
                reused.add(existing);
            } else {
                byName.put(cfg.name(), factory.create(cfg)); // added / changed → 新建
            }
        }
        List<BackendEntry> toRetire = new ArrayList<>();
        for (BackendEntry e : current.byName().values()) {
            if (!reused.contains(e)) {
                toRetire.add(e); // name 移除 或 config 变更（旧 Entry 被取代）→ 下线
            }
        }
        return new ReloadResult(new BackendRegistry(next.version(), byName), List.copyOf(toRetire), true);
    }

    /**
     * 重载结果。
     *
     * @param registry 新 registry（{@code changed=false} 时为原 current）
     * @param toRetire 待优雅下线的旧 Entry（{@code changed=false} 时为空）
     * @param changed  是否实际变更（version 重复时为 false，调用方据此跳过 getAndSet）
     */
    public record ReloadResult(BackendRegistry registry, List<BackendEntry> toRetire, boolean changed) {
        public ReloadResult {
            java.util.Objects.requireNonNull(registry, "registry 不可为空");
            toRetire = List.copyOf(toRetire);
        }

        /** version 重复：不变更，registry 维持原样、无下线。 */
        static ReloadResult unchanged(BackendRegistry current) {
            return new ReloadResult(current, List.of(), false);
        }
    }
}
```


---

## com/arthas/gateway/backend/BackendState.java

**文件**：`src/main/java/com/arthas/gateway/backend/BackendState.java`

```java
package com.arthas.gateway.backend;

/**
 * 后端运行态（data-model.md §3）。
 *
 * <ul>
 *   <li>{@link #ACTIVE}：在册可用，新调用可路由到此。</li>
 *   <li>{@link #RETIRED}：热重载移除中（US2），in-flight 调用仍可完成，新调用不再路由到此。</li>
 * </ul>
 * 一次 {@code tools/call} 全程持有固定的 {@code BackendEntry} 引用，registry 替换不影响 in-flight 调用。
 */
public enum BackendState {
    ACTIVE,
    RETIRED
}
```


---

## com/arthas/gateway/backend/BackendUnreachableException.java

**文件**：`src/main/java/com/arthas/gateway/backend/BackendUnreachableException.java`

```java
package com.arthas.gateway.backend;

/**
 * 目标后端不可达(002 整改 · 统一拦截层域异常，P1-3)。
 *
 * <p>{@code BackendEntry.invoke} 调后端抛基础设施故障(连接拒绝/超时/initialize 失败/SSE 中断)时，
 * 经故障分类 {@code recordFailure} 后包装抛出。<b>取消中断导致的异常不计入熔断</b>
 * (invoke 内检测 {@code Thread.interrupted()} 跳过 recordFailure，但仍抛此异常供上层标 cancelled/failed)。
 *
 * <p>由 {@code ToolsCallRouter} 翻译为结构化 {@code McpError}({@code backend_unreachable}，
 * data 含 {@code available})——与修复前逐字一致(FR-016)。
 */
public final class BackendUnreachableException extends RuntimeException {

    public BackendUnreachableException(Throwable cause) {
        super("target 不可达：" + cause.getClass().getSimpleName()
                + ": " + String.valueOf(cause.getMessage()), cause);
    }
}
```


---

## com/arthas/gateway/backend/CircuitBreaker.java

**文件**：`src/main/java/com/arthas/gateway/backend/CircuitBreaker.java`

```java
package com.arthas.gateway.backend;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 单后端熔断器状态机（data-model.md §8，故障隔离）。
 *
 * <pre>
 *   CLOSED ──连续 N(=3) 次失败──► OPEN ──退避后──► HALF_OPEN ──探测成功──► CLOSED
 *                                   ▲                               │
 *                                   └───────────探测失败（退避升级）──┘
 * </pre>
 *
 * <ul>
 *   <li>CLOSED：正常转发。</li>
 *   <li>OPEN：{@link #allowRequest()} 立即返 false（不等 30s），调用方据此返明确错误。</li>
 *   <li>HALF_OPEN：退避期满后放 <b>1 个</b>探测；探测成功→CLOSED、失败→OPEN（退避升级）。</li>
 * </ul>
 *
 * <p>退避：base 1s、每轮 ×2、cap 30s；恢复（→CLOSED）后重置为基础值。
 *
 * <p><b>失败计入与否由调用方裁决</b>（data-model.md §8）：仅基础设施故障（连接拒绝/超时、initialize 失败、
 * 读超时、SSE 中断）调用 {@link #recordFailure()}；后端业务错误（isError=true/INVALID_PARAMS）是正常响应，
 * 调用 {@link #recordSuccess()}，<b>不计入</b>熔断（对齐宪法原则五：错误显式传播）。
 *
 * <p><b>线程安全（002 整改 P1-1/FR-003）</b>：全部可变状态访问方法均 {@code synchronized}。
 * 默认 {@code maxConcurrentTasks=5}（Semaphore 允许多线程并发操作熔断），"并发由槽串行化保证"的前提
 * 不成立（评审 P1-1）；synchronized 使连续失败计数累加、OPEN/HALF_OPEN 状态转换、HALF_OPEN 仅放 1 探测
 * 均原子一致——无丢失更新、无状态撕裂、无半开放行多探测。熔断非热路径，synchronized 开销可忽略。
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public static final int DEFAULT_FAILURE_THRESHOLD = 3;
    public static final Duration DEFAULT_BASE_BACKOFF = Duration.ofSeconds(1);
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(30);

    private final LongSupplier nanoClock;
    private final int failureThreshold;
    private final long baseBackoffNanos;
    private final long maxBackoffNanos;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private int consecutiveOpens = 0;
    private long openedAtNanos = 0L;
    private long currentBackoffNanos = 0L;

    public CircuitBreaker(LongSupplier nanoClock, int failureThreshold, Duration baseBackoff, Duration maxBackoff) {
        this.nanoClock = nanoClock;
        this.failureThreshold = failureThreshold;
        this.baseBackoffNanos = baseBackoff.toNanos();
        this.maxBackoffNanos = maxBackoff.toNanos();
    }

    /** 默认参数（阈值 3、base 1s、cap 30s）+ 注入时钟。 */
    public static CircuitBreaker create(LongSupplier nanoClock) {
        return new CircuitBreaker(nanoClock, DEFAULT_FAILURE_THRESHOLD, DEFAULT_BASE_BACKOFF, DEFAULT_MAX_BACKOFF);
    }

    public synchronized State state() {
        return state;
    }

    /**
     * 熔断 OPEN 时建议的重试等待（毫秒，剩余退避）；非 OPEN 返 0。
     *
     * <p>用于失效 target 结构化错误（S-ERR-5）的 {@code retryAfterMs} 字段，告知调用方何时可再试。
     * OPEN 但已满退避时返 0（下次 {@link #allowRequest()} 会转 HALF_OPEN 放探测）。
     */
    public synchronized long retryAfterMillis() {
        if (state != State.OPEN) {
            return 0L;
        }
        long remainingNanos = currentBackoffNanos - (nanoClock.getAsLong() - openedAtNanos);
        return Math.max(0L, Duration.ofNanos(remainingNanos).toMillis());
    }

    /** 是否放行请求。CLOSED→true；OPEN→满退避则转 HALF_OPEN 放 1 探测，否则 false；HALF_OPEN→false（探测在途）。 */
    public synchronized boolean allowRequest() {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> {
                if (nanoClock.getAsLong() - openedAtNanos >= currentBackoffNanos) {
                    state = State.HALF_OPEN;
                    yield true;
                }
                yield false;
            }
            case HALF_OPEN -> false;
        };
    }

    /** 记录一次成功：清连续失败计数；HALF_OPEN 探测成功→CLOSED（退避重置）。 */
    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            consecutiveOpens = 0;
        }
    }

    /** 记录一次基础设施失败：CLOSED 累计达阈值→OPEN；HALF_OPEN 探测失败→OPEN（退避升级）；OPEN 忽略。 */
    public synchronized void recordFailure() {
        switch (state) {
            case CLOSED -> {
                consecutiveFailures++;
                if (consecutiveFailures >= failureThreshold) {
                    open();
                }
            }
            case HALF_OPEN -> open();
            case OPEN -> {
                // 已 OPEN，不刷新 openedAt（避免人为延长阻断）
            }
        }
    }

    /** 进入 OPEN：退避 = min(base × 2^(opens-1), max)，opens 累加。 */
    private void open() {
        consecutiveOpens++;
        currentBackoffNanos = escalatedBackoff();
        openedAtNanos = nanoClock.getAsLong();
        state = State.OPEN;
    }

    private long escalatedBackoff() {
        int shift = consecutiveOpens - 1;
        if (shift >= 31) {
            return maxBackoffNanos; // 必然超 cap，避免位移溢出
        }
        return Math.min(baseBackoffNanos * (1L << shift), maxBackoffNanos);
    }
}
```


---

## com/arthas/gateway/backend/CircuitOpenException.java

**文件**：`src/main/java/com/arthas/gateway/backend/CircuitOpenException.java`

```java
package com.arthas.gateway.backend;

/**
 * 目标熔断中(002 整改 · 统一拦截层域异常，P1-3)。
 *
 * <p>{@code BackendEntry.admit} 读熔断守卫发现 breaker 处于 OPEN(未满退避)时抛出。
 * 由 {@code ToolsCallRouter} 翻译为结构化 {@code McpError}({@code backend_unreachable}，
 * data 含 {@code retryAfterMs}、{@code available})——字段与修复前逐字一致(FR-016)。
 *
 * <p>携带 {@code retryAfterMs} 供翻译层填充 {@code data.retryAfterMs}，<b>不</b>依赖 {@code McpError}/注册表
 * (错误边界：BackendEntry 抛域异常，路由器翻译)。
 */
public final class CircuitOpenException extends RuntimeException {

    private final long retryAfterMs;

    public CircuitOpenException(long retryAfterMs) {
        super("target 熔断中(OPEN)，剩余退避 " + retryAfterMs + "ms");
        this.retryAfterMs = retryAfterMs;
    }

    /** 熔断建议重试等待(毫秒)，填入 {@code data.retryAfterMs}。 */
    public long retryAfterMs() {
        return retryAfterMs;
    }
}
```


---

## com/arthas/gateway/backend/ConcurrencyLimitException.java

**文件**：`src/main/java/com/arthas/gateway/backend/ConcurrencyLimitException.java`

```java
package com.arthas.gateway.backend;

/**
 * 目标并发槽已满(002 整改 · 统一拦截层域异常)。
 *
 * <p>{@code BackendEntry.admit} 取槽({@code tryAcquireSlot})失败(并发已达 {@code maxConcurrentTasks})时抛出。
 * 由 {@code ToolsCallRouter} 翻译为结构化 {@code McpError}({@code INVALID_PARAMS}，
 * data 含 {@code maxConcurrentTasks})——与修复前逐字一致(FR-016)。
 */
public final class ConcurrencyLimitException extends RuntimeException {

    private final int maxConcurrentTasks;

    public ConcurrencyLimitException(int maxConcurrentTasks) {
        super("target 并发已达上限：maxConcurrentTasks=" + maxConcurrentTasks);
        this.maxConcurrentTasks = maxConcurrentTasks;
    }

    /** 该目标的并发上限，填入 {@code data.maxConcurrentTasks}。 */
    public int maxConcurrentTasks() {
        return maxConcurrentTasks;
    }
}
```


---

## com/arthas/gateway/backend/DynamicBackendStore.java

**文件**：`src/main/java/com/arthas/gateway/backend/DynamicBackendStore.java`

```java
package com.arthas.gateway.backend;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 动态 target 内存态（data-model §4）。程序化写入动态 target 的唯一入口
 * （{@code ensure-arthas-mcp} 经此注册；{@link RegistryComposer} 经 {@link #list()} 读动态集合）。
 *
 * <p>线程安全：内部 {@link ConcurrentHashMap}。变更后通知调用方重算 effective registry
 * （{@code onChange} 回调 → {@code RegistryComposer.compose} → {@code RegistryHolder.getAndSet} 原子替换）。
 *
 * <p>冲突检测（I-3，{@code register} 内）：
 * <ul>
 *   <li>动态名 ∩ 静态种子名 → 拒绝（保护静态配置）；静态种子名由 {@code staticNames} 供应商提供。</li>
 *   <li>动态名 ∩ 既有动态名、同名<b>异</b> URL → 拒绝。</li>
 *   <li>同名<b>同</b> URL → 幂等（无副作用、不重复触发变更回调）。</li>
 * </ul>
 *
 * @param staticNames 当前静态种子名的供应商（I-3 冲突检测用；解耦 store 与静态注册表持有者）
 * @param onChange    实际变更（新增/更新/移除）时触发的回调，由调用方 wiring 为 compose+swap
 */
public class DynamicBackendStore {

    private final ConcurrentHashMap<String, BackendConfig> byName = new ConcurrentHashMap<>();
    private final Supplier<Set<String>> staticNames;
    private final Runnable onChange;

    public DynamicBackendStore(Supplier<Set<String>> staticNames, Runnable onChange) {
        this.staticNames = staticNames;
        this.onChange = onChange;
    }

    /**
     * 注册一个动态 target（contracts §4.1）。
     *
     * <ol>
     *   <li>强制 {@code source=DYNAMIC}（非 DYNAMIC 拒绝）。</li>
     *   <li>冲突检测：与静态种子同名 / 与既有动态同名异 URL → 抛 {@link BackendConfigException}。</li>
     *   <li>同名同 URL → 幂等（无变更回调）。</li>
     *   <li>写入并 {@code onChange} 触发 compose。</li>
     * </ol>
     */
    public void register(BackendConfig cfg) {
        if (cfg.source() != Source.DYNAMIC) {
            throw new BackendConfigException(
                    "动态注册须 source=DYNAMIC，实得 " + cfg.source() + "（name=" + cfg.name() + "）");
        }
        String name = cfg.name();
        if (staticNames.get().contains(name)) {
            throw new BackendConfigException("动态注册名与静态种子冲突，拒绝（保护静态）：" + name);
        }
        BackendConfig existing = byName.get(name);
        if (existing != null) {
            if (!existing.url().equals(cfg.url())) {
                throw new BackendConfigException("动态注册名与既有动态同名异 URL，拒绝：" + name
                        + "（既有 " + existing.url() + "，新 " + cfg.url() + "）");
            }
            if (existing.equals(cfg)) {
                // 同名同 URL 且核心字段一致 → 幂等：不重复触发变更回调
                return;
            }
        }
        byName.put(name, cfg);
        onChange.run();
    }

    /**
     * 移除一个动态 target（contracts §4.2）。仅 DYNAMIC 可移（store 仅持动态，故静态名不在此 → 无操作）。
     * 不存在 → 幂等无操作。实际移除时 {@code onChange} 触发 compose。
     *
     * @return 是否实际移除（false = 不存在/静态名，幂等无操作）
     */
    public boolean unregister(String name) {
        BackendConfig removed = byName.remove(name);
        if (removed == null) {
            return false;
        }
        onChange.run();
        return true;
    }

    /** 当前动态 target 的不可变快照。 */
    public List<BackendConfig> list() {
        return List.copyOf(byName.values());
    }

    /** 取单个（缺失返 empty）。 */
    public Optional<BackendConfig> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }
}
```


---

## com/arthas/gateway/backend/HttpBackendClient.java

**文件**：`src/main/java/com/arthas/gateway/backend/HttpBackendClient.java`

```java
package com.arthas.gateway.backend;

import com.arthas.gateway.auth.BackendAuthCustomizer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Implementation;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 真实 HTTP Streamable 后端客户端（T024 实装，波次 C）。
 *
 * <p>官方 SDK {@link HttpClientStreamableHttpTransport} + {@link McpSyncClient} 封装，每后端独立一个实例
 * （独立连接池 + 独立 {@code McpClient} 会话 + 独立 SSE 解析，宪法原则三「局部故障韧性」：互不阻塞）。
 *
 * <p>认证头经 {@link BackendAuthCustomizer}（transport builder 的 {@code httpRequestCustomizer}）注入
 * （NONE 不发头、BEARER/BASIC 注入 {@code Authorization}）。连接/请求超时取自 {@link BackendConfig}。
 *
 * <p><b>端点为根 URL</b>（{@link BackendConfig#url()}，§9 实证：arthas 4.3.0 MCP 端点无 {@code /mcp} 后缀，
 * T009 首测 GREEN 裁决；{@code backend-client-contract.md §1} 的 {@code /mcp} 假设以实测为准）。
 *
 * <h3>故障语义（契约 §5；调用方 {@code ToolsCallRouter} 计入熔断）</h3>
 * <ul>
 *   <li>基础设施故障（连接拒绝/超时/initialize 失败/读超时/SSE 中断）：SDK 抛运行时异常 →
 *       调用方 {@code breaker.recordFailure()}（C-CB-1，T043 真实 GREEN）。</li>
 *   <li>后端业务错误（{@code isError=true}/INVALID_PARAMS）：SDK 返回 {@code CallToolResult}
 *       （{@code isError=true}）或抛 {@code McpError} → 调用方 {@code breaker.recordSuccess()}，
 *       <b>不计入</b>熔断（C-CB-2，T043 真实 GREEN）。</li>
 *   <li>401 精细化（特定异常 + 标记 target 不可用 + 不透传 HTTP，C-AUTH-1）：<b>随认证后端夹具一并落地</b>
 *       ——当前 NONE 认证夹具无 401 路径，TDD 硬约束（零桩）下不得用桩提前实装。</li>
 * </ul>
 *
 * <p>{@link #callTool} 直接转发 SDK {@code McpSyncClient#callTool}——结果（content/isError/_meta）
 * <b>原样</b>返回，不改写（FR-004、契约 C-RESULT-1）。
 */
public final class HttpBackendClient implements BackendClient {

    private final BackendConfig config;
    private final McpSyncClient client;
    private volatile boolean initialized;

    /**
     * 按后端配置构造（独立连接池 + 会话）。
     *
     * @param config 后端声明（url=arthas MCP 根 URL、auth=认证模式、超时）
     */
    public HttpBackendClient(BackendConfig config) {
        this.config = Objects.requireNonNull(config, "config 不可为空");
        McpClientTransport transport = HttpClientStreamableHttpTransport.builder(config.url())
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
                .httpRequestCustomizer(BackendAuthCustomizer.forBackend(config))
                .build();
        this.client = McpClient.sync(transport)
                .clientInfo(new Implementation("arthas-mcp-gateway", "0.1.0"))
                .requestTimeout(Duration.ofMillis(config.callTimeoutMs()))
                .build();
    }

    @Override
    public void initialize() {
        if (!initialized) {
            client.initialize();
            initialized = true;
        }
    }

    @Override
    public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
        return client.callTool(new CallToolRequest(name, arguments));
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    /** 后端配置声明（诊断/可观测/list-targets 用）。 */
    public BackendConfig config() {
        return config;
    }

    @Override
    public void close() {
        client.close();
    }
}
```


---

## com/arthas/gateway/backend/Protocol.java

**文件**：`src/main/java/com/arthas/gateway/backend/Protocol.java`

```java
package com.arthas.gateway.backend;

/**
 * 后端 MCP 协议（data-model.md §2）。
 *
 * <p>取值源自 arthas 后端接入契约：
 * <ul>
 *   <li>{@link #STREAMABLE}：有状态会话（Mcp-Session-Id），支持异步任务；arthas 默认形态。</li>
 *   <li>{@link #STATELESS}：无状态，纯 JSON 一来一回，不支持任务（见 C-STATELESS-1）。</li>
 * </ul>
 * 决定是否可对该后端发起异步任务（STATELESS 后端的 optional 工具不可走 ASYNC_TASK）。
 */
public enum Protocol {
    STREAMABLE,
    STATELESS
}
```


---

## com/arthas/gateway/backend/RegistryComposer.java

**文件**：`src/main/java/com/arthas/gateway/backend/RegistryComposer.java`

```java
package com.arthas.gateway.backend;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 静态∪动态合并器（data-model §5，contracts §2/§3/§4.3）。
 *
 * <p>纯逻辑：把「上一次 effective registry」+「静态 registry（已含 Entry）」+「动态 cfg 列表」合并为新
 * effective registry，unchanged 的 Entry <b>复用旧实例</b>（保连接池/session，I-6），未复用旧 Entry 进
 * 下线列表。version 由内部单调计数器驱动（I-7）；无变更的 compose → {@code changed=false}、registry 维持
 * 原实例（调用方据此跳过 {@code getAndSet}，version 去重，D-VERSION-1）。
 *
 * <p><b>调用方</b>：{@code BackendConfigWatcher} 在静态热重载（reloader 产出新 static 后）与动态变更
 * （{@code DynamicBackendStore} register/unregister）两条路径上调用 {@link #compose}，再
 * {@code holder.getAndSet} 原子替换 + 异步下线未复用 Entry（I-1）。本类不触达 holder/watcher——仅算合并结果。
 *
 * <p><b>I-2 关键</b>：静态热重载传入「移除某静态 target 的新 staticReg」，但 dynamic 列表仍含动态 target →
 * 合并结果保留动态 target（热重载不误删动态）。
 */
public final class RegistryComposer {

    private final BackendEntryFactory factory;
    /** effective registry version 单调计数器（每次变更 compose 递增；无变更不递增）。 */
    private final AtomicLong versionSeq = new AtomicLong(0L);

    public RegistryComposer(BackendEntryFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory 不可为空");
    }

    /**
     * 合并上一次 effective + 静态 registry + 动态 cfg 列表为新 effective。
     *
     * @param previous 上一次 effective registry（reuse Entry 来源）
     * @param staticReg 当前静态 registry（Entry 已由 reloader diff/复用产出；含被热重载增删后的静态 target）
     * @param dynamic 当前动态 cfg 列表（来自 {@code DynamicBackendStore.list()}）
     * @return 合并结果（新 effective + 待下线旧 Entry + 是否变更）
     */
    public ComposeResult compose(BackendRegistry previous, BackendRegistry staticReg,
                                 Collection<BackendConfig> dynamic) {
        Objects.requireNonNull(previous, "previous registry 不可为空");
        Objects.requireNonNull(staticReg, "staticReg 不可为空");
        Objects.requireNonNull(dynamic, "dynamic 不可为空");

        Map<String, BackendEntry> byName = new LinkedHashMap<>();
        Set<BackendEntry> reused = Collections.newSetFromMap(new IdentityHashMap<>());

        // 静态 target：staticReg 的 Entry 优先复用 previous 中同 config 的旧实例（保连接）
        for (Map.Entry<String, BackendEntry> e : staticReg.byName().entrySet()) {
            String name = e.getKey();
            BackendEntry staticEntry = e.getValue();
            BackendEntry prev = previous.byName().get(name);
            if (prev != null && prev.config().equals(staticEntry.config())) {
                byName.put(name, prev); // 复用旧实例
                reused.add(prev);
            } else {
                byName.put(name, staticEntry); // 新静态 Entry（reloader 已创建/复用）
                reused.add(staticEntry);
            }
        }
        // 动态 target：复用 previous 中同 config 旧实例，否则 factory.create
        for (BackendConfig cfg : dynamic) {
            BackendEntry prev = previous.byName().get(cfg.name());
            if (prev != null && prev.config().equals(cfg)) {
                byName.put(cfg.name(), prev); // 复用旧实例（I-6）
                reused.add(prev);
            } else {
                BackendEntry created = factory.create(cfg);
                byName.put(cfg.name(), created);
                reused.add(created);
            }
        }
        // 未复用的旧 Entry → 下线
        List<BackendEntry> toRetire = new ArrayList<>();
        for (BackendEntry e : previous.byName().values()) {
            if (!reused.contains(e)) {
                toRetire.add(e);
            }
        }
        boolean changed = !sameEffective(previous, byName);
        BackendRegistry next = changed
                ? new BackendRegistry(versionSeq.incrementAndGet(), byName)
                : previous;
        return new ComposeResult(next, List.copyOf(toRetire), changed);
    }

    /**
     * effective 是否实际变更：名字集相同且每个 target 复用同一 Entry 实例（identity）→ 未变更。
     * 任一名字增删、或某 target 换了新 Entry 实例 → 变更。
     */
    private static boolean sameEffective(BackendRegistry previous, Map<String, BackendEntry> byName) {
        if (!previous.byName().keySet().equals(byName.keySet())) {
            return false;
        }
        for (Map.Entry<String, BackendEntry> e : byName.entrySet()) {
            if (previous.byName().get(e.getKey()) != e.getValue()) {
                return false; // 新 Entry 实例（非 identity 复用）
            }
        }
        return true;
    }

    /**
     * 合并结果。
     *
     * @param registry 新 effective registry（{@code changed=false} 时为原 previous）
     * @param toRetire 待优雅下线的旧 Entry（{@code changed=false} 时为空）
     * @param changed  是否实际变更（false → 调用方跳过 {@code getAndSet}，version 去重）
     */
    public record ComposeResult(BackendRegistry registry, List<BackendEntry> toRetire, boolean changed) {
        public ComposeResult {
            Objects.requireNonNull(registry, "registry 不可为空");
            toRetire = List.copyOf(toRetire);
        }
    }
}
```


---

## com/arthas/gateway/backend/RegistryHolder.java

**文件**：`src/main/java/com/arthas/gateway/backend/RegistryHolder.java`

```java
package com.arthas.gateway.backend;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 注册表持有者（data-model.md §4）——经 {@link AtomicReference} 持有当前 {@link BackendRegistry}，
 * 提供<b>原子替换</b>。
 *
 * <p><b>热重载整体替换</b>：{@link #getAndSet(BackendRegistry)} 原子地把 registry 换为新快照并返回旧快照，
 * 旧快照供调用方<b>异步优雅下线</b>其 Entry（标 RETIRED、in-flight 调用可完成、关 session/连接池，
 * data-model.md §11 规则 8）。新调用经 {@link #current()} 始终见最新快照。
 *
 * <p>一次 {@code tools/call} 的路由解算：先 {@link #current()} 取<b>固定</b>的 BackendRegistry 引用，
 * 再 {@link BackendRegistry#get(String)} 取固定的 {@link BackendEntry}——registry 在调用中途被替换不影响
 * in-flight 调用（data-model.md §3 不变量）。
 */
public final class RegistryHolder {

    private final AtomicReference<BackendRegistry> ref;

    /** 默认持有空注册表（启动期未加载 backends.yaml 时）。 */
    public RegistryHolder() {
        this(BackendRegistry.empty());
    }

    /** 以指定 registry 初始化（启动期加载完成后注入）。 */
    public RegistryHolder(BackendRegistry initial) {
        this.ref = new AtomicReference<>(Objects.requireNonNull(initial, "registry 不可为空"));
    }

    /** 当前注册表快照。 */
    public BackendRegistry current() {
        return ref.get();
    }

    /**
     * 原子替换注册表。
     *
     * @param next 新注册表（非空）
     * @return 旧注册表（供调用方异步优雅下线其 Entry）
     */
    public BackendRegistry getAndSet(BackendRegistry next) {
        return ref.getAndSet(Objects.requireNonNull(next, "registry 不可为空"));
    }

    /** 便捷：从当前注册表按名取后端（缺失返 {@link Optional#empty()}）。 */
    public Optional<BackendEntry> get(String name) {
        return current().get(name);
    }
}
```


---

## com/arthas/gateway/backend/Source.java

**文件**：`src/main/java/com/arthas/gateway/backend/Source.java`

```java
package com.arthas.gateway.backend;

/**
 * 后端来源标记（data-model.md §3，动态纳管增量）。
 *
 * <p>区分一个 {@link BackendConfig} 是源自 YAML 种子（受热重载增删）还是程序化注册
 * （受 register/unregister）。用于 {@code list-targets} 可观测性区分来源
 * （contracts/dynamic-registration-invariants.md §1）。
 *
 * <ul>
 *   <li>{@link #STATIC} —— 源自 {@code config/backends.yaml} 种子，受 {@code BackendRegistryReloader}
 *       热重载增删；YAML 缺省 {@code source} 视为 STATIC（向后兼容，001 既有零改动）。</li>
 *   <li>{@link #DYNAMIC} —— 源自程序化 {@code DynamicBackendStore.register}（由
 *       {@code ensure-arthas-mcp} 触发），不受 YAML 热重载直接影响（热重载只重读 static）。</li>
 * </ul>
 */
public enum Source {
    /** 源自 {@code config/backends.yaml} 种子，受热重载增删。 */
    STATIC,
    /** 源自程序化注册（{@code ensure-arthas-mcp} 触发），受 register/unregister。 */
    DYNAMIC
}
```


---

## com/arthas/gateway/backend/StatelessAsyncException.java

**文件**：`src/main/java/com/arthas/gateway/backend/StatelessAsyncException.java`

```java
package com.arthas.gateway.backend;

/**
 * 无状态后端不支持异步任务(002 整改 · P1-2 契约修复)。
 *
 * <p>{@code BackendEntry.admit} 检测 {@code config().protocol()==Protocol.STATELESS} 时抛出——
 * 无状态后端无法承载带任务语义、需轮询的异步诊断调用。
 * 由 {@code ToolsCallRouter} 翻译为结构化 {@code McpError}({@code INVALID_PARAMS}，
 * {@code reason=stateless_unsupported_async})。
 *
 * <p><b>仅异步路径</b>校验(同步 {@code execute} 不经此检查，STATELESS 同步调用仍正常)。
 */
public final class StatelessAsyncException extends RuntimeException {

    public StatelessAsyncException() {
        super("无状态(STATELESS)后端不支持异步任务");
    }
}
```


---

## com/arthas/gateway/config/BackendConfigWatcherConfig.java

**文件**：`src/main/java/com/arthas/gateway/config/BackendConfigWatcherConfig.java`

```java
package com.arthas.gateway.config;

import com.arthas.gateway.backend.BackendConfigWatcher;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.backend.RegistryComposer;
import com.arthas.gateway.backend.RegistryHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 热重载监听装配（SC-002，T039/T040）：构造 {@link BackendConfigWatcher} 并启动，监听
 * {@code arthas-gateway.backends-file}（见 {@link GatewayProperties}）所在目录。
 *
 * <p>{@code destroyMethod = "close"}：容器关闭时关 WatchService + 中断监听线程。
 * 依赖 {@link RegistryHolder}（由 {@code BackendRegistryBootstrap} 启动期加载），Spring 保证 holder 先就绪。
 *
 * <p>配置文件无父目录或父目录不存在时，{@link BackendConfigWatcher#start()} 记 WARN 跳过监听
 * （启动期初值仍生效，不阻断上下文）。
 *
 * <p><b>003 动态纳管</b>：watcher 注入 {@link RegistryComposer}（合并静态∪动态）与
 * {@code ObjectProvider<DynamicBackendStore>}（懒解析，规避 watcher↔store 循环依赖，见
 * {@link DynamicRegistrationConfig}）。
 */
@Configuration
public class BackendConfigWatcherConfig {

    private static final Logger log = LoggerFactory.getLogger(BackendConfigWatcherConfig.class);

    @Bean(destroyMethod = "close")
    BackendConfigWatcher backendConfigWatcher(GatewayProperties props, BackendEntryFactory factory, RegistryHolder holder,
                                              RegistryComposer composer,
                                              ObjectProvider<DynamicBackendStore> dynamicStoreProvider) {
        Path configFile = Path.of(props.getBackendsFile());
        // 退役宽限 = backendTimeout(默认 11min,002 整改 P2-1/FR-007):保证 in-flight 异步任务(最长 11min)
        // 在退役 target 的 client 关闭前完成,避免被切断。
        BackendConfigWatcher watcher = new BackendConfigWatcher(configFile, factory, holder,
                composer, dynamicStoreProvider, props.getTask().getBackendTimeout());
        try {
            watcher.start();
        } catch (IOException e) {
            log.warn("热重载监听启动失败（{}），配置变更将不自动重载（需重启）", configFile, e);
        }
        return watcher;
    }
}
```


---

## com/arthas/gateway/config/BackendRegistryBootstrap.java

**文件**：`src/main/java/com/arthas/gateway/config/BackendRegistryBootstrap.java`

```java
package com.arthas.gateway.config;

import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendConfigLoader;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.RegistryHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后端注册表启动期装配（T027 装配）：{@code config/backends.yaml} → {@link BackendRegistry} → {@link RegistryHolder}。
 *
 * <p>补 Wave A 的纯逻辑缺口——{@link BackendConfigLoader}（解析）、{@link BackendEntry}/{@link RegistryHolder}
 * （运行对象/持有者）已就绪，但无 Spring 装配把它们串起来。本类在启动期一次性加载后端映射表，
 * 为每个 {@link BackendConfig} 经 {@link BackendEntryFactory} 构造 {@link BackendEntry}，注入 {@link RegistryHolder}
 * 供 {@link com.arthas.gateway.handler.ToolsCallRouter} 路由解算。
 *
 * <p><b>资源解析顺序</b>：先文件系统（相对工作目录，支持运行期外置配置/jar 部署），后 classpath
 * （IDE/测试资源）。两者皆无 → 空注册表（所有 target 不在册，S-ERR-2），不阻断启动。
 *
 * <p><b>热重载不在本类</b>：{@code WatchService} 增删后端（SC-002）属 US2（T037/T040），届时经
 * {@link RegistryHolder#getAndSet} 原子替换。本类仅负责启动期初值。
 *
 * <p><b>构造不连</b>：{@link BackendEntryFactory} 构造 {@code HttpBackendClient} 不发起连接，首次路由转发时
 * 才 {@code initialize()} 握手——故 Phase 2 测试（{@code InitializeAndToolsListContractTest}，不触 tools/call）
 * 不受后端可达性影响。
 */
@Configuration
public class BackendRegistryBootstrap {

    private static final Logger log = LoggerFactory.getLogger(BackendRegistryBootstrap.class);

    /**
     * 启动期加载后端映射表，构造 {@link RegistryHolder}。
     *
     * @param props   网关配置（提供 backends-file 路径）
     * @param factory 后端运行对象工厂
     */
    @Bean
    RegistryHolder registryHolder(GatewayProperties props, BackendEntryFactory factory) {
        return new RegistryHolder(load(props.getBackendsFile(), factory));
    }

    private BackendRegistry load(String file, BackendEntryFactory factory) {
        try (InputStream in = openResource(file)) {
            if (in == null) {
                log.warn("后端映射表未找到 {}：以空注册表启动（所有 target 将不在册，S-ERR-2）", file);
                return BackendRegistry.empty();
            }
            BackendConfigLoader.LoadedBackends loaded = new BackendConfigLoader().load(in);
            Map<String, BackendEntry> byName = new LinkedHashMap<>();
            for (BackendConfig cfg : loaded.backends()) {
                byName.put(cfg.name(), factory.create(cfg));
            }
            BackendRegistry registry = new BackendRegistry(loaded.version(), byName);
            log.info("已加载后端注册表 version={}，{} 个后端：{}", loaded.version(), byName.size(), byName.keySet());
            return registry;
        } catch (IOException e) {
            throw new IllegalStateException("加载后端映射表失败：" + file, e);
        }
    }

    /** 文件系统优先（外置配置），次 classpath（IDE/测试资源）。 */
    private InputStream openResource(String file) throws IOException {
        File f = new File(file);
        if (f.isFile()) {
            return new FileInputStream(f);
        }
        return getClass().getClassLoader().getResourceAsStream(file);
    }
}
```


---

## com/arthas/gateway/config/DynamicRegistrationConfig.java

**文件**：`src/main/java/com/arthas/gateway/config/DynamicRegistrationConfig.java`

```java
package com.arthas.gateway.config;

import com.arthas.gateway.backend.BackendConfigWatcher;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.backend.RegistryComposer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 动态纳管装配（003 增量，data-model §4/§5）：注册 {@link RegistryComposer}（静态∪动态合并，纯逻辑）与
 * {@link DynamicBackendStore}（动态 target 内存态）bean。
 *
 * <p><b>循环依赖规避</b>：watcher 读 {@code store.list()} 合并 dynamic，store 的 {@code onChange} 通知
 * watcher 重算 → 互相依赖。解法：watcher 经 {@code ObjectProvider<DynamicBackendStore>} 懒解析 store
 * （构造期不需 store）；store bean 以 watcher 的方法引用（{@code staticSnapshotNames}/
 * {@code recomposeForDynamicChange}）为 staticNames 供应商与 onChange 回调。装配顺序：watcher 先建
 * （store 未需）→ store 后建（watcher 已就绪，绑方法引用）。
 */
@Configuration
public class DynamicRegistrationConfig {

    /** 静态∪动态合并器（纯逻辑；effective registry version 单调计数器内嵌）。 */
    @Bean
    RegistryComposer registryComposer(BackendEntryFactory factory) {
        return new RegistryComposer(factory);
    }

    /**
     * 动态 target store：
     * <ul>
     *   <li>{@code staticNames} = {@code watcher.staticSnapshotNames()}（I-3 命名冲突检测的静态种子名来源）。</li>
     *   <li>{@code onChange} = {@code watcher.recomposeForDynamicChange()}（动态变更触发 effective 重算）。</li>
     * </ul>
     */
    @Bean
    DynamicBackendStore dynamicBackendStore(BackendConfigWatcher watcher) {
        return new DynamicBackendStore(watcher::staticSnapshotNames, watcher::recomposeForDynamicChange);
    }
}
```


---

## com/arthas/gateway/config/GatewayMcpServerConfig.java

**文件**：`src/main/java/com/arthas/gateway/config/GatewayMcpServerConfig.java`

```java
package com.arthas.gateway.config;

import com.arthas.gateway.handler.McpErrorCodes;
import com.arthas.gateway.handler.ToolsCallRouter;
import com.arthas.gateway.orchestration.K8sToolHandlers;
import com.arthas.gateway.orchestration.K8sToolRegistry;
import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.StaticToolRegistry;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpError;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * 网关 MCP 服务端装配（SDK 2.0.0 / Spring AI 2.0.0 原生路径，见 memory sdk2-vs-spec-divergences）。
 *
 * <p>三件事：
 * <ol>
 *   <li>{@link StaticToolRegistry}——从 {@code arthas-tools.json} 构建不可变 35 工具快照（T013）。</li>
 *   <li>{@code List<SyncToolSpecification>}——35 工具注册为 SDK 工具规格，每个 handler 委托
 *       {@link ToolsCallRouter}（Phase 3 实现真实路由，当前占位返回结果，证明装配通路）。</li>
 *   <li>{@link McpSyncServerCustomizer}——锁定 capabilities 为<b>仅 tools（listChanged=false）</b>，
 *       不声明 prompts/resources/logging/completions（arthas 恒空，契约 S-INIT-2 / server-contract §3）。</li>
 * </ol>
 *
 * <p><b>与 spec 的偏差</b>（已记录 memory sdk2-vs-spec-divergences）：spec 计划手写
 * {@code InitializeHandler}/{@code ToolsListHandler}/{@code transport/} 装配类；SDK 原生路径下
 * initialize 协商、tools/list 返回、Streamable HTTP transport 均由 Spring AI starter + SDK 内置，
 * 本类仅提供工具规格与 capabilities 锁定——故 T015/T016/T017 不再产出独立 handler/transport 类。
 * 行为等价性由 {@code InitializeAndToolsListContractTest}（S-INIT/S-TL）契约测试守护。
 */
@Configuration
public class GatewayMcpServerConfig {

    /** 工具 schema 资源路径（单一事实源，T012）。 */
    private static final String TOOLS_RESOURCE = "arthas-tools.json";

    /**
     * 静态工具注册表：加载 31 个 arthas 工具 schema（注入 target）+ 4 个网关自有工具 = 35。
     *
     * <p>启动期一次性构建不可变快照，作为 {@code tools/list} 的单一填充源（宪法原则二：透明无损聚合）。
     */
    @Bean
    StaticToolRegistry staticToolRegistry() {
        return StaticToolRegistry.fromClasspath(TOOLS_RESOURCE);
    }

    /**
     * 38 个 MCP 工具规格 = 35 arthas/网关工具 + 3 K8S 编排工具（003 特性，契约 §1/§2/§3，R6）。
     *
     * <ul>
     *   <li>35 个静态工具：每个 {@link ExposedTool} 转 {@link Tool}（name/description/inputSchema 逐字），
     *       handler 委托 {@link ToolsCallRouter#route}（经 gateway-core 路由，传输无关）。</li>
     *   <li>3 个编排工具（{@link K8sToolRegistry#tools()}）：规格始终注册（{@code tools/list}=38 与 kubeconfig
     *       <b>无关</b>，回归守护 T029），handler 自带闭包、<b>不经 {@code ToolsCallRouter}</b>——直接调
     *       {@link K8sToolHandlers#handle}（gateway-core 路由零 K8S 感知，R6）。</li>
     * </ul>
     *
     * <p><b>编排 handler 条件装配</b>：{@link K8sToolHandlers} bean 仅在 kubeconfig 存在时装配（见
     * {@code K8sOrchestrationConfig} + {@code K8sEnabledCondition}）。此处经 {@link ObjectProvider} 懒解析：
     * bean 缺失（CI 无 k3s）→ 调编排工具返「K8S 编排未启用」明确错误（INVALID_PARAMS），而非启动期崩。
     *
     * <p>Spring AI starter 自动收集此 bean 注册到 MCP server，驱动 {@code tools/list} 返回。
     */
    @Bean
    List<SyncToolSpecification> mcpToolSpecifications(StaticToolRegistry registry, ToolsCallRouter router,
                                                      ObjectProvider<K8sToolHandlers> k8sHandlersProvider) {
        // 35 个静态工具 → 经 ToolsCallRouter 路由
        List<SyncToolSpecification> specs = new ArrayList<>(registry.tools().size() + K8sToolRegistry.tools().size());
        for (ExposedTool exposed : registry.tools()) {
            Tool tool = McpSchema.Tool.builder()
                    .name(exposed.name())
                    .description(exposed.description())
                    .inputSchema(exposed.inputSchema())
                    .build();
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler =
                    (exchange, request) -> router.route(exposed, request);
            specs.add(new SyncToolSpecification(tool, handler));
        }
        // 3 个编排工具 → handler 自带闭包、不经 ToolsCallRouter（ObjectProvider 懒解析 K8sToolHandlers）
        for (ExposedTool exposed : K8sToolRegistry.tools()) {
            Tool tool = McpSchema.Tool.builder()
                    .name(exposed.name())
                    .description(exposed.description())
                    .inputSchema(exposed.inputSchema())
                    .build();
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler =
                    (exchange, request) -> {
                        K8sToolHandlers handlers = k8sHandlersProvider.getIfAvailable();
                        if (handlers == null) {
                            // kubeconfig 缺失（CI 无 k3s）→ 编排 bean 未装配，返明确错误
                            throw McpError.builder(McpErrorCodes.INVALID_PARAMS)
                                    .message("K8S 编排未启用：未配置可读的 arthas-gateway.k8s.kubeconfig"
                                            + "（工具 " + exposed.name() + " 需真实集群）")
                                    .build();
                        }
                        return handlers.handle(exposed, request);
                    };
            specs.add(new SyncToolSpecification(tool, handler));
        }
        return specs;
    }

    /**
     * 锁定服务端 capabilities 为<b>仅 tools（listChanged=false）</b>，不声明 prompts/resources/logging/completions。
     *
     * <p>Spring AI starter 默认广播 prompts/resources/logging/completions（其 {@code Capabilities} 布尔默认
     * true，且 {@code tools.listChanged} 硬编码 true）——与本网关"仅暴露静态工具集"的契约（S-INIT-2 / §3）不符。
     * 故用官方 {@link McpSyncServerCustomizer} 扩展点精确覆盖。
     *
     * <p><b>覆盖时机</b>：starter 的 {@code mcpSyncServer} 先 {@code spec.capabilities(...)}（offset 557），
     * 再 {@code customizer.ifPresent(...)}（offset 590），最后 {@code spec.build()}——customizer 在后，可覆盖。
     *
     * <p><b>{@code @Primary} 的必要性</b>：starter 自带 {@code servletMcpSyncServerCustomizer}（非
     * {@code @ConditionalOnMissingBean}），与本 bean 并存成两个候选——若不标 {@code @Primary}，
     * {@code Optional<McpSyncServerCustomizer>} 因多候选解析为空，<b>两个都不执行</b>（含 starter 的）。
     * 标 {@code @Primary} 后本 bean 赢得注入；此处一并保留 starter servlet customizer 唯一职责
     * {@code immediateExecution(true)}（WebMVC 下工具 handler 在调用线程即时执行），避免覆盖导致行为退化。
     */
    @Bean
    @Primary
    McpSyncServerCustomizer gatewayCapabilitiesCustomizer() {
        return spec -> {
            // 保留 starter servlet customizer 的职责：WebMVC 下 handler 即时执行
            spec.immediateExecution(true);
            // 锁定 capabilities：仅 tools（listChanged=false，工具集静态），其余不声明
            spec.capabilities(
                    McpSchema.ServerCapabilities.builder()
                            .tools(false)   // ToolCapabilities[listChanged=false]
                            .build());
        };
    }
}
```


---

## com/arthas/gateway/config/GatewayProperties.java

**文件**：`src/main/java/com/arthas/gateway/config/GatewayProperties.java`

```java
package com.arthas.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 网关顶层配置（前缀 {@code arthas-gateway.*}）。绑定后端映射表文件路径与异步任务默认值。
 *
 * <p><b>后端列表本身不在此绑定</b>：{@code config/backends.yaml} 的内容由 {@code BackendConfigLoader}
 * 直接解析并通过 {@code WatchService} 热重载（免重启，SC-002）。此处仅持有"文件位置"，
 * 以免 Spring {@code @ConfigurationProperties}（启动期一次性加载）与热重载语义冲突。
 *
 * <p><b>传输配置不在本类</b>（SDK 2.0.0 / Spring AI 2.0.0 偏离 spec 的处理，见 memory
 * sdk2-vs-spec-divergences）：MCP 传输由 Spring AI starter 自动装配，经标准属性控制——
 * HTTP 端口/地址走 {@code server.port}/{@code server.address}，MCP 端点走
 * {@code spring.ai.mcp.server.streamable-http.mcp-endpoint}，能力协商走 {@code spring.ai.mcp.server.*}。
 * MVP 仅 Streamable HTTP（stdio 与 HTTP 互斥，stdio 延后）。
 *
 * <p>实体定义见 {@code data-model.md}，配置版本与校验规则见 {@code data-model.md §2/§11}。
 */
@ConfigurationProperties(prefix = "arthas-gateway")
public class GatewayProperties {

    /** 后端映射表文件路径（热重载源）。 */
    private String backendsFile = "config/backends.yaml";

    /** 应用层异步任务（方案 C）默认值。 */
    private Task task = new Task();

    /** K8S 编排子段（003 特性：k8s.list-* / k8s.ensure-arthas-mcp 工具的集群连接与供给参数）。 */
    private K8s k8s = new K8s();

    /** portal 管理面能力开关子段（004 特性，{@code arthas-gateway.admin.*}）。 */
    private Admin admin = new Admin();

    public String getBackendsFile() {
        return backendsFile;
    }

    public void setBackendsFile(String backendsFile) {
        this.backendsFile = backendsFile;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public K8s getK8s() {
        return k8s;
    }

    public void setK8s(K8s k8s) {
        this.k8s = k8s;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    /** 异步任务默认值（方案 C，详见 research.md §4）。 */
    public static class Task {
        /** 后台阻塞等后端路①的兜底超时（> 后端 10min 上限，超此标 failed）。 */
        private Duration backendTimeout = Duration.ofMinutes(11);
        /** 已完成任务可查询保留时长（TTL 清理）。 */
        private Duration resultTtl = Duration.ofHours(1);
        /**
         * 全局在途异步任务上限(跨 target 累计,P2-4/FR-010)。
         * <p>{@code null}/未设 → 动态默认 = 注册表后端数 × 5(每次 submit 按当前注册表 size 计算,
         * 后端增减随之伸缩)。显式设正值 → 固定上限(便于压测/限流调优)。
         */
        private Integer globalMaxInflight;

        public Duration getBackendTimeout() {
            return backendTimeout;
        }

        public void setBackendTimeout(Duration backendTimeout) {
            this.backendTimeout = backendTimeout;
        }

        public Duration getResultTtl() {
            return resultTtl;
        }

        public void setResultTtl(Duration resultTtl) {
            this.resultTtl = resultTtl;
        }

        public Integer getGlobalMaxInflight() {
            return globalMaxInflight;
        }

        public void setGlobalMaxInflight(Integer globalMaxInflight) {
            this.globalMaxInflight = globalMaxInflight;
        }
    }

    /**
     * K8S 编排子段（003 特性，{@code arthas-gateway.k8s.*}）。
     *
     * <p>承载 {@code k8s.list-*} / {@code k8s.ensure-arthas-mcp} 工具连接测试集群（debian 上 k3s）
     * 与供给（arthas 注入 + NodePort 暴露）所需的参数。kubeconfig 指向 root-on-node 派生的 admin 凭证
     * （见 [K8S 测试环境设计](../../docs/superpowers/specs/2026-06-23-k8s-test-env-setup-design.md)）。
     * 决策见 [research.md R2/R3](../specs/003-k8s-arthas-mcp-launch/research.md)。
     */
    public static class K8s {

        /** kubeconfig 文件路径（集群外运行网关时远程连 k3s API）。缺省指向测试床导出凭证。 */
        private String kubeconfig = "test-env/k8s/kubeconfig/k3s-admin.yaml";

        /** kubeconfig 内使用的 context（null/缺省取 kubeconfig current-context）。 */
        private String context;

        /** 默认 namespace（list-* / ensure 缺省 namespace 时）。 */
        private String namespace = "default";

        /** NodePort 分配范围（K8S 默认 30000–32767）。ensure 建 NodePort Service 时由集群在此范围自动分配。 */
        private String nodePortRange = "30000-32767";

        /** ensure 全流程超时（注入 + 暴露 + 健康检查 + 注册）。须 > arthas attach + 健康轮询时间。 */
        private Duration ensureTimeout = Duration.ofMinutes(5);

        /** ensure 启动 arthas MCP 的绑定 IP（research.md R4：0.0.0.0 产出 wildcard、NodePort 可达；127.0.0.1 不可达）。 */
        private String targetIp = "0.0.0.0";

        /** arthas-boot.jar 静态工具文件路径（memory arthas-no-dependency：不入 pom、ensure 时上传进 pod）。 */
        private String arthasBootJar = "tools/arthas-boot.jar";

        /** ensure 注入的 arthas MCP 在 pod 内监听端口（NodePort targetPort）。 */
        private int mcpPort = 8563;

        /** 锁定的 arthas 版本（设计 §5：--use-version 4.3.0）。 */
        private String arthasVersion = "4.3.0";

        /**
         * arthas MCP HTTP 服务的访问密码（003 实测发现：arthas 绑 0.0.0.0 暴露外部时强制鉴权，
         * 不配则自动生成随机密码且外部访问 401）。ensure 经 {@code --password} 下发已知值，
         * 并以 Bearer 令牌形态注入动态后端 {@code Auth}，使网关与健康检查均可鉴权访问。缺省为测试床占位值，
         * 生产应显式覆盖（{@code arthas-gateway.k8s.arthas-password}）。
         */
        private String arthasPassword = "arthas-mcp-gateway";

        public String getKubeconfig() {
            return kubeconfig;
        }

        public void setKubeconfig(String kubeconfig) {
            this.kubeconfig = kubeconfig;
        }

        public String getContext() {
            return context;
        }

        public void setContext(String context) {
            this.context = context;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getNodePortRange() {
            return nodePortRange;
        }

        public void setNodePortRange(String nodePortRange) {
            this.nodePortRange = nodePortRange;
        }

        public Duration getEnsureTimeout() {
            return ensureTimeout;
        }

        public void setEnsureTimeout(Duration ensureTimeout) {
            this.ensureTimeout = ensureTimeout;
        }

        public String getTargetIp() {
            return targetIp;
        }

        public void setTargetIp(String targetIp) {
            this.targetIp = targetIp;
        }

        public String getArthasBootJar() {
            return arthasBootJar;
        }

        public void setArthasBootJar(String arthasBootJar) {
            this.arthasBootJar = arthasBootJar;
        }

        public int getMcpPort() {
            return mcpPort;
        }

        public void setMcpPort(int mcpPort) {
            this.mcpPort = mcpPort;
        }

        public String getArthasVersion() {
            return arthasVersion;
        }

        public void setArthasVersion(String arthasVersion) {
            this.arthasVersion = arthasVersion;
        }

        public String getArthasPassword() {
            return arthasPassword;
        }

        public void setArthasPassword(String arthasPassword) {
            this.arthasPassword = arthasPassword;
        }
    }

    /**
     * portal 管理面能力开关子段（004 特性，{@code arthas-gateway.admin.*}）。
     *
     * <p>承载后端 CRUD 与任务导出能力的按需启用开关（research.md R9 / spec FR-014）。
     * 各能力经 {@code @ConditionalOnProperty} 独立装配，关闭则对应端点 404、前端降级提示。
     */
    public static class Admin {

        /** 后端配置 CRUD 能力（{@code arthas-gateway.admin.crud.enabled}，默认开）。 */
        private Crud crud = new Crud();

        /** 异步任务结果导出能力（{@code arthas-gateway.admin.export.enabled}，默认开）。 */
        private Export export = new Export();

        public Crud getCrud() {
            return crud;
        }

        public void setCrud(Crud crud) {
            this.crud = crud;
        }

        public Export getExport() {
            return export;
        }

        public void setExport(Export export) {
            this.export = export;
        }

        /** 后端配置 CRUD 开关（/admin/backends）。 */
        public static class Crud {
            private boolean enabled = true;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }

        /** 任务结果导出开关（/admin/tasks/{id}/export）。 */
        public static class Export {
            private boolean enabled = true;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }
    }
}
```


---

## com/arthas/gateway/config/K8sOrchestrationConfig.java

**文件**：`src/main/java/com/arthas/gateway/config/K8sOrchestrationConfig.java`

```java
package com.arthas.gateway.config;

import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.orchestration.ArthasProvisioner;
import com.arthas.gateway.orchestration.K8sClientFactory;
import com.arthas.gateway.orchestration.K8sEnabledCondition;
import com.arthas.gateway.orchestration.K8sPodExplorer;
import com.arthas.gateway.orchestration.K8sToolHandlers;
import com.arthas.gateway.orchestration.NodePortExposer;
import com.arthas.gateway.orchestration.OrchestrationRecordStore;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * K8S 编排 bean 装配（003 特性，research.md R6）。
 *
 * <p>本 {@code @Configuration} 整体由 {@link K8sEnabledCondition} 把关：仅当 kubeconfig 存在且可读时，
 * 下列编排 bean 才装配（{@link K8sClientFactory} → {@link KubernetesClient} → PodExplorer/Exposer/
 * Provisioner/Handlers/RecordStore）。CI 无 k3s → 本类整体跳过 → 网关以 35 工具形态运行；本地有 k3s → 全量生效。
 *
 * <p><b>3 个编排工具的<b>规格</b>不在此</b>：{@code K8sToolRegistry.tools()} 为静态方法，由
 * {@link GatewayMcpServerConfig}（组合根）合并进 {@code tools/list}（恒 38）；仅 <b>handler</b>（{@link K8sToolHandlers}）
 * 经此条件装配、由 {@code ObjectProvider} 懒解析——故 {@code tools/list}=38 与 kubeconfig 是否存在<b>无关</b>（回归守护 T029）。
 *
 * <p><b>config→orchestration 依赖</b>：本类（组合根）允许依赖 orchestration 包；ArchUnit 边界 T030 仅禁止
 * <b>诊断核心</b>（backend/handler/tool/task/auth/obs）→ orchestration，config 不在禁止范围。
 */
@Configuration
@Conditional(K8sEnabledCondition.class)
public class K8sOrchestrationConfig {

    /** kubeconfig → fabric8 client 工厂（{@link AutoCloseable}：容器关闭释放连接池）。 */
    @Bean(destroyMethod = "close")
    K8sClientFactory k8sClientFactory(GatewayProperties props) {
        return new K8sClientFactory(props);
    }

    /** 单例 fabric8 客户端（list/exec/create 共用，线程安全）。 */
    @Bean
    KubernetesClient kubernetesClient(K8sClientFactory factory) {
        return factory.client();
    }

    /** pod/service 枚举器（list-* 工具后端）。 */
    @Bean
    K8sPodExplorer k8sPodExplorer(KubernetesClient client) {
        return new K8sPodExplorer(client);
    }

    /** NodePort 暴露器（ensure 子步）。 */
    @Bean
    NodePortExposer nodePortExposer(KubernetesClient client) {
        return new NodePortExposer(client);
    }

    /** 供给记录内存态（data-model §8，近实时可观测）。 */
    @Bean
    OrchestrationRecordStore orchestrationRecordStore() {
        return new OrchestrationRecordStore();
    }

    /** arthas 供给器（ensure 核心）。供给参数取自 {@link GatewayProperties.K8s}。 */
    @Bean
    ArthasProvisioner arthasProvisioner(KubernetesClient client, NodePortExposer exposer,
                                        DynamicBackendStore dynamicStore, OrchestrationRecordStore recordStore,
                                        GatewayProperties props) {
        GatewayProperties.K8s k = props.getK8s();
        return new ArthasProvisioner(client, exposer, dynamicStore, recordStore,
                k.getTargetIp(), k.getArthasBootJar(), k.getMcpPort(), k.getArthasVersion(),
                k.getArthasPassword());
    }

    /** 3 个编排工具的本地处理器（handler 自带闭包，不经 ToolsCallRouter）。 */
    @Bean
    K8sToolHandlers k8sToolHandlers(K8sPodExplorer explorer, ArthasProvisioner provisioner, GatewayProperties props) {
        return new K8sToolHandlers(explorer, provisioner, props);
    }
}
```


---

## com/arthas/gateway/config/TaskInfrastructureConfig.java

**文件**：`src/main/java/com/arthas/gateway/config/TaskInfrastructureConfig.java`

```java
package com.arthas.gateway.config;

import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.task.AsyncTaskExecutor;
import com.arthas.gateway.task.TaskStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

/**
 * 异步任务基础设施装配（T033/T035 + 002 整改 P2-4）：{@link TaskStore} + {@link AsyncTaskExecutor}。
 *
 * <p>两者均为有状态、生命周期对象（持后台线程池），从 {@link GatewayProperties.Task} 取默认值：
 * <ul>
 *   <li>{@code resultTtl}（默认 1h）：终态任务可查询保留时长，过期惰性 + 主动清理。</li>
 *   <li>{@code backendTimeout}（默认 11min）：后台阻塞等后端路①的兜底超时，&gt; 后端 10min 上限。</li>
 *   <li>{@code globalMaxInflight}（默认 null）：跨 target 累计在途异步上限；未设→动态默认=后端数×5
 *       （每次 submit 按当前注册表 size 求值，后端增减随之伸缩，P2-4/FR-010）。</li>
 * </ul>
 *
 * <p><b>销毁</b>：两者均有 {@code close()}（关线程池），Spring @Bean 默认推断 destroy-method=infer
 * 会自动调用，容器关闭时优雅回收后台虚拟线程。
 */
@Configuration
public class TaskInfrastructureConfig {

    @Bean
    TaskStore taskStore(GatewayProperties props) {
        return new TaskStore(props.getTask().getResultTtl(), Instant::now);
    }

    @Bean
    AsyncTaskExecutor asyncTaskExecutor(TaskStore store, GatewayProperties props, RegistryHolder holder) {
        return new AsyncTaskExecutor(store, props.getTask().getBackendTimeout(),
                // 全局背压上限供应器：配置优先，未设→动态默认 = 当前注册表后端数 × 5（≥1，防空表时 cap=0 全拒）
                () -> {
                    Integer configured = props.getTask().getGlobalMaxInflight();
                    if (configured != null) {
                        return configured;
                    }
                    return Math.max(1, holder.current().size() * 5);
                });
    }
}
```


---

## com/arthas/gateway/GatewayApplication.java

**文件**：`src/main/java/com/arthas/gateway/GatewayApplication.java`

```java
package com.arthas.gateway;

import com.arthas.gateway.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * arthas MCP 网关入口。
 *
 * <p>作为标准 MCP 服务端（stdio + Streamable HTTP）向 Claude Code 等客户端暴露统一的 arthas 诊断能力，
 * 同时作为 MCP 客户端连接并管理多个目标 JVM 的 arthas MCP 后端。详见 specs/001-arthas-mcp-gateway/。
 *
 * <p>传输装配（transport 包）与协议核心（handler 包）在 Phase 2+ 接入；本类仅承载 Spring Boot 生命周期。
 */
@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```


---

## com/arthas/gateway/handler/DiagnosticRequest.java

**文件**：`src/main/java/com/arthas/gateway/handler/DiagnosticRequest.java`

```java
package com.arthas.gateway.handler;

import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一次诊断调用的解析结果（tools/call 入参解析，server-contract.md §5.1 步骤 1-2，T026）。
 *
 * <p>纯逻辑值对象，承载从 {@link CallToolRequest} + {@link ExposedTool} 解算出的路由依据：
 * <ul>
 *   <li>{@code target}——从 {@code arguments} 取出并<b>剥离</b>的目标 JVM 逻辑名（剥离后永不进后端参数，S-CALL-2）。</li>
 *   <li>{@code backendArgs}——剥离 target 后的剩余参数，<b>原样</b>转发后端（不改写 arthas 参数名）。</li>
 *   <li>{@code routingMode}——取自 {@link ExposedTool#routingMode()}，决定 {@link ToolsCallRouter} 分流。</li>
 * </ul>
 *
 * <p><b>校验边界</b>：本类仅校验 target 的<b>格式</b>（缺失/空/非字符串 → INVALID_PARAMS(-32602)，S-ERR-1）。
 * 「target 不在册 → INVALID_PARAMS + data.available」（S-ERR-2）依赖注册表，属 {@link ToolsCallRouter} 职责。
 *
 * <p>「toolName 命中」（S-ERR-3）由 SDK 保证——只有 {@code tools/list} 注册的工具才会路由到 handler，
 * 未注册工具名 SDK 自动返 INVALID_PARAMS，不到本类。
 *
 * <p>构造时对 {@code backendArgs} 做防御性不可变拷贝({@code LinkedHashMap} + {@link Collections#unmodifiableMap},
 * 容忍 null value、保留稳定序),外部篡改不泄漏。
 */
public record DiagnosticRequest(
        String target,
        Map<String, Object> backendArgs,
        RoutingMode routingMode) {

    public DiagnosticRequest {
        Objects.requireNonNull(target, "target 不可为空");
        Objects.requireNonNull(backendArgs, "backendArgs 不可为空");
        Objects.requireNonNull(routingMode, "routingMode 不可为空");
        // 防御拷贝:LinkedHashMap 容忍 null value(MCP SDK 反序列化可选参数时可能显式传 null,如
        // {"target":"x","timeout":null})——{@code Map.copyOf} 对 null value 抛 NPE 会误伤合法调用(002 整改 P2-2/FR-008);
        // 包装为不可变 + 保留插入稳定序(与 stripTarget 的 LinkedHashMap 一致)。
        backendArgs = Collections.unmodifiableMap(new LinkedHashMap<>(backendArgs));
    }

    /**
     * 从工具与调用请求解析诊断请求。
     *
     * @param tool    注册表中的工具（提供 routingMode）
     * @param request SDK 反序列化的调用请求（arguments 含 target）
     * @return 解析结果（target 已剥离、backendArgs 不可变）
     * @throws McpError target 缺失/空/非字符串时抛 INVALID_PARAMS(-32602)
     */
    public static DiagnosticRequest parse(ExposedTool tool, CallToolRequest request) {
        Objects.requireNonNull(tool, "tool 不可为空");
        Objects.requireNonNull(request, "request 不可为空");
        Map<String, Object> arguments = request.arguments();
        Object rawTarget = arguments == null ? null : arguments.get("target");
        String target = requireTarget(rawTarget);
        Map<String, Object> backendArgs = stripTarget(arguments);
        return new DiagnosticRequest(target, backendArgs, tool.routingMode());
    }

    /** target 格式校验：缺失/非字符串/空白 → INVALID_PARAMS。 */
    private static String requireTarget(Object raw) {
        if (raw == null) {
            throw invalidParams("tools/call 缺少 target 参数：目标 JVM 逻辑名（见 list-targets）");
        }
        if (!(raw instanceof String s)) {
            throw invalidParams("tools/call 的 target 须为字符串（目标 JVM 逻辑名），实得类型："
                    + raw.getClass().getSimpleName());
        }
        if (s.isBlank()) {
            throw invalidParams("tools/call 的 target 不可为空（目标 JVM 逻辑名）");
        }
        return s.trim();
    }

    /** 剥离 target 的 backendArgs（原样保留其余参数；arguments 为 null/空时返空 Map）。 */
    private static Map<String, Object> stripTarget(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>(arguments);
        copy.remove("target");
        return copy;
    }

    /** 构造 INVALID_PARAMS McpError（协议层错误，SDK 转 JSON-RPC error response）。 */
    private static McpError invalidParams(String message) {
        return McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message(message)
                .build();
    }
}
```


---

## com/arthas/gateway/handler/GatewayToolHandlers.java

**文件**：`src/main/java/com/arthas/gateway/handler/GatewayToolHandlers.java`

```java
package com.arthas.gateway.handler;

import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.task.AsyncTaskExecutor;
import com.arthas.gateway.task.GatewayTask;
import com.arthas.gateway.task.TaskState;
import com.arthas.gateway.tool.ExposedTool;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 网关自有工具的本地处理器（gateway-tools-contract.md，T035）。
 *
 * <p>4 个 {@code arthas-gateway.*} 工具（routingMode=GATEWAY_LOCAL，不转发后端）：
 * <ul>
 *   <li>{@code list-targets}：返回当前注册表（name/state/healthy/protocol + version，G-LT-1）。</li>
 *   <li>{@code task-get}：按 status 返回任务快照（working/completed+result/failed+error/cancelled，G-TG-1/2）；
 *       未知 taskId → INVALID_PARAMS（G-TG-3）。</li>
 *   <li>{@code task-list}：全量或按 status 过滤的任务概要（G-TL-1）。</li>
 *   <li>{@code task-cancel}：取消 WORKING 任务（→ cancelled）；终态幂等返当前状态（G-TC-1/2）。</li>
 * </ul>
 *
 * <p>这些工具对 Claude Code 是普通 MCP 工具——"task"仅网关内存态（{@link AsyncTaskExecutor#store()}），
 * 无任何手写 task 协议帧（宪法「不手写帧」硬约束）。响应体均为 {@code TextContent(JSON)}。
 *
 * <p><b>completed.result 渲染</b>：后端 {@link CallToolResult} 的 content（sealed 多态接口）渲染为
 * {@code [{type, text}]} 列表 + {@code isError}（G-TG-2：isError 原样保留），避免多态 Jackson 序列化配置。
 *
 * <p><b>healthy</b>（Phase 5 细化）：{@code state==ACTIVE && breaker 未 OPEN}。熔断 OPEN / RETIRED → false。
 */
@Component
public class GatewayToolHandlers {

    private final RegistryHolder registry;
    private final AsyncTaskExecutor executor;

    public GatewayToolHandlers(RegistryHolder registry, AsyncTaskExecutor executor) {
        this.registry = Objects.requireNonNull(registry, "registry 不可为空");
        this.executor = Objects.requireNonNull(executor, "executor 不可为空");
    }

    /** 分派到具体处理方法（按工具名）。 */
    public CallToolResult handle(ExposedTool tool, CallToolRequest request) {
        return switch (tool.name()) {
            case "arthas-gateway.list-targets" -> listTargets();
            case "arthas-gateway.task-get" -> taskGet(request);
            case "arthas-gateway.task-list" -> taskList(request);
            case "arthas-gateway.task-cancel" -> taskCancel(request);
            default -> throw new IllegalStateException("未知网关自有工具：" + tool.name());
        };
    }

    // ===== list-targets（G-LT-1） =====

    private CallToolResult listTargets() {
        BackendRegistry reg = registry.current();
        List<Map<String, Object>> targets = new ArrayList<>();
        for (BackendEntry e : reg.byName().values()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", e.config().name());
            t.put("state", e.state().name());
            t.put("healthy", e.isHealthy()); // 健康单一事实源(T027/FR-012),委托 BackendEntry.isHealthy()
            t.put("protocol", e.config().protocol().name());
            targets.add(t);
        }
        return McpJson.json(Map.of("targets", targets, "version", reg.version()));
    }

    // ===== task-get（G-TG-1/2/3） =====

    private CallToolResult taskGet(CallToolRequest request) {
        String taskId = requireTaskId(request);
        GatewayTask task = executor.store().get(taskId)
                .orElseThrow(() -> McpError.builder(McpErrorCodes.INVALID_PARAMS)
                        .message("taskId 不存在：" + taskId + "（见 task-list）")
                        .build());
        return McpJson.json(taskGetView(task));
    }

    private static Map<String, Object> taskGetView(GatewayTask task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", task.taskId());
        m.put("status", task.status().name().toLowerCase());
        switch (task.status()) {
            case WORKING -> {
                m.put("toolName", task.toolName());
                m.put("target", task.target());
                m.put("createdAt", task.createdAt().toString());
            }
            case COMPLETED -> {
                m.put("toolName", task.toolName());
                m.put("target", task.target());
                m.put("completedAt", task.completedAt().toString());
                m.put("result", renderResult(task.result()));
            }
            case FAILED -> m.put("error", Map.of(
                    "reason", task.error().reason(),
                    "message", task.error().message()));
            case CANCELLED -> { /* 仅 taskId + status */ }
        }
        return m;
    }

    // ===== task-list（G-TL-1） =====

    private CallToolResult taskList(CallToolRequest request) {
        TaskState filter = parseStatus(request.arguments());
        List<GatewayTask> tasks = filter != null ? executor.store().list(filter) : executor.store().list();
        List<Map<String, Object>> views = new ArrayList<>();
        for (GatewayTask t : tasks) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("taskId", t.taskId());
            v.put("status", t.status().name().toLowerCase());
            v.put("toolName", t.toolName());
            v.put("target", t.target());
            v.put("createdAt", t.createdAt().toString());
            if (t.completedAt() != null) {
                v.put("completedAt", t.completedAt().toString());
            }
            views.add(v);
        }
        return McpJson.json(Map.of("tasks", views));
    }

    // ===== task-cancel（G-TC-1/2） =====

    private CallToolResult taskCancel(CallToolRequest request) {
        String taskId = requireTaskId(request);
        GatewayTask task = executor.store().get(taskId)
                .orElseThrow(() -> McpError.builder(McpErrorCodes.INVALID_PARAMS)
                        .message("taskId 不存在：" + taskId)
                        .build());
        executor.cancel(task); // WORKING→CANCELLED；终态幂等（返 false，不改状态）
        return McpJson.json(Map.of("taskId", task.taskId(), "status", task.status().name().toLowerCase()));
    }

    // ===== 辅助 =====

    private static String requireTaskId(CallToolRequest request) {
        Object v = request.arguments() == null ? null : request.arguments().get("taskId");
        if (!(v instanceof String s) || s.isBlank()) {
            throw McpError.builder(McpErrorCodes.INVALID_PARAMS)
                    .message("taskId 缺失或为空")
                    .build();
        }
        return s;
    }

    private static TaskState parseStatus(Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        Object v = args.get("status");
        if (v == null) {
            return null;
        }
        try {
            return TaskState.valueOf(String.valueOf(v).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw McpError.builder(McpErrorCodes.INVALID_PARAMS)
                    .message("非法 status 过滤值：" + v)
                    .build();
        }
    }

    /** completed.result 渲染：isError 原样 + content 文本列表（G-TG-2）。 */
    private static Map<String, Object> renderResult(CallToolResult result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("isError", Boolean.TRUE.equals(result.isError()));
        List<Map<String, Object>> content = new ArrayList<>();
        for (Content c : result.content()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", c.type());
            if (c instanceof TextContent tc) {
                item.put("text", tc.text());
            }
            content.add(item);
        }
        m.put("content", content);
        return m;
    }
}
```


---

## com/arthas/gateway/handler/McpErrorCodes.java

**文件**：`src/main/java/com/arthas/gateway/handler/McpErrorCodes.java`

```java
package com.arthas.gateway.handler;

/**
 * JSON-RPC 2.0 标准错误码（MCP 线契约 §5.1：仅用 arthas 实现的 5 个标准 code，不使用 -32xxx 自定义）。
 *
 * <p>SDK 2.0.0 {@code McpSchema} 未公开这些常量（{@code javap -constants} 为空），此处集中定义，
 * 供 {@link DiagnosticRequest}/{@link ToolsCallRouter} 构造 {@link io.modelcontextprotocol.spec.McpError}
 * 时引用，避免裸字面量散落。
 *
 * <p>语义（server-contract.md §6 错误传播表）：
 * <ul>
 *   <li>{@link #PARSE_ERROR}：协议解析错误</li>
 *   <li>{@link #INVALID_REQUEST}：非法 JSON-RPC 消息</li>
 *   <li>{@link #METHOD_NOT_FOUND}：未实现 method（SDK 自动处理未注册工具名）</li>
 *   <li>{@link #INVALID_PARAMS}：target 缺失/空/不在册（网关主用）</li>
 *   <li>{@link #INTERNAL_ERROR}：handler 抛非 McpError 异常时 SDK 兜底</li>
 * </ul>
 */
public final class McpErrorCodes {

    /** 协议解析错误。 */
    public static final int PARSE_ERROR = -32700;
    /** 非法 JSON-RPC 消息。 */
    public static final int INVALID_REQUEST = -32600;
    /** 未实现 method。 */
    public static final int METHOD_NOT_FOUND = -32601;
    /** 参数非法（target 缺失/空/不在册）。 */
    public static final int INVALID_PARAMS = -32602;
    /** 内部错误（非 McpError 异常兜底）。 */
    public static final int INTERNAL_ERROR = -32603;

    private McpErrorCodes() {
        // 常量集合，不实例化
    }
}
```


---

## com/arthas/gateway/handler/McpJson.java

**文件**：`src/main/java/com/arthas/gateway/handler/McpJson.java`

```java
package com.arthas.gateway.handler;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * MCP JSON 单例(002 整改 · P3-3/FR-013,US5)。
 *
 * <p>全局共享 {@link ObjectMapper},替代散在 {@code ToolsCallRouter}/{@code GatewayToolHandlers}/
 * {@code StaticToolRegistry} 的 {@code new ObjectMapper()} 多实例。动机:
 * <ul>
 *   <li>{@code ObjectMapper} 线程安全且构造较重(序列化器缓存/模块发现),单例避免重复构造开销;</li>
 *   <li>序列化配置统一——未来加模块/特性,一处生效,三处行为一致;</li>
 *   <li>消除「同作用域多 mapper」带来的配置漂移风险。</li>
 * </ul>
 *
 * <p>{@link #json(Object)} 封装「对象 → {@code CallToolResult}(单 {@link TextContent} JSON、
 * {@code isError=false})」常见模式——网关自有工具响应(list-targets/task-*)与异步接受响应、错误体整形共用。
 */
public final class McpJson {

    /** 全局共享 {@link ObjectMapper}(线程安全,只读复用)。 */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private McpJson() {
    }

    /**
     * 对象序列化为 {@code CallToolResult}:单 {@link TextContent}(JSON 文本),{@code isError=false}。
     * 供网关自有工具响应与异步接受响应整形(002 整改 P3-3/FR-013)。
     *
     * @param node 任意可序列化对象(Map/POJO)
     * @return 含 JSON 文本的 {@code CallToolResult}(非错误)
     */
    public static CallToolResult json(Object node) {
        return new CallToolResult(List.of(new TextContent(MAPPER.writeValueAsString(node))), false, null, null);
    }
}
```


---

## com/arthas/gateway/handler/ToolsCallRouter.java

**文件**：`src/main/java/com/arthas/gateway/handler/ToolsCallRouter.java`

```java
package com.arthas.gateway.handler;

import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendUnreachableException;
import com.arthas.gateway.backend.CircuitOpenException;
import com.arthas.gateway.backend.ConcurrencyLimitException;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.backend.StatelessAsyncException;
import com.arthas.gateway.task.AsyncTaskExecutor;
import com.arthas.gateway.task.GatewayTask;
import com.arthas.gateway.task.GlobalConcurrencyLimitException;
import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具调用路由器（tools/call 的统一入口，传输无关：stdio/HTTP 两路共用）。
 *
 * <p><b>职责</b>（server-contract.md §5）：
 * <ol>
 *   <li>网关自有工具（GATEWAY_LOCAL）在 target 解析<b>前</b>分流到 {@link GatewayToolHandlers}（其 arguments 无 target）。</li>
 *   <li>{@link DiagnosticRequest#parse} 解析 target 并剥离 → 缺失/空/非字符串 → INVALID_PARAMS（S-ERR-1）。</li>
 *   <li>target 在册解算 → 不在册 → INVALID_PARAMS + {@code data.available}（S-ERR-2）。</li>
 *   <li>按 {@link RoutingMode} 分流。</li>
 * </ol>
 *
 * <h3>路由分流（002 整改：两路径变薄，熔断/槽/分类下沉 {@link BackendEntry} 统一拦截层）</h3>
 * <ul>
 *   <li><b>SYNC_DIRECT / STREAM_AGGREGATE</b>（26 即时 + dashboard）：{@code entry.execute(tool, args)}（admit→invoke→releaseSlot），
 *       结果<b>原样透传</b>（含 {@code isError=true}、后端 JSON-RPC error——后者由 invoke 内 SDK 抛 {@link McpError} 透传，S-ERR-4）。</li>
 *   <li><b>ASYNC_TASK</b>（5 optional：watch/trace/stack/tt/monitor）：{@code entry.admit(tool)}（熔断读 + 取槽；US3 加 STATELESS 校验）
 *       → {@link AsyncTaskExecutor#submit} 后台虚拟线程对后端发<b>同步</b> {@code tools/call}（闭包 {@code entry.invoke}，含 initialize CAS + 故障分类，
 *       P1-3），{@code onTerminal=entry::releaseSlot} 在任一终态/提交失败释放槽（P0-2），立即返 {@link #asyncAcceptedResponse}（G-ASYNC-1）。</li>
 *   <li><b>GATEWAY_LOCAL</b>（4 自有：list-targets/task-*）：委托 {@link GatewayToolHandlers}。</li>
 * </ul>
 *
 * <h3>错误翻译（域异常 → 结构化 {@code McpError}）</h3>
 * <p>{@code execute}/{@code admit}/{@code invoke} 抛<b>域异常</b>（不依赖 McpError/注册表），路由器翻译为结构化 {@code McpError}，
 * 字段集与修复前<b>逐字一致</b>（FR-016）：
 * <ul>
 *   <li>{@link CircuitOpenException} → {@code backend_unreachable}，data 含 {@code retryAfterMs}（来自异常）+ {@code available}。</li>
 *   <li>{@link BackendUnreachableException} → {@code backend_unreachable}，data 含 {@code retryAfterMs}（熔断器读）+ {@code available}。</li>
 *   <li>{@link ConcurrencyLimitException} → INVALID_PARAMS，data 含 {@code maxConcurrentTasks}（来自异常）。</li>
 *   <li>{@link StatelessAsyncException} → INVALID_PARAMS，data 含 {@code reason=stateless_unsupported_async}（仅异步路径）。</li>
 *   <li>{@link GlobalConcurrencyLimitException} → INVALID_PARAMS，data 含 {@code reason=global_concurrency_limit} + {@code globalMaxInflight}（跨 target 累计上限）。</li>
 *   <li>后端业务错误 {@link McpError} <b>原样</b>向上抛（不计熔断，C-CB-2；invoke 内已 recordSuccess）。</li>
 * </ul>
 */
@Component
public class ToolsCallRouter {

    private static final Logger log = LoggerFactory.getLogger(ToolsCallRouter.class);

    private final RegistryHolder registry;
    private final AsyncTaskExecutor asyncExecutor;
    private final GatewayToolHandlers gatewayHandlers;

    public ToolsCallRouter(RegistryHolder registry, AsyncTaskExecutor asyncExecutor, GatewayToolHandlers gatewayHandlers) {
        this.registry = Objects.requireNonNull(registry, "registry 不可为空");
        this.asyncExecutor = Objects.requireNonNull(asyncExecutor, "asyncExecutor 不可为空");
        this.gatewayHandlers = Objects.requireNonNull(gatewayHandlers, "gatewayHandlers 不可为空");
    }

    /**
     * 路由一次工具调用。
     *
     * @param tool    注册表中的工具（含 routingMode）
     * @param request SDK 反序列化的调用请求（name/arguments）
     * @return 调用结果（同步透传 / 异步立即返 taskId / 本地处理）
     */
    public CallToolResult route(ExposedTool tool, CallToolRequest request) {
        // 网关自有工具无 target 参数，在 DiagnosticRequest 解析前分流（避免 target 缺失误报）
        if (tool.routingMode() == RoutingMode.GATEWAY_LOCAL) {
            return gatewayHandlers.handle(tool, request);
        }
        DiagnosticRequest dr = DiagnosticRequest.parse(tool, request); // target 格式校验（S-ERR-1）
        return switch (dr.routingMode()) {
            case SYNC_DIRECT, STREAM_AGGREGATE -> forwardSync(tool, dr);
            case ASYNC_TASK -> submitAsync(tool, dr);
            default -> throw new IllegalStateException("不可达：GATEWAY_LOCAL 已在 parse 前分流，" + dr.routingMode());
        };
    }

    /**
     * 同步转发到 target 后端：{@code entry.execute}（统一拦截层 admit→invoke→releaseSlot），结果原样透传。
     * 域异常翻译为结构化 McpError（字段逐字保持）；后端业务错误 McpError 原样向上抛。
     */
    private CallToolResult forwardSync(ExposedTool tool, DiagnosticRequest dr) {
        BackendEntry entry = resolveTarget(dr); // 不在册 → INVALID_PARAMS + data.available（S-ERR-2）
        long start = System.nanoTime();
        try {
            CallToolResult result = entry.execute(tool.name(), dr.backendArgs());
            log.info("工具调用完成 tool={} target={} isError={} 耗时={}ms",
                    tool.name(), dr.target(), result.isError(), Duration.ofNanos(System.nanoTime() - start).toMillis());
            return result;
        } catch (CircuitOpenException e) {
            log.warn("熔断拒绝（OPEN）target={} retryAfterMs={}", dr.target(), e.retryAfterMs());
            throw backendUnreachableError(dr, e.retryAfterMs(),
                    "target 熔断中：" + dr.target() + "（连续基础设施失败，退避后探测恢复）");
        } catch (ConcurrencyLimitException e) {
            throw concurrencyLimitError(entry, dr, e.maxConcurrentTasks());
        } catch (BackendUnreachableException e) {
            // invoke 已 recordFailure（C-CB-1）；retryAfterMs 读熔断器（达阈值 OPEN 时 >0，与修复前一致）
            log.warn("基础设施故障（计入熔断）tool={} target={} 耗时={}ms {}",
                    tool.name(), dr.target(), Duration.ofNanos(System.nanoTime() - start).toMillis(), e.toString());
            throw backendUnreachableError(dr, entry.breaker().retryAfterMillis(),
                    "target 不可达：" + dr.target() + "（" + describeCause(e) + "）");
        }
        // McpError（后端业务错误）不经此 catch，原样向上抛（invoke 内已 recordSuccess，C-CB-2）
    }

    /** ASYNC_TASK：解算 target → admit 准入 → 提交后台异步任务（闭包 invoke + onTerminal 释放槽）→ 立即返 working（G-ASYNC-1）。 */
    private CallToolResult submitAsync(ExposedTool tool, DiagnosticRequest dr) {
        BackendEntry entry = resolveTarget(dr);
        try {
            entry.admit(tool.name()); // STATELESS 校验（仅异步）+ 熔断读 + 取槽；失败抛域异常
        } catch (StatelessAsyncException e) {
            log.warn("STATELESS 后端拒绝异步调用（前置，不提交后台）target={}", dr.target());
            throw statelessAsyncError(dr);
        } catch (CircuitOpenException e) {
            log.warn("熔断拒绝（OPEN）target={} retryAfterMs={}", dr.target(), e.retryAfterMs());
            throw backendUnreachableError(dr, e.retryAfterMs(),
                    "target 熔断中：" + dr.target() + "（连续基础设施失败，退避后探测恢复）");
        } catch (ConcurrencyLimitException e) {
            throw concurrencyLimitError(entry, dr, e.maxConcurrentTasks());
        }
        // entry 固定引用（热重载不串台）；slot 由 onTerminal（entry::releaseSlot）在任务终态/提交失败时释放——
        // 5 个在途 watch 持 5 槽，第 6 个 admit 取槽失败被前置限流。闭包 invoke 含 initialize + 故障分类 +
        // 熔断驱动（P1-3：异步也驱动熔断；取消中断不计，由 invoke 检测 Thread.interrupted 跳过 recordFailure）。
        GatewayTask task;
        try {
            task = asyncExecutor.submit(tool.name(), dr.target(),
                    () -> entry.invoke(tool.name(), dr.backendArgs()),
                    entry::releaseSlot);
        } catch (GlobalConcurrencyLimitException e) {
            // submit 全局背压拒绝(未创建任务/未提交后台):admit 已取的 per-target 槽须显式释放(P0-2 不漏槽),
            // 全局计数已在 submit 内回滚(无泄漏)。
            entry.releaseSlot();
            log.warn("全局并发限流（跨 target 累计）target={} globalMaxInflight={} availableSlots={}",
                    dr.target(), e.globalMaxInflight(), entry.availableSlots());
            throw globalConcurrencyLimitError(dr, e.globalMaxInflight());
        }
        log.info("异步任务已接受 tool={} target={} taskId={}", tool.name(), dr.target(), task.taskId());
        return asyncAcceptedResponse(task);
    }

    /** {@link BackendUnreachableException} cause 的可读描述（供错误消息，不含敏感信息）。 */
    private static String describeCause(BackendUnreachableException e) {
        Throwable c = e.getCause();
        if (c == null) {
            return "unknown";
        }
        return c.getClass().getSimpleName() + ": " + String.valueOf(c.getMessage());
    }

    /** 失效 target 结构化错误（S-ERR-5）：INVALID_PARAMS + data{target,reason:backend_unreachable,available,retryAfterMs}。 */
    private McpError backendUnreachableError(DiagnosticRequest dr, long retryAfterMs, String message) {
        return McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message(message)
                .data(Map.of(
                        "target", dr.target(),
                        "reason", "backend_unreachable",
                        "available", List.copyOf(registry.current().names()),
                        "retryAfterMs", retryAfterMs))
                .build();
    }

    /**
     * STATELESS 异步前置拒绝错误（P1-2/原理四双侧契约）：INVALID_PARAMS + data{target,reason:stateless_unsupported_async,available}。
     *
     * <p>无状态后端无法承载带任务语义、需轮询的异步诊断调用（watch/trace/stack/tt/monitor）——前置返明确错误，
     * 不提交后台、不耗兜底超时。同步调用不受影响（不经此校验）。
     */
    private McpError statelessAsyncError(DiagnosticRequest dr) {
        return McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message("target 协议为 STATELESS，不支持异步诊断任务：" + dr.target()
                        + "（仅同步类工具可用；异步类 watch/trace/stack/tt/monitor 需 STREAMABLE 后端）")
                .data(Map.of(
                        "target", dr.target(),
                        "reason", "stateless_unsupported_async",
                        "available", List.copyOf(registry.current().names())))
                .build();
    }

    /**
     * 全局并发限流结构化错误(P2-4/FR-010):INVALID_PARAMS + data{target,reason:global_concurrency_limit,globalMaxInflight,available}。
     *
     * <p>跨 target 累计在途异步任务超全局上限(配置或动态默认=后端数×5)——防集群触顶(虚拟线程虽廉价,后端总数有限)。
     */
    private McpError globalConcurrencyLimitError(DiagnosticRequest dr, int globalMaxInflight) {
        return McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message("全局在途异步任务已达上限（globalMaxInflight=" + globalMaxInflight
                        + "，跨 target 累计）；稍后重试或减少并发异步诊断。target=" + dr.target())
                .data(Map.of(
                        "target", dr.target(),
                        "reason", "global_concurrency_limit",
                        "globalMaxInflight", globalMaxInflight,
                        "available", List.copyOf(registry.current().names())))
                .build();
    }

    /** 并发越界结构化错误（C-LIMIT-1）：INVALID_PARAMS + data{target,reason:concurrency_limit,maxConcurrentTasks}。 */
    private McpError concurrencyLimitError(BackendEntry entry, DiagnosticRequest dr, int maxConcurrentTasks) {
        log.warn("并发限流（前置拦截，不越界打后端）target={} maxConcurrentTasks={} availableSlots={}",
                dr.target(), maxConcurrentTasks, entry.availableSlots());
        return McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message("target 并发已达上限：" + dr.target()
                        + "（maxConcurrentTasks=" + maxConcurrentTasks + "）")
                .data(Map.of(
                        "target", dr.target(),
                        "reason", "concurrency_limit",
                        "maxConcurrentTasks", maxConcurrentTasks))
                .build();
    }

    /** target 在册解算：缺失 → INVALID_PARAMS + data.available（当前可用逻辑名快照）。 */
    private BackendEntry resolveTarget(DiagnosticRequest dr) {
        Optional<BackendEntry> entry = registry.get(dr.target());
        if (entry.isEmpty()) {
            throw McpError.builder(McpErrorCodes.INVALID_PARAMS)
                    .message("target 不在册：" + dr.target() + "（见 list-targets 的可用目标）")
                    .data(Map.of("available", List.copyOf(registry.current().names())))
                    .build();
        }
        return entry.get();
    }

    /**
     * 异步任务的立即接受响应（G-ASYNC-1）：固定 {@code status:"working"}（submit 时即此态，不重读 task 状态，
     * 避免与后台瞬时失败竞态），附 {@code _meta:{toolName,target}} 供调用方关联。
     */
    public static CallToolResult asyncAcceptedResponse(GatewayTask task) {
        Map<String, Object> body = Map.of(
                "taskId", task.taskId(),
                "status", "working",
                "_meta", Map.of("toolName", task.toolName(), "target", task.target()));
        return McpJson.json(body); // 委托 McpJson 单例(T025/FR-013)
    }
}
```


---

## com/arthas/gateway/obs/BackendRegistryHealthIndicator.java

**文件**：`src/main/java/com/arthas/gateway/obs/BackendRegistryHealthIndicator.java`

```java
package com.arthas.gateway.obs;

import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.RegistryHolder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * T050 后端注册表健康指标——将注册表 + 熔断状态映射到 {@code /actuator/health} 的 details
 * （宪法原则五；运维无需读源码即可见各后端健康；T050「唯一允许 Actuator 端点直接验证」）。
 *
 * <p><b>状态语义</b>（与 {@code list-targets} 一致，不主动探测后端——避免对外发慢调用）：
 * <ul>
 *   <li>网关进程 status 恒为 <b>UP</b>——故障隔离：单后端熔断是正常韧性表现，<b>不</b>拉低网关 status
 *       （熔断 OPEN 时网关仍能对其他 target 服务、对失效 target 返结构化错误）。</li>
 *   <li>单后端 {@code healthy = state==ACTIVE && breaker 未 OPEN}（同 {@code GatewayToolHandlers#listTargets}）。</li>
 *   <li>{@code details.backends[name] = {state, healthy, protocol, breaker}}；
 *       {@code details.summary = {total, healthy, unhealthy}}。</li>
 * </ul>
 *
 * <p>Spring Boot 4.x：health API 迁至 {@code org.springframework.boot.health.contributor}（自 {@code actuate.health}），
 * 经 {@code spring-boot-starter-actuator} → {@code spring-boot-health} 传递可用。
 */
@Component
public class BackendRegistryHealthIndicator implements HealthIndicator {

    private final RegistryHolder registry;

    public BackendRegistryHealthIndicator(RegistryHolder registry) {
        this.registry = Objects.requireNonNull(registry, "registry 不可为空");
    }

    @Override
    public Health health() {
        BackendRegistry reg = registry.current();
        Map<String, Object> backends = new LinkedHashMap<>();
        int healthy = 0;
        int unhealthy = 0;
        for (BackendEntry e : reg.byName().values()) {
            boolean isHealthy = e.isHealthy(); // 健康单一事实源(T027/FR-012),与 list-targets 共用同一判定
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("state", e.state().name());
            detail.put("healthy", isHealthy);
            detail.put("protocol", e.config().protocol().name());
            detail.put("breaker", e.breaker().state().name());
            backends.put(e.config().name(), detail);
            if (isHealthy) {
                healthy++;
            } else {
                unhealthy++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", reg.size());
        summary.put("healthy", healthy);
        summary.put("unhealthy", unhealthy);
        return Health.up()
                .withDetail("backends", backends)
                .withDetail("summary", summary)
                .build();
    }
}
```


---

## com/arthas/gateway/orchestration/ArthasProvisioner.java

**文件**：`src/main/java/com/arthas/gateway/orchestration/ArthasProvisioner.java`

```java
package com.arthas.gateway.orchestration;

import com.arthas.gateway.auth.BackendAuthCustomizer;
import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendConfigException;
import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.Source;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * arthas MCP 供给器（003 特性，契约 §3，data-model §8）：对指定 K8S pod <b>原子幂等</b>完成
 * 「注入 arthas → 启动绑 0.0.0.0 的 arthas MCP → NodePort 暴露 → 内部健康检查 → 动态注册进网关」，
 * 任一子步失败 → <b>不注册</b>（status=failed），全部成功 → status=ready，幂等命中 → status=reused。
 *
 * <h3>流程（契约 §3 行为）</h3>
 * <ol>
 *   <li>派生 {@code logicalName = {server}-{pod}}（K-ENS-8）。</li>
 *   <li>注册表已有且后端健康（MCP 握手成功）→ <b>零副作用复用</b>（status=reused，K-ENS-2）。</li>
 *   <li>否则供给（ensuring）：
 *     <ul>
 *       <li>exec 定位 JVM PID（无 shell/403/不可达→分类故障；无 PID→no_jvm）。</li>
 *       <li>上传 arthas-boot.jar 进 pod /tmp/。</li>
 *       <li>exec 启动 {@code java -jar /tmp/arthas-boot.jar <pid> --attach-only --http-port <mcpPort>
 *           --target-ip <targetIp> --use-version 4.3.0}（R4：0.0.0.0 方 NodePort 可达）。</li>
 *       <li>NodePort 暴露 → mcpUrl（根 URL，无 /mcp）。</li>
 *       <li>内部健康检查：轮询 mcpUrl 的 initialize 握手确认就绪（超时→health_check_timeout；K-ENS-7
 *           loopback 经 NodePort 不可达即触发）。</li>
 *       <li>{@link DynamicBackendStore#register}（source=DYNAMIC）+ composer 原子 swap effective。</li>
 *     </ul>
 *   </li>
 *   <li>任一子步失败 → 不注册、记 failed（已打 label/已建 Service 进 record 供清理，K-ATOMIC-1）。</li>
 * </ol>
 *
 * <p><b>真实性</b>：全程真实 fabric8 exec + 真实 arthas 注入 + 真实 NodePort + 真实 MCP 握手健康检查（零桩）。
 * 诊断复用 gateway-core 既有路由（ensure 仅产出 target，诊断由既有 watch/trace 等经 {@code ToolsCallRouter}）。
 */
public class ArthasProvisioner {

    private static final Logger log = LoggerFactory.getLogger(ArthasProvisioner.class);

    /** pod 内 arthas MCP 监听端口（NodePort targetPort）。 */
    static final int DEFAULT_MCP_PORT = 8563;
    /** locate JVM / install / start exec 超时。 */
    private static final Duration LOCATE_TIMEOUT = Duration.ofSeconds(10);
    /** arthas attach 进程超时（首跑下载 arthas 4.3.0 运行时到 ~/.arthas/lib/4.3.0，留足余量）。 */
    private static final Duration ATTACH_TIMEOUT = Duration.ofSeconds(300);
    /** 健康检查轮询总超时（arthas MCP 就绪握手）默认值。 */
    static final Duration DEFAULT_HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(90);
    /** 健康检查单次握手请求超时。 */
    private static final Duration HEALTH_PROBE_REQUEST_TIMEOUT = Duration.ofSeconds(3);
    /** 健康检查轮询间隔。 */
    private static final Duration HEALTH_POLL_INTERVAL = Duration.ofMillis(1000);
    /** 动态注册后端的连接/调用超时与并发槽（复用 001 默认）。 */
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int CALL_TIMEOUT_MS = 30000;
    private static final int MAX_CONCURRENT_TASKS = 5;
    /** pod 内 arthas-boot.jar 落点。 */
    private static final String REMOTE_ARTHAS_JAR = "/tmp/arthas-boot.jar";
    /** 纯数字 PID 行（jps -q 输出）。 */
    private static final Pattern PID_LINE = Pattern.compile("\\s*(\\d+)\\s*");

    private final KubernetesClient client;
    private final K8sExec exec;
    private final NodePortExposer exposer;
    private final DynamicBackendStore dynamicStore;
    private final OrchestrationRecordStore recordStore;
    private final String targetIp;
    private final Path arthasBootJar;
    private final int mcpPort;
    private final String arthasVersion;
    /** arthas HTTP 访问密码（--password 下发；Bearer 令牌注入动态后端 Auth + 健康检查）。 */
    private final String arthasPassword;
    /** 动态后端认证声明（BEARER + arthasPassword），供注册与健康检查复用。 */
    private final BackendConfig.Auth auth;
    /** 健康检查轮询总超时（生产用默认 90s；K-ENS-7 loopback 故障用例注入短超时快速失败）。 */
    private final Duration healthCheckTimeout;

    /** 生产装配（参数来自 {@link com.arthas.gateway.config.GatewayProperties.K8s}）。 */
    public ArthasProvisioner(KubernetesClient client, NodePortExposer exposer,
                             DynamicBackendStore dynamicStore, OrchestrationRecordStore recordStore,
                             String targetIp, String arthasBootJar, int mcpPort, String arthasVersion,
                             String arthasPassword) {
        this(client, exposer, dynamicStore, recordStore, targetIp, arthasBootJar, mcpPort,
                arthasVersion, arthasPassword, DEFAULT_HEALTH_CHECK_TIMEOUT);
    }

    /**
     * 测试构造器：可注入健康检查轮询总超时。
     *
     * <p>K-ENS-7（arthas 绑 loopback → NodePort 不可达 → 健康检查超时）需<b>短</b>超时快速失败，
     * 避免 90s 默认值拖慢故障用例。生产装配走 9 参构造器（默认 90s）。
     */
    ArthasProvisioner(KubernetesClient client, NodePortExposer exposer,
                      DynamicBackendStore dynamicStore, OrchestrationRecordStore recordStore,
                      String targetIp, String arthasBootJar, int mcpPort, String arthasVersion,
                      String arthasPassword, Duration healthCheckTimeout) {
        this.client = Objects.requireNonNull(client, "client 不可为空");
        this.exec = new K8sExec(client);
        this.exposer = Objects.requireNonNull(exposer, "exposer 不可为空");
        this.dynamicStore = Objects.requireNonNull(dynamicStore, "dynamicStore 不可为空");
        this.recordStore = Objects.requireNonNull(recordStore, "recordStore 不可为空");
        this.targetIp = Objects.requireNonNull(targetIp, "targetIp 不可为空");
        this.mcpPort = mcpPort > 0 ? mcpPort : DEFAULT_MCP_PORT;
        this.arthasVersion = Objects.requireNonNull(arthasVersion, "arthasVersion 不可为空");
        this.arthasPassword = Objects.requireNonNull(arthasPassword, "arthasPassword 不可为空");
        this.auth = new BackendConfig.Auth(AuthMode.BEARER, arthasPassword, null, null);
        this.healthCheckTimeout = Objects.requireNonNull(healthCheckTimeout, "healthCheckTimeout 不可为空");
        Path jar = Path.of(Objects.requireNonNull(arthasBootJar, "arthasBootJar 不可为空"));
        if (!Files.isReadable(jar)) {
            throw new IllegalStateException("arthas-boot.jar 不可读：" + jar.toAbsolutePath()
                    + "（应作为静态工具文件置于工程 tools/，见 memory arthas-no-dependency）");
        }
        this.arthasBootJar = jar;
    }

    /** 确定性派生逻辑名（target 名）= {@code {server}-{pod}}（K-ENS-8）。 */
    public static String deriveLogicalName(String server, String pod) {
        return server + "-" + pod;
    }

    /**
     * 原子幂等供给。
     *
     * @param server    服务器名（逻辑名前缀）
     * @param pod       目标 pod 名
     * @param namespace K8S namespace
     * @param now       供给发起时间（传入瞬时量，便于确定性测试）
     * @return 终态记录（ready/reused/failed）
     */
    public OrchestrationRecord ensure(String server, String pod, String namespace, Instant now) {
        Objects.requireNonNull(server, "server 不可为空");
        Objects.requireNonNull(pod, "pod 不可为空");
        Objects.requireNonNull(namespace, "namespace 不可为空");
        Objects.requireNonNull(now, "now 不可为空");

        String logicalName = deriveLogicalName(server, pod);
        OrchestrationRecord rec = OrchestrationRecord.ensuring(logicalName, server, pod, namespace, now);
        recordStore.record(rec);

        // 幂等复用：注册表已有且后端健康 → 零副作用 reused（K-ENS-2）
        Optional<BackendConfig> existing = dynamicStore.get(logicalName);
        if (existing.isPresent()) {
            String url = existing.get().url();
            if (probeHealthy(url, Duration.ofSeconds(HEALTH_PROBE_REQUEST_TIMEOUT.toSeconds() * 2))) {
                OrchestrationRecord reused = rec.reused(url, null, now);
                recordStore.record(reused);
                log.info("ensure 幂等复用：target={} url={}", logicalName, url);
                return reused;
            }
            log.info("ensure 注册表命中但后端不健康，重新供给：target={}", logicalName);
        }

        // 供给（任一子步失败 → failed 不注册，K-ATOMIC-1）
        OrchestrationRecord terminal = doProvision(rec, namespace, pod, logicalName, now);
        recordStore.record(terminal);
        return terminal;
    }

    /**
     * 供给子流程。任一子步抛 {@link ProvisionException} → 返回 failed 记录（<b>不</b>注册）；
     * 全部成功 → 返回 ready 记录。
     *
     * <p>failed 记录由<b>局部</b> {@code rec} 派生，故即便失败发生在 NodePort 暴露之后（如 health_check），
     * 已打的 mcpUrl/serviceRef 副作用仍随 failed 记录留存（K-ATOMIC-1：副作用供运维追溯清理）。
     */
    private OrchestrationRecord doProvision(OrchestrationRecord rec, String namespace, String pod,
                                            String logicalName, Instant now) {
        try {
            // 1. 定位 JVM PID（无 shell/403/不可达/无 JVM → 分类）
            long pid = locateJvm(namespace, pod);

            // 2. 上传 arthas-boot.jar
            installArthas(namespace, pod);

            // 3. 启动 arthas（attach 进程短命，首跑下载 arthas 运行时）
            startArthas(namespace, pod, pid);

            // 4. NodePort 暴露 → mcpUrl
            NodePortExposer.ExposeResult exposed;
            try {
                exposed = exposer.expose(namespace, pod, logicalName, mcpPort);
            } catch (RuntimeException e) {
                throw new ProvisionException(errorOf("nodeport_alloc_failed", "expose_nodeport",
                        "NodePort Service 创建失败：" + e.getMessage()));
            }
            String mcpUrl = exposed.mcpUrl();
            String serviceRef = exposed.serviceRef();
            rec = rec.withExposed(mcpUrl, serviceRef); // 记录已暴露副作用（供 failed 追溯清理）

            // 5. 健康检查：轮询 mcpUrl MCP 握手（超时 → health_check_timeout；K-ENS-7 loopback 不可达即触发）
            if (!probeHealthy(mcpUrl, healthCheckTimeout)) {
                throw new ProvisionException(errorOf("health_check_timeout", "health_check",
                        "arthas MCP 在超时内未就绪（mcpUrl=" + mcpUrl + "，target-ip=" + targetIp + "）"));
            }

            // 6. 动态注册（source=DYNAMIC）+ composer 原子 swap
            BackendConfig cfg = new BackendConfig(
                    logicalName, mcpUrl, Protocol.STREAMABLE,
                    auth,
                    CONNECT_TIMEOUT_MS, CALL_TIMEOUT_MS, MAX_CONCURRENT_TASKS, Source.DYNAMIC);
            try {
                dynamicStore.register(cfg);
            } catch (BackendConfigException e) {
                // 命名冲突（动态名 ∩ 静态种子 / 同名异 URL）→ name_conflict（K-ENS-9）
                throw new ProvisionException(errorOf("name_conflict", "register", e.getMessage()));
            }

            log.info("ensure 供给完成：target={} mcpUrl={}", logicalName, mcpUrl);
            return rec.ready(mcpUrl, serviceRef, now);
        } catch (ProvisionException e) {
            OrchestrationRecord failed = rec.failed(e.error, now);
            // 末位传 e：SLF4J 打印完整异常链 + 堆栈（宪法原则五可观测性；上传失败原委此前被吞）
            log.warn("ensure 失败（未注册）：target={} reason={} stage={}", logicalName,
                    e.error.reason(), e.error.phase(), e);
            return failed;
        }
    }

    // ===== 子步：定位 JVM =====

    /** exec 定位运行中的 JVM PID；无 shell/403/不可达→分类，无 PID→no_jvm。 */
    private long locateJvm(String namespace, String pod) {
        K8sExec.ExecResult r;
        try {
            r = exec.exec(namespace, pod, LOCATE_TIMEOUT, "sh", "-c",
                    "jps -q 2>/dev/null | grep -E '^[0-9]+$' | head -1");
        } catch (K8sExecException e) {
            throw new ProvisionException(classifyExecError(e, "locate_jvm"));
        }
        if (r.exitCode() != 0) {
            // exec 非零退出：负值=通道故障（无 shell 等），正值=命令失败 → 按 shell/可达性分类
            throw new ProvisionException(classifyExecResult(r, "locate_jvm"));
        }
        String pidStr = r.stdout().trim();
        if (!PID_LINE.matcher(pidStr).matches()) {
            throw new ProvisionException(errorOf("no_jvm", "locate_jvm",
                    "pod 内无可被 arthas attach 的运行中 JVM（" + namespace + "/" + pod + "）"));
        }
        return Long.parseLong(pidStr.trim());
    }

    // ===== 子步：上传 arthas-boot.jar =====

    private void installArthas(String namespace, String pod) {
        try {
            boolean ok = client.pods().inNamespace(namespace).withName(pod)
                    .file(REMOTE_ARTHAS_JAR).upload(arthasBootJar);
            if (!ok) {
                log.warn("installArthas：fabric8 upload 返回 false（namespace={}, pod={}, jar={}）",
                        namespace, pod, arthasBootJar);
                throw new ProvisionException(errorOf("attach_failed", "install_arthas",
                        "上传 arthas-boot.jar 返回 false（" + namespace + "/" + pod + "）"));
            }
        } catch (RuntimeException e) {
            if (e instanceof ProvisionException) {
                throw e;
            }
            // 记录原始异常堆栈（宪法原则五：上传失败根因此前被吞，仅留 reason/stage）
            log.warn("installArthas：fabric8 upload 抛异常（namespace={}, pod={}）", namespace, pod, e);
            throw new ProvisionException(errorOf("attach_failed", "install_arthas",
                    "上传 arthas-boot.jar 失败：" + e.getMessage()), e);
        }
    }

    // ===== 子步：启动 arthas =====

    private void startArthas(String namespace, String pod, long pid) {
        K8sExec.ExecResult r;
        try {
            r = exec.exec(namespace, pod, ATTACH_TIMEOUT,
                    "java", "-jar", REMOTE_ARTHAS_JAR, String.valueOf(pid),
                    "--attach-only",
                    "--http-port", String.valueOf(mcpPort),
                    "--target-ip", targetIp,
                    "--telnet-port", "0",
                    "--use-version", arthasVersion,
                    "--password", arthasPassword);
        } catch (K8sExecException e) {
            throw new ProvisionException(errorOf("attach_failed", "start_arthas",
                    "arthas attach 执行失败：" + e.getMessage()));
        }
        if (r.exitCode() != 0) {
            throw new ProvisionException(errorOf("attach_failed", "start_arthas",
                    "arthas attach 非零退出（exit=" + r.exitCode() + "）：" + r.stderr()));
        }
    }

    // ===== 健康检查（MCP 握手轮询） =====

    /** 轮询 mcpUrl 直至 MCP initialize 握手成功（arthas MCP 就绪）；超时返 false。 */
    boolean probeHealthy(String mcpUrl, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (McpSyncClient c = McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(mcpUrl)
                            .httpRequestCustomizer(new BackendAuthCustomizer(auth))
                            .build())
                    .requestTimeout(HEALTH_PROBE_REQUEST_TIMEOUT)
                    .build()) {
                c.initialize(); // arthas MCP 就绪则握手成功
                return true;
            } catch (RuntimeException e) {
                sleepQuiet(HEALTH_POLL_INTERVAL);
            }
        }
        return false;
    }

    private static void sleepQuiet(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** exec 非零退出分类（locate_jvm 等）：负值=通道故障/无 shell；stderr 含 not-found→no_shell；其余→k8s_unreachable。 */
    private static OrchestrationRecord.Error classifyExecResult(K8sExec.ExecResult r, String stage) {
        String combined = (r.stdout() + " " + r.stderr()).toLowerCase();
        if (r.exitCode() < 0
                || combined.contains("executable file not found") || combined.contains("not found in path")
                || combined.contains("no such file or directory") || combined.contains("not a directory")) {
            return errorOf("no_shell", stage,
                    "pod 内无可用 shell（exec 非零退出 exit=" + r.exitCode() + "）：" + r.stderr().trim());
        }
        if (combined.contains("forbidden")) {
            return errorOf("k8s_forbidden", stage, "exec 被 RBAC 拒绝（exit=" + r.exitCode() + "）：" + r.stderr().trim());
        }
        return errorOf("k8s_unreachable", stage,
                "exec 故障（exit=" + r.exitCode() + "）：" + r.stderr().trim());
    }

    // ===== 故障分类 =====

    /** exec 基础设施故障分类：403→k8s_forbidden；命令未找到→no_shell；其余→k8s_unreachable。 */
    private static OrchestrationRecord.Error classifyExecError(K8sExecException e, String stage) {
        String msg = (e.getMessage() + " " + e.stderr()).toLowerCase();
        Throwable cause = e.getCause();
        int code = -1;
        if (cause instanceof io.fabric8.kubernetes.client.KubernetesClientException kce) {
            code = kce.getCode();
        }
        if (code == 403 || msg.contains("forbidden")) {
            return errorOf("k8s_forbidden", stage, "exec 被 RBAC 拒绝（403）：" + e.getMessage());
        }
        if (msg.contains("executable file not found") || msg.contains("not found in path")
                || msg.contains("no such file or directory") || msg.contains("not a directory")) {
            return errorOf("no_shell", stage, "pod 内无可用 shell（exec 失败）：" + e.getMessage());
        }
        return errorOf("k8s_unreachable", stage, "exec 通道故障：" + e.getMessage());
    }

    private static OrchestrationRecord.Error errorOf(String reason, String stage, String message) {
        return new OrchestrationRecord.Error(stage, reason, message);
    }

    /** 供给子步失败（携带 reason/phase/message，供 ensure 映射为 failed 记录）。 */
    private static final class ProvisionException extends RuntimeException {
        final OrchestrationRecord.Error error;

        ProvisionException(OrchestrationRecord.Error error) {
            super(error.reason() + "@" + error.phase() + ": " + error.message());
            this.error = error;
        }

        ProvisionException(OrchestrationRecord.Error error, Throwable cause) {
            super(error.reason() + "@" + error.phase() + ": " + error.message(), cause);
            this.error = error;
        }
    }
}
```


---

## com/arthas/gateway/orchestration/K8sClientFactory.java

**文件**：`src/main/java/com/arthas/gateway/orchestration/K8sClientFactory.java`

```java
package com.arthas.gateway.orchestration;

import com.arthas.gateway.config.GatewayProperties;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 由 kubeconfig 构建并持有 fabric8 {@link KubernetesClient}（003 特性，research.md R2）。
 *
 * <p>启动期一次性读取 {@code arthas-gateway.k8s.kubeconfig} 指向的 kubeconfig 文件（root-on-node 派生的
 * admin 凭证，见 K8S 测试环境设计），经 {@link Config#fromKubeconfig(String)} 解析（自动取 current-context）
 * 构建单例 {@link KubernetesClient}。{@link KubernetesClient} 线程安全，所有 fabric8 操作（list/exec/create）
 * 共用此实例。
 *
 * <p>{@link AutoCloseable}：容器关闭时关 client（释放底层 HTTP 连接池/WebSocket）。仅在 kubeconfig 存在时
 * 装配（见 {@link K8sEnabledCondition}）——CI 无 k3s 时本 bean 不创建，gateway 以 35 工具形态运行。
 *
 * <p><b>context 覆盖</b>：{@code arthas-gateway.k8s.context} 非空时仅作信息提示（当前实现尊重 kubeconfig
 * 的 current-context；测试床为单 context，覆盖非 MVP 所需）。
 */
public final class K8sClientFactory implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(K8sClientFactory.class);

    private final KubernetesClient client;

    public K8sClientFactory(GatewayProperties props) {
        Objects.requireNonNull(props, "props 不可为空");
        this.client = build(props.getK8s());
    }

    private static KubernetesClient build(GatewayProperties.K8s k8s) {
        Path kubeconfig = Path.of(k8s.getKubeconfig()).toAbsolutePath();
        String content;
        try {
            content = Files.readString(kubeconfig);
        } catch (IOException e) {
            throw new IllegalStateException("kubeconfig 读取失败：" + kubeconfig + "（" + e.getMessage() + "）", e);
        }
        if (content.isBlank()) {
            throw new IllegalStateException("kubeconfig 内容为空：" + kubeconfig);
        }
        Config config = Config.fromKubeconfig(content); // 取 current-context（测试床单 context）
        if (k8s.getContext() != null && !k8s.getContext().isBlank()) {
            log.info("k8s.context 配置为 {}：当前实现尊重 kubeconfig current-context（单 context 测试床）", k8s.getContext());
        }
        KubernetesClient c = new KubernetesClientBuilder().withConfig(config).build();
        log.info("KubernetesClient 已构建（kubeconfig={}, master={})",
                kubeconfig, c.getMasterUrl());
        return c;
    }

    /** 单例 fabric8 客户端（list/exec/create 共用）。 */
    public KubernetesClient client() {
        return client;
    }

    @Override
    public void close() {
        client.close();
        log.info("KubernetesClient 已关闭");
    }
}
```


---

## com/arthas/gateway/orchestration/K8sEnabledCondition.java

**文件**：`src/main/java/com/arthas/gateway/orchestration/K8sEnabledCondition.java`

```java
package com.arthas.gateway.orchestration;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * K8S 编排启用条件（003 特性）：仅当 {@code arthas-gateway.k8s.kubeconfig} 指向的 kubeconfig 文件
 * <b>存在且可读</b>时，装配 K8S 编排 bean（{@link K8sClientFactory} 及其下游 PodExplorer/Exposer/
 * Provisioner/Handlers）。
 *
 * <p><b>设计意图</b>：3 个编排工具的<b>规格</b>（name/description/schema）始终注册（{@code tools/list}=38 恒成立，
 * 回归守护 T029）；但其 <b>handler 执行</b>依赖真实 kubeconfig——CI 无 k3s 时 kubeconfig 不存在 → 跳过编排
 * bean 装配 → 调用编排工具返 "K8S 编排未启用" 明确错误（而非启动期崩上下文）。本地有 k3s 测试床时 kubeconfig
 * 存在 → 全量编排生效。
 *
 * <p>等价于「kubeconfig 可读 = 编排可用」，避免对 CI 侧无意义资源的需求（波次 A 纯逻辑测试不依赖 k3s）。
 */
public class K8sEnabledCondition implements Condition {

    private static final Logger log = LoggerFactory.getLogger(K8sEnabledCondition.class);

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String kubeconfig = context.getEnvironment().getProperty("arthas-gateway.k8s.kubeconfig");
        if (kubeconfig == null || kubeconfig.isBlank()) {
            log.debug("K8S 编排未启用：arthas-gateway.k8s.kubeconfig 未配置");
            return false;
        }
        Path path = Path.of(kubeconfig);
        boolean ok = Files.isReadable(path);
        log.debug("K8S 编排启用判定：kubeconfig={} readable={}", path, ok);
        return ok;
    }
}
```


---

## com/arthas/gateway/orchestration/K8sExec.java

**文件**：`src/main/java/com/arthas/gateway/orchestration/K8sExec.java`

```java
package com.arthas.gateway.orchestration;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecWatch;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * pod 内同步命令执行助手（003 特性，research.md R2）：封装 fabric8 exec，捕获 stdout/stderr/exit code，
 * 把异步 {@link ExecWatch} 收敛为同步 {@link ExecResult}。
 *
 * <p>供 {@link K8sPodExplorer}（探测 hasJvm/hasShell）与 {@link ArthasProvisioner}（定位 JVM PID、启动 arthas）
 * 共用。命令以 argv 形式下发（如 {@code sh -c "..."}），fabric8 经 K8S exec 子资源在目标 pod 容器内执行。
 *
 * <p><b>进程退出码（关键）</b>：经 {@link ExecWatch#exitCode()}（{@code CompletableFuture<Integer>}）获取
 * <b>真实进程退出码</b>——<b>不</b>用 {@code ExecListener.onClose(code)} 的 code（那是 WebSocket 关闭码，
 * 如 1000=NORMAL，非进程退出状态，曾导致误判）。调用方据此区分「进程退出码非 0」（无 shell/命令失败）与
 * 「exec 通道故障」（API 不可达 / RBAC 403）。
 *
 * <p><b>超时与基础设施失败</b>：命令未在 {@code timeout} 内退出、或 exec 通道本身故障（无 shell / RBAC 403 /
 * API 不可达）→ 抛 {@link K8sExecException}（携带已捕获的 stderr 与 cause）。
 *
 * <p>线程安全：无状态（仅持共享 {@link KubernetesClient}）。
 */
public final class K8sExec {

    private final KubernetesClient client;

    public K8sExec(KubernetesClient client) {
        this.client = Objects.requireNonNull(client, "client 不可为空");
    }

    /** 单次执行结果（exit code + stdout + stderr，UTF-8 解码）。 */
    public record ExecResult(int exitCode, String stdout, String stderr) {
    }

    /**
     * 在指定 pod 容器内同步执行命令。
     *
     * @param namespace 命名空间
     * @param pod       pod 名
     * @param timeout   执行超时（含等待 exit code）
     * @param command   命令 argv（如 {@code "sh", "-c", "echo hi"}）
     * @return 执行结果
     * @throws K8sExecException 超时 / exec 通道故障（无 shell / 403 / API 不可达）
     */
    public ExecResult exec(String namespace, String pod, Duration timeout, String... command) {
        Objects.requireNonNull(namespace, "namespace 不可为空");
        Objects.requireNonNull(pod, "pod 不可为空");
        Objects.requireNonNull(timeout, "timeout 不可为空");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ExecWatch watch;
        try {
            watch = client.pods().inNamespace(namespace).withName(pod)
                    .writingOutput(out)
                    .writingError(err)
                    .exec(command);
        } catch (RuntimeException e) {
            // exec 启动即失败（API 不可达 / RBAC 403 / pod 不存在）
            throw new K8sExecException("exec 启动失败：" + String.join(" ", command), e, -1,
                    err.toString(StandardCharsets.UTF_8));
        }
        try {
            // 真实进程退出码（CompletableFuture）——非 WebSocket 关闭码
            int code = watch.exitCode().get(timeout.toSeconds(), TimeUnit.SECONDS);
            return new ExecResult(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } catch (TimeoutException e) {
            throw new K8sExecException("exec 超时（" + timeout + "）：" + String.join(" ", command),
                    e, -1, err.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new K8sExecException("exec 被中断：" + String.join(" ", command), e, -1,
                    err.toString(StandardCharsets.UTF_8));
        } catch (ExecutionException e) {
            // exitCode() future 异常完成：exec 通道故障（403 / pod 不可达 / 无 shell）
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new K8sExecException("exec 通道故障：" + String.join(" ", command) + "（" + cause + "）",
                    cause, -1, err.toString(StandardCharsets.UTF_8));
        } finally {
            watch.close();
        }
    }
}
```


---

## com/arthas/gateway/orchestration/K8sExecException.java

**文件**：`src/main/java/com/arthas/gateway/orchestration/K8sExecException.java`

```java
package com.arthas.gateway.orchestration;

/**
 * pod exec 失败（003 特性）。封装超时、通道故障、基础设施错误（无 shell / RBAC 403 / API 不可达）。
 *
 * <p>携带 {@code exitCode}（-1 表示未获取到，如超时/通道异常）与 {@code stderr}（已捕获的容器标准错误，
 * 供调用方分类故障原因）。由 {@link ArthasProvisioner} 捕获后映射为契约 {@code reason/stage}。
 */
public class K8sExecException extends RuntimeException {

    private final int exitCode;
    private final String stderr;

    public K8sExecException(String message, Throwable cause, int exitCode, String stderr) {
        super(message, cause);
        this.exitCode = exitCode;
        this.stderr = stderr == null ? "" : stderr;
    }

    /** exec exit code（-1 = 未获取，如超时/通道异常）。 */
    public int exitCode() {
        return exitCode;
    }

    /** 已捕获的容器标准错误（可能为空）。 */
    public String stderr() {
        return stderr;
    }
}
```


---

## com/arthas/gateway/orchestration/K8sPodExplorer.java

**文件**：`src/main/java/com/arthas/gateway/orchestration/K8sPodExplorer.java`

```java
package com.arthas.gateway.orchestration;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 集群 pod/service 枚举器（003 特性，契约 §1/§2）：fabric8 list + 容器内 exec 探测，供
 * {@code k8s.list-pods} / {@code k8s.list-services} 工具返回真实集群清单。
 *
 * <p><b>hasJvm/hasShell 探测</b>（设计 §4：目标 pod 须 shell+java+JVM）：对每个 {@code Running} pod exec
 * 一条组合命令，判定 shell 可用性（{@code echo __SHELL_OK__}）与是否有运行中的 JVM（{@code jps -q} 出 PID）。
 * 非 Running pod 或 exec 通道故障（无 shell / RBAC 403 / API 不可达）→ hasShell/hasJvm=false（不可诊断）。
 * 探测仅作「可诊断性」标记，非保证；arthas 注入的真实性在 ensure 时再实测（契约 §1）。
 *
 * <p>K8S API 不可达 / RBAC 不足 → fabric8 抛异常，由 {@link K8sToolHandlers} 映射为
 * INVALID_PARAMS + {@code reason:k8s_unreachable/k8s_forbidden}（契约 §1）。
 */
public final class K8sPodExplorer {

    private static final Logger log = LoggerFactory.getLogger(K8sPodExplorer.class);

    /** 单 pod 探测超时（shell+jvm 组合命令，避免悬挂 pod 阻塞 list）。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    /** shell 探测成功标记（与 jvm 探测合并在一条 exec）。 */
    private static final String SHELL_OK_MARKER = "__SHELL_OK__";

    private final K8sExec exec;
    private final io.fabric8.kubernetes.client.KubernetesClient client;

    public K8sPodExplorer(io.fabric8.kubernetes.client.KubernetesClient client) {
        this.client = Objects.requireNonNull(client, "client 不可为空");
        this.exec = new K8sExec(client);
    }

    /** 单 pod 视图（契约 §1 返回结构）。 */
    public record PodInfo(String name, String namespace, boolean ready, boolean hasJvm, boolean hasShell) {
    }

    /** 单 service 视图（契约 §2 返回结构）。 */
    public record ServiceInfo(String name, String namespace, String type, String clusterIp,
                              List<Map<String, Object>> ports) {
    }

    /** 枚举指定 namespace 的 pod（探测 hasJvm/hasShell）。 */
    public List<PodInfo> listPods(String namespace) {
        Objects.requireNonNull(namespace, "namespace 不可为空");
        List<Pod> pods = client.pods().inNamespace(namespace).list().getItems();
        List<PodInfo> out = new ArrayList<>(pods.size());
        for (Pod pod : pods) {
            String name = pod.getMetadata().getName();
            boolean ready = isReady(pod);
            boolean hasShell = false;
            boolean hasJvm = false;
            if (ready) {
                // 组合探测：shell 标记 + jps 出 PID（仅 Running pod 探测，避免 exec 挂在 Pending pod）
                try {
                    K8sExec.ExecResult r = exec.exec(namespace, name, PROBE_TIMEOUT, "sh", "-c",
                            "echo " + SHELL_OK_MARKER
                                    + "; command -v jps >/dev/null 2>&1 && jps -q 2>/dev/null | grep -E '^[0-9]+$' | head -1 || true");
                    String stdout = r.stdout();
                    hasShell = stdout.contains(SHELL_OK_MARKER);
                    hasJvm = hasShell && stdout.lines().anyMatch(K8sPodExplorer::isPureDigits);
                } catch (K8sExecException e) {
                    log.debug("pod 探测失败（{}）→ hasShell/hasJvm=false：{}", name, e.getMessage());
                }
            }
            out.add(new PodInfo(name, namespace, ready, hasJvm, hasShell));
        }
        return out;
    }

    /** 枚举指定 namespace 的 service。 */
    public List<ServiceInfo> listServices(String namespace) {
        Objects.requireNonNull(namespace, "namespace 不可为空");
        List<Service> services = client.services().inNamespace(namespace).list().getItems();
        List<ServiceInfo> out = new ArrayList<>(services.size());
        for (Service svc : services) {
            List<Map<String, Object>> ports = new ArrayList<>();
            List<ServicePort> sps = svc.getSpec() != null ? svc.getSpec().getPorts() : null;
            if (sps != null) {
                for (ServicePort sp : sps) {
                    Map<String, Object> p = new java.util.LinkedHashMap<>();
                    p.put("port", sp.getPort());
                    p.put("nodePort", sp.getNodePort()); // 非 NodePort 类型时为 null
                    ports.add(p);
                }
            }
            out.add(new ServiceInfo(
                    svc.getMetadata().getName(),
                    namespace,
                    svc.getSpec() != null && svc.getSpec().getType() != null ? svc.getSpec().getType() : "ClusterIP",
                    svc.getSpec() != null ? String.valueOf(svc.getSpec().getClusterIP()) : null,
                    ports));
        }
        return out;
    }

    /** pod 是否就绪（所有容器 ready）。 */
    private static boolean isReady(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null
                || pod.getStatus().getContainerStatuses().isEmpty()) {
            return false;
        }
        return pod.getStatus().getContainerStatuses().stream().allMatch(cs -> Boolean.TRUE.equals(cs.getReady()));
    }

    private static boolean isPureDigits(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        for (int i = 0; i < line.length(); i++) {
            if (!Character.isDigit(line.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
```


---

## com/arthas/gateway/orchestration/K8sToolHandlers.java

**文件**：`src/main/java/com/arthas/gateway/orchestration/K8sToolHandlers.java`

```java
package com.arthas.gateway.orchestration;

import com.arthas.gateway.backend.BackendConfigException;
import com.arthas.gateway.config.GatewayProperties;
import com.arthas.gateway.handler.McpErrorCodes;
import com.arthas.gateway.handler.McpJson;
import com.arthas.gateway.tool.ExposedTool;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 3 个 K8S 编排工具的本地处理器（003 特性，契约 §1/§2/§3，R6）。
 *
 * <p>handler <b>自带闭包</b>（在 {@code GatewayMcpServerConfig} bean 构建时绑定），<b>不经 {@code ToolsCallRouter}</b>
 * ——gateway-core 路由零 K8S 感知。分派按工具名：
 * <ul>
 *   <li>{@code k8s.list-pods}：枚举 pod（hasJvm/hasShell 标记）。</li>
 *   <li>{@code k8s.list-services}：枚举 service。</li>
 *   <li>{@code k8s.ensure-arthas-mcp}：原子幂等供给（委托 {@link ArthasProvisioner}）。</li>
 * </ul>
 *
 * <p><b>错误映射</b>（原则五显式传播）：K8S API 不可达 / RBAC 不足 → INVALID_PARAMS +
 * {@code data.reason}∈{@code k8s_unreachable/k8s_forbidden}；ensure 失败 → INVALID_PARAMS +
 * {@code data.status=failed, data.error={reason,stage,message}}（契约 §3）。
 */
public class K8sToolHandlers {

    private static final Logger log = LoggerFactory.getLogger(K8sToolHandlers.class);

    private final K8sPodExplorer explorer;
    private final ArthasProvisioner provisioner;
    private final GatewayProperties props;
    private final Clock clock;

    public K8sToolHandlers(K8sPodExplorer explorer, ArthasProvisioner provisioner, GatewayProperties props) {
        this(explorer, provisioner, props, Clock.systemUTC());
    }

    /** 测试注入时钟（ensure 记录的 createdAt/completedAt 确定性）。 */
    public K8sToolHandlers(K8sPodExplorer explorer, ArthasProvisioner provisioner, GatewayProperties props, Clock clock) {
        this.explorer = Objects.requireNonNull(explorer, "explorer 不可为空");
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner 不可为空");
        this.props = Objects.requireNonNull(props, "props 不可为空");
        this.clock = Objects.requireNonNull(clock, "clock 不可为空");
    }

    /** 分派到具体处理方法（按工具名）。 */
    public CallToolResult handle(ExposedTool tool, CallToolRequest request) {
        return switch (tool.name()) {
            case K8sToolRegistry.LIST_PODS -> listPods(request);
            case K8sToolRegistry.LIST_SERVICES -> listServices(request);
            case K8sToolRegistry.ENSURE_ARTHAS_MCP -> ensureArthasMcp(request);
            default -> throw new IllegalStateException("未知 K8S 编排工具：" + tool.name());
        };
    }

    // ===== k8s.list-pods（契约 §1，K-LP-1） =====

    private CallToolResult listPods(CallToolRequest request) {
        String namespace = namespace(request);
        List<Map<String, Object>> pods;
        try {
            pods = explorer.listPods(namespace).stream().map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.name());
                m.put("namespace", p.namespace());
                m.put("ready", p.ready());
                m.put("hasJvm", p.hasJvm());
                m.put("hasShell", p.hasShell());
                return m;
            }).toList();
        } catch (RuntimeException e) {
            throw k8sApiError("k8s.list-pods", e);
        }
        return McpJson.json(Map.of("pods", pods, "namespace", namespace));
    }

    // ===== k8s.list-services（契约 §2，K-LS-1） =====

    private CallToolResult listServices(CallToolRequest request) {
        String namespace = namespace(request);
        List<Map<String, Object>> services;
        try {
            services = explorer.listServices(namespace).stream().map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", s.name());
                m.put("namespace", s.namespace());
                m.put("type", s.type());
                m.put("clusterIp", s.clusterIp());
                m.put("ports", s.ports());
                return m;
            }).toList();
        } catch (RuntimeException e) {
            throw k8sApiError("k8s.list-services", e);
        }
        return McpJson.json(Map.of("services", services, "namespace", namespace));
    }

    // ===== k8s.ensure-arthas-mcp（契约 §3，K-ENS-*） =====

    private CallToolResult ensureArthasMcp(CallToolRequest request) {
        String server = requireString(request, "server");
        String pod = requireString(request, "pod");
        String namespace = optionalString(request, "namespace", props.getK8s().getNamespace());

        OrchestrationRecord record;
        try {
            record = provisioner.ensure(server, pod, namespace, clock.instant());
        } catch (BackendConfigException e) {
            // register 命名冲突（动态名 ∩ 静态种子 / 同名异 URL）→ name_conflict（K-ENS-9）
            log.warn("ensure 注册失败（name_conflict）：server={} pod={} {}", server, pod, e.getMessage());
            throw ensureFailedError(ArthasProvisioner.deriveLogicalName(server, pod),
                    "name_conflict", "register", e.getMessage());
        } catch (RuntimeException e) {
            // 非预期基础设施故障（如 K8S API 不可达）→ 按可达性映射
            log.error("ensure 非预期异常：server={} pod={}", server, pod, e);
            throw k8sApiError("k8s.ensure-arthas-mcp", e);
        }

        return switch (record.status()) {
            case READY, REUSED -> McpJson.json(readyView(record));
            case FAILED -> throw ensureFailedError(record.logicalName(),
                    record.error().reason(), record.error().phase(), record.error().message());
            case ENSURING -> throw new IllegalStateException("ensure 返回非终态：" + record);
        };
    }

    /** ready/reused 成功视图（契约 §3 返回结构）。 */
    private static Map<String, Object> readyView(OrchestrationRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("target", r.logicalName());
        m.put("status", r.status().name().toLowerCase());
        m.put("mcpUrl", r.mcpUrl());
        m.put("namespace", r.namespace());
        return m;
    }

    /** ensure 失败 → INVALID_PARAMS + data{target, status:failed, error{reason,stage,message}}（契约 §3）。 */
    private static McpError ensureFailedError(String target, String reason, String stage, String message) {
        return McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message("ensure-arthas-mcp 失败：target=" + target + " reason=" + reason + " stage=" + stage
                        + "（" + message + "）")
                .data(Map.of(
                        "target", target,
                        "status", "failed",
                        "error", Map.of("reason", reason, "stage", stage, "message", message)))
                .build();
    }

    /** K8S API 故障 → INVALID_PARAMS + data.reason=k8s_forbidden(403)/k8s_unreachable（契约 §1/§3）。 */
    private static McpError k8sApiError(String tool, RuntimeException e) {
        String reason;
        if (e instanceof KubernetesClientException kce && kce.getCode() == 403) {
            reason = "k8s_forbidden";
        } else {
            reason = "k8s_unreachable";
        }
        log.warn("{} K8S API 故障 reason={}：{}", tool, reason, e.toString());
        return McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message(tool + " 访问集群失败：" + reason + "（" + e.getMessage() + "）")
                .data(Map.of("reason", reason))
                .build();
    }

    // ===== 参数解析 =====

    private String namespace(CallToolRequest request) {
        return optionalString(request, "namespace", props.getK8s().getNamespace());
    }

    private static String requireString(CallToolRequest request, String key) {
        String v = optionalString(request, key, null);
        if (v == null) {
            throw McpError.builder(McpErrorCodes.INVALID_PARAMS)
                    .message("参数缺失或为空：" + key)
                    .build();
        }
        return v;
    }

    private static String optionalString(CallToolRequest request, String key, String fallback) {
        Object v = request.arguments() == null ? null : request.arguments().get(key);
        if (v instanceof String s && !s.isBlank()) {
            return s;
        }
        return fallback;
    }
}
```


---

## com/arthas/gateway/orchestration/K8sToolRegistry.java

**文件**：`src/main/java/com/arthas/gateway/orchestration/K8sToolRegistry.java`

```java
package com.arthas.gateway.orchestration;

import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 3 个 K8S 编排工具规格（003 特性，契约 §1/§2/§3，research.md R6）。
 *
 * <p>不可变快照：{@code k8s.list-pods} / {@code k8s.list-services} / {@code k8s.ensure-arthas-mcp}，
 * 与既有 35 工具合并进同一 MCP server（{@code tools/list}=38）。三者 {@code routingMode=GATEWAY_LOCAL}（无
 * {@code target} 参数），但 <b>handler 自带闭包、不经 {@code ToolsCallRouter}</b>（gateway-core 路由零 K8S 感知，
 * R6）—— {@code routingMode} 字段仅供 {@link ExposedTool} 完整性，不参与实际路由。
 *
 * <p>工具规格始终注册（即便 kubeconfig 缺失、CI 无 k3s）—— 故 {@code tools/list} 恒为 38（回归守护 T029）；
 * handler 执行依赖真实 kubeconfig（缺时返「K8S 编排未启用」明确错误）。
 */
public final class K8sToolRegistry {

    /** 工具全名常量（handler 分派 + 契约断言引用）。 */
    public static final String LIST_PODS = "k8s.list-pods";
    public static final String LIST_SERVICES = "k8s.list-services";
    public static final String ENSURE_ARTHAS_MCP = "k8s.ensure-arthas-mcp";

    private K8sToolRegistry() {
    }

    /** 3 个编排工具的不可变规格。 */
    public static List<ExposedTool> tools() {
        return List.of(
                new ExposedTool(
                        LIST_PODS,
                        "枚举指定 K8S 命名空间的 pod，含 hasJvm/hasShell 标记（供筛选可被 arthas 诊断的 pod）",
                        objectSchema(Map.of("namespace", stringProp("K8S 命名空间，缺省 default")), List.of()),
                        null,
                        RoutingMode.GATEWAY_LOCAL),
                new ExposedTool(
                        LIST_SERVICES,
                        "枚举指定 K8S 命名空间的 service",
                        objectSchema(Map.of("namespace", stringProp("K8S 命名空间，缺省 default")), List.of()),
                        null,
                        RoutingMode.GATEWAY_LOCAL),
                new ExposedTool(
                        ENSURE_ARTHAS_MCP,
                        "对指定 K8S pod 原子幂等完成：注入 arthas（exec attach 该 pod JVM）+ 启动绑 0.0.0.0 的 arthas MCP + NodePort 暴露 + 内部健康检查 + 动态注册进网关；返回可诊断的 target 名",
                        objectSchema(
                                Map.of(
                                        "server", stringProp("Linux 服务器名（逻辑名前缀 + 来源标识）"),
                                        "pod", stringProp("目标 pod 名（须含 shell+java+JVM）"),
                                        "namespace", stringProp("K8S 命名空间，缺省 default")),
                                List.of("server", "pod")),
                        null,
                        RoutingMode.GATEWAY_LOCAL));
    }

    /** 构造标准 inputSchema（object + properties + required + additionalProperties:false）。 */
    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        schema.put("required", List.copyOf(required));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> stringProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        return p;
    }
}
```


---

## com/arthas/gateway/orchestration/NodePortExposer.java

**文件**：`src/main/java/com/arthas/gateway/orchestration/NodePortExposer.java`

```java
package com.arthas.gateway.orchestration;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * NodePort 暴露器（003 特性，契约 §3，research.md R5）：为目标 pod 打唯一 label + 建（或复用）NodePort Service，
 * 产出可达 {@code mcpUrl=http://<nodeIP>:<nodePort>}（arthas MCP 根 URL，无 /mcp）。
 *
 * <p><b>幂等</b>：Service 名确定性派生自 logicalName（{@code arthas-mcp-<sanitize(logical)>}），已存在则复用其
 * NodePort，不重建（K-ENS-2 零副作用复用的前提）。pod label 同名覆盖（幂等）。
 *
 * <p><b>selector 精确命中</b>：业务 pod 通常无唯一 label，打 {@code arthas-mcp-gateway/target=<labelValue>}
 * 后 selector 精确命中该 pod（设计 §4.1）；pod 重启 IP 变化时 Service 自动重解析（持续可达新 pod）。
 *
 * <p><b>nodeIP 解析</b>：从集群节点 status.addresses 取（优先 ExternalIP，其次 InternalIP）。k3s 单节点测试床的
 * InternalIP=192.168.31.92（网关经 LAN 可达，验证"集群外运行"拓扑）。
 *
 * <p>label value 与 service 名须 K8S 合法：sanitize 把非法字符替换为 '-'、小写、截断。
 */
public final class NodePortExposer {

    private static final Logger log = LoggerFactory.getLogger(NodePortExposer.class);

    /** pod label key（selector 匹配键）。 */
    public static final String TARGET_LABEL_KEY = "arthas-mcp-gateway/target";
    /** service 名前缀。 */
    private static final String SERVICE_PREFIX = "arthas-mcp-";

    private final KubernetesClient client;

    public NodePortExposer(KubernetesClient client) {
        this.client = Objects.requireNonNull(client, "client 不可为空");
    }

    /** 暴露结果（mcpUrl + service 引用，供 Provisioner 记录与注册）。 */
    public record ExposeResult(String serviceName, Integer nodePort, String mcpUrl, String serviceRef) {
    }

    /**
     * 暴露 pod 的 mcpPort 为 NodePort。
     *
     * @param namespace   命名空间
     * @param pod         目标 pod 名
     * @param logicalName 逻辑名（target 名，派生 label value / service 名的来源）
     * @param mcpPort     pod 内 arthas MCP 监听端口（NodePort targetPort）
     * @return 暴露结果（含可达 mcpUrl）
     */
    public ExposeResult expose(String namespace, String pod, String logicalName, int mcpPort) {
        Objects.requireNonNull(namespace, "namespace 不可为空");
        Objects.requireNonNull(pod, "pod 不可为空");
        Objects.requireNonNull(logicalName, "logicalName 不可为空");
        String labelValue = sanitizeLabelValue(logicalName);
        String serviceName = sanitizeServiceName(SERVICE_PREFIX + labelValue);

        // 1. label pod（幂等覆盖）
        labelPod(namespace, pod, labelValue);

        // 2. create-or-get NodePort Service（selector 命中该 label）
        int nodePort = ensureNodePortService(namespace, serviceName, labelValue, mcpPort);

        // 3. 解析 nodeIP → mcpUrl
        String nodeIp = resolveNodeIp();
        String mcpUrl = "http://" + nodeIp + ":" + nodePort;
        String serviceRef = serviceName + "/" + nodePort;
        log.info("NodePort 已暴露：{}/{} → {}（service={} nodePort={}）",
                namespace, pod, mcpUrl, serviceName, nodePort);
        return new ExposeResult(serviceName, nodePort, mcpUrl, serviceRef);
    }

    /** 删除供给时建的 NodePort Service（清理副作用，幂等）。 */
    public boolean deleteService(String namespace, String serviceName) {
        try {
            // fabric8 7.x delete() 返回 List<StatusDetails>：非空表示实际删除了资源
            return !client.services().inNamespace(namespace).withName(serviceName).delete().isEmpty();
        } catch (RuntimeException e) {
            log.warn("删除 NodePort Service 失败（忽略，幂等清理）：{}", serviceName, e);
            return false;
        }
    }

    /** label pod（幂等；已含同值则无操作）。 */
    private void labelPod(String namespace, String pod, String labelValue) {
        Pod cur = client.pods().inNamespace(namespace).withName(pod).get();
        if (cur == null) {
            throw new IllegalStateException("pod 不存在：" + namespace + "/" + pod);
        }
        String existing = cur.getMetadata().getLabels() != null
                ? cur.getMetadata().getLabels().get(TARGET_LABEL_KEY) : null;
        if (labelValue.equals(existing)) {
            return; // 幂等：已含同值
        }
        Pod updated = new PodBuilder(cur).editMetadata()
                .addToLabels(TARGET_LABEL_KEY, labelValue)
                .endMetadata()
                .build();
        client.pods().inNamespace(namespace).resource(updated).update();
    }

    /** create-or-get NodePort Service；返回 NodePort（K8S 在范围内自动分配）。 */
    private int ensureNodePortService(String namespace, String serviceName, String labelValue, int mcpPort) {
        Service existing = client.services().inNamespace(namespace).withName(serviceName).get();
        if (existing != null) {
            Integer np = firstNodePort(existing);
            if (np != null) {
                return np; // 复用既有 NodePort（幂等）
            }
        }
        Service svc = new ServiceBuilder()
                .withNewMetadata().withName(serviceName).endMetadata()
                .withNewSpec()
                .withType("NodePort")
                .addToSelector(TARGET_LABEL_KEY, labelValue)
                .addNewPort()
                .withPort(mcpPort)
                .withTargetPort(new IntOrString(mcpPort))
                .endPort()
                .endSpec()
                .build();
        client.services().inNamespace(namespace).resource(svc).create();
        Service created = client.services().inNamespace(namespace).withName(serviceName).get();
        Integer np = created != null ? firstNodePort(created) : null;
        if (np == null) {
            throw new IllegalStateException("NodePort 分配失败（service=" + serviceName + "）：未取到 nodePort");
        }
        return np;
    }

    private static Integer firstNodePort(Service svc) {
        if (svc.getSpec() == null || svc.getSpec().getPorts() == null || svc.getSpec().getPorts().isEmpty()) {
            return null;
        }
        return svc.getSpec().getPorts().get(0).getNodePort();
    }

    /** 解析集群 nodeIP（优先 ExternalIP，其次 InternalIP）。 */
    private String resolveNodeIp() {
        List<Node> nodes = client.nodes().list().getItems();
        String internal = null;
        for (Node n : nodes) {
            List<NodeAddress> addrs = n.getStatus() != null ? n.getStatus().getAddresses() : null;
            if (addrs == null) {
                continue;
            }
            for (NodeAddress a : addrs) {
                if ("ExternalIP".equals(a.getType()) && a.getAddress() != null) {
                    return a.getAddress();
                }
                if ("InternalIP".equals(a.getType()) && a.getAddress() != null && internal == null) {
                    internal = a.getAddress();
                }
            }
        }
        if (internal != null) {
            return internal;
        }
        throw new IllegalStateException("集群无可达 nodeIP（未找到 ExternalIP/InternalIP）");
    }

    /** sanitize 为合法 label value（小写、非法字符→'-'、≤63）。 */
    static String sanitizeLabelValue(String logicalName) {
        return sanitizeK8sName(logicalName, 63);
    }

    /** sanitize 为合法 service name（DNS-subdomain：小写、非法字符→'-'、≤253）。 */
    static String sanitizeServiceName(String name) {
        return sanitizeK8sName(name, 253);
    }

    private static String sanitizeK8sName(String s, int maxLen) {
        String lower = s.toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lower.length() && sb.length() < maxLen; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.') {
                sb.append(c);
            } else {
                sb.append('-');
            }
        }
        // 去除首尾非字母数字
        String out = sb.toString().replaceAll("^[^a-z0-9]+", "").replaceAll("[^a-z0-9]+$", "");
        if (out.isEmpty()) {
            out = "x";
        }
        return out;
    }
}
```


---

## com/arthas/gateway/orchestration/OrchestrationRecord.java

**文件**：`src/main/java/com/arthas/gateway/orchestration/OrchestrationRecord.java`

```java
package com.arthas.gateway.orchestration;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次 {@code ensure-arthas-mcp} 供给的结构化可观测记录（data-model §8，宪法原则五：网关可被诊断）。
 *
 * <p>不可变值对象；状态转换以"返回新记录"的拷贝风格实现（{@link #ready}/{@link #reused}/
 * {@link #failed}），保留 logicalName/server/pod/namespace/createdAt 不变。
 *
 * <p>{@code createdAt} 为<b>传入</b>瞬时量（非进程内取时），与 001 {@code GatewayTask.createdAt} 一致，
 * 便于确定性测试。状态机见 {@link Status}（data-model §9）。
 *
 * @param logicalName  逻辑名 {@code {server}-{pod}}（与注册的 target 名一致）
 * @param server       供给来源服务器名
 * @param pod          目标 pod 名
 * @param namespace    K8S namespace（缺省 default）
 * @param mcpUrl       暴露端点 {@code http://<nodeIP>:<nodePort>}（arthas MCP 根 URL，无 /mcp）；未暴露前 null
 * @param serviceRef   NodePort Service 引用（{@code name/nodePort}）；未创建前 null
 * @param status       供给状态（状态机）
 * @param error        失败原因（仅 failed 时；含 phase/reason/message）
 * @param createdAt    供给发起时间（传入瞬时量）
 * @param completedAt  完成/失败时间（仅终态非 null）
 */
public record OrchestrationRecord(
        String logicalName,
        String server,
        String pod,
        String namespace,
        String mcpUrl,
        String serviceRef,
        Status status,
        Error error,
        Instant createdAt,
        Instant completedAt) {

    /** 供给状态机（data-model §9）。 */
    public enum Status {
        /** 供给进行中（注入/暴露/健康检查/注册），非终态。 */
        ENSURING(false),
        /** 新供给完成、已注册且健康，终态。 */
        READY(true),
        /** 命中幂等复用（注册表已有且健康，零副作用），终态。 */
        REUSED(true),
        /** 任一子步失败且未注册，终态。 */
        FAILED(true);

        private final boolean terminal;

        Status(boolean terminal) {
            this.terminal = terminal;
        }

        /** 是否终态（ready/reused/failed 为终态，ensuring 非终态）。 */
        public boolean isTerminal() {
            return terminal;
        }
    }

    /** 失败原因（data-model §8 {@code error}，仅 failed 时）。 */
    public record Error(String phase, String reason, String message) {
        public Error {
            Objects.requireNonNull(phase, "error.phase 不可为空");
            Objects.requireNonNull(reason, "error.reason 不可为空");
            Objects.requireNonNull(message, "error.message 不可为空");
        }
    }

    /** 起始态工厂：ensuring（非终态），{@code createdAt} 为传入瞬时量。 */
    public static OrchestrationRecord ensuring(String logicalName, String server, String pod,
                                               String namespace, Instant createdAt) {
        return new OrchestrationRecord(logicalName, server, pod, namespace,
                null, null, Status.ENSURING, null, createdAt, null);
    }

    /**
     * 记录已暴露的副作用（Service 已建、mcpUrl 已知），仍处 ensuring（健康检查/注册前）。
     *
     * <p>§4.1：ensure 失败时已打的 pod label / 已建的 Service 作为可清理副作用保留进 failed 记录供运维追溯。
     */
    public OrchestrationRecord withExposed(String mcpUrl, String serviceRef) {
        return new OrchestrationRecord(logicalName, server, pod, namespace,
                mcpUrl, serviceRef, status, error, createdAt, completedAt);
    }

    /** 转换到 ready（全部子步成功、已注册且健康），终态。 */
    public OrchestrationRecord ready(String mcpUrl, String serviceRef, Instant completedAt) {
        return new OrchestrationRecord(logicalName, server, pod, namespace,
                mcpUrl, serviceRef, Status.READY, null, createdAt, completedAt);
    }

    /** 转换到 reused（幂等复用命中，零副作用），终态。 */
    public OrchestrationRecord reused(String mcpUrl, String serviceRef, Instant completedAt) {
        return new OrchestrationRecord(logicalName, server, pod, namespace,
                mcpUrl, serviceRef, Status.REUSED, null, createdAt, completedAt);
    }

    /** 转换到 failed（任一子步失败、未注册），终态；保留已暴露副作用字段。 */
    public OrchestrationRecord failed(Error error, Instant completedAt) {
        return new OrchestrationRecord(logicalName, server, pod, namespace,
                mcpUrl, serviceRef, Status.FAILED, error, createdAt, completedAt);
    }
}
```


---

## com/arthas/gateway/orchestration/OrchestrationRecordStore.java

**文件**：`src/main/java/com/arthas/gateway/orchestration/OrchestrationRecordStore.java`

```java
package com.arthas.gateway.orchestration;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 供给记录内存态（data-model §8）。
 *
 * <p>按 {@code logicalName} 覆盖最新状态（一次 ensure 全程可能经历 ensuring→ready/reused/failed，
 * 仅终态对外有意义，但中间态亦写入便于近实时观测）。进程级、优雅关闭（MVP 不持久化）。
 *
 * <p>线程安全：内部 {@link ConcurrentHashMap}。供运维经结构化日志/未来 portal 查询一次 ensure 的来龙去脉。
 */
public class OrchestrationRecordStore {

    private final ConcurrentHashMap<String, OrchestrationRecord> byLogicalName = new ConcurrentHashMap<>();

    /** 写入/覆盖一条供给记录（按 logicalName 覆盖最新状态）。 */
    public void record(OrchestrationRecord r) {
        byLogicalName.put(r.logicalName(), r);
    }

    /** 取单个 logicalName 的最新记录（缺失返 empty）。 */
    public Optional<OrchestrationRecord> get(String logicalName) {
        return Optional.ofNullable(byLogicalName.get(logicalName));
    }

    /** 当前全部记录的不可变快照（无序保证）。 */
    public List<OrchestrationRecord> list() {
        return List.copyOf(byLogicalName.values());
    }

    /** 清空（测试/重置用）。 */
    public void clear() {
        byLogicalName.clear();
    }
}
```


---

## com/arthas/gateway/task/AsyncTaskExecutor.java

**文件**：`src/main/java/com/arthas/gateway/task/AsyncTaskExecutor.java`

```java
package com.arthas.gateway.task;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 异步任务执行器（方案 C，data-model.md §6 / gateway-tools-contract.md §5，T033）。
 *
 * <p>对 5 个 optional 工具（watch/trace/stack/tt/monitor）：submit <b>立即</b>创建 WORKING 任务并返回
 * （G-ASYNC-1），后台虚拟线程对后端发<b>同步</b> {@code tools/call}（<b>不带</b> task 字段，走后端自动轮询路①，
 * 参考后端接入契约 §5.b），阻塞兜底至 {@code backendTimeout}（默认 11min，&gt; 后端 10min 上限）。
 *
 * <h3>后台终态映射</h3>
 * <ul>
 *   <li>正常返回（含 {@code isError=true} 的业务错误）→ {@link GatewayTask#markCompleted} 原样保留（G-TG-2）。</li>
 *   <li>超时（兜底超时）→ {@code markFailed(BACKEND_TIMEOUT)} 并中断后台。</li>
 *   <li>基础设施异常（连接拒绝/IO/initialize 失败等）→ {@code markFailed(BACKEND_UNREACHABLE)}。</li>
 *   <li>cancel 中断 → {@code markCancelled}（G-TC-1）。</li>
 * </ul>
 *
 * <h3>设计与可测性</h3>
 * <ul>
 *   <li><b>DIP 缝</b>：{@code submit} 接受注入的 {@link Callable}（{@code backendWork}）——生产由路由层
 *       （T034）闭包真实 {@code entry.client().callTool}；测试注入阻塞 callable 测编排契约（非 arthas 桩，
 *       见 {@code AsyncTaskExecutorTest} 类注释）。后端真实响应保真度由 IT（T030/T035）覆盖。</li>
 *   <li><b>超时实现</b>：单虚拟线程池嵌套 submit——外层 supervisor 线程 {@code worker.get(timeout)} 等待
 *       内层 worker 的后端调用；超时/取消时 {@code worker.cancel(true)} 中断后端调用。</li>
 *   <li><b>taskId 生成</b>：{@code "t-" + 6 hex}（SecureRandom），注入 {@code Supplier} 便于确定性测试。</li>
 *   <li><b>cancel 跟踪</b>：{@code taskId → supervisorFuture} 映射，cancel 时中断 supervisor。</li>
 * </ul>
 *
 * <p><b>002 整改（P0-1/P0-2 + P2-4）</b>：{@code submit} 增 {@code onTerminal} 参数——任务到达任意终态或提交失败
 * （外层/内层 {@code pool.submit} 被拒）时调用恰好一次，由路由层传 {@code entry::releaseSlot} 释放 per-target 槽
 * （US4 再叠加全局背压释放）。外层拒绝时额外 {@code store.remove} 防僵尸 WORKING。熔断/限流/STATELESS 准入
 * 由 {@code BackendEntry.admit}（路由层 submitAsync 提交前调用）负责，{@code backendWork} 闭包内调
 * {@code entry.invoke}（含 initialize CAS + 故障分类 + 熔断驱动，P1-3）——执行器自身不感知熔断/槽。
 * <b>全局背压(P2-4/FR-010)</b>:执行器持跨 target 累计 {@code globalInflight} 计数,{@code submit} 前
 * {@code acquireGlobalInflight}(cap = 配置或动态默认=后端数×5),终态/提交失败配对 {@code releaseGlobalInflight}——
 * 超限抛 {@link GlobalConcurrencyLimitException},路由层翻译为 {@code global_concurrency_limit}。
 */
public final class AsyncTaskExecutor {

    private final TaskStore store;
    private final Duration callTimeout;
    private final Supplier<Instant> clock;
    private final Supplier<String> taskIdGenerator;
    private final ExecutorService pool;
    private final Supplier<Integer> globalInflightCap;
    private final AtomicInteger globalInflight = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Future<?>> supervisorFutures = new ConcurrentHashMap<>();

    /**
     * 全参构造(测试用:注入时钟、taskId 生成器、线程池与全局背压上限供应器以确定性)。
     *
     * @param store             任务存储
     * @param callTimeout       后台阻塞兜底超时(11min)
     * @param clock             时间源(任务 createdAt / completedAt)
     * @param taskIdGenerator   taskId 生成器({@code "t-<hex>"})
     * @param pool              后台线程池
     * @param globalInflightCap 全局在途上限供应器(每次 submit 求值;返回值即 cap,
     *                          动态默认=后端数×5)。{@code Integer.MAX_VALUE} 表示不限。
     */
    public AsyncTaskExecutor(TaskStore store, Duration callTimeout,
                             Supplier<Instant> clock, Supplier<String> taskIdGenerator,
                             ExecutorService pool, Supplier<Integer> globalInflightCap) {
        this.store = Objects.requireNonNull(store, "store 不可为空");
        this.callTimeout = requirePositive(callTimeout, "callTimeout");
        this.clock = Objects.requireNonNull(clock, "clock 不可为空");
        this.taskIdGenerator = Objects.requireNonNull(taskIdGenerator, "taskIdGenerator 不可为空");
        this.pool = Objects.requireNonNull(pool, "pool 不可为空");
        this.globalInflightCap = Objects.requireNonNull(globalInflightCap, "globalInflightCap 不可为空");
    }

    /** 5 参重载:默认线程池 + <b>不限</b>全局背压(保留既有测试调用点;生产装配用 6 参或 3 参)。 */
    public AsyncTaskExecutor(TaskStore store, Duration callTimeout,
                             Supplier<Instant> clock, Supplier<String> taskIdGenerator, ExecutorService pool) {
        this(store, callTimeout, clock, taskIdGenerator, pool, () -> Integer.MAX_VALUE);
    }

    public AsyncTaskExecutor(TaskStore store, Duration callTimeout,
                             Supplier<Instant> clock, Supplier<String> taskIdGenerator) {
        this(store, callTimeout, clock, taskIdGenerator, Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * 生产便利构造:默认真实时钟 + 随机 taskId + 虚拟线程池 + 注入全局背压上限供应器。
     *
     * @param globalInflightCap 全局在途上限供应器(配置值或动态默认=后端数×5,由装配层求值)
     */
    public AsyncTaskExecutor(TaskStore store, Duration callTimeout, Supplier<Integer> globalInflightCap) {
        this(store, callTimeout, Instant::now, defaultTaskIdGenerator(),
                Executors.newVirtualThreadPerTaskExecutor(), globalInflightCap);
    }

    /** 生产便利构造：默认真实时钟 + 随机 taskId。 */
    public AsyncTaskExecutor(TaskStore store, Duration callTimeout) {
        this(store, callTimeout, Instant::now, defaultTaskIdGenerator());
    }

    /**
     * 提交一个异步任务：立即创建 WORKING 任务并返回，后台虚拟线程执行 {@code backendWork}。
     *
     * <p>遗留 3 参重载（onTerminal 为空操作）——保留既有调用点（如 {@code AsyncTaskExecutorTest}）兼容。
     *
     * @param toolName   工具名（watch/trace/stack/tt/monitor）
     * @param target     目标 JVM 逻辑名
     * @param backendWork 后端同步调用（路由层闭包 {@code entry.client().callTool}）；返回结果原样存 completed
     * @return 已入存储的 WORKING 任务（taskId 已分配）
     */
    public GatewayTask submit(String toolName, String target, Callable<CallToolResult> backendWork) {
        return submit(toolName, target, backendWork, () -> {
            // no-op：遗留调用点无槽/背压需释放
        });
    }

    /**
     * 提交一个异步任务（带 {@code onTerminal}，002 整改 P0-1/P0-2）。
     *
     * <p>任务到达<b>任意终态</b>（完成/超时/取消/失败）或<b>提交失败</b>（外层/内层拒绝）时，
     * {@code onTerminal} 被调用<b>恰好一次</b>——由 {@code ToolsCallRouter} 传 {@code entry::releaseSlot}
     * （释放 per-target 槽，US4 再叠加全局背压释放）。外层 {@code pool.submit} 被拒时，移除刚入存储的僵尸任务
     * 并调用 {@code onTerminal}（P0-1 无僵尸 + P0-2 释放槽）。
     *
     * @param onTerminal 终态/提交失败回调（释放槽/背压）
     * @return 已入存储的 WORKING 任务（taskId 已分配）
     */
    public GatewayTask submit(String toolName, String target, Callable<CallToolResult> backendWork, Runnable onTerminal) {
        Objects.requireNonNull(toolName, "toolName 不可为空");
        Objects.requireNonNull(target, "target 不可为空");
        Objects.requireNonNull(backendWork, "backendWork 不可为空");
        Objects.requireNonNull(onTerminal, "onTerminal 不可为空");

        // 全局背压(P2-4/FR-010):跨 target 累计 inflight 上限。失败抛 GlobalConcurrencyLimitException
        // (此时未创建任务/未提交后台;per-target 槽由路由层 admit 已取,其释放由路由层 catch 负责)。
        acquireGlobalInflight();

        String taskId = taskIdGenerator.get();
        GatewayTask task = new GatewayTask(taskId, toolName, target, clock.get(), clock);
        store.put(task);

        Future<?> supervisor;
        try {
            supervisor = pool.submit(() -> orchestrate(task, backendWork, onTerminal));
        } catch (RejectedExecutionException ree) {
            // 外层提交被拒（池已关）：移除僵尸 + 释放槽/背压 + 原样抛（P0-1/P0-2）
            store.remove(taskId);
            onTerminal.run();
            releaseGlobalInflight();
            throw ree;
        }
        supervisorFutures.put(taskId, supervisor);
        return task;
    }

    /** 后台编排：submit 后台调用 + 兜底超时 + 终态映射 + onTerminal（任一终态释放槽/背压，P0-2）。 */
    private void orchestrate(GatewayTask task, Callable<CallToolResult> backendWork, Runnable onTerminal) {
        Future<CallToolResult> worker = null;
        try {
            worker = pool.submit(backendWork);
            CallToolResult result = worker.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
            task.markCompleted(result); // isError=true 原样保留（G-TG-2）；终态返 false（幂等）
        } catch (RejectedExecutionException ree) {
            // 内层 work 提交被拒（池已关）：标 FAILED（无僵尸，P0-1）
            task.markFailed(new TaskError(
                    TaskError.REASON_BACKEND_UNREACHABLE,
                    "后台执行池拒绝提交（已关闭）：" + ree.getMessage()));
        } catch (TimeoutException te) {
            worker.cancel(true);
            task.markFailed(new TaskError(
                    TaskError.REASON_BACKEND_TIMEOUT,
                    "后端 " + callTimeout + " 内未返回（兜底超时）"));
        } catch (ExecutionException ee) {
            worker.cancel(true);
            task.markFailed(toTaskError(ee.getCause()));
        } catch (InterruptedException ie) {
            // cancel 触发的中断：中断后台 worker，标 cancelled（若已终态则幂等返 false）
            Thread.currentThread().interrupt();
            worker.cancel(true);
            task.markCancelled();
        } finally {
            supervisorFutures.remove(task.taskId());
            onTerminal.run();            // 任一终态/内层拒绝都释放 per-target 槽(P0-2)
            releaseGlobalInflight();     // 任一终态都释放全局背压(P2-4)
        }
    }

    /**
     * 取消任务：标 CANCELLED 并中断后台。仅 WORKING 时生效（G-TC-1）；终态任务返 false（G-TC-2 幂等）。
     *
     * @return true=本次 WORKING→CANCELLED；false=已终态（未转换）
     */
    public boolean cancel(GatewayTask task) {
        Objects.requireNonNull(task, "task 不可为空");
        boolean transitioned = task.markCancelled();
        if (transitioned) {
            Future<?> supervisor = supervisorFutures.get(task.taskId());
            if (supervisor != null) {
                supervisor.cancel(true); // 中断 supervisor → orchestrate 捕获 InterruptedException
            }
        }
        return transitioned;
    }

    /** 暴露 TaskStore 供 task-get/task-list handler 查询。 */
    public TaskStore store() {
        return store;
    }

    /** 当前全局在途异步任务数(可观测/测试用)。 */
    public int currentGlobalInflight() {
        return globalInflight.get();
    }

    /**
     * 全局背压 acquire(跨 target 累计上限,P2-4/FR-010)。
     *
     * <p>{@code incrementAndGet} 原子自增;若结果 &gt; cap 则回滚并抛 {@link GlobalConcurrencyLimitException}。
     * 任意瞬间至多 cap 个任务通过(超额者立即回滚),无丢失更新。cap&le;0(注册表空,无后端)直接拒。
     */
    private void acquireGlobalInflight() {
        int cap = globalInflightCap.get();
        if (cap <= 0) {
            throw new GlobalConcurrencyLimitException(cap);
        }
        if (globalInflight.incrementAndGet() > cap) {
            globalInflight.decrementAndGet();
            throw new GlobalConcurrencyLimitException(cap);
        }
    }

    /** 全局背压 release(终态/提交失败配对调用,见 orchestrate finally 与 submit 外层拒绝)。 */
    private void releaseGlobalInflight() {
        globalInflight.decrementAndGet();
    }

    /** 关闭后台线程池（Spring 容器关闭时调用）。 */
    public void close() {
        pool.shutdownNow();
    }

    /** 异常 → TaskError：基础设施故障统一归 backend_unreachable（Phase 5 可细化熔断 reason）。 */
    private static TaskError toTaskError(Throwable cause) {
        Throwable c = cause != null ? cause : new RuntimeException("unknown");
        return new TaskError(TaskError.REASON_BACKEND_UNREACHABLE, c.getClass().getSimpleName() + ": " + c.getMessage());
    }

    private static Duration requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name + " 不可为空");
        if (d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(name + " 必须为正");
        }
        return d;
    }

    /** 默认 taskId 生成：{@code "t-" + 6 hex}（SecureRandom，约 1600 万空间，MVP 单后端足够）。 */
    private static Supplier<String> defaultTaskIdGenerator() {
        SecureRandom rng = new SecureRandom();
        HexFormat hex = HexFormat.of();
        return () -> {
            byte[] b = new byte[3];
            rng.nextBytes(b);
            return "t-" + hex.formatHex(b);
        };
    }
}
```


---

## com/arthas/gateway/task/GatewayTask.java

**文件**：`src/main/java/com/arthas/gateway/task/GatewayTask.java`

```java
package com.arthas.gateway.task;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 异步诊断任务实体（方案 C，data-model.md §6 / gateway-tools-contract.md §5，T031）。
 *
 * <p>由 5 个 optional 工具（watch/trace/stack/tt/monitor）经 {@code AsyncTaskExecutor} 提交创建，
 * 初始 {@link TaskState#WORKING}；后台虚拟线程对后端发同步 {@code tools/call}（阻塞兜底），完成后转换终态。
 *
 * <p><b>线程安全</b>：后台写线程（markCompleted/markFailed）与 task-get/task-cancel 读线程并发，
 * 故转换方法 {@code synchronized}（原子终态检查 + 赋值）；可变字段 {@code volatile} 保证读可见性。
 *
 * <p><b>终态不可逆</b>：转换方法仅在 WORKING 时生效并返 true；已终态则返 false（不覆盖）——
 * 兼容 task-cancel 与后台迟到完成的竞争（先到者赢，后者返 false）。
 *
 * <p><b>G-TG-2</b>：后端 {@code isError=true} 是正常业务响应，经 {@link #markCompleted} 原样保留
 * （status=COMPLETED、result.isError=true），<b>不</b>转 failed——failed 仅用于基础设施故障。
 */
public final class GatewayTask {

    private final String taskId;
    private final String toolName;
    private final String target;
    private final Instant createdAt;
    private final Supplier<Instant> clock;

    private volatile TaskState status = TaskState.WORKING;
    private volatile CallToolResult result;
    private volatile TaskError error;
    private volatile Instant completedAt;

    /**
     * 构造一个 WORKING 任务。
     *
     * @param taskId    任务 id（如 {@code t-7f3a9c}）
     * @param toolName  触发工具名（watch/trace/stack/tt/monitor）
     * @param target    目标 JVM 逻辑名
     * @param createdAt 创建时间（注入便于测试确定性）
     * @param clock     终态时间源（注入便于 {@code TaskStore} TTL 测试确定性；生产传 {@code Instant::now}）
     */
    public GatewayTask(String taskId, String toolName, String target, Instant createdAt, Supplier<Instant> clock) {
        this.taskId = Objects.requireNonNull(taskId, "taskId 不可为空");
        this.toolName = Objects.requireNonNull(toolName, "toolName 不可为空");
        this.target = Objects.requireNonNull(target, "target 不可为空");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不可为空");
        this.clock = Objects.requireNonNull(clock, "clock 不可为空");
    }

    /** 标记成功完成：原样保留后端结果（含 isError=true，G-TG-2）。终态后返 false。 */
    public synchronized boolean markCompleted(CallToolResult result) {
        if (status != TaskState.WORKING) {
            return false;
        }
        this.result = Objects.requireNonNull(result, "result 不可为空");
        this.completedAt = clock.get();
        this.status = TaskState.COMPLETED;
        return true;
    }

    /** 标记基础设施失败（超时/不可达/熔断）。终态后返 false。 */
    public synchronized boolean markFailed(TaskError error) {
        if (status != TaskState.WORKING) {
            return false;
        }
        this.error = Objects.requireNonNull(error, "error 不可为空");
        this.completedAt = clock.get();
        this.status = TaskState.FAILED;
        return true;
    }

    /** 标记取消（task-cancel）。终态后返 false（幂等由调用方据返回值处理）。 */
    public synchronized boolean markCancelled() {
        if (status != TaskState.WORKING) {
            return false;
        }
        this.completedAt = clock.get();
        this.status = TaskState.CANCELLED;
        return true;
    }

    public String taskId() {
        return taskId;
    }

    public String toolName() {
        return toolName;
    }

    public String target() {
        return target;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public TaskState status() {
        return status;
    }

    /** 后端结果（仅 COMPLETED 时非空；含 isError 原样）。 */
    public CallToolResult result() {
        return result;
    }

    /** 失败原因（仅 FAILED 时非空）。 */
    public TaskError error() {
        return error;
    }

    /** 进入终态的时间（非 WORKING 时非空）。 */
    public Instant completedAt() {
        return completedAt;
    }

    /** 是否终态（非 WORKING）。 */
    public boolean isTerminal() {
        return status.isTerminal();
    }
}
```


---

## com/arthas/gateway/task/GlobalConcurrencyLimitException.java

**文件**：`src/main/java/com/arthas/gateway/task/GlobalConcurrencyLimitException.java`

```java
package com.arthas.gateway.task;

/**
 * 全局在途异步任务上限越界(002 整改 · P2-4/FR-010)。
 *
 * <p>{@code AsyncTaskExecutor.submit} 在取槽后、提交后台前检测<b>跨 target 累计</b> inflight 是否超全局上限
 * (配置 {@code arthas-gateway.task.global-max-inflight},未设→动态默认 = 注册表后端数 × 5)。超限时抛本异常,
 * 由 {@code ToolsCallRouter} 翻译为结构化 {@code McpError}({@code INVALID_PARAMS} +
 * {@code reason=global_concurrency_limit}),防集群触顶(虚拟线程虽廉价,但后端总数有限,跨 target 须有全局闸门)。
 *
 * <p>与 {@code ConcurrencyLimitException}(per-target 单后端槽)并列:后者限<b>单 target</b>,本异常限<b>全集群</b>。
 */
public final class GlobalConcurrencyLimitException extends RuntimeException {

    private final int globalMaxInflight;

    public GlobalConcurrencyLimitException(int globalMaxInflight) {
        super("全局在途异步任务已达上限:globalMaxInflight=" + globalMaxInflight);
        this.globalMaxInflight = globalMaxInflight;
    }

    /** 触发限流时的全局上限(配置值或动态默认 = 后端数 × 5)。 */
    public int globalMaxInflight() {
        return globalMaxInflight;
    }
}
```


---

## com/arthas/gateway/task/TaskError.java

**文件**：`src/main/java/com/arthas/gateway/task/TaskError.java`

```java
package com.arthas.gateway.task;

import java.util.Objects;

/**
 * 异步任务失败原因（方案 C，gateway-tools-contract.md §2 {@code failed} 分支）。
 *
 * <p>{@code reason} 为稳定枚举串（便于调用方判别），{@code message} 为可读详情。
 * 仅<b>基础设施故障</b>产生 TaskError（后端业务错误 isError=true 走 COMPLETED，不产生 TaskError，G-TG-2）。
 *
 * @param reason  失败类别（见下方常量）
 * @param message 可读详情
 */
public record TaskError(String reason, String message) {

    /** 后端阻塞超 backend-timeout（默认 11min）仍未响应。 */
    public static final String REASON_BACKEND_TIMEOUT = "backend_timeout";
    /** 后端不可达（连接拒绝 / initialize 失败 / 读超时 / SSE 中断）。 */
    public static final String REASON_BACKEND_UNREACHABLE = "backend_unreachable";

    public TaskError {
        Objects.requireNonNull(reason, "reason 不可为空");
        Objects.requireNonNull(message, "message 不可为空");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason 不可为空");
        }
    }
}
```


---

## com/arthas/gateway/task/TaskState.java

**文件**：`src/main/java/com/arthas/gateway/task/TaskState.java`

```java
package com.arthas.gateway.task;

/**
 * 异步任务状态（方案 C，data-model.md §6 / gateway-tools-contract.md §2）。
 *
 * <pre>
 *   WORKING ──markCompleted──► COMPLETED   （后端返回结果，含 isError=true 原样保留）
 *          ├──markFailed──────► FAILED     （基础设施故障：超时/不可达/熔断 OPEN）
 *          └──markCancelled───► CANCELLED  （task-cancel 或终态幂等）
 * </pre>
 *
 * <p>除 {@link #WORKING} 外均为<b>终态</b>（{@link #isTerminal()}=true），不可逆——
 * {@code GatewayTask} 的转换方法对终态任务返 false（不覆盖）。
 */
public enum TaskState {
    WORKING,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** 是否终态（非 WORKING 即终态，不可逆）。 */
    public boolean isTerminal() {
        return this != WORKING;
    }
}
```


---

## com/arthas/gateway/task/TaskStore.java

**文件**：`src/main/java/com/arthas/gateway/task/TaskStore.java`

```java
package com.arthas.gateway.task;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 异步任务的内存存储 + TTL 清理（方案 C，data-model.md §6 / gateway-tools-contract.md §3，T032）。
 *
 * <p>底层 {@link ConcurrentHashMap}（{@code taskId → GatewayTask}），供 task-get/task-list/task-cancel 查询。
 *
 * <p><b>TTL 清理</b>：终态任务（{@code completedAt} 非空）在 {@code completedAt + ttl} 后移除；
 * WORKING 任务（{@code completedAt=null}）<b>不</b>受 TTL 影响（仍在跑的不应被回收）。两条清理路径：
 * <ul>
 *   <li><b>单条惰性（get）</b>：{@link #get(String)} 仅判定<b>目标单条</b>是否过期——存在且未过期→返回;
 *       过期→定向 {@code remove} 返 empty;不存在→返 empty。<b>不</b>触发全表扫描(002 整改 P2-3/FR-009:
 *       大规模终态任务下逐条 task-get 的 O(N) 读放大)。</li>
 *   <li><b>全表惰性（list）</b>：{@link #list()} 访问时调用 {@link #cleanExpired()} 全表清理,过期任务对读不可见。</li>
 *   <li><b>主动</b>：构造时启动守护虚拟线程周期性 {@link #cleanExpired()}（period ≈ ttl/4，至少 1min），
 *       兜底长时间不被访问的终态任务，限制内存占用。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：{@link ConcurrentHashMap} + 基于 {@code entrySet().removeIf} 的原子移除，
 * 后台写线程（markCompleted/markFailed）与 task-get/list 读线程并发安全。
 *
 * <p><b>时钟注入</b>：{@code clock} 为 {@code Supplier<Instant>}，生产传 {@code Instant::now}，
 * 测试传可变时钟以确定性断言 TTL（与 {@link GatewayTask} 共享同一时钟源）。
 *
 * <p><b>生命周期</b>：{@link #close()} 关闭守护清理线程（Spring 容器关闭时调用）。
 */
public final class TaskStore {

    private final ConcurrentHashMap<String, GatewayTask> tasks = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Supplier<Instant> clock;
    private final ScheduledExecutorService cleaner;

    /**
     * @param ttl   终态任务保留时长（完成后可查询时长，超期移除）
     * @param clock 时间源（终态判定 + TTL 用）
     */
    public TaskStore(Duration ttl, Supplier<Instant> clock) {
        this.ttl = Objects.requireNonNull(ttl, "ttl 不可为空");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl 必须为正");
        }
        this.clock = Objects.requireNonNull(clock, "clock 不可为空");
        this.cleaner = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        long periodSec = Math.max(60, ttl.toSeconds() / 4);
        this.cleaner.scheduleAtFixedRate(this::cleanExpired, periodSec, periodSec, TimeUnit.SECONDS);
    }

    /** 存入/覆盖任务（同 taskId 覆盖）。 */
    public void put(GatewayTask task) {
        Objects.requireNonNull(task, "task 不可为空");
        tasks.put(task.taskId(), task);
    }

    /**
     * 按 taskId 移除任务（002 整改 · 提交失败清理僵尸用，P0-1/FR-001）。
     *
     * <p>外层 {@code pool.submit} 被拒时，执行器移除刚 {@link #put} 的 WORKING 任务，
     * 避免其长期驻留为僵尸（永远不到达终态）。
     */
    public void remove(String taskId) {
        tasks.remove(taskId);
    }

    /**
     * 按 taskId 查询（<b>单条</b>过期判定，不触发全表清理，002 整改 P2-3/FR-009）。
     *
     * <p>存在且未过期→返回;过期→定向 {@code remove} 返 empty;不存在→返 empty。
     * 全表 {@link #cleanExpired()} 仅由 {@link #list()} 与后台 cleaner 负责——避免大规模终态任务下
     * 逐条 task-get 的 O(N) 读放大(轮询 N 任务 = O(N²))。
     *
     * @return 任务(存在且未过期);否则 {@link Optional#empty()}
     */
    public Optional<GatewayTask> get(String taskId) {
        GatewayTask task = tasks.get(taskId);
        if (task == null) {
            return Optional.empty();
        }
        if (isExpired(task, clock.get())) {
            tasks.remove(taskId); // 定向移除过期单条(非全表)
            return Optional.empty();
        }
        return Optional.of(task);
    }

    /**
     * 测试探针(包级可见):不经任何清理,探测内部存储是否仍含某 taskId。
     *
     * <p><b>仅测试用</b>——供读放大断言验证 {@link #get(String)} 是否触发全表清理
     * (全表清理会移除过期的兄弟任务;单条 O(1) 不会)。生产代码不应依赖。
     */
    boolean containsRawForTest(String taskId) {
        return tasks.containsKey(taskId);
    }

    /** 全部任务（触发惰性清理；快照副本，顺序不定）。 */
    public List<GatewayTask> list() {
        cleanExpired();
        return List.copyOf(tasks.values());
    }

    /** 按 status 过滤（触发惰性清理）。 */
    public List<GatewayTask> list(TaskState status) {
        Objects.requireNonNull(status, "status 不可为空");
        cleanExpired();
        return tasks.values().stream()
                .filter(t -> t.status() == status)
                .toList();
    }

    /**
     * 移除所有「终态且 {@code completedAt + ttl} 已过」的任务。
     *
     * @return 本次移除条数（便于测试断言）
     */
    public int cleanExpired() {
        Instant now = clock.get();
        int[] removed = {0};
        tasks.entrySet().removeIf(e -> {
            if (isExpired(e.getValue(), now)) {
                removed[0]++;
                return true;
            }
            return false;
        });
        return removed[0];
    }

    /** 终态（completedAt 非空）且 {@code now} 晚于 {@code completedAt + ttl} → 过期。WORKING 永不过期。 */
    private boolean isExpired(GatewayTask task, Instant now) {
        Instant completedAt = task.completedAt();
        return completedAt != null && now.isAfter(completedAt.plus(ttl));
    }

    /** 关闭守护清理线程（Spring 容器关闭时调用）。 */
    public void close() {
        cleaner.shutdownNow();
    }
}
```


---

## com/arthas/gateway/tool/ExposedTool.java

**文件**：`src/main/java/com/arthas/gateway/tool/ExposedTool.java`

```java
package com.arthas.gateway.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 对调用方暴露的单个工具（data-model.md §5）。
 *
 * <p>不可变值对象。统一承载两类工具：
 * <ul>
 *   <li>arthas 工具（31）：inputSchema 为 arthas 原始 schema + 注入的 {@code target}；taskSupport 非 null。</li>
 *   <li>网关自有工具（4）：routingMode=GATEWAY_LOCAL，taskSupport=null。</li>
 * </ul>
 * inputSchema 在构造时做深冻结，防止外部修改泄漏到 {@code tools/list} 快照。
 */
public record ExposedTool(
        String name,
        String description,
        Map<String, Object> inputSchema,
        TaskSupport taskSupport,
        RoutingMode routingMode) {

    public ExposedTool {
        Objects.requireNonNull(name, "name 不可为空");
        Objects.requireNonNull(description, "description 不可为空");
        Objects.requireNonNull(routingMode, "routingMode 不可为空");
        inputSchema = Map.copyOf(deepImmutable(Objects.requireNonNull(
                inputSchema, "inputSchema 不可为空")));
    }

    /** 将任意 JSON 树深冻结为不可变结构（递归 Map→unmodifiable / List→unmodifiable）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepImmutable(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        src.forEach((k, v) -> out.put(k, switch (v) {
            case Map<?, ?> m -> Map.copyOf(deepImmutable(toStrMap(m)));
            case List<?> l -> List.copyOf(deepImmutableList(l));
            default -> v;
        }));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> deepImmutableList(List<?> src) {
        List<Object> out = new ArrayList<>();
        for (Object v : src) {
            out.add(switch (v) {
                case Map<?, ?> m -> Map.copyOf(deepImmutable(toStrMap(m)));
                case List<?> l -> List.copyOf(deepImmutableList(l));
                default -> v;
            });
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStrMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put((String) k, v));
        return out;
    }
}
```


---

## com/arthas/gateway/tool/RoutingMode.java

**文件**：`src/main/java/com/arthas/gateway/tool/RoutingMode.java`

```java
package com.arthas.gateway.tool;

/**
 * 工具路由模式（data-model.md §5.1，内部字段，不进 MCP 协议）。
 *
 * <p>分类源自 {@code reference/arthas-docs/03-MCP/工具传输分类表.md}：
 * <ul>
 *   <li>{@link #SYNC_DIRECT}：26 个即时工具（非流式、taskSupport=forbidden），同步转发、原样透传。</li>
 *   <li>{@link #STREAM_AGGREGATE}：dashboard（流式 + forbidden），网关聚合 SSE 多帧为一次结果。</li>
 *   <li>{@link #ASYNC_TASK}：5 个 optional 工具（watch/trace/stack/tt/monitor），立即返回 taskId，方案 C 后台阻塞等后端。</li>
 *   <li>{@link #GATEWAY_LOCAL}：4 个网关自有工具（list-targets/task-get/task-list/task-cancel），不转发后端。</li>
 * </ul>
 */
public enum RoutingMode {
    SYNC_DIRECT,
    STREAM_AGGREGATE,
    ASYNC_TASK,
    GATEWAY_LOCAL
}
```


---

## com/arthas/gateway/tool/StaticToolRegistry.java

**文件**：`src/main/java/com/arthas/gateway/tool/StaticToolRegistry.java`

```java
package com.arthas.gateway.tool;

import com.arthas.gateway.handler.McpJson;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 静态工具注册表（宪法原则二：透明无损聚合）。
 *
 * <p>启动期构建不可变 35 工具快照（31 arthas + 4 网关自有），作为 {@code tools/list} 的单一填充源，
 * 不依赖后端动态发现。arthas 工具 schema 逐字摘抄自 {@code arthas-tools.json}（单一事实源），
 * 网关仅注入顶层 {@code target} 参数（string, required, 进 properties 与 required 数组首位）。
 *
 * <p>不变量：构造后 tools() 返回不可变列表，每个工具的 inputSchema 深冻结。
 */
public final class StaticToolRegistry {

    /** 注入到每个 arthas 工具的顶层参数名。 */
    public static final String TARGET_PARAM = "target";

    /** target 参数的 schema 片段（见 server-contract.md §4 示例）。 */
    private static final Map<String, Object> TARGET_SCHEMA = Map.of(
            "type", "string",
            "description", "目标 JVM 逻辑名（见 list-targets），决定路由到哪个 arthas 后端");

    private final List<ExposedTool> tools;
    private final Map<String, ExposedTool> byName;

    /** 由已构建的 ExposedTool 列表构造（做不可变拷贝）。 */
    public StaticToolRegistry(List<ExposedTool> tools) {
        this.tools = List.copyOf(tools);
        Map<String, ExposedTool> map = new LinkedHashMap<>();
        for (ExposedTool t : this.tools) {
            map.put(t.name(), t);
        }
        this.byName = Map.copyOf(map);
    }

    /** 全部 35 个工具的不可变快照。 */
    public List<ExposedTool> tools() {
        return tools;
    }

    /** 按工具名查找；未知工具返回 empty。 */
    public Optional<ExposedTool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * 从 classpath 资源加载 31 个 arthas 工具 schema，注入 target，追加 4 个网关自有工具，
     * 构建不可变 35 工具注册表。
     *
     * @param resourcePath classpath 资源路径（如 {@code "arthas-tools.json"}）
     */
    public static StaticToolRegistry fromClasspath(String resourcePath) {
        try (InputStream in = StaticToolRegistry.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("未找到 classpath 工具 schema 资源：" + resourcePath);
            }
            ObjectMapper mapper = McpJson.MAPPER; // 复用全局单例(T025/FR-013)
            Map<String, Object> root = mapper.readValue(in, new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> specs = asList(root.get("tools"));
            List<ExposedTool> exposed = new ArrayList<>(specs.size() + 4);
            for (Map<String, Object> spec : specs) {
                exposed.add(buildArthasTool(spec));
            }
            exposed.addAll(gatewayTools());
            return new StaticToolRegistry(exposed);
        } catch (IOException e) {
            throw new IllegalStateException("加载工具注册表失败：" + resourcePath, e);
        }
    }

    /** 注入 target 到 arthas 原始 schema，构造 arthas 工具。 */
    @SuppressWarnings("unchecked")
    private static ExposedTool buildArthasTool(Map<String, Object> spec) {
        Map<String, Object> schema = deepMutable((Map<String, Object>) spec.get("inputSchema"));

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        if (properties == null) {
            properties = new LinkedHashMap<>();
        } else {
            properties = deepMutable(properties);
        }
        properties.put(TARGET_PARAM, new LinkedHashMap<>(TARGET_SCHEMA));
        schema.put("properties", properties);

        List<String> required = new ArrayList<>();
        Object rawRequired = schema.get("required");
        if (rawRequired instanceof List<?> list) {
            for (Object r : list) {
                required.add((String) r);
            }
        }
        required.add(0, TARGET_PARAM);
        schema.put("required", required);

        return new ExposedTool(
                (String) spec.get("name"),
                (String) spec.get("description"),
                schema,
                TaskSupport.fromWire((String) spec.get("taskSupport")),
                RoutingMode.valueOf((String) spec.get("routingMode")));
    }

    /** 4 个网关自有工具（routingMode=GATEWAY_LOCAL，不转发后端）。schema 见 gateway-tools-contract.md。 */
    private static List<ExposedTool> gatewayTools() {
        return List.of(
                new ExposedTool(
                        "arthas-gateway.list-targets",
                        "列出网关当前注册的所有诊断目标（逻辑名/健康状态/协议），供 target 参数取值参考",
                        schema(Map.of(), List.of()),
                        null,
                        RoutingMode.GATEWAY_LOCAL),
                new ExposedTool(
                        "arthas-gateway.task-get",
                        "查询一个异步诊断任务（watch/trace/stack/tt/monitor 触发）的状态；完成时返回最终结果",
                        schema(Map.of("taskId", Map.of(
                                "type", "string",
                                "description", "异步工具调用返回的 taskId")), List.of("taskId")),
                        null,
                        RoutingMode.GATEWAY_LOCAL),
                new ExposedTool(
                        "arthas-gateway.task-list",
                        "列出当前所有异步诊断任务的概要（状态总览）",
                        schema(Map.of("status", Map.of(
                                "type", "string",
                                "description", "可选过滤：working|completed|failed|cancelled；不传则全部")), List.of()),
                        null,
                        RoutingMode.GATEWAY_LOCAL),
                new ExposedTool(
                        "arthas-gateway.task-cancel",
                        "取消一个仍在 working 的异步诊断任务",
                        schema(Map.of("taskId", Map.of(
                                "type", "string",
                                "description", "要取消的 taskId")), List.of("taskId")),
                        null,
                        RoutingMode.GATEWAY_LOCAL));
    }

    /** 构造标准 inputSchema 骨架。 */
    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        schema.put("required", new ArrayList<>(required));
        schema.put("additionalProperties", false);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMutable(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (src == null) {
            return out;
        }
        src.forEach((k, v) -> out.put(k, switch (v) {
            case Map<?, ?> m -> deepMutable((Map<String, Object>) m);
            case List<?> l -> new ArrayList<>(l);
            default -> v;
        }));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object raw) {
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                out.add((Map<String, Object>) o);
            }
            return out;
        }
        throw new IllegalStateException("arthas-tools.json 的 tools 字段须为数组");
    }
}
```


---

## com/arthas/gateway/tool/TaskSupport.java

**文件**：`src/main/java/com/arthas/gateway/tool/TaskSupport.java`

```java
package com.arthas.gateway.tool;

import java.util.Locale;

/**
 * 工具任务支持度（data-model.md §5.1，对应 MCP {@code execution.taskSupport}）。
 *
 * <p>JSON 线值为小写 {@code forbidden/optional/required}（MCP能力清单 §0.6）。
 * 仅 31 个 arthas 工具携带；4 个网关自有工具为 {@code null}（不暴露 taskSupport）。
 *
 * <ul>
 *   <li>{@link #FORBIDDEN}：26 个（25 SYNC_DIRECT + dashboard STREAM_AGGREGATE）</li>
 *   <li>{@link #OPTIONAL}：5 个（watch/trace/stack/tt/monitor，方案 C 异步任务）</li>
 *   <li>{@link #REQUIRED}：0（arthas 无必须任务工具）</li>
 * </ul>
 */
public enum TaskSupport {
    FORBIDDEN,
    OPTIONAL,
    REQUIRED;

    /** 从 JSON 线值（如 "forbidden"）解析为枚举。 */
    public static TaskSupport fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            throw new IllegalArgumentException("taskSupport 不可为空");
        }
        return TaskSupport.valueOf(wire.trim().toUpperCase(Locale.ROOT));
    }
}
```

