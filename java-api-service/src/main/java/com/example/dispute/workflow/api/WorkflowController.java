package com.example.dispute.workflow.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.workflow.application.AdjudicationDraftView;
import com.example.dispute.workflow.application.HearingView;
import com.example.dispute.workflow.application.PartySubmissionView;
import com.example.dispute.workflow.application.WorkflowApplicationService;
import com.example.dispute.workflow.application.WorkflowStartView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
@RequestMapping("/api/v1/cases/{caseId}")
public class WorkflowController {

    private final WorkflowApplicationService service;
    private final Clock clock;

    public WorkflowController(WorkflowApplicationService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping("/workflow/start")
    public ApiResponse<WorkflowStartView> start(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9_]{1,59}") String caseId,
            @RequestHeader("Idempotency-Key")
                    @NotBlank
                    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                    String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                service.start(caseId, actor(authentication), idempotencyKey),
                request);
    }

    @GetMapping("/hearing")
    public ApiResponse<HearingView> hearing(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9_]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(service.getHearing(caseId, actor(authentication)), request);
    }

    @GetMapping("/adjudication-draft")
    public ApiResponse<AdjudicationDraftView> draft(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9_]{1,59}") String caseId,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                service.getLatestDraft(caseId, actor(authentication)), request);
    }

    @PostMapping("/submissions/user")
    public ApiResponse<PartySubmissionView> userSubmission(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9_]{1,59}") String caseId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody SubmissionRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                service.submitPartyEvidence(
                        caseId,
                        "USER",
                        body.submissionText(),
                        body.evidenceIds(),
                        actor(authentication),
                        idempotencyKey),
                request);
    }

    @PostMapping("/submissions/merchant")
    public ApiResponse<PartySubmissionView> merchantSubmission(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9_]{1,59}") String caseId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody SubmissionRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        return success(
                service.submitPartyEvidence(
                        caseId,
                        "MERCHANT",
                        body.submissionText(),
                        body.evidenceIds(),
                        actor(authentication),
                        idempotencyKey),
                request);
    }

    @PostMapping("/workflow/reviewer-signal")
    public ApiResponse<Void> reviewerSignal(
            @PathVariable @Pattern(regexp = "CASE_[A-Za-z0-9_]{1,59}") String caseId,
            @Valid @RequestBody ReviewerSignalRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        service.submitReviewerSignal(
                caseId, body.decision(), body.reason(), actor(authentication));
        return success(null, request);
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(
                data,
                correlation(request, TraceIdFilter.REQUEST_ATTRIBUTE),
                correlation(request, TraceIdFilter.TRACE_ATTRIBUTE),
                Instant.now(clock));
    }

    private static AuthenticatedActor actor(Authentication authentication) {
        return (AuthenticatedActor) authentication.getPrincipal();
    }

    private static String correlation(
            HttpServletRequest request, String attribute) {
        return (String) request.getAttribute(attribute);
    }

    public record SubmissionRequest(
            @Size(max = 20_000) String submissionText,
            @Size(max = 100) List<@NotBlank String> evidenceIds) {
        public SubmissionRequest {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            if ((submissionText == null || submissionText.isBlank())
                    && evidenceIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "submission text or evidence ids are required");
            }
        }
    }

    public record ReviewerSignalRequest(
            @NotBlank
                    @Pattern(
                            regexp =
                                    "CONTINUE_WITH_AVAILABLE_EVIDENCE|ESCALATE_MANUAL")
                    String decision,
            @NotBlank @Size(max = 2_000) String reason) {}
}
