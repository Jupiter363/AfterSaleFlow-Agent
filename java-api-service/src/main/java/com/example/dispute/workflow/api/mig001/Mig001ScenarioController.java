package com.example.dispute.workflow.api.mig001;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@Profile("mig001-driver")
@ConditionalOnProperty(
        prefix = "app.orchestration",
        name = "mig001-driver-enabled",
        havingValue = "true")
@RequestMapping("/internal/orchestration/mig001/scenarios")
public class Mig001ScenarioController {

    private final Mig001ScenarioService service;
    private final Clock clock;

    public Mig001ScenarioController(Mig001ScenarioService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Mig001ScenarioView>> create(
            @Valid @RequestBody CreateMig001ScenarioRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String traceId = correlationId(servletRequest, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId = correlationId(servletRequest, TraceIdFilter.REQUEST_ATTRIBUTE);
        Mig001ScenarioView scenario =
                service.create(
                        request.scenarioId(),
                        (AuthenticatedActor) authentication.getPrincipal(),
                        traceId,
                        requestId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(scenario, requestId, traceId, Instant.now(clock)));
    }

    @GetMapping("/{caseId}")
    public ApiResponse<Mig001ScenarioView> status(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        String traceId = correlationId(servletRequest, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId = correlationId(servletRequest, TraceIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.success(
                service.status(
                        caseId, (AuthenticatedActor) authentication.getPrincipal()),
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
