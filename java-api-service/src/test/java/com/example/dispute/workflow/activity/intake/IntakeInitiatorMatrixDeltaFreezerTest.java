package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeDossierProjectionMerger.MatrixAuthority;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeInitiatorMatrixDeltaFreezer;
import com.example.dispute.workflow.application.intake.IntakeInitiatorMatrixFreezer;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class IntakeInitiatorMatrixDeltaFreezerTest {

    private static final String CASE_ID = "CASE_INITIATOR_DELTA_1";

    private final IntakeInitiatorMatrixDeltaFreezer freezer = new IntakeInitiatorMatrixDeltaFreezer();
    private final IntakeInitiatorMatrixFreezer formalValidator = new IntakeInitiatorMatrixFreezer();

    @Test
    void freezesFirstDeltasForUserAndMerchantInitiatorsWithBaselineUnknownNull() {
        ObjectNode userDelta = delta("NEW_DELIVERY_SCOPE", "UNKNOWN", null);
        ((ObjectNode) userDelta.withArray("fact_rows").get(0))
                .put("agreed_statement", "This optional proposal must not be materialized.")
                .put("conflict_summary", "This optional proposal must not be materialized.");
        ObjectNode user = freezer.freeze(
                dossier(ActorRole.USER, ActorRole.MERCHANT),
                userDelta,
                authority(ActorRole.USER, ActorRole.USER, ActorRole.MERCHANT, "MESSAGE_USER_1", 'a'));
        ObjectNode merchant = freezer.freeze(
                dossier(ActorRole.MERCHANT, ActorRole.USER),
                delta("NEW_DELIVERY_SCOPE", "UNKNOWN", null),
                authority(
                        ActorRole.MERCHANT,
                        ActorRole.MERCHANT,
                        ActorRole.USER,
                        "MESSAGE_MERCHANT_1",
                        'b'));

        assertInitialUnknownMatrix(user, ActorRole.USER, ActorRole.MERCHANT, "MESSAGE_USER_1");
        assertInitialUnknownMatrix(
                merchant, ActorRole.MERCHANT, ActorRole.USER, "MESSAGE_MERCHANT_1");
        formalValidator.validateFrozen(user, CASE_ID, ActorRole.USER, ActorRole.MERCHANT);
        formalValidator.validateFrozen(merchant, CASE_ID, ActorRole.MERCHANT, ActorRole.USER);
    }

    @Test
    void advancesFormalLineageCarriesEveryPriorFactAndReusesSemanticNewIds() {
        ObjectNode first = firstUserMatrix();
        String firstFactId = first.at("/fact_rows/0/fact_id").asText();

        ObjectNode semanticReuse = delta("NEW_RESTATED_DELIVERY", "CONFIRM", "delivery disputed");
        ((ObjectNode) semanticReuse.withArray("fact_rows").get(0))
                .put("fact_target", " WHETHER THE PROMISED DELIVERY WAS COMPLETED. ");
        ObjectNode reused = freezer.freeze(
                dossierWithParent(first),
                semanticReuse,
                authority(ActorRole.USER, ActorRole.USER, ActorRole.MERCHANT, "MESSAGE_USER_2", 'c'));

        assertThat(reused.path("matrix_version").asLong()).isEqualTo(2);
        assertThat(reused.at("/parent_ref/matrix_id")).isEqualTo(first.path("matrix_id"));
        assertThat(reused.at("/parent_ref/matrix_version")).isEqualTo(first.path("matrix_version"));
        assertThat(reused.at("/parent_ref/content_hash")).isEqualTo(first.path("content_hash"));
        assertThat(reused.at("/fact_rows/0/fact_id").asText()).isEqualTo(firstFactId);
        assertThat(reused.at("/fact_rows/0/fact_target"))
                .isEqualTo(first.at("/fact_rows/0/fact_target"));
        assertThat(reused.path("source_refs")).contains(first.path("source_refs").get(0));
        assertThat(reused.path("source_refs").toString()).contains("MESSAGE_USER_2");
        assertThat(reused.at("/fact_rows/0/positions/USER/source_refs").toString())
                .contains("MESSAGE_USER_1", "MESSAGE_USER_2");

        ObjectNode followUp = deltaWithPriorAndNew(firstFactId);
        ObjectNode second = freezer.freeze(
                dossierWithParent(first),
                followUp,
                authority(ActorRole.USER, ActorRole.USER, ActorRole.MERCHANT, "MESSAGE_USER_3", 'd'));

        assertThat(second.path("matrix_version").asLong()).isEqualTo(2);
        assertThat(second.at("/parent_ref/matrix_id")).isEqualTo(first.path("matrix_id"));
        assertThat(second.at("/parent_ref/matrix_version")).isEqualTo(first.path("matrix_version"));
        assertThat(second.at("/parent_ref/content_hash")).isEqualTo(first.path("content_hash"));
        assertThat(second.path("fact_rows")).hasSize(2);
        assertThat(second.at("/fact_rows/0/fact_id")).isEqualTo(first.at("/fact_rows/0/fact_id"));
        assertThat(second.at("/fact_rows/0/positions/USER"))
                .isEqualTo(first.at("/fact_rows/0/positions/USER"));
        assertThat(second.at("/fact_rows/1/fact_id").asText()).startsWith("FACT_");
        assertThat(second.at("/fact_rows/1/positions/MERCHANT/stance").asText())
                .isEqualTo("NOT_ADDRESSED");
        assertThat(second.at("/fact_rows/1/party_alignment/status").asText())
                .isEqualTo("NOT_COMPUTED");
        assertThat(second.at("/fact_indexes/not_computed_fact_ids")).hasSize(2);
        assertThat(second.at("/fact_indexes/core_fact_ids")).hasSize(2);
        formalValidator.validateFrozen(second, CASE_ID, ActorRole.USER, ActorRole.MERCHANT);
    }

    @Test
    void rejectsRespondentClaimsAndWrongActorAuthority() {
        ObjectNode withNullRespondentClaim = delta("NEW_DELIVERY_SCOPE", "CONFIRM", "delivery disputed");
        withNullRespondentClaim.putNull("respondent_claim");

        ObjectNode accepted = freezer.freeze(
                dossier(ActorRole.USER, ActorRole.MERCHANT),
                withNullRespondentClaim,
                authority(ActorRole.USER, ActorRole.USER, ActorRole.MERCHANT, "MESSAGE_USER_1", 'a'));
        assertThat(accepted.path("matrix_kind").asText()).isEqualTo("INITIATOR_FROZEN");

        ObjectNode withRespondentClaim = delta("NEW_DELIVERY_SCOPE", "CONFIRM", "delivery disputed");
        withRespondentClaim.putObject("respondent_claim")
                .put("attitude", "DISAGREE")
                .put("position_summary", "The respondent claim must be rejected for initiator input.");

        assertRejected(
                "INTAKE_INITIATOR_MATRIX_RESPONDENT_CLAIM_FORBIDDEN",
                () -> freezer.freeze(
                        dossier(ActorRole.USER, ActorRole.MERCHANT),
                        withRespondentClaim,
                        authority(
                                ActorRole.USER,
                                ActorRole.USER,
                                ActorRole.MERCHANT,
                                "MESSAGE_USER_1",
                                'a')));
        assertRejected(
                "INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID",
                () -> freezer.freeze(
                        dossier(ActorRole.USER, ActorRole.MERCHANT),
                        delta("NEW_DELIVERY_SCOPE", "CONFIRM", "delivery disputed"),
                        authority(
                                ActorRole.MERCHANT,
                                ActorRole.USER,
                                ActorRole.MERCHANT,
                                "MESSAGE_MERCHANT_1",
                                'b')));
    }

    @Test
    void rejectsNonCurrentNewRowsForAnOpeningInitiatorDelta() {
        ObjectNode mixedSource = delta("NEW_DELIVERY_SCOPE", "CONFIRM", "delivery disputed");
        ((ObjectNode) mixedSource.withArray("fact_rows").get(0))
                .put("source_scope", "PREVIOUS_AND_CURRENT_SOURCE");
        assertRejected(
                "INTAKE_INITIATOR_MATRIX_OPENING_INVALID",
                () -> freezer.freeze(
                        dossier(ActorRole.USER, ActorRole.MERCHANT),
                        mixedSource,
                        authority(
                                ActorRole.USER,
                                ActorRole.USER,
                                ActorRole.MERCHANT,
                                "MESSAGE_USER_1",
                                'a')));

        ObjectNode oldFact = delta("FACT_REUSED", "NOT_ADDRESSED", null);
        ((ObjectNode) oldFact.withArray("fact_rows").get(0)).put("source_scope", "PREVIOUS_MATRIX");
        assertRejected(
                "INTAKE_INITIATOR_MATRIX_OPENING_INVALID",
                () -> freezer.freeze(
                        dossier(ActorRole.USER, ActorRole.MERCHANT),
                        oldFact,
                        authority(
                                ActorRole.USER,
                                ActorRole.USER,
                                ActorRole.MERCHANT,
                                "MESSAGE_USER_1",
                                'a')));
    }

    @Test
    void preservesClaimAndSubjectiveReportProvenanceAcrossFollowUps() {
        ObjectNode openingDossier = dossier(ActorRole.USER, ActorRole.MERCHANT);
        ObjectNode openingAttitude = (ObjectNode) openingDossier.path("respondent_attitude");
        openingAttitude.put("source", "发起方单方陈述（主观）");
        openingAttitude.put("status", "DISAGREE");
        openingAttitude.put("position", "The initiator reports that the merchant disputes delivery.");
        ObjectNode first = freezer.freeze(
                openingDossier,
                delta("NEW_DELIVERY_SCOPE", "CONFIRM", "delivery disputed"),
                authority(ActorRole.USER, ActorRole.USER, ActorRole.MERCHANT, "MESSAGE_USER_1", 'a'));

        ObjectNode second = freezer.freeze(
                dossierWithParent(first),
                delta("NEW_RESTATED_DELIVERY", "CONFIRM", "delivery disputed"),
                authority(ActorRole.USER, ActorRole.USER, ActorRole.MERCHANT, "MESSAGE_USER_2", 'b'));

        assertThat(second.at("/claims/initiator_claim/source_refs").toString())
                .isEqualTo("[\"MESSAGE_USER_1\"]");
        assertThat(second.at("/claims/respondent_reported_by_initiator/source_refs").toString())
                .isEqualTo("[\"MESSAGE_USER_1\"]");
        assertThat(second.path("source_refs").toString())
                .contains("MESSAGE_USER_1", "MESSAGE_USER_2");

        ObjectNode changedDossier = dossierWithParent(second);
        ((ObjectNode) changedDossier.path("claim_resolution"))
                .put("reason_summary", "The initiator updates the refund rationale.");
        ObjectNode changedAttitude = (ObjectNode) changedDossier.path("respondent_attitude");
        changedAttitude.put("source", "发起方单方陈述（主观）");
        changedAttitude.put("status", "DISAGREE");
        changedAttitude.put("position", "The initiator repeats the reported respondent position.");
        ObjectNode third = freezer.freeze(
                changedDossier,
                delta("NEW_RESTATED_DELIVERY", "CONFIRM", "delivery disputed"),
                authority(ActorRole.USER, ActorRole.USER, ActorRole.MERCHANT, "MESSAGE_USER_3", 'c'));

        assertThat(third.at("/claims/initiator_claim/source_refs").toString())
                .isEqualTo("[\"MESSAGE_USER_1\",\"MESSAGE_USER_3\"]");
        assertThat(third.at("/claims/respondent_reported_by_initiator/source_refs").toString())
                .isEqualTo("[\"MESSAGE_USER_1\",\"MESSAGE_USER_3\"]");
        assertThat(third.path("source_refs").toString())
                .contains("MESSAGE_USER_1", "MESSAGE_USER_3");
    }

    @Test
    void rejectsUnknownReboundMissingAndCollisionRows() {
        ObjectNode first = firstUserMatrix();
        String factId = first.at("/fact_rows/0/fact_id").asText();
        MatrixAuthority next = authority(
                ActorRole.USER, ActorRole.USER, ActorRole.MERCHANT, "MESSAGE_USER_2", 'c');

        ObjectNode unknown = delta("FACT_UNKNOWN", "CONFIRM", "delivery disputed");
        ((ObjectNode) unknown.withArray("fact_rows").get(0))
                .put("fact_target", "Whether an unrelated delivery was completed.");
        assertRejected(
                "INTAKE_INITIATOR_MATRIX_FACT_UNKNOWN",
                () -> freezer.freeze(
                        dossierWithParent(first),
                        unknown,
                        next));

        ObjectNode rebound = delta(factId, "CONFIRM", "delivery disputed");
        ((ObjectNode) rebound.withArray("fact_rows").get(0)).put("materiality", "SUPPORTING");
        assertRejected(
                "INTAKE_INITIATOR_MATRIX_FACT_REBOUND",
                () -> freezer.freeze(dossierWithParent(first), rebound, next));

        ObjectNode reboundTarget = delta(factId, "CONFIRM", "delivery disputed");
        ((ObjectNode) reboundTarget.withArray("fact_rows").get(0))
                .put("fact_target", "Whether the delivery was completed on a different order.");
        assertRejected(
                "INTAKE_INITIATOR_MATRIX_FACT_REBOUND",
                () -> freezer.freeze(dossierWithParent(first), reboundTarget, next));

        ObjectNode missing = delta("NEW_DIFFERENT_FACT", "CONFIRM", "different value");
        ((ObjectNode) missing.withArray("fact_rows").get(0))
                .put("fact_target", "Whether the repair appointment was missed.");
        assertRejected(
                "INTAKE_INITIATOR_MATRIX_PRIOR_FACT_MISSING",
                () -> freezer.freeze(dossierWithParent(first), missing, next));

        ObjectNode collision = delta(factId, "CONFIRM", "delivery disputed");
        addRow(
                collision.withArray("fact_rows"),
                "NEW_SEMANTIC_ALIAS",
                "CONFIRM",
                "delivery disputed",
                "CURRENT_SOURCE");
        collision.putArray("summary_source_fact_keys").add(factId).add("NEW_SEMANTIC_ALIAS");
        assertRejected(
                "INTAKE_INITIATOR_MATRIX_FACT_DUPLICATE",
                () -> freezer.freeze(dossierWithParent(first), collision, next));
    }

    @Test
    void rejectsTamperedFormalParentHashSourcesAndIndexes() {
        ObjectNode first = firstUserMatrix();
        String factId = first.at("/fact_rows/0/fact_id").asText();
        MatrixAuthority next = authority(
                ActorRole.USER, ActorRole.USER, ActorRole.MERCHANT, "MESSAGE_USER_2", 'c');

        ObjectNode hashTampered = first.deepCopy();
        hashTampered.put("content_hash", "0".repeat(64));
        assertRejectedPrefix(
                "INTAKE_INITIATOR_MATRIX_HASH_INVALID",
                () -> freezer.freeze(
                        dossierWithParent(hashTampered),
                        delta(factId, "CONFIRM", "delivery disputed"),
                        next));

        ObjectNode sourceTampered = first.deepCopy();
        ((ObjectNode) sourceTampered.at("/fact_rows/0/positions/USER"))
                .withArray("source_refs")
                .add("MESSAGE_FORGED_SOURCE");
        rehash(sourceTampered);
        assertRejectedPrefix(
                "INTAKE_INITIATOR_MATRIX_SOURCE_INVALID",
                () -> freezer.freeze(
                        dossierWithParent(sourceTampered),
                        delta(factId, "CONFIRM", "delivery disputed"),
                        next));

        ObjectNode indexTampered = first.deepCopy();
        indexTampered.withObjectProperty("fact_indexes")
                .withArray("agreed_fact_ids")
                .add(factId);
        rehash(indexTampered);
        assertRejectedPrefix(
                "INTAKE_INITIATOR_MATRIX_INDEX_INVALID",
                () -> freezer.freeze(
                        dossierWithParent(indexTampered),
                        delta(factId, "CONFIRM", "delivery disputed"),
                        next));
    }

    private static void assertInitialUnknownMatrix(
            ObjectNode matrix, ActorRole initiatorRole, ActorRole respondentRole, String sourceRef) {
        assertThat(matrix.path("schema_version").asText()).isEqualTo("case_fact_matrix.v2");
        assertThat(matrix.path("matrix_kind").asText()).isEqualTo("INITIATOR_FROZEN");
        assertThat(matrix.path("matrix_version").asLong()).isEqualTo(1);
        assertThat(matrix.path("parent_ref").isNull()).isTrue();
        assertThat(matrix.at("/party_map/initiator_role").asText()).isEqualTo(initiatorRole.name());
        assertThat(matrix.at("/party_map/respondent_role").asText()).isEqualTo(respondentRole.name());
        assertThat(matrix.at("/generation_ref/latest_source_ref").asText()).isEqualTo(sourceRef);
        assertThat(matrix.at("/fact_rows/0/positions/" + initiatorRole.name() + "/stance").asText())
                .isEqualTo("UNKNOWN");
        assertThat(matrix.at("/fact_rows/0/positions/" + initiatorRole.name() + "/asserted_value").isNull())
                .isTrue();
        assertThat(matrix.at("/fact_rows/0/positions/" + respondentRole.name() + "/stance").asText())
                .isEqualTo("NOT_ADDRESSED");
        assertThat(matrix.at("/fact_rows/0/positions/" + respondentRole.name() + "/position_summary").asText())
                .isEqualTo("该方尚未直接陈述。");
        assertThat(matrix.at("/fact_rows/0/positions/" + respondentRole.name() + "/source_type").asText())
                .isEqualTo("NO_DIRECT_POSITION");
        assertThat(matrix.at("/fact_rows/0/party_alignment/status").asText())
                .isEqualTo("NOT_COMPUTED");
        assertThat(matrix.at("/fact_rows/0/party_alignment/agreed_statement").isNull()).isTrue();
        assertThat(matrix.at("/fact_rows/0/party_alignment/conflict_summary").isNull()).isTrue();
        assertThat(matrix.at("/fact_rows/0/requires_resolution").isNull()).isTrue();
        assertThat(matrix.at("/fact_rows/0/truth_status").asText()).isEqualTo("NOT_EVALUATED");
        assertThat(matrix.at("/fact_rows/0/evidence_coverage_status").asText())
                .isEqualTo("PENDING_EVIDENCE_REVIEW");
        ObjectNode hashInput = matrix.deepCopy();
        assertThat(hashInput.remove("content_hash").asText()).isEqualTo(ContractJson.sha256Hex(hashInput));
    }

    private ObjectNode firstUserMatrix() {
        return freezer.freeze(
                dossier(ActorRole.USER, ActorRole.MERCHANT),
                delta("NEW_DELIVERY_SCOPE", "UNKNOWN", null),
                authority(ActorRole.USER, ActorRole.USER, ActorRole.MERCHANT, "MESSAGE_USER_1", 'a'));
    }

    private static ObjectNode dossierWithParent(ObjectNode parent) {
        ObjectNode result = dossier(ActorRole.USER, ActorRole.MERCHANT);
        result.set("case_fact_matrix", parent.deepCopy());
        return result;
    }

    private static ObjectNode dossier(ActorRole initiatorRole, ActorRole respondentRole) {
        ObjectNode dossier = JsonMapper.builder().build().createObjectNode();
        dossier.putObject("case_story")
                .put("one_sentence_summary", "The parties dispute whether delivery was completed.");
        dossier.putObject("claim_resolution")
                .put("initiator_role", initiatorRole.name())
                .put("requested_resolution", "REFUND")
                .put("reason_summary", "The promised delivery was not completed.")
                .put("position_summary", "The initiator requests a refund.");
        dossier.putObject("dispute_core_state")
                .put("core_conflict", "Whether the promised delivery was completed.");
        dossier.putObject("respondent_attitude")
                .put("respondent_role", respondentRole.name())
                .put("status", "NOT_RESPONDED")
                .put("note", "No direct respondent statement exists.");
        return dossier;
    }

    private static ObjectNode delta(String factKey, String stance, String assertedValue) {
        ObjectNode delta = JsonMapper.builder().build().createObjectNode();
        delta.put("schema_version", "case_fact_matrix.delta.v2");
        addRow(delta.putArray("fact_rows"), factKey, stance, assertedValue, "CURRENT_SOURCE");
        delta.putArray("summary_source_fact_keys").add(factKey);
        return delta;
    }

    private static ObjectNode deltaWithPriorAndNew(String priorFactId) {
        ObjectNode delta = JsonMapper.builder().build().createObjectNode();
        delta.put("schema_version", "case_fact_matrix.delta.v2");
        ArrayNode rows = delta.putArray("fact_rows");
        ObjectNode prior = rows.addObject();
        prior.put("fact_key", priorFactId);
        prior.put("category", "FULFILLMENT");
        prior.put("fact_target", "Whether the promised delivery was completed.");
        prior.put("materiality", "CORE");
        prior.put("stance", "NOT_ADDRESSED");
        prior.put("position_summary", "No new initiator statement is supplied.");
        prior.putNull("asserted_value");
        prior.put("source_scope", "PREVIOUS_MATRIX");
        addRow(
                rows,
                "NEW_REPAIR_APPOINTMENT",
                "CONFIRM",
                "appointment missed",
                "CURRENT_SOURCE");
        ((ObjectNode) rows.get(1)).put("fact_target", "Whether the repair appointment was missed.");
        delta.putArray("summary_source_fact_keys").add(priorFactId).add("NEW_REPAIR_APPOINTMENT");
        return delta;
    }

    private static void addRow(
            ArrayNode rows,
            String factKey,
            String stance,
            String assertedValue,
            String sourceScope) {
        ObjectNode row = rows.addObject();
        row.put("fact_key", factKey);
        row.put("category", "FULFILLMENT");
        row.put("fact_target", "Whether the promised delivery was completed.");
        row.put("materiality", "CORE");
        row.put("stance", stance);
        row.put("position_summary", "The initiator describes the delivery dispute.");
        if (assertedValue == null) {
            row.putNull("asserted_value");
        } else {
            row.put("asserted_value", assertedValue);
        }
        row.put("source_scope", sourceScope);
    }

    private static MatrixAuthority authority(
            ActorRole actor,
            ActorRole initiator,
            ActorRole respondent,
            String sourceRef,
            char sourceHashCharacter) {
        return new MatrixAuthority(
                CASE_ID,
                actor,
                initiator,
                respondent,
                sourceRef,
                String.valueOf(sourceHashCharacter).repeat(64));
    }

    private static void rehash(ObjectNode matrix) {
        matrix.remove("content_hash");
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));
    }

    private static void assertRejected(
            String expectedCode, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .satisfies(failure -> assertThat(
                                ((IntakeFinalizationRejectedException) failure).code())
                        .isEqualTo(expectedCode));
    }

    private static void assertRejectedPrefix(
            String expectedCode, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .satisfies(failure -> assertThat(
                                ((IntakeFinalizationRejectedException) failure).code())
                        .isEqualTo(expectedCode));
    }
}
