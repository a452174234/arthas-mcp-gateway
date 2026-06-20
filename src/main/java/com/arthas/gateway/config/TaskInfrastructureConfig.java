package com.arthas.gateway.config;

import com.arthas.gateway.task.AsyncTaskExecutor;
import com.arthas.gateway.task.TaskStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

/**
 * 异步任务基础设施装配（T033/T035）：{@link TaskStore} + {@link AsyncTaskExecutor}。
 *
 * <p>两者均为有状态、生命周期对象（持后台线程池），从 {@link GatewayProperties.Task} 取默认值：
 * <ul>
 *   <li>{@code resultTtl}（默认 1h）：终态任务可查询保留时长，过期惰性 + 主动清理。</li>
 *   <li>{@code backendTimeout}（默认 11min）：后台阻塞等后端路①的兜底超时，&gt; 后端 10min 上限。</li>
 * </ul>
 *
 * <p><b>销毁</b>：两者均有 {@code close()}（关线程池），Spring @Bean 默认推断 destroy-method=infer
 * 会自动调用，容器关闭时优雅回收后台虚拟线程。
 */
@Configuration
public class TaskInfrastructureConfig {

    @Bean
    TaskStore taskStore(GatewayProperties props) {
        return new TaskStore(props.getTask().getResultTtl(), Instant::now);
    }

    @Bean
    AsyncTaskExecutor asyncTaskExecutor(TaskStore store, GatewayProperties props) {
        return new AsyncTaskExecutor(store, props.getTask().getBackendTimeout());
    }
}
