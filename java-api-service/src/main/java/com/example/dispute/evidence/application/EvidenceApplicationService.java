package com.example.dispute.evidence.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class EvidenceApplicationService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(EvidenceApplicationService.class);
    private static final long MAX_FILE_SIZE = 25L * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of(
                    "image/png",
                    "image/jpeg",
                    "application/pdf",
                    "text/plain",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final FulfillmentCaseRepository caseRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final EvidenceDossierRepository dossierRepository;
    private final EvidenceStorage storage;
    private final OcrTaskClient ocrTaskClient;
    private final EvidenceSearchIndexer searchIndexer;
    private final ObjectMapper objectMapper;
    private final AuditRecorder auditRecorder;

    public EvidenceApplicationService(
            FulfillmentCaseRepository caseRepository,
            EvidenceItemRepository evidenceRepository,
            EvidenceDossierRepository dossierRepository,
            EvidenceStorage storage,
            OcrTaskClient ocrTaskClient,
            EvidenceSearchIndexer searchIndexer,
            ObjectMapper objectMapper,
            AuditRecorder auditRecorder) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.dossierRepository = dossierRepository;
        this.storage = storage;
        this.ocrTaskClient = ocrTaskClient;
        this.searchIndexer = searchIndexer;
        this.objectMapper = objectMapper;
        this.auditRecorder = auditRecorder;
    }

    @Transactional
    public EvidenceView upload(
            String caseId,
            MultipartFile file,
            String evidenceType,
            String sourceType,
            String visibility,
            OffsetDateTime occurredAt,
            AuthenticatedActor actor) {
        FulfillmentCaseEntity disputeCase = authorizedCase(caseId, actor);
        validateFile(file);
        byte[] content = bytes(file);
        validateSignature(file.getContentType(), content);
        String hash = sha256(content);
        var duplicate =
                evidenceRepository.findByCaseIdAndFileHashAndSourceType(
                        caseId, hash, sourceType);
        if (duplicate.isPresent()) {
            return toView(duplicate.get());
        }

        EvidenceDossierEntity dossier =
                dossierRepository
                        .findByCaseId(caseId)
                        .orElseGet(
                                () -> {
                                    EvidenceDossierEntity created =
                                            EvidenceDossierEntity.collecting(
                                                    "DOSSIER_" + compactUuid(),
                                                    caseId,
                                                    actor.actorId());
                                    dossierRepository.save(created);
                                    return created;
                                });
        String evidenceId = "EVIDENCE_" + compactUuid();
        EvidenceStorage.StoredObject object =
                storage.storeOriginal(
                        caseId,
                        evidenceId,
                        safeFilename(file.getOriginalFilename()),
                        file.getContentType(),
                        content);
        EvidenceItemEntity entity =
                EvidenceItemEntity.uploaded(
                        evidenceId,
                        disputeCase.getId(),
                        dossier.getId(),
                        required(evidenceType, "evidenceType"),
                        required(sourceType, "sourceType"),
                        actor.role().name(),
                        actor.actorId(),
                        object.bucket(),
                        object.objectKey(),
                        hash,
                        safeFilename(file.getOriginalFilename()),
                        file.getContentType(),
                        file.getSize(),
                        required(visibility, "visibility"),
                        occurredAt);
        EvidenceView view = toView(evidenceRepository.save(entity));
        auditRecorder.record(
                actor,
                "EVIDENCE_UPLOADED",
                "EVIDENCE_ITEM",
                view.id(),
                caseId,
                Map.of(),
                Map.of(
                        "file_hash", view.fileHash(),
                        "source_type", view.sourceType(),
                        "visibility", view.visibility()));
        triggerNonBlockingIntegrations(view);
        return view;
    }

    @Transactional
    public BuildDossierResult buildDossier(
            String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity disputeCase = authorizedCase(caseId, actor);
        List<EvidenceItemEntity> items =
                evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                caseId);
        List<BuildDossierResult.TimelineEntry> timeline =
                items.stream()
                        .map(
                                item ->
                                        new BuildDossierResult.TimelineEntry(
                                                item.getId(),
                                                item.getEvidenceType(),
                                                item.getSourceType(),
                                                item.getOccurredAt() == null
                                                        ? item.getCreatedAt()
                                                        : item.getOccurredAt(),
                                                item.getOriginalFilename()))
                        .toList();
        Map<String, Object> summary =
                Map.of(
                        "evidence_count", items.size(),
                        "pending_parse_count",
                                items.stream()
                                        .filter(
                                                item ->
                                                        !"SUCCEEDED"
                                                                .equals(
                                                                        item.getParseStatus()
                                                                                .name()))
                                        .count(),
                        "sources",
                                items.stream()
                                        .map(EvidenceItemEntity::getSourceType)
                                        .distinct()
                                        .sorted()
                                        .toList());
        String summaryJson = writeJson(summary);
        String timelineJson = writeJson(timeline);
        List<Map<String, Object>> matrix =
                items.stream()
                        .map(
                                item ->
                                        Map.<String, Object>of(
                                                "evidence_id", item.getId(),
                                                "relation_type", "UNMAPPED",
                                                "source_type", item.getSourceType(),
                                                "support_strength", 0.0))
                        .toList();
        String matrixJson = writeJson(matrix);
        EvidenceDossierEntity dossier =
                dossierRepository
                        .findByCaseId(caseId)
                        .map(
                                existing -> {
                                    existing.rebuild(
                                            actor.actorId(),
                                            summaryJson,
                                            timelineJson,
                                            matrixJson);
                                    return existing;
                                })
                        .orElseGet(
                                () ->
                                        EvidenceDossierEntity.firstBuild(
                                                "DOSSIER_" + compactUuid(),
                                                caseId,
                                                actor.actorId(),
                                                summaryJson,
                                                timelineJson,
                                                matrixJson));
        EvidenceDossierEntity saved = dossierRepository.save(dossier);
        disputeCase.markDossierBuilt(actor.actorId());
        auditRecorder.record(
                actor,
                "DOSSIER_BUILT",
                "EVIDENCE_DOSSIER",
                saved.getId(),
                caseId,
                Map.of("previous_version", saved.getDossierVersion() - 1),
                Map.of(
                        "version", saved.getDossierVersion(),
                        "evidence_count", items.size()));
        return new BuildDossierResult(
                saved.getId(),
                caseId,
                saved.getDossierVersion(),
                summary,
                timeline,
                matrix);
    }

    @Transactional(readOnly = true)
    public BuildDossierResult getDossier(
            String caseId, AuthenticatedActor actor) {
        authorizedCase(caseId, actor);
        EvidenceDossierEntity dossier =
                dossierRepository
                        .findByCaseId(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.EVIDENCE_NOT_FOUND,
                                                "evidence dossier not found",
                                                Map.of("case_id", caseId)));
        return new BuildDossierResult(
                dossier.getId(),
                caseId,
                dossier.getDossierVersion(),
                readJsonMap(dossier.getSummaryJson()),
                readTimeline(dossier.getTimelineJson()),
                readMatrix(dossier.getMatrixSummaryJson()));
    }

    private void triggerNonBlockingIntegrations(EvidenceView view) {
        try {
            searchIndexer.indexMetadata(view);
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Evidence metadata indexing deferred: evidence_id={}, error_type={}",
                    view.id(),
                    failure.getClass().getSimpleName());
        }
        try {
            ocrTaskClient.createParseTask(
                    new OcrTaskClient.ParseTask(
                            view.id(),
                            view.caseId(),
                            view.fileBucket(),
                            view.fileObjectKey(),
                            view.contentType()));
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "OCR task creation deferred: evidence_id={}, error_type={}",
                    view.id(),
                    failure.getClass().getSimpleName());
        }
    }

    private FulfillmentCaseEntity authorizedCase(
            String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity entity =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "case not found",
                                                Map.of("case_id", caseId)));
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(entity.getUserId());
                    case MERCHANT -> actor.actorId().equals(entity.getMerchantId());
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                };
        if (!allowed) {
            throw new ForbiddenException("actor cannot access evidence for this case");
        }
        return entity;
    }

    private static void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file must not be empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("file exceeds 25 MiB");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new IllegalArgumentException("unsupported content type");
        }
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot read uploaded file", exception);
        }
    }

    private static void validateSignature(String contentType, byte[] content) {
        boolean valid =
                switch (contentType) {
                    case "image/png" ->
                            startsWith(
                                    content,
                                    new byte[] {
                                        (byte) 0x89,
                                        0x50,
                                        0x4E,
                                        0x47,
                                        0x0D,
                                        0x0A,
                                        0x1A,
                                        0x0A
                                    });
                    case "image/jpeg" ->
                            startsWith(
                                    content,
                                    new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
                    case "application/pdf" ->
                            startsWith(content, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    case "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                            startsWith(content, new byte[] {'P', 'K', 0x03, 0x04});
                    case "text/plain" ->
                            java.util.stream.IntStream.range(0, content.length)
                                    .noneMatch(index -> content[index] == 0);
                    default -> false;
                };
        if (!valid) {
            throw new IllegalArgumentException(
                    "file signature does not match content type");
        }
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (content[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safeFilename(String filename) {
        String value =
                filename == null || filename.isBlank() ? "evidence.bin" : filename;
        value = value.replace('\\', '_').replace('/', '_');
        return value.length() > 200 ? value.substring(value.length() - 200) : value;
    }

    private EvidenceView toView(EvidenceItemEntity entity) {
        return new EvidenceView(
                entity.getId(),
                entity.getCaseId(),
                entity.getEvidenceType(),
                entity.getSourceType(),
                entity.getFileBucket(),
                entity.getFileObjectKey(),
                entity.getFileHash(),
                entity.getOriginalFilename(),
                entity.getContentType(),
                entity.getFileSize(),
                entity.getParseStatus().name(),
                entity.getVisibility(),
                entity.isDesensitized(),
                entity.getOccurredAt(),
                entity.getCreatedAt());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize evidence dossier", exception);
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid dossier summary", exception);
        }
    }

    private List<BuildDossierResult.TimelineEntry> readTimeline(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid dossier timeline", exception);
        }
    }

    private List<Map<String, Object>> readMatrix(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid dossier matrix", exception);
        }
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
