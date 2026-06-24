package com.arthas.gateway.config;

import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.orchestration.ArthasProvisioner;
import com.arthas.gateway.orchestration.K8sClientFactory;
import com.arthas.gateway.orchestration.K8sEnabledCondition;
import com.arthas.gateway.orchestration.K8sPodExplorer;
import com.arthas.gateway.orchestration.K8sToolHandlers;
import com.arthas.gateway.orchestration.NodePortExposer;
import com.arthas.gateway.orchestration.OrchestrationRecordStore;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * K8S 编排 bean 装配（003 特性，research.md R6）。
 *
 * <p>本 {@code @Configuration} 整体由 {@link K8sEnabledCondition} 把关：仅当 kubeconfig 存在且可读时，
 * 下列编排 bean 才装配（{@link K8sClientFactory} → {@link KubernetesClient} → PodExplorer/Exposer/
 * Provisioner/Handlers/RecordStore）。CI 无 k3s → 本类整体跳过 → 网关以 35 工具形态运行；本地有 k3s → 全量生效。
 *
 * <p><b>3 个编排工具的<b>规格</b>不在此</b>：{@code K8sToolRegistry.tools()} 为静态方法，由
 * {@link GatewayMcpServerConfig}（组合根）合并进 {@code tools/list}（恒 38）；仅 <b>handler</b>（{@link K8sToolHandlers}）
 * 经此条件装配、由 {@code ObjectProvider} 懒解析——故 {@code tools/list}=38 与 kubeconfig 是否存在<b>无关</b>（回归守护 T029）。
 *
 * <p><b>config→orchestration 依赖</b>：本类（组合根）允许依赖 orchestration 包；ArchUnit 边界 T030 仅禁止
 * <b>诊断核心</b>（backend/handler/tool/task/auth/obs）→ orchestration，config 不在禁止范围。
 */
@Configuration
@Conditional(K8sEnabledCondition.class)
public class K8sOrchestrationConfig {

    /** kubeconfig → fabric8 client 工厂（{@link AutoCloseable}：容器关闭释放连接池）。 */
    @Bean(destroyMethod = "close")
    K8sClientFactory k8sClientFactory(GatewayProperties props) {
        return new K8sClientFactory(props);
    }

    /** 单例 fabric8 客户端（list/exec/create 共用，线程安全）。 */
    @Bean
    KubernetesClient kubernetesClient(K8sClientFactory factory) {
        return factory.client();
    }

    /** pod/service 枚举器（list-* 工具后端）。 */
    @Bean
    K8sPodExplorer k8sPodExplorer(KubernetesClient client) {
        return new K8sPodExplorer(client);
    }

    /** NodePort 暴露器（ensure 子步）。 */
    @Bean
    NodePortExposer nodePortExposer(KubernetesClient client) {
        return new NodePortExposer(client);
    }

    /** 供给记录内存态（data-model §8，近实时可观测）。 */
    @Bean
    OrchestrationRecordStore orchestrationRecordStore() {
        return new OrchestrationRecordStore();
    }

    /** arthas 供给器（ensure 核心）。供给参数取自 {@link GatewayProperties.K8s}。 */
    @Bean
    ArthasProvisioner arthasProvisioner(KubernetesClient client, NodePortExposer exposer,
                                        DynamicBackendStore dynamicStore, OrchestrationRecordStore recordStore,
                                        GatewayProperties props) {
        GatewayProperties.K8s k = props.getK8s();
        return new ArthasProvisioner(client, exposer, dynamicStore, recordStore,
                k.getTargetIp(), k.getArthasBootJar(), k.getMcpPort(), k.getArthasVersion(),
                k.getArthasPassword());
    }

    /** 3 个编排工具的本地处理器（handler 自带闭包，不经 ToolsCallRouter）。 */
    @Bean
    K8sToolHandlers k8sToolHandlers(K8sPodExplorer explorer, ArthasProvisioner provisioner, GatewayProperties props) {
        return new K8sToolHandlers(explorer, provisioner, props);
    }
}
