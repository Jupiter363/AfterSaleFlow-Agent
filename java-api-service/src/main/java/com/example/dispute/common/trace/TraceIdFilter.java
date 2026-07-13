/*
 * 所属模块：后端公共边界。
 * 文件职责：在 Servlet 请求进入控制器前处理链路标识标识过滤器。
 * 业务链路：核心入口/契约为 「doFilterInternal」；统一 API 包装、异常映射、链路标识、审计端口与事务后副作用。
 * 关键边界：公共组件不得暗含具体案件裁决规则
 */
package com.example.dispute.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// 所属模块：【后端公共边界 / 核心业务层】类型「TraceIdFilter」。
// 类型职责：在 Servlet 请求进入控制器前处理链路标识标识过滤器；本类型显式提供 「doFilterInternal」、「correlationId」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：公共组件不得暗含具体案件裁决规则
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String REQUEST_HEADER = "X-Request-Id";
    public static final String TRACE_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";
    public static final String REQUEST_ATTRIBUTE = TraceIdFilter.class.getName() + ".requestId";
    public static final String MDC_TRACE_KEY = "trace_id";
    public static final String MDC_REQUEST_KEY = "request_id";

    private static final Pattern SAFE_CORRELATION_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    // 所属模块：【后端公共边界 / 核心业务层】「TraceIdFilter.doFilterInternal(HttpServletRequest,HttpServletResponse,FilterChain)」。
    // 具体功能：「TraceIdFilter.doFilterInternal(HttpServletRequest,HttpServletResponse,FilterChain)」：执行do过滤器内部；实际协作者为 「request.getHeader」、「request.setAttribute」、「response.setHeader」、「filterChain.doFilter」；处理的关键状态/协议值包括 「TRACE_」、「REQ_」，最终返回「void」。
    // 上游调用：「TraceIdFilter.doFilterInternal(HttpServletRequest,HttpServletResponse,FilterChain)」由使用「TraceIdFilter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「TraceIdFilter.doFilterInternal(HttpServletRequest,HttpServletResponse,FilterChain)」向下依次触达 「request.getHeader」、「request.setAttribute」、「response.setHeader」、「filterChain.doFilter」。
    // 系统意义：「TraceIdFilter.doFilterInternal(HttpServletRequest,HttpServletResponse,FilterChain)」负责主链路中的“do过滤器内部”；公共组件不得暗含具体案件裁决规则
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = correlationId(request.getHeader(TRACE_HEADER), "TRACE_");
        String requestId = correlationId(request.getHeader(REQUEST_HEADER), "REQ_");

        request.setAttribute(TRACE_ATTRIBUTE, traceId);
        request.setAttribute(REQUEST_ATTRIBUTE, requestId);
        response.setHeader(TRACE_HEADER, traceId);
        response.setHeader(REQUEST_HEADER, requestId);
        MDC.put(MDC_TRACE_KEY, traceId);
        MDC.put(MDC_REQUEST_KEY, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_KEY);
            MDC.remove(MDC_REQUEST_KEY);
        }
    }

    // 所属模块：【后端公共边界 / 核心业务层】「TraceIdFilter.correlationId(String,String)」。
    // 具体功能：「TraceIdFilter.correlationId(String,String)」：读取标识；实际协作者为 「SAFE_CORRELATION_ID.matcher」、「UUID.randomUUID」、「SAFE_CORRELATION_ID.matcher(candidate).matches」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「TraceIdFilter.correlationId(String,String)」的上游调用点包括 「TraceIdFilter.doFilterInternal」。
    // 下游影响：「TraceIdFilter.correlationId(String,String)」向下依次触达 「SAFE_CORRELATION_ID.matcher」、「UUID.randomUUID」、「SAFE_CORRELATION_ID.matcher(candidate).matches」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「TraceIdFilter.correlationId(String,String)」负责主链路中的“标识”；公共组件不得暗含具体案件裁决规则
    private static String correlationId(String candidate, String prefix) {
        if (candidate != null && SAFE_CORRELATION_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
