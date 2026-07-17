package com.example.dispute.workflow.application.projection;

import static com.example.dispute.workflow.application.projection.ProcessProjectionReconciliationResult.Outcome.CONSISTENT;
import static com.example.dispute.workflow.application.projection.ProcessProjectionReconciliationResult.Outcome.DRIFT_DETECTED;
import static com.example.dispute.workflow.application.projection.ProcessProjectionReconciliationResult.Outcome.NOT_OWNED;
import static com.example.dispute.workflow.application.projection.ProcessProjectionReconciliationResult.Outcome.REPAIRED;
import static com.example.dispute.workflow.application.projection.ProcessProjectionReconciliationResult.Outcome.REPAIR_REJECTED;
import static com.example.dispute.workflow.application.projection.ProcessProjectionReconciliationResult.Outcome.SOURCE_INCOMPLETE;
import static com.example.dispute.workflow.application.projection.ProcessProjectionReconciliationResult.Outcome.SOURCE_UNAVAILABLE;
import static com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode.LEGACY;
import static com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode.SHADOW;
import static com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode.TEMPORAL;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus.ACTIVE;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ReconciliationScope.PROJECTION;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ReconciliationSeverity.CRITICAL;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ReconciliationSeverity.ERROR;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ReconciliationSeverity.WARNING;

import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.AuthoritativeProcessObservation;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.AuthoritativeProcessState;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.Incomplete;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReadResult;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReconciliationTarget;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.Unavailable;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.Verified;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.ProcessReconciliationIssueEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ReconciliationScope;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ReconciliationSeverity;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.ProcessReconciliationIssueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessProjectionReconciliationService {

    private final CaseProcessProjectionRepository projectionRepository;
    private final CaseRoomEpochRepository roomEpochRepository;
    private final ProcessReconciliationIssueRepository issueRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProcessProjectionReconciliationService(
            CaseProcessProjectionRepository projectionRepository,
            CaseRoomEpochRepository roomEpochRepository,
            ProcessReconciliationIssueRepository issueRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.projectionRepository = projectionRepository;
        this.roomEpochRepository = roomEpochRepository;
        this.issueRepository = issueRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ProcessProjectionReconciliationResult reconcile(
            ReconciliationTarget target, ReadResult authoritativeRead) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(authoritativeRead, "authoritativeRead must not be null");

        CaseRoomEpochEntity epoch =
                roomEpochRepository
                        .findByTemporalWorkflowIdForUpdate(target.temporalWorkflowId())
                        .orElse(null);
        CaseProcessProjectionEntity projection =
                projectionRepository.findByIdForUpdate(target.caseId()).orElse(null);
        if (epoch == null
                || !epoch.getTenantSurrogate().equals(target.tenantSurrogate())
                || !epoch.getCaseId().equals(target.caseId())) {
            return result(REPAIR_REJECTED, "LOCAL_EPOCH_SCOPE_MISMATCH", null, projection, -1);
        }
        if (epoch.getWriterMode() == LEGACY) {
            return result(NOT_OWNED, "LEGACY_WRITER_NOT_RECONCILED", null, projection, -1);
        }
        if (epoch.getLifecycleStatus() != ACTIVE) {
            String issueKey =
                    recordIssue(
                            target,
                            issue(
                                    "INACTIVE_EPOCH_RECONCILIATION_REJECTED",
                                    scope(epoch.getWriterMode()),
                                    CRITICAL,
                                    epoch,
                                    projection,
                                    -1,
                                    null,
                                    null,
                                    null,
                                    "ROOM_EPOCH_NOT_ACTIVE",
                                    null),
                            false);
            return result(
                    REPAIR_REJECTED,
                    "ROOM_EPOCH_NOT_ACTIVE",
                    issueKey,
                    projection,
                    -1);
        }
        if (projection != null
                && (!projection.getTenantSurrogate().equals(target.tenantSurrogate())
                        || projection.getWriterMode() != epoch.getWriterMode())) {
            String issueKey =
                    recordIssue(
                            target,
                            issue(
                                    "PROJECTION_WRITER_SCOPE_MISMATCH",
                                    scope(epoch.getWriterMode()),
                                    CRITICAL,
                                    epoch,
                                    projection,
                                    -1,
                                    null,
                                    null,
                                    null,
                                    "LOCAL_WRITER_SCOPE_MISMATCH",
                                    null),
                            false);
            return result(
                    REPAIR_REJECTED,
                    "LOCAL_WRITER_SCOPE_MISMATCH",
                    issueKey,
                    projection,
                    -1);
        }

        if (authoritativeRead instanceof Unavailable unavailable) {
            return reconcileUnavailable(target, epoch, projection, unavailable);
        }
        if (authoritativeRead instanceof Incomplete incomplete) {
            return reconcileIncomplete(target, epoch, projection, incomplete);
        }
        return reconcileVerified(target, epoch, projection, (Verified) authoritativeRead);
    }

    private ProcessProjectionReconciliationResult reconcileUnavailable(
            ReconciliationTarget target,
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            Unavailable unavailable) {
        String issueKey =
                recordIssue(
                        target,
                        issue(
                                "AUTHORITATIVE_STATE_UNAVAILABLE",
                                scope(epoch.getWriterMode()),
                                WARNING,
                                epoch,
                                projection,
                                -1,
                                null,
                                null,
                                null,
                                unavailable.reasonCode(),
                                null),
                        false);
        return result(
                SOURCE_UNAVAILABLE,
                unavailable.reasonCode(),
                issueKey,
                projection,
                -1);
    }

    private ProcessProjectionReconciliationResult reconcileIncomplete(
            ReconciliationTarget target,
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            Incomplete incomplete) {
        AuthoritativeProcessObservation observation = incomplete.observation();
        if (!observation.tenantSurrogate().equals(target.tenantSurrogate())
                || !observation.caseId().equals(target.caseId())
                || !observation.temporalWorkflowId().equals(target.temporalWorkflowId())) {
            String issueKey =
                    recordIssue(
                            target,
                            issue(
                                    "AUTHORITATIVE_SCOPE_MISMATCH",
                                    scope(epoch.getWriterMode()),
                                    CRITICAL,
                                    epoch,
                                    projection,
                                    observation.processRevision(),
                                    null,
                                    null,
                                    observation.temporalRunId(),
                                    "AUTHORITATIVE_SCOPE_MISMATCH",
                                    null),
                            false);
            return result(
                    REPAIR_REJECTED,
                    "AUTHORITATIVE_SCOPE_MISMATCH",
                    issueKey,
                    projection,
                    observation.processRevision());
        }
        if (incompleteObservationMatches(projection, observation)) {
            return result(
                    SOURCE_INCOMPLETE,
                    incomplete.reasonCode(),
                    null,
                    projection,
                    observation.processRevision());
        }

        String issueType =
                epoch.getWriterMode() == SHADOW
                        ? "SHADOW_PROJECTION_DRIFT"
                        : "INCOMPLETE_AUTHORITY_PROJECTION_DRIFT";
        String issueKey =
                recordIssue(
                        target,
                        issue(
                                issueType,
                                scope(epoch.getWriterMode()),
                                epoch.getWriterMode() == SHADOW ? WARNING : ERROR,
                                epoch,
                                projection,
                                observation.processRevision(),
                                null,
                                null,
                                observation.temporalRunId(),
                                incomplete.reasonCode(),
                                null),
                        false);
        return result(
                DRIFT_DETECTED,
                incomplete.reasonCode(),
                issueKey,
                projection,
                observation.processRevision());
    }

    private ProcessProjectionReconciliationResult reconcileVerified(
            ReconciliationTarget target,
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            Verified verified) {
        AuthoritativeProcessState state = verified.state();
        if (!state.tenantSurrogate().equals(target.tenantSurrogate())
                || !state.caseId().equals(target.caseId())
                || !state.temporalWorkflowId().equals(target.temporalWorkflowId())) {
            return rejectedVerified(
                    target,
                    epoch,
                    projection,
                    state,
                    verified.verificationRef(),
                    "AUTHORITATIVE_SCOPE_MISMATCH",
                    "AUTHORITATIVE_SCOPE_MISMATCH");
        }
        if (state.roomType() != epoch.getRoomType()
                || state.roomEpoch() != epoch.getRoomEpoch()
                || state.fencingToken() != epoch.getFencingToken()
                || !state.temporalBuildId().equals(epoch.getTemporalBuildId())) {
            return rejectedVerified(
                    target,
                    epoch,
                    projection,
                    state,
                    verified.verificationRef(),
                    "AUTHORITATIVE_FENCE_MISMATCH",
                    "AUTHORITATIVE_FENCE_MISMATCH");
        }
        if (projection != null
                && (projection.getRoomEpoch() != state.roomEpoch()
                        || projection.getFencingToken() != state.fencingToken()
                        || !Objects.equals(
                                projection.getTemporalWorkflowId(),
                                state.temporalWorkflowId())
                        || !Objects.equals(
                                projection.getTemporalBuildId(), state.temporalBuildId()))) {
            return rejectedVerified(
                    target,
                    epoch,
                    projection,
                    state,
                    verified.verificationRef(),
                    "LOCAL_PROJECTION_FENCE_MISMATCH",
                    "LOCAL_PROJECTION_FENCE_MISMATCH");
        }

        boolean projectionMatches = projectionMatches(projection, state, epoch.getWriterMode());
        boolean epochMatches = epochMatches(epoch, state);
        if (projectionMatches && epochMatches) {
            return result(
                    CONSISTENT,
                    "PROJECTION_CONSISTENT",
                    null,
                    projection,
                    state.processRevision());
        }

        String issueType = projection == null ? "PROCESS_PROJECTION_MISSING" : "PROCESS_PROJECTION_DRIFT";
        if (epoch.getWriterMode() == SHADOW) {
            String issueKey =
                    recordIssue(
                            target,
                            issue(
                                    "SHADOW_PROJECTION_DRIFT",
                                    ReconciliationScope.SHADOW,
                                    WARNING,
                                    epoch,
                                    projection,
                                    state.processRevision(),
                                    state.projectionRef(),
                                    state.projectionSha256(),
                                    state.temporalRunId(),
                                    "SHADOW_DETECT_ONLY",
                                    verified.verificationRef()),
                            false);
            return result(
                    DRIFT_DETECTED,
                    "SHADOW_DETECT_ONLY",
                    issueKey,
                    projection,
                    state.processRevision());
        }
        if (epoch.getWriterMode() != TEMPORAL) {
            return result(
                    NOT_OWNED,
                    "NON_TEMPORAL_WRITER_NOT_REPAIRED",
                    null,
                    projection,
                    state.processRevision());
        }
        if (state.processRevision() < epoch.getProcessRevision()
                || state.roomRevision() < epoch.getRoomRevision()
                || (projection != null
                        && (state.processRevision() < projection.getProcessRevision()
                                || state.lastCommandSequence()
                                        < projection.getLastCommandSequence()
                                || state.lastCaseEventSequence()
                                        < projection.getLastCaseEventSequence()))) {
            return rejectedVerified(
                    target,
                    epoch,
                    projection,
                    state,
                    verified.verificationRef(),
                    "AUTHORITATIVE_STATE_STALE",
                    "AUTHORITATIVE_STATE_STALE");
        }
        if ((!epochMatches && state.processRevision() <= epoch.getProcessRevision())
                || (projection != null
                        && !projectionMatches
                        && state.processRevision() <= projection.getProcessRevision())) {
            return rejectedVerified(
                    target,
                    epoch,
                    projection,
                    state,
                    verified.verificationRef(),
                    "SAME_REVISION_STATE_CONFLICT",
                    "SAME_REVISION_STATE_CONFLICT");
        }

        OffsetDateTime repairedAt = now();
        if (!epochMatches) {
            int epochUpdated =
                    roomEpochRepository.advanceFencedEpoch(
                            target.tenantSurrogate(),
                            target.caseId(),
                            epoch.getRoomType().name(),
                            epoch.getRoomEpoch(),
                            epoch.getFencingToken(),
                            epoch.getProcessRevision(),
                            state.processRevision(),
                            epoch.getRoomRevision(),
                            state.roomRevision(),
                            target.temporalWorkflowId(),
                            epoch.getTemporalRunId(),
                            state.temporalRunId(),
                            epoch.getTemporalBuildId(),
                            repairedAt);
            requireSingleRepair(epochUpdated, "room epoch");
        }
        if (projection == null) {
            int inserted = insertProjection(target, state, repairedAt);
            requireSingleRepair(inserted, "missing process projection");
        } else if (!projectionMatches) {
            int projectionUpdated =
                    projectionRepository.advanceFencedProjection(
                            target.tenantSurrogate(),
                            target.caseId(),
                            projection.getRoomEpoch(),
                            projection.getFencingToken(),
                            projection.getProcessRevision(),
                            state.processRevision(),
                            state.macroPhase(),
                            state.currentRoom(),
                            state.roomPhase(),
                            state.lastCommandSequence(),
                            state.lastCaseEventSequence(),
                            offset(state.projectedDeadlineAt()),
                            target.temporalWorkflowId(),
                            projection.getTemporalRunId(),
                            state.temporalRunId(),
                            projection.getTemporalBuildId(),
                            state.projectionRef(),
                            state.projectionSha256(),
                            repairedAt);
            requireSingleRepair(projectionUpdated, "process projection");
        }

        String issueKey =
                recordIssue(
                        target,
                        issue(
                                issueType,
                                PROJECTION,
                                ERROR,
                                epoch,
                                projection,
                                state.processRevision(),
                                state.projectionRef(),
                                state.projectionSha256(),
                                state.temporalRunId(),
                                "FENCED_REPAIR_APPLIED",
                                verified.verificationRef()),
                        true);
        return result(
                REPAIRED,
                "FENCED_REPAIR_APPLIED",
                issueKey,
                projection,
                state.processRevision());
    }

    private ProcessProjectionReconciliationResult rejectedVerified(
            ReconciliationTarget target,
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            AuthoritativeProcessState state,
            String verificationRef,
            String issueType,
            String reasonCode) {
        String issueKey =
                recordIssue(
                        target,
                        issue(
                                issueType,
                                scope(epoch.getWriterMode()),
                                CRITICAL,
                                epoch,
                                projection,
                                state.processRevision(),
                                state.projectionRef(),
                                state.projectionSha256(),
                                state.temporalRunId(),
                                reasonCode,
                                verificationRef),
                        false);
        return result(
                REPAIR_REJECTED,
                reasonCode,
                issueKey,
                projection,
                state.processRevision());
    }

    private int insertProjection(
            ReconciliationTarget target,
            AuthoritativeProcessState state,
            OffsetDateTime repairedAt) {
        return projectionRepository.insertFencedProjection(
                target.tenantSurrogate(),
                target.caseId(),
                state.roomType().name(),
                state.roomEpoch(),
                state.processRevision(),
                state.roomRevision(),
                state.fencingToken(),
                state.macroPhase(),
                state.currentRoom(),
                state.roomPhase(),
                state.lastCommandSequence(),
                state.lastCaseEventSequence(),
                offset(state.projectedDeadlineAt()),
                target.temporalWorkflowId(),
                state.temporalRunId(),
                state.temporalBuildId(),
                state.projectionRef(),
                state.projectionSha256(),
                repairedAt);
    }

    private String recordIssue(
            ReconciliationTarget target, IssueDescription issue, boolean resolved) {
        String digest =
                sha256(
                        String.join(
                                "|",
                                target.tenantSurrogate(),
                                target.caseId(),
                                target.temporalWorkflowId(),
                                issue.issueType(),
                                Long.toString(issue.roomEpoch()),
                                Long.toString(issue.expectedRevision()),
                                Long.toString(issue.actualRevision()),
                                Long.toString(issue.fencingToken()),
                                nullToEmpty(issue.expectedRunId()),
                                nullToEmpty(issue.actualRunId()),
                                issue.reasonCode()));
        String issueKey = "reconciliation:" + digest;
        issueRepository.lockTenantIssueKey(target.tenantSurrogate(), issueKey);
        ProcessReconciliationIssueEntity entity =
                issueRepository
                        .findByTenantSurrogateAndIssueKey(
                                target.tenantSurrogate(), issueKey)
                        .orElseGet(
                                () ->
                                        ProcessReconciliationIssueEntity.detected(
                                                "PRI_" + digest.substring(0, 60),
                                                issueKey,
                                                target.tenantSurrogate(),
                                                target.caseId(),
                                                issue.issueType(),
                                                issue.scope(),
                                                issue.severity(),
                                                issue.roomType(),
                                                issue.roomEpoch(),
                                                Math.max(0, issue.expectedRevision()),
                                                issue.fencingToken(),
                                                issue.expectedRef(),
                                                issue.expectedSha256(),
                                                issue.actualRef(),
                                                issue.actualSha256(),
                                                detailsJson(target, issue),
                                                now()));
        if (resolved) {
            entity.markResolved(now());
        } else {
            entity.reopenIfResolved(now());
        }
        issueRepository.saveAndFlush(entity);
        return issueKey;
    }

    private String detailsJson(ReconciliationTarget target, IssueDescription issue) {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("schemaVersion", "process-reconciliation-issue.v1");
        details.put("reasonCode", issue.reasonCode());
        details.put("caseId", target.caseId());
        details.put("temporalWorkflowId", target.temporalWorkflowId());
        details.put("writerMode", issue.writerMode().name());
        details.put("actualProcessRevision", issue.actualRevision());
        details.put("authoritativeProcessRevision", issue.expectedRevision());
        if (issue.actualRunId() != null) {
            details.put("actualTemporalRunId", issue.actualRunId());
        }
        if (issue.expectedRunId() != null) {
            details.put("authoritativeTemporalRunId", issue.expectedRunId());
        }
        if (issue.verificationRef() != null) {
            details.put("verificationRef", issue.verificationRef());
        }
        return details.toString();
    }

    private static IssueDescription issue(
            String issueType,
            ReconciliationScope scope,
            ReconciliationSeverity severity,
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            long expectedRevision,
            String expectedRef,
            String expectedSha256,
            String expectedRunId,
            String reasonCode,
            String verificationRef) {
        return new IssueDescription(
                issueType,
                scope,
                severity,
                epoch.getWriterMode(),
                epoch.getRoomType(),
                epoch.getRoomEpoch(),
                expectedRevision,
                projection == null ? -1 : projection.getProcessRevision(),
                epoch.getFencingToken(),
                expectedRef,
                expectedSha256,
                projection == null ? null : projection.getProjectionRef(),
                projection == null ? null : projection.getProjectionSha256(),
                expectedRunId,
                projection == null ? epoch.getTemporalRunId() : projection.getTemporalRunId(),
                reasonCode,
                verificationRef);
    }

    private static boolean incompleteObservationMatches(
            CaseProcessProjectionEntity projection,
            AuthoritativeProcessObservation observation) {
        return projection != null
                && projection.getTenantSurrogate().equals(observation.tenantSurrogate())
                && projection.getMacroPhase().equals(observation.macroPhase())
                && Objects.equals(
                        projection.getCurrentRoom(),
                        observation.activeRoomType() == null
                                ? null
                                : observation.activeRoomType().name())
                && projection.getRoomEpoch() == observation.activeRoomEpoch()
                && projection.getProcessRevision() == observation.processRevision()
                && projection.getLastCommandSequence() == observation.lastCommandSequence()
                && projection.getLastCaseEventSequence()
                        == observation.lastCaseEventSequence()
                && Objects.equals(
                        projection.getTemporalWorkflowId(), observation.temporalWorkflowId())
                && Objects.equals(
                        projection.getTemporalRunId(), observation.temporalRunId());
    }

    private static boolean projectionMatches(
            CaseProcessProjectionEntity projection,
            AuthoritativeProcessState state,
            WriterMode writerMode) {
        return projection != null
                && projection.getWriterMode() == writerMode
                && projection.getTenantSurrogate().equals(state.tenantSurrogate())
                && projection.getMacroPhase().equals(state.macroPhase())
                && Objects.equals(projection.getCurrentRoom(), state.currentRoom())
                && projection.getRoomPhase().equals(state.roomPhase())
                && projection.getProcessRevision() == state.processRevision()
                && projection.getRoomEpoch() == state.roomEpoch()
                && projection.getFencingToken() == state.fencingToken()
                && projection.getLastCommandSequence() == state.lastCommandSequence()
                && projection.getLastCaseEventSequence() == state.lastCaseEventSequence()
                && sameInstant(projection.getProjectedDeadlineAt(), state.projectedDeadlineAt())
                && Objects.equals(
                        projection.getTemporalWorkflowId(), state.temporalWorkflowId())
                && Objects.equals(projection.getTemporalRunId(), state.temporalRunId())
                && Objects.equals(projection.getTemporalBuildId(), state.temporalBuildId())
                && Objects.equals(projection.getProjectionRef(), state.projectionRef())
                && Objects.equals(projection.getProjectionSha256(), state.projectionSha256());
    }

    private static boolean epochMatches(
            CaseRoomEpochEntity epoch, AuthoritativeProcessState state) {
        return epoch.getWriterMode() != LEGACY
                && epoch.getLifecycleStatus() == ACTIVE
                && epoch.getTenantSurrogate().equals(state.tenantSurrogate())
                && epoch.getCaseId().equals(state.caseId())
                && epoch.getRoomType() == state.roomType()
                && epoch.getRoomEpoch() == state.roomEpoch()
                && epoch.getProcessRevision() == state.processRevision()
                && epoch.getRoomRevision() == state.roomRevision()
                && epoch.getFencingToken() == state.fencingToken()
                && Objects.equals(epoch.getTemporalWorkflowId(), state.temporalWorkflowId())
                && Objects.equals(epoch.getTemporalRunId(), state.temporalRunId())
                && Objects.equals(epoch.getTemporalBuildId(), state.temporalBuildId());
    }

    private static ProcessProjectionReconciliationResult result(
            ProcessProjectionReconciliationResult.Outcome outcome,
            String reasonCode,
            String issueKey,
            CaseProcessProjectionEntity projection,
            long authoritativeRevision) {
        return new ProcessProjectionReconciliationResult(
                outcome,
                reasonCode,
                issueKey,
                projection == null ? -1 : projection.getProcessRevision(),
                authoritativeRevision);
    }

    private static ReconciliationScope scope(WriterMode writerMode) {
        return writerMode == SHADOW ? ReconciliationScope.SHADOW : PROJECTION;
    }

    private static boolean sameInstant(OffsetDateTime actual, Instant expected) {
        return actual == null ? expected == null : expected != null && actual.toInstant().equals(expected);
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void requireSingleRepair(int updatedRows, String target) {
        if (updatedRows != 1) {
            throw new ProjectionReconciliationRaceException(
                    "fenced repair lost the " + target + " CAS");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private record IssueDescription(
            String issueType,
            ReconciliationScope scope,
            ReconciliationSeverity severity,
            WriterMode writerMode,
            RoomType roomType,
            long roomEpoch,
            long expectedRevision,
            long actualRevision,
            long fencingToken,
            String expectedRef,
            String expectedSha256,
            String actualRef,
            String actualSha256,
            String expectedRunId,
            String actualRunId,
            String reasonCode,
            String verificationRef) {}
}
