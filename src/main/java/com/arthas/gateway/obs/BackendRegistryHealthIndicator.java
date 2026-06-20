package com.arthas.gateway.obs;

import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.BackendState;
import com.arthas.gateway.backend.CircuitBreaker;
import com.arthas.gateway.backend.RegistryHolder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * T050 后端注册表健康指标——将注册表 + 熔断状态映射到 {@code /actuator/health} 的 details
 * （宪法原则五；运维无需读源码即可见各后端健康；T050「唯一允许 Actuator 端点直接验证」）。
 *
 * <p><b>状态语义</b>（与 {@code list-targets} 一致，不主动探测后端——避免对外发慢调用）：
 * <ul>
 *   <li>网关进程 status 恒为 <b>UP</b>——故障隔离：单后端熔断是正常韧性表现，<b>不</b>拉低网关 status
 *       （熔断 OPEN 时网关仍能对其他 target 服务、对失效 target 返结构化错误）。</li>
 *   <li>单后端 {@code healthy = state==ACTIVE && breaker 未 OPEN}（同 {@code GatewayToolHandlers#listTargets}）。</li>
 *   <li>{@code details.backends[name] = {state, healthy, protocol, breaker}}；
 *       {@code details.summary = {total, healthy, unhealthy}}。</li>
 * </ul>
 *
 * <p>Spring Boot 4.x：health API 迁至 {@code org.springframework.boot.health.contributor}（自 {@code actuate.health}），
 * 经 {@code spring-boot-starter-actuator} → {@code spring-boot-health} 传递可用。
 */
@Component
public class BackendRegistryHealthIndicator implements HealthIndicator {

    private final RegistryHolder registry;

    public BackendRegistryHealthIndicator(RegistryHolder registry) {
        this.registry = Objects.requireNonNull(registry, "registry 不可为空");
    }

    @Override
    public Health health() {
        BackendRegistry reg = registry.current();
        Map<String, Object> backends = new LinkedHashMap<>();
        int healthy = 0;
        int unhealthy = 0;
        for (BackendEntry e : reg.byName().values()) {
            boolean isHealthy = e.state() == BackendState.ACTIVE
                    && e.breaker().state() != CircuitBreaker.State.OPEN;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("state", e.state().name());
            detail.put("healthy", isHealthy);
            detail.put("protocol", e.config().protocol().name());
            detail.put("breaker", e.breaker().state().name());
            backends.put(e.config().name(), detail);
            if (isHealthy) {
                healthy++;
            } else {
                unhealthy++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", reg.size());
        summary.put("healthy", healthy);
        summary.put("unhealthy", unhealthy);
        return Health.up()
                .withDetail("backends", backends)
                .withDetail("summary", summary)
                .build();
    }
}
