package com.arthas.gateway.backend;

import com.arthas.gateway.backend.BackendConfigLoader.LoadedBackends;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T039 {@link BackendRegistryReloader} 纯逻辑单测（surefire，无 WatchService / 无线程 / 无真实后端）。
 *
 * <p>覆盖热重载 diff 核心（data-model.md §11 规则 7/8、§3「热重载并发不串台」）：
 * <ul>
 *   <li>add：新后端进新表，无下线。</li>
 *   <li>remove：旧后端进下线列表。</li>
 *   <li>unchanged 复用：同 name 同 config → 复用<b>同一</b> Entry 实例（保连接池/session，免重连）。</li>
 *   <li>config 变更：同 name 不同 url → 新建 Entry，旧 Entry 进下线。</li>
 *   <li>version 去重：{@code next.version==current.version} → 不重建（changed=false）。</li>
 * </ul>
 * 真实文件监听（WatchService）+ 端到端由 {@code HotReloadIT}（failsafe）覆盖。
 */
class BackendRegistryReloaderTest {

    private final BackendEntryFactory factory = new BackendEntryFactory();
    private final BackendRegistryReloader reloader = new BackendRegistryReloader(factory);

    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }

    private static LoadedBackends load(long version, BackendConfig... backends) {
        return new LoadedBackends(version, List.of(backends));
    }

    private static BackendRegistry registry(long version, Map<String, BackendEntry> byName) {
        return new BackendRegistry(version, byName);
    }

    // ===== add：新增后端进新表，无下线 =====

    @Test
    void reload_addsNewBackend() {
        BackendEntry order = factory.create(noneAuth("order", "http://h:1"));
        BackendRegistry current = registry(1L, Map.of("order", order));

        BackendRegistryReloader.ReloadResult r = reloader.reload(current,
                load(2L, noneAuth("order", "http://h:1"), noneAuth("payment", "http://h:2")));

        assertThat(r.changed()).isTrue();
        assertThat(r.registry().version()).isEqualTo(2L);
        assertThat(r.registry().names()).containsExactlyInAnyOrder("order", "payment");
        assertThat(r.registry().get("order").orElseThrow())
                .as("order unchanged → 复用同一实例").isSameAs(order);
        assertThat(r.toRetire()).as("无下线").isEmpty();
    }

    // ===== remove：旧后端进下线列表 =====

    @Test
    void reload_removesRetiredBackend() {
        BackendEntry order = factory.create(noneAuth("order", "http://h:1"));
        BackendEntry payment = factory.create(noneAuth("payment", "http://h:2"));
        BackendRegistry current = registry(1L, Map.of("order", order, "payment", payment));

        BackendRegistryReloader.ReloadResult r = reloader.reload(current,
                load(2L, noneAuth("order", "http://h:1")));

        assertThat(r.changed()).isTrue();
        assertThat(r.registry().names()).containsExactly("order");
        assertThat(r.toRetire()).as("payment 下线").containsExactly(payment);
        assertThat(r.registry().get("order").orElseThrow()).isSameAs(order);
    }

    // ===== unchanged 复用：同 name 同 config → 复用同一 Entry（保连接） =====

    @Test
    void reload_reusesUnchangedEntryBySameConfig() {
        BackendEntry order = factory.create(noneAuth("order", "http://h:1"));
        BackendRegistry current = registry(1L, Map.of("order", order));

        BackendRegistryReloader.ReloadResult r = reloader.reload(current,
                load(2L, noneAuth("order", "http://h:1")));

        assertThat(r.changed()).as("version 变 → changed").isTrue();
        assertThat(r.registry().get("order").orElseThrow())
                .as("复用同一 Entry 实例（连接池/session 保留）").isSameAs(order);
        assertThat(r.toRetire()).isEmpty();
    }

    // ===== config 变更：同 name 不同 url → 新建 Entry，旧 Entry 下线 =====

    @Test
    void reload_replacesEntryOnConfigChange() {
        BackendEntry orderOld = factory.create(noneAuth("order", "http://h:1"));
        BackendRegistry current = registry(1L, Map.of("order", orderOld));

        BackendRegistryReloader.ReloadResult r = reloader.reload(current,
                load(2L, noneAuth("order", "http://h:999"))); // url 变

        assertThat(r.changed()).isTrue();
        BackendEntry orderNew = r.registry().get("order").orElseThrow();
        assertThat(orderNew).as("config 变更 → 新建 Entry").isNotSameAs(orderOld);
        assertThat(orderNew.config().url()).isEqualTo("http://h:999");
        assertThat(r.toRetire()).as("旧 Entry 下线").containsExactly(orderOld);
    }

    // ===== version 去重：相同 version → 不重建 =====

    @Test
    void reload_ignoresRepeatedVersion() {
        BackendEntry order = factory.create(noneAuth("order", "http://h:1"));
        BackendRegistry current = registry(5L, Map.of("order", order));

        // 即便内容不同，version 重复也忽略（去重优先）
        BackendRegistryReloader.ReloadResult r = reloader.reload(current,
                load(5L, noneAuth("payment", "http://h:2")));

        assertThat(r.changed()).as("version 重复 → changed=false").isFalse();
        assertThat(r.registry()).as("registry 维持原样").isSameAs(current);
        assertThat(r.toRetire()).isEmpty();
    }
}
