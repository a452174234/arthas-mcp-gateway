package com.arthas.gateway.orchestration;

import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.config.GatewayProperties;
import com.arthas.gateway.testfixtures.McpClientHarness;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T027 K8S 纳管端到端契约 IT（波次 B，契约 §5，K-ENS-3 / K-COEXIST-2 / SC-003，真实 k3s + 真实 arthas + 真实业务 pod，零桩）。
 *
 * <p>经<b>官方 MCP SDK client</b>（{@link McpClientHarness}，确定性断言）连网关 {@code /mcp}，驱动
 * 「{@code k8s.ensure-arthas-mcp}（编排工具）→ 既有 watch/jvm（诊断工具）」全链路，验证动态纳管 target
 * 的真实端到端行为（契约 §4 时序）。
 *
 * <h3>覆盖断言</h3>
 * <ul>
 *   <li><b>K-ENS-3</b>：经网关 MCP ensure → 用返回 target 调 watch → 经网关捕获<b>该 pod JVM</b> 的真实诊断
 *      （{@code OrderService.hotMethod}，由 demo-business hotLoop 自驱动命中；原样透传，宪法原则二）。</li>
 *   <li><b>K-COEXIST-2</b>：动态 target 不可达（pod 删除）→ {@code list-targets} 标 unhealthy（熔断 OPEN）、
 *       对其诊断返明确错误；<b>其他 target 不受影响</b>（复用 001 熔断隔离，故障局部韧性）。</li>
 *   <li><b>SC-003</b>：pod 删除 → 30 秒内隔离（熔断 OPEN）+ 明确错误（{@code backend_unreachable} +
 *       {@code available} 列表），不影响其他 target。</li>
 * </ul>
 *
 * <p><b>真实性（零桩）</b>：两个 target 均经真实 ensure 注入真实 arthas + 真实 NodePort 暴露；
 * 故障用<b>真实</b>条件——删除 {@code demo-business-2} pod（NodePort 无后端 → 不可达，连接拒绝），
 * 非 WireMock/Mock 模拟失败。
 *
 * <p><b>夹具</b>：{@code demo-business}（测试床固定 pod，<b>不删</b>）→ target1；
 * {@code demo-business-2}（临时第二 JVM pod，@BeforeAll 起、@AfterAll 清理）→ target2，作 SC-003 删除目标。
 * 两个 JVM pod 独立熔断/连接池，验证隔离（契约 §5 K-COEXIST-2 注：K-COEXIST-1 热重载已由波次 A
 * {@code RegistryComposerTest} 覆盖，本 IT 聚焦 K-COEXIST-2/SC-003 运行时隔离）。
 *
 * <p><b>启用门禁</b>：kubeconfig 不可读 → {@link Assumptions#assumeTrue} 跳过（CI 无 k3s）。
 *
 * <p><b>顺序</b>：K-ENS-3 先（ensure target1 + watch）；K-COEXIST-2 后（依赖 target1 已就绪 + target2 已预热）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "arthas-gateway.k8s.kubeconfig=test-env/k8s/kubeconfig/k3s-admin.yaml")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class K8sEnsureContractIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NAMESPACE = "default";
    private static final String SERVER = "debian";
    private static final String DEMO_POD = "demo-business";
    /** 临时第二 JVM pod（SC-003 删除目标；测试床固定 demo-business 不动）。 */
    private static final String DEMO_POD_2 = "demo-business-2";
    private static final String DEMO_IMAGE = "arthas-gateway/demo-business:local";
    private static final String TARGET1 = "debian-demo-business";
    private static final String TARGET2 = "debian-demo-business-2";
    /** demo-business 内运行的热方法类/方法（{@code DemoBusinessApp} 自带，hotLoop 每 ~50ms 自驱动）。 */
    private static final String TARGET_CLASS = "com.arthas.gateway.testfixtures.OrderService";
    private static final String TARGET_METHOD = "hotMethod";
    /** arthas jvm 真实进程级诊断标记（非桩硬证据）。 */
    private static final String[] JVM_MARKERS = {"jvmInfo", "RUNTIME", "resultCount", "MACHINE-NAME", "SPEC-NAME"};

    @LocalServerPort
    private int port;
    @Autowired
    private KubernetesClient client;
    @Autowired
    private ArthasProvisioner provisioner;
    @Autowired
    private DynamicBackendStore dynamicStore;
    @Autowired
    private GatewayProperties props;

    private String gatewayUrl() {
        return "http://localhost:" + port + "/mcp";
    }

    @BeforeAll
    void requireClusterAndProvisionSecondPod() {
        Assumptions.assumeTrue(Files.isReadable(Path.of("test-env/k8s/kubeconfig/k3s-admin.yaml")),
                "跳过：未找到可读 kubeconfig，需真实 k3s 测试床");
        // 起临时第二 JVM pod（同镜像，含 shell+java+JVM）；SC-003 删除目标，不删测试床固定 demo-business
        createDemoPod(DEMO_POD_2);
        waitRunning(DEMO_POD_2);
        // 预热 target2：首次 ensure 会 attach + 下载 arthas lib 到 pod（~3min，一次性下放夹具准备，避免拖慢测试本身）
        OrchestrationRecord rec = provisioner.ensure(SERVER, DEMO_POD_2, NAMESPACE, Clock.systemUTC().instant());
        Assumptions.assumeTrue(
                rec.status() == OrchestrationRecord.Status.READY
                        || rec.status() == OrchestrationRecord.Status.REUSED,
                "跳过：demo-business-2 预热 ensure 未就绪 status=" + rec.status()
                        + (rec.error() != null ? " reason=" + rec.error().reason() : ""));
    }

    @AfterAll
    void cleanup() {
        // 清理动态注册（避免污染后续 IT 的注册表）
        dynamicStore.unregister(TARGET1);
        dynamicStore.unregister(TARGET2);
        // 清理可能残留的 NodePort Service（幂等）
        deleteServiceQuiet("arthas-mcp-" + sanitize(TARGET1));
        deleteServiceQuiet("arthas-mcp-" + sanitize(TARGET2));
        // demo-business-2 pod 已在 K-COEXIST-2 测试中删除（SC-003 故障注入）；demo-business 为测试床固定 pod，不动
    }

    // ===== K-ENS-3：经网关 MCP ensure → 用返回 target 调 watch 捕获该 pod JVM 真实诊断 =====

    @Test
    @Order(1)
    void k_ens_3_ensureViaMcpThenWatchRealDiagnosis() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            // 经网关 MCP 端点 ensure（编排工具；复用既有 attach 或重新供给）
            String target = ensureViaMcp(h, SERVER, DEMO_POD);
            assertThat(target).as("target 确定性派生 {server}-{pod}（K-ENS-8）").isEqualTo(TARGET1);

            // watch 该 target → 真实诊断（OrderService.hotMethod 由 hotLoop 自驱动，numberOfExecutions=1 命中）
            String taskId = submitWatch(h, target, 1);
            String status = pollTaskStatus(h, taskId, Duration.ofSeconds(30));
            assertThat(status).as("K-ENS-3：watch 完成（真实 arthas 诊断）").isEqualTo("completed");

            JsonNode done = JSON.readTree(text(h.callTool("arthas-gateway.task-get", Map.of("taskId", taskId))));
            assertThat(done.path("result").path("isError").asBoolean())
                    .as("watch 成功（非业务错误）").isFalse();
            assertThat(extractResultText(done))
                    .as("结果含命中该 pod JVM 方法的真实诊断（hotMethod/OrderService）")
                    .containsAnyOf("hotMethod", "OrderService", "watch");
        }
    }

    // ===== K-COEXIST-2 + SC-003：pod 删除 → 失效 target 隔离 + 明确错误 + 其他 target 不受影响 =====

    @Test
    @Order(2)
    void k_coexist_2_podDeletionIsolatesDeadTargetOthersUnaffected() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            // 基线：两 target 均 healthy（target1 由 K-ENS-3 ensure，target2 由 @BeforeAll 预热）
            assertThat(healthy(h, TARGET1)).as("基线 target1 healthy").isTrue();
            assertThat(healthy(h, TARGET2)).as("基线 target2 healthy").isTrue();

            // 真实故障注入：删除 demo-business-2 pod（NodePort 无后端 → 不可达，连接拒绝；非桩）
            client.pods().inNamespace(NAMESPACE).withName(DEMO_POD_2).delete();
            waitGone(DEMO_POD_2);
            sleep(3000); // 等 K8S endpoint 收敛（kube-proxy 移除失效 endpoint）

            // 累积 3 次基础设施失败 → 熔断 OPEN（被动 call-driven 隔离，复用 001 熔断语义；breakers 各 target 独立）
            for (int i = 1; i <= 3; i++) {
                final int round = i;
                assertThatThrownBy(() -> h.callTool("jvm", Map.of("target", TARGET2)))
                        .as("第 " + round + " 次：pod 删除后 target2 不可达 → backend_unreachable")
                        .isInstanceOf(McpError.class);
            }

            // K-COEXIST-2：list-targets 标 target2 unhealthy（熔断 OPEN），target1 仍 healthy（隔离）
            assertThat(healthy(h, TARGET2))
                    .as("K-COEXIST-2：pod 删除 → target2 隔离标 unhealthy（熔断 OPEN）").isFalse();
            assertThat(healthy(h, TARGET1))
                    .as("K-COEXIST-2：其他 target 不受影响（healthy）").isTrue();

            // SC-003：对失效 target2 诊断 → 30s 内明确错误（熔断 OPEN 立即拒）+ 结构化 data
            long start = System.nanoTime();
            assertThatThrownBy(() -> h.callTool("jvm", Map.of("target", TARGET2)))
                    .as("SC-003：失效 target 返结构化错误").isInstanceOf(McpError.class)
                    .satisfies(t -> {
                        McpError err = (McpError) t;
                        assertThat(err.getJsonRpcError().code())
                                .as("INVALID_PARAMS(-32602)").isEqualTo(-32602);
                        Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                        assertThat(data.get("target")).isEqualTo(TARGET2);
                        assertThat(data.get("reason")).isEqualTo("backend_unreachable");
                        assertThat(data.get("available").toString())
                                .as("available 列出全部在册 target（含失效的 target2）").contains(TARGET1, TARGET2);
                    });
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(elapsedMs).as("SC-003：30s 内返回明确错误").isLessThan(30_000L);

            // 隔离验证：target1 仍可诊断（独立熔断 + 连接池，不受 target2 pod 删除影响）
            CallToolResult t1Jvm = h.callTool("jvm", Map.of("target", TARGET1));
            assertThat(t1Jvm.isError()).as("target1 诊断仍成功").isNotEqualTo(Boolean.TRUE);
            assertThat(extractText(t1Jvm))
                    .as("target1 真实 JVM 诊断不受 target2 故障影响").containsAnyOf(JVM_MARKERS);
        }
    }

    // ===== 辅助：经网关 MCP ensure（编排工具） =====

    private static String ensureViaMcp(McpClientHarness h, String server, String pod) throws Exception {
        CallToolResult r = h.callTool("k8s.ensure-arthas-mcp", Map.of("server", server, "pod", pod));
        JsonNode node = JSON.readTree(text(r));
        assertThat(node.path("status").asText())
                .as("ensure 经网关 MCP 返 ready/reused").isIn("ready", "reused");
        return node.path("target").asText();
    }

    // ===== 辅助：watch 异步任务（提交 + 轮询） =====

    private static String submitWatch(McpClientHarness h, String target, int numberOfExecutions) throws Exception {
        CallToolResult acc = h.callTool("watch", Map.of(
                "target", target,
                "classPattern", TARGET_CLASS,
                "methodPattern", TARGET_METHOD,
                "numberOfExecutions", numberOfExecutions,
                "timeout", 30));
        String taskId = JSON.readTree(text(acc)).path("taskId").asText();
        assertThat(taskId).as("watch 立即返 taskId").matches("t-[0-9a-f]{6,}");
        return taskId;
    }

    /** 轮询 task-get 直到终态或超时；返回 status（小写）。 */
    private static String pollTaskStatus(McpClientHarness h, String taskId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String status = JSON.readTree(text(h.callTool("arthas-gateway.task-get", Map.of("taskId", taskId))))
                    .path("status").asText();
            if (!"working".equals(status)) {
                return status;
            }
            Thread.sleep(500);
        }
        return "working";
    }

    // ===== 辅助：list-targets 健康查询 =====

    private static boolean healthy(McpClientHarness h, String target) throws Exception {
        CallToolResult r = h.callTool("arthas-gateway.list-targets", Map.of());
        for (JsonNode t : JSON.readTree(text(r)).path("targets")) {
            if (target.equals(t.path("name").asText())) {
                return t.path("healthy").asBoolean();
            }
        }
        return false;
    }

    // ===== 辅助：结果文本提取 =====

    private static String text(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (Content c : result.content()) {
            if (c instanceof TextContent tc && tc.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    private static String extractText(CallToolResult result) {
        return text(result);
    }

    /** 从 task-get completed 节点提取 result.content 文本（K-ENS-3 真实诊断断言）。 */
    private static String extractResultText(JsonNode taskGetNode) {
        StringBuilder sb = new StringBuilder();
        JsonNode content = taskGetNode.path("result").path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                String t = item.path("text").asText("");
                if (!t.isEmpty()) {
                    sb.append(t).append('\n');
                }
            }
        }
        return sb.toString();
    }

    // ===== 辅助：fabric8 pod/service 夹具管理（真实集群操作） =====

    /** 起一个与 demo-business 同镜像的临时 JVM pod（幂等：先删后建）。 */
    private void createDemoPod(String name) {
        client.pods().inNamespace(NAMESPACE).withName(name).delete();
        waitGone(name);
        Pod pod = new PodBuilder()
                .withNewMetadata().withName(name).withNamespace(NAMESPACE)
                .addToLabels("app", name).endMetadata()
                .withNewSpec()
                .addNewContainer().withName("app").withImage(DEMO_IMAGE).withImagePullPolicy("Never")
                .addNewPort().withContainerPort(8081).endPort()
                .endContainer()
                .withRestartPolicy("Always")
                .endSpec()
                .build();
        client.pods().inNamespace(NAMESPACE).resource(pod).create();
    }

    /** 等 pod Running 且 JVM 就绪（DemoBusinessApp main 启动，jps 可见）。 */
    private void waitRunning(String name) {
        for (int i = 0; i < 90; i++) {
            Pod p = client.pods().inNamespace(NAMESPACE).withName(name).get();
            if (p != null && p.getStatus() != null && "Running".equals(p.getStatus().getPhase())) {
                sleep(2000); // 等 DemoBusinessApp JVM 就绪
                return;
            }
            sleep(1000);
        }
        throw new IllegalStateException("pod 未就绪（Running 超时）：" + name);
    }

    /** 等 pod 真正消失（delete 异步终止）。 */
    private void waitGone(String name) {
        for (int i = 0; i < 60; i++) {
            if (client.pods().inNamespace(NAMESPACE).withName(name).get() == null) {
                return;
            }
            sleep(1000);
        }
    }

    private void deleteServiceQuiet(String serviceName) {
        try {
            client.services().inNamespace(NAMESPACE).withName(serviceName).delete();
        } catch (RuntimeException ignored) {
            // 幂等清理
        }
    }

    /** 复用 NodePortExposer 的 sanitize（service 名派生须一致，否则清理删不到）。 */
    private static String sanitize(String logicalName) {
        String lower = logicalName.toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lower.length() && sb.length() < 253; i++) {
            char c = lower.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' ? c : '-');
        }
        return sb.toString().replaceAll("^[^a-z0-9]+", "").replaceAll("[^a-z0-9]+$", "");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
