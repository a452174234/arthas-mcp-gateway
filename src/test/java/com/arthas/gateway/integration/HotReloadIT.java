package com.arthas.gateway.integration;

import com.arthas.gateway.testfixtures.ArthasMcpBackend;
import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T037 热重载端到端契约测试（failsafe，真实 arthas + 真实文件监听 + 真实网关，零桩，SC-002）。
 *
 * <p>覆写 {@code arthas-gateway.backends-file} 指向 {@code target/hotreload-it/backends.yaml}（专用子目录，
 * 远离 {@code target/} 根的构建噪声），驱动真实 {@link com.arthas.gateway.backend.BackendConfigWatcher}
 * （WatchService + 500ms 防抖）→ {@link com.arthas.gateway.backend.BackendRegistryReloader}（diff/复用）
 * → {@link com.arthas.gateway.backend.RegistryHolder#getAndSet} 原子替换 → 优雅下线。
 *
 * <p><b>生命周期</b>：static {@code @BeforeAll}（先于 Spring context 加载）启动真实 arthas 后端 + 写入
 * version 1 空表，使网关以空注册表启动、watcher 监听该文件。static {@code @AfterAll} 关停 arthas。
 *
 * <p>断言（gateway-tools-contract.md §7 热重载、data-model.md §11 规则 7/8）：
 * <ul>
 *   <li><b>新增</b>：写入 version 2（含真实 order 后端）→ 30s 内 list-targets 出现 order 且<b>可诊断</b>
 *       （jvm target=order 返真实 JVM 诊断，非桩）。</li>
 *   <li><b>校验失败保留旧表</b>（§11 规则 7）：写入 version 3（缺 auth 块，非法）→ watcher catch
 *       {@code BackendConfigException} → list-targets 仍含 order（不半替换）。</li>
 *   <li><b>移除</b>：写入 version 4（空表）→ 30s 内 list-targets order 消失，且 jvm target=order 返
 *       INVALID_PARAMS(-32602) + {@code data.available} 不含 order。</li>
 * </ul>
 *
 * <p>version 去重（§11 规则 8：相同 version 忽略）由 {@code BackendRegistryReloaderTest}（单测）覆盖；
 * 并发路由不变量由既有路由 IT 覆盖。本 IT 聚焦<b>真实文件监听端到端保真</b>。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "arthas-gateway.backends-file=target/hotreload-it/backends.yaml")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HotReloadIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 专用配置子目录（远离 target/ 根的构建噪声，避免 watcher 收到无关事件）。 */
    private static final Path CONFIG_DIR = Path.of("target/hotreload-it");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("backends.yaml");

    /** arthas jvm 返回 JSON 含的真实进程级结构标记（与 ToolsCallRoutingContractIT 一致，非桩硬证据）。 */
    private static final String[] JVM_DIAGNOSTIC_MARKERS =
            {"jvmInfo", "RUNTIME", "resultCount", "MACHINE-NAME", "SPEC-NAME"};

    private static ArthasMcpBackend backend;
    private static String orderUrl;

    @LocalServerPort
    private int port;

    private String gatewayUrl() {
        return "http://localhost:" + port + "/mcp";
    }

    @BeforeAll
    static void startRealArthasAndSeedEmptyConfig() throws Exception {
        backend = ArthasMcpBackend.start("order");
        orderUrl = backend.baseUrl();
        Files.createDirectories(CONFIG_DIR);
        writeBackends(1L, List.of()); // 空表 → 网关空注册表启动
    }

    @AfterAll
    static void stopBackend() {
        if (backend != null) {
            backend.close();
        }
    }

    // ===== 新增：version 2（真实 order）→ 30s 内出现且可诊断 =====

    @Test
    @Order(1)
    void added_backend_appears_and_is_diagnosable() throws Exception {
        writeBackends(2L, List.of(backendYaml("order", orderUrl)));
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            awaitTargetPresence(h, "order", Duration.ofSeconds(30));
            assertThat(targetNames(h)).as("热重载后 list-targets 含 order").contains("order");

            // 可诊断：jvm target=order 经新加后端路由到真实 arthas（非桩）
            CallToolResult result = h.callTool("jvm", Map.of("target", "order"));
            assertThat(result.isError()).as("jvm 诊断成功").isNotEqualTo(Boolean.TRUE);
            assertThat(extractText(result))
                    .as("含真实 JVM 进程级诊断标记")
                    .containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
        }
    }

    // ===== 校验失败保留旧表（§11 规则 7）：version 3 缺 auth 块 → 仍含 order =====

    @Test
    @Order(2)
    void invalid_config_keeps_old_registry() throws Exception {
        Files.writeString(CONFIG_FILE, invalidYamlMissingAuth(3L), StandardCharsets.UTF_8);
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            // 等 watcher 防抖(500ms)+poll+重载失败 → 保留旧表
            Thread.sleep(3000);
            assertThat(targetNames(h))
                    .as("非法配置：保留旧表（order 仍在）")
                    .contains("order");
        }
    }

    // ===== 移除：version 4（空表）→ 30s 内消失且调用返明确错误 =====

    @Test
    @Order(3)
    void removed_backend_disappears_and_calls_fail() throws Exception {
        writeBackends(4L, List.of());
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            awaitTargetAbsence(h, "order", Duration.ofSeconds(30));
            assertThat(targetNames(h)).as("热重载后 order 已移除").doesNotContain("order");

            // 调用移除的 target → 明确错误（INVALID_PARAMS + available 不含 order）
            assertThatThrownBy(() -> h.callTool("jvm", Map.of("target", "order")))
                    .as("移除的 target 调用返明确错误")
                    .isInstanceOf(McpError.class)
                    .satisfies(t -> {
                        McpError err = (McpError) t;
                        assertThat(err.getJsonRpcError().code())
                                .as("INVALID_PARAMS(-32602)").isEqualTo(-32602);
                        Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                        assertThat(data).as("data 含 available").isNotNull();
                        assertThat(data.get("available").toString())
                                .as("available 不含已移除的 order")
                                .doesNotContain("order");
                    });
        }
    }

    // ===== 辅助：YAML 生成 =====

    private static void writeBackends(long version, List<String> backendBlocks) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("version: ").append(version).append('\n');
        if (backendBlocks.isEmpty()) {
            sb.append("backends: []\n"); // 空表：行内 flow，免缩进歧义
        } else {
            sb.append("backends:\n");
            for (String b : backendBlocks) {
                sb.append(b);
            }
        }
        Files.writeString(CONFIG_FILE, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String backendYaml(String name, String url) {
        return "  - name: " + name + "\n"
                + "    url: " + url + "\n"
                + "    protocol: STREAMABLE\n"
                + "    auth:\n"
                + "      mode: NONE\n";
    }

    /** 非法配置：缺 auth 块（解析层抛 BackendConfigException）。 */
    private static String invalidYamlMissingAuth(long version) {
        return "version: " + version + "\n"
                + "backends:\n"
                + "  - name: order\n"
                + "    url: " + orderUrl + "\n"
                + "    protocol: STREAMABLE\n";
    }

    // ===== 辅助：list-targets 解析与轮询 =====

    private List<String> targetNames(McpClientHarness h) throws java.io.IOException {
        CallToolResult r = h.callTool("arthas-gateway.list-targets", Map.of());
        JsonNode root = JSON.readTree(text(r));
        List<String> names = new ArrayList<>();
        for (JsonNode t : root.path("targets")) {
            names.add(t.path("name").asText());
        }
        return names;
    }

    private void awaitTargetPresence(McpClientHarness h, String name, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (targetNames(h).contains(name)) {
                return;
            }
            Thread.sleep(500);
        }
    }

    private void awaitTargetAbsence(McpClientHarness h, String name, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!targetNames(h).contains(name)) {
                return;
            }
            Thread.sleep(500);
        }
    }

    private static String text(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (Content c : result.content()) {
            if (c instanceof TextContent tc && tc.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    private static String extractText(CallToolResult result) {
        return text(result);
    }
}
