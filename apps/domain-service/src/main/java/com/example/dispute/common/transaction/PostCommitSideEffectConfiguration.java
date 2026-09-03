/*
 * 所属模块：后端公共边界。
 * 文件职责：在 Spring 启动期装配事务后提交副作用所需 Bean 和基础设施参数。
 * 业务链路：核心入口/契约为 「postCommitSideEffectTaskExecutor」；统一 API 包装、异常映射、链路标识、审计端口与事务后副作用。
 * 关键边界：公共组件不得暗含具体案件裁决规则
 */
package com.example.dispute.common.transaction;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// 所属模块：【后端公共边界 / 核心业务层】类型「PostCommitSideEffectConfiguration」。
// 类型职责：在 Spring 启动期装配事务后提交副作用所需 Bean 和基础设施参数；本类型显式提供 「postCommitSideEffectTaskExecutor」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：公共组件不得暗含具体案件裁决规则
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Configuration(proxyBeanMethods = false)
class PostCommitSideEffectConfiguration {

    // 所属模块：【后端公共边界 / 核心业务层】「PostCommitSideEffectConfiguration.postCommitSideEffectTaskExecutor()」。
    // 具体功能：「PostCommitSideEffectConfiguration.postCommitSideEffectTaskExecutor()」：构建事务后提交副作用任务执行器；实际协作者为 「executor.setThreadNamePrefix」、「executor.setCorePoolSize」、「executor.setMaxPoolSize」、「executor.setQueueCapacity」；处理的关键状态/协议值包括 「post-commit-side-effect-」，最终返回「Executor」。
    // 上游调用：「PostCommitSideEffectConfiguration.postCommitSideEffectTaskExecutor()」由 Spring ApplicationContext 启动过程调用，配置属性完成绑定后执行本工厂方法。
    // 下游影响：「PostCommitSideEffectConfiguration.postCommitSideEffectTaskExecutor()」向下依次触达 「executor.setThreadNamePrefix」、「executor.setCorePoolSize」、「executor.setMaxPoolSize」、「executor.setQueueCapacity」；计算结果以「Executor」交给调用方。
    // 系统意义：「PostCommitSideEffectConfiguration.postCommitSideEffectTaskExecutor()」负责主链路中的“事务后提交副作用任务执行器”；公共组件不得暗含具体案件裁决规则
    @Bean(name = PostCommitSideEffectExecutor.EXECUTOR_BEAN_NAME)
    Executor postCommitSideEffectTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("post-commit-side-effect-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
