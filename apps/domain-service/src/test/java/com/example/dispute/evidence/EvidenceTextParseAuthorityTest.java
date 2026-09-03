package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.evidence.application.EvidenceContentAuthorityLookup;
import com.example.dispute.evidence.application.EvidenceContentAuthorityUnavailableException;
import com.example.dispute.evidence.application.EvidenceContentAuthorityV1;
import com.example.dispute.evidence.application.EvidenceParseOutboxService;
import com.example.dispute.evidence.application.EvidenceStorage;
import com.example.dispute.evidence.application.EvidenceTextContentInvalidException;
import com.example.dispute.evidence.domain.EvidenceParseOutboxStatus;
import com.example.dispute.evidence.infrastructure.EvidenceParseOutboxDispatcher;
import com.example.dispute.evidence.infrastructure.PostCommitEvidenceParseDeliveryTrigger;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceContentAuthorityEntity;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceParseOutboxEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceContentAuthorityRepository;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceParseOutboxRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.room.application.AgentInvocationContext;
import com.example.dispute.room.application.EvidenceAgentTurnCommand;
import com.example.dispute.room.application.EvidenceContextEnvelopeV1;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomObjectIndex;
import com.example.dispute.workflow.targete2e.ingress.rooms.MinioTargetE2eRoomCommandPayloadPublisher;
import com.example.dispute.workflow.targete2e.ingress.rooms.TargetE2eEvidenceTurnInvocationPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class EvidenceTextParseAuthorityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String CASE_ID = "CASE_0141_1";
    private static final String ACTOR_ID = "party-1";
    private static final String RAW_MARKDOWN =
            "2026-08-13，平台在线客服明确承诺退款20元，但订单、退款工单和原支付渠道均无成功退款流水。\r\n";
    private static final String CANONICAL_MARKDOWN =
            "2026-08-13，平台在线客服明确承诺退款20元，但订单、退款工单和原支付渠道均无成功退款流水。\n";

    @Test
    void supportedTextIsParsedAfterCommitAndRequiredBeforeTargetInvocation() throws Exception {
        assertPostCommitDeliveryOnlyRunsAfterCommit();

        MutableClock clock = new MutableClock(Instant.parse("2026-08-17T00:00:00Z"));
        byte[] rawBytes = RAW_MARKDOWN.getBytes(StandardCharsets.UTF_8);
        ParseHarness invalid = ParseHarness.forStoredText(clock, "EVIDENCE_BAD_1", rawBytes);
        EvidenceParseOutboxService.Enqueued invalidEnqueued = invalid.service.enqueue(invalid.evidence);
        EvidenceParseOutboxService.ClaimedJob invalidClaim =
                invalid.service.claimById(invalidEnqueued.outboxId(), Duration.ofSeconds(30)).orElseThrow();
        assertThatThrownBy(
                        () ->
                                invalid.service.completeStoredText(
                                        invalidClaim,
                                        "different admitted bytes".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(EvidenceTextContentInvalidException.class);
        assertThat(
                        invalid.service.failStoredText(
                                invalidClaim, "EVIDENCE_TEXT_CONTENT_INVALID", "hash drift"))
                .isTrue();
        assertThat(invalid.outbox.get().getStatus()).isEqualTo(EvidenceParseOutboxStatus.FAILED);
        assertThat(invalid.authority.get()).isNull();

        ParseHarness parsed = ParseHarness.forStoredText(clock, "EVIDENCE_MARKDOWN_1", rawBytes);
        EvidenceParseOutboxService.Enqueued enqueued = parsed.service.enqueue(parsed.evidence);
        EvidenceParseOutboxService.ClaimedJob crashedClaim =
                parsed.service.claimById(enqueued.outboxId(), Duration.ofSeconds(30)).orElseThrow();
        assertThat(crashedClaim.attemptCount()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(31));

        EvidenceStorage storage = mock(EvidenceStorage.class);
        when(storage.loadOriginal("evidence-originals", parsed.evidence.getFileObjectKey()))
                .thenReturn(rawBytes);
        EvidenceParseOutboxDispatcher dispatcher =
                new EvidenceParseOutboxDispatcher(parsed.service, storage);
        assertThat(dispatcher.dispatchAvailable()).isEqualTo(1);
        verify(storage).loadOriginal("evidence-originals", parsed.evidence.getFileObjectKey());
        assertThat(parsed.outbox.get().getStatus()).isEqualTo(EvidenceParseOutboxStatus.APPLIED);
        assertThat(parsed.evidence.getParseStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(parsed.evidence.getParsedText()).isEqualTo(CANONICAL_MARKDOWN);
        EvidenceContentAuthorityV1 authority = parsed.authority.get().authority();
        assertThat(authority.status()).isEqualTo("SUCCEEDED");
        assertThat(authority.parsedText()).isEqualTo(CANONICAL_MARKDOWN);
        assertThat(authority.parsedByteLength())
                .isEqualTo(CANONICAL_MARKDOWN.getBytes(StandardCharsets.UTF_8).length);
        assertThat(parsed.service.enqueue(parsed.evidence))
                .returns(enqueued.outboxId(), EvidenceParseOutboxService.Enqueued::outboxId)
                .returns(true, EvidenceParseOutboxService.Enqueued::replay);
        assertThat(parsed.service.claimById(enqueued.outboxId(), Duration.ofSeconds(30))).isEmpty();

        MinioClient minio = mock(MinioClient.class);
        Map<String, byte[]> publishedObjects = new LinkedHashMap<>();
        List<byte[]> publishedBodies = new ArrayList<>();
        doAnswer(
                        invocation -> {
                            PutObjectArgs args = invocation.getArgument(0);
                            byte[] body = args.stream().readAllBytes();
                            publishedObjects.put(args.object(), body);
                            publishedBodies.add(body);
                            return null;
                        })
                .when(minio)
                .putObject(any(PutObjectArgs.class));
        TargetE2eRoomObjectIndex index = mock(TargetE2eRoomObjectIndex.class);
        EvidenceContentAuthorityLookup lookup =
                (caseId, evidenceId, fileSha256, contentType, fileSize, parserVersion) ->
                        exactStoredAuthority(
                                        authority,
                                        rawBytes.length,
                                        caseId,
                                        evidenceId,
                                        fileSha256,
                                        contentType,
                                        fileSize,
                                        parserVersion)
                                ? Optional.of(
                                        new EvidenceContentAuthorityLookup.StoredAuthority(
                                                authority, rawBytes.length))
                                : Optional.empty();
        TargetE2eEvidenceTurnInvocationPublisher subject =
                new TargetE2eEvidenceTurnInvocationPublisher(
                        new MinioTargetE2eRoomCommandPayloadPublisher(
                                minio, MAPPER, "target-e2e", "room-command-inputs", index),
                        index,
                        MAPPER,
                        lookup);

        assertThatThrownBy(
                        () ->
                                subject.publish(
                                        command(),
                                        7L,
                                        CommandType.EVIDENCE_SUBMIT,
                                        pendingMarkdownTurn(rawBytes)))
                .isInstanceOf(EvidenceContentAuthorityUnavailableException.class)
                .hasMessage("supported text evidence content authority is not ready");
        assertThatThrownBy(
                        () ->
                                subject.publish(
                                        command(),
                                        7L,
                                        CommandType.EVIDENCE_SUBMIT,
                                        missingAttachmentTurn()))
                .isInstanceOf(EvidenceContentAuthorityUnavailableException.class)
                .hasMessage("supported text evidence content authority is not ready");
        verify(minio, never()).putObject(any(PutObjectArgs.class));
        verifyNoInteractions(index);

        clearInvocations(minio, index);
        assertThatThrownBy(
                        () ->
                                subject.publish(
                                        command(),
                                        7L,
                                        CommandType.EVIDENCE_SUBMIT,
                                        succeededMarkdownTurn(
                                                authority,
                                                rawBytes,
                                                "foreign-party",
                                                rawBytes.length)))
                .isInstanceOf(EvidenceContentAuthorityUnavailableException.class);
        assertThatThrownBy(
                        () ->
                                subject.publish(
                                        command(),
                                        7L,
                                        CommandType.EVIDENCE_SUBMIT,
                                        succeededMarkdownTurn(
                                                authority,
                                                rawBytes,
                                                ACTOR_ID,
                                                rawBytes.length + 1L)))
                .isInstanceOf(EvidenceContentAuthorityUnavailableException.class);
        verify(minio, never()).putObject(any(PutObjectArgs.class));
        verifyNoInteractions(index);

        TargetE2eEvidenceTurnInvocationPublisher.Published first =
                subject.publish(
                        command(),
                        7L,
                        CommandType.EVIDENCE_SUBMIT,
                        succeededMarkdownTurn(authority, rawBytes, ACTOR_ID, rawBytes.length));
        JsonNode invocation = MAPPER.readTree(publishedBodies.getFirst());
        JsonNode nestedAuthority =
                invocation.at(
                        "/evidence_turn_request/context_envelope/evidence_content_authorities/0");
        assertThat(invocation.path("schema_version").asText())
                .isEqualTo(TargetE2eEvidenceTurnInvocationPublisher.SCHEMA_VERSION);
        assertThat(invocation.has("evidence_content_authorities")).isFalse();
        assertThat(nestedAuthority.path("schema_version").asText())
                .isEqualTo("evidence_content_authority.v1");
        assertThat(nestedAuthority.path("evidence_id").asText()).isEqualTo(authority.evidenceId());
        assertThat(nestedAuthority.path("parsed_text").asText()).isEqualTo(CANONICAL_MARKDOWN);
        assertThat(nestedAuthority.path("parsed_content_sha256").asText())
                .isEqualTo(authority.parsedContentSha256());
        TargetE2eEvidenceTurnInvocationPublisher.Published replay =
                subject.publish(
                        command(),
                        7L,
                        CommandType.EVIDENCE_SUBMIT,
                        succeededMarkdownTurn(authority, rawBytes, ACTOR_ID, rawBytes.length));
        assertThat(replay.invocationHash()).isEqualTo(first.invocationHash());
        assertThat(replay.invocation().reference()).isEqualTo(first.invocation().reference());
        assertThat(publishedBodies).hasSize(2);
        assertThat(publishedBodies.get(1)).isEqualTo(publishedBodies.getFirst());

        TargetE2eEvidenceTurnInvocationPublisher.Published unsupportedReplay =
                subject.publish(
                        command(),
                        7L,
                        CommandType.EVIDENCE_SUBMIT,
                        unsupportedImageTurn());
        assertThat(unsupportedReplay.invocationHash()).isNotBlank();
        assertThat(publishedObjects).isNotEmpty();
    }

    private static void assertPostCommitDeliveryOnlyRunsAfterCommit() {
        EvidenceParseOutboxService service = mock(EvidenceParseOutboxService.class);
        EvidenceParseOutboxDispatcher dispatcher =
                new EvidenceParseOutboxDispatcher(service, mock(EvidenceStorage.class));
        PostCommitEvidenceParseDeliveryTrigger trigger =
                new PostCommitEvidenceParseDeliveryTrigger(
                        new PostCommitSideEffectExecutor((Executor) Runnable::run), dispatcher);

        TransactionSynchronizationManager.initSynchronization();
        try {
            trigger.deliveryRequested("EPARSE_COMMIT_1");
            verifyNoInteractions(service);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCommit());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(service).claimById(eq("EPARSE_COMMIT_1"), any(Duration.class));

        clearInvocations(service);
        TransactionSynchronizationManager.initSynchronization();
        try {
            trigger.deliveryRequested("EPARSE_ROLLBACK_1");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verifyNoInteractions(service);
    }

    private static boolean exactStoredAuthority(
            EvidenceContentAuthorityV1 authority,
            long fileSize,
            String caseId,
            String evidenceId,
            String fileSha256,
            String contentType,
            long requestedFileSize,
            String parserVersion) {
        return authority.caseId().equals(caseId)
                && authority.evidenceId().equals(evidenceId)
                && authority.fileSha256().equals(fileSha256)
                && authority.contentType().equals(contentType)
                && fileSize == requestedFileSize
                && authority.parserVersion().equals(parserVersion);
    }

    private static RoomGraphCommand command() {
        return new RoomGraphCommand(
                "room-graph-command.v1",
                "command-evidence-1",
                "target-evidence-run:11111111111111111111111111111111",
                "target-evidence-run:11111111111111111111111111111111:1",
                "tenant-surrogate-1",
                CASE_ID,
                com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.EVIDENCE,
                0L,
                "target-e2e-room-graph",
                "target-e2e-room-graph.v1",
                "checkpoint.v1",
                "grt.v1.11111111111111111111111111111111",
                new RoomGraphCommand.ActorScope(
                        ACTOR_ID,
                        com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole.USER,
                        Audience.USER,
                        List.of("case:" + CASE_ID + ":command:EVIDENCE_SUBMIT")),
                1L,
                "EVIDENCE_SUBMIT",
                1L,
                new RoomGraphCommand.SnapshotRef(
                        "placeholder",
                        "target-e2e-evidence-turn-invocation.v2",
                        "urn:placeholder",
                        "0".repeat(64),
                        1L),
                new RoomGraphCommand.SnapshotRef(
                        "event-1", "event.v1", "urn:event-1", "1".repeat(64), 1L),
                new RoomGraphCommand.InvocationContext(
                        "evidence-clerk",
                        "prompt-v1",
                        "model-v1",
                        "output.v1",
                        "policy-v1",
                        "guard-v1",
                        List.of(),
                        "key-1",
                        "nonce-1"),
                new RoomGraphCommand.RetryBudget(1, 1, 0),
                Instant.parse("2026-08-17T00:10:00Z"),
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                "2".repeat(64));
    }

    private static EvidenceAgentTurnCommand pendingMarkdownTurn(byte[] original) {
        return turn(
                visible(
                        "EVIDENCE_MARKDOWN_1",
                        "text/markdown",
                        original.length,
                        EvidenceContentAuthorityV1.sha256Hex(original),
                        null,
                        "PENDING",
                        ACTOR_ID),
                List.of());
    }

    private static EvidenceAgentTurnCommand succeededMarkdownTurn(
            EvidenceContentAuthorityV1 authority,
            byte[] original,
            String submittedById,
            long visibleFileSize) {
        return turn(
                visible(
                        authority.evidenceId(),
                        authority.contentType(),
                        visibleFileSize,
                        EvidenceContentAuthorityV1.sha256Hex(original),
                        authority.parsedText(),
                        "SUCCEEDED",
                        submittedById),
                List.of(authority));
    }

    private static EvidenceAgentTurnCommand unsupportedImageTurn() {
        return turn(
                visible(
                        "EVIDENCE_IMAGE_1",
                        "image/png",
                        6L,
                        "b".repeat(64),
                        null,
                        "PENDING",
                        ACTOR_ID),
                List.of());
    }

    private static EvidenceAgentTurnCommand missingAttachmentTurn() {
        return turn(
                visible(
                        "EVIDENCE_IMAGE_1",
                        "image/png",
                        6L,
                        "b".repeat(64),
                        null,
                        "PENDING",
                        ACTOR_ID),
                List.of(),
                "EVIDENCE_MISSING_1");
    }

    private static EvidenceAgentTurnCommand turn(
            EvidenceContextEnvelopeV1.VisibleEvidence evidence,
            List<EvidenceContentAuthorityV1> authorities) {
        return turn(evidence, authorities, evidence.evidenceId());
    }

    private static EvidenceAgentTurnCommand turn(
            EvidenceContextEnvelopeV1.VisibleEvidence evidence,
            List<EvidenceContentAuthorityV1> authorities,
            String currentAttachmentRef) {
        EvidenceContextEnvelopeV1 envelope =
                new EvidenceContextEnvelopeV1(
                        EvidenceContextEnvelopeV1.SCHEMA_VERSION,
                        "2026-08-17T00:00:01Z",
                        new EvidenceContextEnvelopeV1.CaseSnapshot(
                                CASE_ID,
                                1L,
                                "EVIDENCE",
                                "DISPUTE",
                                "NON_RECEIPT",
                                "USER",
                                "case",
                                "case",
                                "HIGH",
                                null,
                                null,
                                null,
                                null,
                                "AUTHENTICATED",
                                null,
                                null,
                                "EVIDENCE",
                                null),
                        null,
                        new EvidenceContextEnvelopeV1.ActorSnapshot(
                                ACTOR_ID,
                                "USER",
                                "USER",
                                "access-1",
                                "agent-session-1",
                                "PARTY_PRIVATE",
                                "evidence-clerk",
                                "memory-v1"),
                        new EvidenceContextEnvelopeV1.CurrentEvent(
                                "MESSAGE_1",
                                "PARTY_MESSAGE",
                                MessageType.PARTY_EVIDENCE_REFERENCE,
                                ACTOR_ID,
                                "USER",
                                "提交聊天记录",
                                List.of(currentAttachmentRef),
                                1,
                                "2026-08-17T00:00:00Z"),
                        List.of(evidence),
                        authorities,
                        new EvidenceContextEnvelopeV1.PrivateConversation(
                                "agent-session-1", "PARTY_PRIVATE", 0, false, List.of()),
                        new EvidenceContextEnvelopeV1.RoomPolicy(
                                "room-evidence-1",
                                com.example.dispute.room.domain.RoomType.EVIDENCE,
                                "OPEN",
                                null,
                                "USER",
                                true),
                        null);
        return new EvidenceAgentTurnCommand(
                envelope,
                new AgentInvocationContext(
                        "tenant-domain-1",
                        CASE_ID,
                        com.example.dispute.room.domain.RoomType.EVIDENCE,
                        ACTOR_ID,
                        "USER",
                        "access-1",
                        "PARTY",
                        List.of("ROOM_MESSAGE_WRITE"),
                        "EVIDENCE_CLERK",
                        "agent-invocation-1",
                        "agent-session-1",
                        "PARTY_PRIVATE",
                        "EVIDENCE_PARTY_PRIVATE",
                        List.of(ACTOR_ID),
                        List.of("USER"),
                        "evidence-clerk",
                        "memory-v1"));
    }

    private static EvidenceContextEnvelopeV1.VisibleEvidence visible(
            String evidenceId,
            String contentType,
            long fileSize,
            String fileHash,
            String parsedText,
            String parseStatus,
            String submittedById) {
        return new EvidenceContextEnvelopeV1.VisibleEvidence(
                evidenceId,
                "DOSSIER_1",
                "CHAT_RECORD",
                "USER_UPLOAD",
                "USER",
                submittedById,
                "chat.md",
                contentType,
                fileSize,
                fileHash,
                parsedText,
                parseStatus,
                "PARTIES",
                false,
                null,
                null,
                "2026-08-17T00:00:00Z",
                "2026-08-17T00:00:00Z",
                null,
                "SUBMITTED",
                "BATCH_1",
                "/api/disputes/" + CASE_ID + "/evidence/" + evidenceId + "/content");
    }

    private static final class ParseHarness {
        private final EvidenceParseOutboxService service;
        private final EvidenceItemEntity evidence;
        private final AtomicReference<EvidenceParseOutboxEntity> outbox;
        private final AtomicReference<EvidenceContentAuthorityEntity> authority;

        private ParseHarness(
                EvidenceParseOutboxService service,
                EvidenceItemEntity evidence,
                AtomicReference<EvidenceParseOutboxEntity> outbox,
                AtomicReference<EvidenceContentAuthorityEntity> authority) {
            this.service = service;
            this.evidence = evidence;
            this.outbox = outbox;
            this.authority = authority;
        }

        private static ParseHarness forStoredText(
                MutableClock clock, String evidenceId, byte[] admittedBytes) {
            EvidenceParseOutboxRepository outboxes = mock(EvidenceParseOutboxRepository.class);
            EvidenceContentAuthorityRepository authorities = mock(EvidenceContentAuthorityRepository.class);
            EvidenceItemRepository items = mock(EvidenceItemRepository.class);
            AtomicReference<EvidenceParseOutboxEntity> outbox = new AtomicReference<>();
            AtomicReference<EvidenceContentAuthorityEntity> authority = new AtomicReference<>();
            EvidenceItemEntity evidence =
                    EvidenceItemEntity.uploaded(
                            evidenceId,
                            CASE_ID,
                            "DOSSIER_1",
                            "CHAT_RECORD",
                            "USER_UPLOAD",
                            "USER",
                            ACTOR_ID,
                            "evidence-originals",
                            CASE_ID + "/" + evidenceId + "/chat.md",
                            EvidenceContentAuthorityV1.sha256Hex(admittedBytes),
                            "chat.md",
                            "text/markdown",
                            admittedBytes.length,
                            "PARTIES",
                            OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            when(outboxes.findByEvidenceIdAndFileSha256AndParserVersion(
                            anyString(), anyString(), anyString()))
                    .thenAnswer(ignored -> Optional.ofNullable(outbox.get()));
            when(outboxes.save(any(EvidenceParseOutboxEntity.class)))
                    .thenAnswer(
                            invocation -> {
                                EvidenceParseOutboxEntity saved = invocation.getArgument(0);
                                outbox.set(saved);
                                return saved;
                            });
            when(outboxes.lockById(anyString()))
                    .thenAnswer(
                            invocation ->
                                    Optional.ofNullable(outbox.get())
                                            .filter(
                                                    row ->
                                                            row.getId()
                                                                    .equals(
                                                                            invocation.getArgument(
                                                                                    0))));
            when(outboxes.lockClaimable(
                            any(OffsetDateTime.class),
                            eq(EvidenceParseOutboxStatus.PENDING),
                            eq(EvidenceParseOutboxStatus.IN_FLIGHT),
                            any(Pageable.class)))
                    .thenAnswer(
                            invocation -> {
                                EvidenceParseOutboxEntity row = outbox.get();
                                OffsetDateTime now = invocation.getArgument(0);
                                return row != null && row.claimableAt(now) ? List.of(row) : List.of();
                            });
            when(items.findById(anyString()))
                    .thenAnswer(
                            invocation ->
                                    evidence.getId().equals(invocation.getArgument(0))
                                            ? Optional.of(evidence)
                                            : Optional.empty());
            when(authorities.findByParseOutboxId(anyString()))
                    .thenAnswer(
                            invocation -> {
                                EvidenceContentAuthorityEntity stored = authority.get();
                                return stored != null
                                                && stored.getParseOutboxId()
                                                        .equals(invocation.getArgument(0))
                                        ? Optional.of(stored)
                                        : Optional.empty();
                            });
            when(authorities.save(any(EvidenceContentAuthorityEntity.class)))
                    .thenAnswer(
                            invocation -> {
                                EvidenceContentAuthorityEntity saved = invocation.getArgument(0);
                                authority.set(saved);
                                return saved;
                            });
            return new ParseHarness(
                    new EvidenceParseOutboxService(outboxes, authorities, items, clock),
                    evidence,
                    outbox,
                    authority);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("only UTC is supported by this focused test clock");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
