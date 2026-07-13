/*
 * 所属模块：身份鉴权与运行配置。
 * 文件职责：承载App的类型化配置并在启动期完成约束校验。
 * 业务链路：该文件主要提供类型或包级契约；解析请求身份、限制角色权限并装配基础设施客户端和 Spring Bean。
 * 关键边界：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
 */
package com.example.dispute.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「AppProperties」。
// 类型职责：承载App的类型化配置并在启动期完成约束校验；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String env,
        Security security,
        Integration agent,
        Integration ocr,
        Temporal temporal,
        Minio minio,
        Elasticsearch elasticsearch,
        Feature feature,
        Logging logging) {

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「Security」。
    // 类型职责：定义安全跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record Security(String serviceSecret) {}

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「Integration」。
    // 类型职责：定义Integration跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record Integration(String baseUrl, String serviceSecret, int timeoutMs) {}

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「Temporal」。
    // 类型职责：定义Temporal跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record Temporal(String address, String namespace, String taskQueue) {}

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「Minio」。
    // 类型职责：定义Minio跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record Minio(
            String endpoint,
            String accessKey,
            String secretKey,
            String evidenceOriginalBucket,
            String evidenceDesensitizedBucket) {}

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「Elasticsearch」。
    // 类型职责：定义Elasticsearch跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record Elasticsearch(String url) {}

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「Feature」。
    // 类型职责：定义Feature跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record Feature(
            boolean agentIntakeEnabled,
            boolean agentHearingEnabled,
            boolean agentEvaluationEnabled,
            boolean ocrEnabled,
            boolean humanReviewRequired,
            boolean toolExecutorSimulation,
            boolean autoCloseEnabled) {}

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「Logging」。
    // 类型职责：定义Logging跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record Logging(boolean auditEnabled, boolean sensitiveMaskingEnabled) {}
}
