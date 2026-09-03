/*
 * 所属模块：身份鉴权与运行配置。
 * 文件职责：在 Spring 启动期装配Common所需 Bean 和基础设施参数。
 * 业务链路：核心入口/契约为 「systemClock」；解析请求身份、限制角色权限并装配基础设施客户端和 Spring Bean。
 * 关键边界：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
 */
package com.example.dispute.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「CommonConfiguration」。
// 类型职责：在 Spring 启动期装配Common所需 Bean 和基础设施参数；本类型显式提供 「systemClock」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Configuration
public class CommonConfiguration {

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「CommonConfiguration.systemClock()」。
    // 具体功能：「CommonConfiguration.systemClock()」：构建system时钟；实际协作者为 「Clock.systemUTC」，最终返回「Clock」。
    // 上游调用：「CommonConfiguration.systemClock()」由 Spring ApplicationContext 启动过程调用，配置属性完成绑定后执行本工厂方法。
    // 下游影响：「CommonConfiguration.systemClock()」向下依次触达 「Clock.systemUTC」；计算结果以「Clock」交给调用方。
    // 系统意义：「CommonConfiguration.systemClock()」负责主链路中的“system时钟”；调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「SchedulingConfiguration」。
    // 类型职责：在 Spring 启动期装配Scheduling所需 Bean 和基础设施参数；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(
            name = "dispute.scheduling.enabled",
            havingValue = "true",
            matchIfMissing = true)
    static class SchedulingConfiguration {}
}
