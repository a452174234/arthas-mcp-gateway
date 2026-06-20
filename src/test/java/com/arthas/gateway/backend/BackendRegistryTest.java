package com.arthas.gateway.backend;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BackendRegistry + RegistryHolder 单测（T022，data-model.md §4 / §11 规则 8）。
 *
 * <p>纯逻辑单测（surefire，无真实后端）。覆盖：
 * <ul>
 *   <li>{@link BackendRegistry}：不可变快照（{@code version} + {@code byName}），{@code get}/{@code names}，
 *       对入参 Map 做<b>防御性拷贝</b>（构造后篡改原 Map 不影响 registry）。</li>
 *   <li>{@link RegistryHolder}：{@code AtomicReference} 持有，{@code getAndSet} 原子替换且返回旧快照
 *       （热重载整体替换、in-flight 持旧 Entry 可完成，data-model.md §3 不变量）。</li>
 * </ul>
 *
 * <p><b>零桩约束</b>：{@code NOOP_CLIENT} 为<b>测试缝</b>（占位 client 字段，单测从不调用其 {@code callTool}，
 * 抛 {@link UnsupportedOperationException} 明示「非 arthas 响应替身」）；真实多目标路由隔离（S-CALL-1）由
 * {@code ToolsCallRoutingContractTest}（T019，真实多后端）承担，非本单测职责。
 */
class BackendRegistryTest {

    /** 测试缝：占位 client，注册表单测从不调用其响应路径（非 arthas 替身）。 */
    private static final BackendClient NOOP_CLIENT = new BackendClient() {
        @Override
        public void initialize() {
            // 占位
        }

        @Override
        public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
            throw new UnsupportedOperationException("注册表单测不调用 client（测试缝，非 arthas 响应替身）");
        }

        @Override
        public boolean isInitialized() {
            return false;
        }

        @Override
        public void close() {
            // 占位
        }
    };

    private static BackendConfig config(String name) {
        return new BackendConfig(
                name,
                "http://host:8563/mcp",
                Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000,
                30000,
                5);
    }

    private static BackendEntry entry(String name) {
        return new BackendEntry(config(name), NOOP_CLIENT, CircuitBreaker.create(() -> 0L));
    }

    // ---------------- BackendRegistry ----------------

    @Test
    void emptyRegistryHasVersionZeroAndNoEntries() {
        BackendRegistry r = BackendRegistry.empty();
        assertThat(r.version()).isZero();
        assertThat(r.size()).isZero();
        assertThat(r.names()).isEmpty();
        assertThat(r.get("any")).isEmpty();
    }

    @Test
    void registryHoldsEntriesByNameWithVersion() {
        BackendEntry order = entry("order-service");
        BackendEntry payment = entry("payment");
        BackendRegistry r = new BackendRegistry(7L, Map.of("order-service", order, "payment", payment));

        assertThat(r.version()).isEqualTo(7L);
        assertThat(r.size()).isEqualTo(2);
        assertThat(r.names()).containsExactlyInAnyOrder("order-service", "payment");
        assertThat(r.get("order-service")).contains(order);
        assertThat(r.get("payment")).contains(payment);
        assertThat(r.get("missing")).isEmpty();
    }

    @Test
    void registryDefensivelyCopiesBackingMap() {
        // 不可变快照不变量：构造后篡改原 Map 不得泄漏进 registry（data-model.md §4 不可变）
        Map<String, BackendEntry> mutable = new HashMap<>();
        mutable.put("order-service", entry("order-service"));
        BackendRegistry r = new BackendRegistry(1L, mutable);

        mutable.put("rogue", entry("rogue")); // 构造后向原 Map 塞入 rogue
        mutable.remove("order-service");      // 构造后从原 Map 删 order-service

        assertThat(r.size()).as("防御性拷贝：原 Map 篡改不影响 registry").isEqualTo(1);
        assertThat(r.get("order-service")).as("原 Map 删除不反映到 registry").isPresent();
        assertThat(r.get("rogue")).as("原 Map 新增不泄漏进 registry").isEmpty();
    }

    // ---------------- RegistryHolder ----------------

    @Test
    void holderDefaultsToEmptyRegistry() {
        RegistryHolder h = new RegistryHolder();
        assertThat(h.current().version()).isZero();
        assertThat(h.current().size()).isZero();
    }

    @Test
    void holderInitiallyHoldsProvidedRegistry() {
        BackendRegistry r = new BackendRegistry(1L, Map.of("order-service", entry("order-service")));
        RegistryHolder h = new RegistryHolder(r);
        assertThat(h.current()).isSameAs(r);
        assertThat(h.get("order-service")).isPresent();
    }

    @Test
    void getAndSetAtomicallySwapsAndReturnsPrevious() {
        // 热重载整体替换：getAndSet 返回旧快照（供异步优雅下线），current() 反映新快照
        BackendEntry orderV1 = entry("order-service");
        BackendRegistry v1 = new BackendRegistry(1L, Map.of("order-service", orderV1));
        BackendRegistry v2 = new BackendRegistry(
                2L, Map.of("order-service", entry("order-service"), "payment", entry("payment")));
        RegistryHolder h = new RegistryHolder(v1);

        BackendRegistry previous = h.getAndSet(v2);
        assertThat(previous).as("getAndSet 返回旧 registry").isSameAs(v1);
        assertThat(h.current()).as("current 反映新 registry").isSameAs(v2);
        assertThat(h.current().version()).isEqualTo(2L);
        assertThat(h.current().size()).isEqualTo(2);
        assertThat(h.get("payment")).as("便捷 get 委派新 registry").isPresent();
    }

    @Test
    void getAndSetRejectsNullToFailFast() {
        RegistryHolder h = new RegistryHolder();
        assertThatThrownBy(() -> h.getAndSet(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("registry");
    }
}
