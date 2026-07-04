package com.example.dispute.caseintake.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AppProperties;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.AuditLogEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.AuditLogRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaseApplicationService {

    private final FulfillmentCaseRepository caseRepository;
    private final AuditLogRepository auditLogRepository;
    private final AgentServiceClient agentServiceClient;
    private final CaseRoomRepository roomRepository;
    private final ParticipantService participantService;
    private final AppProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public CaseApplicationService(
            FulfillmentCaseRepository caseRepository,
            AuditLogRepository auditLogRepository,
            AgentServiceClient agentServiceClient,
            CaseRoomRepository roomRepository,
            ParticipantService participantService,
            AppProperties properties,
            Clock clock,
            ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.auditLogRepository = auditLogRepository;
        this.agentServiceClient = agentServiceClient;
        this.roomRepository = roomRepository;
        this.participantService = participantService;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CaseView create(
            CreateCaseCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId,
            String requestId) {
        assertCanCreate(command, actor);
        return caseRepository
                .findByCreationIdempotencyKey(idempotencyKey)
                .map(
                        entity -> {
                            assertCanRead(entity, actor);
                            return toView(
                                    entity,
                                    readSnapshot(entity.getIntakeResultJson()));
                        })
                .orElseGet(
                        () ->
                                createNew(
                                        command,
                                        actor,
                                        idempotencyKey,
                                        traceId,
                                        requestId));
    }

    @Transactional(readOnly = true)
    public CaseView get(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity entity =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "case not found",
                                                Map.of("case_id", caseId)));
        assertCanRead(entity, actor);
        return toView(entity, readSnapshot(entity.getIntakeResultJson()));
    }

    @Transactional(readOnly = true)
    public CasePageView list(
            CaseStatus status,
            String disputeType,
            int page,
            int size,
            AuthenticatedActor actor) {
        Specification<FulfillmentCaseEntity> specification =
                (root, query, criteria) ->
                        criteria.and(
                                criteria.isNull(root.get("deletedAt")),
                                criteria.or(
                                        criteria.equal(
                                                root.get("caseType"),
                                                "DISPUTE"),
                                        criteria.equal(
                                                root.get("caseType"),
                                                "FULFILLMENT_DISPUTE")));
        if (status != null) {
            specification =
                    specification.and(
                            (root, query, criteria) ->
                                    criteria.equal(root.get("caseStatus"), status));
        }
        if (disputeType != null && !disputeType.isBlank()) {
            specification =
                    specification.and(
                            (root, query, criteria) ->
                                    criteria.equal(
                                            root.get("disputeType"),
                                            disputeType));
        }
        specification =
                switch (actor.role()) {
                    case USER ->
                            specification.and(
                                    (root, query, criteria) ->
                                            criteria.equal(
                                                    root.get("userId"),
                                                    actor.actorId()));
                    case MERCHANT ->
                            specification.and(
                                    (root, query, criteria) ->
                                            criteria.equal(
                                                    root.get("merchantId"),
                                                    actor.actorId()));
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM ->
                            specification;
                };
        Page<FulfillmentCaseEntity> result =
                caseRepository.findAll(
                        specification,
                        PageRequest.of(
                                page,
                                size,
                                Sort.by(Sort.Direction.DESC, "createdAt")));
        return new CasePageView(
                result.getContent().stream()
                        .map(
                                entity ->
                                        toView(
                                                entity,
                                                readSnapshot(
                                                        entity.getIntakeResultJson())))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    private CaseView createNew(
            CreateCaseCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId,
            String requestId) {
        boolean degraded = false;
        IntakeAnalysis analysis;
        try {
            analysis =
                    properties.feature().agentIntakeEnabled()
                            ? agentServiceClient.analyze(command, traceId, requestId)
                            : fallback(command);
        } catch (RuntimeException failure) {
            analysis = fallback(command);
            degraded = true;
        }

        List<String> missingSlots =
                command.orderId() == null || command.orderId().isBlank()
                        ? mergeOrderSlot(analysis.missingSlots())
                        : analysis.missingSlots();
        CaseStatus status =
                missingSlots.isEmpty()
                        ? CaseStatus.INTAKE_COMPLETED
                        : CaseStatus.WAITING_SLOT_COMPLETION;
        IntakeSnapshot snapshot =
                new IntakeSnapshot(
                        analysis.potentialDispute(), missingSlots, degraded, clock.instant());
        String snapshotJson = writeJson(snapshot);
        String caseId = "CASE_" + compactUuid();
        FulfillmentCaseEntity entity =
                FulfillmentCaseEntity.create(
                        caseId,
                        blankToNull(command.orderId()),
                        blankToNull(command.afterSaleId()),
                        required(command.userId(), "userId"),
                        required(command.merchantId(), "merchantId"),
                        required(idempotencyKey, "idempotencyKey"),
                        required(analysis.caseType(), "caseType"),
                        required(analysis.title(), "title"),
                        required(analysis.normalizedDescription(), "normalizedDescription"),
                        analysis.riskLevel(),
                        actor.actorId());
        entity.completeIntake(
                analysis.disputeType(),
                status,
                analysis.riskLevel(),
                snapshotJson,
                actor.actorId());
        FulfillmentCaseEntity saved = caseRepository.save(entity);
        OffsetDateTime now = OffsetDateTime.now(clock);
        roomRepository.save(
                CaseRoomEntity.open(
                        "ROOM_" + compactUuid(),
                        caseId,
                        RoomType.INTAKE,
                        now,
                        actor.actorId()));
        if (actor.role() == ActorRole.USER
                || actor.role() == ActorRole.MERCHANT) {
            participantService.addInitiator(saved, actor, now);
        }

        if (properties.logging().auditEnabled()) {
            auditLogRepository.save(
                    AuditLogEntity.caseCreated(
                            "AUDIT_" + compactUuid(),
                            caseId,
                            traceId,
                            requestId,
                            actor.actorId(),
                            actor.role().name(),
                            writeJson(Map.of("case_status", status.name()))));
        }
        return toView(saved, snapshot);
    }

    private static IntakeAnalysis fallback(CreateCaseCommand command) {
        String input =
                command.description() == null
                        ? ""
                        : command.description().toLowerCase(Locale.ROOT);
        boolean dispute =
                input.contains("争议")
                        || input.contains("拒绝")
                        || input.contains("未收到")
                        || input.contains("没有收到");
        List<String> missing =
                command.orderId() == null || command.orderId().isBlank()
                        ? List.of("ORDER_ID")
                        : List.of();
        return new IntakeAnalysis(
                dispute ? "DISPUTE" : "TRANSFERRED",
                dispute ? "FULFILLMENT_CONFLICT" : null,
                dispute ? RiskLevel.HIGH : RiskLevel.MEDIUM,
                dispute,
                missing,
                dispute ? "履约争议待核实" : "履约问题待处理",
                required(command.description(), "description"));
    }

    private static List<String> mergeOrderSlot(List<String> slots) {
        if (slots.contains("ORDER_ID")) {
            return slots;
        }
        java.util.ArrayList<String> merged = new java.util.ArrayList<>(slots);
        merged.add("ORDER_ID");
        return List.copyOf(merged);
    }

    private void assertCanRead(FulfillmentCaseEntity entity, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(entity.getUserId());
                    case MERCHANT -> actor.actorId().equals(entity.getMerchantId());
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                };
        if (!allowed) {
            throw new ForbiddenException("actor cannot access this case");
        }
    }

    private static void assertCanCreate(
            CreateCaseCommand command, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(command.userId());
                    case MERCHANT -> actor.actorId().equals(command.merchantId());
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                };
        if (!allowed) {
            throw new ForbiddenException("actor cannot create a case for this party");
        }
    }

    private CaseView toView(FulfillmentCaseEntity entity, IntakeSnapshot snapshot) {
        return new CaseView(
                entity.getId(),
                entity.getOrderId(),
                entity.getAfterSaleId(),
                entity.getUserId(),
                entity.getMerchantId(),
                publicCaseType(entity.getCaseType()),
                entity.getDisputeType(),
                entity.getCaseStatus(),
                entity.getRouteType(),
                entity.getRiskLevel(),
                entity.getTitle(),
                entity.getDescription(),
                snapshot.potentialDispute(),
                snapshot.missingSlots(),
                snapshot.agentDegraded(),
                entity.getSourceType(),
                entity.getSourceSystem(),
                entity.getExternalCaseRef(),
                entity.getCurrentRoom(),
                entity.getCurrentDeadlineAt(),
                pendingAction(entity.getCaseStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getClosedAt());
    }

    private static String publicCaseType(String caseType) {
        return "FULFILLMENT_DISPUTE".equals(caseType)
                ? "DISPUTE"
                : caseType;
    }

    private static String pendingAction(CaseStatus status) {
        return switch (status) {
            case INTAKE_PENDING,
                    INTAKE_IN_PROGRESS,
                    WAITING_SLOT_COMPLETION,
                    INTAKE_COMPLETED -> "COMPLETE_INTAKE";
            case EVIDENCE_OPEN, WAITING_EVIDENCE -> "SUBMIT_EVIDENCE";
            case EVIDENCE_SEALED -> "ENTER_HEARING";
            case HEARING_OPEN, HEARING -> "PARTICIPATE_HEARING";
            case SETTLEMENT_PENDING -> "REVIEW_SETTLEMENT";
            case DRAFT_READY,
                    DELIBERATION_RUNNING,
                    REVIEW_PENDING,
                    WAITING_HUMAN_REVIEW,
                    REMEDY_PLANNED -> "AWAIT_REVIEW";
            case APPROVED_FOR_EXECUTION, EXECUTING -> "TRACK_EXECUTION";
            case CLOSED, CANCELLED, MANUAL_HANDOFF, NOT_ADMISSIBLE -> "VIEW_OUTCOME";
            default -> "CONTINUE_CASE";
        };
    }

    private IntakeSnapshot readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, IntakeSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted intake snapshot", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize intake data", exception);
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record IntakeSnapshot(
            boolean potentialDispute,
            List<String> missingSlots,
            boolean agentDegraded,
            java.time.Instant analyzedAt) {}
}
