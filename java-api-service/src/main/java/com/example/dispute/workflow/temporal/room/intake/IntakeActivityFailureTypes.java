package com.example.dispute.workflow.temporal.room.intake;

import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

public final class IntakeActivityFailureTypes {

  public static final String BUSINESS = "INTAKE_BUSINESS_REJECTED";
  public static final String AUTHORIZATION = "INTAKE_AUTHORIZATION_REJECTED";
  public static final String SCHEMA = "INTAKE_SCHEMA_REJECTED";
  public static final String STALE_REVISION = "INTAKE_STALE_REVISION";
  public static final String STALE_FENCE = "INTAKE_STALE_FENCE";
  public static final String GUARDRAIL = "INTAKE_GUARDRAIL_REJECTED";
  public static final String RETRY_BUDGET_EXHAUSTED =
      "INTAKE_ACTIVITY_RETRY_BUDGET_EXHAUSTED";
  public static final String INFRASTRUCTURE_RETRYABLE = "INTAKE_INFRASTRUCTURE_RETRYABLE";
  public static final String UNCLASSIFIED = "INTAKE_ACTIVITY_UNCLASSIFIED";

  private static final Set<String> KNOWN_NON_RETRYABLE =
      Set.of(
          BUSINESS,
          AUTHORIZATION,
          SCHEMA,
          STALE_REVISION,
          STALE_FENCE,
          GUARDRAIL,
          RETRY_BUDGET_EXHAUSTED,
          UNCLASSIFIED);

  private IntakeActivityFailureTypes() {}

  public static boolean isRetryable(String failureType) {
    return INFRASTRUCTURE_RETRYABLE.equals(failureType);
  }

  public static boolean isNonRetryable(String failureType) {
    return KNOWN_NON_RETRYABLE.contains(failureType) || !isRetryable(failureType);
  }

  public static String classify(Throwable failure) {
    Objects.requireNonNull(failure, "failure must not be null");
    ApplicationFailure applicationFailure = null;
    Throwable current = failure;
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    while (current != null && visited.add(current)) {
      if (current instanceof TimeoutFailure) {
        return INFRASTRUCTURE_RETRYABLE;
      }
      if (applicationFailure == null && current instanceof ApplicationFailure candidate) {
        applicationFailure = candidate;
      }
      current = current.getCause();
    }
    if (applicationFailure != null
        && applicationFailure.getType() != null
        && !applicationFailure.getType().isBlank()) {
      String type = applicationFailure.getType();
      if (isRetryable(type) || KNOWN_NON_RETRYABLE.contains(type)) {
        return type;
      }
    }
    return UNCLASSIFIED;
  }

  public static Set<String> knownNonRetryableTypes() {
    return KNOWN_NON_RETRYABLE;
  }
}
