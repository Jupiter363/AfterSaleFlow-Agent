package com.example.dispute.room.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.room.application.RoomTurnMemoryQueryService;
import com.example.dispute.room.application.RoomTurnMemoryView;
import com.example.dispute.room.domain.RoomType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/disputes/{caseId}/rooms/{roomType}/turn-memory")
public class RoomTurnMemoryController {

    private final RoomTurnMemoryQueryService service;
    private final Clock clock;

    public RoomTurnMemoryController(RoomTurnMemoryQueryService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping("/latest")
    public ApiResponse<RoomTurnMemoryView> latest(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @PathVariable RoomType roomType,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String traceId = correlationId(servletRequest, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId = correlationId(servletRequest, TraceIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.success(
                service.latestAgentMemory(caseId, roomType, actor(authentication)).orElse(null),
                requestId,
                traceId,
                Instant.now(clock));
    }

    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }

    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) return id;
        throw new IllegalStateException("correlation id filter did not run");
    }
}
