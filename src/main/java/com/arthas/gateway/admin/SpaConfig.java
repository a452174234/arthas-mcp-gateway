package com.arthas.gateway.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 004 SPA fallback（admin-invariants INV-WEB-1）：Vue Router history 模式的 deep link
 * （reload {@code /backends}、{@code /tasks} 或直接访问）直达 Spring → 无映射 → 404。
 *
 * <p>本配置把已知 SPA 路由（{@code /backends}、{@code /tasks}）forward 到 {@code /index.html}，
 * 让浏览器加载 SPA 后由 Vue Router 在前端解析当前路径。不影响 {@code /admin}、{@code /mcp}、
 * {@code /actuator}（API 端点，仍走各自 controller）。
 *
 * <p>新增前端路由时在此补一行 forward（MVP 仅两路由）。
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/backends").setViewName("forward:/index.html");
        registry.addViewController("/tasks").setViewName("forward:/index.html");
    }
}
