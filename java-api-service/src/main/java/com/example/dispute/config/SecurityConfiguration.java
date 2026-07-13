/*
 * 所属模块：身份鉴权与运行配置。
 * 文件职责：在 Spring 启动期装配安全所需 Bean 和基础设施参数。
 * 业务链路：核心入口/契约为 「securityFilterChain」；解析请求身份、限制角色权限并装配基础设施客户端和 Spring Bean。
 * 关键边界：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
 */
package com.example.dispute.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

// 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「SecurityConfiguration」。
// 类型职责：在 Spring 启动期装配安全所需 Bean 和基础设施参数；本类型显式提供 「securityFilterChain」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Configuration
public class SecurityConfiguration {

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「SecurityConfiguration.securityFilterChain(HttpSecurity,HeaderAuthenticationFilter,JsonAuthenticationEntryPoint,JsonAccessDeniedHandler)」。
    // 具体功能：「SecurityConfiguration.securityFilterChain(HttpSecurity,HeaderAuthenticationFilter,JsonAuthenticationEntryPoint,JsonAccessDeniedHandler)」：构建安全过滤器Chain；实际协作者为 「http.csrf」、「csrf.disable」、「sessions.sessionCreationPolicy」、「requests.dispatcherTypeMatchers」；处理的关键状态/协议值包括 「SYSTEM」、「PLATFORM_REVIEWER」、「CUSTOMER_SERVICE」、「ADMIN」，最终返回「SecurityFilterChain」。
    // 上游调用：「SecurityConfiguration.securityFilterChain(HttpSecurity,HeaderAuthenticationFilter,JsonAuthenticationEntryPoint,JsonAccessDeniedHandler)」由 Spring ApplicationContext 启动过程调用，配置属性完成绑定后执行本工厂方法。
    // 下游影响：「SecurityConfiguration.securityFilterChain(HttpSecurity,HeaderAuthenticationFilter,JsonAuthenticationEntryPoint,JsonAccessDeniedHandler)」向下依次触达 「http.csrf」、「csrf.disable」、「sessions.sessionCreationPolicy」、「requests.dispatcherTypeMatchers」；计算结果以「SecurityFilterChain」交给调用方。
    // 系统意义：「SecurityConfiguration.securityFilterChain(HttpSecurity,HeaderAuthenticationFilter,JsonAuthenticationEntryPoint,JsonAccessDeniedHandler)」负责主链路中的“安全过滤器Chain”；调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            HeaderAuthenticationFilter headerAuthenticationFilter,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        sessions ->
                                sessions.sessionCreationPolicy(
                                        SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        requests ->
                                requests
                                        .dispatcherTypeMatchers(
                                                DispatcherType.ASYNC,
                                                DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers(
                                                "/actuator/health",
                                                "/v3/api-docs/**",
                                                "/swagger-ui.html",
                                                "/swagger-ui/**")
                                        .permitAll()
                                        .requestMatchers("/internal/**")
                                        .hasRole("SYSTEM")
                                        .requestMatchers("/api/reviews/**")
                                        .hasAnyRole(
                                                "PLATFORM_REVIEWER",
                                                "CUSTOMER_SERVICE",
                                                "ADMIN")
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(
                        headerAuthenticationFilter, AnonymousAuthenticationFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .build();
    }
}
