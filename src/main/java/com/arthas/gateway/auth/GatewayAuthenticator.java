package com.arthas.gateway.auth;

import java.util.Map;

/**
 * 网关侧认证接口（T055 演进占位，宪法「认证」演进首要项）。
 *
 * <p>认证<b>调用方</b>是面向 Claude Code 的<b>入站</b> MCP 请求（与 {@link BackendAuthCustomizer} 区分——
 * 后者是网关作为客户端对<b>出站</b> arthas 后端的认证头注入）。
 *
 * <p><b>MVP 不承载逻辑</b>：受控内网、无认证（{@link NoopGatewayAuthenticator} 恒放行）。本接口为未来认证演进
 * （Bearer token / API key / mTLS 等）预留接入缝——届时实现此接口并接入请求过滤（如 servlet filter /
 * MCP 拦截器），MVP 阶段此 seam <b>不</b>接入请求流（{@code authenticate} 在 MVP 下不被调用）。
 *
 * <p>注意：后端侧 401（arthas 拒绝网关的认证头）属 C-AUTH-1，随认证后端夹具一并落地（见
 * {@code BackendClient} javadoc）——与本网关侧认证是两个独立关注点。
 */
public interface GatewayAuthenticator {

    /**
     * 校验入站请求的认证凭证（如 {@code Authorization} 头）。
     *
     * @param headers 入站请求头（只读视图；键大小写不敏感由实现处理）
     * @return {@code true}=放行；{@code false}=拒绝（调用方据此后续返 401 / JSON-RPC error）
     */
    boolean authenticate(Map<String, String> headers);
}
