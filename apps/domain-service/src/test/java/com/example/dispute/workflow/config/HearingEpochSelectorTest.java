package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.config.HearingEpochSelector.DecisionReason;
import com.example.dispute.workflow.config.HearingEpochSelector.EpochAdmission;
import com.example.dispute.workflow.config.HearingEpochSelector.ShadowAuthorization;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import org.junit.jupiter.api.Test;

class HearingEpochSelectorTest {

    @Test
    void existingActiveHearingAlwaysRemainsLegacy() {
        HearingEpochSelector selector = enabledSelector();
        assertThat(selector.decide(
                                RoomType.HEARING,
                                "synthetic-tenant-1",
                                "synthetic-case-1",
                                EpochAdmission.EXISTING_ACTIVE_EPOCH,
                                ShadowAuthorization.JAVA_SIGNED_SYNTHETIC_FIXTURE)
                        .reason())
                .isEqualTo(DecisionReason.EXISTING_OR_UNPINNED_EPOCH);
        assertThat(selector.select(
                        RoomType.HEARING,
                        "synthetic-tenant-1",
                        "synthetic-case-1",
                        EpochAdmission.EXISTING_ACTIVE_EPOCH,
                        ShadowAuthorization.JAVA_SIGNED_SYNTHETIC_FIXTURE))
                .isEqualTo(WriterMode.LEGACY);
    }

    @Test
    void onlyJavaSignedSyntheticPinnedNewEpochCanEnterShadow() {
        HearingEpochSelector selector = enabledSelector();
        for (ShadowAuthorization authorization : ShadowAuthorization.values()) {
            WriterMode expected = authorization == ShadowAuthorization.JAVA_SIGNED_SYNTHETIC_FIXTURE
                    ? WriterMode.SHADOW
                    : WriterMode.LEGACY;
            assertThat(selector.select(
                            RoomType.HEARING,
                            "synthetic-tenant-1",
                            "synthetic-case-1",
                            EpochAdmission.PINNED_NEW_EPOCH,
                            authorization))
                    .isEqualTo(expected);
        }
        assertThat(selector.select(
                        RoomType.HEARING,
                        "tenant-real",
                        "case-real",
                        EpochAdmission.PINNED_NEW_EPOCH,
                        ShadowAuthorization.JAVA_SIGNED_SYNTHETIC_FIXTURE))
                .isEqualTo(WriterMode.LEGACY);
    }

    @Test
    void wrongRoomUnpinnedEpochAndMissingConfigurationFailClosed() {
        assertThat(enabledSelector().select(
                        RoomType.EVIDENCE,
                        null,
                        null,
                        null,
                        null))
                .isEqualTo(WriterMode.LEGACY);
        assertThat(enabledSelector().select(
                        RoomType.HEARING,
                        "synthetic-tenant-1",
                        "synthetic-case-1",
                        EpochAdmission.UNPINNED_NEW_EPOCH,
                        ShadowAuthorization.JAVA_SIGNED_SYNTHETIC_FIXTURE))
                .isEqualTo(WriterMode.LEGACY);
        assertThat(new HearingEpochSelector(new HearingEpochSelectionProperties(null, 0, null, false))
                        .select(
                                RoomType.HEARING,
                                "synthetic-tenant-1",
                                "synthetic-case-1",
                                EpochAdmission.PINNED_NEW_EPOCH,
                                ShadowAuthorization.JAVA_SIGNED_SYNTHETIC_FIXTURE))
                .isEqualTo(WriterMode.LEGACY);
    }

    @Test
    void cohortEvidenceIsStableAndPolicyPinned() {
        HearingEpochSelector.SelectionDecision first = enabledSelector().decide(
                RoomType.HEARING,
                "synthetic-tenant-1",
                "synthetic-case-1",
                EpochAdmission.PINNED_NEW_EPOCH,
                ShadowAuthorization.JAVA_SIGNED_SYNTHETIC_FIXTURE);
        HearingEpochSelector.SelectionDecision repeated = enabledSelector().decide(
                RoomType.HEARING,
                "synthetic-tenant-1",
                "synthetic-case-1",
                EpochAdmission.PINNED_NEW_EPOCH,
                ShadowAuthorization.JAVA_SIGNED_SYNTHETIC_FIXTURE);
        assertThat(repeated).isEqualTo(first);
        assertThat(first.cohortKeyHash()).matches("[0-9a-f]{64}");
        assertThat(first.writerMode()).isEqualTo(WriterMode.SHADOW);
    }

    private static HearingEpochSelector enabledSelector() {
        return new HearingEpochSelector(new HearingEpochSelectionProperties(
                WriterMode.SHADOW, 10_000, "hearing.synthetic.v1", true));
    }
}
