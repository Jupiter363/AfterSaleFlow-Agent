package com.example.dispute.workflow.activity.domain;

import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ReconciliationScope.COMMAND;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ReconciliationSeverity.ERROR;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome.ALREADY_APPLIED;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome.ALREADY_EXPIRED;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome.ALREADY_FAILED;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome.ALREADY_REJECTED;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome.ALREADY_SHADOW_COMPLETED;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome.EXPIRED;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome.ORCHESTRATION_ACCEPTED;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome.SHADOW_COMPLETED;

import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseTimelineEventEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.example.dispute.workflow.application.command.CaseCommandReferenceMapper;
import com.example.dispute.workflow.application.epoch.RoomEpochReadiness;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.CommandStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.ProcessReconciliationIssueEntity;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.ProcessReconciliationIssueRepository;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommand;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommandResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRouted;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRoutedResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerEntry;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceGapReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.temporal.failure.ApplicationFailure;
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
public class CaseProcessLedgerActivitiesImpl
        implements CaseProcessLedgerActivities, CaseCommandLifecycleActivities {

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
    public List<CaseCommandLedgerEntry> loadCaseCommandLedgerEntries(
            LoadSequenceRange request) {
        requireProjectionScope(request.tenantSurrogate(), request.caseId());
        return commandRepository
                .findByTenantSurrogateAndCaseIdAndCaseCommandSequenceBetweenOrderByCaseCommandSequenceAsc(
                        request.tenantSurrogate(),
                        request.caseId(),
                        request.fromSequenceInclusive(),
                        request.toSequenceInclusive())
                .stream()
                .limit(request.limit())
                .map(
                        command ->
                                new CaseCommandLedgerEntry(
                                        "case-command-ledger-entry.v1",
                                        CaseCommandReferenceMapper.fromEntity(
                                                command, objectMapper),
                                        CaseCommandLedgerState.valueOf(
                                                command.getCommandStatus().name())))
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

    @Override
    @Transactional
    public ExpireCaseCommandResult expireCaseCommand(ExpireCaseCommand request) {
        CaseCommandEntity command = lockedCommand(request.tenantSurrogate(), request.commandId());
        if (!command.getCaseId().equals(request.caseId())
                || command.getCaseCommandSequence() != request.caseCommandSequence()
                || !command.getRequestHash().equals(request.requestHash())
                || !command.getDeadlineAt().toInstant().equals(request.deadlineAt())) {
            throw permanentFailure(
                    "CASE_COMMAND_EXPIRATION_SCOPE_MISMATCH",
                    "case command expiration scope mismatch");
        }
        CommandStatus status = command.getCommandStatus();
        if (status == CommandStatus.APPLIED) {
            return expirationResult(ALREADY_APPLIED);
        }
        if (status == CommandStatus.SHADOW_COMPLETED) {
            return expirationResult(ALREADY_SHADOW_COMPLETED);
        }
        if (status == CommandStatus.REJECTED) {
            return expirationResult(ALREADY_REJECTED);
        }
        if (status == CommandStatus.FAILED) {
            return expirationResult(ALREADY_FAILED);
        }
        if (status == CommandStatus.EXPIRED) {
            return expirationResult(ALREADY_EXPIRED);
        }
        command.markExpired(
                "COMMAND_DEADLINE_EXPIRED",
                OffsetDateTime.ofInstant(request.expiredAt(), ZoneOffset.UTC));
        return expirationResult(EXPIRED);
    }

    @Override
    @Transactional
    public RecordCaseCommandRoutedResult recordCaseCommandRouted(
            RecordCaseCommandRouted request) {
        CaseCommandEntity command = lockedRoutingCommand(request);
        CommandStatus status = command.getCommandStatus();
        RecordCaseCommandRoutedResult tombstone = routingTombstone(status);
        if (tombstone != null) {
            return tombstone;
        }
        if (status == CommandStatus.ORCHESTRATION_ACCEPTED) {
            return routingResult(ORCHESTRATION_ACCEPTED);
        }

        CaseRoomEpochEntity epoch = activeRoutingEpoch(request);
        OffsetDateTime routedAt = routingTime(request);
        if (!command.getDeadlineAt().isAfter(routedAt)) {
            command.markExpired("COMMAND_DEADLINE_EXPIRED", routedAt);
            return routingResult(EXPIRED);
        }
        if (epoch.getWriterMode() == WriterMode.SHADOW
                || epoch.getWriterMode() == WriterMode.TEMPORAL) {
            command.markOrchestrationAccepted(routedAt);
            return routingResult(ORCHESTRATION_ACCEPTED);
        }
        throw permanentFailure(
                "CASE_COMMAND_ROUTING_WRITER_REJECTED",
                "LEGACY epochs cannot accept Temporal commands");
    }

    @Override
    @Transactional
    public RecordCaseCommandRoutedResult completeCaseCommandRouting(
            RecordCaseCommandRouted request) {
        CaseCommandEntity command = lockedRoutingCommand(request);
        CommandStatus status = command.getCommandStatus();
        RecordCaseCommandRoutedResult tombstone = routingTombstone(status);
        if (tombstone != null) {
            return tombstone;
        }

        CaseRoomEpochEntity epoch = routingEpoch(request);
        if (status == CommandStatus.ORCHESTRATION_ACCEPTED
                && epoch.getWriterMode() == WriterMode.TEMPORAL) {
            return routingResult(ORCHESTRATION_ACCEPTED);
        }
        requireActiveEpoch(epoch);
        OffsetDateTime routedAt = routingTime(request);
        if (epoch.getWriterMode() == WriterMode.SHADOW) {
            command.markShadowCompleted(routedAt);
            return routingResult(SHADOW_COMPLETED);
        }
        if (epoch.getWriterMode() == WriterMode.TEMPORAL) {
            command.markOrchestrationAccepted(routedAt);
            return routingResult(ORCHESTRATION_ACCEPTED);
        }
        throw permanentFailure(
                "CASE_COMMAND_ROUTING_WRITER_REJECTED",
                "LEGACY epochs cannot accept Temporal commands");
    }

    private CaseCommandEntity lockedRoutingCommand(RecordCaseCommandRouted request) {
        CaseCommandEntity command = lockedCommand(request.tenantSurrogate(), request.commandId());
        String expectedWorkflowId =
                CaseProcessWorkflowProtocol.caseWorkflowId(
                        request.tenantSurrogate(), request.caseId());
        if (!command.getCaseId().equals(request.caseId())
                || command.getCaseCommandSequence() != request.caseCommandSequence()
                || !command.getRequestHash().equals(request.requestHash())
                || command.getRoomType() != request.roomType()
                || command.getRoomEpoch() != request.roomEpoch()
                || !request.workflowId().equals(expectedWorkflowId)) {
            throw permanentFailure(
                    "CASE_COMMAND_ROUTING_SCOPE_MISMATCH",
                    "case command routing scope mismatch");
        }
        return command;
    }

    private CaseRoomEpochEntity activeRoutingEpoch(RecordCaseCommandRouted request) {
        CaseRoomEpochEntity epoch = routingEpoch(request);
        requireActiveEpoch(epoch);
        return epoch;
    }

    private CaseRoomEpochEntity routingEpoch(RecordCaseCommandRouted request) {
        CaseRoomEpochEntity epoch =
                roomEpochRepository
                        .findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                                request.caseId(), request.roomType(), request.roomEpoch())
                        .orElseThrow(
                                () ->
                                        permanentFailure(
                                                "CASE_COMMAND_ROUTING_EPOCH_MISSING",
                                                "case room epoch is unavailable"));
        String expectedWorkflowId =
                CaseProcessWorkflowProtocol.caseWorkflowId(
                        request.tenantSurrogate(), request.caseId());
        if (!request.tenantSurrogate().equals(epoch.getTenantSurrogate())
                || (epoch.getTemporalWorkflowId() != null
                        && !epoch.getTemporalWorkflowId().equals(expectedWorkflowId))) {
            throw permanentFailure(
                    "CASE_COMMAND_ROUTING_EPOCH_MISMATCH",
                    "case command routing epoch mismatch");
        }
        CaseProcessProjectionEntity projection =
                projectionRepository
                        .findByIdForUpdate(request.caseId())
                        .orElseThrow(
                                () ->
                                        permanentFailure(
                                                "CASE_COMMAND_ROUTING_PROJECTION_MISSING",
                                                "case process projection is unavailable"));
        if (epoch.getWriterMode() != WriterMode.LEGACY
                && epoch.getLifecycleStatus() != EpochLifecycleStatus.TERMINAL
                && !RoomEpochReadiness.isTemporalReady(epoch, projection)) {
            throw permanentFailure(
                    "CASE_COMMAND_ROUTING_EPOCH_NOT_READY",
                    "room epoch provisioning is not ready for command routing");
        }
        return epoch;
    }

    private static void requireActiveEpoch(CaseRoomEpochEntity epoch) {
        if (epoch.getLifecycleStatus() != EpochLifecycleStatus.ACTIVE) {
            throw permanentFailure(
                    "CASE_COMMAND_ROUTING_EPOCH_MISMATCH",
                    "case command routing epoch mismatch");
        }
    }

    private static RecordCaseCommandRoutedResult routingTombstone(CommandStatus status) {
        return switch (status) {
            case APPLIED -> routingResult(ALREADY_APPLIED);
            case SHADOW_COMPLETED -> routingResult(ALREADY_SHADOW_COMPLETED);
            case REJECTED -> routingResult(ALREADY_REJECTED);
            case FAILED -> routingResult(ALREADY_FAILED);
            case EXPIRED -> routingResult(ALREADY_EXPIRED);
            case PENDING_ORCHESTRATION, ORCHESTRATION_ACCEPTED -> null;
        };
    }

    private static OffsetDateTime routingTime(RecordCaseCommandRouted request) {
        return OffsetDateTime.ofInstant(request.routedAt(), ZoneOffset.UTC);
    }

    private CaseCommandEntity lockedCommand(String tenantSurrogate, String commandId) {
        return commandRepository
                .findByTenantSurrogateAndCommandIdForUpdate(tenantSurrogate, commandId)
                .orElseThrow(
                        () ->
                                permanentFailure(
                                        "CASE_COMMAND_LEDGER_MISSING",
                                        "case command is unavailable"));
    }

    private static ExpireCaseCommandResult expirationResult(
            CaseCommandLifecycleActivities.CommandLifecycleOutcome outcome) {
        return new ExpireCaseCommandResult("expire-case-command-result.v1", outcome);
    }

    private static RecordCaseCommandRoutedResult routingResult(
            CaseCommandLifecycleActivities.CommandLifecycleOutcome outcome) {
        return new RecordCaseCommandRoutedResult(
                "record-case-command-routed-result.v1", outcome);
    }

    private static ApplicationFailure permanentFailure(
            String type, String message) {
        return ApplicationFailure.newNonRetryableFailure(message, type);
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
