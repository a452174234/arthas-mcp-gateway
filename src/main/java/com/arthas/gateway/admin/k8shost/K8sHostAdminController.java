package com.arthas.gateway.admin.k8shost;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * portal K8S host 管理 REST 端点（006 波3，T026，仿 {@code BackendAdminController}）。
 *
 * <p>{@code /admin/k8s-hosts} CRUD → {@link K8sHostAdminService} 写 {@code config/k8s-hosts.yaml} →
 * {@code K8sHostsWatcher} 热重载（INV-PORTAL-K8S-1）。{@link K8sHostDto} 脱敏（INV-PORTAL-K8S-2）。
 * 能力开关 {@code arthas-gateway.admin.k8s-hosts.enabled}（默认开；关则端点 404，INV-PORTAL-K8S-5）。
 *
 * <p>与诊断面 {@code /mcp}、与 {@code /admin/backends} 隔离（INV-ISOL-1 延伸）。
 */
@RestController
@RequestMapping("/admin/k8s-hosts")
@ConditionalOnProperty(name = "arthas-gateway.admin.k8s-hosts.enabled", havingValue = "true", matchIfMissing = true)
public class K8sHostAdminController {

    private final K8sHostAdminService service;

    public K8sHostAdminController(K8sHostAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<K8sHostDto> list() throws IOException {
        return service.list();
    }

    @GetMapping("/{name}")
    public K8sHostDto get(@PathVariable String name) throws IOException {
        return service.list().stream()
                .filter(d -> d.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("K8S host 不存在：" + name));
    }

    @PostMapping
    public K8sHostDto create(@RequestBody CreateK8sHostRequest req) throws IOException {
        return service.create(req);
    }

    @DeleteMapping("/{name}")
    public void delete(@PathVariable String name) throws IOException {
        service.delete(name);
    }
}
