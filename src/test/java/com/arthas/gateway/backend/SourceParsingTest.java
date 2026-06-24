package com.arthas.gateway.backend;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T011 {@code source} 来源标记解析测试（surefire 波次 A，纯逻辑，无 K8S）。
 *
 * <p>断言 D-SOURCE-1（见 contracts/dynamic-registration-invariants.md §5 / data-model §2/§3）：
 * <ul>
 *   <li>YAML 缺省 {@code source} → {@link Source#STATIC}（向后兼容，001 既有种子零改动可用）。</li>
 *   <li>显式 {@code source: STATIC} → STATIC。</li>
 *   <li>显式 {@code source: DYNAMIC} → DYNAMIC（loader 须能解析，纵使常态下动态 target 不经 YAML）。</li>
 *   <li>7 参构造（向后兼容）→ STATIC。</li>
 *   <li>{@code source} 不参与 equals（data-model §2：不参与复用判定核心）——同其余字段、异 source 仍 equal。</li>
 * </ul>
 *
 * <p>TDD：先于实现编写（red：{@link Source} 枚举 / {@code source()} 访问器尚不存在）。
 * 「动态注册路径强制 DYNAMIC」由 {@code DynamicBackendStoreTest}（T015）覆盖。
 */
class SourceParsingTest {

    private static InputStream yaml(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    private static BackendConfig first(BackendConfigLoader.LoadedBackends loaded) {
        return loaded.backends().get(0);
    }

    // ===== D-SOURCE-1：YAML 缺省 source → STATIC =====

    @Test
    void yamlOmittingSourceDefaultsToStatic() {
        BackendConfig cfg = first(new BackendConfigLoader().load(yaml("""
                version: 1
                backends:
                  - name: order
                    url: http://127.0.0.1:8563
                    protocol: STREAMABLE
                    auth: { mode: NONE }
                """)));
        assertThat(cfg.source()).as("缺省 source → STATIC（向后兼容）").isEqualTo(Source.STATIC);
    }

    @Test
    void yamlExplicitStaticParsesToStatic() {
        BackendConfig cfg = first(new BackendConfigLoader().load(yaml("""
                version: 1
                backends:
                  - name: order
                    url: http://127.0.0.1:8563
                    protocol: STREAMABLE
                    auth: { mode: NONE }
                    source: STATIC
                """)));
        assertThat(cfg.source()).isEqualTo(Source.STATIC);
    }

    @Test
    void yamlExplicitDynamicParsesToDynamic() {
        BackendConfig cfg = first(new BackendConfigLoader().load(yaml("""
                version: 1
                backends:
                  - name: dyn
                    url: http://10.0.0.5:31234
                    protocol: STREAMABLE
                    auth: { mode: NONE }
                    source: DYNAMIC
                """)));
        assertThat(cfg.source()).as("loader 须能解析显式 DYNAMIC").isEqualTo(Source.DYNAMIC);
    }

    @Test
    void yamlInvalidSourceValueRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new BackendConfigLoader().load(yaml("""
                                version: 1
                                backends:
                                  - name: order
                                    url: http://127.0.0.1:8563
                                    protocol: STREAMABLE
                                    auth: { mode: NONE }
                                    source: BANANA
                                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    // ===== 向后兼容：7 参构造 → STATIC =====

    @Test
    void sevenArgConstructorDefaultsToStatic() {
        BackendConfig cfg = new BackendConfig(
                "order", "http://127.0.0.1:8563", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
        assertThat(cfg.source()).as("既有 7 参调用点零改动 → STATIC").isEqualTo(Source.STATIC);
    }

    @Test
    void eightArgConstructorHonorsExplicitSource() {
        BackendConfig cfg = new BackendConfig(
                "order", "http://127.0.0.1:8563", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, Source.DYNAMIC);
        assertThat(cfg.source()).isEqualTo(Source.DYNAMIC);
    }

    // ===== source 不参与 equals（data-model §2） =====

    @Test
    void sourceDoesNotParticipateInEquals() {
        BackendConfig staticCfg = new BackendConfig(
                "order", "http://127.0.0.1:8563", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, Source.STATIC);
        BackendConfig dynamicCfg = new BackendConfig(
                "order", "http://127.0.0.1:8563", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, Source.DYNAMIC);
        assertThat(dynamicCfg)
                .as("同 name/url/auth/超时/并发、异 source → 仍 equal（source 不参与复用判定）")
                .isEqualTo(staticCfg)
                .hasSameHashCodeAs(staticCfg);
    }
}
