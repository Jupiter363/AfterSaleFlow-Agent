/*
 * 所属模块：身份鉴权与运行配置。
 * 文件职责：把安全失败写入器转换为统一且不泄露内部细节的响应。
 * 业务链路：核心入口/契约为 「write」；解析请求身份、限制角色权限并装配基础设施客户端和 Spring Bean。
 * 关键边界：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
 */
package com.example.dispute.config;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.trace.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

// 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「SecurityFailureWriter」。
// 类型职责：把安全失败写入器转换为统一且不泄露内部细节的响应；本类型显式提供 「SecurityFailureWriter」、「write」、「correlationId」。
// 协作关系：主要由 「JsonAccessDeniedHandler.handle」、「JsonAuthenticationEntryPoint.commence」 使用。
// 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public final class SecurityFailureWriter {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「SecurityFailureWriter.SecurityFailureWriter(ObjectMapper,Clock)」。
    // 具体功能：「SecurityFailureWriter.SecurityFailureWriter(ObjectMapper,Clock)」：通过构造器接收 「objectMapper」(ObjectMapper)、「clock」(Clock) 并保存为「SecurityFailureWriter」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「SecurityFailureWriter.SecurityFailureWriter(ObjectMapper,Clock)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供。
    // 下游影响：「SecurityFailureWriter.SecurityFailureWriter(ObjectMapper,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SecurityFailureWriter.SecurityFailureWriter(ObjectMapper,Clock)」负责主链路中的“安全失败写入器”；调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public SecurityFailureWriter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「SecurityFailureWriter.write(HttpServletRequest,HttpServletResponse,ErrorCode,String)」。
    // 具体功能：「SecurityFailureWriter.write(HttpServletRequest,HttpServletResponse,ErrorCode,String)」：写入安全失败写入器；实际协作者为 「ApiResponse.failure」、「response.setStatus」、「errorCode.httpStatus」、「response.setContentType」；处理的关键状态/协议值包括 「UTF-8」、「REQ_」、「TRACE_」，最终返回「void」。
    // 上游调用：「SecurityFailureWriter.write(HttpServletRequest,HttpServletResponse,ErrorCode,String)」的上游调用点包括 「JsonAccessDeniedHandler.handle」、「JsonAuthenticationEntryPoint.commence」。
    // 下游影响：「SecurityFailureWriter.write(HttpServletRequest,HttpServletResponse,ErrorCode,String)」向下依次触达 「ApiResponse.failure」、「response.setStatus」、「errorCode.httpStatus」、「response.setContentType」。
    // 系统意义：「SecurityFailureWriter.write(HttpServletRequest,HttpServletResponse,ErrorCode,String)」负责主链路中的“安全失败写入器”；调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    void write(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode errorCode,
            String message)
            throws IOException {
        response.setStatus(errorCode.httpStatus().value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        ApiResponse<Void> body =
                ApiResponse.failure(
                        errorCode,
                        message,
                        Map.of(),
                        correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE, "REQ_"),
                        correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE, "TRACE_"),
                        Instant.now(clock));
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「SecurityFailureWriter.correlationId(HttpServletRequest,String,String)」。
    // 具体功能：「SecurityFailureWriter.correlationId(HttpServletRequest,String,String)」：读取标识；实际协作者为 「UUID.randomUUID」、「request.getAttribute」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「SecurityFailureWriter.correlationId(HttpServletRequest,String,String)」的上游调用点包括 「SecurityFailureWriter.write」。
    // 下游影响：「SecurityFailureWriter.correlationId(HttpServletRequest,String,String)」向下依次触达 「UUID.randomUUID」、「request.getAttribute」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「SecurityFailureWriter.correlationId(HttpServletRequest,String,String)」负责主链路中的“标识”；调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    private static String correlationId(
            HttpServletRequest request, String attribute, String prefix) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String identifier && !identifier.isBlank()) {
            return identifier;
        }
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
