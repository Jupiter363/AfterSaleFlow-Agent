/*
 * 所属模块：身份鉴权与运行配置。
 * 文件职责：限定操作者角色允许出现的状态值。
 * 业务链路：该文件主要提供类型或包级契约；解析请求身份、限制角色权限并装配基础设施客户端和 Spring Bean。
 * 关键边界：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
 */
package com.example.dispute.config;

// 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「ActorRole」。
// 类型职责：限定操作者角色允许出现的状态值；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
// Java 语法：enum 把允许值限制为固定常量，switch 可穷举状态分支。
public enum ActorRole {
    USER,
    MERCHANT,
    CUSTOMER_SERVICE,
    PLATFORM_REVIEWER,
    ADMIN,
    SYSTEM
}
