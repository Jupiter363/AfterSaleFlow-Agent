/*
 * 所属模块：身份鉴权与运行配置。
 * 文件职责：在 Spring 启动期装配Infrastructure所需 Bean 和基础设施参数。
 * 业务链路：核心入口/契约为 「minioClient」、「workflowServiceStubs」、「workflowClient」；解析请求身份、限制角色权限并装配基础设施客户端和 Spring Bean。
 * 关键边界：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
 */
package com.example.dispute.config;

import io.minio.MinioClient;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「InfrastructureClientConfiguration」。
// 类型职责：在 Spring 启动期装配Infrastructure所需 Bean 和基础设施参数；本类型显式提供 「minioClient」、「workflowServiceStubs」、「workflowClient」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Configuration
public class InfrastructureClientConfiguration {

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「InfrastructureClientConfiguration.minioClient(AppProperties)」。
    // 具体功能：「InfrastructureClientConfiguration.minioClient(AppProperties)」：构建minio客户端；实际协作者为 「properties.minio」、「minio.endpoint」、「minio.accessKey」、「minio.secretKey」，最终返回「MinioClient」。
    // 上游调用：「InfrastructureClientConfiguration.minioClient(AppProperties)」由 Spring ApplicationContext 启动过程调用，配置属性完成绑定后执行本工厂方法。
    // 下游影响：「InfrastructureClientConfiguration.minioClient(AppProperties)」向下依次触达 「properties.minio」、「minio.endpoint」、「minio.accessKey」、「minio.secretKey」；计算结果以「MinioClient」交给调用方。
    // 系统意义：「InfrastructureClientConfiguration.minioClient(AppProperties)」负责主链路中的“minio客户端”；调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    @Bean
    MinioClient minioClient(AppProperties properties) {
        AppProperties.Minio minio = properties.minio();
        return MinioClient.builder()
                .endpoint(minio.endpoint())
                .credentials(minio.accessKey(), minio.secretKey())
                .build();
    }

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「InfrastructureClientConfiguration.workflowServiceStubs(AppProperties)」。
    // 具体功能：「InfrastructureClientConfiguration.workflowServiceStubs(AppProperties)」：构建工作流服务Stubs；实际协作者为 「WorkflowServiceStubsOptions.newBuilder」、「WorkflowServiceStubs.newServiceStubs」、「properties.temporal」、「WorkflowServiceStubsOptions.newBuilder().setTarget」，最终返回「WorkflowServiceStubs」。
    // 上游调用：「InfrastructureClientConfiguration.workflowServiceStubs(AppProperties)」由 Spring ApplicationContext 启动过程调用，配置属性完成绑定后执行本工厂方法。
    // 下游影响：「InfrastructureClientConfiguration.workflowServiceStubs(AppProperties)」向下依次触达 「WorkflowServiceStubsOptions.newBuilder」、「WorkflowServiceStubs.newServiceStubs」、「properties.temporal」、「WorkflowServiceStubsOptions.newBuilder().setTarget」；计算结果以「WorkflowServiceStubs」交给调用方。
    // 系统意义：「InfrastructureClientConfiguration.workflowServiceStubs(AppProperties)」负责主链路中的“工作流服务Stubs”；调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs workflowServiceStubs(AppProperties properties) {
        WorkflowServiceStubsOptions options =
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(properties.temporal().address())
                        .build();
        return WorkflowServiceStubs.newServiceStubs(options);
    }

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「InfrastructureClientConfiguration.workflowClient(WorkflowServiceStubs,AppProperties)」。
    // 具体功能：「InfrastructureClientConfiguration.workflowClient(WorkflowServiceStubs,AppProperties)」：构建工作流客户端；实际协作者为 「WorkflowClient.newInstance」、「WorkflowClientOptions.newBuilder」、「properties.temporal」、「WorkflowClientOptions.newBuilder().setNamespace」，最终返回「WorkflowClient」。
    // 上游调用：「InfrastructureClientConfiguration.workflowClient(WorkflowServiceStubs,AppProperties)」由 Spring ApplicationContext 启动过程调用，配置属性完成绑定后执行本工厂方法。
    // 下游影响：「InfrastructureClientConfiguration.workflowClient(WorkflowServiceStubs,AppProperties)」向下依次触达 「WorkflowClient.newInstance」、「WorkflowClientOptions.newBuilder」、「properties.temporal」、「WorkflowClientOptions.newBuilder().setNamespace」；计算结果以「WorkflowClient」交给调用方。
    // 系统意义：「InfrastructureClientConfiguration.workflowClient(WorkflowServiceStubs,AppProperties)」负责主链路中的“工作流客户端”；调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    @Bean
    WorkflowClient workflowClient(
            WorkflowServiceStubs serviceStubs, AppProperties properties) {
        WorkflowClientOptions options =
                WorkflowClientOptions.newBuilder()
                        .setNamespace(properties.temporal().namespace())
                        .build();
        return WorkflowClient.newInstance(serviceStubs, options);
    }
}
