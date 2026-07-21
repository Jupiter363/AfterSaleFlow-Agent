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
        initiatorFreezer.validateFrozen(
                initiatorMatrix,
                authority.caseId(),
                authority.initiatorRole(),
                authority.respondentRole());
        ValidatedDelta delta = deltaPolicy.validate(deltaCandidate);
        return derive(initiatorMatrix, delta, authority);
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

        ArrayNode summaryFactIds = JsonNodeFactory.instance.arrayNode();
        for (String key : delta.summaryKeys()) {
            String factId = resolvedKeys.get(key);
            if (factId == null) {
                throw rejected(
                        "INTAKE_RESPONDENT_MATRIX_DELTA_SUMMARY_INVALID",
                        "respondent matrix summary references an unresolved fact");
            }
            summaryFactIds.add(factId);
        }

        List<String> sourceRefs = sourceRefs(parent, authority.sourceRef());
        ObjectNode matrix = JsonNodeFactory.instance.objectNode();
        matrix.put("schema_version", "case_fact_matrix.v2");
        matrix.put("case_id", authority.caseId());
        matrix.put("matrix_version", requiredPositiveLong(parent, "matrix_version") + 1);
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
                        != parent.path("matrix_version").asLong() + 1
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
    }

    private static long requiredPositiveLong(JsonNode owner, String field) {
        JsonNode value = owner.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID", field + " is invalid");
        }
        return value.longValue();
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
