package com.example.dispute.workflow.config;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.util.Objects;

/** Pure, Java-authoritative stable-cohort selector for new Intake epochs. */
public final class IntakeEpochSelector {

    private static final BigInteger COHORT_MODULUS =
            BigInteger.valueOf(IntakeEpochSelectionProperties.BASIS_POINTS);

    private final IntakeEpochSelectionProperties properties;

    public IntakeEpochSelector(IntakeEpochSelectionProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Returns a bounded decision suitable for primary-owned epoch-allocation wiring. Tenant and case
     * values must come from the Java authority boundary, never from a browser or Graph payload.
     */
    public SelectionDecision decide(
            RoomType roomType,
            String tenantSurrogate,
            String caseId,
            ShadowAuthorization authorization) {
        if (roomType != RoomType.INTAKE) {
            return SelectionDecision.legacy(DecisionReason.NON_INTAKE_ROOM);
        }
        if (!properties.shadowSelectionConfigured()) {
            return SelectionDecision.legacy(DecisionReason.SHADOW_NOT_CONFIGURED);
        }
        if (authorization != ShadowAuthorization.AUTHENTICATED_SIGNED_SYNTHETIC) {
            return SelectionDecision.legacy(DecisionReason.INELIGIBLE_TRAFFIC);
        }
        if (tenantSurrogate == null
                || tenantSurrogate.isBlank()
                || caseId == null
                || caseId.isBlank()) {
            return SelectionDecision.legacy(DecisionReason.MISSING_JAVA_CASE_IDENTITY);
        }

        String cohortKeyHash = cohortKeyHash(
                tenantSurrogate.trim(), caseId.trim(), properties.cohortPolicyVersion());
        int cohortBucket = new BigInteger(cohortKeyHash, 16)
                .mod(COHORT_MODULUS)
                .intValueExact();
        if (cohortBucket >= properties.shadowCohortBasisPoints()) {
            return SelectionDecision.cohort(
                    WriterMode.LEGACY,
                    DecisionReason.OUTSIDE_SHADOW_COHORT,
                    cohortKeyHash,
                    cohortBucket,
                    properties.cohortPolicyVersion());
        }
        return SelectionDecision.cohort(
                WriterMode.SHADOW,
                DecisionReason.AUTHENTICATED_SYNTHETIC_COHORT,
                cohortKeyHash,
                cohortBucket,
                properties.cohortPolicyVersion());
    }

    public WriterMode select(
            RoomType roomType,
            String tenantSurrogate,
            String caseId,
            ShadowAuthorization authorization) {
        return decide(roomType, tenantSurrogate, caseId, authorization).writerMode();
    }

    private static String cohortKeyHash(
            String tenantSurrogate, String caseId, String cohortPolicyVersion) {
        ObjectNode cohortKey = JsonNodeFactory.instance.objectNode();
        cohortKey.put("schema_version", "intake-epoch-cohort-key.v1");
        cohortKey.put("tenant_surrogate", tenantSurrogate);
        cohortKey.put("case_id", caseId);
        cohortKey.put("cohort_policy_version", cohortPolicyVersion);
        return ContractJson.sha256Hex(cohortKey);
    }

    public enum ShadowAuthorization {
        AUTHENTICATED_SIGNED_SYNTHETIC,
        UNAUTHENTICATED_SYNTHETIC,
        AUTHENTICATED_UNSIGNED_SYNTHETIC,
        AUTHENTICATED_SIGNED_REAL_CASE
    }

    public enum DecisionReason {
        NON_INTAKE_ROOM,
        SHADOW_NOT_CONFIGURED,
        INELIGIBLE_TRAFFIC,
        MISSING_JAVA_CASE_IDENTITY,
        OUTSIDE_SHADOW_COHORT,
        AUTHENTICATED_SYNTHETIC_COHORT
    }

    public record SelectionDecision(
            WriterMode writerMode,
            DecisionReason reason,
            String cohortKeyHash,
            int cohortBucket,
            String cohortPolicyVersion) {

        public SelectionDecision {
            Objects.requireNonNull(writerMode, "writerMode must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            if (cohortKeyHash == null) {
                if (cohortBucket != -1 || cohortPolicyVersion != null) {
                    throw new IllegalArgumentException(
                            "a decision without cohort evidence must use the empty sentinel");
                }
            } else {
                if (!cohortKeyHash.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException(
                            "cohortKeyHash must be lowercase SHA-256");
                }
                if (cohortBucket < 0
                        || cohortBucket >= IntakeEpochSelectionProperties.BASIS_POINTS
                        || cohortPolicyVersion == null) {
                    throw new IllegalArgumentException("cohort evidence is incomplete");
                }
            }
            if (writerMode == WriterMode.TEMPORAL) {
                throw new IllegalArgumentException(
                        "TEMPORAL Intake epoch selection is forbidden under the current gate");
            }
            if (writerMode == WriterMode.SHADOW
                    && reason != DecisionReason.AUTHENTICATED_SYNTHETIC_COHORT) {
                throw new IllegalArgumentException(
                        "SHADOW requires the authenticated synthetic cohort reason");
            }
        }

        private static SelectionDecision legacy(DecisionReason reason) {
            return new SelectionDecision(WriterMode.LEGACY, reason, null, -1, null);
        }

        private static SelectionDecision cohort(
                WriterMode mode,
                DecisionReason reason,
                String keyHash,
                int bucket,
                String policyVersion) {
            return new SelectionDecision(mode, reason, keyHash, bucket, policyVersion);
        }
    }
}
