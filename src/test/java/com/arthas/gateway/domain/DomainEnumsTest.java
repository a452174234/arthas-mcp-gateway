package com.arthas.gateway.domain;

import com.arthas.gateway.backend.BackendState;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.tool.RoutingMode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 领域枚举契约测试（T011）。
 * <p>
 * 锁定三个领域叶子枚举的取值集合（宪法原则二：证据驱动——取值源自 data-model.md §2/§3/§9）：
 * <ul>
 *   <li>{@link Protocol}：后端协议（STREAMABLE / STATELESS）</li>
 *   <li>{@link RoutingMode}：工具路由模式（同步直发 / 流式聚合 / 异步任务 / 网关本地）</li>
 *   <li>{@link BackendState}：后端运行态（在册 / 退役中）</li>
 * </ul>
 * 用名字字符串断言而非枚举常量自身，避免「用枚举列举自己」的循环，构成真正的契约钉子。
 */
class DomainEnumsTest {

    @Test
    void protocol_exposesExactlyStreamableAndStateless() {
        assertThat(Arrays.stream(Protocol.values()).map(Enum::name))
                .containsExactlyInAnyOrder("STREAMABLE", "STATELESS");
    }

    @Test
    void routingMode_exposesExactlyFourStrategies() {
        // SYNC_DIRECT(26 即时) / STREAM_AGGREGATE(dashboard) / ASYNC_TASK(5 optional) / GATEWAY_LOCAL(4 自有工具)
        assertThat(Arrays.stream(RoutingMode.values()).map(Enum::name))
                .containsExactlyInAnyOrder(
                        "SYNC_DIRECT", "STREAM_AGGREGATE", "ASYNC_TASK", "GATEWAY_LOCAL");
    }

    @Test
    void backendState_exposesExactlyActiveAndRetired() {
        // ACTIVE=在册；RETIRED=热重载移除中（in-flight 调用可完成，见 data-model.md §3）
        assertThat(Arrays.stream(BackendState.values()).map(Enum::name))
                .containsExactlyInAnyOrder("ACTIVE", "RETIRED");
    }
}
