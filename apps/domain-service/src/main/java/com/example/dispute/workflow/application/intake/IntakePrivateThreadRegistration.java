package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.runtime.temporal.TargetTypedRoomProtocol;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Exact graph-private-thread-registration.v1 wire object. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IntakePrivateThreadRegistration(
        String schemaVersion,
        String registrationId,
        String tenantSurrogate,
        String caseId,
        String roomType,
        long roomEpoch,
        String threadId,
        ActorScope actorScope,
        String actorScopeHash,
        String agentSessionId,
        String graphKey,
        String graphVersion,
        String checkpointSchemaVersion,
        String stateSchemaVersion,
        String promptVersion,
        String modelProfileId,
        String outputSchemaVersion,
        String policyVersion,
        String guardrailVersion,
        String toolPolicyVersion,
        WriterMode writerMode,
        Instant issuedAt,
        String registrationHash) {

    private static final String LEGACY_GRAPH_KEY = "intake.v2";
    private static final String TARGET_GRAPH_KEY = TargetTypedRoomProtocol.GRAPH_KEY;
    private static final String TARGET_CHECKPOINT_SCHEMA =
            TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION;

    public IntakePrivateThreadRegistration {
        if (!"graph-private-thread-registration.v1".equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "schemaVersion must be graph-private-thread-registration.v1");
        }
        registrationId = IntakeContractSupport.identifier(registrationId, "registrationId");
        tenantSurrogate = IntakeContractSupport.identifier(tenantSurrogate, "tenantSurrogate");
        caseId = IntakeContractSupport.identifier(caseId, "caseId");
        if (!"INTAKE".equals(roomType)) {
            throw new IllegalArgumentException("roomType must be INTAKE");
        }
        IntakeContractSupport.nonNegative(roomEpoch, "roomEpoch");
        threadId = IntakeContractSupport.threadId(threadId);
        actorScope = Objects.requireNonNull(actorScope, "actorScope must not be null");
        actorScopeHash = IntakeContractSupport.sha256(actorScopeHash, "actorScopeHash");
        if (!actorScopeHash.equals(IntakeContractHashes.actorScopeHash(actorScope))) {
            throw new IllegalArgumentException("actorScopeHash does not match actorScope");
        }
        agentSessionId = IntakeContractSupport.identifier(agentSessionId, "agentSessionId");
        graphKey = IntakeContractSupport.identifier(graphKey, "graphKey");
        if (!LEGACY_GRAPH_KEY.equals(graphKey) && !TARGET_GRAPH_KEY.equals(graphKey)) {
            throw new IllegalArgumentException("graphKey is not an allowed Intake graph");
        }
        graphVersion = IntakeContractSupport.identifier(graphVersion, "graphVersion");
        checkpointSchemaVersion = IntakeContractSupport.identifier(
                checkpointSchemaVersion, "checkpointSchemaVersion");
        if (!"intake-graph-state.v2".equals(stateSchemaVersion)) {
            throw new IllegalArgumentException("stateSchemaVersion must be intake-graph-state.v2");
        }
        promptVersion = IntakeContractSupport.identifier(promptVersion, "promptVersion");
        modelProfileId = IntakeContractSupport.identifier(modelProfileId, "modelProfileId");
        outputSchemaVersion = IntakeContractSupport.identifier(
                outputSchemaVersion, "outputSchemaVersion");
        policyVersion = IntakeContractSupport.identifier(policyVersion, "policyVersion");
        guardrailVersion = IntakeContractSupport.identifier(guardrailVersion, "guardrailVersion");
        toolPolicyVersion = IntakeContractSupport.identifier(
                toolPolicyVersion, "toolPolicyVersion");
        if (writerMode != WriterMode.SHADOW && writerMode != WriterMode.TEMPORAL) {
            throw new IllegalArgumentException("registration writerMode must be SHADOW or TEMPORAL");
        }
        if (TARGET_GRAPH_KEY.equals(graphKey)
                && (writerMode != WriterMode.TEMPORAL
                        || !TargetTypedRoomProtocol.supportsGraphVersion(graphVersion)
                        || !TARGET_CHECKPOINT_SCHEMA.equals(checkpointSchemaVersion)
                        || !"production-runtime-room-proposal-source.v2".equals(
                                outputSchemaVersion))) {
            throw new IllegalArgumentException(
                    "target graph requires the exact TEMPORAL production-runtime version pins");
        }
        if (LEGACY_GRAPH_KEY.equals(graphKey)
                && !"intake-turn-proposal.v2".equals(outputSchemaVersion)) {
            throw new IllegalArgumentException(
                    "legacy Intake outputSchemaVersion must be intake-turn-proposal.v2");
        }
        if (LEGACY_GRAPH_KEY.equals(graphKey)
                && !actorScope.capabilities().contains("graph.command.execute")) {
            throw new IllegalArgumentException(
                    "legacy private Intake capabilities must include graph.command.execute");
        }
        if (TARGET_GRAPH_KEY.equals(graphKey)
                && !actorScope.capabilities().equals(
                        List.of("case:" + caseId + ":command:INTAKE_MESSAGE"))) {
            throw new IllegalArgumentException(
                    "target private Intake capabilities must exactly bind the case command");
        }
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        registrationHash = IntakeContractSupport.sha256(registrationHash, "registrationHash");
    }

    public void requireCanonicalHash() {
        if (!registrationHash.equals(IntakeContractHashes.registrationHash(this))) {
            throw new IntakeGraphBindingConflictException(
                    "registration hash does not match canonical registration bytes");
        }
    }

    public PrivateTuple privateTuple() {
        return new PrivateTuple(
                tenantSurrogate,
                caseId,
                roomEpoch,
                actorScopeHash,
                agentSessionId,
                graphKey,
                graphVersion,
                checkpointSchemaVersion);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ActorScope(
            String actorId, ActorRole actorRole, Audience audience, List<String> capabilities) {

        public ActorScope {
            actorId = IntakeContractSupport.identifier(actorId, "actorId");
            if (actorRole != ActorRole.USER && actorRole != ActorRole.MERCHANT) {
                throw new IllegalArgumentException("private Intake actorRole must be USER or MERCHANT");
            }
            Audience expected = actorRole == ActorRole.USER ? Audience.USER : Audience.MERCHANT;
            if (audience != expected) {
                throw new IllegalArgumentException("private Intake audience must match actorRole");
            }
            capabilities = IntakeContractSupport.identifiers(
                    capabilities, 1, 16, "capabilities");
        }
    }

    public record PrivateTuple(
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            String actorScopeHash,
            String agentSessionId,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion) {}
}
