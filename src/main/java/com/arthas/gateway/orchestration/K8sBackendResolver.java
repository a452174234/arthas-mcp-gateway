package com.arthas.gateway.orchestration;

import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendResolver;
import com.arthas.gateway.config.GatewayProperties;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * K8S 模式后端懒 resolve 实现（005 US2，research.md R6，INV-K8SHOST-2/3/5）。
 *
 * <p>{@link BackendResolver} 的 orchestration 实装（gateway-core 定义接口，零 fabric8 依赖——本类持
 * {@link ArthasProvisioner} 等编排依赖，位于 orchestration 包，ArchUnit INV-BOUNDARY-2 守护）。
 *
 * <h3>resolve 流程</h3>
 * <ol>
 *   <li><b>静态模式旁路</b>（config.k8sHost 空）→ {@code Optional.empty()}，由 BackendEntry 用 config.url（INV-K8SHOST-5）。</li>
 *   <li><b>K8S 模式</b>（config.k8sHost 非空）：
 *     <ul>
 *       <li>host 不在 provisioners 映射 → {@link K8sResolveException} reason=unknown_k8s_host（INV-K8SHOST-3）。</li>
 *       <li>按 logicalName={@code {hostName}-{pod}} 缓存命中 → 直接返（INV-K8SHOST-2，不重复 ensure）。</li>
 *       <li>未缓存 → 调 {@link ArthasProvisioner#ensure}（注入 arthas + NodePort 暴露 + 健康检查）拿 mcpUrl，
 *           ready/reused 终态 → 缓存并返；failed/其他 → {@link K8sResolveException} reason=ensure_failed。</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p><b>缓存粒度</b>：按 logicalName（hostName + pod 派生）缓存 mcpUrl——同一 K8S 后端首次路由 ensure 一次，
 * 后续命中缓存（ensure 原子幂等，但避免重复 ensure 的 arthas attach / NodePort 往返开销）。
 */
public class K8sBackendResolver implements BackendResolver, AutoCloseable {

    private final Map<String, ArthasProvisioner> provisioners;
    private final Map<String, GatewayProperties.K8sHost> hosts;
    private final Clock clock;
    /** logicalName → mcpUrl 缓存（幂等命中，INV-K8SHOST-2）。 */
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    /** 本 resolver 拥有的 host clients（容器关闭时释放，生命周期随 bean）。 */
    private List<KubernetesClient> ownedClients = List.of();

    public K8sBackendResolver(Map<String, ArthasProvisioner> provisioners,
                              Map<String, GatewayProperties.K8sHost> hosts, Clock clock) {
        this.provisioners = Objects.requireNonNull(provisioners, "provisioners 不可为空");
        this.hosts = Objects.requireNonNull(hosts, "hosts 不可为空");
        this.clock = Objects.requireNonNull(clock, "clock 不可为空");
    }

    @Override
    public Optional<String> resolveMcpUrl(BackendConfig config) {
        if (!config.isK8sMode()) {
            return Optional.empty(); // 静态模式旁路（INV-K8SHOST-5）
        }
        String host = config.k8sHost();
        ArthasProvisioner provisioner = provisioners.get(host);
        if (provisioner == null) {
            throw new K8sResolveException("unknown_k8s_host",
                    "K8S host 未在 arthas-gateway.k8s-hosts 配置：" + host); // INV-K8SHOST-3
        }
        GatewayProperties.K8sHost meta = hosts.get(host);
        String server = meta != null && meta.getName() != null && !meta.getName().isBlank() ? meta.getName() : host;
        String namespace = meta != null && meta.getNamespace() != null && !meta.getNamespace().isBlank()
                ? meta.getNamespace() : "default";
        String logicalName = ArthasProvisioner.deriveLogicalName(server, config.pod());

        String mcpUrl = cache.computeIfAbsent(logicalName, k -> doEnsure(provisioner, server, host, config.pod(), namespace));
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

    /** 注入本 resolver 拥有的 host clients（容器关闭时一并释放）。 */
    public void setOwnedClients(List<KubernetesClient> clients) {
        this.ownedClients = new ArrayList<>(clients);
    }

    @Override
    public void close() {
        for (KubernetesClient c : ownedClients) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 关闭异常忽略（幂等清理）
            }
        }
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
