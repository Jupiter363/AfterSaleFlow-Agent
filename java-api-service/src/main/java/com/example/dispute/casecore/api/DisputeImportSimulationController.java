package com.example.dispute.casecore.api;

import com.example.dispute.casecore.application.DisputeImportService;
import com.example.dispute.casecore.application.SimulatedImportResultView;
import com.example.dispute.common.api.ApiResponse;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public demo adapter for importing externally-originated dispute cases.
 *
 * <p>The browser should never call the internal import boundary directly. This controller validates
 * the current demo actor and then delegates to the internal import workflow with a system service
 * identity, matching how a trusted OMS/after-sale adapter would enter the platform.
 */
@Validated
@RestController
@RequestMapping("/api/disputes/import")
public class DisputeImportSimulationController {

    private static final AuthenticatedActor SYSTEM_IMPORT_ACTOR =
            new AuthenticatedActor("external-import-simulator", ActorRole.SYSTEM);

    private final DisputeImportService service;
    private final Clock clock;

    public DisputeImportSimulationController(DisputeImportService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping("/simulate")
    public ResponseEntity<ApiResponse<SimulatedImportResultView>> simulateImport(
            @Valid @RequestBody SimulateImportRequest request,
            @RequestHeader("Idempotency-Key")
                    @NotBlank
                    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                    String idempotencyKey,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        AuthenticatedActor actor = (AuthenticatedActor) authentication.getPrincipal();
        assertCanSimulateForCurrentIdentity(request, actor);
        String traceId = correlationId(servletRequest, TraceIdFilter.TRACE_ATTRIBUTE);
        String requestId = correlationId(servletRequest, TraceIdFilter.REQUEST_ATTRIBUTE);
        SimulatedImportResultView imported =
                service.simulateExternalImport(
                        request.toCommand(idempotencyKey),
                        SYSTEM_IMPORT_ACTOR,
                        idempotencyKey,
                        traceId,
                        requestId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(imported, requestId, traceId, Instant.now(clock)));
    }

    private static void assertCanSimulateForCurrentIdentity(
            SimulateImportRequest request, AuthenticatedActor actor) {
        if (actor.role() != ActorRole.USER && actor.role() != ActorRole.MERCHANT) {
            throw new ForbiddenException("only user or merchant demo identities can simulate import");
        }
        if (request.initiatorRoleHint() != actor.role()) {
            throw new ForbiddenException("initiator role must match the current demo identity");
        }
        if (!actor.actorId().equals(request.currentActorId())) {
            throw new ForbiddenException("current actor id must match the current demo identity");
        }
    }

    private static String correlationId(HttpServletRequest request, String attribute) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new IllegalStateException("correlation id filter did not run");
    }
}
