package com.arthas.gateway.handler;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.obs.BackendRegistryHealthIndicator;
import com.arthas.gateway.task.AsyncTaskExecutor;
import com.arthas.gateway.task.TaskStore;
import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
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
 * T023 健康判定单一事实源测试(002 整改 · P3-2/FR-012,US5)。
 *
 * <p>钉住「三处健康判定一致」契约:{@code BackendEntry.isHealthy()}(单一事实源,
 * T027 实现委托目标)、{@code list-targets} 的 {@code healthy} 字段、{@code HealthIndicator} 的
 * {@code healthy} detail——三者对同一后端状态(熔断 CLOSED/OPEN、ACTIVE/RETIRED)须返回一致结果。
 *
 * <p>修复前三处各自内联 {@code state==ACTIVE && breaker.state()!=OPEN},任一处独立漂移将导致
 * list-targets 与 actuator/health 对同一后端报不同健康(运维困惑)。T027 收口到 {@code isHealthy()} 后,
 * 本测持续守护该不变量。熔断 OPEN 由真实 {@code recordFailure×3} 驱动(非桩),与既有
 * {@code BackendRegistryHealthIndicatorTest}/{@code ListTargetsContractTest} 一致。
 *
 * <p>本测为<b>钉契约型</b>(pinning):在 T027 委托前后均应 GREEN(委托是无行为变更的重构)。
 */
class HealthSingleSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TaskStore store;
    private AsyncTaskExecutor executor;
    private RegistryHolder registry;
    private GatewayToolHandlers handlers;
    private BackendRegistryHealthIndicator indicator;

    private BackendEntry healthy;
    private BackendEntry openBreaker;
    private BackendEntry retired;

    @BeforeEach
    void setUp() {
        BackendEntryFactory factory = new BackendEntryFactory();
        healthy = factory.create(cfg("healthy"));       // ACTIVE + CLOSED → healthy
        openBreaker = factory.create(cfg("open"));      // ACTIVE + OPEN(真实 recordFailure×3)→ unhealthy
        openBreaker.breaker().recordFailure();
        openBreaker.breaker().recordFailure();
        openBreaker.breaker().recordFailure();
        retired = factory.create(cfg("retired"));       // RETIRED + CLOSED → unhealthy
        retired.markRetired();

        Map<String, BackendEntry> byName = new LinkedHashMap<>();
        byName.put("healthy", healthy);
        byName.put("open", openBreaker);
        byName.put("retired", retired);

        store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));
        registry = new RegistryHolder();
        registry.getAndSet(new BackendRegistry(1L, byName));
        handlers = new GatewayToolHandlers(registry, executor);
        indicator = new BackendRegistryHealthIndicator(registry);
    }

    @AfterEach
    void tearDown() {
        executor.close();
        store.close();
    }

    @Test
    void allThreeSourcesAgreePerBackendState() throws Exception {
        JsonNode targets = json(listTargets()).path("targets");
        Map<?, ?> healthBackends = (Map<?, ?>) indicator.health().getDetails().get("backends");

        for (String name : new String[]{"healthy", "open", "retired"}) {
            BackendEntry entry = switch (name) {
                case "healthy" -> healthy;
                case "open" -> openBreaker;
                default -> retired;
            };
            boolean fromEntry = entry.isHealthy();                       // 单一事实源(T027 委托目标)
            boolean fromListTargets = target(targets, name).path("healthy").asBoolean();
            boolean fromHealthIndicator = (boolean) ((Map<?, ?>) healthBackends.get(name)).get("healthy");

            assertThat(fromListTargets).as("%s: list-targets 与 isHealthy 一致", name).isEqualTo(fromEntry);
            assertThat(fromHealthIndicator).as("%s: HealthIndicator 与 isHealthy 一致", name).isEqualTo(fromEntry);
        }
    }

    @Test
    void openBreakerAndRetiredAreUnhealthyEverywhere() {
        // 三处均判 OPEN/RETIRED 为 unhealthy(便于诊断,不被悄悄当健康)
        assertThat(healthy.isHealthy()).isTrue();
        assertThat(openBreaker.isHealthy()).isFalse();
        assertThat(retired.isHealthy()).isFalse();
    }

    private CallToolResult listTargets() {
        return handlers.handle(
                new ExposedTool("arthas-gateway.list-targets", "test", Map.of("type", "object"), null, RoutingMode.GATEWAY_LOCAL),
                new CallToolRequest("arthas-gateway.list-targets", Map.of()));
    }

    private static JsonNode json(CallToolResult result) throws Exception {
        TextContent tc = (TextContent) result.content().get(0);
        return MAPPER.readTree(tc.text());
    }

    private static JsonNode target(JsonNode root, String name) {
        for (JsonNode t : root) {
            if (name.equals(t.path("name").asText())) {
                return t;
            }
        }
        throw new AssertionError("未找到 target：" + name);
    }

    private static BackendConfig cfg(String name) {
        return new BackendConfig(name, "http://127.0.0.1:9", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null), 5000, 30000, 5);
    }
}
