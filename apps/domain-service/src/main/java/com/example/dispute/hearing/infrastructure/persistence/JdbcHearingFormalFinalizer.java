package com.example.dispute.hearing.infrastructure.persistence;

import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.domain.HearingAuthorityLedger;
import com.example.dispute.hearing.domain.HearingAuthorityRejectedException;
import com.example.dispute.hearing.domain.HearingDomainReceipt;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFormalCommitResult;
import com.example.dispute.hearing.domain.HearingFormalFinalizer;
import com.example.dispute.hearing.domain.HearingFormalTransition;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Formal fact adapter layered on the single Hearing authority ledger. It intentionally has no
 * component annotation and therefore cannot create a formal runtime sink by classpath discovery.
 */
public final class JdbcHearingFormalFinalizer implements HearingFormalFinalizer {

    private final NamedParameterJdbcTemplate jdbc;
    private final HearingAuthorityLedger authorityLedger;

    public JdbcHearingFormalFinalizer(
            NamedParameterJdbcTemplate jdbc, HearingAuthorityLedger authorityLedger) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.authorityLedger = Objects.requireNonNull(authorityLedger, "authorityLedger");
    }

    @Override
    public HearingDomainReceipt appendAction(ActionCommand command) {
        Objects.requireNonNull(command, "command");
        return authorityLedger.commitOrReplay(command.authorityCommit(), () -> {
            MapSqlParameterSource parameters = common(
                            command.authorityCommit().authority(),
                            command.transition(),
                            command.authorityCommit().committedAt())
                    .addValue("actionId", command.actionId())
                    .addValue("actionType", command.actionType().name())
                    .addValue("schemaVersion", command.schemaVersion())
                    .addValue("participantId", command.participantId())
                    .addValue("participantRole", command.participantRole())
                    .addValue("submissionStatus", command.submissionStatus() == null
                            ? null
                            : command.submissionStatus().name())
                    .addValue("payloadJson", command.payloadJson())
                    .addValue("contentHash", command.contentHash())
                    .addValue("agentRunId", command.agentRunId());
            requireSourceStage(
                    command.authorityCommit().authority(),
                    command.transition(),
                    command.agentRunId(),
                    command.authorityCommit().committedAt());
            if (command.actionType().isPartyAction()) {
                requireParty(command);
            } else {
                requireTerminalAgentRun(
                        command.authorityCommit().authority(),
                        command.agentRunId(),
                        command.agentResultHash());
            }
            requireUpdated(
                    jdbc.update(
                            """
                            insert into hearing_flow_action (
                                id, flow_instance_id, stage_id, case_id, action_type,
                                schema_version, participant_id, participant_role,
                                submission_status, payload_json, content_hash, agent_run_id,
                                created_at, created_by
                            ) values (
                                :actionId, :flowId, :sourceStageId, :caseId, :actionType,
                                :schemaVersion, :participantId, :participantRole,
                                :submissionStatus, cast(:payloadJson as jsonb), :contentHash,
                                :agentRunId, :committedAt, :actorId
                            )
                            """,
                            parameters),
                    "HEARING_ACTION_INSERT_FAILED");
            applyTransition(
                    command.authorityCommit().authority(),
                    command.transition(),
                    command.authorityCommit().committedAt());
            return result(
                    command.authorityCommit().authority(),
                    command.transition(),
                    "urn:hearing:action:" + command.actionId(),
                    command.contentHash());
        });
    }

    @Override
    public HearingDomainReceipt adoptPartyAction(AdoptPartyActionCommand command) {
        Objects.requireNonNull(command, "command");
        return authorityLedger.commitOrReplay(command.authorityCommit(), () -> {
            HearingAuthorityExpectation authority = command.authorityCommit().authority();
            requireSourceStage(authority, command.transition(), null, command.authorityCommit().committedAt());
            MapSqlParameterSource parameters = common(authority, command.transition(), command.authorityCommit().committedAt())
                    .addValue("actionId", command.actionId()).addValue("actionType", command.actionType().name())
                    .addValue("schemaVersion", command.schemaVersion()).addValue("participantId", command.participantId())
                    .addValue("participantRole", command.participantRole()).addValue("submissionStatus", command.submissionStatus().name())
                    .addValue("payloadJson", command.payloadJson()).addValue("contentHash", command.contentHash());
            requireLockedPartyAction(parameters);
            if (command.transition().advances()) {
                String statuses = command.actionType()
                                == com.example.dispute.hearing.domain.HearingFlowActionType.ANSWER_BUNDLE
                        ? "and submission_status = 'SUBMITTED'"
                        : "and submission_status in ('SUBMITTED','AUTO_TIMEOUT')";
                requireCount(("""
                    select count(*) from hearing_flow_action where flow_instance_id = :flowId and stage_id = :sourceStageId
                     and case_id = :caseId and action_type = :actionType
                    """ + statuses), parameters, 2, "HEARING_PARTY_ACTIONS_NOT_TERMINAL");
            }
            applyTransition(authority, command.transition(), command.authorityCommit().committedAt());
            return result(authority, command.transition(), "urn:hearing:party-action:" + command.actionId(), command.contentHash());
        });
    }

    private void requireLockedPartyAction(MapSqlParameterSource parameters) {
        var rows = jdbc.queryForList("""
                select id from hearing_flow_action where id = :actionId and flow_instance_id = :flowId
                 and stage_id = :sourceStageId and case_id = :caseId and action_type = :actionType
                 and schema_version = :schemaVersion and participant_id = :participantId
                 and participant_role = :participantRole and submission_status = :submissionStatus
                 and payload_json = cast(:payloadJson as jsonb) and content_hash = :contentHash and agent_run_id is null
                for update
                """, parameters);
        if (rows.size() != 1) {
            throw rejected("HEARING_PARTY_ACTION_NOT_EXACT");
        }
    }

    @Override
    public HearingDomainReceipt advanceStage(StageCommand command) {
        Objects.requireNonNull(command, "command");
        return authorityLedger.commitOrReplay(command.authorityCommit(), () -> {
            HearingAuthorityExpectation authority = command.authorityCommit().authority();
            requireSourceStage(authority, command.transition(), null, command.authorityCommit().committedAt());
            applyTransition(authority, command.transition(), command.authorityCommit().committedAt());
            return result(authority, command.transition(),
                    "urn:hearing:stage:" + authority.stageSequence() + ':' + authority.stage().name(),
                    command.stageOutputHash());
        });
    }

    @Override
    public HearingDomainReceipt finalizeMatrixSynthesis(MatrixSynthesisCommand command) {
        Objects.requireNonNull(command, "command");
        return authorityLedger.commitOrReplay(command.authorityCommit(), () -> {
            HearingAuthorityExpectation authority = command.authorityCommit().authority();
            requireSourceStage(authority, command.transition(), command.agentRunId(), command.authorityCommit().committedAt());
            requireTerminalAgentRun(authority, command.agentRunId(), command.agentResultHash());
            if (command.matrixKind() == MatrixKind.INTAKE) {
                persistIntakeIssueState(command);
            }
            applyTransition(authority, command.transition(), command.authorityCommit().committedAt());
            return result(authority, command.transition(),
                    "urn:hearing:matrix:" + command.matrixKind().name().toLowerCase() + ':' + command.agentRunId(),
                    command.contentHash());
        });
    }

    private void persistIntakeIssueState(MatrixSynthesisCommand command) {
        HearingAuthorityExpectation authority = command.authorityCommit().authority();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("caseId", authority.caseId())
                .addValue("flowId", authority.flowInstanceId())
                .addValue("sourceStageId", command.transition().sourceStageId())
                .addValue("agentRunId", command.agentRunId())
                .addValue("payloadJson", command.payloadJson())
                .addValue("committedAt", offset(command.authorityCommit().committedAt()))
                .addValue("actorId", command.actorId());
        requireOne(
                """
                select count(*)
                  from hearing_flow_action question_set
                  join case_intake_dossier dossier
                    on dossier.case_id = question_set.case_id
                   and dossier.room_type = 'INTAKE'
                 where question_set.flow_instance_id = :flowId
                   and question_set.case_id = :caseId
                   and question_set.action_type = 'QUESTION_SET'
                   and question_set.schema_version = 'hearing_question_set.v4'
                   and question_set.id = cast(:payloadJson as jsonb)
                       #>> '{issue_transition_set,question_set_id}'
                   and question_set.content_hash = cast(:payloadJson as jsonb)
                       #>> '{issue_transition_set,question_set_hash}'
                   and question_set.payload_json ->> 'question_set_id' = question_set.id
                   and question_set.payload_json ->> 'question_set_hash' = question_set.content_hash
                   and dossier.dossier_json #>> '{case_fact_matrix,matrix_id}' =
                       cast(:payloadJson as jsonb) #>> '{case_fact_matrix,parent_ref,matrix_id}'
                   and dossier.dossier_json #>> '{case_fact_matrix,matrix_version}' =
                       cast(:payloadJson as jsonb) #>> '{case_fact_matrix,parent_ref,matrix_version}'
                   and dossier.dossier_json #>> '{case_fact_matrix,content_hash}' =
                       cast(:payloadJson as jsonb) #>> '{case_fact_matrix,parent_ref,content_hash}'
                   and (
                       select count(*)
                         from jsonb_array_elements_text(
                                  cast(:payloadJson as jsonb)
                                      #> '{issue_transition_set,answer_bundle_ids}')
                              with ordinality bundle_id(value, ordinal)
                         join jsonb_array_elements_text(
                                  cast(:payloadJson as jsonb)
                                      #> '{issue_transition_set,answer_bundle_hashes}')
                              with ordinality bundle_hash(value, ordinal)
                           on bundle_hash.ordinal = bundle_id.ordinal
                         join hearing_flow_action answer
                           on answer.flow_instance_id = question_set.flow_instance_id
                          and answer.case_id = question_set.case_id
                          and answer.action_type = 'ANSWER_BUNDLE'
                          and answer.schema_version = 'hearing_answer_bundle.v4'
                          and answer.submission_status = 'SUBMITTED'
                          and answer.id = bundle_id.value
                          and answer.content_hash = bundle_hash.value
                          and answer.payload_json ->> 'answer_bundle_id' = answer.id
                          and answer.payload_json ->> 'answer_bundle_hash' = answer.content_hash
                          and answer.payload_json ->> 'question_set_id' = question_set.id
                          and answer.payload_json ->> 'question_set_hash' = question_set.content_hash
                          and answer.participant_role = case bundle_id.ordinal
                              when 1 then 'USER' when 2 then 'MERCHANT' end
                   ) = 2
                """,
                parameters,
                "HEARING_INTAKE_V4_PARENTS_NOT_EXACT");
        requireUpdated(
                jdbc.update(
                        """
                        insert into hearing_issue_state_set (
                            id, case_id, flow_instance_id, source_stage_id, agent_run_id,
                            schema_version, transition_set_id, transition_hash,
                            question_set_id, question_set_hash,
                            user_answer_bundle_id, user_answer_bundle_hash,
                            merchant_answer_bundle_id, merchant_answer_bundle_hash,
                            matrix_id, matrix_version, matrix_hash,
                            payload_json, content_hash, created_at, created_by
                        ) values (
                            cast(:payloadJson as jsonb) #>> '{issue_state_set,issue_state_set_id}',
                            :caseId, :flowId, :sourceStageId, :agentRunId,
                            'hearing_issue_state_set.v4',
                            cast(:payloadJson as jsonb) #>> '{issue_state_set,transition_set_id}',
                            cast(:payloadJson as jsonb) #>> '{issue_state_set,transition_hash}',
                            cast(:payloadJson as jsonb) #>> '{issue_state_set,question_set_id}',
                            cast(:payloadJson as jsonb) #>> '{issue_state_set,question_set_hash}',
                            cast(:payloadJson as jsonb) #>> '{issue_state_set,answer_bundle_ids,0}',
                            cast(:payloadJson as jsonb) #>> '{issue_state_set,answer_bundle_hashes,0}',
                            cast(:payloadJson as jsonb) #>> '{issue_state_set,answer_bundle_ids,1}',
                            cast(:payloadJson as jsonb) #>> '{issue_state_set,answer_bundle_hashes,1}',
                            cast(:payloadJson as jsonb) #>> '{issue_state_set,matrix_id}',
                            (cast(:payloadJson as jsonb) #>> '{issue_state_set,matrix_version}')::integer,
                            cast(:payloadJson as jsonb) #>> '{issue_state_set,matrix_hash}',
                            cast(:payloadJson as jsonb) -> 'issue_state_set',
                            cast(:payloadJson as jsonb) #>> '{issue_state_set,content_hash}',
                            :committedAt, :actorId
                        )
                        """,
                        parameters),
                "HEARING_ISSUE_STATE_INSERT_FAILED");
    }

    @Override
    public HearingDomainReceipt freezeDossier(DossierCommand command) {
        Objects.requireNonNull(command, "command");
        return authorityLedger.commitOrReplay(command.authorityCommit(), () -> {
            HearingAuthorityExpectation authority = command.authorityCommit().authority();
            requireSourceStage(
                    authority,
                    command.transition(),
                    null,
                    command.authorityCommit().committedAt());
            MapSqlParameterSource parameters = common(
                            authority, command.transition(), command.authorityCommit().committedAt())
                    .addValue("dossierId", command.dossierId())
                    .addValue("caseMatrixVersion", command.caseMatrixVersion())
                    .addValue("caseMatrixHash", command.caseMatrixHash())
                    .addValue("evidenceMatrixVersion", command.evidenceMatrixVersion())
                    .addValue("evidenceMatrixHash", command.evidenceMatrixHash())
                    .addValue("questionSetId", command.questionSetId())
                    .addValue("requestSetId", command.requestSetId())
                    .addValue("payloadJson", command.payloadJson())
                    .addValue("contentHash", command.contentHash());
            requireDossierSources(parameters);
            requireUpdated(
                    jdbc.update(
                            """
                            insert into hearing_trial_dossier (
                                id, case_id, flow_instance_id, schema_version,
                                case_matrix_version, case_matrix_hash,
                                evidence_matrix_version, evidence_matrix_hash,
                                question_set_id, request_set_id, payload_json, content_hash,
                                frozen_at, created_at, created_by
                            ) values (
                                :dossierId, :caseId, :flowId, 'trial_dossier.v2',
                                :caseMatrixVersion, :caseMatrixHash,
                                :evidenceMatrixVersion, :evidenceMatrixHash,
                                :questionSetId, :requestSetId, cast(:payloadJson as jsonb),
                                :contentHash, :committedAt, :committedAt, :actorId
                            )
                            """,
                            parameters),
                    "HEARING_DOSSIER_INSERT_FAILED");
            applyTransition(authority, command.transition(), command.authorityCommit().committedAt());
            return result(
                    authority,
                    command.transition(),
                    "urn:hearing:dossier:" + command.dossierId(),
                    command.contentHash());
        });
    }

    @Override
    public HearingDomainReceipt finalizeJudgeV1(DecisionCommand command) {
        requireDecisionType(command, HearingArtifactType.JUDGE_PROPOSAL);
        return finalizeDecision(command);
    }

    @Override
    public HearingDomainReceipt finalizeJuryReview(DecisionCommand command) {
        requireDecisionType(command, HearingArtifactType.JURY_REVIEW_REPORT);
        return finalizeDecision(command);
    }

    @Override
    public HearingDomainReceipt finalizeJudgeV2(DecisionCommand command) {
        requireDecisionType(command, HearingArtifactType.ADJUDICATION_DRAFT);
        return finalizeDecision(command);
    }

    @Override
    public HearingDomainReceipt commitHandoff(HandoffCommand command) {
        Objects.requireNonNull(command, "command");
        return authorityLedger.commitOrReplay(command.authorityCommit(), () -> {
            HearingAuthorityExpectation authority = command.authorityCommit().authority();
            requireSourceStage(
                    authority,
                    command.transition(),
                    null,
                    command.authorityCommit().committedAt());
            MapSqlParameterSource parameters = common(
                            authority, command.transition(), command.authorityCommit().committedAt())
                    .addValue("handoffId", command.handoffId())
                    .addValue("dossierId", command.dossierId())
                    .addValue("dossierHash", command.dossierHash())
                    .addValue("proposalId", command.proposalId())
                    .addValue("proposalHash", command.proposalHash())
                    .addValue("reportId", command.reportId())
                    .addValue("reportHash", command.reportHash())
                    .addValue("judgeV2Id", command.judgeV2Id())
                    .addValue("judgeV2Hash", command.judgeV2Hash())
                    .addValue("reviewTaskId", command.reviewTaskId())
                    .addValue("reviewPacketId", command.reviewPacketId())
                    .addValue("handoffHash", command.handoffHash());
            requireExactDecisionChain(parameters);
            requireOne(
                    """
                    select count(*)
                      from review_task task
                      join review_packet packet
                        on packet.id = task.packet_id
                       and packet.case_id = task.case_id
                     where task.id = :reviewTaskId
                       and task.packet_id = :reviewPacketId
                       and task.case_id = :caseId
                    """,
                    parameters,
                    "HEARING_REVIEW_TASK_NOT_EXACT");
            requireUpdated(
                    jdbc.update(
                            """
                            insert into hearing_review_handoff_fact (
                                id, case_id, flow_instance_id, trial_dossier_id,
                                trial_dossier_hash, proposal_id, proposal_content_hash,
                                report_id, report_content_hash, judge_v2_id,
                                judge_v2_hash, review_task_id, review_packet_id,
                                content_hash, created_at, created_by
                            ) values (
                                :handoffId, :caseId, :flowId, :dossierId,
                                :dossierHash, :proposalId, :proposalHash,
                                :reportId, :reportHash, :judgeV2Id,
                                :judgeV2Hash, :reviewTaskId, :reviewPacketId,
                                :handoffHash, :committedAt, :actorId
                            )
                            """,
                            parameters),
                    "HEARING_HANDOFF_INSERT_FAILED");
            return result(
                    authority,
                    command.transition(),
                    "urn:hearing:handoff:" + command.handoffId(),
                    command.handoffHash());
        });
    }

    @Override
    public HearingDomainReceipt commitClosure(ClosureCommand command) {
        Objects.requireNonNull(command, "command");
        return authorityLedger.commitOrReplay(command.authorityCommit(), () -> {
            HearingAuthorityExpectation authority = command.authorityCommit().authority();
            requireSourceStage(
                    authority,
                    command.transition(),
                    null,
                    command.authorityCommit().committedAt());
            MapSqlParameterSource parameters = common(
                            authority, command.transition(), command.authorityCommit().committedAt())
                    .addValue("closureId", command.closureId())
                    .addValue("handoffId", command.handoffId())
                    .addValue("handoffReceiptId", command.handoffReceiptId())
                    .addValue("handoffReceiptHash", command.handoffReceiptHash())
                    .addValue("closureHash", command.closureHash());
            requireOne(
                    """
                    select count(*)
                      from hearing_review_handoff_fact handoff
                      join hearing_domain_receipt receipt
                        on receipt.receipt_id = :handoffReceiptId
                       and receipt.receipt_hash = :handoffReceiptHash
                       and receipt.operation_type = 'HANDOFF'
                       and receipt.case_id = handoff.case_id
                       and receipt.flow_instance_id = handoff.flow_instance_id
                       and receipt.epoch_id = :epochId
                       and receipt.result_ref = 'urn:hearing:handoff:' || handoff.id
                       and receipt.result_hash = handoff.content_hash
                     where handoff.id = :handoffId
                       and handoff.case_id = :caseId
                       and handoff.flow_instance_id = :flowId
                    """,
                    parameters,
                    "HEARING_HANDOFF_RECEIPT_NOT_EXACT");
            requireUpdated(
                    jdbc.update(
                            """
                            insert into hearing_closure_fact (
                                id, case_id, flow_instance_id, handoff_id,
                                handoff_receipt_id, handoff_receipt_hash,
                                content_hash, closed_at, created_by
                            ) values (
                                :closureId, :caseId, :flowId, :handoffId,
                                :handoffReceiptId, :handoffReceiptHash,
                                :closureHash, :committedAt, :actorId
                            )
                            """,
                            parameters),
                    "HEARING_CLOSURE_INSERT_FAILED");
            applyTransition(authority, command.transition(), command.authorityCommit().committedAt());
            return result(
                    authority,
                    command.transition(),
                    "urn:hearing:closure:" + command.closureId(),
                    command.closureHash());
        });
    }

    private HearingDomainReceipt finalizeDecision(DecisionCommand command) {
        Objects.requireNonNull(command, "command");
        return authorityLedger.commitOrReplay(command.authorityCommit(), () -> {
            HearingAuthorityExpectation authority = command.authorityCommit().authority();
            requireSourceStage(
                    authority,
                    command.transition(),
                    command.agentRunId(),
                    command.authorityCommit().committedAt());
            requireTerminalAgentRun(authority, command.agentRunId(), command.agentResultHash());
            MapSqlParameterSource parameters = common(
                            authority, command.transition(), command.authorityCommit().committedAt())
                    .addValue("artifactId", command.artifactId())
                    .addValue("artifactType", command.artifactType().name())
                    .addValue("schemaVersion", command.artifactType().schemaVersion())
                    .addValue("contentHash", command.contentHash())
                    .addValue("dossierId", command.dossierId())
                    .addValue("dossierHash", command.dossierHash())
                    .addValue("proposalId", command.proposalId())
                    .addValue("proposalHash", command.proposalHash())
                    .addValue("reportId", command.reportId())
                    .addValue("reportHash", command.reportHash())
                    .addValue("payloadJson", command.payloadJson())
                    .addValue("agentRunId", command.agentRunId());
            requireDossier(parameters);
            if (command.artifactType() != HearingArtifactType.JUDGE_PROPOSAL) {
                requireProposal(parameters);
            }
            if (command.artifactType() == HearingArtifactType.ADJUDICATION_DRAFT) {
                requireReport(parameters);
            }
            requireUpdated(
                    jdbc.update(
                            """
                            insert into hearing_flow_artifact (
                                id, case_id, flow_instance_id, trial_dossier_id,
                                trial_dossier_hash, artifact_type, schema_version,
                                proposal_id, proposal_content_hash, report_id,
                                report_content_hash, content_hash, payload_json,
                                agent_run_id, created_at, created_by
                            ) values (
                                :artifactId, :caseId, :flowId, :dossierId,
                                :dossierHash, :artifactType, :schemaVersion,
                                :proposalId, :proposalHash, :reportId,
                                :reportHash, :contentHash, cast(:payloadJson as jsonb),
                                :agentRunId, :committedAt, :actorId
                            )
                            """,
                            parameters),
                    "HEARING_DECISION_ARTIFACT_INSERT_FAILED");
            applyTransition(authority, command.transition(), command.authorityCommit().committedAt());
            return result(
                    authority,
                    command.transition(),
                    "urn:hearing:artifact:" + command.artifactId(),
                    command.contentHash());
        });
    }

    private void requireSourceStage(
            HearingAuthorityExpectation authority,
            HearingFormalTransition transition,
            String expectedAgentRunId,
            Instant committedAt) {
        MapSqlParameterSource parameters = common(authority, transition, committedAt)
                .addValue("agentRunId", expectedAgentRunId);
        String agentRunPredicate = expectedAgentRunId == null
                ? "and agent_run_id is null"
                : "and agent_run_id = :agentRunId";
        requireOne(
                ("""
                select count(*)
                  from hearing_flow_stage
                 where id = :sourceStageId
                   and flow_instance_id = :flowId
                   and case_id = :caseId
                   and stage_code = :sourceStage
                   and stage_sequence = :sourceStageSequence
                   and stage_status = :expectedSourceStatus
                """ + agentRunPredicate),
                parameters,
                "HEARING_SOURCE_STAGE_NOT_EXACT");
    }

    private void requireParty(ActionCommand command) {
        String partyColumn = "USER".equals(command.participantRole()) ? "user_id" : "merchant_id";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("caseId", command.authorityCommit().authority().caseId())
                .addValue("participantId", command.participantId());
        requireOne(
                "select count(*) from fulfillment_dispute_case where id = :caseId and "
                        + partyColumn + " = :participantId",
                parameters,
                "HEARING_PARTY_NOT_AUTHORIZED");
    }

    private void requireTerminalAgentRun(
            HearingAuthorityExpectation authority, String agentRunId, String resultHash) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenant", authority.tenantSurrogate())
                .addValue("caseId", authority.caseId())
                .addValue("agentRunId", agentRunId)
                .addValue("resultHash", resultHash)
                .addValue("epochId", authority.epochId())
                .addValue("roomEpoch", authority.roomEpoch())
                .addValue("processRevision", authority.processRevision())
                .addValue("fencingToken", authority.fencingToken())
                .addValue("executorKind", authority.writerMode() == com.example.dispute.hearing.domain.HearingWriterMode.TEMPORAL
                        ? "TEMPORAL_ACTIVITY" : "LEGACY_WORKER");
        switch (authority.writerMode()) {
            case TEMPORAL -> requireOne(
                    """
                    select count(*)
                      from agent_run run
                      join agent_run_attempt attempt
                        on attempt.agent_run_id = run.id
                     where run.id = :agentRunId
                       and run.case_id = :caseId
                       and run.tenant_surrogate = :tenant
                       and run.room_epoch_id = :epochId
                       and run.room_type = 'HEARING'
                       and run.room_epoch = :roomEpoch
                       and run.process_revision = :processRevision
                       and run.fencing_token = :fencingToken
                       and run.protocol = 'agent-stream.v3'
                       and run.executor_kind = :executorKind
                       and attempt.executor_kind = :executorKind
                       and run.result_ready_attempt_id = attempt.id
                       and run.final_result_hash = :resultHash
                       and attempt.result_hash = run.final_result_hash
                       and attempt.final_frame_observed = true
                       and attempt.completed_at is not null
                       and (
                            (run.run_status = 'RESULT_READY'
                             and run.finalization_status = 'UNCOMMITTED'
                             and run.committed_attempt_id is null
                             and attempt.attempt_status = 'RESULT_READY')
                         or (run.run_status = 'COMPLETED'
                             and run.finalization_status = 'COMMITTED'
                             and run.committed_attempt_id = attempt.id
                             and attempt.attempt_status = 'COMPLETED')
                       )
                    """,
                    parameters,
                    "HEARING_AGENT_RUN_NOT_TERMINAL");
            case LEGACY -> requireOne(
                    """
                    select count(*)
                      from agent_run
                     where id = :agentRunId
                       and case_id = :caseId
                       and tenant_surrogate = :tenant
                       and room_epoch_id = :epochId
                       and room_type = 'HEARING'
                       and room_epoch = :roomEpoch
                       and process_revision = :processRevision
                       and fencing_token = :fencingToken
                       and executor_kind = :executorKind
                       and run_status = 'COMPLETED'
                       and final_result_hash = :resultHash
                    """,
                    parameters,
                    "HEARING_AGENT_RUN_NOT_TERMINAL");
            case SHADOW -> throw rejected("HEARING_AGENT_RUN_NOT_TERMINAL");
        }
    }

    private void requireDossier(MapSqlParameterSource parameters) {
        requireOne(
                """
                select count(*)
                  from hearing_trial_dossier
                 where id = :dossierId
                   and case_id = :caseId
                   and flow_instance_id = :flowId
                   and schema_version = 'trial_dossier.v2'
                   and content_hash = :dossierHash
                """,
                parameters,
                "HEARING_DOSSIER_PARENT_NOT_EXACT");
    }

    private void requireDossierSources(MapSqlParameterSource parameters) {
        requireOne(
                """
                select count(*)
                  from hearing_flow_stage case_matrix_stage
                  join hearing_flow_stage evidence_matrix_stage
                    on evidence_matrix_stage.flow_instance_id = case_matrix_stage.flow_instance_id
                   and evidence_matrix_stage.case_id = case_matrix_stage.case_id
                   and evidence_matrix_stage.stage_code = 'EVIDENCE_SYNTHESIZING'
                   and evidence_matrix_stage.stage_status = 'COMPLETED'
                  join hearing_flow_action question_set
                    on question_set.flow_instance_id = case_matrix_stage.flow_instance_id
                   and question_set.case_id = case_matrix_stage.case_id
                   and question_set.action_type = 'QUESTION_SET'
                  join hearing_flow_action request_set
                    on request_set.flow_instance_id = question_set.flow_instance_id
                   and request_set.case_id = question_set.case_id
                   and request_set.action_type = 'EVIDENCE_REQUEST_SET'
                 where case_matrix_stage.flow_instance_id = :flowId
                   and case_matrix_stage.case_id = :caseId
                   and case_matrix_stage.stage_code = 'INTAKE_SYNTHESIZING'
                   and case_matrix_stage.stage_status = 'COMPLETED'
                   and case_matrix_stage.output_json -> 'case_fact_matrix'
                       = cast(:payloadJson as jsonb) -> 'case_fact_matrix'
                   and evidence_matrix_stage.output_json -> 'fact_evidence_matrix'
                       = cast(:payloadJson as jsonb) -> 'fact_evidence_matrix'
                   and cast(:payloadJson as jsonb) ->> 'case_matrix_hash' = :caseMatrixHash
                   and (cast(:payloadJson as jsonb) ->> 'case_matrix_version')::integer
                       = :caseMatrixVersion
                   and cast(:payloadJson as jsonb) ->> 'evidence_matrix_hash'
                       = :evidenceMatrixHash
                   and (cast(:payloadJson as jsonb) ->> 'evidence_matrix_version')::integer
                       = :evidenceMatrixVersion
                   and question_set.payload_json ->> 'question_set_id' = :questionSetId
                   and request_set.payload_json ->> 'request_set_id' = :requestSetId
                   and (
                       select count(*)
                         from hearing_flow_action answer
                        where answer.flow_instance_id = :flowId
                          and answer.case_id = :caseId
                          and answer.action_type = 'ANSWER_BUNDLE'
                          and answer.submission_status = 'SUBMITTED'
                   ) = 2
                   and (
                       select count(*)
                         from hearing_flow_action evidence
                        where evidence.flow_instance_id = :flowId
                          and evidence.case_id = :caseId
                          and evidence.action_type = 'EVIDENCE_BATCH'
                          and evidence.submission_status in ('SUBMITTED', 'AUTO_TIMEOUT')
                   ) = 2
                   and jsonb_typeof(cast(:payloadJson as jsonb) -> 'adjudication_rules')
                       = 'array'
                   and jsonb_array_length(
                       cast(:payloadJson as jsonb) -> 'adjudication_rules') > 0
                   and jsonb_array_length(
                       cast(:payloadJson as jsonb) -> 'adjudication_rules') = (
                       select count(*)
                         from policy_rule rule
                        where rule.rule_status = 'ACTIVE'
                          and rule.deleted_at is null
                          and rule.effective_from <= :committedAt
                          and (rule.effective_to is null or rule.effective_to > :committedAt)
                   )
                   and (
                       select count(distinct snapshot ->> 'policy_id')
                         from jsonb_array_elements(
                             cast(:payloadJson as jsonb) -> 'adjudication_rules') snapshot
                   ) = jsonb_array_length(
                       cast(:payloadJson as jsonb) -> 'adjudication_rules')
                   and not exists (
                       select 1
                         from jsonb_array_elements(
                             cast(:payloadJson as jsonb) -> 'adjudication_rules') snapshot
                        where not exists (
                            select 1
                              from policy_rule rule
                             where rule.id = snapshot ->> 'policy_id'
                               and rule.rule_code = snapshot ->> 'rule_code'
                               and to_jsonb(rule.rule_version) = snapshot -> 'rule_version'
                               and rule.rule_name = snapshot ->> 'rule_name'
                               and rule.rule_scope = snapshot ->> 'rule_scope'
                               and rule.rule_status = snapshot ->> 'rule_status'
                               and to_jsonb(rule.priority) = snapshot -> 'priority'
                               and rule.condition_json = snapshot -> 'conditions'
                               and rule.outcome_json = snapshot -> 'outcome'
                               and rule.source_document_json = snapshot -> 'source_document'
                               and rule.effective_from = cast(
                                   snapshot ->> 'effective_from' as timestamptz)
                               and (
                                   (rule.effective_to is null
                                       and snapshot -> 'effective_to' = 'null'::jsonb)
                                   or (rule.effective_to is not null
                                       and rule.effective_to = cast(
                                           snapshot ->> 'effective_to' as timestamptz))
                               )
                               and rule.rule_status = 'ACTIVE'
                               and rule.deleted_at is null
                               and rule.effective_from <= :committedAt
                               and (rule.effective_to is null
                                   or rule.effective_to > :committedAt)
                        )
                   )
                """,
                parameters,
                "HEARING_DOSSIER_SOURCES_NOT_EXACT");
    }

    private void requireProposal(MapSqlParameterSource parameters) {
        requireOne(
                """
                select count(*)
                  from hearing_flow_artifact
                 where id = :proposalId
                   and case_id = :caseId
                   and flow_instance_id = :flowId
                   and trial_dossier_id = :dossierId
                   and trial_dossier_hash = :dossierHash
                   and artifact_type = 'JUDGE_PROPOSAL'
                   and content_hash = :proposalHash
                """,
                parameters,
                "HEARING_V1_PARENT_NOT_EXACT");
    }

    private void requireReport(MapSqlParameterSource parameters) {
        requireOne(
                """
                select count(*)
                  from hearing_flow_artifact
                 where id = :reportId
                   and case_id = :caseId
                   and flow_instance_id = :flowId
                   and trial_dossier_id = :dossierId
                   and trial_dossier_hash = :dossierHash
                   and artifact_type = 'JURY_REVIEW_REPORT'
                   and proposal_id = :proposalId
                   and proposal_content_hash = :proposalHash
                   and content_hash = :reportHash
                """,
                parameters,
                "HEARING_JURY_PARENT_NOT_EXACT");
    }

    private void requireExactDecisionChain(MapSqlParameterSource parameters) {
        requireOne(
                """
                select count(*)
                  from hearing_trial_dossier dossier
                  join hearing_flow_artifact proposal
                    on proposal.id = :proposalId
                   and proposal.case_id = dossier.case_id
                   and proposal.flow_instance_id = dossier.flow_instance_id
                   and proposal.trial_dossier_id = dossier.id
                   and proposal.trial_dossier_hash = dossier.content_hash
                   and proposal.artifact_type = 'JUDGE_PROPOSAL'
                   and proposal.content_hash = :proposalHash
                  join hearing_flow_artifact report
                    on report.id = :reportId
                   and report.case_id = dossier.case_id
                   and report.flow_instance_id = dossier.flow_instance_id
                   and report.trial_dossier_id = dossier.id
                   and report.trial_dossier_hash = dossier.content_hash
                   and report.artifact_type = 'JURY_REVIEW_REPORT'
                   and report.proposal_id = proposal.id
                   and report.proposal_content_hash = proposal.content_hash
                   and report.content_hash = :reportHash
                  join hearing_flow_artifact draft
                    on draft.id = :judgeV2Id
                   and draft.case_id = dossier.case_id
                   and draft.flow_instance_id = dossier.flow_instance_id
                   and draft.trial_dossier_id = dossier.id
                   and draft.trial_dossier_hash = dossier.content_hash
                   and draft.artifact_type = 'ADJUDICATION_DRAFT'
                   and draft.proposal_id = proposal.id
                   and draft.proposal_content_hash = proposal.content_hash
                   and draft.report_id = report.id
                   and draft.report_content_hash = report.content_hash
                   and draft.content_hash = :judgeV2Hash
                 where dossier.id = :dossierId
                   and dossier.content_hash = :dossierHash
                   and dossier.case_id = :caseId
                   and dossier.flow_instance_id = :flowId
                """,
                parameters,
                "HEARING_DECISION_CHAIN_NOT_EXACT");
    }

    private void applyTransition(
            HearingAuthorityExpectation authority,
            HearingFormalTransition transition,
            Instant committedAt) {
        if (!transition.advances()) {
            return;
        }
        MapSqlParameterSource parameters = common(authority, transition, committedAt);
        requireUpdated(
                jdbc.update(
                        """
                        update hearing_flow_stage
                           set stage_status = 'COMPLETED',
                               output_json = cast(:sourceOutputJson as jsonb),
                               completed_at = :committedAt,
                               updated_at = :committedAt,
                               updated_by = :actorId
                         where id = :sourceStageId
                           and flow_instance_id = :flowId
                           and case_id = :caseId
                           and stage_code = :sourceStage
                           and stage_sequence = :sourceStageSequence
                           and stage_status not in ('COMPLETED', 'FAILED')
                        """,
                        parameters),
                "HEARING_SOURCE_STAGE_COMPLETION_FAILED");
        requireUpdated(
                jdbc.update(
                        """
                        update hearing_flow_instance
                           set current_stage = :resultStage,
                               stage_sequence = :resultStageSequence,
                               flow_status = :flowStatus,
                               shared_deadline_at = :stageDeadlineAt,
                               updated_at = :committedAt,
                               updated_by = :actorId
                         where id = :flowId
                           and case_id = :caseId
                           and current_stage = :sourceStage
                           and stage_sequence = :sourceStageSequence
                        """,
                        parameters),
                "HEARING_FLOW_CURSOR_CAS_FAILED");
        requireUpdated(
                jdbc.update(
                        """
                        insert into hearing_flow_stage (
                            id, flow_instance_id, case_id, stage_code, stage_sequence,
                            processor_role, stage_status, shared_deadline_at,
                            input_json, output_json, started_at, created_at, updated_at,
                            created_by, updated_by
                        ) values (
                            :targetStageId, :flowId, :caseId, :resultStage,
                            :resultStageSequence, :processorRole, :targetStatus,
                            :stageDeadlineAt, cast(:targetInputJson as jsonb), '{}'::jsonb,
                            :committedAt, :committedAt, :committedAt, :actorId, :actorId
                        )
                        """,
                        parameters),
                "HEARING_TARGET_STAGE_INSERT_FAILED");
    }

    private HearingFormalCommitResult result(
            HearingAuthorityExpectation authority,
            HearingFormalTransition transition,
            String resultRef,
            String resultHash) {
        return new HearingFormalCommitResult(
                transition.resultStage(),
                transition.resultStageSequence(),
                transition.sharedDeadlineAt(),
                resultRef,
                resultHash,
                nextEventSequence(authority.caseId()));
    }

    private long nextEventSequence(String caseId) {
        Long next = jdbc.queryForObject(
                """
                select coalesce(max(committed_event_sequence), 0) + 1
                  from hearing_domain_receipt
                 where case_id = :caseId
                """,
                Map.of("caseId", caseId),
                Long.class);
        if (next == null || next < 1) {
            throw rejected("HEARING_EVENT_SEQUENCE_FAILED");
        }
        return next;
    }

    private MapSqlParameterSource common(
            HearingAuthorityExpectation authority,
            HearingFormalTransition transition,
            Instant committedAt) {
        return new MapSqlParameterSource()
                .addValue("tenant", authority.tenantSurrogate())
                .addValue("caseId", authority.caseId())
                .addValue("flowId", authority.flowInstanceId())
                .addValue("epochId", authority.epochId())
                .addValue("sourceStage", authority.stage().name())
                .addValue("sourceStageSequence", authority.stageSequence())
                .addValue("expectedSourceStatus", authority.stage().hasSharedPartyDeadline()
                        ? "WAITING_PARTIES"
                        : "RUNNING")
                .addValue("sourceStageId", transition.sourceStageId())
                .addValue("resultStage", transition.resultStage().name())
                .addValue("resultStageSequence", transition.resultStageSequence())
                .addValue("stageDeadlineAt", offset(transition.sharedDeadlineAt()))
                .addValue("targetStageId", transition.targetStageId())
                .addValue("targetInputJson", transition.targetInputJson())
                .addValue("sourceOutputJson", transition.sourceOutputJson())
                .addValue("actorId", transition.actorId())
                .addValue("committedAt", offset(committedAt))
                .addValue("processorRole", processorRole(transition.resultStage()))
                .addValue("targetStatus", transition.resultStage().hasSharedPartyDeadline()
                        ? "WAITING_PARTIES"
                        : "RUNNING")
                .addValue("flowStatus", switch (transition.resultStage()) {
                    case HUMAN_REVIEW_OPEN -> "HUMAN_REVIEW";
                    case CLOSED -> "CLOSED";
                    default -> "ACTIVE";
                });
    }

    private static String processorRole(HearingFlowStage stage) {
        return switch (stage) {
            case PARTY_ANSWERS_OPEN, PARTY_EVIDENCE_OPEN -> "PARTIES";
            case JUDGE_V1_GENERATING, JUDGE_V2_GENERATING -> "PRESIDING_JUDGE";
            case JURY_REVIEWING -> "JURY_PANEL";
            case INTAKE_QUESTIONS_GENERATING, INTAKE_SYNTHESIZING -> "INTAKE_OFFICER";
            case EVIDENCE_REQUESTS_GENERATING, EVIDENCE_SYNTHESIZING -> "EVIDENCE_CLERK";
            default -> "SYSTEM";
        };
    }

    private void requireOne(
            String sql,
            MapSqlParameterSource parameters,
            String failureCode) {
        Integer count = jdbc.queryForObject(sql, parameters, Integer.class);
        if (!Integer.valueOf(1).equals(count)) {
            throw rejected(failureCode);
        }
    }

    private void requireCount(
            String sql, MapSqlParameterSource parameters, int expected, String failureCode) {
        Integer count = jdbc.queryForObject(sql, parameters, Integer.class);
        if (count == null || count != expected) {
            throw rejected(failureCode);
        }
    }

    private static void requireDecisionType(
            DecisionCommand command, HearingArtifactType expected) {
        Objects.requireNonNull(command, "command");
        if (command.artifactType() != expected) {
            throw new IllegalArgumentException("decision finalizer method does not match artifactType");
        }
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static void requireUpdated(int count, String code) {
        if (count != 1) {
            throw rejected(code);
        }
    }

    private static HearingAuthorityRejectedException rejected(String code) {
        return new HearingAuthorityRejectedException(
                code, "formal Hearing persistence validation failed closed");
    }
}
