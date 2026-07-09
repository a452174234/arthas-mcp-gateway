package com.arthas.gateway.orchestration;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 005 US3 测试用 {@link ArthasLauncher} fixture（T022，<b>真实实现非 mock</b>，INV-LAUNCHER-5）。
 *
 * <p>验证 SPI 委托机制（{@code ArthasProvisioner} 委托 launcher）与 @Primary 覆盖（用户自定义实现覆盖 Default，
 * INV-LAUNCHER-3）。<b>不</b>带 {@code @Component}——避免污染所有 SpringBootTest IT（ArthasProvisionerIT 等期望
 * DefaultArthasLauncher）；@Primary 装配由 {@code CustomLauncherContractIT}（T024）的 {@code @TestConfiguration} 显式注入。
 * T023 单测直接 new 或 mock（不经 Spring）。
 *
 * <p><b>探针字段</b>（非 mock 桩，而是真实记录被调用的证据）：
 * <ul>
 *   <li>{@link #locateCalls()}：locatePid 被调次数（验证委托）。</li>
 *   <li>{@link #lastContext()}：最近传入的 LaunchContext（验证契约字段）。</li>
 *   <li>{@link #lastPid()}：startArthas 收到的 pid（验证委托传递 pid）。</li>
 *   <li>{@link #failOnStart()}：注入真实故障（LaunchException → ProvisionException 映射，INV-LAUNCHER-4）。</li>
 * </ul>
 */
public class TestArthasLauncher implements ArthasLauncher {

    /** 固定定位的 PID（验证 ArthasProvisioner 用 launcher 返值，非硬编码）。 */
    public static final long FIXED_PID = 12345L;

    private final AtomicLong locateCalls = new AtomicLong();
    private final AtomicReference<LaunchContext> lastCtx = new AtomicReference<>();
    private final AtomicLong lastPid = new AtomicLong(-1);
    private volatile boolean failOnStart;

    /** 注入真实故障：startArthas 抛 LaunchException（attach_failed@start_arthas，INV-LAUNCHER-4）。 */
    public void failOnStart() {
        this.failOnStart = true;
    }

    public long locateCalls() {
        return locateCalls.get();
    }

    public LaunchContext lastContext() {
        return lastCtx.get();
    }

    public long lastPid() {
        return lastPid.get();
    }

    @Override
    public long locatePid(LaunchContext ctx) {
        locateCalls.incrementAndGet();
        lastCtx.set(ctx);
        return FIXED_PID;
    }

    @Override
    public void startArthas(LaunchContext ctx, long pid) {
        lastCtx.set(ctx);
        lastPid.set(pid);
        if (failOnStart) {
            throw new LaunchException(new OrchestrationRecord.Error(
                    "start_arthas", "attach_failed", "TestArthasLauncher 注入真实故障"));
        }
    }
}
