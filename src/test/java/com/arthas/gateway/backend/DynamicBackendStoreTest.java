package com.arthas.gateway.backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T015 {@code DynamicBackendStore} 测试（surefire 波次 A，纯逻辑，无 K8S）。
 *
 * <p>断言 contracts/dynamic-registration-invariants.md §5（动态注册层）：
 * <ul>
 *   <li>D-REG-1：{@code register(DYNAMIC cfg)} 后 store 含该 target（{@code onChange} 触发一次）。</li>
 *   <li>D-REG-2：{@code register} 与静态种子同名 → 抛 {@link BackendConfigException}（I-3 拒绝，保护静态）。</li>
 *   <li>D-REG-3：同名同 URL 二次 {@code register} → 幂等（无异常、target 仍在、无重复变更回调）。</li>
 *   <li>D-REG-4：同名<b>异</b> URL {@code register} → 抛 {@link BackendConfigException}（I-3）。</li>
 *   <li>D-SOURCE-1（动态注册强制 DYNAMIC）：{@code register} 非 DYNAMIC cfg → 抛。</li>
 *   <li>D-UNREG-1：{@code unregister(动态)} 后 store 不含、{@code onChange} 触发。</li>
 *   <li>D-UNREG-2：{@code unregister} 静态名（store 不持有）→ 无操作、无变更回调。</li>
 *   <li>D-UNREG-3：{@code unregister} 不存在 → 幂等无操作。</li>
 * </ul>
 *
 * <p>注：D-REG-1 的"RegistryHolder.current() 含该 target"由 {@code RegistryComposerTest}（T017）覆盖——
 * 那里 wiring store+composer+holder 验证完整 register→compose→swap 链；本测试聚焦 store 自身不变量。
 */
class DynamicBackendStoreTest {

    private final Set<String> staticSeeds = new HashSet<>(Set.of("static-seed"));
    private int changeCount;
    private DynamicBackendStore store;

    @BeforeEach
    void setUp() {
        changeCount = 0;
        store = new DynamicBackendStore(() -> Set.copyOf(staticSeeds), () -> changeCount++);
    }

    private static BackendConfig dyn(String name, String url) {
        return new BackendConfig(name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, Source.DYNAMIC);
    }

    private static BackendConfig stat(String name, String url) {
        return new BackendConfig(name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, Source.STATIC);
    }

    // ===== D-REG-1：register(DYNAMIC) 后 store 含该 target；onChange 触发一次 =====

    @Test
    void registerDynamicTargetAddsAndNotifies() {
        assertThat(store.list()).isEmpty();
        store.register(dyn("srv-pod", "http://10.0.0.5:31234"));
        assertThat(store.get("srv-pod")).map(BackendConfig::name).contains("srv-pod");
        assertThat(store.list()).hasSize(1);
        assertThat(changeCount).as("register 触发一次 compose 通知").isEqualTo(1);
    }

    // ===== D-SOURCE-1（动态注册强制 DYNAMIC）：register 非 DYNAMIC → 抛 =====

    @Test
    void registerRejectsNonDynamicSource() {
        assertThatThrownBy(() -> store.register(stat("srv-pod", "http://10.0.0.5:31234")))
                .isInstanceOf(BackendConfigException.class)
                .hasMessageContaining("DYNAMIC");
        assertThat(store.list()).as("拒绝的 cfg 不入 store").isEmpty();
        assertThat(changeCount).as("拒绝时不触发变更通知").isZero();
    }

    // ===== D-REG-2：与静态种子同名 → 拒绝（I-3 保护静态） =====

    @Test
    void registerCollidingWithStaticSeedNameRejected() {
        assertThatThrownBy(() -> store.register(dyn("static-seed", "http://10.0.0.5:31234")))
                .isInstanceOf(BackendConfigException.class)
                .hasMessageContaining("static-seed");
        assertThat(store.get("static-seed")).isEmpty();
        assertThat(changeCount).isZero();
    }

    // ===== D-REG-3：同名同 URL 二次 register → 幂等（无异常、仍在、无重复回调） =====

    @Test
    void registerSameNameSameUrlIsIdempotent() {
        store.register(dyn("srv-pod", "http://10.0.0.5:31234"));
        int afterFirst = changeCount;
        // 二次注册（同 name 同 url）—— 不抛、target 仍在、不再重复触发变更回调
        store.register(dyn("srv-pod", "http://10.0.0.5:31234"));
        assertThat(store.list()).hasSize(1);
        assertThat(store.get("srv-pod")).isPresent();
        assertThat(changeCount).as("幂等再注册不重复触发 compose").isEqualTo(afterFirst);
    }

    // ===== D-REG-4：同名异 URL → 拒绝（I-3） =====

    @Test
    void registerSameNameDifferentUrlRejected() {
        store.register(dyn("srv-pod", "http://10.0.0.5:31234"));
        int before = changeCount;
        assertThatThrownBy(() -> store.register(dyn("srv-pod", "http://10.0.0.6:31235")))
                .isInstanceOf(BackendConfigException.class);
        assertThat(store.get("srv-pod")).map(BackendConfig::url).contains("http://10.0.0.5:31234");
        assertThat(changeCount).as("拒绝异 URL 时不触发变更通知").isEqualTo(before);
    }

    // ===== D-UNREG-1：unregister(动态) 后 store 不含、onChange 触发 =====

    @Test
    void unregisterDynamicTargetRemovesAndNotifies() {
        store.register(dyn("srv-pod", "http://10.0.0.5:31234"));
        int before = changeCount;
        store.unregister("srv-pod");
        assertThat(store.get("srv-pod")).isEqualTo(Optional.empty());
        assertThat(changeCount).as("unregister 触发一次 compose 通知").isEqualTo(before + 1);
    }

    // ===== D-UNREG-2：unregister 静态名（store 不持有）→ 无操作、不触发回调 =====

    @Test
    void unregisterStaticSeedNameIsNoOp() {
        store.register(dyn("srv-pod", "http://10.0.0.5:31234"));
        int before = changeCount;
        // static-seed 不在动态 store（静态只经热重载）→ 无操作
        store.unregister("static-seed");
        assertThat(store.list()).as("动态 target 不受影响").hasSize(1);
        assertThat(changeCount).as("无实际变更不触发回调").isEqualTo(before);
    }

    // ===== D-UNREG-3：unregister 不存在 → 幂等无操作 =====

    @Test
    void unregisterNonExistentIsIdempotent() {
        int before = changeCount;
        store.unregister("ghost");
        assertThat(store.list()).isEmpty();
        assertThat(changeCount).isEqualTo(before);
    }
}
