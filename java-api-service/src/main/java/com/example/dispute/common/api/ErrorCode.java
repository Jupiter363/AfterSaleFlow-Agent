/*
 * 所属模块：后端公共边界。
 * 文件职责：限定错误代码允许出现的状态值。
 * 业务链路：核心入口/契约为 「httpStatus」；统一 API 包装、异常映射、链路标识、审计端口与事务后副作用。
 * 关键边界：公共组件不得暗含具体案件裁决规则
 */
package com.example.dispute.common.api;

import org.springframework.http.HttpStatus;

// 所属模块：【后端公共边界 / HTTP 接口层】类型「ErrorCode」。
// 类型职责：限定错误代码允许出现的状态值；本类型显式提供 「ErrorCode」、「httpStatus」。
// 协作关系：主要由 「SecurityFailureWriter.write」 使用。
// 边界意义：公共组件不得暗含具体案件裁决规则
// Java 语法：enum 把允许值限制为固定常量，switch 可穷举状态分支。
public enum ErrorCode {
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    CASE_NOT_FOUND(HttpStatus.NOT_FOUND),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND),
    EVIDENCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    CASE_STATUS_INVALID(HttpStatus.CONFLICT),
    CASE_DUPLICATED(HttpStatus.CONFLICT),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
    EVIDENCE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    EVIDENCE_PARSE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    AGENT_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    AGENT_OUTPUT_SCHEMA_INVALID(HttpStatus.INTERNAL_SERVER_ERROR),
    WORKFLOW_START_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    WORKFLOW_SIGNAL_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    APPROVAL_REQUIRED(HttpStatus.CONFLICT),
    TOOL_EXECUTION_DENIED(HttpStatus.FORBIDDEN),
    TOOL_EXECUTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    EXTERNAL_SERVICE_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),
    DATABASE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    // 所属模块：【后端公共边界 / HTTP 接口层】「ErrorCode.ErrorCode(HttpStatus)」。
    // 具体功能：「ErrorCode.ErrorCode(HttpStatus)」：使用 「httpStatus」(HttpStatus) 初始化「ErrorCode」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「ErrorCode.ErrorCode(HttpStatus)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「ErrorCode.ErrorCode(HttpStatus)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ErrorCode.ErrorCode(HttpStatus)」负责主链路中的“错误代码”；公共组件不得暗含具体案件裁决规则
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    // 所属模块：【后端公共边界 / HTTP 接口层】「ErrorCode.httpStatus()」。
    // 具体功能：「ErrorCode.httpStatus()」：构建http状态，最终返回「HttpStatus」。
    // 上游调用：「ErrorCode.httpStatus()」的上游调用点包括 「SecurityFailureWriter.write」。
    // 下游影响：「ErrorCode.httpStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「HttpStatus」交给调用方。
    // 系统意义：「ErrorCode.httpStatus()」负责主链路中的“http状态”；公共组件不得暗含具体案件裁决规则
    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
