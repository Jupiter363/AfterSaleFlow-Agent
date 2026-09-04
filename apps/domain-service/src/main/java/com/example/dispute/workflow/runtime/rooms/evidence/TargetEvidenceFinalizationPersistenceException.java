package com.example.dispute.workflow.runtime.rooms.evidence;

import com.example.dispute.agentstream.application.AgentRunFinalizationFailure;
import java.sql.SQLException;
import java.util.Set;

/** Retryable transaction conflict while committing a target Evidence formal result. */
public final class TargetEvidenceFinalizationPersistenceException extends RuntimeException
    implements AgentRunFinalizationFailure {

  public static final String CODE = "TargetEvidenceFinalizationPersistenceRetryable";
  private static final Set<String> RETRYABLE_TRANSACTION_STATES = Set.of("40001", "40P01");

  public TargetEvidenceFinalizationPersistenceException(String message, SQLException cause) {
    super(message, cause);
  }

  static boolean isRetryableTransactionConflict(SQLException failure) {
    for (SQLException cursor = failure; cursor != null; cursor = cursor.getNextException()) {
      if (RETRYABLE_TRANSACTION_STATES.contains(cursor.getSQLState())) {
        return true;
      }
    }
    for (Throwable cursor = failure.getCause(); cursor != null; cursor = cursor.getCause()) {
      if (cursor instanceof SQLException sql
          && RETRYABLE_TRANSACTION_STATES.contains(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String code() {
    return CODE;
  }

  @Override
  public boolean retryable() {
    return true;
  }
}
