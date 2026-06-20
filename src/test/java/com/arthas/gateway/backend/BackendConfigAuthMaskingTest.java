package com.arthas.gateway.backend;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T021 {@link BackendConfig.Auth} 凭据脱敏单测(002 整改 · P3-1/FR-011,US5)。
 *
 * <p>验证各 {@link AuthMode} 下 {@code Auth.toString()} <b>不含明文</b> token/username/password——
 * 仅 mode + 掩码({@code ****XX},末 2 位)。修复前 record 默认 toString 泄漏全部明文凭据(日志/异常栈中可见)。
 */
class BackendConfigAuthMaskingTest {

    @Test
    void bearerTokenIsMasked() {
        BackendConfig.Auth auth = new BackendConfig.Auth(AuthMode.BEARER, "super-secret-token", null, null);
        String s = auth.toString();
        assertThat(s).contains("BEARER");
        assertThat(s).as("不含明文 token").doesNotContain("super-secret-token");
        assertThat(s).as("含掩码前缀").contains("****");
        assertThat(s).as("含末 2 位定位").contains("en"); // "super-secret-tok**en**"
    }

    @Test
    void basicCredentialsAreMasked() {
        BackendConfig.Auth auth = new BackendConfig.Auth(AuthMode.BASIC, null, "admin-user", "p@ssw0rd!");
        String s = auth.toString();
        assertThat(s).contains("BASIC");
        assertThat(s).as("不含明文 username").doesNotContain("admin-user");
        assertThat(s).as("不含明文 password").doesNotContain("p@ssw0rd!");
        assertThat(s).as("username/password 均掩码").contains("****");
    }

    @Test
    void noneModeHasNoCredentialFields() {
        BackendConfig.Auth auth = new BackendConfig.Auth(AuthMode.NONE, null, null, null);
        String s = auth.toString();
        assertThat(s).contains("NONE");
        assertThat(s).as("NONE 不暴露凭据字段").doesNotContain("token").doesNotContain("password");
    }

    @Test
    void shortCredentialStillMaskedWithoutLeak() {
        // 短凭据(<=2 位)不泄露任何明文片段
        BackendConfig.Auth auth = new BackendConfig.Auth(AuthMode.BEARER, "ab", null, null);
        String s = auth.toString();
        assertThat(s).doesNotContain("ab");
        assertThat(s).contains("****");
    }
}
