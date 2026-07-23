package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFormalFinalizer;
import com.example.dispute.hearing.domain.HearingFormalRequestHash;
import com.example.dispute.hearing.domain.HearingFormalTransition;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.hearing.infrastructure.persistence.JdbcHearingFormalFinalizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.lang.reflect.Modifier;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

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
        payload.put("schema_version", "adjudication_draft.v2");
        payload.put("draft_id", "DRAFT_1");
        payload.put("trial_dossier_id", "DOSSIER_1");
        payload.put("trial_dossier_hash", HASH_A);
        payload.put("proposal_id", "PROPOSAL_1");
        payload.put("proposal_content_hash", HASH_B);
        payload.put("report_id", "REPORT_1");
        payload.put("report_content_hash", HASH_C);
        payload.putObject("draft").put("draft_text", "formal V2");
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
                "hearing.finalize:tenant-1:CASE_1:2:13:adjudication_draft.v2:" + requestHash,
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
                "hearing.handoff:tenant-1:CASE_1:2:DRAFT_1:" + HASH_D,
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
        try {
            return MAPPER.writeValueAsString(canonicalNode(value));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static JsonNode canonicalNode(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> iterator = value.fields();
            iterator.forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((name, child) -> sorted.set(name, canonicalNode(child)));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = MAPPER.createArrayNode();
            value.forEach(child -> array.add(canonicalNode(child)));
            return array;
        }
        return value.deepCopy();
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
}
