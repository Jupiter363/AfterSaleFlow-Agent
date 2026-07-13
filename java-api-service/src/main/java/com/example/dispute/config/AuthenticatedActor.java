/*
 * 所属模块：身份鉴权与运行配置。
 * 文件职责：定义Authenticated操作者跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；解析请求身份、限制角色权限并装配基础设施客户端和 Spring Bean。
 * 关键边界：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
 */
package com.example.dispute.config;

// 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「AuthenticatedActor」。
// 类型职责：定义Authenticated操作者跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record AuthenticatedActor(String actorId, ActorRole role) {}
