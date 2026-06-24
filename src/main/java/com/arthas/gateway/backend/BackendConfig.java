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
