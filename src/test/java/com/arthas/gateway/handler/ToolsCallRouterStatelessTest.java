package com.arthas.gateway.handler;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.CircuitBreaker;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.task.AsyncTaskExecutor;
import com.arthas.gateway.task.TaskStore;
import com.arthas.gateway.testfixtures.FakeBackendClient;
import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import com.arthas.gateway.tool.TaskSupport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
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
 * T012 {@link ToolsCallRouter} STATELESS 异步前置拒绝单测(002 整改 · P1-2,US3)。
 *
 * <p>验证后端协议契约(原理四双侧契约):对 <b>STATELESS</b> 后端的<b>异步</b>类工具调用(watch/trace/stack/tt/monitor)
 * 在 {@code BackendEntry.admit} 前置返 {@code StatelessAsyncException} → 路由器翻译为
 * {@code INVALID_PARAMS}({@code reason=stateless_unsupported_async}),<b>不</b>进 {@code asyncExecutor.submit}
 * (不提交后台、不耗兜底超时);对同一 STATELESS 后端的<b>同步</b>类工具调用(jvm 等)<b>正常</b>(不抛 STATELESS)。
 *
 * <p><b>真实性</b>:注册表内置 1 个真实 {@link BackendEntry}(STATELESS 协议)+ 受控 {@link FakeBackendClient}
 * (同步成功路径返回标记结果,非 arthas 成功桩)。STATELESS 拒绝是<b>网关自身协议判定</b>(读 config.protocol),
 * 不依赖后端真实响应;异步拒绝后断言 {@code store} 无任务(提交未发生)。
 */
class ToolsCallRouterStatelessTest {

    private static final String STATELESS_TARGET = "stateless-be";

    private TaskStore store;
    private AsyncTaskExecutor executor;
    private ToolsCallRouter router;
    private FakeBackendClient statelessClient;

    @BeforeEach
    void setUp() {
        store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));
        statelessClient = new FakeBackendClient();
        statelessClient.setCallToolResult(
                new CallToolResult(List.of(new TextContent("sync-ok")), false, null, null));

        BackendConfig statelessCfg = new BackendConfig(STATELESS_TARGET, "http://localhost:8563",
                Protocol.STATELESS,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 60000, 3);
        BackendEntry statelessEntry = new BackendEntry(statelessCfg, statelessClient,
                CircuitBreaker.create(System::nanoTime));
        BackendRegistry registry = new BackendRegistry(1L, Map.of(STATELESS_TARGET, statelessEntry));

        router = new ToolsCallRouter(new RegistryHolder(registry), executor,
                new GatewayToolHandlers(new RegistryHolder(registry), executor));
    }

    @AfterEach
    void tearDown() {
        executor.close();
        store.close();
    }

    /** 异步工具(watch)对 STATELESS 后端 → 立即 INVALID_PARAMS + reason=stateless_unsupported_async,不进 submit。 */
    @Test
    void statelessAsyncCallRejectedBeforeSubmit() {
        assertThatThrownBy(() -> router.route(asyncTool(),
                new CallToolRequest("watch", Map.of("target", STATELESS_TARGET))))
                .isInstanceOf(McpError.class)
                .satisfies(t -> {
                    McpError err = (McpError) t;
                    assertThat(err.getJsonRpcError().code())
                            .as("STATELESS 异步 → INVALID_PARAMS").isEqualTo(McpErrorCodes.INVALID_PARAMS);
                    assertThat(err.getJsonRpcError().data()).isNotNull();
                    Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                    assertThat(data.get("reason"))
                            .as("reason=stateless_unsupported_async")
                            .isEqualTo("stateless_unsupported_async");
                    assertThat(data.get("target")).isEqualTo(STATELESS_TARGET);
                    List<String> available = ((List<?>) data.get("available")).stream()
                            .map(Object::toString).toList();
                    assertThat(available)
                            .as("available 含该 STATELESS target(同步仍可用)")
                            .contains(STATELESS_TARGET);
                });
        // 关键:异步拒绝在 admit 前置,asyncExecutor.submit 未被调用 → store 无任务、无槽占用
        assertThat(store.list())
                .as("STATELESS 异步拒绝不提交后台任务(store 为空)").isEmpty();
    }

    /** 同步工具(jvm)对 STATELESS 后端 → 正常(不经 admit 的 STATELESS 检查),返回后端结果。 */
    @Test
    void statelessSyncCallStillWorks() {
        CallToolResult result = router.route(syncTool(),
                new CallToolRequest("jvm", Map.of("target", STATELESS_TARGET)));

        assertThat(result).isNotNull();
        assertThat(result.isError()).as("同步调用正常,非错误").isFalse();
        assertThat(((TextContent) result.content().get(0)).text()).isEqualTo("sync-ok");
    }

    private static ExposedTool asyncTool() {
        return new ExposedTool(
                "watch",
                "异步观测",
                Map.of(
                        "type", "object",
                        "properties", new LinkedHashMap<>(),
                        "required", List.of(),
                        "additionalProperties", false),
                TaskSupport.OPTIONAL,
                RoutingMode.ASYNC_TASK);
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
}
