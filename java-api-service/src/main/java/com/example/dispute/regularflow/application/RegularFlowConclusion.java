/*
 * 所属模块：常规争议路径。
 * 文件职责：定义RegularFlowConclusion跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；处理事实清晰、证据充分的常规路线并形成阶段结论。
 * 关键边界：路径结论仍需进入补救规划和人工审核，不能绕过统一终局链路
 */
package com.example.dispute.regularflow.application;

import java.util.List;

// 所属模块：【常规争议路径 / 应用编排层】类型「RegularFlowConclusion」。
// 类型职责：定义RegularFlowConclusion跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：路径结论仍需进入补救规划和人工审核，不能绕过统一终局链路
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record RegularFlowConclusion(
        String conclusionCode, String summary, List<String> recommendedActions) {}
