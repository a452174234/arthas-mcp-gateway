package com.arthas.gateway.obs;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.BackendState;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T050 后端注册表健康指标单测（surefire，纯逻辑，零桩零 arthas）。
 *
 * <p>{@link BackendRegistryHealthIndicator} 将注册表 + 熔断状态映射到 {@code /actuator/health} 的 details，
 * 运维无需读源码即可见各后端健康（宪法原则五；T050「唯一允许 Actuator 端点直接验证」）。
 *
 * <p><b>状态语义</b>（与 {@code list-targets} 一致、非桩驱动）：
 * <ul>
 *   <li>网关进程 status 恒为 <b>UP</b>——故障隔离：单后端熔断是正常韧性表现，<b>不</b>拉低网关 status。</li>
 *   <li>单后端 {@code healthy = state==ACTIVE && breaker 未 OPEN}（真实 {@code recordFailure×3} 驱动 OPEN，非桩）。</li>
 *   <li>{@code details.backends[name] = {state, healthy, protocol, breaker}}；{@code details.summary = {total, healthy, unhealthy}}。</li>
 * </ul>
 */
class BackendRegistryHealthIndicatorTest {

    private final BackendEntryFactory factory = new BackendEntryFactory();

    @Test
    void health_up_withPerBackendDetails_andTrippedBreakerMarkedUnhealthy() {
        BackendEntry order = factory.create(cfg("order"));
        BackendEntry payment = factory.create(cfg("payment"));
        // order 连续 3 次基础设施失败 → 真实驱动熔断 OPEN（驱动真实状态机，非桩）
        order.breaker().recordFailure();
        order.breaker().recordFailure();
        order.breaker().recordFailure();

        Map<String, BackendEntry> byName = new LinkedHashMap<>();
        byName.put("order", order);
        byName.put("payment", payment);
        BackendRegistry reg = new BackendRegistry(1L, byName);
        BackendRegistryHealthIndicator indicator = new BackendRegistryHealthIndicator(new RegistryHolder(reg));

        Health health = indicator.health();

        // 网关进程健康（故障隔离：单后端 sick ≠ 网关 DOWN）
        assertThat(health.getStatus()).as("网关 status 恒 UP").isEqualTo(Status.UP);

        @SuppressWarnings("unchecked")
        Map<String, Object> backends = (Map<String, Object>) health.getDetails().get("backends");
        assertThat(backends).containsOnlyKeys("order", "payment");

        @SuppressWarnings("unchecked")
        Map<String, Object> orderDetail = (Map<String, Object>) backends.get("order");
        assertThat(orderDetail).containsEntry("healthy", false);
        assertThat(orderDetail).containsEntry("breaker", "OPEN");
        assertThat(orderDetail).containsEntry("state", BackendState.ACTIVE.name());

        @SuppressWarnings("unchecked")
        Map<String, Object> paymentDetail = (Map<String, Object>) backends.get("payment");
        assertThat(paymentDetail).containsEntry("healthy", true);
        assertThat(paymentDetail).containsEntry("breaker", "CLOSED");

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) health.getDetails().get("summary");
        assertThat(summary)
                .containsEntry("total", 2)
                .containsEntry("healthy", 1)
                .containsEntry("unhealthy", 1);
    }

    @Test
    void retiredBackendMarkedUnhealthy() {
        BackendEntry order = factory.create(cfg("order"));
        order.markRetired(); // 热重载移除中：healthy=false（即使 breaker 仍 CLOSED）

        BackendRegistry reg = new BackendRegistry(2L, Map.of("order", order));
        Health health = new BackendRegistryHealthIndicator(new RegistryHolder(reg)).health();

        @SuppressWarnings("unchecked")
        Map<String, Object> orderDetail = (Map<String, Object>)
                ((Map<String, Object>) health.getDetails().get("backends")).get("order");
        assertThat(orderDetail).containsEntry("healthy", false);
        assertThat(orderDetail).containsEntry("state", BackendState.RETIRED.name());
    }

    @Test
    void health_up_emptyRegistry_whenNoBackends() {
        Health health = new BackendRegistryHealthIndicator(new RegistryHolder()).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        @SuppressWarnings("unchecked")
        Map<String, Object> backends = (Map<String, Object>) health.getDetails().get("backends");
        assertThat(backends).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) health.getDetails().get("summary");
        assertThat(summary)
                .containsEntry("total", 0)
                .containsEntry("healthy", 0)
                .containsEntry("unhealthy", 0);
    }

    private static BackendConfig cfg(String name) {
        return new BackendConfig(name, "http://127.0.0.1:9", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null), 5000, 30000, 5);
    }
}
