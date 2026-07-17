package com.example.dispute.workflow.activity.domain;

import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ReconciliationScope.COMMAND;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ReconciliationSeverity.ERROR;

import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseTimelineEventEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.example.dispute.workflow.application.command.CaseCommandReferenceMapper;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.ProcessReconciliationIssueEntity;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.ProcessReconciliationIssueRepository;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceGapReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CaseProcessLedgerActivitiesImpl implements CaseProcessLedgerActivities {

    private final CaseCommandRepository commandRepository;
    private final CaseTimelineEventRepository eventRepository;
    private final CaseRoomRepository roomRepository;
    private final CaseRoomEpochRepository roomEpochRepository;
    private final CaseProcessProjectionRepository projectionRepository;
    private final ProcessReconciliationIssueRepository issueRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CaseProcessLedgerActivitiesImpl(
            CaseCommandRepository commandRepository,
            CaseTimelineEventRepository eventRepository,
            CaseRoomRepository roomRepository,
            CaseRoomEpochRepository roomEpochRepository,
            CaseProcessProjectionRepository projectionRepository,
            ProcessReconciliationIssueRepository issueRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.commandRepository = commandRepository;
        this.eventRepository = eventRepository;
        this.roomRepository = roomRepository;
        this.roomEpochRepository = roomEpochRepository;
        this.projectionRepository = projectionRepository;
        this.issueRepository = issueRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CaseCommandRef> loadCaseCommands(LoadSequenceRange request) {
        requireProjectionScope(request.tenantSurrogate(), request.caseId());
        return commandRepository
                .findByTenantSurrogateAndCaseIdAndCaseCommandSequenceBetweenOrderByCaseCommandSequenceAsc(
                        request.tenantSurrogate(),
                        request.caseId(),
                        request.fromSequenceInclusive(),
                        request.toSequenceInclusive())
                .stream()
                .limit(request.limit())
                .map(command -> CaseCommandReferenceMapper.fromEntity(command, objectMapper))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CaseDomainEventRef> loadDomainEvents(LoadSequenceRange request) {
        requireProjectionScope(request.tenantSurrogate(), request.caseId());
        return eventRepository
                .findByCaseIdAndSequenceNoBetweenOrderBySequenceNoAsc(
                        request.caseId(),
                        request.fromSequenceInclusive(),
                        request.toSequenceInclusive())
                .stream()
                .limit(request.limit())
                .map(event -> eventRef(request, event))
                .toList();
    }

    @Override
    @Transactional
    public void reportSequenceGap(SequenceGapReport report) {
        CaseProcessProjectionEntity projection =
                requireProjectionScope(report.tenantSurrogate(), report.caseId());
        String canonical =
                String.join(
                        "|",
                        "case-process-sequence-gap.v1",
                        report.tenantSurrogate(),
                        report.caseId(),
                        report.workflowId(),
                        report.stream().name(),
                        Long.toString(report.expectedSequence()),
                        Long.toString(report.highestObservedSequence()),
                        report.reasonCode());
        String digest = sha256(canonical);
        String issueKey = "sequence-gap:" + digest;
        issueRepository.lockTenantIssueKey(report.tenantSurrogate(), issueKey);
        ProcessReconciliationIssueEntity issue =
                issueRepository
                        .findByTenantSurrogateAndIssueKey(
                                report.tenantSurrogate(), issueKey)
                        .orElseGet(
                                () ->
                                        ProcessReconciliationIssueEntity.detected(
                                                "PRI_" + digest.substring(0, 60),
                                                issueKey,
                                                report.tenantSurrogate(),
                                                report.caseId(),
                                                report.stream().name() + "_SEQUENCE_GAP",
                                                COMMAND,
                                                ERROR,
                                                roomType(projection.getCurrentRoom()),
                                                projection.getRoomEpoch(),
                                                projection.getProcessRevision(),
                                                projection.getFencingToken(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                gapDetails(report),
                                                now()));
        issue.reopenIfResolved(now());
        issueRepository.saveAndFlush(issue);
    }

    private CaseDomainEventRef eventRef(
            LoadSequenceRange request, CaseTimelineEventEntity event) {
        EventRoomEpoch eventRoomEpoch =
                roomEpoch(request.tenantSurrogate(), request.caseId(), event);
        byte[] payload = event.getEventJson().getBytes(StandardCharsets.UTF_8);
        String payloadHash = sha256(payload);
        return new CaseDomainEventRef(
                "case-domain-event-ref.v1",
                event.getId(),
                request.tenantSurrogate(),
                request.caseId(),
                event.getSequenceNo(),
                event.getEventType(),
                eventRoomEpoch.roomType(),
                eventRoomEpoch.roomEpoch(),
                new PayloadRef(
                        "case-timeline-event.v1",
                        "urn:case-timeline-event:" + event.getId(),
                        payloadHash,
                        payload.length),
                event.getEventTime(),
                traceparent(event.getId()));
    }

    private EventRoomEpoch roomEpoch(
            String tenantSurrogate, String caseId, CaseTimelineEventEntity event) {
        if (event.getRoomId() == null) {
            return new EventRoomEpoch(null, 0);
        }
        CaseRoomEntity room =
                roomRepository
                        .findById(event.getRoomId())
                        .filter(candidate -> candidate.getCaseId().equals(caseId))
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "timeline event room binding is invalid"));
        RoomType roomType;
        try {
            roomType = RoomType.valueOf(room.getRoomType().name());
        } catch (IllegalArgumentException unsupportedRoom) {
            return new EventRoomEpoch(null, 0);
        }
        List<CaseRoomEpochEntity> epochs =
                roomEpochRepository.findEpochAt(
                        tenantSurrogate,
                        caseId,
                        roomType,
                        OffsetDateTime.ofInstant(event.getEventTime(), ZoneOffset.UTC),
                        PageRequest.of(0, 2));
        if (epochs.size() != 1) {
            throw new IllegalStateException(
                    "timeline event does not resolve to exactly one room epoch");
        }
        return new EventRoomEpoch(roomType, epochs.getFirst().getRoomEpoch());
    }

    private CaseProcessProjectionEntity requireProjectionScope(
            String tenantSurrogate, String caseId) {
        CaseProcessProjectionEntity projection =
                projectionRepository
                        .findById(caseId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "case process projection is unavailable"));
        if (!projection.getTenantSurrogate().equals(tenantSurrogate)) {
            throw new IllegalArgumentException("case process tenant scope mismatch");
        }
        return projection;
    }

    private String gapDetails(SequenceGapReport report) {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("schemaVersion", "case-process-sequence-gap.v1");
        details.put("workflowId", report.workflowId());
        details.put("workflowRunId", report.workflowRunId());
        details.put("stream", report.stream().name());
        details.put("expectedSequence", report.expectedSequence());
        details.put("highestObservedSequence", report.highestObservedSequence());
        details.put("recoveryAttempts", report.recoveryAttempts());
        details.put("reasonCode", report.reasonCode());
        return details.toString();
    }

    private static RoomType roomType(String currentRoom) {
        if (currentRoom == null) {
            return null;
        }
        try {
            return RoomType.valueOf(currentRoom);
        } catch (IllegalArgumentException unsupportedRoom) {
            return null;
        }
    }

    private static String traceparent(String eventId) {
        String digest = sha256("case-timeline-event:" + eventId);
        return "00-" + digest.substring(0, 32) + "-" + digest.substring(32, 48) + "-01";
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private record EventRoomEpoch(RoomType roomType, long roomEpoch) {}
}
