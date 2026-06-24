package com.arthas.gateway.orchestration;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * K8S 编排启用条件（003 特性）：仅当 {@code arthas-gateway.k8s.kubeconfig} 指向的 kubeconfig 文件
 * <b>存在且可读</b>时，装配 K8S 编排 bean（{@link K8sClientFactory} 及其下游 PodExplorer/Exposer/
 * Provisioner/Handlers）。
 *
 * <p><b>设计意图</b>：3 个编排工具的<b>规格</b>（name/description/schema）始终注册（{@code tools/list}=38 恒成立，
 * 回归守护 T029）；但其 <b>handler 执行</b>依赖真实 kubeconfig——CI 无 k3s 时 kubeconfig 不存在 → 跳过编排
 * bean 装配 → 调用编排工具返 "K8S 编排未启用" 明确错误（而非启动期崩上下文）。本地有 k3s 测试床时 kubeconfig
 * 存在 → 全量编排生效。
 *
 * <p>等价于「kubeconfig 可读 = 编排可用」，避免对 CI 侧无意义资源的需求（波次 A 纯逻辑测试不依赖 k3s）。
 */
public class K8sEnabledCondition implements Condition {

    private static final Logger log = LoggerFactory.getLogger(K8sEnabledCondition.class);

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String kubeconfig = context.getEnvironment().getProperty("arthas-gateway.k8s.kubeconfig");
        if (kubeconfig == null || kubeconfig.isBlank()) {
            log.debug("K8S 编排未启用：arthas-gateway.k8s.kubeconfig 未配置");
            return false;
        }
        Path path = Path.of(kubeconfig);
        boolean ok = Files.isReadable(path);
        log.debug("K8S 编排启用判定：kubeconfig={} readable={}", path, ok);
        return ok;
    }
}
