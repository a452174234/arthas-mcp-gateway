package com.arthas.gateway.orchestration;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 005 US1 NodePortExposer Service 复用契约 IT（T010，K-ENS-10/11/12，真实 k3s + 真实 demo-business pod，零桩）。
 *
 * <p>直接对真实 {@link KubernetesClient} 装配的 {@link NodePortExposer} 发起 {@code expose}，断言<b>真实 K8S</b>
 * 行为（labelSelector 查询、PATCH type、NodePort 分配），覆盖单测（{@link NodePortExposerTest}）无法触及的
 * 服务端 nodePort 分配与 type 变更副作用。
 *
 * <h3>覆盖断言</h3>
 * <ul>
 *   <li><b>K-ENS-10 复用</b>：预打 {@code arthas-mcp-gateway/target} label 的现有 NodePort Service → ensure 复用
 *       （不新建独立 Service，result.serviceName = 业务 Service 名）。</li>
 *   <li><b>K-ENS-11</b>：预打 label 的 ClusterIP Service → patch type=NodePort + 加端口，K8S 分配 nodePort。
 *       复用业务 Service 名（非 arthas-mcp-）。</li>
 *   <li><b>K-ENS-12 幂等</b>：二次 ensure → nodePort 不变 + 端口数量不重复增长。</li>
 *   <li><b>K-ENS-10 回退</b>：无带 label Service → 回退新建独立 Service（arthas-mcp- 前缀，003 现状）。</li>
 * </ul>
 *
 * <p><b>启用门禁</b>：kubeconfig 不可读 / demo-business pod 不存在 → {@link Assumptions#assumeTrue} 跳过（CI 无 k3s）。
 *
 * <p><b>隔离</b>：每测试用独立 logicalName + 业务 Service（@AfterEach 清理创建的 Service，不删测试床 demo-business pod）。
 */
@SpringBootTest
@TestPropertySource(properties = "arthas-gateway.k8s.kubeconfig=test-env/k8s/kubeconfig/k3s-admin.yaml")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NodePortExposerContractIT {

    private static final String NS = "default";
    private static final String POD = "demo-business";

    @Autowired
    private KubernetesClient client;

    /** 本测试创建的 Service 名（@AfterEach 幂等清理）。 */
    private final Set<String> createdServices = new HashSet<>();

    @BeforeAll
    void requireClusterAndPod() {
        Assumptions.assumeTrue(Files.isReadable(Path.of("test-env/k8s/kubeconfig/k3s-admin.yaml")),
                "跳过：未找到可读 kubeconfig，需真实 k3s 测试床");
        Assumptions.assumeTrue(client.pods().inNamespace(NS).withName(POD).get() != null,
                "跳过：测试床 pod " + POD + " 不存在（NodePortExposer.labelPod 需真实 pod）");
    }

    @AfterEach
    void cleanupServices() {
        for (String name : createdServices) {
            deleteServiceQuiet(name);
        }
        createdServices.clear();
    }

    /** K-ENS-10：预打 label 的现有 NodePort Service → ensure 复用（不新建独立 Service）。 */
    @Test
    void k_ens_10_reuseLabeledNodePortService() {
        String logical = "contractit-reuse";
        String svcName = createLabeledService("biz-contractit-reuse", "NodePort", 8563, 30050, logical);

        NodePortExposer exposer = new NodePortExposer(client);
        NodePortExposer.ExposeResult r = exposer.expose(NS, POD, logical, 8563);

        assertThat(r.serviceName()).as("K-ENS-10：复用业务 Service（非 arthas-mcp- 新建）").isEqualTo(svcName);
        assertThat(r.nodePort()).as("K-ENS-10：复用既有 nodePort").isEqualTo(30050);
    }

    /** K-ENS-11：预打 label 的 ClusterIP Service → patch type=NodePort + 加端口，K8S 分配 nodePort。 */
    @Test
    void k_ens_11_clusterIpServicePatchedToNodePort(TestInfo info) {
        String logical = "contractit-cip";
        String svcName = createLabeledService("biz-contractit-cip", "ClusterIP", 8563, null, logical);

        NodePortExposer exposer = new NodePortExposer(client);
        NodePortExposer.ExposeResult r = exposer.expose(NS, POD, logical, 8563);

        assertThat(r.serviceName()).as("K-ENS-11：复用业务 Service（patch 而非新建）").isEqualTo(svcName);
        assertThat(r.nodePort()).as("K-ENS-11：K8S 分配的 nodePort 在默认范围内").isBetween(30000, 32767);
        Service after = client.services().inNamespace(NS).withName(svcName).get();
        assertThat(after.getSpec().getType()).as("K-ENS-11：Service type 改为 NodePort").isEqualTo("NodePort");
    }

    /** K-ENS-12：二次 ensure → nodePort 不变 + 端口数量不重复增长（幂等）。 */
    @Test
    void k_ens_12_idempotentReuse() {
        String logical = "contractit-idem";
        String svcName = createLabeledService("biz-contractit-idem", "NodePort", 8563, 30052, logical);

        NodePortExposer exposer = new NodePortExposer(client);
        NodePortExposer.ExposeResult first = exposer.expose(NS, POD, logical, 8563);
        NodePortExposer.ExposeResult second = exposer.expose(NS, POD, logical, 8563);

        assertThat(second.nodePort()).as("K-ENS-12：二次 ensure nodePort 不变")
                .isEqualTo(first.nodePort()).isEqualTo(30052);
        Service after = client.services().inNamespace(NS).withName(svcName).get();
        assertThat(after.getSpec().getPorts()).as("K-ENS-12：端口数量不重复增长").hasSize(1);
        assertThat(after.getSpec().getPorts().stream().map(ServicePort::getNodePort).findFirst().orElse(null))
                .as("K-ENS-12：端口仍含既有 nodePort").isEqualTo(30052);
    }

    /** K-ENS-10 回退：无带 label Service → 回退新建独立 Service（arthas-mcp- 前缀，003 现状）。 */
    @Test
    void k_ens_10_fallbackNewWhenNoLabel() {
        String logical = "contractit-fallback"; // 不预创建带 label 的业务 Service
        String expected = "arthas-mcp-" + NodePortExposer.sanitizeLabelValue(logical);
        createdServices.add(expected);

        NodePortExposer exposer = new NodePortExposer(client);
        NodePortExposer.ExposeResult r = exposer.expose(NS, POD, logical, 8563);

        assertThat(r.serviceName()).as("K-ENS-10 回退：新建独立 Service（arthas-mcp- 前缀）")
                .startsWith("arthas-mcp-");
        assertThat(r.nodePort()).as("K-ENS-10 回退：K8S 分配 nodePort").isBetween(30000, 32767);
    }

    // ===== fabric8 Service 夹具管理（真实集群操作） =====

    /** 预创建带 label 的业务 Service（createOrReplace 幂等），登记待清理。 */
    private String createLabeledService(String name, String type, int port, Integer nodePort, String logical) {
        createdServices.add(name);
        String label = NodePortExposer.sanitizeLabelValue(logical);
        Service svc = new ServiceBuilder()
                .withNewMetadata().withName(name).withNamespace(NS)
                .addToLabels(NodePortExposer.TARGET_LABEL_KEY, label).endMetadata()
                .withNewSpec().withType(type)
                .addToSelector("app", "demo-business")
                .addNewPort().withPort(port).withNewTargetPort(port).withNodePort(nodePort).endPort()
                .endSpec()
                .build();
        client.services().inNamespace(NS).resource(svc).createOrReplace();
        return name;
    }

    private void deleteServiceQuiet(String name) {
        try {
            client.services().inNamespace(NS).withName(name).delete();
        } catch (RuntimeException ignored) {
            // 幂等清理
        }
    }
}
