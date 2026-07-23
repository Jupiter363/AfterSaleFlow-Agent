package com.example.dispute.workflow.shadow.hearing;

import com.example.dispute.workflow.shadow.hearing.HearingShadowParityService.ParityComparison;

/** Atomic append-or-load port for the isolated synthetic comparison store. */
@FunctionalInterface
public interface HearingSyntheticComparisonLedger {

    /** Returns the inserted row or the previously committed row for the same comparison key. */
    ParityComparison appendOrLoad(ParityComparison comparison);
}
