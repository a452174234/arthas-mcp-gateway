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
