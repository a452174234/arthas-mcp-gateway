package com.arthas.gateway.auth;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T055 Noop 认证器单测（surefire，纯逻辑）——锚定 MVP 语义：受控内网恒放行。
 *
 * <p>演进时新增真实认证器（如 Bearer）替换此 Noop bean，接入点不变。
 */
class NoopGatewayAuthenticatorTest {

    @Test
    void alwaysAllows_anyHeaders_mvpTrustedIntranet() {
        GatewayAuthenticator auth = new NoopGatewayAuthenticator();
        assertThat(auth.authenticate(Map.of())).isTrue();
        assertThat(auth.authenticate(Map.of("Authorization", "Bearer anything"))).isTrue();
        assertThat(auth.authenticate(Map.of("X-Custom", "x"))).isTrue();
    }
}
