package com.example.dispute.workflow.temporal.room.evidence;

import java.util.Objects;

/** Java-readable, non-authoritative view emitted only after all live recovery fences validate. */
public record EvidenceOperationalRecoveryProjection(
    String schemaVersion,
    EvidenceFinalizationReceiptRef receipt,
    EvidenceOperationalRecoveryStore.TerminalSummary terminalSummary,
    EvidenceOperationalRecoveryStore.JavaRecoveryAuthority javaAuthority) {

  public EvidenceOperationalRecoveryProjection {
    if (!"evidence-operational-recovery-projection.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be evidence-operational-recovery-projection.v1");
    }
    receipt = Objects.requireNonNull(receipt, "receipt");
    terminalSummary = Objects.requireNonNull(terminalSummary, "terminalSummary");
    javaAuthority = Objects.requireNonNull(javaAuthority, "javaAuthority");
    if (!terminalSummary.matches(receipt)) {
      throw new IllegalArgumentException("projection summary does not match receipt");
    }
  }

}
