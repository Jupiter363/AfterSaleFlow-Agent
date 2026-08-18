package com.example.dispute.workflow.infrastructure.persistence.authority.bridge;

import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort.CommandSource;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort.DomainEventSource;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort.StartSource;
import com.example.dispute.workflow.application.authority.epoch.EpochSelectionHasher;
import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt;
import com.example.dispute.workflow.application.intake.IntakeFinalizationReceiptCodec;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.ActiveChildBinding;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.CommandRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.DomainEventRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.StartRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeGraphExecutionRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Read-only JDBC authority bridge. Every bridge operation owns one PostgreSQL
 * REPEATABLE_READ snapshot and fails closed unless the expected authority
 * cardinality and tuple are present in that snapshot.
 *
 * <p>This class deliberately has no write API and does not depend on a Temporal
 * repository. It is safe to construct in the DISABLED/SIGNED_SYNTHETIC_SHADOW
 * gate; command reads reject ACTIVITY_ORCHESTRATED rows.
 */
public final class JdbcIntakeChildBridgeReadPort implements IntakeChildBridgeReadPort {

    public static final int REQUIRED_ISOLATION = Connection.TRANSACTION_REPEATABLE_READ;
    public static final String INERT_DISPOSITION = "INERT_EXTERNAL_EVENT";
    public static final String REGISTERED = "REGISTERED";
    public static final String ACTIVE = "ACTIVE";

    private static final String SHADOW = "SHADOW";
    private static final String EXISTING_PRIVATE_EVENT = "EXISTING_PRIVATE_EVENT";
    private static final String TURN_EVENT_SCHEMA = "intake-turn-event.v2";
    private static final String TURN_PROPOSAL_SCHEMA = "intake-turn-proposal.v2";

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private static final String EPOCH_SQL = """
        select epoch_id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token,
               selection_hash, writer_mode, case_workflow_type, case_workflow_build_id,
               room_workflow_type, room_workflow_build_id, process_contract_version,
               graph_key, graph_version, checkpoint_schema_version, state_schema_version,
               stream_protocol, prompt_version, model_profile_id, output_schema_version,
               policy_version, guardrail_version, tool_policy_version, cohort_policy_version,
               agent_key, agent_session_profile_version, memory_policy_id
          from case_intake_epoch_selection_binding
         where epoch_id = ? and tenant_surrogate = ? and case_id = ?
           and room_type = 'INTAKE' and room_epoch = ? and fencing_token = ?
           and writer_mode = 'SHADOW'
        """;

    private static final String BOOTSTRAP_SQL = """
        select outbox.id, outbox.epoch_id, outbox.tenant_surrogate, outbox.case_id,
               outbox.room_type, outbox.room_epoch, outbox.fencing_token,
               outbox.writer_mode, outbox.case_workflow_id, outbox.room_workflow_id,
               outbox.workflow_type, outbox.task_queue, outbox.update_id,
               outbox.payload_json, outbox.payload_sha256, outbox.outbox_status
          from room_epoch_bootstrap_outbox outbox
          join case_intake_epoch_selection_binding selection
            on selection.epoch_id = outbox.epoch_id
           and selection.tenant_surrogate = outbox.tenant_surrogate
           and selection.case_id = outbox.case_id and selection.room_type = outbox.room_type
           and selection.room_epoch = outbox.room_epoch and selection.fencing_token = outbox.fencing_token
           and selection.writer_mode = outbox.writer_mode
         where outbox.epoch_id = ? and outbox.tenant_surrogate = ? and outbox.case_id = ?
           and outbox.room_type = 'INTAKE' and outbox.room_epoch = ? and outbox.fencing_token = ?
           and outbox.writer_mode = 'SHADOW'
        """;

    // Start is the only bridge read that reopens current session/registration authorization.
    private static final String START_PARTY_SQL = """
        select p.party, p.tenant_surrogate, p.case_id, p.session_tenant_id,
               p.session_case_id, p.room_type, p.room_epoch, p.fencing_token,
               p.registration_id, p.registration_hash, p.thread_id, p.actor_id,
               p.actor_role, p.audience, p.actor_scope_hash, p.access_session_id,
               p.permission_level, p.agent_session_id, p.agent_key, p.prompt_version,
               p.agent_session_profile_version, p.prompt_profile_id, p.memory_policy_id,
               t.registration_status, a.status as access_status, g.status as agent_status,
               t.graph_key, t.graph_version, t.checkpoint_schema_version,
               t.state_schema_version, t.prompt_version as registration_prompt_version,
               t.model_profile_id as registration_model_profile_id,
               t.output_schema_version, t.policy_version, t.guardrail_version,
               t.tool_policy_version, t.writer_mode
          from case_intake_epoch_party_authority p
          join case_intake_graph_thread_binding t
            on t.registration_id = p.registration_id
           and t.tenant_surrogate = p.tenant_surrogate and t.case_id = p.case_id
           and t.room_type = p.room_type and t.room_epoch = p.room_epoch
           and t.fencing_token = p.fencing_token and t.thread_id = p.thread_id
           and t.actor_id = p.actor_id and t.actor_role = p.actor_role
           and t.actor_scope_hash = p.actor_scope_hash
           and t.agent_session_id = p.agent_session_id and t.audience = p.audience
           and t.registration_hash = p.registration_hash
          join case_access_session a
            on a.id = p.access_session_id and a.tenant_id = p.session_tenant_id
           and a.case_id = p.session_case_id and a.actor_id = p.actor_id
           and a.actor_role = p.actor_role and a.permission_level = p.permission_level
          join agent_conversation_session g
            on g.id = p.agent_session_id and g.tenant_id = p.session_tenant_id
           and g.case_id = p.session_case_id and g.room_type = p.room_type
           and g.access_session_id = p.access_session_id and g.actor_id = p.actor_id
           and g.actor_role = p.actor_role and g.agent_key = p.agent_key
           and g.prompt_profile_id = p.prompt_profile_id
           and g.memory_policy_id = p.memory_policy_id
         where p.epoch_id = ? and p.tenant_surrogate = ? and p.case_id = ?
           and p.room_type = 'INTAKE' and p.room_epoch = ? and p.fencing_token = ?
           and p.party in ('INITIATOR', 'RESPONDENT')
           and t.registration_status = 'REGISTERED' and a.status = 'ACTIVE' and g.status = 'ACTIVE'
         order by p.party
        """;

    private static final String COMMAND_SQL = """
        select ca.command_id, ca.case_command_sequence, ca.command_type,
               ca.epoch_id, ca.access_session_id, ca.registration_id,
               ca.tenant_surrogate, ca.case_id, ca.room_type, ca.room_epoch,
               ca.fencing_token, ca.thread_id, ca.actor_id, ca.actor_role,
               ca.actor_scope_hash, ca.agent_session_id, ca.payload_authority_id,
               ca.request_hash, ca.accepted_room_revision, ca.execution_disposition,
               pa.content_sha256, pa.source_kind, pa.schema_version as payload_schema_version,
               pa.object_uri as authority_payload_uri, pa.size_bytes as authority_payload_size_bytes,
               c.payload_uri, c.payload_size_bytes, c.expected_process_revision,
               c.deadline_at, c.command_type as case_command_type,
               p.party, s.case_workflow_type,
               s.case_workflow_build_id, s.room_workflow_type, s.room_workflow_build_id
          from case_intake_command_authority ca
          join case_intake_command_payload_authority pa
            on pa.payload_authority_id = ca.payload_authority_id
           and pa.epoch_id = ca.epoch_id and pa.party_authority_id = ca.party_authority_id
           and pa.access_session_id = ca.access_session_id
           and pa.registration_id = ca.registration_id
           and pa.tenant_surrogate = ca.tenant_surrogate and pa.case_id = ca.case_id
           and pa.room_type = ca.room_type and pa.room_epoch = ca.room_epoch
           and pa.fencing_token = ca.fencing_token and pa.thread_id = ca.thread_id
           and pa.actor_scope_hash = ca.actor_scope_hash
           and pa.agent_session_id = ca.agent_session_id and pa.command_id = ca.command_id
          join case_intake_epoch_party_authority p
            on p.authority_id = ca.party_authority_id and p.epoch_id = ca.epoch_id
           and p.tenant_surrogate = ca.tenant_surrogate and p.case_id = ca.case_id
           and p.room_type = ca.room_type and p.room_epoch = ca.room_epoch
           and p.fencing_token = ca.fencing_token and p.access_session_id = ca.access_session_id
           and p.registration_id = ca.registration_id and p.thread_id = ca.thread_id
           and p.actor_id = ca.actor_id and p.actor_role = ca.actor_role
           and p.actor_scope_hash = ca.actor_scope_hash and p.agent_session_id = ca.agent_session_id
          join case_intake_snapshot_binding source_binding
            on source_binding.binding_id = pa.existing_event_binding_id
           and source_binding.thread_registration_id = pa.registration_id
           and source_binding.tenant_surrogate = pa.tenant_surrogate
           and source_binding.case_id = pa.case_id and source_binding.room_type = pa.room_type
           and source_binding.room_epoch = pa.room_epoch and source_binding.fencing_token = pa.fencing_token
           and source_binding.thread_id = pa.thread_id and source_binding.actor_scope_hash = pa.actor_scope_hash
           and source_binding.agent_session_id = pa.agent_session_id and source_binding.actor_audience = pa.actor_role
           and source_binding.schema_version = pa.schema_version and source_binding.artifact_id = pa.artifact_id
           and source_binding.object_uri = pa.object_uri and source_binding.object_version = pa.object_version
           and source_binding.content_sha256 = pa.content_sha256 and source_binding.size_bytes = pa.size_bytes
           and source_binding.binding_type = 'EVENT' and source_binding.visibility = 'PRIVATE'
           and not source_binding.initialization_marker
          join case_command c
            on c.id = ca.case_command_id and c.tenant_surrogate = ca.tenant_surrogate
           and c.case_id = ca.case_id and c.command_id = ca.command_id
           and c.request_hash = ca.request_hash and c.payload_schema_version = pa.schema_version
           and c.payload_uri = pa.object_uri and c.payload_sha256 = pa.content_sha256
           and c.payload_size_bytes = pa.size_bytes
          join case_intake_epoch_selection_binding s
            on s.epoch_id = ca.epoch_id and s.tenant_surrogate = ca.tenant_surrogate
           and s.case_id = ca.case_id and s.room_type = ca.room_type
           and s.room_epoch = ca.room_epoch and s.fencing_token = ca.fencing_token
          where ca.tenant_surrogate = ? and ca.case_id = ? and ca.command_id = ?
           and ca.room_type = 'INTAKE' and ca.room_epoch = ?
           and ca.actor_id = ? and ca.actor_role = ?
           and ca.command_type = 'INTAKE_MESSAGE'
           and ca.execution_disposition = 'INERT_EXTERNAL_EVENT'
           and pa.source_kind = 'EXISTING_PRIVATE_EVENT'
           and pa.schema_version = 'intake-turn-event.v2'
           and s.writer_mode = 'SHADOW'
        """;

    private static final String EVENT_SQL = """
        select 'case-event:' || event.id as binding_id,
               p.registration_id as thread_registration_id,
               ca.tenant_surrogate, ca.case_id, ca.room_type, ca.room_epoch, ca.fencing_token,
               p.thread_id, p.actor_scope_hash, p.agent_session_id, p.actor_role as actor_audience,
               'case-timeline-event.v1' as schema_version, event.id as artifact_id,
               'urn:case-timeline-event:' || event.id as object_uri, event.id as object_version,
               event.event_json::text, octet_length(event.event_json::text) as size_bytes,
               event.id as event_id, event.sequence_no as event_sequence, event.event_type,
               event.source_refs_json::text as source_refs_json,
               p.party, p.actor_id, p.actor_role, p.registration_id,
               p.access_session_id, ca.command_id as authority_command_id,
               ca.case_command_id, ca.request_hash as authority_request_hash,
               ca.accepted_room_revision,
               s.case_workflow_type,
               s.case_workflow_build_id, s.room_workflow_type, s.room_workflow_build_id,
               pa.source_kind as authority_source_kind,
               pa.schema_version as authority_payload_schema_version,
               pa.object_uri as authority_payload_uri,
               pa.content_sha256 as authority_payload_hash,
               pa.size_bytes as authority_payload_size_bytes,
               source_binding.artifact_id as source_artifact_id,
               source_binding.object_uri as source_payload_uri,
               source_binding.content_sha256 as source_payload_hash,
               source_binding.size_bytes as source_payload_size_bytes
          from case_timeline_event event
          join case_room_epoch epoch
            on epoch.room_id = event.room_id
           and epoch.case_id = event.case_id
           and epoch.room_type = 'INTAKE'
          join case_intake_epoch_selection_binding s
            on s.epoch_id = epoch.id
           and s.tenant_surrogate = ?
           and s.case_id = event.case_id
           and s.room_type = 'INTAKE'
           and s.room_epoch = ?
           and s.fencing_token = ?
           and s.writer_mode = 'SHADOW'
          join case_intake_command_authority ca
            on ca.command_id = coalesce(
                   event.event_json ->> 'command_id',
                   event.event_json #>> '{receipt,command_id}'
               )
           and ca.epoch_id = s.epoch_id
           and ca.party_authority_id is not null
           and ca.access_session_id is not null
           and ca.registration_id is not null
           and ca.tenant_surrogate = s.tenant_surrogate
           and ca.case_id = s.case_id
           and ca.room_type = s.room_type
           and ca.room_epoch = s.room_epoch
           and ca.fencing_token = s.fencing_token
          join case_intake_epoch_party_authority p
            on p.authority_id = ca.party_authority_id
           and p.epoch_id = ca.epoch_id
           and p.tenant_surrogate = ca.tenant_surrogate
           and p.case_id = ca.case_id
           and p.room_type = ca.room_type
           and p.room_epoch = ca.room_epoch
           and p.fencing_token = ca.fencing_token
           and p.access_session_id = ca.access_session_id
           and p.registration_id = ca.registration_id
           and p.thread_id = ca.thread_id
           and p.actor_id = ca.actor_id
           and p.actor_role = ca.actor_role
           and p.actor_scope_hash = ca.actor_scope_hash
           and p.agent_session_id = ca.agent_session_id
          join case_intake_command_payload_authority pa
            on pa.payload_authority_id = ca.payload_authority_id
           and pa.command_id = ca.command_id
           and pa.epoch_id = ca.epoch_id
           and pa.party_authority_id = ca.party_authority_id
           and pa.access_session_id = ca.access_session_id
           and pa.registration_id = ca.registration_id
           and pa.tenant_surrogate = ca.tenant_surrogate
           and pa.case_id = ca.case_id
           and pa.room_type = ca.room_type
           and pa.room_epoch = ca.room_epoch
           and pa.fencing_token = ca.fencing_token
           and pa.thread_id = ca.thread_id
           and pa.actor_scope_hash = ca.actor_scope_hash
           and pa.agent_session_id = ca.agent_session_id
          left join case_intake_snapshot_binding source_binding
            on pa.source_kind = 'EXISTING_PRIVATE_EVENT'
           and source_binding.binding_id = pa.existing_event_binding_id
           and source_binding.thread_registration_id = pa.registration_id
           and source_binding.tenant_surrogate = pa.tenant_surrogate
           and source_binding.case_id = pa.case_id
           and source_binding.room_type = pa.room_type
           and source_binding.room_epoch = pa.room_epoch
           and source_binding.fencing_token = pa.fencing_token
           and source_binding.thread_id = pa.thread_id
           and source_binding.actor_scope_hash = pa.actor_scope_hash
           and source_binding.agent_session_id = pa.agent_session_id
           and source_binding.actor_audience = pa.actor_role
           and source_binding.schema_version = pa.schema_version
           and source_binding.artifact_id = pa.artifact_id
           and source_binding.object_uri = pa.object_uri
           and source_binding.object_version = pa.object_version
           and source_binding.content_sha256 = pa.content_sha256
           and source_binding.size_bytes = pa.size_bytes
           and source_binding.binding_type = 'EVENT'
           and source_binding.visibility = 'PRIVATE'
           and not source_binding.initialization_marker
          join case_command c
            on c.id = ca.case_command_id
           and c.tenant_surrogate = ca.tenant_surrogate
           and c.case_id = ca.case_id
           and c.command_id = ca.command_id
           and c.request_hash = ca.request_hash
           and c.payload_schema_version = pa.schema_version
           and c.payload_uri = pa.object_uri
           and c.payload_sha256 = pa.content_sha256
           and c.payload_size_bytes = pa.size_bytes
         where event.id = ?
           and event.case_id = ?
           and event.sequence_no = ?
           and (
                (
                    event.event_json ->> 'schema_version' = 'intake-turn-committed-event.v1'
                    and ca.command_type = 'INTAKE_MESSAGE'
                    and ca.execution_disposition = 'INERT_EXTERNAL_EVENT'
                    and pa.source_kind = 'EXISTING_PRIVATE_EVENT'
                    and pa.schema_version = 'intake-turn-event.v2'
                    and source_binding.binding_id is not null
                )
                or
                (
                    event.event_json ->> 'schema_version' = 'intake-branch-committed-event.v1'
                    and (
                        (event.event_json ->> 'event_type' = 'CANCELLED'
                            and ca.command_type = 'INTAKE_CANCEL')
                        or
                        (event.event_json ->> 'event_type' in (
                                'INITIATOR_ACCEPTED', 'NOT_ADMISSIBLE', 'RESPONDENT_CONFIRMED'
                            ) and ca.command_type = 'INTAKE_CONFIRM')
                    )
                    and ca.execution_disposition = 'ACTIVITY_ORCHESTRATED'
                    and pa.source_kind = 'SERVER_CANONICAL_BRANCH'
                    and pa.schema_version = 'intake-branch-command.v1'
                )
           )
        """;

    private static final String TURN_EVENT_EVIDENCE_SQL = """
        select operation.operation_key,
               operation.request_hash as operation_request_hash,
               operation.result_sha256 as operation_receipt_hash,
               operation.process_revision as operation_process_revision,
               run.id as logical_run_id, run.committed_attempt_id, run.final_result_hash,
               attempt.id as attempt_id, attempt.command_id as graph_command_id,
               attempt.command_request_hash as graph_command_request_hash,
               attempt.command_json::text as graph_command_json,
               attempt.graph_key, attempt.graph_version, attempt.checkpoint_schema_version,
               attempt.checkpoint_id, attempt.prompt_version, attempt.model_profile_id,
               attempt.output_schema_version as attempt_output_schema_version,
               attempt.policy_version, attempt.guardrail_version,
               attempt.result_hash as attempt_result_hash,
               attempt.result_json::text as graph_result_json,
               manifest.id as manifest_id, manifest.manifest_sha256,
               manifest.output_sha256, manifest.input_snapshot_refs_json::text as manifest_inputs_json,
               output.object_uri as output_uri, output.content_sha256 as output_hash,
               output.schema_version as graph_output_schema_version,
               proposal.operation as proposal_operation,
               proposal.artifact_id as proposal_artifact_id,
               proposal.schema_version as proposal_schema_version,
               proposal.uri as proposal_uri, proposal.sha256 as proposal_hash,
               final_event.sequence_no as final_stream_sequence_no,
               final_event.audience as final_stream_audience,
               final_event.payload_hash as final_stream_payload_hash,
               final_event.payload_json::text as final_stream_payload_json
          from case_timeline_event event
          join domain_operation operation
            on operation.result_uri = 'urn:intake:finalization-receipt:' || event.id
           and operation.operation_status = 'COMPLETED'
           and operation.operation_type = 'INTAKE_TURN_FINALIZE'
           and operation.operation_key = event.event_json ->> 'operation_key'
           and operation.request_hash = event.event_json ->> 'request_hash'
           and operation.result_sha256 = event.event_json #>> '{receipt,receipt_hash}'
          join agent_run run
            on run.id = event.event_json #>> '{receipt,logical_run_id}'
           and run.committed_attempt_id = event.event_json #>> '{receipt,attempt_id}'
           and run.final_result_hash = event.event_json ->> 'result_hash'
           and run.finalization_status = 'COMMITTED'
           and run.protocol = 'agent-stream.v3'
           and run.executor_kind = 'TEMPORAL_ACTIVITY'
          join agent_run_attempt attempt
            on attempt.id = run.committed_attempt_id
           and attempt.agent_run_id = run.id
           and attempt.attempt_status = 'COMPLETED'
           and attempt.result_hash = run.final_result_hash
           and run.request_hash = attempt.command_request_hash
           and attempt.request_hash = attempt.command_json ->> 'request_hash'
           and attempt.final_frame_observed
           and attempt.command_id = ?
           and attempt.command_request_hash = attempt.command_json ->> 'request_hash'
           and attempt.command_json ->> 'command_id' = attempt.command_id
           and attempt.command_json ->> 'logical_run_id' = run.id
           and attempt.command_json ->> 'attempt_id' = attempt.id
           and attempt.command_json ->> 'graph_key' = attempt.graph_key
           and attempt.command_json ->> 'graph_version' = attempt.graph_version
           and attempt.command_json ->> 'checkpoint_schema_version' = attempt.checkpoint_schema_version
           and jsonb_array_length(attempt.result_json #> '{graph_result,artifact_operations}') = 1
           and attempt.result_json #>> '{graph_result,artifact_operations,0,operation}' = 'PROPOSE_PATCH'
          join agent_execution_manifest manifest
            on manifest.id = run.committed_manifest_id
           and manifest.logical_agent_run_id = run.id
           and manifest.attempt_id = run.committed_attempt_id
           and manifest.manifest_sha256 = run.committed_manifest_hash
           and manifest.output_sha256 = run.final_result_hash
           and manifest.terminal_status = 'COMPLETED'
           and manifest.tenant_surrogate = run.tenant_surrogate
           and manifest.case_id = run.case_id
           and manifest.room_type = run.room_type
           and manifest.room_epoch = run.room_epoch
           and manifest.process_revision = run.process_revision
           and manifest.fencing_token = run.fencing_token
           and manifest.graph_key = attempt.graph_key
           and manifest.graph_version = attempt.graph_version
           and manifest.checkpoint_schema_version = attempt.checkpoint_schema_version
           and manifest.checkpoint_id = attempt.checkpoint_id
           and manifest.prompt_version = attempt.prompt_version
           and manifest.model_profile_id = attempt.model_profile_id
           and manifest.policy_version = attempt.policy_version
           and manifest.guardrail_version = attempt.guardrail_version
          join immutable_payload_snapshot output
            on output.id = manifest.output_snapshot_id
           and output.tenant_surrogate = manifest.tenant_surrogate
           and output.case_id = manifest.case_id
           and output.content_sha256 = manifest.output_sha256
           and output.schema_version = 'room-graph-result.v1'
          cross join lateral (
              select operation_row.value ->> 'operation' as operation,
                     operation_row.value -> 'artifact' ->> 'artifact_id' as artifact_id,
                     operation_row.value -> 'artifact' ->> 'schema_version' as schema_version,
                     operation_row.value -> 'artifact' ->> 'uri' as uri,
                     operation_row.value -> 'artifact' ->> 'sha256' as sha256
                from jsonb_array_elements(
                         attempt.result_json #> '{graph_result,artifact_operations}'
                     ) as operation_row(value)
          ) proposal
          join agent_run_stream_event final_event
            on final_event.agent_run_id = run.id
           and final_event.agent_run_attempt_id = run.committed_attempt_id
           and final_event.sequence_no = run.final_stream_sequence_no
           and final_event.stream_protocol = 'agent-stream.v3'
           and final_event.event_type = 'final'
           and final_event.payload_json #>> '{payload,final_result_ref}' = output.object_uri
           and final_event.payload_json #>> '{payload,final_result_hash}' = run.final_result_hash
         where event.id = ?
           and operation.tenant_surrogate = ?
           and operation.case_id = ?
           and operation.room_type = 'INTAKE'
           and operation.room_epoch = ?
           and operation.fencing_token = ?
           and proposal.operation = 'PROPOSE_PATCH'
           and proposal.schema_version = 'intake-turn-proposal.v2'
           and proposal.sha256 = event.event_json ->> 'proposal_hash'
           and event.source_refs_json @> jsonb_build_array(proposal.artifact_id)
           and run.tenant_surrogate = ?
           and run.case_id = ?
           and run.room_type = 'INTAKE'
           and run.room_epoch = ?
           and run.fencing_token = ?
        """;

    private static final String BRANCH_EVENT_EVIDENCE_SQL = """
        select operation.operation_key,
               operation.request_hash as operation_request_hash,
               operation.result_sha256 as operation_result_hash,
               operation.process_revision as operation_process_revision
          from case_timeline_event event
          join domain_operation operation
            on operation.result_uri = 'urn:after-sale-flow:intake-event:' || event.id
           and operation.operation_status = 'COMPLETED'
           and operation.operation_key = event.event_json ->> 'operation_key'
           and operation.request_hash = event.event_json ->> 'request_hash'
           and operation.result_sha256 = event.event_json ->> 'result_hash'
           and operation.operation_type = case event.event_json ->> 'event_type'
               when 'INITIATOR_ACCEPTED' then 'INTAKE_INITIATOR_ACCEPT'
               when 'NOT_ADMISSIBLE' then 'INTAKE_INITIATOR_REJECT'
               when 'CANCELLED' then 'INTAKE_CANCEL'
               when 'RESPONDENT_CONFIRMED' then 'INTAKE_RESPONDENT_CONFIRM'
               else ''
           end
          join case_intake_command_authority ca
            on ca.case_command_id = operation.case_command_id
           and ca.command_id = event.event_json ->> 'command_id'
           and ca.tenant_surrogate = operation.tenant_surrogate
           and ca.case_id = operation.case_id
           and ca.room_type = operation.room_type
           and ca.room_epoch = operation.room_epoch
           and ca.fencing_token = operation.fencing_token
         where event.id = ?
           and operation.tenant_surrogate = ?
           and operation.case_id = ?
           and operation.room_type = 'INTAKE'
           and operation.room_epoch = ?
           and operation.fencing_token = ?
        """;

    private final DataSource dataSource;

    public JdbcIntakeChildBridgeReadPort(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public StartSource readStart(StartRequest request) {
        Objects.requireNonNull(request, "request");
        ProvisionRoomEpoch provision = request.provisioning();
        return inSnapshot(connection -> readStart(connection, provision));
    }

    @Override
    public CommandSource readCommand(CommandRequest request) {
        Objects.requireNonNull(request, "request");
        CaseCommandRef command = request.command();
        return inSnapshot(connection -> readCommand(connection, command));
    }

    @Override
    public DomainEventSource readDomainEvent(DomainEventRequest request) {
        Objects.requireNonNull(request, "request");
        CaseDomainEventRef event = request.event();
        ActiveChildBinding active = request.activeBinding();
        return inSnapshot(connection -> readDomainEvent(connection, event, active));
    }

    private StartSource readStart(Connection connection, ProvisionRoomEpoch provision)
            throws SQLException {
        EpochRow epoch = exactlyOne(connection, EPOCH_SQL, "epoch selection", statement -> {
            statement.setString(1, provision.epochId());
            statement.setString(2, provision.tenantSurrogate());
            statement.setString(3, provision.caseId());
            statement.setLong(4, provision.roomEpoch());
            statement.setLong(5, provision.fencingToken());
        }, JdbcIntakeChildBridgeReadPort::mapEpoch);
        BootstrapRow bootstrap = exactlyOne(connection, BOOTSTRAP_SQL, "bootstrap outbox", statement -> {
            statement.setString(1, provision.epochId());
            statement.setString(2, provision.tenantSurrogate());
            statement.setString(3, provision.caseId());
            statement.setLong(4, provision.roomEpoch());
            statement.setLong(5, provision.fencingToken());
        }, JdbcIntakeChildBridgeReadPort::mapBootstrap);
        ProvisionRoomEpoch persistedProvision = parseBootstrapPayload(bootstrap.payloadJson());
        requireBootstrap(bootstrap, persistedProvision, provision);
        List<PartyRow> parties = all(connection, START_PARTY_SQL, "epoch party authority", statement -> {
            statement.setString(1, provision.epochId());
            statement.setString(2, provision.tenantSurrogate());
            statement.setString(3, provision.caseId());
            statement.setLong(4, provision.roomEpoch());
            statement.setLong(5, provision.fencingToken());
        }, JdbcIntakeChildBridgeReadPort::mapParty);
        if (parties.size() != 2) {
            throw new IntakeAuthorityInvariantException("epoch must have exactly one initiator and respondent");
        }
        PartyRow initiator = party(parties, IntakeParty.INITIATOR);
        PartyRow respondent = party(parties, IntakeParty.RESPONDENT);
        requireStartSelection(epoch, provision);
        for (PartyRow row : parties) {
            requireStartPartyPins(row, epoch);
        }
        ActiveChildBinding binding = binding(epoch);
        return new StartSource(binding, bootstrap.payloadSha256(), epoch.promptVersion(), epoch.modelProfileId(),
                epoch.outputSchemaVersion(), epoch.policyVersion(), epoch.guardrailVersion(),
                epoch.toolPolicyVersion(), initiator.actorScopeHash(), respondent.actorScopeHash());
    }

    private CommandSource readCommand(Connection connection, CaseCommandRef command) throws SQLException {
        CommandRow row = exactlyOne(connection, COMMAND_SQL, "command authority", statement -> {
            statement.setString(1, command.tenantSurrogate());
            statement.setString(2, command.caseId());
            statement.setString(3, command.commandId());
            statement.setLong(4, command.roomEpoch());
            statement.setString(5, command.actorRef().actorId());
            statement.setString(6, command.actorRef().actorRole().name());
        }, JdbcIntakeChildBridgeReadPort::mapCommand);
        requireEquals(row.commandId(), command.commandId(), "command id");
        requireEquals(row.tenantSurrogate(), command.tenantSurrogate(), "tenant");
        requireEquals(row.caseId(), command.caseId(), "case");
        requireEquals(row.roomEpoch(), command.roomEpoch(), "room epoch");
        requireEquals(row.commandType(), command.commandType().name(), "command type");
        requireEquals(row.payloadSchemaVersion(), command.payloadRef().schemaVersion(), "payload schema");
        requireEquals(row.payloadUri(), command.payloadRef().uri(), "payload URI");
        requireEquals(row.payloadSha256(), command.payloadRef().sha256(), "payload hash");
        requireEquals(row.payloadSizeBytes(), command.payloadRef().sizeBytes(), "payload size");
        requireEquals(row.requestHash(), command.requestHash(), "request hash");
        requireInertSourceMatrix(row);
        IntakeParty party = intakeParty(row.party());
        ActorRole actorRole = actorRole(row.actorRole());
        requirePartyRole(party, actorRole);
        // Inert external-event reads intentionally expose no activity execution context.
        return new CommandSource(binding(row), row.commandId(), row.tenantSurrogate(), row.caseId(),
                row.roomEpoch(), row.fencingToken(), row.sequence(), commandType(row.commandType()),
                row.payloadSha256(), row.requestHash(), row.processRevision(), row.roomRevision(),
                party, row.actorScopeHash(), operationKey(row.caseId(), row.commandId()), null);
    }

    private DomainEventSource readDomainEvent(
            Connection connection, CaseDomainEventRef event, ActiveChildBinding active)
            throws SQLException {
        Objects.requireNonNull(active, "active binding");
        requireEquals(active.tenantSurrogate(), event.tenantSurrogate(), "event active tenant");
        requireEquals(active.caseId(), event.caseId(), "event active case");
        requireEquals(active.roomEpoch(), event.roomEpoch(), "event active room epoch");
        EventRow row = exactlyOne(connection, EVENT_SQL, "event authority", statement -> {
            statement.setString(1, event.tenantSurrogate());
            statement.setLong(2, event.roomEpoch());
            statement.setLong(3, active.fencingToken());
            statement.setString(4, event.eventId());
            statement.setString(5, event.caseId());
            statement.setLong(6, event.caseEventSequence());
        }, JdbcIntakeChildBridgeReadPort::mapEvent);
        requireEquals(row.tenantSurrogate(), event.tenantSurrogate(), "tenant");
        requireEquals(row.caseId(), event.caseId(), "case");
        requireEquals(row.roomEpoch(), event.roomEpoch(), "room epoch");
        requireEquals(row.sequence(), event.caseEventSequence(), "event sequence");
        requireEquals(row.schemaVersion(), "case-timeline-event.v1", "event schema");
        requireEquals(row.schemaVersion(), event.payloadRef().schemaVersion(), "event payload schema");
        requireEquals(row.objectUri(), "urn:case-timeline-event:" + row.eventId(), "event payload URI");
        requireEquals(row.objectUri(), event.payloadRef().uri(), "event payload URI");
        String sourcePayloadHash = sha256Utf8(row.eventJson());
        requireEquals(sourcePayloadHash, event.payloadRef().sha256(), "event payload hash");
        requireEquals(row.sizeBytes(), event.payloadRef().sizeBytes(), "event payload size");
        JsonNode json = parseEventJson(row.eventJson());
        IntakeParty party = intakeParty(row.party());
        IntakeDomainEventType eventType = eventType(row.eventType());
        EventEvidence evidence = eventEvidence(connection, row, eventType, json);
        requireEquals(evidence.commandId(), row.authorityCommandId(), "event command authority");
        requireEquals(evidence.requestHash(), eventRequestHash(json), "event request authority");
        String eventHash = ContractJson.sha256Hex(json);
        return new DomainEventSource(binding(row), row.eventId(), row.eventType(), eventType,
                row.tenantSurrogate(), row.caseId(), row.roomEpoch(), row.fencingToken(), row.sequence(),
                sourcePayloadHash, row.objectUri(), eventHash, party, evidence.commandId(), row.actorScopeHash(),
                evidence.operationKey(), evidence.requestHash(), evidence.resultHash(), evidence.processRevision(),
                evidence.roomRevision(), evidence.agentRunRef(), evidence.graphExecutionRef());
    }

    private static ProvisionRoomEpoch parseBootstrapPayload(String payloadJson) {
        try {
            ProvisionRoomEpoch provision = MAPPER.readValue(payloadJson, ProvisionRoomEpoch.class);
            if (provision == null) {
                throw new IntakeAuthorityInvariantException("bootstrap payload is missing");
            }
            return provision;
        } catch (IntakeAuthorityInvariantException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IntakeAuthorityInvariantException("bootstrap payload is malformed", exception);
        }
    }

    private static void requireBootstrap(
            BootstrapRow bootstrap, ProvisionRoomEpoch persisted, ProvisionRoomEpoch requested) {
        requireEquals(bootstrap.epochId(), persisted.epochId(), "bootstrap epoch id");
        requireEquals(bootstrap.tenantSurrogate(), persisted.tenantSurrogate(), "bootstrap tenant");
        requireEquals(bootstrap.caseId(), persisted.caseId(), "bootstrap case");
        requireEquals(bootstrap.roomType(), persisted.roomType().name(), "bootstrap room type");
        requireEquals(bootstrap.roomEpoch(), persisted.roomEpoch(), "bootstrap room epoch");
        requireEquals(bootstrap.fencingToken(), persisted.fencingToken(), "bootstrap fence");
        requireEquals(bootstrap.writerMode(), persisted.writerMode().name(), "bootstrap writer mode");
        requireEquals(bootstrap.caseWorkflowId(), persisted.caseWorkflowId(), "bootstrap case workflow id");
        requireEquals(bootstrap.roomWorkflowId(), persisted.roomWorkflowId(), "bootstrap room workflow id");
        requireEquals(bootstrap.workflowType(), persisted.workflowType(), "bootstrap workflow type");
        requireEquals(bootstrap.taskQueue(), CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE,
                "bootstrap task queue");
        requireEquals(bootstrap.updateId(), persisted.updateId(), "bootstrap update id");
        requireEquals(bootstrap.payloadSha256(), persisted.payloadSha256(), "bootstrap payload hash");
        requireEquals(persisted, requested, "bootstrap payload");
        requireEquals(bootstrap.payloadSha256(), requested.payloadSha256(), "requested provisioning hash");
    }

    private static void requireStartSelection(EpochRow epoch, ProvisionRoomEpoch provision) {
        String expectedSelectionHash;
        try {
            expectedSelectionHash = EpochSelectionHasher.hash(new EpochSelectionHasher.SelectionHashInput(
                    "room-epoch-selection.v2",
                    RoomType.valueOf(epoch.roomType()),
                    WriterMode.valueOf(epoch.writerMode()),
                    epoch.caseWorkflowType(),
                    epoch.caseWorkflowBuildId(),
                    epoch.roomWorkflowType(),
                    epoch.roomWorkflowBuildId(),
                    epoch.processContractVersion(),
                    epoch.graphKey(),
                    epoch.graphVersion(),
                    epoch.checkpointSchemaVersion(),
                    epoch.stateSchemaVersion(),
                    epoch.streamProtocol(),
                    epoch.promptVersion(),
                    epoch.modelProfileId(),
                    epoch.outputSchemaVersion(),
                    epoch.policyVersion(),
                    epoch.guardrailVersion(),
                    epoch.toolPolicyVersion(),
                    epoch.cohortPolicyVersion()));
        } catch (RuntimeException exception) {
            throw new IntakeAuthorityInvariantException("selection pins are malformed", exception);
        }
        requireEquals(epoch.selectionHash(), expectedSelectionHash, "selection hash");
        requireEquals(epoch.epochId(), provision.epochId(), "selection epoch id");
        requireEquals(epoch.tenantSurrogate(), provision.tenantSurrogate(), "selection tenant");
        requireEquals(epoch.caseId(), provision.caseId(), "selection case");
        requireEquals(epoch.roomType(), provision.roomType().name(), "selection room type");
        requireEquals(epoch.roomEpoch(), provision.roomEpoch(), "selection room epoch");
        requireEquals(epoch.fencingToken(), provision.fencingToken(), "selection fence");
        requireEquals(epoch.writerMode(), SHADOW, "selection writer mode");
        requireEquals(provision.writerMode().name(), SHADOW, "provision writer mode");
        requireEquals(provision.selectionSchemaVersion(), "room-epoch-selection.v2", "selection schema");
        requireEquals(epoch.caseWorkflowType(), provision.workflowType(), "case workflow type");
        requireEquals(epoch.caseWorkflowBuildId(), provision.temporalBuildId(), "case workflow build");
        requireEquals(epoch.roomWorkflowType(), provision.roomWorkflowType(), "room workflow type");
        requireEquals(epoch.roomWorkflowBuildId(), provision.roomWorkflowBuildId(), "room workflow build");
        requireEquals(epoch.processContractVersion(), provision.processContractVersion(), "process contract");
        requireEquals(epoch.graphKey(), provision.graphKey(), "graph key");
        requireEquals(epoch.graphVersion(), provision.graphVersion(), "graph version");
        requireEquals(epoch.checkpointSchemaVersion(), provision.checkpointSchemaVersion(), "checkpoint schema");
        requireEquals(epoch.streamProtocol(), provision.streamProtocol(), "stream protocol");
        requireEquals(epoch.graphKey(), "intake.v2", "graph key");
        requireEquals(epoch.stateSchemaVersion(), "intake-graph-state.v2", "state schema");
        requireEquals(epoch.outputSchemaVersion(), TURN_PROPOSAL_SCHEMA, "output schema");
        requireEquals(epoch.agentKey(), "DISPUTE_INTAKE_OFFICER", "agent key");
        requireEquals(epoch.agentSessionProfileVersion(), "agent-session-profile.v1", "agent profile");
        requireEquals(epoch.memoryPolicyId(), "GRAPH_PRIVATE_NO_MEMORY_FRAME_V1", "memory policy");
    }

    private static void requireStartPartyPins(PartyRow row, EpochRow epoch) {
        requireEquals(row.tenantSurrogate(), epoch.tenantSurrogate(), "party tenant");
        requireEquals(row.caseId(), epoch.caseId(), "party case");
        requireEquals(row.sessionTenantId(), epoch.tenantSurrogate(), "session tenant");
        requireEquals(row.sessionCaseId(), epoch.caseId(), "session case");
        requireEquals(row.roomType(), epoch.roomType(), "party room type");
        requireEquals(row.roomEpoch(), epoch.roomEpoch(), "party room epoch");
        requireEquals(row.fencingToken(), epoch.fencingToken(), "party fence");
        requireEquals(row.actorRole(), row.audience(), "party audience");
        requirePartyPermission(row.actorRole(), row.permissionLevel());
        requireEquals(row.agentKey(), epoch.agentKey(), "party agent key");
        requireEquals(row.promptVersion(), epoch.promptVersion(), "party prompt version");
        requireEquals(row.agentSessionProfileVersion(), epoch.agentSessionProfileVersion(), "party profile");
        requireEquals(row.memoryPolicyId(), epoch.memoryPolicyId(), "party memory policy");

        requireEquals(row.registrationWriterMode(), SHADOW, "registration writer mode");
        requireEquals(row.graphKey(), epoch.graphKey(), "registration graph key");
        requireEquals(row.graphVersion(), epoch.graphVersion(), "registration graph version");
        requireEquals(row.checkpointSchemaVersion(), epoch.checkpointSchemaVersion(), "registration checkpoint schema");
        requireEquals(row.stateSchemaVersion(), epoch.stateSchemaVersion(), "registration state schema");
        requireEquals(row.registrationPromptVersion(), epoch.promptVersion(), "registration prompt version");
        requireEquals(row.registrationPromptVersion(), row.promptVersion(), "party registration prompt");
        requireEquals(row.registrationModelProfileId(), epoch.modelProfileId(), "registration model profile");
        requireEquals(row.outputSchemaVersion(), epoch.outputSchemaVersion(), "registration output schema");
        requireEquals(row.policyVersion(), epoch.policyVersion(), "registration policy version");
        requireEquals(row.guardrailVersion(), epoch.guardrailVersion(), "registration guardrail version");
        requireEquals(row.toolPolicyVersion(), epoch.toolPolicyVersion(), "registration tool policy");
    }

    private static void requirePartyPermission(String actorRole, String permissionLevel) {
        if (("USER".equals(actorRole) && "PARTY_USER".equals(permissionLevel))
                || ("MERCHANT".equals(actorRole) && "PARTY_MERCHANT".equals(permissionLevel))) {
            return;
        }
        throw new IntakeAuthorityInvariantException("party permission does not match actor role");
    }

    private static void requireInertSourceMatrix(CommandRow row) {
        requireEquals(row.executionDisposition(), INERT_DISPOSITION, "execution disposition");
        requireEquals(row.commandType(), CommandType.INTAKE_MESSAGE.name(), "inert command type");
        requireEquals(row.sourceKind(), EXISTING_PRIVATE_EVENT, "inert payload source kind");
        requireEquals(row.payloadSchemaVersion(), TURN_EVENT_SCHEMA, "inert payload schema");
    }

    private static String expectedBranchOperationKey(IntakeDomainEventType eventType, EventRow row) {
        return switch (eventType) {
            case INITIATOR_ACCEPTED -> IntakeOperationKeys.initiatorAccept(
                    row.caseId(), row.roomEpoch(), row.authorityCommandId());
            case NOT_ADMISSIBLE -> IntakeOperationKeys.initiatorReject(
                    row.caseId(), row.roomEpoch(), row.authorityCommandId());
            case CANCELLED -> IntakeOperationKeys.cancel(
                    row.caseId(), row.roomEpoch(), row.authorityCommandId());
            case RESPONDENT_CONFIRMED -> IntakeOperationKeys.respondentConfirm(
                    row.caseId(), row.roomEpoch(), row.authorityCommandId());
            default -> throw new IntakeAuthorityInvariantException("unsupported branch event type");
        };
    }

    private static IntakeFinalizationReceipt requireTurnEventJson(
            JsonNode json, EventRow row, TurnEvidenceRow evidence) {
        requireJsonText(json, "schema_version", "intake-turn-committed-event.v1");
        requireJsonText(json, "operation_key", evidence.operationKey());
        requireJsonText(json, "request_hash", evidence.operationRequestHash());
        requireJsonText(json, "result_hash", evidence.finalResultHash());
        requireJsonText(json, "proposal_hash", evidence.proposalHash());
        requireJsonText(json, "actor_scope_hash", row.actorScopeHash());
        JsonNode receiptNode = json.get("receipt");
        if (receiptNode == null || !receiptNode.isObject()) {
            throw new IntakeAuthorityInvariantException("turn receipt is missing");
        }
        IntakeFinalizationReceipt receipt;
        try {
            receipt = MAPPER.treeToValue(receiptNode, IntakeFinalizationReceipt.class);
        } catch (Exception exception) {
            throw new IntakeAuthorityInvariantException("turn receipt is malformed", exception);
        }
        String canonicalReceiptHash = IntakeContractHashes.canonicalHashExcluding(
                IntakeFinalizationReceiptCodec.toTree(receipt), "receipt_hash");
        requireEquals(receipt.receiptHash(), canonicalReceiptHash, "turn receipt hash");
        requireEquals(receipt.receiptHash(), evidence.operationReceiptHash(), "turn operation receipt hash");
        requireEquals(receipt.operationKey(), evidence.operationKey(), "turn receipt operation key");
        requireEquals(receipt.tenantSurrogate(), row.tenantSurrogate(), "turn receipt tenant");
        requireEquals(receipt.caseId(), row.caseId(), "turn receipt case");
        requireEquals(receipt.roomEpoch(), row.roomEpoch(), "turn receipt room epoch");
        requireEquals(receipt.fencingToken(), row.fencingToken(), "turn receipt fence");
        requireEquals(receipt.threadId(), row.threadId(), "turn receipt thread");
        requireEquals(receipt.actorScopeHash(), row.actorScopeHash(), "turn receipt actor scope");
        requireEquals(receipt.agentSessionId(), row.agentSessionId(), "turn receipt agent session");
        requireEquals(receipt.commandId(), row.authorityCommandId(), "turn receipt command");
        requireEquals(receipt.logicalRunId(), evidence.logicalRunId(), "turn receipt logical run");
        requireEquals(receipt.attemptId(), evidence.attemptId(), "turn receipt attempt");
        requireEquals(receipt.resultHash(), evidence.finalResultHash(), "turn receipt graph result hash");
        requireEquals(receipt.proposalHash(), evidence.proposalHash(), "turn receipt proposal hash");
        requireEquals(receipt.processRevision(), evidence.operationProcessRevision(),
                "turn receipt process revision");
        requireEquals(receipt.roomRevision(), row.acceptedRoomRevision(),
                "turn receipt room revision");
        requireEquals(receipt.domainEventIds(), List.of(row.eventId()), "turn receipt event ids");
        requireJsonText(receiptNode, "operation_key", evidence.operationKey());
        requireJsonText(receiptNode, "result_hash", evidence.finalResultHash());
        requireJsonText(receiptNode, "command_id", row.authorityCommandId());
        requireJsonText(receiptNode, "tenant_surrogate", row.tenantSurrogate());
        requireJsonText(receiptNode, "case_id", row.caseId());
        requireJsonText(receiptNode, "thread_id", row.threadId());
        requireJsonText(receiptNode, "actor_scope_hash", row.actorScopeHash());
        requireJsonText(receiptNode, "agent_session_id", row.agentSessionId());
        requireJsonLong(receiptNode, "room_epoch", row.roomEpoch());
        requireJsonLong(receiptNode, "fencing_token", row.fencingToken());
        return receipt;
    }

    private static void requireBranchEventJson(
            JsonNode json, EventRow row, BranchEvidenceRow evidence, IntakeDomainEventType eventType) {
        requireJsonText(json, "schema_version", "intake-branch-committed-event.v1");
        requireJsonText(json, "event_type", branchEventType(eventType));
        requireJsonText(json, "party", row.party());
        requireJsonText(json, "command_id", row.authorityCommandId());
        requireJsonText(json, "tenant_surrogate", row.tenantSurrogate());
        requireJsonText(json, "case_id", row.caseId());
        requireJsonLong(json, "room_epoch", row.roomEpoch());
        requireJsonLong(json, "fencing_token", row.fencingToken());
        requireJsonText(json, "actor_scope_hash", row.actorScopeHash());
        requireJsonText(json, "operation_key", evidence.operationKey());
        requireJsonText(json, "request_hash", evidence.operationRequestHash());
        requireJsonText(json, "result_hash", evidence.operationResultHash());
        requireJsonText(json, "event_id", row.eventId());
        requireJsonLong(json, "event_sequence", row.sequence());
        requireJsonText(json, "event_ref", "urn:after-sale-flow:intake-event:" + row.eventId());
        JsonNode eventHash = json.get("event_hash");
        if (eventHash == null || !eventHash.isTextual()) {
            throw new IntakeAuthorityInvariantException("branch event hash is missing");
        }
        JsonNode withoutHash = json.deepCopy();
        ((ObjectNode) withoutHash).remove("event_hash");
        requireEquals(eventHash.asText(), ContractJson.sha256Hex(withoutHash), "branch event hash");
        requireNonNegativeJson(json, "process_revision");
        requireNonNegativeJson(json, "room_revision");
    }

    private static String branchEventType(IntakeDomainEventType eventType) {
        return switch (eventType) {
            case INITIATOR_ACCEPTED -> "INITIATOR_ACCEPTED";
            case NOT_ADMISSIBLE -> "NOT_ADMISSIBLE";
            case CANCELLED -> "CANCELLED";
            case RESPONDENT_CONFIRMED -> "RESPONDENT_CONFIRMED";
            default -> throw new IntakeAuthorityInvariantException("unsupported branch event type");
        };
    }

    private static void requireTurnDurableEvidence(EventRow row, TurnEvidenceRow evidence) {
        requireEquals(evidence.committedAttemptId(), evidence.attemptId(), "committed attempt");
        requireEquals(evidence.operationKey(),
                IntakeOperationKeys.turnFinalize(row.caseId(), row.roomEpoch(), row.threadId(),
                        row.authorityCommandId(), evidence.finalResultHash()),
                "turn operation key");
        requireEquals(evidence.graphCommandId(), row.authorityCommandId(), "turn graph command");
        requireEquals(evidence.graphKey(), "intake.v2", "turn graph key");
        requireEquals(evidence.graphOutputSchemaVersion(), "room-graph-result.v1",
                "turn graph result schema");
        requireEquals(evidence.outputHash(), evidence.finalResultHash(), "turn graph output hash");
        requireEquals(evidence.outputSha256(), evidence.finalResultHash(), "turn manifest output hash");
        requireEquals(evidence.attemptResultHash(), evidence.finalResultHash(), "turn attempt result hash");
        requireSha256(evidence.operationReceiptHash(), "turn receipt hash");
        requireSha256(evidence.finalStreamPayloadHash(), "turn final stream payload hash");
        requireSha256(evidence.manifestHash(), "turn manifest hash");

        JsonNode command = parseJson(evidence.graphCommandJson(), "graph command JSON");
        requireJsonText(command, "schema_version", "room-graph-command.v1");
        requireJsonText(command, "command_id", evidence.graphCommandId());
        requireJsonText(command, "logical_run_id", evidence.logicalRunId());
        requireJsonText(command, "attempt_id", evidence.attemptId());
        requireJsonText(command, "tenant_surrogate", row.tenantSurrogate());
        requireJsonText(command, "case_id", row.caseId());
        requireJsonText(command, "room_type", "INTAKE");
        requireJsonLong(command, "room_epoch", row.roomEpoch());
        requireJsonText(command, "thread_id", row.threadId());
        requireJsonText(command, "graph_key", evidence.graphKey());
        requireJsonText(command, "graph_version", evidence.graphVersion());
        requireJsonText(command, "checkpoint_schema_version", evidence.checkpointSchemaVersion());
        requireJsonLong(command, "process_revision", evidence.operationProcessRevision());
        requireJsonText(command, "request_hash", evidence.graphCommandRequestHash());
        JsonNode actorScope = command.get("actor_scope");
        if (actorScope == null || !actorScope.isObject()) {
            throw new IntakeAuthorityInvariantException("graph command actor scope is missing");
        }
        requireJsonText(actorScope, "actor_id", row.actorId());
        requireJsonText(actorScope, "actor_role", row.actorRole());
        requireJsonText(actorScope, "audience", row.actorRole());
        requireEquals(ContractJson.sha256Hex(actorScope), row.actorScopeHash(),
                "graph command actor scope hash");
        JsonNode invocation = command.get("invocation_context");
        if (invocation == null || !invocation.isObject()) {
            throw new IntakeAuthorityInvariantException("graph command invocation context is missing");
        }
        requireJsonText(invocation, "prompt_profile_id", evidence.promptVersion());
        requireJsonText(invocation, "model_profile_id", evidence.modelProfileId());
        requireJsonText(invocation, "output_schema_version", evidence.attemptOutputSchemaVersion());
        requireJsonText(invocation, "policy_version", evidence.policyVersion());
        requireJsonText(invocation, "guardrail_version", evidence.guardrailVersion());
        requireEquals(evidence.attemptOutputSchemaVersion(), TURN_PROPOSAL_SCHEMA,
                "turn output schema");
        ObjectNode commandWithoutHash = (ObjectNode) command.deepCopy();
        commandWithoutHash.remove("request_hash");
        requireEquals(evidence.graphCommandRequestHash(), ContractJson.sha256Hex(commandWithoutHash),
                "graph command self hash");

        JsonNode executeResult = parseJson(evidence.graphResultJson(), "AgentRun result JSON");
        requireJsonText(executeResult, "schema_version", "execute-agent-run-result.v3");
        requireJsonText(executeResult, "agent_run_id", evidence.logicalRunId());
        requireJsonText(executeResult, "logical_run_id", evidence.logicalRunId());
        requireJsonText(executeResult, "attempt_id", evidence.attemptId());
        requireJsonText(executeResult, "outcome", "COMPLETED");
        requireJsonText(executeResult, "result_hash", evidence.finalResultHash());
        JsonNode graphResult = executeResult.get("graph_result");
        if (graphResult == null || !graphResult.isObject()) {
            throw new IntakeAuthorityInvariantException("graph result is missing");
        }
        requireJsonText(graphResult, "schema_version", "room-graph-result.v1");
        requireJsonText(graphResult, "command_id", evidence.graphCommandId());
        requireJsonText(graphResult, "logical_run_id", evidence.logicalRunId());
        requireJsonText(graphResult, "attempt_id", evidence.attemptId());
        requireJsonText(graphResult, "graph_key", evidence.graphKey());
        requireJsonText(graphResult, "graph_version", evidence.graphVersion());
        requireJsonText(graphResult, "checkpoint_id", evidence.checkpointId());
        requireJsonText(graphResult, "output_hash", evidence.finalResultHash());
        JsonNode metadata = graphResult.get("execution_metadata");
        if (metadata == null || !metadata.isObject()) {
            throw new IntakeAuthorityInvariantException("graph result execution metadata is missing");
        }
        requireJsonText(metadata, "model_profile_id", evidence.modelProfileId());
        requireJsonText(metadata, "schema_version", evidence.attemptOutputSchemaVersion());
        requireJsonText(metadata, "policy_version", evidence.policyVersion());
        requireJsonText(metadata, "guardrail_version", evidence.guardrailVersion());
        ObjectNode graphWithoutHash = (ObjectNode) graphResult.deepCopy();
        graphWithoutHash.remove("output_hash");
        requireEquals(evidence.finalResultHash(), ContractJson.sha256Hex(graphWithoutHash),
                "graph result self hash");
        JsonNode operations = graphResult.get("artifact_operations");
        if (!(operations instanceof ArrayNode artifacts) || artifacts.size() != 1) {
            throw new IntakeAuthorityInvariantException("graph result must contain one artifact operation");
        }
        JsonNode artifactOperation = artifacts.get(0);
        requireJsonText(artifactOperation, "operation", "PROPOSE_PATCH");
        JsonNode proposal = artifactOperation.get("artifact");
        if (proposal == null || !proposal.isObject()) {
            throw new IntakeAuthorityInvariantException("graph proposal artifact is missing");
        }
        requireJsonText(proposal, "artifact_id", evidence.proposalArtifactId());
        requireJsonText(proposal, "schema_version", evidence.proposalSchemaVersion());
        requireJsonText(proposal, "uri", evidence.proposalUri());
        requireJsonText(proposal, "sha256", evidence.proposalHash());
        requireEquals(evidence.proposalOperation(), "PROPOSE_PATCH", "proposal operation");
        requireEquals(evidence.proposalSchemaVersion(), TURN_PROPOSAL_SCHEMA, "proposal schema");
        requireSha256(evidence.proposalHash(), "proposal hash");
        requireProposalSource(row, evidence.proposalArtifactId(), evidence.proposalUri(), evidence.proposalHash());

        JsonNode manifestInputs = parseJson(evidence.manifestInputsJson(), "manifest input references");
        if (!manifestInputs.isArray()) {
            throw new IntakeAuthorityInvariantException("manifest input references are not an array");
        }
        int proposalInputs = 0;
        for (JsonNode input : manifestInputs) {
            if (input.isObject() && TURN_PROPOSAL_SCHEMA.equals(input.path("schema_version").asText())) {
                proposalInputs++;
                requireJsonText(input, "artifact_id", evidence.proposalArtifactId());
                requireJsonText(input, "uri", evidence.proposalUri());
                requireJsonText(input, "sha256", evidence.proposalHash());
            }
        }
        if (proposalInputs > 1) {
            throw new IntakeAuthorityInvariantException("manifest contains duplicate proposal inputs");
        }

        JsonNode finalStream = parseJson(evidence.finalStreamPayloadJson(), "final stream event");
        requireEquals(evidence.finalStreamPayloadHash(), ContractJson.sha256Hex(finalStream),
                "final stream payload hash");
        requireJsonText(finalStream, "schema_version", "agent-stream.v3");
        requireJsonText(finalStream, "run_id", evidence.logicalRunId());
        requireJsonText(finalStream, "attempt_id", evidence.attemptId());
        requireJsonLong(finalStream, "sequence_no", evidence.finalStreamSequence());
        requireJsonText(finalStream, "event_type", "final");
        requireJsonText(finalStream, "audience", evidence.finalStreamAudience());
        JsonNode payload = finalStream.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new IntakeAuthorityInvariantException("final stream payload is missing");
        }
        requireJsonText(payload, "final_result_ref", evidence.outputUri());
        requireJsonText(payload, "final_result_hash", evidence.finalResultHash());
    }

    private static void requireProposalSource(
            EventRow row, String artifactId, String uri, String hash) {
        JsonNode sourceRefs = parseJson(row.sourceRefsJson(), "event source references");
        if (!sourceRefs.isArray()) {
            throw new IntakeAuthorityInvariantException("event source references are not an array");
        }
        int matches = 0;
        for (JsonNode source : sourceRefs) {
            if (source.isTextual() && source.asText().equals(artifactId)) {
                matches++;
            }
        }
        if (matches != 1 || artifactId == null || uri == null || hash == null) {
            throw new IntakeAuthorityInvariantException("event proposal source reference mismatch");
        }
    }

    private static void requireJsonText(JsonNode json, String field, String expected) {
        JsonNode value = json.get(field);
        if (value == null || !value.isTextual() || !Objects.equals(value.asText(), expected)) {
            throw new IntakeAuthorityInvariantException(field + " mismatch");
        }
    }

    private static void requireJsonLong(JsonNode json, String field, long expected) {
        JsonNode value = json.get(field);
        if (value == null || !value.isIntegralNumber() || value.asLong() != expected) {
            throw new IntakeAuthorityInvariantException(field + " mismatch");
        }
    }

    private static long jsonLongValue(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new IntakeAuthorityInvariantException(field + " is missing");
        }
        return value.asLong();
    }

    private static void requireNonNegativeJson(JsonNode json, String field) {
        if (jsonLongValue(json, field) < 0) {
            throw new IntakeAuthorityInvariantException(field + " must not be negative");
        }
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IntakeAuthorityInvariantException(field + " is not a lowercase SHA-256");
        }
    }

    private static JsonNode parseJson(String value, String label) {
        try {
            JsonNode json = MAPPER.readTree(value);
            if (json == null) {
                throw new IntakeAuthorityInvariantException(label + " is missing");
            }
            return json;
        } catch (IntakeAuthorityInvariantException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IntakeAuthorityInvariantException(label + " is malformed", exception);
        }
    }

    private static String eventRequestHash(JsonNode json) {
        JsonNode value = json.get("request_hash");
        if (value == null || !value.isTextual()) {
            throw new IntakeAuthorityInvariantException("event request hash is missing");
        }
        requireSha256(value.asText(), "event request hash");
        return value.asText();
    }

    private static String sha256Utf8(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IntakeAuthorityInvariantException("SHA-256 is unavailable", exception);
        }
    }

    private EventEvidence eventEvidence(
            Connection connection, EventRow row, IntakeDomainEventType eventType, JsonNode eventJson)
            throws SQLException {
        if (eventType == IntakeDomainEventType.TURN_NEEDS_INPUT
                || eventType == IntakeDomainEventType.TURN_READY_TO_CONFIRM) {
            TurnEvidenceRow evidence = exactlyOne(
                    connection,
                    TURN_EVENT_EVIDENCE_SQL,
                    "turn event durable evidence",
                     statement -> bindTurnEvidence(statement, row),
                     JdbcIntakeChildBridgeReadPort::mapTurnEvidence);
            requireTurnDurableEvidence(row, evidence);
            IntakeFinalizationReceipt receipt = requireTurnEventJson(eventJson, row, evidence);
            IntakeAgentRunRef agentRun = new IntakeAgentRunRef(
                    "intake-agent-run-ref.v1",
                    evidence.logicalRunId(),
                    evidence.attemptId(),
                    evidence.finalResultHash());
            IntakeGraphExecutionRef graph = new IntakeGraphExecutionRef(
                    "intake-graph-execution-ref.v1",
                    row.threadId(),
                    evidence.graphCommandId(),
                    evidence.graphKey(),
                     evidence.graphVersion(),
                     evidence.checkpointId(),
                     evidence.outputUri(),
                     evidence.outputHash(),
                     evidence.proposalUri(),
                     evidence.proposalHash());
            return new EventEvidence(
                    evidence.operationKey(),
                    evidence.operationRequestHash(),
                    evidence.finalResultHash(),
                    evidence.operationProcessRevision(),
                    receipt.roomRevision(),
                    row.authorityCommandId(),
                    agentRun,
                    graph);
        }
        BranchEvidenceRow evidence = exactlyOne(
                connection,
                BRANCH_EVENT_EVIDENCE_SQL,
                "branch event durable evidence",
                 statement -> bindBranchEvidence(statement, row),
                 JdbcIntakeChildBridgeReadPort::mapBranchEvidence);
        requireEquals(evidence.operationKey(), expectedBranchOperationKey(eventType, row),
                "branch operation key");
        requireBranchEventJson(eventJson, row, evidence, eventType);
        long committedProcessRevision = jsonLongValue(eventJson, "process_revision");
        long committedRoomRevision = jsonLongValue(eventJson, "room_revision");
        requireEquals(committedProcessRevision, evidence.operationProcessRevision() + 1,
                "branch committed process revision");
        requireEquals(committedRoomRevision, row.acceptedRoomRevision() + 1,
                "branch committed room revision");
        return new EventEvidence(
                evidence.operationKey(),
                evidence.operationRequestHash(),
                evidence.operationResultHash(),
                committedProcessRevision,
                committedRoomRevision,
                row.authorityCommandId(),
                null,
                null);
    }

    private static void bindTurnEvidence(PreparedStatement statement, EventRow row) throws SQLException {
        statement.setString(1, row.authorityCommandId());
        statement.setString(2, row.eventId());
        statement.setString(3, row.tenantSurrogate());
        statement.setString(4, row.caseId());
        statement.setLong(5, row.roomEpoch());
        statement.setLong(6, row.fencingToken());
        statement.setString(7, row.tenantSurrogate());
        statement.setString(8, row.caseId());
        statement.setLong(9, row.roomEpoch());
        statement.setLong(10, row.fencingToken());
    }

    private static void bindBranchEvidence(PreparedStatement statement, EventRow row) throws SQLException {
        statement.setString(1, row.eventId());
        statement.setString(2, row.tenantSurrogate());
        statement.setString(3, row.caseId());
        statement.setLong(4, row.roomEpoch());
        statement.setLong(5, row.fencingToken());
    }

    private <T> T inSnapshot(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(REQUIRED_ISOLATION);
            connection.setAutoCommit(false);
            try {
                T value = work.run(connection);
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
                throw new ReadUnavailableException("authority snapshot is temporarily unavailable", exception);
            }
            throw new IntakeAuthorityInvariantException("authority snapshot read failed", exception);
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
            // The original read failure remains the actionable error.
        }
    }

    private static <T> T exactlyOne(Connection connection, String sql, String tuple,
            Binder binder, Mapper<T> mapper) throws SQLException {
        List<T> rows = all(connection, sql, tuple, binder, mapper);
        if (rows.size() != 1) {
            throw new IntakeAuthorityInvariantException(tuple + " expected one row, found " + rows.size());
        }
        return rows.getFirst();
    }

    private static <T> List<T> all(Connection connection, String sql, String tuple,
            Binder binder, Mapper<T> mapper) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (result.next()) {
                    rows.add(mapper.map(result));
                    if (rows.size() > 2) {
                        throw new IntakeAuthorityInvariantException(tuple + " is ambiguous");
                    }
                }
                return rows;
            }
        }
    }

    private static EpochRow mapEpoch(ResultSet r) throws SQLException {
        return new EpochRow(r.getString("epoch_id"), r.getString("tenant_surrogate"), r.getString("case_id"),
                r.getString("room_type"), r.getLong("room_epoch"), r.getLong("fencing_token"),
                r.getString("selection_hash"), r.getString("writer_mode"), r.getString("case_workflow_type"),
                r.getString("case_workflow_build_id"), r.getString("room_workflow_type"),
                r.getString("room_workflow_build_id"), r.getString("process_contract_version"), r.getString("graph_key"),
                r.getString("graph_version"), r.getString("checkpoint_schema_version"), r.getString("state_schema_version"),
                r.getString("stream_protocol"), r.getString("prompt_version"), r.getString("model_profile_id"),
                r.getString("output_schema_version"), r.getString("policy_version"), r.getString("guardrail_version"),
                r.getString("tool_policy_version"), r.getString("cohort_policy_version"), r.getString("agent_key"),
                r.getString("agent_session_profile_version"), r.getString("memory_policy_id"));
    }

    private static BootstrapRow mapBootstrap(ResultSet r) throws SQLException {
        return new BootstrapRow(
                r.getString("id"),
                r.getString("epoch_id"),
                r.getString("tenant_surrogate"),
                r.getString("case_id"),
                r.getString("room_type"),
                r.getLong("room_epoch"),
                r.getLong("fencing_token"),
                r.getString("writer_mode"),
                r.getString("case_workflow_id"),
                r.getString("room_workflow_id"),
                r.getString("workflow_type"),
                r.getString("task_queue"),
                r.getString("update_id"),
                r.getString("payload_json"),
                r.getString("payload_sha256"),
                r.getString("outbox_status"));
    }

    private static PartyRow mapParty(ResultSet r) throws SQLException {
        return new PartyRow(r.getString("party"), r.getString("tenant_surrogate"), r.getString("case_id"),
                r.getString("session_tenant_id"), r.getString("session_case_id"), r.getString("room_type"),
                r.getLong("room_epoch"), r.getLong("fencing_token"), r.getString("registration_id"),
                r.getString("registration_hash"), r.getString("thread_id"), r.getString("actor_id"),
                r.getString("actor_role"), r.getString("audience"), r.getString("actor_scope_hash"),
                r.getString("access_session_id"), r.getString("permission_level"), r.getString("agent_session_id"),
                r.getString("agent_key"), r.getString("prompt_version"), r.getString("agent_session_profile_version"),
                r.getString("prompt_profile_id"), r.getString("memory_policy_id"), r.getString("graph_key"),
                r.getString("graph_version"), r.getString("checkpoint_schema_version"), r.getString("state_schema_version"),
                r.getString("registration_prompt_version"), r.getString("registration_model_profile_id"),
                r.getString("output_schema_version"), r.getString("policy_version"), r.getString("guardrail_version"),
                r.getString("tool_policy_version"), r.getString("writer_mode"));
    }

    private static CommandRow mapCommand(ResultSet r) throws SQLException {
        return new CommandRow(r.getString("command_id"), r.getLong("case_command_sequence"), r.getString("command_type"),
                r.getString("epoch_id"), r.getString("access_session_id"), r.getString("registration_id"),
                r.getString("tenant_surrogate"), r.getString("case_id"), r.getString("room_type"), r.getLong("room_epoch"),
                r.getLong("fencing_token"), r.getString("thread_id"), r.getString("actor_id"), r.getString("actor_role"),
                r.getString("actor_scope_hash"), r.getString("agent_session_id"), r.getString("payload_authority_id"),
                r.getString("request_hash"), r.getLong("accepted_room_revision"), r.getString("execution_disposition"),
                r.getString("content_sha256"), r.getString("source_kind"), r.getString("payload_schema_version"),
                r.getString("payload_uri"), r.getLong("payload_size_bytes"), r.getLong("expected_process_revision"),
                r.getString("party"), r.getString("case_workflow_type"),
                r.getString("case_workflow_build_id"), r.getString("room_workflow_type"),
                r.getString("room_workflow_build_id"));
    }

    private static EventRow mapEvent(ResultSet r) throws SQLException {
        return new EventRow(r.getString("binding_id"), r.getString("thread_registration_id"), r.getString("tenant_surrogate"),
                r.getString("case_id"), r.getString("room_type"), r.getLong("room_epoch"), r.getLong("fencing_token"),
                r.getString("thread_id"), r.getString("actor_scope_hash"), r.getString("agent_session_id"),
                r.getString("actor_audience"), r.getString("schema_version"), r.getString("artifact_id"),
                r.getString("object_uri"), r.getString("object_version"), r.getLong("size_bytes"),
                r.getString("event_id"), r.getLong("event_sequence"), r.getString("event_type"),
                r.getString("event_json"), r.getString("source_refs_json"), r.getString("party"),
                r.getString("actor_id"), r.getString("actor_role"), r.getString("authority_command_id"),
                r.getString("case_command_id"), r.getString("authority_request_hash"),
                r.getLong("accepted_room_revision"),
                r.getString("case_workflow_type"),
                r.getString("case_workflow_build_id"), r.getString("room_workflow_type"),
                r.getString("room_workflow_build_id"), r.getString("authority_source_kind"),
                r.getString("authority_payload_schema_version"), r.getString("authority_payload_uri"),
                r.getString("authority_payload_hash"), r.getLong("authority_payload_size_bytes"),
                r.getString("source_artifact_id"), r.getString("source_payload_uri"),
                r.getString("source_payload_hash"), r.getLong("source_payload_size_bytes"));
    }

    private static TurnEvidenceRow mapTurnEvidence(ResultSet r) throws SQLException {
        return new TurnEvidenceRow(
                r.getString("operation_key"),
                r.getString("operation_request_hash"),
                r.getString("operation_receipt_hash"),
                r.getLong("operation_process_revision"),
                r.getString("logical_run_id"),
                r.getString("committed_attempt_id"),
                r.getString("final_result_hash"),
                r.getString("attempt_id"),
                r.getString("graph_command_id"),
                r.getString("graph_command_request_hash"),
                r.getString("graph_command_json"),
                r.getString("graph_key"),
                r.getString("graph_version"),
                r.getString("checkpoint_schema_version"),
                r.getString("checkpoint_id"),
                r.getString("prompt_version"),
                r.getString("model_profile_id"),
                r.getString("attempt_output_schema_version"),
                r.getString("policy_version"),
                r.getString("guardrail_version"),
                r.getString("attempt_result_hash"),
                r.getString("graph_result_json"),
                r.getString("manifest_id"),
                r.getString("manifest_sha256"),
                r.getString("output_sha256"),
                r.getString("manifest_inputs_json"),
                r.getString("output_uri"),
                r.getString("output_hash"),
                r.getString("graph_output_schema_version"),
                r.getString("proposal_operation"),
                r.getString("proposal_artifact_id"),
                r.getString("proposal_schema_version"),
                r.getString("proposal_uri"),
                r.getString("proposal_hash"),
                r.getLong("final_stream_sequence_no"),
                r.getString("final_stream_audience"),
                r.getString("final_stream_payload_hash"),
                r.getString("final_stream_payload_json"));
    }

    private static BranchEvidenceRow mapBranchEvidence(ResultSet r) throws SQLException {
        return new BranchEvidenceRow(
                r.getString("operation_key"),
                r.getString("operation_request_hash"),
                r.getString("operation_result_hash"),
                r.getLong("operation_process_revision"));
    }

    private static ActiveChildBinding binding(EpochRow row) {
        return new ActiveChildBinding("active-intake-child-binding.v1", row.tenantSurrogate(), row.caseId(),
                row.roomEpoch(), row.fencingToken(), "room-epoch-selection.v2", row.caseWorkflowType(),
                row.caseWorkflowBuildId(), row.roomWorkflowType(), row.roomWorkflowBuildId());
    }

    private static ActiveChildBinding binding(CommandRow row) {
        // The command query binds the immutable epoch tuple; selection details are only used for checks.
        return new ActiveChildBinding("active-intake-child-binding.v1", row.tenantSurrogate(), row.caseId(),
                row.roomEpoch(), row.fencingToken(), "room-epoch-selection.v2",
                row.caseWorkflowType(), row.caseWorkflowBuildId(), row.roomWorkflowType(), row.roomWorkflowBuildId());
    }

    private static ActiveChildBinding binding(EventRow row) {
        return new ActiveChildBinding("active-intake-child-binding.v1", row.tenantSurrogate(), row.caseId(),
                row.roomEpoch(), row.fencingToken(), "room-epoch-selection.v2",
                row.caseWorkflowType(), row.caseWorkflowBuildId(), row.roomWorkflowType(), row.roomWorkflowBuildId());
    }

    private static PartyRow party(List<PartyRow> rows, IntakeParty party) {
        PartyRow match = null;
        for (PartyRow row : rows) {
            if (party.name().equals(row.party())) {
                if (match != null) {
                    throw new IntakeAuthorityInvariantException("duplicate " + party + " party");
                }
                match = row;
            }
        }
        if (match == null) {
            throw new IntakeAuthorityInvariantException("missing " + party + " party");
        }
        return match;
    }

    private static IntakeParty intakeParty(String party) {
        try { return IntakeParty.valueOf(party); }
        catch (RuntimeException exception) { throw new IntakeAuthorityInvariantException("invalid party", exception); }
    }

    private static ActorRole actorRole(String role) {
        try { return ActorRole.valueOf(role); }
        catch (RuntimeException exception) { throw new IntakeAuthorityInvariantException("invalid actor role", exception); }
    }

    private static void requirePartyRole(IntakeParty party, ActorRole role) {
        if ((party == IntakeParty.INITIATOR || party == IntakeParty.RESPONDENT)
                && role != ActorRole.USER && role != ActorRole.MERCHANT) {
            throw new IntakeAuthorityInvariantException("party authority actor role is not a participant");
        }
    }

    private static CommandType commandType(String value) {
        try {
            CommandType type = CommandType.valueOf(value);
            if (type != CommandType.INTAKE_MESSAGE && type != CommandType.INTAKE_CONFIRM && type != CommandType.INTAKE_CANCEL) {
                throw new IllegalArgumentException("unsupported Intake command");
            }
            return type;
        } catch (RuntimeException exception) {
            throw new IntakeAuthorityInvariantException("invalid Intake command type", exception);
        }
    }

    private static IntakeDomainEventType eventType(String value) {
        return switch (value) {
            case "TURN_NEEDS_INPUT", "INTAKE_TURN_NEEDS_INPUT" -> IntakeDomainEventType.TURN_NEEDS_INPUT;
            case "TURN_READY_TO_CONFIRM", "INTAKE_TURN_READY_TO_CONFIRM" -> IntakeDomainEventType.TURN_READY_TO_CONFIRM;
            case "INITIATOR_ACCEPTED", "INITIATOR_INTAKE_COMPLETED" -> IntakeDomainEventType.INITIATOR_ACCEPTED;
            case "NOT_ADMISSIBLE", "INTAKE_REJECTED" -> IntakeDomainEventType.NOT_ADMISSIBLE;
            case "CANCELLED", "INTAKE_CANCELLED" -> IntakeDomainEventType.CANCELLED;
            case "RESPONDENT_CONFIRMED", "RESPONDENT_INTAKE_COMPLETED" -> IntakeDomainEventType.RESPONDENT_CONFIRMED;
            default -> throw new IntakeAuthorityInvariantException("unsupported Intake event type");
        };
    }

    private static JsonNode parseEventJson(String value) {
        try { return MAPPER.readTree(value); }
        catch (Exception exception) { throw new IntakeAuthorityInvariantException("malformed event JSON", exception); }
    }

    private static String operationKey(String caseId, String commandId) { return "intake.operation:" + caseId + ":" + commandId; }
    private static void requireEquals(Object actual, Object expected, String field) {
        if (!Objects.equals(actual, expected)) throw new IntakeAuthorityInvariantException(field + " mismatch");
    }

    @FunctionalInterface private interface SqlWork<T> { T run(Connection connection) throws SQLException; }
    @FunctionalInterface private interface Binder { void bind(PreparedStatement statement) throws SQLException; }
    @FunctionalInterface private interface Mapper<T> { T map(ResultSet result) throws SQLException; }

    private record EpochRow(String epochId, String tenantSurrogate, String caseId, String roomType, long roomEpoch,
            long fencingToken, String selectionHash, String writerMode, String caseWorkflowType, String caseWorkflowBuildId,
            String roomWorkflowType, String roomWorkflowBuildId, String processContractVersion, String graphKey,
            String graphVersion, String checkpointSchemaVersion, String stateSchemaVersion, String streamProtocol,
            String promptVersion, String modelProfileId, String outputSchemaVersion, String policyVersion,
            String guardrailVersion, String toolPolicyVersion, String cohortPolicyVersion, String agentKey,
            String agentSessionProfileVersion, String memoryPolicyId) {}

    private record BootstrapRow(
            String id,
            String epochId,
            String tenantSurrogate,
            String caseId,
            String roomType,
            long roomEpoch,
            long fencingToken,
            String writerMode,
            String caseWorkflowId,
            String roomWorkflowId,
            String workflowType,
            String taskQueue,
            String updateId,
            String payloadJson,
            String payloadSha256,
            String outboxStatus) {}

    private record PartyRow(String party, String tenantSurrogate, String caseId, String sessionTenantId,
            String sessionCaseId, String roomType, long roomEpoch, long fencingToken, String registrationId,
            String registrationHash, String threadId, String actorId, String actorRole, String audience,
            String actorScopeHash, String accessSessionId, String permissionLevel, String agentSessionId,
            String agentKey, String promptVersion, String agentSessionProfileVersion, String promptProfileId,
            String memoryPolicyId, String graphKey, String graphVersion, String checkpointSchemaVersion,
            String stateSchemaVersion, String registrationPromptVersion, String registrationModelProfileId,
            String outputSchemaVersion, String policyVersion, String guardrailVersion, String toolPolicyVersion,
            String registrationWriterMode) {
        String modelProfileId() {
            return registrationModelProfileId;
        }
    }

    private record CommandRow(String commandId, long sequence, String commandType, String epochId, String accessSessionId,
            String registrationId, String tenantSurrogate, String caseId, String roomType, long roomEpoch,
            long fencingToken, String threadId, String actorId, String actorRole, String actorScopeHash,
            String agentSessionId, String payloadAuthorityId, String requestHash, long roomRevision,
            String executionDisposition, String payloadSha256, String sourceKind, String payloadSchemaVersion,
            String payloadUri, long payloadSizeBytes, long processRevision, String party,
            String caseWorkflowType, String caseWorkflowBuildId, String roomWorkflowType,
            String roomWorkflowBuildId) {}

    private record EventRow(String bindingId, String registrationId, String tenantSurrogate, String caseId,
            String roomType, long roomEpoch, long fencingToken, String threadId, String actorScopeHash,
            String agentSessionId, String actorAudience, String schemaVersion, String artifactId, String objectUri,
            String objectVersion, long sizeBytes, String eventId, long sequence, String eventType,
            String eventJson, String sourceRefsJson, String party, String actorId, String actorRole,
            String authorityCommandId, String caseCommandId, String authorityRequestHash,
            long acceptedRoomRevision, String caseWorkflowType, String caseWorkflowBuildId,
            String roomWorkflowType, String roomWorkflowBuildId, String authoritySourceKind,
            String authorityPayloadSchemaVersion, String authorityPayloadUri, String authorityPayloadHash,
            long authorityPayloadSizeBytes, String sourceArtifactId, String sourcePayloadUri,
            String sourcePayloadHash, long sourcePayloadSizeBytes) {}

    private record TurnEvidenceRow(
            String operationKey,
            String operationRequestHash,
            String operationReceiptHash,
            long operationProcessRevision,
            String logicalRunId,
            String committedAttemptId,
            String finalResultHash,
            String attemptId,
            String graphCommandId,
            String graphCommandRequestHash,
            String graphCommandJson,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String checkpointId,
            String promptVersion,
            String modelProfileId,
            String attemptOutputSchemaVersion,
            String policyVersion,
            String guardrailVersion,
            String attemptResultHash,
            String graphResultJson,
            String manifestId,
            String manifestHash,
            String outputSha256,
            String manifestInputsJson,
            String outputUri,
            String outputHash,
            String graphOutputSchemaVersion,
            String proposalOperation,
            String proposalArtifactId,
            String proposalSchemaVersion,
            String proposalUri,
            String proposalHash,
            long finalStreamSequence,
            String finalStreamAudience,
            String finalStreamPayloadHash,
            String finalStreamPayloadJson) {}

    private record BranchEvidenceRow(
            String operationKey,
            String operationRequestHash,
            String operationResultHash,
            long operationProcessRevision) {}

    private record EventEvidence(
            String operationKey,
            String requestHash,
            String resultHash,
            long processRevision,
            long roomRevision,
            String commandId,
            IntakeAgentRunRef agentRunRef,
            IntakeGraphExecutionRef graphExecutionRef) {}
}
