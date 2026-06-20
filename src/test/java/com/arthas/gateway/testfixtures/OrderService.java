package com.arthas.gateway.testfixtures;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单业务服务（T008 夹具）——arthas 诊断的真实目标对象。
 *
 * <p>{@link #hotMethod(int)} 是持续被调用的热点方法：内部依次调 {@link #calc}/{@link #check} 形成调用链
 * （便于 trace/stack 展开），返回 {@link OrderResult}（便于 watch 观察返回值）。
 * classPattern={@code com.arthas.gateway.testfixtures.OrderService}、methodPattern={@code hotMethod}。
 *
 * <p>{@link #configureSlowResponse(long)} 注入 {@code Thread.sleep} 模拟慢响应（AsyncTaskTimeout 等测试用，
 * 设计文档 §4「方法支持注入 Thread.sleep」）。默认无 sleep。{@link #hotMethodInvocations()} 暴露调用计数
 * （测试断言「业务服务真实调用产生诊断数据」用）。
 *
 * <p>纯 POJO（无 Spring 依赖），{@link DemoBusinessApp}（JDK HttpServer）与后台守护线程共用同一实例。
 */
public class OrderService {

    private final AtomicLong invocations = new AtomicLong();
    private volatile long slowMs = 0L;

    /**
     * 热点业务方法。
     *
     * @param orderId 订单 id
     * @return 订单结果
     * @throws InterruptedException 当注入慢响应被中断时
     */
    public OrderResult hotMethod(int orderId) throws InterruptedException {
        if (slowMs > 0L) {
            Thread.sleep(slowMs);
        }
        long price = calc(orderId);
        boolean valid = check(orderId);
        invocations.incrementAndGet();
        return new OrderResult(orderId, price, valid);
    }

    /** 价格计算（hotMethod 调用链节点）。 */
    long calc(int orderId) {
        return (long) orderId * 31 + 7;
    }

    /** 合法性检查（hotMethod 调用链节点）。 */
    boolean check(int orderId) {
        return orderId >= 0;
    }

    /** 累计 hotMethod 调用次数（测试断言真实调用用）。 */
    public long hotMethodInvocations() {
        return invocations.get();
    }

    /**
     * 注入慢响应（毫秒）。设 0 清除。
     *
     * @param ms 睡眠毫秒数；&lt;=0 表示不睡
     */
    public void configureSlowResponse(long ms) {
        this.slowMs = ms;
    }

    /** 当前慢响应设置（毫秒），{@code DemoBusinessApp} handler save/restore 用（避免单次注入污染后台循环）。 */
    public long currentSlowMs() {
        return slowMs;
    }
}
