package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.activity.intake.IntakeGraphExecutionPort;

/** Explicit marker for the ES256-signed Graph command boundary used by synthetic shadow. */
@FunctionalInterface
public interface IntakeSignedSyntheticGraphExecutionPort extends IntakeGraphExecutionPort {}
