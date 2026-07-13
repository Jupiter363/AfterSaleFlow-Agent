/*
 * 所属模块：后端公共边界。
 * 文件职责：表达Business失败语义，使上层能够区分业务拒绝、协议错误和可重试故障。
 * 业务链路：核心入口/契约为 「errorCode」、「details」；统一 API 包装、异常映射、链路标识、审计端口与事务后副作用。
 * 关键边界：公共组件不得暗含具体案件裁决规则
 */
package com.example.dispute.common.exception;

import com.example.dispute.common.api.ErrorCode;
import java.util.Map;

// 所属模块：【后端公共边界 / 核心业务层】类型「BusinessException」。
// 类型职责：表达Business失败语义，使上层能够区分业务拒绝、协议错误和可重试故障；本类型显式提供 「BusinessException」、「BusinessException」、「errorCode」、「details」。
// 协作关系：主要由 「AgentRunCoordinator.start」、「GlobalExceptionHandler.handleBusinessException」、「HearingOutcomeOrchestrationService.orchestrate」、「HearingRoundService.assertWritableRound」 使用。
// 边界意义：公共组件不得暗含具体案件裁决规则
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    // 所属模块：【后端公共边界 / 核心业务层】「BusinessException.BusinessException(ErrorCode,String,Map)」。
    // 具体功能：「BusinessException.BusinessException(ErrorCode,String,Map)」：把 「errorCode」(ErrorCode)、「message」(String)、「details」(Map) 交给父异常保存错误链；构造过程不执行日志、重试或业务补偿。
    // 上游调用：「BusinessException.BusinessException(ErrorCode,String,Map)」的上游创建点包括 「AgentRunCoordinator.start」、「HearingOutcomeOrchestrationService.orchestrate」、「HearingRoundService.assertWritableRound」、「RemedyApplicationService.generateForWorkflow」。
    // 下游影响：「BusinessException.BusinessException(ErrorCode,String,Map)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「BusinessException.BusinessException(ErrorCode,String,Map)」负责主链路中的“Business异常”；公共组件不得暗含具体案件裁决规则
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public BusinessException(
            ErrorCode errorCode, String message, Map<String, Object> details) {
        this(errorCode, message, details, null);
    }

    // 所属模块：【后端公共边界 / 核心业务层】「BusinessException.BusinessException(ErrorCode,String,Map,Throwable)」。
    // 具体功能：「BusinessException.BusinessException(ErrorCode,String,Map,Throwable)」：把 「errorCode」(ErrorCode)、「message」(String)、「details」(Map)、「cause」(Throwable) 交给父异常保存错误链；构造过程不执行日志、重试或业务补偿。
    // 上游调用：「BusinessException.BusinessException(ErrorCode,String,Map,Throwable)」的上游创建点包括 「AgentRunCoordinator.start」、「HearingOutcomeOrchestrationService.orchestrate」、「HearingRoundService.assertWritableRound」、「RemedyApplicationService.generateForWorkflow」。
    // 下游影响：「BusinessException.BusinessException(ErrorCode,String,Map,Throwable)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「BusinessException.BusinessException(ErrorCode,String,Map,Throwable)」负责主链路中的“Business异常”；公共组件不得暗含具体案件裁决规则
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public BusinessException(
            ErrorCode errorCode,
            String message,
            Map<String, Object> details,
            Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    // 所属模块：【后端公共边界 / 核心业务层】「BusinessException.errorCode()」。
    // 具体功能：「BusinessException.errorCode()」：构建错误代码，最终返回「ErrorCode」。
    // 上游调用：「BusinessException.errorCode()」的上游调用点包括 「GlobalExceptionHandler.handleBusinessException」。
    // 下游影响：「BusinessException.errorCode()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ErrorCode」交给调用方。
    // 系统意义：「BusinessException.errorCode()」负责主链路中的“错误代码”；公共组件不得暗含具体案件裁决规则
    public ErrorCode errorCode() {
        return errorCode;
    }

    // 所属模块：【后端公共边界 / 核心业务层】「BusinessException.details()」。
    // 具体功能：「BusinessException.details()」：构建详情，最终返回「Map<String, Object>」。
    // 上游调用：「BusinessException.details()」的上游调用点包括 「GlobalExceptionHandler.handleBusinessException」。
    // 下游影响：「BusinessException.details()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Map<String, Object>」交给调用方。
    // 系统意义：「BusinessException.details()」负责主链路中的“详情”；公共组件不得暗含具体案件裁决规则
    public Map<String, Object> details() {
        return details;
    }
}
