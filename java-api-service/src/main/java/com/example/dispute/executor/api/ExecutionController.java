package com.example.dispute.executor.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.executor.application.ActionRecordView;
import com.example.dispute.executor.application.ExecutionBatchView;
import com.example.dispute.executor.application.ToolExecutorService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/disputes/{caseId}")
public class ExecutionController {

    private final ToolExecutorService service;
    private final Clock clock;

    public ExecutionController(ToolExecutorService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping("/execution/execute")
    public ApiResponse<ExecutionBatchView> execute(
            @PathVariable @NotBlank String caseId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                service.executeApprovedActions(
                        caseId, idempotencyKey, actor(authentication)),
                request);
    }

    @GetMapping("/actions")
    public ApiResponse<List<ActionRecordView>> actions(
            @PathVariable @NotBlank String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(service.actions(caseId, actor(authentication)), request);
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(
                data,
                (String) request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE),
                (String) request.getAttribute(TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }
}
