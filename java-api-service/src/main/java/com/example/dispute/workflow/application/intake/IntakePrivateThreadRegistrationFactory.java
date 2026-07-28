package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import java.time.Instant;
import java.util.Objects;

/** Issues the Java-owned opaque thread and exact, hash-bound registration object. */
public final class IntakePrivateThreadRegistrationFactory {

    private static final String ZERO_HASH = "0".repeat(64);

    private final IntakeThreadIdGenerator threadIdGenerator;

    public IntakePrivateThreadRegistrationFactory() {
        this(IntakeThreadIdGenerator.uuidV7());
    }

    public IntakePrivateThreadRegistrationFactory(IntakeThreadIdGenerator threadIdGenerator) {
        this.threadIdGenerator = Objects.requireNonNull(threadIdGenerator, "threadIdGenerator");
    }

    public IntakeGraphThreadBinding issue(IssueRequest request) {
        Objects.requireNonNull(request, "request");
        String threadId = IntakeContractSupport.threadId(threadIdGenerator.nextThreadId());
        var actorScope = Objects.requireNonNull(request.actorScope(), "actorScope");
        String actorScopeHash = IntakeContractHashes.actorScopeHash(actorScope);
        VersionPins pins = Objects.requireNonNull(request.versionPins(), "versionPins");
        IntakePrivateThreadRegistration unsigned =
                new IntakePrivateThreadRegistration(
                        "graph-private-thread-registration.v1",
                        request.registrationId(),
                        request.tenantSurrogate(),
                        request.caseId(),
                        "INTAKE",
                        request.roomEpoch(),
                        threadId,
                        actorScope,
                        actorScopeHash,
                        request.agentSessionId(),
                        pins.graphKey(),
                        pins.graphVersion(),
                        pins.checkpointSchemaVersion(),
                        pins.stateSchemaVersion(),
                        pins.promptVersion(),
                        pins.modelProfileId(),
                        pins.outputSchemaVersion(),
                        pins.policyVersion(),
                        pins.guardrailVersion(),
                        pins.toolPolicyVersion(),
                        request.writerMode(),
                        request.issuedAt(),
                        ZERO_HASH);
        String registrationHash = IntakeContractHashes.registrationHash(unsigned);
        IntakePrivateThreadRegistration registration =
                new IntakePrivateThreadRegistration(
                        unsigned.schemaVersion(),
                        unsigned.registrationId(),
                        unsigned.tenantSurrogate(),
                        unsigned.caseId(),
                        unsigned.roomType(),
                        unsigned.roomEpoch(),
                        unsigned.threadId(),
                        unsigned.actorScope(),
                        unsigned.actorScopeHash(),
                        unsigned.agentSessionId(),
                        unsigned.graphKey(),
                        unsigned.graphVersion(),
                        unsigned.checkpointSchemaVersion(),
                        unsigned.stateSchemaVersion(),
                        unsigned.promptVersion(),
                        unsigned.modelProfileId(),
                        unsigned.outputSchemaVersion(),
                        unsigned.policyVersion(),
                        unsigned.guardrailVersion(),
                        unsigned.toolPolicyVersion(),
                        unsigned.writerMode(),
                        unsigned.issuedAt(),
                        registrationHash);
        return new IntakeGraphThreadBinding(registration, request.fencingToken());
    }

    public record IssueRequest(
            String registrationId,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long fencingToken,
            IntakePrivateThreadRegistration.ActorScope actorScope,
            String agentSessionId,
            VersionPins versionPins,
            WriterMode writerMode,
            Instant issuedAt) {

        public IssueRequest {
            registrationId = IntakeContractSupport.identifier(registrationId, "registrationId");
            tenantSurrogate = IntakeContractSupport.identifier(tenantSurrogate, "tenantSurrogate");
            caseId = IntakeContractSupport.identifier(caseId, "caseId");
            IntakeContractSupport.nonNegative(roomEpoch, "roomEpoch");
            IntakeContractSupport.positive(fencingToken, "fencingToken");
            Objects.requireNonNull(actorScope, "actorScope must not be null");
            agentSessionId = IntakeContractSupport.identifier(agentSessionId, "agentSessionId");
            Objects.requireNonNull(versionPins, "versionPins must not be null");
            if (writerMode != WriterMode.SHADOW && writerMode != WriterMode.TEMPORAL) {
                throw new IllegalArgumentException("writerMode must be SHADOW or TEMPORAL");
            }
            Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        }
    }

    public record VersionPins(
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String stateSchemaVersion,
            String promptVersion,
            String modelProfileId,
            String outputSchemaVersion,
            String policyVersion,
            String guardrailVersion,
            String toolPolicyVersion) {

        public VersionPins {
            graphKey = IntakeContractSupport.identifier(graphKey, "graphKey");
            graphVersion = IntakeContractSupport.identifier(graphVersion, "graphVersion");
            checkpointSchemaVersion = IntakeContractSupport.identifier(
                    checkpointSchemaVersion, "checkpointSchemaVersion");
            stateSchemaVersion = IntakeContractSupport.identifier(
                    stateSchemaVersion, "stateSchemaVersion");
            promptVersion = IntakeContractSupport.identifier(promptVersion, "promptVersion");
            modelProfileId = IntakeContractSupport.identifier(modelProfileId, "modelProfileId");
            outputSchemaVersion = IntakeContractSupport.identifier(
                    outputSchemaVersion, "outputSchemaVersion");
            policyVersion = IntakeContractSupport.identifier(policyVersion, "policyVersion");
            guardrailVersion = IntakeContractSupport.identifier(
                    guardrailVersion, "guardrailVersion");
            toolPolicyVersion = IntakeContractSupport.identifier(
                    toolPolicyVersion, "toolPolicyVersion");
        }

        /** Preserves the original legacy Intake registration contract. */
        public VersionPins(
                String graphVersion,
                String checkpointSchemaVersion,
                String promptVersion,
                String modelProfileId,
                String policyVersion,
                String guardrailVersion,
                String toolPolicyVersion) {
            this(
                    "intake.v2",
                    graphVersion,
                    checkpointSchemaVersion,
                    "intake-graph-state.v2",
                    promptVersion,
                    modelProfileId,
                    "intake-turn-proposal.v2",
                    policyVersion,
                    guardrailVersion,
                    toolPolicyVersion);
        }
    }
}
