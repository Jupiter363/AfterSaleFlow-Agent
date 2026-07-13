/*
 * 所属模块：身份鉴权与运行配置。
 * 文件职责：把JSON访问拒绝结果处理器转换为统一且不泄露内部细节的响应。
 * 业务链路：核心入口/契约为 「handle」；解析请求身份、限制角色权限并装配基础设施客户端和 Spring Bean。
 * 关键边界：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
 */
package com.example.dispute.config;

import com.example.dispute.common.api.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

// 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「JsonAccessDeniedHandler」。
// 类型职责：把JSON访问拒绝结果处理器转换为统一且不泄露内部细节的响应；本类型显式提供 「JsonAccessDeniedHandler」、「handle」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public final class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityFailureWriter failureWriter;

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「JsonAccessDeniedHandler.JsonAccessDeniedHandler(SecurityFailureWriter)」。
    // 具体功能：「JsonAccessDeniedHandler.JsonAccessDeniedHandler(SecurityFailureWriter)」：通过构造器接收 「failureWriter」(SecurityFailureWriter) 并保存为「JsonAccessDeniedHandler」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「JsonAccessDeniedHandler.JsonAccessDeniedHandler(SecurityFailureWriter)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供。
    // 下游影响：「JsonAccessDeniedHandler.JsonAccessDeniedHandler(SecurityFailureWriter)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「JsonAccessDeniedHandler.JsonAccessDeniedHandler(SecurityFailureWriter)」负责主链路中的“JSON访问拒绝结果处理器”；调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public JsonAccessDeniedHandler(SecurityFailureWriter failureWriter) {
        this.failureWriter = failureWriter;
    }

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「JsonAccessDeniedHandler.handle(HttpServletRequest,HttpServletResponse,AccessDeniedException)」。
    // 具体功能：「JsonAccessDeniedHandler.handle(HttpServletRequest,HttpServletResponse,AccessDeniedException)」：响应JSON访问拒绝结果处理器；实际协作者为 「failureWriter.write」，最终返回「void」。
    // 上游调用：「JsonAccessDeniedHandler.handle(HttpServletRequest,HttpServletResponse,AccessDeniedException)」由使用「JsonAccessDeniedHandler」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「JsonAccessDeniedHandler.handle(HttpServletRequest,HttpServletResponse,AccessDeniedException)」向下依次触达 「failureWriter.write」。
    // 系统意义：「JsonAccessDeniedHandler.handle(HttpServletRequest,HttpServletResponse,AccessDeniedException)」负责主链路中的“JSON访问拒绝结果处理器”；调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException, ServletException {
        failureWriter.write(request, response, ErrorCode.FORBIDDEN, "access denied");
    }
}
