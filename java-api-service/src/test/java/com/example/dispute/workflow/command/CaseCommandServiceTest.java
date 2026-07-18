package com.example.dispute.workflow.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

@ExtendWith(MockitoExtension.class)
class CaseCommandServiceTest {

    private static final String CASE_ID = "CASE_CommandService";
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

    private void arrangeWritableProjection() {
        when(disputeCase.getId()).thenReturn(CASE_ID);
        when(caseRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(disputeCase));
        when(projectionRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(projection));
        when(projection.getTenantSurrogate()).thenReturn("legacy-default");
        when(projection.getCurrentRoom()).thenReturn("EVIDENCE");
        when(projection.getWriterMode()).thenReturn(WriterMode.SHADOW);
        when(projection.getProcessRevision()).thenReturn(0L);
        when(projection.getRoomEpoch()).thenReturn(0L);
        when(projection.getFencingToken()).thenReturn(1L);
        when(projection.getTemporalWorkflowId())
                .thenReturn(
                        CaseProcessWorkflowProtocol.caseWorkflowId(
                                "legacy-default", CASE_ID));
        when(roomEpochRepository.findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                        CASE_ID, RoomType.EVIDENCE, 0))
                .thenReturn(Optional.of(roomEpoch));
        when(roomEpoch.getTenantSurrogate()).thenReturn("legacy-default");
        when(roomEpoch.getLifecycleStatus()).thenReturn(EpochLifecycleStatus.ACTIVE);
        when(roomEpoch.getWriterMode()).thenReturn(WriterMode.SHADOW);
        when(roomEpoch.getProcessRevision()).thenReturn(0L);
        when(roomEpoch.getFencingToken()).thenReturn(1L);
        when(roomEpoch.getTemporalWorkflowId())
                .thenReturn(
                        CaseProcessWorkflowProtocol.caseWorkflowId(
                                "legacy-default", CASE_ID));
    }

    private static AcceptCaseCommand command(String payloadHash) {
        return new AcceptCaseCommand(
                CommandType.EVIDENCE_SUBMIT,
                RoomType.EVIDENCE,
                0,
                new PayloadRef(
                        "evidence-command.v1",
                        "urn:command:payload",
                        payloadHash,
                        128),
                0,
                NOW.plusSeconds(3600));
    }

    private static AuthenticatedActor user() {
        return new AuthenticatedActor("user-command", ActorRole.USER);
    }
}
