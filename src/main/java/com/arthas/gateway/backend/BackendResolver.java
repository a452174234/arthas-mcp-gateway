package com.arthas.gateway.backend;

import java.util.Optional;

/**
 * 后端懒 resolve 接口（005 特性，US2，research.md R6）。
 *
 * <p>gateway-core 定义（<b>零 fabric8 依赖</b>，ArchUnit 守护 INV-BOUNDARY-1）；实现在 orchestration 包
 * （{@code K8sBackendResolver}）。{@link BackendEntry} 首次握手时调用：K8S 模式 config（{@code k8sHost}
 * 非空）→ 懒 resolve 出 mcpUrl（调 ensure + 缓存）；静态模式（{@code url} 非空）→ empty（用 config.url）。
 *
 * <p>装配为 {@code Optional<BackendResolver>}（无 K8S 时不装配；K8S 模式 backend 路由时报 {@code no_k8s_resolver}，
 * INV-K8SHOST-4）。
 *
 * @param config 后端配置（K8S 模式：k8sHost 非空 + pod；静态模式：url 非空）
 * @return mcpUrl（K8S 模式，懒 resolve）；empty（静态模式，旁路）
 */
public interface BackendResolver {
    Optional<String> resolveMcpUrl(BackendConfig config);
}
