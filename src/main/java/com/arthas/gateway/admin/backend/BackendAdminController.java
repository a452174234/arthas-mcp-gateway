package com.arthas.gateway.admin.backend;

import com.arthas.gateway.admin.backend.dto.BackendDto;
import com.arthas.gateway.admin.backend.dto.CreateBackendRequest;
import com.arthas.gateway.admin.backend.dto.UpdateBackendRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 004 后端配置 CRUD REST 端点（admin-api-contract §1，A 能力）。
 *
 * <p>{@code /admin/backends}：GET 列表/详情、POST 增、PUT 改、DELETE 删。
 * {@code @ConditionalOnProperty(admin.crud.enabled, matchIfMissing=true)}：默认开；
 * 关闭时本 Controller 不注册 → 端点 404、前端降级（admin-invariants INV-SWITCH-1）。
 * 与诊断面 {@code /mcp} 隔离（admin-invariants INV-ISOL-1）。
 */
@RestController
@RequestMapping("/admin/backends")
@ConditionalOnProperty(name = "arthas-gateway.admin.crud.enabled", havingValue = "true", matchIfMissing = true)
public class BackendAdminController {

    private final BackendAdminService service;

    public BackendAdminController(BackendAdminService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<BackendDto> backends = service.list();
        long healthy = backends.stream().filter(BackendDto::healthy).count();
        return Map.of(
                "backends", backends,
                "summary", Map.of(
                        "total", backends.size(),
                        "healthy", healthy,
                        "unhealthy", backends.size() - healthy));
    }

    @GetMapping("/{name}")
    public BackendDto get(@PathVariable String name) {
        return service.get(name);
    }

    @PostMapping
    public ResponseEntity<BackendDto> create(@RequestBody CreateBackendRequest req) {
        return ResponseEntity.status(201).body(service.create(req));
    }

    @PutMapping("/{name}")
    public BackendDto update(@PathVariable String name, @RequestBody UpdateBackendRequest req) {
        return service.update(name, req);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        service.delete(name);
        return ResponseEntity.noContent().build();
    }
}
