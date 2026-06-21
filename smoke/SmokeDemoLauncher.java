package com.arthas.gateway.smoke;

import com.arthas.gateway.testfixtures.ArthasMcpBackend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 冒烟演示启动器（一次性端到端验证工具，非生产代码 / 非 spec 任务）。
 *
 * <p>复用 {@link ArthasMcpBackend} 夹具拉起 2 个真实 arthas MCP 后端（order-service / payment），
 * 每个后端各带一个真实业务 JVM（{@code com.arthas.gateway.testfixtures.DemoBusinessApp}，hotMethod 持续触发）。
 * 随后写出运行时后端映射表 {@code config/backends-runtime.yaml}（NONE 认证 + 动态端口），并常驻
 * （供网关与 Claude Code 连接）。进程被 kill / Ctrl+C 时经 shutdown hook 关闭全部子进程。
 *
 * <p>用法（须显式 JDK 21 + 设 basedir）：
 * <pre>{@code
 * java -Dbasedir=<工程根绝对路径> -cp target/test-classes:target/smoke-classes \
 *      com.arthas.gateway.smoke.SmokeDemoLauncher
 * }</pre>
 */
public final class SmokeDemoLauncher {

    public static void main(String[] args) throws Exception {
        if (System.getProperty("basedir") == null) {
            System.setProperty("basedir", Path.of(".").toAbsolutePath().normalize().toString());
        }
        Path basedir = Path.of(System.getProperty("basedir")).toAbsolutePath().normalize();

        System.out.println("[launcher] basedir=" + basedir);
        System.out.println("[launcher] 启动后端 order-service ...");
        ArthasMcpBackend order = ArthasMcpBackend.start("order-service");
        System.out.println("[launcher] 启动后端 payment ...");
        ArthasMcpBackend payment = ArthasMcpBackend.start("payment");

        String yaml = ""
                + "version: 1\n"
                + "backends:\n"
                + "  - name: order-service\n"
                + "    url: " + order.baseUrl() + "\n"
                + "    protocol: STREAMABLE\n"
                + "    auth:\n"
                + "      mode: NONE\n"
                + "    connectTimeoutMs: 5000\n"
                + "    callTimeoutMs: 30000\n"
                + "    maxConcurrentTasks: 5\n"
                + "  - name: payment\n"
                + "    url: " + payment.baseUrl() + "\n"
                + "    protocol: STREAMABLE\n"
                + "    auth:\n"
                + "      mode: NONE\n"
                + "    connectTimeoutMs: 5000\n"
                + "    callTimeoutMs: 30000\n"
                + "    maxConcurrentTasks: 5\n";

        Path cfg = basedir.resolve("config/backends-runtime.yaml");
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, yaml, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        System.out.println("=== SMOKE_DEMO_READY ===");
        System.out.println("order-service  baseUrl=" + order.baseUrl()
                + "  appPort=" + order.appPort() + "  mcpPort=" + order.mcpPort());
        System.out.println("payment        baseUrl=" + payment.baseUrl()
                + "  appPort=" + payment.appPort() + "  mcpPort=" + payment.mcpPort());
        System.out.println("runtime-config=" + cfg);
        System.out.println("order-app-health=http://127.0.0.1:" + order.appPort() + "/actuator/health");
        System.out.println("payment-app-health=http://127.0.0.1:" + payment.appPort() + "/actuator/health");
        System.out.println("=== 常驻中（kill 进程以退出）===");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { order.close(); } catch (Exception ignore) { /* 夹具 close 不抛 */ }
            try { payment.close(); } catch (Exception ignore) { /* 同上 */ }
            System.out.println("SMOKE_DEMO_STOPPED");
        }));

        Thread.currentThread().join(); // 常驻
    }

    private SmokeDemoLauncher() {
    }
}
