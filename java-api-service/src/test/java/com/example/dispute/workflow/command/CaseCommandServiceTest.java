package com.example.dispute.workflow.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.AuditLogEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.AuditLogRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.command.CaseCommandDeliveryTrigger;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.application.command.TenantAuthority;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandOutboxEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.WriterActivationStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandOutboxRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class CaseCommandServiceTest {

    private static final String CASE_ID = "CASE_P9_SYNTHETIC_CommandService";
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseCommandRepository commandRepository;
    @Mock private CaseCommandOutboxRepository outboxRepository;
    @Mock private CaseProcessProjectionRepository projectionRepository;
    @Mock private CaseRoomEpochRepository roomEpochRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private CaseCommandDeliveryTrigger deliveryTrigger;
    @Mock private FulfillmentCaseEntity disputeCase;
    @Mock private CaseProcessProjectionEntity projection;
    @Mock private CaseRoomEpochEntity roomEpoch;

    private CaseCommandService service;

    @BeforeEach
    void setUp() {
        TenantAuthority tenantAuthority = () -> "legacy-default";
        service =
                new CaseCommandService(
                        caseRepository,
                        commandRepository,
                        outboxRepository,
                        projectionRepository,
                        roomEpochRepository,
                        auditLogRepository,
                        tenantAuthority,
                        deliveryTrigger,
                        JsonMapper.builder().build(),
                        Clock.fixed(NOW, ZoneOffset.UTC));

        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(disputeCase));
        when(disputeCase.getUserId()).thenReturn("user-command");
    }

    @Test
    void writesCommandAndOutboxThenReplaysTheSameAuthorizedHash() {
        arrangeWritableProjection();
        when(commandRepository.findByTenantSurrogateAndCommandId(
                        "legacy-default", "command.same"))
                .thenReturn(Optional.empty());
        when(commandRepository.findFirstByCaseIdOrderByCaseCommandSequenceDesc(CASE_ID))
                .thenReturn(Optional.empty());

        var first =
                service.accept(
                        CASE_ID,
                        "command.same",
                        command("a".repeat(64)),
                        user(),
                        "TRACE_first",
                        "REQ_first",
                        null);

        var commandCaptor = ArgumentCaptor.forClass(CaseCommandEntity.class);
        verify(commandRepository).save(commandCaptor.capture());
        CaseCommandEntity stored = commandCaptor.getValue();
        assertThat(first.command().caseCommandSequence()).isEqualTo(1);
        assertThat(first.commandStatus()).isEqualTo("PENDING_ORCHESTRATION");
        assertThat(first.command().traceparent())
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        verify(outboxRepository).save(any(CaseCommandOutboxEntity.class));
        verify(deliveryTrigger).deliveryRequested(any());

        when(commandRepository.findByTenantSurrogateAndCommandId(
                        "legacy-default", "command.same"))
                .thenReturn(Optional.of(stored));
        var replay =
                service.accept(
                        CASE_ID,
                        "command.same",
                        command("a".repeat(64)),
                        user(),
                        "TRACE_retry",
                        "REQ_retry",
                        "00-11111111111111111111111111111111-2222222222222222-01");

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.command().requestHash()).isEqualTo(first.command().requestHash());
        verify(outboxRepository, times(1)).save(any(CaseCommandOutboxEntity.class));
    }

    @Test
    void auditsAReusedCommandIdWithADifferentHash() {
        arrangeWritableProjection();
        when(commandRepository.findByTenantSurrogateAndCommandId(
                        "legacy-default", "command.conflict"))
                .thenReturn(Optional.empty());
        when(commandRepository.findFirstByCaseIdOrderByCaseCommandSequenceDesc(CASE_ID))
                .thenReturn(Optional.empty());
        service.accept(
                CASE_ID,
                "command.conflict",
                command("a".repeat(64)),
                user(),
                "TRACE_first",
                "REQ_first",
                null);

        var commandCaptor = ArgumentCaptor.forClass(CaseCommandEntity.class);
        verify(commandRepository).save(commandCaptor.capture());
        when(commandRepository.findByTenantSurrogateAndCommandId(
                        "legacy-default", "command.conflict"))
                .thenReturn(Optional.of(commandCaptor.getValue()));

        assertThatThrownBy(
                        () ->
                                service.accept(
                                        CASE_ID,
                                        "command.conflict",
                                        command("b".repeat(64)),
                                        user(),
                                        "TRACE_conflict",
                                        "REQ_conflict",
                                        null))
                .isInstanceOf(IdempotencyConflictException.class);

        var auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getOutcome()).isEqualTo("CONFLICT");
        assertThat(auditCaptor.getValue().getAction())
                .isEqualTo("CASE_COMMAND_IDEMPOTENCY_CONFLICT");
        assertThat(auditCaptor.getValue().getMetadataJson())
                .contains("COMMAND_ID_HASH_MISMATCH");
    }

    @Test
    void rejectsAnImpersonatingPartyBeforeReadingTheCommandLedger() {
        var intruder = new AuthenticatedActor("user-intruder", ActorRole.USER);

        assertThatThrownBy(
                        () ->
                                service.accept(
                                        CASE_ID,
                                        "command.hidden",
                                        command("a".repeat(64)),
                                        intruder,
                                        "TRACE_intruder",
                                        "REQ_intruder",
                                        null))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(commandRepository, outboxRepository, auditLogRepository);
        verify(projectionRepository, never()).findById(any());
    }

    @Test
    void mapsConcurrentRevisionReservationToRetryableCaseStatusConflict() {
        when(disputeCase.getId()).thenReturn(CASE_ID);
        when(caseRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(disputeCase));
        when(roomEpochRepository.findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                        CASE_ID, RoomType.EVIDENCE, 0))
                .thenThrow(new OptimisticLockingFailureException("concurrent room epoch update"));

        assertThatThrownBy(
                        () ->
                                service.accept(
                                        CASE_ID,
                                        "command.concurrent-revision",
                                        command("a".repeat(64)),
                                        user(),
                                        "TRACE_concurrent_revision",
                                        "REQ_concurrent_revision",
                                        null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.errorCode())
                                    .isEqualTo(ErrorCode.CASE_STATUS_INVALID);
                            assertThat(exception.getMessage())
                                    .isEqualTo(
                                            "expected process revision is already reserved by an active command");
                            assertThat(exception.details())
                                    .containsEntry("case_id", CASE_ID)
                                    .containsEntry("expected_process_revision", 0L);
                        });

        verify(commandRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void evidenceOpeningAndSubmissionPersistStableActorScopePairWhileAdjacentCommandsStaySingleton() {
        arrangeWritableProjection();
        when(disputeCase.getMerchantId()).thenReturn("merchant-command");

        var opening =
                accept(
                        "command.scope.opening",
                        CommandType.EVIDENCE_OPENING,
                        RoomType.EVIDENCE,
                        "a".repeat(64),
                        user());
        var submission =
                accept(
                        "command.scope.submission",
                        CommandType.EVIDENCE_SUBMIT,
                        RoomType.EVIDENCE,
                        "b".repeat(64),
                        user());
        var merchantOpening =
                accept(
                        "command.scope.merchant",
                        CommandType.EVIDENCE_OPENING,
                        RoomType.EVIDENCE,
                        "c".repeat(64),
                        new AuthenticatedActor("merchant-command", ActorRole.MERCHANT));
        var completion =
                accept(
                        "command.scope.completion",
                        CommandType.PARTY_EVIDENCE_COMPLETE,
                        RoomType.EVIDENCE,
                        "d".repeat(64),
                        user());

        arrangeWritableProjection(RoomType.HEARING);
        var hearing =
                accept(
                        "command.scope.hearing",
                        CommandType.HEARING_STATEMENT,
                        RoomType.HEARING,
                        "e".repeat(64),
                        user());

        assertThat(opening.actorRef()).isEqualTo(submission.actorRef());
        assertThat(opening.actorRef().actorScopes())
                .containsExactly(
                        "case:" + CASE_ID + ":command:EVIDENCE_OPENING",
                        "case:" + CASE_ID + ":command:EVIDENCE_SUBMIT");
        assertThat(merchantOpening.actorRef()).isNotEqualTo(opening.actorRef());
        assertThat(merchantOpening.actorRef().actorId()).isEqualTo("merchant-command");
        assertThat(merchantOpening.actorRef().actorScopes())
                .containsExactlyElementsOf(opening.actorRef().actorScopes());
        assertThat(completion.actorRef().actorScopes())
                .containsExactly("case:" + CASE_ID + ":command:PARTY_EVIDENCE_COMPLETE");
        assertThat(hearing.actorRef().actorScopes())
                .containsExactly("case:" + CASE_ID + ":command:HEARING_STATEMENT");
    }

    private void arrangeWritableProjection() {
        arrangeWritableProjection(RoomType.EVIDENCE);
    }

    private void arrangeWritableProjection(RoomType roomType) {
        when(disputeCase.getId()).thenReturn(CASE_ID);
        when(caseRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(disputeCase));
        when(projectionRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(projection));
        when(projection.getCaseId()).thenReturn(CASE_ID);
        when(projection.getTenantSurrogate()).thenReturn("legacy-default");
        when(projection.getCurrentRoom()).thenReturn(roomType.name());
        when(projection.getWriterMode()).thenReturn(WriterMode.SHADOW);
        when(projection.getWriterActivationStatus()).thenReturn(WriterActivationStatus.READY);
        when(projection.getProcessRevision()).thenReturn(0L);
        when(projection.getRoomEpoch()).thenReturn(0L);
        when(projection.getFencingToken()).thenReturn(1L);
        when(projection.getTemporalWorkflowId())
                .thenReturn(
                        CaseProcessWorkflowProtocol.caseWorkflowId(
                                "legacy-default", CASE_ID));
        when(projection.getTemporalRunId()).thenReturn("run-command-service");
        when(projection.getTemporalBuildId()).thenReturn("build-command-service");
        when(roomEpochRepository.findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                        CASE_ID, roomType, 0))
                .thenReturn(Optional.of(roomEpoch));
        when(roomEpoch.getCaseId()).thenReturn(CASE_ID);
        when(roomEpoch.getTenantSurrogate()).thenReturn("legacy-default");
        when(roomEpoch.getRoomType()).thenReturn(roomType);
        when(roomEpoch.getRoomEpoch()).thenReturn(0L);
        when(roomEpoch.getLifecycleStatus()).thenReturn(EpochLifecycleStatus.ACTIVE);
        when(roomEpoch.getProvisioningStatus()).thenReturn(EpochProvisioningStatus.READY);
        when(roomEpoch.getWriterMode()).thenReturn(WriterMode.SHADOW);
        when(roomEpoch.getProcessRevision()).thenReturn(0L);
        when(roomEpoch.getFencingToken()).thenReturn(1L);
        when(roomEpoch.getTemporalWorkflowId())
                .thenReturn(
                        CaseProcessWorkflowProtocol.caseWorkflowId(
                                "legacy-default", CASE_ID));
        when(roomEpoch.getTemporalRunId()).thenReturn("run-command-service");
        when(roomEpoch.getRoomTemporalWorkflowId())
                .thenReturn(
                        CaseProcessWorkflowProtocol.roomWorkflowId(
                                CASE_ID, roomType, 0));
        when(roomEpoch.getRoomTemporalRunId()).thenReturn("room-run-command-service");
        when(roomEpoch.getTemporalBuildId()).thenReturn("build-command-service");
    }

    private static AcceptCaseCommand command(String payloadHash) {
        return command(CommandType.EVIDENCE_SUBMIT, RoomType.EVIDENCE, payloadHash);
    }

    private static AcceptCaseCommand command(
            CommandType commandType, RoomType roomType, String payloadHash) {
        return new AcceptCaseCommand(
                commandType,
                roomType,
                0,
                new PayloadRef(
                        "evidence-command.v1",
                        "urn:command:payload",
                        payloadHash,
                        128),
                0,
                NOW.plusSeconds(3600));
    }

    private com.example.dispute.workflow.contract.v1.CaseCommandRef accept(
            String commandId,
            CommandType commandType,
            RoomType roomType,
            String payloadHash,
            AuthenticatedActor actor) {
        return service.accept(
                        CASE_ID,
                        commandId,
                        command(commandType, roomType, payloadHash),
                        actor,
                        "TRACE_scope",
                        "REQ_scope",
                        null)
                .command();
    }

    private static AuthenticatedActor user() {
        return new AuthenticatedActor("user-command", ActorRole.USER);
    }
}
