package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeDossierProjectionMerger.MatrixAuthority;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeInitiatorMatrixFreezer;
import com.example.dispute.workflow.application.intake.IntakeRespondentMatrixFreezer;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class IntakeRespondentMatrixFreezerTest {

    private static final String CASE_ID = "CASE_RESPONDENT_MATRIX";
    private static final String PRIOR_FACT_ID = "FACT_DELIVERY_SCOPE";
    private static final JsonMapper JSON = new JsonMapper();

    private final IntakeRespondentMatrixFreezer freezer =
            new IntakeRespondentMatrixFreezer();

    @Test
    void derivesTheCompleteBilateralAuthorityDeterministically() throws Exception {
        ObjectNode parent = initiatorMatrix();
        ObjectNode original = parent.deepCopy();
        JsonNode delta = completeDelta();

        ObjectNode first = freezer.deriveCandidate(parent, delta, respondentAuthority());
        ObjectNode repeated = freezer.deriveCandidate(
                parent.deepCopy(), delta.deepCopy(), respondentAuthority());
        ObjectNode explicitNulls = (ObjectNode) delta.deepCopy();
        ((ObjectNode) explicitNulls.at("/fact_rows/0")).putNull("conflict_summary");
        ((ObjectNode) explicitNulls.at("/fact_rows/1")).putNull("agreed_statement");
        ((ObjectNode) explicitNulls.at("/fact_rows/1")).putNull("conflict_summary");
        ((ObjectNode) explicitNulls.at("/respondent_claim"))
                .putNull("alternative_proposal");
        ObjectNode normalized = freezer.deriveCandidate(
                parent.deepCopy(), explicitNulls, respondentAuthority());

        assertThat(parent).isEqualTo(original);
        assertThat(repeated).isEqualTo(first);
        assertThat(normalized).isEqualTo(first);
        assertThat(first.path("matrix_kind").asText()).isEqualTo("BILATERAL_FROZEN");
        assertThat(first.path("matrix_version").asLong()).isEqualTo(2);
        assertThat(first.path("matrix_id").asText()).matches("CASE_MATRIX_[A-F0-9]{20}");
        assertThat(first.at("/parent_ref/matrix_id")).isEqualTo(parent.path("matrix_id"));
        assertThat(first.at("/parent_ref/content_hash")).isEqualTo(parent.path("content_hash"));
        assertThat(first.at("/generation_ref/actor_role").asText()).isEqualTo("MERCHANT");
        assertThat(first.at("/generation_ref/source_stage").asText())
                .isEqualTo("RESPONDENT_INTAKE");
        assertThat(first.at("/generation_ref/latest_source_ref").asText())
                .isEqualTo("MESSAGE_RESPONDENT_2");
        assertThat(first.at("/generation_ref/source_context_hash").asText())
                .isEqualTo("b".repeat(64));
        assertThat(first.path("source_refs").size()).isEqualTo(2);
        assertThat(first.at("/source_refs/0").asText()).isEqualTo("MESSAGE_INITIATOR_1");
        assertThat(first.at("/source_refs/1").asText()).isEqualTo("MESSAGE_RESPONDENT_2");

        assertThat(first.at("/fact_rows/0/fact_id").asText()).isEqualTo(PRIOR_FACT_ID);
        assertThat(first.at("/fact_rows/0/positions/USER"))
                .isEqualTo(parent.at("/fact_rows/0/positions/USER"));
        assertThat(first.at("/fact_rows/0/positions/MERCHANT/source_refs/0").asText())
                .isEqualTo("MESSAGE_RESPONDENT_2");
        assertThat(first.at("/fact_rows/0/party_alignment/status").asText())
                .isEqualTo("AGREED");
        assertThat(first.at("/fact_rows/0/requires_resolution").asBoolean()).isFalse();

        String newFactId = first.at("/fact_rows/1/fact_id").asText();
        assertThat(newFactId).matches("FACT_[A-F0-9]{24}");
        assertThat(newFactId).isNotEqualTo(PRIOR_FACT_ID);
        assertThat(first.at("/fact_rows/1/origin/introduced_stage").asText())
                .isEqualTo("RESPONDENT_INTAKE");
        assertThat(first.at("/fact_rows/1/positions/USER/stance").asText())
                .isEqualTo("NOT_ADDRESSED");
        assertThat(first.at("/fact_rows/1/party_alignment/status").asText())
                .isEqualTo("ONE_SIDED");
        assertThat(first.at("/fact_rows/1/requires_resolution").asBoolean()).isTrue();
        assertThat(first.at("/fact_indexes/agreed_fact_ids/0").asText())
                .isEqualTo(PRIOR_FACT_ID);
        assertThat(first.at("/fact_indexes/one_sided_fact_ids/0").asText())
                .isEqualTo(newFactId);
        assertThat(first.at("/fact_indexes/core_fact_ids").size()).isEqualTo(2);
        assertThat(first.at("/fact_indexes/requires_resolution_fact_ids/0").asText())
                .isEqualTo(newFactId);
        assertThat(first.at("/case_overview/summary_source_fact_ids/0").asText())
                .isEqualTo(PRIOR_FACT_ID);
        assertThat(first.at("/case_overview/summary_source_fact_ids/1").asText())
                .isEqualTo(newFactId);
        assertThat(first.at("/claims/respondent_direct/respondent_role").asText())
                .isEqualTo("MERCHANT");
        assertThat(first.at("/claims/respondent_direct/source_type").asText())
                .isEqualTo("RESPONDENT_DIRECT_INTAKE");
        assertThat(first.at("/claims/respondent_direct/source_refs/0").asText())
                .isEqualTo("MESSAGE_RESPONDENT_2");

        ObjectNode hashInput = first.deepCopy();
        String contentHash = hashInput.remove("content_hash").asText();
        assertThat(contentHash).isEqualTo(ContractJson.sha256Hex(hashInput));
    }

    @Test
    void openingBilateralAuthorityIsTheDeterministicParentOfTheNextRespondentTurn()
            throws Exception {
        ObjectNode opening = openingBilateralMatrix();
        ObjectNode original = opening.deepCopy();
        JsonNode delta = ordinaryRespondentDelta(opening);

        ObjectNode first = freezer.deriveCandidate(
                opening, delta, respondentAuthority());
        ObjectNode replay = freezer.deriveCandidate(
                opening.deepCopy(), delta.deepCopy(), respondentAuthority());

        assertThat(opening).isEqualTo(original);
        assertThat(replay).isEqualTo(first);
        assertThat(first.path("matrix_kind").asText()).isEqualTo("BILATERAL_FROZEN");
        assertThat(first.path("matrix_version").asLong()).isEqualTo(7);
        assertThat(first.path("parent_ref")).isEqualTo(parentRef(opening));
        assertThat(first.at("/generation_ref/latest_source_ref").asText())
                .isEqualTo("MESSAGE_RESPONDENT_2");
        assertThat(first.at("/claims/respondent_direct/source_refs/0").asText())
                .isEqualTo("MESSAGE_RESPONDENT_2");
    }

    @Test
    void respondentRoundsCanonicalizeSelectedSummaryFactsToFinalRowOrder()
            throws Exception {
        ObjectNode initiator = initiatorMatrixWithFiveOrderedFormalFacts();
        ArrayNode initiatorSummary =
                (ArrayNode) initiator.at("/case_overview/summary_source_fact_ids");
        assertThat(initiator.path("matrix_kind").asText()).isEqualTo("INITIATOR_FROZEN");
        assertThat(initiator.path("matrix_version").asLong()).isEqualTo(5);
        assertThat(initiatorSummary.size()).isEqualTo(5);
        for (int index = 0; index < initiatorSummary.size(); index++) {
            assertThat(initiatorSummary.get(index))
                    .isEqualTo(initiator.at("/fact_rows/" + index + "/fact_id"));
        }

        String firstFactKey = "NEW_RESPONDENT_ROUND_ONE";
        ObjectNode firstDelta = respondentDeltaAppendingFormalFact(
                initiator,
                firstFactKey,
                "Whether the first respondent update adds a formal fact.");
        assertThat(firstDelta.at("/fact_rows/0/fact_key").asText())
                .isEqualTo(firstFactKey);
        assertThat(firstDelta.at("/summary_source_fact_keys/0"))
                .isEqualTo(initiatorSummary.get(0));
        assertThat(firstDelta.at("/summary_source_fact_keys/5").asText())
                .isEqualTo(firstFactKey);

        ObjectNode first = freezer.deriveCandidate(
                initiator, firstDelta, respondentAuthority());
        ArrayNode firstSummary =
                (ArrayNode) first.at("/case_overview/summary_source_fact_ids");
        String firstAppendedFactId = first.at("/fact_rows/0/fact_id").asText();
        ArrayNode firstFactIdsInRowOrder = JSON.createArrayNode();
        first.path("fact_rows").forEach(row ->
                firstFactIdsInRowOrder.add(row.path("fact_id").asText()));
        assertThat(first.path("matrix_kind").asText()).isEqualTo("BILATERAL_FROZEN");
        assertThat(first.path("matrix_version").asLong()).isEqualTo(6);
        assertThat(first.path("parent_ref")).isEqualTo(parentRef(initiator));
        assertThat(firstSummary.size()).isEqualTo(6);
        assertThat(firstSummary).isEqualTo(firstFactIdsInRowOrder);
        assertThat(firstSummary.get(0).asText()).isEqualTo(firstAppendedFactId);
        freezer.requireCompleteForFreeze(first, respondentAuthority());

        String secondFactKey = "NEW_RESPONDENT_ROUND_TWO";
        ObjectNode secondDelta = respondentDeltaAppendingFormalFact(
                first,
                secondFactKey,
                "Whether the second respondent update adds a formal fact.");
        assertThat(secondDelta.at("/fact_rows/0/fact_key").asText())
                .isEqualTo(secondFactKey);
        assertThat(secondDelta.at("/summary_source_fact_keys/0"))
                .isEqualTo(firstSummary.get(0));
        assertThat(secondDelta.at("/summary_source_fact_keys/6").asText())
                .isEqualTo(secondFactKey);

        ObjectNode second = freezer.deriveCandidate(
                first, secondDelta, nextRespondentAuthority());
        ArrayNode secondSummary =
                (ArrayNode) second.at("/case_overview/summary_source_fact_ids");
        String secondAppendedFactId = second.at("/fact_rows/0/fact_id").asText();
        ArrayNode secondFactIdsInRowOrder = JSON.createArrayNode();
        second.path("fact_rows").forEach(row ->
                secondFactIdsInRowOrder.add(row.path("fact_id").asText()));
        assertThat(second.path("matrix_version").asLong()).isEqualTo(7);
        assertThat(second.path("parent_ref")).isEqualTo(parentRef(first));
        assertThat(secondSummary.size()).isEqualTo(7);
        assertThat(secondSummary).isEqualTo(secondFactIdsInRowOrder);
        assertThat(secondSummary.get(0).asText()).isEqualTo(secondAppendedFactId);
    }

    @Test
    void openingBilateralParentFailsClosedForRecursiveAuthorityDrift() throws Exception {
        assertInvalidOpeningParent(parent ->
                ((ObjectNode) parent.at("/fact_rows/0"))
                        .put("fact_target", "A stale-hash mutation."), false);
        assertInvalidOpeningParent(parent ->
                ((ObjectNode) parent.path("parent_ref"))
                        .put("matrix_version", parent.path("matrix_version").asLong()), true);
        assertInvalidOpeningParent(parent ->
                ((ObjectNode) parent.path("parent_ref"))
                        .put("content_hash", "f".repeat(63)), true);
        assertInvalidOpeningParent(parent ->
                ((ObjectNode) parent.path("party_map"))
                        .put("respondent_role", "USER"), true);
        assertInvalidOpeningParent(parent ->
                ((ObjectNode) parent.path("generation_ref"))
                        .put("actor_role", "USER"), true);
        assertInvalidOpeningParent(parent ->
                ((ArrayNode) parent.path("source_refs")).remove(
                        parent.path("source_refs").size() - 1), true);
        assertInvalidOpeningParent(parent ->
                ((ArrayNode) parent.at("/claims/initiator_claim/source_refs"))
                        .add("MESSAGE_FORGED"), true);
        assertInvalidOpeningParent(parent -> {
            ArrayNode rows = (ArrayNode) parent.path("fact_rows");
            JsonNode first = rows.remove(0);
            rows.add(first);
        }, true);
        assertInvalidOpeningParent(parent ->
                ((ObjectNode) parent.at("/fact_rows/0/party_alignment"))
                        .put("status", "AGREED"), true);
        assertInvalidOpeningParent(parent ->
                ((ArrayNode) parent.at("/fact_indexes/core_fact_ids")).remove(0), true);
        assertInvalidOpeningParent(parent -> {
            ArrayNode summary =
                    (ArrayNode) parent.at("/case_overview/summary_source_fact_ids");
            JsonNode first = summary.remove(0);
            summary.add(first);
        }, true);

        ObjectNode opening = openingBilateralMatrix();
        ObjectNode wrongSource = ordinaryRespondentDelta(opening);
        ((ObjectNode) wrongSource.at("/fact_rows/0"))
                .put("source_scope", "PREVIOUS_MATRIX");
        ObjectNode rebound = freezer.deriveCandidate(
                opening, wrongSource, respondentAuthority());
        assertThat(rebound.at("/fact_rows/0/positions/MERCHANT/source_refs/0").asText())
                .isEqualTo("MESSAGE_RESPONDENT_2");

        assertThatThrownBy(() -> freezer.deriveCandidate(
                        openingBilateralMatrix(),
                        ordinaryRespondentDelta(openingBilateralMatrix()),
                        initiatorAuthority()))
                .isInstanceOf(IntakeFinalizationRejectedException.class);
    }

    @Test
    void openingBilateralParentRejectsCanonicalLatestSourceRebinding() throws Exception {
        ObjectNode parent = openingBilateralMatrix();
        assertThat(parent.path("source_refs"))
                .anySatisfy(source -> assertThat(source.asText())
                        .isEqualTo("MESSAGE_INITIATOR_1"));
        ((ObjectNode) parent.path("generation_ref"))
                .put("latest_source_ref", "MESSAGE_INITIATOR_1");
        rehashBilateral(parent);

        assertRejected(
                "INTAKE_RESPONDENT_MATRIX_SOURCE_SCOPE_INVALID",
                () -> freezer.deriveCandidate(
                        parent, ordinaryRespondentDelta(parent), respondentAuthority()));
    }

    @Test
    void bilateralDirectClaimRejectsCanonicalOlderSourceRebinding() throws Exception {
        ObjectNode parent = respondentBilateralMatrixWithDirectClaim();
        assertThat(parent.at("/generation_ref/latest_source_ref").asText())
                .isEqualTo("MESSAGE_RESPONDENT_2");
        ArrayNode directSources =
                (ArrayNode) parent.at("/claims/respondent_direct/source_refs");
        directSources.removeAll();
        directSources.add("MESSAGE_INITIATOR_1");
        rehashBilateral(parent);

        assertRejected(
                "INTAKE_RESPONDENT_MATRIX_SOURCE_SCOPE_INVALID",
                () -> freezer.deriveCandidate(
                        parent,
                        carryRespondentDelta(parent),
                        nextRespondentAuthority()));
    }

    @Test
    void bilateralParentRejectsCanonicalAlignmentDriftFromUnchangedPositions()
            throws Exception {
        ObjectNode parent = respondentBilateralMatrixWithDirectClaim();
        ObjectNode row = (ObjectNode) parent.at("/fact_rows/0");
        JsonNode positions = row.path("positions").deepCopy();
        String factId = row.path("fact_id").asText();
        assertThat(row.at("/party_alignment/status").asText()).isEqualTo("CONTESTED");
        ((ObjectNode) row.path("party_alignment")).put("status", "ONE_SIDED");
        ArrayNode contested =
                (ArrayNode) parent.at("/fact_indexes/contested_fact_ids");
        for (int index = 0; index < contested.size(); index++) {
            if (factId.equals(contested.get(index).asText())) {
                contested.remove(index);
                break;
            }
        }
        ((ArrayNode) parent.at("/fact_indexes/one_sided_fact_ids")).add(factId);
        rehashBilateral(parent);
        assertThat(row.path("positions")).isEqualTo(positions);

        assertRejected(
                "INTAKE_RESPONDENT_MATRIX_ALIGNMENT_INVALID",
                () -> freezer.deriveCandidate(
                        parent,
                        carryRespondentDelta(parent),
                        nextRespondentAuthority()));
    }

    @Test
    void bilateralParentRejectsCanonicalMaximumVersionBeforeSuccessorOverflow()
            throws Exception {
        ObjectNode bilateral = openingBilateralMatrix();
        bilateral.put("matrix_version", Long.MAX_VALUE);
        ((ObjectNode) bilateral.path("parent_ref"))
                .put("matrix_version", Long.MAX_VALUE - 1);
        rehashBilateral(bilateral);
        assertRejected(
                "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                () -> freezer.deriveCandidate(
                        bilateral,
                        ordinaryRespondentDelta(bilateral),
                        respondentAuthority()));
    }

    @Test
    void initiatorParentRejectsMaximumVersionBeforeSuccessorOverflow()
            throws Exception {
        ObjectNode initiator = initiatorMatrixWithLineage();
        assertThat(initiator.path("parent_ref").isObject()).isTrue();
        initiator.put("matrix_version", Long.MAX_VALUE);
        ((ObjectNode) initiator.path("parent_ref"))
                .put("matrix_version", Long.MAX_VALUE - 1);
        rehash(initiator);
        new IntakeInitiatorMatrixFreezer().validateFrozen(
                initiator,
                CASE_ID,
                ActorRole.USER,
                ActorRole.MERCHANT);
        assertRejected(
                "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                () -> freezer.deriveCandidate(
                        initiator,
                        ordinaryRespondentDelta(initiator),
                        respondentAuthority()));
    }

    @Test
    void rejectsActorsWithoutJavaRespondentAuthority() throws Exception {
        assertRejected(
                "INTAKE_RESPONDENT_MATRIX_AUTHORITY_INVALID",
                () -> freezer.deriveCandidate(
                        initiatorMatrix(), completeDelta(), initiatorAuthority()));
    }

    @Test
    void carriesAnUnaddressedPriorFactWithoutFabricatingRespondentSources() throws Exception {
        ObjectNode delta = JSON.createObjectNode();
        delta.put("schema_version", "case_fact_matrix.delta.v2");
        delta.putArray("fact_rows")
                .addObject()
                .put("fact_key", PRIOR_FACT_ID)
                .put("category", "FULFILLMENT")
                .put("fact_target", "Whether the promised installation was delivered.")
                .put("materiality", "CORE")
                .put("stance", "NOT_ADDRESSED")
                .put("position_summary", "The respondent has not addressed this fact.")
                .putNull("asserted_value")
                .put("source_scope", "PREVIOUS_MATRIX");
        delta.putArray("summary_source_fact_keys").add(PRIOR_FACT_ID);

        ObjectNode parent = initiatorMatrix();
        ObjectNode candidate = freezer.deriveCandidate(parent, delta, respondentAuthority());

        assertThat(candidate.at("/fact_rows/0/positions/MERCHANT"))
                .isEqualTo(parent.at("/fact_rows/0/positions/MERCHANT"));
        assertThat(candidate.at("/fact_rows/0/positions/MERCHANT/source_refs").isEmpty())
                .isTrue();
        assertThat(candidate.at("/fact_rows/0/origin/source_refs").size()).isEqualTo(1);
        assertThat(candidate.at("/fact_rows/0/party_alignment/status").asText())
                .isEqualTo("ONE_SIDED");
        assertThat(candidate.at("/fact_rows/0/requires_resolution").asBoolean()).isTrue();
        freezer.requireCompleteForFreeze(candidate, respondentAuthority());
    }

    @Test
    void derivesTheSameAuthorityWhenTheMerchantIsTheInitiator() throws Exception {
        ObjectNode unilateral = unilateral();
        unilateral.withObjectProperty("party_map")
                .put("initiator_role", "MERCHANT")
                .put("respondent_role", "USER");
        unilateral.withObjectProperty("claim_resolution")
                .put("initiator_role", "MERCHANT");
        rehash(unilateral);
        ObjectNode parent = new IntakeInitiatorMatrixFreezer()
                .freeze(CASE_ID, ActorRole.MERCHANT, ActorRole.USER, unilateral);
        MatrixAuthority authority = new MatrixAuthority(
                CASE_ID,
                ActorRole.USER,
                ActorRole.MERCHANT,
                ActorRole.USER,
                "MESSAGE_USER_RESPONDENT_2",
                "d".repeat(64));

        ObjectNode candidate = freezer.deriveCandidate(parent, completeDelta(), authority);

        assertThat(candidate.at("/party_map/initiator_role").asText()).isEqualTo("MERCHANT");
        assertThat(candidate.at("/party_map/respondent_role").asText()).isEqualTo("USER");
        assertThat(candidate.at("/fact_rows/0/positions/MERCHANT"))
                .isEqualTo(parent.at("/fact_rows/0/positions/MERCHANT"));
        assertThat(candidate.at("/fact_rows/0/positions/USER/source_refs/0").asText())
                .isEqualTo("MESSAGE_USER_RESPONDENT_2");
        assertThat(candidate.at("/claims/respondent_direct/respondent_role").asText())
                .isEqualTo("USER");
        freezer.requireCompleteForFreeze(candidate, authority);
    }

    @Test
    void canonicalizesParentAliasAndRejectsInvalidFactAuthority() throws Exception {
        ObjectNode parent = initiatorMatrix();
        String canonicalId = parent.at("/fact_rows/0/fact_id").asText();

        ObjectNode stableAlias = (ObjectNode) completeDelta();
        ObjectNode stableAliasRow = (ObjectNode) stableAlias.at("/fact_rows/0");
        stableAliasRow.put("fact_key", "FACT_INTAKE_PRIVATE_ALIAS");
        stableAliasRow.put("source_scope", "PREVIOUS_AND_CURRENT_SOURCE");
        stableAlias.withArray("summary_source_fact_keys")
                .set(0, JSON.getNodeFactory().textNode("FACT_INTAKE_PRIVATE_ALIAS"));
        ObjectNode stableAliasCandidate =
                freezer.deriveCandidate(parent, stableAlias, respondentAuthority());
        assertThat(stableAliasCandidate.path("fact_rows").size()).isEqualTo(2);
        assertThat(stableAliasCandidate.at("/fact_rows/0/fact_id").asText())
                .isEqualTo(canonicalId);
        assertThat(stableAliasCandidate.at("/case_overview/summary_source_fact_ids/0").asText())
                .isEqualTo(canonicalId);
        assertThat(stableAliasCandidate.at("/fact_rows/0/origin/source_refs/0").asText())
                .isEqualTo("MESSAGE_INITIATOR_1");
        assertThat(stableAliasCandidate.at("/fact_rows/0/origin/source_refs/1").asText())
                .isEqualTo("MESSAGE_RESPONDENT_2");
        assertThat(stableAliasCandidate
                        .at("/fact_rows/0/positions/MERCHANT/source_refs/0")
                        .asText())
                .isEqualTo("MESSAGE_RESPONDENT_2");

        ObjectNode unknown = (ObjectNode) completeDelta();
        ObjectNode unknownRow = (ObjectNode) unknown.at("/fact_rows/0");
        unknownRow.put("fact_key", "FACT_UNKNOWN");
        unknownRow.put("category", "LOGISTICS");
        unknownRow.put("fact_target", "An unmatched logistics fact.");
        unknown.withArray("summary_source_fact_keys")
                .set(0, JSON.getNodeFactory().textNode("FACT_UNKNOWN"));
        assertRejected(
                "INTAKE_RESPONDENT_MATRIX_FACT_UNKNOWN",
                () -> freezer.deriveCandidate(parent, unknown, respondentAuthority()));

        ObjectNode rebound = (ObjectNode) completeDelta();
        ObjectNode reboundRow = (ObjectNode) rebound.at("/fact_rows/0");
        reboundRow.put("fact_key", "FACT_INTAKE_REBOUND_ALIAS");
        reboundRow.put("materiality", "SUPPORTING");
        rebound.withArray("summary_source_fact_keys")
                .set(0, JSON.getNodeFactory().textNode("FACT_INTAKE_REBOUND_ALIAS"));
        assertRejected(
                "INTAKE_RESPONDENT_MATRIX_FACT_REBOUND",
                () -> freezer.deriveCandidate(parent, rebound, respondentAuthority()));

        ObjectNode missing = (ObjectNode) completeDelta();
        missing.withArray("fact_rows").remove(0);
        missing.withArray("summary_source_fact_keys").remove(0);
        assertRejected(
                "INTAKE_RESPONDENT_MATRIX_PRIOR_FACT_MISSING",
                () -> freezer.deriveCandidate(parent, missing, respondentAuthority()));

        ObjectNode collision = (ObjectNode) completeDelta();
        ObjectNode newRow = (ObjectNode) collision.at("/fact_rows/1");
        newRow.put("category", "FULFILLMENT");
        newRow.put("fact_target", "Whether the promised installation was delivered.");
        assertRejected(
                "INTAKE_RESPONDENT_MATRIX_NEW_FACT_COLLISION",
                () -> freezer.deriveCandidate(parent, collision, respondentAuthority()));

        ObjectNode duplicateAlias = (ObjectNode) completeDelta();
        ObjectNode duplicateAliasRow = (ObjectNode) duplicateAlias.at("/fact_rows/1");
        duplicateAliasRow.put("fact_key", "FACT_INTAKE_DUPLICATE_ALIAS");
        duplicateAliasRow.put("category", "FULFILLMENT");
        duplicateAliasRow.put("fact_target", "Whether the promised installation was delivered.");
        duplicateAlias.withArray("summary_source_fact_keys")
                .set(1, JSON.getNodeFactory().textNode("FACT_INTAKE_DUPLICATE_ALIAS"));
        assertRejected(
                "INTAKE_RESPONDENT_MATRIX_FACT_DUPLICATE",
                () -> freezer.deriveCandidate(parent, duplicateAlias, respondentAuthority()));

        ObjectNode duplicateParent = initiatorMatrix();
        ObjectNode duplicateRow = ((ObjectNode) duplicateParent.at("/fact_rows/0"))
                .deepCopy();
        duplicateRow.put("fact_id", "FACT_DUPLICATE_SEMANTIC_BINDING");
        duplicateParent.withArray("fact_rows").add(duplicateRow);
        duplicateParent.withObjectProperty("fact_indexes")
                .withArray("not_computed_fact_ids")
                .add("FACT_DUPLICATE_SEMANTIC_BINDING");
        duplicateParent.withObjectProperty("fact_indexes")
                .withArray("core_fact_ids")
                .add("FACT_DUPLICATE_SEMANTIC_BINDING");
        rehash(duplicateParent);
        assertRejected(
                "INTAKE_RESPONDENT_MATRIX_PARENT_INVALID",
                () -> freezer.deriveCandidate(
                        duplicateParent, completeDelta(), respondentAuthority()));
    }

    @Test
    void enforcesExactDeltaShapeRelationalRulesAndSourceMembership() throws Exception {
        ObjectNode derivedField = (ObjectNode) completeDelta();
        ((ObjectNode) derivedField.at("/fact_rows/0")).put("fact_id", "FACT_MODEL_OWNED");
        assertRejected(
                "INTAKE_RESPONDENT_MATRIX_DELTA_INVALID",
                () -> freezer.deriveCandidate(
                        initiatorMatrix(), derivedField, respondentAuthority()));

        ObjectNode badSummary = (ObjectNode) completeDelta();
        badSummary.withArray("summary_source_fact_keys").add("NEW_NOT_IN_ROWS");
        assertRejected(
                "INTAKE_RESPONDENT_MATRIX_DELTA_SUMMARY_INVALID",
                () -> freezer.deriveCandidate(
                        initiatorMatrix(), badSummary, respondentAuthority()));

        ObjectNode unaddressedCurrent = (ObjectNode) completeDelta();
        ObjectNode first = (ObjectNode) unaddressedCurrent.at("/fact_rows/0");
        first.put("stance", "NOT_ADDRESSED");
        first.putNull("asserted_value");
        first.put("source_scope", "CURRENT_SOURCE");
        assertRejected(
                "INTAKE_RESPONDENT_MATRIX_DELTA_RELATION_INVALID",
                () -> freezer.deriveCandidate(
                        initiatorMatrix(), unaddressedCurrent, respondentAuthority()));

        ObjectNode substantivePrevious = (ObjectNode) completeDelta();
        ((ObjectNode) substantivePrevious.at("/fact_rows/0"))
                .put("source_scope", "PREVIOUS_MATRIX");
        ObjectNode reboundSubstantive = freezer.deriveCandidate(
                initiatorMatrix(), substantivePrevious, respondentAuthority());
        assertThat(reboundSubstantive
                        .at("/fact_rows/0/positions/MERCHANT/source_refs/0")
                        .asText())
                .isEqualTo("MESSAGE_RESPONDENT_2");

        ObjectNode newPreviousAndCurrent = (ObjectNode) completeDelta();
        ((ObjectNode) newPreviousAndCurrent.at("/fact_rows/1"))
                .put("source_scope", "PREVIOUS_AND_CURRENT_SOURCE");
        ObjectNode accepted = freezer.deriveCandidate(
                initiatorMatrix(), newPreviousAndCurrent, respondentAuthority());
        assertThat(accepted.at("/fact_rows/1/origin/source_refs/0").asText())
                .isEqualTo("MESSAGE_RESPONDENT_2");
    }

    @Test
    void explicitCurrentSourceUnknownIsAReadyEvidenceResolutionPosition() throws Exception {
        ObjectNode delta = (ObjectNode) completeDelta();
        ObjectNode row = (ObjectNode) delta.at("/fact_rows/0");
        row.put("stance", "UNKNOWN");
        row.putNull("asserted_value");

        ObjectNode candidate = freezer.deriveCandidate(
                initiatorMatrix(), delta, respondentAuthority());

        assertThat(candidate.at("/fact_rows/0/positions/MERCHANT/stance").asText())
                .isEqualTo("UNKNOWN");
        assertThat(candidate.at("/fact_rows/0/positions/MERCHANT/source_type").asText())
                .isEqualTo("DIRECT_PARTY_STATEMENT");
        assertThat(candidate.at("/fact_rows/0/positions/MERCHANT/source_refs/0").asText())
                .isEqualTo("MESSAGE_RESPONDENT_2");
        assertThat(candidate.at("/fact_rows/0/party_alignment/status").asText())
                .isEqualTo("ONE_SIDED");
        assertThat(candidate.at("/fact_rows/0/requires_resolution").asBoolean()).isTrue();

        freezer.requireCompleteForFreeze(candidate, respondentAuthority());
    }

    private static ObjectNode initiatorMatrix() {
        return new IntakeInitiatorMatrixFreezer()
                .freeze(CASE_ID, ActorRole.USER, ActorRole.MERCHANT, unilateral());
    }

    private ObjectNode openingBilateralMatrix() throws Exception {
        ObjectNode parent = initiatorMatrixWithLineage();
        ObjectNode opening = freezer.deriveCandidate(
                parent, respondentOpeningCarry(parent), respondentOpeningAuthority());
        assertThat(opening.path("matrix_version").asLong()).isEqualTo(6);
        assertThat(opening.path("matrix_kind").asText()).isEqualTo("BILATERAL_FROZEN");
        assertThat(opening.at("/claims/respondent_direct").isNull()).isTrue();
        return opening;
    }

    private static ObjectNode initiatorMatrixWithFiveOrderedFormalFacts() {
        ObjectNode unilateral = unilateral();
        IntakeInitiatorMatrixFreezer initiatorFreezer =
                new IntakeInitiatorMatrixFreezer();
        ObjectNode parent = null;
        for (int round = 1; round <= 5; round++) {
            if (round > 1) {
                appendInitiatorFormalFact(unilateral, round);
            }
            unilateral.put("matrix_version", round);
            rehash(unilateral);
            parent = initiatorFreezer.freeze(
                    CASE_ID,
                    ActorRole.USER,
                    ActorRole.MERCHANT,
                    unilateral,
                    parent);
        }
        if (parent == null) {
            throw new AssertionError("five-round initiator authority was not frozen");
        }
        return parent;
    }

    private static void appendInitiatorFormalFact(ObjectNode unilateral, int round) {
        String factId = "FACT_INITIATOR_ROUND_" + round;
        String sourceRef = "MESSAGE_INITIATOR_" + round;
        String factTarget = "Whether initiator round " + round + " adds a formal fact.";
        ObjectNode sourceBinding = (ObjectNode) unilateral.path("source_binding");
        sourceBinding.withArray("source_refs").add(sourceRef);
        sourceBinding.put("latest_source_ref", sourceRef);
        unilateral.withArray("summary_source_fact_ids").add(factId);
        unilateral.withObjectProperty("dispute_core_state")
                .withArray("facts_in_dispute")
                .add("initiator round " + round + " fact");
        unilateral.withObjectProperty("dispute_core_state")
                .withArray("next_verification_focus")
                .add("initiator round " + round + " verification");
        ObjectNode row = unilateral.withArray("fact_rows").addObject();
        row.put("fact_id", factId);
        row.put("category", "AFTER_SALES");
        row.put("fact_target", factTarget);
        row.put("materiality", "CORE");
        row.putObject("origin")
                .put("source_stage", "INTAKE")
                .putArray("source_refs")
                .add(sourceRef);
        row.putObject("initiator_position")
                .put("stance", "CONFIRM")
                .put("position_summary", "The initiator states the next formal fact.")
                .put("asserted_value", "initiator round " + round)
                .putArray("source_refs")
                .add(sourceRef);
        row.put("truth_status", "NOT_EVALUATED");
    }

    private static ObjectNode respondentDeltaAppendingFormalFact(
            ObjectNode parent, String factKey, String factTarget) {
        ObjectNode delta = ordinaryRespondentDelta(parent);
        ArrayNode priorRows = (ArrayNode) delta.path("fact_rows");
        ArrayNode rows = JSON.createArrayNode();
        rows.addObject()
                .put("fact_key", factKey)
                .put("category", "AFTER_SALES")
                .put("fact_target", factTarget)
                .put("materiality", "CORE")
                .put("stance", "CONFIRM")
                .put("position_summary", "The respondent adds the next formal fact.")
                .put("asserted_value", "respondent formal value")
                .put("source_scope", "CURRENT_SOURCE");
        priorRows.forEach(row -> rows.add(row.deepCopy()));
        delta.set("fact_rows", rows);
        ArrayNode summary = delta.putArray("summary_source_fact_keys");
        parent.at("/case_overview/summary_source_fact_ids")
                .forEach(factId -> summary.add(factId.asText()));
        summary.add(factKey);
        return delta;
    }

    private static ObjectNode initiatorMatrixWithLineage() {
        ObjectNode unilateral = unilateralWithTwoFacts();
        IntakeInitiatorMatrixFreezer initiatorFreezer =
                new IntakeInitiatorMatrixFreezer();
        ObjectNode parent = null;
        for (int version = 1; version <= 5; version++) {
            unilateral.put("matrix_version", version);
            rehash(unilateral);
            parent = initiatorFreezer.freeze(
                    CASE_ID,
                    ActorRole.USER,
                    ActorRole.MERCHANT,
                    unilateral,
                    parent);
        }
        return parent;
    }

    private static ObjectNode unilateralWithTwoFacts() {
        ObjectNode matrix = unilateral();
        matrix.withArray("summary_source_fact_ids").add("FACT_SITE_WORK");
        matrix.withObjectProperty("dispute_core_state")
                .withArray("facts_in_dispute")
                .add("site work scope");
        matrix.withObjectProperty("dispute_core_state")
                .withArray("next_verification_focus")
                .add("site work request");
        ObjectNode row = matrix.withArray("fact_rows").addObject();
        row.put("fact_id", "FACT_SITE_WORK");
        row.put("category", "AFTER_SALES");
        row.put("fact_target", "Whether separate site work was requested.");
        row.put("materiality", "CORE");
        row.putObject("origin")
                .put("source_stage", "INTAKE")
                .putArray("source_refs")
                .add("MESSAGE_INITIATOR_1");
        row.putObject("initiator_position")
                .put("stance", "DENY")
                .put("position_summary", "The user denies requesting separate site work.")
                .put("asserted_value", "no separate work requested")
                .putArray("source_refs")
                .add("MESSAGE_INITIATOR_1");
        row.put("truth_status", "NOT_EVALUATED");
        rehash(matrix);
        return matrix;
    }

    private static ObjectNode respondentOpeningCarry(ObjectNode parent) {
        ObjectNode delta = JSON.createObjectNode();
        delta.put("schema_version", "case_fact_matrix.delta.v2");
        ArrayNode rows = delta.putArray("fact_rows");
        ArrayNode summary = delta.putArray("summary_source_fact_keys");
        for (JsonNode prior : parent.withArray("fact_rows")) {
            String factId = prior.path("fact_id").asText();
            ObjectNode row = rows.addObject();
            row.put("fact_key", factId);
            row.set("category", prior.required("category").deepCopy());
            row.set("fact_target", prior.required("fact_target").deepCopy());
            row.set("materiality", prior.required("materiality").deepCopy());
            row.put("stance", "NOT_ADDRESSED");
            row.set(
                    "position_summary",
                    prior.at("/positions/MERCHANT/position_summary").deepCopy());
            row.putNull("asserted_value");
            row.put("source_scope", "PREVIOUS_MATRIX");
            summary.add(factId);
        }
        return delta;
    }

    private static ObjectNode ordinaryRespondentDelta(ObjectNode parent) {
        ObjectNode delta = JSON.createObjectNode();
        delta.put("schema_version", "case_fact_matrix.delta.v2");
        ArrayNode rows = delta.putArray("fact_rows");
        ArrayNode summary = delta.putArray("summary_source_fact_keys");
        int index = 0;
        for (JsonNode prior : parent.withArray("fact_rows")) {
            String factId = prior.path("fact_id").asText();
            ObjectNode row = rows.addObject();
            row.put("fact_key", factId);
            row.set("category", prior.required("category").deepCopy());
            row.set("fact_target", prior.required("fact_target").deepCopy());
            row.set("materiality", prior.required("materiality").deepCopy());
            row.put("stance", index++ == 0 ? "CONFIRM" : "DENY");
            row.put("position_summary", "The merchant provides a direct position.");
            row.put("asserted_value", "merchant direct value");
            row.put("source_scope", "CURRENT_SOURCE");
            row.put("conflict_summary", "The parties disagree about this fact.");
            summary.add(factId);
        }
        delta.putObject("respondent_claim")
                .put("attitude", "DISAGREE")
                .put("position_summary", "The merchant disputes the requested refund.");
        return delta;
    }

    private ObjectNode respondentBilateralMatrixWithDirectClaim() throws Exception {
        ObjectNode opening = openingBilateralMatrix();
        return freezer.deriveCandidate(
                opening, ordinaryRespondentDelta(opening), respondentAuthority());
    }

    private static ObjectNode carryRespondentDelta(ObjectNode parent) {
        ObjectNode delta = JSON.createObjectNode();
        delta.put("schema_version", "case_fact_matrix.delta.v2");
        ArrayNode rows = delta.putArray("fact_rows");
        ArrayNode summary = delta.putArray("summary_source_fact_keys");
        for (JsonNode prior : parent.withArray("fact_rows")) {
            String factId = prior.path("fact_id").asText();
            JsonNode respondent = prior.at("/positions/MERCHANT");
            ObjectNode row = rows.addObject();
            row.put("fact_key", factId);
            row.set("category", prior.required("category").deepCopy());
            row.set("fact_target", prior.required("fact_target").deepCopy());
            row.set("materiality", prior.required("materiality").deepCopy());
            row.set("stance", respondent.required("stance").deepCopy());
            row.set(
                    "position_summary",
                    respondent.required("position_summary").deepCopy());
            row.set("asserted_value", respondent.required("asserted_value").deepCopy());
            row.put("source_scope", "PREVIOUS_MATRIX");
            JsonNode conflict = prior.at("/party_alignment/conflict_summary");
            if (!conflict.isNull()) {
                row.set("conflict_summary", conflict.deepCopy());
            }
            summary.add(factId);
        }
        JsonNode priorClaim = parent.at("/claims/respondent_direct");
        ObjectNode claim = delta.putObject("respondent_claim");
        claim.set("attitude", priorClaim.required("attitude").deepCopy());
        claim.set(
                "position_summary",
                priorClaim.required("position_summary").deepCopy());
        if (!priorClaim.path("alternative_proposal").isNull()) {
            claim.set(
                    "alternative_proposal",
                    priorClaim.required("alternative_proposal").deepCopy());
        }
        return delta;
    }

    private static ObjectNode parentRef(ObjectNode parent) {
        ObjectNode reference = JSON.createObjectNode();
        reference.set("matrix_id", parent.required("matrix_id").deepCopy());
        reference.set("matrix_version", parent.required("matrix_version").deepCopy());
        reference.set("content_hash", parent.required("content_hash").deepCopy());
        return reference;
    }

    private void assertInvalidOpeningParent(
            Consumer<ObjectNode> mutation, boolean refreshHash) throws Exception {
        ObjectNode parent = openingBilateralMatrix();
        mutation.accept(parent);
        if (refreshHash) {
            rehashBilateral(parent);
        }
        assertThatThrownBy(() -> freezer.deriveCandidate(
                        parent, ordinaryRespondentDelta(parent), respondentAuthority()))
                .isInstanceOf(IntakeFinalizationRejectedException.class);
    }

    private static ObjectNode unilateral() {
        ObjectNode matrix = JSON.createObjectNode();
        matrix.put("schema_version", "unilateral_case_matrix.v1");
        matrix.put("matrix_version", 1);
        ObjectNode source = matrix.putObject("source_binding");
        source.put("case_id", CASE_ID);
        source.put("source_stage", "INTAKE");
        source.putArray("source_refs").add("MESSAGE_INITIATOR_1");
        source.put("latest_source_ref", "MESSAGE_INITIATOR_1");
        source.put("source_context_hash", "a".repeat(64));
        matrix.putObject("party_map")
                .put("initiator_role", "USER")
                .put("respondent_role", "MERCHANT");
        matrix.put("case_summary", "The user requests a refund for an undelivered installation.");
        matrix.putArray("summary_source_fact_ids").add(PRIOR_FACT_ID);
        matrix.putObject("claim_resolution")
                .put("initiator_role", "USER")
                .put("requested_resolution", "REFUND")
                .put("reason_summary", "The promised installation was not delivered.")
                .put("position_summary", "The user seeks a refund.")
                .putArray("source_refs")
                .add("MESSAGE_INITIATOR_1");
        ObjectNode core = matrix.putObject("dispute_core_state");
        core.put("core_conflict", "Whether the promised installation was delivered.");
        core.putArray("facts_in_dispute").add("delivery scope");
        core.putArray("next_verification_focus").add("installation record");
        ObjectNode row = matrix.putArray("fact_rows").addObject();
        row.put("fact_id", PRIOR_FACT_ID);
        row.put("category", "FULFILLMENT");
        row.put("fact_target", "Whether the promised installation was delivered.");
        row.put("materiality", "CORE");
        row.putObject("origin")
                .put("source_stage", "INTAKE")
                .putArray("source_refs")
                .add("MESSAGE_INITIATOR_1");
        row.putObject("initiator_position")
                .put("stance", "CONFIRM")
                .put("position_summary", "The user states the service was not delivered.")
                .put("asserted_value", "installation missing")
                .putArray("source_refs")
                .add("MESSAGE_INITIATOR_1");
        row.put("truth_status", "NOT_EVALUATED");
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));
        return matrix;
    }

    private static JsonNode completeDelta() throws Exception {
        return JSON.readTree(
                """
                {
                  "schema_version":"case_fact_matrix.delta.v2",
                  "fact_rows":[
                    {
                      "fact_key":"FACT_DELIVERY_SCOPE",
                      "category":"FULFILLMENT",
                      "fact_target":"Whether the promised installation was delivered.",
                      "materiality":"CORE",
                      "stance":"CONFIRM",
                      "position_summary":"The merchant confirms the installation is missing.",
                      "asserted_value":"installation missing",
                      "source_scope":"CURRENT_SOURCE",
                      "agreed_statement":"The installation was not delivered."
                    },
                    {
                      "fact_key":"NEW_SITE_WORK",
                      "category":"AFTER_SALES",
                      "fact_target":"Whether separate site work was requested.",
                      "materiality":"CORE",
                      "stance":"CONFIRM",
                      "position_summary":"The merchant states separate site work was requested.",
                      "asserted_value":"separate work requested",
                      "source_scope":"CURRENT_SOURCE"
                    }
                  ],
                  "summary_source_fact_keys":["FACT_DELIVERY_SCOPE","NEW_SITE_WORK"],
                  "respondent_claim":{
                    "attitude":"DISAGREE",
                    "position_summary":"The merchant disputes the requested refund."
                  }
                }
                """);
    }

    private static MatrixAuthority respondentAuthority() {
        return new MatrixAuthority(
                CASE_ID,
                ActorRole.MERCHANT,
                ActorRole.USER,
                ActorRole.MERCHANT,
                "MESSAGE_RESPONDENT_2",
                "b".repeat(64));
    }

    private static MatrixAuthority respondentOpeningAuthority() {
        return new MatrixAuthority(
                CASE_ID,
                ActorRole.MERCHANT,
                ActorRole.USER,
                ActorRole.MERCHANT,
                "MESSAGE_RESPONDENT_OPENING",
                "e".repeat(64));
    }

    private static MatrixAuthority nextRespondentAuthority() {
        return new MatrixAuthority(
                CASE_ID,
                ActorRole.MERCHANT,
                ActorRole.USER,
                ActorRole.MERCHANT,
                "MESSAGE_RESPONDENT_3",
                "f".repeat(64));
    }

    private static MatrixAuthority initiatorAuthority() {
        return new MatrixAuthority(
                CASE_ID,
                ActorRole.USER,
                ActorRole.USER,
                ActorRole.MERCHANT,
                "MESSAGE_INITIATOR_2",
                "c".repeat(64));
    }

    private static void rehash(ObjectNode matrix) {
        matrix.remove("content_hash");
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));
    }

    private static void rehashBilateral(ObjectNode matrix) {
        matrix.remove("content_hash");
        matrix.remove("matrix_id");
        String matrixId = "CASE_MATRIX_"
                + ContractJson.sha256Hex(matrix)
                        .substring(0, 20)
                        .toUpperCase(java.util.Locale.ROOT);
        matrix.put("matrix_id", matrixId);
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));

        ObjectNode idInput = matrix.deepCopy();
        idInput.remove("content_hash");
        idInput.remove("matrix_id");
        assertThat(matrix.path("matrix_id").asText())
                .isEqualTo("CASE_MATRIX_"
                        + ContractJson.sha256Hex(idInput)
                                .substring(0, 20)
                                .toUpperCase(java.util.Locale.ROOT));
        ObjectNode hashInput = matrix.deepCopy();
        String contentHash = hashInput.remove("content_hash").asText();
        assertThat(contentHash).isEqualTo(ContractJson.sha256Hex(hashInput));
    }

    private static void assertRejected(
            String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .satisfies(failure -> assertThat(
                                ((IntakeFinalizationRejectedException) failure).code())
                        .isEqualTo(code));
    }
}
