package com.example.dispute.casecore.api;

import com.example.dispute.casecore.application.DemoCasePurgeService;
import com.example.dispute.casecore.application.DemoCasePurgeView;
import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/disputes")
public class DemoCasePurgeController {

    private final DemoCasePurgeService service;
    private final Clock clock;

    public DemoCasePurgeController(DemoCasePurgeService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @DeleteMapping("/{caseId}")
    public ApiResponse<DemoCasePurgeView> purge(
            @PathVariable
                    @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}")
                    String caseId,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String requestId =
                correlationId(servletRequest, TraceIdFilter.REQUEST_ATTRIBUTE);
        String traceId =
                correlationId(servletRequest, TraceIdFilter.TRACE_ATTRIBUTE);
        DemoCasePurgeView result =
                service.purge(
                        caseId,
                        (AuthenticatedActor) authentication.getPrincipal());
        return ApiResponse.success(result, requestId, traceId, Instant.now(clock));
    }

    private static String correlationId(
            HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
