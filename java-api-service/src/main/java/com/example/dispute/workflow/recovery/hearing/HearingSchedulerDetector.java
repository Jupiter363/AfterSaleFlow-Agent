package com.example.dispute.workflow.recovery.hearing;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/** Read-only detector contract for future Temporal scheduler epochs. */
public interface HearingSchedulerDetector {

    Detection inspectDeadlineProjection();

    Detection inspectHandoffProjection();

    static HearingSchedulerDetector unavailable() {
        return new HearingSchedulerDetector() {
            @Override
            public Detection inspectDeadlineProjection() {
                throw new IllegalStateException("deadline detector is unavailable");
            }

            @Override
            public Detection inspectHandoffProjection() {
                throw new IllegalStateException("handoff detector is unavailable");
            }
        };
    }

    record Detection(
            DetectionOutcome outcome,
            long candidateCount,
            long mismatchCount,
            String evidenceHash) {

        public Detection {
            Objects.requireNonNull(outcome, "outcome must not be null");
            if (candidateCount < 0 || mismatchCount < 0 || mismatchCount > candidateCount) {
                throw new IllegalArgumentException("detector counts are invalid");
            }
            if (outcome == DetectionOutcome.NO_CANDIDATE && candidateCount != 0) {
                throw new IllegalArgumentException("NO_CANDIDATE requires an empty scan");
            }
            if (outcome == DetectionOutcome.MATCH && (candidateCount == 0 || mismatchCount != 0)) {
                throw new IllegalArgumentException("MATCH requires candidates without mismatches");
            }
            if (outcome == DetectionOutcome.MISMATCH && mismatchCount == 0) {
                throw new IllegalArgumentException("MISMATCH requires at least one mismatch");
            }
            if (evidenceHash == null || !evidenceHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("evidenceHash must be lowercase SHA-256");
            }
        }

        public static Detection fromCounts(
                DetectorKind kind, long candidateCount, long mismatchCount) {
            Objects.requireNonNull(kind, "kind must not be null");
            DetectionOutcome outcome =
                    candidateCount == 0
                            ? DetectionOutcome.NO_CANDIDATE
                            : mismatchCount == 0
                                    ? DetectionOutcome.MATCH
                                    : DetectionOutcome.MISMATCH;
            ObjectNode evidence = JsonNodeFactory.instance.objectNode();
            evidence.put("schema_version", "hearing-scheduler-detection.v2");
            evidence.put("authority", "DOMAIN_POSTGRESQL_FULL_LEGACY_CANDIDATE_SCAN");
            evidence.put("mutation_authority", false);
            evidence.put("enqueue_authority", false);
            evidence.put("phase_authority", false);
            evidence.put("time_authority", false);
            evidence.put("detector_kind", kind.name());
            evidence.put("outcome", outcome.name());
            evidence.put("candidate_count", candidateCount);
            evidence.put("mismatch_count", mismatchCount);
            return new Detection(
                    outcome, candidateCount, mismatchCount, ContractJson.sha256Hex(evidence));
        }
    }

    enum DetectorKind {
        DEADLINE_PROJECTION,
        HANDOFF_PROJECTION
    }

    enum DetectionOutcome {
        NO_CANDIDATE,
        MATCH,
        MISMATCH
    }
}
