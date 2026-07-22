package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.config.IntakeEpochSelector.DecisionReason;
import com.example.dispute.workflow.config.IntakeEpochSelector.SelectionDecision;
import com.example.dispute.workflow.config.IntakeEpochSelector.ShadowAuthorization;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import org.junit.jupiter.api.Test;

class IntakeEpochSelectorTest {

    @Test
    void defaultsAndPartialConfigurationSelectLegacy() {
        assertThat(selector(WriterMode.LEGACY, 0, null, false)
                        .select(
                                RoomType.INTAKE,
                                "tenant-1",
                                "case-1",
                                ShadowAuthorization.AUTHENTICATED_SIGNED_SYNTHETIC))
                .isEqualTo(WriterMode.LEGACY);
        assertThat(selector(WriterMode.SHADOW, 10_000, null, true)
                        .select(
                                RoomType.INTAKE,
                                "tenant-1",
                                "case-1",
                                ShadowAuthorization.AUTHENTICATED_SIGNED_SYNTHETIC))
                .isEqualTo(WriterMode.LEGACY);
    }

    @Test
    void rejectsNonIntakeBeforeEnablementAndCohortEvaluation() {
        IntakeEpochSelector selector = enabledSelector(10_000, "cohort.v1");

        SelectionDecision decision = selector.decide(
                RoomType.EVIDENCE, null, null, null);

        assertThat(decision.writerMode()).isEqualTo(WriterMode.LEGACY);
        assertThat(decision.reason()).isEqualTo(DecisionReason.NON_INTAKE_ROOM);
        assertThat(decision.cohortKeyHash()).isNull();
    }

    @Test
    void admitsOnlyAuthenticatedSignedSyntheticTraffic() {
        IntakeEpochSelector selector = enabledSelector(10_000, "cohort.v1");

        for (ShadowAuthorization authorization : ShadowAuthorization.values()) {
            WriterMode expected =
                    authorization == ShadowAuthorization.AUTHENTICATED_SIGNED_SYNTHETIC
                            ? WriterMode.SHADOW
                            : WriterMode.LEGACY;
            assertThat(selector.select(RoomType.INTAKE, "tenant-1", "case-1", authorization))
                    .isEqualTo(expected);
        }
        assertThat(selector.select(RoomType.INTAKE, "tenant-1", "case-1", null))
                .isEqualTo(WriterMode.LEGACY);
        assertThat(selector.select(
                        RoomType.INTAKE,
                        "tenant-1",
                        "case-1",
                        ShadowAuthorization.AUTHENTICATED_SIGNED_REAL_CASE))
                .isEqualTo(WriterMode.LEGACY);
    }

    @Test
    void cohortIsStableAndControlledByThePinnedPolicy() {
        SelectionDecision first = enabledSelector(10_000, "cohort.v1")
                .decide(
                        RoomType.INTAKE,
                        "tenant-1",
                        "case-1",
                        ShadowAuthorization.AUTHENTICATED_SIGNED_SYNTHETIC);
        SelectionDecision repeated = enabledSelector(10_000, "cohort.v1")
                .decide(
                        RoomType.INTAKE,
                        "tenant-1",
                        "case-1",
                        ShadowAuthorization.AUTHENTICATED_SIGNED_SYNTHETIC);
        SelectionDecision repinned = enabledSelector(10_000, "cohort.v2")
                .decide(
                        RoomType.INTAKE,
                        "tenant-1",
                        "case-1",
                        ShadowAuthorization.AUTHENTICATED_SIGNED_SYNTHETIC);

        assertThat(repeated).isEqualTo(first);
        assertThat(first.cohortKeyHash()).matches("[0-9a-f]{64}");
        assertThat(repinned.cohortKeyHash()).isNotEqualTo(first.cohortKeyHash());

        assertThat(enabledSelector(first.cohortBucket(), "cohort.v1")
                        .select(
                                RoomType.INTAKE,
                                "tenant-1",
                                "case-1",
                                ShadowAuthorization.AUTHENTICATED_SIGNED_SYNTHETIC))
                .isEqualTo(WriterMode.LEGACY);
        assertThat(enabledSelector(first.cohortBucket() + 1, "cohort.v1")
                        .select(
                                RoomType.INTAKE,
                                "tenant-1",
                                "case-1",
                                ShadowAuthorization.AUTHENTICATED_SIGNED_SYNTHETIC))
                .isEqualTo(WriterMode.SHADOW);
    }

    @Test
    void missingJavaOwnedIdentityFailsClosed() {
        IntakeEpochSelector selector = enabledSelector(10_000, "cohort.v1");

        SelectionDecision decision = selector.decide(
                RoomType.INTAKE,
                " ",
                "case-1",
                ShadowAuthorization.AUTHENTICATED_SIGNED_SYNTHETIC);

        assertThat(decision.writerMode()).isEqualTo(WriterMode.LEGACY);
        assertThat(decision.reason()).isEqualTo(DecisionReason.MISSING_JAVA_CASE_IDENTITY);
    }

    private static IntakeEpochSelector enabledSelector(int basisPoints, String policyVersion) {
        return selector(WriterMode.SHADOW, basisPoints, policyVersion, true);
    }

    private static IntakeEpochSelector selector(
            WriterMode mode, int basisPoints, String policyVersion, boolean enabled) {
        return new IntakeEpochSelector(
                new IntakeEpochSelectionProperties(mode, basisPoints, policyVersion, enabled));
    }
}
