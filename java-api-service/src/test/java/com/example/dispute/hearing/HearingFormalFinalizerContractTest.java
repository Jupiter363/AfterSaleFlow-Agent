package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.domain.HearingAuthorityLedger;
import com.example.dispute.hearing.domain.HearingAuthorityRejectedException;
import com.example.dispute.hearing.domain.HearingDomainReceipt;
import com.example.dispute.hearing.domain.HearingFlowActionType;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFormalFinalizer;
import com.example.dispute.hearing.domain.HearingFormalRequestHash;
import com.example.dispute.hearing.domain.HearingFormalTransition;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.hearing.infrastructure.persistence.JdbcHearingFormalFinalizer;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class HearingFormalFinalizerContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);
    private static final String HASH_D = "d".repeat(64);
    private static final String ACTOR = "hearing-finalizer";

    @Test
    void judgeV2CommandBindsAuthorityRequestHashAndExactParentPayload() {
        HearingAuthorityExpectation authority = authority(
                HearingFlowStage.JUDGE_V2_GENERATING, 13, 7, 9);
        HearingFormalTransition transition = advance(
                "STAGE_13", HearingFlowStage.HUMAN_REVIEW_OPEN, "STAGE_14");
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("schema_version", "adjudication_draft.v3");
        payload.put("draft_id", "DRAFT_1");
        payload.put("trial_dossier_id", "DOSSIER_1");
        payload.put("trial_dossier_hash", HASH_A);
        payload.put("proposal_id", "PROPOSAL_1");
        payload.put("proposal_content_hash", HASH_B);
        payload.put("report_id", "REPORT_1");
        payload.put("report_content_hash", HASH_C);
        ObjectNode draft = payload.putObject("draft");
        draft.put("decision_action", "REFUND_ONLY");
        draft.putArray("remedy_orders").addObject()
                .put("remedy_type", "REFUND_ONLY")
                .put("order_text", "Refund the supported amount.")
                .putArray("fact_ids").add("FACT_1");
        draft.putArray("fact_findings").addObject()
                .put("fact_id", "FACT_1")
                .put("finding", "The frozen evidence partly supports delivery.")
                .putArray("evidence_ids").add("EVIDENCE_1");
        draft.putArray("rule_applications").addObject()
                .put("rule_code", "DELIVERY_PROOF")
                .put("rule_version", 1)
                .put("rule_name", "Delivery proof")
                .putArray("fact_ids").add("FACT_1");
        draft.put("decision_reasoning", "The frozen fact, evidence and rule support relief.");
        draft.putArray("reviewer_attention").add("Confirm the exact amount.");
        payload.putArray("review_responses").addObject()
                .put("review_item_ref", "V1_FOCUS_01")
                .put("review_source", "V1_REVIEW_FOCUS")
                .put("disposition", "ACCEPTED")
                .put("response", "The frozen authority supports this response.")
                .putArray("affected_fields").add("decision_reasoning");
        payload.put("public_text", "formal V2");
        String contentHash = hashWithout(payload, "content_hash");
        payload.put("content_hash", contentHash);

        String requestHash = HearingFormalRequestHash.compute(
                "DECISION",
                authority,
                transition,
                HearingArtifactType.ADJUDICATION_DRAFT,
                "DRAFT_1",
                contentHash,
                "DOSSIER_1",
                HASH_A,
                "PROPOSAL_1",
                HASH_B,
                "REPORT_1",
                HASH_C,
                "RUN_3",
                HASH_D,
                ACTOR);
        HearingAuthorityCommit commit = commit(
                authority,
                HearingAuthorityCommit.OperationType.FINALIZE,
                "hearing.finalize:tenant-1:CASE_1:2:13:adjudication_draft.v3:" + requestHash,
                requestHash);

        HearingFormalFinalizer.DecisionCommand command = new HearingFormalFinalizer.DecisionCommand(
                commit,
                transition,
                HearingArtifactType.ADJUDICATION_DRAFT,
                "DRAFT_1",
                contentHash,
                "DOSSIER_1",
                HASH_A,
                "PROPOSAL_1",
                HASH_B,
                "REPORT_1",
                HASH_C,
                json(payload),
                "RUN_3",
                HASH_D,
                ACTOR);

        assertThat(command.authorityCommit().requestHash()).isEqualTo(requestHash);
        assertThat(command.transition().resultStage()).isEqualTo(HearingFlowStage.HUMAN_REVIEW_OPEN);

        ObjectNode substituted = payload.deepCopy();
        substituted.put("proposal_content_hash", HASH_D);
        assertThatThrownBy(() -> new HearingFormalFinalizer.DecisionCommand(
                        commit,
                        transition,
                        HearingArtifactType.ADJUDICATION_DRAFT,
                        "DRAFT_1",
                        contentHash,
                        "DOSSIER_1",
                        HASH_A,
                        "PROPOSAL_1",
                        HASH_B,
                        "REPORT_1",
                        HASH_C,
                        json(substituted),
                        "RUN_3",
                        HASH_D,
                        ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proposal_content_hash");
    }

    @Test
    void dossierV2CommandBindsTheDerivedFinalizeKeyAndExcludesProcessIntermediates() {
        HearingAuthorityExpectation authority = authority(
                HearingFlowStage.DOSSIER_FREEZING, 10, 6, 8);
        HearingFormalTransition transition = advance(
                "STAGE_10", HearingFlowStage.JUDGE_V1_GENERATING, "STAGE_11");
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("schema_version", "trial_dossier.v2");
        payload.put("trial_dossier_id", "DOSSIER_1");
        payload.put("case_id", "CASE_1");
        payload.put("frozen_at", NOW.toString());
        payload.put("case_matrix_version", 2);
        payload.put("case_matrix_hash", HASH_A);
        payload.putObject("case_fact_matrix")
                .put("schema_version", "case_fact_matrix.v2")
                .put("case_id", "CASE_1")
                .put("matrix_id", "CASE_MATRIX_2")
                .put("matrix_version", 2)
                .put("content_hash", HASH_A);
        payload.put("evidence_matrix_version", 3);
        payload.put("evidence_matrix_hash", HASH_B);
        payload.putObject("fact_evidence_matrix")
                .put("schema_version", "fact_evidence_matrix.v3")
                .put("case_id", "CASE_1")
                .put("matrix_id", "EVIDENCE_MATRIX_3")
                .put("matrix_version", 3)
                .put("matrix_status", "FROZEN")
                .put("case_fact_matrix_id", "CASE_MATRIX_2")
                .put("case_fact_matrix_version", 2)
                .put("case_fact_matrix_hash", HASH_A)
                .put("content_hash", HASH_B);
        payload.putArray("adjudication_rules").addObject()
                .put("rule_code", "DELIVERY_PROOF")
                .put("rule_version", 1);
        String contentHash = hashWithout(payload, "content_hash");
        payload.put("content_hash", contentHash);
        String requestHash = HearingFormalRequestHash.compute(
                "DOSSIER", authority, transition, "DOSSIER_1",
                2, HASH_A, 3, HASH_B, "QUESTION_SET_1", "REQUEST_SET_1",
                contentHash, ACTOR);
        HearingAuthorityCommit commit = commit(
                authority,
                HearingAuthorityCommit.OperationType.FINALIZE,
                "hearing.finalize:tenant-1:CASE_1:2:10:trial_dossier.v2:" + requestHash,
                requestHash);

        HearingFormalFinalizer.DossierCommand command =
                new HearingFormalFinalizer.DossierCommand(
                        commit,
                        transition,
                        "DOSSIER_1",
                        2,
                        HASH_A,
                        3,
                        HASH_B,
                        "QUESTION_SET_1",
                        "REQUEST_SET_1",
                        json(payload),
                        contentHash,
                        ACTOR);

        assertThat(command.authorityCommit().operationKey())
                .contains(":trial_dossier.v2:" + requestHash);
        assertThat(payload.has("question_set")).isFalse();
        assertThat(payload.has("answer_bundles")).isFalse();
        assertThat(payload.has("evidence_request_set")).isFalse();
        assertThat(payload.has("evidence_batches")).isFalse();
    }

    @Test
    void dossierV2PersistenceGuardBindsFrozenMatricesAndRulesWithoutRetiredPayloadFields() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AtomicReference<String> sourceSql = new AtomicReference<>();
        AtomicInteger matchedRows = new AtomicInteger(1);
        when(jdbc.queryForObject(
                        anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenAnswer(invocation -> {
                    sourceSql.set(invocation.getArgument(0));
                    return matchedRows.get();
                });
        JdbcHearingFormalFinalizer finalizer =
                new JdbcHearingFormalFinalizer(jdbc, mock(HearingAuthorityLedger.class));
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("caseId", "CASE_1")
                .addValue("flowId", "FLOW_1")
                .addValue("caseMatrixVersion", 2)
                .addValue("caseMatrixHash", HASH_A)
                .addValue("evidenceMatrixVersion", 3)
                .addValue("evidenceMatrixHash", HASH_B)
                .addValue("questionSetId", "QUESTION_SET_1")
                .addValue("requestSetId", "REQUEST_SET_1")
                .addValue("payloadJson", "{}")
                .addValue("committedAt", NOW);

        assertThatCode(() -> invokeDossierSourceGuard(finalizer, parameters))
                .doesNotThrowAnyException();
        assertThat(sourceSql.get())
                .contains(
                        "case_matrix_stage.output_json -> 'case_fact_matrix'",
                        "evidence_matrix_stage.output_json -> 'fact_evidence_matrix'",
                        "from policy_rule rule",
                        "snapshot ->> 'policy_id'",
                        "answer.submission_status = 'SUBMITTED'",
                        "evidence.submission_status in ('SUBMITTED', 'AUTO_TIMEOUT')")
                .doesNotContain(
                        "-> 'question_set'",
                        "-> 'answer_bundles'",
                        "-> 'evidence_request_set'",
                        "-> 'evidence_batches'");

        matchedRows.set(0);
        assertThatThrownBy(() -> invokeDossierSourceGuard(finalizer, parameters))
                .isInstanceOf(HearingAuthorityRejectedException.class)
                .extracting(failure -> ((HearingAuthorityRejectedException) failure).code())
                .isEqualTo("HEARING_DOSSIER_SOURCES_NOT_EXACT");
    }

    @Test
    void handoffAndClosureKeysBindExactV2AndHandoffReceipt() {
        HearingAuthorityExpectation handoffAuthority = authority(
                HearingFlowStage.HUMAN_REVIEW_OPEN, 14, 10, 12);
        HearingFormalTransition handoffTransition = stay("STAGE_14", HearingFlowStage.HUMAN_REVIEW_OPEN);
        String handoffHash = HearingFormalRequestHash.compute(
                "HANDOFF_FACT",
                handoffAuthority,
                "HANDOFF_1",
                "DOSSIER_1",
                HASH_A,
                "PROPOSAL_1",
                HASH_B,
                "REPORT_1",
                HASH_C,
                "DRAFT_1",
                HASH_D,
                "TASK_1",
                "PACKET_1",
                ACTOR,
                NOW);
        String handoffRequestHash = HearingFormalRequestHash.compute(
                "HANDOFF",
                handoffAuthority,
                handoffTransition,
                "HANDOFF_1",
                "DOSSIER_1",
                HASH_A,
                "PROPOSAL_1",
                HASH_B,
                "REPORT_1",
                HASH_C,
                "DRAFT_1",
                HASH_D,
                "TASK_1",
                "PACKET_1",
                handoffHash,
                ACTOR);
        HearingAuthorityCommit handoffCommit = commit(
                handoffAuthority,
                HearingAuthorityCommit.OperationType.HANDOFF,
                HearingFormalRequestHash.handoffOperationKey(
                        handoffAuthority.tenantSurrogate(),
                        handoffAuthority.caseId(),
                        handoffAuthority.epochId(),
                        handoffAuthority.roomEpoch(),
                        "DRAFT_1",
                        HASH_D),
                handoffRequestHash);
        HearingFormalFinalizer.HandoffCommand handoff = new HearingFormalFinalizer.HandoffCommand(
                handoffCommit,
                handoffTransition,
                "HANDOFF_1",
                "DOSSIER_1",
                HASH_A,
                "PROPOSAL_1",
                HASH_B,
                "REPORT_1",
                HASH_C,
                "DRAFT_1",
                HASH_D,
                "TASK_1",
                "PACKET_1",
                handoffHash,
                ACTOR);
        assertThat(handoff.judgeV2Hash()).isEqualTo(HASH_D);
        HearingAuthorityCommit legacyHandoffKey = commit(
                handoffAuthority,
                HearingAuthorityCommit.OperationType.HANDOFF,
                "hearing.handoff:tenant-1:CASE_1:2:DRAFT_1:" + HASH_D,
                handoffRequestHash);
        assertThatThrownBy(() -> new HearingFormalFinalizer.HandoffCommand(
                        legacyHandoffKey,
                        handoffTransition,
                        "HANDOFF_1",
                        "DOSSIER_1",
                        HASH_A,
                        "PROPOSAL_1",
                        HASH_B,
                        "REPORT_1",
                        HASH_C,
                        "DRAFT_1",
                        HASH_D,
                        "TASK_1",
                        "PACKET_1",
                        handoffHash,
                        ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operation key");

        HearingAuthorityExpectation closureAuthority = authority(
                HearingFlowStage.HUMAN_REVIEW_OPEN, 14, 11, 13);
        HearingFormalTransition closureTransition = advance(
                "STAGE_14", HearingFlowStage.CLOSED, "STAGE_15");
        String closureHash = HearingFormalRequestHash.compute(
                "CLOSURE_FACT",
                closureAuthority,
                "CLOSURE_1",
                "HANDOFF_1",
                "HDR_HANDOFF_1",
                HASH_B,
                ACTOR,
                NOW);
        String closureRequestHash = HearingFormalRequestHash.compute(
                "CLOSURE",
                closureAuthority,
                closureTransition,
                "CLOSURE_1",
                "HANDOFF_1",
                "HDR_HANDOFF_1",
                HASH_B,
                closureHash,
                ACTOR);
        HearingAuthorityCommit closureCommit = commit(
                closureAuthority,
                HearingAuthorityCommit.OperationType.CLOSE,
                "hearing.close:tenant-1:CASE_1:2:" + HASH_B,
                closureRequestHash);
        HearingFormalFinalizer.ClosureCommand closure = new HearingFormalFinalizer.ClosureCommand(
                closureCommit,
                closureTransition,
                "CLOSURE_1",
                "HANDOFF_1",
                "HDR_HANDOFF_1",
                HASH_B,
                closureHash,
                ACTOR);
        assertThat(closure.transition().resultStage()).isEqualTo(HearingFlowStage.CLOSED);

        HearingAuthorityCommit substitutedKey = commit(
                closureAuthority,
                HearingAuthorityCommit.OperationType.CLOSE,
                "hearing.close:tenant-1:CASE_1:2:" + HASH_D,
                closureRequestHash);
        assertThatThrownBy(() -> new HearingFormalFinalizer.ClosureCommand(
                        substitutedKey,
                        closureTransition,
                        "CLOSURE_1",
                        "HANDOFF_1",
                        "HDR_HANDOFF_1",
                        HASH_B,
                        closureHash,
                        ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operation key");
    }

    @Test
    void formalJdbcAdapterIsDormantAndNotSpringRegistered() {
        assertThat(JdbcHearingFormalFinalizer.class.getAnnotations()).isEmpty();
        assertThat(Modifier.isFinal(JdbcHearingFormalFinalizer.class.getModifiers())).isTrue();
    }

    @Test
    void temporalResultReadyAttemptIsFinalizableExactlyOnceAndLifecycleDriftFailsClosed() {
        String runId = "target-hearing-run:finalizable-contract";
        String attemptId = runId + ":1";
        String resultHash = "e".repeat(64);
        HearingAuthorityExpectation authority = authority(
                HearingFlowStage.INTAKE_QUESTIONS_GENERATING, 4, 17, 6);
        Instant deadline = NOW.plusSeconds(600);
        HearingFormalTransition transition = new HearingFormalTransition(
                "STAGE_4", HearingFlowStage.PARTY_ANSWERS_OPEN, 5, deadline,
                "STAGE_5", "{}", "{}", ACTOR);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("schema_version", HearingFlowActionType.QUESTION_SET.schemaVersion());
        payload.put("question_set_hash", HASH_A);
        String contentHash = hashWithout(payload, "question_set_hash");
        payload.put("question_set_hash", contentHash);
        String payloadJson = json(payload);
        String actionId = "ACTION_QUESTION_SET_FINALIZABLE";
        String requestHash = HearingFormalRequestHash.compute(
                "ACTION", authority, transition, actionId, HearingFlowActionType.QUESTION_SET,
                HearingFlowActionType.QUESTION_SET.schemaVersion(), null, null, null,
                contentHash, runId, resultHash, null, ACTOR);
        HearingAuthorityCommit commit = commit(
                authority, HearingAuthorityCommit.OperationType.FINALIZE,
                "hearing.finalize:tenant-1:CASE_1:2:4:QUESTION_SET:" + requestHash,
                requestHash);
        HearingFormalFinalizer.ActionCommand command = new HearingFormalFinalizer.ActionCommand(
                commit, transition, actionId, HearingFlowActionType.QUESTION_SET,
                HearingFlowActionType.QUESTION_SET.schemaVersion(), null, null, null,
                payloadJson, contentHash, runId, resultHash, null, ACTOR);

        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AgentLifecycle lifecycle = new AgentLifecycle(runId, attemptId, resultHash, authority);
        AtomicReference<String> lifecycleSql = new AtomicReference<>();
        when(jdbc.queryForObject(
                anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    MapSqlParameterSource parameters = invocation.getArgument(1);
                    if (sql.contains("from hearing_flow_stage")) {
                        return 1;
                    }
                    if (sql.contains("from agent_run")) {
                        lifecycleSql.set(sql);
                        return lifecycle.matches(sql, parameters) ? 1 : 0;
                    }
                    throw new AssertionError("unexpected cardinality query: " + sql);
                });
        when(jdbc.queryForObject(anyString(), any(Map.class), eq(Long.class))).thenReturn(1L);
        AtomicInteger actionInserts = new AtomicInteger();
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenAnswer(invocation -> {
                    if (((String) invocation.getArgument(0)).contains("insert into hearing_flow_action")) {
                        actionInserts.incrementAndGet();
                    }
                    return 1;
                });

        HearingAuthorityLedger ledger = mock(HearingAuthorityLedger.class);
        AtomicReference<HearingDomainReceipt> storedReceipt = new AtomicReference<>();
        when(ledger.commitOrReplay(
                any(HearingAuthorityCommit.class), any(HearingAuthorityLedger.FormalCommitAction.class)))
                .thenAnswer(invocation -> {
                    HearingDomainReceipt replay = storedReceipt.get();
                    if (replay != null) {
                        return replay;
                    }
                    HearingAuthorityLedger.FormalCommitAction mutation = invocation.getArgument(1);
                    mutation.commit();
                    HearingDomainReceipt receipt = mock(HearingDomainReceipt.class);
                    storedReceipt.set(receipt);
                    return receipt;
                });
        JdbcHearingFormalFinalizer finalizer = new JdbcHearingFormalFinalizer(jdbc, ledger);

        HearingDomainReceipt first = finalizer.appendAction(command);
        assertThat(first).isSameAs(storedReceipt.get());
        assertThat(actionInserts).hasValue(1);
        assertThat(lifecycleSql.get())
                .contains(
                        "join agent_run_attempt attempt",
                        "run.protocol = 'agent-stream.v3'",
                        "run.run_status = 'RESULT_READY'",
                        "run.finalization_status = 'UNCOMMITTED'",
                        "run.result_ready_attempt_id = attempt.id",
                        "run.committed_attempt_id is null",
                        "attempt.attempt_status = 'RESULT_READY'",
                        "attempt.result_hash = run.final_result_hash",
                        "attempt.final_frame_observed = true",
                        "attempt.completed_at is not null",
                        "run.run_status = 'COMPLETED'",
                        "run.finalization_status = 'COMMITTED'",
                        "run.committed_attempt_id = attempt.id",
                        "attempt.attempt_status = 'COMPLETED'");

        lifecycle.completedCommitted();
        HearingDomainReceipt replay = finalizer.appendAction(command);
        assertThat(replay).isSameAs(first);
        assertThat(actionInserts).hasValue(1);
        assertThatCode(() -> invokeAgentRunGuard(finalizer, authority, runId, resultHash))
                .doesNotThrowAnyException();

        List<Consumer<AgentLifecycle>> temporalDrifts = new ArrayList<>();
        temporalDrifts.add(value -> value.attemptId = null);
        temporalDrifts.add(value -> value.attemptId = "target-hearing-run:different:1");
        temporalDrifts.add(value -> value.committedAttemptId = value.attemptId);
        temporalDrifts.add(value -> value.attemptResultHash = HASH_D);
        temporalDrifts.add(value -> value.roomType = "EVIDENCE");
        temporalDrifts.add(value -> value.roomEpoch++);
        temporalDrifts.add(value -> value.processRevision++);
        temporalDrifts.add(value -> value.fencingToken++);
        temporalDrifts.add(value -> value.runExecutor = "LEGACY_WORKER");
        temporalDrifts.add(value -> value.attemptExecutor = "LEGACY_WORKER");
        temporalDrifts.add(value -> value.finalFrameObserved = false);
        temporalDrifts.add(value -> value.completedAt = null);
        temporalDrifts.add(value -> value.runStatus = "RUNNING");
        temporalDrifts.add(value -> value.runStatus = "FAILED");
        for (Consumer<AgentLifecycle> drift : temporalDrifts) {
            lifecycle.resultReadyUncommitted();
            drift.accept(lifecycle);
            assertThatThrownBy(() -> invokeAgentRunGuard(finalizer, authority, runId, resultHash))
                    .isInstanceOf(HearingAuthorityRejectedException.class)
                    .extracting(failure -> ((HearingAuthorityRejectedException) failure).code())
                    .isEqualTo("HEARING_AGENT_RUN_NOT_TERMINAL");
        }

        HearingAuthorityExpectation legacyAuthority = new HearingAuthorityExpectation(
                authority.tenantSurrogate(), authority.caseId(), authority.flowInstanceId(),
                authority.epochId(), authority.roomEpoch(), HearingWriterMode.LEGACY,
                authority.stage(), authority.stageSequence(), authority.processRevision(),
                authority.roomRevision(), 0);
        lifecycle.resultReadyUncommitted();
        lifecycle.protocol = "agent_stream.v1";
        lifecycle.runExecutor = "LEGACY_WORKER";
        lifecycle.attemptExecutor = "LEGACY_WORKER";
        lifecycle.fencingToken = 0;
        assertThatThrownBy(() -> invokeAgentRunGuard(
                finalizer, legacyAuthority, runId, resultHash))
                .isInstanceOf(HearingAuthorityRejectedException.class)
                .extracting(failure -> ((HearingAuthorityRejectedException) failure).code())
                .isEqualTo("HEARING_AGENT_RUN_NOT_TERMINAL");
        lifecycle.runStatus = "COMPLETED";
        assertThatCode(() -> invokeAgentRunGuard(
                finalizer, legacyAuthority, runId, resultHash)).doesNotThrowAnyException();
    }

    private static HearingAuthorityExpectation authority(
            HearingFlowStage stage, int sequence, long processRevision, long roomRevision) {
        return new HearingAuthorityExpectation(
                "tenant-1",
                "CASE_1",
                "FLOW_1",
                "EPOCH_1",
                2,
                HearingWriterMode.TEMPORAL,
                stage,
                sequence,
                processRevision,
                roomRevision,
                5);
    }

    private static HearingAuthorityCommit commit(
            HearingAuthorityExpectation authority,
            HearingAuthorityCommit.OperationType operationType,
            String operationKey,
            String requestHash) {
        return new HearingAuthorityCommit(
                HearingAuthorityCommit.SCHEMA_VERSION,
                authority,
                operationType,
                operationKey,
                requestHash,
                21L,
                NOW);
    }

    private static HearingFormalTransition advance(
            String sourceStageId, HearingFlowStage result, String targetStageId) {
        return new HearingFormalTransition(
                sourceStageId,
                result,
                result.ordinal() + 1,
                null,
                targetStageId,
                "{}",
                "{}",
                ACTOR);
    }

    private static HearingFormalTransition stay(String sourceStageId, HearingFlowStage result) {
        return new HearingFormalTransition(
                sourceStageId,
                result,
                result.ordinal() + 1,
                null,
                null,
                null,
                null,
                ACTOR);
    }

    private static String hashWithout(ObjectNode payload, String field) {
        ObjectNode copy = payload.deepCopy();
        copy.remove(field);
        return sha256(canonicalJson(copy));
    }

    private static String canonicalJson(JsonNode value) {
        return ContractJson.canonicalString(value);
    }

    private static String json(JsonNode value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void invokeAgentRunGuard(
            JdbcHearingFormalFinalizer finalizer,
            HearingAuthorityExpectation authority,
            String agentRunId,
            String resultHash) {
        try {
            Method guard = JdbcHearingFormalFinalizer.class.getDeclaredMethod(
                    "requireTerminalAgentRun",
                    HearingAuthorityExpectation.class, String.class, String.class);
            guard.setAccessible(true);
            guard.invoke(finalizer, authority, agentRunId, resultHash);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void invokeDossierSourceGuard(
            JdbcHearingFormalFinalizer finalizer, MapSqlParameterSource parameters) {
        try {
            Method guard = JdbcHearingFormalFinalizer.class.getDeclaredMethod(
                    "requireDossierSources", MapSqlParameterSource.class);
            guard.setAccessible(true);
            guard.invoke(finalizer, parameters);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class AgentLifecycle {
        private final String runId;
        private final String expectedAttemptId;
        private final String expectedResultHash;
        private final HearingAuthorityExpectation authority;
        private String protocol;
        private String runStatus;
        private String finalizationStatus;
        private String resultReadyAttemptId;
        private String committedAttemptId;
        private String finalResultHash;
        private String roomType;
        private long roomEpoch;
        private long processRevision;
        private long fencingToken;
        private String runExecutor;
        private String attemptId;
        private String attemptStatus;
        private String attemptExecutor;
        private String attemptResultHash;
        private boolean finalFrameObserved;
        private Instant completedAt;

        private AgentLifecycle(
                String runId,
                String attemptId,
                String resultHash,
                HearingAuthorityExpectation authority) {
            this.runId = runId;
            this.expectedAttemptId = attemptId;
            this.expectedResultHash = resultHash;
            this.authority = authority;
            resultReadyUncommitted();
        }

        private void resultReadyUncommitted() {
            protocol = "agent-stream.v3";
            runStatus = "RESULT_READY";
            finalizationStatus = "UNCOMMITTED";
            resultReadyAttemptId = expectedAttemptId;
            committedAttemptId = null;
            finalResultHash = expectedResultHash;
            roomType = "HEARING";
            roomEpoch = authority.roomEpoch();
            processRevision = authority.processRevision();
            fencingToken = authority.fencingToken();
            runExecutor = "TEMPORAL_ACTIVITY";
            attemptId = expectedAttemptId;
            attemptStatus = "RESULT_READY";
            attemptExecutor = "TEMPORAL_ACTIVITY";
            attemptResultHash = expectedResultHash;
            finalFrameObserved = true;
            completedAt = NOW;
        }

        private void completedCommitted() {
            resultReadyUncommitted();
            runStatus = "COMPLETED";
            finalizationStatus = "COMMITTED";
            committedAttemptId = expectedAttemptId;
            attemptStatus = "COMPLETED";
        }

        private boolean matches(String sql, MapSqlParameterSource parameters) {
            boolean common = runId.equals(parameters.getValue("agentRunId"))
                    && expectedResultHash.equals(parameters.getValue("resultHash"))
                    && authority.tenantSurrogate().equals(parameters.getValue("tenant"))
                    && authority.caseId().equals(parameters.getValue("caseId"))
                    && authority.epochId().equals(parameters.getValue("epochId"))
                    && roomEpoch == ((Number) parameters.getValue("roomEpoch")).longValue()
                    && processRevision == ((Number) parameters.getValue("processRevision")).longValue()
                    && fencingToken == ((Number) parameters.getValue("fencingToken")).longValue()
                    && "HEARING".equals(roomType)
                    && finalResultHash.equals(expectedResultHash);
            if ("LEGACY_WORKER".equals(parameters.getValue("executorKind"))) {
                return common && "LEGACY_WORKER".equals(runExecutor)
                        && "COMPLETED".equals(runStatus)
                        && sql.contains("run_status = 'COMPLETED'")
                        && !sql.contains("run_status = 'RESULT_READY'");
            }
            boolean exactSql = sql.contains("join agent_run_attempt attempt")
                    && sql.contains("run.protocol = 'agent-stream.v3'")
                    && sql.contains("run.result_ready_attempt_id = attempt.id")
                    && sql.contains("attempt.result_hash = run.final_result_hash")
                    && sql.contains("attempt.final_frame_observed = true")
                    && sql.contains("attempt.completed_at is not null");
            boolean commonAttempt = expectedAttemptId.equals(resultReadyAttemptId)
                    && expectedAttemptId.equals(attemptId)
                    && expectedResultHash.equals(attemptResultHash)
                    && "TEMPORAL_ACTIVITY".equals(runExecutor)
                    && "TEMPORAL_ACTIVITY".equals(attemptExecutor)
                    && finalFrameObserved
                    && completedAt != null;
            boolean precommit = "RESULT_READY".equals(runStatus)
                    && "UNCOMMITTED".equals(finalizationStatus)
                    && committedAttemptId == null
                    && "RESULT_READY".equals(attemptStatus);
            boolean committed = "COMPLETED".equals(runStatus)
                    && "COMMITTED".equals(finalizationStatus)
                    && expectedAttemptId.equals(committedAttemptId)
                    && "COMPLETED".equals(attemptStatus);
            return exactSql && common && commonAttempt && (precommit || committed);
        }
    }
}
