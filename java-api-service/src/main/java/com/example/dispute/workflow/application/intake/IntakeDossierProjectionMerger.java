package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.application.intake.IntakeTurnEventPublisher.SourceType;
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
    private static final String PARTY_INTAKE_STATE_SCHEMA_VERSION = "party-intake-state.v1";
    private static final List<String> PARTY_INTAKE_ROLES = List.of("USER", "MERCHANT");
    private static final Set<String> PARTY_INTAKE_ENTRY_FIELDS = Set.of(
            "intake_quality", "missing_information", "handoff_notes", "admission");
    private static final Map<String, Integer> QUALITY_COMPONENT_MAXIMA = Map.of(
            "references", 15,
            "event_story", 20,
            "party_positions", 20,
            "requested_resolution", 15,
            "risk_and_conflicts", 15,
            "next_action_clarity", 15);

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
            "handoff_notes",
            "party_intake_state");

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
    private final IntakeInitiatorMatrixDeltaFreezer initiatorMatrixDeltaFreezer =
            new IntakeInitiatorMatrixDeltaFreezer();
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
        requirePartyIntakeTransition(current, (ObjectNode) patch, matrixAuthority);

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
        boolean respondentOpening = matrixAuthority != null
                && matrixAuthority.sourceType() == SourceType.RESPONDENT_OPENING;
        if (respondentOpening
                && (matrixPatch == null
                        || matrixAuthority.actorRole() != matrixAuthority.respondentRole()
                        || !"case_fact_matrix.delta.v2"
                                .equals(matrixPatch.path("schema_version").asText()))) {
            throw rejected(
                    "INTAKE_RESPONDENT_OPENING_MATRIX_INVALID",
                    "respondent opening requires its exact respondent carry delta");
        }
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
                if (matrixAuthority.actorRole() == matrixAuthority.initiatorRole()) {
                    ObjectNode initiatorFrozen = initiatorMatrixDeltaFreezer.freeze(
                            merged, matrixPatch, matrixAuthority);
                    merged.set("case_fact_matrix", initiatorFrozen);
                    matrixChanged = true;
                    formalAuthorityValidated = true;
                } else if (matrixAuthority.actorRole() == matrixAuthority.respondentRole()) {
                    JsonNode currentFormalMatrix = merged.path("case_fact_matrix");
                    if (!currentFormalMatrix.isObject()) {
                        throw rejected(
                                "INTAKE_RESPONDENT_MATRIX_PARENT_REQUIRED",
                                "respondent delta requires the Java-owned initiator matrix");
                    }
                    ObjectNode bilateralCandidate = respondentMatrixFreezer.deriveCandidate(
                            (ObjectNode) currentFormalMatrix, matrixPatch, matrixAuthority);
                    formalAuthorityValidated = true;
                    if (respondentOpening) {
                        requireRespondentOpeningCarry(
                                (ObjectNode) currentFormalMatrix,
                                matrixPatch,
                                matrixAuthority,
                                proposal);
                        merged.set("case_fact_matrix", bilateralCandidate);
                        matrixChanged = true;
                    } else if (proposal.readiness()
                                    == IntakeTurnProposal.Readiness.READY_TO_CONFIRM
                            && proposal.missingFields().isEmpty()) {
                        respondentMatrixFreezer.requireCompleteForFreeze(
                                bilateralCandidate, matrixAuthority);
                        merged.set("case_fact_matrix", bilateralCandidate);
                        matrixChanged = true;
                    }
                } else {
                    throw rejected(
                            "INTAKE_MATRIX_ACTOR_AUTHORITY_INVALID",
                            "matrix delta actor is not a Java-authorized case party");
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
        requirePartyIntakeProjection(merged, matrixAuthority);
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

    private static void requireRespondentOpeningCarry(
            ObjectNode parent,
            JsonNode delta,
            MatrixAuthority authority,
            IntakeTurnProposal proposal) {
        if (authority.sourceType() != SourceType.RESPONDENT_OPENING
                || authority.actorRole() != authority.respondentRole()
                || proposal.readiness() != IntakeTurnProposal.Readiness.INCOMPLETE
                || proposal.recommendation() != IntakeTurnProposal.Recommendation.NEED_MORE_INFO
                || !proposal.missingFields().isEmpty()) {
            throw rejected(
                    "INTAKE_RESPONDENT_OPENING_MATRIX_INVALID",
                    "respondent opening matrix outcome or authority is invalid");
        }
        JsonNode respondentClaim = delta.get("respondent_claim");
        if (respondentClaim != null && !respondentClaim.isNull()) {
            throw rejected(
                    "INTAKE_RESPONDENT_OPENING_MATRIX_INVALID",
                    "respondent opening cannot create a respondent claim");
        }

        JsonNode parentRows = parent.path("fact_rows");
        JsonNode deltaRows = delta.path("fact_rows");
        Map<String, JsonNode> parentById = new HashMap<>();
        for (JsonNode parentRow : parentRows) {
            parentById.put(parentRow.path("fact_id").asText(), parentRow);
        }
        if (!deltaRows.isArray() || deltaRows.size() != parentById.size()) {
            throw rejected(
                    "INTAKE_RESPONDENT_OPENING_MATRIX_INVALID",
                    "respondent opening must carry every prior fact exactly once");
        }

        Set<String> carried = new HashSet<>();
        int rowIndex = 0;
        for (JsonNode row : deltaRows) {
            String factId = row.path("fact_key").asText();
            JsonNode orderedPrior = parentRows.get(rowIndex++);
            JsonNode prior = parentById.get(factId);
            JsonNode priorRespondent = prior == null
                    ? null
                    : prior.path("positions").path(authority.respondentRole().name());
            if (prior == null
                    || orderedPrior == null
                    || !factId.equals(orderedPrior.path("fact_id").asText())
                    || !carried.add(factId)
                    || !prior.path("category").equals(row.path("category"))
                    || !prior.path("fact_target").equals(row.path("fact_target"))
                    || !prior.path("materiality").equals(row.path("materiality"))
                    || priorRespondent == null
                    || !"NOT_ADDRESSED".equals(priorRespondent.path("stance").asText())
                    || !"NOT_ADDRESSED".equals(row.path("stance").asText())
                    || !"PREVIOUS_MATRIX".equals(row.path("source_scope").asText())
                    || !priorRespondent
                            .path("position_summary")
                            .equals(row.path("position_summary"))
                    || !absentOrNull(row.get("asserted_value"))
                    || !sameOptionalText(
                            prior.path("party_alignment").get("agreed_statement"),
                            row.get("agreed_statement"))
                    || !sameOptionalText(
                            prior.path("party_alignment").get("conflict_summary"),
                            row.get("conflict_summary"))) {
                throw rejected(
                        "INTAKE_RESPONDENT_OPENING_MATRIX_INVALID",
                        "respondent opening carry diverges from prior matrix authority");
            }
        }
        if (!carried.equals(parentById.keySet())
                || !parent.path("case_overview")
                        .path("summary_source_fact_ids")
                        .equals(delta.path("summary_source_fact_keys"))) {
            throw rejected(
                    "INTAKE_RESPONDENT_OPENING_MATRIX_INVALID",
                    "respondent opening carry changes fact membership or summary authority");
        }
    }

    private static boolean absentOrNull(JsonNode value) {
        return value == null || value.isNull();
    }

    private static boolean sameOptionalText(JsonNode left, JsonNode right) {
        return Objects.equals(optionalText(left), optionalText(right));
    }

    private static String optionalText(JsonNode value) {
        return value == null || value.isNull() ? null : value.textValue();
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

    private static void requirePartyIntakeTransition(
            JsonNode current, ObjectNode patch, MatrixAuthority authority) {
        JsonNode currentState = current.get("party_intake_state");
        JsonNode proposedState = patch.get("party_intake_state");
        if (currentState == null && proposedState == null) {
            return;
        }
        if (authority == null) {
            throw rejected(
                    "INTAKE_PARTY_STATE_AUTHORITY_REQUIRED",
                    "party-scoped Intake state requires current Java actor authority");
        }
        if (currentState != null) {
            validatePartyIntakeState(currentState);
        }
        if (proposedState != null) {
            validatePartyIntakeState(proposedState);
        }

        String actorRole = authority.actorRole().name();
        String otherRole = "USER".equals(actorRole) ? "MERCHANT" : "USER";
        if (currentState != null
                && proposedState != null
                && !currentState.path(otherRole).equals(proposedState.path(otherRole))) {
            throw rejected(
                    "INTAKE_PARTY_STATE_OTHER_PARTY_DRIFT",
                    "party-scoped Intake patch changes the non-current party authority");
        }
        if (currentState == null
                && proposedState != null
                && !defaultPartyIntakeEntry().equals(proposedState.path(otherRole))) {
            throw rejected(
                    "INTAKE_PARTY_STATE_LEGACY_MIGRATION_INVALID",
                    "legacy Intake migration may initialize only the current party entry");
        }
        if (proposedState != null) {
            JsonNode previousActorEntry = currentState == null
                    ? defaultPartyIntakeEntry()
                    : currentState.path(actorRole);
            JsonNode proposedActorEntry = proposedState.path(actorRole);
            JsonNode previousHandoff = previousActorEntry.path("handoff_notes");
            JsonNode proposedHandoff = proposedActorEntry.path("handoff_notes");
            String previousStatus = previousHandoff.path("remark_status").asText("");
            String proposedStatus = proposedHandoff.path("remark_status").asText("");
            String previousSource =
                    previousHandoff.path("phase_source_message_id").asText("");
            String proposedSource =
                    proposedHandoff.path("phase_source_message_id").asText("");
            if (!previousStatus.equals(proposedStatus)) {
                if (authority.sourceType() != SourceType.ROOM_MESSAGE
                        || !authority.sourceRef().equals(proposedSource)) {
                    throw rejected(
                            "INTAKE_PARTY_STATE_PHASE_SOURCE_INVALID",
                            "party Intake phase change is not bound to the current room message");
                }
            } else if (!previousSource.equals(proposedSource)) {
                throw rejected(
                        "INTAKE_PARTY_STATE_PHASE_SOURCE_DRIFT",
                        "party Intake phase source changed without a phase transition");
            }
        }
    }

    private static void requirePartyIntakeProjection(
            ObjectNode dossier, MatrixAuthority authority) {
        JsonNode state = dossier.get("party_intake_state");
        if (state == null) {
            return;
        }
        if (authority == null) {
            throw rejected(
                    "INTAKE_PARTY_STATE_AUTHORITY_REQUIRED",
                    "party-scoped Intake projection requires current Java actor authority");
        }
        validatePartyIntakeState(state);
        JsonNode actorEntry = state.path(authority.actorRole().name());
        for (String field : PARTY_INTAKE_ENTRY_FIELDS) {
            if (!actorEntry.path(field).equals(dossier.path(field))) {
                throw rejected(
                        "INTAKE_PARTY_STATE_MIRROR_CONFLICT",
                        "legacy Intake branch is not the exact current-actor mirror");
            }
        }
    }

    private static void validatePartyIntakeState(JsonNode state) {
        requireExactObjectFields(
                state,
                Set.of("schema_version", "USER", "MERCHANT"),
                "INTAKE_PARTY_STATE_SCHEMA_INVALID",
                "party_intake_state must contain exactly schema_version, USER, and MERCHANT");
        if (!PARTY_INTAKE_STATE_SCHEMA_VERSION.equals(
                state.path("schema_version").asText(null))) {
            throw rejected(
                    "INTAKE_PARTY_STATE_SCHEMA_INVALID",
                    "party_intake_state schema_version is unsupported");
        }
        for (String role : PARTY_INTAKE_ROLES) {
            validatePartyIntakeEntry(state.path(role), role);
        }
    }

    private static void validatePartyIntakeEntry(JsonNode entry, String role) {
        requireExactObjectFields(
                entry,
                PARTY_INTAKE_ENTRY_FIELDS,
                "INTAKE_PARTY_STATE_ENTRY_INVALID",
                "party Intake entry must contain exactly the four state branches");

        JsonNode quality = entry.path("intake_quality");
        requireExactObjectFields(
                quality,
                Set.of(
                        "score",
                        "threshold",
                        "ready_for_next_step",
                        "score_breakdown",
                        "improvement_reason"),
                "INTAKE_PARTY_STATE_QUALITY_INVALID",
                "party Intake quality shape is invalid");
        JsonNode score = quality.path("score");
        JsonNode threshold = quality.path("threshold");
        JsonNode ready = quality.path("ready_for_next_step");
        JsonNode breakdown = quality.path("score_breakdown");
        requireExactObjectFields(
                breakdown,
                QUALITY_COMPONENT_MAXIMA.keySet(),
                "INTAKE_PARTY_STATE_QUALITY_INVALID",
                "party Intake score breakdown shape is invalid");
        if (!score.isIntegralNumber()
                || !score.canConvertToInt()
                || score.intValue() < 0
                || score.intValue() > 100
                || !threshold.isIntegralNumber()
                || threshold.intValue() != 85
                || !ready.isBoolean()
                || !quality.path("improvement_reason").isTextual()) {
            throw rejected(
                    "INTAKE_PARTY_STATE_QUALITY_INVALID",
                    "party Intake quality violates the canonical scalar contract");
        }
        int breakdownTotal = 0;
        for (Map.Entry<String, Integer> component : QUALITY_COMPONENT_MAXIMA.entrySet()) {
            JsonNode value = breakdown.path(component.getKey());
            if (!value.isIntegralNumber()
                    || !value.canConvertToInt()
                    || value.intValue() < 0
                    || value.intValue() > component.getValue()) {
                throw rejected(
                        "INTAKE_PARTY_STATE_QUALITY_INVALID",
                        "party Intake score component is outside its canonical range");
            }
            breakdownTotal += value.intValue();
        }
        if (breakdownTotal != score.intValue()) {
            throw rejected(
                    "INTAKE_PARTY_STATE_QUALITY_INVALID",
                    "party Intake score does not equal its canonical breakdown");
        }

        JsonNode missing = entry.path("missing_information");
        requireExactObjectFields(
                missing,
                Set.of("blocking_gaps", "nice_to_have_gaps", "next_questions"),
                "INTAKE_PARTY_STATE_MISSING_INVALID",
                "party Intake missing-information shape is invalid");
        for (String field : List.of("blocking_gaps", "nice_to_have_gaps", "next_questions")) {
            JsonNode values = missing.path(field);
            if (!values.isArray()) {
                throw rejected(
                        "INTAKE_PARTY_STATE_MISSING_INVALID",
                        "party Intake missing-information value is not an array");
            }
            values.forEach(value -> {
                if (!value.isTextual()) {
                    throw rejected(
                            "INTAKE_PARTY_STATE_MISSING_INVALID",
                            "party Intake missing-information item is not text");
                }
            });
        }

        JsonNode handoff = entry.path("handoff_notes");
        requireExactObjectFields(
                handoff,
                Set.of(
                        "remark_status",
                        "phase_source_message_id",
                        "latest_remark",
                        "remarks",
                        "instruction"),
                "INTAKE_PARTY_STATE_HANDOFF_INVALID",
                "party Intake handoff shape is invalid");
        Set<String> remarkStatuses = Set.of(
                "NOT_READY",
                "READY_PENDING_REMARK_INVITE",
                "WAITING_FOR_REMARK",
                "HAS_REMARKS",
                "NO_EXTRA_REMARKS");
        if (!handoff.path("remark_status").isTextual()
                || !remarkStatuses.contains(handoff.path("remark_status").textValue())
                || !handoff.path("phase_source_message_id").isTextual()
                || !handoff.path("latest_remark").isTextual()
                || !handoff.path("instruction").isTextual()
                || !handoff.path("remarks").isArray()) {
            throw rejected(
                    "INTAKE_PARTY_STATE_HANDOFF_INVALID",
                    "party Intake handoff violates the canonical scalar contract");
        }
        Set<String> remarkSourceIds = new HashSet<>();
        handoff.path("remarks").forEach(remark -> {
            requireExactObjectFields(
                    remark,
                    Set.of("role", "text", "source_message_id", "turn_source"),
                    "INTAKE_PARTY_STATE_HANDOFF_INVALID",
                    "party Intake remark shape is invalid");
            if (!role.equals(remark.path("role").asText(null))
                    || !remark.path("text").isTextual()
                    || !remark.path("source_message_id").isTextual()
                    || !remark.path("turn_source").isTextual()) {
                throw rejected(
                    "INTAKE_PARTY_STATE_HANDOFF_INVALID",
                    "party Intake remark has foreign or malformed authority");
            }
            if (!remarkSourceIds.add(remark.path("source_message_id").textValue())) {
                throw rejected(
                        "INTAKE_PARTY_STATE_HANDOFF_INVALID",
                        "party Intake remarks repeat a source message authority");
            }
        });

        JsonNode admission = entry.path("admission");
        requireExactObjectFields(
                admission,
                Set.of("recommendation", "reasoning", "confidence"),
                "INTAKE_PARTY_STATE_ADMISSION_INVALID",
                "party Intake admission shape is invalid");
        Set<String> recommendations = Set.of("NEED_MORE_INFO", "ACCEPTED", "NOT_ADMISSIBLE");
        JsonNode confidence = admission.path("confidence");
        if (!admission.path("recommendation").isTextual()
                || !recommendations.contains(admission.path("recommendation").textValue())
                || !admission.path("reasoning").isTextual()
                || !confidence.isNumber()
                || confidence.decimalValue().compareTo(BigDecimal.ZERO) < 0
                || confidence.decimalValue().compareTo(BigDecimal.ONE) > 0) {
            throw rejected(
                    "INTAKE_PARTY_STATE_ADMISSION_INVALID",
                    "party Intake admission violates the canonical scalar contract");
        }

        boolean isReady = ready.booleanValue();
        boolean accepted = "ACCEPTED".equals(admission.path("recommendation").textValue());
        String remarkStatus = handoff.path("remark_status").textValue();
        JsonNode remarks = handoff.path("remarks");
        String latestRemark = handoff.path("latest_remark").textValue();
        if ((isReady
                        && (score.intValue() < 85
                                || missing.path("blocking_gaps").size() > 0
                                || !accepted
                                || "NOT_READY".equals(remarkStatus)))
                || (!isReady && (accepted || !"NOT_READY".equals(remarkStatus)))) {
            throw rejected(
                    "INTAKE_PARTY_STATE_OUTCOME_CONFLICT",
                    "party Intake readiness, handoff, and admission disagree");
        }
        boolean canonicalRemarkState = switch (remarkStatus) {
            case "NOT_READY", "READY_PENDING_REMARK_INVITE", "WAITING_FOR_REMARK" ->
                latestRemark.isEmpty() && remarks.isEmpty();
            case "HAS_REMARKS" -> !latestRemark.isBlank()
                    && !remarks.isEmpty()
                    && latestRemark.equals(remarks.get(remarks.size() - 1).path("text").asText());
            case "NO_EXTRA_REMARKS" -> "无额外备注。".equals(latestRemark) && remarks.isEmpty();
            default -> false;
        };
        if (!canonicalRemarkState) {
            throw rejected(
                    "INTAKE_PARTY_STATE_HANDOFF_INVALID",
                    "party Intake remark status does not match its canonical payload");
        }
    }

    private static void requireExactObjectFields(
            JsonNode value,
            Set<String> expected,
            String code,
            String message) {
        if (!value.isObject()) {
            throw rejected(code, message);
        }
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw rejected(code, message);
        }
    }

    private static ObjectNode defaultPartyIntakeEntry() {
        ObjectNode entry = JsonNodeFactory.instance.objectNode();
        ObjectNode quality = entry.putObject("intake_quality");
        quality.put("score", 0);
        quality.put("threshold", 85);
        quality.put("ready_for_next_step", false);
        ObjectNode breakdown = quality.putObject("score_breakdown");
        QUALITY_COMPONENT_MAXIMA.keySet().forEach(component -> breakdown.put(component, 0));
        quality.put("improvement_reason", "等待当前参与方补充案情。");
        ObjectNode missing = entry.putObject("missing_information");
        missing.putArray("blocking_gaps");
        missing.putArray("nice_to_have_gaps");
        missing.putArray("next_questions");
        ObjectNode handoff = entry.putObject("handoff_notes");
        handoff.put("remark_status", "NOT_READY");
        handoff.put("phase_source_message_id", "");
        handoff.put("latest_remark", "");
        handoff.putArray("remarks");
        handoff.put("instruction", "当前参与方案情达到阈值后，接待官会询问交接备注。");
        ObjectNode admission = entry.putObject("admission");
        admission.put("recommendation", "NEED_MORE_INFO");
        admission.put("reasoning", "");
        admission.put("confidence", 0);
        return entry;
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
            ClaimResolutionAuthority claimResolutionAuthority,
            SourceType sourceType) {

        public MatrixAuthority(
                String caseId,
                ActorRole actorRole,
                ActorRole initiatorRole,
                ActorRole respondentRole,
                String sourceRef,
                String sourceContextHash,
                ClaimResolutionAuthority claimResolutionAuthority) {
            this(
                    caseId,
                    actorRole,
                    initiatorRole,
                    respondentRole,
                    sourceRef,
                    sourceContextHash,
                    claimResolutionAuthority,
                    null);
        }

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
                    null,
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
