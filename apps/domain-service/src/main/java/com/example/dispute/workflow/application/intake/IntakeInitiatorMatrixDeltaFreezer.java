package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.application.intake.IntakeDossierProjectionMerger.MatrixAuthority;
import com.example.dispute.workflow.application.intake.IntakeRespondentMatrixDeltaPolicy.DeltaRow;
import com.example.dispute.workflow.application.intake.IntakeRespondentMatrixDeltaPolicy.ValidatedDelta;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reduces an initiator-scoped semantic delta into Java-owned {@code INITIATOR_FROZEN} authority.
 *
 * <p>The shared delta parser deliberately accepts the respondent union member. This reducer narrows
 * that input back to the initiator-safe subset before deriving any formal fields.
 */
public final class IntakeInitiatorMatrixDeltaFreezer {

    private static final Set<ActorRole> PARTY_ROLES = Set.of(ActorRole.USER, ActorRole.MERCHANT);
    private static final Set<String> INITIATOR_STANCES =
            Set.of("CONFIRM", "DENY", "PARTIAL", "UNKNOWN");
    private static final Set<String> CLAIM_ATTITUDES = Set.of(
            "AGREE",
            "PARTIALLY_AGREE",
            "DISAGREE",
            "ALTERNATIVE_PROPOSED",
            "NEED_MORE_INFO",
            "NOT_ADDRESSED");
    private static final Set<String> NO_RESPONSE_ATTITUDES =
            Set.of("UNKNOWN", "PLATFORM_UNKNOWN", "NOT_RESPONDED", "NOT_ADDRESSED");
    private static final String SUBJECTIVE_RESPONDENT_SOURCE = "发起方单方陈述（主观）";
    private static final String NO_DIRECT_OPPONENT_POSITION = "该方尚未直接陈述。";

    private final IntakeInitiatorMatrixFreezer formalValidator =
            new IntakeInitiatorMatrixFreezer();
    private final IntakeRespondentMatrixDeltaPolicy deltaPolicy =
            new IntakeRespondentMatrixDeltaPolicy();

    /**
     * Freezes one initiator delta against the already merged dossier and, where present, its formal
     * initiator parent.
     */
    public ObjectNode freeze(
            ObjectNode mergedDossier, JsonNode deltaCandidate, MatrixAuthority authority) {
        Objects.requireNonNull(mergedDossier, "mergedDossier");
        Objects.requireNonNull(authority, "authority");
        requireInitiatorAuthority(authority);
        if (deltaCandidate == null || !deltaCandidate.isObject()) {
            throw rejected("INTAKE_INITIATOR_MATRIX_DELTA_INVALID", "matrix delta is not an object");
        }
        ObjectNode rawDelta = (ObjectNode) deltaCandidate;
        if (rawDelta.hasNonNull("respondent_claim")) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_RESPONDENT_CLAIM_FORBIDDEN",
                    "initiator matrix deltas cannot carry respondent_claim");
        }
        ValidatedDelta delta = deltaPolicy.validate(rawDelta);
        requireInitiatorSafeDelta(delta);

        ObjectNode parent = formalParent(mergedDossier, authority);
        if (parent == null) {
            requireOpeningDelta(delta);
        }
        return derive(mergedDossier, parent, delta, authority);
    }

    private ObjectNode formalParent(ObjectNode dossier, MatrixAuthority authority) {
        JsonNode formal = dossier.get("case_fact_matrix");
        if (formal == null || formal.isNull()) {
            if (dossier.has("unilateral_case_matrix")) {
                throw rejected(
                        "INTAKE_INITIATOR_MATRIX_PARENT_INVALID",
                        "initiator delta cannot reset deployed unilateral matrix lineage");
            }
            return null;
        }
        if (!formal.isObject()) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_PARENT_INVALID",
                    "persisted formal matrix is not an object");
        }
        ObjectNode parent = (ObjectNode) formal;
        formalValidator.validateFrozen(
                parent,
                authority.caseId(),
                authority.initiatorRole(),
                authority.respondentRole());
        return parent;
    }

    private static void requireInitiatorSafeDelta(ValidatedDelta delta) {
        for (DeltaRow row : delta.rows()) {
            if (!INITIATOR_STANCES.contains(row.stance())
                    && !"NOT_ADDRESSED".equals(row.stance())) {
                throw rejected(
                        "INTAKE_INITIATOR_MATRIX_DELTA_INVALID",
                        "initiator matrix delta stance is invalid");
            }
        }
    }

    private static void requireOpeningDelta(ValidatedDelta delta) {
        for (DeltaRow row : delta.rows()) {
            if (!row.factKey().startsWith("NEW_")
                    || !"CURRENT_SOURCE".equals(row.sourceScope())
                    || "NOT_ADDRESSED".equals(row.stance())) {
                throw rejected(
                        "INTAKE_INITIATOR_MATRIX_OPENING_INVALID",
                        "an opening initiator matrix delta must contain current-source NEW facts");
            }
        }
    }

    private ObjectNode derive(
            ObjectNode dossier,
            ObjectNode parent,
            ValidatedDelta delta,
            MatrixAuthority authority) {
        ParentIndex parentIndex = ParentIndex.from(parent);
        Map<String, String> resolvedKeys = new LinkedHashMap<>();
        Set<String> resolvedIds = new HashSet<>();
        Set<String> carriedParentIds = new LinkedHashSet<>();
        ArrayNode rows = JsonNodeFactory.instance.arrayNode();

        for (DeltaRow item : delta.rows()) {
            ResolvedFact resolved = resolve(item, parentIndex, authority);
            ObjectNode prior = resolved.prior();
            String factId = resolved.factId();
            if (prior != null) {
                requireStableBinding(prior, item);
                if (!carriedParentIds.add(factId)) {
                    throw rejected(
                            "INTAKE_INITIATOR_MATRIX_FACT_DUPLICATE",
                            "initiator matrix delta carries a prior fact more than once");
                }
            }
            if (!resolvedIds.add(factId)) {
                throw rejected(
                        "INTAKE_INITIATOR_MATRIX_FACT_DUPLICATE",
                        "initiator matrix delta resolves more than one row to the same fact");
            }
            resolvedKeys.put(item.factKey(), factId);
            rows.add(deriveRow(prior, factId, item, authority));
        }

        if (!carriedParentIds.equals(parentIndex.byId().keySet())) {
            Set<String> missing = new LinkedHashSet<>(parentIndex.byId().keySet());
            missing.removeAll(carriedParentIds);
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_PRIOR_FACT_MISSING",
                    "initiator matrix delta must carry every prior fact exactly once: " + missing);
        }

        ArrayNode summaryFactIds = summaryFactIds(delta, resolvedKeys);
        List<String> sourceRefs = sourceRefs(parent, authority.sourceRef());
        ObjectNode matrix = JsonNodeFactory.instance.objectNode();
        matrix.put("schema_version", "case_fact_matrix.v2");
        matrix.put("case_id", authority.caseId());
        matrix.put("matrix_version", nextVersion(parent));
        matrix.put("matrix_kind", "INITIATOR_FROZEN");
        if (parent == null) {
            matrix.putNull("parent_ref");
        } else {
            matrix.set("parent_ref", parentRef(parent));
        }
        matrix.set("party_map", partyMap(authority));
        matrix.set("source_refs", textArray(sourceRefs));
        matrix.set("case_overview", caseOverview(dossier, summaryFactIds));
        matrix.set("claims", claims(dossier, parent, authority));
        matrix.set("fact_rows", rows);
        matrix.putArray("fact_relationships");
        matrix.set("generation_ref", generationRef(authority));
        matrix.set("fact_indexes", factIndexes(rows));
        matrix.put("matrix_id", matrixId(matrix));
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));
        validateDerived(matrix, parent, authority, sourceRefs);
        return matrix;
    }

    private static ResolvedFact resolve(
            DeltaRow item, ParentIndex parentIndex, MatrixAuthority authority) {
        if (item.factKey().startsWith("FACT_")) {
            ObjectNode prior = parentIndex.byId().get(item.factKey());
            if (prior == null) {
                prior = parentIndex.byFingerprint().get(fingerprint(item.category(), item.factTarget()));
                if (prior == null) {
                    throw rejected(
                            "INTAKE_INITIATOR_MATRIX_FACT_UNKNOWN",
                            "initiator matrix delta references an unknown formal fact");
                }
            }
            return new ResolvedFact(prior.path("fact_id").asText(), prior);
        }

        ObjectNode prior = parentIndex.byFingerprint().get(fingerprint(item.category(), item.factTarget()));
        if (prior != null) {
            return new ResolvedFact(prior.path("fact_id").asText(), prior);
        }
        String factId = stableFactId(authority.caseId(), item.category(), item.factTarget());
        if (parentIndex.byId().containsKey(factId)) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_NEW_FACT_COLLISION",
                    "a proposal-local new fact collides with formal authority");
        }
        return new ResolvedFact(factId, null);
    }

    private static ArrayNode summaryFactIds(
            ValidatedDelta delta, Map<String, String> resolvedKeys) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String key : delta.summaryKeys()) {
            String factId = resolvedKeys.get(key);
            if (factId == null) {
                throw rejected(
                        "INTAKE_INITIATOR_MATRIX_SUMMARY_INVALID",
                        "initiator matrix summary references an unresolved fact");
            }
            ids.add(factId);
        }
        return textArray(ids);
    }

    private static ObjectNode deriveRow(
            ObjectNode prior, String factId, DeltaRow item, MatrixAuthority authority) {
        if ("NOT_ADDRESSED".equals(item.stance()) && prior == null) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_SOURCE_SCOPE_INVALID",
                    "NOT_ADDRESSED can only preserve a prior initiator position");
        }
        ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("fact_id", factId);
        row.set(
                "category",
                prior == null
                        ? JsonNodeFactory.instance.textNode(item.category())
                        : prior.required("category").deepCopy());
        row.set(
                "fact_target",
                prior == null
                        ? JsonNodeFactory.instance.textNode(item.factTarget())
                        : prior.required("fact_target").deepCopy());
        row.set(
                "materiality",
                prior == null
                        ? JsonNodeFactory.instance.textNode(item.materiality())
                        : prior.required("materiality").deepCopy());
        row.set("origin", origin(prior, item.sourceScope(), authority.sourceRef()));
        ObjectNode positions = row.putObject("positions");
        positions.set(
                authority.initiatorRole().name(),
                initiatorPosition(prior, item, authority.initiatorRole(), authority.sourceRef()));
        positions.set(
                authority.respondentRole().name(),
                oppositePosition(prior, authority.respondentRole()));
        ObjectNode alignment = row.putObject("party_alignment");
        alignment.put("status", "NOT_COMPUTED");
        alignment.putNull("agreed_statement");
        alignment.putNull("conflict_summary");
        row.putNull("requires_resolution");
        row.put("truth_status", "NOT_EVALUATED");
        row.put("evidence_coverage_status", "PENDING_EVIDENCE_REVIEW");
        return row;
    }

    private static ObjectNode origin(ObjectNode prior, String sourceScope, String currentSource) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        if (prior == null) {
            result.put("introduced_stage", "INITIATOR_INTAKE");
        } else {
            ObjectNode priorOrigin = (ObjectNode) prior.required("origin");
            result.set("introduced_stage", priorOrigin.required("introduced_stage").deepCopy());
            addTextValues(refs, priorOrigin.required("source_refs"), "prior fact origin");
        }
        if (usesCurrentSource(sourceScope)) {
            refs.add(currentSource);
        }
        requireSourceCount(refs, "fact origin");
        result.set("source_refs", textArray(refs));
        return result;
    }

    private static ObjectNode initiatorPosition(
            ObjectNode prior, DeltaRow item, ActorRole initiatorRole, String currentSource) {
        if ("NOT_ADDRESSED".equals(item.stance())) {
            if (prior == null || !"PREVIOUS_MATRIX".equals(item.sourceScope())) {
                throw rejected(
                        "INTAKE_INITIATOR_MATRIX_SOURCE_SCOPE_INVALID",
                        "NOT_ADDRESSED can only preserve a prior initiator position");
            }
            return ((ObjectNode) prior.required("positions"))
                    .required(initiatorRole.name())
                    .deepCopy();
        }

        LinkedHashSet<String> refs = new LinkedHashSet<>();
        if (prior != null) {
            addTextValues(
                    refs,
                    prior.required("positions")
                            .required(initiatorRole.name())
                            .required("source_refs"),
                    "prior initiator position");
        }
        if (usesCurrentSource(item.sourceScope())) {
            refs.add(currentSource);
        }
        requireSourceCount(refs, "initiator position");
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("stance", item.stance());
        result.put("position_summary", item.positionSummary());
        if (item.assertedValue() == null) {
            result.putNull("asserted_value");
        } else {
            result.put("asserted_value", item.assertedValue());
        }
        result.put("source_type", "DIRECT_PARTY_STATEMENT");
        result.set("source_refs", textArray(refs));
        return result;
    }

    private static ObjectNode oppositePosition(ObjectNode prior, ActorRole respondentRole) {
        if (prior != null) {
            return ((ObjectNode) prior.required("positions"))
                    .required(respondentRole.name())
                    .deepCopy();
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("stance", "NOT_ADDRESSED");
        result.put("position_summary", NO_DIRECT_OPPONENT_POSITION);
        result.putNull("asserted_value");
        result.put("source_type", "NO_DIRECT_POSITION");
        result.putArray("source_refs");
        return result;
    }

    private static ObjectNode partyMap(MatrixAuthority authority) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("initiator_role", authority.initiatorRole().name());
        result.put("respondent_role", authority.respondentRole().name());
        return result;
    }

    private static ObjectNode generationRef(MatrixAuthority authority) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("actor_role", authority.initiatorRole().name());
        result.put("source_stage", "INITIATOR_INTAKE");
        result.put("latest_source_ref", authority.sourceRef());
        result.put("source_context_hash", authority.sourceContextHash());
        return result;
    }

    private static ObjectNode caseOverview(ObjectNode dossier, ArrayNode summaryFactIds) {
        String summary = requireDossierText(
                firstText(dossier.path("case_story"), "one_sentence_summary", "summary"),
                20_000,
                "case summary");
        String coreConflict = firstNonNull(
                firstText(dossier.path("dispute_core_state"), "core_conflict", "core_issue"),
                firstText(dossier.path("dispute_focus"), "core_conflict", "core_issue"),
                summary);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("neutral_summary", summary);
        result.put(
                "core_conflict",
                requireDossierText(coreConflict, 20_000, "dispute core conflict"));
        result.set("summary_source_fact_ids", summaryFactIds);
        return result;
    }

    private static ObjectNode claims(
            ObjectNode dossier, ObjectNode parent, MatrixAuthority authority) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("initiator_claim", initiatorClaim(dossier, parent, authority));
        ObjectNode reported = reportedRespondentAttitude(dossier, parent, authority);
        if (reported == null) {
            result.putNull("respondent_reported_by_initiator");
        } else {
            result.set("respondent_reported_by_initiator", reported);
        }
        result.putNull("respondent_direct");
        result.putNull("claim_conflict");
        return result;
    }

    private static ObjectNode initiatorClaim(
            ObjectNode dossier, ObjectNode parent, MatrixAuthority authority) {
        JsonNode claim = dossier.path("claim_resolution");
        JsonNode requested = dossier.path("requested_resolution");
        if ((!claim.isMissingNode() && !claim.isObject() && !claim.isNull())
                || (!requested.isMissingNode() && !requested.isObject() && !requested.isNull())) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_DOSSIER_INVALID",
                    "initiator claim dossier branch is invalid");
        }
        if (!claim.isObject() && !requested.isObject()) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_DOSSIER_INCOMPLETE",
                    "initiator matrix requires a trusted claim resolution");
        }
        String suppliedRole = firstText(claim, "initiator_role");
        if (suppliedRole != null && !authority.initiatorRole().name().equals(suppliedRole)) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID",
                    "dossier claim initiator role conflicts with Java authority");
        }
        String summary = requireDossierText(
                firstText(dossier.path("case_story"), "one_sentence_summary", "summary"),
                20_000,
                "case summary");
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("initiator_role", authority.initiatorRole().name());
        result.put(
                "requested_resolution",
                requireIdentifier(
                        firstNonNull(
                                firstText(claim, "requested_resolution"),
                                firstText(requested, "requested_resolution", "kind")),
                        "requested resolution"));
        JsonNode amount = firstNonNullValue(claim, "requested_amount", requested);
        if (amount != null) {
            if (!amount.isNumber() || amount.decimalValue().signum() < 0) {
                throw rejected(
                        "INTAKE_INITIATOR_MATRIX_DOSSIER_INVALID",
                        "initiator requested amount is invalid");
            }
            result.set("requested_amount", amount.deepCopy());
        }
        String items = firstNonNull(
                firstText(claim, "requested_items"), firstText(requested, "requested_items"));
        if (items != null) {
            result.put("requested_items", requireDossierText(items, 2_000, "requested items"));
        }
        result.put(
                "reason_summary",
                requireDossierText(
                        firstNonNull(
                                firstText(claim, "reason_summary", "request_reason"),
                                firstText(requested, "reason_summary", "reason"),
                                summary),
                        20_000,
                        "claim reason summary"));
        result.put(
                "position_summary",
                requireDossierText(
                        firstNonNull(firstText(claim, "position_summary", "normalized_statement"), summary),
                        20_000,
                        "claim position summary"));
        ObjectNode priorClaim = parent == null
                ? null
                : (ObjectNode) parent.required("claims").required("initiator_claim");
        result.set("source_refs", claimSourceRefs(priorClaim, result, authority.sourceRef()));
        return result;
    }

    private static ObjectNode reportedRespondentAttitude(
            ObjectNode dossier, ObjectNode parent, MatrixAuthority authority) {
        ObjectNode prior = parent == null || !parent.at("/claims/respondent_reported_by_initiator").isObject()
                ? null
                : (ObjectNode) parent.at("/claims/respondent_reported_by_initiator");
        JsonNode attitude = dossier.path("respondent_attitude");
        if (attitude.isMissingNode() || attitude.isNull()) {
            return prior == null ? null : prior.deepCopy();
        }
        if (!attitude.isObject()) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_DOSSIER_INVALID",
                    "reported respondent dossier branch is invalid");
        }
        if (attitude.isEmpty()) {
            return prior == null ? null : prior.deepCopy();
        }
        String source = firstText(attitude, "source");
        String proposedAttitude = firstText(attitude, "attitude", "status");
        String position = firstText(attitude, "position", "summary", "position_summary", "note");
        if (!SUBJECTIVE_RESPONDENT_SOURCE.equals(source)
                || NO_RESPONSE_ATTITUDES.contains(proposedAttitude)
                || position == null) {
            return prior == null ? null : prior.deepCopy();
        }
        String suppliedRole = firstText(attitude, "respondent_role");
        if (suppliedRole != null && !authority.respondentRole().name().equals(suppliedRole)) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID",
                    "reported respondent role conflicts with Java authority");
        }
        if (proposedAttitude == null || !CLAIM_ATTITUDES.contains(proposedAttitude)) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_DOSSIER_INVALID",
                    "reported respondent attitude is invalid");
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("respondent_role", authority.respondentRole().name());
        result.put("attitude", proposedAttitude);
        result.put(
                "position_summary",
                requireDossierText(position, 20_000, "reported respondent position"));
        result.put("source_type", "INITIATOR_SUBJECTIVE_REPORT");
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        if (prior != null) {
            addTextValues(refs, prior.required("source_refs"), "prior reported respondent position");
        }
        refs.add(authority.sourceRef());
        requireSourceCount(refs, "reported respondent position");
        result.set("source_refs", textArray(refs));
        return result;
    }

    private static ArrayNode claimSourceRefs(
            ObjectNode priorClaim, ObjectNode material, String currentSource) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        if (priorClaim != null) {
            addTextValues(refs, priorClaim.required("source_refs"), "prior initiator claim");
            if (!claimMaterialEquals(priorClaim, material)) {
                refs.add(currentSource);
            }
        } else {
            refs.add(currentSource);
        }
        requireSourceCount(refs, "initiator claim");
        return textArray(refs);
    }

    private static boolean claimMaterialEquals(ObjectNode priorClaim, ObjectNode material) {
        ObjectNode priorMaterial = priorClaim.deepCopy();
        priorMaterial.remove("source_refs");
        return priorMaterial.equals(material);
    }

    private static ObjectNode parentRef(ObjectNode parent) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("matrix_id", parent.required("matrix_id").deepCopy());
        result.set("matrix_version", parent.required("matrix_version").deepCopy());
        result.set("content_hash", parent.required("content_hash").deepCopy());
        return result;
    }

    private static ObjectNode factIndexes(ArrayNode rows) {
        ObjectNode indexes = JsonNodeFactory.instance.objectNode();
        ArrayNode notComputed = indexes.putArray("not_computed_fact_ids");
        ArrayNode core = indexes.putArray("core_fact_ids");
        for (JsonNode row : rows) {
            String factId = row.path("fact_id").asText();
            notComputed.add(factId);
            if ("CORE".equals(row.path("materiality").asText())) {
                core.add(factId);
            }
        }
        for (String key : List.of(
                "agreed_fact_ids",
                "partially_agreed_fact_ids",
                "contested_fact_ids",
                "one_sided_fact_ids",
                "unresolved_fact_ids",
                "requires_resolution_fact_ids")) {
            indexes.putArray(key);
        }
        return indexes;
    }

    private void validateDerived(
            ObjectNode matrix,
            ObjectNode parent,
            MatrixAuthority authority,
            List<String> sourceRefs) {
        formalValidator.validateFrozen(
                matrix,
                authority.caseId(),
                authority.initiatorRole(),
                authority.respondentRole());
        ObjectNode idInput = matrix.deepCopy();
        idInput.remove("matrix_id");
        idInput.remove("content_hash");
        if (!matrixId(idInput).equals(matrix.path("matrix_id").asText())
                || !sourceRefs.equals(textValues(matrix.path("source_refs")))
                || !authority.initiatorRole().name().equals(
                        matrix.at("/party_map/initiator_role").asText())
                || !authority.respondentRole().name().equals(
                        matrix.at("/party_map/respondent_role").asText())
                || !authority.initiatorRole().name().equals(
                        matrix.at("/generation_ref/actor_role").asText())
                || !"INITIATOR_INTAKE".equals(
                        matrix.at("/generation_ref/source_stage").asText())
                || !authority.sourceRef().equals(
                        matrix.at("/generation_ref/latest_source_ref").asText())
                || !authority.sourceContextHash().equals(
                        matrix.at("/generation_ref/source_context_hash").asText())) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_DERIVATION_INVALID",
                    "initiator matrix does not match Java authority");
        }
        if (parent == null) {
            if (matrix.path("matrix_version").asLong() != 1 || !matrix.path("parent_ref").isNull()) {
                throw rejected(
                        "INTAKE_INITIATOR_MATRIX_PARENT_INVALID",
                        "initial initiator matrix must begin at version one");
            }
            return;
        }
        if (matrix.path("matrix_version").asLong() != parent.path("matrix_version").asLong() + 1
                || !matrix.at("/parent_ref/matrix_id").equals(parent.path("matrix_id"))
                || !matrix.at("/parent_ref/matrix_version").equals(parent.path("matrix_version"))
                || !matrix.at("/parent_ref/content_hash").equals(parent.path("content_hash"))) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_PARENT_INVALID",
                    "initiator matrix parent reference does not match formal authority");
        }
    }

    private static List<String> sourceRefs(ObjectNode parent, String currentSource) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        if (parent != null) {
            addTextValues(refs, parent.required("source_refs"), "formal parent source refs");
        }
        refs.add(currentSource);
        if (refs.size() > 256) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_SOURCE_LIMIT",
                    "initiator matrix source authority exceeds the formal limit");
        }
        return List.copyOf(refs);
    }

    private static long nextVersion(ObjectNode parent) {
        if (parent == null) {
            return 1;
        }
        long version = parent.path("matrix_version").longValue();
        if (version == Long.MAX_VALUE) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_VERSION_INVALID",
                    "initiator matrix version cannot advance safely");
        }
        return version + 1;
    }

    private static void requireStableBinding(ObjectNode prior, DeltaRow item) {
        if (!item.category().equals(prior.path("category").asText())
                || !item.materiality().equals(prior.path("materiality").asText())
                || !fingerprint(item.category(), item.factTarget())
                        .equals(fingerprint(
                                prior.path("category").asText(),
                                prior.path("fact_target").asText()))) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_FACT_REBOUND",
                    "initiator matrix delta changes a formal fact binding");
        }
    }

    private static void requireInitiatorAuthority(MatrixAuthority authority) {
        if (authority.actorRole() != authority.initiatorRole()
                || !PARTY_ROLES.contains(authority.initiatorRole())
                || !PARTY_ROLES.contains(authority.respondentRole())) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID",
                    "matrix delta requires the Java-authorized initiator actor");
        }
    }

    private static boolean usesCurrentSource(String sourceScope) {
        return "CURRENT_SOURCE".equals(sourceScope)
                || "PREVIOUS_AND_CURRENT_SOURCE".equals(sourceScope);
    }

    private static void addTextValues(Set<String> target, JsonNode values, String label) {
        if (!values.isArray()) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_PARENT_INVALID", label + " must be an array");
        }
        for (JsonNode value : values) {
            if (!value.isTextual()
                    || value.textValue().isBlank()
                    || !value.textValue().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw rejected(
                        "INTAKE_INITIATOR_MATRIX_PARENT_INVALID", label + " contains an invalid source");
            }
            target.add(value.textValue());
        }
    }

    private static void requireSourceCount(Set<String> refs, String label) {
        if (refs.isEmpty() || refs.size() > 50) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_SOURCE_LIMIT",
                    label + " source authority exceeds the formal limit");
        }
    }

    private static ArrayNode textArray(Iterable<String> values) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        values.forEach(result::add);
        return result;
    }

    private static List<String> textValues(JsonNode values) {
        List<String> result = new ArrayList<>();
        if (!values.isArray()) {
            return List.of();
        }
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private static String matrixId(ObjectNode formalMaterial) {
        return "CASE_MATRIX_"
                + ContractJson.sha256Hex(formalMaterial).substring(0, 20).toUpperCase(Locale.ROOT);
    }

    private static String stableFactId(String caseId, String category, String target) {
        ObjectNode binding = JsonNodeFactory.instance.objectNode();
        binding.put("case_id", caseId);
        binding.put("category", category);
        binding.put("fact_target", normalizeFactTarget(target));
        return "FACT_"
                + ContractJson.sha256Hex(binding).substring(0, 24).toUpperCase(Locale.ROOT);
    }

    private static String fingerprint(String category, String target) {
        ObjectNode binding = JsonNodeFactory.instance.objectNode();
        binding.put("category", category);
        binding.put("fact_target", normalizeFactTarget(target));
        return ContractJson.sha256Hex(binding);
    }

    private static String normalizeFactTarget(String target) {
        return target.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String firstText(JsonNode owner, String... fields) {
        if (owner == null || !owner.isObject()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = owner.get(field);
            if (value != null && value.isTextual() && !value.textValue().isBlank()) {
                return value.textValue();
            }
        }
        return null;
    }

    private static JsonNode firstNonNullValue(JsonNode first, String field, JsonNode second) {
        JsonNode value = first.path(field);
        if (!value.isMissingNode() && !value.isNull()) {
            return value;
        }
        value = second.path(field);
        return value.isMissingNode() || value.isNull() ? null : value;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String requireIdentifier(String value, String label) {
        String result = requireDossierText(value, 128, label);
        if (!result.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_DOSSIER_INVALID", label + " is not an identifier");
        }
        return result;
    }

    private static String requireDossierText(String value, int maximum, String label) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_DOSSIER_INCOMPLETE", label + " is missing or invalid");
        }
        return value;
    }

    private static IntakeFinalizationRejectedException rejected(String code, String message) {
        return new IntakeFinalizationRejectedException(code, message);
    }

    private record ResolvedFact(String factId, ObjectNode prior) {}

    private record ParentIndex(
            Map<String, ObjectNode> byId, Map<String, ObjectNode> byFingerprint) {

        private static ParentIndex from(ObjectNode parent) {
            if (parent == null) {
                return new ParentIndex(Map.of(), Map.of());
            }
            Map<String, ObjectNode> rows = new LinkedHashMap<>();
            Map<String, ObjectNode> fingerprints = new LinkedHashMap<>();
            for (JsonNode candidate : parent.withArray("fact_rows")) {
                ObjectNode row = (ObjectNode) candidate;
                String factId = row.path("fact_id").asText();
                if (rows.putIfAbsent(factId, row) != null) {
                    throw rejected(
                            "INTAKE_INITIATOR_MATRIX_PARENT_INVALID",
                            "formal parent contains duplicate fact identifiers");
                }
                String fingerprint = fingerprint(
                        row.path("category").asText(), row.path("fact_target").asText());
                if (fingerprints.putIfAbsent(fingerprint, row) != null) {
                    throw rejected(
                            "INTAKE_INITIATOR_MATRIX_PARENT_INVALID",
                            "formal parent contains duplicate semantic facts");
                }
            }
            return new ParentIndex(Map.copyOf(rows), Map.copyOf(fingerprints));
        }
    }
}
