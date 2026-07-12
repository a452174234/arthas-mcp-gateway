package com.arthas.gateway.orchestration;

import com.arthas.gateway.config.GatewayProperties;

/**
 * HostEntry 工厂（006 波2，T016）：按 {@link GatewayProperties.K8sHost} 建 client + exposer + provisioner 三件套。
 *
 * <p>解耦 {@link K8sHostStore} 的 lifecycle diff 逻辑与实际 client/provisioner 构造（便于单测注入 mock，
 * 实际构造由 {@code K8sOrchestrationConfig} 装配的真实实现承担：kubeconfig→buildFromKubeconfig / ssh→buildFromSsh）。
 */
@FunctionalInterface
public interface HostEntryFactory {

    /** 按 host 配置建 HostEntry（含 client/exposer/provisioner；调用方负责 close）。 */
    HostEntry create(GatewayProperties.K8sHost host);
}
