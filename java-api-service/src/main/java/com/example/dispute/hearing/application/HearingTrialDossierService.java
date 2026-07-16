package com.example.dispute.hearing.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.domain.HearingFlowActionType;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowActionEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowInstanceEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingTrialDossierEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowActionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowInstanceRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingTrialDossierRepository;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.policy.application.PolicyApplicationService;
import com.example.dispute.policy.application.PolicyRuleView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Validates every collaboration binding and freezes one immutable trial_dossier.v1. */
@Service
public class HearingTrialDossierService {

    private final HearingTrialDossierRepository trialDossierRepository;
    private final HearingFlowInstanceRepository flowInstanceRepository;
    private final HearingFlowActionRepository actionRepository;
    private final FulfillmentCaseRepository caseRepository;
    private final PolicyApplicationService policyApplicationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HearingTrialDossierService(
            HearingTrialDossierRepository trialDossierRepository,
            HearingFlowInstanceRepository flowInstanceRepository,
            HearingFlowActionRepository actionRepository,
            FulfillmentCaseRepository caseRepository,
            PolicyApplicationService policyApplicationService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.trialDossierRepository = trialDossierRepository;
        this.flowInstanceRepository = flowInstanceRepository;
        this.actionRepository = actionRepository;
        this.caseRepository = caseRepository;
        this.policyApplicationService = policyApplicationService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Freezes from the two synthesis envelopes while loading every durable party action from the
     * V2 ledger. This is the orchestration-facing entry point; it does not infer state from room
     * messages or accept missing bundles.
     */
    @Transactional
    public HearingTrialDossierEntity freeze(
            String caseId,
            JsonNode intakeSynthesis,
            JsonNode evidenceSynthesis,
            String actorId) {
        HearingFlowInstanceEntity flow =
                flowInstanceRepository
                        .findByCaseId(caseId)
                        .orElseThrow(() -> new IllegalStateException("hearing flow instance not found"));
        FulfillmentCaseEntity dispute = requireCase(caseId);
        ObjectNode questionSet =
                persistedActionPayload(flow.getId(), HearingFlowActionType.QUESTION_SET, null);
        ObjectNode requestSet =
                persistedActionPayload(
                        flow.getId(), HearingFlowActionType.EVIDENCE_REQUEST_SET, null);
        ArrayNode answerBundles = objectMapper.createArrayNode();
        ArrayNode evidenceBatches = objectMapper.createArrayNode();
        for (ActorRole role : partyRoles()) {
            String participantId = participantId(dispute, role);
            answerBundles.add(
                    persistedActionPayload(
                            flow.getId(), HearingFlowActionType.ANSWER_BUNDLE, participantId));
            evidenceBatches.add(
                    persistedActionPayload(
                            flow.getId(), HearingFlowActionType.EVIDENCE_BATCH, participantId));
        }
        ArrayNode policyRules = policyRuleSnapshots();
        return freeze(
                new FreezeTrialDossierCommand(
                        caseId,
                        flow.getId(),
                        requireObject(intakeSynthesis, "intakeSynthesis")
                                .path("case_fact_matrix"),
                        requireObject(evidenceSynthesis, "evidenceSynthesis")
                                .path("fact_evidence_matrix"),
                        questionSet,
                        answerBundles,
                        requestSet,
                        evidenceBatches,
                        policyRules,
                        actorId));
    }

    @Transactional
    public HearingTrialDossierEntity freeze(FreezeTrialDossierCommand command) {
        requireCommand(command);
        HearingFlowInstanceEntity flow =
                flowInstanceRepository
                        .findByCaseIdForUpdate(command.caseId())
                        .orElseThrow(() -> new IllegalStateException("hearing flow instance not found"));
        if (!flow.getId().equals(command.flowInstanceId())) {
            throw new IllegalStateException("trial dossier belongs to another hearing flow");
        }
        FulfillmentCaseEntity dispute = requireCase(command.caseId());
        Map<ActorRole, String> participantIds = participantIds(dispute);
        if (flow.getCurrentStage() != HearingFlowStage.DOSSIER_FREEZING) {
            throw new IllegalStateException(
                    "trial dossier can freeze only during DOSSIER_FREEZING, current stage is "
                            + flow.getCurrentStage());
        }

        ObjectNode caseMatrix = requireObject(command.caseFactMatrix(), "caseFactMatrix");
        requireSchema(caseMatrix, "case_fact_matrix.v2");
        requireCase(caseMatrix, command.caseId(), "case fact matrix");
        verifyEmbeddedHash(caseMatrix, "content_hash", "case fact matrix");
        int caseMatrixVersion = positiveInt(caseMatrix, "matrix_version", "case fact matrix");
        String caseMatrixHash = requiredText(caseMatrix, "content_hash", "case fact matrix");
        String caseMatrixId = requiredText(caseMatrix, "matrix_id", "case fact matrix");
        Set<String> factIds = factIds(caseMatrix);

        ObjectNode evidenceMatrix = requireObject(command.factEvidenceMatrix(), "factEvidenceMatrix");
        requireSchema(evidenceMatrix, "fact_evidence_matrix.v2");
        requireCase(evidenceMatrix, command.caseId(), "fact evidence matrix");
        requireTextEquals(
                evidenceMatrix,
                "case_fact_matrix_id",
                caseMatrixId,
                "fact evidence matrix");
        requireIntEquals(
                evidenceMatrix,
                "case_fact_matrix_version",
                caseMatrixVersion,
                "fact evidence matrix");
        requireTextEquals(
                evidenceMatrix,
                "case_fact_matrix_hash",
                caseMatrixHash,
                "fact evidence matrix");
        verifyEmbeddedHash(evidenceMatrix, "content_hash", "fact evidence matrix");
        evidenceMatrix.put("matrix_status", "FROZEN");
        evidenceMatrix.put("content_hash", embeddedHash(evidenceMatrix, "content_hash"));
        int evidenceMatrixVersion =
                positiveInt(evidenceMatrix, "matrix_version", "fact evidence matrix");
        String evidenceMatrixHash =
                requiredText(evidenceMatrix, "content_hash", "fact evidence matrix");
        validateEvidenceFactIds(evidenceMatrix, factIds);

        ObjectNode questionSet = requireObject(command.questionSet(), "questionSet");
        requireSchema(questionSet, "hearing_question_set.v1");
        requireCase(questionSet, command.caseId(), "question set");
        requireQuestionMatrixBinding(questionSet, caseMatrix);
        String questionSetId = requiredText(questionSet, "question_set_id", "question set");
        Map<String, Set<ActorRole>> questionTargets = questionTargets(questionSet, factIds);

        ArrayNode answerBundles = requireArray(command.answerBundles(), "answerBundles");
        Map<ActorRole, ObjectNode> answersByRole =
                validateAnswerBundles(
                        answerBundles, questionSetId, questionTargets, participantIds);

        ObjectNode requestSet = requireObject(command.evidenceRequestSet(), "evidenceRequestSet");
        requireSchema(requestSet, "hearing_evidence_request_set.v1");
        requireMatrixBinding(requestSet, caseMatrixVersion, caseMatrixHash, "evidence request set");
        String requestSetId = requiredText(requestSet, "request_set_id", "evidence request set");
        Map<String, Set<ActorRole>> requestTargets = requestTargets(requestSet, factIds);

        ArrayNode evidenceBatches = requireArray(command.evidenceBatches(), "evidenceBatches");
        Map<ActorRole, ObjectNode> batchesByRole =
                validateEvidenceBatches(
                        evidenceBatches, requestSetId, requestTargets, participantIds);
        ArrayNode policyRules = requireArray(command.policyRules(), "policyRules");
        validatePolicyRules(policyRules);

        verifyPersistedActions(
                flow,
                questionSetId,
                questionSet,
                answersByRole,
                requestSetId,
                requestSet,
                batchesByRole,
                participantIds);

        var existing = trialDossierRepository.findByCaseId(command.caseId());
        if (existing.isPresent()) {
            assertSameFrozenInputs(
                    existing.orElseThrow(),
                    caseMatrixHash,
                    evidenceMatrixHash,
                    questionSetId,
                    requestSetId,
                    questionSet,
                    answerBundles,
                    requestSet,
                    evidenceBatches,
                    policyRules);
            return existing.orElseThrow();
        }

        String dossierId = "TRIAL_DOSSIER_" + compactUuid();
        Instant frozenAt = clock.instant();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schema_version", "trial_dossier.v1");
        payload.put("trial_dossier_id", dossierId);
        payload.put("case_id", command.caseId());
        payload.put("frozen_at", frozenAt.toString());
        payload.put("case_matrix_version", caseMatrixVersion);
        payload.put("case_matrix_hash", caseMatrixHash);
        payload.set("case_fact_matrix", caseMatrix.deepCopy());
        payload.put("evidence_matrix_version", evidenceMatrixVersion);
        payload.put("evidence_matrix_hash", evidenceMatrixHash);
        payload.set("fact_evidence_matrix", evidenceMatrix.deepCopy());
        payload.put("question_set_id", questionSetId);
        payload.set("question_set", questionSet.deepCopy());
        payload.set("answer_bundles", answerBundles.deepCopy());
        payload.put("request_set_id", requestSetId);
        payload.set("evidence_request_set", requestSet.deepCopy());
        payload.set("evidence_batches", evidenceBatches.deepCopy());
        payload.set("policy_rules", policyRules.deepCopy());
        String contentHash = sha256(canonicalJson(payload));
        payload.put("content_hash", contentHash);

        return trialDossierRepository.save(
                HearingTrialDossierEntity.frozen(
                        dossierId,
                        command.caseId(),
                        command.flowInstanceId(),
                        caseMatrixVersion,
                        caseMatrixHash,
                        evidenceMatrixVersion,
                        evidenceMatrixHash,
                        questionSetId,
                        requestSetId,
                        json(payload),
                        contentHash,
                        frozenAt,
                        command.actorId()));
    }

    @Transactional(readOnly = true)
    public HearingTrialDossierEntity requireFrozen(String caseId) {
        return trialDossierRepository
                .findByCaseId(caseId)
                .filter(item -> "trial_dossier.v1".equals(item.getSchemaVersion()))
                .orElseThrow(() -> new IllegalStateException("trial_dossier.v1 is not frozen"));
    }

    private void verifyPersistedActions(
            HearingFlowInstanceEntity flow,
            String questionSetId,
            ObjectNode questionSet,
            Map<ActorRole, ObjectNode> answersByRole,
            String requestSetId,
            ObjectNode requestSet,
            Map<ActorRole, ObjectNode> batchesByRole,
            Map<ActorRole, String> participantIds) {
        verifyAction(
                actionRepository
                        .findByFlowInstanceIdAndActionType(
                                flow.getId(), HearingFlowActionType.QUESTION_SET)
                        .orElseThrow(
                                () -> new IllegalStateException("persisted question set missing")),
                flow,
                HearingFlowActionType.QUESTION_SET,
                null,
                null,
                questionSet);
        for (ActorRole role : partyRoles()) {
            verifyAction(
                    actionRepository
                            .findByFlowInstanceIdAndActionTypeAndParticipantId(
                                    flow.getId(),
                                    HearingFlowActionType.ANSWER_BUNDLE,
                                    participantIds.get(role))
                            .orElseThrow(
                                    () -> new IllegalStateException("persisted answer bundle missing")),
                    flow,
                    HearingFlowActionType.ANSWER_BUNDLE,
                    participantIds.get(role),
                    role,
                    answersByRole.get(role));
        }
        verifyAction(
                actionRepository
                        .findByFlowInstanceIdAndActionType(
                                flow.getId(), HearingFlowActionType.EVIDENCE_REQUEST_SET)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "persisted evidence request set missing")),
                flow,
                HearingFlowActionType.EVIDENCE_REQUEST_SET,
                null,
                null,
                requestSet);
        for (ActorRole role : partyRoles()) {
            verifyAction(
                    actionRepository
                            .findByFlowInstanceIdAndActionTypeAndParticipantId(
                                    flow.getId(),
                                    HearingFlowActionType.EVIDENCE_BATCH,
                                    participantIds.get(role))
                            .orElseThrow(
                                    () -> new IllegalStateException("persisted evidence batch missing")),
                    flow,
                    HearingFlowActionType.EVIDENCE_BATCH,
                    participantIds.get(role),
                    role,
                    batchesByRole.get(role));
        }
    }

    private ObjectNode persistedActionPayload(
            String flowInstanceId, HearingFlowActionType type, String participantId) {
        HearingFlowActionEntity action =
                participantId == null
                        ? actionRepository
                                .findByFlowInstanceIdAndActionType(flowInstanceId, type)
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "persisted " + type + " action missing"))
                        : actionRepository
                                .findByFlowInstanceIdAndActionTypeAndParticipantId(
                                        flowInstanceId, type, participantId)
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "persisted "
                                                                + type
                                                                + " action missing for "
                                                                + participantId));
        return requireObject(parse(action.getPayloadJson(), type + " action"), type.name());
    }

    private void verifyAction(
            HearingFlowActionEntity action,
            HearingFlowInstanceEntity flow,
            HearingFlowActionType type,
            String participantId,
            ActorRole role,
            JsonNode payload) {
        if (!flow.getId().equals(action.getFlowInstanceId())
                || !flow.getCaseId().equals(action.getCaseId())
                || action.getActionType() != type
                || !java.util.Objects.equals(action.getParticipantId(), participantId)
                || action.getParticipantRole() != role) {
            throw new IllegalStateException("hearing action binding does not match frozen dossier");
        }
        JsonNode persisted = parse(action.getPayloadJson(), "hearing action payload");
        String canonicalPayload = canonicalJson(payload);
        if (!canonicalPayload.equals(canonicalJson(persisted))
                || !action.getContentHash().equals(sha256(canonicalPayload))) {
            throw new IllegalStateException("hearing action payload/hash does not match freeze input");
        }
    }

    private void assertSameFrozenInputs(
            HearingTrialDossierEntity existing,
            String caseMatrixHash,
            String evidenceMatrixHash,
            String questionSetId,
            String requestSetId,
            JsonNode questionSet,
            JsonNode answerBundles,
            JsonNode requestSet,
            JsonNode evidenceBatches,
            JsonNode policyRules) {
        JsonNode payload = parse(existing.getPayloadJson(), "trial dossier");
        boolean same =
                caseMatrixHash.equals(existing.getCaseMatrixHash())
                        && evidenceMatrixHash.equals(existing.getEvidenceMatrixHash())
                        && questionSetId.equals(existing.getQuestionSetId())
                        && requestSetId.equals(existing.getRequestSetId())
                        && canonicalJson(questionSet).equals(canonicalJson(payload.path("question_set")))
                        && canonicalJson(answerBundles)
                                .equals(canonicalJson(payload.path("answer_bundles")))
                        && canonicalJson(requestSet)
                                .equals(canonicalJson(payload.path("evidence_request_set")))
                        && canonicalJson(evidenceBatches)
                                .equals(canonicalJson(payload.path("evidence_batches")))
                        && canonicalJson(policyRules)
                                .equals(canonicalJson(payload.path("policy_rules")));
        if (!same) {
            throw new IllegalStateException("trial dossier is already frozen with different inputs");
        }
    }

    private Map<String, Set<ActorRole>> questionTargets(
            ObjectNode questionSet, Set<String> knownFactIds) {
        ArrayNode questions = requireArray(questionSet.path("questions"), "questions");
        if (questions.isEmpty() || questions.size() > 5) {
            throw new IllegalStateException("question set must contain one to five questions");
        }
        Map<String, Set<ActorRole>> result = new LinkedHashMap<>();
        for (JsonNode item : questions) {
            ObjectNode question = requireObject(item, "question");
            String id = requiredText(question, "question_id", "question");
            if (result.put(id, targetRoles(question, "question")) != null) {
                throw new IllegalStateException("duplicate question id: " + id);
            }
            requireKnownFactIds(question.path("fact_ids"), knownFactIds, "question " + id);
            requiredText(question, "issue_id", "question");
            requiredText(question, "question_text", "question");
        }
        return Map.copyOf(result);
    }

    private Map<ActorRole, ObjectNode> validateAnswerBundles(
            ArrayNode bundles,
            String questionSetId,
            Map<String, Set<ActorRole>> targets,
            Map<ActorRole, String> participantIds) {
        if (bundles.size() != 2) {
            throw new IllegalStateException("trial dossier requires two answer bundles");
        }
        Map<ActorRole, ObjectNode> result = new LinkedHashMap<>();
        for (JsonNode item : bundles) {
            ObjectNode bundle = requireObject(item, "answer bundle");
            String schema = requiredText(bundle, "schema_version", "answer bundle");
            boolean statement = "hearing_party_statement.v1".equals(schema);
            if (!statement && !"hearing_answer_bundle.v1".equals(schema)) {
                throw new IllegalStateException("unsupported answer submission schema");
            }
            String boundSetId =
                    statement
                            ? nonBlankText(bundle, "issue_set_id", "question_set_id")
                            : requiredText(bundle, "question_set_id", "answer bundle");
            if (!questionSetId.equals(boundSetId)) {
                throw new IllegalStateException("answer submission issue set binding mismatch");
            }
            ActorRole role = partyRole(bundle, "answer bundle");
            requireParticipantBinding(
                    bundle, participantIds.get(role), statement, "answer submission");
            if (result.put(role, bundle) != null) {
                throw new IllegalStateException("duplicate answer bundle for " + role);
            }
            String status = terminalStatus(bundle, "answer bundle");
            if (statement) {
                if ("SUBMITTED".equals(status)) {
                    requiredText(bundle, "statement_text", "party statement");
                } else if (bundle.path("statement_text").isTextual()
                        && !bundle.path("statement_text").asText().isBlank()) {
                    throw new IllegalStateException(
                            "timed-out party statement cannot contain statement_text");
                }
                requireArray(bundle.path("source_message_ids"), "source_message_ids");
                continue;
            }
            ArrayNode answers = requireArray(bundle.path("answers"), "answers");
            Set<String> actual = new LinkedHashSet<>();
            for (JsonNode answerNode : answers) {
                ObjectNode answer = requireObject(answerNode, "answer");
                String questionId = requiredText(answer, "question_id", "answer");
                if (!targets.containsKey(questionId) || !targets.get(questionId).contains(role)) {
                    throw new IllegalStateException("foreign or non-applicable question id: " + questionId);
                }
                if (!actual.add(questionId)) {
                    throw new IllegalStateException("duplicate answer question id: " + questionId);
                }
                requiredText(answer, "answer_text", "answer");
            }
            Set<String> expected = new LinkedHashSet<>();
            targets.forEach(
                    (id, roles) -> {
                        if (roles.contains(role)) {
                            expected.add(id);
                        }
                    });
            if (("SUBMITTED".equals(status) && !actual.equals(expected))
                    || ("AUTO_TIMEOUT".equals(status) && !actual.isEmpty())) {
                throw new IllegalStateException(
                        "answer bundle must cover each applicable question exactly once");
            }
        }
        requireBothRoles(result.keySet(), "answer bundles");
        return Map.copyOf(result);
    }

    private Map<String, Set<ActorRole>> requestTargets(
            ObjectNode requestSet, Set<String> knownFactIds) {
        ArrayNode requests = requireArray(requestSet.path("requests"), "requests");
        Map<String, Set<ActorRole>> result = new LinkedHashMap<>();
        for (JsonNode item : requests) {
            ObjectNode request = requireObject(item, "evidence request");
            String id = requiredText(request, "request_id", "evidence request");
            if (result.put(id, targetRoles(request, "evidence request")) != null) {
                throw new IllegalStateException("duplicate evidence request id: " + id);
            }
            requireKnownFactIds(request.path("fact_ids"), knownFactIds, "evidence request " + id);
            requiredText(request, "requested_material", "evidence request");
            requiredText(request, "verification_goal", "evidence request");
            if (!request.path("required").isBoolean()) {
                throw new IllegalStateException("evidence request required flag is missing");
            }
        }
        return Map.copyOf(result);
    }

    private Map<ActorRole, ObjectNode> validateEvidenceBatches(
            ArrayNode batches,
            String requestSetId,
            Map<String, Set<ActorRole>> targets,
            Map<ActorRole, String> participantIds) {
        if (batches.size() != 2) {
            throw new IllegalStateException("trial dossier requires two evidence batches");
        }
        Map<ActorRole, ObjectNode> result = new LinkedHashMap<>();
        Set<String> allEvidenceIds = new HashSet<>();
        for (JsonNode item : batches) {
            ObjectNode batch = requireObject(item, "evidence batch");
            requireSchema(batch, "hearing_evidence_batch.v1");
            requireTextEquals(batch, "request_set_id", requestSetId, "evidence batch");
            ActorRole role = partyRole(batch, "evidence batch");
            requireParticipantBinding(
                    batch, participantIds.get(role), false, "evidence batch");
            if (result.put(role, batch) != null) {
                throw new IllegalStateException("duplicate evidence batch for " + role);
            }
            terminalStatus(batch, "evidence batch");
            ArrayNode evidenceIds = requireArray(batch.path("evidence_ids"), "evidence_ids");
            if (evidenceIds.size() > 50) {
                throw new IllegalStateException("evidence batch cannot contain more than 50 files");
            }
            Set<String> localEvidenceIds = uniqueTextValues(evidenceIds, "evidence id");
            if (localEvidenceIds.stream().anyMatch(allEvidenceIds::contains)) {
                throw new IllegalStateException("evidence ids must be unique across both batches");
            }
            allEvidenceIds.addAll(localEvidenceIds);
            for (String requestId : uniqueTextValues(
                    requireArray(batch.path("request_ids"), "request_ids"), "request id")) {
                if (!targets.containsKey(requestId) || !targets.get(requestId).contains(role)) {
                    throw new IllegalStateException("foreign or non-applicable request id: " + requestId);
                }
            }
        }
        requireBothRoles(result.keySet(), "evidence batches");
        return Map.copyOf(result);
    }

    private void validateEvidenceFactIds(ObjectNode matrix, Set<String> knownFactIds) {
        for (String field : List.of("links", "fact_coverage")) {
            JsonNode rows = matrix.path(field);
            if (!rows.isMissingNode() && !rows.isArray()) {
                throw new IllegalStateException(field + " must be an array");
            }
            for (JsonNode row : rows) {
                String factId = row.path("fact_id").asText("");
                if (!knownFactIds.contains(factId)) {
                    throw new IllegalStateException("fact evidence matrix references unknown fact " + factId);
                }
            }
        }
    }

    private Set<String> factIds(ObjectNode matrix) {
        ArrayNode rows = requireArray(matrix.path("fact_rows"), "case fact rows");
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            String id = requiredText(requireObject(row, "case fact row"), "fact_id", "case fact row");
            if (!ids.add(id)) {
                throw new IllegalStateException("duplicate case fact id: " + id);
            }
        }
        return Set.copyOf(ids);
    }

    private void requireKnownFactIds(JsonNode value, Set<String> known, String context) {
        Set<String> values = uniqueTextValues(requireArray(value, context + " fact_ids"), "fact id");
        if (values.isEmpty() || !known.containsAll(values)) {
            throw new IllegalStateException(context + " references missing or unknown fact ids");
        }
    }

    private Set<ActorRole> targetRoles(ObjectNode value, String context) {
        ArrayNode roles = requireArray(value.path("target_roles"), context + " target_roles");
        Set<ActorRole> result = new LinkedHashSet<>();
        for (JsonNode role : roles) {
            ActorRole parsed = parsePartyRole(role.asText(""), context);
            if (!result.add(parsed)) {
                throw new IllegalStateException(context + " contains duplicate target role");
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException(context + " requires at least one target role");
        }
        return Set.copyOf(result);
    }

    private ActorRole partyRole(ObjectNode value, String context) {
        return parsePartyRole(requiredText(value, "participant_role", context), context);
    }

    private void requireParticipantBinding(
            ObjectNode value, String expectedId, boolean required, String context) {
        String participantId = value.path("participant_id").asText("");
        if ((required && participantId.isBlank())
                || (!participantId.isBlank() && !participantId.equals(expectedId))) {
            throw new IllegalStateException(context + " participant_id binding mismatch");
        }
    }

    private String nonBlankText(ObjectNode value, String preferred, String fallback) {
        String result = value.path(preferred).asText("");
        return result.isBlank()
                ? requiredText(value, fallback, "answer submission")
                : result;
    }

    private ActorRole parsePartyRole(String value, String context) {
        if (ActorRole.USER.name().equals(value)) {
            return ActorRole.USER;
        }
        if (ActorRole.MERCHANT.name().equals(value)) {
            return ActorRole.MERCHANT;
        }
        throw new IllegalStateException(context + " role must be USER or MERCHANT");
    }

    private String terminalStatus(ObjectNode value, String context) {
        String status = requiredText(value, "submission_status", context);
        if (!"SUBMITTED".equals(status) && !"AUTO_TIMEOUT".equals(status)) {
            throw new IllegalStateException(context + " has invalid submission_status");
        }
        return status;
    }

    private void requireBothRoles(Set<ActorRole> roles, String context) {
        if (!roles.equals(Set.of(ActorRole.USER, ActorRole.MERCHANT))) {
            throw new IllegalStateException(context + " must contain USER and MERCHANT once");
        }
    }

    private Set<String> uniqueTextValues(ArrayNode values, String context) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode item : values) {
            String value = item.asText("");
            if (value.isBlank() || !result.add(value)) {
                throw new IllegalStateException(context + " values must be non-blank and unique");
            }
        }
        return result;
    }

    private ArrayNode policyRuleSnapshots() {
        List<PolicyRuleView> activeRules = policyApplicationService.findActive(null);
        if (activeRules.isEmpty() || activeRules.size() > 100) {
            throw new IllegalStateException("trial dossier requires between one and 100 active policy rules");
        }
        ArrayNode snapshots = objectMapper.createArrayNode();
        for (PolicyRuleView rule : activeRules) {
            ObjectNode value = snapshots.addObject();
            value.put("policy_id", rule.id());
            value.put("rule_code", rule.ruleCode());
            value.put("rule_version", rule.ruleVersion());
            value.put("rule_name", rule.ruleName());
            value.put("rule_scope", rule.ruleScope());
            value.put("rule_status", rule.ruleStatus());
            value.put("effective_from", rule.effectiveFrom().toString());
            if (rule.effectiveTo() == null) {
                value.putNull("effective_to");
            } else {
                value.put("effective_to", rule.effectiveTo().toString());
            }
            value.put("priority", rule.priority());
            value.set("conditions", objectMapper.valueToTree(rule.conditions()));
            value.set("outcome", objectMapper.valueToTree(rule.outcome()));
            value.set("source_document", objectMapper.valueToTree(rule.sourceDocument()));
        }
        validatePolicyRules(snapshots);
        return snapshots;
    }

    private void validatePolicyRules(ArrayNode policyRules) {
        if (policyRules.isEmpty() || policyRules.size() > 100) {
            throw new IllegalStateException("trial dossier requires between one and 100 active policy rules");
        }
        Set<String> versions = new LinkedHashSet<>();
        for (JsonNode item : policyRules) {
            ObjectNode rule = requireObject(item, "policy rule");
            requiredText(rule, "policy_id", "policy rule");
            String ruleCode = requiredText(rule, "rule_code", "policy rule");
            int ruleVersion = positiveInt(rule, "rule_version", "policy rule");
            requiredText(rule, "rule_name", "policy rule");
            requiredText(rule, "rule_scope", "policy rule");
            requireTextEquals(rule, "rule_status", "ACTIVE", "policy rule");
            requiredText(rule, "effective_from", "policy rule");
            requireObject(rule.path("conditions"), "policy rule conditions");
            requireObject(rule.path("outcome"), "policy rule outcome");
            requireObject(rule.path("source_document"), "policy rule source document");
            if (!versions.add(ruleCode + "\u001f" + ruleVersion)) {
                throw new IllegalStateException("policy rule versions must be unique");
            }
        }
    }

    private void requireMatrixBinding(
            ObjectNode value, int matrixVersion, String matrixHash, String context) {
        requireIntEquals(value, "case_matrix_version", matrixVersion, context);
        requireTextEquals(value, "case_matrix_hash", matrixHash, context);
    }

    private void requireQuestionMatrixBinding(
            ObjectNode questionSet, ObjectNode finalCaseMatrix) {
        ObjectNode parent =
                requireObject(
                        finalCaseMatrix.path("parent_ref"),
                        "final case matrix parent_ref");
        requiredText(parent, "matrix_id", "final case matrix parent_ref");
        int parentVersion =
                positiveInt(parent, "matrix_version", "final case matrix parent_ref");
        String parentHash =
                requiredText(parent, "content_hash", "final case matrix parent_ref");
        if (!parentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("final case matrix parent_ref hash is invalid");
        }
        int finalVersion =
                positiveInt(finalCaseMatrix, "matrix_version", "final case matrix");
        if (finalVersion != parentVersion + 1) {
            throw new IllegalStateException(
                    "final hearing case matrix must immediately follow its parent version");
        }
        requireMatrixBinding(questionSet, parentVersion, parentHash, "question set");
    }

    private void requireSchema(ObjectNode value, String expected) {
        requireTextEquals(value, "schema_version", expected, expected);
    }

    private void requireCase(ObjectNode value, String caseId, String context) {
        requireTextEquals(value, "case_id", caseId, context);
    }

    private void requireTextEquals(
            ObjectNode value, String field, String expected, String context) {
        String actual = requiredText(value, field, context);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(context + " " + field + " binding mismatch");
        }
    }

    private void requireIntEquals(
            ObjectNode value, String field, int expected, String context) {
        if (positiveInt(value, field, context) != expected) {
            throw new IllegalStateException(context + " " + field + " binding mismatch");
        }
    }

    private int positiveInt(ObjectNode value, String field, String context) {
        JsonNode node = value.path(field);
        if (!node.canConvertToInt() || node.asInt() < 1) {
            throw new IllegalStateException(context + " " + field + " must be positive");
        }
        return node.asInt();
    }

    private String requiredText(ObjectNode value, String field, String context) {
        String text = value.path(field).asText("");
        if (text.isBlank()) {
            throw new IllegalStateException(context + " " + field + " must not be blank");
        }
        return text;
    }

    private ObjectNode requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalStateException(field + " must be an object");
        }
        return value.deepCopy();
    }

    private ArrayNode requireArray(JsonNode value, String field) {
        if (value == null || !value.isArray()) {
            throw new IllegalStateException(field + " must be an array");
        }
        return value.deepCopy();
    }

    private void verifyEmbeddedHash(ObjectNode value, String field, String context) {
        String expected = requiredText(value, field, context);
        if (!expected.matches("[0-9a-f]{64}") || !expected.equals(embeddedHash(value, field))) {
            throw new IllegalStateException(context + " content hash is invalid");
        }
    }

    private String embeddedHash(ObjectNode value, String field) {
        ObjectNode copy = value.deepCopy();
        copy.remove(field);
        return sha256(canonicalJson(copy));
    }

    private JsonNode parse(String value, String context) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot parse " + context, exception);
        }
    }

    private String canonicalJson(JsonNode value) {
        return json(canonicalNode(value));
    }

    private JsonNode canonicalNode(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            TreeSet<String> names = new TreeSet<>();
            value.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                sorted.set(name, canonicalNode(value.get(name)));
            }
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            value.forEach(item -> array.add(canonicalNode(item)));
            return array;
        }
        return value.deepCopy();
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize hearing dossier", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requireCommand(FreezeTrialDossierCommand command) {
        if (command == null
                || blank(command.caseId())
                || blank(command.flowInstanceId())
                || blank(command.actorId())) {
            throw new IllegalArgumentException("freeze command identifiers must not be blank");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static List<ActorRole> partyRoles() {
        return List.of(ActorRole.USER, ActorRole.MERCHANT);
    }

    private FulfillmentCaseEntity requireCase(String caseId) {
        return caseRepository
                .findById(caseId)
                .orElseThrow(() -> new IllegalStateException("hearing case not found"));
    }

    private static Map<ActorRole, String> participantIds(FulfillmentCaseEntity dispute) {
        return Map.of(
                ActorRole.USER,
                dispute.getUserId(),
                ActorRole.MERCHANT,
                dispute.getMerchantId());
    }

    private static String participantId(
            FulfillmentCaseEntity dispute, ActorRole role) {
        return role == ActorRole.USER ? dispute.getUserId() : dispute.getMerchantId();
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public record FreezeTrialDossierCommand(
            String caseId,
            String flowInstanceId,
            JsonNode caseFactMatrix,
            JsonNode factEvidenceMatrix,
            JsonNode questionSet,
            JsonNode answerBundles,
            JsonNode evidenceRequestSet,
            JsonNode evidenceBatches,
            JsonNode policyRules,
            String actorId) {}
}
