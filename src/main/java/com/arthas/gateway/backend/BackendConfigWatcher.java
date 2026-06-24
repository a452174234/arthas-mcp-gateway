package com.arthas.gateway.backend;

import com.arthas.gateway.backend.BackendConfigLoader.LoadedBackends;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 热重载文件监听器（SC-002，T039/T040）：{@link WatchService} 监听 {@code backends.yaml} 所在目录，
 * 防抖 500ms 后重载 → {@link BackendRegistryReloader} diff（unchanged 复用）→ {@link RegistryHolder#getAndSet}
 * 原子替换 → 旧 Entry {@code markRetired} + 异步 {@code close}（in-flight 可完成，data-model.md §3）。
 *
 * <h3>故障语义（§11 规则 7）</h3>
 * <p>YAML 解析/校验失败（{@link BackendConfigException}）或读取 IO 失败 → <b>保留旧注册表</b>、记 ERROR，
 * 不半替换（原子性）。version 重复（{@link BackendRegistryReloader}）→ 忽略。
 *
 * <h3>生命周期</h3>
 * <p>{@link AutoCloseable}：{@code close()} 关 WatchService + 中断监听虚拟线程。Spring 装配为 @Bean
 * （{@code destroyMethod=infer}）在容器关闭时自动调用。配置文件无父目录/父目录不存在 → 记 WARN 不监听
 * （启动期初值仍由 {@code BackendRegistryBootstrap} 加载生效）。
 */
public final class BackendConfigWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BackendConfigWatcher.class);
    /** 事件防抖静默期：最后一次文件变更后静默此时长才触发重载（避免编辑器多次写盘抖动）。 */
    private static final Duration DEBOUNCE = Duration.ofMillis(500);
    /** 监听线程 poll 间隔（兼作 close 响应粒度）。 */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    /** 容器关闭时等待退役关闭调度器的上限(让已到期任务执行完;未到期 grace 内的在关闭时不再阻塞)。 */
    private static final Duration SHUTDOWN_AWAIT = Duration.ofSeconds(2);

    private final Path configFile;
    private final BackendConfigLoader loader;
    private final BackendRegistryReloader reloader;
    private final RegistryHolder holder;
    private final RegistryComposer composer;
    /**
     * 动态 target store（经 {@link ObjectProvider} 懒解析，规避 watcher↔store 构造期循环依赖：
     * watcher 读 store.list() 合并 dynamic；store 的 onChange 通知 watcher 重算）。运行期（reload/recompose）
     * 解析时 store bean 已就绪。
     */
    private final ObjectProvider<DynamicBackendStore> dynamicStoreProvider;
    private final Duration retirementGrace;
    /**
     * 当前<b>静态</b> registry 快照（仅 static 来源；reloader 据此 diff）。启动期 = holder 初值（bootstrap
     * 加载的 static，此时无动态）。热重载更新此快照；compose 用其 Entry + 动态列表合并为 effective。
     */
    private volatile BackendRegistry staticSnapshot;
    /**
     * 退役关闭调度器(可追踪,002 整改 P2-1/FR-007):替代裸 {@code Thread.startVirtualThread(sleep)} 的
     * fire-and-forget 虚拟线程堆积。{@code close()} 时 {@code shutdown} + {@code awaitTermination} 优雅回收。
     */
    private final ScheduledExecutorService retireScheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    private WatchService watchService;
    private Thread watcherThread;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    /** 默认装配 + 显式退役宽限(002 整改 P2-1/FR-007:生产装配传 backendTimeout=11min)。 */
    public BackendConfigWatcher(Path configFile, BackendEntryFactory factory, RegistryHolder holder,
                                RegistryComposer composer, ObjectProvider<DynamicBackendStore> dynamicStoreProvider,
                                Duration retirementGrace) {
        this(configFile, new BackendConfigLoader(), new BackendRegistryReloader(factory), holder,
                composer, dynamicStoreProvider, retirementGrace);
    }

    /**
     * 全参构造（测试注入 loader/reloader/composer/store/grace）。
     *
     * <p>启动期 {@code staticSnapshot} 取 holder 当前值（bootstrap 加载的 static；此时无动态 target，
     * 故 holder 初值即纯 static）。
     */
    public BackendConfigWatcher(Path configFile, BackendConfigLoader loader, BackendRegistryReloader reloader,
                                RegistryHolder holder, RegistryComposer composer,
                                ObjectProvider<DynamicBackendStore> dynamicStoreProvider, Duration retirementGrace) {
        this.configFile = java.util.Objects.requireNonNull(configFile, "configFile 不可为空");
        this.loader = java.util.Objects.requireNonNull(loader, "loader 不可为空");
        this.reloader = java.util.Objects.requireNonNull(reloader, "reloader 不可为空");
        this.holder = java.util.Objects.requireNonNull(holder, "holder 不可为空");
        this.composer = java.util.Objects.requireNonNull(composer, "composer 不可为空");
        this.dynamicStoreProvider = java.util.Objects.requireNonNull(dynamicStoreProvider, "dynamicStoreProvider 不可为空");
        this.retirementGrace = java.util.Objects.requireNonNull(retirementGrace, "retirementGrace 不可为空");
        this.staticSnapshot = holder.current();
    }

    /**
     * 启动监听（幂等）。配置文件父目录缺失 → 记 WARN 跳过（不影响启动期初值）。
     */
    public synchronized void start() throws IOException {
        Path dir = configFile.getParent();
        if (dir == null) {
            log.warn("配置文件无父目录（{}），跳过热重载监听", configFile);
            return;
        }
        if (!Files.isDirectory(dir)) {
            log.warn("配置文件父目录不存在（{}），跳过热重载监听（启动期初值仍生效）", dir);
            return;
        }
        watchService = FileSystems.getDefault().newWatchService();
        dir.register(watchService,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE);
        watcherThread = Thread.ofVirtual().name("backend-config-watcher").unstarted(() -> watchLoop(dir));
        watcherThread.start();
        log.info("热重载监听已启动：{}", configFile);
    }

    /** 监听循环：收集目标文件事件 → 防抖 → 重载。 */
    private void watchLoop(Path dir) {
        long lastEvent = 0L;
        boolean dirty = false;
        while (!closed.get() && !Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watchService.poll(POLL_INTERVAL.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (ClosedWatchServiceException e) {
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (key != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    Object ctx = event.context();
                    if (ctx instanceof Path changed && dir.resolve(changed).equals(configFile)) {
                        lastEvent = System.nanoTime();
                        dirty = true;
                    }
                }
                key.reset();
            }
            if (dirty && System.nanoTime() - lastEvent >= DEBOUNCE.toNanos()) {
                dirty = false;
                reloadOnce();
            }
        }
    }

    /**
     * 单次重载：加载 → 静态 diff（reloader 对 staticSnapshot）→ 合并动态（composer）→ 原子替换 → 下线。
     * 失败保留旧表。合并动态保证热重载不误删动态 target（I-2）。
     */
    void reloadOnce() {
        BackendRegistry current = holder.current();
        try {
            LoadedBackends loaded;
            try (InputStream in = Files.newInputStream(configFile)) {
                loaded = loader.load(in);
            }
            BackendRegistryReloader.ReloadResult result = reloader.reload(staticSnapshot, loaded);
            if (!result.changed()) {
                log.debug("热重载：static version 重复（{}），忽略", loaded.version());
                return;
            }
            staticSnapshot = result.registry();
            applyCompose();
            log.info("热重载完成：static version {}→{}，effective {} 个 target",
                    current.version(), result.registry().version(), holder.current().size());
        } catch (BackendConfigException e) {
            log.error("热重载失败，保留旧注册表（version={}）：{}", current.version(), e.getMessage());
        } catch (IOException e) {
            log.error("热重载读取失败，保留旧注册表（version={}）：{}", current.version(), configFile, e);
        }
    }

    /**
     * 动态变更触发重算（{@code DynamicBackendStore} register/unregister 的 onChange 回调）：
     * 用当前 staticSnapshot + 最新动态列表 compose → 原子替换 → 下线。仅实际变更才 swap（version 去重）。
     */
    public void recomposeForDynamicChange() {
        try {
            applyCompose();
        } catch (RuntimeException e) {
            log.error("动态变更重算失败，保留旧 effective registry", e);
        }
    }

    /** 当前静态种子名集合（供 {@code DynamicBackendStore} 做 I-3 命名冲突检测）。 */
    public Set<String> staticSnapshotNames() {
        return staticSnapshot.names();
    }

    /** 最新动态 cfg 列表（store 懒解析；运行期已就绪）。 */
    private Collection<BackendConfig> dynamicList() {
        return dynamicStoreProvider.getObject().list();
    }

    /**
     * 合并 staticSnapshot + 动态列表为 effective，原子替换 + 下线未复用 Entry（I-1/I-6）。
     * 无变更 → 跳过 getAndSet（version 去重，D-VERSION-1）。
     */
    private void applyCompose() {
        BackendRegistry previous = holder.current();
        RegistryComposer.ComposeResult composed = composer.compose(previous, staticSnapshot, dynamicList());
        if (!composed.changed()) {
            log.debug("compose 无变更，跳过 swap（version 去重）");
            return;
        }
        holder.getAndSet(composed.registry());
        retireAll(composed.toRetire());
    }

    /**
     * 优雅下线:立即 markRetired(新调用不再路由),延迟 grace 后关 client(让 in-flight 完成)。
     *
     * <p>002 整改 P2-1/FR-007:延迟关闭改由可追踪 {@link #retireScheduler} 调度(替代裸
     * {@code Thread.startVirtualThread(sleep)} 的 fire-and-forget 虚拟线程堆积),{@code close()} 可优雅回收。
     */
    private void retireAll(List<BackendEntry> toRetire) {
        for (BackendEntry entry : toRetire) {
            entry.markRetired();
            retireScheduler.schedule(() -> closeRetiredClient(entry),
                    retirementGrace.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** 退役后端 client 的延迟关闭(由 retireScheduler 在 grace 后触发)。 */
    private void closeRetiredClient(BackendEntry entry) {
        try {
            entry.client().close();
            log.debug("已关闭退役后端 client：{}", entry.config().name());
        } catch (RuntimeException e) {
            log.warn("关闭退役后端 client 失败：{}", entry.config().name(), e);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (watchService != null) {
                try {
                    watchService.close();
                } catch (IOException e) {
                    log.debug("关闭 WatchService 异常", e);
                }
            }
            if (watcherThread != null) {
                watcherThread.interrupt();
            }
            // 退役关闭调度器:shutdown 让已到期的退役关闭任务执行完;awaitTermination 有界等待(未到期 grace
            // 内的任务在容器关闭时不再阻塞——容器停止后由 JVM/OS 回收连接)。优雅回收,不再堆积 fire-and-forget 线程。
            retireScheduler.shutdown();
            try {
                if (!retireScheduler.awaitTermination(SHUTDOWN_AWAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    retireScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                retireScheduler.shutdownNow();
            }
        }
    }
}
