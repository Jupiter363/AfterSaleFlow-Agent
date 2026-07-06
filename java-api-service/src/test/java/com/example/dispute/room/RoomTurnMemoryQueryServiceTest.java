package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.RoomTurnMemoryQueryService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoomTurnMemoryQueryServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseParticipantRepository participantRepository;
    @Mock private RoomTurnMemoryRepository memoryRepository;
    @Mock private CaseIntakeDossierRepository intakeDossierRepository;

    private RoomTurnMemoryQueryService service;

    @BeforeEach
    void setUp() {
        service =
                new RoomTurnMemoryQueryService(
                        caseRepository,
                        participantRepository,
                        memoryRepository,
                        intakeDossierRepository,
                        new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void latestAgentMemoryReturnsStructuredScrollSnapshotForCaseOwner() {
        FulfillmentCaseEntity dispute = intakeCase();
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(memoryRepository
                        .findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc(
                                dispute.getId(), RoomType.INTAKE))
                .thenReturn(
                        Optional.of(
                                RoomTurnMemoryEntity.agentTurn(
                                        "MEMORY_LATEST",
                                        dispute.getId(),
                                        RoomType.INTAKE,
                                        3,
                                        "dispute-intake-officer",
                                        "DISPUTE_INTAKE_OFFICER",
                                        "已整理退款诉求，等待商家确认。",
                                        "{\"requested_outcome\":\"REFUND\"}",
                                        "{\"current_outcome\":\"REFUND\"}",
                                        "[{\"op\":\"UPSERT_CARD\"}]",
                                        "RUN_3")));
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(CaseIntakeDossierEntity.create(
                        "INTAKE_DOSSIER_QUERY",
                        dispute.getId(),
                        RoomType.INTAKE,
                        "{\"schema_version\":\"intake_case_detail.v1\",\"intake_quality\":{\"score\":88,\"ready_for_next_step\":true}}",
                        88,
                        true,
                        "ACCEPTED",
                        3,
                        "dispute-intake-officer")));

        var result =
                service.latestAgentMemory(
                        dispute.getId(),
                        RoomType.INTAKE,
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().turnNo()).isEqualTo(3);
        assertThat(result.orElseThrow().scrollSnapshot().path("current_outcome").asText())
                .isEqualTo("REFUND");
        assertThat(result.orElseThrow().caseIntakeDossier().qualityScore()).isEqualTo(88);
        assertThat(result.orElseThrow().caseIntakeDossier().readyForNextStep()).isTrue();
        assertThat(result.orElseThrow().canvasOperations()).hasSize(1);
    }

    @Test
    void latestAgentMemoryRejectsActorsOutsideTheDispute() {
        FulfillmentCaseEntity dispute = intakeCase();
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "other-user", ActorRole.USER))
                .thenReturn(false);

        assertThatThrownBy(
                        () ->
                                service.latestAgentMemory(
                                        dispute.getId(),
                                        RoomType.INTAKE,
                                        new AuthenticatedActor(
                                                "other-user", ActorRole.USER)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void latestEvidenceAgentMemoryIsScopedToTheRequestingParty() {
        FulfillmentCaseEntity dispute = intakeCase();
        RoomTurnMemoryEntity userParticipant =
                RoomTurnMemoryEntity.participantTurn(
                        "MEMORY_EVIDENCE_USER_PARTY",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        1,
                        "user-local",
                        "USER",
                        "用户侧只说明了签收异常。");
        RoomTurnMemoryEntity userClerk =
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_EVIDENCE_USER_CLERK",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        1,
                        "evidence-clerk",
                        "EVIDENCE_CLERK",
                        "用户侧书记官：请补充开箱照片原图。",
                        "{\"memory_frame\":{\"side\":\"USER\"}}",
                        "{\"side\":\"USER\"}",
                        "[]",
                        "RUN_USER");
        RoomTurnMemoryEntity merchantParticipant =
                RoomTurnMemoryEntity.participantTurn(
                        "MEMORY_EVIDENCE_MERCHANT_PARTY",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        2,
                        "merchant-local",
                        "MERCHANT",
                        "商家侧只说明了质检视频。");
        RoomTurnMemoryEntity merchantClerk =
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_EVIDENCE_MERCHANT_CLERK",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        2,
                        "evidence-clerk",
                        "EVIDENCE_CLERK",
                        "商家侧书记官：请补充发货质检视频原件。",
                        "{\"memory_frame\":{\"side\":\"MERCHANT\"}}",
                        "{\"side\":\"MERCHANT\"}",
                        "[]",
                        "RUN_MERCHANT");
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(memoryRepository.findTop50ByCaseIdAndRoomTypeOrderByTurnNoDesc(
                        dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(List.of(merchantClerk, merchantParticipant, userClerk, userParticipant));

        var result =
                service.latestAgentMemory(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().turnNo()).isEqualTo(1);
        assertThat(result.orElseThrow().agentResponse()).contains("用户侧书记官");
        assertThat(result.orElseThrow().agentResponse()).doesNotContain("商家侧书记官");
        assertThat(result.orElseThrow().memoryFrame().path("side").asText()).isEqualTo("USER");
    }

    private static FulfillmentCaseEntity intakeCase() {
        return FulfillmentCaseEntity.imported(
                "CASE_TURN_MEMORY_QUERY",
                "ORDER-QUERY",
                null,
                "LOG-QUERY",
                "user-local",
                "merchant-local",
                "idem-query",
                "SIGNED_NOT_RECEIVED",
                "签收未收到",
                "用户表示订单显示签收但没有收到包裹。",
                RiskLevel.HIGH,
                CaseStatus.INTAKE_PENDING,
                "INTAKE",
                OffsetDateTime.parse("2026-07-05T02:00:00Z"),
                "OMS",
                "EXT-QUERY",
                "system");
    }
}
