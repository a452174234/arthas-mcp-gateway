package com.arthas.gateway.backend;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * 单后端配置声明（data-model.md §2，005 US2 增量 K8S 模式）。源自 {@code config/backends.yaml}，不可变值对象。
 *
 * <p><b>两种寻址模式</b>（005 US2，INV-K8SHOST-1 互斥）：
 * <ul>
 *   <li><b>静态模式</b>（003/004 既有）：{@code url} 非空——直连业务 arthas MCP 地址。</li>
 *   <li><b>K8S 模式</b>（005 新增）：{@code k8sHost} 非空 + {@code pod}——声明远端 K8S 集群入口 + 业务 pod，
 *       首次路由懒 resolve（{@link BackendResolver} ensure + 缓存）出 mcpUrl。</li>
 * </ul>
 * {@code url} 与 {@code k8sHost} <b>互斥</b>（不可同时配置 / 不可皆空）；K8S 模式 {@code pod} 必填。
 *
 * <p>紧凑构造器做<b>单实例不变量校验</b>：name 非空、url（静态模式）为合法 http(s) URL（含 host）、
 * k8sHost/pod（K8S 模式）互斥与必填、protocol/auth 非空、超时为正、{@code maxConcurrentTasks} ∈ [1,5]。
 * 跨实例校验（name 唯一）由 {@link BackendConfigLoader} 负责。任意失败抛 {@link BackendConfigException}，
 * 以便热重载保留旧表（data-model.md §11 规则 7）。
 *
 * <p><b>{@code source} 来源标记</b>（003 动态纳管增量）：最后一个组件，缺省 {@link Source#STATIC}。
 * {@code equals}/{@code hashCode} 排除 {@code source}（同核心字段异 source 仍视为同一可复用后端，保连接池复用语义），
 * <b>纳入</b> {@code k8sHost}/{@code pod}（K8S 模式寻址变化=不同后端）。
 */
public record BackendConfig(
        String name,
        String url,
        Protocol protocol,
        Auth auth,
        int connectTimeoutMs,
        int callTimeoutMs,
        int maxConcurrentTasks,
        String k8sHost,
        String pod,
        Source source) {

    public BackendConfig {
        if (name == null || name.isBlank()) {
            throw new BackendConfigException("后端 name 不可为空");
        }
        Objects.requireNonNull(protocol, "protocol 不可为空");
        Objects.requireNonNull(auth, "auth 不可为空");
        validateModeRouting(name, url, k8sHost, pod); // url/k8sHost 互斥 + K8S 模式 pod 必填（INV-K8SHOST-1）
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
        // source 缺省 STATIC（向后兼容：YAML 不写 source 视为 STATIC；既有 7/8 参调用点零改动）。
        if (source == null) {
            source = Source.STATIC;
        }
    }

    /**
     * 寻址模式校验（005 US2，INV-K8SHOST-1）：url 与 k8sHost 互斥；静态模式校验 url 合法；
     * K8S 模式 pod 必填。
     */
    private static void validateModeRouting(String name, String url, String k8sHost, String pod) {
        boolean hasUrl = url != null && !url.isBlank();
        boolean hasK8s = k8sHost != null && !k8sHost.isBlank();
        if (hasUrl && hasK8s) {
            throw new BackendConfigException(name + ": url 与 k8sHost 互斥（不可同时配置）");
        }
        if (!hasUrl && !hasK8s) {
            throw new BackendConfigException(name + ": url 与 k8sHost 须二选一（静态模式配 url / K8S 模式配 k8sHost+pod）");
        }
        if (hasUrl) {
            requireHttpUrl(name, url); // 静态模式：url 须为合法 http(s)
        } else if (pod == null || pod.isBlank()) {
            throw new BackendConfigException(name + ": K8S 模式（k8sHost 非空）须配 pod");
        }
    }

    /** K8S 模式判定（k8sHost 非空）。 */
    public boolean isK8sMode() {
        return k8sHost != null && !k8sHost.isBlank();
    }

    /**
     * 005 US2 懒 resolve 后构造静态等价 config：用解析出的 mcpUrl 替换（K8S 模式 → 静态 url），
     * 供 {@code HttpBackendClient} 按 mcpUrl 建连。k8sHost/pod 清空（已 resolve，不再需要）。
     */
    public BackendConfig withResolvedUrl(String mcpUrl) {
        return new BackendConfig(name, mcpUrl, protocol, auth,
                connectTimeoutMs, callTimeoutMs, maxConcurrentTasks, null, null, source);
    }

    /**
     * 向后兼容构造器（不含 source / k8sHost / pod）—— 静态 url 模式，{@code source} 缺省 {@link Source#STATIC}。
     *
     * <p>保留 001 既有 7 参调用点（各测试）零改动通过。
     */
    public BackendConfig(String name, String url, Protocol protocol, Auth auth,
                         int connectTimeoutMs, int callTimeoutMs, int maxConcurrentTasks) {
        this(name, url, protocol, auth, connectTimeoutMs, callTimeoutMs, maxConcurrentTasks, null, null, Source.STATIC);
    }

    /**
     * 向后兼容构造器（不含 k8sHost / pod，含 source）—— 静态 url 模式。
     *
     * <p>保留 003 既有 8 参调用点（{@code BackendConfigLoader} 旧路径 + {@code ArthasProvisioner} 动态注册）零改动通过。
     */
    public BackendConfig(String name, String url, Protocol protocol, Auth auth,
                         int connectTimeoutMs, int callTimeoutMs, int maxConcurrentTasks, Source source) {
        this(name, url, protocol, auth, connectTimeoutMs, callTimeoutMs, maxConcurrentTasks, null, null, source);
    }

    /**
     * 复用判定 equals（data-model §2：排除 {@code source}，纳入 {@code k8sHost}/{@code pod}）。
     *
     * <p>比较 9 个复用核心字段（name/url/protocol/auth/超时/并发/k8sHost/pod）。{@code source} 为可观测标记，
     * 不影响「是否同一可复用后端」。k8sHost/pod 纳入——寻址模式变化=不同后端。
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
                && Objects.equals(auth, that.auth)
                && Objects.equals(k8sHost, that.k8sHost)
                && Objects.equals(pod, that.pod);
    }

    /** 复用判定 hashCode（与 {@link #equals(Object)} 协同，排除 {@code source}，纳入 k8sHost/pod）。 */
    @Override
    public int hashCode() {
        return Objects.hash(name, url, protocol, auth, connectTimeoutMs, callTimeoutMs, maxConcurrentTasks, k8sHost, pod);
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
