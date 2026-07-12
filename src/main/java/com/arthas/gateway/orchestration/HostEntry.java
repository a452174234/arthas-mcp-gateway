package com.arthas.gateway.orchestration;

import com.arthas.gateway.config.GatewayProperties;
import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * 单个 K8S host 的运行时三件套（006 波2，T016）：fabric8 client + NodePort 暴露器 + arthas 供给器 + host 配置。
 *
 * <p>{@link AutoCloseable}：{@link K8sHostStore} 删除/重建 host 时 {@link #close()} 关 client（释放 fabric8 HTTP 连接池）。
 * 实体见 [data-model.md §5](../../../../specs/006-k8s-host-remote-access/data-model.md)。
 */
public final class HostEntry implements AutoCloseable {

    private final String name;
    private final KubernetesClient client;
    private final NodePortExposer exposer;
    private final ArthasProvisioner provisioner;
    private final GatewayProperties.K8sHost k8sHost;

    public HostEntry(String name, KubernetesClient client, NodePortExposer exposer,
                     ArthasProvisioner provisioner, GatewayProperties.K8sHost k8sHost) {
        this.name = name;
        this.client = client;
        this.exposer = exposer;
        this.provisioner = provisioner;
        this.k8sHost = k8sHost;
    }

    public String name() {
        return name;
    }

    public KubernetesClient client() {
        return client;
    }

    public NodePortExposer exposer() {
        return exposer;
    }

    public ArthasProvisioner provisioner() {
        return provisioner;
    }

    public GatewayProperties.K8sHost k8sHost() {
        return k8sHost;
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception ignore) {
            // 关闭失败不影响其他 host
        }
    }
}
