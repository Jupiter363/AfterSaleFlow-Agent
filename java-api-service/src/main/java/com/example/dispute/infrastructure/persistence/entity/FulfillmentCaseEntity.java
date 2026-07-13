/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射履约案件数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「create」、「imported」、「completeIntake」、「admitToEvidence」、「rejectAsNotAdmissible」、「cancelIntake」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.casecore.domain.CaseSourceType;
import com.example.dispute.config.ActorRole;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「FulfillmentCaseEntity」。
// 类型职责：映射履约案件数据库记录并保存可审计状态；本类型显式提供 「FulfillmentCaseEntity」、「FulfillmentCaseEntity」、「FulfillmentCaseEntity」、「create」、「create」、「create」。
// 协作关系：主要由 「AccessSessionResolver.permissionLevelFor」、「CaseApplicationService.assertCanRead」、「CaseApplicationService.createNew」、「CaseApplicationService.toView」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "fulfillment_dispute_case")
public class FulfillmentCaseEntity extends AbstractEntity {

    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "after_sales_id", length = 64)
    private String afterSaleId;

    @Column(name = "logistics_id", length = 64)
    private String logisticsId;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "merchant_id", length = 128, nullable = false)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "initiator_role", length = 32, nullable = false)
    private ActorRole initiatorRole;

    @Column(name = "creation_idempotency_key", length = 128, nullable = false, unique = true)
    private String creationIdempotencyKey;

    @Column(name = "case_type", length = 64, nullable = false)
    private String caseType;

    @Column(name = "dispute_type", length = 64)
    private String disputeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_status", length = 64, nullable = false)
    private CaseStatus caseStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "hearing_route", length = 64)
    private RouteType routeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 32, nullable = false)
    private RiskLevel riskLevel;

    @Column(name = "current_workflow_id", length = 128)
    private String currentWorkflowId;

    @Column(name = "title", length = 256, nullable = false)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "intake_result_json", nullable = false, columnDefinition = "jsonb")
    private String intakeResultJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    private String metadataJson;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 32, nullable = false)
    private CaseSourceType sourceType;

    @Column(name = "source_system", length = 64)
    private String sourceSystem;

    @Column(name = "external_case_ref", length = 128)
    private String externalCaseRef;

    @Column(name = "current_room", length = 32)
    private String currentRoom;

    @Column(name = "current_deadline_at")
    private OffsetDateTime currentDeadlineAt;

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.FulfillmentCaseEntity()」。
    // 具体功能：「FulfillmentCaseEntity.FulfillmentCaseEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「FulfillmentCaseEntity.FulfillmentCaseEntity()」的上游创建点包括 「FulfillmentCaseEntity.create」、「FulfillmentCaseEntity.imported」。
    // 下游影响：「FulfillmentCaseEntity.FulfillmentCaseEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「FulfillmentCaseEntity.FulfillmentCaseEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected FulfillmentCaseEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.FulfillmentCaseEntity(String,String,String,String,String,String,String,String,String,RiskLevel,String)」。
    // 具体功能：「FulfillmentCaseEntity.FulfillmentCaseEntity(String,String,String,String,String,String,String,String,String,RiskLevel,String)」：使用 「id」(String)、「orderId」(String)、「afterSaleId」(String)、「userId」(String)、「merchantId」(String)、「creationIdempotencyKey」(String)、「caseType」(String)、「title」(String)、「description」(String)、「riskLevel」(RiskLevel)、「actorId」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「FulfillmentCaseEntity.FulfillmentCaseEntity(String,String,String,String,String,String,String,String,String,RiskLevel,String)」的上游创建点包括 「FulfillmentCaseEntity.create」、「FulfillmentCaseEntity.imported」。
    // 下游影响：「FulfillmentCaseEntity.FulfillmentCaseEntity(String,String,String,String,String,String,String,String,String,RiskLevel,String)」向下依次触达 「inferInitiatorRole」。
    // 系统意义：「FulfillmentCaseEntity.FulfillmentCaseEntity(String,String,String,String,String,String,String,String,String,RiskLevel,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private FulfillmentCaseEntity(
            String id,
            String orderId,
            String afterSaleId,
            String userId,
            String merchantId,
            String creationIdempotencyKey,
            String caseType,
            String title,
            String description,
            RiskLevel riskLevel,
            String actorId) {
        this(
                id,
                orderId,
                afterSaleId,
                userId,
                merchantId,
                inferInitiatorRole(userId, merchantId, actorId, ActorRole.USER),
                creationIdempotencyKey,
                caseType,
                title,
                description,
                riskLevel,
                actorId);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.FulfillmentCaseEntity(String,String,String,String,String,ActorRole,String,String,String,String,RiskLevel,String)」。
    // 具体功能：「FulfillmentCaseEntity.FulfillmentCaseEntity(String,String,String,String,String,ActorRole,String,String,String,String,RiskLevel,String)」：使用 「id」(String)、「orderId」(String)、「afterSaleId」(String)、「userId」(String)、「merchantId」(String)、「initiatorRole」(ActorRole)、「creationIdempotencyKey」(String)、「caseType」(String)、「title」(String)、「description」(String)、「riskLevel」(RiskLevel)、「actorId」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「FulfillmentCaseEntity.FulfillmentCaseEntity(String,String,String,String,String,ActorRole,String,String,String,String,RiskLevel,String)」的上游创建点包括 「FulfillmentCaseEntity.create」、「FulfillmentCaseEntity.imported」。
    // 下游影响：「FulfillmentCaseEntity.FulfillmentCaseEntity(String,String,String,String,String,ActorRole,String,String,String,String,RiskLevel,String)」向下依次触达 「Objects.requireNonNull」、「required」。
    // 系统意义：「FulfillmentCaseEntity.FulfillmentCaseEntity(String,String,String,String,String,ActorRole,String,String,String,String,RiskLevel,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private FulfillmentCaseEntity(
            String id,
            String orderId,
            String afterSaleId,
            String userId,
            String merchantId,
            ActorRole initiatorRole,
            String creationIdempotencyKey,
            String caseType,
            String title,
            String description,
            RiskLevel riskLevel,
            String actorId) {
        super(id);
        this.orderId = orderId;
        this.afterSaleId = afterSaleId;
        this.userId = required(userId, "userId");
        this.merchantId = required(merchantId, "merchantId");
        this.initiatorRole =
                Objects.requireNonNull(initiatorRole, "initiatorRole must not be null");
        this.creationIdempotencyKey =
                required(creationIdempotencyKey, "creationIdempotencyKey");
        this.caseType = required(caseType, "caseType");
        this.title = required(title, "title");
        this.description = required(description, "description");
        this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        this.caseStatus = CaseStatus.INTAKE_PENDING;
        this.intakeResultJson = "{}";
        this.metadataJson = "{}";
        this.sourceType = CaseSourceType.INTAKE_CREATED;
        this.currentRoom = "INTAKE";
        this.createdBy = required(actorId, "actorId");
        this.updatedBy = actorId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.create(String,String,String,String,String,String,String,String,String,RiskLevel,String)」。
    // 具体功能：「FulfillmentCaseEntity.create(String,String,String,String,String,String,String,String,String,RiskLevel,String)」：创建履约案件，最终返回「FulfillmentCaseEntity」。
    // 上游调用：「FulfillmentCaseEntity.create(String,String,String,String,String,String,String,String,String,RiskLevel,String)」的上游调用点包括 「CaseApplicationService.createNew」、「CaseApplicationServiceTest.notifiedCounterpartyCanReadMerchantInitiatedCaseButInitiatorRoleStaysMerchant」、「CaseApplicationServiceTest.normalizesLegacyFulfillmentDisputesToTheFinalPublicCaseType」、「CaseApplicationServiceTest.admittingTransferredIntakeCasePromotesItToDispute」。
    // 下游影响：「FulfillmentCaseEntity.create(String,String,String,String,String,String,String,String,String,RiskLevel,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「FulfillmentCaseEntity」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.create(String,String,String,String,String,String,String,String,String,RiskLevel,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static FulfillmentCaseEntity create(
            String id,
            String orderId,
            String afterSaleId,
            String userId,
            String merchantId,
            String creationIdempotencyKey,
            String caseType,
            String title,
            String description,
            RiskLevel riskLevel,
            String actorId) {
        return new FulfillmentCaseEntity(
                id,
                orderId,
                afterSaleId,
                userId,
                merchantId,
                creationIdempotencyKey,
                caseType,
                title,
                description,
                riskLevel,
                actorId);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.create(String,String,String,String,String,String,String,String,String,String,RiskLevel,String)」。
    // 具体功能：「FulfillmentCaseEntity.create(String,String,String,String,String,String,String,String,String,String,RiskLevel,String)」：创建履约案件：先更新内部状态 「logisticsId」；实际协作者为 「blankToNull」，最终返回「FulfillmentCaseEntity」。
    // 上游调用：「FulfillmentCaseEntity.create(String,String,String,String,String,String,String,String,String,String,RiskLevel,String)」的上游调用点包括 「CaseApplicationService.createNew」、「CaseApplicationServiceTest.notifiedCounterpartyCanReadMerchantInitiatedCaseButInitiatorRoleStaysMerchant」、「CaseApplicationServiceTest.normalizesLegacyFulfillmentDisputesToTheFinalPublicCaseType」、「CaseApplicationServiceTest.admittingTransferredIntakeCasePromotesItToDispute」。
    // 下游影响：「FulfillmentCaseEntity.create(String,String,String,String,String,String,String,String,String,String,RiskLevel,String)」向下依次触达 「blankToNull」；计算结果以「FulfillmentCaseEntity」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.create(String,String,String,String,String,String,String,String,String,String,RiskLevel,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static FulfillmentCaseEntity create(
            String id,
            String orderId,
            String afterSaleId,
            String logisticsId,
            String userId,
            String merchantId,
            String creationIdempotencyKey,
            String caseType,
            String title,
            String description,
            RiskLevel riskLevel,
            String actorId) {
        FulfillmentCaseEntity entity =
                new FulfillmentCaseEntity(
                        id,
                        orderId,
                        afterSaleId,
                        userId,
                        merchantId,
                        creationIdempotencyKey,
                        caseType,
                        title,
                        description,
                        riskLevel,
                        actorId);
        entity.logisticsId = blankToNull(logisticsId);
        return entity;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.create(String,String,String,String,String,String,ActorRole,String,String,String,String,RiskLevel,String)」。
    // 具体功能：「FulfillmentCaseEntity.create(String,String,String,String,String,String,ActorRole,String,String,String,String,RiskLevel,String)」：创建履约案件：先更新内部状态 「logisticsId」；实际协作者为 「blankToNull」，最终返回「FulfillmentCaseEntity」。
    // 上游调用：「FulfillmentCaseEntity.create(String,String,String,String,String,String,ActorRole,String,String,String,String,RiskLevel,String)」的上游调用点包括 「CaseApplicationService.createNew」、「CaseApplicationServiceTest.notifiedCounterpartyCanReadMerchantInitiatedCaseButInitiatorRoleStaysMerchant」、「CaseApplicationServiceTest.normalizesLegacyFulfillmentDisputesToTheFinalPublicCaseType」、「CaseApplicationServiceTest.admittingTransferredIntakeCasePromotesItToDispute」。
    // 下游影响：「FulfillmentCaseEntity.create(String,String,String,String,String,String,ActorRole,String,String,String,String,RiskLevel,String)」向下依次触达 「blankToNull」；计算结果以「FulfillmentCaseEntity」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.create(String,String,String,String,String,String,ActorRole,String,String,String,String,RiskLevel,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static FulfillmentCaseEntity create(
            String id,
            String orderId,
            String afterSaleId,
            String logisticsId,
            String userId,
            String merchantId,
            ActorRole initiatorRole,
            String creationIdempotencyKey,
            String caseType,
            String title,
            String description,
            RiskLevel riskLevel,
            String actorId) {
        FulfillmentCaseEntity entity =
                new FulfillmentCaseEntity(
                        id,
                        orderId,
                        afterSaleId,
                        userId,
                        merchantId,
                        initiatorRole,
                        creationIdempotencyKey,
                        caseType,
                        title,
                        description,
                        riskLevel,
                        actorId);
        entity.logisticsId = blankToNull(logisticsId);
        return entity;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.imported(String,String,String,String,String,String,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime,String,String,String)」。
    // 具体功能：「FulfillmentCaseEntity.imported(String,String,String,String,String,String,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime,String,String,String)」：提供「imported」的便捷重载：接收 「id」(String)、「orderId」(String)、「afterSaleId」(String)、「logisticsId」(String)、「userId」(String)、「merchantId」(String)、「creationIdempotencyKey」(String)、「disputeType」(String)、「title」(String)、「description」(String)、「riskLevel」(RiskLevel)、「caseStatus」(CaseStatus)、「currentRoom」(String)、「currentDeadlineAt」(OffsetDateTime)、「sourceSystem」(String)、「externalCaseRef」(String)、「actorId」(String)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「FulfillmentCaseEntity.imported(String,String,String,String,String,String,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime,String,String,String)」的上游调用点包括 「ExternalCaseImportTransactionService.importDispute」、「FulfillmentCaseEntity.imported」、「DisputeImportServiceTest.returnsTheExistingCaseForTheSameExternalReference」、「DisputeImportServiceTest.replayRepairsThePersistedRoomInsteadOfTrustingConflictingPayloadState」。
    // 下游影响：「FulfillmentCaseEntity.imported(String,String,String,String,String,String,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime,String,String,String)」向下依次触达 「imported」；计算结果以「FulfillmentCaseEntity」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.imported(String,String,String,String,String,String,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static FulfillmentCaseEntity imported(
            String id,
            String orderId,
            String afterSaleId,
            String logisticsId,
            String userId,
            String merchantId,
            String creationIdempotencyKey,
            String disputeType,
            String title,
            String description,
            RiskLevel riskLevel,
            CaseStatus caseStatus,
            String currentRoom,
            OffsetDateTime currentDeadlineAt,
            String sourceSystem,
            String externalCaseRef,
            String actorId) {
        return imported(
                id,
                orderId,
                afterSaleId,
                logisticsId,
                userId,
                merchantId,
                ActorRole.USER,
                creationIdempotencyKey,
                disputeType,
                title,
                description,
                riskLevel,
                caseStatus,
                currentRoom,
                currentDeadlineAt,
                sourceSystem,
                externalCaseRef,
                actorId);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.imported(String,String,String,String,String,String,ActorRole,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime,String,String,String)」。
    // 具体功能：「FulfillmentCaseEntity.imported(String,String,String,String,String,String,ActorRole,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime,String,String,String)」：导入导入案件：先更新内部状态 「logisticsId」、「disputeType」、「caseStatus」、「sourceType」；实际协作者为 「Objects.requireNonNull」、「blankToNull」、「required」、「isFullHearingLifecycle」；处理的关键状态/协议值包括 「DISPUTE」、「disputeType」、「sourceSystem」、「externalCaseRef」，最终返回「FulfillmentCaseEntity」。
    // 上游调用：「FulfillmentCaseEntity.imported(String,String,String,String,String,String,ActorRole,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime,String,String,String)」的上游调用点包括 「ExternalCaseImportTransactionService.importDispute」、「FulfillmentCaseEntity.imported」、「DisputeImportServiceTest.returnsTheExistingCaseForTheSameExternalReference」、「DisputeImportServiceTest.replayRepairsThePersistedRoomInsteadOfTrustingConflictingPayloadState」。
    // 下游影响：「FulfillmentCaseEntity.imported(String,String,String,String,String,String,ActorRole,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime,String,String,String)」向下依次触达 「Objects.requireNonNull」、「blankToNull」、「required」、「isFullHearingLifecycle」；计算结果以「FulfillmentCaseEntity」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.imported(String,String,String,String,String,String,ActorRole,String,String,String,String,RiskLevel,CaseStatus,String,OffsetDateTime,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static FulfillmentCaseEntity imported(
            String id,
            String orderId,
            String afterSaleId,
            String logisticsId,
            String userId,
            String merchantId,
            ActorRole initiatorRole,
            String creationIdempotencyKey,
            String disputeType,
            String title,
            String description,
            RiskLevel riskLevel,
            CaseStatus caseStatus,
            String currentRoom,
            OffsetDateTime currentDeadlineAt,
            String sourceSystem,
            String externalCaseRef,
            String actorId) {
        FulfillmentCaseEntity entity =
                new FulfillmentCaseEntity(
                        id,
                        orderId,
                        afterSaleId,
                        userId,
                        merchantId,
                        initiatorRole,
                        creationIdempotencyKey,
                        "DISPUTE",
                        title,
                        description,
                        riskLevel,
                        actorId);
        entity.logisticsId = blankToNull(logisticsId);
        entity.disputeType = required(disputeType, "disputeType");
        entity.caseStatus = Objects.requireNonNull(caseStatus, "caseStatus must not be null");
        entity.sourceType = CaseSourceType.EXTERNAL_IMPORT;
        entity.sourceSystem = required(sourceSystem, "sourceSystem");
        entity.externalCaseRef = required(externalCaseRef, "externalCaseRef");
        entity.currentRoom = required(currentRoom, "currentRoom");
        if (isFullHearingLifecycle(caseStatus, currentRoom)) {
            entity.routeType = RouteType.FULL_HEARING;
        }
        entity.currentDeadlineAt = currentDeadlineAt;
        entity.intakeResultJson = "{\"potentialDispute\":true,\"missingSlots\":[],\"agentDegraded\":false,\"analyzedAt\":\"2026-07-03T00:00:00Z\"}";
        return entity;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.completeIntake(String,CaseStatus,RiskLevel,String,String)」。
    // 具体功能：「FulfillmentCaseEntity.completeIntake(String,CaseStatus,RiskLevel,String,String)」：完成接待：先更新内部状态 「disputeType」、「caseStatus」、「riskLevel」、「intakeResultJson」；实际协作者为 「Objects.requireNonNull」、「required」；处理的关键状态/协议值包括 「intakeResultJson」、「actorId」，最终返回「void」。
    // 上游调用：「FulfillmentCaseEntity.completeIntake(String,CaseStatus,RiskLevel,String,String)」由使用「FulfillmentCaseEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FulfillmentCaseEntity.completeIntake(String,CaseStatus,RiskLevel,String,String)」向下依次触达 「Objects.requireNonNull」、「required」。
    // 系统意义：「FulfillmentCaseEntity.completeIntake(String,CaseStatus,RiskLevel,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void completeIntake(
            String disputeType,
            CaseStatus status,
            RiskLevel riskLevel,
            String intakeResultJson,
            String actorId) {
        this.disputeType = disputeType;
        this.caseStatus = Objects.requireNonNull(status, "status must not be null");
        this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        this.intakeResultJson = required(intakeResultJson, "intakeResultJson");
        this.updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.admitToEvidence(String,RiskLevel,String,OffsetDateTime,String)」。
    // 具体功能：「FulfillmentCaseEntity.admitToEvidence(String,RiskLevel,String,OffsetDateTime,String)」：更新admit证据：先更新内部状态 「disputeType」、「caseType」、「riskLevel」、「intakeResultJson」；实际协作者为 「Objects.requireNonNull」、「assertIntakeCanBeConfirmed」、「required」；处理的关键状态/协议值包括 「disputeType」、「DISPUTE」、「intakeAnalysisJson」、「EVIDENCE」，最终返回「void」。
    // 上游调用：「FulfillmentCaseEntity.admitToEvidence(String,RiskLevel,String,OffsetDateTime,String)」由使用「FulfillmentCaseEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FulfillmentCaseEntity.admitToEvidence(String,RiskLevel,String,OffsetDateTime,String)」向下依次触达 「Objects.requireNonNull」、「assertIntakeCanBeConfirmed」、「required」。
    // 系统意义：「FulfillmentCaseEntity.admitToEvidence(String,RiskLevel,String,OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void admitToEvidence(
            String disputeType,
            RiskLevel riskLevel,
            String intakeAnalysisJson,
            OffsetDateTime deadlineAt,
            String actorId) {
        assertIntakeCanBeConfirmed();
        this.disputeType = required(disputeType, "disputeType");
        this.caseType = "DISPUTE";
        this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        this.intakeResultJson = required(intakeAnalysisJson, "intakeAnalysisJson");
        this.caseStatus = CaseStatus.EVIDENCE_OPEN;
        this.currentRoom = "EVIDENCE";
        this.currentDeadlineAt =
                Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
        this.updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.rejectAsNotAdmissible(String,RiskLevel,String,String)」。
    // 具体功能：「FulfillmentCaseEntity.rejectAsNotAdmissible(String,RiskLevel,String,String)」：拒绝As不Admissible：先更新内部状态 「disputeType」、「riskLevel」、「intakeResultJson」、「caseStatus」；实际协作者为 「Objects.requireNonNull」、「assertIntakeCanBeConfirmed」、「required」；处理的关键状态/协议值包括 「disputeType」、「intakeAnalysisJson」、「actorId」，最终返回「void」。
    // 上游调用：「FulfillmentCaseEntity.rejectAsNotAdmissible(String,RiskLevel,String,String)」由使用「FulfillmentCaseEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FulfillmentCaseEntity.rejectAsNotAdmissible(String,RiskLevel,String,String)」向下依次触达 「Objects.requireNonNull」、「assertIntakeCanBeConfirmed」、「required」。
    // 系统意义：「FulfillmentCaseEntity.rejectAsNotAdmissible(String,RiskLevel,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void rejectAsNotAdmissible(
            String disputeType,
            RiskLevel riskLevel,
            String intakeAnalysisJson,
            String actorId) {
        assertIntakeCanBeConfirmed();
        this.disputeType = required(disputeType, "disputeType");
        this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        this.intakeResultJson = required(intakeAnalysisJson, "intakeAnalysisJson");
        this.caseStatus = CaseStatus.NOT_ADMISSIBLE;
        this.currentRoom = null;
        this.currentDeadlineAt = null;
        this.updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.cancelIntake(String,OffsetDateTime)」。
    // 具体功能：「FulfillmentCaseEntity.cancelIntake(String,OffsetDateTime)」：判断能否cancel接待：先更新内部状态 「caseStatus」、「currentRoom」、「currentDeadlineAt」、「closedAt」；实际协作者为 「Objects.requireNonNull」、「assertIntakeCanBeConfirmed」、「required」；处理的关键状态/协议值包括 「actorId」，最终返回「void」。
    // 上游调用：「FulfillmentCaseEntity.cancelIntake(String,OffsetDateTime)」由使用「FulfillmentCaseEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FulfillmentCaseEntity.cancelIntake(String,OffsetDateTime)」向下依次触达 「Objects.requireNonNull」、「assertIntakeCanBeConfirmed」、「required」。
    // 系统意义：「FulfillmentCaseEntity.cancelIntake(String,OffsetDateTime)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void cancelIntake(String actorId, OffsetDateTime now) {
        assertIntakeCanBeConfirmed();
        this.caseStatus = CaseStatus.CANCELLED;
        this.currentRoom = null;
        this.currentDeadlineAt = null;
        this.closedAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.assertIntakeCanBeConfirmed()」。
    // 具体功能：「FulfillmentCaseEntity.assertIntakeCanBeConfirmed()」：断言接待CanBeConfirmed；不满足前置条件时抛出 「IllegalStateException」，最终返回「void」。
    // 上游调用：「FulfillmentCaseEntity.assertIntakeCanBeConfirmed()」的上游调用点包括 「FulfillmentCaseEntity.admitToEvidence」、「FulfillmentCaseEntity.rejectAsNotAdmissible」、「FulfillmentCaseEntity.cancelIntake」。
    // 下游影响：「FulfillmentCaseEntity.assertIntakeCanBeConfirmed()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「FulfillmentCaseEntity.assertIntakeCanBeConfirmed()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    private void assertIntakeCanBeConfirmed() {
        if (caseStatus != CaseStatus.INTAKE_PENDING
                && caseStatus != CaseStatus.INTAKE_IN_PROGRESS
                && caseStatus != CaseStatus.WAITING_SLOT_COMPLETION
                && caseStatus != CaseStatus.INTAKE_COMPLETED) {
            throw new IllegalStateException(
                    "intake cannot be confirmed from case status " + caseStatus);
        }
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.openHearing(OffsetDateTime,String)」。
    // 具体功能：「FulfillmentCaseEntity.openHearing(OffsetDateTime,String)」：开放庭审：先更新内部状态 「routeType」、「caseStatus」、「currentRoom」、「currentDeadlineAt」；实际协作者为 「Objects.requireNonNull」、「required」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「HEARING」、「actorId」，最终返回「void」。
    // 上游调用：「FulfillmentCaseEntity.openHearing(OffsetDateTime,String)」的上游调用点包括 「EvidenceCompletionService.sealEvidenceAndOpenHearing」。
    // 下游影响：「FulfillmentCaseEntity.openHearing(OffsetDateTime,String)」向下依次触达 「Objects.requireNonNull」、「required」。
    // 系统意义：「FulfillmentCaseEntity.openHearing(OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void openHearing(OffsetDateTime deadlineAt, String actorId) {
        if (caseStatus != CaseStatus.EVIDENCE_OPEN
                && caseStatus != CaseStatus.EVIDENCE_SEALED) {
            throw new IllegalStateException("hearing cannot open from " + caseStatus);
        }
        routeType = RouteType.FULL_HEARING;
        caseStatus = CaseStatus.HEARING_OPEN;
        currentRoom = "HEARING";
        currentDeadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
        updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.attachHearingWorkflow(String,String)」。
    // 具体功能：「FulfillmentCaseEntity.attachHearingWorkflow(String,String)」：按attach庭审工作流：先更新内部状态 「currentWorkflowId」、「caseStatus」、「updatedBy」；实际协作者为 「ensureFullHearingRoute」、「required」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「workflowId」、「actorId」，最终返回「void」。
    // 上游调用：「FulfillmentCaseEntity.attachHearingWorkflow(String,String)」的上游调用点包括 「HearingCourtBootstrapService.ensureHearingState」。
    // 下游影响：「FulfillmentCaseEntity.attachHearingWorkflow(String,String)」向下依次触达 「ensureFullHearingRoute」、「required」。
    // 系统意义：「FulfillmentCaseEntity.attachHearingWorkflow(String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void attachHearingWorkflow(String workflowId, String actorId) {
        if (caseStatus != CaseStatus.HEARING_OPEN
                && caseStatus != CaseStatus.HEARING) {
            throw new IllegalStateException(
                    "hearing workflow cannot attach from " + caseStatus);
        }
        ensureFullHearingRoute(actorId);
        if (currentWorkflowId != null
                && !currentWorkflowId.equals(workflowId)) {
            throw new IllegalStateException(
                    "case is already controlled by another workflow");
        }
        currentWorkflowId = required(workflowId, "workflowId");
        caseStatus = CaseStatus.HEARING;
        updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.ensureFullHearingRoute(String)」。
    // 具体功能：「FulfillmentCaseEntity.ensureFullHearingRoute(String)」：确保Full庭审路由：先更新内部状态 「routeType」、「updatedBy」；实际协作者为 「isFullHearingLifecycle」、「required」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「actorId」，最终返回「void」。
    // 上游调用：「FulfillmentCaseEntity.ensureFullHearingRoute(String)」的上游调用点包括 「FulfillmentCaseEntity.attachHearingWorkflow」、「RemedyApplicationService.resolveSourceRoute」。
    // 下游影响：「FulfillmentCaseEntity.ensureFullHearingRoute(String)」向下依次触达 「isFullHearingLifecycle」、「required」。
    // 系统意义：「FulfillmentCaseEntity.ensureFullHearingRoute(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void ensureFullHearingRoute(String actorId) {
        if (routeType == RouteType.FULL_HEARING) {
            return;
        }
        if (routeType != null) {
            throw new IllegalStateException(
                    "case route " + routeType + " cannot be controlled by a hearing workflow");
        }
        if (!isFullHearingLifecycle(caseStatus, currentRoom)) {
            throw new IllegalStateException(
                    "full hearing route cannot be inferred from status "
                            + caseStatus
                            + " and room "
                            + currentRoom);
        }
        routeType = RouteType.FULL_HEARING;
        updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.markDossierBuilt(String)」。
    // 具体功能：「FulfillmentCaseEntity.markDossierBuilt(String)」：标记卷宗Built：先更新内部状态 「caseStatus」、「updatedBy」；实际协作者为 「required」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「actorId」，最终返回「void」。
    // 上游调用：「FulfillmentCaseEntity.markDossierBuilt(String)」由使用「FulfillmentCaseEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FulfillmentCaseEntity.markDossierBuilt(String)」向下依次触达 「required」。
    // 系统意义：「FulfillmentCaseEntity.markDossierBuilt(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void markDossierBuilt(String actorId) {
        if (caseStatus == CaseStatus.WAITING_SLOT_COMPLETION
                || caseStatus == CaseStatus.CLOSED
                || caseStatus == CaseStatus.CANCELLED) {
            throw new IllegalStateException(
                    "dossier cannot be built from case status " + caseStatus);
        }
        caseStatus = CaseStatus.DOSSIER_BUILT;
        updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.applyRoute(RouteType,String)」。
    // 具体功能：「FulfillmentCaseEntity.applyRoute(RouteType,String)」：应用路由：先更新内部状态 「routeType」、「caseStatus」、「updatedBy」；实际协作者为 「Objects.requireNonNull」、「required」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「actorId」，最终返回「void」。
    // 上游调用：「FulfillmentCaseEntity.applyRoute(RouteType,String)」由使用「FulfillmentCaseEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FulfillmentCaseEntity.applyRoute(RouteType,String)」向下依次触达 「Objects.requireNonNull」、「required」。
    // 系统意义：「FulfillmentCaseEntity.applyRoute(RouteType,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void applyRoute(RouteType routeType, String actorId) {
        if (caseStatus != CaseStatus.DOSSIER_BUILT) {
            throw new IllegalStateException(
                    "case cannot be routed from status " + caseStatus);
        }
        this.routeType = Objects.requireNonNull(routeType, "routeType must not be null");
        this.caseStatus = CaseStatus.ROUTED;
        this.updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.startHearing(String,String)」。
    // 具体功能：「FulfillmentCaseEntity.startHearing(String,String)」：启动庭审：先更新内部状态 「currentWorkflowId」、「caseStatus」、「updatedBy」；实际协作者为 「required」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「workflowId」、「actorId」，最终返回「void」。
    // 上游调用：「FulfillmentCaseEntity.startHearing(String,String)」由使用「FulfillmentCaseEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FulfillmentCaseEntity.startHearing(String,String)」向下依次触达 「required」。
    // 系统意义：「FulfillmentCaseEntity.startHearing(String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void startHearing(String workflowId, String actorId) {
        if (caseStatus != CaseStatus.ROUTED
                || routeType != RouteType.FULL_HEARING) {
            throw new IllegalStateException(
                    "hearing cannot start from status "
                            + caseStatus
                            + " and route "
                            + routeType);
        }
        this.currentWorkflowId = required(workflowId, "workflowId");
        this.caseStatus = CaseStatus.HEARING;
        this.updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.markRemedyPlanned(String)」。
    // 具体功能：「FulfillmentCaseEntity.markRemedyPlanned(String)」：标记补救Planned：先更新内部状态 「caseStatus」、「updatedBy」；实际协作者为 「required」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「actorId」，最终返回「void」。
    // 上游调用：「FulfillmentCaseEntity.markRemedyPlanned(String)」的上游调用点包括 「RemedyApplicationService.generate」。
    // 下游影响：「FulfillmentCaseEntity.markRemedyPlanned(String)」向下依次触达 「required」。
    // 系统意义：「FulfillmentCaseEntity.markRemedyPlanned(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void markRemedyPlanned(String actorId) {
        if (caseStatus != CaseStatus.ROUTED && caseStatus != CaseStatus.HEARING) {
            throw new IllegalStateException(
                    "remedy cannot be planned from status " + caseStatus);
        }
        this.caseStatus = CaseStatus.REMEDY_PLANNED;
        this.currentRoom = routeType == RouteType.FULL_HEARING ? "DRAFT" : "REVIEW";
        this.currentDeadlineAt = null;
        this.updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.waitForHumanReview(String)」。
    // 具体功能：「FulfillmentCaseEntity.waitForHumanReview(String)」：更新wait面向人工审核：先更新内部状态 「caseStatus」、「updatedBy」；实际协作者为 「required」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「actorId」，最终返回「void」。
    // 上游调用：「FulfillmentCaseEntity.waitForHumanReview(String)」由使用「FulfillmentCaseEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FulfillmentCaseEntity.waitForHumanReview(String)」向下依次触达 「required」。
    // 系统意义：「FulfillmentCaseEntity.waitForHumanReview(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void waitForHumanReview(String actorId) {
        if (caseStatus != CaseStatus.REMEDY_PLANNED
                && caseStatus != CaseStatus.WAITING_EVIDENCE) {
            throw new IllegalStateException(
                    "review cannot start from status " + caseStatus);
        }
        caseStatus = CaseStatus.WAITING_HUMAN_REVIEW;
        currentRoom = routeType == RouteType.FULL_HEARING ? "DRAFT" : "REVIEW";
        currentDeadlineAt = null;
        updatedBy = required(actorId, "actorId");
    }

    public void enterHumanReview(String actorId) {
        if (caseStatus != CaseStatus.WAITING_HUMAN_REVIEW) {
            throw new IllegalStateException(
                    "review room cannot open from status " + caseStatus);
        }
        currentRoom = "REVIEW";
        currentDeadlineAt = null;
        updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.applyReviewOutcome(ApprovalDecisionType,String)」。
    // 具体功能：「FulfillmentCaseEntity.applyReviewOutcome(ApprovalDecisionType,String)」：应用审核结果：先更新内部状态 「caseStatus」、「updatedBy」；实际协作者为 「required」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「actorId」，最终返回「void」。
    // 上游调用：「FulfillmentCaseEntity.applyReviewOutcome(ApprovalDecisionType,String)」由使用「FulfillmentCaseEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FulfillmentCaseEntity.applyReviewOutcome(ApprovalDecisionType,String)」向下依次触达 「required」。
    // 系统意义：「FulfillmentCaseEntity.applyReviewOutcome(ApprovalDecisionType,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void applyReviewOutcome(
            com.example.dispute.domain.model.ApprovalDecisionType decision,
            String actorId) {
        if (caseStatus != CaseStatus.WAITING_HUMAN_REVIEW) {
            throw new IllegalStateException(
                    "review cannot complete from status " + caseStatus);
        }
        caseStatus =
                switch (decision) {
                    case APPROVE, MODIFY_AND_APPROVE ->
                            CaseStatus.APPROVED_FOR_EXECUTION;
                    case REQUEST_MORE_EVIDENCE -> CaseStatus.WAITING_EVIDENCE;
                    case REJECT, ESCALATE_MANUAL -> CaseStatus.MANUAL_HANDOFF;
                };
        currentRoom =
                decision == com.example.dispute.domain.model.ApprovalDecisionType.REQUEST_MORE_EVIDENCE
                        ? "EVIDENCE"
                        : "OUTCOME";
        currentDeadlineAt = null;
        updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.beginExecution(String)」。
    // 具体功能：「FulfillmentCaseEntity.beginExecution(String)」：更新begin执行：先更新内部状态 「caseStatus」、「updatedBy」；实际协作者为 「required」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「actorId」，最终返回「void」。
    // 上游调用：「FulfillmentCaseEntity.beginExecution(String)」由使用「FulfillmentCaseEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FulfillmentCaseEntity.beginExecution(String)」向下依次触达 「required」。
    // 系统意义：「FulfillmentCaseEntity.beginExecution(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void beginExecution(String actorId) {
        if (caseStatus == CaseStatus.EXECUTING) {
            updatedBy = required(actorId, "actorId");
            return;
        }
        if (caseStatus != CaseStatus.APPROVED_FOR_EXECUTION) {
            throw new IllegalStateException(
                    "execution cannot start from status " + caseStatus);
        }
        caseStatus = CaseStatus.EXECUTING;
        currentRoom = "OUTCOME";
        currentDeadlineAt = null;
        updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.close(String)」。
    // 具体功能：「FulfillmentCaseEntity.close(String)」：关闭履约案件：先更新内部状态 「caseStatus」、「closedAt」、「updatedBy」；实际协作者为 「required」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「actorId」，最终返回「void」。
    // 上游调用：「FulfillmentCaseEntity.close(String)」由使用「FulfillmentCaseEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FulfillmentCaseEntity.close(String)」向下依次触达 「required」。
    // 系统意义：「FulfillmentCaseEntity.close(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void close(String actorId) {
        if (caseStatus == CaseStatus.CLOSED) {
            return;
        }
        if (caseStatus != CaseStatus.EXECUTING) {
            throw new IllegalStateException(
                    "case cannot close from status " + caseStatus);
        }
        caseStatus = CaseStatus.CLOSED;
        currentRoom = "OUTCOME";
        currentDeadlineAt = null;
        closedAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.prePersist()」。
    // 具体功能：「FulfillmentCaseEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「now」、「createdAt」、「updatedAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「FulfillmentCaseEntity.prePersist()」由使用「FulfillmentCaseEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FulfillmentCaseEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「FulfillmentCaseEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.preUpdate()」。
    // 具体功能：「FulfillmentCaseEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「FulfillmentCaseEntity.preUpdate()」由使用「FulfillmentCaseEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FulfillmentCaseEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「FulfillmentCaseEntity.preUpdate()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.required(String,String)」。
    // 具体功能：「FulfillmentCaseEntity.required(String,String)」：校验字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「FulfillmentCaseEntity.required(String,String)」的上游调用点包括 「FulfillmentCaseEntity.FulfillmentCaseEntity」、「FulfillmentCaseEntity.imported」、「FulfillmentCaseEntity.completeIntake」、「FulfillmentCaseEntity.admitToEvidence」。
    // 下游影响：「FulfillmentCaseEntity.required(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.required(String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.blankToNull(String)」。
    // 具体功能：「FulfillmentCaseEntity.blankToNull(String)」：判断空白值空值，最终返回「String」。
    // 上游调用：「FulfillmentCaseEntity.blankToNull(String)」的上游调用点包括 「FulfillmentCaseEntity.create」、「FulfillmentCaseEntity.imported」。
    // 下游影响：「FulfillmentCaseEntity.blankToNull(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.blankToNull(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.inferInitiatorRole(String,String,String,ActorRole)」。
    // 具体功能：「FulfillmentCaseEntity.inferInitiatorRole(String,String,String,ActorRole)」：构建infer发起方角色，最终返回「ActorRole」。
    // 上游调用：「FulfillmentCaseEntity.inferInitiatorRole(String,String,String,ActorRole)」的上游调用点包括 「FulfillmentCaseEntity.FulfillmentCaseEntity」。
    // 下游影响：「FulfillmentCaseEntity.inferInitiatorRole(String,String,String,ActorRole)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ActorRole」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.inferInitiatorRole(String,String,String,ActorRole)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    private static ActorRole inferInitiatorRole(
            String userId, String merchantId, String actorId, ActorRole fallback) {
        if (actorId != null && actorId.equals(userId)) {
            return ActorRole.USER;
        }
        if (actorId != null && actorId.equals(merchantId)) {
            return ActorRole.MERCHANT;
        }
        return fallback;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.isFullHearingLifecycle(CaseStatus,String)」。
    // 具体功能：「FulfillmentCaseEntity.isFullHearingLifecycle(CaseStatus,String)」：判断是否Full庭审生命周期；处理的关键状态/协议值包括 「HEARING」，最终返回「boolean」。
    // 上游调用：「FulfillmentCaseEntity.isFullHearingLifecycle(CaseStatus,String)」的上游调用点包括 「FulfillmentCaseEntity.imported」、「FulfillmentCaseEntity.ensureFullHearingRoute」。
    // 下游影响：「FulfillmentCaseEntity.isFullHearingLifecycle(CaseStatus,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.isFullHearingLifecycle(CaseStatus,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    private static boolean isFullHearingLifecycle(CaseStatus status, String room) {
        if ("HEARING".equals(room)) {
            return true;
        }
        return status == CaseStatus.HEARING
                || status == CaseStatus.HEARING_OPEN
                || status == CaseStatus.WAITING_EVIDENCE
                || status == CaseStatus.SETTLEMENT_PENDING
                || status == CaseStatus.DRAFT_READY
                || status == CaseStatus.DELIBERATION_RUNNING;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getCaseStatus()」。
    // 具体功能：「FulfillmentCaseEntity.getCaseStatus()」：读取「FulfillmentCaseEntity」中的「caseStatus」状态，向 JPA、应用服务或序列化层返回「CaseStatus」。
    // 上游调用：「FulfillmentCaseEntity.getCaseStatus()」的上游调用点包括 「ExternalCaseImportTransactionService.materializePersistedCurrentRoom」、「ExternalCaseImportTransactionService.view」、「CaseApplicationService.toView」、「EvidenceCompletionService.sealEvidenceAndOpenHearing」。
    // 下游影响：「FulfillmentCaseEntity.getCaseStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「CaseStatus」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getCaseStatus()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public CaseStatus getCaseStatus() {
        return caseStatus;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getRiskLevel()」。
    // 具体功能：「FulfillmentCaseEntity.getRiskLevel()」：读取「FulfillmentCaseEntity」中的「riskLevel」状态，向 JPA、应用服务或序列化层返回「RiskLevel」。
    // 上游调用：「FulfillmentCaseEntity.getRiskLevel()」的上游调用点包括 「ExternalCaseImportTransactionService.view」、「CaseApplicationService.toView」、「CaseClosureService.buildSnapshot」、「HearingCourtBootstrapService.intakeFactMap」。
    // 下游影响：「FulfillmentCaseEntity.getRiskLevel()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RiskLevel」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getRiskLevel()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getRouteType()」。
    // 具体功能：「FulfillmentCaseEntity.getRouteType()」：读取「FulfillmentCaseEntity」中的「routeType」状态，向 JPA、应用服务或序列化层返回「RouteType」。
    // 上游调用：「FulfillmentCaseEntity.getRouteType()」的上游调用点包括 「CaseApplicationService.toView」、「CaseClosureService.buildSnapshot」、「HearingCourtBootstrapService.canReadExistingBootstrappedCourt」、「RemedyApplicationService.resolveSourceRoute」。
    // 下游影响：「FulfillmentCaseEntity.getRouteType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RouteType」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getRouteType()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public RouteType getRouteType() {
        return routeType;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getVersion()」。
    // 具体功能：「FulfillmentCaseEntity.getVersion()」：读取「FulfillmentCaseEntity」中的「version」状态，向 JPA、应用服务或序列化层返回「long」。
    // 上游调用：「FulfillmentCaseEntity.getVersion()」的上游调用点包括 「HearingCourtBootstrapService.courtroomContext」、「EvidenceContextEnvelopeFactory.caseSnapshot」。
    // 下游影响：「FulfillmentCaseEntity.getVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「long」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public long getVersion() {
        return version;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getOrderId()」。
    // 具体功能：「FulfillmentCaseEntity.getOrderId()」：读取「FulfillmentCaseEntity」中的「orderId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FulfillmentCaseEntity.getOrderId()」的上游调用点包括 「ExternalCaseImportTransactionService.view」、「CaseApplicationService.toView」、「HearingCourtBootstrapService.courtroomContext」、「HearingCourtOrchestrator.command」。
    // 下游影响：「FulfillmentCaseEntity.getOrderId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getOrderId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getOrderId() {
        return orderId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getAfterSaleId()」。
    // 具体功能：「FulfillmentCaseEntity.getAfterSaleId()」：读取「FulfillmentCaseEntity」中的「afterSaleId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FulfillmentCaseEntity.getAfterSaleId()」的上游调用点包括 「ExternalCaseImportTransactionService.view」、「CaseApplicationService.toView」、「HearingCourtBootstrapService.courtroomContext」、「HearingCourtOrchestrator.command」。
    // 下游影响：「FulfillmentCaseEntity.getAfterSaleId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getAfterSaleId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getAfterSaleId() {
        return afterSaleId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getLogisticsId()」。
    // 具体功能：「FulfillmentCaseEntity.getLogisticsId()」：读取「FulfillmentCaseEntity」中的「logisticsId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FulfillmentCaseEntity.getLogisticsId()」的上游调用点包括 「ExternalCaseImportTransactionService.view」、「CaseApplicationService.toView」、「HearingCourtBootstrapService.courtroomContext」、「HearingCourtBootstrapService.defaultKnownFacts」。
    // 下游影响：「FulfillmentCaseEntity.getLogisticsId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getLogisticsId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getLogisticsId() {
        return logisticsId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getUserId()」。
    // 具体功能：「FulfillmentCaseEntity.getUserId()」：读取「FulfillmentCaseEntity」中的「userId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FulfillmentCaseEntity.getUserId()」的上游调用点包括 「ExternalCaseImportTransactionService.view」、「CaseApplicationService.assertCanRead」、「CaseApplicationService.toView」、「EvidenceCatalogService.assertCanAccess」。
    // 下游影响：「FulfillmentCaseEntity.getUserId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getUserId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getUserId() {
        return userId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getMerchantId()」。
    // 具体功能：「FulfillmentCaseEntity.getMerchantId()」：读取「FulfillmentCaseEntity」中的「merchantId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FulfillmentCaseEntity.getMerchantId()」的上游调用点包括 「ExternalCaseImportTransactionService.view」、「CaseApplicationService.assertCanRead」、「CaseApplicationService.toView」、「EvidenceCatalogService.assertCanAccess」。
    // 下游影响：「FulfillmentCaseEntity.getMerchantId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getMerchantId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getMerchantId() {
        return merchantId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getInitiatorRole()」。
    // 具体功能：「FulfillmentCaseEntity.getInitiatorRole()」：读取「FulfillmentCaseEntity」中的「initiatorRole」状态，向 JPA、应用服务或序列化层返回「ActorRole」。
    // 上游调用：「FulfillmentCaseEntity.getInitiatorRole()」的上游调用点包括 「ExternalCaseImportTransactionService.view」、「CaseApplicationService.toView」、「EvidenceCompletionService.assertInitiatorHasSubmittedEvidence」、「HearingCourtBootstrapService.intakeFactMap」。
    // 下游影响：「FulfillmentCaseEntity.getInitiatorRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ActorRole」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getInitiatorRole()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public ActorRole getInitiatorRole() {
        return initiatorRole;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getCaseType()」。
    // 具体功能：「FulfillmentCaseEntity.getCaseType()」：读取「FulfillmentCaseEntity」中的「caseType」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FulfillmentCaseEntity.getCaseType()」的上游调用点包括 「CaseApplicationService.toView」、「EvidenceContextEnvelopeFactory.caseSnapshot」、「RouterApplicationService.createConclusion」。
    // 下游影响：「FulfillmentCaseEntity.getCaseType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getCaseType()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCaseType() {
        return caseType;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getDisputeType()」。
    // 具体功能：「FulfillmentCaseEntity.getDisputeType()」：读取「FulfillmentCaseEntity」中的「disputeType」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FulfillmentCaseEntity.getDisputeType()」的上游调用点包括 「ExternalCaseImportTransactionService.view」、「CaseApplicationService.toView」、「HearingCourtBootstrapService.courtroomContext」、「HearingCourtBootstrapService.defaultPolicyHooks」。
    // 下游影响：「FulfillmentCaseEntity.getDisputeType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getDisputeType()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getDisputeType() {
        return disputeType;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getTitle()」。
    // 具体功能：「FulfillmentCaseEntity.getTitle()」：读取「FulfillmentCaseEntity」中的「title」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FulfillmentCaseEntity.getTitle()」的上游调用点包括 「ExternalCaseImportTransactionService.view」、「CaseApplicationService.toView」、「HearingCourtBootstrapService.judgeOpeningText」、「HearingCourtOrchestrator.command」。
    // 下游影响：「FulfillmentCaseEntity.getTitle()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getTitle()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getTitle() {
        return title;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getDescription()」。
    // 具体功能：「FulfillmentCaseEntity.getDescription()」：读取「FulfillmentCaseEntity」中的「description」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FulfillmentCaseEntity.getDescription()」的上游调用点包括 「ExternalCaseImportTransactionService.view」、「CaseApplicationService.toView」、「HearingCourtBootstrapService.intakeFactMap」、「HearingCourtBootstrapService.defaultClaimResolution」。
    // 下游影响：「FulfillmentCaseEntity.getDescription()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getDescription()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getDescription() {
        return description;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getIntakeResultJson()」。
    // 具体功能：「FulfillmentCaseEntity.getIntakeResultJson()」：读取「FulfillmentCaseEntity」中的「intakeResultJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FulfillmentCaseEntity.getIntakeResultJson()」的上游调用点包括 「IntakeRoomService.acceptedIntakeResultJson」。
    // 下游影响：「FulfillmentCaseEntity.getIntakeResultJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getIntakeResultJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getIntakeResultJson() {
        return intakeResultJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getCreatedAt()」。
    // 具体功能：「FulfillmentCaseEntity.getCreatedAt()」：读取「FulfillmentCaseEntity」中的「createdAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「FulfillmentCaseEntity.getCreatedAt()」的上游调用点包括 「CaseApplicationService.toView」。
    // 下游影响：「FulfillmentCaseEntity.getCreatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getCreatedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getUpdatedAt()」。
    // 具体功能：「FulfillmentCaseEntity.getUpdatedAt()」：读取「FulfillmentCaseEntity」中的「updatedAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「FulfillmentCaseEntity.getUpdatedAt()」的上游调用点包括 「CaseApplicationService.toView」。
    // 下游影响：「FulfillmentCaseEntity.getUpdatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getUpdatedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getCurrentWorkflowId()」。
    // 具体功能：「FulfillmentCaseEntity.getCurrentWorkflowId()」：读取「FulfillmentCaseEntity」中的「currentWorkflowId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FulfillmentCaseEntity.getCurrentWorkflowId()」的上游调用点包括 「HearingCourtBootstrapService.ensureHearingState」、「HearingCourtOrchestrator.command」、「RemedyApplicationService.resolveSourceRoute」。
    // 下游影响：「FulfillmentCaseEntity.getCurrentWorkflowId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getCurrentWorkflowId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCurrentWorkflowId() {
        return currentWorkflowId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getClosedAt()」。
    // 具体功能：「FulfillmentCaseEntity.getClosedAt()」：读取「FulfillmentCaseEntity」中的「closedAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「FulfillmentCaseEntity.getClosedAt()」的上游调用点包括 「CaseApplicationService.toView」。
    // 下游影响：「FulfillmentCaseEntity.getClosedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getClosedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getClosedAt() {
        return closedAt;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getSourceType()」。
    // 具体功能：「FulfillmentCaseEntity.getSourceType()」：读取「FulfillmentCaseEntity」中的「sourceType」状态，向 JPA、应用服务或序列化层返回「CaseSourceType」。
    // 上游调用：「FulfillmentCaseEntity.getSourceType()」的上游调用点包括 「ExternalCaseImportTransactionService.view」、「CaseApplicationService.toView」、「CaseOutcomeService.finalDecision」、「EvidenceContextEnvelopeFactory.caseSnapshot」。
    // 下游影响：「FulfillmentCaseEntity.getSourceType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「CaseSourceType」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getSourceType()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public CaseSourceType getSourceType() {
        return sourceType;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getSourceSystem()」。
    // 具体功能：「FulfillmentCaseEntity.getSourceSystem()」：读取「FulfillmentCaseEntity」中的「sourceSystem」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FulfillmentCaseEntity.getSourceSystem()」的上游调用点包括 「ExternalCaseImportTransactionService.view」、「CaseApplicationService.toView」、「EvidenceContextEnvelopeFactory.caseSnapshot」。
    // 下游影响：「FulfillmentCaseEntity.getSourceSystem()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getSourceSystem()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getSourceSystem() {
        return sourceSystem;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getExternalCaseRef()」。
    // 具体功能：「FulfillmentCaseEntity.getExternalCaseRef()」：读取「FulfillmentCaseEntity」中的「externalCaseRef」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FulfillmentCaseEntity.getExternalCaseRef()」的上游调用点包括 「ExternalCaseImportTransactionService.view」、「CaseApplicationService.toView」、「EvidenceContextEnvelopeFactory.caseSnapshot」。
    // 下游影响：「FulfillmentCaseEntity.getExternalCaseRef()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getExternalCaseRef()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getExternalCaseRef() {
        return externalCaseRef;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getCurrentRoom()」。
    // 具体功能：「FulfillmentCaseEntity.getCurrentRoom()」：读取「FulfillmentCaseEntity」中的「currentRoom」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FulfillmentCaseEntity.getCurrentRoom()」的上游调用点包括 「ExternalCaseImportTransactionService.materializePersistedCurrentRoom」、「ExternalCaseImportTransactionService.view」、「CaseApplicationService.toView」、「EvidenceCatalogService.isHearingPartySharedEvidence」。
    // 下游影响：「FulfillmentCaseEntity.getCurrentRoom()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getCurrentRoom()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCurrentRoom() {
        return currentRoom;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FulfillmentCaseEntity.getCurrentDeadlineAt()」。
    // 具体功能：「FulfillmentCaseEntity.getCurrentDeadlineAt()」：读取「FulfillmentCaseEntity」中的「currentDeadlineAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「FulfillmentCaseEntity.getCurrentDeadlineAt()」的上游调用点包括 「ExternalCaseImportTransactionService.materializePersistedCurrentRoom」、「ExternalCaseImportTransactionService.view」、「CaseApplicationService.toView」、「EvidenceCompletionService.announceHearingOpened」。
    // 下游影响：「FulfillmentCaseEntity.getCurrentDeadlineAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「FulfillmentCaseEntity.getCurrentDeadlineAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getCurrentDeadlineAt() {
        return currentDeadlineAt;
    }
}
