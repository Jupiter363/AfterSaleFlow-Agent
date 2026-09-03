package com.example.dispute.workflow.projection;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.Incomplete;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReconciliationTarget;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.Unavailable;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.Verified;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.infrastructure.projection.SdkTemporalAuthoritativeProcessStateReader;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildKind;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessSnapshot;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import com.example.dispute.workflow.temporal.caseprocess.ProvisioningCommitment;
import io.temporal.api.common.v1.Memo;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.history.v1.WorkflowPropertiesModifiedEventAttributes;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SdkTemporalAuthoritativeProcessStateReaderTest {

    private static final String TENANT = "tenant-reader";
    private static final String CASE_ID = "CASE_Reader";
    private static final String WORKFLOW_ID =
            CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
    private static final String ROOM_WORKFLOW_ID =
            CaseProcessWorkflowProtocol.roomWorkflowId(CASE_ID, RoomType.EVIDENCE, 2);
    private static final String FIRST_RUN_ID = "run-reader-first";
    private static final String CURRENT_RUN_ID = "run-reader-current";
    private static final String ROOM_RUN_ID = "run-room-first";
    private static final String MEMO_KEY = "case_process_authority_checkpoint_v1";
    private static final ReconciliationTarget TARGET =
            new ReconciliationTarget(TENANT, CASE_ID, WORKFLOW_ID);
    private static final DataConverter CONVERTER = DefaultDataConverter.STANDARD_INSTANCE;

    @Mock private WorkflowClient workflowClient;
    @Mock private WorkflowClientOptions workflowClientOptions;
    @Mock private CaseProcessWorkflow workflow;

    private SdkTemporalAuthoritativeProcessStateReader reader;

    @BeforeEach
    void setUp() {
        when(workflowClient.getOptions()).thenReturn(workflowClientOptions);
        when(workflowClientOptions.getDataConverter()).thenReturn(CONVERTER);
        reader = new SdkTemporalAuthoritativeProcessStateReader(workflowClient);
        when(workflowClient.newWorkflowStub(
                        CaseProcessWorkflow.class, TARGET.temporalWorkflowId()))
                .thenReturn(workflow);
    }

    @Test
    void queryWithoutAHistoryBackedCommitmentRemainsIncomplete() {
        when(workflow.state()).thenReturn(observationSnapshot(TARGET.caseId()));

        var result = reader.read(TARGET);

        assertThat(result).isInstanceOf(Incomplete.class);
        Incomplete incomplete = (Incomplete) result;
        assertThat(incomplete.reasonCode())
                .isEqualTo("TEMPORAL_PROVISIONING_COMMITMENT_MISSING");
        assertThat(incomplete.observation().macroPhase())
                .isEqualTo("CONTROL_PLANE_SHADOW");
        assertThat(incomplete.observation().verifiedFirstExecutionRunId()).isNull();
        assertThat(incomplete.observation().verifiedActiveChildRunId()).isNull();
        assertThat(incomplete.observation().activeRoomRevision()).isEqualTo(12);
        assertThat(incomplete.observation().activeFencingToken()).isEqualTo(9);
        assertThat(incomplete.observation().processRevision()).isEqualTo(7);
        assertThat(incomplete.observation().lastCommandSequence()).isEqualTo(11);
        assertThat(incomplete.observation().lastCaseEventSequence()).isEqualTo(20);
    }

    @Test
    void progressedSnapshotCarriesOnlyHistoryVerifiedRunBindings() {
        ProvisionRoomEpoch request = request();
        ProvisionRoomEpochReceipt receipt = receipt(request);
        when(workflow.state()).thenReturn(progressedSnapshot(request));
        when(workflow.provisioningCommitment())
                .thenReturn(
                        new ProvisioningCommitment(
                                request.updateId(), request.payloadSha256(), request, receipt));
        when(workflowClient.fetchHistory(WORKFLOW_ID, FIRST_RUN_ID))
                .thenReturn(history(receipt, true));

        var result = reader.read(TARGET);

        assertThat(result).isInstanceOf(Incomplete.class);
        Incomplete incomplete = (Incomplete) result;
        assertThat(incomplete.reasonCode())
                .isEqualTo("CASE_PROCESS_STATE_NOT_REPAIR_COMPLETE");
        assertThat(incomplete.observation().verifiedFirstExecutionRunId())
                .isEqualTo(FIRST_RUN_ID);
        assertThat(incomplete.observation().verifiedActiveChildRunId())
                .isEqualTo(ROOM_RUN_ID);
        assertThat(incomplete.observation().activeRoomRevision()).isEqualTo(4);
        assertThat(incomplete.observation().processRevision()).isEqualTo(8);
    }

    @Test
    void returnsVerifiedOnlyWhenTheFirstRunAndAuthorityMemoMatchTheCommitment() {
        ProvisionRoomEpoch request = request();
        ProvisionRoomEpochReceipt receipt = receipt(request);
        ProvisioningCommitment commitment =
                new ProvisioningCommitment(
                        request.updateId(), request.payloadSha256(), request, receipt);
        when(workflow.state()).thenReturn(completeSnapshot(request));
        when(workflow.provisioningCommitment()).thenReturn(commitment);
        when(workflowClient.fetchHistory(WORKFLOW_ID, FIRST_RUN_ID))
                .thenReturn(history(receipt, true));

        var result = reader.read(TARGET);

        assertThat(result).isInstanceOf(Verified.class);
        Verified verified = (Verified) result;
        assertThat(verified.state().caseId()).isEqualTo(CASE_ID);
        assertThat(verified.state().fencingToken()).isEqualTo(request.fencingToken());
        assertThat(verified.state().temporalRunId()).isEqualTo(FIRST_RUN_ID);
        assertThat(verified.verificationRef())
                .startsWith("temporal:" + WORKFLOW_ID + ":" + FIRST_RUN_ID)
                .contains(":memo-2:");
    }

    @Test
    void aQueryCommitmentWithoutTheHistoryMemoCannotAuthorizeRepair() {
        ProvisionRoomEpoch request = request();
        ProvisionRoomEpochReceipt receipt = receipt(request);
        when(workflow.state()).thenReturn(completeSnapshot(request));
        when(workflow.provisioningCommitment())
                .thenReturn(
                        new ProvisioningCommitment(
                                request.updateId(), request.payloadSha256(), request, receipt));
        when(workflowClient.fetchHistory(WORKFLOW_ID, FIRST_RUN_ID))
                .thenReturn(history(receipt, false));

        assertThat(reader.read(TARGET))
                .isEqualTo(new Unavailable("TEMPORAL_AUTHORITY_MEMO_MISSING"));
    }

    @Test
    void aContinueAsNewRunUsesTheAuthorityMemoCarriedOnItsStartEvent() {
        ProvisionRoomEpoch request = request();
        ProvisionRoomEpochReceipt receipt = receipt(request);
        ProvisioningCommitment commitment =
                new ProvisioningCommitment(
                        request.updateId(), request.payloadSha256(), request, receipt);
        when(workflow.state()).thenReturn(completeSnapshot(request, CURRENT_RUN_ID));
        when(workflow.provisioningCommitment()).thenReturn(commitment);
        when(workflowClient.fetchHistory(WORKFLOW_ID, FIRST_RUN_ID))
                .thenReturn(history(receipt, true));
        when(workflowClient.fetchHistory(WORKFLOW_ID, CURRENT_RUN_ID))
                .thenReturn(continuedHistory(receipt));

        var result = reader.read(TARGET);

        assertThat(result).isInstanceOf(Verified.class);
        assertThat(((Verified) result).verificationRef())
                .contains(":" + FIRST_RUN_ID + ":" + CURRENT_RUN_ID + ":memo-1:");
    }

    @Test
    void rejectsAQueryResponseFromAnotherCase() {
        when(workflow.state()).thenReturn(observationSnapshot("CASE_Other"));

        var result = reader.read(TARGET);

        assertThat(result).isEqualTo(new Unavailable("TEMPORAL_QUERY_SCOPE_MISMATCH"));
    }

    private static ProvisionRoomEpoch request() {
        return new ProvisionRoomEpoch(
                ProvisionRoomEpoch.SCHEMA_VERSION,
                "CRE_reader",
                TENANT,
                CASE_ID,
                "ROOM_reader",
                RoomType.EVIDENCE,
                2,
                7,
                3,
                9,
                "EVIDENCE",
                "EVIDENCE",
                "OPEN",
                WriterMode.TEMPORAL,
                WORKFLOW_ID,
                ROOM_WORKFLOW_ID,
                "room-epoch-selection.v1",
                "case-process-contract.v1",
                CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                "control-v1",
                "evidence.v2",
                "1.0.0",
                "checkpoint.v1",
                "agent-stream.v2",
                0,
                0,
                1,
                1,
                Instant.parse("2026-07-18T15:00:00Z"),
                null,
                null,
                Instant.parse("2026-07-18T12:00:00Z"));
    }

    private static ProvisionRoomEpochReceipt receipt(ProvisionRoomEpoch request) {
        return new ProvisionRoomEpochReceipt(
                "provision-room-epoch-receipt.v1",
                request.epochId(),
                request.tenantSurrogate(),
                request.caseId(),
                request.roomId(),
                request.roomType(),
                request.roomEpoch(),
                request.fencingToken(),
                request.initialProcessRevision(),
                request.initialRoomRevision(),
                request.macroPhase(),
                request.currentRoom(),
                request.roomPhase(),
                request.projectedDeadlineAt(),
                request.writerMode(),
                request.selectionSchemaVersion(),
                request.processContractVersion(),
                request.workflowType(),
                request.temporalBuildId(),
                request.graphKey(),
                request.graphVersion(),
                request.checkpointSchemaVersion(),
                request.streamProtocol(),
                request.lastCommandSequence(),
                request.lastCaseEventSequence(),
                request.firstCommandSequence(),
                request.firstCaseEventSequence(),
                request.projectionRef(),
                request.projectionSha256(),
                request.requestedAt(),
                request.caseWorkflowId(),
                FIRST_RUN_ID,
                request.roomWorkflowId(),
                ROOM_RUN_ID,
                request.payloadSha256());
    }

    private static WorkflowExecutionHistory history(
            ProvisionRoomEpochReceipt receipt, boolean includeMemo) {
        History.Builder history =
                History.newBuilder()
                        .addEvents(
                                HistoryEvent.newBuilder()
                                        .setEventId(1)
                                        .setEventType(EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
                                        .setWorkflowExecutionStartedEventAttributes(
                                                WorkflowExecutionStartedEventAttributes.newBuilder()
                                                        .setFirstExecutionRunId(FIRST_RUN_ID)
                                                        .setOriginalExecutionRunId(FIRST_RUN_ID)));
        if (includeMemo) {
            history.addEvents(
                    HistoryEvent.newBuilder()
                            .setEventId(2)
                            .setEventType(EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED)
                            .setWorkflowPropertiesModifiedEventAttributes(
                                    WorkflowPropertiesModifiedEventAttributes.newBuilder()
                                            .setUpsertedMemo(
                                                    Memo.newBuilder()
                                                            .putFields(
                                                                    MEMO_KEY,
                                                                    CONVERTER.toPayload(receipt)
                                                                            .orElseThrow()))));
        }
        return new WorkflowExecutionHistory(history.build(), WORKFLOW_ID);
    }

    private static WorkflowExecutionHistory continuedHistory(
            ProvisionRoomEpochReceipt receipt) {
        History history =
                History.newBuilder()
                        .addEvents(
                                HistoryEvent.newBuilder()
                                        .setEventId(1)
                                        .setEventType(EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
                                        .setWorkflowExecutionStartedEventAttributes(
                                                WorkflowExecutionStartedEventAttributes.newBuilder()
                                                        .setFirstExecutionRunId(FIRST_RUN_ID)
                                                        .setOriginalExecutionRunId(FIRST_RUN_ID)
                                                        .setMemo(
                                                                Memo.newBuilder()
                                                                        .putFields(
                                                                                MEMO_KEY,
                                                                                CONVERTER
                                                                                        .toPayload(
                                                                                                receipt)
                                                                                        .orElseThrow()))))
                        .build();
        return new WorkflowExecutionHistory(history, WORKFLOW_ID);
    }

    private static CaseProcessSnapshot completeSnapshot(ProvisionRoomEpoch request) {
        return completeSnapshot(request, FIRST_RUN_ID);
    }

    private static CaseProcessSnapshot completeSnapshot(
            ProvisionRoomEpoch request, String workflowRunId) {
        return new CaseProcessSnapshot(
                "case-process-snapshot.v1",
                WORKFLOW_ID,
                workflowRunId,
                TENANT,
                CASE_ID,
                "CONTROL_PLANE_SHADOW",
                request.roomType(),
                request.roomEpoch(),
                request.roomWorkflowId(),
                request.initialProcessRevision(),
                request.firstCommandSequence(),
                request.firstCaseEventSequence(),
                0,
                0,
                0,
                0,
                0,
                request.lastCommandSequence(),
                request.lastCaseEventSequence(),
                0,
                "NONE",
                null,
                List.of(),
                request.fencingToken(),
                ROOM_RUN_ID,
                1,
                request.payloadSha256());
    }

    private static CaseProcessSnapshot observationSnapshot(String caseId) {
        return new CaseProcessSnapshot(
                "case-process-snapshot.v1",
                WORKFLOW_ID,
                FIRST_RUN_ID,
                TENANT,
                caseId,
                "CONTROL_PLANE_SHADOW",
                RoomType.EVIDENCE,
                2,
                ROOM_WORKFLOW_ID,
                7,
                12,
                21,
                11,
                20,
                0,
                0,
                8,
                11,
                20,
                1,
                null,
                null,
                List.of("command-reader"),
                9,
                ROOM_RUN_ID,
                0,
                null,
                ActiveChildKind.TARGET_TYPED_ROOM,
                "target-room-selection.v1",
                "EvidenceRoomWorkflow",
                "room-build-reader",
                12L,
                null);
    }

    private static CaseProcessSnapshot progressedSnapshot(ProvisionRoomEpoch request) {
        return new CaseProcessSnapshot(
                "case-process-snapshot.v1",
                WORKFLOW_ID,
                FIRST_RUN_ID,
                TENANT,
                CASE_ID,
                "CONTROL_PLANE_SHADOW",
                request.roomType(),
                request.roomEpoch(),
                request.roomWorkflowId(),
                request.initialProcessRevision() + 1,
                request.firstCommandSequence() + 1,
                request.firstCaseEventSequence() + 2,
                1,
                2,
                0,
                0,
                1,
                1,
                2,
                0,
                "NONE",
                null,
                List.of("command-reader"),
                request.fencingToken(),
                ROOM_RUN_ID,
                1,
                request.payloadSha256(),
                ActiveChildKind.TARGET_TYPED_ROOM,
                request.selectionSchemaVersion(),
                "EvidenceRoomWorkflow",
                "room-build-reader",
                request.initialRoomRevision() + 1,
                null);
    }
}
