package com.example.dispute.workflow.application.epoch;

import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.workflow.application.command.TenantAuthority;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.ActivateRoomEpoch;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.RoomEpochAllocation;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TerminalRoomEpoch;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TerminateRoomEpoch;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TransitionRoomEpoch;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.WriterActivationStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochBootstrapEnqueuer;
import com.example.dispute.workflow.targete2e.temporal.TargetRoomEpochBindingWriter;
import com.example.dispute.workflow.targete2e.temporal.TargetRoomEpochBindingWriter.BindingContext;
import com.example.dispute.workflow.targete2e.temporal.TargetRoomEpochBindingWriter.SuccessorContext;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionalRoomEpochAllocator implements RoomEpochAllocator {

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomEpochRepository epochRepository;
    private final CaseProcessProjectionRepository projectionRepository;
    private final RoomEpochSelector selector;
    private final TenantAuthority tenantAuthority;
    private final ObjectProvider<RoomEpochBootstrapEnqueuer> bootstrapEnqueuer;
    private final ObjectProvider<RoomEpochBootstrapDeliveryTrigger> bootstrapDeliveryTrigger;
    private final ObjectProvider<TargetRoomEpochBindingWriter> targetBindingWriter;

    @Autowired
    public TransactionalRoomEpochAllocator(
            FulfillmentCaseRepository caseRepository,
            CaseRoomEpochRepository epochRepository,
            CaseProcessProjectionRepository projectionRepository,
            RoomEpochSelector selector,
            TenantAuthority tenantAuthority,
            ObjectProvider<RoomEpochBootstrapEnqueuer> bootstrapEnqueuer,
            ObjectProvider<RoomEpochBootstrapDeliveryTrigger> bootstrapDeliveryTrigger,
            ObjectProvider<TargetRoomEpochBindingWriter> targetBindingWriter) {
        this.caseRepository = caseRepository;
        this.epochRepository = epochRepository;
        this.projectionRepository = projectionRepository;
        this.selector = selector;
        this.tenantAuthority = tenantAuthority;
        this.bootstrapEnqueuer = bootstrapEnqueuer;
        this.bootstrapDeliveryTrigger = bootstrapDeliveryTrigger;
        this.targetBindingWriter = targetBindingWriter;
    }

    public TransactionalRoomEpochAllocator(
            FulfillmentCaseRepository caseRepository,
            CaseRoomEpochRepository epochRepository,
            CaseProcessProjectionRepository projectionRepository,
            RoomEpochSelector selector,
            TenantAuthority tenantAuthority,
            ObjectProvider<RoomEpochBootstrapEnqueuer> bootstrapEnqueuer,
            ObjectProvider<TargetRoomEpochBindingWriter> targetBindingWriter) {
        this(
                caseRepository,
                epochRepository,
                projectionRepository,
                selector,
                tenantAuthority,
                bootstrapEnqueuer,
                null,
                targetBindingWriter);
    }

    public TransactionalRoomEpochAllocator(
            FulfillmentCaseRepository caseRepository,
            CaseRoomEpochRepository epochRepository,
            CaseProcessProjectionRepository projectionRepository,
            RoomEpochSelector selector,
            TenantAuthority tenantAuthority,
            ObjectProvider<RoomEpochBootstrapEnqueuer> bootstrapEnqueuer) {
        this(
                caseRepository,
                epochRepository,
                projectionRepository,
                selector,
                tenantAuthority,
                bootstrapEnqueuer,
                null,
                null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RoomEpochAllocation activate(ActivateRoomEpoch command) {
        lockCase(command.caseId());
        String tenant = trustedTenant();
        var writerSlot = epochRepository.findWriterSlotByCaseIdForUpdate(command.caseId());
        if (writerSlot.isPresent()) {
            CaseRoomEpochEntity existing = writerSlot.orElseThrow();
            requireTenant(existing, tenant);
            if (existing.getRoomType() != command.roomType()) {
                throw failure(
                        "ACTIVE_ROOM_EPOCH_CONFLICT",
                        "another room epoch is already ACTIVE for the case");
            }
            requireCompatibleRoom(existing, command.roomId());
            requireProjectionMatches(existing, lockedProjection(command.caseId()));
            return allocation(existing);
        }
        if (projectionRepository.findByIdForUpdate(command.caseId()).isPresent()) {
            throw failure(
                    "ROOM_EPOCH_STATE_INCONSISTENT",
                    "a process projection exists without an ACTIVE room epoch");
        }

        long roomEpoch = nextRoomEpoch(command.caseId(), command.roomType());
        long fencingToken = nextFencingToken(command.caseId());
        RoomEpochSelection selection = selector.selectForNewEpoch(
                command.roomType(), RoomEpochSelectionContext.realCase(tenant, command.caseId()));
        requireProvisionable(selection);
        CaseRoomEpochEntity epoch =
                newEpoch(
                        tenant,
                        command.caseId(),
                        command.roomId(),
                        command.roomType(),
                        roomEpoch,
                        0,
                        fencingToken,
                        selection,
                        command.occurredAt());
        epochRepository.saveAndFlush(epoch);
        persistTargetBinding(epoch, selection);
        CaseProcessProjectionEntity projection =
                projection(
                        epoch,
                        command.macroPhase(),
                        command.roomType().name(),
                        command.roomPhase(),
                        command.projectedDeadlineAt(),
                        command.occurredAt());
        projectionRepository.saveAndFlush(projection);
        enqueueProvisioning(epoch, projection, command.occurredAt());
        return allocation(epoch);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RoomEpochAllocation transition(TransitionRoomEpoch command) {
        lockCase(command.caseId());
        String tenant = trustedTenant();
        CaseRoomEpochEntity active =
                epochRepository
                        .findWriterSlotByCaseIdForUpdate(command.caseId())
                        .orElseThrow(
                                () ->
                                        failure(
                                            "ACTIVE_ROOM_EPOCH_MISSING",
                                            "the case has no ACTIVE room epoch to transition"));
        requireTenant(active, tenant);
        CaseProcessProjectionEntity projection = lockedProjection(command.caseId());
        requireProjectionMatches(active, projection);
        requireReadyForStateChange(active, projection);

        if (active.getRoomType() == command.nextRoomType()) {
            requireCompatibleRoom(active, command.nextRoomId());
            if (command.hasProjectionAuthority()) {
                requireProjectionAuthority(projection, command);
            }
            if (command.hasSequenceAuthority()) {
                requireSequenceAuthority(projection, command);
            }
            return allocation(active);
        }
        if (active.getRoomType() != command.expectedRoomType()) {
            throw failure(
                    "STALE_ROOM_EPOCH",
                    "the ACTIVE room epoch does not match the expected source room");
        }

        long nextRoomEpoch = nextRoomEpoch(command.caseId(), command.nextRoomType());
        long nextFencingToken = nextFencingToken(command.caseId());
        long nextProcessRevision = Math.addExact(active.getProcessRevision(), 1);
        long closedRoomRevision = Math.addExact(active.getRoomRevision(), 1);
        RoomEpochSelection selection =
                active.getWriterMode() == WriterMode.TEMPORAL
                        ? selectTargetSuccessor(active, command.nextRoomType())
                        : selector.selectForNewEpoch(
                                command.nextRoomType(),
                                RoomEpochSelectionContext.realCase(tenant, command.caseId()));
        requireProvisionable(selection);

        active.terminalize(
                active.getFencingToken(),
                nextProcessRevision,
                closedRoomRevision,
                command.occurredAt());
        epochRepository.saveAndFlush(active);

        CaseRoomEpochEntity next =
                newEpoch(
                        tenant,
                        command.caseId(),
                        command.nextRoomId(),
                        command.nextRoomType(),
                        nextRoomEpoch,
                        nextProcessRevision,
                        nextFencingToken,
                        selection,
                        command.occurredAt());
        epochRepository.saveAndFlush(next);
        persistTargetBinding(next, selection);
        if (command.hasSequenceAuthority()) {
            projection.advanceSequenceHighWater(
                    active.getRoomEpoch(),
                    active.getFencingToken(),
                    command.lastCommandSequence(),
                    command.lastCaseEventSequence());
        }
        if (command.hasProjectionAuthority()) {
            projection.switchTo(
                    active.getRoomEpoch(),
                    active.getFencingToken(),
                    command.macroPhase(),
                    command.nextRoomType().name(),
                    command.roomPhase(),
                    next.getWriterMode(),
                    nextProcessRevision,
                    nextRoomEpoch,
                    nextFencingToken,
                    command.projectedDeadlineAt(),
                    next.getTemporalWorkflowId(),
                    next.getTemporalRunId(),
                    next.getTemporalBuildId(),
                    command.occurredAt(),
                    command.projectionRef(),
                    command.projectionSha256());
        } else {
            projection.switchTo(
                    active.getRoomEpoch(),
                    active.getFencingToken(),
                    command.macroPhase(),
                    command.nextRoomType().name(),
                    command.roomPhase(),
                    next.getWriterMode(),
                    nextProcessRevision,
                    nextRoomEpoch,
                    nextFencingToken,
                    command.projectedDeadlineAt(),
                    next.getTemporalWorkflowId(),
                    next.getTemporalRunId(),
                    next.getTemporalBuildId(),
                    command.occurredAt());
        }
        projectionRepository.saveAndFlush(projection);
        enqueueProvisioning(next, projection, command.occurredAt());
        return allocation(next);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RoomEpochAllocation terminate(TerminateRoomEpoch command) {
        lockCase(command.caseId());
        String tenant = trustedTenant();
        var writerSlot = epochRepository.findWriterSlotByCaseIdForUpdate(command.caseId());
        if (writerSlot.isEmpty()) {
            CaseRoomEpochEntity existing = latestTerminal(command.caseId(), command.expectedRoomType());
            requireTenant(existing, tenant);
            requireProjectionMatches(existing, lockedProjection(command.caseId()));
            return allocation(existing);
        }
        CaseRoomEpochEntity epoch = writerSlot.orElseThrow();
        requireTenant(epoch, tenant);
        if (epoch.getRoomType() != command.expectedRoomType()) {
            throw failure(
                    "STALE_ROOM_EPOCH",
                    "the ACTIVE room epoch does not match the expected terminal room");
        }
        CaseProcessProjectionEntity projection = lockedProjection(command.caseId());
        requireProjectionMatches(epoch, projection);
        requireReadyForStateChange(epoch, projection);
        long nextProcessRevision = Math.addExact(epoch.getProcessRevision(), 1);
        epoch.terminalize(
                epoch.getFencingToken(),
                nextProcessRevision,
                Math.addExact(epoch.getRoomRevision(), 1),
                command.occurredAt());
        projection.terminate(
                epoch.getRoomEpoch(),
                epoch.getFencingToken(),
                command.macroPhase(),
                command.roomPhase(),
                nextProcessRevision,
                command.occurredAt());
        epochRepository.saveAndFlush(epoch);
        projectionRepository.saveAndFlush(projection);
        return allocation(epoch);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RoomEpochAllocation recordTerminal(TerminalRoomEpoch command) {
        lockCase(command.caseId());
        String tenant = trustedTenant();
        if (epochRepository.findWriterSlotByCaseIdForUpdate(command.caseId()).isPresent()) {
            throw failure(
                    "ACTIVE_ROOM_EPOCH_CONFLICT",
                    "a terminal import cannot replace an ACTIVE room epoch");
        }
        var existing =
                epochRepository
                        .findTopByCaseIdAndRoomTypeAndLifecycleStatusOrderByRoomEpochDesc(
                                command.caseId(),
                                command.roomType(),
                                EpochLifecycleStatus.TERMINAL);
        if (existing.isPresent()) {
            CaseRoomEpochEntity terminal = existing.orElseThrow();
            requireTenant(terminal, tenant);
            requireCompatibleRoom(terminal, command.roomId());
            requireProjectionMatches(terminal, lockedProjection(command.caseId()));
            return allocation(terminal);
        }
        if (projectionRepository.findByIdForUpdate(command.caseId()).isPresent()) {
            throw failure(
                    "ROOM_EPOCH_STATE_INCONSISTENT",
                    "a process projection exists without terminal epoch history");
        }

        long roomEpoch = nextRoomEpoch(command.caseId(), command.roomType());
        long fencingToken = nextFencingToken(command.caseId());
        RoomEpochSelection selection =
                ConfiguredRoomEpochSelector.terminalLegacySelection(command.roomType());
        CaseRoomEpochEntity terminal =
                CaseRoomEpochEntity.terminal(
                        epochId(),
                        tenant,
                        command.caseId(),
                        command.roomId(),
                        command.roomType(),
                        roomEpoch,
                        WriterMode.LEGACY,
                        0,
                        0,
                        fencingToken,
                        null,
                        null,
                        selection.buildId(),
                        selection.graphKey(),
                        selection.graphVersion(),
                        selection.checkpointSchemaVersion(),
                        selection.streamProtocol(),
                        selection.selectionSchemaVersion(),
                        selection.processContractVersion(),
                        selection.caseWorkflowType(),
                        command.occurredAt(),
                        command.occurredAt());
        epochRepository.saveAndFlush(terminal);
        projectionRepository.saveAndFlush(
                projection(
                        terminal,
                        command.macroPhase(),
                        null,
                        command.roomPhase(),
                        null,
                        command.occurredAt()));
        return allocation(terminal);
    }

    private CaseRoomEpochEntity newEpoch(
            String tenant,
            String caseId,
            String roomId,
            RoomType roomType,
            long roomEpoch,
            long processRevision,
            long fencingToken,
            RoomEpochSelection selection,
            OffsetDateTime occurredAt) {
        String workflowId =
                selection.writerMode() == WriterMode.LEGACY
                        ? null
                        : CaseProcessWorkflowProtocol.caseWorkflowId(tenant, caseId);
        if (selection.writerMode() == WriterMode.TEMPORAL) {
            return CaseRoomEpochEntity.preparing(
                    epochId(),
                    tenant,
                    caseId,
                    roomId,
                    roomType,
                    roomEpoch,
                    processRevision,
                    0,
                    fencingToken,
                    workflowId,
                    selection.caseWorkflowBuildId(),
                    selection.graphKey(),
                    selection.graphVersion(),
                    selection.checkpointSchemaVersion(),
                    selection.streamProtocol(),
                    selection.selectionSchemaVersion(),
                    selection.processContractVersion(),
                    selection.caseWorkflowType(),
                    selection.roomWorkflowType(),
                    selection.roomWorkflowBuildId(),
                    occurredAt);
        }
        return CaseRoomEpochEntity.active(
                epochId(),
                tenant,
                caseId,
                roomId,
                roomType,
                roomEpoch,
                selection.writerMode(),
                processRevision,
                0,
                fencingToken,
                workflowId,
                null,
                selection.caseWorkflowBuildId(),
                selection.graphKey(),
                selection.graphVersion(),
                selection.checkpointSchemaVersion(),
                selection.streamProtocol(),
                selection.selectionSchemaVersion(),
                selection.processContractVersion(),
                selection.caseWorkflowType(),
                selection.roomWorkflowType(),
                selection.roomWorkflowBuildId(),
                occurredAt);
    }

    private static CaseProcessProjectionEntity projection(
            CaseRoomEpochEntity epoch,
            String macroPhase,
            String currentRoom,
            String roomPhase,
            OffsetDateTime deadline,
            OffsetDateTime projectedAt) {
        return CaseProcessProjectionEntity.initialize(
                epoch.getCaseId(),
                epoch.getTenantSurrogate(),
                macroPhase,
                currentRoom,
                roomPhase,
                epoch.getWriterMode(),
                epoch.getProcessRevision(),
                epoch.getRoomEpoch(),
                epoch.getFencingToken(),
                deadline,
                epoch.getTemporalWorkflowId(),
                epoch.getTemporalRunId(),
                epoch.getTemporalBuildId(),
                projectedAt);
    }

    private void lockCase(String caseId) {
        caseRepository
                .findByIdForUpdate(caseId)
                .orElseThrow(
                        () -> failure("CASE_NOT_FOUND", "case is unavailable for epoch allocation"));
    }

    private CaseProcessProjectionEntity lockedProjection(String caseId) {
        return projectionRepository
                .findByIdForUpdate(caseId)
                .orElseThrow(
                        () ->
                                failure(
                                        "PROCESS_PROJECTION_MISSING",
                                        "the ACTIVE room epoch has no process projection"));
    }

    private CaseRoomEpochEntity latestTerminal(String caseId, RoomType roomType) {
        return epochRepository
                .findTopByCaseIdAndRoomTypeAndLifecycleStatusOrderByRoomEpochDesc(
                        caseId, roomType, EpochLifecycleStatus.TERMINAL)
                .orElseThrow(
                        () ->
                                failure(
                                        "TERMINAL_ROOM_EPOCH_MISSING",
                                        "the terminal room epoch is unavailable"));
    }

    private void requireProjectionMatches(
            CaseRoomEpochEntity epoch, CaseProcessProjectionEntity projection) {
        if (!epoch.getCaseId().equals(projection.getCaseId())
                || !epoch.getTenantSurrogate().equals(projection.getTenantSurrogate())
                || epoch.getRoomEpoch() != projection.getRoomEpoch()
                || epoch.getFencingToken() != projection.getFencingToken()
                || epoch.getWriterMode() != projection.getWriterMode()
                || epoch.getProcessRevision() != projection.getProcessRevision()
                || (epoch.getLifecycleStatus() == EpochLifecycleStatus.ACTIVE
                        && !epoch.getRoomType().name().equals(projection.getCurrentRoom()))
                || !Objects.equals(
                        epoch.getTemporalWorkflowId(), projection.getTemporalWorkflowId())
                || !Objects.equals(epoch.getTemporalRunId(), projection.getTemporalRunId())
                || !epoch.getTemporalBuildId().equals(projection.getTemporalBuildId())
                || projection.getWriterActivationStatus()
                        != expectedActivationStatus(epoch)) {
            throw failure(
                    "ROOM_EPOCH_STATE_INCONSISTENT",
                    "room epoch and process projection ownership do not match");
        }
    }

    private static void requireCompatibleRoom(CaseRoomEpochEntity epoch, String roomId) {
        if (epoch.getRoomId() == null) {
            throw failure(
                    "ROOM_EPOCH_STATE_INCONSISTENT",
                    "the persisted room epoch has no room instance binding");
        }
        if (!epoch.getRoomId().equals(roomId)) {
            throw failure(
                    "ROOM_EPOCH_IDEMPOTENCY_CONFLICT",
                    "the persisted room epoch belongs to a different room instance");
        }
    }

    private static void requireProjectionAuthority(
            CaseProcessProjectionEntity projection, TransitionRoomEpoch command) {
        if (!Objects.equals(projection.getProjectionRef(), command.projectionRef())
                || !Objects.equals(
                        projection.getProjectionSha256(), command.projectionSha256())) {
            throw failure(
                    "ROOM_EPOCH_PROJECTION_AUTHORITY_CONFLICT",
                    "the active room epoch is bound to different projection authority");
        }
    }

    private static void requireSequenceAuthority(
            CaseProcessProjectionEntity projection, TransitionRoomEpoch command) {
        if (projection.getLastCommandSequence() != command.lastCommandSequence()
                || projection.getLastCaseEventSequence() != command.lastCaseEventSequence()) {
            throw failure(
                    "ROOM_EPOCH_SEQUENCE_AUTHORITY_CONFLICT",
                    "the active room epoch is bound to different sequence authority");
        }
    }

    private static void requireTenant(CaseRoomEpochEntity epoch, String tenant) {
        if (!tenant.equals(epoch.getTenantSurrogate())) {
            throw failure(
                    "ROOM_EPOCH_TENANT_MISMATCH",
                    "the persisted room epoch belongs to another tenant authority");
        }
    }

    private void requireProvisionable(RoomEpochSelection selection) {
        if (selection.writerMode() == WriterMode.TEMPORAL
                && selection.targetActivationBinding() == null) {
            throw failure(
                    "TARGET_E2E_ACTIVATION_BINDING_MISSING",
                    "TEMPORAL room epoch selection requires a target activation binding");
        }
        if (selection.writerMode() != WriterMode.LEGACY
                && bootstrapEnqueuer.getIfAvailable() == null) {
            throw failure(
                    "ROOM_EPOCH_BOOTSTRAP_UNAVAILABLE",
                    "non-LEGACY epoch activation requires durable bootstrap infrastructure");
        }
        if (selection.writerMode() != WriterMode.LEGACY) {
            bootstrapDeliveryAuthority();
        }
    }

    private void persistTargetBinding(
            CaseRoomEpochEntity epoch,
            RoomEpochSelection selection) {
        if (selection.targetActivationBinding() == null) {
            return;
        }
        if (selection.writerMode() != WriterMode.TEMPORAL) {
            throw failure(
                    "TARGET_E2E_ACTIVATION_BINDING_INVALID",
                    "only a TEMPORAL room epoch can persist a target activation binding");
        }
        targetBindingAuthority()
                .persist(
                        new BindingContext(
                                epoch.getId(),
                                epoch.getTenantSurrogate(),
                                epoch.getCaseId(),
                                epoch.getRoomType(),
                                epoch.getRoomEpoch(),
                                epoch.getFencingToken(),
                                selection));
    }

    private RoomEpochSelection selectTargetSuccessor(
            CaseRoomEpochEntity source, RoomType nextRoomType) {
        return targetBindingAuthority()
                .selectSuccessor(
                        new SuccessorContext(
                                source.getId(),
                                source.getTenantSurrogate(),
                                source.getCaseId(),
                                source.getRoomType(),
                                source.getRoomEpoch(),
                                source.getFencingToken(),
                                source.getProcessRevision(),
                                source.getTemporalWorkflowId(),
                                nextRoomType,
                                persistedSelection(source)));
    }

    private TargetRoomEpochBindingWriter targetBindingAuthority() {
        if (targetBindingWriter == null) {
            throw failure(
                    "TARGET_E2E_ACTIVATION_BINDING_UNAVAILABLE",
                    "target room epoch binding writer is unavailable");
        }
        var writers = targetBindingWriter.stream().toList();
        if (writers.size() != 1) {
            throw failure(
                    "TARGET_E2E_ACTIVATION_BINDING_UNAVAILABLE",
                    "target room epoch binding requires exactly one writer");
        }
        return writers.getFirst();
    }

    private void enqueueProvisioning(
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            OffsetDateTime availableAt) {
        if (epoch.getWriterMode() == WriterMode.LEGACY) {
            return;
        }
        RoomEpochBootstrapEnqueuer enqueuer = bootstrapEnqueuer.getIfAvailable();
        if (enqueuer == null) {
            throw failure(
                    "ROOM_EPOCH_BOOTSTRAP_UNAVAILABLE",
                    "non-LEGACY epoch activation requires durable bootstrap infrastructure");
        }
        String outboxId = enqueuer.enqueue(epoch, projection, availableAt);
        bootstrapDeliveryAuthority().deliveryRequested(outboxId);
    }

    private RoomEpochBootstrapDeliveryTrigger bootstrapDeliveryAuthority() {
        if (bootstrapDeliveryTrigger == null) {
            throw failure(
                    "ROOM_EPOCH_BOOTSTRAP_DELIVERY_UNAVAILABLE",
                    "non-LEGACY epoch activation requires exact bootstrap delivery");
        }
        var triggers = bootstrapDeliveryTrigger.stream().toList();
        if (triggers.size() != 1) {
            throw failure(
                    "ROOM_EPOCH_BOOTSTRAP_DELIVERY_UNAVAILABLE",
                    "non-LEGACY epoch activation requires exactly one bootstrap delivery trigger");
        }
        return triggers.getFirst();
    }

    private static void requireReadyForStateChange(
            CaseRoomEpochEntity epoch, CaseProcessProjectionEntity projection) {
        if (!RoomEpochReadiness.isReady(epoch, projection)) {
            throw failure(
                    "ROOM_EPOCH_PROVISIONING_INCOMPLETE",
                    "room epoch provisioning must be READY before lifecycle advancement");
        }
    }

    private static WriterActivationStatus expectedActivationStatus(
            CaseRoomEpochEntity epoch) {
        if (epoch.getLifecycleStatus() == EpochLifecycleStatus.TERMINAL) {
            return WriterActivationStatus.TERMINAL;
        }
        if (epoch.getWriterMode() == WriterMode.LEGACY) {
            return WriterActivationStatus.READY;
        }
        EpochProvisioningStatus status = epoch.getProvisioningStatus();
        return switch (status) {
            case PENDING -> WriterActivationStatus.PREPARING;
            case PROVISIONING -> WriterActivationStatus.PROVISIONING;
            case READY -> WriterActivationStatus.READY;
            case FAILED -> WriterActivationStatus.FAILED;
            case NOT_REQUIRED -> throw failure(
                    "ROOM_EPOCH_STATE_INCONSISTENT",
                    "non-LEGACY epoch cannot bypass provisioning");
        };
    }

    private long nextRoomEpoch(String caseId, RoomType roomType) {
        return epochRepository
                .findMaxRoomEpoch(caseId, roomType)
                .map(value -> Math.addExact(value, 1))
                .orElse(0L);
    }

    private long nextFencingToken(String caseId) {
        return epochRepository
                .findMaxFencingToken(caseId)
                .map(value -> Math.addExact(value, 1))
                .orElse(1L);
    }

    private String trustedTenant() {
        String tenant = tenantAuthority.tenantSurrogate();
        if (tenant == null || tenant.isBlank()) {
            throw failure("TENANT_AUTHORITY_MISSING", "tenant authority is unavailable");
        }
        return tenant;
    }

    private static RoomEpochAllocation allocation(CaseRoomEpochEntity epoch) {
        RoomEpochSelection selection = persistedSelection(epoch);
        return new RoomEpochAllocation(
                epoch.getId(),
                epoch.getTenantSurrogate(),
                epoch.getCaseId(),
                epoch.getRoomId(),
                epoch.getRoomType(),
                epoch.getRoomEpoch(),
                epoch.getProcessRevision(),
                epoch.getRoomRevision(),
                epoch.getFencingToken(),
                epoch.getWriterMode(),
                epoch.getLifecycleStatus(),
                epoch.getTemporalWorkflowId(),
                epoch.getTemporalRunId(),
                selection);
    }

    private static RoomEpochSelection persistedSelection(CaseRoomEpochEntity epoch) {
        return new RoomEpochSelection(
                        epoch.getWriterMode(),
                        epoch.getSelectionSchemaVersion(),
                        epoch.getProcessContractVersion(),
                        epoch.getWorkflowType(),
                        epoch.getTemporalBuildId(),
                        epoch.getRoomWorkflowType(),
                        epoch.getRoomWorkflowBuildId(),
                        epoch.getGraphKey(),
                        epoch.getGraphVersion(),
                        epoch.getCheckpointSchemaVersion(),
                        epoch.getStreamProtocol());
    }

    private static String epochId() {
        return "CRE_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static RoomEpochAllocationException failure(String reasonCode, String message) {
        return new RoomEpochAllocationException(reasonCode, message);
    }
}
