package com.arthas.gateway.auth;

import com.arthas.gateway.backend.BackendConfig;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * 后端认证头注入（T023，data-model.md §2 auth）。
 *
 * <p>实现官方 SDK {@link McpSyncHttpClientRequestCustomizer}（经 transport builder 的
 * {@code httpRequestCustomizer(...)} 注入，<b>非</b>已弃用的 {@code customizeRequest()}）。
 * 在每条发往后端的 HTTP 请求上按后端 auth 模式设置 {@code Authorization} 头：
 * <ul>
 *   <li>NONE：不发头；</li>
 *   <li>BEARER：{@code Authorization: Bearer <token>}；</li>
 *   <li>BASIC：{@code Authorization: Basic <base64(user:pass)>}。</li>
 * </ul>
 *
 * <p>认证头值在构造时按不可变 {@link BackendConfig.Auth} 预计算并缓存（同一后端请求间恒定），
 * {@link #customize} 仅应用之，不依赖 method/uri/body/context。
 */
public final class BackendAuthCustomizer implements McpSyncHttpClientRequestCustomizer {

    private static final String AUTHORIZATION = "Authorization";

    private final String headerValue;

    public BackendAuthCustomizer(BackendConfig.Auth auth) {
        this.headerValue = headerValue(Objects.requireNonNull(auth, "auth 不可为空"));
    }

    /** 工厂：便于调用方从 BackendConfig 直接构造。 */
    public static BackendAuthCustomizer forBackend(BackendConfig config) {
        return new BackendAuthCustomizer(config.auth());
    }

    /** 计算 {@code Authorization} 头值（NONE → null，表示不发头）。纯函数，便于单测。 */
    static String headerValue(BackendConfig.Auth auth) {
        return switch (auth.mode()) {
            case NONE -> null;
            case BEARER -> "Bearer " + auth.token();
            case BASIC -> "Basic " + Base64.getEncoder().encodeToString(
                    (auth.username() + ":" + auth.password()).getBytes(StandardCharsets.UTF_8));
        };
    }

    @Override
    public void customize(HttpRequest.Builder builder, String method, URI uri, String body, McpTransportContext context) {
        if (headerValue != null) {
            builder.header(AUTHORIZATION, headerValue);
        }
    }
}
