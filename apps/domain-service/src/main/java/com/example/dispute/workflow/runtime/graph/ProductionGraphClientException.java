package com.example.dispute.workflow.runtime.graph;

/** Bounded failure classification that never embeds response bodies or credentials. */
public final class ProductionGraphClientException extends RuntimeException {

  public enum RecoveryAction {
    RETRY_SAME_SEALED_COMMAND,
    CREATE_NEXT_ATTEMPT,
    RECONCILE_SEALED_COMMAND,
    FAIL_LOGICAL_RUN
  }

  private final String errorCode;
  private final RecoveryAction recoveryAction;

  private ProductionGraphClientException(
      String errorCode, RecoveryAction recoveryAction, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
    this.recoveryAction = recoveryAction;
  }

  public static ProductionGraphClientException protocol(String message, Throwable cause) {
    return new ProductionGraphClientException(
        "PRODUCTION_RUNTIME_GRAPH_PROTOCOL_REJECTED", RecoveryAction.FAIL_LOGICAL_RUN, message, cause);
  }

  public static ProductionGraphClientException transport(String message, Throwable cause) {
    return new ProductionGraphClientException(
        "PRODUCTION_RUNTIME_GRAPH_TRANSPORT_FAILED",
        RecoveryAction.RECONCILE_SEALED_COMMAND,
        message,
        cause);
  }

  public static ProductionGraphClientException notSubmitted(String message, Throwable cause) {
    return new ProductionGraphClientException(
        "PRODUCTION_RUNTIME_GRAPH_COMMAND_NOT_SUBMITTED",
        RecoveryAction.RETRY_SAME_SEALED_COMMAND,
        message,
        cause);
  }

  public static ProductionGraphClientException remote(
      String code, boolean retryable, String message) {
    return new ProductionGraphClientException(
        code,
        retryable ? RecoveryAction.RETRY_SAME_SEALED_COMMAND : RecoveryAction.FAIL_LOGICAL_RUN,
        message,
        null);
  }

  public static ProductionGraphClientException attemptAborted(String reasonCode) {
    if (reasonCode == null
        || !reasonCode.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("target Graph abort reason code is invalid");
    }
    return new ProductionGraphClientException(
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
