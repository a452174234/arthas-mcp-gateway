package com.arthas.gateway.admin.backend;

import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.Source;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 004 backends.yaml 结构化写回（research.md R2：SnakeYAML dump 重写，不保留原文注释）。
 *
 * <p>静态后端 CRUD（POST/PUT/DELETE）经此写回 {@code config/backends.yaml}，触发既有 WatchService 热重载（R7）。
 * {@code version} 由调用方递增传入（{@code BackendRegistryReloader} 按 version 去重，须变才触发重载）。
 *
 * <p>已知限制（R2）：dump 不保留注释；机密字段写回当前解析值（${ENV} 占位符还原后置）。
 */
@Component
public class BackendsYamlWriter {

    private final Yaml yaml = new Yaml();

    /** 写回 version + backends 到 file（覆盖，UTF-8）。 */
    public void write(Path file, long version, List<BackendConfig> backends) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", version);
        root.put("backends", backends.stream().map(this::toMap).toList());
        Files.writeString(file, yaml.dumpAsMap(root), StandardCharsets.UTF_8);
    }

    private Map<String, Object> toMap(BackendConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", cfg.name());
        m.put("url", cfg.url());
        m.put("protocol", cfg.protocol().name());
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("mode", cfg.auth().mode().name());
        if (cfg.auth().token() != null) {
            auth.put("token", cfg.auth().token());
        }
        if (cfg.auth().username() != null) {
            auth.put("username", cfg.auth().username());
        }
        if (cfg.auth().password() != null) {
            auth.put("password", cfg.auth().password());
        }
        m.put("auth", auth);
        m.put("connectTimeoutMs", cfg.connectTimeoutMs());
        m.put("callTimeoutMs", cfg.callTimeoutMs());
        m.put("maxConcurrentTasks", cfg.maxConcurrentTasks());
        // source：仅 DYNAMIC 显式写（STATIC 缺省，向后兼容）
        if (cfg.source() == Source.DYNAMIC) {
            m.put("source", "DYNAMIC");
        }
        return m;
    }
}
