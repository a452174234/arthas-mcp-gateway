package com.arthas.gateway.orchestration;

import com.arthas.gateway.handler.McpJson;
import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T020 K8S 编排列举工具契约 IT（波次 B，契约 §1/§2，K-LP-1/K-LS-1，真实 k3s + 真实业务 pod，零桩）。
 *
 * <p>经官方 MCP SDK client（{@link McpClientHarness}）连<b>网关</b> {@code /mcp} 端点驱动
 * {@code k8s.list-pods} / {@code k8s.list-services}，断言返回<b>真实集群</b>清单：
 * <ul>
 *   <li>K-LP-1：default 命名空间清单含 {@code demo-business}（hasJvm=true、hasShell=true）；
 *       namespace 过滤生效（kube-system 不含 demo-business）。</li>
 *   <li>K-LS-1：default 命名空间 service 清单非空。</li>
 *   <li>附带：{@code tools/list}=38（35 既有 + 3 编排），编排工具恒声明（回归守护 T029 前置证据）。</li>
 * </ul>
 *
 * <p><b>启用门禁</b>：仅当 {@code arthas-gateway.k8s.kubeconfig} 指向的 kubeconfig 可读时跑（真实集群）；
 * CI 无 k3s → {@link Assumptions#assumeTrue} 跳过（不报失败）。{@code @SpringBootTest} 启动时
 * {@link K8sEnabledCondition} 命中 → 编排 bean 装配 → {@code tools/list}=38。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "arthas-gateway.k8s.kubeconfig=test-env/k8s/kubeconfig/k3s-admin.yaml")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class K8sListToolsContractIT {

    /** 38 = 35 既有 arthas/网关工具 + 3 K8S 编排工具（list-pods/list-services/ensure-arthas-mcp）。 */
    private static final int EXPECTED_TOOL_COUNT = 38;

    @LocalServerPort
    private int port;

    @BeforeAll
    void requireRealCluster() {
        // 真实环境门禁：kubeconfig 不可读 → Assume 跳过（CI 无 k3s），不算失败
        Assumptions.assumeTrue(Files.isReadable(Path.of("test-env/k8s/kubeconfig/k3s-admin.yaml")),
                "跳过：未找到可读 kubeconfig（test-env/k8s/kubeconfig/k3s-admin.yaml），需真实 k3s 测试床");
    }

    private String gatewayUrl() {
        return "http://localhost:" + port + "/mcp";
    }

    // ===== tools/list = 38（编排工具恒声明） =====

    @Test
    void k_tools_list_includesThreeOrchestrationTools() {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            List<Tool> tools = h.listTools().tools();
            assertThat(tools).as("tools/list 含 38 工具（35+3 编排）").hasSize(EXPECTED_TOOL_COUNT);
            assertThat(tools.stream().map(Tool::name))
                    .as("含 3 个编排工具")
                    .contains(K8sToolRegistry.LIST_PODS, K8sToolRegistry.LIST_SERVICES,
                            K8sToolRegistry.ENSURE_ARTHAS_MCP);
        }
    }

    // ===== K-LP-1：k8s.list-pods 返回真实 pod 清单 + namespace 过滤 =====

    @Test
    @SuppressWarnings("unchecked")
    void k_lp_1_listPodsReturnsRealClusterPodsWithJvmShellMarkers() {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();

            // default 命名空间：须含 demo-business，且 hasJvm/hasShell=true（真实 JVM pod 硬证据）
            Map<String, Object> defaultResult = json(h.callTool(K8sToolRegistry.LIST_PODS, Map.of()));
            List<Map<String, Object>> defaultPods = (List<Map<String, Object>>) defaultResult.get("pods");
            assertThat(defaultPods).as("default pod 清单非空").isNotEmpty();
            Map<String, Object> demo = defaultPods.stream()
                    .filter(p -> "demo-business".equals(p.get("name")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("default 清单未含 demo-business pod"));
            assertThat(demo.get("hasJvm")).as("demo-business hasJvm=true（真实运行 JVM）").isEqualTo(true);
            assertThat(demo.get("hasShell")).as("demo-business hasShell=true").isEqualTo(true);
            assertThat(defaultResult.get("namespace")).isEqualTo("default");

            // namespace 过滤：kube-system 不含 demo-business
            Map<String, Object> ksResult = json(h.callTool(K8sToolRegistry.LIST_PODS,
                    Map.of("namespace", "kube-system")));
            List<Map<String, Object>> ksPods = (List<Map<String, Object>>) ksResult.get("pods");
            assertThat(ksPods.stream().map(p -> p.get("name")))
                    .as("kube-system 命名空间过滤生效（不含 demo-business）")
                    .doesNotContain("demo-business");
        }
    }

    // ===== K-LS-1：k8s.list-services 返回真实 service 清单 =====

    @Test
    @SuppressWarnings("unchecked")
    void k_ls_1_listServicesReturnsRealClusterServices() {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            Map<String, Object> result = json(h.callTool(K8sToolRegistry.LIST_SERVICES, Map.of()));
            List<Map<String, Object>> services = (List<Map<String, Object>>) result.get("services");
            assertThat(services).as("service 清单非空（k3s 自带 kube-dns 等）").isNotEmpty();
            assertThat(result.get("namespace")).isEqualTo("default");
            // 每条 service 含契约字段
            for (Map<String, Object> svc : services) {
                assertThat(svc).containsKeys("name", "namespace", "type", "clusterIp", "ports");
            }
        }
    }

    /** 解析 callTool 结果 TextContent 为 JSON Map。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(CallToolResult result) {
        assertThat(result).as("list-* 返回非空结果").isNotNull();
        assertThat(result.isError()).as("list-* 非 error").isNotEqualTo(Boolean.TRUE);
        StringBuilder sb = new StringBuilder();
        for (Content c : result.content()) {
            if (c instanceof TextContent tc && tc.text() != null) {
                sb.append(tc.text());
            }
        }
        assertThat(sb.length()).as("list-* 返回 TextContent 非空").isGreaterThan(0);
        return (Map<String, Object>) McpJson.MAPPER.readValue(sb.toString(), Map.class);
    }
}
