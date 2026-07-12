package com.arthas.gateway.admin.k8shost;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * SSH 凭证 AES-GCM 加密器（006 波3，T024，research.md R7）。
 *
 * <p>portal 接收明文 ssh 密码 → {@link #encrypt(String)} 加密落盘 {@code config/k8s-hosts.yaml}；
 * {@code K8sHostsWatcher} 加载时 {@link #decrypt(String)} 解密。密钥来自 {@code ARTHAS_GATEWAY_SECRET}
 * 环境变量（128/256-bit base64）。未配密钥 → {@code isConfigured()=false}，{@link #encrypt} 抛
 * {@code secret_key_not_configured}（portal 写凭证端点返 400，INV-PORTAL-K8S-3）。
 *
 * <p>AES-GCM（IV=12B 随机 + 128-bit tag）提供机密性 + 完整性；IV 附密文前（base64 编码）。
 */
public final class K8sHostSecretCipher {

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN_BYTES = 12;
    private static final int TAG_LEN_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param secretBase64 ARTHAS_GATEWAY_SECRET（base64，16 或 32 字节）；null/空 → 未配置
     */
    public K8sHostSecretCipher(String secretBase64) {
        this.key = decodeKey(secretBase64);
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plain) {
        requireConfigured();
        try {
            byte[] iv = new byte[IV_LEN_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LEN_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("ssh 凭证加密失败：" + safeMsg(e), e);
        }
    }

    public String decrypt(String enc) {
        requireConfigured();
        try {
            byte[] all = Base64.getDecoder().decode(enc);
            byte[] iv = new byte[IV_LEN_BYTES];
            byte[] ct = new byte[all.length - IV_LEN_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_LEN_BYTES);
            System.arraycopy(all, IV_LEN_BYTES, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LEN_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("ssh 凭证解密失败：" + safeMsg(e), e);
        }
    }

    private void requireConfigured() {
        if (key == null) {
            throw new IllegalStateException("secret_key_not_configured（未设 ARTHAS_GATEWAY_SECRET 环境变量）");
        }
    }

    private static String safeMsg(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static SecretKeySpec decodeKey(String secretBase64) {
        if (secretBase64 == null || secretBase64.isBlank()) {
            return null;
        }
        byte[] keyBytes = Base64.getDecoder().decode(secretBase64);
        if (keyBytes.length != 16 && keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "ARTHAS_GATEWAY_SECRET 须为 128-bit 或 256-bit（16/32 字节 base64），实际 " + keyBytes.length + " 字节");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
