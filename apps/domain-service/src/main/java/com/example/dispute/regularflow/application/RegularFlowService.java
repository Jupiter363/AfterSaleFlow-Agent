/*
 * 所属模块：常规争议路径。
 * 文件职责：编排RegularFlow规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「conclude」；处理事实清晰、证据充分的常规路线并形成阶段结论。
 * 关键边界：路径结论仍需进入补救规划和人工审核，不能绕过统一终局链路
 */
package com.example.dispute.regularflow.application;

import org.springframework.stereotype.Service;

// 所属模块：【常规争议路径 / 应用编排层】类型「RegularFlowService」。
// 类型职责：编排RegularFlow规则、权限校验与事实读写；本类型显式提供 「conclude」。
// 协作关系：主要由 「RouterApplicationService.createConclusion」 使用。
// 边界意义：路径结论仍需进入补救规划和人工审核，不能绕过统一终局链路
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class RegularFlowService {

    // 所属模块：【常规争议路径 / 应用编排层】「RegularFlowService.conclude(String)」。
    // 具体功能：「RegularFlowService.conclude(String)」：形成阶段结论RegularFlowConclusion；处理的关键状态/协议值包括 「LOGISTICS_QUERY」、「DELIVERY_STATUS」、「LOGISTICS_STATUS_READY」、「QUERY_LOGISTICS」，最终返回「RegularFlowConclusion」。
    // 上游调用：「RegularFlowService.conclude(String)」的上游调用点包括 「RouterApplicationService.createConclusion」。
    // 下游影响：「RegularFlowService.conclude(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RegularFlowConclusion」交给调用方。
    // 系统意义：「RegularFlowService.conclude(String)」负责主链路中的“RegularFlowConclusion”；路径结论仍需进入补救规划和人工审核，不能绕过统一终局链路
    public RegularFlowConclusion conclude(String caseType) {
        return switch (caseType) {
            case "LOGISTICS_QUERY", "DELIVERY_STATUS" ->
                    new RegularFlowConclusion(
                            "LOGISTICS_STATUS_READY",
                            "Order and logistics status are ready for remedy planning.",
                            java.util.List.of(
                                    "QUERY_LOGISTICS", "PREPARE_STATUS_NOTICE"));
            case "DELIVERY_REMINDER" ->
                    new RegularFlowConclusion(
                            "FULFILLMENT_REMINDER_RECOMMENDED",
                            "A fulfillment reminder is recommended for approval.",
                            java.util.List.of("CREATE_FULFILLMENT_REMINDER"));
            default ->
                    new RegularFlowConclusion(
                            "REGULAR_SERVICE_TICKET_RECOMMENDED",
                            "A regular fulfillment service ticket is recommended.",
                            java.util.List.of("CREATE_SERVICE_TICKET"));
        };
    }
}
