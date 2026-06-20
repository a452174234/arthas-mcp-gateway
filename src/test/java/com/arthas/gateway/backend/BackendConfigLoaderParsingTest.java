package com.arthas.gateway.backend;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T022 {@link BackendConfigLoader} 严格数值解析单测(002 整改 · P3-4/FR-014,US5)。
 *
 * <p>验证 {@code asInt}/{@code readVersion} <b>拒浮点/超界</b>且错误信息含原始值(便于定位配置错误),
 * 合法整数通过。修复前 {@code instanceof Number} 静默截断 {@code 5.0→5}、超 int 的 Long 截断为负数/错值,
 * 掩盖配置错误。
 *
 * <p>用真实 {@link BackendConfigLoader}(真实 SnakeYAML 解析)读 YAML 字节流,非桩。
 */
class BackendConfigLoaderParsingTest {

    private static BackendConfigLoader loader() {
        return new BackendConfigLoader(s -> null);
    }

    private static void load(String yaml) {
        loader().load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void floatMaxConcurrentTasksRejectedWithOriginalValue() {
        // SnakeYAML 解析 5.0 为 Double → asInt 拒绝(修复前 instanceof Number 截断为 5,掩盖错误)
        assertThatThrownBy(() -> load(yaml("maxConcurrentTasks: 5.0")))
                .isInstanceOf(BackendConfigException.class)
                .hasMessageContaining("maxConcurrentTasks")
                .hasMessageContaining("5.0");
    }

    @Test
    void outOfRangeLongMaxConcurrentTasksRejectedWithOriginalValue() {
        // 2147483648 > Integer.MAX_VALUE(2147483647)→ Long 超界 → 拒(修复前 n.intValue() 截断为 -2147483648)
        assertThatThrownBy(() -> load(yaml("maxConcurrentTasks: 2147483648")))
                .isInstanceOf(BackendConfigException.class)
                .hasMessageContaining("2147483648");
    }

    @Test
    void floatVersionRejectedWithOriginalValue() {
        // version: 1.0 → Double → readVersion 拒(修复前 n.longValue() → 1L,掩盖非整数版本号)
        assertThatThrownBy(() -> load("version: 1.0\nbackends: []\n"))
                .isInstanceOf(BackendConfigException.class)
                .hasMessageContaining("version")
                .hasMessageContaining("1.0");
    }

    @Test
    void validIntegerConfigLoads() {
        // 合法整数:version=1、maxConcurrentTasks=5(均在 int 范围)→ 正常加载 1 个后端
        BackendConfigLoader.LoadedBackends loaded = loader().load(
                new ByteArrayInputStream(yaml("maxConcurrentTasks: 5").getBytes(StandardCharsets.UTF_8)));
        assertThat(loaded.version()).isEqualTo(1L);
        assertThat(loaded.backends()).hasSize(1);
        assertThat(loaded.backends().get(0).maxConcurrentTasks()).isEqualTo(5);
    }

    /** 构造含单后端、可指定 maxConcurrentTasks 原始文本的最小合法 YAML(version=1)。 */
    private static String yaml(String maxConcurrentTasksLine) {
        return "version: 1\n"
                + "backends:\n"
                + "  - name: order\n"
                + "    url: http://localhost:8563\n"
                + "    protocol: STREAMABLE\n"
                + "    auth:\n"
                + "      mode: NONE\n"
                + "    " + maxConcurrentTasksLine + "\n";
    }
}
