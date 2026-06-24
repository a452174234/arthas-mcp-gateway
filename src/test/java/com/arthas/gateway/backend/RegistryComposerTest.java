package com.arthas.gateway.backend;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T017 {@code RegistryComposer.compose} 纯逻辑测试（surefire 波次 A，无 K8S）。
 *
 * <p>断言 contracts/dynamic-registration-invariants.md §3/§5：
 * <ul>
 *   <li><b>D-COEXIST-1（I-2 关键）</b>：静态热重载移除某静态 target，但动态 target 仍在 effective
 *       （热重载不误删动态 target）。</li>
 *   <li><b>D-REG-1</b>：compose(static ∪ dynamic) 后 effective 含动态 target。</li>
 *   <li><b>D-ATOMIC-1（I-1）</b>：unchanged target 的 {@link BackendEntry} 实例跨 compose 不变
 *       （in-flight 调用持有的引用稳定，不串台）。</li>
 *   <li><b>I-6 复用减少重连</b>：同 name 同 config 的 Entry 复用旧实例（保连接池）。</li>
 *   <li><b>D-VERSION-1（I-7）</b>：无实际变更的 compose → {@code changed=false}、registry 维持原实例
 *       （调用方据此跳过 getAndSet，version 去重）；有变更 → version 单调递增。</li>
 *   <li><b>I-3 守护</b>：动态与静态同名（异 source）经 store 拒绝在先，compose 收到的 static/dynamic
 *       名字不相交；本测试用不相交名验证合并正确。</li>
 * </ul>
 *
 * <p>{@code compose} 为纯函数（输入 previous effective + static registry + dynamic cfgs → effective + toRetire）；
 * 「静态热重载移除 A、动态 D 仍在」即以 staticReg 不含 A、dynamic 含 D 调用 compose 验证。
 */
class RegistryComposerTest {

    private final BackendEntryFactory factory = new BackendEntryFactory();
    private final RegistryComposer composer = new RegistryComposer(factory);

    private static BackendConfig stat(String name, String url) {
        return new BackendConfig(name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, Source.STATIC);
    }

    private static BackendConfig dyn(String name, String url) {
        return new BackendConfig(name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, Source.DYNAMIC);
    }

    /** 由若干 cfg 经 factory 构造 Entry，组装 registry（version 仅为标识，compose 用自身计数器）。 */
    private BackendRegistry registry(long version, BackendConfig... cfgs) {
        Map<String, BackendEntry> byName = new LinkedHashMap<>();
        for (BackendConfig c : cfgs) {
            byName.put(c.name(), factory.create(c));
        }
        return new BackendRegistry(version, byName);
    }

    private static Collection<BackendConfig> dynList(BackendConfig... cfgs) {
        return List.of(cfgs);
    }

    // ===== D-REG-1：compose(static ∪ dynamic) 后 effective 含动态 target =====

    @Test
    void composeMergesStaticAndDynamic() {
        BackendRegistry previous = registry(1L); // 空
        BackendRegistry staticReg = registry(10L, stat("order", "http://1.1.1.1:8563"));
        RegistryComposer.ComposeResult r = composer.compose(previous, staticReg,
                dynList(dyn("srv-pod", "http://10.0.0.5:31234")));

        assertThat(r.changed()).isTrue();
        assertThat(r.registry().names()).containsExactlyInAnyOrder("order", "srv-pod");
        assertThat(r.toRetire()).isEmpty();
    }

    // ===== D-COEXIST-1（I-2 关键）：静态移除 A、动态 D 仍在 effective =====

    @Test
    void staticReloadRemovingTargetKeepsDynamicTarget() {
        // previous effective = 静态 A + 动态 D
        BackendRegistry previous = registry(1L, stat("alpha", "http://1.1.1.1:8563"),
                dyn("srv-pod", "http://10.0.0.5:31234"));
        // 静态热重载移除 alpha（staticReg 不含 alpha），动态 D 不受 YAML 影响（仍由 store 提供）
        BackendRegistry staticReg = registry(11L); // 空 static
        RegistryComposer.ComposeResult r = composer.compose(previous, staticReg,
                dynList(dyn("srv-pod", "http://10.0.0.5:31234")));

        assertThat(r.registry().names()).as("动态 D 仍在 effective（热重载不误删）").containsExactly("srv-pod");
        assertThat(r.toRetire()).as("被移除的静态 alpha 进下线").hasSize(1);
        assertThat(r.toRetire().get(0).config().name()).isEqualTo("alpha");
    }

    // ===== D-ATOMIC-1（I-1）/ I-6：unchanged target 的 Entry 实例跨 compose 不变（复用旧实例） =====

    @Test
    void unchangedEntriesAreReusedByInstancePreservingConnections() {
        BackendConfig staticCfg = stat("order", "http://1.1.1.1:8563");
        BackendConfig dynCfg = dyn("srv-pod", "http://10.0.0.5:31234");
        BackendEntry staticEntry = factory.create(staticCfg);
        BackendEntry dynEntry = factory.create(dynCfg);
        Map<String, BackendEntry> prevByName = new LinkedHashMap<>();
        prevByName.put("order", staticEntry);
        prevByName.put("srv-pod", dynEntry);
        BackendRegistry previous = new BackendRegistry(1L, prevByName);

        BackendRegistry staticReg = new BackendRegistry(11L, Map.of("order", staticEntry));
        RegistryComposer.ComposeResult r = composer.compose(previous, staticReg, dynList(dynCfg));

        assertThat(r.registry().get("order")).as("静态 Entry 复用旧实例（保连接）").containsSame(staticEntry);
        assertThat(r.registry().get("srv-pod")).as("动态 Entry 复用旧实例（保连接）").containsSame(dynEntry);
        assertThat(r.toRetire()).isEmpty();
    }

    // ===== D-VERSION-1（I-7）：无变更 → changed=false、registry 维持原实例、跳过 swap =====

    @Test
    void noChangeComposeSkipsSwapAndKeepsPreviousInstance() {
        BackendConfig staticCfg = stat("order", "http://1.1.1.1:8563");
        BackendConfig dynCfg = dyn("srv-pod", "http://10.0.0.5:31234");
        BackendEntry staticEntry = factory.create(staticCfg);
        BackendRegistry staticReg = new BackendRegistry(11L, Map.of("order", staticEntry));
        // 首次 compose 建立含 order + srv-pod 的 effective
        BackendRegistry effective = composer.compose(registry(1L), staticReg, dynList(dynCfg)).registry();

        // 再次 compose：static 与 dynamic 均未变 → 应复用、changed=false、registry 维持原实例
        RegistryComposer.ComposeResult r = composer.compose(effective, staticReg, dynList(dynCfg));
        assertThat(r.changed()).as("无变更 → changed=false（version 去重）").isFalse();
        assertThat(r.registry()).as("无变更 → 维持原 effective 实例（调用方跳过 getAndSet）").isSameAs(effective);
        assertThat(r.toRetire()).isEmpty();
    }

    // ===== I-7：有变更 → version 单调递增 =====

    @Test
    void changedComposesProduceMonotonicallyIncreasingVersions() {
        BackendRegistry staticReg = registry(11L, stat("order", "http://1.1.1.1:8563"));
        long v1 = composer.compose(registry(1L), staticReg, dynList()).registry().version();
        long v2 = composer.compose(registry(1L), staticReg,
                dynList(dyn("srv-pod", "http://10.0.0.5:31234"))).registry().version();
        long v3 = composer.compose(registry(1L), staticReg,
                dynList(dyn("srv-pod", "http://10.0.0.5:31234"),
                        dyn("srv-pod2", "http://10.0.0.5:31235"))).registry().version();
        assertThat(v2).as("每次变更 version 递增").isGreaterThan(v1);
        assertThat(v3).isGreaterThan(v2);
    }

    // ===== I-3 守护：静态/动态同名已由 store 拒绝在先，compose 收到不相交名 → 合并无冲突 =====

    @Test
    void disjointStaticAndDynamicNamesMergeWithoutConflict() {
        BackendRegistry staticReg = registry(11L, stat("order", "http://1.1.1.1:8563"));
        RegistryComposer.ComposeResult r = composer.compose(registry(1L), staticReg,
                dynList(dyn("srv-pod", "http://10.0.0.5:31234")));
        assertThat(r.registry().size()).isEqualTo(2);
    }
}
