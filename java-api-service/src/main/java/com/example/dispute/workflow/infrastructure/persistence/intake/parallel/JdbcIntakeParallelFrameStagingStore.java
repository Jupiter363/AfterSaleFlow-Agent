package com.example.dispute.workflow.infrastructure.persistence.intake.parallel;

import com.example.dispute.agentstream.application.AgentRunStreamEventService;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV4EventWriter;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.AssemblyState;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.AssemblyView;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.EventAuthority;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.ExactThreeCompletion;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.ExactThreeFrame;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.ExecutionAction;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.ExecutionLane;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.ExecutionPlan;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameManifest;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameRetryAdmission;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameRetryReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSealCommand;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSealReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSetAdmission;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSetReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameSlotView;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameType;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.IngressCommand;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.IngressReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.SlotState;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.StagingConflictException;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL technical staging store for one exact-three parallel Intake attempt. */
@Repository
public final class JdbcIntakeParallelFrameStagingStore
        implements IntakeParallelFrameStagingPort {

    private static final String LOCK_ADMISSION_AUTHORITY_SQL =
            """
            select attempt.attempt_status, attempt.command_id, attempt.command_request_hash,
                   attempt.model_profile_id as attempt_model_profile_id,
                   run.protocol, run.run_status, run.finalization_status,
                   run.tenant_surrogate as run_tenant_surrogate,
                   run.case_id as run_case_id, run.room_epoch_id, run.room_epoch,
                   run.fencing_token, binding.binding_id,
                   binding.thread_registration_id, binding.event_sequence,
                   binding.binding_generation, binding.tenant_surrogate,
                   binding.case_id, binding.room_epoch as binding_room_epoch,
                   binding.fencing_token as binding_fencing_token,
                   binding.thread_id, binding.actor_scope_hash,
                   binding.agent_session_id, binding.binding_type,
                   authority.current_binding_id,
                   authority.current_generation as current_binding_generation,
                   authority.authority_version
              from agent_run_attempt attempt
              join agent_run run on run.id = attempt.agent_run_id
              join case_intake_snapshot_binding binding
                on binding.binding_id = :eventBindingId
              join case_intake_event_slot_authority authority
                on authority.thread_registration_id = binding.thread_registration_id
               and authority.logical_sequence = binding.event_sequence
             where attempt.agent_run_id = :runId
               and attempt.id = :attemptId
               and binding.thread_registration_id = :threadRegistrationId
               and binding.event_sequence = :logicalSequence
               and binding.binding_generation = :bindingGeneration
             for update of attempt, authority
            """;

    private static final String INSERT_FRAME_SET_SQL =
            """
            insert into intake_parallel_frame_set (
                frame_set_id, agent_run_id, agent_run_attempt_id, command_id,
                command_request_sha256, tenant_surrogate, case_id, room_id,
                room_epoch, fencing_token, thread_id, actor_scope_hash,
                agent_session_id, event_binding_id, thread_registration_id,
                logical_sequence, binding_generation, authority_version,
                context_envelope_sha256, model_context_view_sha256,
                execution_profile_id, projection_registry_version,
                model_profile_id, turn_deadline_at
            ) values (
                :frameSetId, :runId, :attemptId, :commandId,
                :commandRequestSha256, :tenantSurrogate, :caseId, :roomId,
                :roomEpoch, :fencingToken, :threadId, :actorScopeSha256,
                :agentSessionId, :eventBindingId, :threadRegistrationId,
                :logicalSequence, :bindingGeneration, :authorityVersion,
                :contextEnvelopeSha256, :modelContextViewSha256,
                :executionProfileId, :projectionRegistryVersion,
                :modelProfileId, :turnDeadlineAt
            )
            on conflict do nothing
            """;

    private static final String INSERT_GENERATION_SQL =
            """
            insert into intake_parallel_frame_generation (
                frame_set_id, frame_type, frame_generation, frame_id,
                prompt_profile_id, output_schema_id, model_profile_id,
                frame_model_input_sha256, frame_prompt_sha256,
                repair_code, validation_path
            ) values (
                :frameSetId, :frameType, :frameGeneration, :frameId,
                :promptProfileId, :outputSchemaId, :modelProfileId,
                :frameModelInputSha256, :framePromptSha256,
                :repairCode, :validationPath
            )
            on conflict do nothing
            """;

    private static final String INSERT_SLOT_SQL =
            """
            insert into intake_parallel_frame_slot (
                frame_set_id, frame_type, current_generation,
                current_frame_id, slot_state
            ) values (
                :frameSetId, :frameType, :frameGeneration, :frameId, 'ADMITTED'
            )
            on conflict do nothing
            """;

    private static final String LOAD_FRAME_SET_SQL =
            """
            select frame_set_id, agent_run_id, agent_run_attempt_id, command_id,
                   command_request_sha256, tenant_surrogate, case_id, room_id,
                   room_epoch, fencing_token, thread_id, actor_scope_hash,
                   agent_session_id, event_binding_id, thread_registration_id,
                   logical_sequence, binding_generation, authority_version,
                   context_envelope_sha256, model_context_view_sha256,
                   execution_profile_id, projection_registry_version,
                   model_profile_id, turn_deadline_at, assembly_state,
                   input_set_sha256, proposal_artifact_id, proposal_sha256,
                   graph_result_sha256, terminal_receipt_id
              from intake_parallel_frame_set
             where frame_set_id = :frameSetId
            """;

    private static final String LOAD_MANIFESTS_SQL =
            """
            select frame_type, frame_generation, frame_id, prompt_profile_id,
                   output_schema_id, model_profile_id, frame_model_input_sha256,
                   frame_prompt_sha256, repair_code, validation_path
             from intake_parallel_frame_generation
             where frame_set_id = :frameSetId
               and frame_generation = 1
             order by frame_type, frame_generation
            """;

    private static final String LOAD_SLOTS_SQL =
            """
            select frame_type, current_generation, current_frame_id,
                   slot_state, current_result_id
              from intake_parallel_frame_slot
             where frame_set_id = :frameSetId
             order by frame_type
            """;

    private static final String LOCK_RETRY_SQL =
            """
            select frame_set.assembly_state, frame_set.agent_run_id,
                   frame_set.agent_run_attempt_id, frame_set.model_profile_id,
                   frame_set.event_binding_id, frame_set.thread_registration_id,
                   frame_set.logical_sequence, frame_set.binding_generation,
                   frame_set.authority_version, attempt.attempt_status,
                   frame_set.turn_deadline_at > clock_timestamp() as deadline_open,
                   run.run_status, run.protocol, run.finalization_status,
                   slot.current_generation as current_frame_generation,
                   slot.current_frame_id, slot.slot_state,
                   generation.failure_code, generation.failure_retryable,
                   authority.current_binding_id,
                   authority.current_generation as current_binding_generation,
                   authority.authority_version as current_authority_version
              from intake_parallel_frame_set frame_set
              join agent_run_attempt attempt
                on attempt.id = frame_set.agent_run_attempt_id
               and attempt.agent_run_id = frame_set.agent_run_id
              join agent_run run on run.id = frame_set.agent_run_id
              join intake_parallel_frame_slot slot
                on slot.frame_set_id = frame_set.frame_set_id
               and slot.frame_type = :frameType
              join intake_parallel_frame_generation generation
                on generation.frame_set_id = slot.frame_set_id
               and generation.frame_type = slot.frame_type
               and generation.frame_generation = slot.current_generation
              join case_intake_event_slot_authority authority
                on authority.thread_registration_id = frame_set.thread_registration_id
               and authority.logical_sequence = frame_set.logical_sequence
             where frame_set.frame_set_id = :frameSetId
            for update of frame_set, attempt, slot, generation, authority
            """;

    private static final String LOCK_FRAME_SQL =
            """
            select frame_set.assembly_state, frame_set.agent_run_id,
                   frame_set.agent_run_attempt_id, frame_set.command_id,
                   frame_set.command_request_sha256, frame_set.event_binding_id,
                   frame_set.thread_registration_id, frame_set.logical_sequence,
                   frame_set.binding_generation, frame_set.authority_version,
                   frame_set.context_envelope_sha256,
                   frame_set.model_context_view_sha256,
                   frame_set.projection_registry_version,
                   frame_set.model_profile_id, frame_set.turn_deadline_at,
                   frame_set.turn_deadline_at > clock_timestamp() as deadline_open,
                   attempt.attempt_status, attempt.last_sequence_no,
                   run.run_status, run.protocol, run.finalization_status,
                   run.created_by as actor_id,
                   run.stream_audience_actor_ids_json::text as audience_actor_ids_json,
                   slot.current_generation as current_frame_generation,
                   slot.current_frame_id, slot.slot_state,
                   slot.current_result_id, slot.slot_version,
                   generation.prompt_profile_id, generation.output_schema_id,
                   generation.model_profile_id as generation_model_profile_id,
                   generation.frame_model_input_sha256,
                   generation.frame_prompt_sha256, generation.repair_code,
                   generation.validation_path, generation.provider_call_lease_state,
                   generation.preview_state,
                   generation.first_preview_next_local_index,
                   generation.latest_snapshot_next_local_index,
                   generation.latest_snapshot_sha256,
                   generation.latest_projection_item_sha256,
                   generation.next_local_index, generation.staging_state,
                   generation.provider_call_count, generation.result_id,
                   generation.failure_code, generation.failure_retryable,
                   authority.current_binding_id,
                   authority.current_generation as current_binding_generation,
                   authority.authority_version as current_authority_version
              from intake_parallel_frame_set frame_set
              join agent_run_attempt attempt
                on attempt.id = frame_set.agent_run_attempt_id
               and attempt.agent_run_id = frame_set.agent_run_id
              join agent_run run on run.id = frame_set.agent_run_id
              join intake_parallel_frame_slot slot
                on slot.frame_set_id = frame_set.frame_set_id
               and slot.frame_type = :frameType
              join intake_parallel_frame_generation generation
                on generation.frame_set_id = slot.frame_set_id
               and generation.frame_type = slot.frame_type
               and generation.frame_generation = slot.current_generation
              join case_intake_event_slot_authority authority
                on authority.thread_registration_id = frame_set.thread_registration_id
               and authority.logical_sequence = frame_set.logical_sequence
             where frame_set.frame_set_id = :frameSetId
             for update of frame_set, attempt, slot, generation, authority
            """;

    private static final String LOCK_EXECUTION_PLAN_SQL =
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
                   frame_set.assembly_state,
                   frame_set.turn_deadline_at > clock_timestamp() as deadline_open,
                   attempt.attempt_status, attempt.last_sequence_no,
                   attempt.public_output_emitted,
                   run.run_status, run.protocol, run.finalization_status,
                   slot.frame_type, slot.current_generation,
                   slot.current_frame_id, slot.slot_state,
                   slot.current_result_id, slot.slot_version,
                   generation.prompt_profile_id, generation.output_schema_id,
                   generation.model_profile_id as generation_model_profile_id,
                    generation.frame_model_input_sha256,
                    generation.frame_prompt_sha256,
                    generation.repair_code, generation.validation_path,
                    generation.provider_call_lease_state,
                   generation.next_local_index, generation.staging_state,
                   generation.provider_call_count, generation.result_id,
                   generation.failure_code, generation.failure_retryable,
                   result.result_sha256, result.public_projection_sha256,
                   result.next_local_index as result_next_local_index,
                   authority.current_binding_id,
                   authority.current_generation as current_binding_generation,
                   authority.authority_version as current_authority_version
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
              left join intake_parallel_frame_result result
                on result.frame_set_id = slot.frame_set_id
               and result.frame_type = slot.frame_type
               and result.frame_generation = slot.current_generation
               and result.result_id = slot.current_result_id
              join case_intake_event_slot_authority authority
                on authority.thread_registration_id = frame_set.thread_registration_id
               and authority.logical_sequence = frame_set.logical_sequence
             where frame_set.frame_set_id = :frameSetId
             order by case slot.frame_type
                        when 'DIALOGUE_FRAME' then 1
                        when 'DOSSIER_FRAME' then 2
                        when 'QUALITY_FRAME' then 3
                        else 4
                      end
            for update of frame_set, attempt, slot, generation, authority
            """;

    private static final String LOAD_EXACT_THREE_COMPLETION_SQL =
            """
            select frame_set.frame_set_id, frame_set.agent_run_id,
                   frame_set.agent_run_attempt_id, frame_set.event_binding_id,
                   frame_set.thread_registration_id, frame_set.logical_sequence,
                   frame_set.binding_generation, frame_set.authority_version,
                   frame_set.assembly_state, attempt.last_sequence_no,
                   attempt.public_output_emitted,
                   slot.frame_type, slot.current_generation,
                   slot.current_frame_id, slot.slot_state,
                   slot.current_result_id, slot.slot_version,
                   generation.staging_state, generation.result_id,
                   generation.next_local_index,
                   result.result_sha256, result.public_projection_sha256,
                   result.next_local_index as result_next_local_index,
                   authority.current_binding_id,
                   authority.current_generation as current_binding_generation,
                   authority.authority_version as current_authority_version
              from intake_parallel_frame_set frame_set
              join agent_run_attempt attempt
                on attempt.id = frame_set.agent_run_attempt_id
               and attempt.agent_run_id = frame_set.agent_run_id
              join intake_parallel_frame_slot slot
                on slot.frame_set_id = frame_set.frame_set_id
              join intake_parallel_frame_generation generation
                on generation.frame_set_id = slot.frame_set_id
               and generation.frame_type = slot.frame_type
               and generation.frame_generation = slot.current_generation
              join intake_parallel_frame_result result
                on result.frame_set_id = slot.frame_set_id
               and result.frame_type = slot.frame_type
               and result.frame_generation = slot.current_generation
               and result.result_id = slot.current_result_id
              join case_intake_event_slot_authority authority
                on authority.thread_registration_id = frame_set.thread_registration_id
               and authority.logical_sequence = frame_set.logical_sequence
             where frame_set.frame_set_id = :frameSetId
             order by case slot.frame_type
                        when 'DIALOGUE_FRAME' then 1
                        when 'DOSSIER_FRAME' then 2
                        when 'QUALITY_FRAME' then 3
                        else 4
                      end
            """;

    private static final String FIND_INGRESS_REPLAY_SQL =
            """
            select ingress_id, frame_set_id, agent_run_id, agent_run_attempt_id,
                   frame_type, frame_generation, ingress_identity,
                   stream_session_id, transport_sequence, event_kind,
                   local_index, canonical_payload_sha256, global_sequence,
                   public_event_id, receipt_id
              from intake_parallel_frame_ingress
             where agent_run_id = :runId and agent_run_attempt_id = :attemptId
               and (
                    ingress_identity = :ingressIdentity
                    or (
                        frame_set_id = :frameSetId
                        and stream_session_id = :streamSessionId
                        and transport_sequence = :transportSequence
                    )
               )
             order by ingress_id
            """;

    private static final String INSERT_INGRESS_SQL =
            """
            insert into intake_parallel_frame_ingress (
                ingress_id, frame_set_id, agent_run_id, agent_run_attempt_id,
                frame_type, frame_generation, ingress_identity,
                stream_session_id, transport_sequence, event_kind, local_index,
                canonical_payload_json, canonical_payload_sha256,
                global_sequence, public_event_id, receipt_id
            ) values (
                :ingressId, :frameSetId, :runId, :attemptId,
                :frameType, :frameGeneration, :ingressIdentity,
                :streamSessionId, :transportSequence, :eventKind, :localIndex,
                cast(:canonicalPayloadJson as jsonb), :canonicalPayloadSha256,
                :globalSequence, :publicEventId, :receiptId
            )
            """;

    private static final String UPDATE_ATTEMPT_PROGRESS_SQL =
            """
            update agent_run_attempt
               set last_sequence_no = :globalSequence,
                   public_output_started = public_output_started or :visible,
                   public_output_started_at = case
                       when :visible then coalesce(public_output_started_at, :occurredAt)
                       else public_output_started_at
                   end,
                   public_output_emitted = public_output_emitted or :visible,
                   updated_at = greatest(updated_at, clock_timestamp()),
                   attempt_version = attempt_version + 1
             where agent_run_id = :runId and id = :attemptId
               and attempt_status = 'RUNNING'
               and last_sequence_no = :previousSequence
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PostgresAgentRunV4EventWriter eventWriter;
    private final AgentRunStreamEventService streamEventService;

    public JdbcIntakeParallelFrameStagingStore(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PostgresAgentRunV4EventWriter eventWriter,
            AgentRunStreamEventService streamEventService) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
        this.streamEventService =
                Objects.requireNonNull(streamEventService, "streamEventService");
    }

    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public FrameSetReceipt admit(FrameSetAdmission admission) {
        Objects.requireNonNull(admission, "admission");
        MapSqlParameterSource parameters = admissionParameters(admission);
        List<Map<String, Object>> authority =
                jdbc.queryForList(LOCK_ADMISSION_AUTHORITY_SQL, parameters);
        if (authority.size() != 1) {
            throw conflict("INTAKE_PARALLEL_ADMISSION_AUTHORITY_MISSING", "authority is absent");
        }
        requireAdmissionAuthority(admission, authority.getFirst());

        int inserted = jdbc.update(INSERT_FRAME_SET_SQL, parameters);
        if (inserted == 1) {
            for (FrameManifest manifest : admission.manifests()) {
                MapSqlParameterSource manifestParameters = manifestParameters(
                        admission.frameSetId(), manifest, null, null);
                if (jdbc.update(INSERT_GENERATION_SQL, manifestParameters) != 1
                        || jdbc.update(INSERT_SLOT_SQL, manifestParameters) != 1) {
                    throw conflict(
                            "INTAKE_PARALLEL_ADMISSION_PARTIAL",
                            "exact-three generation admission was not atomic");
                }
            }
        } else {
            requireExactAdmissionReplay(admission);
        }
        EnumMap<FrameType, Long> selected = new EnumMap<>(FrameType.class);
        for (FrameType frameType : FrameType.values()) {
            selected.put(frameType, 1L);
        }
        return new FrameSetReceipt(
                admission.frameSetId(),
                inserted == 1,
                deterministicId("IPFSR", admission.frameSetId()),
                AssemblyState.COLLECTING,
                selected);
    }

    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public FrameRetryReceipt admitRetry(FrameRetryAdmission admission) {
        Objects.requireNonNull(admission, "admission");
        FrameManifest replacement = admission.replacement();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("frameSetId", admission.frameSetId())
                .addValue("frameType", replacement.frameType().name());
        List<Map<String, Object>> rows = jdbc.queryForList(LOCK_RETRY_SQL, parameters);
        if (rows.size() != 1) {
            throw conflict("INTAKE_PARALLEL_RETRY_AUTHORITY_MISSING", "retry slot is absent");
        }
        Map<String, Object> row = rows.getFirst();
        requireCurrentEventAuthority(row);
        long currentGeneration = number(row, "current_frame_generation");
        String currentState = text(row, "slot_state");
        if (currentGeneration == replacement.generation()
                && replacement.frameId().equals(text(row, "current_frame_id"))) {
            requireRetryPredecessor(admission);
            requireStoredManifest(admission.frameSetId(), replacement, admission.repairCode(), admission.validationPath());
            return new FrameRetryReceipt(
                    admission.frameSetId(),
                    replacement.frameType(),
                    replacement.generation(),
                    replacement.frameId(),
                    deterministicId("IPFRR", replacement.frameId()),
                    false);
        }
        requireRunningCollecting(row);
        if (currentGeneration != admission.expectedCurrentGeneration()
                || !currentState.equals(admission.expectedCurrentState().name())) {
            throw conflict(
                    "INTAKE_PARALLEL_RETRY_SLOT_DRIFT",
                    "retry expected generation/state no longer owns the slot");
        }
        if (!Boolean.TRUE.equals(row.get("failure_retryable"))) {
            throw conflict(
                    "INTAKE_PARALLEL_RETRY_NOT_AUTHORIZED",
                    "terminal Frame generation is not retryable");
        }
        if (!admission.repairCode().equals(text(row, "failure_code"))) {
            throw conflict(
                    "INTAKE_PARALLEL_RETRY_REASON_DRIFT",
                    "retry repair code does not match the failed generation");
        }
        if (!replacement.modelProfileId().equals(text(row, "model_profile_id"))) {
            throw conflict("INTAKE_PARALLEL_RETRY_MODEL_DRIFT", "retry changed model profile");
        }
        MapSqlParameterSource replacementParameters = manifestParameters(
                admission.frameSetId(),
                replacement,
                admission.repairCode(),
                admission.validationPath());
        if (jdbc.update(INSERT_GENERATION_SQL, replacementParameters) != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_RETRY_GENERATION_CONFLICT",
                    "replacement generation identity already exists");
        }
        int advanced = jdbc.update(
                """
                update intake_parallel_frame_slot
                   set current_generation = :frameGeneration,
                       current_frame_id = :frameId,
                       slot_state = 'ADMITTED', current_result_id = null,
                       slot_version = slot_version + 1,
                       updated_at = clock_timestamp()
                 where frame_set_id = :frameSetId and frame_type = :frameType
                   and current_generation = :expectedGeneration
                   and slot_state = :expectedState
                """,
                replacementParameters
                        .addValue("expectedGeneration", admission.expectedCurrentGeneration())
                        .addValue("expectedState", admission.expectedCurrentState().name()));
        if (advanced != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_RETRY_SLOT_CAS_FAILED",
                    "replacement generation did not advance the current slot");
        }
        return new FrameRetryReceipt(
                admission.frameSetId(),
                replacement.frameType(),
                replacement.generation(),
                replacement.frameId(),
                deterministicId("IPFRR", replacement.frameId()),
                true);
    }

    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public ExecutionPlan planExecution(FrameSetAdmission admission) {
        Objects.requireNonNull(admission, "admission");
        List<Map<String, Object>> rows = jdbc.queryForList(
                LOCK_EXECUTION_PLAN_SQL,
                Map.of("frameSetId", admission.frameSetId()));
        if (rows.size() != FrameType.values().length) {
            throw conflict(
                    "INTAKE_PARALLEL_EXECUTION_PLAN_INCOMPLETE",
                    "execution planning requires exactly three current Frame slots");
        }
        requireExactAdmissionReplay(admission);
        EnumMap<FrameType, Map<String, Object>> current = new EnumMap<>(FrameType.class);
        for (Map<String, Object> row : rows) {
            if (!sameAdmission(admission, row)) {
                throw conflict(
                        "INTAKE_PARALLEL_EXECUTION_PLAN_AUTHORITY_DRIFT",
                        "execution plan differs from its durable admission");
            }
            requireCurrentEventAuthority(row);
            FrameType type = FrameType.valueOf(text(row, "frame_type"));
            if (current.put(type, row) != null) {
                throw conflict(
                        "INTAKE_PARALLEL_EXECUTION_PLAN_AMBIGUOUS",
                        "execution plan contains a duplicate Frame slot");
            }
        }
        if (!current.keySet().equals(java.util.Set.of(FrameType.values()))) {
            throw conflict(
                    "INTAKE_PARALLEL_EXECUTION_PLAN_INCOMPLETE",
                    "execution plan is missing a required Frame slot");
        }
        boolean allSealed = current.values().stream()
                .allMatch(row -> SlotState.SEALED.name().equals(text(row, "slot_state")));
        if (!allSealed) {
            current.values().forEach(JdbcIntakeParallelFrameStagingStore::requireRunningCollecting);
        }

        EnumMap<FrameType, FrameManifest> replacements = new EnumMap<>(FrameType.class);
        for (FrameType type : FrameType.values()) {
            Map<String, Object> row = current.get(type);
            SlotState state = SlotState.valueOf(text(row, "slot_state"));
            if (state != SlotState.FAILED && state != SlotState.AMBIGUOUS) {
                continue;
            }
            requireRetryablePlanningPredecessor(row, state);
            long currentGeneration = number(row, "current_generation");
            if (currentGeneration != 1) {
                throw conflict(
                        "INTAKE_PARALLEL_EXECUTION_RETRY_EXHAUSTED",
                        "parallel Frame recovery permits exactly one replacement generation");
            }
            long nextGeneration = currentGeneration + 1;
            replacements.put(type, new FrameManifest(
                    type,
                    nextGeneration,
                    replacementFrameId(
                            admission.frameSetId(),
                            type,
                            text(row, "current_frame_id"),
                            nextGeneration,
                            text(row, "frame_model_input_sha256")),
                    text(row, "prompt_profile_id"),
                    text(row, "output_schema_id"),
                    text(row, "generation_model_profile_id"),
                    text(row, "frame_model_input_sha256"),
                    text(row, "frame_prompt_sha256")));
        }

        for (FrameType type : FrameType.values()) {
            FrameManifest replacement = replacements.get(type);
            if (replacement == null) {
                continue;
            }
            Map<String, Object> predecessor = current.get(type);
            MapSqlParameterSource parameters = manifestParameters(
                    admission.frameSetId(),
                    replacement,
                    text(predecessor, "failure_code"),
                    IntakeParallelFrameStagingPort.RETRY_VALIDATION_PATH);
            if (jdbc.update(INSERT_GENERATION_SQL, parameters) != 1) {
                throw conflict(
                        "INTAKE_PARALLEL_EXECUTION_RETRY_CONFLICT",
                        "replacement generation identity already exists");
            }
        }

        for (FrameType type : FrameType.values()) {
            FrameManifest replacement = replacements.get(type);
            if (replacement == null) {
                continue;
            }
            Map<String, Object> predecessor = current.get(type);
            MapSqlParameterSource parameters = manifestParameters(
                            admission.frameSetId(),
                            replacement,
                            text(predecessor, "failure_code"),
                            IntakeParallelFrameStagingPort.RETRY_VALIDATION_PATH)
                    .addValue("expectedGeneration", number(predecessor, "current_generation"))
                    .addValue("expectedFrameId", text(predecessor, "current_frame_id"))
                    .addValue("expectedState", text(predecessor, "slot_state"))
                    .addValue("expectedSlotVersion", number(predecessor, "slot_version"));
            int advanced = jdbc.update(
                    """
                    update intake_parallel_frame_slot
                       set current_generation = :frameGeneration,
                           current_frame_id = :frameId,
                           slot_state = 'ADMITTED', current_result_id = null,
                           slot_version = slot_version + 1,
                           updated_at = clock_timestamp()
                     where frame_set_id = :frameSetId and frame_type = :frameType
                       and current_generation = :expectedGeneration
                       and current_frame_id = :expectedFrameId
                       and slot_state = :expectedState
                       and slot_version = :expectedSlotVersion
                    """,
                    parameters);
            if (advanced != 1) {
                throw conflict(
                        "INTAKE_PARALLEL_EXECUTION_RETRY_CAS_FAILED",
                        "replacement generation did not atomically advance its slot");
            }
        }

        EnumMap<FrameType, ExecutionLane> lanes = new EnumMap<>(FrameType.class);
        for (FrameType type : FrameType.values()) {
            Map<String, Object> row = current.get(type);
            FrameManifest replacement = replacements.get(type);
            if (replacement != null) {
                lanes.put(type, new ExecutionLane(
                        type,
                        replacement.generation(),
                        replacement.frameId(),
                        SlotState.ADMITTED,
                        ExecutionAction.RUN_RETRY,
                        0,
                        number(row, "slot_version") + 1,
                        null,
                        null,
                        null,
                        text(row, "failure_code")));
                continue;
            }
            SlotState state = SlotState.valueOf(text(row, "slot_state"));
            if (state == SlotState.STARTED) {
                throw conflict(
                        "INTAKE_PARALLEL_EXECUTION_STARTED_AMBIGUOUS",
                        "a started Frame has no safe cross-process resume authority");
            }
            if (state == SlotState.SEALED) {
                requireSealedPlanningResult(row);
                lanes.put(type, new ExecutionLane(
                        type,
                        number(row, "current_generation"),
                        text(row, "current_frame_id"),
                        SlotState.SEALED,
                        ExecutionAction.SKIP_SEALED,
                        number(row, "result_next_local_index"),
                        number(row, "slot_version"),
                        text(row, "current_result_id"),
                        text(row, "result_sha256"),
                        text(row, "public_projection_sha256"),
                        null));
                continue;
            }
            if (state != SlotState.ADMITTED
                    || !"ADMITTED".equals(text(row, "staging_state"))
                    || !"ADMITTED".equals(text(row, "provider_call_lease_state"))
                    || number(row, "next_local_index") != 0
                    || number(row, "provider_call_count") != 0
                    || nullableText(row, "current_result_id") != null
                    || nullableText(row, "result_id") != null
                    || nullableText(row, "failure_code") != null) {
                throw conflict(
                        "INTAKE_PARALLEL_EXECUTION_ADMISSION_DRIFT",
                        "current admitted Frame is not a fresh runnable generation");
            }
            long currentGeneration = number(row, "current_generation");
            String repairCode = nullableText(row, "repair_code");
            String validationPath = nullableText(row, "validation_path");
            ExecutionAction action;
            if (currentGeneration == 1 && repairCode == null && validationPath == null) {
                action = ExecutionAction.RUN_CURRENT;
            } else if (currentGeneration == 2
                    && repairCode != null
                    && IntakeParallelFrameStagingPort.RETRY_VALIDATION_PATH.equals(validationPath)) {
                action = ExecutionAction.RUN_RETRY;
            } else {
                throw conflict(
                        "INTAKE_PARALLEL_EXECUTION_LINEAGE_DRIFT",
                        "admitted Frame generation has no exact execution lineage");
            }
            lanes.put(type, new ExecutionLane(
                    type,
                    currentGeneration,
                    text(row, "current_frame_id"),
                    SlotState.ADMITTED,
                    action,
                    0,
                    number(row, "slot_version"),
                    null,
                    null,
                    null,
                    repairCode));
        }
        return new ExecutionPlan(
                admission.frameSetId(), admission.runId(), admission.attemptId(), lanes);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public Optional<ExactThreeCompletion> findExactThreeCompletion(
            String frameSetId, String runId, String attemptId) {
        Objects.requireNonNull(frameSetId, "frameSetId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(attemptId, "attemptId");
        List<Map<String, Object>> rows = jdbc.queryForList(
                LOAD_EXACT_THREE_COMPLETION_SQL,
                Map.of("frameSetId", frameSetId));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() != FrameType.values().length) {
            throw conflict(
                    "INTAKE_PARALLEL_COMPLETION_INCOMPLETE",
                    "durable completion does not expose exactly three Frame results");
        }
        EnumMap<FrameType, ExactThreeFrame> frames = new EnumMap<>(FrameType.class);
        long lastSequenceNo = -1;
        boolean publicOutputEmitted = false;
        for (Map<String, Object> row : rows) {
            if (!frameSetId.equals(text(row, "frame_set_id"))
                    || !runId.equals(text(row, "agent_run_id"))
                    || !attemptId.equals(text(row, "agent_run_attempt_id"))) {
                throw conflict(
                        "INTAKE_PARALLEL_COMPLETION_AUTHORITY_DRIFT",
                        "durable completion belongs to another run or attempt");
            }
            requireCurrentEventAuthority(row);
            requireSealedPlanningResult(row);
            FrameType type = FrameType.valueOf(text(row, "frame_type"));
            if (frames.put(type, new ExactThreeFrame(
                            type,
                            number(row, "current_generation"),
                            text(row, "current_frame_id"),
                            number(row, "slot_version"),
                            text(row, "current_result_id"),
                            text(row, "result_sha256"),
                            text(row, "public_projection_sha256"),
                            number(row, "result_next_local_index")))
                    != null) {
                throw conflict(
                        "INTAKE_PARALLEL_COMPLETION_AMBIGUOUS",
                        "durable completion contains a duplicate Frame result");
            }
            long observedLast = number(row, "last_sequence_no");
            boolean observedPublic = Boolean.TRUE.equals(row.get("public_output_emitted"));
            if ((lastSequenceNo >= 0 && lastSequenceNo != observedLast)
                    || (publicOutputEmitted && !observedPublic)) {
                throw conflict(
                        "INTAKE_PARALLEL_COMPLETION_WATERMARK_DRIFT",
                        "durable completion rows disagree on attempt progress");
            }
            lastSequenceNo = observedLast;
            publicOutputEmitted = observedPublic;
        }
        if (!frames.keySet().equals(java.util.Set.of(FrameType.values()))
                || lastSequenceNo < 0
                || !publicOutputEmitted) {
            throw conflict(
                    "INTAKE_PARALLEL_COMPLETION_NOT_PUBLIC",
                    "exact-three completion lacks durable public progress");
        }
        return Optional.of(new ExactThreeCompletion(
                frameSetId,
                runId,
                attemptId,
                lastSequenceNo,
                true,
                frames));
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public Optional<AssemblyView> findAssembly(String frameSetId) {
        Objects.requireNonNull(frameSetId, "frameSetId");
        List<Map<String, Object>> sets = jdbc.queryForList(
                LOAD_FRAME_SET_SQL, Map.of("frameSetId", frameSetId));
        if (sets.isEmpty()) {
            return Optional.empty();
        }
        if (sets.size() != 1) {
            throw conflict("INTAKE_PARALLEL_ASSEMBLY_AMBIGUOUS", "Frame set is ambiguous");
        }
        Map<String, Object> row = sets.getFirst();
        EventAuthority eventAuthority = new EventAuthority(
                text(row, "event_binding_id"),
                text(row, "thread_registration_id"),
                number(row, "logical_sequence"),
                number(row, "binding_generation"),
                number(row, "authority_version"),
                text(row, "command_request_sha256"));
        EnumMap<FrameType, FrameSlotView> slots = new EnumMap<>(FrameType.class);
        for (Map<String, Object> slot : jdbc.queryForList(
                LOAD_SLOTS_SQL, Map.of("frameSetId", frameSetId))) {
            FrameType type = FrameType.valueOf(text(slot, "frame_type"));
            if (slots.put(
                            type,
                            new FrameSlotView(
                                    type,
                                    number(slot, "current_generation"),
                                    text(slot, "current_frame_id"),
                                    SlotState.valueOf(text(slot, "slot_state")),
                                    nullableText(slot, "current_result_id")))
                    != null) {
                throw conflict(
                        "INTAKE_PARALLEL_ASSEMBLY_SLOT_AMBIGUOUS",
                        "Frame set has duplicate current slots");
            }
        }
        if (slots.size() != FrameType.values().length) {
            throw conflict(
                    "INTAKE_PARALLEL_ASSEMBLY_SLOT_INCOMPLETE",
                    "Frame set does not expose exact-three slots");
        }
        return Optional.of(new AssemblyView(
                frameSetId,
                text(row, "agent_run_id"),
                text(row, "agent_run_attempt_id"),
                eventAuthority,
                text(row, "context_envelope_sha256"),
                text(row, "model_context_view_sha256"),
                AssemblyState.valueOf(text(row, "assembly_state")),
                slots,
                nullableText(row, "input_set_sha256"),
                nullableText(row, "proposal_artifact_id"),
                nullableText(row, "proposal_sha256"),
                nullableText(row, "graph_result_sha256"),
                nullableText(row, "terminal_receipt_id")));
    }

    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public IngressReceipt append(IngressCommand command) {
        Objects.requireNonNull(command, "command");
        JsonNode payloadNode = objectMapper.valueToTree(command.publicPayload());
        String canonicalPayloadJson = ContractJson.canonicalString(payloadNode);
        String canonicalPayloadSha256 = ContractJson.sha256Hex(payloadNode);
        if (!canonicalPayloadSha256.equals(command.canonicalPayloadSha256())) {
            throw conflict(
                    "INTAKE_PARALLEL_INGRESS_PAYLOAD_HASH_INVALID",
                    "ingress payload hash does not bind canonical V4 payload");
        }

        MapSqlParameterSource identity = ingressIdentityParameters(
                command.frameSetId(),
                command.runId(),
                command.attemptId(),
                command.streamSessionId(),
                command.transportSequence(),
                command.ingressIdentity(),
                command.frameType(),
                command.generation(),
                command.ingressKind().publicEventType().wireValue(),
                command.localIndex(),
                canonicalPayloadSha256);
        Map<String, Object> authority = lockFrame(
                command.frameSetId(), command.frameType());
        requireFrameSetAuthority(
                authority,
                command.runId(),
                command.attemptId(),
                command.frameType());

        Optional<Map<String, Object>> replay = findIngressReplay(identity);
        if (replay.isPresent()) {
            requireExactIngressReplay(identity, replay.orElseThrow());
            long durableHighWatermark =
                    durableHighWatermark(command.runId(), command.attemptId());
            scheduleStreamCatchUp(command.runId(), command.attemptId(), durableHighWatermark);
            return new IngressReceipt(
                    text(replay.orElseThrow(), "ingress_id"),
                    text(replay.orElseThrow(), "receipt_id"),
                    false,
                    number(replay.orElseThrow(), "global_sequence"),
                    durableHighWatermark);
        }

        requireCurrentFrameAuthority(
                authority, command.generation(), command.publicPayload());
        requireRunningCollecting(authority);
        requireSessionPosition(
                command.frameSetId(),
                command.streamSessionId(),
                command.transportSequence());
        requireIngressState(command, authority);
        long previousSequence = number(authority, "last_sequence_no");
        long globalSequence = Math.addExact(previousSequence, 1L);
        String publicEventId = randomId("ARSE4");
        String ingressId = randomId("IPFI");
        String receiptId = randomId("IPFIR");

        var eventReceipt = eventWriter.appendInCurrentTransaction(
                new PostgresAgentRunV4EventWriter.EventWriteCommand(
                        publicEventId,
                        command.runId(),
                        command.attemptId(),
                        globalSequence,
                        command.ingressKind().publicEventType(),
                        command.audience(),
                        command.occurredAt(),
                        command.publicPayload(),
                        text(authority, "actor_id"),
                        text(authority, "audience_actor_ids_json")));
        MapSqlParameterSource insert = copy(identity)
                .addValue("ingressId", ingressId)
                .addValue("canonicalPayloadJson", canonicalPayloadJson)
                .addValue("globalSequence", globalSequence)
                .addValue("publicEventId", publicEventId)
                .addValue("receiptId", receiptId);
        if (jdbc.update(INSERT_INGRESS_SQL, insert) != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_INGRESS_INSERT_CONFLICT",
                    "ingress identity was claimed concurrently");
        }
        applyIngressMutation(command, authority, ingressId, globalSequence);
        boolean visible = command.ingressKind()
                != IntakeParallelFrameStagingPort.IngressKind.USAGE;
        if (jdbc.update(
                        UPDATE_ATTEMPT_PROGRESS_SQL,
                        insert.addValue("previousSequence", previousSequence)
                                .addValue("visible", visible)
                                .addValue("occurredAt", Timestamp.from(command.occurredAt())))
                != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_STREAM_SEQUENCE_CAS_FAILED",
                    "attempt sequence authority changed during append");
        }
        scheduleStreamCatchUp(
                command.runId(), command.attemptId(), eventReceipt.durableHighWatermark());
        return new IngressReceipt(
                ingressId,
                receiptId,
                true,
                globalSequence,
                eventReceipt.durableHighWatermark());
    }

    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public FrameSealReceipt seal(FrameSealCommand command) {
        Objects.requireNonNull(command, "command");
        JsonNode resultNode = readJson(command.canonicalResultJson(), "canonical Frame result");
        String canonicalResultJson = ContractJson.canonicalString(resultNode);
        if (!canonicalResultJson.equals(command.canonicalResultJson())
                || !ContractJson.sha256Hex(resultNode).equals(command.resultSha256())) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_RESULT_HASH_INVALID",
                    "Frame result is not canonical or its hash drifted");
        }

        Map<String, Object> authority = lockFrame(command.frameSetId(), command.frameType());
        requireFrameSetAuthority(
                authority,
                command.runId(),
                command.attemptId(),
                command.frameType());
        requireCurrentFrameAuthority(authority, command.generation(), null);
        Optional<FrameSealReceipt> replay = exactSealReplay(command, authority);
        if (replay.isPresent()) {
            FrameSealReceipt receipt = replay.orElseThrow();
            scheduleStreamCatchUp(
                    command.runId(), command.attemptId(), receipt.durableHighWatermark());
            return receipt;
        }

        requireRunningCollecting(authority);
        if (!"STARTED".equals(text(authority, "staging_state"))
                || !"STARTED".equals(text(authority, "slot_state"))) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_NOT_SEALABLE",
                    "only the current started Frame generation may seal");
        }
        if (!command.frameId().equals(text(authority, "current_frame_id"))
                || !command.contextEnvelopeSha256()
                        .equals(text(authority, "context_envelope_sha256"))
                || !command.modelContextViewSha256()
                        .equals(text(authority, "model_context_view_sha256"))
                || command.nextLocalIndex() != number(authority, "next_local_index")) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_SEAL_AUTHORITY_DRIFT",
                    "Frame seal does not match current context, identity, or projection watermark");
        }
        String computedProjectionSha256 = projectionSha256(
                command.frameSetId(),
                command.frameType(),
                command.generation(),
                command.nextLocalIndex());
        if (!computedProjectionSha256.equals(command.publicProjectionSha256())) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_PROJECTION_HASH_INVALID",
                    "Frame seal projection hash does not bind the durable item trace");
        }
        requireSessionPosition(
                command.frameSetId(),
                command.streamSessionId(),
                command.transportSequence());

        long previousSequence = number(authority, "last_sequence_no");
        long globalSequence = Math.addExact(previousSequence, 1L);
        String resultId = randomId("IPFR");
        String frameReceiptId = randomId("IPFSR");
        String ingressId = randomId("IPFI");
        String publicEventId = randomId("ARSE4");
        AgentStreamEventV4.Payload payload = sealedPayload(command, frameReceiptId);
        JsonNode payloadNode = objectMapper.valueToTree(payload);
        String canonicalPayloadJson = ContractJson.canonicalString(payloadNode);
        String canonicalPayloadSha256 = ContractJson.sha256Hex(payloadNode);
        MapSqlParameterSource identity = ingressIdentityParameters(
                command.frameSetId(),
                command.runId(),
                command.attemptId(),
                command.streamSessionId(),
                command.transportSequence(),
                command.ingressIdentity(),
                command.frameType(),
                command.generation(),
                AgentStreamEventV4.EventType.PUBLIC_FRAME_SEALED.wireValue(),
                null,
                canonicalPayloadSha256);
        var eventReceipt = eventWriter.appendInCurrentTransaction(
                new PostgresAgentRunV4EventWriter.EventWriteCommand(
                        publicEventId,
                        command.runId(),
                        command.attemptId(),
                        globalSequence,
                        AgentStreamEventV4.EventType.PUBLIC_FRAME_SEALED,
                        command.audience(),
                        command.completedAt(),
                        payload,
                        text(authority, "actor_id"),
                        text(authority, "audience_actor_ids_json")));
        MapSqlParameterSource insert = copy(identity)
                .addValue("ingressId", ingressId)
                .addValue("canonicalPayloadJson", canonicalPayloadJson)
                .addValue("globalSequence", globalSequence)
                .addValue("publicEventId", publicEventId)
                .addValue("receiptId", frameReceiptId);
        if (jdbc.update(INSERT_INGRESS_SQL, insert) != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_SEAL_INGRESS_CONFLICT",
                    "Frame seal ingress identity was claimed concurrently");
        }
        MapSqlParameterSource resultParameters = copy(insert)
                .addValue("resultId", resultId)
                .addValue("frameId", command.frameId())
                .addValue("childCheckpointRef", command.childCheckpointRef())
                .addValue("childCheckpointSha256", command.childCheckpointSha256())
                .addValue("contextEnvelopeSha256", command.contextEnvelopeSha256())
                .addValue("modelContextViewSha256", command.modelContextViewSha256())
                .addValue("canonicalResultJson", canonicalResultJson)
                .addValue("resultSha256", command.resultSha256())
                .addValue("publicProjectionSha256", command.publicProjectionSha256())
                .addValue("nextLocalIndex", command.nextLocalIndex())
                .addValue("providerCallCount", command.usage().providerCallCount())
                .addValue("inputTokens", command.usage().inputTokens())
                .addValue("outputTokens", command.usage().outputTokens())
                .addValue("totalTokens", command.usage().totalTokens())
                .addValue("latencyMs", command.usage().latencyMs())
                .addValue("completedAt", Timestamp.from(command.completedAt()));
        if (jdbc.update(
                        """
                        insert into intake_parallel_frame_result (
                            result_id, frame_set_id, frame_type, frame_generation,
                            frame_id, child_checkpoint_ref, child_checkpoint_sha256,
                            context_envelope_sha256, model_context_view_sha256,
                            canonical_result_json, result_sha256,
                            public_projection_sha256, next_local_index,
                            provider_call_count, input_tokens, output_tokens,
                            total_tokens, latency_ms, sealed_at
                        ) values (
                            :resultId, :frameSetId, :frameType, :frameGeneration,
                            :frameId, :childCheckpointRef, :childCheckpointSha256,
                            :contextEnvelopeSha256, :modelContextViewSha256,
                            cast(:canonicalResultJson as jsonb), :resultSha256,
                            :publicProjectionSha256, :nextLocalIndex,
                            :providerCallCount, :inputTokens, :outputTokens,
                            :totalTokens, :latencyMs, :completedAt
                        )
                        """,
                        resultParameters)
                != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_RESULT_INSERT_CONFLICT",
                    "Frame result was not inserted exactly once");
        }
        if (jdbc.update(
                        """
                        update intake_parallel_frame_generation
                           set provider_call_lease_state = 'TERMINAL',
                               staging_state = 'SEALED', result_id = :resultId,
                               provider_call_count = :providerCallCount,
                               terminal_at = :completedAt,
                               updated_at = greatest(updated_at, :completedAt)
                         where frame_set_id = :frameSetId and frame_type = :frameType
                           and frame_generation = :frameGeneration
                           and frame_id = :frameId and staging_state = 'STARTED'
                           and result_id is null
                        """,
                        resultParameters)
                != 1
                || jdbc.update(
                                """
                                update intake_parallel_frame_slot
                                   set slot_state = 'SEALED', current_result_id = :resultId,
                                       slot_version = slot_version + 1,
                                       updated_at = greatest(updated_at, :completedAt)
                                 where frame_set_id = :frameSetId and frame_type = :frameType
                                   and current_generation = :frameGeneration
                                   and current_frame_id = :frameId
                                   and slot_state = 'STARTED'
                                """,
                                resultParameters)
                        != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_SEAL_CAS_FAILED",
                    "Frame result did not seal the current generation and slot");
        }
        if (jdbc.update(
                        UPDATE_ATTEMPT_PROGRESS_SQL,
                        resultParameters
                                .addValue("previousSequence", previousSequence)
                                .addValue("visible", true)
                                .addValue("occurredAt", Timestamp.from(command.completedAt())))
                != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_STREAM_SEQUENCE_CAS_FAILED",
                    "attempt sequence authority changed during Frame seal");
        }
        boolean exactThreeSealed = exactThreeSealed(command.frameSetId());
        scheduleStreamCatchUp(
                command.runId(), command.attemptId(), eventReceipt.durableHighWatermark());
        return new FrameSealReceipt(
                command.frameSetId(),
                command.frameType(),
                command.generation(),
                resultId,
                frameReceiptId,
                true,
                exactThreeSealed,
                AssemblyState.COLLECTING,
                globalSequence,
                eventReceipt.durableHighWatermark());
    }

    private void scheduleStreamCatchUp(
            String runId, String attemptId, long durableHighWatermark) {
        streamEventService.wakeUpAfterCommit(runId, attemptId, durableHighWatermark);
    }

    private Map<String, Object> lockFrame(String frameSetId, FrameType frameType) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                LOCK_FRAME_SQL,
                Map.of("frameSetId", frameSetId, "frameType", frameType.name()));
        if (rows.size() != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_AUTHORITY_MISSING",
                    "current Frame generation is absent or ambiguous");
        }
        return rows.getFirst();
    }

    private void requireFrameSetAuthority(
            Map<String, Object> row,
            String runId,
            String attemptId,
            FrameType frameType) {
        requireCurrentEventAuthority(row);
        if (!runId.equals(text(row, "agent_run_id"))
                || !attemptId.equals(text(row, "agent_run_attempt_id"))
                || !frameType.promptProfileId().equals(text(row, "prompt_profile_id"))
                || !frameType.outputSchemaId().equals(text(row, "output_schema_id"))
                || !text(row, "model_profile_id")
                        .equals(text(row, "generation_model_profile_id"))) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_AUTHORITY_DRIFT",
                    "Frame request does not match current attempt, slot, prompt, or schema");
        }
    }

    private static void requireCurrentFrameAuthority(
            Map<String, Object> row,
            long generation,
            AgentStreamEventV4.Payload payload) {
        if (generation != number(row, "current_frame_generation")) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_GENERATION_SUPERSEDED",
                    "new Frame work belongs to a non-current generation");
        }
        if (payload == null) {
            return;
        }
        String payloadFrameId = payload.frameId() != null
                ? payload.frameId()
                : payload.newFrameId();
        if (!text(row, "current_frame_id").equals(payloadFrameId)) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_ID_DRIFT",
                    "public payload belongs to a non-current Frame id");
        }
    }

    private Optional<Map<String, Object>> findIngressReplay(MapSqlParameterSource parameters) {
        List<Map<String, Object>> rows = jdbc.queryForList(FIND_INGRESS_REPLAY_SQL, parameters);
        if (rows.size() > 1) {
            throw conflict(
                    "INTAKE_PARALLEL_INGRESS_IDENTITY_AMBIGUOUS",
                    "ingress identity and transport sequence resolve to different events");
        }
        return rows.stream().findFirst();
    }

    private static void requireExactIngressReplay(
            MapSqlParameterSource expected, Map<String, Object> stored) {
        Long expectedLocal = (Long) expected.getValue("localIndex");
        Long storedLocal = nullableNumber(stored, "local_index");
        boolean exact = Objects.equals(expected.getValue("frameSetId"), stored.get("frame_set_id"))
                && Objects.equals(expected.getValue("runId"), stored.get("agent_run_id"))
                && Objects.equals(expected.getValue("attemptId"), stored.get("agent_run_attempt_id"))
                && Objects.equals(expected.getValue("frameType"), stored.get("frame_type"))
                && ((Number) expected.getValue("frameGeneration")).longValue()
                        == number(stored, "frame_generation")
                && Objects.equals(
                        expected.getValue("ingressIdentity"), stored.get("ingress_identity"))
                && Objects.equals(
                        expected.getValue("streamSessionId"), stored.get("stream_session_id"))
                && ((Number) expected.getValue("transportSequence")).longValue()
                        == number(stored, "transport_sequence")
                && Objects.equals(expected.getValue("eventKind"), stored.get("event_kind"))
                && Objects.equals(expectedLocal, storedLocal)
                && Objects.equals(
                        expected.getValue("canonicalPayloadSha256"),
                        stored.get("canonical_payload_sha256"));
        if (!exact) {
            throw conflict(
                    "INTAKE_PARALLEL_INGRESS_REPLAY_CONFLICT",
                    "ingress identity or transport sequence is bound to another payload");
        }
    }

    private void requireSessionPosition(
            String frameSetId, String streamSessionId, long transportSequence) {
        Long maximum = jdbc.queryForObject(
                """
                select max(transport_sequence)
                  from intake_parallel_frame_ingress
                 where frame_set_id = :frameSetId
                   and stream_session_id = :streamSessionId
                """,
                Map.of("frameSetId", frameSetId, "streamSessionId", streamSessionId),
                Long.class);
        long expected = maximum == null ? 0L : Math.addExact(maximum, 1L);
        if (transportSequence != expected) {
            throw conflict(
                    "INTAKE_PARALLEL_TRANSPORT_SEQUENCE_INVALID",
                    "multiplex session transport sequence is not contiguous");
        }
    }

    private void requireIngressState(IngressCommand command, Map<String, Object> row) {
        AgentStreamEventV4.Payload payload = command.publicPayload();
        long nextLocalIndex = number(row, "next_local_index");
        String stagingState = text(row, "staging_state");
        String slotState = text(row, "slot_state");
        switch (command.ingressKind()) {
            case PUBLIC_FRAME_START -> {
                if (!"ADMITTED".equals(stagingState)
                        || !"ADMITTED".equals(slotState)
                        || !deterministicId("IPFSR", command.frameSetId())
                                .equals(payload.frameSetReceiptId())
                        || !text(row, "projection_registry_version")
                                .equals(payload.projectionRegistryVersion())) {
                    throw conflict(
                            "INTAKE_PARALLEL_FRAME_START_INVALID",
                            "Frame start does not match admission receipt or current slot");
                }
            }
            case PUBLIC_FRAME_PROJECTION_ITEM -> {
                requireStarted(stagingState, slotState);
                if (command.localIndex() == null
                        || command.localIndex() != nextLocalIndex
                        || payload.nextLocalIndex() == null
                        || payload.nextLocalIndex().longValue() != nextLocalIndex + 1L) {
                    throw conflict(
                            "INTAKE_PARALLEL_LOCAL_INDEX_INVALID",
                            "projection item must use the exclusive next local index");
                }
                String itemSha256 = projectionItemSha256(payload);
                if (!itemSha256.equals(payload.itemSha256())) {
                    throw conflict(
                            "INTAKE_PARALLEL_PROJECTION_ITEM_HASH_INVALID",
                            "projection item hash does not bind canonical item content");
                }
            }
            case ACTIVE_FRAME_SNAPSHOT -> {
                requireStarted(stagingState, slotState);
                if (payload.nextLocalIndex() == null
                        || payload.nextLocalIndex().longValue() != nextLocalIndex
                        || !projectionSha256(
                                        command.frameSetId(),
                                        command.frameType(),
                                        command.generation(),
                                        nextLocalIndex)
                                .equals(payload.projectionSha256())) {
                    throw conflict(
                            "INTAKE_PARALLEL_SNAPSHOT_PROJECTION_DRIFT",
                            "snapshot does not cover the current durable projection prefix");
                }
            }
            case FRAME_GENERATION_RESET -> {
                if (!"ADMITTED".equals(stagingState)
                        || !"ADMITTED".equals(slotState)
                        || command.generation() <= 1
                        || payload.oldGeneration() == null
                        || payload.oldGeneration().longValue() != command.generation() - 1L
                        || !previousFrameId(
                                        command.frameSetId(),
                                        command.frameType(),
                                        command.generation() - 1L)
                                .equals(payload.oldFrameId())) {
                    throw conflict(
                            "INTAKE_PARALLEL_GENERATION_RESET_INVALID",
                            "generation reset does not bind the immediately preceding Frame");
                }
            }
            case PUBLIC_FRAME_INTERRUPTED -> {
                requireStarted(stagingState, slotState);
                if (payload.nextLocalIndex() == null
                        || payload.nextLocalIndex().longValue() != nextLocalIndex) {
                    throw conflict(
                            "INTAKE_PARALLEL_INTERRUPTION_WATERMARK_DRIFT",
                            "interruption does not bind the current projection watermark");
                }
            }
            case USAGE -> requireStarted(stagingState, slotState);
            case PUBLIC_FRAME_SEALED -> throw conflict(
                    "INTAKE_PARALLEL_SEAL_BOUNDARY_BYPASSED",
                    "sealed event must use atomic seal");
        }
    }

    private static void requireStarted(String stagingState, String slotState) {
        if (!"STARTED".equals(stagingState) || !"STARTED".equals(slotState)) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_NOT_STARTED",
                    "Frame projection, usage, interruption, and snapshot require STARTED state");
        }
    }

    private void applyIngressMutation(
            IngressCommand command,
            Map<String, Object> authority,
            String ingressId,
            long globalSequence) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("frameSetId", command.frameSetId())
                .addValue("frameType", command.frameType().name())
                .addValue("frameGeneration", command.generation())
                .addValue("frameId", text(authority, "current_frame_id"))
                .addValue("occurredAt", Timestamp.from(command.occurredAt()))
                .addValue("ingressId", ingressId)
                .addValue("globalSequence", globalSequence);
        switch (command.ingressKind()) {
            case PUBLIC_FRAME_START -> startFrame(parameters);
            case PUBLIC_FRAME_PROJECTION_ITEM -> appendProjectionItem(command, authority, parameters);
            case ACTIVE_FRAME_SNAPSHOT -> updateSnapshot(command, parameters, globalSequence);
            case FRAME_GENERATION_RESET, USAGE -> {
                // Durable event only. The replacement generation was already admitted atomically.
            }
            case PUBLIC_FRAME_INTERRUPTED -> interruptFrame(command, parameters);
            case PUBLIC_FRAME_SEALED -> throw conflict(
                    "INTAKE_PARALLEL_SEAL_BOUNDARY_BYPASSED",
                    "sealed event must use atomic seal");
        }
    }

    private void startFrame(MapSqlParameterSource parameters) {
        int generation = jdbc.update(
                """
                update intake_parallel_frame_generation
                   set provider_call_lease_state = 'STARTED', staging_state = 'STARTED',
                       started_at = :occurredAt,
                       updated_at = greatest(updated_at, :occurredAt)
                 where frame_set_id = :frameSetId and frame_type = :frameType
                   and frame_generation = :frameGeneration and frame_id = :frameId
                   and provider_call_lease_state = 'ADMITTED'
                   and staging_state = 'ADMITTED'
                """,
                parameters);
        int slot = jdbc.update(
                """
                update intake_parallel_frame_slot
                   set slot_state = 'STARTED', slot_version = slot_version + 1,
                       updated_at = greatest(updated_at, :occurredAt)
                 where frame_set_id = :frameSetId and frame_type = :frameType
                   and current_generation = :frameGeneration
                   and current_frame_id = :frameId and slot_state = 'ADMITTED'
                """,
                parameters);
        if (generation != 1 || slot != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_START_CAS_FAILED",
                    "Frame start did not advance generation and slot exactly once");
        }
    }

    private void appendProjectionItem(
            IngressCommand command,
            Map<String, Object> authority,
            MapSqlParameterSource parameters) {
        AgentStreamEventV4.Payload payload = command.publicPayload();
        String canonicalValue = payload.valueKind() == AgentStreamEventV4.ValueKind.JSON_VALUE
                ? ContractJson.canonicalString(readJson(payload.canonicalValueJson(), "projection value"))
                : null;
        String authorityBindingSha256 = projectionAuthorityBindingSha256(
                command, authority, payload);
        MapSqlParameterSource item = copy(parameters)
                .addValue("localIndex", command.localIndex())
                .addValue("canonicalItemId", payload.canonicalItemId())
                .addValue("projectionKind", payload.projectionKind())
                .addValue("projectionPathId", payload.projectionPathId())
                .addValue("valueKind", payload.valueKind().name())
                .addValue("canonicalValueJson", canonicalValue)
                .addValue("publicText", payload.publicText())
                .addValue("itemSha256", payload.itemSha256())
                .addValue("authorityBindingSha256", authorityBindingSha256)
                .addValue("nextLocalIndex", payload.nextLocalIndex());
        if (jdbc.update(
                        """
                        insert into intake_parallel_frame_projection_item (
                            frame_set_id, frame_type, frame_generation, local_index,
                            canonical_item_id, projection_kind, projection_path_id,
                            value_kind, canonical_value_json, public_text,
                            item_sha256, authority_binding_sha256, ingress_id
                        ) values (
                            :frameSetId, :frameType, :frameGeneration, :localIndex,
                            :canonicalItemId, :projectionKind, :projectionPathId,
                            :valueKind, cast(:canonicalValueJson as jsonb), :publicText,
                            :itemSha256, :authorityBindingSha256, :ingressId
                        )
                        """,
                        item)
                != 1
                || jdbc.update(
                                """
                                update intake_parallel_frame_generation
                                   set next_local_index = :nextLocalIndex,
                                       latest_projection_item_sha256 = :itemSha256,
                                       preview_state = 'OBSERVED',
                                       first_preview_next_local_index = coalesce(
                                           first_preview_next_local_index, :nextLocalIndex),
                                       updated_at = greatest(updated_at, :occurredAt)
                                 where frame_set_id = :frameSetId and frame_type = :frameType
                                   and frame_generation = :frameGeneration
                                   and frame_id = :frameId and staging_state = 'STARTED'
                                   and next_local_index = :localIndex
                                """,
                                item)
                        != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_PROJECTION_ITEM_CAS_FAILED",
                    "projection item did not advance the exclusive local index");
        }
    }

    private void updateSnapshot(
            IngressCommand command,
            MapSqlParameterSource parameters,
            long globalSequence) {
        AgentStreamEventV4.Payload payload = command.publicPayload();
        MapSqlParameterSource snapshot = copy(parameters)
                .addValue("nextLocalIndex", payload.nextLocalIndex())
                .addValue("projectionSha256", payload.projectionSha256())
                .addValue(
                        "snapshotCursor",
                        "agent-stream.v4:"
                                + command.runId()
                                + ":"
                                + command.attemptId()
                                + ":"
                                + globalSequence);
        if (jdbc.update(
                        """
                        update intake_parallel_frame_generation
                           set latest_snapshot_next_local_index = :nextLocalIndex,
                               latest_snapshot_sha256 = :projectionSha256,
                               latest_snapshot_cursor = :snapshotCursor,
                               updated_at = greatest(updated_at, :occurredAt)
                         where frame_set_id = :frameSetId and frame_type = :frameType
                           and frame_generation = :frameGeneration
                           and frame_id = :frameId and staging_state = 'STARTED'
                           and next_local_index = :nextLocalIndex
                        """,
                        snapshot)
                != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_SNAPSHOT_CAS_FAILED",
                    "snapshot did not bind current Frame progress");
        }
    }

    private void interruptFrame(IngressCommand command, MapSqlParameterSource parameters) {
        AgentStreamEventV4.Payload payload = command.publicPayload();
        boolean ambiguous = "CALL_STATE_AMBIGUOUS".equals(payload.reasonCode());
        String terminalState = ambiguous ? "AMBIGUOUS" : "FAILED";
        String leaseState = ambiguous ? "AMBIGUOUS" : "TERMINAL";
        MapSqlParameterSource interrupted = copy(parameters)
                .addValue("terminalState", terminalState)
                .addValue("leaseState", leaseState)
                .addValue("failureCode", payload.reasonCode())
                .addValue("retryable", payload.retryable());
        int generation = jdbc.update(
                """
                update intake_parallel_frame_generation
                   set provider_call_lease_state = :leaseState,
                       staging_state = :terminalState,
                       failure_code = :failureCode, failure_retryable = :retryable,
                       terminal_at = :occurredAt,
                       updated_at = greatest(updated_at, :occurredAt)
                 where frame_set_id = :frameSetId and frame_type = :frameType
                   and frame_generation = :frameGeneration and frame_id = :frameId
                   and staging_state = 'STARTED'
                """,
                interrupted);
        int slot = jdbc.update(
                """
                update intake_parallel_frame_slot
                   set slot_state = :terminalState, slot_version = slot_version + 1,
                       updated_at = greatest(updated_at, :occurredAt)
                 where frame_set_id = :frameSetId and frame_type = :frameType
                   and current_generation = :frameGeneration
                   and current_frame_id = :frameId and slot_state = 'STARTED'
                """,
                interrupted);
        if (generation != 1 || slot != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_INTERRUPTION_CAS_FAILED",
                    "interruption did not terminate generation and slot exactly once");
        }
    }

    private Optional<FrameSealReceipt> exactSealReplay(
            FrameSealCommand command, Map<String, Object> authority) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("frameSetId", command.frameSetId())
                .addValue("runId", command.runId())
                .addValue("attemptId", command.attemptId())
                .addValue("frameType", command.frameType().name())
                .addValue("frameGeneration", command.generation())
                .addValue("ingressIdentity", command.ingressIdentity())
                .addValue("streamSessionId", command.streamSessionId())
                .addValue("transportSequence", command.transportSequence());
        List<Map<String, Object>> ingressRows = jdbc.queryForList(
                """
                select ingress.ingress_id, ingress.frame_set_id, ingress.agent_run_id,
                       ingress.agent_run_attempt_id, ingress.frame_type,
                       ingress.frame_generation, ingress.ingress_identity,
                       ingress.stream_session_id, ingress.transport_sequence,
                       ingress.event_kind, ingress.local_index,
                       ingress.canonical_payload_json::text,
                       ingress.canonical_payload_sha256, ingress.global_sequence,
                       ingress.public_event_id, ingress.receipt_id,
                       public_event.event_type, public_event.audience
                  from intake_parallel_frame_ingress ingress
                  join agent_run_stream_event public_event
                    on public_event.id = ingress.public_event_id
                 where ingress.agent_run_id = :runId
                   and ingress.agent_run_attempt_id = :attemptId
                   and (
                        ingress.ingress_identity = :ingressIdentity
                        or (
                            ingress.frame_set_id = :frameSetId
                            and ingress.stream_session_id = :streamSessionId
                            and ingress.transport_sequence = :transportSequence
                        )
                   )
                 order by ingress.ingress_id
                """,
                parameters);
        if (ingressRows.size() > 1) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_SEAL_REPLAY_AMBIGUOUS",
                    "Frame seal identity resolves to multiple ingress rows");
        }
        boolean sealed = "SEALED".equals(text(authority, "slot_state"));
        if (ingressRows.isEmpty()) {
            if (sealed || nullableText(authority, "current_result_id") != null) {
                throw conflict(
                        "INTAKE_PARALLEL_FRAME_SEAL_REPLAY_CORRUPT",
                        "sealed Frame has no immutable seal ingress");
            }
            return Optional.empty();
        }
        Map<String, Object> ingress = ingressRows.getFirst();
        if (!sealed || nullableText(authority, "current_result_id") == null) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_SEAL_REPLAY_CONFLICT",
                    "seal identity is already bound without a sealed current result");
        }
        boolean exactIngress = command.frameSetId().equals(text(ingress, "frame_set_id"))
                && command.runId().equals(text(ingress, "agent_run_id"))
                && command.attemptId().equals(text(ingress, "agent_run_attempt_id"))
                && command.frameType().name().equals(text(ingress, "frame_type"))
                && command.generation() == number(ingress, "frame_generation")
                && command.ingressIdentity().equals(text(ingress, "ingress_identity"))
                && command.streamSessionId().equals(text(ingress, "stream_session_id"))
                && command.transportSequence() == number(ingress, "transport_sequence")
                && AgentStreamEventV4.EventType.PUBLIC_FRAME_SEALED.wireValue()
                        .equals(text(ingress, "event_kind"))
                && AgentStreamEventV4.EventType.PUBLIC_FRAME_SEALED.wireValue()
                        .equals(text(ingress, "event_type"))
                && command.audience().name().equals(text(ingress, "audience"))
                && nullableNumber(ingress, "local_index") == null;
        if (!exactIngress) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_SEAL_REPLAY_CONFLICT",
                    "Frame seal identity is bound to another ingress");
        }

        List<Map<String, Object>> results = jdbc.queryForList(
                """
                select result_id, frame_set_id, frame_type, frame_generation,
                       frame_id, child_checkpoint_ref, child_checkpoint_sha256,
                       context_envelope_sha256, model_context_view_sha256,
                       canonical_result_json::text, result_sha256,
                       public_projection_sha256, next_local_index,
                       provider_call_count, input_tokens, output_tokens,
                       total_tokens, latency_ms
                  from intake_parallel_frame_result
                 where result_id = :resultId
                   and frame_set_id = :frameSetId
                   and frame_type = :frameType
                   and frame_generation = :frameGeneration
                """,
                parameters.addValue("resultId", text(authority, "current_result_id")));
        if (results.size() != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_SEAL_REPLAY_CORRUPT",
                    "sealed Frame result is absent or ambiguous");
        }
        Map<String, Object> result = results.getFirst();
        String storedCanonicalResult = ContractJson.canonicalString(
                readJson(text(result, "canonical_result_json"), "stored Frame result"));
        boolean exactResult = command.frameId().equals(text(result, "frame_id"))
                && command.childCheckpointRef().equals(text(result, "child_checkpoint_ref"))
                && command.childCheckpointSha256()
                        .equals(text(result, "child_checkpoint_sha256"))
                && command.contextEnvelopeSha256()
                        .equals(text(result, "context_envelope_sha256"))
                && command.modelContextViewSha256()
                        .equals(text(result, "model_context_view_sha256"))
                && command.canonicalResultJson().equals(storedCanonicalResult)
                && command.resultSha256().equals(text(result, "result_sha256"))
                && command.publicProjectionSha256()
                        .equals(text(result, "public_projection_sha256"))
                && command.nextLocalIndex() == number(result, "next_local_index")
                && command.usage().providerCallCount()
                        == number(result, "provider_call_count")
                && command.usage().inputTokens() == number(result, "input_tokens")
                && command.usage().outputTokens() == number(result, "output_tokens")
                && command.usage().totalTokens() == number(result, "total_tokens")
                && command.usage().latencyMs() == number(result, "latency_ms");
        if (!exactResult) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_SEAL_REPLAY_CONFLICT",
                    "Frame seal replay differs from the immutable result");
        }
        JsonNode storedPayload = readJson(
                text(ingress, "canonical_payload_json"), "stored Frame seal payload");
        String storedPayloadSha256 = ContractJson.sha256Hex(storedPayload);
        if (!storedPayloadSha256.equals(text(ingress, "canonical_payload_sha256"))) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_SEAL_REPLAY_CORRUPT",
                    "stored Frame seal payload hash drifted");
        }
        String frameReceiptId = text(ingress, "receipt_id");
        JsonNode expectedPayload = objectMapper.valueToTree(sealedPayload(command, frameReceiptId));
        if (!ContractJson.canonicalString(expectedPayload)
                .equals(ContractJson.canonicalString(storedPayload))) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_SEAL_REPLAY_CONFLICT",
                    "Frame seal replay public payload drifted");
        }
        return Optional.of(new FrameSealReceipt(
                command.frameSetId(),
                command.frameType(),
                command.generation(),
                text(result, "result_id"),
                frameReceiptId,
                false,
                exactThreeSealed(command.frameSetId()),
                AssemblyState.COLLECTING,
                number(ingress, "global_sequence"),
                durableHighWatermark(command.runId(), command.attemptId())));
    }

    private String projectionSha256(
            String frameSetId, FrameType frameType, long generation, long nextLocalIndex) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select local_index, canonical_item_id, projection_kind,
                       projection_path_id, value_kind,
                       canonical_value_json::text, public_text, item_sha256
                  from intake_parallel_frame_projection_item
                 where frame_set_id = :frameSetId and frame_type = :frameType
                   and frame_generation = :frameGeneration
                   and local_index < :nextLocalIndex
                 order by local_index
                """,
                new MapSqlParameterSource()
                        .addValue("frameSetId", frameSetId)
                        .addValue("frameType", frameType.name())
                        .addValue("frameGeneration", generation)
                        .addValue("nextLocalIndex", nextLocalIndex));
        if (rows.size() != nextLocalIndex) {
            throw conflict(
                    "INTAKE_PARALLEL_PROJECTION_PREFIX_INCOMPLETE",
                    "durable projection prefix is missing an item");
        }
        ArrayNode prefix = objectMapper.createArrayNode();
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            if (number(row, "local_index") != index) {
                throw conflict(
                        "INTAKE_PARALLEL_PROJECTION_PREFIX_INVALID",
                        "durable projection indices are not contiguous");
            }
            ObjectNode item = projectionItemNode(
                    text(row, "canonical_item_id"),
                    text(row, "projection_kind"),
                    text(row, "projection_path_id"),
                    text(row, "value_kind"),
                    nullableText(row, "canonical_value_json"),
                    nullableText(row, "public_text"));
            String itemSha256 = ContractJson.sha256Hex(item);
            if (!itemSha256.equals(text(row, "item_sha256"))) {
                throw conflict(
                        "INTAKE_PARALLEL_PROJECTION_ITEM_CORRUPT",
                        "durable projection item hash drifted");
            }
            prefix.add(item);
        }
        return ContractJson.sha256Hex(prefix);
    }

    private AgentStreamEventV4.Payload sealedPayload(
            FrameSealCommand command, String frameReceiptId) {
        return new AgentStreamEventV4.Payload(
                command.frameId(),
                AgentStreamEventV4.FrameType.valueOf(command.frameType().name()),
                Math.toIntExact(command.generation()),
                null,
                null,
                AgentStreamEventV4.DeliveryClass.DURABLE_STAGING,
                null,
                Math.toIntExact(command.nextLocalIndex()),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                frameReceiptId,
                command.resultSha256(),
                command.publicProjectionSha256(),
                null,
                null,
                null,
                null,
                null);
    }

    private boolean exactThreeSealed(String frameSetId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select count(*) as slot_count,
                       count(*) filter (
                           where slot_state = 'SEALED' and current_result_id is not null
                       ) as sealed_count
                  from intake_parallel_frame_slot
                 where frame_set_id = :frameSetId
                """,
                Map.of("frameSetId", frameSetId));
        if (rows.size() != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_FRAME_SLOT_AMBIGUOUS",
                    "Frame slot aggregate is ambiguous");
        }
        return number(rows.getFirst(), "slot_count") == FrameType.values().length
                && number(rows.getFirst(), "sealed_count") == FrameType.values().length;
    }

    private static MapSqlParameterSource ingressIdentityParameters(
            String frameSetId,
            String runId,
            String attemptId,
            String streamSessionId,
            long transportSequence,
            String ingressIdentity,
            FrameType frameType,
            long frameGeneration,
            String eventKind,
            Long localIndex,
            String canonicalPayloadSha256) {
        return new MapSqlParameterSource()
                .addValue("frameSetId", frameSetId)
                .addValue("runId", runId)
                .addValue("attemptId", attemptId)
                .addValue("streamSessionId", streamSessionId)
                .addValue("transportSequence", transportSequence)
                .addValue("ingressIdentity", ingressIdentity)
                .addValue("frameType", frameType.name())
                .addValue("frameGeneration", frameGeneration)
                .addValue("eventKind", eventKind)
                .addValue("localIndex", localIndex)
                .addValue("canonicalPayloadSha256", canonicalPayloadSha256);
    }

    private static MapSqlParameterSource copy(MapSqlParameterSource source) {
        return new MapSqlParameterSource(source.getValues());
    }

    private long durableHighWatermark(String runId, String attemptId) {
        List<Long> rows = jdbc.query(
                """
                select highest_contiguous_sequence_no
                  from agent_run_stream_delivery_high_watermark
                 where stream_protocol = 'agent-stream.v4'
                   and agent_run_id = :runId
                   and agent_run_attempt_id = :attemptId
                """,
                Map.of("runId", runId, "attemptId", attemptId),
                (resultSet, rowNumber) -> resultSet.getLong("highest_contiguous_sequence_no"));
        if (rows.size() != 1 || rows.getFirst() < 0) {
            throw conflict(
                    "INTAKE_PARALLEL_STREAM_WATERMARK_MISSING",
                    "durable stream replay watermark is absent or ambiguous");
        }
        return rows.getFirst();
    }

    private String previousFrameId(String frameSetId, FrameType frameType, long generation) {
        List<String> rows = jdbc.query(
                """
                select frame_id
                  from intake_parallel_frame_generation
                 where frame_set_id = :frameSetId and frame_type = :frameType
                   and frame_generation = :frameGeneration
                """,
                new MapSqlParameterSource()
                        .addValue("frameSetId", frameSetId)
                        .addValue("frameType", frameType.name())
                        .addValue("frameGeneration", generation),
                (resultSet, rowNumber) -> resultSet.getString("frame_id"));
        if (rows.size() != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_PREVIOUS_FRAME_MISSING",
                    "generation reset predecessor is absent or ambiguous");
        }
        return rows.getFirst();
    }

    private String projectionItemSha256(AgentStreamEventV4.Payload payload) {
        return ContractJson.sha256Hex(projectionItemNode(
                payload.canonicalItemId(),
                payload.projectionKind(),
                payload.projectionPathId(),
                payload.valueKind().name(),
                payload.canonicalValueJson(),
                payload.publicText()));
    }

    private ObjectNode projectionItemNode(
            String canonicalItemId,
            String projectionKind,
            String projectionPathId,
            String valueKind,
            String canonicalValueJson,
            String publicText) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("canonical_item_id", canonicalItemId);
        item.put("projection_kind", projectionKind);
        item.put("projection_path_id", projectionPathId);
        item.put("value_kind", valueKind);
        if (AgentStreamEventV4.ValueKind.JSON_VALUE.name().equals(valueKind)) {
            item.set(
                    "canonical_value",
                    readJson(canonicalValueJson, "projection canonical value"));
        } else if (AgentStreamEventV4.ValueKind.TEXT.name().equals(valueKind)) {
            item.put("public_text", Objects.requireNonNull(publicText, "publicText"));
        } else {
            throw conflict(
                    "INTAKE_PARALLEL_PROJECTION_VALUE_KIND_INVALID",
                    "projection item value kind is unsupported");
        }
        return item;
    }

    private String projectionAuthorityBindingSha256(
            IngressCommand command,
            Map<String, Object> authority,
            AgentStreamEventV4.Payload payload) {
        ObjectNode binding = objectMapper.createObjectNode();
        binding.put("frame_set_id", command.frameSetId());
        binding.put("run_id", command.runId());
        binding.put("attempt_id", command.attemptId());
        binding.put("event_binding_id", text(authority, "event_binding_id"));
        binding.put("binding_generation", number(authority, "binding_generation"));
        binding.put("authority_version", number(authority, "authority_version"));
        binding.put("context_envelope_sha256", text(authority, "context_envelope_sha256"));
        binding.put("model_context_view_sha256", text(authority, "model_context_view_sha256"));
        binding.put("projection_registry_version", text(authority, "projection_registry_version"));
        binding.put("frame_type", command.frameType().name());
        binding.put("frame_generation", command.generation());
        binding.put("frame_id", text(authority, "current_frame_id"));
        binding.put("local_index", command.localIndex());
        binding.put("canonical_item_id", payload.canonicalItemId());
        binding.put("item_sha256", payload.itemSha256());
        return ContractJson.sha256Hex(binding);
    }

    private JsonNode readJson(String value, String description) {
        if (value == null || value.isBlank()) {
            throw conflict(
                    "INTAKE_PARALLEL_JSON_INVALID", description + " is absent");
        }
        try {
            JsonNode parsed = objectMapper.readTree(value);
            if (parsed == null) {
                throw conflict(
                        "INTAKE_PARALLEL_JSON_INVALID", description + " is empty");
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw conflict(
                    "INTAKE_PARALLEL_JSON_INVALID", description + " is not valid JSON");
        }
    }

    private static Long nullableNumber(Map<String, Object> row, String name) {
        Object value = row.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw conflict(
                    "INTAKE_PARALLEL_CORRUPT_AUTHORITY", name + " is not nullable numeric");
        }
        return number.longValue();
    }

    private void requireAdmissionAuthority(
            FrameSetAdmission admission, Map<String, Object> row) {
        if (!"RUNNING".equals(text(row, "attempt_status"))
                || !"RUNNING".equals(text(row, "run_status"))
                || !"UNCOMMITTED".equals(text(row, "finalization_status"))
                || !"agent-stream.v4".equals(text(row, "protocol"))) {
            throw conflict(
                    "INTAKE_PARALLEL_ATTEMPT_NOT_RUNNING",
                    "parallel Frame admission requires a running V4 attempt");
        }
        EventAuthority authority = admission.eventAuthority();
        boolean exact = admission.commandId().equals(text(row, "command_id"))
                && authority.commandRequestSha256().equals(text(row, "command_request_hash"))
                && admission.modelProfileId().equals(text(row, "attempt_model_profile_id"))
                && admission.tenantSurrogate().equals(text(row, "run_tenant_surrogate"))
                && admission.tenantSurrogate().equals(text(row, "tenant_surrogate"))
                && admission.caseId().equals(text(row, "run_case_id"))
                && admission.caseId().equals(text(row, "case_id"))
                && admission.roomId().equals(text(row, "room_epoch_id"))
                && admission.roomEpoch() == number(row, "room_epoch")
                && admission.roomEpoch() == number(row, "binding_room_epoch")
                && admission.fencingToken() == number(row, "fencing_token")
                && admission.fencingToken() == number(row, "binding_fencing_token")
                && admission.threadId().equals(text(row, "thread_id"))
                && admission.actorScopeSha256().equals(text(row, "actor_scope_hash"))
                && admission.agentSessionId().equals(text(row, "agent_session_id"))
                && "EVENT".equals(text(row, "binding_type"))
                && authority.eventBindingId().equals(text(row, "binding_id"))
                && authority.eventBindingId().equals(text(row, "current_binding_id"))
                && authority.threadRegistrationId().equals(text(row, "thread_registration_id"))
                && authority.logicalSequence() == number(row, "event_sequence")
                && authority.bindingGeneration() == number(row, "binding_generation")
                && authority.bindingGeneration() == number(row, "current_binding_generation")
                && authority.authorityVersion() == number(row, "authority_version");
        if (!exact) {
            throw conflict(
                    "INTAKE_PARALLEL_ADMISSION_AUTHORITY_DRIFT",
                    "admission does not match attempt, event, scope, or V080 current authority");
        }
    }

    private void requireExactAdmissionReplay(FrameSetAdmission admission) {
        List<Map<String, Object>> sets = jdbc.queryForList(
                LOAD_FRAME_SET_SQL, Map.of("frameSetId", admission.frameSetId()));
        if (sets.size() != 1 || !sameAdmission(admission, sets.getFirst())) {
            throw conflict(
                    "INTAKE_PARALLEL_ADMISSION_REPLAY_CONFLICT",
                    "frameSetId is bound to another admission");
        }
        List<Map<String, Object>> stored = jdbc.queryForList(
                LOAD_MANIFESTS_SQL, Map.of("frameSetId", admission.frameSetId()));
        if (stored.size() != FrameType.values().length) {
            throw conflict(
                    "INTAKE_PARALLEL_ADMISSION_REPLAY_INCOMPLETE",
                    "stored exact-three manifests are incomplete");
        }
        Map<FrameType, FrameManifest> expected = admission.manifestsByType();
        for (Map<String, Object> row : stored) {
            FrameType type = FrameType.valueOf(text(row, "frame_type"));
            FrameManifest manifest = expected.get(type);
            if (manifest == null
                    || !sameManifest(manifest, row)
                    || nullableText(row, "repair_code") != null
                    || nullableText(row, "validation_path") != null) {
                throw conflict(
                        "INTAKE_PARALLEL_ADMISSION_REPLAY_CONFLICT",
                        "stored Frame manifest differs from replay");
            }
        }
    }

    private boolean sameAdmission(FrameSetAdmission admission, Map<String, Object> row) {
        EventAuthority authority = admission.eventAuthority();
        return admission.runId().equals(text(row, "agent_run_id"))
                && admission.attemptId().equals(text(row, "agent_run_attempt_id"))
                && admission.commandId().equals(text(row, "command_id"))
                && authority.commandRequestSha256().equals(text(row, "command_request_sha256"))
                && admission.tenantSurrogate().equals(text(row, "tenant_surrogate"))
                && admission.caseId().equals(text(row, "case_id"))
                && admission.roomId().equals(text(row, "room_id"))
                && admission.roomEpoch() == number(row, "room_epoch")
                && admission.fencingToken() == number(row, "fencing_token")
                && admission.threadId().equals(text(row, "thread_id"))
                && admission.actorScopeSha256().equals(text(row, "actor_scope_hash"))
                && admission.agentSessionId().equals(text(row, "agent_session_id"))
                && authority.eventBindingId().equals(text(row, "event_binding_id"))
                && authority.threadRegistrationId().equals(text(row, "thread_registration_id"))
                && authority.logicalSequence() == number(row, "logical_sequence")
                && authority.bindingGeneration() == number(row, "binding_generation")
                && authority.authorityVersion() == number(row, "authority_version")
                && admission.contextEnvelopeSha256().equals(text(row, "context_envelope_sha256"))
                && admission.modelContextViewSha256().equals(text(row, "model_context_view_sha256"))
                && admission.executionProfileId().equals(text(row, "execution_profile_id"))
                && admission.projectionRegistryVersion().equals(text(row, "projection_registry_version"))
                && admission.modelProfileId().equals(text(row, "model_profile_id"))
                && admission.turnDeadlineAt().equals(instant(row, "turn_deadline_at"));
    }

    private void requireStoredManifest(
            String frameSetId,
            FrameManifest manifest,
            String repairCode,
            String validationPath) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select frame_type, frame_generation, frame_id, prompt_profile_id,
                       output_schema_id, model_profile_id, frame_model_input_sha256,
                       frame_prompt_sha256, repair_code, validation_path
                  from intake_parallel_frame_generation
                 where frame_set_id = :frameSetId and frame_type = :frameType
                   and frame_generation = :frameGeneration
                """,
                manifestParameters(frameSetId, manifest, repairCode, validationPath));
        if (rows.size() != 1
                || !sameManifest(manifest, rows.getFirst())
                || !Objects.equals(repairCode, nullableText(rows.getFirst(), "repair_code"))
                || !Objects.equals(validationPath, nullableText(rows.getFirst(), "validation_path"))) {
            throw conflict(
                    "INTAKE_PARALLEL_RETRY_REPLAY_CONFLICT",
                    "replacement generation replay differs from stored authority");
        }
    }

    private void requireRetryPredecessor(FrameRetryAdmission admission) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select staging_state, failure_code, failure_retryable
                  from intake_parallel_frame_generation
                 where frame_set_id = :frameSetId and frame_type = :frameType
                   and frame_generation = :frameGeneration
                """,
                new MapSqlParameterSource()
                        .addValue("frameSetId", admission.frameSetId())
                        .addValue("frameType", admission.replacement().frameType().name())
                        .addValue("frameGeneration", admission.expectedCurrentGeneration()));
        if (rows.size() != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_RETRY_PREDECESSOR_MISSING",
                    "retry predecessor is absent or ambiguous");
        }
        Map<String, Object> predecessor = rows.getFirst();
        if (!admission.expectedCurrentState().name().equals(text(predecessor, "staging_state"))
                || !admission.repairCode().equals(text(predecessor, "failure_code"))
                || !Boolean.TRUE.equals(predecessor.get("failure_retryable"))) {
            throw conflict(
                    "INTAKE_PARALLEL_RETRY_REPLAY_CONFLICT",
                    "retry replay does not match its terminal predecessor");
        }
    }

    private static void requireRetryablePlanningPredecessor(
            Map<String, Object> row, SlotState state) {
        String expectedLease = state == SlotState.AMBIGUOUS ? "AMBIGUOUS" : "TERMINAL";
        if (!state.name().equals(text(row, "staging_state"))
                || !expectedLease.equals(text(row, "provider_call_lease_state"))
                || nullableText(row, "failure_code") == null
                || !Boolean.TRUE.equals(row.get("failure_retryable"))
                || nullableText(row, "current_result_id") != null
                || nullableText(row, "result_id") != null) {
            throw conflict(
                    "INTAKE_PARALLEL_EXECUTION_RETRY_NOT_AUTHORIZED",
                    "terminal Frame generation does not grant a retry replacement");
        }
    }

    private static void requireSealedPlanningResult(Map<String, Object> row) {
        String currentResultId = nullableText(row, "current_result_id");
        if (!"SEALED".equals(text(row, "slot_state"))
                || !"SEALED".equals(text(row, "staging_state"))
                || currentResultId == null
                || !currentResultId.equals(nullableText(row, "result_id"))
                || nullableText(row, "result_sha256") == null
                || nullableText(row, "public_projection_sha256") == null
                || nullableNumber(row, "result_next_local_index") == null
                || number(row, "next_local_index")
                        != number(row, "result_next_local_index")) {
            throw conflict(
                    "INTAKE_PARALLEL_EXECUTION_SEALED_RESULT_DRIFT",
                    "sealed Frame slot is not bound to one immutable current result");
        }
    }

    private String replacementFrameId(
            String frameSetId,
            FrameType frameType,
            String oldFrameId,
            long generation,
            String frameModelInputSha256) {
        ObjectNode identity = objectMapper.createObjectNode();
        identity.put("frame_set_id", frameSetId);
        identity.put("frame_type", frameType.name());
        identity.put("old_frame_id", oldFrameId);
        identity.put("generation", generation);
        identity.put("frame_model_input_sha256", frameModelInputSha256);
        return "intake.frame." + ContractJson.sha256Hex(identity).substring(0, 32);
    }

    private static boolean sameManifest(FrameManifest manifest, Map<String, Object> row) {
        return manifest.frameType().name().equals(text(row, "frame_type"))
                && manifest.generation() == number(row, "frame_generation")
                && manifest.frameId().equals(text(row, "frame_id"))
                && manifest.promptProfileId().equals(text(row, "prompt_profile_id"))
                && manifest.outputSchemaId().equals(text(row, "output_schema_id"))
                && manifest.modelProfileId().equals(text(row, "model_profile_id"))
                && manifest.frameModelInputSha256().equals(text(row, "frame_model_input_sha256"))
                && manifest.framePromptSha256().equals(text(row, "frame_prompt_sha256"));
    }

    private static MapSqlParameterSource admissionParameters(FrameSetAdmission admission) {
        EventAuthority authority = admission.eventAuthority();
        return new MapSqlParameterSource()
                .addValue("frameSetId", admission.frameSetId())
                .addValue("runId", admission.runId())
                .addValue("attemptId", admission.attemptId())
                .addValue("commandId", admission.commandId())
                .addValue("commandRequestSha256", authority.commandRequestSha256())
                .addValue("tenantSurrogate", admission.tenantSurrogate())
                .addValue("caseId", admission.caseId())
                .addValue("roomId", admission.roomId())
                .addValue("roomEpoch", admission.roomEpoch())
                .addValue("fencingToken", admission.fencingToken())
                .addValue("threadId", admission.threadId())
                .addValue("actorScopeSha256", admission.actorScopeSha256())
                .addValue("agentSessionId", admission.agentSessionId())
                .addValue("eventBindingId", authority.eventBindingId())
                .addValue("threadRegistrationId", authority.threadRegistrationId())
                .addValue("logicalSequence", authority.logicalSequence())
                .addValue("bindingGeneration", authority.bindingGeneration())
                .addValue("authorityVersion", authority.authorityVersion())
                .addValue("contextEnvelopeSha256", admission.contextEnvelopeSha256())
                .addValue("modelContextViewSha256", admission.modelContextViewSha256())
                .addValue("executionProfileId", admission.executionProfileId())
                .addValue("projectionRegistryVersion", admission.projectionRegistryVersion())
                .addValue("modelProfileId", admission.modelProfileId())
                .addValue("turnDeadlineAt", Timestamp.from(admission.turnDeadlineAt()));
    }

    private static MapSqlParameterSource manifestParameters(
            String frameSetId,
            FrameManifest manifest,
            String repairCode,
            String validationPath) {
        return new MapSqlParameterSource()
                .addValue("frameSetId", frameSetId)
                .addValue("frameType", manifest.frameType().name())
                .addValue("frameGeneration", manifest.generation())
                .addValue("frameId", manifest.frameId())
                .addValue("promptProfileId", manifest.promptProfileId())
                .addValue("outputSchemaId", manifest.outputSchemaId())
                .addValue("modelProfileId", manifest.modelProfileId())
                .addValue("frameModelInputSha256", manifest.frameModelInputSha256())
                .addValue("framePromptSha256", manifest.framePromptSha256())
                .addValue("repairCode", repairCode)
                .addValue("validationPath", validationPath);
    }

    private static void requireCurrentEventAuthority(Map<String, Object> row) {
        if (!text(row, "event_binding_id").equals(text(row, "current_binding_id"))
                || number(row, "binding_generation")
                        != number(row, "current_binding_generation")
                || number(row, "authority_version") != number(row, "current_authority_version")) {
            throw conflict(
                    "INTAKE_PARALLEL_EVENT_AUTHORITY_SUPERSEDED",
                    "Frame set is bound to a superseded V080 event authority");
        }
    }

    private static void requireRunningCollecting(Map<String, Object> row) {
        if (!"COLLECTING".equals(text(row, "assembly_state"))
                || !"RUNNING".equals(text(row, "attempt_status"))
                || !"RUNNING".equals(text(row, "run_status"))
                || !"UNCOMMITTED".equals(text(row, "finalization_status"))
                || !"agent-stream.v4".equals(text(row, "protocol"))
                || !Boolean.TRUE.equals(row.get("deadline_open"))) {
            throw conflict(
                    "INTAKE_PARALLEL_ATTEMPT_NOT_RUNNING",
                    "new Frame work requires a collecting V4 attempt");
        }
    }

    private static StagingConflictException conflict(String code, String message) {
        return new StagingConflictException(code, message);
    }

    private static String deterministicId(String prefix, String value) {
        return prefix + "_" + sha256Text(value).substring(0, 32);
    }

    private static String randomId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String sha256Text(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static long number(Map<String, Object> row, String name) {
        Object value = row.get(name);
        if (!(value instanceof Number number)) {
            throw conflict("INTAKE_PARALLEL_CORRUPT_AUTHORITY", name + " is not numeric");
        }
        return number.longValue();
    }

    private static String text(Map<String, Object> row, String name) {
        Object value = row.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw conflict("INTAKE_PARALLEL_CORRUPT_AUTHORITY", name + " is not text");
        }
        return text;
    }

    private static String nullableText(Map<String, Object> row, String name) {
        Object value = row.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw conflict("INTAKE_PARALLEL_CORRUPT_AUTHORITY", name + " is not nullable text");
        }
        return text;
    }

    private static Instant instant(Map<String, Object> row, String name) {
        Object value = row.get(name);
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        throw conflict("INTAKE_PARALLEL_CORRUPT_AUTHORITY", name + " is not an instant");
    }
}
