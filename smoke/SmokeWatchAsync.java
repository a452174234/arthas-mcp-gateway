package com.arthas.gateway.smoke;

import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * watch 异步能力专项冒烟（一次性验证工具，非生产代码 / 非 spec 任务）。
 *
 * <p>用官方 MCP SDK client 连网关，聚焦验证 arthas-gateway 的异步长任务通道：
 * <ul>
 *   <li>[1] <b>非阻塞</b>：watch 提交「接受耗时」毫秒级；提交期间并发一次同步 jvm，其耗时与独立值相当（证明 watch 后台执行、不阻塞网关）。
 *       随后轮询 task-get 至终态，记录 working→completed 转移 + createdAt/completedAt 时序。</li>
 *   <li>[2] <b>task-cancel</b>：提交一个自然不结束的长 watch，立即 cancel，task-get 确认 cancelled。</li>
 *   <li>[3] <b>跨 target 并发</b>：同时对 order-service / payment 提交 watch，task-list 展示双任务，均至 completed。</li>
 * </ul>
 * 完整 watch 结果结构（accessPoint/className/methodName/cost/value=入参+返回值）在终态 task-get 中打印。
 */
public final class SmokeWatchAsync {

    private static final String ORDER_CLASS = "com.arthas.gateway.testfixtures.OrderService";
    private static final Pattern TASK_ID = Pattern.compile("\"taskId\"\\s*:\\s*\"(t-[0-9a-f]+)\"");
    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([A-Za-z]+)\"");

    private final McpClientHarness h;

    SmokeWatchAsync(McpClientHarness h) {
        this.h = h;
    }

    public static void main(String[] args) throws Exception {
        String url = (args.length > 0) ? args[0] : "http://127.0.0.1:8761/mcp";
        System.out.println("########## WATCH ASYNC SMOKE -> " + url + " ##########");
        try (McpClientHarness h = new McpClientHarness(url)) {
            McpSchema_InitEcho(h);
            SmokeWatchAsync t = new SmokeWatchAsync(h);
            t.call("arthas-gateway.list-targets", Map.of());
            t.nonBlockingAndLifecycle();
            t.cancelFlow();
            t.concurrentMultiTarget();
        }
        System.out.println("########## WATCH ASYNC SMOKE DONE ##########");
    }

    // ===== [1] 非阻塞 + 生命周期 =====
    void nonBlockingAndLifecycle() throws Exception {
        System.out.println("\n===== [1] 非阻塞 + 生命周期 =====");
        long t0 = System.nanoTime();
        CallToolResult acc = call("watch", Map.of(
                "target", "order-service",
                "classPattern", ORDER_CLASS,
                "methodPattern", "hotMethod",
                "numberOfExecutions", 30,
                "timeout", 30));
        long acceptMs = (System.nanoTime() - t0) / 1_000_000;
        String taskId = extractTaskId(acc);
        System.out.println("[1] >>> watch 接受耗时 = " + acceptMs + " ms  taskId=" + taskId);

        // 提交后立即做一次同步调用，测其耗时（若 watch 阻塞网关，此处会被拖慢）
        long s0 = System.nanoTime();
        callQuiet("jvm", Map.of("target", "payment"));
        long syncMs = (System.nanoTime() - s0) / 1_000_000;
        System.out.println("[1] >>> 同期同步 jvm(payment) 耗时 = " + syncMs
                + " ms  （独立基线约 110~400ms；相当 ⇒ 未被 watch 阻塞）");

        if (taskId != null) {
            pollLifecycle(taskId, Duration.ofSeconds(20));
        }
    }

    // ===== [2] task-cancel =====
    void cancelFlow() throws Exception {
        System.out.println("\n===== [2] task-cancel 流程 =====");
        CallToolResult acc = call("watch", Map.of(
                "target", "order-service",
                "classPattern", ORDER_CLASS,
                "methodPattern", "hotMethod",
                "numberOfExecutions", 500,
                "timeout", 60));
        String taskId = extractTaskId(acc);
        System.out.println("[2] >>> 长 watch 提交 taskId=" + taskId);
        if (taskId == null) {
            return;
        }
        Thread.sleep(400); // 让任务进入 working
        System.out.println("[2] cancel 前的 task-get：");
        call("arthas-gateway.task-get", Map.of("taskId", taskId));
        call("arthas-gateway.task-cancel", Map.of("taskId", taskId));
        Thread.sleep(600);
        System.out.println("[2] cancel 后的 task-get：");
        call("arthas-gateway.task-get", Map.of("taskId", taskId));
    }

    // ===== [3] 跨 target 并发 =====
    void concurrentMultiTarget() throws Exception {
        System.out.println("\n===== [3] 跨 target 并发 =====");
        CallToolResult a = callQuiet("watch", Map.of(
                "target", "order-service", "classPattern", ORDER_CLASS, "methodPattern", "hotMethod",
                "numberOfExecutions", 8, "timeout", 20));
        CallToolResult b = callQuiet("watch", Map.of(
                "target", "payment", "classPattern", ORDER_CLASS, "methodPattern", "hotMethod",
                "numberOfExecutions", 8, "timeout", 20));
        String ta = extractTaskId(a);
        String tb = extractTaskId(b);
        System.out.println("[3] >>> 并发提交  order-service=" + ta + "   payment=" + tb);
        if (ta != null) {
            pollLifecycle(ta, Duration.ofSeconds(20));
        }
        if (tb != null) {
            pollLifecycle(tb, Duration.ofSeconds(20));
        }
        System.out.println("[3] task-list：");
        call("arthas-gateway.task-list", Map.of());
    }

    // ===== 辅助 =====
    void pollLifecycle(String taskId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String prev = null;
        while (System.nanoTime() < deadline) {
            CallToolResult r = safeCall("arthas-gateway.task-get", Map.of("taskId", taskId));
            String body = textOf(r);
            Matcher m = STATUS.matcher(body);
            String status = m.find() ? m.group(1) : "?";
            if (!status.equals(prev)) {
                System.out.println("    [lifecycle] " + taskId + " -> " + status);
                prev = status;
            }
            if (!"working".equalsIgnoreCase(status)) {
                System.out.println("    [task-get 终态原文]\n" + cap(body, 1300));
                return;
            }
            Thread.sleep(400);
        }
        System.out.println("    [lifecycle] " + taskId + " 在 " + timeout + " 内未达终态");
    }

    CallToolResult call(String tool, Map<String, Object> args) {
        System.out.println("---- tools/call  tool=" + tool + "  args=" + SmokeMcpClient.argsJson(args));
        return printResult(safeCall(tool, args));
    }

    CallToolResult callQuiet(String tool, Map<String, Object> args) {
        return safeCall(tool, args);
    }

    CallToolResult safeCall(String tool, Map<String, Object> args) {
        try {
            return h.callTool(tool, args);
        } catch (McpError e) {
            System.out.println("    [jsonrpc-error] " + e.getMessage());
            return null;
        }
    }

    CallToolResult printResult(CallToolResult r) {
        if (r == null) {
            System.out.println("    (无结果)");
            return null;
        }
        System.out.println("    isError=" + r.isError() + "  text:\n" + cap(textOf(r), 900));
        return r;
    }

    static String textOf(CallToolResult r) {
        if (r == null || r.content() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Content c : r.content()) {
            if (c instanceof TextContent tc && tc.text() != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    static String extractTaskId(CallToolResult r) {
        Matcher m = TASK_ID.matcher(textOf(r));
        return m.find() ? m.group(1) : null;
    }

    static String cap(String s, int n) {
        return (s.length() <= n) ? s : s.substring(0, n) + " …[截断 " + n + "/" + s.length() + "]";
    }

    /** initialize 握手并打印 serverInfo（独立方法避免与字段初始化顺序耦合）。 */
    private static void McpSchema_InitEcho(McpClientHarness h) {
        try {
            var init = h.initialize();
            System.out.println("[INIT] protocolVersion=" + init.protocolVersion()
                    + "  serverInfo=" + init.serverInfo());
        } catch (Exception e) {
            System.out.println("[INIT] 失败: " + e);
        }
    }
}
