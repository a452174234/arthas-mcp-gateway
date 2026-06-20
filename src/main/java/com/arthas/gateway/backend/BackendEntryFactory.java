package com.arthas.gateway.backend;

import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * 后端运行对象工厂（T027 装配）：{@link BackendConfig} → {@link BackendEntry}。
 *
 * <p>聚合三件套：
 * <ul>
 *   <li>{@link HttpBackendClient}——真实 MCP 客户端（T024，独立连接池 + 会话 + 认证头）；构造不连，
 *       首次 {@code initialize()}（路由转发时）才建立后端会话。</li>
 *   <li>{@link CircuitBreaker}——熔断器（T045），注入纳秒时钟；Phase 3 初始 CLOSED，Phase 5 T048 接入
 *       {@code allowRequest}/{@code recordSuccess}/{@code recordFailure} 守卫。</li>
 *   <li>{@link BackendEntry}——含 {@code taskSlots}（Semaphore(maxConcurrentTasks)，T047 per-target 限流）。</li>
 * </ul>
 *
 * <p>时钟可注入（测试用固定时钟驱动熔断退避）；默认 {@link System#nanoTime}（单调纳秒）。
 */
@Component
public class BackendEntryFactory {

    private final LongSupplier clock;

    /** 默认系统纳秒时钟。 */
    public BackendEntryFactory() {
        this(System::nanoTime);
    }

    /** 注入时钟（测试驱动熔断退避时间）。 */
    public BackendEntryFactory(LongSupplier clock) {
        this.clock = clock;
    }

    /** 由后端配置构造运行对象（client 不连、breaker=CLOSED、slots=Semaphore(maxConcurrentTasks)）。 */
    public BackendEntry create(BackendConfig config) {
        BackendClient client = new HttpBackendClient(config);
        CircuitBreaker breaker = CircuitBreaker.create(clock);
        return new BackendEntry(config, client, breaker);
    }
}
