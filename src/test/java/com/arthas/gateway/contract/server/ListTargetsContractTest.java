package com.arthas.gateway.contract.server;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.handler.GatewayToolHandlers;
import com.arthas.gateway.task.AsyncTaskExecutor;
import com.arthas.gateway.task.TaskStore;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T038 {@code list-targets} 契约测试（surefire，handler 级纯逻辑，零 MCP 传输 / 零真实后端）。
 *
 * <p>补 {@code GatewayToolsContractTest.g_lt_1}（单 target）未覆盖的多 target + healthy 派生 + 无参契约：
 * <ul>
 *   <li><b>G-LT-1 多 target</b>：注册表含多个 target → {@code list-targets} 全量返回（name/state/protocol + version）。</li>
 *   <li><b>G-LT-1 healthy 派生</b>：{@code healthy = state==ACTIVE && breaker.state()!=OPEN}。注入一个熔断 OPEN 的 target
 *       （3 次 {@code recordFailure} 触发阈值）→ {@code healthy=false}，但<b>仍列出</b>（便于诊断，gateway-tools-contract.md §1）。
 *       熔断由 {@link com.arthas.gateway.backend.CircuitBreaker} 自身 API 驱动（网关自有逻辑，非 arthas 桩）。</li>
 *   <li><b>G-LT-2 无需 target 参数</b>：以空参 {@code Map.of()} 调用即成功，响应不依赖/回显 target。</li>
 * </ul>
 *
 * <p>「热重载后内容更新」由 {@code HotReloadIT}（真实文件监听）端到端覆盖；本测聚焦多 target 响应整形 + healthy 派生逻辑。
 */
class ListTargetsContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TaskStore store;
    private AsyncTaskExecutor executor;
    private RegistryHolder registry;
    private GatewayToolHandlers handlers;

    @BeforeEach
    void setUp() {
        BackendEntryFactory factory = new BackendEntryFactory();
        BackendEntry order = factory.create(noneAuth("order", "http://127.0.0.1:1"));     // ACTIVE + CLOSED
        BackendEntry payment = factory.create(noneAuth("payment", "http://127.0.0.1:2")); // ACTIVE + CLOSED
        BackendEntry inventory = factory.create(noneAuth("inventory", "http://127.0.0.1:3"));
        // 连续 3 次基础设施失败 → 熔断 OPEN（DEFAULT_FAILURE_THRESHOLD=3）
        inventory.breaker().recordFailure();
        inventory.breaker().recordFailure();
        inventory.breaker().recordFailure();

        Map<String, BackendEntry> byName = new LinkedHashMap<>();
        byName.put("order", order);
        byName.put("payment", payment);
        byName.put("inventory", inventory);

        store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));
        registry = new RegistryHolder();
        registry.getAndSet(new BackendRegistry(42L, byName));
        handlers = new GatewayToolHandlers(registry, executor);
    }

    @AfterEach
    void tearDown() {
        executor.close();
        store.close();
    }

    // ===== G-LT-1：多 target 全量返回 + version =====

    @Test
    void g_lt_1_multiTarget_returnsAllWithStateProtocolAndVersion() throws Exception {
        JsonNode node = json(listTargets());

        assertThat(node.path("version").asLong()).as("回显注册表 version").isEqualTo(42L);
        assertThat(node.path("targets").isArray()).isTrue();
        assertThat(node.path("targets")).as("多 target 全量列出").hasSize(3);

        // order / payment：ACTIVE + 熔断 CLOSED → healthy=true
        JsonNode order = target(node, "order");
        assertThat(order.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(order.path("protocol").asText()).isEqualTo("STREAMABLE");
        assertThat(order.path("healthy").asBoolean()).as("ACTIVE+CLOSED → healthy").isTrue();

        JsonNode payment = target(node, "payment");
        assertThat(payment.path("healthy").asBoolean()).isTrue();
    }

    // ===== G-LT-1：healthy=false（熔断 OPEN）仍列出 =====

    @Test
    void g_lt_1_openBreaker_marksUnhealthyButStillListed() throws Exception {
        JsonNode node = json(listTargets());

        JsonNode inventory = target(node, "inventory");
        assertThat(inventory).as("熔断 target 仍列出（便于诊断）").isNotNull();
        assertThat(inventory.path("healthy").asBoolean())
                .as("breaker OPEN → healthy=false")
                .isFalse();
    }

    // ===== G-LT-2：无需 target 参数 =====

    @Test
    void g_lt_2_noTargetParameterRequired() throws Exception {
        // 空参调用成功 + 响应不依赖/回显 target（list-targets 描述「可用目标」，自身不消费 target）
        JsonNode node = json(listTargets());

        assertThat(node.has("target")).as("响应无 target 字段").isFalse();
        assertThat(node.path("targets").isArray()).isTrue();
    }

    // ===== 辅助 =====

    private CallToolResult listTargets() {
        return handlers.handle(
                new com.arthas.gateway.tool.ExposedTool(
                        "arthas-gateway.list-targets", "test", Map.of("type", "object"), null,
                        com.arthas.gateway.tool.RoutingMode.GATEWAY_LOCAL),
                new CallToolRequest("arthas-gateway.list-targets", Map.of()));
    }

    private static JsonNode json(CallToolResult result) throws Exception {
        TextContent tc = (TextContent) result.content().get(0);
        return MAPPER.readTree(tc.text());
    }

    private static JsonNode target(JsonNode root, String name) {
        for (JsonNode t : root.path("targets")) {
            if (name.equals(t.path("name").asText())) {
                return t;
            }
        }
        return null;
    }

    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }
}
