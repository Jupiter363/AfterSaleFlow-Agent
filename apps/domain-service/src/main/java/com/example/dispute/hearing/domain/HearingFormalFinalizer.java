package com.example.dispute.hearing.domain;

import java.util.Objects;

/**
 * Dormant Java business-truth boundary for formal Hearing facts. Implementations must remain
 * unreachable from runtime registration until the Phase 6 barriers explicitly admit them.
 */
public interface HearingFormalFinalizer {

    HearingDomainReceipt appendAction(ActionCommand command);

    HearingDomainReceipt adoptPartyAction(AdoptPartyActionCommand command);

    HearingDomainReceipt advanceStage(StageCommand command);

    HearingDomainReceipt finalizeMatrixSynthesis(MatrixSynthesisCommand command);

    HearingDomainReceipt freezeDossier(DossierCommand command);

    HearingDomainReceipt finalizeJudgeV1(DecisionCommand command);

    HearingDomainReceipt finalizeJuryReview(DecisionCommand command);

    HearingDomainReceipt finalizeJudgeV2(DecisionCommand command);

    HearingDomainReceipt commitHandoff(HandoffCommand command);

    HearingDomainReceipt commitClosure(ClosureCommand command);

    /** A deterministic non-Graph transition in the fixed fifteen-stage protocol. */
    record StageCommand(
            HearingAuthorityCommit authorityCommit,
            HearingFormalTransition transition,
            String stageOutputJson,
            String stageOutputHash,
            String actorId) {
        public StageCommand {
            Objects.requireNonNull(authorityCommit, "authorityCommit");
            Objects.requireNonNull(transition, "transition");
            actorId = HearingAuthorityExpectation.identifier(actorId, "actorId");
            hash(stageOutputHash, "stageOutputHash");
            transition.requireSource(authorityCommit.authority());
            if (!transition.advances() || !transition.actorId().equals(actorId)
                    || !HearingFormalPayload.canonicalJson(stageOutputJson)
                            .equals(transition.sourceOutputJson())
                    || !HearingFormalPayload.hashCanonical(stageOutputJson).equals(stageOutputHash)) {
                throw new IllegalArgumentException("stage command must carry an exact adjacent canonical output");
            }
            requireOperation(authorityCommit, HearingAuthorityCommit.OperationType.STAGE,
                    authorityPrefix(authorityCommit) + authorityCommit.authority().stageSequence()
                            + ':' + authorityCommit.authority().stage().name());
            HearingFormalRequestHash.require(authorityCommit, "STAGE", transition, stageOutputHash, actorId);
        }
    }

    /** Attaches a receipt to a browser action already durably written by the Java API transaction. */
    record AdoptPartyActionCommand(
            HearingAuthorityCommit authorityCommit,
            HearingFormalTransition transition,
            String actionId,
            HearingFlowActionType actionType,
            String schemaVersion,
            String participantId,
            String participantRole,
            HearingFlowSubmissionStatus submissionStatus,
            String payloadJson,
            String contentHash,
            String requestId,
            String actorId) {
        public AdoptPartyActionCommand {
            Objects.requireNonNull(authorityCommit, "authorityCommit");
            Objects.requireNonNull(transition, "transition");
            Objects.requireNonNull(actionType, "actionType");
            if (!actionType.isPartyAction()) {
                throw new IllegalArgumentException("only a party action can be adopted");
            }
            actionId = HearingAuthorityExpectation.identifier(actionId, "actionId");
            participantId = HearingAuthorityExpectation.identifier(participantId, "participantId");
            participantRole = partyRole(participantRole);
            Objects.requireNonNull(submissionStatus, "submissionStatus");
            requestId = HearingAuthorityExpectation.identifier(requestId, "requestId");
            actorId = HearingAuthorityExpectation.identifier(actorId, "actorId");
            hash(contentHash, "contentHash");
            transition.requireSource(authorityCommit.authority());
            HearingFlowStage source = authorityCommit.authority().stage();
            HearingFlowActionType expected = source == HearingFlowStage.PARTY_ANSWERS_OPEN
                    ? HearingFlowActionType.ANSWER_BUNDLE
                    : source == HearingFlowStage.PARTY_EVIDENCE_OPEN
                            ? HearingFlowActionType.EVIDENCE_BATCH : null;
            if (expected != actionType || !transition.actorId().equals(actorId)
                    || (actionType == HearingFlowActionType.ANSWER_BUNDLE
                            && submissionStatus != HearingFlowSubmissionStatus.SUBMITTED)) {
                throw new IllegalArgumentException("party adoption does not match its Hearing wait stage");
            }
            if (!actionType.acceptsSchemaVersion(schemaVersion)) {
                throw new IllegalArgumentException("party action schema is invalid");
            }
            HearingFormalPayload.requireAction(payloadJson, schemaVersion, contentHash,
                    participantId, participantRole, submissionStatus);
            requireOperation(authorityCommit, HearingAuthorityCommit.OperationType.PARTY_TERMINAL,
                    authorityPrefix(authorityCommit) + authorityCommit.authority().stageSequence()
                            + ':' + participantId + ':' + requestId);
            HearingFormalRequestHash.require(authorityCommit, "ADOPT_PARTY_ACTION", transition, actionId,
                    actionType, schemaVersion, participantId, participantRole, submissionStatus, contentHash,
                    requestId, actorId);
        }
    }

    /** A bounded Graph synthesis whose only formal effect is the next adjacent stage cursor. */
    record MatrixSynthesisCommand(
            HearingAuthorityCommit authorityCommit,
            HearingFormalTransition transition,
            MatrixKind matrixKind,
            String payloadJson,
            String contentHash,
            String agentRunId,
            String agentResultHash,
            String actorId) {
        public MatrixSynthesisCommand {
            Objects.requireNonNull(authorityCommit, "authorityCommit");
            Objects.requireNonNull(transition, "transition");
            Objects.requireNonNull(matrixKind, "matrixKind");
            hash(contentHash, "contentHash");
            agentRunId = HearingAuthorityExpectation.identifier(agentRunId, "agentRunId");
            hash(agentResultHash, "agentResultHash");
            actorId = HearingAuthorityExpectation.identifier(actorId, "actorId");
            transition.requireSource(authorityCommit.authority());
            if (authorityCommit.authority().stage() != matrixKind.sourceStage()
                    || transition.resultStage() != matrixKind.resultStage()
                    || !transition.advances() || !transition.actorId().equals(actorId)
                    || !HearingFormalPayload.canonicalJson(payloadJson)
                            .equals(transition.sourceOutputJson())) {
                throw new IllegalArgumentException("matrix synthesis does not match its fixed Hearing transition");
            }
            HearingFormalPayload.requireMatrixSynthesis(payloadJson, matrixKind, contentHash);
            requireOperation(authorityCommit, HearingAuthorityCommit.OperationType.FINALIZE,
                    authorityPrefix(authorityCommit) + authorityCommit.authority().stageSequence()
                            + ':' + matrixKind.schemaVersion() + ':' + authorityCommit.requestHash());
            HearingFormalRequestHash.require(authorityCommit, "MATRIX_SYNTHESIS", transition, matrixKind,
                    contentHash, agentRunId, agentResultHash, actorId);
        }
    }

    enum MatrixKind {
        INTAKE(HearingFlowStage.INTAKE_SYNTHESIZING, HearingFlowStage.EVIDENCE_REQUESTS_GENERATING,
                "hearing_intake_synthesis.v5", "case_fact_matrix"),
        EVIDENCE(HearingFlowStage.EVIDENCE_SYNTHESIZING, HearingFlowStage.DOSSIER_FREEZING,
                "hearing_evidence_synthesis.v1", "fact_evidence_matrix");

        private final HearingFlowStage sourceStage;
        private final HearingFlowStage resultStage;
        private final String schemaVersion;
        private final String matrixField;

        MatrixKind(HearingFlowStage sourceStage, HearingFlowStage resultStage,
                String schemaVersion, String matrixField) {
            this.sourceStage = sourceStage;
            this.resultStage = resultStage;
            this.schemaVersion = schemaVersion;
            this.matrixField = matrixField;
        }

        public HearingFlowStage sourceStage() { return sourceStage; }
        public HearingFlowStage resultStage() { return resultStage; }
        public String schemaVersion() { return schemaVersion; }
        public String matrixField() { return matrixField; }
        public String matrixSchemaVersion() {
            return this == INTAKE ? "case_fact_matrix.v2" : "fact_evidence_matrix.v3";
        }
    }

    record ActionCommand(
            HearingAuthorityCommit authorityCommit,
            HearingFormalTransition transition,
            String actionId,
            HearingFlowActionType actionType,
            String schemaVersion,
            String participantId,
            String participantRole,
            HearingFlowSubmissionStatus submissionStatus,
            String payloadJson,
            String contentHash,
            String agentRunId,
            String agentResultHash,
            String requestId,
            String actorId) {

        public ActionCommand {
            Objects.requireNonNull(authorityCommit, "authorityCommit");
            Objects.requireNonNull(transition, "transition");
            Objects.requireNonNull(actionType, "actionType");
            actionId = HearingAuthorityExpectation.identifier(actionId, "actionId");
            actorId = HearingAuthorityExpectation.identifier(actorId, "actorId");
            hash(contentHash, "contentHash");
            transition.requireSource(authorityCommit.authority());
            if (!transition.actorId().equals(actorId)) {
                throw new IllegalArgumentException("action transition actor is not exact");
            }
            HearingFlowStage expectedSource = switch (actionType) {
                case QUESTION_SET -> HearingFlowStage.INTAKE_QUESTIONS_GENERATING;
                case ANSWER_BUNDLE -> HearingFlowStage.PARTY_ANSWERS_OPEN;
                case EVIDENCE_REQUEST_SET -> HearingFlowStage.EVIDENCE_REQUESTS_GENERATING;
                case EVIDENCE_BATCH -> HearingFlowStage.PARTY_EVIDENCE_OPEN;
            };
            HearingFlowStage expectedResult = switch (actionType) {
                case QUESTION_SET -> HearingFlowStage.PARTY_ANSWERS_OPEN;
                case ANSWER_BUNDLE -> HearingFlowStage.PARTY_ANSWERS_OPEN;
                case EVIDENCE_REQUEST_SET -> HearingFlowStage.PARTY_EVIDENCE_OPEN;
                case EVIDENCE_BATCH -> HearingFlowStage.PARTY_EVIDENCE_OPEN;
            };
            if (authorityCommit.authority().stage() != expectedSource
                    || transition.resultStage() != expectedResult) {
                throw new IllegalArgumentException("action is not valid at the expected Hearing stage");
            }
            if (!actionType.acceptsSchemaVersion(schemaVersion)) {
                throw new IllegalArgumentException("schemaVersion is not valid for actionType");
            }
            if (actionType.isPartyAction()) {
                participantId = HearingAuthorityExpectation.identifier(participantId, "participantId");
                participantRole = partyRole(participantRole);
                Objects.requireNonNull(submissionStatus, "submissionStatus");
                if (agentRunId != null) {
                    throw new IllegalArgumentException("party action cannot be owned by an AgentRun");
                }
                if (agentResultHash != null) {
                    throw new IllegalArgumentException("party action cannot carry an Agent result hash");
                }
                requestId = HearingAuthorityExpectation.identifier(requestId, "requestId");
                requireOperation(
                        authorityCommit,
                        HearingAuthorityCommit.OperationType.PARTY_TERMINAL,
                        authorityPrefix(authorityCommit) + authorityCommit.authority().stageSequence()
                                + ':' + participantId + ':' + requestId);
            } else {
                if (participantId != null || participantRole != null || submissionStatus != null) {
                    throw new IllegalArgumentException("generated action cannot carry party identity");
                }
                agentRunId = HearingAuthorityExpectation.identifier(agentRunId, "agentRunId");
                hash(agentResultHash, "agentResultHash");
                if (requestId != null) {
                    throw new IllegalArgumentException("generated action does not use a party requestId");
                }
                requireOperation(
                        authorityCommit,
                        HearingAuthorityCommit.OperationType.FINALIZE,
                        authorityPrefix(authorityCommit) + authorityCommit.authority().stageSequence()
                                + ':' + actionType.name() + ':' + authorityCommit.requestHash());
            }
            HearingFormalPayload.requireAction(
                    payloadJson,
                    schemaVersion,
                    contentHash,
                    participantId,
                    participantRole,
                    submissionStatus);
            HearingFormalRequestHash.require(
                    authorityCommit,
                    "ACTION",
                    transition,
                    actionId,
                    actionType,
                    schemaVersion,
                    participantId,
                    participantRole,
                    submissionStatus,
                    contentHash,
                    agentRunId,
                    agentResultHash,
                    requestId,
                    actorId);
        }
    }

    record DossierCommand(
            HearingAuthorityCommit authorityCommit,
            HearingFormalTransition transition,
            String dossierId,
            int caseMatrixVersion,
            String caseMatrixHash,
            int evidenceMatrixVersion,
            String evidenceMatrixHash,
            String questionSetId,
            String requestSetId,
            String payloadJson,
            String contentHash,
            String actorId) {

        public DossierCommand {
            Objects.requireNonNull(authorityCommit, "authorityCommit");
            Objects.requireNonNull(transition, "transition");
            dossierId = HearingAuthorityExpectation.identifier(dossierId, "dossierId");
            questionSetId = HearingAuthorityExpectation.identifier(questionSetId, "questionSetId");
            requestSetId = HearingAuthorityExpectation.identifier(requestSetId, "requestSetId");
            actorId = HearingAuthorityExpectation.identifier(actorId, "actorId");
            hash(caseMatrixHash, "caseMatrixHash");
            hash(evidenceMatrixHash, "evidenceMatrixHash");
            hash(contentHash, "contentHash");
            if (caseMatrixVersion < 1 || evidenceMatrixVersion < 1) {
                throw new IllegalArgumentException("dossier matrix versions must be positive");
            }
            transition.requireSource(authorityCommit.authority());
            if (authorityCommit.authority().stage() != HearingFlowStage.DOSSIER_FREEZING
                    || transition.resultStage() != HearingFlowStage.JUDGE_V1_GENERATING
                    || !transition.actorId().equals(actorId)) {
                throw new IllegalArgumentException("dossier freeze must advance stage 10 to stage 11");
            }
            requireOperation(
                    authorityCommit,
                    HearingAuthorityCommit.OperationType.FINALIZE,
                    authorityPrefix(authorityCommit) + authorityCommit.authority().stageSequence()
                            + ":trial_dossier.v2:" + authorityCommit.requestHash());
            HearingFormalPayload.requireDossier(
                    payloadJson,
                    dossierId,
                    authorityCommit.authority().caseId(),
                    contentHash,
                    caseMatrixVersion,
                    caseMatrixHash,
                    evidenceMatrixVersion,
                    evidenceMatrixHash,
                    questionSetId,
                    requestSetId,
                    authorityCommit.committedAt());
            HearingFormalRequestHash.require(
                    authorityCommit,
                    "DOSSIER",
                    transition,
                    dossierId,
                    caseMatrixVersion,
                    caseMatrixHash,
                    evidenceMatrixVersion,
                    evidenceMatrixHash,
                    questionSetId,
                    requestSetId,
                    contentHash,
                    actorId);
        }
    }

    record DecisionCommand(
            HearingAuthorityCommit authorityCommit,
            HearingFormalTransition transition,
            HearingArtifactType artifactType,
            String artifactId,
            String contentHash,
            String dossierId,
            String dossierHash,
            String proposalId,
            String proposalHash,
            String reportId,
            String reportHash,
            String payloadJson,
            String agentRunId,
            String agentResultHash,
            String actorId) {

        public DecisionCommand {
            Objects.requireNonNull(authorityCommit, "authorityCommit");
            Objects.requireNonNull(transition, "transition");
            Objects.requireNonNull(artifactType, "artifactType");
            artifactId = HearingAuthorityExpectation.identifier(artifactId, "artifactId");
            dossierId = HearingAuthorityExpectation.identifier(dossierId, "dossierId");
            agentRunId = HearingAuthorityExpectation.identifier(agentRunId, "agentRunId");
            hash(agentResultHash, "agentResultHash");
            actorId = HearingAuthorityExpectation.identifier(actorId, "actorId");
            hash(contentHash, "contentHash");
            hash(dossierHash, "dossierHash");
            transition.requireSource(authorityCommit.authority());
            HearingFlowStage expectedSource = switch (artifactType) {
                case JUDGE_PROPOSAL -> HearingFlowStage.JUDGE_V1_GENERATING;
                case JURY_REVIEW_REPORT -> HearingFlowStage.JURY_REVIEWING;
                case ADJUDICATION_DRAFT -> HearingFlowStage.JUDGE_V2_GENERATING;
            };
            HearingFlowStage expectedResult = switch (artifactType) {
                case JUDGE_PROPOSAL -> HearingFlowStage.JURY_REVIEWING;
                case JURY_REVIEW_REPORT -> HearingFlowStage.JUDGE_V2_GENERATING;
                case ADJUDICATION_DRAFT -> HearingFlowStage.HUMAN_REVIEW_OPEN;
            };
            if (authorityCommit.authority().stage() != expectedSource
                    || transition.resultStage() != expectedResult
                    || !transition.actorId().equals(actorId)) {
                throw new IllegalArgumentException("decision artifact is not valid at this Hearing stage");
            }
            if (artifactType != HearingArtifactType.JUDGE_PROPOSAL) {
                proposalId = HearingAuthorityExpectation.identifier(proposalId, "proposalId");
                hash(proposalHash, "proposalHash");
            } else if (proposalId != null || proposalHash != null || reportId != null || reportHash != null) {
                throw new IllegalArgumentException("Judge V1 cannot carry decision parents");
            }
            if (artifactType == HearingArtifactType.ADJUDICATION_DRAFT) {
                reportId = HearingAuthorityExpectation.identifier(reportId, "reportId");
                hash(reportHash, "reportHash");
            } else if (reportId != null || reportHash != null) {
                throw new IllegalArgumentException("only Judge V2 can carry the Jury parent");
            }
            requireOperation(
                    authorityCommit,
                    HearingAuthorityCommit.OperationType.FINALIZE,
                    authorityPrefix(authorityCommit) + authorityCommit.authority().stageSequence()
                            + ':' + artifactType.schemaVersion() + ':' + authorityCommit.requestHash());
            HearingFormalPayload.requireDecision(
                    payloadJson,
                    artifactType,
                    artifactId,
                    contentHash,
                    dossierId,
                    dossierHash,
                    proposalId,
                    proposalHash,
                    reportId,
                    reportHash);
            HearingFormalRequestHash.require(
                    authorityCommit,
                    "DECISION",
                    transition,
                    artifactType,
                    artifactId,
                    contentHash,
                    dossierId,
                    dossierHash,
                    proposalId,
                    proposalHash,
                    reportId,
                    reportHash,
                    agentRunId,
                    agentResultHash,
                    actorId);
        }
    }

    record HandoffCommand(
            HearingAuthorityCommit authorityCommit,
            HearingFormalTransition transition,
            String handoffId,
            String dossierId,
            String dossierHash,
            String proposalId,
            String proposalHash,
            String reportId,
            String reportHash,
            String judgeV2Id,
            String judgeV2Hash,
            String reviewTaskId,
            String reviewPacketId,
            String handoffHash,
            String actorId) {

        public HandoffCommand {
            Objects.requireNonNull(authorityCommit, "authorityCommit");
            Objects.requireNonNull(transition, "transition");
            handoffId = HearingAuthorityExpectation.identifier(handoffId, "handoffId");
            dossierId = HearingAuthorityExpectation.identifier(dossierId, "dossierId");
            proposalId = HearingAuthorityExpectation.identifier(proposalId, "proposalId");
            reportId = HearingAuthorityExpectation.identifier(reportId, "reportId");
            judgeV2Id = HearingAuthorityExpectation.identifier(judgeV2Id, "judgeV2Id");
            reviewTaskId = HearingAuthorityExpectation.identifier(reviewTaskId, "reviewTaskId");
            reviewPacketId = HearingAuthorityExpectation.identifier(reviewPacketId, "reviewPacketId");
            actorId = HearingAuthorityExpectation.identifier(actorId, "actorId");
            hash(dossierHash, "dossierHash");
            hash(proposalHash, "proposalHash");
            hash(reportHash, "reportHash");
            hash(judgeV2Hash, "judgeV2Hash");
            hash(handoffHash, "handoffHash");
            String expectedHandoffHash = HearingFormalRequestHash.compute(
                    "HANDOFF_FACT",
                    authorityCommit.authority(),
                    handoffId,
                    dossierId,
                    dossierHash,
                    proposalId,
                    proposalHash,
                    reportId,
                    reportHash,
                    judgeV2Id,
                    judgeV2Hash,
                    reviewTaskId,
                    reviewPacketId,
                    actorId,
                    authorityCommit.committedAt());
            if (!handoffHash.equals(expectedHandoffHash)) {
                throw new IllegalArgumentException("handoffHash is not canonical");
            }
            transition.requireSource(authorityCommit.authority());
            if (authorityCommit.authority().stage() != HearingFlowStage.HUMAN_REVIEW_OPEN
                    || transition.resultStage() != HearingFlowStage.HUMAN_REVIEW_OPEN
                    || transition.advances()
                    || !transition.actorId().equals(actorId)) {
                throw new IllegalArgumentException("handoff must preserve HUMAN_REVIEW_OPEN");
            }
            requireOperation(
                    authorityCommit,
                    HearingAuthorityCommit.OperationType.HANDOFF,
                    HearingFormalRequestHash.handoffOperationKey(
                            authorityCommit.authority().tenantSurrogate(),
                            authorityCommit.authority().caseId(),
                            authorityCommit.authority().epochId(),
                            authorityCommit.authority().roomEpoch(),
                            judgeV2Id,
                            judgeV2Hash));
            HearingFormalRequestHash.require(
                    authorityCommit,
                    "HANDOFF",
                    transition,
                    handoffId,
                    dossierId,
                    dossierHash,
                    proposalId,
                    proposalHash,
                    reportId,
                    reportHash,
                    judgeV2Id,
                    judgeV2Hash,
                    reviewTaskId,
                    reviewPacketId,
                    handoffHash,
                    actorId);
        }
    }

    record ClosureCommand(
            HearingAuthorityCommit authorityCommit,
            HearingFormalTransition transition,
            String closureId,
            String handoffId,
            String handoffReceiptId,
            String handoffReceiptHash,
            String closureHash,
            String actorId) {

        public ClosureCommand {
            Objects.requireNonNull(authorityCommit, "authorityCommit");
            Objects.requireNonNull(transition, "transition");
            closureId = HearingAuthorityExpectation.identifier(closureId, "closureId");
            handoffId = HearingAuthorityExpectation.identifier(handoffId, "handoffId");
            handoffReceiptId = HearingAuthorityExpectation.identifier(
                    handoffReceiptId, "handoffReceiptId");
            actorId = HearingAuthorityExpectation.identifier(actorId, "actorId");
            hash(handoffReceiptHash, "handoffReceiptHash");
            hash(closureHash, "closureHash");
            String expectedClosureHash = HearingFormalRequestHash.compute(
                    "CLOSURE_FACT",
                    authorityCommit.authority(),
                    closureId,
                    handoffId,
                    handoffReceiptId,
                    handoffReceiptHash,
                    actorId,
                    authorityCommit.committedAt());
            if (!closureHash.equals(expectedClosureHash)) {
                throw new IllegalArgumentException("closureHash is not canonical");
            }
            transition.requireSource(authorityCommit.authority());
            if (authorityCommit.authority().stage() != HearingFlowStage.HUMAN_REVIEW_OPEN
                    || transition.resultStage() != HearingFlowStage.CLOSED
                    || !transition.advances()
                    || !transition.actorId().equals(actorId)) {
                throw new IllegalArgumentException("closure must advance stage 14 to CLOSED");
            }
            requireOperation(
                    authorityCommit,
                    HearingAuthorityCommit.OperationType.CLOSE,
                    authorityPrefix(authorityCommit) + handoffReceiptHash);
            HearingFormalRequestHash.require(
                    authorityCommit,
                    "CLOSURE",
                    transition,
                    closureId,
                    handoffId,
                    handoffReceiptId,
                    handoffReceiptHash,
                    closureHash,
                    actorId);
        }
    }

    private static String authorityPrefix(HearingAuthorityCommit commit) {
        String operation = switch (commit.operationType()) {
            case STAGE -> "stage";
            case PARTY_TERMINAL -> "party";
            case AGENT_RESULT -> "agent";
            case FINALIZE -> "finalize";
            case HANDOFF -> "handoff";
            case CLOSE -> "close";
        };
        HearingAuthorityExpectation authority = commit.authority();
        return "hearing." + operation + ':' + authority.tenantSurrogate() + ':'
                + authority.caseId() + ':' + authority.roomEpoch() + ':';
    }

    private static void requireOperation(
            HearingAuthorityCommit commit,
            HearingAuthorityCommit.OperationType operationType,
            String expectedKey) {
        if (commit.authority().writerMode() == HearingWriterMode.SHADOW) {
            throw new IllegalArgumentException("SHADOW cannot construct a formal Hearing command");
        }
        if (commit.operationType() != operationType || !commit.operationKey().equals(expectedKey)) {
            throw new IllegalArgumentException("operation key is not exact for the formal command");
        }
    }

    private static String partyRole(String value) {
        if (!"USER".equals(value) && !"MERCHANT".equals(value)) {
            throw new IllegalArgumentException("participantRole must be USER or MERCHANT");
        }
        return value;
    }

    private static void hash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }
}
