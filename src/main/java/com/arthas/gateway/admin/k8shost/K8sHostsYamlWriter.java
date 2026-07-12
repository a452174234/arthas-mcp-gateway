package com.arthas.gateway.admin.k8shost;

import com.arthas.gateway.config.GatewayProperties;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * K8S Hosts 配置 YAML 写入器（006 波3，T025，仿 {@code BackendsYamlWriter}）。
 *
 * <p>把 {@code List<K8sHost>} 序列化为 {@code config/k8s-hosts.yaml}（version + hosts[]）。ssh 凭证
 *（password/private-key/passphrase）经 {@link K8sHostSecretCipher} 加密落盘（INV-PORTAL-K8S-4）；
 * 未配密钥时保留明文（Service 层应已拦截写凭证请求，INV-PORTAL-K8S-3）。
 *
 * <p>kebab-case key 与 {@link com.arthas.gateway.config.K8sHostsConfig} 解析一致（kubeconfig-remote-path 等）。
 * 写入触发 {@code K8sHostsWatcher} WatchService 热重载（portal CRUD → 热生效，INV-PORTAL-K8S-1）。
 */
public final class K8sHostsYamlWriter {

    public void write(Path file, long version, List<GatewayProperties.K8sHost> hosts,
                      K8sHostSecretCipher cipher) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", version);
        List<Map<String, Object>> hostsList = new ArrayList<>();
        for (GatewayProperties.K8sHost h : hosts) {
            hostsList.add(toMap(h, cipher));
        }
        root.put("hosts", hostsList);
        Files.writeString(file, new Yaml().dumpAsMap(root));
    }

    private static Map<String, Object> toMap(GatewayProperties.K8sHost h, K8sHostSecretCipher cipher) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", h.getName());
        m.put("namespace", h.getNamespace() != null ? h.getNamespace() : "default");
        if (h.getKubeconfig() != null && !h.getKubeconfig().isBlank()) {
            m.put("kubeconfig", h.getKubeconfig());
        }
        if (h.getSsh() != null) {
            m.put("ssh", toSshMap(h.getSsh(), cipher));
        }
        return m;
    }

    private static Map<String, Object> toSshMap(GatewayProperties.K8sHost.Ssh s, K8sHostSecretCipher cipher) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", s.getHost());
        m.put("port", s.getPort());
        m.put("user", s.getUser());
        if (s.getPassword() != null && !s.getPassword().isBlank()) {
            m.put("password", cipher != null && cipher.isConfigured() ? cipher.encrypt(s.getPassword()) : s.getPassword());
        }
        if (s.getPrivateKey() != null && !s.getPrivateKey().isBlank()) {
            m.put("private-key", cipher != null && cipher.isConfigured() ? cipher.encrypt(s.getPrivateKey()) : s.getPrivateKey());
        }
        if (s.getPassphrase() != null && !s.getPassphrase().isBlank()) {
            m.put("passphrase", cipher != null && cipher.isConfigured() ? cipher.encrypt(s.getPassphrase()) : s.getPassphrase());
        }
        m.put("kubeconfig-remote-path", s.getKubeconfigRemotePath());
        if (s.getServerOverride() != null && !s.getServerOverride().isBlank()) {
            m.put("server-override", s.getServerOverride());
        }
        if (s.isInsecureSkipTlsVerify()) {
            m.put("insecure-skip-tls-verify", true);
        }
        return m;
    }
}
