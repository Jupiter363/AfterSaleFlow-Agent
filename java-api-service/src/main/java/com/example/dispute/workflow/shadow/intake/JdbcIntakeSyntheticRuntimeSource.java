package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.workflow.application.authority.epoch.EpochSelectionHasher;
import com.example.dispute.workflow.application.authority.epoch.EpochSelectionHasher.SelectionHashInput;
import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher.SnapshotRequest;
import com.example.dispute.workflow.application.intake.IntakeEventReference;
import com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory.CommandRequest;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration.ActorScope;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.infrastructure.persistence.authority.bridge.IntakeAuthorityInvariantException;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort.ReadUnavailableException;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticAdmissionReader.AdmissionQuery;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticAdmissionReader.AdmissionPins;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticAdmissionReader.PersistedAdmission;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticGraphMaterialSource.ArtifactMaterial;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticGraphMaterialSource.GraphPlan;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticGraphMaterialSource.GraphPlanQuery;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityMaterialSource.ParityMaterial;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityMaterialSource.ParityMaterialQuery;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticSnapshotMaterialSource.SnapshotMaterial;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticSnapshotMaterialSource.SnapshotMaterialQuery;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocationMode;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ImmutablePayloadRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Production read-only authority resolver for the signed-synthetic Intake runtime.
 *
 * <p>Every load revalidates admission, the active SHADOW epoch, R1.5 inert command authority, and
 * mutable session/registration state in one lock-free REPEATABLE_READ snapshot. The injected
 * material sources may supply only data that Domain PostgreSQL intentionally does not own.
 */
public final class JdbcIntakeSyntheticRuntimeSource implements IntakeSyntheticRuntimeSource {

    public static final int REQUIRED_ISOLATION = Connection.TRANSACTION_REPEATABLE_READ;

    private static final String AUTHORITY_SQL = """
        select ca.case_command_id, ca.party_authority_id, ca.payload_authority_id,
               ca.access_session_id, ca.command_id, ca.case_command_sequence, ca.command_type,
               ca.epoch_id, ca.registration_id, ca.tenant_surrogate, ca.case_id,
               ca.room_epoch, ca.fencing_token, ca.thread_id, ca.actor_id, ca.actor_role,
               ca.actor_scope_hash, ca.agent_session_id, ca.request_hash,
               ca.accepted_room_revision, ca.execution_disposition,
               pa.source_kind, pa.schema_version as payload_schema_version,
               pa.object_uri as authority_payload_uri,
               pa.content_sha256 as authority_payload_hash,
               pa.size_bytes as authority_payload_size,
               c.payload_uri as command_payload_uri,
               c.payload_sha256 as command_payload_hash,
               c.payload_size_bytes as command_payload_size,
               c.expected_process_revision, c.deadline_at, c.traceparent,
               p.party,
               epoch.room_id, epoch.writer_mode as epoch_writer_mode,
               epoch.lifecycle_status, epoch.provisioning_status,
               epoch.selection_schema_version as epoch_selection_schema_version,
               epoch.process_revision as epoch_process_revision,
               epoch.room_revision as epoch_room_revision,
               epoch.graph_key as epoch_graph_key,
               epoch.graph_version as epoch_graph_version,
               epoch.checkpoint_schema_version as epoch_checkpoint_schema_version,
               epoch.room_workflow_build_id as epoch_room_workflow_build_id,
               selection.selection_hash, selection.writer_mode as selection_writer_mode,
               selection.case_workflow_type, selection.case_workflow_build_id,
               selection.room_workflow_type, selection.room_workflow_build_id,
               selection.process_contract_version, selection.graph_key as selection_graph_key,
               selection.graph_version as selection_graph_version,
               selection.checkpoint_schema_version as selection_checkpoint_schema_version,
               selection.state_schema_version as selection_state_schema_version,
               selection.stream_protocol, selection.prompt_version as selection_prompt_version,
               selection.model_profile_id as selection_model_profile_id,
               selection.output_schema_version as selection_output_schema_version,
               selection.policy_version as selection_policy_version,
               selection.guardrail_version as selection_guardrail_version,
               selection.tool_policy_version as selection_tool_policy_version,
               selection.cohort_policy_version, selection.agent_key,
               selection.agent_session_profile_version, selection.memory_policy_id,
               thread.schema_version as registration_schema_version,
               thread.registration_hash, thread.actor_capabilities_json::text as actor_capabilities_json,
               thread.audience, thread.graph_key as registration_graph_key,
               thread.graph_version as registration_graph_version,
               thread.checkpoint_schema_version as registration_checkpoint_schema_version,
               thread.state_schema_version as registration_state_schema_version,
               thread.prompt_version as registration_prompt_version,
               thread.model_profile_id as registration_model_profile_id,
               thread.output_schema_version as registration_output_schema_version,
               thread.policy_version as registration_policy_version,
               thread.guardrail_version as registration_guardrail_version,
               thread.tool_policy_version as registration_tool_policy_version,
               thread.writer_mode as registration_writer_mode,
               thread.registration_status, thread.issued_at,
               access.status as access_status, agent.status as agent_status,
               source.binding_id as event_binding_id, source.event_id, source.message_id,
               source.artifact_id as event_artifact_id, source.object_uri as event_uri,
               source.object_version as event_object_version,
               source.content_sha256 as event_hash, source.size_bytes as event_size,
               source.event_sequence, source.domain_revision as event_domain_revision,
               source.audience as event_audience, source.occurred_at as event_occurred_at,
               source.created_at as event_created_at
          from case_intake_command_authority ca
          join case_intake_command_payload_authority pa
            on pa.payload_authority_id = ca.payload_authority_id
           and pa.command_id = ca.command_id and pa.epoch_id = ca.epoch_id
           and pa.party_authority_id = ca.party_authority_id
           and pa.access_session_id = ca.access_session_id
           and pa.registration_id = ca.registration_id
           and pa.tenant_surrogate = ca.tenant_surrogate and pa.case_id = ca.case_id
           and pa.room_type = ca.room_type and pa.room_epoch = ca.room_epoch
           and pa.fencing_token = ca.fencing_token and pa.thread_id = ca.thread_id
           and pa.actor_scope_hash = ca.actor_scope_hash
           and pa.agent_session_id = ca.agent_session_id
          join case_command c
            on c.id = ca.case_command_id and c.tenant_surrogate = ca.tenant_surrogate
           and c.case_id = ca.case_id and c.command_id = ca.command_id
           and c.request_hash = ca.request_hash
          join case_intake_epoch_party_authority p
            on p.authority_id = ca.party_authority_id and p.epoch_id = ca.epoch_id
           and p.tenant_surrogate = ca.tenant_surrogate and p.case_id = ca.case_id
           and p.room_type = ca.room_type and p.room_epoch = ca.room_epoch
           and p.fencing_token = ca.fencing_token and p.access_session_id = ca.access_session_id
           and p.registration_id = ca.registration_id and p.thread_id = ca.thread_id
           and p.actor_id = ca.actor_id and p.actor_role = ca.actor_role
           and p.actor_scope_hash = ca.actor_scope_hash
           and p.agent_session_id = ca.agent_session_id
          join case_room_epoch epoch
            on epoch.id = ca.epoch_id and epoch.tenant_surrogate = ca.tenant_surrogate
           and epoch.case_id = ca.case_id and epoch.room_type = ca.room_type
           and epoch.room_epoch = ca.room_epoch and epoch.fencing_token = ca.fencing_token
          join case_intake_epoch_selection_binding selection
            on selection.epoch_id = epoch.id
           and selection.tenant_surrogate = epoch.tenant_surrogate
           and selection.case_id = epoch.case_id and selection.room_type = epoch.room_type
           and selection.room_epoch = epoch.room_epoch
           and selection.fencing_token = epoch.fencing_token
          join case_intake_graph_thread_binding thread
            on thread.registration_id = p.registration_id
           and thread.tenant_surrogate = p.tenant_surrogate and thread.case_id = p.case_id
           and thread.room_type = p.room_type and thread.room_epoch = p.room_epoch
           and thread.fencing_token = p.fencing_token and thread.thread_id = p.thread_id
           and thread.actor_id = p.actor_id and thread.actor_role = p.actor_role
           and thread.audience = p.audience and thread.actor_scope_hash = p.actor_scope_hash
           and thread.agent_session_id = p.agent_session_id
           and thread.registration_hash = p.registration_hash
          join case_access_session access
            on access.id = p.access_session_id and access.tenant_id = p.session_tenant_id
           and access.case_id = p.session_case_id and access.actor_id = p.actor_id
           and access.actor_role = p.actor_role and access.permission_level = p.permission_level
          join agent_conversation_session agent
            on agent.id = p.agent_session_id and agent.tenant_id = p.session_tenant_id
           and agent.case_id = p.session_case_id and agent.room_type = p.room_type
           and agent.access_session_id = p.access_session_id and agent.actor_id = p.actor_id
           and agent.actor_role = p.actor_role and agent.agent_key = p.agent_key
           and agent.prompt_profile_id = p.prompt_profile_id
           and agent.memory_policy_id = p.memory_policy_id
          join case_intake_snapshot_binding source
            on source.binding_id = pa.existing_event_binding_id
           and source.thread_registration_id = pa.registration_id
           and source.tenant_surrogate = pa.tenant_surrogate and source.case_id = pa.case_id
           and source.room_type = pa.room_type and source.room_epoch = pa.room_epoch
           and source.fencing_token = pa.fencing_token and source.thread_id = pa.thread_id
           and source.actor_scope_hash = pa.actor_scope_hash
           and source.agent_session_id = pa.agent_session_id
           and source.actor_audience = pa.actor_role
           and source.schema_version = pa.schema_version and source.artifact_id = pa.artifact_id
           and source.object_uri = pa.object_uri and source.object_version = pa.object_version
           and source.content_sha256 = pa.content_sha256 and source.size_bytes = pa.size_bytes
         where ca.tenant_surrogate = ? and ca.case_id = ? and ca.command_id = ?
           and ca.room_type = 'INTAKE'
           and ca.command_type = 'INTAKE_MESSAGE'
           and ca.execution_disposition = 'INERT_EXTERNAL_EVENT'
           and pa.source_kind = 'EXISTING_PRIVATE_EVENT'
           and pa.schema_version = 'intake-turn-event.v2'
           and selection.writer_mode = 'SHADOW'
        """;

    private static final String INITIAL_SNAPSHOT_SQL = """
        select binding_id, thread_registration_id, tenant_surrogate, case_id,
               room_epoch, fencing_token, thread_id, actor_scope_hash, agent_session_id,
               artifact_id, schema_version, object_uri, object_version, content_sha256,
               size_bytes, domain_revision, room_revision, projection_revision,
               initial_last_sequence, created_at
          from case_intake_snapshot_binding
         where thread_registration_id = ? and tenant_surrogate = ? and case_id = ?
           and room_type = 'INTAKE' and room_epoch = ? and fencing_token = ?
           and thread_id = ? and actor_scope_hash = ? and agent_session_id = ?
           and binding_type = 'INITIAL' and visibility = 'PRIVATE'
           and initialization_marker
        """;

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private final DataSource dataSource;
    private final IntakeSyntheticAdmissionReader admissionReader;
    private final IntakeSyntheticSnapshotMaterialSource snapshotMaterialSource;
    private final IntakeSyntheticGraphMaterialSource graphMaterialSource;
    private final IntakeSyntheticParityMaterialSource parityMaterialSource;
    private final Clock clock;

    public JdbcIntakeSyntheticRuntimeSource(
            DataSource dataSource,
            IntakeSyntheticAdmissionReader admissionReader,
            IntakeSyntheticSnapshotMaterialSource snapshotMaterialSource,
            IntakeSyntheticGraphMaterialSource graphMaterialSource,
            IntakeSyntheticParityMaterialSource parityMaterialSource) {
        this(dataSource, admissionReader, snapshotMaterialSource, graphMaterialSource,
                parityMaterialSource, Clock.systemUTC());
    }

    JdbcIntakeSyntheticRuntimeSource(
            DataSource dataSource,
            IntakeSyntheticAdmissionReader admissionReader,
            IntakeSyntheticSnapshotMaterialSource snapshotMaterialSource,
            IntakeSyntheticGraphMaterialSource graphMaterialSource,
            IntakeSyntheticParityMaterialSource parityMaterialSource,
            Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.admissionReader = Objects.requireNonNull(admissionReader, "admissionReader must not be null");
        this.snapshotMaterialSource = Objects.requireNonNull(
                snapshotMaterialSource, "snapshotMaterialSource must not be null");
        this.graphMaterialSource = Objects.requireNonNull(
                graphMaterialSource, "graphMaterialSource must not be null");
        this.parityMaterialSource = Objects.requireNonNull(
                parityMaterialSource, "parityMaterialSource must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public SnapshotInput loadSnapshot(SnapshotPublicationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ResolvedAuthority resolved = resolve(
                request.envelope(), request.threadId(), request.agentSessionId(),
                request.operationKey(), request.requestHash(), false);
        requireStageOperation(resolved.admission(), request);
        SnapshotMaterial material = Objects.requireNonNull(
                snapshotMaterialSource.load(new SnapshotMaterialQuery(
                        resolved.authority(), resolved.row().threadBinding(),
                        resolved.row().epochRoomRevision())),
                "snapshot material must not be null");
        requireEqual(material.domainRevision(), request.domainRevision(), "snapshot domain revision");
        SnapshotRequest publication = new SnapshotRequest(
                material.snapshotId(),
                resolved.row().threadBinding(),
                material.domainRevision(),
                resolved.row().epochRoomRevision(),
                material.projectionRevision(),
                material.sourceRefs(),
                material.initialCaseFacts(),
                material.shareableProjection(),
                material.ownMessages(),
                material.currentDossier(),
                material.createdAt());
        return new SnapshotInput(resolved.authority(), material.domainRevision(), publication);
    }

    @Override
    public GraphInput loadGraph(GraphExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ResolvedAuthority resolved = resolve(
                request.envelope(), request.threadId(), request.agentSessionId(),
                request.operationKey(), request.requestHash(), true);
        requireStageOperation(resolved.admission(), request);
        GraphPlan plan = Objects.requireNonNull(
                graphMaterialSource.loadPlan(new GraphPlanQuery(
                        resolved.authority(), resolved.row().roomId(), resolved.row().epochId(),
                        resolved.admission().logicalRunId(), resolved.admission().attemptId(),
                        resolved.row().threadBinding(), resolved.initialSnapshot(),
                        resolved.row().event())),
                "Graph plan must not be null");
        requireEqual(plan.stageCode(), request.envelope().commandType().name(), "Graph stage code");
        requireEqual(plan.operation(), request.envelope().commandType().name(), "Graph operation");
        requireEqual(plan.agentProfileId(), resolved.admission().pins().agentKey(),
                "Graph agent profile");
        requireEqual(plan.logicalRunId(), resolved.admission().logicalRunId(),
                "admitted logical run");
        requireEqual(plan.attemptId(), resolved.admission().attemptId(), "admitted attempt");
        requireAttemptLineage(plan);
        var envelope = request.envelope();
        CommandRequest command = new CommandRequest(
                envelope.commandId(),
                plan.logicalRunId(),
                plan.attemptId(),
                resolved.row().threadBinding(),
                resolved.initialSnapshot(),
                resolved.row().event(),
                envelope.processRevision(),
                plan.stageCode(),
                envelope.commandSequence(),
                plan.agentProfileId(),
                envelope.retryBudget().providerAttemptsRemaining(),
                envelope.retryBudget().activityAttemptsRemaining(),
                envelope.retryBudget().repairsRemaining(),
                Instant.ofEpochMilli(envelope.deadlineEpochMillis()),
                resolved.row().traceparent(),
                plan.envelopeKeyId(),
                plan.envelopeNonce());
        var bindingContext = new AgentRunCommandBindingFactory.Context(
                resolved.row().roomId(), resolved.row().epochId(), plan.operation(),
                plan.logicalIdempotencyKey());
        return new GraphInput(
                resolved.authority(), command, bindingContext, plan.attemptNo(), plan.attemptLimit(),
                plan.previousAttemptId(), plan.resetRequired(), plan.publicSequenceOffset());
    }

    @Override
    public GraphArtifacts loadGraphArtifacts(GraphArtifactQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        GraphExecutionRequest request = query.activityRequest();
        ResolvedAuthority resolved = resolve(
                request.envelope(), request.threadId(), request.agentSessionId(),
                request.operationKey(), request.requestHash(), false);
        requireStageOperation(resolved.admission(), request);
        ArtifactMaterial material = Objects.requireNonNull(
                graphMaterialSource.loadArtifacts(query), "Graph artifact material must not be null");
        requireResultArtifact(material.result(), query);
        requireProposalArtifact(material.proposal(), query);
        return new GraphArtifacts(resolved.authority(), material.result(), material.proposal());
    }

    @Override
    public ParityInput loadParity(TurnFinalizationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ResolvedAuthority resolved = resolve(
                request.envelope(), request.threadId(), request.agentSessionId(),
                request.operationKey(), request.requestHash(), false);
        requireStageOperation(resolved.admission(), request);
        requireGraphReceipt(request);
        ParityMaterial material = Objects.requireNonNull(
                parityMaterialSource.load(new ParityMaterialQuery(
                        resolved.authority(), request,
                        resolved.admission().parityBaselineRef(),
                        resolved.admission().parityBaselineHash())),
                "parity material must not be null");
        return new ParityInput(
                resolved.authority(),
                request.graphExecution().operation().resultHash(),
                request.graphExecution().graphExecutionRef().proposalHash(),
                material.legacy(), material.shadow(), material.projectedEventType());
    }

    private ResolvedAuthority resolve(
            ActivityEnvelope envelope,
            String threadId,
            String agentSessionId,
            String activityOperationKey,
            String requestHash,
            boolean includeInitialSnapshot) {
        return inSnapshot(connection -> {
            AdmissionQuery query = new AdmissionQuery(
                    envelope, threadId, agentSessionId, activityOperationKey, requestHash);
            List<PersistedAdmission> admissions = admissionReader.find(connection, query);
            if (admissions.size() != 1) {
                throw new IntakeAuthorityInvariantException(
                        "synthetic admission expected one row, found " + admissions.size());
            }
            AuthorityRow row = exactlyOne(connection, AUTHORITY_SQL, "synthetic runtime authority", statement -> {
                statement.setString(1, envelope.tenantSurrogate());
                statement.setString(2, envelope.caseId());
                statement.setString(3, envelope.commandId());
            }, JdbcIntakeSyntheticRuntimeSource::mapAuthority);
            PersistedAdmission admission = admissions.getFirst();
            requireAuthority(row, admission, query);
            IntakeSnapshotReference initial = includeInitialSnapshot
                    ? readInitialSnapshot(connection, row)
                    : null;
            return new ResolvedAuthority(
                    new ActivityAuthority(envelope, threadId, agentSessionId,
                            activityOperationKey, requestHash),
                    admission, row, initial);
        });
    }

    private static IntakeSnapshotReference readInitialSnapshot(Connection connection, AuthorityRow row)
            throws SQLException {
        return exactlyOne(connection, INITIAL_SNAPSHOT_SQL, "initial snapshot", statement -> {
            var registration = row.threadBinding().registration();
            statement.setString(1, registration.registrationId());
            statement.setString(2, registration.tenantSurrogate());
            statement.setString(3, registration.caseId());
            statement.setLong(4, registration.roomEpoch());
            statement.setLong(5, row.threadBinding().fencingToken());
            statement.setString(6, registration.threadId());
            statement.setString(7, registration.actorScopeHash());
            statement.setString(8, registration.agentSessionId());
        }, JdbcIntakeSyntheticRuntimeSource::mapInitialSnapshot);
    }

    private static void requireAuthority(
            AuthorityRow row, PersistedAdmission admission, AdmissionQuery query) {
        ActivityEnvelope envelope = query.envelope();
        requireEqual(admission.schemaVersion(), "intake-synthetic-activity-admission.v1",
                "admission schema");
        requireEqual(admission.trafficSource(), "AUTHENTICATED_SIGNED_SYNTHETIC",
                "admission traffic source");
        requireEqual(admission.admissionStatus(), "VERIFIED", "admission status");
        requireHash(admission.authorizationHash(), "admission authorization hash");
        requireEqual(admission.epochId(), row.epochId(), "admission epoch id");
        requireEqual(admission.partyAuthorityId(), row.partyAuthorityId(),
                "admission party authority");
        requireEqual(admission.caseCommandId(), row.caseCommandId(), "admission case command");
        requireEqual(admission.payloadAuthorityId(), row.payloadAuthorityId(),
                "admission payload authority");
        requireEqual(admission.accessSessionId(), row.accessSessionId(),
                "admission access session");
        requireEqual(admission.registrationId(), row.threadBinding().registration().registrationId(),
                "admission registration");
        requireEqual(admission.actorId(), row.threadBinding().registration().actorScope().actorId(),
                "admission actor");
        requireEqual(admission.actorRole(), row.threadBinding().registration().actorScope().actorRole(),
                "admission actor role");
        requireEnvelope(admission, query);
        requireAdmissionPins(admission);
        requireRetry(admission, envelope);

        requireEqual(row.lifecycleStatus(), "ACTIVE", "active epoch lifecycle");
        requireEqual(row.provisioningStatus(), "READY", "active epoch provisioning");
        requireEqual(row.epochWriterMode(), "SHADOW", "active epoch writer mode");
        requireEqual(row.epochSelectionSchemaVersion(), "room-epoch-selection.v2",
                "active epoch selection schema");
        requireEqual(row.selection().writerMode(), "SHADOW", "selected writer mode");
        requireEqual(row.epochProcessRevision(), envelope.processRevision(), "active process revision");
        requireEqual(row.epochRoomRevision(), envelope.roomRevision(), "active room revision");
        requireEqual(row.expectedProcessRevision(), envelope.processRevision(),
                "command process revision");
        requireEqual(row.acceptedRoomRevision(), envelope.roomRevision(),
                "command room revision");
        requireEqual(row.commandId(), envelope.commandId(), "command id");
        requireEqual(row.commandSequence(), envelope.commandSequence(), "command sequence");
        requireEqual(row.commandType(), "INTAKE_MESSAGE", "command type");
        requireEqual(envelope.commandType(), IntakeCommandType.INTAKE_MESSAGE,
                "Activity command type");
        requireEqual(row.party(), envelope.party().name(), "command party");
        requireEqual(row.executionDisposition(), "INERT_EXTERNAL_EVENT", "execution disposition");
        requireEqual(row.sourceKind(), "EXISTING_PRIVATE_EVENT", "payload source kind");
        requireEqual(row.payloadSchemaVersion(), "intake-turn-event.v2", "payload schema");
        requireEqual(row.payloadUri(), envelope.commandPayloadRef(), "command payload URI");
        requireEqual(row.payloadHash(), envelope.commandPayloadHash(), "command payload hash");
        requireEqual(row.requestHash(), query.requestHash(), "command request hash");
        requireEqual(row.deadlineAt().toEpochMilli(), envelope.deadlineEpochMillis(),
                "command deadline");
        requireEqual(row.threadBinding().fencingToken(), envelope.fencingToken(),
                "fencing token");
        requireEqual(row.threadBinding().registration().actorScopeHash(), envelope.actorScopeHash(),
                "command actor scope");
        requireEqual(row.threadBinding().registration().threadId(), query.threadId(),
                "command thread");
        requireEqual(row.threadBinding().registration().agentSessionId(), query.agentSessionId(),
                "command Agent Session");
        requireEqual(row.accessStatus(), "ACTIVE", "access session status");
        requireEqual(row.agentStatus(), "ACTIVE", "Agent Session status");
        requireEqual(row.registrationStatus(), "REGISTERED", "registration status");
        requireEqual(row.event().payloadRef().uri(), row.payloadUri(), "event payload URI");
        requireEqual(row.event().payloadRef().sha256(), row.payloadHash(), "event payload hash");
        requireEqual(row.event().payloadRef().sizeBytes(), row.payloadSize(), "event payload size");
        requireSelection(row, admission);
        IntakeSyntheticRuntimeAuthority.requireRegistration(
                envelope, query.threadId(), query.agentSessionId(), row.threadBinding());
    }

    private static void requireEnvelope(PersistedAdmission admission, AdmissionQuery query) {
        ActivityEnvelope envelope = query.envelope();
        requireEqual(admission.tenantSurrogate(), envelope.tenantSurrogate(), "admission tenant");
        requireEqual(admission.caseId(), envelope.caseId(), "admission case");
        requireEqual(admission.roomType(), "INTAKE", "admission room type");
        requireEqual(admission.writerMode(), "SHADOW", "admission writer mode");
        requireEqual(admission.roomEpoch(), envelope.roomEpoch(), "admission room epoch");
        requireEqual(admission.fencingToken(), envelope.fencingToken(), "admission fence");
        requireEqual(admission.commandId(), envelope.commandId(), "admission command");
        requireEqual(admission.commandSequence(), envelope.commandSequence(), "admission sequence");
        requireEqual(admission.commandType(), envelope.commandType(), "admission command type");
        requireEqual(admission.party(), envelope.party(), "admission party");
        requireEqual(admission.actorScopeHash(), envelope.actorScopeHash(), "admission actor scope");
        requireEqual(admission.commandPayloadRef(), envelope.commandPayloadRef(),
                "admission payload reference");
        requireEqual(admission.commandPayloadHash(), envelope.commandPayloadHash(),
                "admission payload hash");
        requireEqual(admission.requestHash(), query.requestHash(), "admission request hash");
        requireEqual(admission.threadId(), query.threadId(), "admission thread");
        requireEqual(admission.agentSessionId(), query.agentSessionId(), "admission Agent Session");
        requireEqual(admission.processRevision(), envelope.processRevision(),
                "admission process revision");
        requireEqual(admission.roomRevision(), envelope.roomRevision(), "admission room revision");
        requireEqual(admission.acceptedRoomRevision(), envelope.roomRevision(),
                "admission accepted room revision");
        requireEqual(admission.deadlineEpochMillis(), envelope.deadlineEpochMillis(),
                "admission deadline");
        requireEqual(admission.activityPinnedVersions(), envelope.pinnedVersions(),
                "admission pinned versions");
    }

    private static void requireAdmissionPins(PersistedAdmission admission) {
        try {
            JsonNode persisted = MAPPER.readTree(admission.pinnedVersionsJson());
            AdmissionPins pins = admission.pins();
            var typed = MAPPER.createObjectNode();
            typed.put("case_workflow_type", pins.caseWorkflowType());
            typed.put("case_workflow_build_id", pins.caseWorkflowBuildId());
            typed.put("room_workflow_type", pins.roomWorkflowType());
            typed.put("room_workflow_build_id", pins.roomWorkflowBuildId());
            typed.put("process_contract_version", pins.processContractVersion());
            typed.put("graph_key", pins.graphKey());
            typed.put("graph_version", pins.graphVersion());
            typed.put("checkpoint_schema_version", pins.checkpointSchemaVersion());
            typed.put("state_schema_version", pins.stateSchemaVersion());
            typed.put("stream_protocol", pins.streamProtocol());
            typed.put("prompt_version", pins.promptVersion());
            typed.put("model_profile_id", pins.modelProfileId());
            typed.put("output_schema_version", pins.outputSchemaVersion());
            typed.put("policy_version", pins.policyVersion());
            typed.put("guardrail_version", pins.guardrailVersion());
            typed.put("tool_policy_version", pins.toolPolicyVersion());
            typed.put("cohort_policy_version", pins.cohortPolicyVersion());
            typed.put("agent_key", pins.agentKey());
            typed.put("agent_session_profile_version", pins.agentSessionProfileVersion());
            typed.put("memory_policy_id", pins.memoryPolicyId());
            if (persisted == null || !persisted.equals(typed)) {
                throw new IntakeAuthorityInvariantException(
                        "admission pinned_versions snapshot mismatch");
            }
        } catch (IntakeAuthorityInvariantException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IntakeAuthorityInvariantException(
                    "admission pinned_versions snapshot is malformed", exception);
        }
    }

    private static void requireRetry(PersistedAdmission admission, ActivityEnvelope envelope) {
        var admitted = admission.admittedRetryBudget();
        var activity = envelope.retryBudget();
        requireEqual(activity.providerAttemptsRemaining(), admitted.providerAttemptsRemaining(),
                "provider retry budget");
        requireEqual(activity.repairsRemaining(), admitted.repairsRemaining(), "repair retry budget");
        int admittedActivityAttempts = admitted.activityAttemptsRemaining();
        var invocation = envelope.invocation();
        if (admittedActivityAttempts < 1) {
            throw new IntakeAuthorityInvariantException("admitted Activity retry budget is exhausted");
        }
        switch (invocation.mode()) {
            case FIRST_EXECUTION -> requireEqual(
                    invocation.sharedRetriesRemaining(), admittedActivityAttempts - 1,
                    "first Activity shared retry budget");
            case INFRASTRUCTURE_RETRY -> {
                if (invocation.sharedRetriesRemaining() >= admittedActivityAttempts - 1) {
                    throw new IntakeAuthorityInvariantException(
                            "infrastructure retry did not consume the admitted retry budget");
                }
            }
            case RECONCILE_ONLY -> requireEqual(
                    invocation.sharedRetriesRemaining(), 0, "reconcile retry budget");
        }
    }

    private static void requireSelection(AuthorityRow row, PersistedAdmission admission) {
        SelectionRow selection = row.selection();
        AdmissionPins selectedPins = new AdmissionPins(
                selection.caseWorkflowType(), selection.caseWorkflowBuildId(),
                selection.roomWorkflowType(), selection.roomWorkflowBuildId(),
                selection.processContractVersion(), selection.graphKey(), selection.graphVersion(),
                selection.checkpointSchemaVersion(), selection.stateSchemaVersion(),
                selection.streamProtocol(), selection.promptVersion(), selection.modelProfileId(),
                selection.outputSchemaVersion(), selection.policyVersion(),
                selection.guardrailVersion(), selection.toolPolicyVersion(),
                selection.cohortPolicyVersion(), selection.agentKey(),
                selection.agentSessionProfileVersion(), selection.memoryPolicyId());
        requireEqual(admission.pins(), selectedPins, "admission selection pins");
        requireEqual(admission.selectionHash(), selection.selectionHash(),
                "admission selection hash");
        requireEqual(selection.graphKey(), "intake.v2", "selected graph key");
        requireEqual(selection.stateSchemaVersion(), "intake-graph-state.v2",
                "selected state schema");
        requireEqual(selection.outputSchemaVersion(), "intake-turn-proposal.v2",
                "selected output schema");
        requireEqual(selection.toolPolicyVersion(), "no-tools.v1", "selected tool policy");
        requireEqual(selection.agentKey(), "DISPUTE_INTAKE_OFFICER", "selected agent key");
        requireEqual(selection.agentSessionProfileVersion(), "agent-session-profile.v1",
                "selected Agent Session profile");
        requireEqual(selection.memoryPolicyId(), "GRAPH_PRIVATE_NO_MEMORY_FRAME_V1",
                "selected memory policy");
        requireEqual(row.epochGraphKey(), selection.graphKey(), "epoch graph key");
        requireEqual(row.epochGraphVersion(), selection.graphVersion(), "epoch graph version");
        requireEqual(row.epochCheckpointSchemaVersion(), selection.checkpointSchemaVersion(),
                "epoch checkpoint schema");
        requireEqual(row.epochRoomWorkflowBuildId(), selection.roomWorkflowBuildId(),
                "epoch room Workflow build");
        String expectedHash = EpochSelectionHasher.hash(new SelectionHashInput(
                "room-epoch-selection.v2", RoomType.INTAKE, WriterMode.SHADOW,
                selection.caseWorkflowType(), selection.caseWorkflowBuildId(),
                selection.roomWorkflowType(), selection.roomWorkflowBuildId(),
                selection.processContractVersion(), selection.graphKey(), selection.graphVersion(),
                selection.checkpointSchemaVersion(), selection.stateSchemaVersion(),
                selection.streamProtocol(), selection.promptVersion(), selection.modelProfileId(),
                selection.outputSchemaVersion(), selection.policyVersion(),
                selection.guardrailVersion(), selection.toolPolicyVersion(),
                selection.cohortPolicyVersion()));
        requireEqual(selection.selectionHash(), expectedHash, "selection hash");
        var registration = row.threadBinding().registration();
        requireEqual(admission.registrationHash(), registration.registrationHash(),
                "admission registration hash");
        requireEqual(registration.registrationHash(),
                IntakeContractHashes.registrationHash(registration), "registration hash");
        requireEqual(registration.promptVersion(), selection.promptVersion(),
                "registration prompt pin");
        requireEqual(registration.modelProfileId(), selection.modelProfileId(),
                "registration model pin");
        requireEqual(registration.graphVersion(), selection.graphVersion(),
                "registration graph pin");
        requireEqual(registration.checkpointSchemaVersion(), selection.checkpointSchemaVersion(),
                "registration checkpoint pin");
        requireEqual(registration.outputSchemaVersion(), selection.outputSchemaVersion(),
                "registration output pin");
        requireEqual(registration.policyVersion(), selection.policyVersion(),
                "registration policy pin");
        requireEqual(registration.guardrailVersion(), selection.guardrailVersion(),
                "registration guardrail pin");
        requireEqual(registration.toolPolicyVersion(), selection.toolPolicyVersion(),
                "registration tool pin");
        requireHash(admission.parityBaselineHash(), "parity baseline hash");
        if (admission.parityBaselineRef() == null
                || !admission.parityBaselineRef().matches("^(s3|minio|urn):.*")) {
            throw new IntakeAuthorityInvariantException("parity baseline reference is invalid");
        }
    }

    private static void requireStageOperation(
            PersistedAdmission admission, SnapshotPublicationRequest request) {
        requireCommandOperation(admission);
        requireEqual(request.operationKey(), IntakeOperationKeys.snapshotPublish(
                request.envelope().caseId(), request.envelope().roomEpoch(),
                request.envelope().actorScopeHash(), request.domainRevision()),
                "snapshot Activity operation key");
    }

    private static void requireStageOperation(
            PersistedAdmission admission, GraphExecutionRequest request) {
        requireCommandOperation(admission);
        requireEqual(request.operationKey(), IntakeOperationKeys.graphExecute(
                request.envelope().caseId(), request.envelope().roomEpoch(),
                request.threadId(), request.envelope().commandId()),
                "Graph Activity operation key");
    }

    private static void requireStageOperation(
            PersistedAdmission admission, TurnFinalizationRequest request) {
        requireCommandOperation(admission);
        requireEqual(request.operationKey(), IntakeOperationKeys.turnFinalize(
                request.envelope().caseId(), request.envelope().roomEpoch(), request.threadId(),
                request.envelope().commandId(),
                request.graphExecution().graphExecutionRef().resultHash()),
                "parity Activity operation key");
    }

    private static void requireCommandOperation(PersistedAdmission admission) {
        requireEqual(admission.commandOperationKey(),
                "intake.operation:" + admission.caseId() + ":" + admission.commandId(),
                "admitted command operation key");
    }

    private void requireNotExpired(ResolvedAuthority resolved) {
        PersistedAdmission admission = resolved.admission();
        if (admission.issuedAtEpochSeconds() < 0
                || admission.notBeforeEpochSeconds() < admission.issuedAtEpochSeconds()
                || admission.expiresAtEpochSeconds() <= admission.notBeforeEpochSeconds()
                || admission.expiresAtEpochSeconds() - admission.issuedAtEpochSeconds() > 60) {
            throw new IntakeAuthorityInvariantException(
                    "synthetic admission token validity window is malformed");
        }
        if (!clock.instant().isBefore(Instant.ofEpochMilli(admission.deadlineEpochMillis()))) {
            throw new IntakeAuthorityInvariantException("synthetic admission deadline elapsed");
        }
    }

    private static void requireAttemptLineage(GraphPlan plan) {
        if (plan.attemptNo() == 1) {
            if (plan.previousAttemptId() != null || plan.resetRequired()
                    || plan.publicSequenceOffset() != 0) {
                throw new IntakeAuthorityInvariantException("first Graph attempt lineage is invalid");
            }
            return;
        }
        if (plan.previousAttemptId() == null
                || plan.publicSequenceOffset() != (plan.resetRequired() ? 1 : 0)) {
            throw new IntakeAuthorityInvariantException("Graph retry lineage is invalid");
        }
    }

    private static void requireGraphReceipt(TurnFinalizationRequest request) {
        var envelope = request.envelope();
        var operation = request.graphExecution().operation();
        var graph = request.graphExecution().graphExecutionRef();
        requireEqual(operation.requestHash(), request.requestHash(), "Graph receipt request hash");
        requireEqual(operation.processRevision(), envelope.processRevision(),
                "Graph receipt process revision");
        requireEqual(operation.roomRevision(), envelope.roomRevision(),
                "Graph receipt room revision");
        requireEqual(graph.threadId(), request.threadId(), "Graph receipt thread");
        requireEqual(graph.graphCommandId(), envelope.commandId(), "Graph receipt command");
        requireEqual(graph.graphVersion(), envelope.pinnedVersions().graphVersion(),
                "Graph receipt version");
    }

    private static void requireResultArtifact(ImmutablePayloadRef result, GraphArtifactQuery query) {
        requireEqual(result.artifactType(), "GRAPH_RESULT", "result artifact type");
        requireEqual(result.artifactSchemaVersion(), "room-graph-result.v1", "result schema");
        requireEqual(result.uri(), query.resultRef(), "result URI");
        requireEqual(result.contentHash(), query.result().outputHash(), "result hash");
    }

    private static void requireProposalArtifact(ImmutablePayloadRef proposal, GraphArtifactQuery query) {
        var operation = query.result().artifactOperations().getFirst();
        var pointer = operation.artifact();
        requireEqual(proposal.artifactType(), "INTAKE_PROPOSAL", "proposal artifact type");
        requireEqual(proposal.artifactId(), pointer.artifactId(), "proposal artifact id");
        requireEqual(proposal.artifactSchemaVersion(), pointer.schemaVersion(), "proposal schema");
        requireEqual(proposal.uri(), pointer.uri(), "proposal URI");
        requireEqual(proposal.contentHash(), pointer.sha256(), "proposal hash");
    }

    private <T> T inSnapshot(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(REQUIRED_ISOLATION);
            connection.setAutoCommit(false);
            try {
                T value = work.run(connection);
                if (value instanceof ResolvedAuthority resolved) {
                    requireNotExpired(resolved);
                }
                connection.commit();
                return value;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection);
                throw failure;
            }
        } catch (IntakeAuthorityInvariantException exception) {
            throw exception;
        } catch (SQLException exception) {
            if (retryable(exception)) {
                throw new ReadUnavailableException(
                        "synthetic Intake authority snapshot is temporarily unavailable", exception);
            }
            throw new IntakeAuthorityInvariantException(
                    "synthetic Intake authority snapshot read failed", exception);
        }
    }

    private static boolean retryable(SQLException exception) {
        String state = exception.getSQLState();
        return state != null && (state.startsWith("08") || state.startsWith("40"));
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the authority failure that caused the rollback.
        }
    }

    private static <T> T exactlyOne(
            Connection connection, String sql, String label, Binder binder, Mapper<T> mapper)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IntakeAuthorityInvariantException(label + " expected one row, found 0");
                }
                T row = mapper.map(result);
                if (result.next()) {
                    throw new IntakeAuthorityInvariantException(label + " expected one row, found 2");
                }
                return row;
            }
        }
    }

    private static AuthorityRow mapAuthority(ResultSet row) throws SQLException {
        String tenant = row.getString("tenant_surrogate");
        String caseId = row.getString("case_id");
        long roomEpoch = row.getLong("room_epoch");
        long fence = row.getLong("fencing_token");
        String registrationId = row.getString("registration_id");
        String threadId = row.getString("thread_id");
        String actorScopeHash = row.getString("actor_scope_hash");
        String agentSessionId = row.getString("agent_session_id");
        ActorRole actorRole = ActorRole.valueOf(row.getString("actor_role"));
        Audience audience = Audience.valueOf(row.getString("audience"));
        ActorScope actorScope = new ActorScope(
                row.getString("actor_id"), actorRole, audience,
                parseCapabilities(row.getString("actor_capabilities_json")));
        IntakePrivateThreadRegistration registration = new IntakePrivateThreadRegistration(
                row.getString("registration_schema_version"), registrationId, tenant, caseId,
                "INTAKE", roomEpoch, threadId, actorScope, actorScopeHash, agentSessionId,
                row.getString("registration_graph_key"), row.getString("registration_graph_version"),
                row.getString("registration_checkpoint_schema_version"),
                row.getString("registration_state_schema_version"),
                row.getString("registration_prompt_version"),
                row.getString("registration_model_profile_id"),
                row.getString("registration_output_schema_version"),
                row.getString("registration_policy_version"),
                row.getString("registration_guardrail_version"),
                row.getString("registration_tool_policy_version"),
                WriterMode.valueOf(row.getString("registration_writer_mode")),
                instant(row, "issued_at"), row.getString("registration_hash"));
        IntakeGraphThreadBinding binding = new IntakeGraphThreadBinding(registration, fence);
        IntakeEventReference event = new IntakeEventReference(
                row.getString("event_binding_id"), registrationId,
                row.getString("event_id"), row.getString("message_id"), tenant, caseId,
                roomEpoch, fence, threadId, actorScopeHash, agentSessionId,
                new RoomGraphCommand.SnapshotRef(
                        row.getString("event_artifact_id"), row.getString("payload_schema_version"),
                        row.getString("event_uri"), row.getString("event_hash"),
                        row.getLong("event_size")),
                row.getString("event_object_version"), row.getLong("event_sequence"),
                row.getLong("event_domain_revision"),
                Audience.valueOf(row.getString("event_audience")),
                instant(row, "event_occurred_at"), instant(row, "event_created_at"));
        SelectionRow selection = new SelectionRow(
                row.getString("selection_hash"), row.getString("selection_writer_mode"),
                row.getString("case_workflow_type"), row.getString("case_workflow_build_id"),
                row.getString("room_workflow_type"), row.getString("room_workflow_build_id"),
                row.getString("process_contract_version"), row.getString("selection_graph_key"),
                row.getString("selection_graph_version"),
                row.getString("selection_checkpoint_schema_version"),
                row.getString("selection_state_schema_version"), row.getString("stream_protocol"),
                row.getString("selection_prompt_version"),
                row.getString("selection_model_profile_id"),
                row.getString("selection_output_schema_version"),
                row.getString("selection_policy_version"),
                row.getString("selection_guardrail_version"),
                row.getString("selection_tool_policy_version"),
                row.getString("cohort_policy_version"), row.getString("agent_key"),
                row.getString("agent_session_profile_version"), row.getString("memory_policy_id"));
        requireEqual(row.getString("authority_payload_uri"), row.getString("command_payload_uri"),
                "persisted payload URI");
        requireEqual(row.getString("authority_payload_hash"), row.getString("command_payload_hash"),
                "persisted payload hash");
        requireEqual(row.getLong("authority_payload_size"), row.getLong("command_payload_size"),
                "persisted payload size");
        return new AuthorityRow(
                row.getString("epoch_id"), row.getString("room_id"),
                row.getString("party_authority_id"), row.getString("case_command_id"),
                row.getString("payload_authority_id"), row.getString("access_session_id"),
                row.getString("command_id"),
                row.getLong("case_command_sequence"), row.getString("command_type"),
                row.getString("request_hash"), row.getLong("expected_process_revision"),
                row.getLong("accepted_room_revision"), row.getString("execution_disposition"),
                row.getString("source_kind"), row.getString("payload_schema_version"),
                row.getString("authority_payload_uri"), row.getString("authority_payload_hash"),
                row.getLong("authority_payload_size"), instant(row, "deadline_at"),
                row.getString("traceparent"), row.getString("party"),
                row.getString("epoch_writer_mode"), row.getString("lifecycle_status"),
                row.getString("provisioning_status"), row.getString("epoch_selection_schema_version"),
                row.getLong("epoch_process_revision"), row.getLong("epoch_room_revision"),
                row.getString("epoch_graph_key"), row.getString("epoch_graph_version"),
                row.getString("epoch_checkpoint_schema_version"),
                row.getString("epoch_room_workflow_build_id"), selection, binding,
                row.getString("access_status"), row.getString("agent_status"),
                row.getString("registration_status"), event);
    }

    private static IntakeSnapshotReference mapInitialSnapshot(ResultSet row) throws SQLException {
        return new IntakeSnapshotReference(
                row.getString("binding_id"), row.getString("thread_registration_id"),
                row.getString("tenant_surrogate"), row.getString("case_id"),
                row.getLong("room_epoch"), row.getLong("fencing_token"),
                row.getString("thread_id"), row.getString("actor_scope_hash"),
                row.getString("agent_session_id"),
                new RoomGraphCommand.SnapshotRef(
                        row.getString("artifact_id"), row.getString("schema_version"),
                        row.getString("object_uri"), row.getString("content_sha256"),
                        row.getLong("size_bytes")),
                row.getString("object_version"), row.getLong("domain_revision"),
                row.getLong("room_revision"), row.getLong("projection_revision"),
                row.getLong("initial_last_sequence"), instant(row, "created_at"));
    }

    private static List<String> parseCapabilities(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isArray()) {
                throw new IntakeAuthorityInvariantException("registration capabilities are malformed");
            }
            List<String> values = new ArrayList<>();
            node.forEach(value -> {
                if (!value.isTextual()) {
                    throw new IntakeAuthorityInvariantException(
                            "registration capabilities are malformed");
                }
                values.add(value.asText());
            });
            return List.copyOf(values);
        } catch (IntakeAuthorityInvariantException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IntakeAuthorityInvariantException(
                    "registration capabilities are malformed", exception);
        }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp timestamp = row.getTimestamp(column);
        if (timestamp == null) {
            throw new IntakeAuthorityInvariantException(column + " is missing");
        }
        return timestamp.toInstant();
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IntakeAuthorityInvariantException(field + " is not a lowercase SHA-256");
        }
    }

    private static void requireEqual(Object actual, Object expected, String field) {
        if (!Objects.equals(actual, expected)) {
            throw new IntakeAuthorityInvariantException(field + " mismatch");
        }
    }

    private static void requireEqual(long actual, long expected, String field) {
        if (actual != expected) {
            throw new IntakeAuthorityInvariantException(field + " mismatch");
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface Mapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    private record ResolvedAuthority(
            ActivityAuthority authority,
            PersistedAdmission admission,
            AuthorityRow row,
            IntakeSnapshotReference initialSnapshot) {}

    private record AuthorityRow(
            String epochId,
            String roomId,
            String partyAuthorityId,
            String caseCommandId,
            String payloadAuthorityId,
            String accessSessionId,
            String commandId,
            long commandSequence,
            String commandType,
            String requestHash,
            long expectedProcessRevision,
            long acceptedRoomRevision,
            String executionDisposition,
            String sourceKind,
            String payloadSchemaVersion,
            String payloadUri,
            String payloadHash,
            long payloadSize,
            Instant deadlineAt,
            String traceparent,
            String party,
            String epochWriterMode,
            String lifecycleStatus,
            String provisioningStatus,
            String epochSelectionSchemaVersion,
            long epochProcessRevision,
            long epochRoomRevision,
            String epochGraphKey,
            String epochGraphVersion,
            String epochCheckpointSchemaVersion,
            String epochRoomWorkflowBuildId,
            SelectionRow selection,
            IntakeGraphThreadBinding threadBinding,
            String accessStatus,
            String agentStatus,
            String registrationStatus,
            IntakeEventReference event) {}

    private record SelectionRow(
            String selectionHash,
            String writerMode,
            String caseWorkflowType,
            String caseWorkflowBuildId,
            String roomWorkflowType,
            String roomWorkflowBuildId,
            String processContractVersion,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String stateSchemaVersion,
            String streamProtocol,
            String promptVersion,
            String modelProfileId,
            String outputSchemaVersion,
            String policyVersion,
            String guardrailVersion,
            String toolPolicyVersion,
            String cohortPolicyVersion,
            String agentKey,
            String agentSessionProfileVersion,
            String memoryPolicyId) {}
}
