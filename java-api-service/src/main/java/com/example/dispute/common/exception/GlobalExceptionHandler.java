/*
 * 所属模块：后端公共边界。
 * 文件职责：把Global处理器转换为统一且不泄露内部细节的响应。
 * 业务链路：核心入口/契约为 「handleBusinessException」、「handleMethodArgumentNotValid」、「handleInvalidRequest」、「handleNoResourceFound」、「handleDisconnectedAsyncRequest」、「handleSecurityException」；统一 API 包装、异常映射、链路标识、审计端口与事务后副作用。
 * 关键边界：公共组件不得暗含具体案件裁决规则
 */
package com.example.dispute.common.exception;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.trace.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// 所属模块：【后端公共边界 / 核心业务层】类型「GlobalExceptionHandler」。
// 类型职责：把Global处理器转换为统一且不泄露内部细节的响应；本类型显式提供 「GlobalExceptionHandler」、「handleBusinessException」、「handleMethodArgumentNotValid」、「handleInvalidRequest」、「handleNoResourceFound」、「handleDisconnectedAsyncRequest」。
// 协作关系：主要由 「GlobalExceptionHandlerTest.mapsAnUnknownApiRouteToResourceNotFound」、「GlobalExceptionHandlerTest.setUp」 使用。
// 边界意义：公共组件不得暗含具体案件裁决规则
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    // 所属模块：【后端公共边界 / 核心业务层】「GlobalExceptionHandler.GlobalExceptionHandler(Clock)」。
    // 具体功能：「GlobalExceptionHandler.GlobalExceptionHandler(Clock)」：使用 「clock」(Clock) 初始化「GlobalExceptionHandler」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「GlobalExceptionHandler.GlobalExceptionHandler(Clock)」的上游创建点包括 「GlobalExceptionHandlerTest.setUp」。
    // 下游影响：「GlobalExceptionHandler.GlobalExceptionHandler(Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「GlobalExceptionHandler.GlobalExceptionHandler(Clock)」负责主链路中的“Global异常处理器”；公共组件不得暗含具体案件裁决规则
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    // 所属模块：【后端公共边界 / 核心业务层】「GlobalExceptionHandler.handleBusinessException(BusinessException,HttpServletRequest)」。
    // 具体功能：「GlobalExceptionHandler.handleBusinessException(BusinessException,HttpServletRequest)」：响应Business异常；实际协作者为 「ResponseEntity.status」、「exception.errorCode」、「exception.details」、「errorCode.httpStatus」，最终返回「ResponseEntity<ApiResponse<Void>>」。
    // 上游调用：「GlobalExceptionHandler.handleBusinessException(BusinessException,HttpServletRequest)」由使用「GlobalExceptionHandler」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「GlobalExceptionHandler.handleBusinessException(BusinessException,HttpServletRequest)」向下依次触达 「ResponseEntity.status」、「exception.errorCode」、「exception.details」、「errorCode.httpStatus」；计算结果以「ResponseEntity<ApiResponse<Void>>」交给调用方。
    // 系统意义：「GlobalExceptionHandler.handleBusinessException(BusinessException,HttpServletRequest)」负责主链路中的“Business异常”；公共组件不得暗含具体案件裁决规则
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception, HttpServletRequest request) {
        ErrorCode errorCode = exception.errorCode();
        ApiResponse<Void> body =
                failure(
                        errorCode,
                        exception.getMessage(),
                        exception.details(),
                        request);
        return ResponseEntity.status(errorCode.httpStatus()).body(body);
    }

    // 所属模块：【后端公共边界 / 核心业务层】「GlobalExceptionHandler.handleMethodArgumentNotValid(MethodArgumentNotValidException,HttpServletRequest)」。
    // 具体功能：「GlobalExceptionHandler.handleMethodArgumentNotValid(MethodArgumentNotValidException,HttpServletRequest)」：响应MethodArgument不Valid；实际协作者为 「ResponseEntity.status」、「exception.getBindingResult」、「fields.putIfAbsent」、「error.getField」；处理的关键状态/协议值包括 「fields」，最终返回「ResponseEntity<ApiResponse<Void>>」。
    // 上游调用：「GlobalExceptionHandler.handleMethodArgumentNotValid(MethodArgumentNotValidException,HttpServletRequest)」由使用「GlobalExceptionHandler」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「GlobalExceptionHandler.handleMethodArgumentNotValid(MethodArgumentNotValidException,HttpServletRequest)」向下依次触达 「ResponseEntity.status」、「exception.getBindingResult」、「fields.putIfAbsent」、「error.getField」；计算结果以「ResponseEntity<ApiResponse<Void>>」交给调用方。
    // 系统意义：「GlobalExceptionHandler.handleMethodArgumentNotValid(MethodArgumentNotValidException,HttpServletRequest)」负责主链路中的“MethodArgument不Valid”；公共组件不得暗含具体案件裁决规则
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception
                .getBindingResult()
                .getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ErrorCode errorCode = ErrorCode.INVALID_ARGUMENT;
        ApiResponse<Void> body =
                failure(
                        errorCode,
                        "request validation failed",
                        Map.of("fields", fields),
                        request);
        return ResponseEntity.status(errorCode.httpStatus()).body(body);
    }

    // 所属模块：【后端公共边界 / 核心业务层】「GlobalExceptionHandler.handleInvalidRequest(Exception,HttpServletRequest)」。
    // 具体功能：「GlobalExceptionHandler.handleInvalidRequest(Exception,HttpServletRequest)」：响应非法请求；实际协作者为 「ResponseEntity.status」、「errorCode.httpStatus」、「failure」、「safeReason」；处理的关键状态/协议值包括 「reason」，最终返回「ResponseEntity<ApiResponse<Void>>」。
    // 上游调用：「GlobalExceptionHandler.handleInvalidRequest(Exception,HttpServletRequest)」由使用「GlobalExceptionHandler」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「GlobalExceptionHandler.handleInvalidRequest(Exception,HttpServletRequest)」向下依次触达 「ResponseEntity.status」、「errorCode.httpStatus」、「failure」、「safeReason」；计算结果以「ResponseEntity<ApiResponse<Void>>」交给调用方。
    // 系统意义：「GlobalExceptionHandler.handleInvalidRequest(Exception,HttpServletRequest)」负责主链路中的“非法请求”；公共组件不得暗含具体案件裁决规则
    @ExceptionHandler({
        MissingRequestHeaderException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(
            Exception exception, HttpServletRequest request) {
        ErrorCode errorCode = ErrorCode.INVALID_ARGUMENT;
        ApiResponse<Void> body =
                failure(
                        errorCode,
                        "request validation failed",
                        Map.of("reason", safeReason(exception)),
                        request);
        return ResponseEntity.status(errorCode.httpStatus()).body(body);
    }

    // 所属模块：【后端公共边界 / 核心业务层】「GlobalExceptionHandler.handleNoResourceFound(NoResourceFoundException,HttpServletRequest)」。
    // 具体功能：「GlobalExceptionHandler.handleNoResourceFound(NoResourceFoundException,HttpServletRequest)」：响应编号ResourceFound；实际协作者为 「ResponseEntity.status」、「exception.getResourcePath」、「errorCode.httpStatus」、「failure」；处理的关键状态/协议值包括 「path」，最终返回「ResponseEntity<ApiResponse<Void>>」。
    // 上游调用：「GlobalExceptionHandler.handleNoResourceFound(NoResourceFoundException,HttpServletRequest)」的上游调用点包括 「GlobalExceptionHandlerTest.mapsAnUnknownApiRouteToResourceNotFound」。
    // 下游影响：「GlobalExceptionHandler.handleNoResourceFound(NoResourceFoundException,HttpServletRequest)」向下依次触达 「ResponseEntity.status」、「exception.getResourcePath」、「errorCode.httpStatus」、「failure」；计算结果以「ResponseEntity<ApiResponse<Void>>」交给调用方。
    // 系统意义：「GlobalExceptionHandler.handleNoResourceFound(NoResourceFoundException,HttpServletRequest)」负责主链路中的“编号ResourceFound”；公共组件不得暗含具体案件裁决规则
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(
            NoResourceFoundException exception,
            HttpServletRequest request) {
        ErrorCode errorCode = ErrorCode.RESOURCE_NOT_FOUND;
        ApiResponse<Void> body =
                failure(
                        errorCode,
                        "resource not found",
                        Map.of("path", exception.getResourcePath()),
                request);
        return ResponseEntity.status(errorCode.httpStatus()).body(body);
    }

    // 所属模块：【后端公共边界 / 核心业务层】「GlobalExceptionHandler.handleDisconnectedAsyncRequest(AsyncRequestNotUsableException)」。
    // 具体功能：「GlobalExceptionHandler.handleDisconnectedAsyncRequest(AsyncRequestNotUsableException)」：响应断开连接的订阅Async请求；实际协作者为 「LOGGER.debug」、「ResponseEntity.noContent」，最终返回「ResponseEntity<Void>」。
    // 上游调用：「GlobalExceptionHandler.handleDisconnectedAsyncRequest(AsyncRequestNotUsableException)」由使用「GlobalExceptionHandler」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「GlobalExceptionHandler.handleDisconnectedAsyncRequest(AsyncRequestNotUsableException)」向下依次触达 「LOGGER.debug」、「ResponseEntity.noContent」；计算结果以「ResponseEntity<Void>」交给调用方。
    // 系统意义：「GlobalExceptionHandler.handleDisconnectedAsyncRequest(AsyncRequestNotUsableException)」负责主链路中的“断开连接的订阅Async请求”；公共组件不得暗含具体案件裁决规则
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<Void> handleDisconnectedAsyncRequest(
            AsyncRequestNotUsableException exception) {
        LOGGER.debug(
                "Async request stream ended before response could be written: exception_type={}",
                exception.getClass().getName());
        return ResponseEntity.noContent().build();
    }

    // 所属模块：【后端公共边界 / 核心业务层】「GlobalExceptionHandler.handleSecurityException(SecurityException,HttpServletRequest)」。
    // 具体功能：「GlobalExceptionHandler.handleSecurityException(SecurityException,HttpServletRequest)」：响应安全异常；实际协作者为 「ResponseEntity.status」、「errorCode.httpStatus」、「failure」，最终返回「ResponseEntity<ApiResponse<Void>>」。
    // 上游调用：「GlobalExceptionHandler.handleSecurityException(SecurityException,HttpServletRequest)」由使用「GlobalExceptionHandler」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「GlobalExceptionHandler.handleSecurityException(SecurityException,HttpServletRequest)」向下依次触达 「ResponseEntity.status」、「errorCode.httpStatus」、「failure」；计算结果以「ResponseEntity<ApiResponse<Void>>」交给调用方。
    // 系统意义：「GlobalExceptionHandler.handleSecurityException(SecurityException,HttpServletRequest)」负责主链路中的“安全异常”；公共组件不得暗含具体案件裁决规则
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleSecurityException(
            SecurityException exception, HttpServletRequest request) {
        ErrorCode errorCode = ErrorCode.FORBIDDEN;
        ApiResponse<Void> body =
                failure(errorCode, "access denied", Map.of(), request);
        return ResponseEntity.status(errorCode.httpStatus()).body(body);
    }

    // 所属模块：【后端公共边界 / 核心业务层】「GlobalExceptionHandler.handleUnexpectedException(Exception,HttpServletRequest)」。
    // 具体功能：「GlobalExceptionHandler.handleUnexpectedException(Exception,HttpServletRequest)」：响应Unexpected异常；实际协作者为 「LOGGER.error」、「ResponseEntity.status」、「errorCode.httpStatus」、「failure」，最终返回「ResponseEntity<ApiResponse<Void>>」。
    // 上游调用：「GlobalExceptionHandler.handleUnexpectedException(Exception,HttpServletRequest)」由使用「GlobalExceptionHandler」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「GlobalExceptionHandler.handleUnexpectedException(Exception,HttpServletRequest)」向下依次触达 「LOGGER.error」、「ResponseEntity.status」、「errorCode.httpStatus」、「failure」；计算结果以「ResponseEntity<ApiResponse<Void>>」交给调用方。
    // 系统意义：「GlobalExceptionHandler.handleUnexpectedException(Exception,HttpServletRequest)」负责主链路中的“Unexpected异常”；公共组件不得暗含具体案件裁决规则
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(
            Exception exception, HttpServletRequest request) {
        LOGGER.error(
                "Unhandled request failure: exception_type={}",
                exception.getClass().getName());
        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        ApiResponse<Void> body =
                failure(errorCode, "internal server error", Map.of(), request);
        return ResponseEntity.status(errorCode.httpStatus()).body(body);
    }

    // 所属模块：【后端公共边界 / 核心业务层】「GlobalExceptionHandler.failure(ErrorCode,String,Map,HttpServletRequest)」。
    // 具体功能：「GlobalExceptionHandler.failure(ErrorCode,String,Map,HttpServletRequest)」：标记失败失败；实际协作者为 「ApiResponse.failure」、「correlationId」；处理的关键状态/协议值包括 「REQ_」、「TRACE_」，最终返回「ApiResponse<Void>」。
    // 上游调用：「GlobalExceptionHandler.failure(ErrorCode,String,Map,HttpServletRequest)」的上游调用点包括 「GlobalExceptionHandler.handleBusinessException」、「GlobalExceptionHandler.handleMethodArgumentNotValid」、「GlobalExceptionHandler.handleInvalidRequest」、「GlobalExceptionHandler.handleNoResourceFound」。
    // 下游影响：「GlobalExceptionHandler.failure(ErrorCode,String,Map,HttpServletRequest)」向下依次触达 「ApiResponse.failure」、「correlationId」；计算结果以「ApiResponse<Void>」交给调用方。
    // 系统意义：「GlobalExceptionHandler.failure(ErrorCode,String,Map,HttpServletRequest)」负责主链路中的“失败”；公共组件不得暗含具体案件裁决规则
    private ApiResponse<Void> failure(
            ErrorCode errorCode,
            String message,
            Map<String, Object> details,
            HttpServletRequest request) {
        return ApiResponse.failure(
                errorCode,
                message,
                details,
                correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE, "REQ_"),
                correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE, "TRACE_"),
                Instant.now(clock));
    }

    // 所属模块：【后端公共边界 / 核心业务层】「GlobalExceptionHandler.correlationId(HttpServletRequest,String,String)」。
    // 具体功能：「GlobalExceptionHandler.correlationId(HttpServletRequest,String,String)」：读取标识；实际协作者为 「UUID.randomUUID」、「request.getAttribute」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「GlobalExceptionHandler.correlationId(HttpServletRequest,String,String)」的上游调用点包括 「GlobalExceptionHandler.failure」。
    // 下游影响：「GlobalExceptionHandler.correlationId(HttpServletRequest,String,String)」向下依次触达 「UUID.randomUUID」、「request.getAttribute」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「GlobalExceptionHandler.correlationId(HttpServletRequest,String,String)」负责主链路中的“标识”；公共组件不得暗含具体案件裁决规则
    private static String correlationId(
            HttpServletRequest request, String attributeName, String prefix) {
        Object value = request.getAttribute(attributeName);
        if (value instanceof String identifier && !identifier.isBlank()) {
            return identifier;
        }
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    // 所属模块：【后端公共边界 / 核心业务层】「GlobalExceptionHandler.safeReason(Exception)」。
    // 具体功能：「GlobalExceptionHandler.safeReason(Exception)」：生成安全值原因；实际协作者为 「missing.getHeaderName」，最终返回「String」。
    // 上游调用：「GlobalExceptionHandler.safeReason(Exception)」的上游调用点包括 「GlobalExceptionHandler.handleInvalidRequest」。
    // 下游影响：「GlobalExceptionHandler.safeReason(Exception)」向下依次触达 「missing.getHeaderName」；计算结果以「String」交给调用方。
    // 系统意义：「GlobalExceptionHandler.safeReason(Exception)」负责主链路中的“原因”；公共组件不得暗含具体案件裁决规则
    private static String safeReason(Exception exception) {
        if (exception instanceof MissingRequestHeaderException missing) {
            return "missing request header: " + missing.getHeaderName();
        }
        if (exception instanceof ConstraintViolationException) {
            return "request constraint violated";
        }
        if (exception instanceof IllegalArgumentException) {
            return exception.getMessage() == null
                    ? "invalid argument"
                    : exception.getMessage();
        }
        return "request body is unreadable";
    }
}
