package com.arthas.gateway.orchestration;

import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendResolver;
import com.arthas.gateway.config.GatewayProperties;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * K8S 模式后端懒 resolve 实现（005 US2 + 006 波2 改造，INV-K8SHOST-2/3/5 + INV-HOT-1）。
 *
 * <p>{@link BackendResolver} 的 orchestration 实装（gateway-core 定义接口，零 fabric8 依赖——本类位于
 * orchestration 包，ArchUnit INV-BOUNDARY-2 守护）。
 *
 * <h3>006 波2 改造</h3>
 * <p>从启动期不可变 {@code Map<host, provisioner>} 改为从 {@link K8sHostStore} 运行时查 {@link HostEntry}——
 * host 增删改（热重载）立即可见。host 重建时 {@link #invalidateHost(String)} 清该 host 的 resolve 缓存
 * （经 {@code K8sHostStore.setCacheInvalidator(resolver::invalidateHost)} 装配注入，INV-HOT-1）。
 *
 * <h3>resolve 流程</h3>
 * <ol>
 *   <li><b>静态模式旁路</b>（config.k8sHost 空）→ {@code Optional.empty()}（INV-K8SHOST-5）。</li>
 *   <li><b>K8S 模式</b>：host 不在 store → {@link K8sResolveException} unknown_k8s_host（INV-K8SHOST-3）；
 *       按 logicalName 缓存命中 → 返（INV-K8SHOST-2）；未缓存 → ensure 拿 mcpUrl（ready/reused→缓存返；
 *       failed/其他→ensure_failed）。</li>
 * </ol>
 */
public class K8sBackendResolver implements BackendResolver, AutoCloseable {

    private final K8sHostStore store;
    private final Clock clock;
    /** logicalName → mcpUrl 缓存（幂等命中，INV-K8SHOST-2）。host 重建时按前缀清（invalidateHost）。 */
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public K8sBackendResolver(K8sHostStore store, Clock clock) {
        this.store = java.util.Objects.requireNonNull(store, "store 不可为空");
        this.clock = java.util.Objects.requireNonNull(clock, "clock 不可为空");
    }

    @Override
    public Optional<String> resolveMcpUrl(BackendConfig config) {
        if (!config.isK8sMode()) {
            return Optional.empty(); // 静态模式旁路（INV-K8SHOST-5）
        }
        String host = config.k8sHost();
        HostEntry entry = store.get(host);
        if (entry == null) {
            throw new K8sResolveException("unknown_k8s_host",
                    "K8S host 未配置：" + host); // INV-K8SHOST-3
        }
        GatewayProperties.K8sHost meta = entry.k8sHost();
        String server = meta != null && meta.getName() != null && !meta.getName().isBlank() ? meta.getName() : host;
        String namespace = meta != null && meta.getNamespace() != null && !meta.getNamespace().isBlank()
                ? meta.getNamespace() : "default";
        String logicalName = ArthasProvisioner.deriveLogicalName(server, config.pod());

        String mcpUrl = cache.computeIfAbsent(logicalName,
                k -> doEnsure(entry.provisioner(), server, host, config.pod(), namespace));
        return Optional.of(mcpUrl);
    }

    private String doEnsure(ArthasProvisioner provisioner, String server, String host, String pod, String namespace) {
        OrchestrationRecord rec = provisioner.ensure(server, pod, namespace, clock.instant());
        if (rec.status() != OrchestrationRecord.Status.READY
                && rec.status() != OrchestrationRecord.Status.REUSED) {
            throw new K8sResolveException("ensure_failed",
                    host + "/" + pod + " ensure 终态=" + rec.status()
                            + (rec.error() != null ? " reason=" + rec.error().reason() + "@" + rec.error().phase() : ""));
        }
        return rec.mcpUrl();
    }

    /**
     * 清该 host 的 resolve 缓存（006 波2，T019）。
     *
     * <p>host 重建时由 {@link K8sHostStore} 经 {@code cacheInvalidator} 回调（装配期
     * {@code store.setCacheInvalidator(resolver::invalidateHost)} 注入），使下次路由重新 ensure（INV-HOT-1）。
     */
    public void invalidateHost(String hostName) {
        String prefix = hostName + "-";
        cache.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
    }

    @Override
    public void close() {
        // client 生命周期由 K8sHostStore 管理（store.close）；resolver 无独立资源
    }

    /** K8S 懒 resolve 故障（unknown_k8s_host / ensure_failed），携带 reason 供上层观测/分类。 */
    public static class K8sResolveException extends RuntimeException {
        private final String reason;

        public K8sResolveException(String reason, String message) {
            super(reason + ": " + message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
