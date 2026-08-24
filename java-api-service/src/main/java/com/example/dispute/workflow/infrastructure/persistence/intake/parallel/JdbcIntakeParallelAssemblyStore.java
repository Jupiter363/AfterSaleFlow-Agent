package com.example.dispute.workflow.infrastructure.persistence.intake.parallel;

import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.AssemblyConflictException;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.AssemblyLookup;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ExactThreeInputs;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.FrameSetAuthority;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.PublishReady;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyArtifact;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyAuthority;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyLookup;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.SealedFrameRecord;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.SelectedFrameProof;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.AssemblyState;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.EventAuthority;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameType;
import com.example.dispute.workflow.contract.v1.AgentPlatformContractCodec;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphCommandEnvelope;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphResultEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL exact-three technical assembler artifact store. */
@Repository
public final class JdbcIntakeParallelAssemblyStore implements IntakeParallelAssemblyStore {

    private static final String GRAPH_RESULT_SCHEMA = "room-graph-result.schema.json";
    private static final String FRAME_ORDER =
            "case slot.frame_type when 'DIALOGUE_FRAME' then 1 "
                    + "when 'DOSSIER_FRAME' then 2 when 'QUALITY_FRAME' then 3 else 4 end";

    private static final String EXACT_THREE_SELECT =
            """
            select frame_set.frame_set_id, frame_set.agent_run_id,
                   frame_set.agent_run_attempt_id, frame_set.command_id,
                   frame_set.command_request_sha256, frame_set.tenant_surrogate,
                   frame_set.case_id, frame_set.room_id, frame_set.room_epoch,
                   frame_set.fencing_token, frame_set.thread_id,
                   frame_set.actor_scope_hash, frame_set.agent_session_id,
                   frame_set.event_binding_id, frame_set.thread_registration_id,
                   frame_set.logical_sequence, frame_set.binding_generation,
                   frame_set.authority_version, frame_set.context_envelope_sha256,
                   frame_set.model_context_view_sha256,
                   frame_set.execution_profile_id,
                   frame_set.projection_registry_version,
                   frame_set.model_profile_id, frame_set.turn_deadline_at,
                   frame_set.assembly_state, frame_set.version as frame_set_version,
                   frame_set.input_set_sha256, frame_set.proposal_artifact_id,
                   frame_set.proposal_sha256, frame_set.graph_result_artifact_id,
                   frame_set.graph_result_sha256, frame_set.terminal_receipt_id,
                   slot.frame_type, slot.current_generation,
                   slot.current_frame_id, slot.slot_state, slot.current_result_id,
                   generation.staging_state, generation.result_id as generation_result_id,
                   result.result_id, result.frame_id as result_frame_id,
                   result.canonical_result_json::text as canonical_result_json,
                   result.result_sha256, result.public_projection_sha256,
                   result.next_local_index, result.provider_call_count,
                   result.input_tokens, result.output_tokens, result.total_tokens,
                   result.latency_ms
              from intake_parallel_frame_set frame_set
              join intake_parallel_frame_slot slot
                on slot.frame_set_id = frame_set.frame_set_id
              join intake_parallel_frame_generation generation
                on generation.frame_set_id = slot.frame_set_id
               and generation.frame_type = slot.frame_type
               and generation.frame_generation = slot.current_generation
              join intake_parallel_frame_result result
                on result.result_id = slot.current_result_id
               and result.frame_set_id = slot.frame_set_id
               and result.frame_type = slot.frame_type
               and result.frame_generation = slot.current_generation
             where frame_set.frame_set_id = :frameSetId
               and frame_set.agent_run_id = :runId
               and frame_set.agent_run_attempt_id = :attemptId
               and frame_set.command_id = :commandId
               and frame_set.command_request_sha256 = :commandRequestSha256
             order by %s
            """.formatted(FRAME_ORDER);

    private static final String LOCK_EXACT_THREE =
            """
            select frame_set.frame_set_id, frame_set.agent_run_id,
                   frame_set.agent_run_attempt_id, frame_set.command_id,
                   frame_set.command_request_sha256, frame_set.tenant_surrogate,
                   frame_set.case_id, frame_set.room_id, frame_set.room_epoch,
                   frame_set.fencing_token, frame_set.thread_id,
                   frame_set.actor_scope_hash, frame_set.agent_session_id,
                   frame_set.event_binding_id, frame_set.thread_registration_id,
                   frame_set.logical_sequence, frame_set.binding_generation,
                   frame_set.authority_version, frame_set.context_envelope_sha256,
                   frame_set.model_context_view_sha256,
                   frame_set.execution_profile_id,
                   frame_set.projection_registry_version,
                   frame_set.model_profile_id, frame_set.turn_deadline_at,
                   frame_set.turn_deadline_at > clock_timestamp() as deadline_open,
                   frame_set.assembly_state, frame_set.version as frame_set_version,
                   frame_set.input_set_sha256, frame_set.proposal_artifact_id,
                   frame_set.proposal_sha256, frame_set.graph_result_artifact_id,
                   frame_set.graph_result_sha256, frame_set.terminal_receipt_id,
                   attempt.attempt_status, run.run_status, run.protocol,
                   run.finalization_status,
                   authority.current_binding_id,
                   authority.current_generation as current_binding_generation,
                   authority.authority_version as current_authority_version,
                   slot.frame_type, slot.current_generation,
                   slot.current_frame_id, slot.slot_state, slot.current_result_id,
                   generation.staging_state, generation.result_id as generation_result_id,
                   result.result_id, result.frame_id as result_frame_id,
                   result.canonical_result_json::text as canonical_result_json,
                   result.result_sha256, result.public_projection_sha256,
                   result.next_local_index, result.provider_call_count,
                   result.input_tokens, result.output_tokens, result.total_tokens,
                   result.latency_ms
              from intake_parallel_frame_set frame_set
              join agent_run_attempt attempt
                on attempt.id = frame_set.agent_run_attempt_id
               and attempt.agent_run_id = frame_set.agent_run_id
              join agent_run run on run.id = frame_set.agent_run_id
              join intake_parallel_frame_slot slot
                on slot.frame_set_id = frame_set.frame_set_id
              join intake_parallel_frame_generation generation
                on generation.frame_set_id = slot.frame_set_id
               and generation.frame_type = slot.frame_type
               and generation.frame_generation = slot.current_generation
              join intake_parallel_frame_result result
                on result.result_id = slot.current_result_id
               and result.frame_set_id = slot.frame_set_id
               and result.frame_type = slot.frame_type
               and result.frame_generation = slot.current_generation
              join case_intake_event_slot_authority authority
                on authority.thread_registration_id = frame_set.thread_registration_id
               and authority.logical_sequence = frame_set.logical_sequence
             where frame_set.frame_set_id = :frameSetId
               and frame_set.agent_run_id = :runId
               and frame_set.agent_run_attempt_id = :attemptId
               and frame_set.command_id = :commandId
               and frame_set.command_request_sha256 = :commandRequestSha256
             order by %s
             for update of frame_set, attempt, slot, generation, authority
            """.formatted(FRAME_ORDER);

    private static final String LOAD_READY_ARTIFACT =
            """
            select frame_set.assembly_state, frame_set.version as frame_set_version,
                   frame_set.input_set_sha256,
                   proposal.artifact_id as proposal_artifact_id,
                   proposal.artifact_uri as proposal_uri,
                   proposal.proposal_sha256, proposal.canonical_proposal_bytes,
                   proposal.profile_manifest_id,
                   graph.result_artifact_id, graph.result_ref,
                   graph.graph_result_sha256, graph.canonical_graph_result_bytes,
                   graph.canonical_command_envelope_bytes,
                   graph.command_envelope_sha256,
                   graph.canonical_proposal_source_bytes,
                   graph.target_proposal_sha256,
                   graph.canonical_result_envelope_bytes,
                   graph.result_envelope_sha256, graph.checkpoint_ns,
                   graph.registry_binding_sha256, graph.tool_policy_version
              from intake_parallel_frame_set frame_set
              join intake_parallel_proposal_artifact proposal
                on proposal.artifact_id = frame_set.proposal_artifact_id
               and proposal.frame_set_id = frame_set.frame_set_id
               and proposal.input_set_sha256 = frame_set.input_set_sha256
               and proposal.proposal_sha256 = frame_set.proposal_sha256
              join intake_parallel_graph_result_artifact graph
                on graph.result_artifact_id = frame_set.graph_result_artifact_id
               and graph.frame_set_id = frame_set.frame_set_id
               and graph.input_set_sha256 = frame_set.input_set_sha256
               and graph.graph_result_sha256 = frame_set.graph_result_sha256
             where frame_set.agent_run_id = :runId
               and frame_set.agent_run_attempt_id = :attemptId
               and frame_set.command_id = :commandId
               and frame_set.command_request_sha256 = :commandRequestSha256
               and frame_set.assembly_state in ('READY', 'COMMITTED')
            """;

    private static final String LOCK_READY_FOR_TERMINAL =
            """
            select frame_set.frame_set_id, frame_set.assembly_state,
                   frame_set.version as frame_set_version, frame_set.ready_at,
                   frame_set.event_binding_id, frame_set.binding_generation,
                   frame_set.authority_version,
                   authority.current_binding_id,
                   authority.current_generation as current_binding_generation,
                   authority.authority_version as current_authority_version,
                   frame_set.input_set_sha256,
                   proposal.artifact_id as proposal_artifact_id,
                   proposal.artifact_uri as proposal_uri,
                   proposal.proposal_sha256, proposal.canonical_proposal_bytes,
                   proposal.profile_manifest_id,
                   graph.result_artifact_id, graph.result_ref,
                   graph.graph_result_sha256, graph.canonical_graph_result_bytes,
                   graph.canonical_command_envelope_bytes,
                   graph.command_envelope_sha256,
                   graph.canonical_proposal_source_bytes,
                   graph.target_proposal_sha256,
                   graph.canonical_result_envelope_bytes,
                   graph.result_envelope_sha256, graph.checkpoint_ns,
                   graph.registry_binding_sha256, graph.tool_policy_version
              from intake_parallel_frame_set frame_set
              join intake_parallel_proposal_artifact proposal
                on proposal.artifact_id = frame_set.proposal_artifact_id
               and proposal.frame_set_id = frame_set.frame_set_id
               and proposal.input_set_sha256 = frame_set.input_set_sha256
               and proposal.proposal_sha256 = frame_set.proposal_sha256
              join intake_parallel_graph_result_artifact graph
                on graph.result_artifact_id = frame_set.graph_result_artifact_id
               and graph.frame_set_id = frame_set.frame_set_id
               and graph.input_set_sha256 = frame_set.input_set_sha256
               and graph.graph_result_sha256 = frame_set.graph_result_sha256
              join case_intake_event_slot_authority authority
                on authority.thread_registration_id = frame_set.thread_registration_id
               and authority.logical_sequence = frame_set.logical_sequence
             where frame_set.agent_run_id = :runId
               and frame_set.agent_run_attempt_id = :attemptId
               and frame_set.command_id = :commandId
               and frame_set.command_request_sha256 = :commandRequestSha256
               and frame_set.assembly_state in ('READY', 'COMMITTED')
             for update of frame_set, authority
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AgentPlatformContractCodec contractCodec;
    private final TargetE2EGraphEnvelopeCodec envelopeCodec;

    public JdbcIntakeParallelAssemblyStore(
            NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.contractCodec = new AgentPlatformContractCodec();
        this.envelopeCodec = new TargetE2EGraphEnvelopeCodec(this.objectMapper, contractCodec);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public ExactThreeInputs loadExactThree(AssemblyLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        List<Map<String, Object>> rows = jdbc.queryForList(EXACT_THREE_SELECT, parameters(lookup));
        requireExactThree(rows, "INTAKE_PARALLEL_ASSEMBLY_INPUT_INCOMPLETE");
        if (!AssemblyState.COLLECTING.name().equals(text(rows.getFirst(), "assembly_state"))) {
            throw conflict(
                    "INTAKE_PARALLEL_ASSEMBLY_NOT_COLLECTING",
                    "exact-three input snapshot requires a collecting Frame set");
        }
        return inputs(rows);
    }

    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public ReadyReceipt publishReady(PublishReady command) {
        Objects.requireNonNull(command, "command");
        validateArtifact(command.artifact());
        List<Map<String, Object>> rows = jdbc.queryForList(
                LOCK_EXACT_THREE, parameters(command.lookup()));
        requireExactThree(rows, "INTAKE_PARALLEL_ASSEMBLY_AUTHORITY_INCOMPLETE");
        AssemblyState state = AssemblyState.valueOf(text(rows.getFirst(), "assembly_state"));
        if (state == AssemblyState.READY || state == AssemblyState.COMMITTED) {
            ReadyArtifact stored = loadReadyRequired(new ReadyLookup(
                    command.lookup().runId(),
                    command.lookup().attemptId(),
                    command.lookup().commandId(),
                    command.lookup().commandRequestSha256()));
            requireSelectedProofs(command.selectedFrames(), rows);
            requireExactArtifact(command.artifact(), stored);
            return new ReadyReceipt(
                    false, state, number(rows.getFirst(), "frame_set_version"), stored);
        }
        if (state == AssemblyState.FAILED_UNCOMMITTED) {
            throw conflict(
                    "INTAKE_PARALLEL_ASSEMBLY_TERMINAL",
                    "failed-uncommitted Frame set cannot publish READY artifacts");
        }
        requireRunningAuthority(command, rows);
        requireSelectedProofs(command.selectedFrames(), rows);
        insertProposal(command.lookup().frameSetId(), command.artifact());
        insertGraphResult(command.lookup().frameSetId(), command.artifact());
        MapSqlParameterSource update = artifactParameters(command.lookup().frameSetId(), command.artifact())
                .addValue("expectedVersion", command.expectedFrameSetVersion())
                .addValue("readyAt", Timestamp.from(Instant.now()));
        if (jdbc.update(
                        """
                        update intake_parallel_frame_set
                           set assembly_state = 'READY',
                               input_set_sha256 = :inputSetSha256,
                               proposal_artifact_id = :proposalArtifactId,
                               proposal_sha256 = :proposalSha256,
                               graph_result_artifact_id = :resultArtifactId,
                               graph_result_sha256 = :graphResultSha256,
                               ready_at = :readyAt, updated_at = :readyAt,
                               version = version + 1
                         where frame_set_id = :frameSetId
                           and assembly_state = 'COLLECTING'
                           and version = :expectedVersion
                        """,
                        update)
                != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_ASSEMBLY_READY_CAS_FAILED",
                    "Frame-set version changed before READY publication");
        }
        return new ReadyReceipt(
                true,
                AssemblyState.READY,
                Math.addExact(command.expectedFrameSetVersion(), 1),
                command.artifact());
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public Optional<ReadyArtifact> loadReady(ReadyLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        List<Map<String, Object>> rows = jdbc.queryForList(
                LOAD_READY_ARTIFACT, readyParameters(lookup));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_READY_ARTIFACT_AMBIGUOUS",
                    "ready artifact lookup returned more than one immutable authority");
        }
        ReadyArtifact artifact = artifact(rows.getFirst());
        validateArtifact(artifact);
        return Optional.of(artifact);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReadyAuthority lockReadyForTerminal(ReadyLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        List<Map<String, Object>> rows = jdbc.queryForList(
                LOCK_READY_FOR_TERMINAL, readyParameters(lookup));
        if (rows.size() != 1) {
            throw conflict(
                    rows.isEmpty()
                            ? "INTAKE_PARALLEL_READY_AUTHORITY_MISSING"
                            : "INTAKE_PARALLEL_READY_AUTHORITY_AMBIGUOUS",
                    "terminalization requires exactly one immutable READY authority");
        }
        Map<String, Object> row = rows.getFirst();
        if (!text(row, "event_binding_id").equals(text(row, "current_binding_id"))
                || number(row, "binding_generation")
                        != number(row, "current_binding_generation")
                || number(row, "authority_version")
                        != number(row, "current_authority_version")) {
            throw conflict(
                    "INTAKE_PARALLEL_READY_AUTHORITY_STALE",
                    "READY Frame set is not bound to the current Intake event slot");
        }
        ReadyArtifact artifact = artifact(row);
        validateArtifact(artifact);
        return new ReadyAuthority(
                text(row, "frame_set_id"),
                AssemblyState.valueOf(text(row, "assembly_state")),
                number(row, "frame_set_version"),
                instant(row, "ready_at"),
                artifact);
    }

    private ExactThreeInputs inputs(List<Map<String, Object>> rows) {
        Map<String, Object> first = rows.getFirst();
        EventAuthority eventAuthority = new EventAuthority(
                text(first, "event_binding_id"),
                text(first, "thread_registration_id"),
                number(first, "logical_sequence"),
                number(first, "binding_generation"),
                number(first, "authority_version"),
                text(first, "command_request_sha256"));
        FrameSetAuthority authority = new FrameSetAuthority(
                text(first, "frame_set_id"),
                text(first, "agent_run_id"),
                text(first, "agent_run_attempt_id"),
                text(first, "command_id"),
                text(first, "command_request_sha256"),
                text(first, "tenant_surrogate"),
                text(first, "case_id"),
                text(first, "room_id"),
                number(first, "room_epoch"),
                number(first, "fencing_token"),
                text(first, "thread_id"),
                text(first, "actor_scope_hash"),
                text(first, "agent_session_id"),
                eventAuthority,
                text(first, "context_envelope_sha256"),
                text(first, "model_context_view_sha256"),
                text(first, "execution_profile_id"),
                text(first, "projection_registry_version"),
                text(first, "model_profile_id"),
                instant(first, "turn_deadline_at"),
                number(first, "frame_set_version"));
        EnumMap<FrameType, SealedFrameRecord> frames = new EnumMap<>(FrameType.class);
        for (Map<String, Object> row : rows) {
            FrameType type = FrameType.valueOf(text(row, "frame_type"));
            frames.put(type, sealedFrame(row, type));
        }
        return new ExactThreeInputs(authority, frames);
    }

    private SealedFrameRecord sealedFrame(Map<String, Object> row, FrameType type) {
        return new SealedFrameRecord(
                type,
                number(row, "current_generation"),
                text(row, "current_frame_id"),
                text(row, "current_result_id"),
                ContractJson.canonicalString(parseJson(text(row, "canonical_result_json"))),
                text(row, "result_sha256"),
                text(row, "public_projection_sha256"),
                number(row, "next_local_index"),
                number(row, "input_tokens"),
                number(row, "output_tokens"),
                number(row, "total_tokens"),
                number(row, "latency_ms"),
                Math.toIntExact(number(row, "provider_call_count")));
    }

    private static void requireExactThree(List<Map<String, Object>> rows, String code) {
        if (rows.size() != FrameType.values().length) {
            throw conflict(code, "exact-three Frame authority is missing or ambiguous");
        }
        EnumMap<FrameType, Boolean> observed = new EnumMap<>(FrameType.class);
        String frameSetId = text(rows.getFirst(), "frame_set_id");
        for (Map<String, Object> row : rows) {
            FrameType type = FrameType.valueOf(text(row, "frame_type"));
            if (observed.put(type, Boolean.TRUE) != null
                    || !frameSetId.equals(text(row, "frame_set_id"))
                    || !"SEALED".equals(text(row, "slot_state"))
                    || !"SEALED".equals(text(row, "staging_state"))
                    || !text(row, "current_frame_id").equals(text(row, "result_frame_id"))
                    || !text(row, "current_result_id").equals(text(row, "generation_result_id"))
                    || !text(row, "current_result_id").equals(text(row, "result_id"))) {
                throw conflict(code, "current Frame slot does not bind one sealed immutable result");
            }
        }
        if (!observed.keySet().equals(Map.of(
                        FrameType.DIALOGUE_FRAME, true,
                        FrameType.DOSSIER_FRAME, true,
                        FrameType.QUALITY_FRAME, true)
                .keySet())) {
            throw conflict(code, "exact-three Frame types are incomplete");
        }
    }

    private static void requireRunningAuthority(
            PublishReady command, List<Map<String, Object>> rows) {
        Map<String, Object> row = rows.getFirst();
        if (command.expectedFrameSetVersion() != number(row, "frame_set_version")
                || !"COLLECTING".equals(text(row, "assembly_state"))
                || !"RUNNING".equals(text(row, "attempt_status"))
                || !"RUNNING".equals(text(row, "run_status"))
                || !"UNCOMMITTED".equals(text(row, "finalization_status"))
                || !"agent-stream.v4".equals(text(row, "protocol"))
                || !Boolean.TRUE.equals(row.get("deadline_open"))) {
            throw conflict(
                    "INTAKE_PARALLEL_ASSEMBLY_ATTEMPT_NOT_RUNNING",
                    "READY publication requires a live collecting V4 attempt");
        }
        if (!text(row, "event_binding_id").equals(text(row, "current_binding_id"))
                || number(row, "binding_generation")
                        != number(row, "current_binding_generation")
                || number(row, "authority_version")
                        != number(row, "current_authority_version")) {
            throw conflict(
                    "INTAKE_PARALLEL_ASSEMBLY_EVENT_AUTHORITY_SUPERSEDED",
                    "READY publication is bound to a superseded V080 event authority");
        }
    }

    private static void requireSelectedProofs(
            Map<FrameType, SelectedFrameProof> expected, List<Map<String, Object>> rows) {
        EnumMap<FrameType, SelectedFrameProof> stored = new EnumMap<>(FrameType.class);
        for (Map<String, Object> row : rows) {
            FrameType type = FrameType.valueOf(text(row, "frame_type"));
            stored.put(type, new SelectedFrameProof(
                    type,
                    number(row, "current_generation"),
                    text(row, "current_frame_id"),
                    text(row, "current_result_id"),
                    text(row, "result_sha256"),
                    text(row, "public_projection_sha256")));
        }
        if (!stored.equals(expected)) {
            throw conflict(
                    "INTAKE_PARALLEL_ASSEMBLY_FRAME_AUTHORITY_DRIFT",
                    "selected Frame proofs differ from the locked current slots");
        }
    }

    private void insertProposal(String frameSetId, ReadyArtifact artifact) {
        MapSqlParameterSource parameters = artifactParameters(frameSetId, artifact)
                .addValue(
                        "canonicalProposalJson",
                        new String(artifact.canonicalProposalBytes(), StandardCharsets.UTF_8));
        int inserted = jdbc.update(
                """
                insert into intake_parallel_proposal_artifact (
                    artifact_id, frame_set_id, schema_version, input_set_sha256,
                    artifact_uri, canonical_proposal_json, canonical_proposal_bytes,
                    proposal_sha256, size_bytes, profile_manifest_id
                ) values (
                    :proposalArtifactId, :frameSetId, 'intake-turn-proposal.v2',
                    :inputSetSha256, :proposalUri, cast(:canonicalProposalJson as jsonb),
                    :canonicalProposalBytes, :proposalSha256, :proposalSizeBytes,
                    :profileManifestId
                ) on conflict do nothing
                """,
                parameters);
        if (inserted == 0) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    """
                    select artifact_id, frame_set_id, input_set_sha256, artifact_uri,
                           proposal_sha256, canonical_proposal_bytes, size_bytes,
                           profile_manifest_id
                      from intake_parallel_proposal_artifact
                     where artifact_id = :proposalArtifactId
                    """,
                    parameters);
            if (rows.size() != 1 || !sameProposalArtifact(frameSetId, artifact, rows.getFirst())) {
                throw conflict(
                        "INTAKE_PARALLEL_PROPOSAL_ARTIFACT_CONFLICT",
                        "proposal artifact identity is already bound to different bytes");
            }
        }
    }

    private void insertGraphResult(String frameSetId, ReadyArtifact artifact) {
        MapSqlParameterSource parameters = artifactParameters(frameSetId, artifact);
        int inserted = jdbc.update(
                """
                insert into intake_parallel_graph_result_artifact (
                    result_artifact_id, frame_set_id, input_set_sha256,
                    schema_version, result_ref, graph_result_sha256,
                    canonical_graph_result_bytes, graph_result_size_bytes,
                    proposal_artifact_id, proposal_sha256,
                    canonical_command_envelope_bytes, command_envelope_sha256,
                    command_envelope_size_bytes, canonical_proposal_source_bytes,
                    target_proposal_sha256, proposal_source_size_bytes,
                    canonical_result_envelope_bytes, result_envelope_sha256,
                    result_envelope_size_bytes, checkpoint_ns,
                    registry_binding_sha256, tool_policy_version
                ) values (
                    :resultArtifactId, :frameSetId, :inputSetSha256,
                    'room-graph-result.v1', :resultRef, :graphResultSha256,
                    :canonicalGraphResultBytes, :graphResultSizeBytes,
                    :proposalArtifactId, :proposalSha256,
                    :canonicalCommandEnvelopeBytes, :commandEnvelopeSha256,
                    :commandEnvelopeSizeBytes, :canonicalProposalSourceBytes,
                    :targetProposalSha256, :proposalSourceSizeBytes,
                    :canonicalResultEnvelopeBytes, :resultEnvelopeSha256,
                    :resultEnvelopeSizeBytes, :checkpointNs,
                    :registryBindingSha256, :toolPolicyVersion
                ) on conflict do nothing
                """,
                parameters);
        if (inserted == 0) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    """
                    select result_artifact_id, frame_set_id, input_set_sha256,
                           result_ref, graph_result_sha256,
                           canonical_graph_result_bytes, proposal_artifact_id,
                           proposal_sha256, canonical_command_envelope_bytes,
                           command_envelope_sha256, canonical_proposal_source_bytes,
                           target_proposal_sha256, canonical_result_envelope_bytes,
                           result_envelope_sha256, checkpoint_ns,
                           registry_binding_sha256, tool_policy_version
                      from intake_parallel_graph_result_artifact
                     where result_artifact_id = :resultArtifactId
                    """,
                    parameters);
            if (rows.size() != 1 || !sameGraphArtifact(frameSetId, artifact, rows.getFirst())) {
                throw conflict(
                        "INTAKE_PARALLEL_GRAPH_ARTIFACT_CONFLICT",
                        "Graph result artifact identity is already bound to different bytes");
            }
        }
    }

    private ReadyArtifact loadReadyRequired(ReadyLookup lookup) {
        return loadReady(lookup).orElseThrow(() -> conflict(
                "INTAKE_PARALLEL_READY_ARTIFACT_MISSING",
                "READY Frame set does not resolve its immutable artifacts"));
    }

    private static ReadyArtifact artifact(Map<String, Object> row) {
        return new ReadyArtifact(
                text(row, "input_set_sha256"),
                text(row, "proposal_artifact_id"),
                text(row, "proposal_uri"),
                text(row, "proposal_sha256"),
                bytes(row, "canonical_proposal_bytes"),
                text(row, "profile_manifest_id"),
                text(row, "result_artifact_id"),
                text(row, "result_ref"),
                text(row, "graph_result_sha256"),
                bytes(row, "canonical_graph_result_bytes"),
                bytes(row, "canonical_command_envelope_bytes"),
                text(row, "command_envelope_sha256"),
                bytes(row, "canonical_proposal_source_bytes"),
                text(row, "target_proposal_sha256"),
                bytes(row, "canonical_result_envelope_bytes"),
                text(row, "result_envelope_sha256"),
                text(row, "checkpoint_ns"),
                text(row, "registry_binding_sha256"),
                text(row, "tool_policy_version"));
    }

    private void validateArtifact(ReadyArtifact artifact) {
        JsonNode proposal = parseBytes(artifact.canonicalProposalBytes(), "proposal artifact");
        requireCanonical(artifact.canonicalProposalBytes(), proposal, "proposal artifact");
        String proposalHash = IntakeContractHashes.canonicalHashExcluding(proposal, "proposal_hash");
        if (!artifact.proposalSha256().equals(proposalHash)
                || !proposalHash.equals(proposal.path("proposal_hash").asText(null))) {
            throw conflict(
                    "INTAKE_PARALLEL_PROPOSAL_HASH_INVALID",
                    "proposal canonical bytes do not bind their self-hash");
        }

        TargetE2EGraphCommandEnvelope command =
                envelopeCodec.decodeCommand(artifact.canonicalCommandEnvelopeBytes());
        if (!artifact.commandEnvelopeSha256().equals(command.commandEnvelopeHash())
                || !MessageDigest.isEqual(
                        artifact.canonicalCommandEnvelopeBytes(),
                        envelopeCodec.encodeCommand(command))) {
            throw conflict(
                    "INTAKE_PARALLEL_COMMAND_ENVELOPE_HASH_INVALID",
                    "command envelope column or bytes differ from canonical authority");
        }
        byte[] proposalSource = envelopeCodec.validateProposalSource(
                artifact.canonicalProposalSourceBytes(),
                command.command(),
                artifact.targetProposalSha256());
        JsonNode proposalSourceDocument = parseBytes(proposalSource, "proposal source artifact");
        requireCanonical(proposalSource, proposalSourceDocument, "proposal source artifact");
        TargetE2EGraphResultEnvelope resultEnvelope = envelopeCodec.decodeResult(
                artifact.canonicalResultEnvelopeBytes(), command, proposalSource);
        if (!artifact.resultEnvelopeSha256().equals(resultEnvelope.resultEnvelopeHash())
                || !artifact.graphResultSha256().equals(resultEnvelope.resultHash())
                || !MessageDigest.isEqual(
                        artifact.canonicalResultEnvelopeBytes(),
                        envelopeCodec.encodeResult(
                                resultEnvelope, command, proposalSourceDocument))) {
            throw conflict(
                    "INTAKE_PARALLEL_RESULT_ENVELOPE_HASH_INVALID",
                    "result envelope columns or bytes differ from canonical authority");
        }
        RoomGraphResult graphResult;
        try {
            graphResult = contractCodec.decode(
                    GRAPH_RESULT_SCHEMA,
                    parseBytes(artifact.canonicalGraphResultBytes(), "Graph result artifact"),
                    RoomGraphResult.class);
        } catch (RuntimeException failure) {
            throw new AssemblyConflictException(
                    "INTAKE_PARALLEL_GRAPH_RESULT_INVALID",
                    "Graph result artifact cannot be decoded");
        }
        requireCanonical(
                artifact.canonicalGraphResultBytes(),
                objectMapper.valueToTree(graphResult),
                "Graph result artifact");
        if (!artifact.graphResultSha256().equals(graphResult.outputHash())
                || !artifact.graphResultSha256().equals(IntakeContractHashes.graphResultHash(graphResult))
                || !objectMapper.valueToTree(graphResult)
                        .equals(objectMapper.valueToTree(resultEnvelope.result()))) {
            throw conflict(
                    "INTAKE_PARALLEL_GRAPH_RESULT_HASH_INVALID",
                    "Graph result bytes differ from their output hash or result envelope");
        }
        var proposalOperations = graphResult.artifactOperations().stream()
                .filter(operation -> operation.operation() == ArtifactOperationType.PROPOSE_PATCH)
                .toList();
        if (proposalOperations.size() != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_GRAPH_RESULT_PROPOSAL_INVALID",
                    "Graph result must contain exactly one PROPOSE_PATCH");
        }
        var pointer = proposalOperations.getFirst().artifact();
        if (!artifact.proposalArtifactId().equals(pointer.artifactId())
                || !"intake-turn-proposal.v2".equals(pointer.schemaVersion())
                || !artifact.proposalUri().equals(pointer.uri())
                || !artifact.proposalSha256().equals(pointer.sha256())) {
            throw conflict(
                    "INTAKE_PARALLEL_GRAPH_RESULT_PROPOSAL_INVALID",
                    "Graph result pointer differs from immutable proposal authority");
        }
    }

    private static boolean sameProposalArtifact(
            String frameSetId, ReadyArtifact expected, Map<String, Object> stored) {
        return frameSetId.equals(text(stored, "frame_set_id"))
                && expected.inputSetSha256().equals(text(stored, "input_set_sha256"))
                && expected.proposalArtifactId().equals(text(stored, "artifact_id"))
                && expected.proposalUri().equals(text(stored, "artifact_uri"))
                && expected.proposalSha256().equals(text(stored, "proposal_sha256"))
                && expected.profileManifestId().equals(text(stored, "profile_manifest_id"))
                && expected.canonicalProposalBytes().length == number(stored, "size_bytes")
                && Arrays.equals(
                        expected.canonicalProposalBytes(), bytes(stored, "canonical_proposal_bytes"));
    }

    private static boolean sameGraphArtifact(
            String frameSetId, ReadyArtifact expected, Map<String, Object> stored) {
        return frameSetId.equals(text(stored, "frame_set_id"))
                && expected.inputSetSha256().equals(text(stored, "input_set_sha256"))
                && expected.resultArtifactId().equals(text(stored, "result_artifact_id"))
                && expected.resultRef().equals(text(stored, "result_ref"))
                && expected.graphResultSha256().equals(text(stored, "graph_result_sha256"))
                && expected.proposalArtifactId().equals(text(stored, "proposal_artifact_id"))
                && expected.proposalSha256().equals(text(stored, "proposal_sha256"))
                && expected.commandEnvelopeSha256().equals(text(stored, "command_envelope_sha256"))
                && expected.targetProposalSha256().equals(text(stored, "target_proposal_sha256"))
                && expected.resultEnvelopeSha256().equals(text(stored, "result_envelope_sha256"))
                && expected.checkpointNs().equals(text(stored, "checkpoint_ns"))
                && expected.registryBindingSha256().equals(text(stored, "registry_binding_sha256"))
                && expected.toolPolicyVersion().equals(text(stored, "tool_policy_version"))
                && Arrays.equals(
                        expected.canonicalGraphResultBytes(),
                        bytes(stored, "canonical_graph_result_bytes"))
                && Arrays.equals(
                        expected.canonicalCommandEnvelopeBytes(),
                        bytes(stored, "canonical_command_envelope_bytes"))
                && Arrays.equals(
                        expected.canonicalProposalSourceBytes(),
                        bytes(stored, "canonical_proposal_source_bytes"))
                && Arrays.equals(
                        expected.canonicalResultEnvelopeBytes(),
                        bytes(stored, "canonical_result_envelope_bytes"));
    }

    private static void requireExactArtifact(ReadyArtifact expected, ReadyArtifact actual) {
        boolean exact = expected.inputSetSha256().equals(actual.inputSetSha256())
                && expected.proposalArtifactId().equals(actual.proposalArtifactId())
                && expected.proposalUri().equals(actual.proposalUri())
                && expected.proposalSha256().equals(actual.proposalSha256())
                && expected.profileManifestId().equals(actual.profileManifestId())
                && expected.resultArtifactId().equals(actual.resultArtifactId())
                && expected.resultRef().equals(actual.resultRef())
                && expected.graphResultSha256().equals(actual.graphResultSha256())
                && expected.commandEnvelopeSha256().equals(actual.commandEnvelopeSha256())
                && expected.targetProposalSha256().equals(actual.targetProposalSha256())
                && expected.resultEnvelopeSha256().equals(actual.resultEnvelopeSha256())
                && expected.checkpointNs().equals(actual.checkpointNs())
                && expected.registryBindingSha256().equals(actual.registryBindingSha256())
                && expected.toolPolicyVersion().equals(actual.toolPolicyVersion())
                && Arrays.equals(expected.canonicalProposalBytes(), actual.canonicalProposalBytes())
                && Arrays.equals(
                        expected.canonicalGraphResultBytes(), actual.canonicalGraphResultBytes())
                && Arrays.equals(
                        expected.canonicalCommandEnvelopeBytes(), actual.canonicalCommandEnvelopeBytes())
                && Arrays.equals(
                        expected.canonicalProposalSourceBytes(), actual.canonicalProposalSourceBytes())
                && Arrays.equals(
                        expected.canonicalResultEnvelopeBytes(), actual.canonicalResultEnvelopeBytes());
        if (!exact) {
            throw conflict(
                    "INTAKE_PARALLEL_READY_REPLAY_CONFLICT",
                    "READY artifact replay differs from immutable stored authority");
        }
    }

    private static MapSqlParameterSource parameters(AssemblyLookup lookup) {
        return new MapSqlParameterSource()
                .addValue("frameSetId", lookup.frameSetId())
                .addValue("runId", lookup.runId())
                .addValue("attemptId", lookup.attemptId())
                .addValue("commandId", lookup.commandId())
                .addValue("commandRequestSha256", lookup.commandRequestSha256());
    }

    private static MapSqlParameterSource readyParameters(ReadyLookup lookup) {
        return new MapSqlParameterSource()
                .addValue("runId", lookup.runId())
                .addValue("attemptId", lookup.attemptId())
                .addValue("commandId", lookup.commandId())
                .addValue("commandRequestSha256", lookup.commandRequestSha256());
    }

    private static MapSqlParameterSource artifactParameters(
            String frameSetId, ReadyArtifact artifact) {
        return new MapSqlParameterSource()
                .addValue("frameSetId", frameSetId)
                .addValue("inputSetSha256", artifact.inputSetSha256())
                .addValue("proposalArtifactId", artifact.proposalArtifactId())
                .addValue("proposalUri", artifact.proposalUri())
                .addValue("proposalSha256", artifact.proposalSha256())
                .addValue("canonicalProposalBytes", artifact.canonicalProposalBytes())
                .addValue("proposalSizeBytes", artifact.canonicalProposalBytes().length)
                .addValue("profileManifestId", artifact.profileManifestId())
                .addValue("resultArtifactId", artifact.resultArtifactId())
                .addValue("resultRef", artifact.resultRef())
                .addValue("graphResultSha256", artifact.graphResultSha256())
                .addValue("canonicalGraphResultBytes", artifact.canonicalGraphResultBytes())
                .addValue("graphResultSizeBytes", artifact.canonicalGraphResultBytes().length)
                .addValue("canonicalCommandEnvelopeBytes", artifact.canonicalCommandEnvelopeBytes())
                .addValue("commandEnvelopeSha256", artifact.commandEnvelopeSha256())
                .addValue("commandEnvelopeSizeBytes", artifact.canonicalCommandEnvelopeBytes().length)
                .addValue("canonicalProposalSourceBytes", artifact.canonicalProposalSourceBytes())
                .addValue("targetProposalSha256", artifact.targetProposalSha256())
                .addValue("proposalSourceSizeBytes", artifact.canonicalProposalSourceBytes().length)
                .addValue("canonicalResultEnvelopeBytes", artifact.canonicalResultEnvelopeBytes())
                .addValue("resultEnvelopeSha256", artifact.resultEnvelopeSha256())
                .addValue("resultEnvelopeSizeBytes", artifact.canonicalResultEnvelopeBytes().length)
                .addValue("checkpointNs", artifact.checkpointNs())
                .addValue("registryBindingSha256", artifact.registryBindingSha256())
                .addValue("toolPolicyVersion", artifact.toolPolicyVersion());
    }

    private JsonNode parseBytes(byte[] bytes, String label) {
        try {
            JsonNode document = objectMapper.readTree(bytes);
            if (document == null || !document.isObject()) {
                throw conflict("INTAKE_PARALLEL_ARTIFACT_JSON_INVALID", label + " is not an object");
            }
            return document;
        } catch (IOException failure) {
            throw conflict("INTAKE_PARALLEL_ARTIFACT_JSON_INVALID", label + " is invalid JSON");
        }
    }

    private JsonNode parseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException failure) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_RESULT_CORRUPT",
                    "stored Frame result is invalid JSON");
        }
    }

    private static void requireCanonical(byte[] bytes, JsonNode document, String label) {
        if (!MessageDigest.isEqual(bytes, ContractJson.canonicalize(document))) {
            throw conflict(
                    "INTAKE_PARALLEL_ARTIFACT_NOT_CANONICAL", label + " is not RFC 8785 canonical");
        }
    }

    private static byte[] bytes(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        throw conflict(
                "INTAKE_PARALLEL_ARTIFACT_CORRUPT", field + " is not immutable byte authority");
    }

    private static String text(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw conflict("INTAKE_PARALLEL_AUTHORITY_CORRUPT", field + " is not nonblank text");
    }

    private static long number(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw conflict("INTAKE_PARALLEL_AUTHORITY_CORRUPT", field + " is not numeric");
    }

    private static Instant instant(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        throw conflict("INTAKE_PARALLEL_AUTHORITY_CORRUPT", field + " is not an instant");
    }

    private static AssemblyConflictException conflict(String code, String message) {
        return new AssemblyConflictException(code, message);
    }
}
