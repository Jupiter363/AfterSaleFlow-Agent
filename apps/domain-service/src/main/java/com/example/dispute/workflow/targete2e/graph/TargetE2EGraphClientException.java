package com.example.dispute.workflow.targete2e.graph;

/** Bounded failure classification that never embeds response bodies or credentials. */
public final class TargetE2EGraphClientException extends RuntimeException {

  public enum RecoveryAction {
    RETRY_SAME_SEALED_COMMAND,
    CREATE_NEXT_ATTEMPT,
    RECONCILE_SEALED_COMMAND,
    FAIL_LOGICAL_RUN
  }

  private final String errorCode;
  private final RecoveryAction recoveryAction;

  private TargetE2EGraphClientException(
      String errorCode, RecoveryAction recoveryAction, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
    this.recoveryAction = recoveryAction;
  }

  public static TargetE2EGraphClientException protocol(String message, Throwable cause) {
    return new TargetE2EGraphClientException(
        "TARGET_E2E_GRAPH_PROTOCOL_REJECTED", RecoveryAction.FAIL_LOGICAL_RUN, message, cause);
  }

  public static TargetE2EGraphClientException transport(String message, Throwable cause) {
    return new TargetE2EGraphClientException(
        "TARGET_E2E_GRAPH_TRANSPORT_FAILED",
        RecoveryAction.RECONCILE_SEALED_COMMAND,
        message,
        cause);
  }

  public static TargetE2EGraphClientException notSubmitted(String message, Throwable cause) {
    return new TargetE2EGraphClientException(
        "TARGET_E2E_GRAPH_COMMAND_NOT_SUBMITTED",
        RecoveryAction.RETRY_SAME_SEALED_COMMAND,
        message,
        cause);
  }

  public static TargetE2EGraphClientException remote(
      String code, boolean retryable, String message) {
    return new TargetE2EGraphClientException(
        code,
        retryable ? RecoveryAction.RETRY_SAME_SEALED_COMMAND : RecoveryAction.FAIL_LOGICAL_RUN,
        message,
        null);
  }

  public static TargetE2EGraphClientException attemptAborted(String reasonCode) {
    if (reasonCode == null
        || !reasonCode.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("target Graph abort reason code is invalid");
    }
    return new TargetE2EGraphClientException(
        reasonCode,
        RecoveryAction.CREATE_NEXT_ATTEMPT,
        "Python durably aborted the target Graph attempt",
        null);
  }

  public String errorCode() {
    return errorCode;
  }

  public RecoveryAction recoveryAction() {
    return recoveryAction;
  }
}
