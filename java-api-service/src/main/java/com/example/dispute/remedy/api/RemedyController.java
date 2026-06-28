package com.example.dispute.remedy.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.remedy.application.RemedyApplicationService;
import com.example.dispute.remedy.application.RemedyPlanView;
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
@RequestMapping("/api/v1/cases/{caseId}/remedy-plan")
public class RemedyController {

    private final RemedyApplicationService service;
    private final Clock clock;

    public RemedyController(RemedyApplicationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<RemedyPlanView> get(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9_]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return ApiResponse.success(
                service.get(
                        caseId,
                        (AuthenticatedActor) authentication.getPrincipal()),
                (String) request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE),
                (String) request.getAttribute(TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }
}
