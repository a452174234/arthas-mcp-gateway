package com.arthas.gateway.orchestration;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.NodeListBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.ServiceListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 005 US1 NodePortExposer Service 复用单测（T009，K-ENS-10/11/12）。
 *
 * <p>用 fabric8 官方 {@link KubernetesMockServer}（请求-响应模式，模拟真实 K8S API server）验证 NodePortExposer
 * 的<b>决策逻辑</b>（findLabeledService 命中走 patch / 未命中回退新建 / 幂等复用）。NodePortExposer 直接持
 * {@link KubernetesClient} 调 fluent 链，与 fabric8 紧耦合——Mockito 深桩对 fabric8 复杂泛型链不可靠，
 * 故用 fabric8 官方 mock server（labelSelector 查询、PUT、POST 真实 HTTP 模拟）。真实 k3s 端到端（含
 * NodePort 分配）由 {@code NodePortExposerContractIT}（T010）覆盖。
 *
 * <h3>覆盖断言</h3>
 * <ul>
 *   <li><b>K-ENS-10 复用</b>：带 {@code arthas-mcp-gateway/target} label 的现有 NodePort Service → ensure 复用
 *       （result.serviceName = 业务 Service 名，非 {@code arthas-mcp-} 前缀，不新建独立 Service）。</li>
 *   <li><b>K-ENS-11</b>：带 label 的 ClusterIP Service → patch（PUT type=NodePort + 加端口），复用业务 Service 名。</li>
 *   <li><b>K-ENS-12 幂等</b>：已含同 targetPort → 复用既有 nodePort（二次 ensure ports 不变）。</li>
 *   <li><b>K-ENS-10 回退</b>：无带 label Service → 回退新建独立 Service（{@code arthas-mcp-} 前缀，003 现状）。</li>
 * </ul>
 */
@EnableKubernetesMockClient(crud = false)
class NodePortExposerTest {

    private static final String NS = "default";
    private static final String POD = "demo-business";
    private static final String LOGICAL = "debian-demo-business";
    private static final String LABEL = NodePortExposer.sanitizeLabelValue(LOGICAL);
    private static final int MCP_PORT = 8563;
    private static final String NODE_IP = "192.168.31.92";

    // fabric8 注解注入（PER_METHOD：每测试独立 mock server）
    KubernetesMockServer mockServer;
    KubernetesClient client;

    /** K-ENS-10：带 label 的现有 NodePort Service → ensure 复用（不新建独立 Service）。 */
    @Test
    void k_ens_10_reuseExistingLabeledNodePortService() {
        Service business = service("business-svc", LABEL, "NodePort", MCP_PORT, 32001);
        registerCommon();
        registerLabeledList(business);

        NodePortExposer exposer = new NodePortExposer(client);
        NodePortExposer.ExposeResult r = exposer.expose(NS, POD, LOGICAL, MCP_PORT);

        assertThat(r.serviceName()).as("K-ENS-10：复用业务 Service 名（非 arthas-mcp- 前缀）")
                .isEqualTo("business-svc");
        assertThat(r.nodePort()).as("K-ENS-10：复用既有 nodePort").isEqualTo(32001);
        assertThat(r.mcpUrl()).isEqualTo("http://" + NODE_IP + ":32001");
    }

    /** K-ENS-11：带 label 的 ClusterIP Service → patch（PUT type=NodePort + 加端口），复用业务 Service。 */
    @Test
    void k_ens_11_clusterIpServicePatchedToNodePort() {
        Service clusterIp = service("business-svc", LABEL, "ClusterIP", MCP_PORT, null);
        Service patched = service("business-svc", LABEL, "NodePort", MCP_PORT, 32005);
        registerCommon();
        registerLabeledList(clusterIp);
        // patchServiceAddNodePort update：fabric8 update() 在 svc 无 resourceVersion 时先 GET 拿版本再 PUT。
        mockServer.expect().get().withPath("/api/v1/namespaces/default/services/business-svc")
                .andReturn(200, clusterIp).always();
        // PUT（patch type=NodePort + 加端口）→ 返回 patch 后 Service（K8S 分配 nodePort=32005）
        mockServer.expect().put().withPath("/api/v1/namespaces/default/services/business-svc")
                .andReturn(200, patched).always();

        NodePortExposer exposer = new NodePortExposer(client);
        NodePortExposer.ExposeResult r = exposer.expose(NS, POD, LOGICAL, MCP_PORT);

        assertThat(r.serviceName()).as("K-ENS-11：复用业务 Service 名（patch 而非新建）")
                .isEqualTo("business-svc");
        assertThat(r.nodePort()).as("K-ENS-11：patch 后分配的 nodePort").isEqualTo(32005);
    }

    /** K-ENS-12：已含同 targetPort 的 NodePort Service → 复用既有 nodePort（二次 ensure ports 不变）。 */
    @Test
    void k_ens_12_idempotentReuseSameNodePort() {
        Service business = service("business-svc", LABEL, "NodePort", MCP_PORT, 32001);
        registerCommon();
        registerLabeledList(business);

        NodePortExposer exposer = new NodePortExposer(client);
        NodePortExposer.ExposeResult first = exposer.expose(NS, POD, LOGICAL, MCP_PORT);
        NodePortExposer.ExposeResult second = exposer.expose(NS, POD, LOGICAL, MCP_PORT);

        assertThat(second.serviceName()).as("K-ENS-12：复用业务 Service（非新建 arthas-mcp-）")
                .isEqualTo("business-svc").isEqualTo(first.serviceName());
        assertThat(second.nodePort()).as("K-ENS-12：二次 ensure nodePort 不变")
                .isEqualTo(32001).isEqualTo(first.nodePort());
    }

    /** K-ENS-10 回退：无带 label 的 Service → 回退新建独立 Service（arthas-mcp- 前缀，003 现状）。 */
    @Test
    void k_ens_10_fallbackNewServiceWhenNoLabel() {
        Service created = service("arthas-mcp-debian-demo-business", LABEL, "NodePort", MCP_PORT, 32010);
        registerCommon();
        registerLabeledList(); // 空列表 → 触发回退
        // ensureNodePortService（回退）：POST create → created；GET withName → created
        mockServer.expect().post().withPath("/api/v1/namespaces/default/services")
                .andReturn(201, created).always();
        mockServer.expect().get().withPath("/api/v1/namespaces/default/services/arthas-mcp-debian-demo-business")
                .andReturn(200, created).always();

        NodePortExposer exposer = new NodePortExposer(client);
        NodePortExposer.ExposeResult r = exposer.expose(NS, POD, LOGICAL, MCP_PORT);

        assertThat(r.serviceName()).as("K-ENS-10 回退：新建独立 Service（arthas-mcp- 前缀）")
                .startsWith("arthas-mcp-").contains("debian-demo-business");
        assertThat(r.nodePort()).isEqualTo(32010);
    }

    /** pod 不存在 → IllegalStateException（既有行为，003 兼容）。 */
    @Test
    void exposeThrowsWhenPodMissing() {
        mockServer.expect().get().withPath("/api/v1/namespaces/default/pods/demo-business")
                .andReturn(404, null).always();

        NodePortExposer exposer = new NodePortExposer(client);

        assertThatThrownBy(() -> exposer.expose(NS, POD, LOGICAL, MCP_PORT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(POD);
    }

    // ===== mock server 端点注册辅助 =====

    /** 注册公共端点：pod get/update（labelPod）、nodes list（resolveNodeIp）。 */
    private void registerCommon() {
        mockServer.expect().get().withPath("/api/v1/namespaces/default/pods/demo-business")
                .andReturn(200, podWithoutLabel()).always();
        // labelPod update（pod 无 label → 打 label；PUT 返回 pod）
        mockServer.expect().put().withPath("/api/v1/namespaces/default/pods/demo-business")
                .andReturn(200, podWithoutLabel()).always();
        mockServer.expect().get().withPath("/api/v1/nodes")
                .andReturn(200, new NodeListBuilder().withItems(nodeWithInternalIp(NODE_IP)).build()).always();
    }

    /**
     * 注册 labelSelector list 端点（findLabeledService 查询）。
     *
     * <p>fabric8 mock server 用精确匹配（path + query）。fabric8 client 发的 labelSelector 请求 path 为
     * {@code /services?labelSelector=arthas-mcp-gateway%2Ftarget%3D<value>}（{@code /}→{@code %2F}，
     * {@code =}→{@code %3D}），故 withPath 须含完整 encoded query。
     *
     * @param services 命中的带 label Service 列表（空 → findLabeledService 返空，触发回退）
     */
    private void registerLabeledList(Service... services) {
        ServiceList list = new ServiceListBuilder().withItems(services).build();
        String path = "/api/v1/namespaces/default/services?labelSelector="
                + "arthas-mcp-gateway%2Ftarget%3D" + LABEL;
        mockServer.expect().get().withPath(path).andReturn(200, list).always();
    }

    // ===== fabric8 model 构造辅助 =====

    private static Service service(String name, String label, String type, int port, Integer nodePort) {
        return new ServiceBuilder()
                .withNewMetadata().withName(name).withNamespace(NS)
                .addToLabels(NodePortExposer.TARGET_LABEL_KEY, label).endMetadata()
                .withNewSpec().withType(type)
                .addNewPort().withPort(port).withNewTargetPort(port)
                .withNodePort(nodePort).endPort()
                .endSpec()
                .build();
    }

    private static Pod podWithoutLabel() {
        return new PodBuilder()
                .withNewMetadata().withName(POD).withNamespace(NS).endMetadata()
                .withNewSpec().endSpec()
                .build();
    }

    private static Node nodeWithInternalIp(String ip) {
        return new NodeBuilder()
                .withNewMetadata().withName("node1").endMetadata()
                .withNewStatus()
                .addNewAddress().withType("InternalIP").withAddress(ip).endAddress()
                .endStatus()
                .build();
    }
}
