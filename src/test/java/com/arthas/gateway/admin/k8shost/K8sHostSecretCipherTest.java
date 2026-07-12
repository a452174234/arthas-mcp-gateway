package com.arthas.gateway.admin.k8shost;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * K8sHostSecretCipher 单测（006 波3，T021，TDD）。
 *
 * <p>AES-GCM 加密/解密 ssh 凭证（密钥来自 {@code ARTHAS_GATEWAY_SECRET}）。验证：往返、IV 随机（每次密文不同）、
 * 未配密钥 {@code isConfigured=false} + encrypt 抛 secret_key_not_configured（INV-PORTAL-K8S-3/4）。
 */
class K8sHostSecretCipherTest {

    /** 256-bit（32 字节）base64 密钥。 */
    private static final String SECRET_256 = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptDecryptRoundTrip() {
        K8sHostSecretCipher cipher = new K8sHostSecretCipher(SECRET_256);
        String enc = cipher.encrypt("root-password");
        assertThat(enc).isNotEqualTo("root-password");
        assertThat(cipher.decrypt(enc)).isEqualTo("root-password");
    }

    @Test
    void ivRandomProducesDifferentCiphertextEachTime() {
        K8sHostSecretCipher cipher = new K8sHostSecretCipher(SECRET_256);
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void notConfiguredWhenSecretNull() {
        assertThat(new K8sHostSecretCipher(null).isConfigured()).isFalse();
        assertThat(new K8sHostSecretCipher("  ").isConfigured()).isFalse();
    }

    @Test
    void configuredWhenSecretPresent() {
        assertThat(new K8sHostSecretCipher(SECRET_256).isConfigured()).isTrue();
    }

    @Test
    void encryptRejectsWhenNotConfigured() {
        K8sHostSecretCipher cipher = new K8sHostSecretCipher(null);
        assertThatThrownBy(() -> cipher.encrypt("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret_key_not_configured");
    }

    @Test
    void decryptRejectsWhenNotConfigured() {
        K8sHostSecretCipher cipher = new K8sHostSecretCipher(null);
        assertThatThrownBy(() -> cipher.decrypt("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret_key_not_configured");
    }
}
