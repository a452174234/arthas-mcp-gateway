package com.arthas.gateway.auth;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MVP 默认认证器——恒放行（{@link GatewayAuthenticator} 的 Noop 实现）。
 *
 * <p>对应 MVP 假设：网关部署在<b>受控内网</b>、无入站认证，靠网络隔离保护（见 spec 假设 / quickstart §1）。
 * 作为 {@code @Component} bean 存在于上下文，为未来认证演进提供可注入的默认实现——届时替换为真实认证器
 * （{@code BearerGatewayAuthenticator} 等）即可，无需改动接入点。
 *
 * <p>MVP 阶段<b>无人调用</b> {@link #authenticate}（请求流未接入认证过滤）；本 bean 仅作 DI 缝与演进锚点存在。
 */
@Component
public class NoopGatewayAuthenticator implements GatewayAuthenticator {

    @Override
    public boolean authenticate(Map<String, String> headers) {
        return true; // MVP 受控内网：恒放行（无入站认证逻辑）
    }
}
