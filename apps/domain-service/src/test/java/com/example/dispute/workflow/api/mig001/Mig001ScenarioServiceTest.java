package com.example.dispute.workflow.api.mig001;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.casecore.application.DisputeImportService;
import com.example.dispute.casecore.application.ImportDisputeCommand;
import com.example.dispute.casecore.application.ImportedDisputeView;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.workflow.config.OrchestrationCutoverProperties;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.RoomEpochBootstrapOutboxEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.BootstrapOutboxStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.WriterActivationStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.RoomEpochBootstrapOutboxRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;

class Mig001ScenarioServiceTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final AuthenticatedActor SYSTEM =
            new AuthenticatedActor("mig001-test-driver", ActorRole.SYSTEM);

    private DisputeImportService importService;
    private FulfillmentCaseRepository caseRepository;
    private CaseProcessProjectionRepository projectionRepository;
    private CaseRoomEpochRepository epochRepository;
    private RoomEpochBootstrapOutboxRepository bootstrapRepository;
    private Mig001ScenarioTupleReader tupleReader;
    private Mig001ScenarioService service;

    @BeforeEach
    void setUp() {
        importService = mock(DisputeImportService.class);
        caseRepository = mock(FulfillmentCaseRepository.class);
        projectionRepository = mock(CaseProcessProjectionRepository.class);
        epochRepository = mock(CaseRoomEpochRepository.class);
        bootstrapRepository = mock(RoomEpochBootstrapOutboxRepository.class);
        tupleReader = new Mig001ScenarioTupleReader(
                caseRepository, projectionRepository, epochRepository, bootstrapRepository);
        service = new Mig001ScenarioService(
                new OrchestrationCutoverProperties(WriterMode.SHADOW, false, false),
                importService,
                tupleReader);
    }

    @Test
    void createsSyntheticEvidenceScenarioThroughTheImportFacade() {
        when(importService.importDispute(any(), eq(SYSTEM), any(), eq("trace-1"), eq("request-1")))
                .thenReturn(imported("CASE_MIG001"));
        stubStatusTuple("CASE_MIG001", TOKEN);

        Mig001ScenarioView result = service.create(TOKEN, SYSTEM, "trace-1", "request-1");

        ArgumentCaptor<ImportDisputeCommand> command = ArgumentCaptor.forClass(ImportDisputeCommand.class);
        verify(importService).importDispute(
                command.capture(), eq(SYSTEM), eq("mig001-scenario:" + TOKEN),
                eq("trace-1"), eq("request-1"));
        assertThat(command.getValue().sourceSystem()).isEqualTo("MIG001_SYNTHETIC");
        assertThat(command.getValue().externalCaseReference()).isEqualTo("mig001-" + TOKEN);
        assertThat(command.getValue().userId()).isEqualTo("user-local");
        assertThat(command.getValue().merchantId()).isEqualTo("merchant-local");
        assertThat(command.getValue().caseStatus()).isEqualTo(CaseStatus.EVIDENCE_OPEN);
        assertThat(command.getValue().currentRoom()).isEqualTo("EVIDENCE");
        assertThat(result.lifecycleStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void replayUsesTheSameBusinessIdentityAndReturnsTheSameTuple() {
        when(importService.importDispute(any(), eq(SYSTEM), any(), any(), any()))
                .thenReturn(imported("CASE_MIG001"));
        stubStatusTuple("CASE_MIG001", TOKEN);

        Mig001ScenarioView first = service.create(TOKEN, SYSTEM, "trace-1", "request-1");
        Mig001ScenarioView replay = service.create(TOKEN, SYSTEM, "trace-2", "request-2");

        assertThat(replay).isEqualTo(first);
        verify(importService).importDispute(any(), eq(SYSTEM), eq("mig001-scenario:" + TOKEN),
                eq("trace-1"), eq("request-1"));
        verify(importService).importDispute(any(), eq(SYSTEM), eq("mig001-scenario:" + TOKEN),
                eq("trace-2"), eq("request-2"));
    }

    @Test
    void rejectsNonShadowModeNonSystemAndUnboundCases() {
        assertThatThrownBy(() -> new Mig001ScenarioService(
                new OrchestrationCutoverProperties(WriterMode.TEMPORAL, false, false),
                importService,
                tupleReader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("new-epoch-mode=SHADOW");
        assertThatThrownBy(() -> service.status(
                "CASE_MIG001", new AuthenticatedActor("user-local", ActorRole.USER)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.create(
                "customer-order-123", SYSTEM, "trace", "request"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128-bit lowercase hex token");

        FulfillmentCaseEntity forged = mock(FulfillmentCaseEntity.class);
        when(forged.getSourceSystem()).thenReturn("MIG001_SYNTHETIC");
        when(forged.getExternalCaseRef()).thenReturn("mig001-" + TOKEN);
        when(caseRepository.findById("CASE_FORGED")).thenReturn(Optional.of(forged));
        assertThatThrownBy(() -> service.status("CASE_FORGED", SYSTEM))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("idempotency binding not found");
    }

    @Test
    void rejectsAProjectionEpochBootstrapMismatch() {
        stubStatusTuple("CASE_MIG001", TOKEN);
        when(bootstrapRepository.findByEpochId("epoch-1")).thenAnswer(ignored -> {
            RoomEpochBootstrapOutboxEntity mismatched = bootstrap("OTHER_TENANT");
            return Optional.of(mismatched);
        });

        assertThatThrownBy(() -> service.status("CASE_MIG001", SYSTEM))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("tuple is inconsistent");
    }

    @Test
    void registersOnlyWithProfilePropertyAndShadowMode() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(Mig001ScenarioService.class)
                .withBean(DisputeImportService.class, () -> importService)
                .withBean(Mig001ScenarioTupleReader.class, () -> tupleReader);

        runner.withBean(OrchestrationCutoverProperties.class,
                        () -> new OrchestrationCutoverProperties(WriterMode.SHADOW, false, false))
                .run(context -> assertThat(context).doesNotHaveBean(Mig001ScenarioService.class));
        runner.withPropertyValues("spring.profiles.active=mig001-driver")
                .withBean(OrchestrationCutoverProperties.class,
                        () -> new OrchestrationCutoverProperties(WriterMode.SHADOW, false, false))
                .run(context -> assertThat(context).doesNotHaveBean(Mig001ScenarioService.class));
        runner.withPropertyValues("spring.profiles.active=mig001-driver",
                        "app.orchestration.mig001-driver-enabled=true")
                .withBean(OrchestrationCutoverProperties.class,
                        () -> new OrchestrationCutoverProperties(WriterMode.SHADOW, false, false))
                .run(context -> assertThat(context).hasSingleBean(Mig001ScenarioService.class));
        runner.withPropertyValues("spring.profiles.active=mig001-driver",
                        "app.orchestration.mig001-driver-enabled=true")
                .withBean(OrchestrationCutoverProperties.class,
                        () -> new OrchestrationCutoverProperties(WriterMode.LEGACY, false, false))
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    private void stubStatusTuple(String caseId, String scenarioId) {
        FulfillmentCaseEntity dispute = mock(FulfillmentCaseEntity.class);
        when(dispute.getId()).thenReturn(caseId);
        when(dispute.getSourceSystem()).thenReturn("MIG001_SYNTHETIC");
        when(dispute.getExternalCaseRef()).thenReturn("mig001-" + scenarioId);
        when(caseRepository.findById(caseId)).thenReturn(Optional.of(dispute));
        when(caseRepository.findByCreationIdempotencyKey("mig001-scenario:" + scenarioId))
                .thenReturn(Optional.of(dispute));

        CaseProcessProjectionEntity projection = mock(CaseProcessProjectionEntity.class);
        when(projection.getCaseId()).thenReturn(caseId);
        when(projection.getTenantSurrogate()).thenReturn("legacy-default");
        when(projection.getRoomEpoch()).thenReturn(0L);
        when(projection.getProcessRevision()).thenReturn(0L);
        when(projection.getFencingToken()).thenReturn(1L);
        when(projection.getWriterMode()).thenReturn(WriterMode.SHADOW);
        when(projection.getWriterActivationStatus()).thenReturn(WriterActivationStatus.PREPARING);
        when(projection.getTemporalWorkflowId()).thenReturn("case-workflow-1");
        when(projectionRepository.findById(caseId)).thenReturn(Optional.of(projection));

        CaseRoomEpochEntity epoch = mock(CaseRoomEpochEntity.class);
        when(epoch.getId()).thenReturn("epoch-1");
        when(epoch.getCaseId()).thenReturn(caseId);
        when(epoch.getTenantSurrogate()).thenReturn("legacy-default");
        when(epoch.getRoomId()).thenReturn("ROOM_MIG001");
        when(epoch.getRoomType()).thenReturn(RoomType.EVIDENCE);
        when(epoch.getRoomEpoch()).thenReturn(0L);
        when(epoch.getProcessRevision()).thenReturn(0L);
        when(epoch.getRoomRevision()).thenReturn(0L);
        when(epoch.getFencingToken()).thenReturn(1L);
        when(epoch.getWriterMode()).thenReturn(WriterMode.SHADOW);
        when(epoch.getLifecycleStatus()).thenReturn(EpochLifecycleStatus.ACTIVE);
        when(epoch.getProvisioningStatus()).thenReturn(EpochProvisioningStatus.PENDING);
        when(epoch.getTemporalWorkflowId()).thenReturn("case-workflow-1");
        when(epoch.getRoomTemporalWorkflowId()).thenReturn("room-workflow-1");
        when(epochRepository.findByCaseIdAndRoomTypeAndRoomEpoch(caseId, RoomType.EVIDENCE, 0L))
                .thenReturn(Optional.of(epoch));
        RoomEpochBootstrapOutboxEntity bootstrap = bootstrap("legacy-default");
        when(bootstrapRepository.findByEpochId("epoch-1"))
                .thenReturn(Optional.of(bootstrap));
    }

    private RoomEpochBootstrapOutboxEntity bootstrap(String tenant) {
        RoomEpochBootstrapOutboxEntity bootstrap = mock(RoomEpochBootstrapOutboxEntity.class);
        when(bootstrap.getEpochId()).thenReturn("epoch-1");
        when(bootstrap.getCaseId()).thenReturn("CASE_MIG001");
        when(bootstrap.getTenantSurrogate()).thenReturn(tenant);
        when(bootstrap.getRoomType()).thenReturn(RoomType.EVIDENCE);
        when(bootstrap.getRoomEpoch()).thenReturn(0L);
        when(bootstrap.getFencingToken()).thenReturn(1L);
        when(bootstrap.getWriterMode()).thenReturn(WriterMode.SHADOW);
        when(bootstrap.getCaseWorkflowId()).thenReturn("case-workflow-1");
        when(bootstrap.getRoomWorkflowId()).thenReturn("room-workflow-1");
        when(bootstrap.getUpdateId()).thenReturn("bootstrap-update-1");
        when(bootstrap.getOutboxStatus()).thenReturn(BootstrapOutboxStatus.PENDING);
        return bootstrap;
    }

    private static ImportedDisputeView imported(String caseId) {
        return new ImportedDisputeView(caseId, "ORDER", "AFTERSALE", "LOGISTICS", "user-local",
                "merchant-local", "MIG001_REGRESSION", "EXTERNAL", "MIG001_SYNTHETIC",
                "mig001-" + TOKEN, RiskLevel.LOW, "MIG-001 synthetic regression scenario",
                "Synthetic non-PII scenario.", CaseStatus.EVIDENCE_OPEN, "EVIDENCE", null, null, "USER");
    }
}
