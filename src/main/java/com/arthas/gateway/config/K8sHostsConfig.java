package com.arthas.gateway.config;

import com.arthas.gateway.config.GatewayProperties.K8sHost;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * K8S Host 配置加载器（006 波2，T018）：解析 {@code config/k8s-hosts.yaml} → {@code List<K8sHost>}。
 *
 * <p>文件不存在 → 回退 {@link GatewayProperties#getK8sHosts()}（application.yml 内联，005 兼容，INV-HOT-5）。
 * 解析用 SnakeYAML（手动 Map → K8sHost，kebab-case key 与 Spring 绑定一致）。
 * 解析失败由调用方（{@code K8sHostsWatcher}）捕获并保留旧配置（INV-HOT-4）。
 *
 * <p>仅加载 host 列表（连接相关）；k8s 全局参数热生效由 {@code K8sParams}（T020）承担。
 */
public final class K8sHostsConfig {

    /** 加载结果：hosts 列表 + 是否走了回退（文件不存在）。 */
    public record LoadedHosts(List<K8sHost> hosts, boolean fromFallback) {
    }

    /**
     * 加载 K8S Host 列表。
     *
     * @param file  config/k8s-hosts.yaml 路径
     * @param props 网关配置（回退源：内联 k8s-hosts）
     * @return 加载结果
     * @throws IOException 文件读取失败
     */
    public LoadedHosts loadHosts(Path file, GatewayProperties props) throws IOException {
        if (!Files.exists(file)) {
            return new LoadedHosts(props.getK8sHosts(), true);
        }
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> root = new Yaml().load(in);
            List<K8sHost> hosts = new ArrayList<>();
            if (root != null) {
                Object hostsObj = root.get("hosts");
                if (hostsObj instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            hosts.add(toK8sHost(m));
                        }
                    }
                }
            }
            return new LoadedHosts(hosts, false);
        }
    }

    @SuppressWarnings("unchecked")
    private static K8sHost toK8sHost(Map<?, ?> m) {
        K8sHost h = new K8sHost();
        h.setName((String) m.get("name"));
        Object ns = m.get("namespace");
        h.setNamespace(ns != null ? (String) ns : "default");
        Object kc = m.get("kubeconfig");
        if (kc != null) {
            h.setKubeconfig((String) kc);
        }
        Object ssh = m.get("ssh");
        if (ssh instanceof Map<?, ?> sm) {
            h.setSsh(toSsh((Map<String, Object>) sm));
        }
        return h;
    }

    private static GatewayProperties.K8sHost.Ssh toSsh(Map<String, Object> m) {
        GatewayProperties.K8sHost.Ssh s = new GatewayProperties.K8sHost.Ssh();
        s.setHost((String) m.get("host"));
        Object port = m.get("port");
        if (port instanceof Number n) {
            s.setPort(n.intValue());
        }
        s.setUser((String) m.get("user"));
        s.setPassword((String) m.get("password"));
        s.setPrivateKey((String) m.get("private-key"));
        s.setPassphrase((String) m.get("passphrase"));
        s.setKubeconfigRemotePath((String) m.get("kubeconfig-remote-path"));
        s.setServerOverride((String) m.get("server-override"));
        Object insecure = m.get("insecure-skip-tls-verify");
        if (insecure instanceof Boolean b) {
            s.setInsecureSkipTlsVerify(b);
        }
        return s;
    }

    /**
     * 006 波2 T020：加载全局 K8S 参数（{@code config/k8s-hosts.yaml} 的 {@code k8s-params} 段）。
     *
     * <p>文件不存在 / 无 {@code k8s-params} 段 → 回退 {@link K8sParams#from(GatewayProperties.K8s)}（启动期绑定，005 兼容）。
     * Duration 用 Spring {@code DurationStyle.SIMPLE}（支持 {@code 5m}/{@code 30s} 简洁格式）。
     */
    public K8sParams loadParams(Path file, GatewayProperties props) throws IOException {
        K8sParams fallback = K8sParams.from(props.getK8s());
        if (!Files.exists(file)) {
            return fallback;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> root = new Yaml().load(in);
            if (root == null || !(root.get("k8s-params") instanceof Map<?, ?> raw)) {
                return fallback;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> pm = (Map<String, Object>) raw;
            return new K8sParams(
                    str(pm, "target-ip", fallback.targetIp()),
                    intVal(pm, "mcp-port", fallback.mcpPort()),
                    str(pm, "arthas-version", fallback.arthasVersion()),
                    str(pm, "arthas-password", fallback.arthasPassword()),
                    durationVal(pm, "ensure-timeout", fallback.ensureTimeout()),
                    str(pm, "node-port-range", fallback.nodePortRange()),
                    str(pm, "arthas-boot-jar", fallback.arthasBootJar()));
        }
    }

    private static String str(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v != null ? v.toString() : fallback;
    }

    private static int intVal(Map<String, Object> m, String key, int fallback) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static Duration durationVal(Map<String, Object> m, String key, Duration fallback) {
        Object v = m.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return org.springframework.boot.convert.DurationStyle.SIMPLE.parse(v.toString());
        } catch (Exception e) {
            return fallback;
        }
    }
}
