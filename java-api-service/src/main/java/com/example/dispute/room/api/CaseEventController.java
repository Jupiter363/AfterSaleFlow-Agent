package com.example.dispute.room.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.room.application.CaseEventView;
import com.example.dispute.room.application.CaseEventService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Validated
@RestController
@RequestMapping("/api/disputes/{caseId}/events")
public class CaseEventController {

    private final CaseEventService service;
    private final Clock clock;

    public CaseEventController(CaseEventService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @RequestHeader(value = "Last-Event-ID", required = false) @Min(0)
                    Long lastEventId,
            @RequestParam(value = "last_event_id", required = false) @Min(0)
                    Long queryCursor,
            Authentication authentication) {
        long cursor =
                lastEventId != null
                        ? lastEventId
                        : queryCursor != null ? queryCursor : 0L;
        return service.subscribe(
                caseId,
                cursor,
                (AuthenticatedActor) authentication.getPrincipal());
    }

    @GetMapping("/replay")
    public ApiResponse<List<CaseEventView>> replay(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @RequestParam(value = "after_sequence", defaultValue = "0") @Min(0)
                    long afterSequence,
            Authentication authentication,
            HttpServletRequest request) {
        return ApiResponse.success(
                service.replay(
                        caseId,
                        afterSequence,
                        (AuthenticatedActor) authentication.getPrincipal()),
                correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String identifier && !identifier.isBlank()) {
            return identifier;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
