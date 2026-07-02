package com.example.dispute.room.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.room.application.IntakeConfirmationView;
import com.example.dispute.room.application.IntakeRoomService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/disputes/{caseId}/intake")
public class IntakeRoomController {

    private final IntakeRoomService service;
    private final Clock clock;

    public IntakeRoomController(IntakeRoomService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping("/confirm")
    public ApiResponse<IntakeConfirmationView> confirm(
            @PathVariable
                    @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}")
                    String caseId,
            @Valid @RequestBody IntakeConfirmationRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String traceId = correlationId(servletRequest, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId = correlationId(servletRequest, TraceIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.success(
                service.confirm(
                        caseId,
                        (AuthenticatedActor) authentication.getPrincipal(),
                        request.toCommand()),
                requestId,
                traceId,
                Instant.now(clock));
    }

    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
