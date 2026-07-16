package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.IntakeProgressService;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakePartyCompletionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakePartyCompletionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IntakeProgressServiceTest {

    @Test
    void respondentMovesFromLockedIntakeToOpenIntakeThenSharedEvidence() {
        FulfillmentCaseRepository cases = mock(FulfillmentCaseRepository.class);
        CaseIntakePartyCompletionRepository completions =
                mock(CaseIntakePartyCompletionRepository.class);
        IntakeProgressService service =
                new IntakeProgressService(
                        cases,
                        completions,
                        Clock.fixed(
                                Instant.parse("2026-07-15T00:30:00Z"),
                                ZoneOffset.UTC));
        FulfillmentCaseEntity dispute = evidenceCase();
        AuthenticatedActor merchant =
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT);

        when(completions.findByCaseIdAndParticipantRoleAndParticipantId(
                        dispute.getId(), ActorRole.USER, "user-local"))
                .thenReturn(Optional.empty());
        when(completions.findByCaseIdAndParticipantRoleAndParticipantId(
                        dispute.getId(), ActorRole.MERCHANT, "merchant-local"))
                .thenReturn(Optional.empty());
        when(completions.countByCaseId(dispute.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.assertIntakeRead(dispute, merchant))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.assertEvidenceAccess(dispute, merchant))
                .isInstanceOf(ForbiddenException.class);

        CaseIntakePartyCompletionEntity initiator =
                CaseIntakePartyCompletionEntity.terminal(
                        "INTAKE_COMPLETE_USER",
                        dispute.getId(),
                        ActorRole.USER,
                        "user-local",
                        "COMPLETED",
                        Instant.parse("2026-07-15T00:00:00Z"),
                        "user-local");
        when(completions.findByCaseIdAndParticipantRoleAndParticipantId(
                        dispute.getId(), ActorRole.USER, "user-local"))
                .thenReturn(Optional.of(initiator));

        service.assertIntakeRead(dispute, merchant);
        service.assertIntakePost(dispute, merchant);
        assertThat(service.status(dispute, merchant).respondentStatus()).isEqualTo("OPEN");
        assertThat(service.status(dispute, merchant).evidenceDeadlineAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-15T02:00:00Z"));
        assertThatThrownBy(() -> service.assertEvidenceAccess(dispute, merchant))
                .isInstanceOf(ForbiddenException.class);

        CaseIntakePartyCompletionEntity respondent =
                CaseIntakePartyCompletionEntity.terminal(
                        "INTAKE_COMPLETE_MERCHANT",
                        dispute.getId(),
                        ActorRole.MERCHANT,
                        "merchant-local",
                        "COMPLETED",
                        Instant.parse("2026-07-15T00:40:00Z"),
                        "merchant-local");
        when(completions.findByCaseIdAndParticipantRoleAndParticipantId(
                        dispute.getId(), ActorRole.MERCHANT, "merchant-local"))
                .thenReturn(Optional.of(respondent));

        service.assertEvidenceAccess(dispute, merchant);
        assertThat(service.status(dispute, merchant).canEnterEvidence()).isTrue();
        assertThat(service.status(dispute, merchant).evidenceDeadlineAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-15T02:00:00Z"));

        dispute.openHearing(
                OffsetDateTime.parse("2026-07-15T03:00:00Z"), "hearing-system");
        service.assertEvidenceAccess(dispute, merchant);
        dispute.attachHearingWorkflow("WORKFLOW_INTAKE_PROGRESS", "hearing-system");
        service.assertEvidenceAccess(dispute, merchant);
        assertThat(service.status(dispute, merchant).canEnterEvidence()).isFalse();

        dispute.markRemedyPlanned("hearing-system");
        dispute.waitForHumanReview("hearing-system");
        service.assertEvidenceReadAccess(dispute, merchant);
        assertThatThrownBy(() -> service.assertEvidenceAccess(dispute, merchant))
                .isInstanceOf(ForbiddenException.class);
    }

    private static FulfillmentCaseEntity evidenceCase() {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.imported(
                        "CASE_INTAKE_PROGRESS",
                        "ORDER-PROGRESS",
                        null,
                        "LOG-PROGRESS",
                        "user-local",
                        "merchant-local",
                        "idem-progress",
                        "PRODUCT_QUALITY",
                        "安装费争议",
                        "用户申请退还安装费",
                        RiskLevel.MEDIUM,
                        CaseStatus.INTAKE_PENDING,
                        "INTAKE",
                        null,
                        "OMS",
                        "EXT-PROGRESS",
                        "external-adapter");
        dispute.admitToEvidence(
                "PRODUCT_QUALITY",
                RiskLevel.MEDIUM,
                "{}",
                OffsetDateTime.parse("2026-07-15T02:00:00Z"),
                "user-local");
        return dispute;
    }
}
