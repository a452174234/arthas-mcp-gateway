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

    private long readVersion(Map<String, Object> root) {
        Object raw = root.get("version");
        if (raw == null) {
            throw new BackendConfigException("backends.yaml 缺 version 字段（热重载去重所需）");
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        throw new BackendConfigException("version 须为整数，实得 " + raw);
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

        return new BackendConfig(name, url, protocol, auth, connectTimeoutMs, callTimeoutMs, maxConcurrentTasks);
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
        String token = resolvePlaceholder(asNullableString(a.get("token")));
        String username = resolvePlaceholder(asNullableString(a.get("username")));
        String password = resolvePlaceholder(asNullableString(a.get("password")));
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

    private static String asNullableString(Object raw) {
        return raw == null ? null : String.valueOf(raw).trim();
    }

    private static int asInt(Object raw, String field) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        throw new BackendConfigException(field + " 须为整数，实得 " + raw);
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
