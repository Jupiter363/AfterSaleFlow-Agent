/*
 * 所属模块：案件生命周期通知。
 * 文件职责：把通知能力暴露为经过认证与统一异常处理的 HTTP 接口。
 * 业务链路：核心入口/契约为 「list」、「unreadCount」、「markRead」、「markAllRead」、「dismiss」；根据案件阶段向用户、商家和平台人员投递站内通知并维护已读状态。
 * 关键边界：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
 */
package com.example.dispute.notification.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.application.NotificationView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 所属模块：【案件生命周期通知 / HTTP 接口层】类型「NotificationController」。
// 类型职责：把通知能力暴露为经过认证与统一异常处理的 HTTP 接口；本类型显式提供 「NotificationController」、「list」、「unreadCount」、「markRead」、「markAllRead」、「dismiss」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Validated
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;
    private final Clock clock;

    // 所属模块：【案件生命周期通知 / HTTP 接口层】「NotificationController.NotificationController(NotificationService,Clock)」。
    // 具体功能：「NotificationController.NotificationController(NotificationService,Clock)」：通过构造器接收 「service」(NotificationService)、「clock」(Clock) 并保存为「NotificationController」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「NotificationController.NotificationController(NotificationService,Clock)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「NotificationController.NotificationController(NotificationService,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「NotificationController.NotificationController(NotificationService,Clock)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public NotificationController(NotificationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    // 所属模块：【案件生命周期通知 / HTTP 接口层】「NotificationController.list(Authentication,HttpServletRequest)」。
    // 具体功能：「NotificationController.list(Authentication,HttpServletRequest)」：处理「GET /api/notifications」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.list」、「response」、「actor」，并返回「ApiResponse<List<NotificationView>>」。
    // 上游调用：「NotificationController.list(Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /api/notifications」HTTP 请求。
    // 下游影响：「NotificationController.list(Authentication,HttpServletRequest)」向下依次触达 「service.list」、「response」、「actor」；计算结果以「ApiResponse<List<NotificationView>>」交给调用方。
    // 系统意义：「NotificationController.list(Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping
    public ApiResponse<List<NotificationView>> list(
            Authentication authentication, HttpServletRequest request) {
        return response(
                service.list(actor(authentication)),
                request);
    }

    // 所属模块：【案件生命周期通知 / HTTP 接口层】「NotificationController.unreadCount(Authentication,HttpServletRequest)」。
    // 具体功能：「NotificationController.unreadCount(Authentication,HttpServletRequest)」：处理「GET /api/notifications/unread-count」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.unreadCount」、「response」、「actor」，并返回「ApiResponse<UnreadCountView>」。
    // 上游调用：「NotificationController.unreadCount(Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「GET /api/notifications/unread-count」HTTP 请求。
    // 下游影响：「NotificationController.unreadCount(Authentication,HttpServletRequest)」向下依次触达 「service.unreadCount」、「response」、「actor」；计算结果以「ApiResponse<UnreadCountView>」交给调用方。
    // 系统意义：「NotificationController.unreadCount(Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountView> unreadCount(
            Authentication authentication, HttpServletRequest request) {
        return response(
                new UnreadCountView(service.unreadCount(actor(authentication))),
                request);
    }

    // 所属模块：【案件生命周期通知 / HTTP 接口层】「NotificationController.markRead(String,Authentication,HttpServletRequest)」。
    // 具体功能：「NotificationController.markRead(String,Authentication,HttpServletRequest)」：处理「POST /api/notifications」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.markRead」、「response」、「actor」，并返回「ApiResponse<NotificationView>」。
    // 上游调用：「NotificationController.markRead(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /api/notifications」HTTP 请求。
    // 下游影响：「NotificationController.markRead(String,Authentication,HttpServletRequest)」向下依次触达 「service.markRead」、「response」、「actor」；计算结果以「ApiResponse<NotificationView>」交给调用方。
    // 系统意义：「NotificationController.markRead(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/{notificationId}/read")
    public ApiResponse<NotificationView> markRead(
            @PathVariable
                    @Pattern(regexp = "NOTICE_[A-Za-z0-9]{1,58}")
                    String notificationId,
            Authentication authentication,
            HttpServletRequest request) {
        return response(
                service.markRead(notificationId, actor(authentication)),
                request);
    }

    // 所属模块：【案件生命周期通知 / HTTP 接口层】「NotificationController.markAllRead(Authentication,HttpServletRequest)」。
    // 具体功能：「NotificationController.markAllRead(Authentication,HttpServletRequest)」：处理「POST /api/notifications/read-all」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.markAllRead」、「response」、「actor」，并返回「ApiResponse<MarkedCountView>」。
    // 上游调用：「NotificationController.markAllRead(Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「POST /api/notifications/read-all」HTTP 请求。
    // 下游影响：「NotificationController.markAllRead(Authentication,HttpServletRequest)」向下依次触达 「service.markAllRead」、「response」、「actor」；计算结果以「ApiResponse<MarkedCountView>」交给调用方。
    // 系统意义：「NotificationController.markAllRead(Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @PostMapping("/read-all")
    public ApiResponse<MarkedCountView> markAllRead(
            Authentication authentication, HttpServletRequest request) {
        return response(
                new MarkedCountView(service.markAllRead(actor(authentication))),
                request);
    }

    // 所属模块：【案件生命周期通知 / HTTP 接口层】「NotificationController.dismiss(String,Authentication,HttpServletRequest)」。
    // 具体功能：「NotificationController.dismiss(String,Authentication,HttpServletRequest)」：处理「DELETE /api/notifications」请求，把路径、查询参数和认证主体转换为应用层调用，主要委托 「service.dismiss」、「actor」、「response」，并返回「ApiResponse<DeletedNotificationView>」。
    // 上游调用：「NotificationController.dismiss(String,Authentication,HttpServletRequest)」的上游是携带认证信息与 Trace/Request ID 的「DELETE /api/notifications」HTTP 请求。
    // 下游影响：「NotificationController.dismiss(String,Authentication,HttpServletRequest)」向下依次触达 「service.dismiss」、「actor」、「response」；计算结果以「ApiResponse<DeletedNotificationView>」交给调用方。
    // 系统意义：「NotificationController.dismiss(String,Authentication,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    @DeleteMapping("/{notificationId}")
    public ApiResponse<DeletedNotificationView> dismiss(
            @PathVariable
                    @Pattern(regexp = "NOTICE_[A-Za-z0-9]{1,58}")
                    String notificationId,
            Authentication authentication,
            HttpServletRequest request) {
        service.dismiss(notificationId, actor(authentication));
        return response(
                new DeletedNotificationView(notificationId, true),
                request);
    }

    // 所属模块：【案件生命周期通知 / HTTP 接口层】「NotificationController.response(T,HttpServletRequest)」。
    // 具体功能：「NotificationController.response(T,HttpServletRequest)」：构建响应；实际协作者为 「ApiResponse.success」、「correlationId」，最终返回「ApiResponse<T>」。
    // 上游调用：「NotificationController.response(T,HttpServletRequest)」的上游调用点包括 「NotificationController.list」、「NotificationController.unreadCount」、「NotificationController.markRead」、「NotificationController.markAllRead」。
    // 下游影响：「NotificationController.response(T,HttpServletRequest)」向下依次触达 「ApiResponse.success」、「correlationId」；计算结果以「ApiResponse<T>」交给调用方。
    // 系统意义：「NotificationController.response(T,HttpServletRequest)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private <T> ApiResponse<T> response(T data, HttpServletRequest request) {
        String traceId = correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId = correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.success(data, requestId, traceId, Instant.now(clock));
    }

    // 所属模块：【案件生命周期通知 / HTTP 接口层】「NotificationController.actor(Authentication)」。
    // 具体功能：「NotificationController.actor(Authentication)」：解析操作者Authenticated操作者；实际协作者为 「authentication.getPrincipal」，最终返回「AuthenticatedActor」。
    // 上游调用：「NotificationController.actor(Authentication)」的上游调用点包括 「NotificationController.list」、「NotificationController.unreadCount」、「NotificationController.markRead」、「NotificationController.markAllRead」。
    // 下游影响：「NotificationController.actor(Authentication)」向下依次触达 「authentication.getPrincipal」；计算结果以「AuthenticatedActor」交给调用方。
    // 系统意义：「NotificationController.actor(Authentication)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }

    // 所属模块：【案件生命周期通知 / HTTP 接口层】「NotificationController.correlationId(HttpServletRequest,String)」。
    // 具体功能：「NotificationController.correlationId(HttpServletRequest,String)」：读取标识；实际协作者为 「request.getAttribute」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「NotificationController.correlationId(HttpServletRequest,String)」的上游调用点包括 「NotificationController.response」。
    // 下游影响：「NotificationController.correlationId(HttpServletRequest,String)」向下依次触达 「request.getAttribute」；计算结果以「String」交给调用方。
    // 系统意义：「NotificationController.correlationId(HttpServletRequest,String)」是外部请求进入业务事实源的边界，必须先完成身份/参数校验，再由应用服务决定事务和权限。
    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }

    // 所属模块：【案件生命周期通知 / HTTP 接口层】类型「UnreadCountView」。
    // 类型职责：定义未读数量跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record UnreadCountView(long unreadCount) {}

    // 所属模块：【案件生命周期通知 / HTTP 接口层】类型「MarkedCountView」。
    // 类型职责：定义Marked数量跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record MarkedCountView(long markedCount) {}

    // 所属模块：【案件生命周期通知 / HTTP 接口层】类型「DeletedNotificationView」。
    // 类型职责：定义Deleted通知跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record DeletedNotificationView(String notificationId, boolean deleted) {}
}
