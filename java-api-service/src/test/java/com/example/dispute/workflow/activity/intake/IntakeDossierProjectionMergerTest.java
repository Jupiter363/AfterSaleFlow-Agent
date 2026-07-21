package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeDossierProjectionMerger;
import com.example.dispute.workflow.application.intake.IntakeDossierProjectionMerger.MatrixAuthority;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntakeDossierProjectionMergerTest {

    private static final JsonMapper JSON = new JsonMapper();
    private final IntakeDossierProjectionMerger merger = new IntakeDossierProjectionMerger();

    @Test
    void deepMergesApprovedBranchesAndDerivesProjectionMetadata() throws Exception {
        JsonNode current = JSON.readTree(
                """
                {
                  "case_story":{"summary":"original","stable":"keep"},
                  "references":{"source_refs":["MESSAGE_1"]}
                }
                """);
        IntakeTurnProposal proposal = proposal(
                JSON.readTree(
                        """
                        {
                          "schema_version":"intake-dossier.v2",
                          "case_story":{"summary":"updated"},
                          "requested_resolution":{"kind":"REFUND"}
                        }
                        """),
                null);

        var result = merger.merge(current, proposal);

        assertThat(result.dossier().at("/case_story/summary").asText()).isEqualTo("updated");
        assertThat(result.dossier().at("/case_story/stable").asText()).isEqualTo("keep");
        assertThat(result.dossier().at("/requested_resolution/kind").asText())
                .isEqualTo("REFUND");
        assertThat(result.dossier().at("/intake_quality/ready_for_next_step").asBoolean())
                .isFalse();
        assertThat(result.dossier().at("/admission/recommendation").asText())
                .isEqualTo("NEED_MORE_INFO");
        assertThat(result.qualityScore()).isEqualTo(82);
    }

    @Test
    void rejectsStableFactRebindingAgainstTheCurrentDomainProjection() throws Exception {
        JsonNode current = JSON.readTree(
                """
                {
                  "party_positions":{
                    "fact_rows":[{
                      "fact_id":"FACT_DAMAGE",
                      "category":"PRODUCT_STATE",
                      "fact_target":"The product arrived damaged."
                    }]
                  }
                }
                """);
        IntakeTurnProposal proposal = proposal(
                JSON.readTree(
                        """
                        {
                          "party_positions":{
                            "fact_rows":[{
                              "fact_id":"FACT_DAMAGE",
                              "category":"PRODUCT_STATE",
                              "fact_target":"A different asserted fact."
                            }]
                          }
                        }
                        """),
                null);

        assertRejected(
                "INTAKE_DOSSIER_STABLE_ID_REBOUND", () -> merger.merge(current, proposal));
    }

    @Test
    void derivesAJavaOwnedUnilateralProjectionFromTheFrozenDraftShape() throws Exception {
        JsonNode dossierPatch = completeDossierPatch();
        JsonNode matrixPatch = unilateralDraft();

        var result = merger.merge(
                JSON.createObjectNode(),
                proposal(dossierPatch, matrixPatch),
                matrixAuthority(ActorRole.USER));

        ObjectNode matrix = (ObjectNode) result.dossier().path("unilateral_case_matrix");
        assertThat(result.matrixVersion()).isEqualTo(1);
        assertThat(matrix.path("schema_version").asText())
                .isEqualTo("unilateral_case_matrix.v1");
        assertThat(matrix.has("matrix_kind")).isFalse();
        assertThat(matrix.at("/fact_rows/0/fact_id").asText()).startsWith("FACT_");
        assertThat(matrix.at("/fact_rows/0/origin/source_refs/0").asText())
                .isEqualTo("MESSAGE_P4_USER_2");
        ObjectNode hashInput = matrix.deepCopy();
        String contentHash = hashInput.remove("content_hash").asText();
        assertThat(contentHash).isEqualTo(ContractJson.sha256Hex(hashInput));
    }

    @Test
    void incrementsTheJavaVersionAndPreservesStableFactIds() throws Exception {
        var first = merger.merge(
                JSON.createObjectNode(),
                proposal(completeDossierPatch(), unilateralDraft()),
                matrixAuthority(ActorRole.USER));
        String factId = first.dossier()
                .at("/unilateral_case_matrix/fact_rows/0/fact_id")
                .asText();
        ObjectNode nextDraft = JSON.createObjectNode();
        nextDraft.put("schema_version", "unilateral_case_matrix.draft.v1");
        ObjectNode row = nextDraft.putArray("fact_rows").addObject();
        row.put("fact_key", factId);
        row.put("category", "PRODUCT_PAGE");
        row.put("fact_target", "The listing included basic installation.");
        row.put("materiality", "CORE");
        row.put("position_summary", "The buyer relied on the listing.");
        row.put("asserted_value", "Installation included");
        row.put("source_scope", "PREVIOUS_MATRIX");
        nextDraft.putArray("summary_source_fact_keys").add(factId);

        var second = merger.merge(
                first.dossier(),
                proposal(completeDossierPatch(), nextDraft),
                matrixAuthority(ActorRole.USER));

        assertThat(second.matrixVersion()).isEqualTo(2);
        assertThat(second.dossier().at("/unilateral_case_matrix/fact_rows/0/fact_id").asText())
                .isEqualTo(factId);
    }

    @Test
    void rejectsARehashedPriorProjectionWithUnknownShape() throws Exception {
        ObjectNode current = merger.merge(
                        JSON.createObjectNode(),
                        proposal(completeDossierPatch(), unilateralDraft()),
                        matrixAuthority(ActorRole.USER))
                .dossier();
        ObjectNode matrix = (ObjectNode) current.path("unilateral_case_matrix");
        ((ObjectNode) matrix.at("/fact_rows/0")).put("internal_notes", "must not propagate");
        matrix.remove("content_hash");
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));

        assertRejected(
                "INTAKE_MATRIX_CURRENT_INVALID",
                () -> merger.merge(
                        current,
                        proposal(completeDossierPatch(), unilateralDraft()),
                        matrixAuthority(ActorRole.USER)));
    }

    @Test
    void rejectsDossierValuesOutsideTheFrozenIdentifierContract() throws Exception {
        ObjectNode patch = (ObjectNode) completeDossierPatch();
        ((ObjectNode) patch.path("claim_resolution"))
                .put("requested_resolution", "not an identifier");

        assertRejected(
                "INTAKE_MATRIX_DOSSIER_INCOMPLETE",
                () -> merger.merge(
                        JSON.createObjectNode(),
                        proposal(patch, unilateralDraft()),
                        matrixAuthority(ActorRole.USER)));
    }

    @Test
    void rejectsFrozenMatrixTransitionsAndModelOwnedDerivedFields() throws Exception {
        JsonNode frozen = JSON.readTree(
                """
                {
                  "schema_version":"case_fact_matrix.v2",
                  "matrix_version":1,
                  "matrix_kind":"BILATERAL_FROZEN"
                }
                """);
        assertRejected(
                "INTAKE_MATRIX_FORMAL_TRANSITION_FORBIDDEN",
                () -> merger.merge(
                        JSON.createObjectNode(),
                        proposal(JSON.createObjectNode(), frozen),
                        matrixAuthority(ActorRole.USER)));

        JsonNode modelOwnedVersion = JSON.readTree(
                """
                {
                  "schema_version":"unilateral_case_matrix.draft.v1",
                  "matrix_version":99,
                  "fact_rows":[],
                  "summary_source_fact_keys":[]
                }
                """);
        assertRejected(
                "INTAKE_MATRIX_DERIVED_FIELD_FORBIDDEN",
                () -> merger.merge(
                        JSON.createObjectNode(),
                        proposal(JSON.createObjectNode(), modelOwnedVersion),
                        matrixAuthority(ActorRole.USER)));
    }

    @Test
    void respondentMatrixDraftRequiresTheLaterJavaConfirmCommand() throws Exception {
        assertRejected(
                "INTAKE_MATRIX_FORMAL_TRANSITION_FORBIDDEN",
                () -> merger.merge(
                        JSON.createObjectNode(),
                        proposal(completeDossierPatch(), unilateralDraft()),
                        matrixAuthority(ActorRole.MERCHANT)));
    }

    @Test
    void rejectsConflictingDossierReadinessAndRecommendation() throws Exception {
        JsonNode patch = JSON.readTree(
                """
                {
                  "intake_quality":{"ready_for_next_step":true},
                  "admission":{"recommendation":"ACCEPTED"}
                }
                """);

        assertRejected(
                "INTAKE_DOSSIER_READINESS_CONFLICT",
                () -> merger.merge(JSON.createObjectNode(), proposal(patch, null)));
    }

    @Test
    void rejectsDuplicateAndUnboundStableIdentifiers() throws Exception {
        JsonNode duplicateFacts = JSON.readTree(
                """
                {
                  "party_positions":{"fact_rows":[
                    {"fact_id":"FACT_DUP","category":"OTHER","fact_target":"A"},
                    {"fact_id":"FACT_DUP","category":"OTHER","fact_target":"A"}
                  ]}
                }
                """);
        assertRejected(
                "INTAKE_DOSSIER_STABLE_ID_CONFLICT",
                () -> merger.merge(duplicateFacts, proposal(JSON.createObjectNode(), null)));

        JsonNode unboundSource = JSON.readTree(
                """
                {"references":{"items":[{"source_id":"SOURCE_UNBOUND"}]}}
                """);
        assertRejected(
                "INTAKE_DOSSIER_STABLE_ID_UNBOUND",
                () -> merger.merge(unboundSource, proposal(JSON.createObjectNode(), null)));
    }

    private static JsonNode completeDossierPatch() throws Exception {
        return JSON.readTree(
                """
                {
                  "case_story":{"one_sentence_summary":"A disputed installation fee."},
                  "claim_resolution":{
                    "initiator_role":"USER",
                    "requested_resolution":"REFUND",
                    "requested_amount":150,
                    "request_reason":"The listing included installation.",
                    "normalized_statement":"Refund the installation fee."
                  },
                  "dispute_core_state":{
                    "core_conflict":"Whether installation was included.",
                    "facts_in_dispute":["Installation scope"],
                    "next_verification_focus":["Listing terms"]
                  }
                }
                """);
    }

    private static JsonNode unilateralDraft() throws Exception {
        return JSON.readTree(
                """
                {
                  "schema_version":"unilateral_case_matrix.draft.v1",
                  "fact_rows":[{
                    "fact_key":"NEW_INSTALL_SCOPE",
                    "category":"PRODUCT_PAGE",
                    "fact_target":"The listing included basic installation.",
                    "materiality":"CORE",
                    "position_summary":"The buyer relied on the listing.",
                    "asserted_value":"Installation included",
                    "source_scope":"CURRENT_SOURCE"
                  }],
                  "summary_source_fact_keys":["NEW_INSTALL_SCOPE"]
                }
                """);
    }

    private static MatrixAuthority matrixAuthority(ActorRole actorRole) {
        return new MatrixAuthority(
                "CASE_P4_SYNTHETIC_1",
                actorRole,
                ActorRole.USER,
                ActorRole.MERCHANT,
                "MESSAGE_P4_USER_2",
                "c".repeat(64));
    }

    private static IntakeTurnProposal proposal(JsonNode patch, JsonNode matrixPatch) {
        return new IntakeTurnProposal(
                "intake-turn-proposal.v2",
                "COMMAND_P4_USER_2",
                "RUN_P4_USER_2",
                "ATTEMPT_P4_USER_2_1",
                "CASE_P4_SYNTHETIC_1",
                1,
                IntakeTestFixtures.THREAD_ID,
                "a".repeat(64),
                "AGENT_SESSION_P4_USER_1",
                2,
                "b".repeat(64),
                "c".repeat(64),
                "Please confirm the requested resolution.",
                patch,
                matrixPatch,
                IntakeTurnProposal.Readiness.INCOMPLETE,
                List.of("requested_resolution_detail"),
                IntakeTurnProposal.Recommendation.NEED_MORE_INFO,
                IntakeTurnProposal.KnowledgeAnswerMode.NONE,
                new BigDecimal("0.82"),
                new IntakeTurnProposal.ProfileVersions(
                        "2.0.0",
                        "intake-checkpoint.v2",
                        "intake-prompt.v2",
                        "intake-model.synthetic.v1",
                        "intake-turn-proposal.v2",
                        "intake-policy.v2",
                        "intake-guardrail.v2",
                        "no-tools.v1"),
                "d".repeat(64));
    }

    private static void assertRejected(String code, ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .satisfies(failure -> assertThat(
                                ((IntakeFinalizationRejectedException) failure).code())
                        .isEqualTo(code));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
