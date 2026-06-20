package com.arthas.gateway.backend;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BackendConfig + BackendConfigLoader 契约测试（T021）。
 *
 * <p>纯逻辑单测（surefire，无需真实 arthas）。覆盖 data-model.md §2/§11 的解析与校验：
 * <ul>
 *   <li>解析多后端：字段、顺序、version 原样读取</li>
 *   <li>默认值：省略 protocol/connectTimeoutMs/callTimeoutMs/maxConcurrentTasks 时取 STREAMABLE/5000/30000/5</li>
 *   <li>${ENV:default} 占位解析（token 等机密字段从环境变量取，避免明文入库）</li>
 *   <li>校验失败（重名/非法 URL/非 http scheme/auth 与 mode 不对应/未知枚举/越界/缺 version）→ {@link BackendConfigException}，
 *       以便热重载调用方捕获后<b>保留旧注册表</b>、不半替换（data-model.md §11 规则 7）</li>
 * </ul>
 *
 * <p>TDD：先于实现编写，预期编译失败（BackendConfig/Loader/Exception/LodedBackends 尚不存在）。
 */
class BackendConfigLoaderTest {

    /** 默认 loader（环境变量解析，测试不依赖具体环境变量值）。 */
    private static BackendConfigLoader loader() {
        return new BackendConfigLoader();
    }

    /** 构造测试用 YAML 输入流。 */
    private static InputStream yaml(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    // ===== 解析（绿） =====

    @Test
    void loadsMultipleBackendsWithFieldsVersionAndOrder() {
        BackendConfigLoader.LoadedBackends loaded = loader().load(yaml("""
                version: 2
                backends:
                  - name: order-service
                    url: http://127.0.0.1:8563/mcp
                    protocol: STREAMABLE
                    auth:
                      mode: NONE
                    connectTimeoutMs: 5000
                    callTimeoutMs: 30000
                    maxConcurrentTasks: 5
                  - name: payment
                    url: http://127.0.0.1:8564/mcp
                    protocol: STREAMABLE
                    auth:
                      mode: BEARER
                      token: secret-pay
                    connectTimeoutMs: 7000
                    callTimeoutMs: 40000
                    maxConcurrentTasks: 3
                """));

        assertThat(loaded.version()).isEqualTo(2L);
        assertThat(loaded.backends()).extracting(BackendConfig::name)
                .containsExactly("order-service", "payment");

        BackendConfig order = find(loaded, "order-service");
        assertThat(order.url()).isEqualTo("http://127.0.0.1:8563/mcp");
        assertThat(order.protocol()).isEqualTo(Protocol.STREAMABLE);
        assertThat(order.auth().mode()).isEqualTo(AuthMode.NONE);
        assertThat(order.connectTimeoutMs()).isEqualTo(5000);
        assertThat(order.callTimeoutMs()).isEqualTo(30000);
        assertThat(order.maxConcurrentTasks()).isEqualTo(5);

        BackendConfig pay = find(loaded, "payment");
        assertThat(pay.auth().mode()).isEqualTo(AuthMode.BEARER);
        assertThat(pay.auth().token()).isEqualTo("secret-pay");
        assertThat(pay.maxConcurrentTasks()).isEqualTo(3);
    }

    @Test
    void appliesDefaultsWhenOptionalFieldsOmitted() {
        BackendConfigLoader.LoadedBackends loaded = loader().load(yaml("""
                version: 1
                backends:
                  - name: minimal
                    url: http://127.0.0.1:9000/mcp
                    auth:
                      mode: NONE
                """));

        BackendConfig cfg = find(loaded, "minimal");
        assertThat(cfg.protocol()).as("省略 protocol 默认 STREAMABLE").isEqualTo(Protocol.STREAMABLE);
        assertThat(cfg.connectTimeoutMs()).as("省略 connectTimeoutMs 默认 5000").isEqualTo(5000);
        assertThat(cfg.callTimeoutMs()).as("省略 callTimeoutMs 默认 30000").isEqualTo(30000);
        assertThat(cfg.maxConcurrentTasks()).as("省略 maxConcurrentTasks 默认 5").isEqualTo(5);
    }

    @Test
    void parsesBasicAuthCredentials() {
        BackendConfigLoader.LoadedBackends loaded = loader().load(yaml("""
                version: 1
                backends:
                  - name: legacy
                    url: http://127.0.0.1:8565/mcp
                    auth:
                      mode: BASIC
                      username: ops
                      password: p@ss
                """));

        BackendConfig cfg = find(loaded, "legacy");
        assertThat(cfg.auth().mode()).isEqualTo(AuthMode.BASIC);
        assertThat(cfg.auth().username()).isEqualTo("ops");
        assertThat(cfg.auth().password()).isEqualTo("p@ss");
    }

    // ===== ${ENV:default} 占位解析 =====

    @Test
    void resolvesBearerTokenFromEnvPlaceholderWhenEnvPresent() {
        Function<String, String> env = key -> "PAYMENT_TOKEN".equals(key) ? "env-secret" : null;
        BackendConfigLoader.LoadedBackends loaded = new BackendConfigLoader(env).load(yaml("""
                version: 1
                backends:
                  - name: payment
                    url: http://127.0.0.1:8564/mcp
                    auth:
                      mode: BEARER
                      token: ${PAYMENT_TOKEN:change-me}
                """));

        assertThat(find(loaded, "payment").auth().token())
                .as("环境变量存在时用其值（机密不入库）").isEqualTo("env-secret");
    }

    @Test
    void resolvesPlaceholderToDefaultWhenEnvAbsent() {
        Function<String, String> alwaysAbsent = key -> null;
        BackendConfigLoader.LoadedBackends loaded = new BackendConfigLoader(alwaysAbsent).load(yaml("""
                version: 1
                backends:
                  - name: payment
                    url: http://127.0.0.1:8564/mcp
                    auth:
                      mode: BEARER
                      token: ${PAYMENT_TOKEN:change-me}
                """));

        assertThat(find(loaded, "payment").auth().token())
                .as("环境变量缺失时用占位默认值").isEqualTo("change-me");
    }

    // ===== 校验失败（保留旧表） =====

    @Test
    void rejectsDuplicateBackendNames() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: dup
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: NONE }
                  - name: dup
                    url: http://127.0.0.1:2/mcp
                    auth: { mode: NONE }
                """)))
                .isInstanceOf(BackendConfigException.class)
                .hasMessageContaining("dup");
    }

    @Test
    void rejectsUrlWithoutScheme() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: bad
                    url: not-a-url
                    auth: { mode: NONE }
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: ftp
                    url: ftp://127.0.0.1:8563/mcp
                    auth: { mode: NONE }
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsBearerWithoutToken() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: b
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: BEARER }
                """)))
                .isInstanceOf(BackendConfigException.class)
                .hasMessageContaining("b");
    }

    @Test
    void rejectsBasicWithoutCredentials() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: c
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: BASIC }
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsBasicWithOnlyUsername() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: c2
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: BASIC, username: ops }
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsUnknownProtocol() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: p
                    url: http://127.0.0.1:1/mcp
                    protocol: WEIRD
                    auth: { mode: NONE }
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsUnknownAuthMode() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: a
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: OAUTH }
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsMaxConcurrentTasksAboveHardLimit() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: over
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: NONE }
                    maxConcurrentTasks: 6
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsNonPositiveMaxConcurrentTasks() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: zero
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: NONE }
                    maxConcurrentTasks: 0
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: "   "
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: NONE }
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsMissingVersion() {
        assertThatThrownBy(() -> loader().load(yaml("""
                backends:
                  - name: nover
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: NONE }
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsNonPositiveConnectTimeout() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: t
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: NONE }
                    connectTimeoutMs: 0
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsMalformedYaml() {
        assertThatThrownBy(() -> loader().load(yaml("version: 1\nbackends: \"this is a string not a list\"\n")))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void loadedBackendsWithEmptyListWhenNoBackendsKey() {
        // backends 缺失视为空注册表（version 仍须存在）；语义：允许「无后端」配置，非错误
        BackendConfigLoader.LoadedBackends loaded = loader().load(yaml("version: 1\n"));
        assertThat(loaded.version()).isEqualTo(1L);
        assertThat(loaded.backends()).isEmpty();
    }

    @Test
    void loadedBackendsIsImmutable() {
        BackendConfigLoader.LoadedBackends loaded = loader().load(yaml("""
                version: 1
                backends:
                  - name: x
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: NONE }
                """));
        assertThatThrownBy(() -> loaded.backends().add(
                new BackendConfig("y", "http://127.0.0.1:2/mcp", Protocol.STREAMABLE,
                        new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                        5000, 30000, 5)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ===== 辅助 =====

    private static BackendConfig find(BackendConfigLoader.LoadedBackends loaded, String name) {
        return loaded.backends().stream()
                .filter(b -> b.name().equals(name))
                .findFirst().orElseThrow();
    }
}
