package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.domain.HearingAuthorityRejectedException;
import com.example.dispute.hearing.domain.HearingDomainReceipt;
import com.example.dispute.hearing.domain.HearingFlowActionType;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFlowSubmissionStatus;
import com.example.dispute.hearing.domain.HearingFormalCommitResult;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowActionEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowArtifactEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowInstanceEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class HearingFlowPersistenceContractTest {

    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);

    @Test
    void flowCursorUsesIndependentStagesAndOnlyPartyStagesCarryDeadline() {
        HearingFlowInstanceEntity flow =
                HearingFlowInstanceEntity.start(
                        "HEARING_FLOW_1", "CASE_1", "HEARING_1", NOW, "system");

        flow.advance(
                HearingFlowStage.CASE_INTRODUCTION,
                2,
                null,
                NOW.plusSeconds(1),
                "system");

        assertThat(flow.getSchemaVersion()).isEqualTo("hearing_flow.v2");
        assertThat(flow.getCurrentStage()).isEqualTo(HearingFlowStage.CASE_INTRODUCTION);
        assertThat(flow.getStageSequence()).isEqualTo(2);
        assertThat(flow.getSharedDeadlineAt()).isNull();

        assertThatThrownBy(
                        () ->
                                flow.advance(
                                        HearingFlowStage.PARTY_ANSWERS_OPEN,
                                        3,
                                        null,
                                        NOW.plusSeconds(2),
                                        "system"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shared deadline");
    }

    @Test
    void autoTimeoutIsSubmissionStatusOnPartyActionNotAnotherProvenanceMode() {
        HearingFlowActionEntity action =
                HearingFlowActionEntity.partyAction(
                        "ANSWER_USER",
                        "HEARING_FLOW_1",
                        "STAGE_5",
                        "CASE_1",
                        HearingFlowActionType.ANSWER_BUNDLE,
                        "user-1",
                        ActorRole.USER,
                        HearingFlowSubmissionStatus.AUTO_TIMEOUT,
                        "{\"schema_version\":\"hearing_answer_bundle.v1\"}",
                        HASH_A,
                        NOW,
                        "hearing-timeout");

        assertThat(action.getSubmissionStatus())
                .isEqualTo(HearingFlowSubmissionStatus.AUTO_TIMEOUT);
        assertThat(action.getParticipantId()).isEqualTo("user-1");
        assertThat(action.getParticipantRole()).isEqualTo(ActorRole.USER);
        assertThat(action.getAgentRunId()).isNull();
    }

    @Test
    void decisionArtifactsUseCanonicalSchemasAndExactParentIdHashChain() {
        HearingFlowArtifactEntity proposal =
                HearingFlowArtifactEntity.judgeProposal(
                        "PROPOSAL_1",
                        "CASE_1",
                        "HEARING_FLOW_1",
                        "TRIAL_DOSSIER_1",
                        HASH_A,
                        HASH_B,
                        "{}",
                        "AGENT_RUN_1",
                        NOW,
                        "judge");
        HearingFlowArtifactEntity report =
                HearingFlowArtifactEntity.juryReviewReport(
                        "REPORT_1",
                        "CASE_1",
                        "HEARING_FLOW_1",
                        "TRIAL_DOSSIER_1",
                        HASH_A,
                        proposal.getId(),
                        proposal.getContentHash(),
                        HASH_C,
                        "{}",
                        "AGENT_RUN_2",
                        NOW,
                        "jury");

        assertThat(proposal.getArtifactType()).isEqualTo(HearingArtifactType.JUDGE_PROPOSAL);
        assertThat(proposal.getSchemaVersion()).isEqualTo("judge_proposal.v1");
        assertThat(report.getSchemaVersion()).isEqualTo("jury_review_report.v1");
        assertThat(report.getProposalId()).isEqualTo("PROPOSAL_1");
        assertThat(report.getProposalContentHash()).isEqualTo(HASH_B);

        assertThatThrownBy(
                        () ->
                                HearingFlowArtifactEntity.adjudicationDraft(
                                        "DRAFT_1",
                                        "CASE_1",
                                        "HEARING_FLOW_1",
                                        "TRIAL_DOSSIER_1",
                                        HASH_A,
                                        "PROPOSAL_1",
                                        HASH_B,
                                        "REPORT_1",
                                        null,
                                        HASH_C,
                                        "{}",
                                        "AGENT_RUN_3",
                                        NOW,
                                        "judge"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent id/hash chain");
    }

    @Test
    void v044IsAdditiveAndPreservesTheHistoricalHearingLedgers() throws IOException {
        Path migrationDirectory = Path.of("src/main/resources/db/migration");
        String v044 = Files.readString(
                migrationDirectory.resolve("V044__hearing_temporal_projection.sql"));

        assertThat(v044)
                .contains("create table hearing_temporal_projection")
                .contains("create table hearing_domain_receipt")
                .contains("references case_room_epoch")
                .contains("writer_mode = 'LEGACY'")
                .contains("old Java Hearing advance is fenced for a TEMPORAL epoch")
                .doesNotContain("drop table hearing_flow_")
                .doesNotContain("truncate hearing_flow_")
                .doesNotContain("alter column participant_id");
        assertThat(sha256(migrationDirectory.resolve("V035__hearing_flow_v2.sql")))
                .isEqualTo("ae74633cb318961a12397fc5c7f9fcc0f3ae13e4b82372e240af05b6976423cf");
        assertThat(sha256(
                        migrationDirectory.resolve(
                                "V037__key_hearing_party_actions_by_participant_id.sql")))
                .isEqualTo("a060583e7764531b562ec88befedd3b3f535ecede0bc2c7faa09f198f471a71c");
    }

    @Test
    void domainReceiptBindsSourceAuthorityAndOnlyOneAdjacentStage() {
        HearingAuthorityExpectation authority = new HearingAuthorityExpectation(
                "tenant-1",
                "CASE_1",
                "HEARING_FLOW_1",
                "EPOCH_1",
                0,
                HearingWriterMode.LEGACY,
                HearingFlowStage.COURT_PREPARING,
                1,
                3,
                7,
                0);
        HearingAuthorityCommit command = new HearingAuthorityCommit(
                HearingAuthorityCommit.SCHEMA_VERSION,
                authority,
                HearingAuthorityCommit.OperationType.STAGE,
                "hearing.stage:tenant-1:CASE_1:0:1:COURT_PREPARING",
                HASH_A,
                null,
                NOW);
        HearingDomainReceipt receipt = HearingDomainReceipt.committed(
                command,
                new HearingFormalCommitResult(
                        HearingFlowStage.CASE_INTRODUCTION,
                        2,
                        null,
                        "urn:hearing:stage:CASE_1:2",
                        HASH_B,
                        9),
                null,
                null,
                null,
                "legacy-java.v1");

        assertThat(receipt.schemaVersion()).isEqualTo("hearing-domain-receipt.v1");
        assertThat(receipt.sourceProcessRevision()).isEqualTo(3);
        assertThat(receipt.processRevision()).isEqualTo(4);
        assertThat(receipt.sourceRoomRevision()).isEqualTo(7);
        assertThat(receipt.roomRevision()).isEqualTo(8);
        assertThat(receipt.receiptHash()).matches("[0-9a-f]{64}");

        HearingAuthorityCommit conflict = new HearingAuthorityCommit(
                HearingAuthorityCommit.SCHEMA_VERSION,
                authority,
                HearingAuthorityCommit.OperationType.STAGE,
                command.operationKey(),
                HASH_C,
                null,
                NOW.plusSeconds(1));
        assertThatThrownBy(() -> receipt.requireReplayOf(conflict))
                .isInstanceOf(HearingAuthorityRejectedException.class)
                .extracting(failure -> ((HearingAuthorityRejectedException) failure).code())
                .isEqualTo("HEARING_IDEMPOTENCY_CONFLICT");
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
