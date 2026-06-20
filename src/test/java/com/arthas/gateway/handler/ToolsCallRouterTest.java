package com.arthas.gateway.handler;

import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.task.AsyncTaskExecutor;
import com.arthas.gateway.task.TaskStore;
import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import com.arthas.gateway.tool.TaskSupport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T027/T028 {@link ToolsCallRouter} 纯逻辑单测（surefire，无 arthas）。
 *
 * <p>覆盖路由层的<b>错误传播前置路径</b>（不触及后端转发）：
 * <ul>
 *   <li>S-ERR-1：target 缺失 → INVALID_PARAMS(-32602)（{@link DiagnosticRequest} 抛，路由器透传）。</li>
 *   <li>S-ERR-2：target 不在册 → INVALID_PARAMS(-32602) + {@code data.available}（当前可用 target 列表）。</li>
 * </ul>
 *
 * <p>用空注册表（真实 {@link RegistryHolder}，非桩）——前置校验在路由到后端之前完成，无需真实 BackendClient。
 * 真实转发（S-CALL：jvm 真实诊断透传、S-CALL-2 剥离、S-ERR-4 后端 isError 透传）由
 * {@code ToolsCallRoutingContractIT}（failsafe，真实 arthas）覆盖。
 */
class ToolsCallRouterTest {

    private TaskStore store;
    private AsyncTaskExecutor executor;
    private ToolsCallRouter router;

    @BeforeEach
    void setUp() {
        store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));
        // 空注册表路由器（target 永不在册 → S-ERR-2）
        router = new ToolsCallRouter(
                new RegistryHolder(BackendRegistry.empty()),
                executor,
                new GatewayToolHandlers(new RegistryHolder(BackendRegistry.empty()), executor));
    }

    @AfterEach
    void tearDown() {
        executor.close();
        store.close();
    }

    private static ExposedTool syncTool() {
        return new ExposedTool(
                "jvm",
                "JVM 诊断",
                Map.of(
                        "type", "object",
                        "properties", new LinkedHashMap<>(),
                        "required", List.of(),
                        "additionalProperties", false),
                TaskSupport.FORBIDDEN,
                RoutingMode.SYNC_DIRECT);
    }

    // ===== S-ERR-1：target 缺失/空 → INVALID_PARAMS =====

    @Test
    void s_err_1_missingTargetThrowsInvalidParams() {
        assertThatThrownBy(() -> router.route(syncTool(), new CallToolRequest("jvm", Map.of())))
                .isInstanceOf(McpError.class)
                .hasFieldOrPropertyWithValue("jsonRpcError.code", -32602);
    }

    @Test
    void s_err_1_nullArgumentsThrowsInvalidParams() {
        assertThatThrownBy(() -> router.route(syncTool(), new CallToolRequest("jvm", null)))
                .isInstanceOf(McpError.class)
                .hasFieldOrPropertyWithValue("jsonRpcError.code", -32602);
    }

    // ===== S-ERR-2：target 不在册 → INVALID_PARAMS + data.available =====

    @Test
    void s_err_2_unknownTargetThrowsInvalidParamsWithAvailable() {
        assertThatThrownBy(() -> router.route(
                syncTool(), new CallToolRequest("jvm", Map.of("target", "ghost"))))
                .isInstanceOf(McpError.class)
                .satisfies(t -> {
                    McpError err = (McpError) t;
                    assertThat(err.getJsonRpcError().code()).isEqualTo(-32602);
                    // data.available 附当前可用 target 列表
                    assertThat(err.getJsonRpcError().data()).isNotNull();
                    Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                    assertThat(data.containsKey("available"))
                            .as("data 含 available 字段")
                            .isTrue();
                    assertThat((List<?>) data.get("available"))
                            .as("available 为当前注册表逻辑名快照（空表 → 空列表）")
                            .isEmpty();
                    // 消息指出未知的 target，便于调用方定位
                    assertThat(err.getJsonRpcError().message()).contains("ghost");
                });
    }
}
