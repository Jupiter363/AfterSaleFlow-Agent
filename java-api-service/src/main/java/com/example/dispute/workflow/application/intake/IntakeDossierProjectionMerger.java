package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministically applies an approved Intake patch without weakening stable fact bindings. */
public final class IntakeDossierProjectionMerger {

    private static final Set<String> DOSSIER_BRANCHES = Set.of(
            "schema_version",
            "case_story",
            "references",
            "party_positions",
            "dispute_focus",
            "requested_resolution",
            "claim_resolution",
            "respondent_attitude",
            "dispute_core_state",
            "risk_assessment",
            "missing_information",
            "intake_quality",
            "admission");

    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "memory_frame",
            "internal_handoff",
            "handoff_notes",
            "hidden_reasoning",
            "chain_of_thought",
            "tool_calls",
            "tool_parameters",
            "writer_mode",
            "credentials",
            "credential",
            "password",
            "api_key",
            "access_token",
            "refresh_token",
            "authorization_header",
            "private_key",
            "client_secret",
            "raw_audit_records",
            "audit_records",
            "reviewer_notes",
            "other_party_private_messages",
            "opposing_party_private_messages",
            "private_conversation",
            "internal_notes",
            "opposing_party_messages",
            "opposing_party_private",
            "other_party_messages",
            "other_party_private",
            "trusted_model_profile",
            "prompt_version",
            "model_profile_id",
            "policy_version",
            "guardrail_version",
            "tool_policy_version",
            "open_evidence",
            "complete_party",
            "send_summons",
            "execute_tool",
            "process_state",
            "case_status",
            "room_transition",
            "evidence_deadline",
            "review_instructions",
            "tool_instructions",
            "admit_case",
            "cancel_case",
            "cancel_intake",
            "freeze_matrix",
            "open_room",
            "set_deadline",
            "invite_participant");

    private final IntakeUnilateralMatrixPolicy matrixPolicy = new IntakeUnilateralMatrixPolicy();

    public MergeResult merge(JsonNode current, IntakeTurnProposal proposal) {
        return merge(current, proposal, null);
    }

    public MergeResult merge(
            JsonNode current, IntakeTurnProposal proposal, MatrixAuthority matrixAuthority) {
        if (current == null || !current.isObject()) {
            throw rejected("INTAKE_DOSSIER_CURRENT_INVALID", "persisted Intake dossier is not an object");
        }
        JsonNode patch = proposal.dossierPatch();
        if (!patch.isObject()) {
            throw rejected("INTAKE_DOSSIER_PATCH_INVALID", "dossier patch is not an object");
        }
        patch.fieldNames().forEachRemaining(name -> {
            if ("case_fact_matrix".equals(name) || "unilateral_case_matrix".equals(name)) {
                throw rejected(
                        "INTAKE_MATRIX_PATCH_REQUIRED",
                        "matrix changes must use the dedicated matrix_patch field");
            }
            if (!DOSSIER_BRANCHES.contains(name)) {
                throw rejected(
                        "INTAKE_DOSSIER_PATCH_INVALID",
                        "dossier patch contains an unauthorized branch");
            }
        });
        rejectForbiddenKeys(patch);

        ObjectNode merged = (ObjectNode) current.deepCopy();
        deepMerge(merged, (ObjectNode) patch);
        boolean matrixChanged = false;

        JsonNode matrixPatch = proposal.matrixPatch();
        if (matrixPatch != null) {
            if (matrixAuthority == null) {
                throw rejected(
                        "INTAKE_MATRIX_AUTHORITY_REQUIRED",
                        "matrix patch requires current Java case and source authority");
            }
            rejectForbiddenKeys(matrixPatch);
            merged.set(
                    "unilateral_case_matrix",
                    matrixPolicy.apply(merged, matrixPatch, matrixAuthority));
            matrixChanged = true;
        } else if (matrixAuthority != null && merged.path("unilateral_case_matrix").isObject()) {
            matrixPolicy.validateExisting(
                    (ObjectNode) merged.path("unilateral_case_matrix"), matrixAuthority);
        }

        normalizeProjectionMetadata(merged, (ObjectNode) patch, proposal);
        requireStableTransition(current, merged);
        int qualityScore = qualityScore(merged, proposal);
        boolean ready = proposal.readiness() == IntakeTurnProposal.Readiness.READY_TO_CONFIRM;
        Long matrixVersion = matrixChanged ? matrixVersion(merged) : null;
        return new MergeResult(
                merged,
                qualityScore,
                ready,
                proposal.recommendation().name(),
                matrixVersion);
    }

    private static void deepMerge(ObjectNode target, ObjectNode patch) {
        Iterator<Map.Entry<String, JsonNode>> fields = patch.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode existing = target.get(field.getKey());
            JsonNode incoming = field.getValue();
            if (existing != null && existing.isObject() && incoming.isObject()) {
                ObjectNode child = (ObjectNode) existing.deepCopy();
                deepMerge(child, (ObjectNode) incoming);
                target.set(field.getKey(), child);
            } else {
                target.set(field.getKey(), incoming.deepCopy());
            }
        }
    }

    private static void requireStableTransition(JsonNode previous, JsonNode current) {
        StableIndex before = StableIndex.from(previous);
        StableIndex after = StableIndex.from(current);
        if (!after.factIds.containsAll(before.factIds)
                || !after.sourceRefs.containsAll(before.sourceRefs)) {
            throw rejected(
                    "INTAKE_DOSSIER_STABLE_ID_DELETED",
                    "dossier patch deletes a stable fact or source reference");
        }
        before.bindings.forEach((id, binding) -> {
            if (!binding.equals(after.bindings.get(id))) {
                throw rejected(
                        "INTAKE_DOSSIER_STABLE_ID_REBOUND",
                        "dossier patch rebinds a stable fact or source hash");
            }
        });
    }

    private static void normalizeProjectionMetadata(
            ObjectNode dossier, ObjectNode patch, IntakeTurnProposal proposal) {
        boolean ready = proposal.readiness() == IntakeTurnProposal.Readiness.READY_TO_CONFIRM;
        JsonNode proposedReady = patch.path("intake_quality").path("ready_for_next_step");
        if (!proposedReady.isMissingNode()
                && (!proposedReady.isBoolean() || proposedReady.booleanValue() != ready)) {
            throw rejected(
                    "INTAKE_DOSSIER_READINESS_CONFLICT",
                    "dossier readiness conflicts with the typed proposal readiness");
        }
        JsonNode proposedRecommendation = patch.path("admission").path("recommendation");
        if (!proposedRecommendation.isMissingNode()
                && (!proposedRecommendation.isTextual()
                        || !proposal.recommendation().name().equals(
                                proposedRecommendation.textValue()))) {
            throw rejected(
                    "INTAKE_DOSSIER_RECOMMENDATION_CONFLICT",
                    "dossier recommendation conflicts with the typed proposal recommendation");
        }
        ObjectNode quality = dossier.path("intake_quality").isObject()
                ? (ObjectNode) dossier.path("intake_quality")
                : dossier.putObject("intake_quality");
        quality.put("ready_for_next_step", ready);
        ObjectNode admission = dossier.path("admission").isObject()
                ? (ObjectNode) dossier.path("admission")
                : dossier.putObject("admission");
        admission.put("recommendation", proposal.recommendation().name());
    }

    private static void rejectForbiddenKeys(JsonNode value) {
        if (value.isObject()) {
            value.fields().forEachRemaining(field -> {
                if (FORBIDDEN_KEYS.contains(field.getKey())) {
                    throw rejected(
                            "INTAKE_DOSSIER_FORBIDDEN_FIELD",
                            "dossier patch contains an internal or formal-action field");
                }
                rejectForbiddenKeys(field.getValue());
            });
        } else if (value.isArray()) {
            value.forEach(IntakeDossierProjectionMerger::rejectForbiddenKeys);
        }
    }

    private static int qualityScore(ObjectNode dossier, IntakeTurnProposal proposal) {
        JsonNode score = dossier.path("intake_quality").path("score");
        if (score.isIntegralNumber() && score.canConvertToInt()) {
            return Math.max(0, Math.min(100, score.intValue()));
        }
        return proposal.confidence()
                .multiply(java.math.BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    private static Long matrixVersion(ObjectNode dossier) {
        for (String branch : List.of("case_fact_matrix", "unilateral_case_matrix")) {
            JsonNode matrix = dossier.path(branch);
            if (!matrix.isObject()) {
                continue;
            }
            JsonNode version = matrix.path("matrix_version");
            if (version.isIntegralNumber() && version.canConvertToLong() && version.longValue() > 0) {
                return version.longValue();
            }
        }
        return null;
    }

    private static IntakeFinalizationRejectedException rejected(String code, String message) {
        return new IntakeFinalizationRejectedException(code, message);
    }

    public record MergeResult(
            ObjectNode dossier,
            int qualityScore,
            boolean readyForNextStep,
            String recommendation,
            Long matrixVersion) {

        public MergeResult {
            dossier = dossier.deepCopy();
        }

        @Override
        public ObjectNode dossier() {
            return dossier.deepCopy();
        }

        public String canonicalDossierJson() {
            return ContractJson.canonicalString(dossier);
        }
    }

    private static final class StableIndex {
        private final Set<String> factIds = new HashSet<>();
        private final Map<String, String> bindings = new HashMap<>();
        private final Set<String> sourceRefs = new HashSet<>();

        private static StableIndex from(JsonNode value) {
            StableIndex index = new StableIndex();
            index.visit(value);
            return index;
        }

        private void visit(JsonNode value) {
            if (value == null) {
                return;
            }
            if (value.isObject()) {
                String factId = text(value, "fact_id");
                if (factId != null) {
                    if (!factIds.add(factId)) {
                        throw rejected(
                                "INTAKE_DOSSIER_STABLE_ID_CONFLICT",
                                "dossier contains a duplicate stable fact identifier");
                    }
                    boolean bound = false;
                    if (value.has("category") && value.has("fact_target")) {
                        ObjectNode binding = ((ObjectNode) value).objectNode();
                        binding.set("category", value.path("category").deepCopy());
                        binding.set("fact_target", value.path("fact_target").deepCopy());
                        register("fact:" + factId, binding);
                        bound = true;
                    }
                    String contentHash = text(value, "content_hash");
                    if (contentHash != null) {
                        register("fact-hash:" + factId, value.path("content_hash"));
                        bound = true;
                    }
                    if (!bound) {
                        throw rejected(
                                "INTAKE_DOSSIER_STABLE_ID_UNBOUND",
                                "dossier contains a stable fact id without a semantic binding");
                    }
                }
                String sourceId = text(value, "source_id");
                if (sourceId != null) {
                    if (!sourceRefs.add(sourceId)) {
                        throw rejected(
                                "INTAKE_DOSSIER_STABLE_ID_CONFLICT",
                                "dossier contains a duplicate stable source identifier");
                    }
                    ObjectNode sourceBinding = ((ObjectNode) value).objectNode();
                    for (String hashField : List.of("source_hash", "sha256", "content_hash")) {
                        String hash = text(value, hashField);
                        if (hash != null) {
                            sourceBinding.put(hashField, hash);
                        }
                    }
                    if (sourceBinding.isEmpty()) {
                        throw rejected(
                                "INTAKE_DOSSIER_STABLE_ID_UNBOUND",
                                "dossier contains a stable source id without a hash binding");
                    }
                    register("source:" + sourceId, sourceBinding);
                }
                JsonNode refs = value.path("source_refs");
                if (refs.isArray()) {
                    refs.forEach(ref -> {
                        if (ref.isTextual()) {
                            sourceRefs.add(ref.textValue());
                        }
                    });
                }
                value.forEach(this::visit);
            } else if (value.isArray()) {
                value.forEach(this::visit);
            }
        }

        private void register(String id, JsonNode value) {
            String binding = ContractJson.sha256Hex(value);
            String previous = bindings.putIfAbsent(id, binding);
            if (previous != null && !previous.equals(binding)) {
                throw rejected(
                        "INTAKE_DOSSIER_STABLE_ID_CONFLICT",
                        "dossier contains conflicting bindings for one stable identifier");
            }
        }

        private static String text(JsonNode value, String field) {
            JsonNode child = value.get(field);
            return child != null && child.isTextual() ? child.textValue() : null;
        }
    }

    public record MatrixAuthority(
            String caseId,
            ActorRole actorRole,
            ActorRole initiatorRole,
            ActorRole respondentRole,
            String sourceRef,
            String sourceContextHash) {

        public MatrixAuthority {
            caseId = IntakeContractSupport.identifier(caseId, "caseId");
            actorRole = Objects.requireNonNull(actorRole, "actorRole");
            initiatorRole = Objects.requireNonNull(initiatorRole, "initiatorRole");
            respondentRole = Objects.requireNonNull(respondentRole, "respondentRole");
            if (initiatorRole == respondentRole
                    || (actorRole != initiatorRole && actorRole != respondentRole)) {
                throw new IllegalArgumentException("matrix party authority is invalid");
            }
            sourceRef = IntakeContractSupport.identifier(sourceRef, "sourceRef");
            sourceContextHash =
                    IntakeContractSupport.sha256(sourceContextHash, "sourceContextHash");
        }
    }
}
