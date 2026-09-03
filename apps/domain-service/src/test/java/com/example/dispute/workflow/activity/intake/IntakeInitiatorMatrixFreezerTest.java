package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeInitiatorMatrixFreezer;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class IntakeInitiatorMatrixFreezerTest {

    private static final String CASE_ID = "CASE_MATRIX_FREEZE";

    private final IntakeInitiatorMatrixFreezer freezer = new IntakeInitiatorMatrixFreezer();

    @Test
    void freezesTheValidatedUnilateralProjectionIntoStableJavaOwnedAuthority() {
        ObjectNode unilateral = unilateral();
        ObjectNode original = unilateral.deepCopy();

        ObjectNode first = freezer.freeze(CASE_ID, ActorRole.USER, ActorRole.MERCHANT, unilateral);
        ObjectNode second = freezer.freeze(
                CASE_ID, ActorRole.USER, ActorRole.MERCHANT, unilateral.deepCopy());

        assertThat(unilateral).isEqualTo(original);
        assertThat(second).isEqualTo(first);
        assertThat(first.path("schema_version").asText()).isEqualTo("case_fact_matrix.v2");
        assertThat(first.path("matrix_kind").asText()).isEqualTo("INITIATOR_FROZEN");
        assertThat(first.path("matrix_version").asLong()).isEqualTo(1);
        assertThat(first.path("parent_ref").isNull()).isTrue();
        assertThat(first.path("matrix_id").asText()).matches("CASE_MATRIX_[A-F0-9]{20}");
        assertThat(first.at("/fact_rows/0/positions/USER/source_type").asText())
                .isEqualTo("DIRECT_PARTY_STATEMENT");
        assertThat(first.at("/fact_rows/0/positions/MERCHANT/stance").asText())
                .isEqualTo("NOT_ADDRESSED");
        assertThat(first.at("/fact_rows/0/positions/MERCHANT/source_refs").isEmpty()).isTrue();
        assertThat(first.at("/fact_indexes/not_computed_fact_ids/0").asText())
                .isEqualTo("FACT_DELIVERY_SCOPE");
        assertThat(first.at("/fact_indexes/core_fact_ids/0").asText())
                .isEqualTo("FACT_DELIVERY_SCOPE");

        ObjectNode hashInput = first.deepCopy();
        String storedHash = hashInput.remove("content_hash").asText();
        assertThat(storedHash).isEqualTo(ContractJson.sha256Hex(hashInput));
        freezer.validateFrozen(first, CASE_ID, ActorRole.USER, ActorRole.MERCHANT);
    }

    @Test
    void rejectsRehashedSourceAndDerivedIndexTampering() {
        ObjectNode sourceTampered = freezer.freeze(
                CASE_ID, ActorRole.USER, ActorRole.MERCHANT, unilateral());
        ((ObjectNode) sourceTampered.at("/fact_rows/0/positions/USER"))
                .putArray("source_refs")
                .add("MESSAGE_FORGED_SOURCE");
        rehash(sourceTampered);

        assertRejected(() -> freezer.validateFrozen(
                sourceTampered, CASE_ID, ActorRole.USER, ActorRole.MERCHANT));

        ObjectNode indexTampered = freezer.freeze(
                CASE_ID, ActorRole.USER, ActorRole.MERCHANT, unilateral());
        indexTampered.withObjectProperty("fact_indexes")
                .withArray("agreed_fact_ids")
                .add("FACT_DELIVERY_SCOPE");
        rehash(indexTampered);

        assertRejected(() -> freezer.validateFrozen(
                indexTampered, CASE_ID, ActorRole.USER, ActorRole.MERCHANT));
    }

    @Test
    void rejectsAValidFrozenPayloadWhenTheCurrentCasePartyBindingChanges() {
        ObjectNode matrix = freezer.freeze(CASE_ID, ActorRole.USER, ActorRole.MERCHANT, unilateral());

        assertRejected(() -> freezer.validateFrozen(
                matrix, CASE_ID, ActorRole.MERCHANT, ActorRole.USER));
    }

    @Test
    void acceptsBaselineUnknownInitiatorStanceWithANullAssertedValue() {
        ObjectNode matrix = freezer.freeze(CASE_ID, ActorRole.USER, ActorRole.MERCHANT, unilateral());
        ObjectNode position = (ObjectNode) matrix.at("/fact_rows/0/positions/USER");
        position.put("stance", "UNKNOWN");
        position.putNull("asserted_value");
        rehash(matrix);

        freezer.validateFrozen(matrix, CASE_ID, ActorRole.USER, ActorRole.MERCHANT);
    }

    @Test
    void advancesASecondInitiatorTurnWithStableFactsSourcesAndParentAuthority() {
        ObjectNode first = freezer.freeze(
                CASE_ID, ActorRole.USER, ActorRole.MERCHANT, unilateral());
        ObjectNode revised = unilateral();
        revised.put("matrix_version", 2);
        revised.withObjectProperty("source_binding")
                .withArray("source_refs")
                .add("MESSAGE_INITIATOR_2");
        revised.withObjectProperty("source_binding")
                .put("latest_source_ref", "MESSAGE_INITIATOR_2")
                .put("source_context_hash", "b".repeat(64));
        revised.withArray("fact_rows")
                .get(0)
                .withObjectProperty("initiator_position")
                .withArray("source_refs")
                .add("MESSAGE_INITIATOR_2");
        revised.withObjectProperty("claim_resolution")
                .withArray("source_refs")
                .add("MESSAGE_INITIATOR_2");
        rehash(revised);

        ObjectNode second = freezer.freeze(
                CASE_ID, ActorRole.USER, ActorRole.MERCHANT, revised, first);

        assertThat(second.path("schema_version").asText()).isEqualTo("case_fact_matrix.v2");
        assertThat(second.path("matrix_kind").asText()).isEqualTo("INITIATOR_FROZEN");
        assertThat(second.path("matrix_version").asLong()).isEqualTo(2);
        assertThat(second.at("/parent_ref/matrix_id")).isEqualTo(first.path("matrix_id"));
        assertThat(second.at("/parent_ref/matrix_version")).isEqualTo(first.path("matrix_version"));
        assertThat(second.at("/parent_ref/content_hash")).isEqualTo(first.path("content_hash"));
        assertThat(second.at("/fact_rows/0/fact_id"))
                .isEqualTo(first.at("/fact_rows/0/fact_id"));
        assertThat(second.path("source_refs").toString())
                .contains("MESSAGE_INITIATOR_1", "MESSAGE_INITIATOR_2");
        assertThat(second.at("/fact_rows/0/positions/MERCHANT/stance").asText())
                .isEqualTo("NOT_ADDRESSED");
        assertThat(second.at("/fact_rows/0/positions/MERCHANT/position_summary").asText())
                .isEqualTo("该方尚未直接陈述。");
        assertThat(second.at("/fact_rows/0/party_alignment/status").asText())
                .isEqualTo("NOT_COMPUTED");
        freezer.validateFrozen(second, CASE_ID, ActorRole.USER, ActorRole.MERCHANT);
    }

    @Test
    void rejectsASecondInitiatorTurnThatRebindsStableFactMateriality() {
        ObjectNode first = freezer.freeze(
                CASE_ID, ActorRole.USER, ActorRole.MERCHANT, unilateral());
        ObjectNode revised = unilateral();
        revised.put("matrix_version", 2);
        ((ObjectNode) revised.withArray("fact_rows").get(0)).put("materiality", "SUPPORTING");
        rehash(revised);

        assertThatThrownBy(() -> freezer.freeze(
                        CASE_ID, ActorRole.USER, ActorRole.MERCHANT, revised, first))
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .satisfies(failure -> assertThat(
                                ((IntakeFinalizationRejectedException) failure).code())
                        .isEqualTo("INTAKE_INITIATOR_MATRIX_STABLE_AUTHORITY_INVALID"));
    }

    private static ObjectNode unilateral() {
        ObjectNode matrix = JsonMapper.builder().build().createObjectNode();
        matrix.put("schema_version", "unilateral_case_matrix.v1");
        matrix.put("matrix_version", 3);
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
        matrix.putArray("summary_source_fact_ids").add("FACT_DELIVERY_SCOPE");
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
        row.put("fact_id", "FACT_DELIVERY_SCOPE");
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

    private static void rehash(ObjectNode matrix) {
        matrix.remove("content_hash");
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .satisfies(failure -> assertThat(
                                ((IntakeFinalizationRejectedException) failure).code())
                        .startsWith("INTAKE_INITIATOR_MATRIX_"));
    }
}
