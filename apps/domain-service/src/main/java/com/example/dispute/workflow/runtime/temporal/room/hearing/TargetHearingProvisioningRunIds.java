package com.example.dispute.workflow.runtime.temporal.room.hearing;

/** Deterministic transient run binding used only until room provisioning records the real run id. */
public final class TargetHearingProvisioningRunIds {

  private static final String PREFIX = "provisioning:";

  private TargetHearingProvisioningRunIds() {}

  public static String provisional(String epochId) {
    if (epochId == null
        || epochId.isBlank()
        || epochId.length() > 64
        || !epochId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) {
      throw new IllegalArgumentException("target Hearing epoch id is invalid");
    }
    return PREFIX + epochId;
  }
}
