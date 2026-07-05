package com.arthas.gateway.admin.backend;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendConfigLoader;
import com.arthas.gateway.backend.BackendConfigLoader.LoadedBackends;
import com.arthas.gateway.backend.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 004 BackendsYamlWriter 契约（T011）：写回 round-trip + 不保留注释（R2）+ auth 保留。
 */
class BackendsYamlWriterTest {

    @TempDir
    Path tmp;

    @Test
    void writeRoundTripsThroughLoader_invFile1() throws IOException {
        Path file = tmp.resolve("backends.yaml");
        BackendConfig cfg = new BackendConfig("svc", "http://h:8563",
                Protocol.STREAMABLE, new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
        new BackendsYamlWriter().write(file, 2L, List.of(cfg));

        try (InputStream in = Files.newInputStream(file)) {
            LoadedBackends loaded = new BackendConfigLoader().load(in);
            assertThat(loaded.version()).isEqualTo(2L);
            assertThat(loaded.backends()).hasSize(1);
            BackendConfig rt = loaded.backends().get(0);
            assertThat(rt.name()).isEqualTo("svc");
            assertThat(rt.url()).isEqualTo("http://h:8563");
            assertThat(rt.auth().mode()).isEqualTo(AuthMode.NONE);
        }
    }

    @Test
    void writeDoesNotPreserveComments_r2() throws IOException {
        Path file = tmp.resolve("backends.yaml");
        Files.writeString(file, "# 顶部注释\nversion: 1\nbackends: []\n# 尾注释\n");
        new BackendsYamlWriter().write(file, 2L, List.of());

        String content = Files.readString(file);
        assertThat(content).as("R2：SnakeYAML dump 不保留原文注释").doesNotContain("# 顶部注释", "# 尾注释");
        assertThat(content).contains("version: 2");
    }

    @Test
    void writeBearerAuthPreserved() throws IOException {
        Path file = tmp.resolve("backends.yaml");
        BackendConfig cfg = new BackendConfig("pay", "http://h:8564",
                Protocol.STREAMABLE, new BackendConfig.Auth(AuthMode.BEARER, "tok", null, null),
                5000, 30000, 3);
        new BackendsYamlWriter().write(file, 1L, List.of(cfg));

        try (InputStream in = Files.newInputStream(file)) {
            LoadedBackends loaded = new BackendConfigLoader().load(in);
            assertThat(loaded.backends().get(0).auth().mode()).isEqualTo(AuthMode.BEARER);
            assertThat(loaded.backends().get(0).auth().token()).isEqualTo("tok");
            assertThat(loaded.backends().get(0).maxConcurrentTasks()).isEqualTo(3);
        }
    }
}
