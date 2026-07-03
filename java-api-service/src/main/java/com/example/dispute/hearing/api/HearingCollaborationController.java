package com.example.dispute.hearing.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.hearing.application.HearingRoundView;
import com.example.dispute.hearing.application.SettlementService;
import com.example.dispute.hearing.application.SettlementView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/disputes/{caseId}/hearing")
public class HearingCollaborationController {

    private final HearingRoundService roundService;
    private final SettlementService settlementService;
    private final Clock clock;

    public HearingCollaborationController(
            HearingRoundService roundService,
            SettlementService settlementService,
            Clock clock) {
        this.roundService = roundService;
        this.settlementService = settlementService;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> hearing(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        AuthenticatedActor actor = actor(authentication);
        return success(
                Map.of(
                        "rounds", roundService.list(caseId, actor),
                        "settlements", settlementService.list(caseId, actor)),
                request);
    }

    @GetMapping("/rounds")
    public ApiResponse<List<HearingRoundView>> rounds(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(roundService.list(caseId, actor(authentication)), request);
    }

    @PostMapping("/rounds/complete")
    public ApiResponse<HearingRoundView> completeRound(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @Valid @RequestBody CompleteHearingRoundRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                roundService.completeNext(
                        caseId, body.toCommand(), actor(authentication)),
                request);
    }

    @GetMapping("/settlements")
    public ApiResponse<List<SettlementView>> settlements(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(settlementService.list(caseId, actor(authentication)), request);
    }

    @PostMapping("/settlements")
    public ApiResponse<SettlementView> proposeSettlement(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @Valid @RequestBody SettlementProposalRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                settlementService.propose(
                        caseId,
                        body.toCommand(),
                        actor(authentication),
                        correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE)),
                request);
    }

    @PostMapping("/settlements/{version}/confirm")
    public ApiResponse<SettlementView> confirmSettlement(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9]{1,59}") String caseId,
            @PathVariable int version,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                settlementService.confirm(
                        caseId, version, actor(authentication), idempotencyKey),
                request);
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(
                data,
                correlationId(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlationId(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }

    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String identifier && !identifier.isBlank()) {
            return identifier;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
