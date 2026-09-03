/*
 * 所属模块：后端公共边界。
 * 文件职责：定义Api响应跨层传递时使用的不可变数据契约。
 * 业务链路：核心入口/契约为 「success」、「failure」；统一 API 包装、异常映射、链路标识、审计端口与事务后副作用。
 * 关键边界：公共组件不得暗含具体案件裁决规则
 */
package com.example.dispute.common.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Map;

// 所属模块：【后端公共边界 / HTTP 接口层】类型「ApiResponse」。
// 类型职责：定义Api响应跨层传递时使用的不可变数据契约；本类型显式提供 「success」、「failure」。
// 协作关系：主要由 「AgentRunController.get」、「AgentRunController.replay」、「AuditController.list」、「CaseAgentRunController.active」 使用。
// 边界意义：公共组件不得暗含具体案件裁决规则
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        Map<String, Object> details,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("trace_id") String traceId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp) {

    // 所属模块：【后端公共边界 / HTTP 接口层】「ApiResponse.success(T,String,String,Instant)」。
    // 具体功能：「ApiResponse.success(T,String,String,Instant)」：构建成功；处理的关键状态/协议值包括 「SUCCESS」、「success」，最终返回「ApiResponse<T>」。
    // 上游调用：「ApiResponse.success(T,String,String,Instant)」的上游调用点包括 「AgentRunController.get」、「AgentRunController.replay」、「CaseAgentRunController.active」、「InternalAgentRunController.start」。
    // 下游影响：「ApiResponse.success(T,String,String,Instant)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ApiResponse<T>」交给调用方。
    // 系统意义：「ApiResponse.success(T,String,String,Instant)」负责主链路中的“成功”；公共组件不得暗含具体案件裁决规则
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    public static <T> ApiResponse<T> success(
            T data, String requestId, String traceId, Instant timestamp) {
        return new ApiResponse<>(
                true, "SUCCESS", "success", data, null, requestId, traceId, timestamp);
    }

    // 所属模块：【后端公共边界 / HTTP 接口层】「ApiResponse.failure(ErrorCode,String,Map,String,String,Instant)」。
    // 具体功能：「ApiResponse.failure(ErrorCode,String,Map,String,String,Instant)」：标记失败失败，最终返回「ApiResponse<T>」。
    // 上游调用：「ApiResponse.failure(ErrorCode,String,Map,String,String,Instant)」的上游调用点包括 「GlobalExceptionHandler.failure」、「SecurityFailureWriter.write」、「ApiResponseTest.failureCarriesStableErrorCodeAndDetails」。
    // 下游影响：「ApiResponse.failure(ErrorCode,String,Map,String,String,Instant)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ApiResponse<T>」交给调用方。
    // 系统意义：「ApiResponse.failure(ErrorCode,String,Map,String,String,Instant)」负责主链路中的“失败”；公共组件不得暗含具体案件裁决规则
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    public static <T> ApiResponse<T> failure(
            ErrorCode errorCode,
            String message,
            Map<String, Object> details,
            String requestId,
            String traceId,
            Instant timestamp) {
        return new ApiResponse<>(
                false,
                errorCode.name(),
                message,
                null,
                Map.copyOf(details),
                requestId,
                traceId,
                timestamp);
    }
}
