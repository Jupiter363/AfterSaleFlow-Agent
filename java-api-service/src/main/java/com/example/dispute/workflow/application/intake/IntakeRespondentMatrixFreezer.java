package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.application.intake.IntakeDossierProjectionMerger.MatrixAuthority;
import com.example.dispute.workflow.application.intake.IntakeRespondentMatrixDeltaPolicy.DeltaRow;
import com.example.dispute.workflow.application.intake.IntakeRespondentMatrixDeltaPolicy.RespondentClaim;
import com.example.dispute.workflow.application.intake.IntakeRespondentMatrixDeltaPolicy.ValidatedDelta;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Derives a Java-owned bilateral candidate from one canonical initiator matrix and delta. */
public final class IntakeRespondentMatrixFreezer {

    private static final Set<String> MATRIX_FIELDS = Set.of(
            "schema_version",
            "case_id",
            "matrix_id",
            "matrix_version",
            "matrix_kind",
            "parent_ref",
            "content_hash",
            "party_map",
            "source_refs",
            "case_overview",
            "claims",
            "fact_rows",
            "fact_relationships",
            "generation_ref",
            "fact_indexes");
    private static final Set<String> PARTY_MAP_FIELDS =
            Set.of("initiator_role", "respondent_role");
    private static final Set<String> PARENT_FIELDS =
            Set.of("matrix_id", "matrix_version", "content_hash");
    private static final Set<String> OVERVIEW_FIELDS =
            Set.of("neutral_summary", "core_conflict", "summary_source_fact_ids");
    private static final Set<String> CLAIM_FIELDS = Set.of(
            "initiator_claim",
            "respondent_reported_by_initiator",
            "respondent_direct",
            "claim_conflict");
    private static final Set<String> INITIATOR_CLAIM_REQUIRED_FIELDS = Set.of(
            "initiator_role",
            "requested_resolution",
            "reason_summary",
            "position_summary",
            "source_refs");
    private static final Set<String> INITIATOR_CLAIM_ALLOWED_FIELDS = Set.of(
            "initiator_role",
            "requested_resolution",
            "requested_amount",
            "requested_items",
            "reason_summary",
            "position_summary",
            "source_refs");
    private static final Set<String> REPORTED_RESPONDENT_FIELDS = Set.of(
            "respondent_role", "attitude", "position_summary", "source_type", "source_refs");
    private static final Set<String> RESPONDENT_DIRECT_FIELDS = Set.of(
            "respondent_role",
            "attitude",
            "position_summary",
            "alternative_proposal",
            "source_type",
            "source_refs");
    private static final Set<String> ROW_FIELDS = Set.of(
            "fact_id",
            "category",
            "fact_target",
            "materiality",
            "origin",
            "positions",
            "party_alignment",
            "requires_resolution",
            "truth_status",
            "evidence_coverage_status");
    private static final Set<String> ORIGIN_FIELDS = Set.of("introduced_stage", "source_refs");
    private static final Set<String> POSITION_FIELDS = Set.of(
            "stance", "position_summary", "asserted_value", "source_type", "source_refs");
    private static final Set<String> ALIGNMENT_FIELDS =
            Set.of("status", "agreed_statement", "conflict_summary");
    private static final Set<String> GENERATION_FIELDS =
            Set.of("actor_role", "source_stage", "latest_source_ref", "source_context_hash");
    private static final Set<String> INDEX_FIELDS = Set.of(
            "not_computed_fact_ids",
            "agreed_fact_ids",
            "partially_agreed_fact_ids",
            "contested_fact_ids",
            "one_sided_fact_ids",
            "unresolved_fact_ids",
            "core_fact_ids",
            "requires_resolution_fact_ids");
    private static final Set<String> CATEGORIES = Set.of(
            "ORDER",
            "PRODUCT_PAGE",
            "PAYMENT",
            "FULFILLMENT",
            "LOGISTICS",
            "PRODUCT_STATE",
            "COMMUNICATION",
            "AFTER_SALES",
            "TIME",
            "OTHER");
    private static final Set<String> MATERIALITIES = Set.of("CORE", "SUPPORTING", "CONTEXT");
    private static final Set<String> CLAIM_ATTITUDES = Set.of(
            "AGREE",
            "PARTIALLY_AGREE",
            "DISAGREE",
            "ALTERNATIVE_PROPOSED",
            "NEED_MORE_INFO",
            "NOT_ADDRESSED");
    private static final Set<String> BILATERAL_ALIGNMENT_STATUSES = Set.of(
            "AGREED", "PARTIALLY_AGREED", "CONTESTED", "ONE_SIDED", "UNRESOLVED");
    private static final Set<String> SUBSTANTIVE_STANCES = Set.of("CONFIRM", "DENY", "PARTIAL");
    private static final Set<String> AGREEMENT_STANCES = Set.of("CONFIRM", "DENY");
    private static final Set<ActorRole> PARTY_ROLES = Set.of(ActorRole.USER, ActorRole.MERCHANT);
    private static final Pattern VALUE_SEPARATOR =
            Pattern.compile("[\\W_]", Pattern.UNICODE_CHARACTER_CLASS);
    private static final String NO_DIRECT_INITIATOR_POSITION =
            "No direct initiator position is recorded.";
    private static final String DEFAULT_CONFLICT_SUMMARY =
            "The parties have not reached a shared account of this fact.";

    private final IntakeInitiatorMatrixFreezer initiatorFreezer =
            new IntakeInitiatorMatrixFreezer();
    private final IntakeRespondentMatrixDeltaPolicy deltaPolicy =
            new IntakeRespondentMatrixDeltaPolicy();

    public ObjectNode deriveCandidate(
            ObjectNode initiatorMatrix, JsonNode deltaCandidate, MatrixAuthority authority) {
        Objects.requireNonNull(initiatorMatrix, "initiatorMatrix");
        Objects.requireNonNull(authority, "authority");
        requireRespondentAuthority(authority);
        validateParent(initiatorMatrix, authority);
        ValidatedDelta delta = deltaPolicy.validate(deltaCandidate);
        return derive(initiatorMatrix, delta, authority);
    }

    private void validateParent(ObjectNode parent, MatrixAuthority authority) {
        String kind = parent.path("matrix_kind").asText("");
        if ("INITIATOR_FROZEN".equals(kind)) {
            initiatorFreezer.validateFrozen(
                    parent,
                    authority.caseId(),
                    authority.initiatorRole(),
                    authority.respondentRole());
            requiredNextVersion(parent);
            return;
        }
        if ("BILATERAL_FROZEN".equals(kind)) {
            validateBilateralParent(parent, authority);
            requiredNextVersion(parent);
            return;
        }
        throw rejected(
                "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                "respondent matrix delta requires exact Java-owned frozen authority");
    }

    private static void validateBilateralParent(
            ObjectNode matrix, MatrixAuthority authority) {
        requireExactFields(matrix, MATRIX_FIELDS, "bilateral matrix");
        long version = requiredPositiveLong(matrix, "matrix_version");
        String matrixId = requiredIdentifier(matrix, "matrix_id");
        if (!"case_fact_matrix.v2".equals(requiredText(matrix, "schema_version", 64))
                || !authority.caseId().equals(requiredIdentifier(matrix, "case_id"))
                || !"BILATERAL_FROZEN".equals(requiredText(matrix, "matrix_kind", 64))
                || version < 2
                || !matrixId.matches("CASE_MATRIX_[A-F0-9]{20}")) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "bilateral matrix does not match current Java case authority");
        }

        ObjectNode hashInput = matrix.deepCopy();
        String storedHash = requiredHash(hashInput, "content_hash");
        hashInput.remove("content_hash");
        if (!storedHash.equals(ContractJson.sha256Hex(hashInput))) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_HASH_INVALID",
                    "bilateral matrix content hash is not canonical");
        }
        ObjectNode idInput = hashInput.deepCopy();
        idInput.remove("matrix_id");
        String expectedMatrixId = "CASE_MATRIX_"
                + ContractJson.sha256Hex(idInput).substring(0, 20).toUpperCase(Locale.ROOT);
        if (!matrixId.equals(expectedMatrixId)) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "bilateral matrix id does not bind its canonical authority");
        }

        ObjectNode parentRef = requiredObject(matrix, "parent_ref", "bilateral parent ref");
        requireExactFields(parentRef, PARENT_FIELDS, "bilateral parent ref");
        long parentVersion = requiredPositiveLong(parentRef, "matrix_version");
        if (parentVersion == Long.MAX_VALUE
                || parentVersion + 1 != version
                || !requiredIdentifier(parentRef, "matrix_id")
                        .matches("CASE_MATRIX_[A-F0-9]{20}")) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "bilateral matrix parent lineage is invalid");
        }
        requiredHash(parentRef, "content_hash");

        ObjectNode partyMap = requiredObject(matrix, "party_map", "bilateral party map");
        requireExactFields(partyMap, PARTY_MAP_FIELDS, "bilateral party map");
        if (!authority.initiatorRole().name().equals(requiredText(partyMap, "initiator_role", 32))
                || !authority.respondentRole().name()
                        .equals(requiredText(partyMap, "respondent_role", 32))
                || authority.initiatorRole() == authority.respondentRole()) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_AUTHORITY_INVALID",
                    "bilateral matrix party map conflicts with Java authority");
        }

        List<String> sourceRefs = requiredTextArray(
                matrix, "source_refs", 1, 256, 128, "bilateral source refs");
        Set<String> declaredSources = new LinkedHashSet<>(sourceRefs);
        ObjectNode generation =
                requiredObject(matrix, "generation_ref", "bilateral generation ref");
        requireExactFields(generation, GENERATION_FIELDS, "bilateral generation ref");
        String latestSource = requiredIdentifier(generation, "latest_source_ref");
        if (!authority.respondentRole().name()
                        .equals(requiredText(generation, "actor_role", 32))
                || !"RESPONDENT_INTAKE".equals(
                        requiredText(generation, "source_stage", 64))
                || !declaredSources.contains(latestSource)
                || !latestSource.equals(sourceRefs.get(sourceRefs.size() - 1))) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_SOURCE_SCOPE_INVALID",
                    "bilateral matrix generation is outside respondent source authority");
        }
        requiredHash(generation, "source_context_hash");

        JsonNode relationships = matrix.path("fact_relationships");
        if (!relationships.isArray() || !relationships.isEmpty()) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "bilateral matrix fact relationships are invalid");
        }

        ObjectNode overview = requiredObject(matrix, "case_overview", "bilateral overview");
        requireExactFields(overview, OVERVIEW_FIELDS, "bilateral overview");
        requiredText(overview, "neutral_summary", 20_000);
        String coreConflict = requiredText(overview, "core_conflict", 20_000);
        validateBilateralClaims(
                matrix, authority, declaredSources, latestSource, coreConflict);

        JsonNode rowsNode = matrix.path("fact_rows");
        if (!rowsNode.isArray() || rowsNode.isEmpty() || rowsNode.size() > 200) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "bilateral matrix fact rows are invalid");
        }
        List<String> ids = new ArrayList<>();
        Map<String, List<String>> byStatus = new LinkedHashMap<>();
        for (String status : BILATERAL_ALIGNMENT_STATUSES) {
            byStatus.put(status, new ArrayList<>());
        }
        List<String> coreIds = new ArrayList<>();
        List<String> requiresResolutionIds = new ArrayList<>();
        for (JsonNode row : rowsNode) {
            validateBilateralRow(
                    row,
                    authority,
                    declaredSources,
                    ids,
                    byStatus,
                    coreIds,
                    requiresResolutionIds);
        }
        validateSummaryIds(overview, ids);
        validateBilateralIndexes(matrix, byStatus, coreIds, requiresResolutionIds);
    }

    public void requireCompleteForFreeze(ObjectNode bilateralCandidate, MatrixAuthority authority) {
        Objects.requireNonNull(bilateralCandidate, "bilateralCandidate");
        Objects.requireNonNull(authority, "authority");
        requireRespondentAuthority(authority);
        if (!"case_fact_matrix.v2".equals(
                        bilateralCandidate.path("schema_version").asText())
                || !"BILATERAL_FROZEN".equals(
                        bilateralCandidate.path("matrix_kind").asText())
                || !authority.caseId().equals(bilateralCandidate.path("case_id").asText())) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_NOT_READY",
                    "respondent matrix candidate is not Java-owned bilateral authority");
        }
        JsonNode rows = bilateralCandidate.path("fact_rows");
        if (!rows.isArray() || rows.isEmpty()) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_NOT_READY",
                    "respondent matrix candidate has no complete fact rows");
        }
        for (JsonNode row : rows) {
            JsonNode respondent =
                    row.path("positions").path(authority.respondentRole().name());
            if ("UNKNOWN".equals(respondent.path("stance").asText())
                    || "NOT_COMPUTED".equals(
                            row.path("party_alignment").path("status").asText())
                    || !row.path("requires_resolution").isBoolean()) {
                throw rejected(
                        "INTAKE_RESPONDENT_MATRIX_NOT_READY",
                        "READY_TO_CONFIRM cannot freeze an unresolved respondent placeholder");
            }
        }
    }

    private static ObjectNode derive(
            ObjectNode parent, ValidatedDelta delta, MatrixAuthority authority) {
        ParentIndex parentIndex = ParentIndex.from(parent);
        Map<String, String> resolvedKeys = new LinkedHashMap<>();
        Set<String> resolvedIds = new HashSet<>();
        Set<String> carriedParentIds = new HashSet<>();
        ArrayNode rows = JsonNodeFactory.instance.arrayNode();

        for (DeltaRow item : delta.rows()) {
            ObjectNode prior = null;
            String factId;
            if (item.factKey().startsWith("FACT_")) {
                factId = item.factKey();
                prior = parentIndex.byId().get(factId);
                if (prior == null) {
                    throw rejected(
                            "INTAKE_RESPONDENT_MATRIX_FACT_UNKNOWN",
                            "respondent matrix delta references an unknown formal fact");
                }
                requireStableBinding(prior, item);
                if (!carriedParentIds.add(factId)) {
                    throw rejected(
                            "INTAKE_RESPONDENT_MATRIX_FACT_DUPLICATE",
                            "respondent matrix delta carries a prior fact more than once");
                }
            } else {
                if (parentIndex.byFingerprint()
                        .containsKey(fingerprint(item.category(), item.factTarget()))) {
                    throw rejected(
                            "INTAKE_RESPONDENT_MATRIX_NEW_FACT_COLLISION",
                            "a proposal-local new fact duplicates existing formal authority");
                }
                factId = stableFactId(
                        authority.caseId(), item.category(), item.factTarget());
                if (parentIndex.byId().containsKey(factId)) {
                    throw rejected(
                            "INTAKE_RESPONDENT_MATRIX_NEW_FACT_COLLISION",
                            "a proposal-local new fact collides with existing formal authority");
                }
            }
            if (!resolvedIds.add(factId)) {
                throw rejected(
                        "INTAKE_RESPONDENT_MATRIX_FACT_DUPLICATE",
                        "respondent matrix delta resolves more than one row to the same fact");
            }
            resolvedKeys.put(item.factKey(), factId);
            rows.add(deriveRow(prior, factId, item, authority));
        }

        if (!carriedParentIds.equals(parentIndex.byId().keySet())) {
            Set<String> missing = new LinkedHashSet<>(parentIndex.byId().keySet());
            missing.removeAll(carriedParentIds);
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PRIOR_FACT_MISSING",
                    "respondent matrix delta must carry every prior fact exactly once: " + missing);
        }

        Set<String> selectedSummaryIds = new LinkedHashSet<>();
        for (String key : delta.summaryKeys()) {
            String factId = resolvedKeys.get(key);
            if (factId == null) {
                throw rejected(
                        "INTAKE_RESPONDENT_MATRIX_DELTA_SUMMARY_INVALID",
                        "respondent matrix summary references an unresolved fact");
            }
            if (!selectedSummaryIds.add(factId)) {
                throw rejected(
                        "INTAKE_RESPONDENT_MATRIX_DELTA_SUMMARY_INVALID",
                        "respondent matrix summary references a fact more than once");
            }
        }
        ArrayNode summaryFactIds = JsonNodeFactory.instance.arrayNode();
        for (JsonNode row : rows) {
            String factId = row.path("fact_id").asText("");
            if (selectedSummaryIds.remove(factId)) {
                summaryFactIds.add(factId);
            }
        }
        if (!selectedSummaryIds.isEmpty()) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_DELTA_SUMMARY_INVALID",
                    "respondent matrix summary cannot bind the final formal row order");
        }

        List<String> sourceRefs = sourceRefs(parent, authority.sourceRef());
        ObjectNode matrix = JsonNodeFactory.instance.objectNode();
        matrix.put("schema_version", "case_fact_matrix.v2");
        matrix.put("case_id", authority.caseId());
        matrix.put("matrix_version", requiredNextVersion(parent));
        matrix.put("matrix_kind", "BILATERAL_FROZEN");
        matrix.set("parent_ref", parentRef(parent));
        matrix.set("party_map", parent.required("party_map").deepCopy());
        matrix.set("source_refs", textArray(sourceRefs));
        matrix.set("case_overview", caseOverview(parent, summaryFactIds));
        matrix.set("claims", claims(parent, delta.respondentClaim(), authority));
        matrix.set("fact_rows", rows);
        matrix.set("fact_relationships", parent.required("fact_relationships").deepCopy());
        matrix.set("generation_ref", generationRef(authority));
        matrix.set("fact_indexes", factIndexes(rows));
        matrix.put("matrix_id", matrixId(matrix));
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));
        validateDerived(matrix, parent, authority, sourceRefs);
        return matrix;
    }

    private static ObjectNode deriveRow(
            ObjectNode prior, String factId, DeltaRow item, MatrixAuthority authority) {
        ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("fact_id", factId);
        row.put("category", item.category());
        row.put("fact_target", item.factTarget());
        row.put("materiality", item.materiality());
        row.set("origin", origin(prior, item, authority.sourceRef()));
        ObjectNode positions = prior == null
                ? newPositions(authority.initiatorRole(), authority.respondentRole())
                : ((ObjectNode) prior.required("positions")).deepCopy();
        positions.set(
                authority.respondentRole().name(),
                respondentPosition(
                        prior == null
                                ? null
                                : (ObjectNode) prior.required("positions")
                                        .required(authority.respondentRole().name()),
                        item,
                        authority.sourceRef()));
        row.set("positions", positions);
        ObjectNode alignment = alignment(positions, item);
        row.set("party_alignment", alignment);
        row.put("requires_resolution", !"AGREED".equals(alignment.path("status").asText()));
        row.put("truth_status", "NOT_EVALUATED");
        if (prior == null) {
            row.put("evidence_coverage_status", "PENDING_EVIDENCE_REVIEW");
        } else {
            row.set(
                    "evidence_coverage_status",
                    prior.required("evidence_coverage_status").deepCopy());
        }
        return row;
    }

    private static ObjectNode origin(ObjectNode prior, DeltaRow item, String currentSource) {
        ObjectNode origin = JsonNodeFactory.instance.objectNode();
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        if (prior == null) {
            origin.put("introduced_stage", "RESPONDENT_INTAKE");
        } else {
            ObjectNode priorOrigin = (ObjectNode) prior.required("origin");
            origin.set("introduced_stage", priorOrigin.required("introduced_stage").deepCopy());
            addTextValues(refs, priorOrigin.required("source_refs"));
        }
        if (usesCurrentSource(item.sourceScope())) {
            refs.add(currentSource);
        }
        requireSourceCount(refs, "fact origin");
        origin.set("source_refs", textArray(refs));
        return origin;
    }

    private static ObjectNode newPositions(ActorRole initiatorRole, ActorRole respondentRole) {
        ObjectNode positions = JsonNodeFactory.instance.objectNode();
        ObjectNode initiator = positions.putObject(initiatorRole.name());
        initiator.put("stance", "NOT_ADDRESSED");
        initiator.put("position_summary", NO_DIRECT_INITIATOR_POSITION);
        initiator.putNull("asserted_value");
        initiator.put("source_type", "NO_DIRECT_POSITION");
        initiator.putArray("source_refs");
        positions.set(respondentRole.name(), JsonNodeFactory.instance.objectNode());
        return positions;
    }

    private static ObjectNode respondentPosition(
            ObjectNode prior, DeltaRow item, String currentSource) {
        if ("NOT_ADDRESSED".equals(item.stance())) {
            if (prior == null
                    || !"PREVIOUS_MATRIX".equals(item.sourceScope())
                    || !"NOT_ADDRESSED".equals(prior.path("stance").asText())) {
                throw rejected(
                        "INTAKE_RESPONDENT_MATRIX_SOURCE_SCOPE_INVALID",
                        "NOT_ADDRESSED can only preserve the prior respondent position");
            }
            return prior.deepCopy();
        }

        LinkedHashSet<String> refs = new LinkedHashSet<>();
        if (usesPreviousSource(item.sourceScope()) && prior != null) {
            addTextValues(refs, prior.required("source_refs"));
        }
        if (usesCurrentSource(item.sourceScope())) {
            refs.add(currentSource);
        }
        if (refs.isEmpty()) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_SOURCE_SCOPE_INVALID",
                    "a substantive respondent position has no authorized source");
        }
        requireSourceCount(refs, "respondent position");
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

    private static ObjectNode alignment(ObjectNode positions, DeltaRow item) {
        ObjectNode user = (ObjectNode) positions.required("USER");
        ObjectNode merchant = (ObjectNode) positions.required("MERCHANT");
        String userStance = user.path("stance").asText();
        String merchantStance = merchant.path("stance").asText();
        String status = alignmentStatus(
                userStance,
                merchantStance,
                nullableText(user.get("asserted_value")),
                nullableText(merchant.get("asserted_value")),
                item.agreedStatement() != null && item.conflictSummary() != null);
        ObjectNode alignment = JsonNodeFactory.instance.objectNode();
        alignment.put("status", status);
        if ("AGREED".equals(status)) {
            alignment.put(
                    "agreed_statement",
                    item.agreedStatement() == null ? item.factTarget() : item.agreedStatement());
            alignment.putNull("conflict_summary");
        } else if ("PARTIALLY_AGREED".equals(status)) {
            alignment.put("agreed_statement", item.agreedStatement());
            alignment.put("conflict_summary", item.conflictSummary());
        } else {
            alignment.putNull("agreed_statement");
            alignment.put(
                    "conflict_summary",
                    item.conflictSummary() == null || "NOT_ADDRESSED".equals(item.stance())
                            ? DEFAULT_CONFLICT_SUMMARY
                            : item.conflictSummary());
        }
        return alignment;
    }

    private static String alignmentStatus(
            String leftStance,
            String rightStance,
            String leftValue,
            String rightValue,
            boolean hasSharedScope) {
        boolean leftSubstantive = SUBSTANTIVE_STANCES.contains(leftStance);
        boolean rightSubstantive = SUBSTANTIVE_STANCES.contains(rightStance);
        if (!leftSubstantive && !rightSubstantive) {
            return "UNRESOLVED";
        }
        if (!leftSubstantive || !rightSubstantive) {
            return "ONE_SIDED";
        }
        String normalizedLeft = normalizeValue(leftValue);
        String normalizedRight = normalizeValue(rightValue);
        boolean sameValue = !normalizedLeft.isEmpty() && normalizedLeft.equals(normalizedRight);
        if (leftStance.equals(rightStance) && AGREEMENT_STANCES.contains(leftStance)) {
            return sameValue ? "AGREED" : "CONTESTED";
        }
        if ("DENY".equals(leftStance) || "DENY".equals(rightStance)) {
            return "CONTESTED";
        }
        if ("PARTIAL".equals(leftStance) || "PARTIAL".equals(rightStance)) {
            return hasSharedScope ? "PARTIALLY_AGREED" : "CONTESTED";
        }
        return "CONTESTED";
    }

    private static ObjectNode claims(
            ObjectNode parent, RespondentClaim proposal, MatrixAuthority authority) {
        ObjectNode prior = (ObjectNode) parent.required("claims");
        ObjectNode claims = JsonNodeFactory.instance.objectNode();
        claims.set("initiator_claim", prior.required("initiator_claim").deepCopy());
        claims.set(
                "respondent_reported_by_initiator",
                prior.required("respondent_reported_by_initiator").deepCopy());
        if (proposal == null) {
            claims.putNull("respondent_direct");
            claims.putNull("claim_conflict");
            return claims;
        }
        ObjectNode direct = claims.putObject("respondent_direct");
        direct.put("respondent_role", authority.respondentRole().name());
        direct.put("attitude", proposal.attitude());
        direct.put("position_summary", proposal.positionSummary());
        if (proposal.alternativeProposal() == null) {
            direct.putNull("alternative_proposal");
        } else {
            direct.put("alternative_proposal", proposal.alternativeProposal());
        }
        direct.put("source_type", "RESPONDENT_DIRECT_INTAKE");
        direct.putArray("source_refs").add(authority.sourceRef());
        claims.set(
                "claim_conflict",
                parent.required("case_overview").required("core_conflict").deepCopy());
        return claims;
    }

    private static ObjectNode caseOverview(ObjectNode parent, ArrayNode summaryFactIds) {
        ObjectNode prior = (ObjectNode) parent.required("case_overview");
        ObjectNode overview = JsonNodeFactory.instance.objectNode();
        overview.set("neutral_summary", prior.required("neutral_summary").deepCopy());
        overview.set("core_conflict", prior.required("core_conflict").deepCopy());
        overview.set("summary_source_fact_ids", summaryFactIds);
        return overview;
    }

    private static ObjectNode parentRef(ObjectNode parent) {
        ObjectNode ref = JsonNodeFactory.instance.objectNode();
        ref.set("matrix_id", parent.required("matrix_id").deepCopy());
        ref.set("matrix_version", parent.required("matrix_version").deepCopy());
        ref.set("content_hash", parent.required("content_hash").deepCopy());
        return ref;
    }

    private static ObjectNode generationRef(MatrixAuthority authority) {
        ObjectNode generation = JsonNodeFactory.instance.objectNode();
        generation.put("actor_role", authority.respondentRole().name());
        generation.put("source_stage", "RESPONDENT_INTAKE");
        generation.put("latest_source_ref", authority.sourceRef());
        generation.put("source_context_hash", authority.sourceContextHash());
        return generation;
    }

    private static ObjectNode factIndexes(ArrayNode rows) {
        ObjectNode indexes = JsonNodeFactory.instance.objectNode();
        Map<String, ArrayNode> byStatus = Map.of(
                "NOT_COMPUTED", indexes.putArray("not_computed_fact_ids"),
                "AGREED", indexes.putArray("agreed_fact_ids"),
                "PARTIALLY_AGREED", indexes.putArray("partially_agreed_fact_ids"),
                "CONTESTED", indexes.putArray("contested_fact_ids"),
                "ONE_SIDED", indexes.putArray("one_sided_fact_ids"),
                "UNRESOLVED", indexes.putArray("unresolved_fact_ids"));
        ArrayNode core = indexes.putArray("core_fact_ids");
        ArrayNode requiresResolution = indexes.putArray("requires_resolution_fact_ids");
        for (JsonNode row : rows) {
            String factId = row.path("fact_id").asText();
            ArrayNode statusIndex = byStatus.get(row.at("/party_alignment/status").asText());
            if (statusIndex == null) {
                throw rejected(
                        "INTAKE_RESPONDENT_MATRIX_ALIGNMENT_INVALID",
                        "derived respondent matrix alignment is invalid");
            }
            statusIndex.add(factId);
            if ("CORE".equals(row.path("materiality").asText())) {
                core.add(factId);
            }
            if (row.path("requires_resolution").asBoolean()) {
                requiresResolution.add(factId);
            }
        }
        return indexes;
    }

    private static String matrixId(ObjectNode formalMaterial) {
        return "CASE_MATRIX_"
                + ContractJson.sha256Hex(formalMaterial)
                        .substring(0, 20)
                        .toUpperCase(Locale.ROOT);
    }

    private static String stableFactId(String caseId, String category, String target) {
        ObjectNode binding = JsonNodeFactory.instance.objectNode();
        binding.put("case_id", caseId);
        binding.put("category", category);
        binding.put("fact_target", target);
        return "FACT_"
                + ContractJson.sha256Hex(binding).substring(0, 24).toUpperCase(Locale.ROOT);
    }

    private static String fingerprint(String category, String target) {
        ObjectNode binding = JsonNodeFactory.instance.objectNode();
        binding.put("category", category);
        binding.put("fact_target", target);
        return ContractJson.sha256Hex(binding);
    }

    private static List<String> sourceRefs(ObjectNode parent, String currentSource) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        addTextValues(refs, parent.required("source_refs"));
        refs.add(currentSource);
        if (refs.size() > 256) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_SOURCE_LIMIT",
                    "bilateral matrix source authority exceeds the formal limit");
        }
        return List.copyOf(refs);
    }

    private static void requireStableBinding(ObjectNode prior, DeltaRow item) {
        if (!item.category().equals(prior.path("category").asText())
                || !item.factTarget().equals(prior.path("fact_target").asText())
                || !item.materiality().equals(prior.path("materiality").asText())) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_FACT_REBOUND",
                    "respondent matrix delta changes a formal fact binding");
        }
    }

    private static void requireRespondentAuthority(MatrixAuthority authority) {
        if (authority.actorRole() != authority.respondentRole()
                || !PARTY_ROLES.contains(authority.initiatorRole())
                || !PARTY_ROLES.contains(authority.respondentRole())) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_AUTHORITY_INVALID",
                    "matrix delta requires the Java-authorized respondent actor");
        }
    }

    private static void validateDerived(
            ObjectNode matrix,
            ObjectNode parent,
            MatrixAuthority authority,
            List<String> sourceRefs) {
        ObjectNode hashInput = matrix.deepCopy();
        JsonNode hash = hashInput.remove("content_hash");
        if (hash == null
                || !hash.isTextual()
                || !hash.asText().matches("[0-9a-f]{64}")
                || !hash.asText().equals(ContractJson.sha256Hex(hashInput))) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_HASH_INVALID",
                    "bilateral matrix content hash is not canonical");
        }
        if (!"case_fact_matrix.v2".equals(matrix.path("schema_version").asText())
                || !authority.caseId().equals(matrix.path("case_id").asText())
                || !"BILATERAL_FROZEN".equals(matrix.path("matrix_kind").asText())
                || matrix.path("matrix_version").asLong()
                        != requiredNextVersion(parent)
                || !parent.path("matrix_id").equals(matrix.at("/parent_ref/matrix_id"))
                || !parent.path("matrix_version").equals(matrix.at("/parent_ref/matrix_version"))
                || !parent.path("content_hash").equals(matrix.at("/parent_ref/content_hash"))
                || !sourceRefs.equals(textValues(matrix.path("source_refs")))
                || !authority.respondentRole().name().equals(
                        matrix.at("/generation_ref/actor_role").asText())
                || !"RESPONDENT_INTAKE".equals(
                        matrix.at("/generation_ref/source_stage").asText())
                || !authority.sourceRef().equals(
                        matrix.at("/generation_ref/latest_source_ref").asText())
                || !authority.sourceContextHash().equals(
                        matrix.at("/generation_ref/source_context_hash").asText())) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_DERIVATION_INVALID",
                    "bilateral matrix does not match Java parent or source authority");
        }
        validateBilateralParent(matrix, authority);
    }

    private static void validateBilateralClaims(
            ObjectNode matrix,
            MatrixAuthority authority,
            Set<String> declaredSources,
            String latestSource,
            String coreConflict) {
        ObjectNode claims = requiredObject(matrix, "claims", "bilateral claims");
        requireExactFields(claims, CLAIM_FIELDS, "bilateral claims");

        ObjectNode initiatorClaim =
                requiredObject(claims, "initiator_claim", "bilateral initiator claim");
        requireFields(
                initiatorClaim,
                INITIATOR_CLAIM_REQUIRED_FIELDS,
                INITIATOR_CLAIM_ALLOWED_FIELDS,
                "bilateral initiator claim");
        if (!authority.initiatorRole().name()
                        .equals(requiredText(initiatorClaim, "initiator_role", 32))
                || !requiredIdentifier(initiatorClaim, "requested_resolution")
                        .matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "bilateral initiator claim conflicts with Java authority");
        }
        JsonNode requestedAmount = initiatorClaim.path("requested_amount");
        if (!requestedAmount.isMissingNode()
                && !requestedAmount.isNull()
                && (!requestedAmount.isNumber() || requestedAmount.decimalValue().signum() < 0)) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "bilateral initiator requested amount is invalid");
        }
        if (initiatorClaim.hasNonNull("requested_items")) {
            requiredText(initiatorClaim, "requested_items", 2_000);
        }
        requiredText(initiatorClaim, "reason_summary", 20_000);
        requiredText(initiatorClaim, "position_summary", 20_000);
        requireDeclaredSources(
                requiredTextArray(
                        initiatorClaim,
                        "source_refs",
                        1,
                        50,
                        128,
                        "bilateral initiator claim source refs"),
                declaredSources,
                "bilateral initiator claim source refs");

        JsonNode reported = claims.path("respondent_reported_by_initiator");
        if (!reported.isNull()) {
            if (!reported.isObject()) {
                throw rejected(
                        "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                        "reported respondent claim must be an object or null");
            }
            ObjectNode attitude = (ObjectNode) reported;
            requireExactFields(attitude, REPORTED_RESPONDENT_FIELDS, "reported respondent claim");
            if (!authority.respondentRole().name()
                            .equals(requiredText(attitude, "respondent_role", 32))
                    || !CLAIM_ATTITUDES.contains(requiredIdentifier(attitude, "attitude"))
                    || !"INITIATOR_SUBJECTIVE_REPORT".equals(
                            requiredText(attitude, "source_type", 64))) {
                throw rejected(
                        "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                        "reported respondent claim is outside initiator authority");
            }
            requiredText(attitude, "position_summary", 20_000);
            requireDeclaredSources(
                    requiredTextArray(
                            attitude,
                            "source_refs",
                            1,
                            50,
                            128,
                            "reported respondent source refs"),
                    declaredSources,
                    "reported respondent source refs");
        }

        JsonNode direct = claims.path("respondent_direct");
        JsonNode claimConflict = claims.path("claim_conflict");
        if (direct.isNull()) {
            if (!claimConflict.isNull()) {
                throw rejected(
                        "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                        "bilateral claim conflict requires a direct respondent claim");
            }
            return;
        }
        if (!direct.isObject()) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "direct respondent claim must be an object or null");
        }
        ObjectNode respondentDirect = (ObjectNode) direct;
        requireExactFields(
                respondentDirect, RESPONDENT_DIRECT_FIELDS, "direct respondent claim");
        if (!authority.respondentRole().name()
                        .equals(requiredText(respondentDirect, "respondent_role", 32))
                || !CLAIM_ATTITUDES.contains(
                        requiredIdentifier(respondentDirect, "attitude"))
                || !"RESPONDENT_DIRECT_INTAKE".equals(
                        requiredText(respondentDirect, "source_type", 64))) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "direct respondent claim conflicts with Java authority");
        }
        requiredText(respondentDirect, "position_summary", 20_000);
        if (!respondentDirect.path("alternative_proposal").isNull()) {
            requiredText(respondentDirect, "alternative_proposal", 20_000);
        }
        List<String> directSources = requiredTextArray(
                respondentDirect,
                "source_refs",
                1,
                50,
                128,
                "direct respondent source refs");
        requireDeclaredSources(
                directSources, declaredSources, "direct respondent source refs");
        if (!directSources.equals(List.of(latestSource))) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_SOURCE_SCOPE_INVALID",
                    "direct respondent claim must bind the latest respondent source");
        }
        if (!claimConflict.isTextual()
                || claimConflict.asText().isBlank()
                || !coreConflict.equals(claimConflict.asText())) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "bilateral claim conflict is not bound to the formal overview");
        }
    }

    private static void validateBilateralRow(
            JsonNode candidate,
            MatrixAuthority authority,
            Set<String> declaredSources,
            List<String> ids,
            Map<String, List<String>> byStatus,
            List<String> coreIds,
            List<String> requiresResolutionIds) {
        if (!candidate.isObject()) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "bilateral matrix fact row must be an object");
        }
        ObjectNode row = (ObjectNode) candidate;
        requireExactFields(row, ROW_FIELDS, "bilateral matrix fact row");
        String factId = requiredText(row, "fact_id", 128);
        String materiality = requiredText(row, "materiality", 32);
        if (!factId.matches("FACT_[A-Za-z0-9_:-]{1,123}")
                || ids.contains(factId)
                || !CATEGORIES.contains(requiredText(row, "category", 64))
                || !MATERIALITIES.contains(materiality)) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "bilateral matrix fact identifiers or enums are invalid");
        }
        ids.add(factId);
        if ("CORE".equals(materiality)) {
            coreIds.add(factId);
        }
        requiredText(row, "fact_target", 20_000);
        if (!"NOT_EVALUATED".equals(requiredText(row, "truth_status", 64))
                || !"PENDING_EVIDENCE_REVIEW".equals(
                        requiredText(row, "evidence_coverage_status", 64))
                || !row.path("requires_resolution").isBoolean()) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "bilateral matrix derived fact fields are invalid");
        }

        ObjectNode origin = requiredObject(row, "origin", "bilateral fact origin");
        requireExactFields(origin, ORIGIN_FIELDS, "bilateral fact origin");
        String introducedStage = requiredText(origin, "introduced_stage", 64);
        if (!"INITIATOR_INTAKE".equals(introducedStage)
                && !"RESPONDENT_INTAKE".equals(introducedStage)) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "bilateral fact origin has no party authority");
        }
        requireDeclaredSources(
                requiredTextArray(
                        origin, "source_refs", 1, 50, 128, "bilateral fact origin source refs"),
                declaredSources,
                "bilateral fact origin source refs");

        ObjectNode positions = requiredObject(row, "positions", "bilateral fact positions");
        requireExactFields(
                positions,
                Set.of(authority.initiatorRole().name(), authority.respondentRole().name()),
                "bilateral fact positions");
        ObjectNode initiatorPosition = requiredObject(
                positions,
                authority.initiatorRole().name(),
                "bilateral initiator position");
        ObjectNode respondentPosition = requiredObject(
                positions,
                authority.respondentRole().name(),
                "bilateral respondent position");
        validateBilateralPosition(
                initiatorPosition,
                declaredSources,
                "bilateral initiator position");
        validateBilateralPosition(
                respondentPosition,
                declaredSources,
                "bilateral respondent position");

        ObjectNode alignment = requiredObject(row, "party_alignment", "bilateral alignment");
        requireExactFields(alignment, ALIGNMENT_FIELDS, "bilateral alignment");
        String status = requiredText(alignment, "status", 64);
        if (!BILATERAL_ALIGNMENT_STATUSES.contains(status)) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_ALIGNMENT_INVALID",
                    "bilateral alignment status is invalid");
        }
        ObjectNode userPosition = (ObjectNode) positions.required("USER");
        ObjectNode merchantPosition = (ObjectNode) positions.required("MERCHANT");
        boolean hasSharedScope = !alignment.path("agreed_statement").isNull()
                && !alignment.path("conflict_summary").isNull();
        String expectedStatus = alignmentStatus(
                userPosition.path("stance").asText(),
                merchantPosition.path("stance").asText(),
                nullableText(userPosition.get("asserted_value")),
                nullableText(merchantPosition.get("asserted_value")),
                hasSharedScope);
        if (!status.equals(expectedStatus)) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_ALIGNMENT_INVALID",
                    "bilateral alignment conflicts with the two party positions");
        }
        boolean requiresResolution = row.path("requires_resolution").booleanValue();
        if (requiresResolution == "AGREED".equals(status)) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_ALIGNMENT_INVALID",
                    "bilateral resolution index conflicts with alignment");
        }
        if ("AGREED".equals(status)) {
            requiredText(alignment, "agreed_statement", 20_000);
            requireNull(alignment, "conflict_summary", "agreed bilateral conflict summary");
        } else if ("PARTIALLY_AGREED".equals(status)) {
            requiredText(alignment, "agreed_statement", 20_000);
            requiredText(alignment, "conflict_summary", 20_000);
        } else {
            requireNull(alignment, "agreed_statement", "unresolved bilateral agreed statement");
            requiredText(alignment, "conflict_summary", 20_000);
        }
        byStatus.get(status).add(factId);
        if (requiresResolution) {
            requiresResolutionIds.add(factId);
        }
    }

    private static void validateBilateralPosition(
            ObjectNode position, Set<String> declaredSources, String label) {
        requireExactFields(position, POSITION_FIELDS, label);
        String stance = requiredText(position, "stance", 64);
        requiredText(position, "position_summary", 20_000);
        if (!Set.of("CONFIRM", "DENY", "PARTIAL", "UNKNOWN", "NOT_ADDRESSED")
                .contains(stance)) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    label + " stance is invalid");
        }
        JsonNode assertedValue = position.path("asserted_value");
        if (!assertedValue.isNull()) {
            requiredText(position, "asserted_value", 2_000);
        }
        JsonNode sourceRefs = position.path("source_refs");
        if ("NOT_ADDRESSED".equals(stance)) {
            if (!"NO_DIRECT_POSITION".equals(requiredText(position, "source_type", 64))
                    || !assertedValue.isNull()
                    || !sourceRefs.isArray()
                    || !sourceRefs.isEmpty()) {
                throw rejected(
                        "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                        label + " fabricates an absent party position");
            }
            return;
        }
        if (!"DIRECT_PARTY_STATEMENT".equals(requiredText(position, "source_type", 64))) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    label + " is not a direct party statement");
        }
        requireDeclaredSources(
                requiredTextArray(
                        position, "source_refs", 1, 50, 128, label + " source refs"),
                declaredSources,
                label + " source refs");
    }

    private static void validateSummaryIds(ObjectNode overview, List<String> rowIds) {
        List<String> summaryIds = requiredTextArray(
                overview,
                "summary_source_fact_ids",
                1,
                200,
                128,
                "bilateral summary source facts");
        int previousIndex = -1;
        for (String summaryId : summaryIds) {
            int rowIndex = rowIds.indexOf(summaryId);
            if (rowIndex <= previousIndex) {
                throw rejected(
                        "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                        "bilateral summary facts are unknown or out of formal row order");
            }
            previousIndex = rowIndex;
        }
    }

    private static void validateBilateralIndexes(
            ObjectNode matrix,
            Map<String, List<String>> byStatus,
            List<String> coreIds,
            List<String> requiresResolutionIds) {
        ObjectNode indexes = requiredObject(matrix, "fact_indexes", "bilateral fact indexes");
        requireExactFields(indexes, INDEX_FIELDS, "bilateral fact indexes");
        requireExactIndex(indexes.path("not_computed_fact_ids"), List.of(), "not computed facts");
        requireExactIndex(indexes.path("agreed_fact_ids"), byStatus.get("AGREED"), "agreed facts");
        requireExactIndex(
                indexes.path("partially_agreed_fact_ids"),
                byStatus.get("PARTIALLY_AGREED"),
                "partially agreed facts");
        requireExactIndex(
                indexes.path("contested_fact_ids"), byStatus.get("CONTESTED"), "contested facts");
        requireExactIndex(
                indexes.path("one_sided_fact_ids"), byStatus.get("ONE_SIDED"), "one-sided facts");
        requireExactIndex(
                indexes.path("unresolved_fact_ids"),
                byStatus.get("UNRESOLVED"),
                "unresolved facts");
        requireExactIndex(indexes.path("core_fact_ids"), coreIds, "core facts");
        requireExactIndex(
                indexes.path("requires_resolution_fact_ids"),
                requiresResolutionIds,
                "resolution-required facts");
    }

    private static ObjectNode requiredObject(JsonNode owner, String field, String label) {
        JsonNode value = owner.path(field);
        if (!value.isObject()) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    label + " must be an object");
        }
        return (ObjectNode) value;
    }

    private static void requireExactFields(ObjectNode value, Set<String> expected, String label) {
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    label + " has unexpected or missing fields");
        }
    }

    private static void requireFields(
            ObjectNode value, Set<String> required, Set<String> allowed, String label) {
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    label + " has unexpected or missing fields");
        }
    }

    private static List<String> requiredTextArray(
            ObjectNode owner,
            String field,
            int minimum,
            int maximum,
            int itemMaximum,
            String label) {
        JsonNode values = owner.path(field);
        if (!values.isArray() || values.size() < minimum || values.size() > maximum) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    label + " has an invalid length");
        }
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual()
                    || value.asText().isBlank()
                    || value.asText().length() > itemMaximum
                    || !value.asText().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                    || !unique.add(value.asText())) {
                throw rejected(
                        "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                        label + " contains an invalid or duplicate identifier");
            }
            result.add(value.asText());
        }
        return result;
    }

    private static void requireDeclaredSources(
            List<String> references, Set<String> declaredSources, String label) {
        if (!declaredSources.containsAll(references)) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_SOURCE_SCOPE_INVALID",
                    label + " is not bound to a declared source");
        }
    }

    private static void requireNull(ObjectNode owner, String field, String label) {
        if (!owner.path(field).isNull()) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    label + " must be null");
        }
    }

    private static String requiredIdentifier(JsonNode owner, String field) {
        String value = requiredText(owner, field, 128);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    field + " is invalid");
        }
        return value;
    }

    private static String requiredHash(JsonNode owner, String field) {
        String value = requiredText(owner, field, 64);
        if (!value.matches("[0-9a-f]{64}")) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_HASH_INVALID",
                    field + " is invalid");
        }
        return value;
    }

    private static String requiredText(JsonNode owner, String field, int maximum) {
        JsonNode value = owner.path(field);
        if (!value.isTextual()
                || value.asText().isBlank()
                || value.asText().length() > maximum) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    field + " is invalid");
        }
        return value.asText();
    }

    private static void requireExactIndex(
            JsonNode index, List<String> expected, String label) {
        if (!index.isArray() || index.size() != expected.size()) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "bilateral " + label + " do not match fact rows");
        }
        List<String> actual = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode value : index) {
            String factId = value.isTextual() ? value.asText() : "";
            if (!factId.matches("FACT_[A-Za-z0-9_:-]{1,123}") || !unique.add(factId)) {
                throw rejected(
                        "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                        "bilateral " + label + " contain invalid fact ids");
            }
            actual.add(factId);
        }
        if (!expected.equals(actual)) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "bilateral " + label + " do not match fact rows");
        }
    }

    private static long requiredPositiveLong(JsonNode owner, String field) {
        JsonNode value = owner.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID", field + " is invalid");
        }
        return value.longValue();
    }

    private static long requiredNextVersion(JsonNode parent) {
        long current = requiredPositiveLong(parent, "matrix_version");
        if (current == Long.MAX_VALUE) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "respondent matrix parent version cannot advance safely");
        }
        return current + 1;
    }

    private static boolean usesCurrentSource(String scope) {
        return "CURRENT_SOURCE".equals(scope) || "PREVIOUS_AND_CURRENT_SOURCE".equals(scope);
    }

    private static boolean usesPreviousSource(String scope) {
        return "PREVIOUS_MATRIX".equals(scope) || "PREVIOUS_AND_CURRENT_SOURCE".equals(scope);
    }

    private static void requireSourceCount(Set<String> refs, String label) {
        if (refs.isEmpty() || refs.size() > 50) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_SOURCE_LIMIT",
                    label + " source authority exceeds the formal limit");
        }
    }

    private static void addTextValues(Set<String> target, JsonNode values) {
        if (!values.isArray()) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                    "formal source references are not an array");
        }
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw rejected(
                        "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                        "formal source reference is invalid");
            }
            target.add(value.textValue());
        }
    }

    private static ArrayNode textArray(Iterable<String> values) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        values.forEach(result::add);
        return result;
    }

    private static List<String> textValues(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private static String nullableText(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String normalizeValue(String value) {
        if (value == null) {
            return "";
        }
        return VALUE_SEPARATOR
                .matcher(Normalizer.normalize(value, Normalizer.Form.NFKC))
                .replaceAll("")
                .toLowerCase(Locale.ROOT);
    }

    private static IntakeFinalizationRejectedException rejected(String code, String message) {
        return new IntakeFinalizationRejectedException(code, message);
    }

    private record ParentIndex(
            Map<String, ObjectNode> byId, Map<String, String> byFingerprint) {
        private static ParentIndex from(ObjectNode parent) {
            Map<String, ObjectNode> rows = new LinkedHashMap<>();
            Map<String, String> fingerprints = new LinkedHashMap<>();
            for (JsonNode value : parent.withArray("fact_rows")) {
                ObjectNode row = (ObjectNode) value;
                String factId = row.path("fact_id").asText();
                rows.put(factId, row);
                String fingerprint = fingerprint(
                        row.path("category").asText(), row.path("fact_target").asText());
                String duplicate = fingerprints.putIfAbsent(fingerprint, factId);
                if (duplicate != null && !duplicate.equals(factId)) {
                    throw rejected(
                            "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                            "formal parent contains duplicate semantic facts");
                }
            }
            return new ParentIndex(Map.copyOf(rows), Map.copyOf(fingerprints));
        }
    }
}
