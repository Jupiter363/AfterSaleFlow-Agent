package com.example.dispute.review.api;

import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.ReviewTaskStatus;
import com.example.dispute.review.application.ReviewApplicationService;
import com.example.dispute.review.application.ReviewDecisionCommand;
import com.example.dispute.review.application.ReviewDecisionView;
import com.example.dispute.review.application.ReviewPacketView;
import com.example.dispute.review.application.ReviewTaskView;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {
    private final ReviewApplicationService service;
    private final Clock clock;
    public ReviewController(ReviewApplicationService service,Clock clock){this.service=service;this.clock=clock;}

    @GetMapping
    public ApiResponse<List<ReviewTaskView>> list(
            @RequestParam(defaultValue="PENDING") ReviewTaskStatus status,
            Authentication authentication,HttpServletRequest request){
        return success(service.list(status,actor(authentication)),request);
    }
    @GetMapping("/{taskId}/packet")
    public ApiResponse<ReviewPacketView> packet(
            @PathVariable @NotBlank String taskId,Authentication authentication,HttpServletRequest request){
        return success(service.packet(taskId,actor(authentication)),request);
    }
    @PostMapping("/{taskId}/decision")
    public ApiResponse<ReviewDecisionView> decide(
            @PathVariable @NotBlank String taskId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody DecisionRequest body,
            Authentication authentication,HttpServletRequest request){
        return success(service.decide(taskId,new ReviewDecisionCommand(
                body.decision(),body.reason(),body.approvedPlan(),idempotencyKey),actor(authentication)),request);
    }
    private <T> ApiResponse<T> success(T data,HttpServletRequest request){return ApiResponse.success(
            data,(String)request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE),
            (String)request.getAttribute(TraceIdFilter.TRACE_ATTRIBUTE),Instant.now(clock));}
    private static AuthenticatedActor actor(Authentication auth){return (AuthenticatedActor)auth.getPrincipal();}
    public record DecisionRequest(
            @NotNull ApprovalDecisionType decision,
            @NotBlank @Size(max=2000) String reason,
            JsonNode approvedPlan){}
}
