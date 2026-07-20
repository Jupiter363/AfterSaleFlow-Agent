package com.example.dispute.workflow.temporal.room.intake;

import java.util.Set;

public final class IntakeActivityFailureTypes {

  public static final String BUSINESS = "INTAKE_BUSINESS_REJECTED";
  public static final String AUTHORIZATION = "INTAKE_AUTHORIZATION_REJECTED";
  public static final String SCHEMA = "INTAKE_SCHEMA_REJECTED";
  public static final String STALE_REVISION = "INTAKE_STALE_REVISION";
  public static final String STALE_FENCE = "INTAKE_STALE_FENCE";
  public static final String GUARDRAIL = "INTAKE_GUARDRAIL_REJECTED";
  public static final String INFRASTRUCTURE_RETRYABLE = "INTAKE_INFRASTRUCTURE_RETRYABLE";

  private static final Set<String> KNOWN_NON_RETRYABLE =
      Set.of(BUSINESS, AUTHORIZATION, SCHEMA, STALE_REVISION, STALE_FENCE, GUARDRAIL);

  private IntakeActivityFailureTypes() {}

  public static boolean isRetryable(String failureType) {
    return INFRASTRUCTURE_RETRYABLE.equals(failureType);
  }

  public static boolean isNonRetryable(String failureType) {
    return KNOWN_NON_RETRYABLE.contains(failureType) || !isRetryable(failureType);
  }

  public static Set<String> knownNonRetryableTypes() {
    return KNOWN_NON_RETRYABLE;
  }
}
