package com.example.dispute.policy.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.policy.application.PolicyApplicationService;
import com.example.dispute.policy.application.PolicyRuleView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/reviews/policies")
public class PolicyController {

    private final PolicyApplicationService service;
    private final Clock clock;

    public PolicyController(PolicyApplicationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<List<PolicyRuleView>> findActive(
            @RequestParam(required = false)
                    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{1,63}")
                    String scope,
            HttpServletRequest request) {
        String traceId = correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId = correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.success(
                service.findActive(scope),
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
