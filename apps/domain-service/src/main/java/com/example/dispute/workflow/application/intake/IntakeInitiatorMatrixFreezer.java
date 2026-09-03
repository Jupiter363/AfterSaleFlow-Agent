package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.application.intake.IntakeDossierProjectionMerger.MatrixAuthority;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Converts a validated unilateral projection into Java-owned INITIATOR_FROZEN authority. */
public final class IntakeInitiatorMatrixFreezer {

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
    private static final Set<String> STANCES = Set.of("CONFIRM", "DENY", "PARTIAL", "UNKNOWN");
    private static final Set<String> CLAIM_ATTITUDES = Set.of(
            "AGREE",
            "PARTIALLY_AGREE",
            "DISAGREE",
            "ALTERNATIVE_PROPOSED",
            "NEED_MORE_INFO",
            "NOT_ADDRESSED");

    private final IntakeUnilateralMatrixPolicy unilateralPolicy =
            new IntakeUnilateralMatrixPolicy();

    public ObjectNode freeze(
            String caseId,
            ActorRole initiatorRole,
            ActorRole respondentRole,
            ObjectNode unilateral) {
        return freeze(caseId, initiatorRole, respondentRole, unilateral, null);
    }

    public ObjectNode freeze(
            String caseId,
            ActorRole initiatorRole,
            ActorRole respondentRole,
            ObjectNode unilateral,
            ObjectNode previousFrozen) {
        Objects.requireNonNull(unilateral, "unilateral");
        JsonNode sourceBinding = unilateral.path("source_binding");
        MatrixAuthority authority = new MatrixAuthority(
                caseId,
                initiatorRole,
                initiatorRole,
                respondentRole,
                requiredIdentifier(sourceBinding, "latest_source_ref"),
                requiredHash(sourceBinding, "source_context_hash"));
        unilateralPolicy.validateExisting(unilateral, authority);
        if (previousFrozen != null) {
            validateFrozen(previousFrozen, caseId, initiatorRole, respondentRole);
        }

        ObjectNode matrix = unilateral.objectNode();
        matrix.put("schema_version", "case_fact_matrix.v2");
        matrix.put("case_id", caseId);
        matrix.put(
                "matrix_id",
                "CASE_MATRIX_"
                        + ContractJson.sha256Hex(unilateral).substring(0, 20).toUpperCase());
        long matrixVersion = nextVersion(previousFrozen);
        if (previousFrozen != null
                && unilateral.path("matrix_version").longValue() != matrixVersion) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_VERSION_INVALID",
                    "initiator projection version does not advance its formal parent");
        }
        matrix.put("matrix_version", matrixVersion);
        matrix.put("matrix_kind", "INITIATOR_FROZEN");
        if (previousFrozen == null) {
            matrix.putNull("parent_ref");
        } else {
            matrix.set("parent_ref", parentRef(previousFrozen));
        }
        matrix.set("party_map", unilateral.required("party_map").deepCopy());
        matrix.set("source_refs", unilateral.required("source_binding").required("source_refs").deepCopy());
        matrix.set("case_overview", caseOverview(unilateral));
        matrix.set("claims", claims(unilateral));
        matrix.set("fact_rows", factRows(unilateral, initiatorRole, respondentRole));
        matrix.putArray("fact_relationships");
        matrix.set("generation_ref", generationRef(unilateral, initiatorRole));
        matrix.set("fact_indexes", factIndexes(matrix.withArray("fact_rows")));
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));
        validateFrozen(matrix, caseId, initiatorRole, respondentRole);
        if (previousFrozen != null) {
            validateRevision(matrix, previousFrozen, initiatorRole);
        }
        return matrix;
    }

    /**
     * Bridges a deployed unilateral projection into the unified formal lineage on its next turn.
     * The legacy projection remains the authoritative parent, so its version is neither reset nor
     * discarded.
     */
    public ObjectNode freezeLegacyRevision(
            String caseId,
            ActorRole initiatorRole,
            ActorRole respondentRole,
            ObjectNode unilateral,
            ObjectNode previousLegacy) {
        Objects.requireNonNull(unilateral, "unilateral");
        Objects.requireNonNull(previousLegacy, "previousLegacy");
        validateUnilateral(caseId, initiatorRole, respondentRole, unilateral);
        validateUnilateral(caseId, initiatorRole, respondentRole, previousLegacy);
        long previousVersion = previousLegacy.path("matrix_version").longValue();
        if (previousVersion == Long.MAX_VALUE
                || unilateral.path("matrix_version").longValue() != previousVersion + 1) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_VERSION_INVALID",
                    "initiator projection version does not advance its legacy parent");
        }

        ObjectNode matrix = buildFrozen(
                caseId,
                initiatorRole,
                respondentRole,
                unilateral,
                previousVersion + 1,
                legacyParentRef(previousLegacy));
        validateLegacyRevision(matrix, previousLegacy, initiatorRole);
        return matrix;
    }

    /**
     * Upgrades a deployed unilateral authority during confirmation without resetting its version
     * history. The schema transition is represented as the next version and binds the legacy
     * content hash as its parent.
     */
    public ObjectNode migrateLegacy(
            String caseId,
            ActorRole initiatorRole,
            ActorRole respondentRole,
            ObjectNode previousLegacy) {
        Objects.requireNonNull(previousLegacy, "previousLegacy");
        validateUnilateral(caseId, initiatorRole, respondentRole, previousLegacy);
        long previousVersion = previousLegacy.path("matrix_version").longValue();
        if (previousVersion == Long.MAX_VALUE) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_VERSION_INVALID",
                    "legacy initiator matrix version cannot advance safely");
        }
        ObjectNode migrationProjection = previousLegacy.deepCopy();
        migrationProjection.put("matrix_version", previousVersion + 1);
        migrationProjection.remove("content_hash");
        migrationProjection.put("content_hash", ContractJson.sha256Hex(migrationProjection));
        return freezeLegacyRevision(
                caseId,
                initiatorRole,
                respondentRole,
                migrationProjection,
                previousLegacy);
    }

    public void validateFrozen(
            ObjectNode matrix,
            String caseId,
            ActorRole initiatorRole,
            ActorRole respondentRole) {
        Objects.requireNonNull(matrix, "matrix");
        requireExactFields(matrix, MATRIX_FIELDS, "initiator matrix");
        if (!"case_fact_matrix.v2".equals(requiredText(matrix, "schema_version", 64))
                || !caseId.equals(requiredIdentifier(matrix, "case_id"))
                || !"INITIATOR_FROZEN".equals(requiredText(matrix, "matrix_kind", 64))
                || !matrix.path("matrix_version").isIntegralNumber()
                || !matrix.path("matrix_version").canConvertToLong()
                || matrix.path("matrix_version").longValue() < 1
                || !requiredIdentifier(matrix, "matrix_id").matches("CASE_MATRIX_[A-F0-9]{20}")) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID",
                    "initiator matrix does not match current Java case authority");
        }
        validateParentRef(matrix);
        ObjectNode hashInput = matrix.deepCopy();
        JsonNode storedHash = hashInput.remove("content_hash");
        if (storedHash == null
                || !storedHash.isTextual()
                || !storedHash.asText().matches("[0-9a-f]{64}")
                || !storedHash.asText().equals(ContractJson.sha256Hex(hashInput))) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_HASH_INVALID",
                    "initiator matrix content hash is not canonical");
        }
        ObjectNode partyMap = requiredObject(matrix, "party_map", "initiator matrix party map");
        requireExactFields(partyMap, PARTY_MAP_FIELDS, "initiator matrix party map");
        if (!initiatorRole.name().equals(requiredText(partyMap, "initiator_role", 32))
                || !respondentRole.name().equals(requiredText(partyMap, "respondent_role", 32))
                || initiatorRole == respondentRole) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID",
                    "initiator matrix party map does not match current Java authority");
        }
        List<String> sourceRefs = requiredTextArray(
                matrix, "source_refs", 1, 256, 128, "initiator matrix source refs");
        Set<String> declaredSources = new HashSet<>(sourceRefs);
        ObjectNode generation = requiredObject(matrix, "generation_ref", "initiator matrix generation");
        requireExactFields(generation, GENERATION_FIELDS, "initiator matrix generation");
        String latestSource = requiredIdentifier(generation, "latest_source_ref");
        if (!initiatorRole.name().equals(requiredText(generation, "actor_role", 32))
                || !"INITIATOR_INTAKE".equals(requiredText(generation, "source_stage", 64))
                || !declaredSources.contains(latestSource)
                || !requiredHash(generation, "source_context_hash").matches("[0-9a-f]{64}")) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_SOURCE_INVALID",
                    "initiator matrix generation does not bind the declared source authority");
        }
        validateOverview(matrix);
        validateClaims(matrix, initiatorRole, respondentRole, declaredSources);
        JsonNode relationships = matrix.path("fact_relationships");
        if (!relationships.isArray() || !relationships.isEmpty()) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_RELATIONSHIPS_INVALID",
                    "initiator matrix cannot contain derived fact relationships");
        }
        JsonNode rows = matrix.path("fact_rows");
        if (!rows.isArray() || rows.isEmpty() || rows.size() > 200) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_ROWS_INVALID",
                    "initiator matrix fact rows are invalid");
        }
        List<String> ids = new java.util.ArrayList<>();
        List<String> coreIds = new java.util.ArrayList<>();
        for (JsonNode row : rows) {
            validateRow(
                    row,
                    initiatorRole,
                    respondentRole,
                    declaredSources,
                    ids,
                    coreIds);
        }
        validateOverviewFactIds(matrix, ids);
        validateIndexes(matrix, ids, coreIds);
    }

    private static long nextVersion(ObjectNode previous) {
        if (previous == null) {
            return 1;
        }
        long version = previous.path("matrix_version").longValue();
        if (version == Long.MAX_VALUE) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_VERSION_INVALID",
                    "initiator matrix version cannot advance safely");
        }
        return version + 1;
    }

    private static ObjectNode parentRef(ObjectNode previous) {
        ObjectNode parent = previous.objectNode();
        parent.set("matrix_id", previous.required("matrix_id").deepCopy());
        parent.set("matrix_version", previous.required("matrix_version").deepCopy());
        parent.set("content_hash", previous.required("content_hash").deepCopy());
        return parent;
    }

    private static void validateParentRef(ObjectNode matrix) {
        long version = matrix.path("matrix_version").longValue();
        JsonNode parent = matrix.path("parent_ref");
        if (version == 1) {
            requireNull(matrix, "parent_ref", "initiator matrix parent reference");
            return;
        }
        if (!parent.isObject()) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_PARENT_INVALID",
                    "revised initiator matrix requires a formal parent reference");
        }
        ObjectNode parentObject = (ObjectNode) parent;
        requireExactFields(parentObject, PARENT_FIELDS, "initiator matrix parent reference");
        if (!requiredIdentifier(parentObject, "matrix_id").matches("CASE_MATRIX_[A-F0-9]{20}")
                || !parentObject.path("matrix_version").isIntegralNumber()
                || parentObject.path("matrix_version").longValue() != version - 1
                || !requiredHash(parentObject, "content_hash").matches("[0-9a-f]{64}")) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_PARENT_INVALID",
                    "initiator matrix parent reference is invalid");
        }
    }

    private static void validateRevision(
            ObjectNode matrix, ObjectNode previous, ActorRole initiatorRole) {
        if (!matrix.path("parent_ref").path("matrix_id").equals(previous.path("matrix_id"))
                || !matrix.path("parent_ref")
                        .path("matrix_version")
                        .equals(previous.path("matrix_version"))
                || !matrix.path("parent_ref")
                        .path("content_hash")
                        .equals(previous.path("content_hash"))) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_PARENT_INVALID",
                    "initiator matrix parent reference does not match current authority");
        }
        Set<String> currentSources = new HashSet<>(requiredTextArray(
                matrix, "source_refs", 1, 256, 128, "initiator matrix source refs"));
        Set<String> previousSources = new HashSet<>(requiredTextArray(
                previous, "source_refs", 1, 256, 128, "initiator matrix source refs"));
        if (!currentSources.containsAll(previousSources)) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_SOURCE_INVALID",
                    "initiator revision drops prior matrix source authority");
        }

        java.util.Map<String, JsonNode> currentRows = new java.util.HashMap<>();
        matrix.withArray("fact_rows")
                .forEach(row -> currentRows.put(row.path("fact_id").asText(), row));
        for (JsonNode previousRow : previous.withArray("fact_rows")) {
            JsonNode currentRow = currentRows.get(previousRow.path("fact_id").asText());
            if (currentRow == null
                    || !currentRow.path("category").equals(previousRow.path("category"))
                    || !currentRow.path("fact_target").equals(previousRow.path("fact_target"))
                    || !currentRow.path("materiality").equals(previousRow.path("materiality"))
                    || !containsAllText(
                            currentRow.path("origin").path("source_refs"),
                            previousRow.path("origin").path("source_refs"))
                    || !containsAllText(
                            currentRow
                                    .path("positions")
                                    .path(initiatorRole.name())
                                    .path("source_refs"),
                            previousRow
                                    .path("positions")
                                    .path(initiatorRole.name())
                                    .path("source_refs"))) {
                throw rejected(
                        "INTAKE_INITIATOR_MATRIX_STABLE_AUTHORITY_INVALID",
                        "initiator revision drops or rebinds a stable fact authority");
            }
        }
    }

    private static void validateLegacyRevision(
            ObjectNode matrix, ObjectNode previous, ActorRole initiatorRole) {
        JsonNode parent = matrix.path("parent_ref");
        if (!parent.path("matrix_id").equals(legacyMatrixId(previous))
                || !parent.path("matrix_version").equals(previous.path("matrix_version"))
                || !parent.path("content_hash").equals(previous.path("content_hash"))) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_PARENT_INVALID",
                    "initiator matrix parent reference does not match legacy authority");
        }
        Set<String> currentSources = new HashSet<>(requiredTextArray(
                matrix, "source_refs", 1, 256, 128, "initiator matrix source refs"));
        Set<String> previousSources = new HashSet<>(requiredTextArray(
                requiredObject(previous, "source_binding", "legacy initiator matrix source binding"),
                "source_refs",
                1,
                256,
                128,
                "legacy initiator matrix source refs"));
        if (!currentSources.containsAll(previousSources)) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_SOURCE_INVALID",
                    "initiator revision drops prior legacy source authority");
        }

        java.util.Map<String, JsonNode> currentRows = new java.util.HashMap<>();
        matrix.withArray("fact_rows")
                .forEach(row -> currentRows.put(row.path("fact_id").asText(), row));
        for (JsonNode previousRow : previous.withArray("fact_rows")) {
            JsonNode currentRow = currentRows.get(previousRow.path("fact_id").asText());
            if (currentRow == null
                    || !currentRow.path("category").equals(previousRow.path("category"))
                    || !currentRow.path("fact_target").equals(previousRow.path("fact_target"))
                    || !currentRow.path("materiality").equals(previousRow.path("materiality"))
                    || !containsAllText(
                            currentRow.path("origin").path("source_refs"),
                            previousRow.path("origin").path("source_refs"))
                    || !containsAllText(
                            currentRow
                                    .path("positions")
                                    .path(initiatorRole.name())
                                    .path("source_refs"),
                            previousRow.path("initiator_position").path("source_refs"))) {
                throw rejected(
                        "INTAKE_INITIATOR_MATRIX_STABLE_AUTHORITY_INVALID",
                        "initiator revision drops or rebinds legacy fact authority");
            }
        }
    }

    private ObjectNode buildFrozen(
            String caseId,
            ActorRole initiatorRole,
            ActorRole respondentRole,
            ObjectNode unilateral,
            long matrixVersion,
            JsonNode parentRef) {
        ObjectNode matrix = unilateral.objectNode();
        matrix.put("schema_version", "case_fact_matrix.v2");
        matrix.put("case_id", caseId);
        matrix.put(
                "matrix_id",
                "CASE_MATRIX_"
                        + ContractJson.sha256Hex(unilateral).substring(0, 20).toUpperCase());
        matrix.put("matrix_version", matrixVersion);
        matrix.put("matrix_kind", "INITIATOR_FROZEN");
        matrix.set("parent_ref", parentRef.deepCopy());
        matrix.set("party_map", unilateral.required("party_map").deepCopy());
        matrix.set(
                "source_refs",
                unilateral.required("source_binding").required("source_refs").deepCopy());
        matrix.set("case_overview", caseOverview(unilateral));
        matrix.set("claims", claims(unilateral));
        matrix.set("fact_rows", factRows(unilateral, initiatorRole, respondentRole));
        matrix.putArray("fact_relationships");
        matrix.set("generation_ref", generationRef(unilateral, initiatorRole));
        matrix.set("fact_indexes", factIndexes(matrix.withArray("fact_rows")));
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));
        validateFrozen(matrix, caseId, initiatorRole, respondentRole);
        return matrix;
    }

    private void validateUnilateral(
            String caseId,
            ActorRole initiatorRole,
            ActorRole respondentRole,
            ObjectNode unilateral) {
        JsonNode sourceBinding = unilateral.path("source_binding");
        MatrixAuthority authority = new MatrixAuthority(
                caseId,
                initiatorRole,
                initiatorRole,
                respondentRole,
                requiredIdentifier(sourceBinding, "latest_source_ref"),
                requiredHash(sourceBinding, "source_context_hash"));
        unilateralPolicy.validateExisting(unilateral, authority);
    }

    private static ObjectNode legacyParentRef(ObjectNode previous) {
        ObjectNode parent = previous.objectNode();
        parent.set("matrix_id", legacyMatrixId(previous));
        parent.set("matrix_version", previous.required("matrix_version").deepCopy());
        parent.set("content_hash", previous.required("content_hash").deepCopy());
        return parent;
    }

    private static JsonNode legacyMatrixId(ObjectNode previous) {
        return previous.textNode(
                "CASE_MATRIX_"
                        + requiredHash(previous, "content_hash")
                                .substring(0, 20)
                                .toUpperCase());
    }

    private static boolean containsAllText(JsonNode current, JsonNode previous) {
        Set<String> currentValues = new HashSet<>();
        current.forEach(value -> currentValues.add(value.asText()));
        for (JsonNode value : previous) {
            if (!currentValues.contains(value.asText())) {
                return false;
            }
        }
        return true;
    }

    private static ObjectNode caseOverview(ObjectNode unilateral) {
        ObjectNode overview = unilateral.objectNode();
        overview.put("neutral_summary", requiredText(unilateral, "case_summary", 20_000));
        overview.put(
                "core_conflict",
                requiredText(unilateral.path("dispute_core_state"), "core_conflict", 20_000));
        overview.set(
                "summary_source_fact_ids",
                unilateral.required("summary_source_fact_ids").deepCopy());
        return overview;
    }

    private static ObjectNode claims(ObjectNode unilateral) {
        ObjectNode claims = unilateral.objectNode();
        claims.set("initiator_claim", unilateral.required("claim_resolution").deepCopy());
        JsonNode reported = unilateral.path("reported_respondent_attitude");
        if (reported.isObject()) {
            claims.set("respondent_reported_by_initiator", reported.deepCopy());
        } else {
            claims.putNull("respondent_reported_by_initiator");
        }
        claims.putNull("respondent_direct");
        claims.putNull("claim_conflict");
        return claims;
    }

    private static ArrayNode factRows(
            ObjectNode unilateral, ActorRole initiatorRole, ActorRole respondentRole) {
        ArrayNode rows = unilateral.arrayNode();
        for (JsonNode candidate : unilateral.withArray("fact_rows")) {
            ObjectNode source = (ObjectNode) candidate;
            ObjectNode row = rows.addObject();
            for (String field : List.of("fact_id", "category", "fact_target", "materiality")) {
                row.set(field, source.required(field).deepCopy());
            }
            ObjectNode origin = row.putObject("origin");
            origin.put("introduced_stage", "INITIATOR_INTAKE");
            origin.set("source_refs", source.required("origin").required("source_refs").deepCopy());
            ObjectNode positions = row.putObject("positions");
            ObjectNode direct = positions.putObject(initiatorRole.name());
            JsonNode position = source.required("initiator_position");
            direct.set("stance", position.required("stance").deepCopy());
            direct.set("position_summary", position.required("position_summary").deepCopy());
            direct.set("asserted_value", position.required("asserted_value").deepCopy());
            direct.put("source_type", "DIRECT_PARTY_STATEMENT");
            direct.set("source_refs", position.required("source_refs").deepCopy());
            ObjectNode absent = positions.putObject(respondentRole.name());
            absent.put("stance", "NOT_ADDRESSED");
            absent.put("position_summary", "该方尚未直接陈述。");
            absent.putNull("asserted_value");
            absent.put("source_type", "NO_DIRECT_POSITION");
            absent.putArray("source_refs");
            ObjectNode alignment = row.putObject("party_alignment");
            alignment.put("status", "NOT_COMPUTED");
            alignment.putNull("agreed_statement");
            alignment.putNull("conflict_summary");
            row.putNull("requires_resolution");
            row.put("truth_status", "NOT_EVALUATED");
            row.put("evidence_coverage_status", "PENDING_EVIDENCE_REVIEW");
        }
        return rows;
    }

    private static ObjectNode generationRef(ObjectNode unilateral, ActorRole initiatorRole) {
        JsonNode source = unilateral.required("source_binding");
        ObjectNode generation = unilateral.objectNode();
        generation.put("actor_role", initiatorRole.name());
        generation.put("source_stage", "INITIATOR_INTAKE");
        generation.set("latest_source_ref", source.required("latest_source_ref").deepCopy());
        generation.set("source_context_hash", source.required("source_context_hash").deepCopy());
        return generation;
    }

    private static ObjectNode factIndexes(ArrayNode rows) {
        ObjectNode indexes = rows.objectNode();
        ArrayNode notComputed = indexes.putArray("not_computed_fact_ids");
        ArrayNode core = indexes.putArray("core_fact_ids");
        for (JsonNode row : rows) {
            notComputed.add(row.path("fact_id").asText());
            if ("CORE".equals(row.path("materiality").asText())) {
                core.add(row.path("fact_id").asText());
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

    private static void validateOverview(ObjectNode matrix) {
        ObjectNode overview = requiredObject(matrix, "case_overview", "initiator matrix overview");
        requireExactFields(overview, OVERVIEW_FIELDS, "initiator matrix overview");
        requiredText(overview, "neutral_summary", 20_000);
        requiredText(overview, "core_conflict", 20_000);
    }

    private static void validateClaims(
            ObjectNode matrix,
            ActorRole initiatorRole,
            ActorRole respondentRole,
            Set<String> declaredSources) {
        ObjectNode claims = requiredObject(matrix, "claims", "initiator matrix claims");
        requireExactFields(claims, CLAIM_FIELDS, "initiator matrix claims");
        ObjectNode initiatorClaim = requiredObject(
                claims, "initiator_claim", "initiator matrix initiator claim");
        requireFields(
                initiatorClaim,
                INITIATOR_CLAIM_REQUIRED_FIELDS,
                INITIATOR_CLAIM_ALLOWED_FIELDS,
                "initiator matrix initiator claim");
        if (!initiatorRole.name().equals(requiredText(initiatorClaim, "initiator_role", 32))
                || !requiredIdentifier(initiatorClaim, "requested_resolution").matches(
                        "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_CLAIM_INVALID",
                    "initiator claim does not match the Java party authority");
        }
        JsonNode requestedAmount = initiatorClaim.path("requested_amount");
        if (!requestedAmount.isMissingNode()
                && !requestedAmount.isNull()
                && (!requestedAmount.isNumber() || requestedAmount.decimalValue().signum() < 0)) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_CLAIM_INVALID",
                    "initiator requested amount is invalid");
        }
        if (initiatorClaim.hasNonNull("requested_items")) {
            requiredText(initiatorClaim, "requested_items", 2_000);
        }
        requiredText(initiatorClaim, "reason_summary", 20_000);
        requiredText(initiatorClaim, "position_summary", 20_000);
        List<String> initiatorClaimSources = requiredTextArray(
                initiatorClaim,
                "source_refs",
                1,
                50,
                128,
                "initiator claim source refs");
        requireDeclaredSources(
                initiatorClaimSources,
                declaredSources,
                "initiator claim source refs");
        requireNull(claims, "respondent_direct", "initiator matrix respondent direct claim");
        requireNull(claims, "claim_conflict", "initiator matrix claim conflict");

        JsonNode reported = claims.path("respondent_reported_by_initiator");
        if (reported.isNull()) {
            return;
        }
        if (!reported.isObject()) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_CLAIM_INVALID",
                    "reported respondent claim must be an object or null");
        }
        ObjectNode attitude = (ObjectNode) reported;
        requireExactFields(attitude, REPORTED_RESPONDENT_FIELDS, "reported respondent claim");
        if (!respondentRole.name().equals(requiredText(attitude, "respondent_role", 32))
                || !CLAIM_ATTITUDES.contains(requiredIdentifier(attitude, "attitude"))
                || !"INITIATOR_SUBJECTIVE_REPORT".equals(
                        requiredText(attitude, "source_type", 64))) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_CLAIM_INVALID",
                    "reported respondent claim is not an initiator-scoped report");
        }
        requiredText(attitude, "position_summary", 20_000);
        List<String> reportedSources = requiredTextArray(
                attitude,
                "source_refs",
                1,
                50,
                128,
                "reported respondent source refs");
        requireDeclaredSources(
                reportedSources,
                declaredSources,
                "reported respondent source refs");
    }

    private static void validateRow(
            JsonNode candidate,
            ActorRole initiatorRole,
            ActorRole respondentRole,
            Set<String> declaredSources,
            List<String> ids,
            List<String> coreIds) {
        if (!candidate.isObject()) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_ROWS_INVALID",
                    "initiator matrix fact row must be an object");
        }
        ObjectNode row = (ObjectNode) candidate;
        requireExactFields(row, ROW_FIELDS, "initiator matrix fact row");
        String factId = requiredText(row, "fact_id", 128);
        String materiality = requiredText(row, "materiality", 32);
        if (!factId.matches("FACT_[A-Za-z0-9_:-]{1,123}")
                || ids.contains(factId)
                || !CATEGORIES.contains(requiredText(row, "category", 64))
                || !MATERIALITIES.contains(materiality)) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_ROWS_INVALID",
                    "initiator matrix fact identifiers or enums are invalid");
        }
        ids.add(factId);
        if ("CORE".equals(materiality)) {
            coreIds.add(factId);
        }
        requiredText(row, "fact_target", 20_000);
        if (!"NOT_EVALUATED".equals(requiredText(row, "truth_status", 64))
                || !"PENDING_EVIDENCE_REVIEW".equals(
                        requiredText(row, "evidence_coverage_status", 64))
                || !row.path("requires_resolution").isNull()) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_ROWS_INVALID",
                    "initiator matrix derived fact fields are invalid");
        }
        ObjectNode origin = requiredObject(row, "origin", "initiator matrix fact origin");
        requireExactFields(origin, ORIGIN_FIELDS, "initiator matrix fact origin");
        if (!"INITIATOR_INTAKE".equals(requiredText(origin, "introduced_stage", 64))) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_ROWS_INVALID",
                    "initiator matrix fact origin is not initiator-owned");
        }
        List<String> originSources = requiredTextArray(
                origin, "source_refs", 1, 50, 128, "fact origin source refs");
        requireDeclaredSources(
                originSources,
                declaredSources,
                "fact origin source refs");
        ObjectNode positions = requiredObject(row, "positions", "initiator matrix fact positions");
        requireExactFields(
                positions,
                Set.of(initiatorRole.name(), respondentRole.name()),
                "initiator matrix fact positions");
        ObjectNode initiator = requiredObject(
                positions, initiatorRole.name(), "initiator matrix direct party position");
        requireExactFields(initiator, POSITION_FIELDS, "initiator matrix direct party position");
        if (!STANCES.contains(requiredText(initiator, "stance", 64))
                || !"DIRECT_PARTY_STATEMENT".equals(requiredText(initiator, "source_type", 64))) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_ROWS_INVALID",
                    "initiator direct party position is invalid");
        }
        requiredText(initiator, "position_summary", 20_000);
        JsonNode assertedValue = initiator.required("asserted_value");
        if (!assertedValue.isNull()) {
            requiredText(initiator, "asserted_value", 2_000);
        }
        List<String> initiatorSources = requiredTextArray(
                initiator, "source_refs", 1, 50, 128, "initiator fact source refs");
        requireDeclaredSources(
                initiatorSources,
                declaredSources,
                "initiator fact source refs");
        ObjectNode respondent = requiredObject(
                positions, respondentRole.name(), "initiator matrix absent respondent position");
        requireExactFields(respondent, POSITION_FIELDS, "initiator matrix absent respondent position");
        if (!"NOT_ADDRESSED".equals(requiredText(respondent, "stance", 64))
                || !"该方尚未直接陈述。".equals(
                        requiredText(respondent, "position_summary", 20_000))
                || !respondent.path("asserted_value").isNull()
                || !"NO_DIRECT_POSITION".equals(requiredText(respondent, "source_type", 64))
                || !respondent.path("source_refs").isArray()
                || !respondent.path("source_refs").isEmpty()) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_ROWS_INVALID",
                    "initiator matrix must not fabricate a respondent position");
        }
        ObjectNode alignment = requiredObject(row, "party_alignment", "initiator matrix alignment");
        requireExactFields(alignment, ALIGNMENT_FIELDS, "initiator matrix alignment");
        if (!"NOT_COMPUTED".equals(requiredText(alignment, "status", 64))
                || !alignment.path("agreed_statement").isNull()
                || !alignment.path("conflict_summary").isNull()) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_ROWS_INVALID",
                    "initiator matrix cannot contain bilateral alignment");
        }
    }

    private static void validateOverviewFactIds(ObjectNode matrix, List<String> ids) {
        ObjectNode overview = requiredObject(matrix, "case_overview", "initiator matrix overview");
        List<String> summaryIds = requiredTextArray(
                overview,
                "summary_source_fact_ids",
                1,
                200,
                128,
                "initiator matrix summary source facts");
        if (!new HashSet<>(ids).containsAll(summaryIds)) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_SUMMARY_INVALID",
                    "initiator matrix summary references an unknown fact");
        }
    }

    private static void validateIndexes(
            ObjectNode matrix, List<String> ids, List<String> coreIds) {
        ObjectNode indexes = requiredObject(matrix, "fact_indexes", "initiator matrix fact indexes");
        requireExactFields(indexes, INDEX_FIELDS, "initiator matrix fact indexes");
        requireExactIndex(
                indexes.path("not_computed_fact_ids"), ids, "not computed fact indexes");
        requireExactIndex(indexes.path("core_fact_ids"), coreIds, "core fact indexes");
        for (String key : List.of(
                "agreed_fact_ids",
                "partially_agreed_fact_ids",
                "contested_fact_ids",
                "one_sided_fact_ids",
                "unresolved_fact_ids",
                "requires_resolution_fact_ids")) {
            requireExactIndex(indexes.path(key), List.of(), key);
        }
    }

    private static ObjectNode requiredObject(JsonNode owner, String field, String label) {
        JsonNode value = owner.path(field);
        if (!value.isObject()) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_STRUCTURE_INVALID",
                    label + " must be an object");
        }
        return (ObjectNode) value;
    }

    private static void requireExactFields(ObjectNode value, Set<String> expected, String label) {
        Set<String> actual = new HashSet<>();
        Iterator<String> names = value.fieldNames();
        while (names.hasNext()) {
            actual.add(names.next());
        }
        if (!actual.equals(expected)) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_STRUCTURE_INVALID",
                    label + " has unexpected or missing fields");
        }
    }

    private static void requireFields(
            ObjectNode value, Set<String> required, Set<String> allowed, String label) {
        Set<String> actual = new HashSet<>();
        Iterator<String> names = value.fieldNames();
        while (names.hasNext()) {
            actual.add(names.next());
        }
        if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_STRUCTURE_INVALID",
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
                    "INTAKE_INITIATOR_MATRIX_STRUCTURE_INVALID",
                    label + " has an invalid length");
        }
        List<String> result = new java.util.ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual()
                    || value.asText().isBlank()
                    || value.asText().length() > itemMaximum
                    || !value.asText().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                    || !unique.add(value.asText())) {
                throw rejected(
                        "INTAKE_INITIATOR_MATRIX_STRUCTURE_INVALID",
                        label + " contains an invalid source or fact identifier");
            }
            result.add(value.asText());
        }
        return result;
    }

    private static void requireDeclaredSources(
            List<String> references,
            Set<String> declaredSources,
            String label) {
        if (!declaredSources.containsAll(references)) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_SOURCE_INVALID",
                    label + " is not bound to a declared source");
        }
    }

    private static void requireNull(ObjectNode owner, String field, String label) {
        if (!owner.path(field).isNull()) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_STRUCTURE_INVALID",
                    label + " must be null");
        }
    }

    private static String requiredIdentifier(JsonNode owner, String field) {
        String value = requiredText(owner, field, 128);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw rejected("INTAKE_INITIATOR_MATRIX_SOURCE_INVALID", field + " is invalid");
        }
        return value;
    }

    private static String requiredHash(JsonNode owner, String field) {
        String value = requiredText(owner, field, 64);
        if (!value.matches("[0-9a-f]{64}")) {
            throw rejected("INTAKE_INITIATOR_MATRIX_SOURCE_INVALID", field + " is invalid");
        }
        return value;
    }

    private static String requiredText(JsonNode owner, String field, int maximum) {
        JsonNode value = owner.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maximum) {
            throw rejected("INTAKE_INITIATOR_MATRIX_INVALID", field + " is invalid");
        }
        return value.asText();
    }

    private static void requireExactIndex(
            JsonNode index, List<String> expected, String label) {
        if (!index.isArray() || index.size() != expected.size()) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_INDEX_INVALID",
                    "initiator matrix " + label + " do not match fact rows");
        }
        List<String> actual = new java.util.ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode value : index) {
            String factId = value.isTextual() ? value.asText() : "";
            if (!factId.matches("FACT_[A-Za-z0-9_:-]{1,123}") || !unique.add(factId)) {
                throw rejected(
                        "INTAKE_INITIATOR_MATRIX_INDEX_INVALID",
                        "initiator matrix " + label + " contains invalid fact ids");
            }
            actual.add(factId);
        }
        if (!expected.equals(actual)) {
            throw rejected(
                    "INTAKE_INITIATOR_MATRIX_INDEX_INVALID",
                    "initiator matrix " + label + " do not match fact rows");
        }
    }

    private static IntakeFinalizationRejectedException rejected(String code, String message) {
        return new IntakeFinalizationRejectedException(code, message);
    }
}
