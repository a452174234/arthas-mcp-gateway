package com.arthas.gateway.admin.backend;

import com.arthas.gateway.admin.backend.dto.BackendDto;
import com.arthas.gateway.admin.backend.dto.CreateBackendRequest;
import com.arthas.gateway.admin.backend.dto.UpdateBackendRequest;
import com.arthas.gateway.admin.backend.exception.BackendAdminException;
import com.arthas.gateway.admin.backend.exception.BackendConflictException;
import com.arthas.gateway.admin.backend.exception.BackendNotFoundException;
import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendConfigLoader;
import com.arthas.gateway.backend.BackendConfigLoader.LoadedBackends;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.backend.Source;
import com.arthas.gateway.config.GatewayProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 004 后端配置 CRUD 业务编排（admin-api-contract §1，research.md R3/R7）。
 *
 * <p>静态后端：经 {@link BackendsYamlWriter} 写回 {@code config/backends.yaml}（version 递增触发热重载，SC-002）。
 * 动态后端（source=DYNAMIC，003 ensure 产生）：POST/PUT 拒绝（INV-DYN-1）、DELETE = {@link DynamicBackendStore#unregister}。
 *
 * <p>核心逻辑在 Java（宪法原则六）；前端仅消费此服务暴露的 /admin API。
 */
@Service
public class BackendAdminService {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_CALL_TIMEOUT_MS = 30000;
    private static final int DEFAULT_MAX_CONCURRENT = 5;

    private final RegistryHolder registryHolder;
    private final DynamicBackendStore dynamicStore;
    private final BackendsYamlWriter yamlWriter;
    private final BackendConfigLoader loader;
    private final Path backendsFile;

    @Autowired
    public BackendAdminService(RegistryHolder registryHolder, DynamicBackendStore dynamicStore,
                               BackendsYamlWriter yamlWriter, GatewayProperties props) {
        this(registryHolder, dynamicStore, yamlWriter, new BackendConfigLoader(), Path.of(props.getBackendsFile()));
    }

    /** 测试用：注入 loader + backendsFile（便于 @TempDir）。 */
    BackendAdminService(RegistryHolder registryHolder, DynamicBackendStore dynamicStore,
                        BackendsYamlWriter yamlWriter, BackendConfigLoader loader, Path backendsFile) {
        this.registryHolder = registryHolder;
        this.dynamicStore = dynamicStore;
        this.yamlWriter = yamlWriter;
        this.loader = loader;
        this.backendsFile = backendsFile;
    }

    public List<BackendDto> list() {
        java.util.Map<String, BackendEntry> byName = registryHolder.current().byName();
        List<BackendDto> dtos = new ArrayList<>();
        for (BackendEntry e : byName.values()) {
            dtos.add(toDto(e));
        }
        // 006 波4 T030：兜底——BackendConfigWatcher compose 异常吞咽（:190-196）时 holder 可能缺新 dynamic
        //（ensure 后 portal 不可见 bug 根因之一）。补 dynamicStore 中未进 holder 的（默认 ACTIVE/healthy/CLOSED），
        // 保证 ensure 纳管后 portal list 可见（INV-DISP-1）。正常情况 holder 已含，兜底无加（不影响）。
        for (BackendConfig dyn : dynamicStore.list()) {
            if (!byName.containsKey(dyn.name())) {
                dtos.add(toDto(dyn, "ACTIVE", true, "CLOSED"));
            }
        }
        return dtos;
    }

    public BackendDto get(String name) {
        BackendEntry entry = registryHolder.get(name)
                .orElseThrow(() -> new BackendNotFoundException(name, availableNames()));
        return toDto(entry);
    }

    public BackendDto create(CreateBackendRequest req) {
        String name = requireNonBlank(req.name(), "name");
        requireNonBlank(req.url(), "url");
        if (registryHolder.current().names().contains(name)) {
            throw new BackendConflictException("name 已存在：" + name, "duplicate_name");
        }
        BackendConfig cfg = toBackendConfig(req, name);
        LoadedBackends loaded = loadCurrent();
        List<BackendConfig> updated = new ArrayList<>(loaded.backends());
        updated.add(cfg);
        writeYaml(loaded.version() + 1, updated);
        return toDto(cfg, "ACTIVE", false, "CLOSED");
    }

    public BackendDto update(String name, UpdateBackendRequest req) {
        BackendEntry entry = registryHolder.get(name)
                .orElseThrow(() -> new BackendNotFoundException(name, availableNames()));
        if (entry.config().source() == Source.DYNAMIC) {
            throw new BackendConflictException(
                    "动态后端不可改（须先删再 ensure）：" + name, "dynamic_backend_not_editable");
        }
        BackendConfig merged = mergeConfig(entry.config(), req);
        LoadedBackends loaded = loadCurrent();
        List<BackendConfig> newList = loaded.backends().stream()
                .map(b -> b.name().equals(name) ? merged : b)
                .toList();
        writeYaml(loaded.version() + 1, newList);
        return toDto(merged, "ACTIVE", false, "CLOSED");
    }

    public void delete(String name) {
        BackendEntry entry = registryHolder.get(name)
                .orElseThrow(() -> new BackendNotFoundException(name, availableNames()));
        if (entry.config().source() == Source.DYNAMIC) {
            dynamicStore.unregister(name);
            return;
        }
        LoadedBackends loaded = loadCurrent();
        List<BackendConfig> newList = loaded.backends().stream()
                .filter(b -> !b.name().equals(name))
                .toList();
        writeYaml(loaded.version() + 1, newList);
    }

    private List<String> availableNames() {
        return List.copyOf(registryHolder.current().names());
    }

    private LoadedBackends loadCurrent() {
        try (InputStream in = Files.newInputStream(backendsFile)) {
            return loader.load(in);
        } catch (IOException e) {
            throw new BackendAdminException("读取 backends.yaml 失败：" + backendsFile, e);
        }
    }

    private void writeYaml(long version, List<BackendConfig> backends) {
        try {
            yamlWriter.write(backendsFile, version, backends);
        } catch (IOException e) {
            throw new BackendAdminException("写回 backends.yaml 失败：" + backendsFile, e);
        }
    }

    private BackendDto toDto(BackendEntry entry) {
        BackendConfig cfg = entry.config();
        return toDto(cfg, entry.state().name(), entry.isHealthy(), entry.breaker().state().name());
    }

    BackendDto toDto(BackendConfig cfg, String state, boolean healthy, String breaker) {
        boolean k8s = cfg.isK8sMode();
        return new BackendDto(
                cfg.name(),
                cfg.source().name(),
                state,
                healthy,
                breaker,
                cfg.url(),
                cfg.protocol().name(),
                cfg.auth().mode().name(),
                cfg.connectTimeoutMs(),
                cfg.callTimeoutMs(),
                cfg.maxConcurrentTasks(),
                // 006 波4 T031：K8S 来源字段（k8sHost/pod/sourceDetail 从 cfg；namespace/ensureStatus 后置填充）
                k8s ? cfg.k8sHost() : null,
                k8s ? cfg.pod() : null,
                null,
                k8s ? "k8s:" + cfg.k8sHost() : "static",
                null);
    }

    private BackendConfig toBackendConfig(CreateBackendRequest req, String name) {
        Protocol protocol = req.protocol() != null
                ? Protocol.valueOf(req.protocol().trim().toUpperCase()) : Protocol.STREAMABLE;
        AuthMode mode = req.authMode() != null
                ? AuthMode.valueOf(req.authMode().trim().toUpperCase()) : AuthMode.NONE;
        BackendConfig.Auth auth = new BackendConfig.Auth(mode, req.token(), req.username(), req.password());
        return new BackendConfig(name, req.url(), protocol, auth,
                req.connectTimeoutMs() != null ? req.connectTimeoutMs() : DEFAULT_CONNECT_TIMEOUT_MS,
                req.callTimeoutMs() != null ? req.callTimeoutMs() : DEFAULT_CALL_TIMEOUT_MS,
                req.maxConcurrentTasks() != null ? req.maxConcurrentTasks() : DEFAULT_MAX_CONCURRENT,
                Source.STATIC);
    }

    private BackendConfig mergeConfig(BackendConfig existing, UpdateBackendRequest req) {
        String url = req.url() != null ? req.url() : existing.url();
        AuthMode mode = req.authMode() != null
                ? AuthMode.valueOf(req.authMode().trim().toUpperCase()) : existing.auth().mode();
        String token = req.token() != null ? req.token() : existing.auth().token();
        String username = req.username() != null ? req.username() : existing.auth().username();
        String password = req.password() != null ? req.password() : existing.auth().password();
        BackendConfig.Auth auth = new BackendConfig.Auth(mode, token, username, password);
        return new BackendConfig(existing.name(), url, existing.protocol(), auth,
                req.connectTimeoutMs() != null ? req.connectTimeoutMs() : existing.connectTimeoutMs(),
                req.callTimeoutMs() != null ? req.callTimeoutMs() : existing.callTimeoutMs(),
                req.maxConcurrentTasks() != null ? req.maxConcurrentTasks() : existing.maxConcurrentTasks(),
                existing.source());
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BackendConflictException(field + " 不可为空", "missing_" + field);
        }
        return value.trim();
    }
}
