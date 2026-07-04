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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;
    private final Clock clock;

    public NotificationController(NotificationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<List<NotificationView>> list(
            Authentication authentication, HttpServletRequest request) {
        return response(
                service.list(actor(authentication)),
                request);
    }

    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountView> unreadCount(
            Authentication authentication, HttpServletRequest request) {
        return response(
                new UnreadCountView(service.unreadCount(actor(authentication))),
                request);
    }

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

    @PostMapping("/read-all")
    public ApiResponse<MarkedCountView> markAllRead(
            Authentication authentication, HttpServletRequest request) {
        return response(
                new MarkedCountView(service.markAllRead(actor(authentication))),
                request);
    }

    private <T> ApiResponse<T> response(T data, HttpServletRequest request) {
        String traceId = correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId = correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.success(data, requestId, traceId, Instant.now(clock));
    }

    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }

    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }

    public record UnreadCountView(long unreadCount) {}

    public record MarkedCountView(long markedCount) {}
}
