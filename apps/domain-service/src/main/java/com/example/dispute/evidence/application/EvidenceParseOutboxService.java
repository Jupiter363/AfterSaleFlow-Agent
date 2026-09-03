package com.example.dispute.evidence.application;

import com.example.dispute.evidence.domain.EvidenceParseOutboxStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceContentAuthorityEntity;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceParseOutboxEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceContentAuthorityRepository;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceParseOutboxRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owns durable stored-text parse admission, leasing, and immutable content authority. */
@Service
public class EvidenceParseOutboxService {
    public static final String PARSER_VERSION = "java-stored-utf8.v1";
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    private final EvidenceParseOutboxRepository outboxes;
    private final EvidenceContentAuthorityRepository authorities;
    private final EvidenceItemRepository evidenceItems;
    private final Clock clock;

    public EvidenceParseOutboxService(
            EvidenceParseOutboxRepository outboxes,
            EvidenceContentAuthorityRepository authorities,
            EvidenceItemRepository evidenceItems,
            Clock clock) {
        this.outboxes = outboxes;
        this.authorities = authorities;
        this.evidenceItems = evidenceItems;
        this.clock = clock;
    }

    /** Called inside the upload transaction, after original-file and EvidenceItem persistence. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Enqueued enqueue(EvidenceItemEntity evidence) {
        requireEvidenceCoordinate(evidence);
        if (!EvidenceContentAuthorityV1.isSupportedTextContentType(evidence.getContentType())) {
            throw new IllegalArgumentException("durable content authority is reserved for supported text evidence");
        }
        String requestKey = requestKey(evidence.getId(), evidence.getFileHash(), PARSER_VERSION);
        Optional<EvidenceParseOutboxEntity> existing =
                outboxes.findByEvidenceIdAndFileSha256AndParserVersion(
                        evidence.getId(), evidence.getFileHash(), PARSER_VERSION);
        if (existing.isPresent()) {
            EvidenceParseOutboxEntity row = existing.orElseThrow();
            requireSameCoordinate(row, evidence, requestKey);
            return new Enqueued(row.getId(), true);
        }
        OffsetDateTime now = now();
        EvidenceParseOutboxEntity row =
                EvidenceParseOutboxEntity.pending(
                        "EPARSE_" + requestKey.substring(0, 32),
                        evidence.getCaseId(),
                        evidence.getId(),
                        evidence.getFileHash(),
                        evidence.getContentType(),
                        evidence.getFileSize(),
                        PARSER_VERSION,
                        evidence.getFileBucket(),
                        evidence.getFileObjectKey(),
                        requestKey,
                        now);
        outboxes.save(row);
        return new Enqueued(row.getId(), false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedJob> claimById(String outboxId, Duration leaseDuration) {
        OffsetDateTime now = now();
        return outboxes.lockById(outboxId).filter(row -> row.claimableAt(now)).map(row -> claim(row, now, leaseDuration));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedJob> claimNext(Duration leaseDuration) {
        OffsetDateTime now = now();
        List<EvidenceParseOutboxEntity> rows =
                outboxes.lockClaimable(
                        now,
                        EvidenceParseOutboxStatus.PENDING,
                        EvidenceParseOutboxStatus.IN_FLIGHT,
                        PageRequest.of(0, 1));
        return rows.isEmpty() ? Optional.empty() : Optional.of(claim(rows.getFirst(), now, leaseDuration));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean defer(ClaimedJob job, String errorCode, String errorDetail) {
        OffsetDateTime now = now();
        return outboxes
                .lockById(job.outboxId())
                .filter(row -> row.getStatus() == EvidenceParseOutboxStatus.IN_FLIGHT)
                .map(
                        row -> {
                            row.defer(job.leaseOwner(), errorCode, errorDetail, now.plus(RETRY_DELAY), now);
                            return true;
                        })
                .orElse(false);
    }

    /**
     * The worker reads only the exact stored coordinate admitted by upload, then atomically commits
     * the canonical text authority, item projection and outbox terminal state.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Completion completeStoredText(ClaimedJob job, byte[] storedBytes) {
        OffsetDateTime completedAt = now();
        EvidenceParseOutboxEntity outbox =
                outboxes
                        .lockById(job.outboxId())
                        .orElseThrow(() -> new IllegalStateException("evidence parse outbox is missing"));
        if (outbox.getStatus() != EvidenceParseOutboxStatus.IN_FLIGHT
                || !Objects.equals(outbox.getLeaseOwner(), job.leaseOwner())
                || outbox.getLeaseExpiresAt() == null
                || !outbox.getLeaseExpiresAt().isAfter(completedAt)) {
            throw new IllegalStateException("evidence parse outbox lease is stale");
        }
        EvidenceItemEntity evidence =
                evidenceItems
                        .findById(job.evidenceId())
                        .orElseThrow(
                                () ->
                                        new EvidenceTextContentInvalidException(
                                                "evidence parse source is missing"));
        try {
            requireSameCoordinate(outbox, evidence, job.requestKey());
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new EvidenceTextContentInvalidException(
                    "stored text evidence coordinate drifted", failure);
        }
        if (storedBytes == null
                || storedBytes.length != outbox.getFileSize()
                || !EvidenceContentAuthorityV1.sha256Hex(storedBytes).equals(outbox.getFileSha256())) {
            throw new EvidenceTextContentInvalidException(
                    "stored text evidence source binding is invalid");
        }
        String decoded = strictUtf8(storedBytes);
        EvidenceContentAuthorityV1 authority;
        try {
            authority =
                    EvidenceContentAuthorityV1.completed(
                            outbox.getCaseId(),
                            outbox.getEvidenceId(),
                            outbox.getFileSha256(),
                            outbox.getContentType(),
                            outbox.getParserVersion(),
                            decoded,
                            completedAt);
        } catch (IllegalArgumentException failure) {
            throw new EvidenceTextContentInvalidException(
                    "stored text evidence content is invalid", failure);
        }
        Optional<EvidenceContentAuthorityEntity> existing = authorities.findByParseOutboxId(outbox.getId());
        if (existing.isPresent()) {
            throw new EvidenceTextContentInvalidException(
                    "evidence content authority already exists before terminal outbox state");
        }
        String authorityId =
                "ECA_"
                        + EvidenceContentAuthorityV1.sha256Hex(
                                        (outbox.getRequestKey() + "\n" + authority.parsedContentSha256())
                                                .getBytes(StandardCharsets.UTF_8))
                                .substring(0, 32);
        authorities.save(
                EvidenceContentAuthorityEntity.from(
                        authorityId,
                        outbox.getId(),
                        authority,
                        outbox.getFileSize(),
                        outbox.getSourceBucket(),
                        outbox.getSourceObjectKey(),
                        completedAt));
        evidence.applyParseSuccess(
                authority.parsedText(),
                "{\"content_authority_schema_version\":\"evidence_content_authority.v1\",\"source\":\"STORED_UTF8\"}",
                "SYSTEM");
        outbox.markApplied(job.leaseOwner(), completedAt);
        return new Completion(authority, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failStoredText(ClaimedJob job, String errorCode, String detail) {
        OffsetDateTime now = now();
        return outboxes
                .lockById(job.outboxId())
                .filter(row -> row.getStatus() == EvidenceParseOutboxStatus.IN_FLIGHT)
                .map(
                        row -> {
                            row.markFailed(job.leaseOwner(), errorCode, detail, now);
                            EvidenceItemEntity evidence =
                                    evidenceItems
                                            .findById(job.evidenceId())
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "evidence parse source is missing"));
                            evidence.applyParseFailure(
                                    "{\"error_code\":\"" + errorCode + "\"}", "SYSTEM");
                            return true;
                        })
                .orElse(false);
    }

    private ClaimedJob claim(
            EvidenceParseOutboxEntity row, OffsetDateTime now, Duration leaseDuration) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("evidence parse outbox lease duration is invalid");
        }
        String leaseOwner = UUID.randomUUID().toString();
        OffsetDateTime leaseExpiresAt = now.plus(leaseDuration);
        row.claim(leaseOwner, now, leaseExpiresAt);
        return new ClaimedJob(
                row.getId(),
                row.getEvidenceId(),
                row.getCaseId(),
                row.getSourceBucket(),
                row.getSourceObjectKey(),
                row.getContentType(),
                row.getFileSha256(),
                row.getFileSize(),
                row.getParserVersion(),
                row.getRequestKey(),
                leaseOwner,
                leaseExpiresAt,
                row.getAttemptCount());
    }

    private static void requireEvidenceCoordinate(EvidenceItemEntity evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.getId() == null
                || evidence.getCaseId() == null
                || evidence.getFileHash() == null
                || !evidence.getFileHash().matches("[0-9a-f]{64}")
                || evidence.getContentType() == null
                || evidence.getFileSize() == null
                || evidence.getFileSize() < 1
                || evidence.getFileBucket() == null
                || evidence.getFileObjectKey() == null) {
            throw new IllegalArgumentException("evidence parse outbox coordinate is incomplete");
        }
    }

    private static void requireSameCoordinate(
            EvidenceParseOutboxEntity outbox, EvidenceItemEntity evidence, String requestKey) {
        requireEvidenceCoordinate(evidence);
        if (!outbox.getEvidenceId().equals(evidence.getId())
                || !outbox.getCaseId().equals(evidence.getCaseId())
                || !outbox.getFileSha256().equals(evidence.getFileHash())
                || !outbox.getContentType().equals(evidence.getContentType())
                || outbox.getFileSize() != evidence.getFileSize()
                || !outbox.getSourceBucket().equals(evidence.getFileBucket())
                || !outbox.getSourceObjectKey().equals(evidence.getFileObjectKey())
                || !outbox.getRequestKey().equals(requestKey)) {
            throw new IllegalStateException("evidence parse outbox coordinate drifted");
        }
    }

    private static String requestKey(String evidenceId, String fileSha256, String parserVersion) {
        return EvidenceContentAuthorityV1.sha256Hex(
                ("evidence-parse-request.v1\n" + evidenceId + "\n" + fileSha256 + "\n" + parserVersion)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String strictUtf8(byte[] bytes) {
        try {
            CharBuffer decoded =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException failure) {
            throw new EvidenceTextContentInvalidException(
                    "stored text evidence is not strict UTF-8", failure);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public record Enqueued(String outboxId, boolean replay) {}

    public record ClaimedJob(
            String outboxId,
            String evidenceId,
            String caseId,
            String sourceBucket,
            String sourceObjectKey,
            String contentType,
            String fileSha256,
            long fileSize,
            String parserVersion,
            String requestKey,
            String leaseOwner,
            OffsetDateTime leaseExpiresAt,
            int attemptCount) {
    }

    public record Completion(EvidenceContentAuthorityV1 authority, boolean replay) {}
}
