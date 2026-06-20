package com.arthas.gateway.testfixtures;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OrderService 单测（T008 夹具业务逻辑，纯 Java）。
 *
 * <p>验证 {@link DemoBusinessApp} 的被诊断目标业务逻辑正确性——这是 arthas watch/trace/stack/tt 的
 * 真实诊断对象（classPattern={@code com.arthas.gateway.testfixtures.OrderService}、methodPattern={@code hotMethod}）。
 * 夹具自身逻辑正确是所有依赖它的集成测试（T018~T020/T043 等）的前提，故独立单测保证。
 *
 * <p>纯逻辑单测（surefire，无 arthas）：测 hotMethod 计算/调用计数/慢响应注入。arthas 真实诊断（watch 抓真实调用）
 * 由 T009 端到端夹具 + T018 契约测试（真实 attach + 真实 watch）承担，非本单测职责。
 */
class OrderServiceTest {

    @Test
    void hotMethodComputesPriceAndValidity() throws InterruptedException {
        OrderService service = new OrderService();
        OrderResult result = service.hotMethod(5);

        assertThat(result.orderId()).isEqualTo(5);
        assertThat(result.price()).as("price = orderId*31 + 7").isEqualTo(5L * 31 + 7);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void hotMethodMarksInvalidForNegativeOrderId() throws InterruptedException {
        OrderService service = new OrderService();
        assertThat(service.hotMethod(-1).valid()).isFalse();
        assertThat(service.hotMethod(0).valid()).as("0 视为合法").isTrue();
    }

    @Test
    void hotMethodCountsEachInvocation() throws InterruptedException {
        OrderService service = new OrderService();
        assertThat(service.hotMethodInvocations()).isZero();
        service.hotMethod(1);
        service.hotMethod(2);
        service.hotMethod(3);
        assertThat(service.hotMethodInvocations()).isEqualTo(3L);
    }

    @Test
    void slowResponseInjectionDelaysHotMethod() throws InterruptedException {
        OrderService service = new OrderService();
        service.configureSlowResponse(150);

        long startMs = System.nanoTime();
        service.hotMethod(1);
        long elapsedMs = (System.nanoTime() - startMs) / 1_000_000L;

        assertThat(elapsedMs).as("注入 150ms 慢响应后耗时 >= 140ms（容忍调度误差）").isGreaterThanOrEqualTo(140L);
    }

    @Test
    void defaultHasNoSlowResponse() throws InterruptedException {
        OrderService service = new OrderService();

        long startMs = System.nanoTime();
        service.hotMethod(1);
        long elapsedMs = (System.nanoTime() - startMs) / 1_000_000L;

        assertThat(elapsedMs).as("默认无 sleep，应快速返回（< 50ms）").isLessThan(50L);
    }

    @Test
    void slowResponseCanBeCleared() throws InterruptedException {
        OrderService service = new OrderService();
        service.configureSlowResponse(120);
        service.hotMethod(1); // 慢一次
        service.configureSlowResponse(0); // 清除

        long startMs = System.nanoTime();
        service.hotMethod(2);
        long elapsedMs = (System.nanoTime() - startMs) / 1_000_000L;

        assertThat(elapsedMs).as("清除后无 sleep").isLessThan(50L);
    }
}
