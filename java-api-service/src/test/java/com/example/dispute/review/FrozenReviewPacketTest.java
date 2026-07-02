package com.example.dispute.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.infrastructure.persistence.entity.ReviewPacketEntity;
import com.example.dispute.review.domain.ReviewPacketVersions;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FrozenReviewPacketTest {

    @Test
    void freezesEverySourceVersionAndActionHashBeforeReview() {
        OffsetDateTime frozenAt =
                OffsetDateTime.of(
                        2026, 7, 2, 12, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime expiresAt = frozenAt.plusDays(7);
        ReviewPacketVersions versions =
                new ReviewPacketVersions(
                        11,
                        4,
                        3,
                        2,
                        1,
                        5,
                        "RULESET_2026_07",
                        "hearing-v2",
                        "signed-not-received-v3",
                        "presiding-judge-v2");

        ReviewPacketEntity packet =
                ReviewPacketEntity.createFrozen(
                        "PACKET_frozen",
                        "CASE_frozen",
                        "REMEDY_frozen",
                        6,
                        versions,
                        "ACTION_HASH_frozen",
                        frozenAt,
                        expiresAt,
                        "{}",
                        "[]",
                        "[]",
                        "[]",
                        "{}",
                        "{}",
                        "[]",
                        "SYSTEM");

        assertThat(packet.isFrozen()).isTrue();
        assertThat(packet.getCaseVersion()).isEqualTo(11);
        assertThat(packet.getDossierVersion()).isEqualTo(4);
        assertThat(packet.getIssueVersion()).isEqualTo(3);
        assertThat(packet.getAdjudicationDraftVersion()).isEqualTo(2);
        assertThat(packet.getDeliberationReportVersion()).isEqualTo(1);
        assertThat(packet.getRemedyPlanVersion()).isEqualTo(5);
        assertThat(packet.getRulesetVersion()).isEqualTo("RULESET_2026_07");
        assertThat(packet.getPromptVersion()).isEqualTo("hearing-v2");
        assertThat(packet.getSkillVersion())
                .isEqualTo("signed-not-received-v3");
        assertThat(packet.getProfileVersion())
                .isEqualTo("presiding-judge-v2");
        assertThat(packet.getActionHash()).isEqualTo("ACTION_HASH_frozen");
        assertThat(packet.getFrozenAt()).isEqualTo(frozenAt);
        assertThat(packet.getExpiresAt()).isEqualTo(expiresAt);
    }
}
