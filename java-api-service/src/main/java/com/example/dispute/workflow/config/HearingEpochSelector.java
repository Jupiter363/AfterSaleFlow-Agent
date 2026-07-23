package com.example.dispute.workflow.config;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.util.Objects;

/** Pure selector that cannot allocate a Temporal Hearing writer under the Phase 6 gate. */
public final class HearingEpochSelector {

    private static final BigInteger COHORT_MODULUS =
            BigInteger.valueOf(HearingEpochSelectionProperties.BASIS_POINTS);

    private final HearingEpochSelectionProperties properties;

    public HearingEpochSelector(HearingEpochSelectionProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    public SelectionDecision decide(
            RoomType roomType,
            String tenantSurrogate,
            String caseId,
            EpochAdmission epochAdmission,
            ShadowAuthorization authorization) {
        if (roomType != RoomType.HEARING) {
            return SelectionDecision.legacy(DecisionReason.NON_HEARING_ROOM);
        }
        if (epochAdmission != EpochAdmission.PINNED_NEW_EPOCH) {
            return SelectionDecision.legacy(DecisionReason.EXISTING_OR_UNPINNED_EPOCH);
        }
        if (!properties.signedSyntheticSelectionConfigured()) {
            return SelectionDecision.legacy(DecisionReason.SYNTHETIC_SHADOW_NOT_CONFIGURED);
        }
        if (authorization != ShadowAuthorization.JAVA_SIGNED_SYNTHETIC_FIXTURE) {
            return SelectionDecision.legacy(DecisionReason.INELIGIBLE_TRAFFIC);
        }
        if (!syntheticIdentity(tenantSurrogate) || !syntheticIdentity(caseId)) {
            return SelectionDecision.legacy(DecisionReason.NON_SYNTHETIC_OR_MISSING_IDENTITY);
        }

        String cohortHash = cohortHash(
                tenantSurrogate.trim(), caseId.trim(), properties.cohortPolicyVersion());
        int bucket = new BigInteger(cohortHash, 16).mod(COHORT_MODULUS).intValueExact();
        if (bucket >= properties.syntheticShadowCohortBasisPoints()) {
            return SelectionDecision.cohort(
                    WriterMode.LEGACY,
                    DecisionReason.OUTSIDE_SYNTHETIC_COHORT,
                    cohortHash,
                    bucket,
                    properties.cohortPolicyVersion());
        }
        return SelectionDecision.cohort(
                WriterMode.SHADOW,
                DecisionReason.JAVA_SIGNED_SYNTHETIC_COHORT,
                cohortHash,
                bucket,
                properties.cohortPolicyVersion());
    }

    public WriterMode select(
            RoomType roomType,
            String tenantSurrogate,
            String caseId,
            EpochAdmission epochAdmission,
            ShadowAuthorization authorization) {
        return decide(roomType, tenantSurrogate, caseId, epochAdmission, authorization).writerMode();
    }

    private static boolean syntheticIdentity(String value) {
        return value != null
                && value.trim().matches("synthetic-[A-Za-z0-9][A-Za-z0-9._:-]{0,117}");
    }

    private static String cohortHash(
            String tenantSurrogate, String caseId, String policyVersion) {
        ObjectNode key = JsonNodeFactory.instance.objectNode();
        key.put("schema_version", "hearing-epoch-cohort-key.v1");
        key.put("tenant_surrogate", tenantSurrogate);
        key.put("case_id", caseId);
        key.put("cohort_policy_version", policyVersion);
        return ContractJson.sha256Hex(key);
    }

    public enum EpochAdmission {
        EXISTING_ACTIVE_EPOCH,
        PINNED_NEW_EPOCH,
        UNPINNED_NEW_EPOCH
    }

    public enum ShadowAuthorization {
        JAVA_SIGNED_SYNTHETIC_FIXTURE,
        UNSIGNED_SYNTHETIC_FIXTURE,
        SIGNED_REAL_CASE,
        UNAUTHENTICATED
    }

    public enum DecisionReason {
        NON_HEARING_ROOM,
        EXISTING_OR_UNPINNED_EPOCH,
        SYNTHETIC_SHADOW_NOT_CONFIGURED,
        INELIGIBLE_TRAFFIC,
        NON_SYNTHETIC_OR_MISSING_IDENTITY,
        OUTSIDE_SYNTHETIC_COHORT,
        JAVA_SIGNED_SYNTHETIC_COHORT
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
            if (writerMode == WriterMode.TEMPORAL) {
                throw new IllegalArgumentException("TEMPORAL Hearing allocation is forbidden");
            }
            if (cohortKeyHash == null) {
                if (cohortBucket != -1 || cohortPolicyVersion != null) {
                    throw new IllegalArgumentException("empty cohort evidence must use sentinels");
                }
            } else if (!cohortKeyHash.matches("[0-9a-f]{64}")
                    || cohortBucket < 0
                    || cohortBucket >= HearingEpochSelectionProperties.BASIS_POINTS
                    || cohortPolicyVersion == null) {
                throw new IllegalArgumentException("cohort evidence is incomplete");
            }
            if (writerMode == WriterMode.SHADOW
                    && reason != DecisionReason.JAVA_SIGNED_SYNTHETIC_COHORT) {
                throw new IllegalArgumentException("SHADOW requires signed synthetic admission");
            }
        }

        private static SelectionDecision legacy(DecisionReason reason) {
            return new SelectionDecision(WriterMode.LEGACY, reason, null, -1, null);
        }

        private static SelectionDecision cohort(
                WriterMode mode, DecisionReason reason, String hash, int bucket, String policy) {
            return new SelectionDecision(mode, reason, hash, bucket, policy);
        }
    }
}
