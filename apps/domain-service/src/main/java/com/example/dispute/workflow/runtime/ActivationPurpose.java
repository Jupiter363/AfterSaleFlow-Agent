package com.example.dispute.workflow.runtime;

/** Distinguishes new admission from completion of work durably admitted before expiry. */
public enum ActivationPurpose {
  NEW_ADMISSION,
  DRAIN_ACCEPTED_COMMAND
}
