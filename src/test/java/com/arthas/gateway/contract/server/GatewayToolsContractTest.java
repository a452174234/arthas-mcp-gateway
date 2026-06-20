package com.arthas.gateway.contract.server;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.handler.GatewayToolHandlers;
import com.arthas.gateway.handler.ToolsCallRouter;
import com.arthas.gateway.task.AsyncTaskExecutor;
import com.arthas.gateway.task.GatewayTask;
import com.arthas.gateway.task.TaskError;
import com.arthas.gateway.task.TaskStore;
import com.arthas.gateway.task.TaskState;
import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T029 网关自有工具契约测试（surefire，纯逻辑，零 MCP 传输 / 零真实后端）。
 *
 * <p>断言 gateway-tools-contract.md §6 的纯逻辑断言点（响应整形 + handler 分派）：
 * G-ASYNC-1（立即响应整形）、G-LT-1（list-targets 返回注册表）、G-TG-1/2/3（task-get 各状态 + isError 原样 +
 * 未知→INVALID_PARAMS）、G-TL-1（task-list 全量 + 过滤）、G-TC-1/2（task-cancel working→cancelled / 终态幂等）。
 *
 * <p><b>测试边界</b>（遵循宪法「真实环境、禁止桩」）：
 * <ul>
 *   <li>本测的是网关<b>自有</b>逻辑（响应整形 + handler 分派），非 arthas 交互。</li>
 *   <li>任务状态由 {@link GatewayTask} 自身转换方法（markCompleted/markFailed/markCancelled）置入——
 *       这是网关状态机（T031 已测），用于构造 handler 测试夹具；其内嵌的 CallToolResult 为<b>映射测试数据</b>
 *       （测「handler 是否把 result 正确放入 completed 响应」），<b>非</b> arthas 成功响应桩。</li>
 *   <li>真实 arthas 结果保真度（真实诊断→completed.result）由端到端 {@code AsyncTaskContractIT}（真实 arthas）覆盖；
 *       G-ASYNC-1 的「route 不阻塞」由 {@code AsyncTaskExecutorTest}（submit 非阻塞）+ 上述 IT 覆盖。</li>
 * </ul>
 */
class GatewayToolsContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-06-20T10:00:00Z");

    private TaskStore store;
    private AsyncTaskExecutor executor;
    private RegistryHolder registry;
    private GatewayToolHandlers handlers;
    private ToolsCallRouter router;

    @BeforeEach
    void setUp() {
        store = new TaskStore(Duration.ofHours(1), () -> T0);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));
        BackendEntryFactory factory = new BackendEntryFactory();
        BackendEntry entry = factory.create(noneAuth("order", "http://127.0.0.1:1")); // closed port，构造不连
        registry = new RegistryHolder();
        registry.getAndSet(new BackendRegistry(7L, Map.of("order", entry)));
        handlers = new GatewayToolHandlers(registry, executor);
        router = new ToolsCallRouter(registry, executor, handlers);
    }

    @AfterEach
    void tearDown() {
        executor.close();
        store.close();
    }

    // ===== G-ASYNC-1：立即响应整形（固定 working，不读 task 状态避免与后台竞态） =====

    @Test
    void g_async_1_immediateResponseShape() throws Exception {
        GatewayTask task = working("t-7f3a9c", "watch", "order");

        CallToolResult resp = ToolsCallRouter.asyncAcceptedResponse(task);

        JsonNode node = json(resp);
        assertThat(node.path("taskId").asText()).isEqualTo("t-7f3a9c");
        assertThat(node.path("status").asText()).isEqualTo("working");
        assertThat(node.path("_meta").path("toolName").asText()).isEqualTo("watch");
        assertThat(node.path("_meta").path("target").asText()).isEqualTo("order");
    }

    // ===== G-LT-1：list-targets 返回注册表 =====

    @Test
    void g_lt_1_listTargetsReturnsRegistry() throws Exception {
        CallToolResult resp = handlers.handle(listTargetsTool(), new CallToolRequest("arthas-gateway.list-targets", Map.of()));

        JsonNode node = json(resp);
        assertThat(node.path("version").asLong()).isEqualTo(7L);
        assertThat(node.path("targets").isArray()).isTrue();
        JsonNode t = node.path("targets").get(0);
        assertThat(t.path("name").asText()).isEqualTo("order");
        assertThat(t.path("protocol").asText()).isEqualTo("STREAMABLE");
        assertThat(t.path("state").asText()).isEqualTo("ACTIVE");
    }

    // ===== G-TG-1：task-get 各状态分支 =====

    @Test
    void g_tg_1_taskGetByStatus() throws Exception {
        seed(working("t-1", "watch", "order"));
        seed(completed("t-2", "trace", "order", false));
        seed(failed("t-3", "stack", "order"));
        seed(cancelled("t-4", "tt", "order"));

        assertThat(taskGetStatus("t-1")).isEqualTo("working");
        assertThat(taskGetStatus("t-2")).isEqualTo("completed");
        assertThat(taskGetStatus("t-3")).isEqualTo("failed");
        assertThat(taskGetStatus("t-4")).isEqualTo("cancelled");

        JsonNode failed = json(taskGet("t-3"));
        assertThat(failed.path("error").path("reason").asText()).isEqualTo(TaskError.REASON_BACKEND_TIMEOUT);
        assertThat(failed.path("error").path("message").asText()).isNotBlank();
    }

    // ===== G-TG-2：后端 isError=true 在 completed.result 原样保留（不转 failed） =====

    @Test
    void g_tg_2_isErrorPreservedInCompletedResult() throws Exception {
        seed(completed("t-err", "watch", "order", true)); // isError=true 业务错误

        JsonNode node = json(taskGet("t-err"));

        assertThat(node.path("status").asText())
                .as("isError=true 是业务响应 → completed（非 failed）").isEqualTo("completed");
        assertThat(node.path("result").path("isError").asBoolean())
                .as("result.isError 原样保留为 true").isTrue();
    }

    // ===== G-TG-3：未知 taskId → INVALID_PARAMS =====

    @Test
    void g_tg_3_unknownTaskIdReturnsInvalidParams() {
        assertThatThrownBy(() -> taskGet("t-ghost"))
                .isInstanceOf(McpError.class)
                .satisfies(t -> assertThat(((McpError) t).getJsonRpcError().code()).isEqualTo(-32602));
    }

    // ===== G-TL-1：task-list 全量 + status 过滤 =====

    @Test
    void g_tl_1_taskListAllAndStatusFilter() throws Exception {
        seed(working("t-w", "watch", "order"));
        seed(completed("t-c", "trace", "order", false));
        seed(failed("t-f", "stack", "order"));

        JsonNode all = json(taskList(null));
        assertThat(all.path("tasks")).hasSize(3);

        JsonNode working = json(taskList("working"));
        assertThat(working.path("tasks")).hasSize(1);
        assertThat(working.path("tasks").get(0).path("taskId").asText()).isEqualTo("t-w");

        JsonNode failed = json(taskList("failed"));
        assertThat(failed.path("tasks")).hasSize(1);
        assertThat(failed.path("tasks").get(0).path("status").asText()).isEqualTo("failed");
    }

    // ===== G-TC-1：cancel working → cancelled =====

    @Test
    void g_tc_1_cancelWorkingTransitionsToCancelled() throws Exception {
        seed(working("t-w", "watch", "order"));

        JsonNode node = json(taskCancel("t-w"));

        assertThat(node.path("status").asText()).isEqualTo("cancelled");
        assertThat(store.get("t-w").orElseThrow().status()).isEqualTo(TaskState.CANCELLED);
    }

    // ===== G-TC-2：cancel 终态任务幂等（返当前状态，不报错） =====

    @Test
    void g_tc_2_cancelTerminalIsIdempotent() throws Exception {
        seed(cancelled("t-x", "tt", "order")); // 已终态

        JsonNode node = json(taskCancel("t-x"));

        assertThat(node.path("status").asText()).as("返当前状态 cancelled").isEqualTo("cancelled");
    }

    // ===== 夹具与辅助 =====

    private static GatewayTask working(String id, String tool, String target) {
        return new GatewayTask(id, tool, target, T0, () -> T0);
    }

    private static GatewayTask completed(String id, String tool, String target, boolean error) {
        GatewayTask t = working(id, tool, target);
        t.markCompleted(result("诊断输出：" + id, error));
        return t;
    }

    private static GatewayTask failed(String id, String tool, String target) {
        GatewayTask t = working(id, tool, target);
        t.markFailed(new TaskError(TaskError.REASON_BACKEND_TIMEOUT, "后端 11min 未响应"));
        return t;
    }

    private static GatewayTask cancelled(String id, String tool, String target) {
        GatewayTask t = working(id, tool, target);
        t.markCancelled();
        return t;
    }

    private void seed(GatewayTask task) {
        store.put(task);
    }

    private static CallToolResult result(String text, boolean error) {
        return new CallToolResult(List.of(new TextContent(text)), error, null, null);
    }

    private CallToolResult taskGet(String taskId) {
        return handlers.handle(taskGetTool(), new CallToolRequest("arthas-gateway.task-get", Map.of("taskId", taskId)));
    }

    private String taskGetStatus(String taskId) throws Exception {
        return json(taskGet(taskId)).path("status").asText();
    }

    private CallToolResult taskList(String status) {
        Map<String, Object> args = status != null ? Map.of("status", status) : Map.of();
        return handlers.handle(taskListTool(), new CallToolRequest("arthas-gateway.task-list", args));
    }

    private CallToolResult taskCancel(String taskId) {
        return handlers.handle(taskCancelTool(), new CallToolRequest("arthas-gateway.task-cancel", Map.of("taskId", taskId)));
    }

    private static JsonNode json(CallToolResult result) throws Exception {
        TextContent tc = (TextContent) result.content().get(0);
        return MAPPER.readTree(tc.text());
    }

    private static ExposedTool listTargetsTool() {
        return tool("arthas-gateway.list-targets");
    }

    private static ExposedTool taskGetTool() {
        return tool("arthas-gateway.task-get");
    }

    private static ExposedTool taskListTool() {
        return tool("arthas-gateway.task-list");
    }

    private static ExposedTool taskCancelTool() {
        return tool("arthas-gateway.task-cancel");
    }

    private static ExposedTool tool(String name) {
        return new ExposedTool(name, "test", Map.of("type", "object"), null, RoutingMode.GATEWAY_LOCAL);
    }

    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }
}
