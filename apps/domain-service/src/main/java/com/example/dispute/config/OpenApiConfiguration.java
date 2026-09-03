/*
 * 所属模块：身份鉴权与运行配置。
 * 文件职责：在 Spring 启动期装配OpenApi所需 Bean 和基础设施参数。
 * 业务链路：核心入口/契约为 「disputeOpenApi」；解析请求身份、限制角色权限并装配基础设施客户端和 Spring Bean。
 * 关键边界：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
 */
package com.example.dispute.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「OpenApiConfiguration」。
// 类型职责：在 Spring 启动期装配OpenApi所需 Bean 和基础设施参数；本类型显式提供 「disputeOpenApi」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Configuration
public class OpenApiConfiguration {

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「OpenApiConfiguration.disputeOpenApi()」。
    // 具体功能：「OpenApiConfiguration.disputeOpenApi()」：构建争议OpenApi；实际协作者为 「newOpenAPI().info」、「description」、「newInfo().title("OrderFulfillmentDisputeAPI").version」、「newInfo().title」；处理的关键状态/协议值包括 「v1」，最终返回「OpenAPI」。
    // 上游调用：「OpenApiConfiguration.disputeOpenApi()」由 Spring ApplicationContext 启动过程调用，配置属性完成绑定后执行本工厂方法。
    // 下游影响：「OpenApiConfiguration.disputeOpenApi()」向下依次触达 「newOpenAPI().info」、「description」、「newInfo().title("OrderFulfillmentDisputeAPI").version」、「newInfo().title」；计算结果以「OpenAPI」交给调用方。
    // 系统意义：「OpenApiConfiguration.disputeOpenApi()」负责主链路中的“争议OpenApi”；调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    @Bean
    OpenAPI disputeOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Order Fulfillment Dispute API")
                                .version("v1")
                                .description(
                                        "Human-review-gated dispute workflow and deterministic execution API."));
    }
}
