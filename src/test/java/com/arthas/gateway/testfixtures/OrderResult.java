package com.arthas.gateway.testfixtures;

/**
 * 订单业务结果（T008 夹具）。{@link OrderService#hotMethod} 的返回值——arthas watch 的观察对象。
 *
 * @param orderId 订单 id（入参回显）
 * @param price   计算价格（{@code calc} 产出）
 * @param valid   是否合法（{@code check} 产出）
 */
public record OrderResult(int orderId, long price, boolean valid) {
}
