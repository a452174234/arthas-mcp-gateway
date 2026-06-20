package com.arthas.gateway.testfixtures;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 真实业务服务夹具（T008，设计文档 §4）——arthas attach 的目标 JVM。
 *
 * <p><b>技术形态：纯 Java + JDK {@link HttpServer}</b>（用户裁决 2026-06-20，替代设计文档原述「Spring Boot 进程」
 * ——对齐 reference {@code TargetJvmApp} 纯 Java 范式，子进程类路径只需 {@code target/test-classes}，
 * 无 fat jar / classpath 地狱；启动快、依赖零）。诊断价值齐全：{@link OrderService#hotMethod} 调用链
 * （calc/check）+ HTTP {@code /api/order} 触发 + 可注入慢响应 + 后台守护线程持续触发（arthas watch 抓事件）。
 *
 * <h3>暴露</h3>
 * <ul>
 *   <li>{@code GET/POST /api/order?orderId=&lt;n&gt;&slowMs=&lt;ms&gt;}：触发 {@code hotMethod}，返回 JSON 结果。
 *       {@code slowMs} 为<b>单次请求</b>慢响应注入，handler save/restore 不污染后台循环（AsyncTaskTimeout 用）。</li>
 *   <li>{@code GET /actuator/health}：返回 {@code {"status":"UP"}}，T009 轮询就绪用（reference 范式）。</li>
 * </ul>
 *
 * <h3>生命周期</h3>
 * <p>可实例化（{@link #start(int, long)}/{@link #stop()}）便于夹具自测；{@link #main(String[])} 供 T009 子进程启动
 * （{@code java -cp target/test-classes com.arthas.gateway.testfixtures.DemoBusinessApp <port>}，
 * 可选 {@code -Ddemo.slowMs=<ms>} 进程级慢响应），{@code main} 阻塞常驻、注册 shutdown hook。
 */
public final class DemoBusinessApp {

    /** 子进程默认端口（main 未传参时）。 */
    private static final int DEFAULT_PORT = 8081;

    private final OrderService orderService = new OrderService();
    private final ExecutorService httpPool = Executors.newFixedThreadPool(8);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private HttpServer server;
    private Thread hotLoop;

    /**
     * 启动业务服务。
     *
     * @param port   监听端口
     * @param slowMs 进程级慢响应注入（&gt;0 时设到 OrderService；HTTP 单次 slowMs 另支持）
     * @throws IOException           端口绑定失败
     * @throws IllegalStateException 重复启动
     */
    public void start(int port, long slowMs) throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("DemoBusinessApp 已启动");
        }
        if (slowMs > 0L) {
            orderService.configureSlowResponse(slowMs);
        }
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/order", new OrderHandler(orderService));
        server.createContext("/actuator/health", new HealthHandler());
        server.setExecutor(httpPool);
        server.start();
        startHotLoop();
    }

    /** 实际监听端口（传入 0 由 OS 分配时，取实际端口）。 */
    public int port() {
        return server.getAddress().getPort();
    }

    /** 共享业务服务（测试断言 hotMethod 真实调用计数用）。 */
    public OrderService orderService() {
        return orderService;
    }

    /** 启动后台守护线程：每 ~50ms 触发一次 hotMethod（arthas watch/trace/stack/tt 抓事件依赖持续调用）。 */
    private void startHotLoop() {
        hotLoop = new Thread(() -> {
            int value = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    orderService.hotMethod(value++ & 0xFF);
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "demo-hot-loop");
        hotLoop.setDaemon(true);
        hotLoop.start();
    }

    /** 关停 HttpServer + 线程池 + 后台循环（幂等）。 */
    public void stop() {
        if (running.getAndSet(false)) {
            if (server != null) {
                server.stop(0);
            }
            httpPool.shutdownNow();
            if (hotLoop != null) {
                hotLoop.interrupt();
            }
        }
    }

    /**
     * 子进程入口（T009 经 {@code java -cp target/test-classes} 启动）。
     *
     * @param args {@code args[0]}=端口（缺省 {@link #DEFAULT_PORT}）
     */
    public static void main(String[] args) throws Exception {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        long slowMs = Long.parseLong(System.getProperty("demo.slowMs", "0"));
        DemoBusinessApp app = new DemoBusinessApp();
        app.start(port, slowMs);
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "demo-shutdown"));
        System.out.println("DemoBusinessApp started on port " + port);
        Thread.currentThread().join(); // 常驻，等待 shutdown hook 或进程 destroy
    }

    // ---------------- HTTP handlers ----------------

    /** {@code /api/order} 处理：触发 hotMethod，返回 JSON 结果，支持单次 slowMs 注入。 */
    static final class OrderHandler implements HttpHandler {
        private final OrderService service;

        OrderHandler(OrderService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
                respondJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            int orderId = (int) parseLong(query.get("orderId"), System.nanoTime() & 0xFF);
            long reqSlowMs = parseLong(query.get("slowMs"), 0L);
            long savedSlowMs = service.currentSlowMs();
            try {
                if (reqSlowMs > 0L) {
                    service.configureSlowResponse(reqSlowMs);
                }
                OrderResult result = service.hotMethod(orderId);
                respondJson(exchange, 200, toJson(result));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                respondJson(exchange, 503, "{\"error\":\"interrupted\"}");
            } finally {
                service.configureSlowResponse(savedSlowMs); // 恢复，避免污染后台循环
            }
        }
    }

    /** {@code /actuator/health} 处理：固定返回 UP（T009 轮询就绪）。 */
    static final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            respondJson(exchange, 200, "{\"status\":\"UP\"}");
        }
    }

    // ---------------- helpers ----------------

    static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Map.of();
        }
        Map<String, String> params = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return params;
    }

    static long parseLong(String value, long fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String toJson(OrderResult result) {
        return "{\"orderId\":" + result.orderId()
                + ",\"price\":" + result.price()
                + ",\"valid\":" + result.valid() + "}";
    }
}
