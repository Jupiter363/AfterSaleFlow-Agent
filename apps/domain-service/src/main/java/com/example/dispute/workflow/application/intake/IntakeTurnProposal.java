package com.example.dispute.workflow.application.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Typed intake-turn-proposal.v2 after immutable-object and JSON Schema verification. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IntakeTurnProposal(
        String schemaVersion,
        String commandId,
        String logicalRunId,
        String attemptId,
        String caseId,
        long roomEpoch,
        String threadId,
        String actorScopeHash,
        String agentSessionId,
        long cognitiveRevision,
        String sourceSnapshotHash,
        String sourceEventHash,
        String roomUtterance,
        ConversationAction conversationAction,
        JsonNode dossierPatch,
        JsonNode matrixPatch,
        Readiness readiness,
        List<String> missingFields,
        Recommendation recommendation,
        KnowledgeAnswerMode knowledgeAnswerMode,
        BigDecimal confidence,
        ProfileVersions profileVersions,
        String proposalHash) {

    public IntakeTurnProposal {
        if (!"intake-turn-proposal.v2".equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must be intake-turn-proposal.v2");
        }
        commandId = IntakeContractSupport.identifier(commandId, "commandId");
        logicalRunId = IntakeContractSupport.identifier(logicalRunId, "logicalRunId");
        attemptId = IntakeContractSupport.identifier(attemptId, "attemptId");
        caseId = IntakeContractSupport.identifier(caseId, "caseId");
        IntakeContractSupport.nonNegative(roomEpoch, "roomEpoch");
        threadId = IntakeContractSupport.threadId(threadId);
        actorScopeHash = IntakeContractSupport.sha256(actorScopeHash, "actorScopeHash");
        agentSessionId = IntakeContractSupport.identifier(agentSessionId, "agentSessionId");
        IntakeContractSupport.positive(cognitiveRevision, "cognitiveRevision");
        sourceSnapshotHash =
                IntakeContractSupport.sha256(sourceSnapshotHash, "sourceSnapshotHash");
        if (sourceEventHash != null) {
            sourceEventHash = IntakeContractSupport.sha256(sourceEventHash, "sourceEventHash");
        }
        roomUtterance = IntakeContractSupport.boundedText(roomUtterance, 20_000, "roomUtterance");
        conversationAction = Objects.requireNonNull(conversationAction, "conversationAction");
        dossierPatch = IntakeContractSupport.immutableJson(dossierPatch, "dossierPatch");
        if (matrixPatch != null && !matrixPatch.isNull()) {
            matrixPatch = IntakeContractSupport.immutableJson(matrixPatch, "matrixPatch");
        } else {
            matrixPatch = null;
        }
        readiness = Objects.requireNonNull(readiness, "readiness");
        missingFields = IntakeContractSupport.identifiers(missingFields, 0, 30, "missingFields");
        if (readiness == Readiness.READY_TO_CONFIRM && !missingFields.isEmpty()) {
            throw new IllegalArgumentException(
                    "READY_TO_CONFIRM proposals cannot contain missing fields");
        }
        recommendation = Objects.requireNonNull(recommendation, "recommendation");
        knowledgeAnswerMode = Objects.requireNonNull(knowledgeAnswerMode, "knowledgeAnswerMode");
        confidence = Objects.requireNonNull(confidence, "confidence");
        if (confidence.compareTo(BigDecimal.ZERO) < 0
                || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("confidence must be between zero and one");
        }
        profileVersions = Objects.requireNonNull(profileVersions, "profileVersions");
        proposalHash = IntakeContractSupport.sha256(proposalHash, "proposalHash");
    }

    public IntakeTurnProposal(
            String schemaVersion,
            String commandId,
            String logicalRunId,
            String attemptId,
            String caseId,
            long roomEpoch,
            String threadId,
            String actorScopeHash,
            String agentSessionId,
            long cognitiveRevision,
            String sourceSnapshotHash,
            String sourceEventHash,
            String roomUtterance,
            JsonNode dossierPatch,
            JsonNode matrixPatch,
            Readiness readiness,
            List<String> missingFields,
            Recommendation recommendation,
            KnowledgeAnswerMode knowledgeAnswerMode,
            BigDecimal confidence,
            ProfileVersions profileVersions,
            String proposalHash) {
        this(
                schemaVersion,
                commandId,
                logicalRunId,
                attemptId,
                caseId,
                roomEpoch,
                threadId,
                actorScopeHash,
                agentSessionId,
                cognitiveRevision,
                sourceSnapshotHash,
                sourceEventHash,
                roomUtterance,
                ConversationAction.ASK_SUBSTANTIVE,
                dossierPatch,
                matrixPatch,
                readiness,
                missingFields,
                recommendation,
                knowledgeAnswerMode,
                confidence,
                profileVersions,
                proposalHash);
    }

    @Override
    public JsonNode dossierPatch() {
        return dossierPatch.deepCopy();
    }

    @Override
    public JsonNode matrixPatch() {
        return matrixPatch == null ? null : matrixPatch.deepCopy();
    }

    public enum Readiness {
        INCOMPLETE,
        READY_TO_CONFIRM,
        NEEDS_REVIEW
    }

    public enum ConversationAction {
        ASK_SUBSTANTIVE,
        INVITE_OPTIONAL_REMARK,
        ACK_REMARK,
        ACK_NO_REMARK
    }

    public enum Recommendation {
        ACCEPTED,
        NEED_MORE_INFO,
        NOT_ADMISSIBLE
    }

    public enum KnowledgeAnswerMode {
        NONE,
        STUB
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProfileVersions(
            String graphVersion,
            String checkpointSchemaVersion,
            String promptVersion,
            String modelProfileId,
            String outputSchemaVersion,
            String policyVersion,
            String guardrailVersion,
            String toolPolicyVersion) {

        public ProfileVersions {
            graphVersion = IntakeContractSupport.identifier(graphVersion, "graphVersion");
            checkpointSchemaVersion = IntakeContractSupport.identifier(
                    checkpointSchemaVersion, "checkpointSchemaVersion");
            promptVersion = IntakeContractSupport.identifier(promptVersion, "promptVersion");
            modelProfileId = IntakeContractSupport.identifier(modelProfileId, "modelProfileId");
            if (!"intake-turn-proposal.v2".equals(outputSchemaVersion)) {
                throw new IllegalArgumentException(
                        "outputSchemaVersion must be intake-turn-proposal.v2");
            }
            policyVersion = IntakeContractSupport.identifier(policyVersion, "policyVersion");
            guardrailVersion =
                    IntakeContractSupport.identifier(guardrailVersion, "guardrailVersion");
            toolPolicyVersion =
                    IntakeContractSupport.identifier(toolPolicyVersion, "toolPolicyVersion");
        }
    }
}
