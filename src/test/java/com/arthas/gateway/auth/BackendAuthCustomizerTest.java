package com.arthas.gateway.auth;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BackendAuthCustomizer 测试（T023）。
 *
 * <p>覆盖 BEARER/BASIC/NONE 三种 Authorization 头的注入（data-model.md §2 auth；
 * 认证头语义见 {@code reference/arthas-docs/03-MCP/后端接入契约.md}）：
 * <ul>
 *   <li>NONE：不发 Authorization 头</li>
 *   <li>BEARER：{@code Authorization: Bearer <token>}</li>
 *   <li>BASIC：{@code Authorization: Basic <base64(user:pass)>}</li>
 * </ul>
 * 经官方 SDK {@code McpSyncHttpClientRequestCustomizer}（非已弃用 customizeRequest）注入。
 * 纯逻辑单测（surefire）——直接驱动 customize() 应用到 {@link HttpRequest.Builder}，无需真实后端。
 */
class BackendAuthCustomizerTest {

    private static BackendConfig.Auth auth(AuthMode mode, String token, String user, String pass) {
        return new BackendConfig.Auth(mode, token, user, pass);
    }

    /** 应用 customizer 后取 Authorization 头值（缺失返 null）。 */
    private static String appliedAuthorizationHeader(BackendConfig.Auth auth) {
        URI uri = URI.create("http://127.0.0.1:8563/mcp");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        new BackendAuthCustomizer(auth).customize(builder, "POST", uri, "{}", null);
        return builder.POST(HttpRequest.BodyPublishers.noBody()).build()
                .headers().firstValue("Authorization").orElse(null);
    }

    @Test
    void noneAuthAddsNoAuthorizationHeader() {
        assertThat(appliedAuthorizationHeader(auth(AuthMode.NONE, null, null, null)))
                .as("NONE 不发 Authorization 头").isNull();
    }

    @Test
    void bearerAuthAddsBearerHeader() {
        assertThat(appliedAuthorizationHeader(auth(AuthMode.BEARER, "tok-123", null, null)))
                .isEqualTo("Bearer tok-123");
    }

    @Test
    void basicAuthAddsBase64EncodedUserPassword() {
        String header = appliedAuthorizationHeader(auth(AuthMode.BASIC, null, "ops", "p@ss"));
        assertThat(header).as("BASIC 头前缀").startsWith("Basic ");
        String decoded = new String(
                Base64.getDecoder().decode(header.substring("Basic ".length())), StandardCharsets.UTF_8);
        assertThat(decoded).as("解码后为 user:pass").isEqualTo("ops:p@ss");
    }

    @Test
    void headerValuePureFunctionIsDeterministic() {
        assertThat(BackendAuthCustomizer.headerValue(auth(AuthMode.BEARER, "xyz", null, null)))
                .isEqualTo("Bearer xyz");
        assertThat(BackendAuthCustomizer.headerValue(auth(AuthMode.NONE, null, null, null)))
                .as("NONE 头值为 null").isNull();
    }

    @Test
    void customizerOnlySetsAuthorizationHeader() {
        // 预置一个无关头，确认 customizer 仅加 Authorization、不破坏既有头
        URI uri = URI.create("http://127.0.0.1:8563/mcp");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).header("X-Trace", "1");
        new BackendAuthCustomizer(auth(AuthMode.BEARER, "t", null, null))
                .customize(builder, "POST", uri, "{}", null);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer t");
        assertThat(request.headers().firstValue("X-Trace")).as("既有头保留").contains("1");
    }
}
