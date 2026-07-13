/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：编排原始证据提交、对象存储和 OCR 排队规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「submit」、「deletePending」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.domain.EvidenceSubmissionStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceSubmissionBatchEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceSubmissionBatchRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.RoomMessageCommand;
import com.example.dispute.room.application.RoomMessageService;
import com.example.dispute.room.application.RoomMessageView;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【证据与版本化卷宗 / 应用编排层】类型「EvidenceSubmissionService」。
// 类型职责：编排原始证据提交、对象存储和 OCR 排队规则、权限校验与事实读写；本类型显式提供 「EvidenceSubmissionService」、「submit」、「deletePending」、「createSubmission」、「submissionRoom」、「assertParty」。
// 协作关系：主要由 「EvidenceController.deletePending」、「EvidenceController.submitBatch」、「EvidenceSubmissionServiceTest.deletesOnlyPendingEvidenceOwnedByCurrentActor」、「EvidenceSubmissionServiceTest.refusesToDeleteSubmittedEvidence」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class EvidenceSubmissionService {

    private final FulfillmentCaseRepository caseRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final EvidenceSubmissionBatchRepository batchRepository;
    private final RoomMessageService roomMessageService;
    private final ObjectMapper objectMapper;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceSubmissionService.EvidenceSubmissionService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceSubmissionBatchRepository,RoomMessageService,ObjectMapper,AuditRecorder,Clock)」。
    // 具体功能：「EvidenceSubmissionService.EvidenceSubmissionService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceSubmissionBatchRepository,RoomMessageService,ObjectMapper,AuditRecorder,Clock)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「evidenceRepository」(EvidenceItemRepository)、「batchRepository」(EvidenceSubmissionBatchRepository)、「roomMessageService」(RoomMessageService)、「objectMapper」(ObjectMapper)、「auditRecorder」(AuditRecorder)、「clock」(Clock) 并保存为「EvidenceSubmissionService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「EvidenceSubmissionService.EvidenceSubmissionService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceSubmissionBatchRepository,RoomMessageService,ObjectMapper,AuditRecorder,Clock)」的上游创建点包括 「EvidenceSubmissionServiceTest.setUp」。
    // 下游影响：「EvidenceSubmissionService.EvidenceSubmissionService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceSubmissionBatchRepository,RoomMessageService,ObjectMapper,AuditRecorder,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceSubmissionService.EvidenceSubmissionService(FulfillmentCaseRepository,EvidenceItemRepository,EvidenceSubmissionBatchRepository,RoomMessageService,ObjectMapper,AuditRecorder,Clock)」负责主链路中的“证据提交服务”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public EvidenceSubmissionService(
            FulfillmentCaseRepository caseRepository,
            EvidenceItemRepository evidenceRepository,
            EvidenceSubmissionBatchRepository batchRepository,
            RoomMessageService roomMessageService,
            ObjectMapper objectMapper,
            AuditRecorder auditRecorder,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.batchRepository = batchRepository;
        this.roomMessageService = roomMessageService;
        this.objectMapper = objectMapper;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceSubmissionService.submit(String,EvidenceSubmissionCommand,AuthenticatedActor,String,String)」。
    // 具体功能：「EvidenceSubmissionService.submit(String,EvidenceSubmissionCommand,AuthenticatedActor,String,String)」：在案件行锁和举证窗口校验通过后执行批次幂等：相同 idempotencyKey 返回原批次，否则校验每个 evidenceId 属于当前案件和提交人，再创建不可变提交批次与对应房间消息，最终返回「EvidenceSubmissionView」。
    // 上游调用：「EvidenceSubmissionService.submit(String,EvidenceSubmissionCommand,AuthenticatedActor,String,String)」的上游调用点包括 「EvidenceController.submitBatch」、「EvidenceSubmissionServiceTest.submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk」、「EvidenceSubmissionServiceTest.submitsHearingSupplementEvidenceToTheHearingRoom」。
    // 下游影响：「EvidenceSubmissionService.submit(String,EvidenceSubmissionCommand,AuthenticatedActor,String,String)」向下依次触达 「caseRepository.findByIdForUpdate」、「batchRepository.findByCaseIdAndIdempotencyKey」、「assertParty」、「createSubmission」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceSubmissionService.submit(String,EvidenceSubmissionCommand,AuthenticatedActor,String,String)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public EvidenceSubmissionView submit(
            String caseId,
            EvidenceSubmissionCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertParty(dispute, actor);
        return batchRepository
                .findByCaseIdAndIdempotencyKey(caseId, idempotencyKey)
                .map(this::viewWithoutMessage)
                .orElseGet(
                        () ->
                                createSubmission(
                                        dispute,
                                        command,
                                        actor,
                                        idempotencyKey,
                                        traceId));
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceSubmissionService.deletePending(String,String,AuthenticatedActor)」。
    // 具体功能：「EvidenceSubmissionService.deletePending(String,String,AuthenticatedActor)」：删除待处理：先由 Spring 事务代理统一提交数据库变化，再按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」、「evidenceRepository.findById」、「evidence.getCaseId」、「item.deletePending」；处理的关键状态/协议值包括 「EVIDENCE_PENDING_DELETED」、「EVIDENCE_ITEM」、「submission_status」、「PENDING_SUBMISSION」，最终返回「void」。
    // 上游调用：「EvidenceSubmissionService.deletePending(String,String,AuthenticatedActor)」的上游调用点包括 「EvidenceController.deletePending」、「EvidenceSubmissionServiceTest.deletesOnlyPendingEvidenceOwnedByCurrentActor」、「EvidenceSubmissionServiceTest.refusesToDeleteSubmittedEvidence」。
    // 下游影响：「EvidenceSubmissionService.deletePending(String,String,AuthenticatedActor)」向下依次触达 「caseRepository.findByIdForUpdate」、「evidenceRepository.findById」、「evidence.getCaseId」、「item.deletePending」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「EvidenceSubmissionService.deletePending(String,String,AuthenticatedActor)」定义原子提交边界；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public void deletePending(
            String caseId,
            String evidenceId,
            AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertParty(dispute, actor);
        EvidenceItemEntity item =
                evidenceRepository
                        .findById(evidenceId)
                        .filter(evidence -> evidence.getCaseId().equals(caseId))
                        .orElseThrow(() -> new IllegalArgumentException("evidence not found"));
        assertOwner(item, actor);
        item.deletePending(OffsetDateTime.now(clock), actor.actorId());
        auditRecorder.record(
                actor,
                "EVIDENCE_PENDING_DELETED",
                "EVIDENCE_ITEM",
                evidenceId,
                caseId,
                Map.of("submission_status", "PENDING_SUBMISSION"),
                Map.of("submission_status", "VOIDED"));
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceSubmissionService.createSubmission(FulfillmentCaseEntity,EvidenceSubmissionCommand,AuthenticatedActor,String,String)」。
    // 具体功能：「EvidenceSubmissionService.createSubmission(FulfillmentCaseEntity,EvidenceSubmissionCommand,AuthenticatedActor,String,String)」：锁定并规范化证据 ID，验证材料尚未删除且提交方一致，保存 EvidenceSubmissionBatch，再以同一幂等键向证据房间写入引用这些材料的消息和审计事实，最终返回「EvidenceSubmissionView」。
    // 上游调用：「EvidenceSubmissionService.createSubmission(FulfillmentCaseEntity,EvidenceSubmissionCommand,AuthenticatedActor,String,String)」的上游调用点包括 「EvidenceSubmissionService.submit」。
    // 下游影响：「EvidenceSubmissionService.createSubmission(FulfillmentCaseEntity,EvidenceSubmissionCommand,AuthenticatedActor,String,String)」向下依次触达 「evidenceRepository.findAllById」、「batchRepository.save」、「roomMessageService.post」、「EvidenceSubmissionBatchEntity.submitted」；计算结果以「EvidenceSubmissionView」交给调用方。
    // 系统意义：「EvidenceSubmissionService.createSubmission(FulfillmentCaseEntity,EvidenceSubmissionCommand,AuthenticatedActor,String,String)」负责主链路中的“提交”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private EvidenceSubmissionView createSubmission(
            FulfillmentCaseEntity dispute,
            EvidenceSubmissionCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId) {
        List<String> evidenceIds = normalizedEvidenceIds(command.evidenceIds());
        if (evidenceIds.isEmpty()) {
            throw new IllegalArgumentException("evidence_ids must not be empty");
        }
        List<EvidenceItemEntity> evidences = evidenceRepository.findAllById(evidenceIds);
        if (evidences.size() != evidenceIds.size()) {
            throw new IllegalArgumentException("some evidence items were not found");
        }
        for (EvidenceItemEntity item : evidences) {
            if (!item.getCaseId().equals(dispute.getId())) {
                throw new IllegalArgumentException("evidence belongs to a different case");
            }
            assertOwner(item, actor);
            if (item.getSubmissionStatus() != EvidenceSubmissionStatus.PENDING_SUBMISSION) {
                throw new IllegalStateException("only pending evidence can be submitted");
            }
        }
        Instant submittedAt = clock.instant();
        EvidenceSubmissionBatchEntity batch =
                EvidenceSubmissionBatchEntity.submitted(
                        "EVIDENCE_BATCH_" + compactUuid(),
                        dispute.getId(),
                        actor.role().name(),
                        actor.actorId(),
                        json(evidenceIds),
                        command.batchNote(),
                        idempotencyKey,
                        submittedAt);
        batchRepository.save(batch);
        OffsetDateTime submittedOffset = OffsetDateTime.ofInstant(submittedAt, ZoneOffset.UTC);
        for (EvidenceItemEntity item : evidences) {
            item.markSubmittedForParties(batch.getId(), submittedOffset, actor.actorId());
        }
        RoomMessageView message =
                roomMessageService.post(
                        dispute.getId(),
                        submissionRoom(dispute),
                        new RoomMessageCommand(
                                MessageType.PARTY_EVIDENCE_REFERENCE,
                                submissionMessage(actor, evidenceIds, command.batchNote()),
                                evidenceIds),
                        actor,
                        "evidence-batch-message:" + idempotencyKey,
                        traceId);
        batch.attachRoomMessage(message.id());
        auditRecorder.record(
                actor,
                "EVIDENCE_BATCH_SUBMITTED",
                "EVIDENCE_SUBMISSION_BATCH",
                batch.getId(),
                dispute.getId(),
                Map.of(),
                Map.of(
                        "evidence_count", evidenceIds.size(),
                        "room_message_id", message.id()));
        return view(batch, message);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceSubmissionService.submissionRoom(FulfillmentCaseEntity)」。
    // 具体功能：「EvidenceSubmissionService.submissionRoom(FulfillmentCaseEntity)」：构建提交房间；实际协作者为 「dispute.getCurrentRoom」，最终返回「RoomType」。
    // 上游调用：「EvidenceSubmissionService.submissionRoom(FulfillmentCaseEntity)」的上游调用点包括 「EvidenceSubmissionService.createSubmission」。
    // 下游影响：「EvidenceSubmissionService.submissionRoom(FulfillmentCaseEntity)」向下依次触达 「dispute.getCurrentRoom」；计算结果以「RoomType」交给调用方。
    // 系统意义：「EvidenceSubmissionService.submissionRoom(FulfillmentCaseEntity)」负责主链路中的“提交房间”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static RoomType submissionRoom(FulfillmentCaseEntity dispute) {
        if (RoomType.HEARING.name().equals(dispute.getCurrentRoom())) {
            return RoomType.HEARING;
        }
        return RoomType.EVIDENCE;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceSubmissionService.assertParty(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「EvidenceSubmissionService.assertParty(FulfillmentCaseEntity,AuthenticatedActor)」：断言当事方；实际协作者为 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「EvidenceSubmissionService.assertParty(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「EvidenceSubmissionService.submit」、「EvidenceSubmissionService.deletePending」。
    // 下游影响：「EvidenceSubmissionService.assertParty(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」。
    // 系统意义：「EvidenceSubmissionService.assertParty(FulfillmentCaseEntity,AuthenticatedActor)」在“当事方”进入下游前阻断非法状态；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static void assertParty(FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean allowed =
                actor.role() == ActorRole.USER && actor.actorId().equals(dispute.getUserId())
                        || actor.role() == ActorRole.MERCHANT
                                && actor.actorId().equals(dispute.getMerchantId());
        if (!allowed) {
            throw new ForbiddenException("only case parties can submit evidence");
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceSubmissionService.assertOwner(EvidenceItemEntity,AuthenticatedActor)」。
    // 具体功能：「EvidenceSubmissionService.assertOwner(EvidenceItemEntity,AuthenticatedActor)」：断言Owner；实际协作者为 「actor.role」、「item.getSubmittedByRole」、「actor.actorId」、「item.getSubmittedById」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「EvidenceSubmissionService.assertOwner(EvidenceItemEntity,AuthenticatedActor)」的上游调用点包括 「EvidenceSubmissionService.deletePending」、「EvidenceSubmissionService.createSubmission」。
    // 下游影响：「EvidenceSubmissionService.assertOwner(EvidenceItemEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「item.getSubmittedByRole」、「actor.actorId」、「item.getSubmittedById」。
    // 系统意义：「EvidenceSubmissionService.assertOwner(EvidenceItemEntity,AuthenticatedActor)」在“Owner”进入下游前阻断非法状态；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static void assertOwner(EvidenceItemEntity item, AuthenticatedActor actor) {
        if (!actor.role().name().equals(item.getSubmittedByRole())
                || !actor.actorId().equals(item.getSubmittedById())) {
            throw new ForbiddenException("actor cannot mutate counterparty evidence");
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceSubmissionService.normalizedEvidenceIds(List)」。
    // 具体功能：「EvidenceSubmissionService.normalizedEvidenceIds(List)」：规范化normalized证据Ids，最终返回「List<String>」。
    // 上游调用：「EvidenceSubmissionService.normalizedEvidenceIds(List)」的上游调用点包括 「EvidenceSubmissionService.createSubmission」。
    // 下游影响：「EvidenceSubmissionService.normalizedEvidenceIds(List)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「List<String>」交给调用方。
    // 系统意义：「EvidenceSubmissionService.normalizedEvidenceIds(List)」负责主链路中的“normalized证据Ids”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static List<String> normalizedEvidenceIds(List<String> evidenceIds) {
        return new LinkedHashSet<>(evidenceIds).stream().toList();
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceSubmissionService.submissionMessage(AuthenticatedActor,List,String)」。
    // 具体功能：「EvidenceSubmissionService.submissionMessage(AuthenticatedActor,List,String)」：构建提交消息；实际协作者为 「actor.role」；处理的关键状态/协议值包括 「商家」、「用户」、「备注：」，最终返回「String」。
    // 上游调用：「EvidenceSubmissionService.submissionMessage(AuthenticatedActor,List,String)」的上游调用点包括 「EvidenceSubmissionService.createSubmission」。
    // 下游影响：「EvidenceSubmissionService.submissionMessage(AuthenticatedActor,List,String)」向下依次触达 「actor.role」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceSubmissionService.submissionMessage(AuthenticatedActor,List,String)」负责主链路中的“提交消息”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String submissionMessage(
            AuthenticatedActor actor, List<String> evidenceIds, String batchNote) {
        String party = actor.role() == ActorRole.MERCHANT ? "商家" : "用户";
        String note = batchNote == null || batchNote.isBlank() ? "" : "备注：" + batchNote;
        return party
                + "提交了 "
                + evidenceIds.size()
                + " 份证据材料，请证据书记官围绕来源、形成时间、真实性、完整性和案情关联性进行核验。"
                + note;
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceSubmissionService.viewWithoutMessage(EvidenceSubmissionBatchEntity)」。
    // 具体功能：「EvidenceSubmissionService.viewWithoutMessage(EvidenceSubmissionBatchEntity)」：构建视图缺少消息；实际协作者为 「view」，最终返回「EvidenceSubmissionView」。
    // 上游调用：「EvidenceSubmissionService.viewWithoutMessage(EvidenceSubmissionBatchEntity)」只由「EvidenceSubmissionService」内部流程使用，负责封装“视图缺少消息”这一步校验、映射或状态转换。
    // 下游影响：「EvidenceSubmissionService.viewWithoutMessage(EvidenceSubmissionBatchEntity)」向下依次触达 「view」；计算结果以「EvidenceSubmissionView」交给调用方。
    // 系统意义：「EvidenceSubmissionService.viewWithoutMessage(EvidenceSubmissionBatchEntity)」统一“视图缺少消息”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private EvidenceSubmissionView viewWithoutMessage(EvidenceSubmissionBatchEntity batch) {
        return view(batch, null);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceSubmissionService.view(EvidenceSubmissionBatchEntity,RoomMessageView)」。
    // 具体功能：「EvidenceSubmissionService.view(EvidenceSubmissionBatchEntity,RoomMessageView)」：构建视图；实际协作者为 「batch.getId」、「batch.getCaseId」、「batch.getActorRole」、「batch.getActorId」，最终返回「EvidenceSubmissionView」。
    // 上游调用：「EvidenceSubmissionService.view(EvidenceSubmissionBatchEntity,RoomMessageView)」的上游调用点包括 「EvidenceSubmissionService.createSubmission」、「EvidenceSubmissionService.viewWithoutMessage」。
    // 下游影响：「EvidenceSubmissionService.view(EvidenceSubmissionBatchEntity,RoomMessageView)」向下依次触达 「batch.getId」、「batch.getCaseId」、「batch.getActorRole」、「batch.getActorId」；计算结果以「EvidenceSubmissionView」交给调用方。
    // 系统意义：「EvidenceSubmissionService.view(EvidenceSubmissionBatchEntity,RoomMessageView)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private EvidenceSubmissionView view(
            EvidenceSubmissionBatchEntity batch, RoomMessageView message) {
        return new EvidenceSubmissionView(
                batch.getId(),
                batch.getCaseId(),
                batch.getActorRole(),
                batch.getActorId(),
                readEvidenceIds(batch.getEvidenceIdsJson()),
                batch.getBatchNote(),
                batch.getSubmitStatus(),
                batch.getSubmittedAt(),
                message);
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceSubmissionService.readEvidenceIds(String)」。
    // 具体功能：「EvidenceSubmissionService.readEvidenceIds(String)」：读取证据Ids；实际协作者为 「objectMapper.readValue」；不满足前置条件时抛出 「IllegalStateException」，最终返回「List<String>」。
    // 上游调用：「EvidenceSubmissionService.readEvidenceIds(String)」的上游调用点包括 「EvidenceSubmissionService.view」。
    // 下游影响：「EvidenceSubmissionService.readEvidenceIds(String)」向下依次触达 「objectMapper.readValue」；计算结果以「List<String>」交给调用方。
    // 系统意义：「EvidenceSubmissionService.readEvidenceIds(String)」统一“证据Ids”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private List<String> readEvidenceIds(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid evidence batch ids", exception);
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceSubmissionService.json(Object)」。
    // 具体功能：「EvidenceSubmissionService.json(Object)」：序列化JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「EvidenceSubmissionService.json(Object)」的上游调用点包括 「EvidenceSubmissionService.createSubmission」。
    // 下游影响：「EvidenceSubmissionService.json(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceSubmissionService.json(Object)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize evidence batch", exception);
        }
    }

    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceSubmissionService.compactUuid()」。
    // 具体功能：「EvidenceSubmissionService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「EvidenceSubmissionService.compactUuid()」的上游调用点包括 「EvidenceSubmissionService.createSubmission」。
    // 下游影响：「EvidenceSubmissionService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceSubmissionService.compactUuid()」负责主链路中的“UUID”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
