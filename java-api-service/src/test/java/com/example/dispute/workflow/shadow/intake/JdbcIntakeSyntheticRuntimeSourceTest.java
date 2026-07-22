package com.example.dispute.workflow.shadow.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.authority.epoch.EpochSelectionHasher;
import com.example.dispute.workflow.application.authority.epoch.EpochSelectionHasher.SelectionHashInput;
import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher.SnapshotRequest;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration.ActorScope;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory.IssueRequest;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory.VersionPins;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.infrastructure.persistence.authority.bridge.IntakeAuthorityInvariantException;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.Classification;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.Dimension;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.ObservedValue;
import com.example.dispute.workflow.shadow.intake.IntakeShadowParityService.ParitySnapshot;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticAdmissionReader.PersistedAdmission;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticAdmissionReader.AdmissionPins;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticGraphMaterialSource.ArtifactMaterial;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticGraphMaterialSource.GraphPlan;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityMaterialSource.ParityMaterial;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticSnapshotMaterialSource.SnapshotMaterial;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.GraphArtifactQuery;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocationMode;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ImmutablePayloadRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.OperationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeGraphExecutionRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcIntakeSyntheticRuntimeSourceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final String TENANT = "tenant-synthetic-source";
    private static final String CASE_ID = "CASE_SYNTHETIC_SOURCE";
    private static final String COMMAND_ID = "CMD_SYNTHETIC_SOURCE";
    private static final String EPOCH_ID = "EPOCH_SYNTHETIC_SOURCE";
    private static final String CASE_COMMAND_ID = "CASE_COMMAND_SYNTHETIC_SOURCE";
    private static final String REGISTRATION_ID = "REG_SYNTHETIC_SOURCE";
    private static final String THREAD_ID = "grt.v1." + "a".repeat(32);
    private static final String AGENT_SESSION_ID = "AGENT_SESSION_SYNTHETIC_SOURCE";
    private static final String REQUEST_HASH = hash(11);
    private static final String PAYLOAD_HASH = hash(12);
    private static final String PAYLOAD_URI = "urn:intake:event:synthetic-source";
    private static final Instant ISSUED_AT = Instant.parse("2026-07-20T08:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-07-25T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-21T08:00:00Z"), ZoneOffset.UTC);

    private DataSource dataSource;
    private Connection connection;
    private IntakeSyntheticAdmissionReader admissionReader;
    private IntakeSyntheticSnapshotMaterialSource snapshots;
    private IntakeSyntheticGraphMaterialSource graph;
    private IntakeSyntheticParityMaterialSource parity;
    private JdbcIntakeSyntheticRuntimeSource source;
    private IntakePrivateThreadRegistration registration;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        admissionReader = mock(IntakeSyntheticAdmissionReader.class);
        snapshots = mock(IntakeSyntheticSnapshotMaterialSource.class);
        graph = mock(IntakeSyntheticGraphMaterialSource.class);
        parity = mock(IntakeSyntheticParityMaterialSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        registration = registration(THREAD_ID, REGISTRATION_ID);
        source = new JdbcIntakeSyntheticRuntimeSource(
                dataSource, admissionReader, snapshots, graph, parity, CLOCK);
    }

    @Test
    void graphLoadUsesOneReadOnlyRepeatableReadSnapshotAndAuthoritativePlan() throws Exception {
        GraphExecutionRequest request = graphRequest();
        stubAdmission(request.envelope(), request.operationKey());
        stubGraphRows(registration, "ACTIVE", "ACTIVE", "REGISTERED", "INITIATOR", 2, false);
        GraphPlan plan = graphPlan();
        when(graph.loadPlan(any())).thenReturn(plan);

        var input = source.loadGraph(request);

        assertThat(input.command().threadBinding().registration()).isEqualTo(registration);
        assertThat(input.command().initialSnapshot().threadRegistrationId())
                .isEqualTo(REGISTRATION_ID);
        assertThat(input.command().event().payloadRef().sha256()).isEqualTo(PAYLOAD_HASH);
        assertThat(input.command().logicalRunId()).isEqualTo(plan.logicalRunId());
        assertThat(input.bindingContext().roomEpochId()).isEqualTo(EPOCH_ID);
        assertThat(input.attemptNo()).isEqualTo(1);
        verify(connection).setReadOnly(true);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
    }

    @Test
    void snapshotBodyComesOnlyFromInjectedAuthoritativeMaterialSource() throws Exception {
        SnapshotPublicationRequest request = snapshotRequest();
        stubAdmission(request.envelope(), request.operationKey());
        stubAuthorityRows(registration, "ACTIVE", "ACTIVE", "REGISTERED", "INITIATOR", 2, false);
        SnapshotMaterial material = new SnapshotMaterial(
                "SNAPSHOT_SYNTHETIC_SOURCE", 4, 6, List.of("EVENT_SYNTHETIC_SOURCE"),
                MAPPER.createObjectNode().put("case_id", CASE_ID),
                MAPPER.createObjectNode().put("room_phase", "OPEN"),
                List.of(), MAPPER.createObjectNode().put("schema_version", "intake-dossier.v2"),
                Instant.parse("2026-07-20T08:01:00Z"));
        when(snapshots.load(any())).thenReturn(material);

        var input = source.loadSnapshot(request);

        SnapshotRequest publication = input.publication();
        assertThat(publication.domainRevision()).isEqualTo(4);
        assertThat(publication.roomRevision()).isEqualTo(2);
        assertThat(publication.initialCaseFacts().path("case_id").asText()).isEqualTo(CASE_ID);
        verify(snapshots).load(any());
    }

    @Test
    void parityClassificationsComeOnlyFromInjectedAuthoritativeMaterialSource() throws Exception {
        TurnFinalizationRequest request = finalizationRequest();
        stubAdmission(request.envelope(), request.operationKey());
        stubAuthorityRows(registration, "ACTIVE", "ACTIVE", "REGISTERED", "INITIATOR", 2, false);
        ParityMaterial material = new ParityMaterial(
                paritySnapshot(20), paritySnapshot(40), IntakeDomainEventType.TURN_NEEDS_INPUT);
        when(parity.load(any())).thenReturn(material);

        var input = source.loadParity(request);

        assertThat(input.resultHash())
                .isEqualTo(request.graphExecution().graphExecutionRef().resultHash());
        assertThat(input.proposalHash())
                .isEqualTo(request.graphExecution().graphExecutionRef().proposalHash());
        assertThat(input.legacy()).isSameAs(material.legacy());
        verify(parity).load(any());
    }

    @Test
    void immutableGraphArtifactMetadataIsNeverSynthesizedFromGraphPointers() throws Exception {
        GraphExecutionRequest request = graphRequest();
        RoomGraphResult result = mock(RoomGraphResult.class);
        RoomGraphResult.ArtifactOperation operation = mock(RoomGraphResult.ArtifactOperation.class);
        ArtifactPointer pointer = new ArtifactPointer(
                "PROPOSAL_SYNTHETIC_SOURCE", "intake-turn-proposal.v2",
                "urn:intake:proposal:synthetic-source", hash(31));
        when(result.outputHash()).thenReturn(hash(30));
        when(result.artifactOperations()).thenReturn(List.of(operation));
        when(operation.artifact()).thenReturn(pointer);
        GraphArtifactQuery query = new GraphArtifactQuery(
                request, mock(com.example.dispute.workflow.contract.v1.RoomGraphCommand.class),
                result, "urn:intake:result:synthetic-source");
        stubAdmission(request.envelope(), request.operationKey());
        stubAuthorityRows(registration, "ACTIVE", "ACTIVE", "REGISTERED", "INITIATOR", 2, false);
        ArtifactMaterial material = new ArtifactMaterial(
                immutable("RESULT_SYNTHETIC_SOURCE", "GRAPH_RESULT", "room-graph-result.v1",
                        "urn:intake:result:synthetic-source", hash(30)),
                immutable(pointer.artifactId(), "INTAKE_PROPOSAL", pointer.schemaVersion(),
                        pointer.uri(), pointer.sha256()));
        when(graph.loadArtifacts(query)).thenReturn(material);

        var artifacts = source.loadGraphArtifacts(query);

        assertThat(artifacts.result()).isSameAs(material.result());
        assertThat(artifacts.proposal()).isSameAs(material.proposal());
    }

    @Test
    void staleFenceFailsBeforeAnyPrivateMaterialRead() throws Exception {
        SnapshotPublicationRequest request = snapshotRequest();
        stubAdmission(request.envelope(), request.operationKey());
        stubAuthorityRows(registration, "ACTIVE", "ACTIVE", "REGISTERED", "INITIATOR", 3, false);

        assertThatThrownBy(() -> source.loadSnapshot(request))
                .isInstanceOf(IntakeAuthorityInvariantException.class)
                .hasMessageContaining("fencing token");
        verifyNoInteractions(snapshots, graph, parity);
    }

    @Test
    void revokedAccessSessionFailsBeforeAnyPrivateMaterialRead() throws Exception {
        SnapshotPublicationRequest request = snapshotRequest();
        stubAdmission(request.envelope(), request.operationKey());
        stubAuthorityRows(registration, "REVOKED", "ACTIVE", "REGISTERED", "INITIATOR", 2, false);

        assertThatThrownBy(() -> source.loadSnapshot(request))
                .isInstanceOf(IntakeAuthorityInvariantException.class)
                .hasMessage("access session status mismatch");
        verifyNoInteractions(snapshots, graph, parity);
    }

    @Test
    void revokedAgentSessionFailsBeforeAnyPrivateMaterialRead() throws Exception {
        SnapshotPublicationRequest request = snapshotRequest();
        stubAdmission(request.envelope(), request.operationKey());
        stubAuthorityRows(registration, "ACTIVE", "REVOKED", "REGISTERED", "INITIATOR", 2, false);

        assertThatThrownBy(() -> source.loadSnapshot(request))
                .isInstanceOf(IntakeAuthorityInvariantException.class)
                .hasMessage("Agent Session status mismatch");
        verifyNoInteractions(snapshots, graph, parity);
    }

    @Test
    void retiredRegistrationFailsBeforeAnyPrivateMaterialRead() throws Exception {
        SnapshotPublicationRequest request = snapshotRequest();
        stubAdmission(request.envelope(), request.operationKey());
        stubAuthorityRows(registration, "ACTIVE", "ACTIVE", "RETIRED", "INITIATOR", 2, false);

        assertThatThrownBy(() -> source.loadSnapshot(request))
                .isInstanceOf(IntakeAuthorityInvariantException.class)
                .hasMessage("registration status mismatch");
        verifyNoInteractions(snapshots, graph, parity);
    }

    @Test
    void crossThreadAuthorityFailsClosed() throws Exception {
        SnapshotPublicationRequest request = snapshotRequest();
        stubAdmission(request.envelope(), request.operationKey());
        IntakePrivateThreadRegistration other = registration(
                "grt.v1." + "b".repeat(32), "REG_SYNTHETIC_OTHER");
        stubAuthorityRows(other, "ACTIVE", "ACTIVE", "REGISTERED", "INITIATOR", 2, false);

        assertThatThrownBy(() -> source.loadSnapshot(request))
                .isInstanceOf(IntakeAuthorityInvariantException.class)
                .hasMessage("admission registration mismatch");
        verifyNoInteractions(snapshots, graph, parity);
    }

    @Test
    void crossPartyAuthorityFailsClosedWithoutRoleInference() throws Exception {
        SnapshotPublicationRequest request = snapshotRequest();
        stubAdmission(request.envelope(), request.operationKey());
        stubAuthorityRows(registration, "ACTIVE", "ACTIVE", "REGISTERED", "RESPONDENT", 2, false);

        assertThatThrownBy(() -> source.loadSnapshot(request))
                .isInstanceOf(IntakeAuthorityInvariantException.class)
                .hasMessage("command party mismatch");
        verifyNoInteractions(snapshots, graph, parity);
    }

    @Test
    void ambiguousAuthorityCandidatesFailOnTheSecondRow() throws Exception {
        SnapshotPublicationRequest request = snapshotRequest();
        stubAdmission(request.envelope(), request.operationKey());
        stubAuthorityRows(registration, "ACTIVE", "ACTIVE", "REGISTERED", "INITIATOR", 2, true);

        assertThatThrownBy(() -> source.loadSnapshot(request))
                .isInstanceOf(IntakeAuthorityInvariantException.class)
                .hasMessage("synthetic runtime authority expected one row, found 2");
        verifyNoInteractions(snapshots, graph, parity);
    }

    @Test
    void replayReadsTheSameImmutableAuthorityWithoutConsumingIt() throws Exception {
        SnapshotPublicationRequest request = snapshotRequest();
        stubAdmissionReplay(request.envelope(), request.operationKey());
        stubAuthorityReplayRows(registration);
        SnapshotMaterial material = new SnapshotMaterial(
                "SNAPSHOT_SYNTHETIC_SOURCE", 4, 6, List.of("EVENT_SYNTHETIC_SOURCE"),
                MAPPER.createObjectNode().put("case_id", CASE_ID),
                MAPPER.createObjectNode().put("room_phase", "OPEN"),
                List.of(), MAPPER.createObjectNode().put("schema_version", "intake-dossier.v2"),
                Instant.parse("2026-07-20T08:01:00Z"));
        when(snapshots.load(any())).thenReturn(material);

        var first = source.loadSnapshot(request);
        var replay = source.loadSnapshot(request);

        assertThat(replay).isEqualTo(first);
        verify(admissionReader, times(2)).find(any(), any());
        verify(connection, times(2)).commit();
        verify(snapshots, times(2)).load(any());
    }

    @Test
    void admittedThreeAttemptBudgetAllowsAConsumedInfrastructureRetry() throws Exception {
        SnapshotPublicationRequest request = snapshotRequest(
                ActivityInvocationMode.INFRASTRUCTURE_RETRY, 1);
        stubAdmission(request.envelope(), request.operationKey());
        stubAuthorityRows(registration, "ACTIVE", "ACTIVE", "REGISTERED", "INITIATOR", 2, false);
        SnapshotMaterial material = snapshotMaterial();
        when(snapshots.load(any())).thenReturn(material);

        assertThat(source.loadSnapshot(request).publication().domainRevision()).isEqualTo(4);
    }

    @Test
    void admittedThreeAttemptBudgetAllowsReceiptOnlyReconciliationAtZero() throws Exception {
        SnapshotPublicationRequest request = snapshotRequest(
                ActivityInvocationMode.RECONCILE_ONLY, 0);
        stubAdmission(request.envelope(), request.operationKey());
        stubAuthorityRows(registration, "ACTIVE", "ACTIVE", "REGISTERED", "INITIATOR", 2, false);
        SnapshotMaterial material = snapshotMaterial();
        when(snapshots.load(any())).thenReturn(material);

        assertThat(source.loadSnapshot(request).publication().domainRevision()).isEqualTo(4);
    }

    @Test
    void persistedVerifiedAdmissionSurvivesIngressTokenExpirationUntilCommandDeadline()
            throws Exception {
        SnapshotPublicationRequest request = snapshotRequest();
        PersistedAdmission admitted = admission(request.envelope(), request.operationKey());
        assertThat(CLOCK.instant().getEpochSecond()).isGreaterThan(admitted.expiresAtEpochSeconds());
        when(admissionReader.find(any(), any())).thenReturn(List.of(admitted));
        stubAuthorityRows(registration, "ACTIVE", "ACTIVE", "REGISTERED", "INITIATOR", 2, false);
        when(snapshots.load(any())).thenReturn(snapshotMaterial());

        assertThat(source.loadSnapshot(request).publication().domainRevision()).isEqualTo(4);
    }

    @Test
    void ambiguousPersistedAdmissionsFailBeforeDomainOrMaterialReads() throws Exception {
        SnapshotPublicationRequest request = snapshotRequest();
        PersistedAdmission admitted = admission(request.envelope(), request.operationKey());
        when(admissionReader.find(any(), any())).thenReturn(List.of(admitted, admitted));

        assertThatThrownBy(() -> source.loadSnapshot(request))
                .isInstanceOf(IntakeAuthorityInvariantException.class)
                .hasMessage("synthetic admission expected one row, found 2");
        verifyNoInteractions(snapshots, graph, parity);
    }

    private void stubAdmission(ActivityEnvelope envelope, String operationKey) throws Exception {
        when(admissionReader.find(any(), any()))
                .thenReturn(List.of(admission(envelope, operationKey)));
    }

    private void stubAdmissionReplay(ActivityEnvelope envelope, String operationKey) throws Exception {
        PersistedAdmission admission = admission(envelope, operationKey);
        when(admissionReader.find(any(), any()))
                .thenReturn(List.of(admission), List.of(admission));
    }

    private PersistedAdmission admission(ActivityEnvelope envelope, String ignoredActivityOperationKey)
            throws Exception {
        RetryBudget admittedBudget = new RetryBudget("intake-retry-budget.v1", 2, 3, 1);
        SelectionFixture selection = selection();
        AdmissionPins pins = admissionPins(selection);
        long issuedAt = ISSUED_AT.getEpochSecond();
        return new PersistedAdmission(
                "intake-synthetic-activity-admission.v1",
                "AUTHENTICATED_SIGNED_SYNTHETIC",
                "VERIFIED",
                issuedAt,
                issuedAt,
                issuedAt + 50,
                EPOCH_ID,
                "PARTY_AUTHORITY_SYNTHETIC_SOURCE",
                CASE_COMMAND_ID,
                "PAYLOAD_AUTHORITY_SYNTHETIC_SOURCE",
                "ACCESS_SYNTHETIC_SOURCE",
                REGISTRATION_ID,
                TENANT,
                CASE_ID,
                "INTAKE",
                "SHADOW",
                1,
                2,
                "user-synthetic-source",
                ActorRole.USER,
                COMMAND_ID,
                1,
                IntakeCommandType.INTAKE_MESSAGE,
                IntakeParty.INITIATOR,
                registration.actorScopeHash(),
                PAYLOAD_URI,
                PAYLOAD_HASH,
                "intake.operation:" + CASE_ID + ":" + COMMAND_ID,
                REQUEST_HASH,
                2,
                THREAD_ID,
                AGENT_SESSION_ID,
                7,
                2,
                DEADLINE.toEpochMilli(),
                admittedBudget,
                "RUN_SYNTHETIC_SOURCE",
                "ATTEMPT_SYNTHETIC_SOURCE",
                selection.hash(),
                registration.registrationHash(),
                pins,
                pinsJson(pins),
                "urn:intake:parity-baseline:synthetic-source",
                hash(14),
                hash(13));
    }

    private void stubGraphRows(
            IntakePrivateThreadRegistration rowRegistration,
            String accessStatus,
            String agentStatus,
            String registrationStatus,
            String partyValue,
            long fence,
            boolean ambiguous) throws Exception {
        PreparedStatement authorityStatement = mock(PreparedStatement.class);
        PreparedStatement initialStatement = mock(PreparedStatement.class);
        ResultSet authority = mock(ResultSet.class);
        ResultSet initial = mock(ResultSet.class);
        when(connection.prepareStatement(anyString()))
                .thenReturn(authorityStatement, initialStatement);
        when(authorityStatement.executeQuery()).thenReturn(authority);
        when(initialStatement.executeQuery()).thenReturn(initial);
        stubAuthorityResult(authority, rowRegistration, accessStatus, agentStatus,
                registrationStatus, partyValue, fence, ambiguous, false);
        stubInitialResult(initial, rowRegistration, fence, false);
    }

    private void stubAuthorityRows(
            IntakePrivateThreadRegistration rowRegistration,
            String accessStatus,
            String agentStatus,
            String registrationStatus,
            String partyValue,
            long fence,
            boolean ambiguous) throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        stubAuthorityResult(result, rowRegistration, accessStatus, agentStatus,
                registrationStatus, partyValue, fence, ambiguous, false);
    }

    private void stubAuthorityReplayRows(IntakePrivateThreadRegistration rowRegistration)
            throws Exception {
        PreparedStatement firstStatement = mock(PreparedStatement.class);
        PreparedStatement secondStatement = mock(PreparedStatement.class);
        ResultSet first = mock(ResultSet.class);
        ResultSet second = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(firstStatement, secondStatement);
        when(firstStatement.executeQuery()).thenReturn(first);
        when(secondStatement.executeQuery()).thenReturn(second);
        stubAuthorityResult(first, rowRegistration, "ACTIVE", "ACTIVE", "REGISTERED",
                "INITIATOR", 2, false, false);
        stubAuthorityResult(second, rowRegistration, "ACTIVE", "ACTIVE", "REGISTERED",
                "INITIATOR", 2, false, false);
    }

    private static void stubAuthorityResult(
            ResultSet row,
            IntakePrivateThreadRegistration registration,
            String accessStatus,
            String agentStatus,
            String registrationStatus,
            String partyValue,
            long fence,
            boolean ambiguous,
            boolean ignored) throws Exception {
        when(row.next()).thenReturn(true, ambiguous, false);
        SelectionFixture selection = selection();
        when(row.getString(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "case_command_id" -> CASE_COMMAND_ID;
            case "party_authority_id" -> "PARTY_AUTHORITY_SYNTHETIC_SOURCE";
            case "payload_authority_id" -> "PAYLOAD_AUTHORITY_SYNTHETIC_SOURCE";
            case "access_session_id" -> "ACCESS_SYNTHETIC_SOURCE";
            case "command_id" -> COMMAND_ID;
            case "command_type" -> "INTAKE_MESSAGE";
            case "epoch_id" -> EPOCH_ID;
            case "registration_id" -> registration.registrationId();
            case "tenant_surrogate" -> TENANT;
            case "case_id" -> CASE_ID;
            case "thread_id" -> registration.threadId();
            case "actor_id" -> registration.actorScope().actorId();
            case "actor_role" -> registration.actorScope().actorRole().name();
            case "actor_scope_hash" -> registration.actorScopeHash();
            case "agent_session_id" -> registration.agentSessionId();
            case "request_hash" -> REQUEST_HASH;
            case "execution_disposition" -> "INERT_EXTERNAL_EVENT";
            case "source_kind" -> "EXISTING_PRIVATE_EVENT";
            case "payload_schema_version" -> "intake-turn-event.v2";
            case "authority_payload_uri", "command_payload_uri", "event_uri" -> PAYLOAD_URI;
            case "authority_payload_hash", "command_payload_hash", "event_hash" -> PAYLOAD_HASH;
            case "traceparent" -> "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
            case "party" -> partyValue;
            case "room_id" -> "ROOM_SYNTHETIC_SOURCE";
            case "epoch_writer_mode", "selection_writer_mode", "registration_writer_mode" -> "SHADOW";
            case "lifecycle_status" -> "ACTIVE";
            case "provisioning_status" -> "READY";
            case "epoch_selection_schema_version" -> "room-epoch-selection.v2";
            case "epoch_graph_key", "selection_graph_key", "registration_graph_key" -> "intake.v2";
            case "epoch_graph_version", "selection_graph_version", "registration_graph_version" -> "2.0.0";
            case "epoch_checkpoint_schema_version", "selection_checkpoint_schema_version",
                    "registration_checkpoint_schema_version" -> "intake-checkpoint.v2";
            case "epoch_room_workflow_build_id", "room_workflow_build_id" -> "intake-workflow.synthetic.v1";
            case "selection_hash" -> selection.hash();
            case "case_workflow_type" -> selection.caseWorkflowType();
            case "case_workflow_build_id" -> selection.caseWorkflowBuildId();
            case "room_workflow_type" -> selection.roomWorkflowType();
            case "process_contract_version" -> selection.processContractVersion();
            case "selection_state_schema_version", "registration_state_schema_version" -> "intake-graph-state.v2";
            case "stream_protocol" -> "agent-stream.v2";
            case "selection_prompt_version", "registration_prompt_version" -> "intake-prompt.v2";
            case "selection_model_profile_id", "registration_model_profile_id" -> "intake-model.synthetic.v1";
            case "selection_output_schema_version", "registration_output_schema_version" -> "intake-turn-proposal.v2";
            case "selection_policy_version", "registration_policy_version" -> "intake-policy.v2";
            case "selection_guardrail_version", "registration_guardrail_version" -> "intake-guardrail.v2";
            case "selection_tool_policy_version", "registration_tool_policy_version" -> "no-tools.v1";
            case "cohort_policy_version" -> "synthetic-only.v1";
            case "agent_key" -> "DISPUTE_INTAKE_OFFICER";
            case "agent_session_profile_version" -> "agent-session-profile.v1";
            case "memory_policy_id" -> "GRAPH_PRIVATE_NO_MEMORY_FRAME_V1";
            case "registration_schema_version" -> registration.schemaVersion();
            case "registration_hash" -> registration.registrationHash();
            case "actor_capabilities_json" -> "[\"graph.command.execute\"]";
            case "audience", "event_audience" -> registration.actorScope().audience().name();
            case "registration_status" -> registrationStatus;
            case "access_status" -> accessStatus;
            case "agent_status" -> agentStatus;
            case "event_binding_id" -> "EVENT_BINDING_SYNTHETIC_SOURCE";
            case "event_id" -> "EVENT_SYNTHETIC_SOURCE";
            case "message_id" -> "MESSAGE_SYNTHETIC_SOURCE";
            case "event_artifact_id" -> "EVENT_ARTIFACT_SYNTHETIC_SOURCE";
            case "event_object_version" -> "event-version-1";
            default -> null;
        });
        when(row.getLong(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "case_command_sequence", "event_sequence" -> 1L;
            case "room_epoch" -> 1L;
            case "fencing_token" -> fence;
            case "expected_process_revision", "epoch_process_revision" -> 7L;
            case "accepted_room_revision", "epoch_room_revision" -> 2L;
            case "authority_payload_size", "command_payload_size", "event_size" -> 512L;
            case "event_domain_revision" -> 5L;
            default -> 0L;
        });
        when(row.getTimestamp(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "deadline_at" -> Timestamp.from(DEADLINE);
            case "issued_at" -> Timestamp.from(ISSUED_AT);
            case "event_occurred_at", "event_created_at" ->
                    Timestamp.from(Instant.parse("2026-07-20T08:02:00Z"));
            default -> null;
        });
    }

    private static void stubInitialResult(
            ResultSet row, IntakePrivateThreadRegistration registration, long fence, boolean ambiguous)
            throws Exception {
        when(row.next()).thenReturn(true, ambiguous, false);
        when(row.getString(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "binding_id" -> "INITIAL_BINDING_SYNTHETIC_SOURCE";
            case "thread_registration_id" -> registration.registrationId();
            case "tenant_surrogate" -> TENANT;
            case "case_id" -> CASE_ID;
            case "thread_id" -> registration.threadId();
            case "actor_scope_hash" -> registration.actorScopeHash();
            case "agent_session_id" -> registration.agentSessionId();
            case "artifact_id" -> "SNAPSHOT_ARTIFACT_SYNTHETIC_SOURCE";
            case "schema_version" -> "intake-domain-snapshot.v2";
            case "object_uri" -> "urn:intake:snapshot:synthetic-source";
            case "object_version" -> "snapshot-version-1";
            case "content_sha256" -> hash(20);
            default -> null;
        });
        when(row.getLong(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "room_epoch" -> 1L;
            case "fencing_token" -> fence;
            case "size_bytes" -> 1024L;
            case "domain_revision" -> 4L;
            case "room_revision" -> 1L;
            case "projection_revision" -> 4L;
            case "initial_last_sequence" -> 0L;
            default -> 0L;
        });
        when(row.getTimestamp("created_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-07-20T08:01:00Z")));
    }

    private static SnapshotPublicationRequest snapshotRequest() {
        return snapshotRequest(ActivityInvocationMode.FIRST_EXECUTION, 2);
    }

    private static SnapshotPublicationRequest snapshotRequest(
            ActivityInvocationMode mode, int sharedRetriesRemaining) {
        ActivityEnvelope envelope = envelope(mode, sharedRetriesRemaining);
        return new SnapshotPublicationRequest(
                "intake-snapshot-publication-request.v1", envelope, THREAD_ID, AGENT_SESSION_ID, 4,
                IntakeOperationKeys.snapshotPublish(CASE_ID, 1, envelope.actorScopeHash(), 4),
                REQUEST_HASH);
    }

    private static GraphExecutionRequest graphRequest() {
        return new GraphExecutionRequest(
                "intake-graph-execution-request.v1", envelope(), THREAD_ID, AGENT_SESSION_ID,
                IntakeOperationKeys.graphExecute(CASE_ID, 1, THREAD_ID, COMMAND_ID), REQUEST_HASH);
    }

    private static TurnFinalizationRequest finalizationRequest() {
        GraphExecutionRequest graphRequest = graphRequest();
        String resultHash = hash(30);
        String proposalHash = hash(31);
        GraphExecutionReceipt graphReceipt = new GraphExecutionReceipt(
                "intake-graph-execution-receipt.v1",
                new OperationReceipt(
                        "intake-operation-receipt.v1", graphRequest.operationKey(), REQUEST_HASH,
                        resultHash, 7, 2),
                new IntakeAgentRunRef(
                        "intake-agent-run-ref.v1", "RUN_SYNTHETIC_SOURCE", "ATTEMPT_SYNTHETIC_SOURCE", resultHash),
                new IntakeGraphExecutionRef(
                        "intake-graph-execution-ref.v1", THREAD_ID, COMMAND_ID, "intake.v2", "2.0.0",
                        "CHECKPOINT_SYNTHETIC_SOURCE", "urn:intake:result:synthetic-source", resultHash,
                        "urn:intake:proposal:synthetic-source", proposalHash),
                immutable("RESULT_SYNTHETIC_SOURCE", "GRAPH_RESULT", "room-graph-result.v1",
                        "urn:intake:result:synthetic-source", resultHash),
                immutable("PROPOSAL_SYNTHETIC_SOURCE", "INTAKE_PROPOSAL", "intake-turn-proposal.v2",
                        "urn:intake:proposal:synthetic-source", proposalHash));
        return new TurnFinalizationRequest(
                "intake-turn-finalization-request.v1", graphRequest.envelope(), THREAD_ID,
                AGENT_SESSION_ID, graphReceipt,
                IntakeOperationKeys.turnFinalize(CASE_ID, 1, THREAD_ID, COMMAND_ID, resultHash),
                REQUEST_HASH);
    }

    private static ActivityEnvelope envelope() {
        return envelope(ActivityInvocationMode.FIRST_EXECUTION, 2);
    }

    private static ActivityEnvelope envelope(
            ActivityInvocationMode mode, int sharedRetriesRemaining) {
        IntakePrivateThreadRegistration registration = registration(THREAD_ID, REGISTRATION_ID);
        int activityAttempts = mode == ActivityInvocationMode.RECONCILE_ONLY ? 0 : 1;
        return new ActivityEnvelope(
                "intake-activity-envelope.v1", TENANT, CASE_ID, 1, 2, COMMAND_ID, 1,
                IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR,
                registration.actorScopeHash(), PAYLOAD_URI, PAYLOAD_HASH, 7, 2,
                DEADLINE.toEpochMilli(), new RetryBudget(
                        "intake-retry-budget.v1", 2, activityAttempts, 1),
                pinnedVersions(),
                new ActivityInvocation(
                        "intake-activity-invocation.v1", mode, sharedRetriesRemaining));
    }

    private static GraphPlan graphPlan() {
        return new GraphPlan(
                "RUN_SYNTHETIC_SOURCE", "ATTEMPT_SYNTHETIC_SOURCE", 1, 3, null, false, 0,
                "INTAKE_MESSAGE", "DISPUTE_INTAKE_OFFICER", "INTAKE_MESSAGE",
                "intake.graph:" + CASE_ID + ":" + COMMAND_ID,
                "synthetic-envelope-key.v1", "synthetic-envelope-nonce.v1");
    }

    private static IntakePrivateThreadRegistration registration(String threadId, String registrationId) {
        return new IntakePrivateThreadRegistrationFactory(() -> threadId).issue(new IssueRequest(
                registrationId, TENANT, CASE_ID, 1, 2,
                new ActorScope("user-synthetic-source", ActorRole.USER, Audience.USER,
                        List.of("graph.command.execute")),
                AGENT_SESSION_ID,
                new VersionPins(
                        "2.0.0", "intake-checkpoint.v2", "intake-prompt.v2",
                        "intake-model.synthetic.v1", "intake-policy.v2",
                        "intake-guardrail.v2", "no-tools.v1"),
                WriterMode.SHADOW, ISSUED_AT)).registration();
    }

    private static PinnedVersions pinnedVersions() {
        return new PinnedVersions(
                "intake-pinned-versions.v1", "intake-workflow.synthetic.v1", "2.0.0",
                "intake-checkpoint.v2", "intake-prompt.v2", "intake-model.synthetic.v1",
                "intake-turn-proposal.v2", "intake-policy.v2", "intake-guardrail.v2",
                "no-tools.v1");
    }

    private static SelectionFixture selection() {
        String caseType = "CaseProcessWorkflow";
        String caseBuild = "case-workflow.synthetic.v1";
        String roomType = "IntakeRoomWorkflow";
        String roomBuild = "intake-workflow.synthetic.v1";
        String processContract = "case-process-contract.v1";
        String hash = EpochSelectionHasher.hash(new SelectionHashInput(
                "room-epoch-selection.v2", RoomType.INTAKE, WriterMode.SHADOW,
                caseType, caseBuild, roomType, roomBuild, processContract, "intake.v2", "2.0.0",
                "intake-checkpoint.v2", "intake-graph-state.v2", "agent-stream.v2",
                "intake-prompt.v2", "intake-model.synthetic.v1", "intake-turn-proposal.v2",
                "intake-policy.v2", "intake-guardrail.v2", "no-tools.v1", "synthetic-only.v1"));
        return new SelectionFixture(hash, caseType, caseBuild, roomType, roomBuild, processContract);
    }

    private static AdmissionPins admissionPins(SelectionFixture selection) {
        return new AdmissionPins(
                selection.caseWorkflowType(), selection.caseWorkflowBuildId(),
                selection.roomWorkflowType(), selection.roomWorkflowBuildId(),
                selection.processContractVersion(), "intake.v2", "2.0.0",
                "intake-checkpoint.v2", "intake-graph-state.v2", "agent-stream.v2",
                "intake-prompt.v2", "intake-model.synthetic.v1", "intake-turn-proposal.v2",
                "intake-policy.v2", "intake-guardrail.v2", "no-tools.v1",
                "synthetic-only.v1", "DISPUTE_INTAKE_OFFICER",
                "agent-session-profile.v1", "GRAPH_PRIVATE_NO_MEMORY_FRAME_V1");
    }

    private static String pinsJson(AdmissionPins pins) throws Exception {
        Map<String, String> values = Map.ofEntries(
                Map.entry("case_workflow_type", pins.caseWorkflowType()),
                Map.entry("case_workflow_build_id", pins.caseWorkflowBuildId()),
                Map.entry("room_workflow_type", pins.roomWorkflowType()),
                Map.entry("room_workflow_build_id", pins.roomWorkflowBuildId()),
                Map.entry("process_contract_version", pins.processContractVersion()),
                Map.entry("graph_key", pins.graphKey()),
                Map.entry("graph_version", pins.graphVersion()),
                Map.entry("checkpoint_schema_version", pins.checkpointSchemaVersion()),
                Map.entry("state_schema_version", pins.stateSchemaVersion()),
                Map.entry("stream_protocol", pins.streamProtocol()),
                Map.entry("prompt_version", pins.promptVersion()),
                Map.entry("model_profile_id", pins.modelProfileId()),
                Map.entry("output_schema_version", pins.outputSchemaVersion()),
                Map.entry("policy_version", pins.policyVersion()),
                Map.entry("guardrail_version", pins.guardrailVersion()),
                Map.entry("tool_policy_version", pins.toolPolicyVersion()),
                Map.entry("cohort_policy_version", pins.cohortPolicyVersion()),
                Map.entry("agent_key", pins.agentKey()),
                Map.entry("agent_session_profile_version", pins.agentSessionProfileVersion()),
                Map.entry("memory_policy_id", pins.memoryPolicyId()));
        return MAPPER.writeValueAsString(values);
    }

    private static ImmutablePayloadRef immutable(
            String id, String type, String schema, String uri, String hash) {
        return new ImmutablePayloadRef(
                "immutable-payload-ref.v1", id, type, schema, uri, "version-1", hash, 512);
    }

    private static SnapshotMaterial snapshotMaterial() {
        return new SnapshotMaterial(
                "SNAPSHOT_SYNTHETIC_SOURCE", 4, 6, List.of("EVENT_SYNTHETIC_SOURCE"),
                MAPPER.createObjectNode().put("case_id", CASE_ID),
                MAPPER.createObjectNode().put("room_phase", "OPEN"),
                List.of(), MAPPER.createObjectNode().put("schema_version", "intake-dossier.v2"),
                Instant.parse("2026-07-20T08:01:00Z"));
    }

    private static ParitySnapshot paritySnapshot(int offset) {
        EnumMap<Dimension, ObservedValue> values = new EnumMap<>(Dimension.class);
        for (Dimension dimension : Dimension.values()) {
            values.put(dimension, new ObservedValue(Classification.VALUE,
                    hash(offset + dimension.ordinal())));
        }
        return new ParitySnapshot(values, Set.of());
    }

    private static String hash(int value) {
        return String.format("%064x", value);
    }

    private record SelectionFixture(
            String hash,
            String caseWorkflowType,
            String caseWorkflowBuildId,
            String roomWorkflowType,
            String roomWorkflowBuildId,
            String processContractVersion) {}
}
