/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：编排证据应用规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「upload」、「buildDossier」、「getDossier」、「content」、「contentForModel」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.domain.EvidenceSubmissionStatus;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

// 所属模块：【证据与版本化卷宗 / 应用编排层】类型「EvidenceApplicationService」。
// 类型职责：编排证据应用规则、权限校验与事实读写；本类型显式提供 「EvidenceApplicationService」、「upload」、「upload」、「modelProcessingAuthorization」、「buildDossier」、「getDossier」。
// 协作关系：主要由 「EvidenceController.content」、「EvidenceController.upload」、「InternalEvidenceController.content」、「EvidenceApplicationServiceTest.buildsVersionedTimelineWithoutResponsibilityOrDecisionFields」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
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
                    "text/markdown",
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

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.EvidenceApplicationService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceDossierRepository,EvidenceStorage,OcrTaskClient,EvidenceSearchIndexer,ObjectMapper,AuditRecorder)」。
    // 具体功能：「EvidenceApplicationService.EvidenceApplicationService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceDossierRepository,EvidenceStorage,OcrTaskClient,EvidenceSearchIndexer,ObjectMapper,AuditRecorder)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「evidenceRepository」(EvidenceItemRepository)、「dossierRepository」(EvidenceDossierRepository)、「storage」(EvidenceStorage)、「ocrTaskClient」(OcrTaskClient)、「searchIndexer」(EvidenceSearchIndexer)、「objectMapper」(ObjectMapper)、「auditRecorder」(AuditRecorder) 并保存为「EvidenceApplicationService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「EvidenceApplicationService.EvidenceApplicationService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceDossierRepository,EvidenceStorage,OcrTaskClient,EvidenceSearchIndexer,ObjectMapper,AuditRecorder)」的上游创建点包括 「EvidenceApplicationServiceTest.setUp」。
    // 下游影响：「EvidenceApplicationService.EvidenceApplicationService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceDossierRepository,EvidenceStorage,OcrTaskClient,EvidenceSearchIndexer,ObjectMapper,AuditRecorder)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceApplicationService.EvidenceApplicationService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceDossierRepository,EvidenceStorage,OcrTaskClient,EvidenceSearchIndexer,ObjectMapper,AuditRecorder)」负责主链路中的“证据应用服务”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
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

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.upload(String,MultipartFile,String,String,String,OffsetDateTime,AuthenticatedActor)」。
    // 具体功能：「EvidenceApplicationService.upload(String,MultipartFile,String,String,String,OffsetDateTime,AuthenticatedActor)」：提供「upload」的便捷重载：接收 「caseId」(String)、「file」(MultipartFile)、「evidenceType」(String)、「sourceType」(String)、「visibility」(String)、「occurredAt」(OffsetDateTime)、「actor」(AuthenticatedActor)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「EvidenceApplicationService.upload(String,MultipartFile,String,String,String,OffsetDateTime,AuthenticatedActor)」的上游调用点包括 「EvidenceController.upload」、「EvidenceApplicationService.upload」、「EvidenceApplicationServiceTest.uploadsOriginalWithHashAndMetadataEvenWhenOcrTriggerFails」、「EvidenceApplicationServiceTest.duplicateAuthorizationMergesWithExistingMetadata」。
    // 下游影响：「EvidenceApplicationService.upload(String,MultipartFile,String,String,String,OffsetDateTime,AuthenticatedActor)」向下依次触达 「upload」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceApplicationService.upload(String,MultipartFile,String,String,String,OffsetDateTime,AuthenticatedActor)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public EvidenceView upload(
            String caseId,
            MultipartFile file,
            String evidenceType,
            String sourceType,
            String visibility,
            OffsetDateTime occurredAt,
            AuthenticatedActor actor) {
        return upload(
                caseId,
                file,
                evidenceType,
                sourceType,
                visibility,
                false,
                occurredAt,
                actor);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.upload(String,MultipartFile,String,String,String,boolean,OffsetDateTime,AuthenticatedActor)」。
    // 具体功能：「EvidenceApplicationService.upload(String,MultipartFile,String,String,String,boolean,OffsetDateTime,AuthenticatedActor)」：上传证据：先由 Spring 事务代理统一提交数据库变化，再把新状态写入 PostgreSQL 事实表；实际协作者为 「findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc」、「evidenceRepository.save」、「dossierRepository.findByCaseId」、「dossierRepository.save」；处理的关键状态/协议值包括 「model_processing_authorized」、「EVIDENCE_MODEL_PROCESSING_AUTHORIZED」、「EVIDENCE_ITEM」、「DOSSIER_」，最终返回「EvidenceView」。
    // 上游调用：「EvidenceApplicationService.upload(String,MultipartFile,String,String,String,boolean,OffsetDateTime,AuthenticatedActor)」的上游调用点包括 「EvidenceController.upload」、「EvidenceApplicationService.upload」、「EvidenceApplicationServiceTest.uploadsOriginalWithHashAndMetadataEvenWhenOcrTriggerFails」、「EvidenceApplicationServiceTest.duplicateAuthorizationMergesWithExistingMetadata」。
    // 下游影响：「EvidenceApplicationService.upload(String,MultipartFile,String,String,String,boolean,OffsetDateTime,AuthenticatedActor)」向下依次触达 「findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc」、「evidenceRepository.save」、「dossierRepository.findByCaseId」、「dossierRepository.save」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceApplicationService.upload(String,MultipartFile,String,String,String,boolean,OffsetDateTime,AuthenticatedActor)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public EvidenceView upload(
            String caseId,
            MultipartFile file,
            String evidenceType,
            String sourceType,
            String visibility,
            boolean modelProcessingAuthorized,
            OffsetDateTime occurredAt,
            AuthenticatedActor actor) {
        FulfillmentCaseEntity disputeCase = authorizedCase(caseId, actor);
        validateSourceType(sourceType, actor);
        validateFile(file);
        byte[] content = bytes(file);
        validateSignature(file.getContentType(), content);
        String hash = sha256(content);
        var duplicate =
                evidenceRepository
                        .findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc(
                                caseId, hash, sourceType);
        if (duplicate.isPresent()) {
            EvidenceItemEntity existing = duplicate.get();
            if (modelProcessingAuthorized
                    && !Boolean.TRUE.equals(
                            readJsonMap(existing.getMetadataJson())
                                    .get("model_processing_authorized"))) {
                existing.authorizeModelProcessing(
                        modelProcessingAuthorization(existing.getMetadataJson(), actor),
                        actor.actorId());
                evidenceRepository.save(existing);
                auditRecorder.record(
                        actor,
                        "EVIDENCE_MODEL_PROCESSING_AUTHORIZED",
                        "EVIDENCE_ITEM",
                        existing.getId(),
                        caseId,
                        Map.of("model_processing_authorized", false),
                        Map.of("model_processing_authorized", true));
            }
            return toView(existing);
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
        if (modelProcessingAuthorized) {
            entity.authorizeModelProcessing(
                    modelProcessingAuthorization(entity.getMetadataJson(), actor),
                    actor.actorId());
        }
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
                        "visibility", view.visibility(),
                        "model_processing_authorized", modelProcessingAuthorized));
        triggerNonBlockingIntegrations(view);
        return view;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.modelProcessingAuthorization(String,AuthenticatedActor)」。
    // 具体功能：「EvidenceApplicationService.modelProcessingAuthorization(String,AuthenticatedActor)」：构建modelProcessing授权；实际协作者为 「actor.actorId」、「readJsonMap」、「writeJson」；处理的关键状态/协议值包括 「model_processing_authorized」、「authorization_scope」、「CURRENT_DISPUTE_EVIDENCE_REVIEW」、「authorized_by」，最终返回「String」。
    // 上游调用：「EvidenceApplicationService.modelProcessingAuthorization(String,AuthenticatedActor)」的上游调用点包括 「EvidenceApplicationService.upload」。
    // 下游影响：「EvidenceApplicationService.modelProcessingAuthorization(String,AuthenticatedActor)」向下依次触达 「actor.actorId」、「readJsonMap」、「writeJson」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceApplicationService.modelProcessingAuthorization(String,AuthenticatedActor)」负责主链路中的“modelProcessing授权”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private String modelProcessingAuthorization(
            String existingMetadataJson, AuthenticatedActor actor) {
        Map<String, Object> metadata =
                new LinkedHashMap<>(readJsonMap(existingMetadataJson));
        metadata.put("model_processing_authorized", true);
        metadata.put("authorization_scope", "CURRENT_DISPUTE_EVIDENCE_REVIEW");
        metadata.put("authorized_by", actor.actorId());
        return writeJson(metadata);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.buildDossier(String,AuthenticatedActor)」。
    // 具体功能：「EvidenceApplicationService.buildDossier(String,AuthenticatedActor)」：组装卷宗：先由 Spring 事务代理统一提交数据库变化，再把新状态写入 PostgreSQL 事实表；实际协作者为 「findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「dossierRepository.findByCaseId」、「dossierRepository.save」、「EvidenceDossierEntity.firstBuild」；处理的关键状态/协议值包括 「evidence_count」、「pending_parse_count」、「SUCCEEDED」、「sources」，最终返回「BuildDossierResult」。
    // 上游调用：「EvidenceApplicationService.buildDossier(String,AuthenticatedActor)」的上游调用点包括 「EvidenceApplicationServiceTest.buildsVersionedTimelineWithoutResponsibilityOrDecisionFields」。
    // 下游影响：「EvidenceApplicationService.buildDossier(String,AuthenticatedActor)」向下依次触达 「findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「dossierRepository.findByCaseId」、「dossierRepository.save」、「EvidenceDossierEntity.firstBuild」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceApplicationService.buildDossier(String,AuthenticatedActor)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public BuildDossierResult buildDossier(
            String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity disputeCase = authorizedCase(caseId, actor);
        List<EvidenceItemEntity> items =
                evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                caseId)
                        .stream()
                        .filter(EvidenceApplicationService::isSubmittedEvidence)
                        .toList();
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
                items.stream().map(this::toView).toList(),
                timeline,
                matrix);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.getDossier(String,AuthenticatedActor)」。
    // 具体功能：「EvidenceApplicationService.getDossier(String,AuthenticatedActor)」：读取卷宗：先由 Spring 事务代理统一提交数据库变化，再把 Optional 空值转换为明确业务异常；实际协作者为 「dossierRepository.findByCaseId」、「findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「dossier.getId」、「dossier.getDossierVersion」；处理的关键状态/协议值包括 「case_id」，最终返回「BuildDossierResult」。
    // 上游调用：「EvidenceApplicationService.getDossier(String,AuthenticatedActor)」的上游调用点包括 「EvidenceApplicationServiceTest.getsFrozenDossierWithObjectShapedEvidenceMatrix」。
    // 下游影响：「EvidenceApplicationService.getDossier(String,AuthenticatedActor)」向下依次触达 「dossierRepository.findByCaseId」、「findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「dossier.getId」、「dossier.getDossierVersion」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceApplicationService.getDossier(String,AuthenticatedActor)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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
        List<EvidenceView> evidences =
                evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                caseId)
                        .stream()
                        .filter(EvidenceApplicationService::isSubmittedEvidence)
                        .map(this::toView)
                        .toList();
        return new BuildDossierResult(
                dossier.getId(),
                caseId,
                dossier.getDossierVersion(),
                readJsonMap(dossier.getSummaryJson()),
                evidences,
                readTimeline(dossier.getTimelineJson()),
                readMatrix(dossier.getMatrixSummaryJson()));
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.triggerNonBlockingIntegrations(EvidenceView)」。
    // 具体功能：「EvidenceApplicationService.triggerNonBlockingIntegrations(EvidenceView)」：触发非阻塞外部集成；实际协作者为 「searchIndexer.indexMetadata」、「ocrTaskClient.createParseTask」、「LOGGER.warn」、「view.id」，最终返回「void」。
    // 上游调用：「EvidenceApplicationService.triggerNonBlockingIntegrations(EvidenceView)」的上游调用点包括 「EvidenceApplicationService.upload」。
    // 下游影响：「EvidenceApplicationService.triggerNonBlockingIntegrations(EvidenceView)」向下依次触达 「searchIndexer.indexMetadata」、「ocrTaskClient.createParseTask」、「LOGGER.warn」、「view.id」。
    // 系统意义：「EvidenceApplicationService.triggerNonBlockingIntegrations(EvidenceView)」负责主链路中的“非阻塞外部集成”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
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

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.validateSourceType(String,AuthenticatedActor)」。
    // 具体功能：「EvidenceApplicationService.validateSourceType(String,AuthenticatedActor)」：校验来源类型；实际协作者为 「actor.role」；不满足前置条件时抛出 「ForbiddenException」；处理的关键状态/协议值包括 「USER_UPLOAD」、「MERCHANT_UPLOAD」、「PLATFORM_UPLOAD」，最终返回「void」。
    // 上游调用：「EvidenceApplicationService.validateSourceType(String,AuthenticatedActor)」的上游调用点包括 「EvidenceApplicationService.upload」。
    // 下游影响：「EvidenceApplicationService.validateSourceType(String,AuthenticatedActor)」向下依次触达 「actor.role」。
    // 系统意义：「EvidenceApplicationService.validateSourceType(String,AuthenticatedActor)」在“来源类型”进入下游前阻断非法状态；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static void validateSourceType(
            String sourceType, AuthenticatedActor actor) {
        String expected =
                switch (actor.role()) {
                    case USER -> "USER_UPLOAD";
                    case MERCHANT -> "MERCHANT_UPLOAD";
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM ->
                            "PLATFORM_UPLOAD";
                };
        if (!expected.equals(sourceType)) {
            throw new ForbiddenException(
                    "evidence source does not match the authenticated actor");
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.authorizedCase(String,AuthenticatedActor)」。
    // 具体功能：「EvidenceApplicationService.authorizedCase(String,AuthenticatedActor)」：授权authorized案件：先按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findById」、「actor.role」、「actor.actorId」、「entity.getUserId」；不满足前置条件时抛出 「ForbiddenException」；处理的关键状态/协议值包括 「case_id」，最终返回「FulfillmentCaseEntity」。
    // 上游调用：「EvidenceApplicationService.authorizedCase(String,AuthenticatedActor)」的上游调用点包括 「EvidenceApplicationService.upload」、「EvidenceApplicationService.buildDossier」、「EvidenceApplicationService.getDossier」、「EvidenceApplicationService.evidenceItem」。
    // 下游影响：「EvidenceApplicationService.authorizedCase(String,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「actor.role」、「actor.actorId」、「entity.getUserId」；计算结果以「FulfillmentCaseEntity」交给调用方。
    // 系统意义：「EvidenceApplicationService.authorizedCase(String,AuthenticatedActor)」负责主链路中的“authorized案件”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
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

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.validateFile(MultipartFile)」。
    // 具体功能：「EvidenceApplicationService.validateFile(MultipartFile)」：校验文件；实际协作者为 「file.getSize」、「file.getContentType」；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「EvidenceApplicationService.validateFile(MultipartFile)」的上游调用点包括 「EvidenceApplicationService.upload」。
    // 下游影响：「EvidenceApplicationService.validateFile(MultipartFile)」向下依次触达 「file.getSize」、「file.getContentType」。
    // 系统意义：「EvidenceApplicationService.validateFile(MultipartFile)」在“文件”进入下游前阻断非法状态；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
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

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.bytes(MultipartFile)」。
    // 具体功能：「EvidenceApplicationService.bytes(MultipartFile)」：构建文件字节；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「byte[]」。
    // 上游调用：「EvidenceApplicationService.bytes(MultipartFile)」的上游调用点包括 「EvidenceApplicationService.upload」。
    // 下游影响：「EvidenceApplicationService.bytes(MultipartFile)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「byte[]」交给调用方。
    // 系统意义：「EvidenceApplicationService.bytes(MultipartFile)」负责主链路中的“文件字节”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot read uploaded file", exception);
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.validateSignature(String,byte[])」。
    // 具体功能：「EvidenceApplicationService.validateSignature(String,byte[])」：校验文件签名；实际协作者为 「noneMatch」、「java.util.stream.IntStream.range」；不满足前置条件时抛出 「IllegalArgumentException」；处理的关键状态/协议值包括 「%PDF-」，最终返回「void」。
    // 上游调用：「EvidenceApplicationService.validateSignature(String,byte[])」的上游调用点包括 「EvidenceApplicationService.upload」。
    // 下游影响：「EvidenceApplicationService.validateSignature(String,byte[])」向下依次触达 「noneMatch」、「java.util.stream.IntStream.range」。
    // 系统意义：「EvidenceApplicationService.validateSignature(String,byte[])」在“文件签名”进入下游前阻断非法状态；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
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
                    case "text/plain", "text/markdown" ->
                            java.util.stream.IntStream.range(0, content.length)
                                    .noneMatch(index -> content[index] == 0);
                    default -> false;
                };
        if (!valid) {
            throw new IllegalArgumentException(
                    "file signature does not match content type");
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.startsWith(byte[],byte[])」。
    // 具体功能：「EvidenceApplicationService.startsWith(byte[],byte[])」：启动starts包含，最终返回「boolean」。
    // 上游调用：「EvidenceApplicationService.startsWith(byte[],byte[])」的上游调用点包括 「EvidenceApplicationService.validateSignature」。
    // 下游影响：「EvidenceApplicationService.startsWith(byte[],byte[])」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceApplicationService.startsWith(byte[],byte[])」负责主链路中的“starts包含”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
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

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.sha256(byte[])」。
    // 具体功能：「EvidenceApplicationService.sha256(byte[])」：计算 SHA-256：先计算稳定哈希以绑定审批快照；实际协作者为 「MessageDigest.getInstance」、「HexFormat.of().formatHex」、「MessageDigest.getInstance("SHA-256").digest」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「SHA-256」，最终返回「String」。
    // 上游调用：「EvidenceApplicationService.sha256(byte[])」的上游调用点包括 「EvidenceApplicationService.upload」。
    // 下游影响：「EvidenceApplicationService.sha256(byte[])」向下依次触达 「MessageDigest.getInstance」、「HexFormat.of().formatHex」、「MessageDigest.getInstance("SHA-256").digest」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceApplicationService.sha256(byte[])」负责主链路中的“256”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.safeFilename(String)」。
    // 具体功能：「EvidenceApplicationService.safeFilename(String)」：生成安全值Filename；实际协作者为 「value.replace」、「value.replace('\\','_').replace」；处理的关键状态/协议值包括 「evidence.bin」，最终返回「String」。
    // 上游调用：「EvidenceApplicationService.safeFilename(String)」的上游调用点包括 「EvidenceApplicationService.upload」。
    // 下游影响：「EvidenceApplicationService.safeFilename(String)」向下依次触达 「value.replace」、「value.replace('\\','_').replace」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceApplicationService.safeFilename(String)」负责主链路中的“Filename”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String safeFilename(String filename) {
        String value =
                filename == null || filename.isBlank() ? "evidence.bin" : filename;
        value = value.replace('\\', '_').replace('/', '_');
        return value.length() > 200 ? value.substring(value.length() - 200) : value;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.toView(EvidenceItemEntity)」。
    // 具体功能：「EvidenceApplicationService.toView(EvidenceItemEntity)」：转换视图；实际协作者为 「entity.getId」、「entity.getCaseId」、「entity.getEvidenceType」、「entity.getSourceType」，最终返回「EvidenceView」。
    // 上游调用：「EvidenceApplicationService.toView(EvidenceItemEntity)」的上游调用点包括 「EvidenceApplicationService.upload」。
    // 下游影响：「EvidenceApplicationService.toView(EvidenceItemEntity)」向下依次触达 「entity.getId」、「entity.getCaseId」、「entity.getEvidenceType」、「entity.getSourceType」；计算结果以「EvidenceView」交给调用方。
    // 系统意义：「EvidenceApplicationService.toView(EvidenceItemEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
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
                entity.getCreatedAt(),
                entity.getSubmissionStatus().name(),
                entity.getSubmittedAt(),
                entity.getSubmissionBatchId());
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.content(String,String,AuthenticatedActor)」。
    // 具体功能：「EvidenceApplicationService.content(String,String,AuthenticatedActor)」：构建内容：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「evidenceItem」、「canReadContent」、「loadContent」；不满足前置条件时抛出 「ForbiddenException」，最终返回「EvidenceContentView」。
    // 上游调用：「EvidenceApplicationService.content(String,String,AuthenticatedActor)」的上游调用点包括 「EvidenceController.content」。
    // 下游影响：「EvidenceApplicationService.content(String,String,AuthenticatedActor)」向下依次触达 「evidenceItem」、「canReadContent」、「loadContent」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceApplicationService.content(String,String,AuthenticatedActor)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public EvidenceContentView content(
            String caseId,
            String evidenceId,
            AuthenticatedActor actor) {
        EvidenceItemEntity item = evidenceItem(caseId, evidenceId, actor);
        if (!canReadContent(item, actor)) {
            throw new ForbiddenException("actor cannot read evidence content");
        }
        return loadContent(item);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.contentForModel(String,String,AuthenticatedActor)」。
    // 具体功能：「EvidenceApplicationService.contentForModel(String,String,AuthenticatedActor)」：构建内容面向Model：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「item.isDesensitized」、「item.getMetadataJson」、「evidenceItem」、「canReadContent」；不满足前置条件时抛出 「ForbiddenException」；处理的关键状态/协议值包括 「model_processing_authorized」，最终返回「EvidenceContentView」。
    // 上游调用：「EvidenceApplicationService.contentForModel(String,String,AuthenticatedActor)」的上游调用点包括 「InternalEvidenceController.content」、「EvidenceApplicationServiceTest.systemCannotReadRawEvidenceForModelWithoutPerEvidenceAuthorization」、「EvidenceApplicationServiceTest.systemCanReadRawEvidenceForModelAfterPerEvidenceAuthorization」、「EvidenceApplicationServiceTest.systemCanReadDesensitizedEvidenceForModelWithoutRawAuthorization」。
    // 下游影响：「EvidenceApplicationService.contentForModel(String,String,AuthenticatedActor)」向下依次触达 「item.isDesensitized」、「item.getMetadataJson」、「evidenceItem」、「canReadContent」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceApplicationService.contentForModel(String,String,AuthenticatedActor)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public EvidenceContentView contentForModel(
            String caseId,
            String evidenceId,
            AuthenticatedActor actor) {
        EvidenceItemEntity item = evidenceItem(caseId, evidenceId, actor);
        if (!canReadContent(item, actor)) {
            throw new ForbiddenException("actor cannot read evidence content");
        }
        boolean modelProcessingAllowed =
                item.isDesensitized()
                        || Boolean.TRUE.equals(
                                readJsonMap(item.getMetadataJson())
                                        .get("model_processing_authorized"));
        if (!modelProcessingAllowed) {
            throw new ForbiddenException(
                    "evidence is not authorized for model processing");
        }
        return loadContent(item);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.evidenceItem(String,String,AuthenticatedActor)」。
    // 具体功能：「EvidenceApplicationService.evidenceItem(String,String,AuthenticatedActor)」：构建证据Item：先按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「evidenceRepository.findById」、「evidence.getCaseId」、「dispute.getId」、「evidence.getDeletedAt」；处理的关键状态/协议值包括 「evidence_id」，最终返回「EvidenceItemEntity」。
    // 上游调用：「EvidenceApplicationService.evidenceItem(String,String,AuthenticatedActor)」的上游调用点包括 「EvidenceApplicationService.content」、「EvidenceApplicationService.contentForModel」。
    // 下游影响：「EvidenceApplicationService.evidenceItem(String,String,AuthenticatedActor)」向下依次触达 「evidenceRepository.findById」、「evidence.getCaseId」、「dispute.getId」、「evidence.getDeletedAt」；计算结果以「EvidenceItemEntity」交给调用方。
    // 系统意义：「EvidenceApplicationService.evidenceItem(String,String,AuthenticatedActor)」负责主链路中的“证据Item”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private EvidenceItemEntity evidenceItem(
            String caseId, String evidenceId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute = authorizedCase(caseId, actor);
        return evidenceRepository
                .findById(evidenceId)
                .filter(evidence -> evidence.getCaseId().equals(dispute.getId()))
                .filter(evidence -> evidence.getDeletedAt() == null)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        ErrorCode.EVIDENCE_NOT_FOUND,
                                        "evidence not found",
                                        Map.of("evidence_id", evidenceId)));
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.loadContent(EvidenceItemEntity)」。
    // 具体功能：「EvidenceApplicationService.loadContent(EvidenceItemEntity)」：加载内容；实际协作者为 「storage.loadOriginal」、「item.getOriginalFilename」、「item.getContentType」、「item.getFileBucket」，最终返回「EvidenceContentView」。
    // 上游调用：「EvidenceApplicationService.loadContent(EvidenceItemEntity)」的上游调用点包括 「EvidenceApplicationService.content」、「EvidenceApplicationService.contentForModel」。
    // 下游影响：「EvidenceApplicationService.loadContent(EvidenceItemEntity)」向下依次触达 「storage.loadOriginal」、「item.getOriginalFilename」、「item.getContentType」、「item.getFileBucket」；计算结果以「EvidenceContentView」交给调用方。
    // 系统意义：「EvidenceApplicationService.loadContent(EvidenceItemEntity)」负责主链路中的“内容”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private EvidenceContentView loadContent(EvidenceItemEntity item) {
        return new EvidenceContentView(
                item.getOriginalFilename(),
                item.getContentType(),
                storage.loadOriginal(item.getFileBucket(), item.getFileObjectKey()));
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.canReadContent(EvidenceItemEntity,AuthenticatedActor)」。
    // 具体功能：「EvidenceApplicationService.canReadContent(EvidenceItemEntity,AuthenticatedActor)」：判断能否Read内容；实际协作者为 「actor.role」、「item.getSubmittedByRole」、「actor.actorId」、「item.getSubmittedById」，最终返回「boolean」。
    // 上游调用：「EvidenceApplicationService.canReadContent(EvidenceItemEntity,AuthenticatedActor)」的上游调用点包括 「EvidenceApplicationService.content」、「EvidenceApplicationService.contentForModel」。
    // 下游影响：「EvidenceApplicationService.canReadContent(EvidenceItemEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「item.getSubmittedByRole」、「actor.actorId」、「item.getSubmittedById」；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceApplicationService.canReadContent(EvidenceItemEntity,AuthenticatedActor)」负责主链路中的“Read内容”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static boolean canReadContent(EvidenceItemEntity item, AuthenticatedActor actor) {
        if (actor.role() == ActorRole.ADMIN
                || actor.role() == ActorRole.SYSTEM
                || actor.role() == ActorRole.PLATFORM_REVIEWER
                || actor.role() == ActorRole.CUSTOMER_SERVICE) {
            return true;
        }
        return actor.role().name().equals(item.getSubmittedByRole())
                && actor.actorId().equals(item.getSubmittedById());
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.isSubmittedEvidence(EvidenceItemEntity)」。
    // 具体功能：「EvidenceApplicationService.isSubmittedEvidence(EvidenceItemEntity)」：判断是否Submitted证据；实际协作者为 「item.getSubmissionStatus」，最终返回「boolean」。
    // 上游调用：「EvidenceApplicationService.isSubmittedEvidence(EvidenceItemEntity)」只由「EvidenceApplicationService」内部流程使用，负责封装“Submitted证据”这一步校验、映射或状态转换。
    // 下游影响：「EvidenceApplicationService.isSubmittedEvidence(EvidenceItemEntity)」向下依次触达 「item.getSubmissionStatus」；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceApplicationService.isSubmittedEvidence(EvidenceItemEntity)」负责主链路中的“Submitted证据”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static boolean isSubmittedEvidence(EvidenceItemEntity item) {
        return item.getSubmissionStatus() == EvidenceSubmissionStatus.SUBMITTED;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.writeJson(Object)」。
    // 具体功能：「EvidenceApplicationService.writeJson(Object)」：写入JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「EvidenceApplicationService.writeJson(Object)」的上游调用点包括 「EvidenceApplicationService.modelProcessingAuthorization」、「EvidenceApplicationService.buildDossier」。
    // 下游影响：「EvidenceApplicationService.writeJson(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceApplicationService.writeJson(Object)」负责主链路中的“JSON”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize evidence dossier", exception);
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.readJsonMap(String)」。
    // 具体功能：「EvidenceApplicationService.readJsonMap(String)」：读取JSON映射；实际协作者为 「objectMapper.readValue」；不满足前置条件时抛出 「IllegalStateException」，最终返回「Map<String, Object>」。
    // 上游调用：「EvidenceApplicationService.readJsonMap(String)」的上游调用点包括 「EvidenceApplicationService.upload」、「EvidenceApplicationService.modelProcessingAuthorization」、「EvidenceApplicationService.getDossier」、「EvidenceApplicationService.contentForModel」。
    // 下游影响：「EvidenceApplicationService.readJsonMap(String)」向下依次触达 「objectMapper.readValue」；计算结果以「Map<String, Object>」交给调用方。
    // 系统意义：「EvidenceApplicationService.readJsonMap(String)」统一“JSON映射”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private Map<String, Object> readJsonMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid dossier summary", exception);
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.readTimeline(String)」。
    // 具体功能：「EvidenceApplicationService.readTimeline(String)」：读取时间线；实际协作者为 「objectMapper.readValue」；不满足前置条件时抛出 「IllegalStateException」，最终返回「List<BuildDossierResult.TimelineEntry>」。
    // 上游调用：「EvidenceApplicationService.readTimeline(String)」的上游调用点包括 「EvidenceApplicationService.getDossier」。
    // 下游影响：「EvidenceApplicationService.readTimeline(String)」向下依次触达 「objectMapper.readValue」；计算结果以「List<BuildDossierResult.TimelineEntry>」交给调用方。
    // 系统意义：「EvidenceApplicationService.readTimeline(String)」统一“时间线”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private List<BuildDossierResult.TimelineEntry> readTimeline(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid dossier timeline", exception);
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.readMatrix(String)」。
    // 具体功能：「EvidenceApplicationService.readMatrix(String)」：读取矩阵：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」、「node.isObject」、「objectMapper.convertValue」、「node.path("fact_evidence_matrix").isArray」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「fact_evidence_matrix」，最终返回「List<Map<String, Object>>」。
    // 上游调用：「EvidenceApplicationService.readMatrix(String)」的上游调用点包括 「EvidenceApplicationService.getDossier」。
    // 下游影响：「EvidenceApplicationService.readMatrix(String)」向下依次触达 「objectMapper.readTree」、「node.isObject」、「objectMapper.convertValue」、「node.path("fact_evidence_matrix").isArray」；计算结果以「List<Map<String, Object>>」交给调用方。
    // 系统意义：「EvidenceApplicationService.readMatrix(String)」统一“矩阵”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private List<Map<String, Object>> readMatrix(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode matrix =
                    node.isObject() && node.path("fact_evidence_matrix").isArray()
                            ? node.path("fact_evidence_matrix")
                            : node;
            return objectMapper.convertValue(matrix, new TypeReference<>() {});
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            throw new IllegalStateException("invalid dossier matrix", exception);
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.compactUuid()」。
    // 具体功能：「EvidenceApplicationService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「EvidenceApplicationService.compactUuid()」的上游调用点包括 「EvidenceApplicationService.upload」、「EvidenceApplicationService.buildDossier」。
    // 下游影响：「EvidenceApplicationService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceApplicationService.compactUuid()」负责主链路中的“UUID”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceApplicationService.required(String,String)」。
    // 具体功能：「EvidenceApplicationService.required(String,String)」：校验字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「EvidenceApplicationService.required(String,String)」的上游调用点包括 「EvidenceApplicationService.upload」。
    // 下游影响：「EvidenceApplicationService.required(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceApplicationService.required(String,String)」在“字符串”进入下游前阻断非法状态；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
