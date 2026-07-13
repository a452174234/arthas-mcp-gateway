package com.arthas.gateway.orchestration;

import com.arthas.gateway.config.GatewayProperties;
import com.arthas.gateway.config.K8sParams;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * K8S host 运行时生命周期管理（006 波2，T016，仿 {@code DynamicBackendStore}）。
 *
 * <p>持有 hostName → {@link HostEntry} 映射 + 每个 host 的「连接签名」（kubeconfig 或 ssh 字段，不含 namespace）。
 * {@link #applyDiff(List)} 对比新旧 host 列表：新增→factory.create + 存；删除→entry.close + 移除 + 清缓存；
 * 连接签名变→close 旧 + 重建 + 清缓存；不变（含 namespace 单独变）→不重建（namespace 热生效由 K8sParams/provisioner 读新值承担）。
 *
 * <p><b>线程安全</b>：{@link #applyDiff} 同步（重建串行）；{@link #get(String)} 路由读无锁（ConcurrentHashMap）。
 * 实体见 [data-model.md §5](../../../../specs/006-k8s-host-remote-access/data-model.md)；契约 INV-HOT-1/2。
 */
public final class K8sHostStore implements AutoCloseable {

    private final ConcurrentHashMap<String, HostEntry> byName = new ConcurrentHashMap<>();
    private final Map<String, String> connectionSignatureByName = new ConcurrentHashMap<>();
    private final HostEntryFactory factory;
    private volatile Consumer<String> cacheInvalidator;

    public K8sHostStore(HostEntryFactory factory, Consumer<String> cacheInvalidator) {
        this.factory = factory;
        this.cacheInvalidator = cacheInvalidator;
    }

    /**
     * 后设 cacheInvalidator（006 波2 装配解 store↔resolver 环：store bean 先建，resolver bean 建好后注入
     * {@code resolver::invalidateHost}，使 host 重建时清该 host 的 resolve 缓存）。
     */
    public void setCacheInvalidator(Consumer<String> cacheInvalidator) {
        this.cacheInvalidator = cacheInvalidator;
    }

    private volatile K8sParams params;

    /**
     * 006 波2 T020：刷新全局 K8S 参数（{@code K8sHostsWatcher} 加载 {@code config/k8s-hosts.yaml} 的
     * {@code k8s-params} 段后调用）。{@code ArthasProvisioner} 经 {@code paramsSupplier} 读 {@code currentParams()}，
     * 使全局参数热生效（下次 ensure 用新值，INV-HOT-3）。
     */
    public void updateParams(K8sParams params) {
        this.params = params;
    }

    /** 当前全局参数快照（provisioner 经 supplier 读）。 */
    public K8sParams currentParams() {
        return params;
    }

    /**
     * 对比 desired host 列表，diff 出增/删/改并应用。
     *
     * @param desired 期望的 host 列表（按 name 唯一）
     */
    public synchronized void applyDiff(List<GatewayProperties.K8sHost> desired) {
        Set<String> desiredNames = new HashSet<>();
        for (GatewayProperties.K8sHost h : desired) {
            String name = h.getName();
            String sig = connectionSignature(h);
            desiredNames.add(name);
            HostEntry existing = byName.get(name);
            if (existing == null) {
                // 新增
                byName.put(name, factory.create(h));
                connectionSignatureByName.put(name, sig);
            } else if (!sig.equals(connectionSignatureByName.get(name))) {
                // 连接配置变 → 重建（close 旧 + 建新 + 清缓存）
                existing.close();
                byName.put(name, factory.create(h));
                connectionSignatureByName.put(name, sig);
                cacheInvalidator.accept(name);
            }
            // else 连接不变（含 namespace 单独变）→ 不重建 client
        }
        // 删除：current 不在 desired
        Iterator<String> it = byName.keySet().iterator();
        while (it.hasNext()) {
            String name = it.next();
            if (!desiredNames.contains(name)) {
                byName.get(name).close();
                it.remove();
                connectionSignatureByName.remove(name);
                cacheInvalidator.accept(name);
            }
        }
    }

    /** 路由查询（无锁）。 */
    public HostEntry get(String name) {
        return byName.get(name);
    }

    /** 连接签名：连接相关字段（kubeconfig 或 ssh 全字段），不含 namespace（namespace 变不触发 client 重建）。 */
    private static String connectionSignature(GatewayProperties.K8sHost h) {
        GatewayProperties.K8sHost.Ssh s = h.getSsh();
        if (s != null) {
            return "ssh|" + s.getHost() + "|" + s.getPort() + "|" + s.getUser()
                    + "|" + sig(s.getPassword()) + "|" + sig(s.getPrivateKey()) + "|" + sig(s.getPassphrase())
                    + "|" + s.getKubeconfigRemotePath() + "|" + s.getServerOverride() + "|" + s.isInsecureSkipTlsVerify();
        }
        return "kc|" + h.getKubeconfig();
    }

    private static String sig(String secret) {
        // 仅作变化检测（null vs 非 null vs 值变），不持久化不入日志
        return secret == null ? "" : secret;
    }

    @Override
    public void close() {
        byName.values().forEach(HostEntry::close);
        byName.clear();
        connectionSignatureByName.clear();
    }
}
