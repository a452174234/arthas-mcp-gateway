package com.arthas.gateway.orchestration;

import com.arthas.gateway.config.GatewayProperties;
import com.arthas.gateway.config.K8sHostsConfig;
import com.arthas.gateway.config.K8sHostsConfig.LoadedHosts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * K8S Hosts 配置热重载监听器（006 波2，T017，仿 {@code BackendConfigWatcher}）。
 *
 * <p>{@link WatchService} 监听 {@code config/k8s-hosts.yaml} 父目录，防抖 500ms 后重载 →
 * {@link K8sHostsConfig#loadHosts} → {@link K8sHostStore#applyDiff}。三触发源（手改文件 / portal CRUD / 启动）
 * 统一经此管道（{@link #start()} 启动期初次加载 + 监听）。
 *
 * <p><b>故障语义</b>（INV-HOT-4）：加载/解析失败 → 记 ERROR，保留旧配置（store 不变，{@link K8sHostStore#applyDiff}
 * 原子性）。文件不存在 → {@link K8sHostsConfig} 回退 application.yml 内联（005 兼容，INV-HOT-5）。
 *
 * <p>{@link AutoCloseable}：{@code close()} 关 WatchService + 中断监听虚拟线程。
 */
public final class K8sHostsWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(K8sHostsWatcher.class);
    /** 事件防抖静默期：最后一次文件变更后静默此时长才触发重载（避免编辑器多次写盘抖动）。 */
    private static final Duration DEBOUNCE = Duration.ofMillis(500);
    /** 监听线程 poll 间隔（兼作 close 响应粒度）。 */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private final Path configFile;
    private final K8sHostsConfig loader;
    private final GatewayProperties props;
    private final K8sHostStore store;

    private WatchService watchService;
    private Thread watcherThread;
    private final AtomicBoolean closed = new AtomicBoolean();

    public K8sHostsWatcher(Path configFile, K8sHostsConfig loader, GatewayProperties props, K8sHostStore store) {
        this.configFile = java.util.Objects.requireNonNull(configFile, "configFile 不可为空");
        this.loader = java.util.Objects.requireNonNull(loader, "loader 不可为空");
        this.props = java.util.Objects.requireNonNull(props, "props 不可为空");
        this.store = java.util.Objects.requireNonNull(store, "store 不可为空");
    }

    /**
     * 启动期初次加载（{@link #reloadOnce()}）+ 启动监听。配置父目录缺失 → 记 WARN 跳过监听（初次加载仍生效）。
     */
    public synchronized void start() throws IOException {
        reloadOnce(); // 启动期初次加载（含回退 application.yml）
        Path dir = configFile.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            log.warn("K8S Hosts 配置父目录缺失（{}），跳过热重载监听（启动期初值仍生效）", configFile);
            return;
        }
        watchService = FileSystems.getDefault().newWatchService();
        dir.register(watchService,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE);
        watcherThread = Thread.ofVirtual().name("k8s-hosts-watcher").unstarted(() -> watchLoop(dir));
        watcherThread.start();
        log.info("K8S Hosts 热重载监听已启动：{}", configFile);
    }

    /** 监听循环：收集目标文件事件 → 防抖 → 重载。 */
    private void watchLoop(Path dir) {
        long lastEvent = 0L;
        boolean dirty = false;
        while (!closed.get() && !Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watchService.poll(POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
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
     * 单次重载：加载 → {@link K8sHostStore#applyDiff}。失败保留旧配置（INV-HOT-4）。
     */
    void reloadOnce() {
        try {
            LoadedHosts loaded = loader.loadHosts(configFile, props);
            store.applyDiff(loaded.hosts());
            log.info("K8S Hosts 重载完成：{} host(s)（{}）", loaded.hosts().size(),
                    loaded.fromFallback() ? "回退 application.yml 内联" : configFile);
        } catch (Exception e) {
            log.error("K8S Hosts 热重载失败，保留旧配置：{}", e.getMessage(), e);
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
        }
    }
}
