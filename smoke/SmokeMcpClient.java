package com.arthas.gateway.smoke;

import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 冒烟 MCP 客户端（一次性端到端验证工具，非生产代码 / 非 spec 任务）。
 *
 * <p>用<b>官方 MCP Java SDK client</b>（合规客户端，走标准 Streamable HTTP，非裸 curl）连网关 {@code /mcp}，
 * 打印 {@code initialize} / {@code tools/list} 摘要，并对一组代表性工具逐一发起 {@code tools/call}，
 * 输出<b>完整请求入参与响应文本</b>（供测试报告归档）。覆盖：
 * <ul>
 *   <li>同步转发：{@code jvm} / {@code thread} / {@code ognl}（双 target）</li>
 *   <li>聚合：{@code dashboard}</li>
 *   <li>异步长任务：{@code watch} → 立即返 taskId → {@code task-get} / {@code task-list}</li>
 *   <li>错误用例：未知 target（S-ERR-5）、缺失必填 target（INVALID_PARAMS）</li>
 * </ul>
 *
 * <p>用法（须显式 JDK 21）：
 * <pre>{@code
 * java -cp target/test-classes:target/smoke-classes:<sdk-cp> \
 *      com.arthas.gateway.smoke.SmokeMcpClient [gatewayBaseUrl默认 http://127.0.0.1:8761/mcp]
 * }</pre>
 */
public final class SmokeMcpClient {

    /** 单条文本响应在控制台截断阈值（完整响应另见网关日志）。 */
    private static final int TEXT_CAP = 1500;
    private static final Pattern TASK_ID = Pattern.compile("\"taskId\"\\s*:\\s*\"(t-[0-9a-f]+)\"");

    private static final String ORDER_CLASS = "com.arthas.gateway.testfixtures.OrderService";
    // 目标 JVM（DemoBusinessApp）以 -Ddemo.slowMs=0 启动；读该系统属性既验 ognl 执行，又反验目标 JVM 身份
    private static final String OGNL_EXPR = "@java.lang.System@getProperty(\"demo.slowMs\")";

    public static void main(String[] args) throws Exception {
        String url = (args.length > 0) ? args[0] : "http://127.0.0.1:8761/mcp";
        System.out.println("########## MCP SMOKE CLIENT → " + url + " ##########");
        try (McpClientHarness h = new McpClientHarness(url)) {
            McpSchema.InitializeResult init = h.initialize();
            System.out.println("[INIT] protocolVersion=" + init.protocolVersion()
                    + "  serverInfo=" + init.serverInfo());

            McpSchema.ListToolsResult tools = h.listTools();
            int n = tools.tools() == null ? 0 : tools.tools().size();
            System.out.println("[TOOLS/LIST] count=" + n);
            if (tools.tools() != null) {
                for (McpSchema.Tool t : tools.tools()) {
                    String desc = t.description() == null ? "" : t.description().replaceAll("\\s+", " ");
                    if (desc.length() > 80) {
                        desc = desc.substring(0, 80) + "…";
                    }
                    System.out.println("    - " + t.name() + (desc.isEmpty() ? "" : "  :: " + desc));
                }
            }

            // —— 同步转发（双 target）——
            call(h, "arthas-gateway.list-targets", Map.of());
            call(h, "jvm", Map.of("target", "order-service"));
            call(h, "thread", Map.of("target", "payment"));
            call(h, "ognl", Map.of(
                    "target", "order-service",
                    "expression", OGNL_EXPR));

            // —— 聚合 ——
            call(h, "dashboard", Map.of("target", "order-service"));

            // —— 异步长任务：watch → taskId → task-get / task-list ——
            CallToolResult watch = call(h, "watch", Map.of(
                    "target", "order-service",
                    "classPattern", ORDER_CLASS,
                    "methodPattern", "hotMethod",
                    "numberOfExecutions", 5,
                    "timeout", 20));
            String taskId = extractTaskId(watch);
            if (taskId != null) {
                System.out.println("[smoke] watch 已受理，taskId=" + taskId + "，等待采集中…");
                Thread.sleep(2500);
                call(h, "arthas-gateway.task-get", Map.of("taskId", taskId));
                call(h, "arthas-gateway.task-list", Map.of());
            } else {
                System.out.println("[smoke] ⚠ 未解析到 taskId（watch 响应见上）");
            }

            // —— 错误用例 ——
            call(h, "jvm", Map.of("target", "no-such-target")); // S-ERR-5 backend_unreachable
            call(h, "jvm", Map.of());                            // INVALID_PARAMS 缺 target

            System.out.println("########## SMOKE CLIENT DONE ##########");
        }
    }

    /** 发起一次 tools/call 并打印完整请求/响应，返回结果供调用方进一步解析。 */
    static CallToolResult call(McpClientHarness h, String tool, Map<String, Object> args) {
        System.out.println("================ tools/call ================");
        System.out.println("REQUEST  tool=" + tool);
        System.out.println("REQUEST  args=" + argsJson(args));
        CallToolResult r;
        try {
            r = h.callTool(tool, args);
        } catch (McpError e) {
            // 网关对参数/路由错误返回 JSON-RPC error（如未知 target 的 S-ERR-5）——作为响应如实记录，不崩。
            System.out.println("RESPONSE jsonrpc-error(throw McpError): " + e.getMessage());
            return null;
        }
        System.out.println("RESPONSE isError=" + r.isError());
        StringBuilder buf = new StringBuilder();
        if (r.content() != null) {
            for (Content c : r.content()) {
                if (c instanceof TextContent tc && tc.text() != null) {
                    if (!buf.isEmpty()) {
                        buf.append('\n');
                    }
                    buf.append(tc.text());
                } else {
                    System.out.println("RESPONSE content[" + (buf.length()) + "]=<"
                            + c.getClass().getSimpleName() + ">");
                }
            }
        }
        String text = buf.toString();
        if (text.isEmpty()) {
            System.out.println("RESPONSE text=(空)");
        } else if (text.length() <= TEXT_CAP) {
            System.out.println("RESPONSE text:");
            System.out.println(text);
        } else {
            System.out.println("RESPONSE text (截断 " + TEXT_CAP + "/" + text.length() + " 字符):");
            System.out.println(text.substring(0, TEXT_CAP));
            System.out.println("…[已截断]");
        }
        return r;
    }

    static String extractTaskId(CallToolResult r) {
        if (r == null || r.content() == null) {
            return null;
        }
        for (Content c : r.content()) {
            if (c instanceof TextContent tc && tc.text() != null) {
                Matcher m = TASK_ID.matcher(tc.text());
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    /** 极简 Map→JSON（仅用于打印请求入参，值均为基本类型/字符串）。 */
    static String argsJson(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        Map<String, Object> ordered = new LinkedHashMap<>(args);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : ordered.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append('"').append(e.getKey()).append("\": ");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                String s = v.toString();
                sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
            first = false;
        }
        return sb.append('}').toString();
    }

    private SmokeMcpClient() {
    }
}
