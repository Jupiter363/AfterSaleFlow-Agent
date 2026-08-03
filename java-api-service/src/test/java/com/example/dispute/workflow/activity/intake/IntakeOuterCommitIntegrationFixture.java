package com.example.dispute.workflow.activity.intake;

import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory.FinalizationFacts;
import com.example.dispute.room.infrastructure.persistence.JdbcIntakeGraphBindingStore;
import com.example.dispute.workflow.application.TemporalAgentRunV2WorkflowLauncher;
import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeEventReference;
import com.example.dispute.workflow.application.intake.IntakeFinalizationOperationKey;
import com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory;
import com.example.dispute.workflow.application.intake.IntakeGraphFinalizationRequest;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakeImmutableProposalReader.StoredProposal;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.GraphStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.contract.v1.RoomGraphResult.ExecutionMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

final class IntakeOuterCommitIntegrationFixture {

    static final Instant NOW = Instant.parse("2026-07-21T08:03:00Z");

    private IntakeOuterCommitIntegrationFixture() {}

    static Scenario create(ObjectMapper mapper, String suffix) {
        String caseId = "CASE_OUTER_" + suffix;
        String tenant = "tenant-outer-acid";
        String threadId = "grt.v1." + sha256(suffix).substring(0, 32);
        String registrationId = "REG_OUTER_" + suffix;
        String sessionId = "AGENT_SESSION_OUTER_" + suffix;
        String commandId = "COMMAND_OUTER_" + suffix;
        String runId = "RUN_OUTER_" + suffix;
        String attemptId = "ATTEMPT_OUTER_" + suffix;
        Instant issued = NOW.minus(5, ChronoUnit.MINUTES);
        var actor = new IntakePrivateThreadRegistration.ActorScope(
                "user-outer-" + suffix,
                ActorRole.USER,
                Audience.USER,
                List.of("graph.command.execute"));
        IntakeGraphThreadBinding binding =
                new IntakePrivateThreadRegistrationFactory(() -> threadId)
                        .issue(new IntakePrivateThreadRegistrationFactory.IssueRequest(
                                registrationId,
                                tenant,
                                caseId,
                                1,
                                2,
                                actor,
                                sessionId,
                                new IntakePrivateThreadRegistrationFactory.VersionPins(
                                        "2.0.0",
                                        "intake-checkpoint.v2",
                                        "intake-prompt.v2",
                                        "intake-model.synthetic.v1",
                                        "intake-policy.v2",
                                        "intake-guardrail.v2",
                                        "no-tools.v1"),
                                WriterMode.TEMPORAL,
                                issued));
        IntakeSnapshotReference snapshot = new IntakeSnapshotReference(
                "SNAPSHOT_OUTER_" + suffix,
                registrationId,
                tenant,
                caseId,
                1,
                2,
                threadId,
                binding.registration().actorScopeHash(),
                sessionId,
                new RoomGraphCommand.SnapshotRef(
                        "SNAPSHOT_OUTER_" + suffix,
                        "intake-domain-snapshot.v2",
                        "urn:intake:snapshot:" + suffix,
                        sha256("snapshot:" + suffix),
                        1024),
                "version-1",
                4,
                3,
                4,
                1,
                issued.plusSeconds(1));
        IntakeEventReference event = new IntakeEventReference(
                "EVENT_OUTER_" + suffix,
                registrationId,
                "EVENT_OUTER_" + suffix,
                "MESSAGE_PARTY_OUTER_" + suffix,
                tenant,
                caseId,
                1,
                2,
                threadId,
                binding.registration().actorScopeHash(),
                sessionId,
                new RoomGraphCommand.SnapshotRef(
                        "EVENT_OUTER_" + suffix,
                        "intake-turn-event.v2",
                        "urn:intake:event:" + suffix,
                        sha256("event:" + suffix),
                        512),
                "version-1",
                2,
                5,
                Audience.USER,
                issued.plusSeconds(2),
                issued.plusSeconds(3));
        RoomGraphCommand command = new IntakeGraphCommandFactory().create(
                new IntakeGraphCommandFactory.CommandRequest(
                        commandId,
                        runId,
                        attemptId,
                        binding,
                        snapshot,
                        event,
                        5,
                        "INTAKE_ACTIVE",
                        2,
                        "intake-agent.v2",
                        2,
                        3,
                        1,
                        NOW.plus(5, ChronoUnit.MINUTES),
                        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                        "graph-envelope.synthetic.v1",
                        "nonce-" + suffix));

        IntakeTurnProposal proposal = proposal(mapper, suffix, command, binding, snapshot, event);
        byte[] proposalPayload = ContractJson.canonicalize(mapper.valueToTree(proposal));
        StoredProposal storedProposal = new StoredProposal(
                "PROPOSAL_OUTER_" + suffix,
                "intake-turn-proposal.v2",
                "urn:intake:proposal:" + suffix,
                "version-1",
                proposal.proposalHash(),
                proposalPayload.length,
                proposalPayload);
        RoomGraphResult graphResult = graphResult(
                command, storedProposal, "CHECKPOINT_OUTER_" + suffix);
        IntakeGraphFinalizationRequest.Authority authority =
                new IntakeGraphFinalizationRequest.Authority(
                        tenant,
                        caseId,
                        1,
                        2,
                        threadId,
                        binding.registration().actorScopeHash(),
                        sessionId,
                        commandId,
                        runId,
                        attemptId,
                        graphResult.outputHash(),
                        proposal.proposalHash(),
                        graphResult.checkpointId(),
                        graphResult.cognitiveRevision(),
                        5,
                        3,
                        "INTAKE_ACTIVE",
                        2,
                        proposal.profileVersions());
        String operationKey = IntakeFinalizationOperationKey.create(
                caseId, 1, threadId, commandId, graphResult.outputHash());
        IntakeGraphFinalizationRequest unsigned = new IntakeGraphFinalizationRequest(
                operationKey,
                "0".repeat(64),
                authority,
                command,
                graphResult,
                binding,
                snapshot,
                event,
                new com.example.dispute.workflow.application.intake.IntakeProposalReference(
                        storedProposal.artifactId(),
                        storedProposal.schemaVersion(),
                        storedProposal.uri(),
                        storedProposal.objectVersion(),
                        storedProposal.contentSha256(),
                        storedProposal.sizeBytes()));
        IntakeGraphFinalizationRequest finalizationRequest =
                new IntakeGraphFinalizationRequest(
                        unsigned.operationKey(),
                        unsigned.canonicalRequestHash(),
                        authority,
                        command,
                        graphResult,
                        binding,
                        snapshot,
                        event,
                        unsigned.proposalReference());
        ExecuteAgentRunRequest executionRequest = new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                runId,
                1,
                "agent-stream.v2",
                "e".repeat(64),
                null,
                false,
                0,
                command);
        ExecuteAgentRunResult executionResult = new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                runId,
                runId,
                attemptId,
                1,
                ExecuteAgentRunResult.Outcome.COMPLETED,
                graphResult,
                graphResult.outputHash(),
                7,
                true,
                null,
                false,
                null,
                NOW);
        FinalizationFacts facts = new FinalizationFacts(
                2,
                logicalKey(runId),
                TemporalAgentRunV2WorkflowLauncher.workflowId(runId),
                "TEMPORAL_RUN_" + suffix,
                "outer-acid-build",
                "synthetic",
                "synthetic-1",
                "urn:agent-manifest:" + suffix,
                new ArtifactPointer(
                        "GRAPH_RESULT_OUTER_" + suffix,
                        graphResult.schemaVersion(),
                        "urn:graph-result:" + suffix,
                        graphResult.outputHash()),
                List.of(new ArtifactPointer(
                        storedProposal.artifactId(),
                        storedProposal.schemaVersion(),
                        storedProposal.uri(),
                        storedProposal.contentSha256())),
                List.of(),
                1,
                NOW.plusSeconds(1));
        return new Scenario(
                suffix,
                caseId,
                tenant,
                binding,
                snapshot,
                event,
                command,
                graphResult,
                finalizationRequest,
                storedProposal,
                executionRequest,
                executionResult,
                facts);
    }

    private static IntakeTurnProposal proposal(
            ObjectMapper mapper,
            String suffix,
            RoomGraphCommand command,
            IntakeGraphThreadBinding binding,
            IntakeSnapshotReference snapshot,
            IntakeEventReference event) {
        ObjectNode patch = mapper.createObjectNode();
        patch.put("schema_version", "intake-dossier.v2");
        patch.putObject("case_story").put("summary", "Outer ACID integration turn");
        patch.putObject("requested_resolution").put("kind", "REFUND");
        IntakeTurnProposal.ProfileVersions profiles =
                new IntakeTurnProposal.ProfileVersions(
                        command.graphVersion(),
                        command.checkpointSchemaVersion(),
                        command.invocationContext().promptProfileId(),
                        command.invocationContext().modelProfileId(),
                        command.invocationContext().outputSchemaVersion(),
                        command.invocationContext().policyVersion(),
                        command.invocationContext().guardrailVersion(),
                        binding.registration().toolPolicyVersion());
        IntakeTurnProposal unsigned = new IntakeTurnProposal(
                "intake-turn-proposal.v2",
                command.commandId(),
                command.logicalRunId(),
                command.attemptId(),
                command.caseId(),
                command.roomEpoch(),
                command.threadId(),
                binding.registration().actorScopeHash(),
                binding.registration().agentSessionId(),
                2,
                snapshot.payloadRef().sha256(),
                event.payloadRef().sha256(),
                "The requested resolution is refund.",
                patch,
                null,
                IntakeTurnProposal.Readiness.INCOMPLETE,
                List.of("requested_resolution_detail"),
                IntakeTurnProposal.Recommendation.NEED_MORE_INFO,
                IntakeTurnProposal.KnowledgeAnswerMode.NONE,
                new java.math.BigDecimal("0.82"),
                profiles,
                "0".repeat(64));
        JsonNode tree = mapper.valueToTree(unsigned);
        String proposalHash = IntakeContractHashes.canonicalHashExcluding(tree, "proposal_hash");
        return new IntakeTurnProposal(
                unsigned.schemaVersion(),
                unsigned.commandId(),
                unsigned.logicalRunId(),
                unsigned.attemptId(),
                unsigned.caseId(),
                unsigned.roomEpoch(),
                unsigned.threadId(),
                unsigned.actorScopeHash(),
                unsigned.agentSessionId(),
                unsigned.cognitiveRevision(),
                unsigned.sourceSnapshotHash(),
                unsigned.sourceEventHash(),
                unsigned.roomUtterance(),
                unsigned.dossierPatch(),
                unsigned.matrixPatch(),
                unsigned.readiness(),
                unsigned.missingFields(),
                unsigned.recommendation(),
                unsigned.knowledgeAnswerMode(),
                unsigned.confidence(),
                unsigned.profileVersions(),
                proposalHash);
    }

    private static RoomGraphResult graphResult(
            RoomGraphCommand command, StoredProposal proposal, String checkpointId) {
        RoomGraphResult unsigned = new RoomGraphResult(
                "room-graph-result.v1",
                command.commandId(),
                command.logicalRunId(),
                command.attemptId(),
                command.graphKey(),
                command.graphVersion(),
                checkpointId,
                2,
                GraphStatus.COMPLETED,
                List.of(),
                List.of(new RoomGraphResult.ArtifactOperation(
                        ArtifactOperationType.PROPOSE_PATCH,
                        new ArtifactPointer(
                                proposal.artifactId(),
                                proposal.schemaVersion(),
                                proposal.uri(),
                                proposal.contentSha256()))),
                null,
                null,
                null,
                "0".repeat(64),
                new Usage(10, 5, 15),
                new ExecutionMetadata(
                        command.invocationContext().promptProfileId(),
                        command.invocationContext().modelProfileId(),
                        command.invocationContext().outputSchemaVersion(),
                        command.invocationContext().policyVersion(),
                        command.invocationContext().guardrailVersion()));
        return new RoomGraphResult(
                unsigned.schemaVersion(),
                unsigned.commandId(),
                unsigned.logicalRunId(),
                unsigned.attemptId(),
                unsigned.graphKey(),
                unsigned.graphVersion(),
                unsigned.checkpointId(),
                unsigned.cognitiveRevision(),
                unsigned.status(),
                unsigned.publicEventProposals(),
                unsigned.artifactOperations(),
                unsigned.needsInput(),
                unsigned.needsReview(),
                unsigned.error(),
                IntakeContractHashes.graphResultHash(unsigned),
                unsigned.usage(),
                unsigned.executionMetadata());
    }

    static void insert(JdbcTemplate jdbc, ObjectMapper mapper, Scenario fixture) {
        String caseId = fixture.caseId();
        String roomId = "ROOM_" + caseId;
        String epochId = "EPOCH_" + caseId;
        String actorId = fixture.binding().registration().actorScope().actorId();
        String sessionId = fixture.binding().registration().agentSessionId();
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key, case_type,
                    case_status, initiator_role, initiator_id, respondent_role, respondent_id,
                    risk_level, title, description, current_room, created_by, updated_by
                ) values (?, ?, ?, ?, 'DISPUTE', 'INTAKE_IN_PROGRESS', 'USER', ?,
                    'MERCHANT', ?, 'MEDIUM', 'Outer ACID Intake', 'outer transaction fixture',
                    'INTAKE', 'test', 'test')
                """,
                caseId,
                actorId,
                "merchant-" + caseId,
                "create-" + caseId,
                actorId,
                "merchant-" + caseId);
        jdbc.update(
                """
                insert into case_participant (
                    id, case_id, actor_id, participant_role, participant_status,
                    joined_at, created_at, updated_at, created_by, updated_by
                ) values (?, ?, ?, 'USER', 'ACTIVE', ?, ?, ?, 'test', 'test')
                """,
                "PART_USER_" + caseId,
                caseId,
                actorId,
                now,
                now,
                now);
        jdbc.update(
                """
                insert into case_participant (
                    id, case_id, actor_id, participant_role, participant_status,
                    joined_at, created_at, updated_at, created_by, updated_by
                ) values (?, ?, ?, 'MERCHANT', 'ACTIVE', ?, ?, ?, 'test', 'test')
                """,
                "PART_MERCHANT_" + caseId,
                caseId,
                "merchant-" + caseId,
                now,
                now,
                now);
        jdbc.update(
                """
                insert into case_room (
                    id, case_id, room_type, room_status, opened_at, created_by, updated_by
                ) values (?, ?, 'INTAKE', 'OPEN', ?, 'test', 'test')
                """,
                roomId,
                caseId,
                now);
        jdbc.update(
                """
                insert into case_process_projection (
                    case_id, tenant_surrogate, macro_phase, current_room, room_phase, writer_mode,
                    writer_activation_status, process_revision, room_epoch, fencing_token,
                    last_command_sequence, last_case_event_sequence, temporal_workflow_id,
                    temporal_run_id, temporal_build_id, projected_at, updated_at
                ) values (?, ?, 'INTAKE', 'INTAKE', 'INTAKE_ACTIVE', 'TEMPORAL', 'READY',
                    5, 1, 2, 2, 0, ?, ?, 'outer-build', ?, ?)
                """,
                caseId,
                fixture.tenant(),
                "CASE_WORKFLOW_" + caseId,
                "CASE_RUN_" + caseId,
                now,
                now);
        jdbc.update(
                """
                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch, writer_mode,
                    lifecycle_status, provisioning_status, process_revision, room_revision,
                    fencing_token, temporal_workflow_id, temporal_run_id, room_temporal_workflow_id,
                    room_temporal_run_id, temporal_build_id, graph_key, graph_version,
                    checkpoint_schema_version, stream_protocol, selection_schema_version,
                    process_contract_version, workflow_type, room_workflow_type,
                    room_workflow_build_id, activated_at, provisioned_at, created_at, updated_at
                ) values (?, ?, ?, ?, 'INTAKE', 1, 'TEMPORAL', 'ACTIVE', 'READY', 5, 3, 2,
                    ?, ?, ?, ?, 'outer-build', 'intake.v2', '2.0.0', 'intake-checkpoint.v2',
                    'agent-stream.v2', 'room-epoch-selection.v2', 'case-process-contract.v1',
                    'CaseProcessWorkflow', 'IntakeRoomWorkflow', 'outer-room-build', ?, ?, ?, ?)
                """,
                epochId,
                fixture.tenant(),
                caseId,
                roomId,
                "CASE_WORKFLOW_" + caseId,
                "CASE_RUN_" + caseId,
                "ROOM_WORKFLOW_" + caseId,
                "ROOM_RUN_" + caseId,
                now,
                now,
                now,
                now);
        jdbc.update(
                """
                insert into case_access_session (
                    id, tenant_id, case_id, actor_id, actor_role, permission_level,
                    permission_scopes_json, status, created_at, updated_at, created_by
                ) values (?, ?, ?, ?, 'USER', 'PARTY_USER', cast(? as jsonb),
                    'ACTIVE', ?, ?, 'test')
                """,
                "ACCESS_" + caseId,
                fixture.tenant(),
                caseId,
                actorId,
                "[\"CASE_READ\",\"INTAKE_PRIVATE_READ\",\"INTAKE_PARTICIPATE\","
                        + "\"AGENT_SESSION_WRITE\"]",
                now,
                now);
        jdbc.update(
                """
                insert into agent_conversation_session (
                    id, tenant_id, case_id, room_type, actor_id, actor_role, agent_key,
                    access_session_id, prompt_profile_id, memory_policy_id, conversation_scope,
                    status, created_at, updated_at, created_by
                ) values (?, ?, ?, 'INTAKE', ?, 'USER', 'DISPUTE_INTAKE_OFFICER', ?,
                    'intake-prompt.v2', 'GRAPH_PRIVATE_NO_MEMORY_FRAME_V1', ?, 'ACTIVE', ?, ?, 'test')
                """,
                sessionId,
                fixture.tenant(),
                caseId,
                actorId,
                "ACCESS_" + caseId,
                "scope:" + caseId,
                now,
                now);

        JdbcIntakeGraphBindingStore bindings = new JdbcIntakeGraphBindingStore(
                new NamedParameterJdbcTemplate(jdbc.getDataSource()));
        bindings.register(fixture.binding());
        jdbc.update(
                """
                update case_intake_graph_thread_binding
                   set registration_status = 'REGISTERED', registered_at = created_at
                 where registration_id = ?
                """,
                fixture.binding().registration().registrationId());
        bindings.bindInitialSnapshot(fixture.snapshot());
        bindings.bindEvent(fixture.event());

        String runId = fixture.command().logicalRunId();
        String attemptId = fixture.command().attemptId();
        String requestHash = fixture.command().requestHash();
        String resultHash = fixture.graphResult().outputHash();
        ArtifactPointer output = fixture.facts().output();
        jdbc.update(
                """
                insert into immutable_payload_snapshot (
                    id, tenant_surrogate, case_id, room_type, snapshot_type,
                    source_type, source_id, schema_version, object_uri,
                    content_sha256, size_bytes, content_type, visibility,
                    created_at, created_by
                ) values (?, ?, ?, 'INTAKE', 'AGENT_OUTPUT', 'AGENT_RUN', ?, ?, ?,
                    ?, ?, 'application/json', 'INTERNAL', ?, 'test')
                """,
                output.artifactId(),
                fixture.tenant(),
                caseId,
                runId,
                output.schemaVersion(),
                output.uri(),
                output.sha256(),
                ContractJson.canonicalize(mapper.valueToTree(fixture.graphResult())).length,
                now);
        jdbc.update(
                """
                insert into agent_run (
                    id, case_id, room_id, agent_id, agent_role, profile_version, prompt_version,
                    skill_version, ruleset_version, model, run_status, input_refs_json,
                    validation_json, risk_flags_json, started_at, trace_id, created_by,
                    stream_operation, stream_endpoint, stream_request_json, stream_request_hash,
                    stream_audience_json, stream_audience_actor_ids_json, stream_idempotency_key,
                    stream_request_id, updated_at, tenant_surrogate, protocol,
                    logical_idempotency_key, executor_kind, finalization_status, room_epoch_id,
                    room_type, room_epoch, process_revision, fencing_token, request_hash,
                    attempt_limit, deadline_at, result_ready_attempt_id, final_result_hash,
                    lineage_schema_version, logical_input_hash
                ) values (?, ?, ?, 'agent-stream:intake', 'SYSTEM', 'runtime',
                    'intake-prompt.v2', 'intake-skill.v2', 'agent-stream.v2', 'synthetic',
                    'RUNNING', '{}'::jsonb, '{}'::jsonb, '[]'::jsonb, ?, 'trace-outer',
                    'test', 'INTAKE_TURN', 'internal://graph', '{}'::jsonb, ?, '[]'::jsonb,
                    '[]'::jsonb, ?, ?, ?, ?, 'agent-stream.v2', ?, 'TEMPORAL_ACTIVITY',
                    'UNCOMMITTED', ?, 'INTAKE', 1, 5, 2, ?, 1, ?, null, null,
                    'agent-run-lineage.v1', ?)
                """,
                runId,
                caseId,
                roomId,
                now,
                requestHash,
                logicalKey(runId),
                runId,
                now,
                fixture.tenant(),
                logicalKey(runId),
                epochId,
                requestHash,
                now.plusMinutes(5),
                fixture.executionRequest().logicalInputHash());
        jdbc.update(
                """
                insert into agent_run_attempt (
                    id, agent_run_id, attempt_no, attempt_status, executor_kind, provider,
                    model_profile_id, model_version, graph_key, graph_version,
                    checkpoint_schema_version, checkpoint_id, prompt_version,
                    output_schema_version, policy_version, guardrail_version, request_hash,
                    lineage_schema_version, command_id, command_request_hash, logical_input_hash,
                    command_json, reset_required, public_sequence_offset, result_hash, result_json,
                    input_tokens, output_tokens, total_tokens, latency_ms, public_output_emitted,
                    final_frame_observed, last_sequence_no, started_at, completed_at, created_at,
                    updated_at, created_by
                ) values (?, ?, 1, 'RESULT_READY', 'TEMPORAL_ACTIVITY', 'synthetic',
                    'intake-model.synthetic.v1', 'synthetic-1', 'intake.v2', '2.0.0',
                    'intake-checkpoint.v2', ?, 'intake-prompt.v2', 'intake-turn-proposal.v2',
                    'intake-policy.v2', 'intake-guardrail.v2', ?,
                    'agent-run-attempt-lineage.v1', ?, ?, ?, cast(? as jsonb), false, 0, ?,
                    cast(? as jsonb), 10, 5, 15, 1, true, true, 7, ?, ?, ?, ?, 'test')
                """,
                attemptId,
                runId,
                fixture.graphResult().checkpointId(),
                requestHash,
                fixture.command().commandId(),
                requestHash,
                fixture.executionRequest().logicalInputHash(),
                ContractJson.canonicalString(mapper.valueToTree(fixture.command())),
                resultHash,
                ContractJson.canonicalString(mapper.valueToTree(fixture.executionResult())),
                now,
                now,
                now,
                now);
        int transitioned = jdbc.update(
                """
                update agent_run
                   set run_status = 'RESULT_READY',
                       result_ready_attempt_id = ?,
                       final_result_hash = ?,
                       updated_at = ?
                 where id = ?
                   and run_status = 'RUNNING'
                   and result_ready_attempt_id is null
                   and final_result_hash is null
                """,
                attemptId,
                resultHash,
                now,
                runId);
        if (transitioned != 1) {
            throw new IllegalStateException("fixture AgentRun did not transition to RESULT_READY");
        }
    }

    private static String logicalKey(String runId) {
        return "key:" + runId;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 must be available", impossible);
        }
    }

    record Scenario(
            String suffix,
            String caseId,
            String tenant,
            IntakeGraphThreadBinding binding,
            IntakeSnapshotReference snapshot,
            IntakeEventReference event,
            RoomGraphCommand command,
            RoomGraphResult graphResult,
            IntakeGraphFinalizationRequest finalizationRequest,
            StoredProposal storedProposal,
            ExecuteAgentRunRequest executionRequest,
            ExecuteAgentRunResult executionResult,
            FinalizationFacts facts) {}
}
