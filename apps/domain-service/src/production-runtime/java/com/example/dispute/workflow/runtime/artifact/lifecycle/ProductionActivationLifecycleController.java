package com.example.dispute.workflow.runtime.artifact.lifecycle;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.workflow.runtime.ProductionActivationLifecycleStore.ActivationIdentity;
import com.example.dispute.workflow.runtime.ProductionActivationLifecycleStore.LifecycleState;
import com.example.dispute.workflow.runtime.lifecycle.ProductionActivationLifecycleControl;
import com.example.dispute.workflow.runtime.lifecycle.ProductionActivationLifecycleControl.DrainCommand;
import com.example.dispute.workflow.runtime.lifecycle.ProductionActivationLifecycleControl.RefreshCommand;
import com.example.dispute.workflow.runtime.lifecycle.ProductionActivationLifecycleControl.RevokeCommand;
import com.example.dispute.workflow.runtime.lifecycle.ProductionActivationLifecycleControl.TransitionOutcome;
import com.example.dispute.workflow.runtime.lifecycle.ProductionActivationLifecycleReceiptSigner;
import com.example.dispute.workflow.runtime.lifecycle.ProductionDrainCompletionAttestationVerifier;
import io.swagger.v3.oas.annotations.Hidden;
import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Private Batch 4 lifecycle capability; never part of the browser or public OpenAPI surface. */
@Hidden
@RestController
@Profile("production-runtime & api")
@ConditionalOnProperty(name = "app.production-runtime.enabled", havingValue = "true")
@RequestMapping("/internal/production-runtime/activation/lifecycle")
public final class ProductionActivationLifecycleController {

  private static final String LIFECYCLE_CAPABILITY_HEADER =
      "X-Production-Runtime-Lifecycle-Capability";

  private final ProductionActivationLifecycleControl control;
  private final ProductionActivationLifecycleReceiptSigner receiptSigner;
  private final ProductionDrainCompletionAttestationVerifier drainVerifier;
  private final Clock clock;
  private final String serviceCapability;
  private final String runtimeContextHash;

  public ProductionActivationLifecycleController(
      ProductionActivationLifecycleControl control,
      ProductionActivationLifecycleReceiptSigner receiptSigner,
      ProductionDrainCompletionAttestationVerifier drainVerifier,
      Environment environment,
      Clock clock) {
    this.control = Objects.requireNonNull(control, "control");
    this.receiptSigner = Objects.requireNonNull(receiptSigner, "receiptSigner");
    this.drainVerifier = Objects.requireNonNull(drainVerifier, "drainVerifier");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.serviceCapability = required(environment, "production.runtime.lifecycle.service-capability");
    this.runtimeContextHash = required(environment, "production.runtime.runtime-context-hash");
  }

  @PostMapping(
      path = "/refresh-to-drain-only",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<LifecycleResponse> refreshToDrainOnly(
      @RequestHeader(value = LIFECYCLE_CAPABILITY_HEADER, required = false)
          String suppliedCapability,
      @RequestBody RefreshCommand command) {
    requireCapability(suppliedCapability);
    try {
      var outcome = control.refreshToDrainOnly(command);
      String lifecycleReceiptJws =
          outcome.state() == LifecycleState.DRAIN_ONLY
              ? receiptSigner.issue(
                  command.identity(),
                  runtimeContextHash,
                  LifecycleState.ACTIVE,
                  LifecycleState.DRAIN_ONLY,
                  outcome.effectiveAt())
              : null;
      return ResponseEntity.ok()
          .header(HttpHeaders.CACHE_CONTROL, "no-store, no-transform")
          .header(HttpHeaders.PRAGMA, "no-cache")
          .header("X-Content-Type-Options", "nosniff")
          .body(
              new LifecycleResponse(
                  "REFRESH_TO_DRAIN_ONLY",
                  outcome.state().name(),
                  "OBSERVED",
                  outcome.observedAt(),
                  lifecycleReceiptJws));
    } catch (SecurityException rejected) {
      throw bindingRejected();
    }
  }

  @PostMapping(
      path = "/mark-drained",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<LifecycleResponse> markDrained(
      @RequestHeader(value = LIFECYCLE_CAPABILITY_HEADER, required = false)
          String suppliedCapability,
      @RequestBody DrainRequest request) {
    requireCapability(suppliedCapability);
    DrainCommand command;
    try {
      command =
          new DrainCommand(
              request.identity(),
              drainVerifier.verify(request.drainCompletionJws(), request.identity()).proof());
    } catch (IllegalArgumentException | SecurityException rejected) {
      throw bindingRejected();
    }
    Instant observedAt = clock.instant();
    if (command.proof().completedAt().truncatedTo(ChronoUnit.MICROS).isAfter(observedAt)) {
      return rejectedTimestamp("MARK_DRAINED", LifecycleState.DRAINED, observedAt);
    }
    try {
      return transition(
          "MARK_DRAINED",
          control.markDrained(command),
          command.identity(),
          LifecycleState.DRAIN_ONLY,
          command.proof().completedAt());
    } catch (SecurityException rejected) {
      throw bindingRejected();
    }
  }

  @PostMapping(
      path = "/revoke-terminal",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<LifecycleResponse> revokeTerminal(
      @RequestHeader(value = LIFECYCLE_CAPABILITY_HEADER, required = false)
          String suppliedCapability,
      @RequestBody RevokeCommand command) {
    requireCapability(suppliedCapability);
    Instant observedAt = clock.instant();
    if (command.revokedAt().truncatedTo(ChronoUnit.MICROS).isAfter(observedAt)) {
      return rejectedTimestamp("REVOKE_TERMINAL", LifecycleState.REVOKED_TERMINAL, observedAt);
    }
    try {
      return transition(
          "REVOKE_TERMINAL",
          control.revokeTerminal(command),
          command.identity(),
          LifecycleState.DRAINED,
          command.revokedAt());
    } catch (SecurityException rejected) {
      throw bindingRejected();
    }
  }

  private ResponseEntity<LifecycleResponse> transition(
      String operation,
      TransitionOutcome outcome,
      ActivationIdentity identity,
      LifecycleState fromState,
      Instant transitionedAt) {
    String lifecycleReceiptJws =
        outcome.successful()
            ? receiptSigner.issue(
                identity,
                runtimeContextHash,
                fromState,
                outcome.targetState(),
                transitionedAt)
            : null;
    LifecycleResponse response =
        new LifecycleResponse(
            operation,
            outcome.targetState().name(),
            outcome.result().name(),
            outcome.observedAt(),
            lifecycleReceiptJws);
    ResponseEntity.BodyBuilder builder =
        outcome.successful()
            ? ResponseEntity.ok()
            : ResponseEntity.status(HttpStatus.CONFLICT);
    return builder
        .header(HttpHeaders.CACHE_CONTROL, "no-store, no-transform")
        .header(HttpHeaders.PRAGMA, "no-cache")
        .header("X-Content-Type-Options", "nosniff")
        .body(response);
  }

  private void requireCapability(String suppliedCapability) {
    if (!ProductionActivationLifecycleControl.serviceCapabilityMatches(
        serviceCapability, suppliedCapability)) {
      throw new ForbiddenException("invalid production runtime lifecycle capability");
    }
  }

  private static ResponseEntity<LifecycleResponse> rejectedTimestamp(
      String operation, LifecycleState targetState, Instant observedAt) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .header(HttpHeaders.CACHE_CONTROL, "no-store, no-transform")
        .header(HttpHeaders.PRAGMA, "no-cache")
        .header("X-Content-Type-Options", "nosniff")
        .body(
            new LifecycleResponse(
                operation,
                targetState.name(),
                "REJECTED_TIMESTAMP_ORDER",
                observedAt,
                null));
  }

  private static ForbiddenException bindingRejected() {
    return new ForbiddenException("production runtime lifecycle binding rejected");
  }

  private static String required(Environment environment, String property) {
    String value = Objects.requireNonNull(environment, "environment").getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("required production runtime property is absent: " + property);
    }
    return value.trim();
  }

  /** Deliberately excludes the request identity, manifest hash and service capability. */
  public record LifecycleResponse(
      String operation,
      String lifecycleState,
      String result,
      Instant observedAt,
      String lifecycleReceiptJws) {}

  /** The caller supplies no authoritative counters or seal booleans, only an independent JWS. */
  public record DrainRequest(ActivationIdentity identity, String drainCompletionJws) {
    public DrainRequest {
      Objects.requireNonNull(identity, "identity");
      if (drainCompletionJws == null
          || drainCompletionJws.isBlank()
          || drainCompletionJws.length() > 24 * 1024) {
        throw new IllegalArgumentException("drain completion attestation is invalid");
      }
    }
  }
}
