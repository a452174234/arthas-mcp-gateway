package com.arthas.gateway.backend;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 后端运行对象工厂（T027 装配）：{@link BackendConfig} → {@link BackendEntry}。
 *
 * <p>聚合三件套：
 * <ul>
 *   <li>{@link HttpBackendClient}——真实 MCP 客户端（T024，独立连接池 + 会话 + 认证头）；构造不连，
 *       首次 {@code initialize()}（路由转发时）才建立后端会话。</li>
 *   <li>{@link CircuitBreaker}——熔断器（T045），注入纳秒时钟。</li>
 *   <li>{@link BackendEntry}——含 {@code taskSlots}（Semaphore(maxConcurrentTasks)，T047 per-target 限流）。</li>
 * </ul>
 *
 * <p><b>005 US2 懒 resolve</b>：静态模式（config.url）构造时预建 {@link HttpBackendClient}；K8S 模式
 * （config.k8sHost）传 {@code null}，由 {@link BackendEntry} 首次 initialize 时经 {@link BackendResolver}
 * 懒 resolve 出 mcpUrl 再建。用 {@link ObjectProvider}（<b>非</b>构造期 {@code Optional}）注入 resolver——
 * 因 {@code BackendResolver → ArthasProvisioner → DynamicBackendStore → BackendConfigWatcher → 本工厂}
 * 形成循环，构造期注入会 {@code BeanCurrentlyInCreationException}；{@code ObjectProvider} 推迟到 {@code create}
 * 时解析，打破构造期环。
 *
 * <p>时钟可注入（测试用固定时钟驱动熔断退避）；默认 {@link System#nanoTime}（单调纳秒）。
 */
@Component
public class BackendEntryFactory {

    private final LongSupplier clock;
    private final ObjectProvider<BackendResolver> resolverProvider;

    /** 测试默认构造（无 resolver，静态模式）。 */
    public BackendEntryFactory() {
        this(System::nanoTime);
    }

    /** 注入时钟（测试驱动熔断退避时间），resolver 默认 empty。 */
    public BackendEntryFactory(LongSupplier clock) {
        this(clock, null);
    }

    /** 005 US2：Spring 注入 {@link ObjectProvider}（懒解析，避免构造期循环依赖）。 */
    @Autowired
    public BackendEntryFactory(ObjectProvider<BackendResolver> resolverProvider) {
        this(System::nanoTime, resolverProvider);
    }

    /** 全参数构造（测试/装配）。 */
    public BackendEntryFactory(LongSupplier clock, ObjectProvider<BackendResolver> resolverProvider) {
        this.clock = clock;
        this.resolverProvider = resolverProvider;
    }

    /**
     * 由后端配置构造运行对象：静态模式预建 client（config.url）；K8S 模式 client=null（懒 resolve，INV-K8SHOST-4）。
     * breaker=CLOSED、slots=Semaphore(maxConcurrentTasks)。resolver 经 {@link ObjectProvider} 懒取（无 K8S 配置时 empty）。
     */
    public BackendEntry create(BackendConfig config) {
        BackendClient client = config.isK8sMode() ? null : new HttpBackendClient(config);
        CircuitBreaker breaker = CircuitBreaker.create(clock);
        // Supplier 懒解析：create() 不触发 backendResolver（避免启动期 registryHolder↔dynamicBackendStore 装配环）；
        // 运行时 initializeOnce 才解析（此时 context 已就绪）
        Supplier<Optional<BackendResolver>> supplier = () -> resolverProvider != null
                ? Optional.ofNullable(resolverProvider.getIfAvailable())
                : Optional.empty();
        return new BackendEntry(config, client, breaker, supplier);
    }
}
