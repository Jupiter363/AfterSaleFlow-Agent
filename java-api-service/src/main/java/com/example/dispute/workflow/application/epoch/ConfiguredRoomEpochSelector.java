package com.example.dispute.workflow.application.epoch;

import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource;
import com.example.dispute.workflow.application.epoch.RoomEpochSelection.TargetActivationBinding;
import com.example.dispute.workflow.config.IntakeEpochSelectionProperties;
import com.example.dispute.workflow.config.IntakeEpochSelector;
import com.example.dispute.workflow.config.IntakeEpochSelector.ShadowAuthorization;
import com.example.dispute.workflow.config.OrchestrationCutoverProperties;
import com.example.dispute.workflow.config.TemporalWorkerProperties;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.targete2e.temporal.TargetRoomEpochSelectionAuthority;
import com.example.dispute.workflow.targete2e.temporal.TargetRoomEpochSelectionAuthority.Grant;
import com.example.dispute.workflow.targete2e.temporal.TargetRoomEpochSelectionAuthority.Request;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public final class ConfiguredRoomEpochSelector implements RoomEpochSelector {

    public static final String SELECTION_SCHEMA_VERSION = RoomEpochSelection.V1;
    public static final String INTAKE_SELECTION_SCHEMA_VERSION = RoomEpochSelection.V2;
    public static final String PROCESS_CONTRACT_VERSION = "case-process-contract.v1";
    public static final String GRAPH_VERSION = "1.0.0";
    public static final String CHECKPOINT_SCHEMA_VERSION = "checkpoint.v1";
    public static final String STREAM_PROTOCOL = "agent-stream.v2";
    public static final String LEGACY_BUILD_ID = "legacy-java.v1";
    public static final String LEGACY_WORKFLOW_TYPE = "LegacyJavaRoomState";
    public static final String INTAKE_GRAPH_VERSION = "2.0.0";
    public static final String INTAKE_CHECKPOINT_SCHEMA_VERSION = "intake-checkpoint.v2";
    public static final String INTAKE_ROOM_WORKFLOW_TYPE = "IntakeRoomWorkflow";
    public static final String INTAKE_ROOM_WORKFLOW_BUILD_ID = "intake-room.synthetic.v1";

    private final OrchestrationCutoverProperties cutoverProperties;
    private final TemporalWorkerProperties workerProperties;
    private final IntakeEpochSelector intakeEpochSelector;
    private final TargetRoomEpochSelectionAuthority targetSelectionAuthority;

    public ConfiguredRoomEpochSelector(
            OrchestrationCutoverProperties cutoverProperties,
            TemporalWorkerProperties workerProperties) {
        this(
                cutoverProperties,
                workerProperties,
                new IntakeEpochSelectionProperties(WriterMode.LEGACY, 0, null, false),
                TargetRoomEpochSelectionAuthority.disabled());
    }

    @Autowired
    public ConfiguredRoomEpochSelector(
            OrchestrationCutoverProperties cutoverProperties,
            TemporalWorkerProperties workerProperties,
            IntakeEpochSelectionProperties intakeSelectionProperties,
            ObjectProvider<TargetRoomEpochSelectionAuthority> targetSelectionAuthorityProvider) {
        this(
                cutoverProperties,
                workerProperties,
                intakeSelectionProperties,
                resolveTargetAuthority(targetSelectionAuthorityProvider));
    }

    public ConfiguredRoomEpochSelector(
            OrchestrationCutoverProperties cutoverProperties,
            TemporalWorkerProperties workerProperties,
            IntakeEpochSelectionProperties intakeSelectionProperties) {
        this(
                cutoverProperties,
                workerProperties,
                intakeSelectionProperties,
                TargetRoomEpochSelectionAuthority.disabled());
    }

    public ConfiguredRoomEpochSelector(
            OrchestrationCutoverProperties cutoverProperties,
            TemporalWorkerProperties workerProperties,
            IntakeEpochSelectionProperties intakeSelectionProperties,
            TargetRoomEpochSelectionAuthority targetSelectionAuthority) {
        this.cutoverProperties = cutoverProperties;
        this.workerProperties = workerProperties;
        this.intakeEpochSelector = new IntakeEpochSelector(intakeSelectionProperties);
        this.targetSelectionAuthority = Objects.requireNonNull(targetSelectionAuthority);
    }

    @Override
    public RoomEpochSelection selectForNewEpoch(RoomType roomType) {
        Objects.requireNonNull(roomType, "roomType must not be null");
        rejectTemporalSelection();
        if (roomType != RoomType.INTAKE) {
            return terminalLegacySelection(roomType);
        }
        return terminalLegacySelection(roomType);
    }

    @Override
    public RoomEpochSelection selectForNewEpoch(
            RoomType roomType, RoomEpochSelectionContext context) {
        Objects.requireNonNull(roomType, "roomType must not be null");
        Objects.requireNonNull(context, "context must not be null");
        if (cutoverProperties.newEpochMode() == WriterMode.TEMPORAL) {
            return targetTemporalSelection(roomType, context);
        }
        if (roomType != RoomType.INTAKE) {
            return terminalLegacySelection(roomType);
        }
        rejectTemporalSelection();
        if (cutoverProperties.newEpochMode() != WriterMode.SHADOW) {
            return terminalLegacySelection(roomType);
        }

        WriterMode writerMode = intakeEpochSelector.select(
                roomType,
                context.tenantSurrogate(),
                context.caseId(),
                shadowAuthorization(context.trafficSource()));
        if (writerMode != WriterMode.SHADOW) {
            return terminalLegacySelection(roomType);
        }
        requireAllocationEnabled(writerMode);
        return new RoomEpochSelection(
                writerMode,
                INTAKE_SELECTION_SCHEMA_VERSION,
                PROCESS_CONTRACT_VERSION,
                CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                workerProperties.legacyBuildId(),
                INTAKE_ROOM_WORKFLOW_TYPE,
                INTAKE_ROOM_WORKFLOW_BUILD_ID,
                graphKey(roomType),
                INTAKE_GRAPH_VERSION,
                INTAKE_CHECKPOINT_SCHEMA_VERSION,
                STREAM_PROTOCOL);
    }

    private void rejectTemporalSelection() {
        if (cutoverProperties.newEpochMode() != WriterMode.TEMPORAL) {
            return;
        }
        requireAllocationEnabled(WriterMode.TEMPORAL);
        throw new IllegalStateException(
                "TEMPORAL room epoch selection requires scoped selection context");
    }

    private RoomEpochSelection targetTemporalSelection(
            RoomType roomType, RoomEpochSelectionContext context) {
        requireAllocationEnabled(WriterMode.TEMPORAL);

        Request request =
                new Request(
                        TargetRoomEpochSelectionAuthority.PROFILE,
                        TargetRoomEpochSelectionAuthority.EXECUTION_LANE,
                        context.tenantSurrogate(),
                        context.caseId(),
                        roomType,
                        context.trafficSource());
        Grant grant = targetSelectionAuthority.authorize(request).orElse(null);
        if (grant == null) {
            throw new IllegalStateException(
                    "TEMPORAL room epoch selection requires exact target activation authority");
        }
        if (!grant.exactlyBinds(request)) {
            throw new IllegalStateException(
                    "target room epoch authorization does not match the requested scope");
        }
        RoomEpochSelection selection =
                new RoomEpochSelection(
                        WriterMode.TEMPORAL,
                        grant.selectionSchemaVersion(),
                        grant.processContractVersion(),
                        grant.caseWorkflowType(),
                        grant.caseWorkflowBuildId(),
                        grant.roomWorkflowType(),
                        grant.roomWorkflowBuildId(),
                        grant.graphKey(),
                        grant.graphVersion(),
                        grant.checkpointSchemaVersion(),
                        grant.streamProtocol(),
                        new TargetActivationBinding(
                                grant.activationId(),
                                grant.activationManifestHash(),
                                request.executionLane(),
                                grant.isolatedDomainDbBindingHash()));
        requireTargetSelectionPins(roomType, selection);
        return selection;
    }

    private static void requireTargetSelectionPins(
            RoomType roomType, RoomEpochSelection selection) {
        if (!RoomEpochSelection.V2.equals(selection.selectionSchemaVersion())
                || !PROCESS_CONTRACT_VERSION.equals(selection.processContractVersion())
                || !CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE.equals(
                        selection.caseWorkflowType())
                || !TargetTypedRoomProtocol.workflowType(roomType)
                        .equals(selection.roomWorkflowType())
                || !TargetTypedRoomProtocol.GRAPH_KEY.equals(selection.graphKey())
                || !TargetTypedRoomProtocol.GRAPH_VERSION.equals(selection.graphVersion())
                || !TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION.equals(
                        selection.checkpointSchemaVersion())
                || !STREAM_PROTOCOL.equals(selection.streamProtocol())) {
            throw new IllegalStateException("target room epoch authorization has invalid protocol pins");
        }
    }

    private static TargetRoomEpochSelectionAuthority resolveTargetAuthority(
            ObjectProvider<TargetRoomEpochSelectionAuthority> provider) {
        List<TargetRoomEpochSelectionAuthority> authorities = provider.stream().toList();
        if (authorities.isEmpty()) {
            return TargetRoomEpochSelectionAuthority.disabled();
        }
        if (authorities.size() != 1) {
            throw new IllegalStateException(
                    "target room epoch selection requires at most one authority");
        }
        return authorities.getFirst();
    }

    private void requireAllocationEnabled(WriterMode writerMode) {
        if (writerMode != WriterMode.LEGACY
                && !cutoverProperties.nonLegacyEpochAllocationEnabled()) {
            throw new IllegalStateException(
                    "non-LEGACY room epoch allocation is disabled");
        }
        if (writerMode == WriterMode.TEMPORAL
                && !cutoverProperties.temporalWriterEnabled()) {
            throw new IllegalStateException("TEMPORAL room writer activation is disabled");
        }
    }

    private static ShadowAuthorization shadowAuthorization(TrafficSource source) {
        return source == TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC
                ? ShadowAuthorization.AUTHENTICATED_SIGNED_SYNTHETIC
                : ShadowAuthorization.AUTHENTICATED_SIGNED_REAL_CASE;
    }

    public static RoomEpochSelection terminalLegacySelection(RoomType roomType) {
        return new RoomEpochSelection(
                WriterMode.LEGACY,
                SELECTION_SCHEMA_VERSION,
                PROCESS_CONTRACT_VERSION,
                LEGACY_WORKFLOW_TYPE,
                LEGACY_BUILD_ID,
                graphKey(roomType),
                GRAPH_VERSION,
                CHECKPOINT_SCHEMA_VERSION,
                STREAM_PROTOCOL);
    }

    private static String graphKey(RoomType roomType) {
        return switch (roomType) {
            case INTAKE -> "intake.v2";
            case EVIDENCE -> "evidence.v2";
            case HEARING -> "hearing.v2";
            case REVIEW -> "review.v1";
        };
    }
}
