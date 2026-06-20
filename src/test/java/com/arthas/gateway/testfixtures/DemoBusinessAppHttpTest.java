package com.arthas.gateway.testfixtures;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DemoBusinessApp HTTP 启动契约集成测试（T008）。
 *
 * <p>验证夹具自身的启动可靠性（T009 端到端 arthas attach 依赖此前提）：JDK {@code HttpServer} 暴露
 * {@code /actuator/health}（T009 轮询就绪用）与 {@code /api/order}（触发 hotMethod 真实执行），后台守护线程
 * 持续触发 hotMethod（arthas watch 抓事件依赖，不靠 HTTP 时序），per-request {@code slowMs} 注入且不泄漏。
 *
 * <p>启动<b>真实</b> DemoBusinessApp（真实业务服务）+ 真实 HTTP 调用 → 真实 hotMethod 执行。arthas 真实诊断
 * （watch 捕获）由 T009 夹具 attach + T018 契约测试承担，非本测试职责（本测试不启动 arthas）。
 */
class DemoBusinessAppHttpTest {

    private DemoBusinessApp app;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    private DemoBusinessApp startApp() throws IOException {
        app = new DemoBusinessApp();
        app.start(freePort(), 0L);
        return app;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static String base(DemoBusinessApp app) {
        return "http://127.0.0.1:" + app.port();
    }

    private HttpResponse<String> get(DemoBusinessApp app, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base(app) + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        DemoBusinessApp app = startApp();
        HttpResponse<String> resp = get(app, "/actuator/health");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("UP");
    }

    @Test
    void orderEndpointInvokesHotMethodAndReturnsResult() throws Exception {
        DemoBusinessApp app = startApp();
        long before = app.orderService().hotMethodInvocations();

        HttpResponse<String> resp = get(app, "/api/order?orderId=42");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).as("回显 orderId").contains("\"orderId\":42");
        assertThat(app.orderService().hotMethodInvocations())
                .as("HTTP 调用真实触发了 hotMethod").isGreaterThan(before);
    }

    @Test
    void backgroundLoopKeepsHotMethodFiring() throws Exception {
        DemoBusinessApp app = startApp();
        long before = app.orderService().hotMethodInvocations();

        // 后台守护线程每 ~50ms 触发 hotMethod（arthas watch/trace 抓事件依赖此持续调用）
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (app.orderService().hotMethodInvocations() >= before + 5) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(app.orderService().hotMethodInvocations())
                .as("后台循环持续触发 hotMethod").isGreaterThanOrEqualTo(before + 5);
    }

    @Test
    void perRequestSlowResponseDoesNotLeak() throws Exception {
        DemoBusinessApp app = startApp();

        long slowStart = System.nanoTime();
        get(app, "/api/order?orderId=1&slowMs=300");
        long slowElapsedMs = (System.nanoTime() - slowStart) / 1_000_000L;
        assertThat(slowElapsedMs).as("slowMs=300 使该请求变慢").isGreaterThanOrEqualTo(280L);

        long fastStart = System.nanoTime();
        get(app, "/api/order?orderId=2");
        long fastElapsedMs = (System.nanoTime() - fastStart) / 1_000_000L;
        assertThat(fastElapsedMs).as("slowMs 不泄漏到后续请求（save/restore）").isLessThan(100L);
    }
}
