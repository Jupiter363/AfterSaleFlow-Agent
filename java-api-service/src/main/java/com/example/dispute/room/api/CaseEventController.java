package com.example.dispute.room.api;

import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.room.application.CaseEventService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
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

    public CaseEventController(CaseEventService service) {
        this.service = service;
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
}
