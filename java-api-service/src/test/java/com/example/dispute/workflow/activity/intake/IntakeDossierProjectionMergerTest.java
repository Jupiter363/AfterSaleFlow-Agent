package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeDossierProjectionMerger;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
        assertThat(result.qualityScore()).isEqualTo(82);
        assertThat(result.readyForNextStep()).isFalse();
        assertThat(result.recommendation()).isEqualTo("NEED_MORE_INFO");
    }

    @Test
    void rejectsStableFactRebindingAgainstTheCurrentDomainProjection() throws Exception {
        JsonNode current = JSON.readTree(
                """
                {
                  "case_fact_matrix":{
                    "fact_rows":[{
                      "fact_id":"FACT_DAMAGE",
                      "category":"PRODUCT",
                      "fact_target":"The product arrived damaged."
                    }],
                    "source_refs":["MESSAGE_1"]
                  }
                }
                """);
        IntakeTurnProposal proposal = proposal(
                JSON.readTree(
                        """
                        {
                          "case_fact_matrix":{
                            "fact_rows":[{
                              "fact_id":"FACT_DAMAGE",
                              "category":"PRODUCT",
                              "fact_target":"A different asserted fact."
                            }]
                          }
                        }
                        """),
                null);

        assertThatThrownBy(() -> merger.merge(current, proposal))
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .satisfies(failure -> assertThat(
                                ((IntakeFinalizationRejectedException) failure).code())
                        .isEqualTo("INTAKE_DOSSIER_STABLE_ID_REBOUND"));
    }

    @Test
    void appliesAnExplicitVersionedMatrixPatchToOneFormalBranch() throws Exception {
        JsonNode current = JSON.readTree(
                """
                {
                  "case_fact_matrix":{
                    "schema_version":"case_fact_matrix.v2",
                    "matrix_version":1,
                    "matrix_kind":"UNILATERAL_DRAFT",
                    "source_refs":["MESSAGE_1"]
                  }
                }
                """);
        JsonNode matrixPatch = JSON.readTree(
                """
                {
                  "schema_version":"case_fact_matrix.v2",
                  "matrix_version":2,
                  "matrix_kind":"INITIATOR_FROZEN"
                }
                """);

        var result = merger.merge(current, proposal(JSON.createObjectNode(), matrixPatch));

        assertThat(result.matrixVersion()).isEqualTo(2);
        assertThat(result.dossier().at("/case_fact_matrix/matrix_kind").asText())
                .isEqualTo("INITIATOR_FROZEN");
        assertThat(result.dossier().at("/case_fact_matrix/source_refs/0").asText())
                .isEqualTo("MESSAGE_1");
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
}
