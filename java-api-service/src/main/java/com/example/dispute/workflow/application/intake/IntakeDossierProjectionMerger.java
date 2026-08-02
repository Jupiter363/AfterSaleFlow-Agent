package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministically applies an approved Intake patch without weakening stable fact bindings. */
public final class IntakeDossierProjectionMerger {

    private static final String DOSSIER_SCHEMA_VERSION = "intake-dossier.v2";

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
            "admission",
            "handoff_notes");

    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "memory_frame",
            "internal_handoff",
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

    private static final Set<String> DERIVED_MATRIX_DOSSIER_KEYS = Set.of(
            "case_fact_matrix",
            "unilateral_case_matrix",
            "matrix_patch",
            "matrix_id",
            "matrix_version",
            "matrix_kind",
            "generation_ref",
            "parent_ref",
            "party_map",
            "fact_indexes",
            "source_binding",
            "truth_status",
            "fact_relationships",
            "summary_source_fact_ids",
            "evidence_coverage_status");

    private static final Set<String> FORMAL_MATRIX_SCHEMA_VERSIONS = Set.of(
            "unilateral_case_matrix.v1",
            "unilateral_case_matrix.draft.v1",
            "case_fact_matrix.v2",
            "case_fact_matrix.delta.v2");
    private static final Set<String> REQUESTED_RESOLUTIONS = Set.of(
            "REFUND",
            "RETURN_REFUND",
            "RESHIP",
            "REPLACE_OR_REPAIR",
            "COMPENSATION",
            "CANCEL_ORDER",
            "VERIFY_OR_EXPLAIN_ONLY",
            "OTHER",
            "UNKNOWN");

    private final IntakeUnilateralMatrixPolicy matrixPolicy = new IntakeUnilateralMatrixPolicy();
    private final IntakeInitiatorMatrixFreezer initiatorMatrixFreezer =
            new IntakeInitiatorMatrixFreezer();
    private final IntakeRespondentMatrixFreezer respondentMatrixFreezer =
            new IntakeRespondentMatrixFreezer();

    public MergeResult merge(JsonNode current, IntakeTurnProposal proposal) {
        return merge(current, proposal, null);
    }

    public MergeResult merge(
            JsonNode current, IntakeTurnProposal proposal, MatrixAuthority matrixAuthority) {
        if (current == null || !current.isObject()) {
            throw rejected("INTAKE_DOSSIER_CURRENT_INVALID", "persisted Intake dossier is not an object");
        }
        requireProposalOutcomeConsistency(proposal);
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
        rejectDerivedMatrixDossierKeys(patch);

        boolean hasPersistedClaim = current.path("claim_resolution").isObject()
                || current.path("requested_resolution").isObject();
        ObjectNode merged = (ObjectNode) current.deepCopy();
        deepMerge(merged, (ObjectNode) patch);
        if (!hasPersistedClaim) {
            applyClaimAuthority(merged, matrixAuthority);
        }
        boolean matrixChanged = false;
        boolean formalAuthorityValidated = false;

        JsonNode matrixPatch = proposal.matrixPatch();
        if (matrixPatch != null) {
            if (matrixAuthority == null) {
                throw rejected(
                        "INTAKE_MATRIX_AUTHORITY_REQUIRED",
                        "matrix patch requires current Java case and source authority");
            }
            rejectForbiddenKeys(matrixPatch);
            String matrixSchema = matrixPatch.path("schema_version").asText();
            if ("unilateral_case_matrix.draft.v1".equals(matrixSchema)) {
                ObjectNode previousFormal = null;
                ObjectNode previousLegacy = null;
                if (merged.path("case_fact_matrix").isObject()) {
                    previousFormal = ((ObjectNode) merged.path("case_fact_matrix")).deepCopy();
                    initiatorMatrixFreezer.validateFrozen(
                            previousFormal,
                            matrixAuthority.caseId(),
                            matrixAuthority.initiatorRole(),
                            matrixAuthority.respondentRole());
                } else if (merged.path("unilateral_case_matrix").isObject()) {
                    previousLegacy =
                            ((ObjectNode) merged.path("unilateral_case_matrix")).deepCopy();
                }
                ObjectNode unilateral = matrixPolicy.apply(merged, matrixPatch, matrixAuthority);
                ObjectNode frozen = previousLegacy == null
                        ? initiatorMatrixFreezer.freeze(
                                matrixAuthority.caseId(),
                                matrixAuthority.initiatorRole(),
                                matrixAuthority.respondentRole(),
                                unilateral,
                                previousFormal)
                        : initiatorMatrixFreezer.freezeLegacyRevision(
                                matrixAuthority.caseId(),
                                matrixAuthority.initiatorRole(),
                                matrixAuthority.respondentRole(),
                                unilateral,
                                previousLegacy);
                merged.set("case_fact_matrix", frozen);
                merged.remove("unilateral_case_matrix");
                matrixChanged = true;
                formalAuthorityValidated = true;
            } else if ("case_fact_matrix.delta.v2".equals(matrixSchema)) {
                if (matrixAuthority.actorRole() != matrixAuthority.respondentRole()) {
                    throw rejected(
                            "INTAKE_RESPONDENT_MATRIX_AUTHORITY_INVALID",
                            "matrix delta requires the Java-authorized respondent actor");
                }
                JsonNode currentFormalMatrix = merged.path("case_fact_matrix");
                if (!currentFormalMatrix.isObject()) {
                    throw rejected(
                            "INTAKE_RESPONDENT_MATRIX_PARENT_REQUIRED",
                            "respondent delta requires the Java-owned initiator matrix");
                }
                ObjectNode bilateralCandidate = respondentMatrixFreezer.deriveCandidate(
                        (ObjectNode) currentFormalMatrix, matrixPatch, matrixAuthority);
                formalAuthorityValidated = true;
                if (proposal.readiness() == IntakeTurnProposal.Readiness.READY_TO_CONFIRM
                        && proposal.missingFields().isEmpty()) {
                    respondentMatrixFreezer.requireCompleteForFreeze(
                            bilateralCandidate, matrixAuthority);
                    merged.set("case_fact_matrix", bilateralCandidate);
                    matrixChanged = true;
                }
            } else {
                if ("case_fact_matrix.v2".equals(matrixSchema)
                        || matrixPatch.has("matrix_kind")) {
                    throw rejected(
                            "INTAKE_MATRIX_FORMAL_TRANSITION_FORBIDDEN",
                            "matrix patch cannot create or revise a formal matrix");
                }
                throw rejected(
                        "INTAKE_MATRIX_PATCH_INVALID",
                        "matrix patch is not a supported semantic proposal");
            }
        } else if (matrixAuthority != null) {
            if (merged.path("case_fact_matrix").isObject()) {
                JsonNode existingMatrix = merged.path("case_fact_matrix");
                if ("INITIATOR_FROZEN".equals(existingMatrix.path("matrix_kind").asText())) {
                    initiatorMatrixFreezer.validateFrozen(
                            (ObjectNode) existingMatrix,
                            matrixAuthority.caseId(),
                            matrixAuthority.initiatorRole(),
                            matrixAuthority.respondentRole());
                    formalAuthorityValidated = true;
                }
            } else if (merged.path("unilateral_case_matrix").isObject()) {
                // Preserve deployed pre-unification dossiers until their next matrix draft or the
                // existing confirmation lifecycle migrates them. In particular, do not reset a
                // multi-turn legacy projection to formal version 1 here.
                matrixPolicy.validateExisting(
                        (ObjectNode) merged.path("unilateral_case_matrix"), matrixAuthority);
            }
        }

        // A formal matrix is the only externally visible authority. Clean up a stale legacy
        // projection only after the current Java case/party authority has validated that formal
        // matrix; an untrusted object-shaped branch must never discard the legacy authority.
        if (formalAuthorityValidated) {
            merged.remove("unilateral_case_matrix");
        }

        if (proposal.readiness() == IntakeTurnProposal.Readiness.READY_TO_CONFIRM
                && matrixAuthority != null
                && matrixAuthority.actorRole() == matrixAuthority.respondentRole()
                && !matrixChanged) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_NOT_READY",
                    "respondent readiness requires a complete bilateral matrix delta");
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
        JsonNode proposedSchema = patch.get("schema_version");
        if (proposedSchema != null
                && (!proposedSchema.isTextual()
                        || !(DOSSIER_SCHEMA_VERSION.equals(proposedSchema.textValue())
                                || "intake_case_detail.v1".equals(proposedSchema.textValue())))) {
            throw rejected(
                    "INTAKE_DOSSIER_SCHEMA_INVALID",
                    "dossier patch schema is not the formal Intake dossier schema");
        }
        dossier.put("schema_version", DOSSIER_SCHEMA_VERSION);

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

    private static void requireProposalOutcomeConsistency(IntakeTurnProposal proposal) {
        boolean ready = proposal.readiness() == IntakeTurnProposal.Readiness.READY_TO_CONFIRM;
        boolean accepted = proposal.recommendation() == IntakeTurnProposal.Recommendation.ACCEPTED;
        if (ready != accepted
                || (proposal.recommendation()
                                == IntakeTurnProposal.Recommendation.NOT_ADMISSIBLE
                        && proposal.readiness() != IntakeTurnProposal.Readiness.NEEDS_REVIEW)) {
            throw rejected(
                    "INTAKE_PROPOSAL_OUTCOME_CONFLICT",
                    "proposal readiness and recommendation are inconsistent");
        }
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

    private static void rejectDerivedMatrixDossierKeys(JsonNode value) {
        if (value.isObject()) {
            value.fields().forEachRemaining(field -> {
                if (DERIVED_MATRIX_DOSSIER_KEYS.contains(field.getKey())) {
                    throw rejected(
                            "INTAKE_MATRIX_DERIVED_FIELD_FORBIDDEN",
                            "dossier patch cannot carry Java-derived matrix authority");
                }
                if ("schema_version".equals(field.getKey())
                        && field.getValue().isTextual()
                        && FORMAL_MATRIX_SCHEMA_VERSIONS.contains(
                                field.getValue().textValue())) {
                    throw rejected(
                            "INTAKE_MATRIX_DERIVED_FIELD_FORBIDDEN",
                            "dossier patch cannot carry a matrix schema");
                }
                rejectDerivedMatrixDossierKeys(field.getValue());
            });
        } else if (value.isArray()) {
            value.forEach(IntakeDossierProjectionMerger::rejectDerivedMatrixDossierKeys);
        }
    }

    private static int qualityScore(ObjectNode dossier, IntakeTurnProposal proposal) {
        JsonNode score = dossier.path("intake_quality").path("score");
        if (score.isIntegralNumber() && score.canConvertToInt()) {
            return Math.max(0, Math.min(100, score.intValue()));
        }
        return 0;
    }

    private static Long matrixVersion(ObjectNode dossier) {
        for (String branch : List.of("case_fact_matrix")) {
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
            index.visitRoot(value);
            return index;
        }

        private void visitRoot(JsonNode value) {
            if (value == null || !value.isObject()) {
                visit(value);
                return;
            }
            boolean hasFormalMatrix = value.path("case_fact_matrix").isObject();
            value.fields().forEachRemaining(field -> {
                if (!hasFormalMatrix || !"unilateral_case_matrix".equals(field.getKey())) {
                    visit(field.getValue());
                }
            });
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
                        if (value.has("materiality")) {
                            binding.set("materiality", value.path("materiality").deepCopy());
                        }
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
            ObjectNode material = JsonNodeFactory.instance.objectNode();
            material.set("value", value.deepCopy());
            String binding = ContractJson.sha256Hex(material);
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

    private static void applyClaimAuthority(ObjectNode dossier, MatrixAuthority authority) {
        if (authority == null || authority.claimResolutionAuthority() == null) {
            return;
        }
        ClaimResolutionAuthority trusted = authority.claimResolutionAuthority();
        ObjectNode claim = dossier.path("claim_resolution").isObject()
                ? (ObjectNode) dossier.path("claim_resolution")
                : dossier.putObject("claim_resolution");
        claim.put("initiator_role", authority.initiatorRole().name());
        claim.put("requested_resolution", trusted.requestedResolution());
        setOrRemove(claim, "requested_amount", trusted.requestedAmount());
        setOrRemove(claim, "requested_items", trusted.requestedItems());
        setOrRemove(claim, "request_reason", trusted.requestReason());

        if (dossier.path("requested_resolution").isObject()) {
            ObjectNode requested = (ObjectNode) dossier.path("requested_resolution");
            requested.put("kind", trusted.requestedResolution());
            requested.put("requested_resolution", trusted.requestedResolution());
            setOrRemove(requested, "requested_amount", trusted.requestedAmount());
            setOrRemove(requested, "requested_items", trusted.requestedItems());
            setOrRemove(requested, "reason", trusted.requestReason());
        }
    }

    private static void setOrRemove(ObjectNode target, String field, BigDecimal value) {
        if (value == null) {
            target.remove(field);
        } else {
            target.put(field, value);
        }
    }

    private static void setOrRemove(ObjectNode target, String field, String value) {
        if (value == null) {
            target.remove(field);
        } else {
            target.put(field, value);
        }
    }

    public record ClaimResolutionAuthority(
            String requestedResolution,
            BigDecimal requestedAmount,
            String requestedItems,
            String requestReason) {

        public ClaimResolutionAuthority {
            requestedResolution =
                    IntakeContractSupport.identifier(requestedResolution, "requestedResolution");
            if (!REQUESTED_RESOLUTIONS.contains(requestedResolution)) {
                throw new IllegalArgumentException("requestedResolution is unsupported");
            }
            if (requestedAmount != null && requestedAmount.signum() < 0) {
                throw new IllegalArgumentException("requestedAmount must not be negative");
            }
            requestedItems = optionalBounded(requestedItems, 2_000, "requestedItems");
            requestReason = optionalBounded(requestReason, 20_000, "requestReason");
        }

        private static String optionalBounded(String value, int maxLength, String field) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String normalized = value.strip();
            if (normalized.length() > maxLength) {
                throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
            }
            return normalized;
        }
    }

    public record MatrixAuthority(
            String caseId,
            ActorRole actorRole,
            ActorRole initiatorRole,
            ActorRole respondentRole,
            String sourceRef,
            String sourceContextHash,
            ClaimResolutionAuthority claimResolutionAuthority) {

        public MatrixAuthority(
                String caseId,
                ActorRole actorRole,
                ActorRole initiatorRole,
                ActorRole respondentRole,
                String sourceRef,
                String sourceContextHash) {
            this(
                    caseId,
                    actorRole,
                    initiatorRole,
                    respondentRole,
                    sourceRef,
                    sourceContextHash,
                    null);
        }

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
