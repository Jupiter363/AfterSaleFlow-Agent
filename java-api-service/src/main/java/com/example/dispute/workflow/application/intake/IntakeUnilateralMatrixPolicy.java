package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.application.intake.IntakeDossierProjectionMerger.MatrixAuthority;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the non-frozen unilateral projection from a model-authored semantic draft. */
public final class IntakeUnilateralMatrixPolicy {

    private static final Set<String> DRAFT_FIELDS =
            Set.of("schema_version", "fact_rows", "summary_source_fact_keys");
    private static final Set<String> DRAFT_ROW_FIELDS = Set.of(
            "fact_key",
            "category",
            "fact_target",
            "materiality",
            "position_summary",
            "asserted_value",
            "source_scope");
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
    private static final Set<String> SOURCE_SCOPES =
            Set.of("CURRENT_SOURCE", "PREVIOUS_MATRIX", "PREVIOUS_AND_CURRENT_SOURCE");
    private static final Set<String> MATRIX_REQUIRED_FIELDS = Set.of(
            "schema_version",
            "matrix_version",
            "content_hash",
            "source_binding",
            "party_map",
            "case_summary",
            "summary_source_fact_ids",
            "claim_resolution",
            "dispute_core_state",
            "fact_rows");
    private static final Set<String> MATRIX_ALLOWED_FIELDS = union(
            MATRIX_REQUIRED_FIELDS, Set.of("reported_respondent_attitude"));
    private static final Set<String> SOURCE_BINDING_FIELDS = Set.of(
            "case_id",
            "source_stage",
            "source_refs",
            "latest_source_ref",
            "source_context_hash");
    private static final Set<String> PARTY_MAP_FIELDS =
            Set.of("initiator_role", "respondent_role");
    private static final Set<String> CLAIM_REQUIRED_FIELDS = Set.of(
            "initiator_role",
            "requested_resolution",
            "reason_summary",
            "position_summary",
            "source_refs");
    private static final Set<String> CLAIM_ALLOWED_FIELDS = union(
            CLAIM_REQUIRED_FIELDS, Set.of("requested_amount", "requested_items"));
    private static final Set<String> ATTITUDE_FIELDS = Set.of(
            "respondent_role", "attitude", "position_summary", "source_type", "source_refs");
    private static final Set<String> CORE_STATE_FIELDS =
            Set.of("core_conflict", "facts_in_dispute", "next_verification_focus");
    private static final Set<String> FACT_ROW_FIELDS = Set.of(
            "fact_id",
            "category",
            "fact_target",
            "materiality",
            "origin",
            "initiator_position",
            "truth_status");
    private static final Set<String> ORIGIN_FIELDS = Set.of("source_stage", "source_refs");
    private static final Set<String> POSITION_FIELDS =
            Set.of("stance", "position_summary", "asserted_value", "source_refs");
    private static final Set<String> SOURCE_STAGES = Set.of("INTAKE", "RESPONDENT_INTAKE");
    private static final Set<String> STANCES = Set.of("CONFIRM", "DENY", "PARTIAL");
    private static final Set<String> CLAIM_ATTITUDES = Set.of(
            "AGREE",
            "PARTIALLY_AGREE",
            "DISAGREE",
            "ALTERNATIVE_PROPOSED",
            "NEED_MORE_INFO",
            "NOT_ADDRESSED");
    private static final Set<String> NO_RESPONSE_ATTITUDES =
            Set.of("UNKNOWN", "PLATFORM_UNKNOWN", "NOT_RESPONDED", "NOT_ADDRESSED");

    public void validateExisting(ObjectNode matrix, MatrixAuthority authority) {
        validateProjection(matrix, authority);
    }

    ObjectNode apply(ObjectNode dossier, JsonNode candidate, MatrixAuthority authority) {
        if (!authority.caseId().matches("CASE_[A-Za-z0-9_]{1,59}")) {
            throw rejected(
                    "INTAKE_MATRIX_CURRENT_AUTHORITY_MISMATCH",
                    "case id cannot be represented by unilateral_case_matrix.v1");
        }
        if (authority.actorRole() != authority.initiatorRole()) {
            throw rejected(
                    "INTAKE_MATRIX_FORMAL_TRANSITION_FORBIDDEN",
                    "unilateral matrix drafts require the Java-authorized initiator actor");
        }
        if (!candidate.isObject()) {
            throw rejected("INTAKE_MATRIX_PATCH_INVALID", "matrix patch is not an object");
        }
        if ("case_fact_matrix.v2".equals(candidate.path("schema_version").asText())
                || candidate.has("matrix_kind")) {
            throw rejected(
                    "INTAKE_MATRIX_FORMAL_TRANSITION_FORBIDDEN",
                    "model output cannot create or revise a formal case matrix; "
                            + "Java result finalization owns deterministic matrix conversion");
        }
        ObjectNode draft = (ObjectNode) candidate;
        requireExactFields(draft, DRAFT_FIELDS, "matrix draft");
        if (!"unilateral_case_matrix.draft.v1".equals(draft.path("schema_version").asText())) {
            throw rejected(
                    "INTAKE_MATRIX_PATCH_SCHEMA_INVALID",
                    "matrix patch must be unilateral_case_matrix.draft.v1");
        }

        ObjectNode previous = null;
        if (dossier.path("case_fact_matrix").isObject()) {
            previous = projectionFromInitiatorMatrix(
                    dossier, (ObjectNode) dossier.path("case_fact_matrix"), authority);
        } else if (dossier.path("unilateral_case_matrix").isObject()) {
            previous = (ObjectNode) dossier.path("unilateral_case_matrix");
            validatePrevious(previous, authority);
        }
        PreviousIndex index = PreviousIndex.from(previous);
        ArrayNode draftRows = requireArray(draft, "fact_rows", 1, 200);
        Map<String, String> resolvedKeys = new HashMap<>();
        Set<String> resolvedIds = new HashSet<>();
        ArrayNode factRows = dossier.arrayNode();
        for (JsonNode value : draftRows) {
            if (!value.isObject()) {
                throw rejected("INTAKE_MATRIX_PATCH_INVALID", "matrix fact row is not an object");
            }
            ObjectNode row = (ObjectNode) value;
            requireExactFields(row, DRAFT_ROW_FIELDS, "matrix fact row");
            String key = requireText(row, "fact_key", 128);
            if (!key.matches("(?:FACT|NEW)_[A-Za-z0-9_:-]{1,123}")) {
                throw rejected("INTAKE_MATRIX_FACT_KEY_INVALID", "matrix fact key is invalid");
            }
            String category = requireEnum(row, "category", CATEGORIES);
            String target = requireText(row, "fact_target", 20_000);
            String materiality = requireEnum(row, "materiality", MATERIALITIES);
            String position = requireText(row, "position_summary", 20_000);
            String asserted = requireText(row, "asserted_value", 2_000);
            String sourceScope = requireEnum(row, "source_scope", SOURCE_SCOPES);

            ObjectNode prior = key.startsWith("FACT_") ? index.byId().get(key) : null;
            String factId;
            if (key.startsWith("FACT_")) {
                if (prior == null) {
                    throw rejected(
                            "INTAKE_MATRIX_FACT_UNKNOWN",
                            "matrix draft references an unknown stable fact id");
                }
                requireStableFactBinding(prior, category, target);
                factId = key;
            } else {
                if ("PREVIOUS_MATRIX".equals(sourceScope)) {
                    throw rejected(
                            "INTAKE_MATRIX_SOURCE_SCOPE_INVALID",
                            "a new fact cannot cite only the previous matrix");
                }
                String fingerprint = fingerprint(category, target);
                factId = index.byFingerprint().getOrDefault(
                        fingerprint, stableFactId(authority.caseId(), category, target));
                prior = index.byId().get(factId);
            }
            if (resolvedKeys.putIfAbsent(key, factId) != null || !resolvedIds.add(factId)) {
                throw rejected(
                        "INTAKE_MATRIX_FACT_ID_CONFLICT",
                        "matrix draft resolves more than one row to the same stable fact id");
            }

            if ("PREVIOUS_MATRIX".equals(sourceScope)) {
                requireUnchangedPreviousRow(prior, materiality, position, asserted);
            }
            factRows.add(buildFactRow(
                    dossier,
                    prior,
                    factId,
                    category,
                    target,
                    materiality,
                    position,
                    asserted,
                    sourceScope,
                    authority));
        }

        ArrayNode summaryKeys = requireArray(draft, "summary_source_fact_keys", 1, 200);
        ArrayNode summaryIds = dossier.arrayNode();
        Set<String> seenSummary = new HashSet<>();
        for (JsonNode value : summaryKeys) {
            if (!value.isTextual() || !resolvedKeys.containsKey(value.textValue())) {
                throw rejected(
                        "INTAKE_MATRIX_SUMMARY_SOURCE_INVALID",
                        "matrix summary references an unknown draft fact key");
            }
            String id = resolvedKeys.get(value.textValue());
            if (!seenSummary.add(id)) {
                throw rejected(
                        "INTAKE_MATRIX_SUMMARY_SOURCE_INVALID",
                        "matrix summary contains a duplicate stable fact id");
            }
            summaryIds.add(id);
        }

        ObjectNode matrix = dossier.objectNode();
        matrix.put("schema_version", "unilateral_case_matrix.v1");
        if (index.version() == Long.MAX_VALUE) {
            throw rejected(
                    "INTAKE_MATRIX_VERSION_EXHAUSTED",
                    "unilateral matrix version cannot advance safely");
        }
        matrix.put("matrix_version", index.version() + 1);
        matrix.set("source_binding", sourceBinding(dossier, previous, authority));
        matrix.set("party_map", partyMap(dossier, authority));
        String caseSummary = firstText(
                dossier.path("case_story"), "one_sentence_summary", "summary");
        matrix.put("case_summary", requireBoundedValue(caseSummary, 20_000, "case summary"));
        matrix.set("summary_source_fact_ids", summaryIds);
        matrix.set("claim_resolution", claimResolution(dossier, authority));
        ObjectNode reportedAttitude = reportedRespondentAttitude(dossier, authority);
        if (reportedAttitude != null) {
            matrix.set("reported_respondent_attitude", reportedAttitude);
        }
        matrix.set("dispute_core_state", disputeCoreState(dossier));
        matrix.set("fact_rows", factRows);
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));
        validateProjection(matrix, authority);
        return matrix;
    }

    private static ObjectNode projectionFromInitiatorMatrix(
            ObjectNode dossier, ObjectNode formal, MatrixAuthority authority) {
        if (!"case_fact_matrix.v2".equals(formal.path("schema_version").asText())
                || !"INITIATOR_FROZEN".equals(formal.path("matrix_kind").asText())
                || !authority.caseId().equals(formal.path("case_id").asText())) {
            throw rejected(
                    "INTAKE_MATRIX_FORMAL_TRANSITION_FORBIDDEN",
                    "initiator revisions require the current INITIATOR_FROZEN matrix");
        }
        ObjectNode projection = dossier.objectNode();
        projection.put("schema_version", "unilateral_case_matrix.v1");
        projection.set("matrix_version", formal.required("matrix_version").deepCopy());

        ObjectNode sourceBinding = projection.putObject("source_binding");
        sourceBinding.put("case_id", authority.caseId());
        sourceBinding.put("source_stage", "INTAKE");
        sourceBinding.set("source_refs", formal.required("source_refs").deepCopy());
        JsonNode generation = formal.required("generation_ref");
        sourceBinding.set(
                "latest_source_ref", generation.required("latest_source_ref").deepCopy());
        sourceBinding.set(
                "source_context_hash", generation.required("source_context_hash").deepCopy());
        projection.set("party_map", formal.required("party_map").deepCopy());

        JsonNode overview = formal.required("case_overview");
        projection.set("case_summary", overview.required("neutral_summary").deepCopy());
        projection.set(
                "summary_source_fact_ids",
                overview.required("summary_source_fact_ids").deepCopy());
        JsonNode claims = formal.required("claims");
        projection.set("claim_resolution", claims.required("initiator_claim").deepCopy());
        if (claims.path("respondent_reported_by_initiator").isObject()) {
            projection.set(
                    "reported_respondent_attitude",
                    claims.required("respondent_reported_by_initiator").deepCopy());
        }

        if (dossier.path("dispute_core_state").isObject()) {
            projection.set("dispute_core_state", disputeCoreState(dossier));
        } else {
            ObjectNode core = projection.putObject("dispute_core_state");
            core.set("core_conflict", overview.required("core_conflict").deepCopy());
            core.putArray("facts_in_dispute");
            core.putArray("next_verification_focus");
        }

        ArrayNode rows = projection.putArray("fact_rows");
        for (JsonNode candidate : formal.withArray("fact_rows")) {
            ObjectNode formalRow = (ObjectNode) candidate;
            ObjectNode row = rows.addObject();
            for (String field : List.of("fact_id", "category", "fact_target", "materiality")) {
                row.set(field, formalRow.required(field).deepCopy());
            }
            ObjectNode origin = row.putObject("origin");
            origin.put("source_stage", "INTAKE");
            origin.set(
                    "source_refs",
                    formalRow.required("origin").required("source_refs").deepCopy());
            JsonNode direct = formalRow
                    .required("positions")
                    .required(authority.initiatorRole().name());
            ObjectNode initiatorPosition = row.putObject("initiator_position");
            for (String field : List.of(
                    "stance", "position_summary", "asserted_value", "source_refs")) {
                initiatorPosition.set(field, direct.required(field).deepCopy());
            }
            row.set("truth_status", formalRow.required("truth_status").deepCopy());
        }
        projection.put("content_hash", ContractJson.sha256Hex(projection));
        validateProjection(projection, authority);
        return projection;
    }

    private static ObjectNode buildFactRow(
            ObjectNode dossier,
            ObjectNode prior,
            String factId,
            String category,
            String target,
            String materiality,
            String position,
            String asserted,
            String sourceScope,
            MatrixAuthority authority) {
        List<String> priorOriginRefs = prior == null
                ? List.of()
                : textArray(prior.path("origin").path("source_refs"), "prior fact source refs", 50);
        List<String> priorPositionRefs = prior == null
                ? List.of()
                : textArray(
                        prior.path("initiator_position").path("source_refs"),
                        "prior position source refs",
                        50);
        List<String> originRefs = prior == null
                ? List.of(authority.sourceRef())
                : priorOriginRefs;
        List<String> positionRefs =
                sourceRefs(priorPositionRefs, sourceScope, authority.sourceRef());
        ObjectNode row = dossier.objectNode();
        row.put("fact_id", factId);
        row.put("category", category);
        row.put("fact_target", target);
        row.put("materiality", materiality);
        ObjectNode origin = row.putObject("origin");
        origin.put(
                "source_stage",
                prior == null
                        ? "INTAKE"
                        : requireEnum(prior.path("origin"), "source_stage", SOURCE_STAGES));
        origin.set("source_refs", toArray(dossier, originRefs));
        ObjectNode initiatorPosition = row.putObject("initiator_position");
        initiatorPosition.put(
                "stance",
                prior == null
                        ? "CONFIRM"
                        : requireEnum(
                                prior.path("initiator_position"), "stance", STANCES));
        initiatorPosition.put("position_summary", position);
        initiatorPosition.put("asserted_value", asserted);
        initiatorPosition.set("source_refs", toArray(dossier, positionRefs));
        row.put("truth_status", "NOT_EVALUATED");
        return row;
    }

    private static ObjectNode sourceBinding(
            ObjectNode dossier, ObjectNode previous, MatrixAuthority authority) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        if (previous != null) {
            refs.addAll(textArray(
                    previous.path("source_binding").path("source_refs"),
                    "matrix source refs",
                    256));
        }
        refs.add(authority.sourceRef());
        ObjectNode binding = dossier.objectNode();
        binding.put("case_id", authority.caseId());
        binding.put("source_stage", "INTAKE");
        binding.set("source_refs", toArray(dossier, refs));
        binding.put("latest_source_ref", authority.sourceRef());
        binding.put("source_context_hash", authority.sourceContextHash());
        return binding;
    }

    private static ObjectNode partyMap(ObjectNode dossier, MatrixAuthority authority) {
        ObjectNode map = dossier.objectNode();
        map.put("initiator_role", authority.initiatorRole().name());
        map.put("respondent_role", authority.respondentRole().name());
        return map;
    }

    private static ObjectNode claimResolution(ObjectNode dossier, MatrixAuthority authority) {
        JsonNode claim = dossier.path("claim_resolution");
        JsonNode requested = dossier.path("requested_resolution");
        if (!claim.isObject() && !requested.isObject()) {
            throw rejected(
                    "INTAKE_MATRIX_DOSSIER_INCOMPLETE",
                    "unilateral matrix requires a claim resolution branch");
        }
        String suppliedRole = firstText(claim, "initiator_role");
        if (suppliedRole != null && !authority.initiatorRole().name().equals(suppliedRole)) {
            throw rejected(
                    "INTAKE_MATRIX_PARTY_MISMATCH",
                    "claim initiator role conflicts with Java case authority");
        }
        ObjectNode result = dossier.objectNode();
        result.put("initiator_role", authority.initiatorRole().name());
        result.put(
                "requested_resolution",
                requireIdentifierValue(
                        firstNonNull(
                                firstText(claim, "requested_resolution"),
                                firstText(requested, "requested_resolution", "kind")),
                        "requested resolution"));
        JsonNode amount = claim.path("requested_amount");
        if (amount.isMissingNode() || amount.isNull()) {
            amount = requested.path("requested_amount");
        }
        if (!amount.isMissingNode() && !amount.isNull()) {
            if (!amount.isNumber() || amount.decimalValue().signum() < 0) {
                throw rejected(
                        "INTAKE_MATRIX_DOSSIER_INCOMPLETE",
                        "requested amount must be a non-negative number");
            }
            result.set("requested_amount", amount.deepCopy());
        }
        String items = firstNonNull(
                firstText(claim, "requested_items"), firstText(requested, "requested_items"));
        if (items != null) {
            result.put(
                    "requested_items",
                    requireBoundedValue(items, 2_000, "requested items"));
        }
        String summary = requireBoundedValue(
                firstText(dossier.path("case_story"), "one_sentence_summary", "summary"),
                20_000,
                "case summary");
        result.put(
                "reason_summary",
                requireBoundedValue(
                        firstNonNull(
                                firstText(claim, "reason_summary", "request_reason"),
                                firstText(requested, "reason_summary", "reason"),
                                summary),
                        20_000,
                        "claim reason summary"));
        result.put(
                "position_summary",
                requireBoundedValue(
                        firstNonNull(
                                firstText(claim, "position_summary", "normalized_statement"),
                                summary),
                        20_000,
                        "claim position summary"));
        result.set("source_refs", toArray(dossier, List.of(authority.sourceRef())));
        return result;
    }

    private static ObjectNode reportedRespondentAttitude(
            ObjectNode dossier, MatrixAuthority authority) {
        JsonNode attitude = dossier.path("respondent_attitude");
        if (!attitude.isObject() || attitude.isEmpty()) {
            return null;
        }
        String suppliedRole = firstText(attitude, "respondent_role");
        if (suppliedRole != null && !authority.respondentRole().name().equals(suppliedRole)) {
            throw rejected(
                    "INTAKE_MATRIX_PARTY_MISMATCH",
                    "reported respondent role conflicts with Java case authority");
        }
        ObjectNode result = dossier.objectNode();
        result.put("respondent_role", authority.respondentRole().name());
        String proposedAttitude = firstText(attitude, "attitude", "status");
        if (NO_RESPONSE_ATTITUDES.contains(proposedAttitude)) {
            return null;
        }
        String normalizedAttitude =
                requireIdentifierValue(proposedAttitude, "respondent attitude");
        if (!CLAIM_ATTITUDES.contains(normalizedAttitude)) {
            throw rejected(
                    "INTAKE_MATRIX_DOSSIER_INCOMPLETE",
                    "unilateral matrix respondent attitude is invalid");
        }
        result.put("attitude", normalizedAttitude);
        result.put(
                "position_summary",
                requireBoundedValue(
                        firstText(attitude, "position_summary", "position", "note"),
                        20_000,
                        "respondent position"));
        result.put("source_type", "INITIATOR_SUBJECTIVE_REPORT");
        result.set("source_refs", toArray(dossier, List.of(authority.sourceRef())));
        return result;
    }

    private static ObjectNode disputeCoreState(ObjectNode dossier) {
        JsonNode state = dossier.path("dispute_core_state");
        if (!state.isObject()) {
            if (dossier.has("dispute_core_state")) {
                throw rejected(
                        "INTAKE_MATRIX_DOSSIER_INCOMPLETE",
                        "unilateral matrix dispute core state branch is invalid");
            }
            state = deriveBaselineDisputeCoreState(dossier);
            dossier.set("dispute_core_state", state.deepCopy());
        }
        String coreConflict = firstText(state, "core_conflict", "core_issue");
        if (coreConflict == null) {
            ObjectNode baseline = deriveBaselineDisputeCoreState(dossier);
            coreConflict = firstText(baseline, "core_conflict");
        }

        JsonNode factsInDispute = firstPresent(
                state, "facts_in_dispute", "fact_disputes", "factual_disputes");
        if (factsInDispute.isMissingNode()) {
            factsInDispute = baselineFactsInDispute(dossier);
        }
        JsonNode nextVerificationFocus = firstPresent(
                state, "next_verification_focus", "verification_focus");
        if (nextVerificationFocus.isMissingNode()) {
            nextVerificationFocus = baselineNextVerificationFocus(dossier);
        }

        ObjectNode result = dossier.objectNode();
        result.put(
                "core_conflict",
                requireBoundedValue(coreConflict, 20_000, "core conflict"));
        result.set(
                "facts_in_dispute",
                optionalTextArray(dossier, factsInDispute, 50));
        result.set(
                "next_verification_focus",
                optionalTextArray(dossier, nextVerificationFocus, 20));

        ObjectNode normalizedState = dossier.objectNode();
        normalizedState.set("core_conflict", result.required("core_conflict").deepCopy());
        normalizedState.set("facts_in_dispute", result.required("facts_in_dispute").deepCopy());
        normalizedState.set(
                "next_verification_focus",
                result.required("next_verification_focus").deepCopy());
        String conflictType = firstText(state, "conflict_type");
        if (conflictType != null) {
            normalizedState.put(
                    "conflict_type",
                    requireIdentifierValue(conflictType, "dispute conflict type"));
        }
        dossier.set("dispute_core_state", normalizedState);
        return result;
    }

    private static ObjectNode deriveBaselineDisputeCoreState(ObjectNode dossier) {
        JsonNode focus = dossier.path("dispute_focus");
        String coreConflict = firstNonNull(
                firstText(focus, "core_conflict", "core_issue"),
                firstText(dossier.path("case_story"), "one_sentence_summary", "summary"));
        if (coreConflict == null) {
            throw rejected(
                    "INTAKE_MATRIX_DOSSIER_INCOMPLETE",
                    "unilateral matrix requires a dispute core state or case summary");
        }

        ObjectNode derived = dossier.objectNode();
        derived.put(
                "core_conflict",
                requireBoundedValue(coreConflict, 20_000, "core conflict"));
        derived.set(
                "facts_in_dispute",
                optionalTextArray(dossier, baselineFactsInDispute(dossier), 50));
        derived.set(
                "next_verification_focus",
                optionalTextArray(dossier, baselineNextVerificationFocus(dossier), 20));
        return derived;
    }

    private static JsonNode baselineFactsInDispute(ObjectNode dossier) {
        return firstPresent(
                dossier.path("dispute_focus"), "facts_in_dispute", "focus_points");
    }

    private static JsonNode baselineNextVerificationFocus(ObjectNode dossier) {
        JsonNode verification = firstPresent(
                dossier.path("dispute_focus"),
                "next_verification_focus",
                "facts_to_verify");
        if (!verification.isMissingNode()) {
            return verification;
        }
        return firstPresent(
                dossier.path("missing_information"),
                "next_verification_focus",
                "blocking_gaps",
                "missing_fields",
                "missing_facts");
    }

    private static JsonNode firstPresent(JsonNode owner, String... fields) {
        if (owner != null && owner.isObject()) {
            for (String field : fields) {
                JsonNode value = owner.get(field);
                if (value != null && !value.isNull()) {
                    return value;
                }
            }
        }
        return JsonNodeFactory.instance.missingNode();
    }

    private static void validateProjection(ObjectNode matrix, MatrixAuthority authority) {
        requireFields(matrix, MATRIX_REQUIRED_FIELDS, MATRIX_ALLOWED_FIELDS, "unilateral matrix");
        if (!"unilateral_case_matrix.v1".equals(matrix.path("schema_version").asText())) {
            throw projectionInvalid("unilateral matrix schema is invalid");
        }
        JsonNode version = matrix.path("matrix_version");
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 1) {
            throw projectionInvalid("unilateral matrix version is invalid");
        }
        ObjectNode hashInput = matrix.deepCopy();
        JsonNode storedHash = hashInput.remove("content_hash");
        if (storedHash == null
                || !storedHash.isTextual()
                || !storedHash.textValue().matches("[0-9a-f]{64}")
                || !storedHash.textValue().equals(ContractJson.sha256Hex(hashInput))) {
            throw rejected(
                    "INTAKE_MATRIX_CURRENT_HASH_INVALID",
                    "unilateral matrix content hash is not canonical");
        }

        ObjectNode sourceBinding = projectionObject(matrix, "source_binding");
        requireExactProjectionFields(sourceBinding, SOURCE_BINDING_FIELDS, "matrix source binding");
        String matrixCaseId = projectionIdentifier(sourceBinding, "case_id");
        if (!matrixCaseId.matches("CASE_[A-Za-z0-9_]{1,59}")
                || !authority.caseId().equals(matrixCaseId)
                || !"INTAKE".equals(projectionText(sourceBinding, "source_stage", 64))) {
            throw rejected(
                    "INTAKE_MATRIX_CURRENT_AUTHORITY_MISMATCH",
                    "unilateral matrix source binding conflicts with Java case authority");
        }
        Set<String> declaredSources = new LinkedHashSet<>(projectionTextArray(
                sourceBinding.path("source_refs"), "matrix source refs", 1, 256, 128, true));
        String latestSource = projectionIdentifier(sourceBinding, "latest_source_ref");
        if (!declaredSources.contains(latestSource)
                || !projectionText(sourceBinding, "source_context_hash", 64)
                        .matches("[0-9a-f]{64}")) {
            throw projectionInvalid("matrix latest source or source context hash is invalid");
        }

        ObjectNode partyMap = projectionObject(matrix, "party_map");
        requireExactProjectionFields(partyMap, PARTY_MAP_FIELDS, "matrix party map");
        if (!authority.initiatorRole().name().equals(
                        projectionText(partyMap, "initiator_role", 32))
                || !authority.respondentRole().name().equals(
                        projectionText(partyMap, "respondent_role", 32))) {
            throw rejected(
                    "INTAKE_MATRIX_CURRENT_AUTHORITY_MISMATCH",
                    "unilateral matrix party map conflicts with Java case authority");
        }
        projectionText(matrix, "case_summary", 20_000);

        JsonNode rows = matrix.path("fact_rows");
        if (!rows.isArray() || rows.isEmpty() || rows.size() > 200) {
            throw projectionInvalid("unilateral matrix fact rows are invalid");
        }
        Set<String> factIds = new LinkedHashSet<>();
        for (JsonNode value : rows) {
            ObjectNode row = requireProjectionObject(value, "fact row");
            requireExactProjectionFields(row, FACT_ROW_FIELDS, "matrix fact row");
            String factId = projectionText(row, "fact_id", 128);
            if (!factId.matches("FACT_[A-Za-z0-9_:-]{1,123}") || !factIds.add(factId)) {
                throw projectionInvalid("unilateral matrix fact ids are invalid");
            }
            if (!CATEGORIES.contains(projectionText(row, "category", 128))
                    || !MATERIALITIES.contains(projectionText(row, "materiality", 128))) {
                throw projectionInvalid("unilateral matrix fact enums are invalid");
            }
            projectionText(row, "fact_target", 20_000);
            if (!"NOT_EVALUATED".equals(projectionText(row, "truth_status", 64))) {
                throw projectionInvalid("unilateral matrix truth status is invalid");
            }
            ObjectNode origin = projectionObject(row, "origin");
            requireExactProjectionFields(origin, ORIGIN_FIELDS, "matrix fact origin");
            if (!SOURCE_STAGES.contains(projectionText(origin, "source_stage", 64))) {
                throw projectionInvalid("matrix fact source stage is invalid");
            }
            requireDeclaredSources(
                    projectionTextArray(
                            origin.path("source_refs"), "fact origin source refs", 1, 50, 128, true),
                    declaredSources);
            ObjectNode position = projectionObject(row, "initiator_position");
            requireExactProjectionFields(position, POSITION_FIELDS, "matrix fact position");
            if (!STANCES.contains(projectionText(position, "stance", 64))) {
                throw projectionInvalid("matrix fact stance is invalid");
            }
            projectionText(position, "position_summary", 20_000);
            projectionText(position, "asserted_value", 2_000);
            requireDeclaredSources(
                    projectionTextArray(
                            position.path("source_refs"),
                            "fact position source refs",
                            1,
                            50,
                            128,
                            true),
                    declaredSources);
        }

        List<String> summaryIds = projectionTextArray(
                matrix.path("summary_source_fact_ids"),
                "matrix summary source fact ids",
                1,
                200,
                128,
                true);
        if (!factIds.containsAll(summaryIds)) {
            throw projectionInvalid("matrix summary references an unknown fact id");
        }

        ObjectNode claim = projectionObject(matrix, "claim_resolution");
        requireFields(claim, CLAIM_REQUIRED_FIELDS, CLAIM_ALLOWED_FIELDS, "matrix claim");
        if (!authority.initiatorRole().name().equals(
                projectionText(claim, "initiator_role", 32))) {
            throw projectionInvalid("matrix claim initiator role is invalid");
        }
        projectionIdentifier(claim, "requested_resolution");
        JsonNode amount = claim.path("requested_amount");
        if (!amount.isMissingNode()
                && !amount.isNull()
                && (!amount.isNumber() || amount.decimalValue().signum() < 0)) {
            throw projectionInvalid("matrix requested amount is invalid");
        }
        if (claim.hasNonNull("requested_items")) {
            projectionText(claim, "requested_items", 2_000);
        }
        projectionText(claim, "reason_summary", 20_000);
        projectionText(claim, "position_summary", 20_000);
        requireDeclaredSources(
                projectionTextArray(
                        claim.path("source_refs"), "claim source refs", 1, 50, 128, true),
                declaredSources);

        if (matrix.hasNonNull("reported_respondent_attitude")) {
            ObjectNode attitude = projectionObject(matrix, "reported_respondent_attitude");
            requireExactProjectionFields(attitude, ATTITUDE_FIELDS, "reported respondent attitude");
            if (!authority.respondentRole().name().equals(
                            projectionText(attitude, "respondent_role", 32))
                    || !"INITIATOR_SUBJECTIVE_REPORT".equals(
                            projectionText(attitude, "source_type", 64))) {
                throw projectionInvalid("reported respondent authority is invalid");
            }
            if (!CLAIM_ATTITUDES.contains(projectionIdentifier(attitude, "attitude"))) {
                throw projectionInvalid("reported respondent attitude is invalid");
            }
            projectionText(attitude, "position_summary", 20_000);
            requireDeclaredSources(
                    projectionTextArray(
                            attitude.path("source_refs"),
                            "respondent attitude source refs",
                            1,
                            50,
                            128,
                            true),
                    declaredSources);
        }

        ObjectNode core = projectionObject(matrix, "dispute_core_state");
        requireExactProjectionFields(core, CORE_STATE_FIELDS, "matrix dispute core state");
        projectionText(core, "core_conflict", 20_000);
        projectionTextArray(
                core.path("facts_in_dispute"), "facts in dispute", 0, 50, 2_000, false);
        projectionTextArray(
                core.path("next_verification_focus"),
                "next verification focus",
                0,
                20,
                2_000,
                false);
    }

    private static void validatePrevious(ObjectNode previous, MatrixAuthority authority) {
        validateProjection(previous, authority);
    }

    private static void requireStableFactBinding(
            ObjectNode prior, String category, String target) {
        if (!category.equals(prior.path("category").asText())
                || !target.equals(prior.path("fact_target").asText())) {
            throw rejected(
                    "INTAKE_MATRIX_FACT_REBOUND",
                    "matrix draft rebinds a stable fact id");
        }
    }

    private static void requireUnchangedPreviousRow(
            ObjectNode prior, String materiality, String position, String asserted) {
        if (prior == null
                || !materiality.equals(prior.path("materiality").asText())
                || !position.equals(
                        prior.path("initiator_position").path("position_summary").asText())
                || !asserted.equals(
                        prior.path("initiator_position").path("asserted_value").asText())) {
            throw rejected(
                    "INTAKE_MATRIX_PREVIOUS_FACT_MUTATED",
                    "PREVIOUS_MATRIX scope cannot change an existing fact row");
        }
    }

    private static List<String> sourceRefs(
            List<String> previous, String scope, String current) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (!"CURRENT_SOURCE".equals(scope)) {
            values.addAll(previous);
        }
        if (!"PREVIOUS_MATRIX".equals(scope)) {
            values.add(current);
        }
        if (values.isEmpty()) {
            throw rejected("INTAKE_MATRIX_SOURCE_SCOPE_INVALID", "matrix fact has no source");
        }
        return List.copyOf(values);
    }

    private static String stableFactId(String caseId, String category, String target) {
        ObjectNode binding = JsonNodeFactory.instance.objectNode();
        binding.put("case_id", caseId);
        binding.put("category", category);
        binding.put("fact_target", target);
        return "FACT_" + ContractJson.sha256Hex(binding).substring(0, 24).toUpperCase();
    }

    private static String fingerprint(String category, String target) {
        ObjectNode binding = JsonNodeFactory.instance.objectNode();
        binding.put("category", category);
        binding.put("fact_target", target);
        return ContractJson.sha256Hex(binding);
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.addAll(right);
        return Set.copyOf(result);
    }

    private static void requireFields(
            ObjectNode value, Set<String> required, Set<String> allowed, String name) {
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
            throw projectionInvalid(name + " fields are invalid");
        }
    }

    private static void requireExactProjectionFields(
            ObjectNode value, Set<String> expected, String name) {
        requireFields(value, expected, expected, name);
    }

    private static ObjectNode projectionObject(JsonNode owner, String field) {
        return requireProjectionObject(owner.path(field), field);
    }

    private static ObjectNode requireProjectionObject(JsonNode value, String name) {
        if (!value.isObject()) {
            throw projectionInvalid(name + " is not an object");
        }
        return (ObjectNode) value;
    }

    private static String projectionText(JsonNode owner, String field, int maximum) {
        JsonNode value = owner.path(field);
        if (!value.isTextual()
                || value.textValue().isBlank()
                || value.textValue().length() > maximum) {
            throw projectionInvalid(field + " is invalid");
        }
        return value.textValue();
    }

    private static String projectionIdentifier(JsonNode owner, String field) {
        String value = projectionText(owner, field, 128);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw projectionInvalid(field + " is not an identifier");
        }
        return value;
    }

    private static List<String> projectionTextArray(
            JsonNode value,
            String name,
            int minimum,
            int maximum,
            int itemMaximum,
            boolean identifiers) {
        if (!value.isArray() || value.size() < minimum || value.size() > maximum) {
            throw projectionInvalid(name + " has an invalid size");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()
                    || item.textValue().isBlank()
                    || item.textValue().length() > itemMaximum
                    || (identifiers
                            && !item.textValue()
                                    .matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
                throw projectionInvalid(name + " contains an invalid value");
            }
            result.add(item.textValue());
        }
        return List.copyOf(result);
    }

    private static void requireDeclaredSources(
            List<String> referenced, Set<String> declared) {
        if (!declared.containsAll(referenced)) {
            throw projectionInvalid("matrix fact or claim references an undeclared source");
        }
    }

    private static IntakeFinalizationRejectedException projectionInvalid(String message) {
        return rejected("INTAKE_MATRIX_CURRENT_INVALID", message);
    }

    private static void requireExactFields(ObjectNode value, Set<String> allowed, String name) {
        Iterator<String> fields = value.fieldNames();
        Set<String> actual = new HashSet<>();
        fields.forEachRemaining(actual::add);
        if (!actual.equals(allowed)) {
            throw rejected(
                    "INTAKE_MATRIX_DERIVED_FIELD_FORBIDDEN",
                    name + " must contain only the frozen semantic draft fields");
        }
    }

    private static ArrayNode requireArray(
            JsonNode owner, String field, int minimum, int maximum) {
        JsonNode value = owner.path(field);
        if (!value.isArray() || value.size() < minimum || value.size() > maximum) {
            throw rejected("INTAKE_MATRIX_PATCH_INVALID", field + " has an invalid size");
        }
        return (ArrayNode) value;
    }

    private static String requireEnum(JsonNode owner, String field, Set<String> values) {
        String value = requireText(owner, field, 128);
        if (!values.contains(value)) {
            throw rejected("INTAKE_MATRIX_PATCH_INVALID", field + " is invalid");
        }
        return value;
    }

    private static String requireText(JsonNode owner, String field, int maximum) {
        JsonNode value = owner.path(field);
        if (!value.isTextual()
                || value.textValue().isBlank()
                || value.textValue().length() > maximum) {
            throw rejected("INTAKE_MATRIX_PATCH_INVALID", field + " is invalid");
        }
        return value.textValue();
    }

    private static String firstText(JsonNode owner, String... fields) {
        if (owner == null || !owner.isObject()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = owner.path(field);
            if (value.isTextual() && !value.textValue().isBlank()) {
                return value.textValue();
            }
        }
        return null;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String requireBoundedValue(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw rejected(
                    "INTAKE_MATRIX_DOSSIER_INCOMPLETE",
                    "unilateral matrix requires " + name);
        }
        return value;
    }

    private static String requireIdentifierValue(String value, String name) {
        value = requireBoundedValue(value, 128, name);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw rejected(
                    "INTAKE_MATRIX_DOSSIER_INCOMPLETE",
                    "unilateral matrix " + name + " is not an identifier");
        }
        return value;
    }

    private static List<String> textArray(JsonNode value, String name, int maximum) {
        if (!value.isArray() || value.isEmpty() || value.size() > maximum) {
            throw rejected("INTAKE_MATRIX_CURRENT_INVALID", name + " is invalid");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()
                    || item.textValue().isBlank()
                    || item.textValue().length() > 128
                    || !item.textValue().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw rejected("INTAKE_MATRIX_CURRENT_INVALID", name + " is invalid");
            }
            result.add(item.textValue());
        }
        return List.copyOf(result);
    }

    private static ArrayNode optionalTextArray(
            ObjectNode factory, JsonNode value, int maximum) {
        ArrayNode result = factory.arrayNode();
        if (value.isMissingNode() || value.isNull()) {
            return result;
        }
        if (!value.isArray() || value.size() > maximum) {
            throw rejected(
                    "INTAKE_MATRIX_DOSSIER_INCOMPLETE", "matrix dossier array is invalid");
        }
        for (JsonNode item : value) {
            if (!item.isTextual()
                    || item.textValue().isBlank()
                    || item.textValue().length() > 2_000) {
                throw rejected(
                        "INTAKE_MATRIX_DOSSIER_INCOMPLETE", "matrix dossier array is invalid");
            }
            result.add(item.textValue());
        }
        return result;
    }

    private static ArrayNode toArray(ObjectNode factory, Iterable<String> values) {
        ArrayNode result = factory.arrayNode();
        values.forEach(result::add);
        return result;
    }

    private static IntakeFinalizationRejectedException rejected(String code, String message) {
        return new IntakeFinalizationRejectedException(code, message);
    }

    private record PreviousIndex(
            long version, Map<String, ObjectNode> byId, Map<String, String> byFingerprint) {

        private static PreviousIndex from(ObjectNode previous) {
            if (previous == null) {
                return new PreviousIndex(0, Map.of(), Map.of());
            }
            Map<String, ObjectNode> byId = new HashMap<>();
            Map<String, String> byFingerprint = new HashMap<>();
            JsonNode rows = previous.path("fact_rows");
            if (!rows.isArray() || rows.isEmpty() || rows.size() > 200) {
                throw rejected(
                        "INTAKE_MATRIX_CURRENT_INVALID",
                        "persisted unilateral matrix fact rows are invalid");
            }
            for (JsonNode value : rows) {
                if (!value.isObject()) {
                    throw rejected(
                            "INTAKE_MATRIX_CURRENT_INVALID",
                            "persisted unilateral matrix fact row is invalid");
                }
                ObjectNode row = (ObjectNode) value;
                String id = requireText(row, "fact_id", 128);
                if (!id.matches("FACT_[A-Za-z0-9_:-]{1,123}")
                        || byId.putIfAbsent(id, row) != null) {
                    throw rejected(
                            "INTAKE_MATRIX_CURRENT_INVALID",
                            "persisted unilateral matrix fact ids are invalid");
                }
                String category = requireText(row, "category", 128);
                String target = requireText(row, "fact_target", 20_000);
                String prior = byFingerprint.putIfAbsent(fingerprint(category, target), id);
                if (prior != null && !prior.equals(id)) {
                    throw rejected(
                            "INTAKE_MATRIX_CURRENT_INVALID",
                            "persisted unilateral matrix duplicates a semantic fact");
                }
            }
            return new PreviousIndex(
                    previous.path("matrix_version").longValue(), Map.copyOf(byId), Map.copyOf(byFingerprint));
        }
    }
}
