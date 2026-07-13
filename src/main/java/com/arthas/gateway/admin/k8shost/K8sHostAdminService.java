package com.arthas.gateway.admin.k8shost;

import com.arthas.gateway.config.GatewayProperties;
import com.arthas.gateway.config.K8sHostsConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * portal K8S host 管理服务（006 波3，T025/T026，仿 {@code BackendAdminService}）。
 *
 * <p>CRUD 操作写 {@code config/k8s-hosts.yaml}（{@link K8sHostsYamlWriter} 序列化，含凭证加密）→ 触发
 * {@code K8sHostsWatcher} WatchService 热重载（portal 是热重载触发源之一，INV-PORTAL-K8S-1）。
 *
 * <p><b>校验</b>：name 必填；{@code kubeconfig} 与 {@code ssh} 互斥（INV-SSH-3）；重名拒绝。
 * <b>脱敏</b>：{@link #list()} 返 {@link K8sHostDto}（无凭证，INV-PORTAL-K8S-2）。
 */
public class K8sHostAdminService {

    private final Path file;
    private final GatewayProperties props;
    private final K8sHostsConfig loader;
    private final K8sHostsYamlWriter writer;
    private final K8sHostSecretCipher cipher;

    public K8sHostAdminService(Path file, GatewayProperties props, K8sHostsConfig loader,
                               K8sHostsYamlWriter writer, K8sHostSecretCipher cipher) {
        this.file = file;
        this.props = props;
        this.loader = loader;
        this.writer = writer;
        this.cipher = cipher;
    }

    public List<K8sHostDto> list() throws IOException {
        return load().stream().map(K8sHostDto::from).toList();
    }

    public K8sHostDto create(CreateK8sHostRequest req) throws IOException {
        validate(req);
        List<GatewayProperties.K8sHost> hosts = new ArrayList<>(load());
        if (hosts.stream().anyMatch(h -> h.getName().equals(req.name()))) {
            throw new IllegalArgumentException("K8S host 名已存在：" + req.name());
        }
        GatewayProperties.K8sHost newHost = req.toK8sHost();
        hosts.add(newHost);
        write(hosts);
        return K8sHostDto.from(newHost);
    }

    public void delete(String name) throws IOException {
        List<GatewayProperties.K8sHost> hosts = new ArrayList<>(load());
        boolean removed = hosts.removeIf(h -> h.getName().equals(name));
        if (!removed) {
            throw new IllegalArgumentException("K8S host 不存在：" + name);
        }
        write(hosts);
    }

    private void validate(CreateK8sHostRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("name 必填");
        }
        boolean hasKc = req.kubeconfig() != null && !req.kubeconfig().isBlank();
        boolean hasSsh = req.ssh() != null;
        if (hasKc == hasSsh) {
            throw new IllegalArgumentException("kubeconfig 与 ssh 须二选一（互斥）");
        }
    }

    private List<GatewayProperties.K8sHost> load() throws IOException {
        return loader.loadHosts(file, props).hosts();
    }

    private void write(List<GatewayProperties.K8sHost> hosts) throws IOException {
        writer.write(file, System.currentTimeMillis(), hosts, cipher);
    }
}
