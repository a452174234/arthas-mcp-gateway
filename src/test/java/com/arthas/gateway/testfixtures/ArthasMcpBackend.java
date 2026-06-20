package com.arthas.gateway.testfixtures;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 真实 arthas MCP 后端夹具（T009，设计文档 §3/§4/§5）。
 *
 * <p>编排：启 {@link DemoBusinessApp} 子进程 → {@code java -jar tools/arthas-boot.jar} attach
 * （纯 Java Attach API，无 bash/as.sh 依赖，Windows 原生）→ 轮询 MCP 端口就绪 → 暴露 {@code baseUrl}。
 * arthas agent 注入目标 JVM 后，MCP HTTP 服务常驻<b>目标 JVM 内</b>，随其生灭（attach 进程短命，exit 0）。
 *
 * <h3>端点裁决（设计文档 §9，本夹具首测实证）</h3>
 * <p>暴露的 {@code baseUrl} 为<b>根 URL</b> {@code http://127.0.0.1:<mcpPort>}（无 {@code /mcp} 后缀）
 * ——对齐 reference {@code ArthasMcpJavaSdkIT} 实证（{@code HttpClientStreamableHttpTransport}
 * .builder("http://127.0.0.1:" + httpPort)}，tools/list+callTool 全成功）。{@code backend-client-contract.md §1}
 * 的 {@code /mcp} 描述为设计假设，以本首测实证为准。
 *
 * <h3>构件（用户约束 2026-06-20：本工程不依赖 arthas）</h3>
 * <p>arthas-boot.jar 作<b>静态工具文件</b> {@code tools/arthas-boot.jar}，经 {@code java -jar} 使用
 * （不入 pom、不构建 reference 源码）。首跑自动下载 arthas 4.3.0 运行时到 {@code ~/.arthas/lib/4.3.0}
 * （{@code --use-version 4.3.0} 锁版本，对齐设计文档 §5）。
 *
 * <h3>原子单元（设计文档 §6，单后端优先）</h3>
 * <p>一个实例 = 1 目标 JVM + 1 arthas。{@link AutoCloseable}，{@code close()} 销毁目标 JVM 子进程
 * （MCP 服务随之释放）。可按逻辑名实例化为多目标后端（集群能力后置）。
 *
 * @see DemoBusinessApp
 * @see McpClientHarness
 */
public final class ArthasMcpBackend implements AutoCloseable {

    /** 业务服务就绪轮询超时（端口监听）。 */
    private static final Duration APP_READY_TIMEOUT = Duration.ofSeconds(30);
    /** arthas attach 进程超时（含首跑下载 arthas 4.3.0 运行时，对齐 reference 90s 并放宽）。 */
    private static final Duration ATTACH_TIMEOUT = Duration.ofSeconds(180);
    /** arthas MCP 端口（注入目标 JVM 内）就绪轮询超时。 */
    private static final Duration MCP_PORT_READY_TIMEOUT = Duration.ofSeconds(30);
    /** 锁定的 arthas 版本（设计文档 §5）。 */
    private static final String ARTHAS_VERSION = "4.3.0";

    private final String logicalName;
    private final int mcpPort;
    private final int appPort;
    private final String baseUrl;
    private final Process appProcess;

    private ArthasMcpBackend(String logicalName, int mcpPort, int appPort, Process appProcess) {
        this.logicalName = logicalName;
        this.mcpPort = mcpPort;
        this.appPort = appPort;
        this.baseUrl = "http://127.0.0.1:" + mcpPort;
        this.appProcess = appProcess;
    }

    /**
     * 启动单后端夹具（NONE 认证）。
     *
     * @param logicalName 逻辑名（多目标后端标识）
     * @return 就绪的夹具（baseUrl 可连）
     * @throws IOException          子进程/端口操作失败
     * @throws InterruptedException attach/轮询被中断
     */
    public static ArthasMcpBackend start(String logicalName) throws IOException, InterruptedException {
        return start(logicalName, null);
    }

    /**
     * 启动单后端夹具。
     *
     * @param logicalName 逻辑名
     * @param token       BEARER 认证 token；{@code null} 表示 NONE。当前实现仅 NONE
     *                    （BEARER 待 arthas MCP token 配置机制核实后补，见设计文档 §8）
     * @return 就绪的夹具
     */
    public static ArthasMcpBackend start(String logicalName, String token) throws IOException, InterruptedException {
        if (logicalName == null || logicalName.isBlank()) {
            throw new IllegalArgumentException("logicalName 不可为空");
        }
        int appPort = findFreePort();
        int mcpPort = findFreePort();

        Path basedir = Path.of(System.getProperty("basedir"));
        Path appLog = basedir.resolve("target/demo-app-" + logicalName + ".log");
        Process appProcess = startDemoBusinessApp(appPort, appLog);
        try {
            waitForPortOpen("127.0.0.1", appPort, APP_READY_TIMEOUT, "DemoBusinessApp");
            long pid = appProcess.pid();

            Path arthasBootJar = resolveArthasBootJar(basedir);
            Path attachLog = basedir.resolve("target/arthas-attach-" + logicalName + ".log");
            runArthasAttach(arthasBootJar, pid, mcpPort, attachLog, ATTACH_TIMEOUT);
            waitForPortOpen("127.0.0.1", mcpPort, MCP_PORT_READY_TIMEOUT, "arthas MCP");
        } catch (RuntimeException | InterruptedException | IOException e) {
            destroyProcess(appProcess);
            throw e;
        }
        return new ArthasMcpBackend(logicalName, mcpPort, appPort, appProcess);
    }

    /** MCP 端点根 URL（{@code http://127.0.0.1:<mcpPort>}，§9 实证）。 */
    public String baseUrl() {
        return baseUrl;
    }

    /** arthas MCP HTTP 端口（注入目标 JVM 内）。 */
    public int mcpPort() {
        return mcpPort;
    }

    /** 业务服务 HTTP 端口（{@code /api/order}、{@code /actuator/health}）。 */
    public int appPort() {
        return appPort;
    }

    /** 逻辑名。 */
    public String name() {
        return logicalName;
    }

    @Override
    public void close() {
        destroyProcess(appProcess); // MCP 服务随目标 JVM 生灭
    }

    // ---------------- internals ----------------

    private static Process startDemoBusinessApp(int port, Path logFile) throws IOException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Path.of(System.getProperty("basedir"), "target", "test-classes").toString();
        Files.createDirectories(logFile.getParent());
        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-cp", classpath,
                "-Ddemo.slowMs=0",
                DemoBusinessApp.class.getName(),
                String.valueOf(port));
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());
        return pb.start();
    }

    /**
     * 经 {@code java -jar tools/arthas-boot.jar} attach arthas 到目标 JVM。
     *
     * <p>命令行对齐 reference {@code runAttach} 范式（PID 位置参数 + {@code --attach-only} +
     * {@code --target-ip}/{@code --telnet-port}/{@code --http-port}），去 bash/as.sh、加
     * {@code --use-version 4.3.0} 锁版本。attach 进程短命（注入 agent 后 exit 0）。
     */
    private static void runArthasAttach(Path arthasBootJar, long pid, int mcpPort, Path logFile,
                                        Duration timeout) throws IOException, InterruptedException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Files.createDirectories(logFile.getParent());
        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-jar", arthasBootJar.toString(),
                String.valueOf(pid), // 目标 JVM PID（位置参数）
                "--attach-only",
                "--target-ip", "127.0.0.1",
                "--telnet-port", "0", // 禁用 telnet（reference 范式），仅 MCP HTTP
                "--http-port", String.valueOf(mcpPort),
                "--use-version", ARTHAS_VERSION); // 锁 4.3.0（设计文档 §5）
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());
        Process attach = pb.start();
        if (!attach.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            attach.destroyForcibly();
            throw new IllegalStateException(
                    "arthas attach 超时（" + timeout + "）: pid=" + pid + ", mcpPort=" + mcpPort
                            + "，详见日志 " + logFile);
        }
        if (attach.exitValue() != 0) {
            throw new IllegalStateException(
                    "arthas attach 失败（exit=" + attach.exitValue() + "）: pid=" + pid
                            + "，详见日志 " + logFile);
        }
    }

    /** 定位静态工具文件 {@code tools/arthas-boot.jar}（工程相对路径，memory arthas-no-dependency）。 */
    private static Path resolveArthasBootJar(Path basedir) {
        Path jar = basedir.resolve("tools/arthas-boot.jar").normalize();
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("arthas-boot.jar 未找到: " + jar
                    + "（应作为静态工具文件置于工程 tools/，见 memory arthas-no-dependency）");
        }
        return jar;
    }

    private static void waitForPortOpen(String host, int port, Duration timeout, String label)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 500);
                return;
            } catch (IOException ignored) {
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException("等待 " + label + " 端口监听超时: " + host + ":" + port + "（" + timeout + "）");
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static void destroyProcess(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
