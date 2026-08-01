package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeDossierProjectionMerger;
import com.example.dispute.workflow.application.intake.IntakeDossierProjectionMerger.ClaimResolutionAuthority;
import com.example.dispute.workflow.application.intake.IntakeDossierProjectionMerger.MatrixAuthority;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntakeDossierProjectionMergerTest {

    private static final JsonMapper JSON = new JsonMapper();
    private final IntakeDossierProjectionMerger merger = new IntakeDossierProjectionMerger();

    @Test
    void addsTheFormalDossierSchemaWhenTheModelPatchOmitsIt() {
        var result = merger.merge(
                JSON.createObjectNode(),
                proposal(JSON.createObjectNode(), null));

        assertThat(result.dossier().path("schema_version").asText())
                .isEqualTo("intake-dossier.v2");
    }

    @Test
    void acceptsTheExactFormalDossierSchemaFromTheModelPatch() throws Exception {
        JsonNode patch = JSON.readTree(
                """
                {
                  "schema_version":"intake-dossier.v2",
                  "case_story":{"summary":"schema-bound"}
                }
                """);

        var result = merger.merge(JSON.createObjectNode(), proposal(patch, null));

        assertThat(result.dossier().path("schema_version").asText())
                .isEqualTo("intake-dossier.v2");
        assertThat(result.dossier().at("/case_story/summary").asText())
                .isEqualTo("schema-bound");
    }

    @Test
    void rejectsConflictingOrUnknownModelDossierSchemas() {
        for (String schema : List.of("intake_case_detail.v1", "intake-dossier.v3")) {
            ObjectNode patch = JSON.createObjectNode();
            patch.put("schema_version", schema);

            assertRejected(
                    "INTAKE_DOSSIER_SCHEMA_INVALID",
                    () -> merger.merge(JSON.createObjectNode(), proposal(patch, null)));
        }

        ObjectNode nonTextual = JSON.createObjectNode();
        nonTextual.put("schema_version", 2);
        assertRejected(
                "INTAKE_DOSSIER_SCHEMA_INVALID",
                () -> merger.merge(JSON.createObjectNode(), proposal(nonTextual, null)));
    }

    @Test
    void preservesExistingProjectionAndMatrixMetadataWhileNormalizingTheRootSchema()
            throws Exception {
        JsonNode current = JSON.readTree(
                """
                {
                  "schema_version":"intake-dossier.v2",
                  "case_fact_matrix":{
                    "schema_version":"case_fact_matrix.v2",
                    "matrix_kind":"INITIATOR_FROZEN"
                  },
                  "intake_quality":{
                    "score":61,
                    "ready_for_next_step":false
                  },
                  "admission":{"recommendation":"NEED_MORE_INFO"}
                }
                """);

        var result = merger.merge(current, proposal(JSON.createObjectNode(), null));

        assertThat(result.dossier().path("schema_version").asText())
                .isEqualTo("intake-dossier.v2");
        assertThat(result.dossier().at("/case_fact_matrix/schema_version").asText())
                .isEqualTo("case_fact_matrix.v2");
        assertThat(result.dossier().at("/case_fact_matrix/matrix_kind").asText())
                .isEqualTo("INITIATOR_FROZEN");
        assertThat(result.dossier().at("/intake_quality/score").asInt()).isEqualTo(61);
        assertThat(result.dossier().at("/intake_quality/ready_for_next_step").asBoolean())
                .isFalse();
        assertThat(result.dossier().at("/admission/recommendation").asText())
                .isEqualTo("NEED_MORE_INFO");
    }

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
        assertThat(result.dossier().path("schema_version").asText())
                .isEqualTo("intake-dossier.v2");
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
    void derivesAndPersistsOnlyTheUnifiedInitiatorMatrixFromTheDraftShape() throws Exception {
        JsonNode dossierPatch = completeDossierPatch();
        JsonNode matrixPatch = unilateralDraft();

        var result = merger.merge(
                JSON.createObjectNode(),
                proposal(dossierPatch, matrixPatch),
                matrixAuthority(ActorRole.USER));

        ObjectNode matrix = (ObjectNode) result.dossier().path("case_fact_matrix");
        assertThat(result.matrixVersion()).isEqualTo(1);
        assertThat(matrix.path("schema_version").asText())
                .isEqualTo("case_fact_matrix.v2");
        assertThat(matrix.path("matrix_kind").asText()).isEqualTo("INITIATOR_FROZEN");
        assertThat(result.dossier().has("unilateral_case_matrix")).isFalse();
        assertThat(matrix.path("parent_ref").isNull()).isTrue();
        assertThat(matrix.at("/fact_rows/0/fact_id").asText()).startsWith("FACT_");
        assertThat(matrix.at("/fact_rows/0/origin/source_refs/0").asText())
                .isEqualTo("MESSAGE_P4_USER_2");
        assertThat(matrix.at("/fact_rows/0/positions/USER/stance").asText())
                .isEqualTo("CONFIRM");
        assertThat(matrix.at("/fact_rows/0/positions/MERCHANT/stance").asText())
                .isEqualTo("NOT_ADDRESSED");
        assertThat(matrix.at("/fact_rows/0/positions/MERCHANT/position_summary").asText())
                .isEqualTo("该方尚未直接陈述。");
        assertThat(matrix.at("/fact_rows/0/party_alignment/status").asText())
                .isEqualTo("NOT_COMPUTED");
        ObjectNode hashInput = matrix.deepCopy();
        String contentHash = hashInput.remove("content_hash").asText();
        assertThat(contentHash).isEqualTo(ContractJson.sha256Hex(hashInput));
    }

    @Test
    void buildsTheUnifiedMatrixFromTrustedClaimFactsWhenTheModelOmitsTheClaimBranch()
            throws Exception {
        JsonNode modelPatch = JSON.readTree(
                """
                {
                  "case_story":{"one_sentence_summary":"商品频繁自动关机，排障后仍未解决。"},
                  "dispute_core_state":{
                    "core_conflict":"商品故障是否应当换货或维修。",
                    "facts_in_dispute":["故障原因"],
                    "next_verification_focus":["订单与保修信息"]
                  }
                }
                """);
        MatrixAuthority authority = new MatrixAuthority(
                "CASE_PRODUCT_QUALITY_1",
                ActorRole.USER,
                ActorRole.USER,
                ActorRole.MERCHANT,
                "MESSAGE_PRODUCT_QUALITY_1",
                "a".repeat(64),
                new ClaimResolutionAuthority(
                        "REPLACE_OR_REPAIR", null, null, null));

        var result = merger.merge(
                JSON.createObjectNode(),
                proposal(modelPatch, unilateralDraft()),
                authority);

        assertThat(result.dossier().at("/claim_resolution/requested_resolution").asText())
                .isEqualTo("REPLACE_OR_REPAIR");
        assertThat(result.dossier()
                        .at("/case_fact_matrix/claims/initiator_claim/requested_resolution")
                        .asText())
                .isEqualTo("REPLACE_OR_REPAIR");
        assertThat(result.dossier().at("/case_fact_matrix/schema_version").asText())
                .isEqualTo("case_fact_matrix.v2");
        assertThat(result.dossier().at("/case_fact_matrix/matrix_kind").asText())
                .isEqualTo("INITIATOR_FROZEN");
        assertThat(result.dossier().has("unilateral_case_matrix")).isFalse();
    }

    @Test
    void derivesTheBaselineCoreStateWhenTheModelProvidesMatrixSemanticsWithoutTheBranch()
            throws Exception {
        JsonNode modelPatch = JSON.readTree(
                """
                {
                  "case_story":{"one_sentence_summary":"The product repeatedly shuts down after ten days."},
                  "dispute_focus":{"focus_points":["Recurring shutdown","Troubleshooting did not resolve it"]},
                  "missing_information":{"missing_fields":["product_model","purchase_date"]},
                  "respondent_attitude":{
                    "status":"NOT_RESPONDED",
                    "note":"The merchant has not provided a direct statement."
                  }
                }
                """);
        MatrixAuthority authority = new MatrixAuthority(
                "CASE_PRODUCT_QUALITY_2",
                ActorRole.USER,
                ActorRole.USER,
                ActorRole.MERCHANT,
                "MESSAGE_PRODUCT_QUALITY_2",
                "b".repeat(64),
                new ClaimResolutionAuthority(
                        "REPLACE_OR_REPAIR", null, null, null));

        var result = merger.merge(
                JSON.createObjectNode(),
                proposal(modelPatch, unilateralDraft()),
                authority);

        assertThat(result.dossier().at("/dispute_core_state/core_conflict").asText())
                .isEqualTo("The product repeatedly shuts down after ten days.");
        assertThat(result.dossier().at("/dispute_core_state/facts_in_dispute/0").asText())
                .isEqualTo("Recurring shutdown");
        assertThat(result.dossier().at("/dispute_core_state/next_verification_focus/0").asText())
                .isEqualTo("product_model");
        assertThat(result.dossier().at("/case_fact_matrix/case_overview/core_conflict").asText())
                .isEqualTo("The product repeatedly shuts down after ten days.");
        assertThat(result.dossier()
                        .at("/case_fact_matrix/claims/initiator_claim/requested_resolution")
                        .asText())
                .isEqualTo("REPLACE_OR_REPAIR");
        assertThat(result.dossier().at("/case_fact_matrix/matrix_kind").asText())
                .isEqualTo("INITIATOR_FROZEN");
        assertThat(result.dossier()
                        .at("/case_fact_matrix/claims/respondent_reported_by_initiator")
                        .isNull())
                .isTrue();
        assertThat(result.dossier()
                        .at("/case_fact_matrix/fact_rows/0/positions/MERCHANT/stance")
                        .asText())
                .isEqualTo("NOT_ADDRESSED");
        assertThat(result.dossier().has("unilateral_case_matrix")).isFalse();
    }

    @Test
    void canonicalizesTheObservedPartialCoreStateBeforeFormalMatrixFinalization()
            throws Exception {
        JsonNode modelPatch = JSON.readTree(
                """
                {
                  "case_story":{
                    "one_sentence_summary":"用户称商品在保修维修后不到两周再次出现同一故障。"
                  },
                  "dispute_core_state":{
                    "blocker":"缺少故障复现的具体时间细节及用户明确的首选解决方案",
                    "current_status":"INITIATED",
                    "fact_disputes":["故障复现的具体时长","用户对处理方案的最终偏好"]
                  },
                  "missing_information":{
                    "missing_facts":["距离上次维修完成的具体天数"],
                    "next_questions":["距离上次维修完成具体过去了多少天？"]
                  }
                }
                """);
        MatrixAuthority authority = new MatrixAuthority(
                "CASE_PRODUCT_QUALITY_3",
                ActorRole.USER,
                ActorRole.USER,
                ActorRole.MERCHANT,
                "MESSAGE_PRODUCT_QUALITY_3",
                "c".repeat(64),
                new ClaimResolutionAuthority(
                        "REPLACE_OR_REPAIR", null, null, null));

        var result = merger.merge(
                JSON.createObjectNode(),
                proposal(modelPatch, unilateralDraft()),
                authority);

        assertThat(result.dossier().at("/dispute_core_state/core_conflict").asText())
                .isEqualTo("用户称商品在保修维修后不到两周再次出现同一故障。");
        assertThat(result.dossier().at("/dispute_core_state/facts_in_dispute/0").asText())
                .isEqualTo("故障复现的具体时长");
        assertThat(result.dossier().at("/dispute_core_state/next_verification_focus/0").asText())
                .isEqualTo("距离上次维修完成的具体天数");
        assertThat(result.dossier().at("/case_fact_matrix/case_overview/core_conflict").asText())
                .isEqualTo("用户称商品在保修维修后不到两周再次出现同一故障。");
        assertThat(result.dossier().at("/dispute_core_state/facts_in_dispute/1").asText())
                .isEqualTo("用户对处理方案的最终偏好");
        assertThat(result.dossier().at("/dispute_core_state/blocker").isMissingNode()).isTrue();
        assertThat(result.dossier().at("/dispute_core_state/current_status").isMissingNode())
                .isTrue();
        assertThat(result.dossier().at("/dispute_core_state/fact_disputes").isMissingNode())
                .isTrue();
    }

    @Test
    void independentlyBackfillsCoreArraysWithoutReplacingAnExplicitCoreConflict()
            throws Exception {
        JsonNode modelPatch = JSON.readTree(
                """
                {
                  "case_story":{"one_sentence_summary":"模型案情摘要。"},
                  "dispute_focus":{"focus_points":["维修后故障是否再次发生"]},
                  "dispute_core_state":{"core_conflict":"用户请求换货，商家尚未直接回应。"},
                  "missing_information":{
                    "missing_facts":["距离上次维修完成的具体天数"],
                    "next_questions":["距离上次维修完成具体过去了多少天？"]
                  }
                }
                """);
        MatrixAuthority authority = new MatrixAuthority(
                "CASE_PRODUCT_QUALITY_4",
                ActorRole.USER,
                ActorRole.USER,
                ActorRole.MERCHANT,
                "MESSAGE_PRODUCT_QUALITY_4",
                "d".repeat(64),
                new ClaimResolutionAuthority(
                        "REPLACE_OR_REPAIR", null, null, null));

        var result = merger.merge(
                JSON.createObjectNode(),
                proposal(modelPatch, unilateralDraft()),
                authority);

        assertThat(result.dossier().at("/dispute_core_state/core_conflict").asText())
                .isEqualTo("用户请求换货，商家尚未直接回应。");
        assertThat(result.dossier().at("/dispute_core_state/facts_in_dispute/0").asText())
                .isEqualTo("维修后故障是否再次发生");
        assertThat(result.dossier().at("/dispute_core_state/next_verification_focus/0").asText())
                .isEqualTo("距离上次维修完成的具体天数");
        assertThat(result.dossier().at("/dispute_core_state/next_verification_focus/1").isMissingNode())
                .isTrue();
    }

    @Test
    void treatsBaselineNoResponseAliasesAsUnaddressedWithoutCreatingAReportedClaim()
            throws Exception {
        for (String status :
                List.of("UNKNOWN", "PLATFORM_UNKNOWN", "NOT_RESPONDED", "NOT_ADDRESSED")) {
            ObjectNode modelPatch = (ObjectNode) completeDossierPatch();
            modelPatch.putObject("party_positions").putArray("respondent_statements");
            modelPatch.putObject("respondent_attitude")
                    .put("status", status)
                    .put("description", "待确认");

            var result = merger.merge(
                    JSON.createObjectNode(),
                    proposal(modelPatch, unilateralDraft()),
                    matrixAuthority(ActorRole.USER));

            assertThat(result.dossier().at("/case_fact_matrix/matrix_kind").asText())
                    .isEqualTo("INITIATOR_FROZEN");
            assertThat(result.dossier()
                            .at("/case_fact_matrix/claims/respondent_reported_by_initiator")
                            .isNull())
                    .isTrue();
            assertThat(result.dossier()
                            .at("/case_fact_matrix/fact_rows/0/positions/MERCHANT/stance")
                            .asText())
                    .isEqualTo("NOT_ADDRESSED");
        }
    }

    @Test
    void rejectsArbitraryRespondentAttitudeStatus() throws Exception {
        ObjectNode modelPatch = (ObjectNode) completeDossierPatch();
        modelPatch.putObject("respondent_attitude")
                .put("status", "PENDING_REVIEW")
                .put("position", "The respondent position is pending review.");

        assertRejected(
                "INTAKE_MATRIX_DOSSIER_INCOMPLETE",
                () -> merger.merge(
                        JSON.createObjectNode(),
                        proposal(modelPatch, unilateralDraft()),
                        matrixAuthority(ActorRole.USER)));
    }

    @Test
    void incrementsTheJavaVersionAndPreservesStableFactIds() throws Exception {
        var first = merger.merge(
                JSON.createObjectNode(),
                proposal(completeDossierPatch(), unilateralDraft()),
                matrixAuthority(ActorRole.USER));
        ObjectNode firstMatrix = (ObjectNode) first.dossier().path("case_fact_matrix");
        String factId = firstMatrix.at("/fact_rows/0/fact_id").asText();
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

        MatrixAuthority secondAuthority = new MatrixAuthority(
                "CASE_P4_SYNTHETIC_1",
                ActorRole.USER,
                ActorRole.USER,
                ActorRole.MERCHANT,
                "MESSAGE_P4_USER_3",
                "f".repeat(64));
        var second = merger.merge(
                first.dossier(),
                proposal(completeDossierPatch(), nextDraft),
                secondAuthority);

        assertThat(second.matrixVersion()).isEqualTo(2);
        ObjectNode secondMatrix = (ObjectNode) second.dossier().path("case_fact_matrix");
        assertThat(second.dossier().has("unilateral_case_matrix")).isFalse();
        assertThat(secondMatrix.at("/fact_rows/0/fact_id").asText())
                .isEqualTo(factId);
        assertThat(secondMatrix.at("/parent_ref/matrix_id"))
                .isEqualTo(firstMatrix.path("matrix_id"));
        assertThat(secondMatrix.at("/parent_ref/matrix_version").asLong()).isEqualTo(1);
        assertThat(secondMatrix.at("/parent_ref/content_hash"))
                .isEqualTo(firstMatrix.path("content_hash"));
        assertThat(secondMatrix.path("source_refs")).contains(firstMatrix.path("source_refs").get(0));
        assertThat(secondMatrix.path("source_refs").toString()).contains("MESSAGE_P4_USER_3");
    }

    @Test
    void bridgesADeployedLegacyProjectionIntoTheUnifiedVersionChain() throws Exception {
        ObjectNode current = dossierWithInitiatorMatrix();
        ObjectNode legacy = legacyProjectionFrom(current);
        current.remove("case_fact_matrix");
        current.set("unilateral_case_matrix", legacy);
        String factId = legacy.at("/fact_rows/0/fact_id").asText();
        MatrixAuthority authority = new MatrixAuthority(
                "CASE_P4_SYNTHETIC_1",
                ActorRole.USER,
                ActorRole.USER,
                ActorRole.MERCHANT,
                "MESSAGE_P4_USER_3",
                "f".repeat(64));

        var result = merger.merge(
                current,
                proposal(completeDossierPatch(), carryForwardDraft(factId)),
                authority);

        JsonNode formal = result.dossier().path("case_fact_matrix");
        assertThat(result.dossier().has("unilateral_case_matrix")).isFalse();
        assertThat(formal.path("matrix_version").asLong())
                .isEqualTo(legacy.path("matrix_version").asLong() + 1);
        assertThat(formal.at("/parent_ref/matrix_version"))
                .isEqualTo(legacy.path("matrix_version"));
        assertThat(formal.at("/parent_ref/content_hash"))
                .isEqualTo(legacy.path("content_hash"));
        assertThat(formal.at("/fact_rows/0/fact_id").asText()).isEqualTo(factId);
    }

    @Test
    void continuesAnInitiatorMatrixAfterMoreThanTwentyAuthorizedSources() throws Exception {
        ObjectNode current = dossierWithInitiatorMatrix();
        ObjectNode formal = (ObjectNode) current.path("case_fact_matrix");
        for (int index = 2; index <= 21; index++) {
            String sourceRef = "MESSAGE_P4_USER_HISTORY_" + index;
            formal.withArray("source_refs").add(sourceRef);
            ((ArrayNode) formal.at("/fact_rows/0/origin/source_refs")).add(sourceRef);
            ((ArrayNode) formal.at("/fact_rows/0/positions/USER/source_refs")).add(sourceRef);
            ((ArrayNode) formal.at("/claims/initiator_claim/source_refs")).add(sourceRef);
        }
        formal.remove("content_hash");
        formal.put("content_hash", ContractJson.sha256Hex(formal));
        String factId = formal.at("/fact_rows/0/fact_id").asText();
        MatrixAuthority authority = new MatrixAuthority(
                "CASE_P4_SYNTHETIC_1",
                ActorRole.USER,
                ActorRole.USER,
                ActorRole.MERCHANT,
                "MESSAGE_P4_USER_3",
                "f".repeat(64));

        var result = merger.merge(
                current,
                proposal(completeDossierPatch(), carryForwardDraft(factId)),
                authority);

        assertThat(result.dossier().at("/case_fact_matrix/source_refs").size()).isEqualTo(22);
        assertThat(result.dossier().at("/case_fact_matrix/matrix_version").asLong())
                .isEqualTo(2);
    }

    @Test
    void removesAStaleLegacyBranchAfterRespondentParentValidation() throws Exception {
        ObjectNode current = dossierWithInitiatorMatrix();
        ObjectNode parent = ((ObjectNode) current.path("case_fact_matrix")).deepCopy();
        current.set("unilateral_case_matrix", legacyProjectionFrom(current));

        var result = merger.merge(
                current,
                proposal(
                        JSON.createObjectNode(),
                        respondentDelta(parent.at("/fact_rows/0/fact_id").asText()),
                        IntakeTurnProposal.Readiness.INCOMPLETE,
                        List.of("respondent_supporting_evidence")),
                matrixAuthority(ActorRole.MERCHANT));

        assertThat(result.dossier().path("case_fact_matrix")).isEqualTo(parent);
        assertThat(result.dossier().has("unilateral_case_matrix")).isFalse();
    }

    @Test
    void retainsLegacyAuthorityWhenAnObjectShapedFormalBranchWasNotValidated() throws Exception {
        ObjectNode current = dossierWithInitiatorMatrix();
        current.set("unilateral_case_matrix", legacyProjectionFrom(current));
        current.set(
                "case_fact_matrix",
                JSON.createObjectNode()
                        .put("schema_version", "case_fact_matrix.v2")
                        .put("matrix_kind", "INITIATOR_FROZEN"));

        var result = merger.merge(
                current, proposal(JSON.createObjectNode(), null));

        assertThat(result.dossier().has("unilateral_case_matrix")).isTrue();
    }

    @Test
    void rejectsARehashedPriorProjectionWithUnknownShape() throws Exception {
        ObjectNode current = merger.merge(
                        JSON.createObjectNode(),
                        proposal(completeDossierPatch(), unilateralDraft()),
                        matrixAuthority(ActorRole.USER))
                .dossier();
        ObjectNode matrix = (ObjectNode) current.path("case_fact_matrix");
        ((ObjectNode) matrix.at("/fact_rows/0")).put("internal_notes", "must not propagate");
        matrix.remove("content_hash");
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));

        assertRejected(
                "INTAKE_INITIATOR_MATRIX_STRUCTURE_INVALID",
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
    void rejectsMatrixPatchSchemasFromTheWrongPartyAuthority() throws Exception {
        assertRejected(
                "INTAKE_MATRIX_FORMAL_TRANSITION_FORBIDDEN",
                () -> merger.merge(
                        JSON.createObjectNode(),
                        proposal(completeDossierPatch(), unilateralDraft()),
                        matrixAuthority(ActorRole.MERCHANT)));

        assertRejected(
                "INTAKE_RESPONDENT_MATRIX_AUTHORITY_INVALID",
                () -> merger.merge(
                        JSON.createObjectNode(),
                        proposal(
                                JSON.createObjectNode(),
                                respondentDelta("FACT_NOT_VISIBLE")),
                        matrixAuthority(ActorRole.USER)));
    }

    @Test
    void validatesRespondentDeltasButFreezesOnlyACompleteReadyProposal() throws Exception {
        ObjectNode current = dossierWithInitiatorMatrix();
        ObjectNode parent = ((ObjectNode) current.path("case_fact_matrix")).deepCopy();
        JsonNode delta = respondentDelta(parent.at("/fact_rows/0/fact_id").asText());

        var incomplete = merger.merge(
                current,
                proposal(
                        JSON.createObjectNode(),
                        delta,
                        IntakeTurnProposal.Readiness.INCOMPLETE,
                        List.of("respondent_supporting_evidence")),
                matrixAuthority(ActorRole.MERCHANT));

        assertThat(incomplete.dossier().path("case_fact_matrix")).isEqualTo(parent);
        assertThat(incomplete.matrixVersion()).isNull();

        var needsReview = merger.merge(
                current,
                proposal(
                        JSON.createObjectNode(),
                        delta,
                        IntakeTurnProposal.Readiness.NEEDS_REVIEW,
                        List.of()),
                matrixAuthority(ActorRole.MERCHANT));

        assertThat(needsReview.dossier().path("case_fact_matrix")).isEqualTo(parent);
        assertThat(needsReview.matrixVersion()).isNull();

        assertRejected(
                "INTAKE_RESPONDENT_MATRIX_NOT_READY",
                () -> merger.merge(
                        current,
                        proposal(
                                JSON.createObjectNode(),
                                null,
                                IntakeTurnProposal.Readiness.READY_TO_CONFIRM,
                                List.of()),
                        matrixAuthority(ActorRole.MERCHANT)));

        var ready = merger.merge(
                current,
                proposal(
                        JSON.createObjectNode(),
                        delta,
                        IntakeTurnProposal.Readiness.READY_TO_CONFIRM,
                        List.of()),
                matrixAuthority(ActorRole.MERCHANT));

        assertThat(ready.dossier().at("/case_fact_matrix/matrix_kind").asText())
                .isEqualTo("BILATERAL_FROZEN");
        assertThat(ready.dossier().at("/case_fact_matrix/parent_ref/content_hash"))
                .isEqualTo(parent.path("content_hash"));
        assertThat(ready.matrixVersion()).isEqualTo(2);
    }

    @Test
    void rejectsInconsistentTypedReadinessAndRecommendation() {
        assertRejected(
                "INTAKE_PROPOSAL_OUTCOME_CONFLICT",
                () -> merger.merge(
                        JSON.createObjectNode(),
                        proposal(
                                JSON.createObjectNode(),
                                null,
                                IntakeTurnProposal.Readiness.READY_TO_CONFIRM,
                                List.of(),
                                IntakeTurnProposal.Recommendation.NEED_MORE_INFO)));
        assertRejected(
                "INTAKE_PROPOSAL_OUTCOME_CONFLICT",
                () -> merger.merge(
                        JSON.createObjectNode(),
                        proposal(
                                JSON.createObjectNode(),
                                null,
                                IntakeTurnProposal.Readiness.INCOMPLETE,
                                List.of("admissibility_review"),
                                IntakeTurnProposal.Recommendation.NOT_ADMISSIBLE)));
    }

    @Test
    void rejectsJavaDerivedMatrixMetadataHiddenInOrdinaryDossierBranches() throws Exception {
        for (String key : List.of(
                "case_fact_matrix",
                "unilateral_case_matrix",
                "matrix_patch",
                "matrix_id",
                "matrix_version",
                "matrix_kind",
                "generation_ref",
                "parent_ref",
                "party_map",
                "fact_indexes",
                "source_binding",
                "truth_status",
                "fact_relationships",
                "summary_source_fact_ids",
                "evidence_coverage_status")) {
            ObjectNode patch = JSON.createObjectNode();
            patch.putObject("case_story")
                    .putArray("nested")
                    .addObject()
                    .put(key, "model-owned");
            assertRejected(
                    "INTAKE_MATRIX_DERIVED_FIELD_FORBIDDEN",
                    () -> merger.merge(
                            JSON.createObjectNode(), proposal(patch, null)));
        }

        for (String schema : List.of(
                "unilateral_case_matrix.v1",
                "unilateral_case_matrix.draft.v1",
                "case_fact_matrix.v2",
                "case_fact_matrix.delta.v2")) {
            ObjectNode patch = JSON.createObjectNode();
            patch.putObject("case_story")
                    .putArray("nested")
                    .addObject()
                    .put("schema_version", schema);
            assertRejected(
                    "INTAKE_MATRIX_DERIVED_FIELD_FORBIDDEN",
                    () -> merger.merge(
                            JSON.createObjectNode(), proposal(patch, null)));
        }

        ObjectNode ordinaryStableReferences = (ObjectNode) JSON.readTree(
                """
                {
                  "party_positions":{"fact_rows":[{
                    "fact_id":"FACT_ORDINARY",
                    "category":"OTHER",
                    "fact_target":"An ordinary dossier fact."
                  }]},
                  "references":{"source_refs":["MESSAGE_ORDINARY"]}
                }
                """);
        ((ObjectNode) ordinaryStableReferences.at("/party_positions/fact_rows/0"))
                .put("content_hash", "f".repeat(64));
        var accepted = merger.merge(
                JSON.createObjectNode(), proposal(ordinaryStableReferences, null));
        assertThat(accepted.dossier().at("/party_positions/fact_rows/0/fact_id").asText())
                .isEqualTo("FACT_ORDINARY");
        assertThat(accepted.dossier().at("/references/source_refs/0").asText())
                .isEqualTo("MESSAGE_ORDINARY");
        assertThat(accepted.dossier().at("/party_positions/fact_rows/0/content_hash").asText())
                .isEqualTo("f".repeat(64));
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

    private static ObjectNode carryForwardDraft(String factId) {
        ObjectNode draft = JSON.createObjectNode();
        draft.put("schema_version", "unilateral_case_matrix.draft.v1");
        ObjectNode row = draft.putArray("fact_rows").addObject();
        row.put("fact_key", factId);
        row.put("category", "PRODUCT_PAGE");
        row.put("fact_target", "The listing included basic installation.");
        row.put("materiality", "CORE");
        row.put("position_summary", "The buyer relied on the listing.");
        row.put("asserted_value", "Installation included");
        row.put("source_scope", "PREVIOUS_MATRIX");
        draft.putArray("summary_source_fact_keys").add(factId);
        return draft;
    }

    private static ObjectNode legacyProjectionFrom(ObjectNode dossier) {
        ObjectNode formal = (ObjectNode) dossier.path("case_fact_matrix");
        ObjectNode legacy = JSON.createObjectNode();
        legacy.put("schema_version", "unilateral_case_matrix.v1");
        legacy.set("matrix_version", formal.required("matrix_version").deepCopy());
        ObjectNode sourceBinding = legacy.putObject("source_binding");
        sourceBinding.set("case_id", formal.required("case_id").deepCopy());
        sourceBinding.put("source_stage", "INTAKE");
        sourceBinding.set("source_refs", formal.required("source_refs").deepCopy());
        sourceBinding.set(
                "latest_source_ref",
                formal.required("generation_ref").required("latest_source_ref").deepCopy());
        sourceBinding.set(
                "source_context_hash",
                formal.required("generation_ref").required("source_context_hash").deepCopy());
        legacy.set("party_map", formal.required("party_map").deepCopy());
        legacy.set(
                "case_summary",
                formal.required("case_overview").required("neutral_summary").deepCopy());
        legacy.set(
                "summary_source_fact_ids",
                formal.required("case_overview").required("summary_source_fact_ids").deepCopy());
        legacy.set(
                "claim_resolution",
                formal.required("claims").required("initiator_claim").deepCopy());
        legacy.set("dispute_core_state", dossier.required("dispute_core_state").deepCopy());
        var rows = legacy.putArray("fact_rows");
        for (JsonNode candidate : formal.withArray("fact_rows")) {
            ObjectNode row = rows.addObject();
            for (String field : List.of("fact_id", "category", "fact_target", "materiality")) {
                row.set(field, candidate.required(field).deepCopy());
            }
            row.putObject("origin")
                    .put("source_stage", "INTAKE")
                    .set(
                            "source_refs",
                            candidate.required("origin").required("source_refs").deepCopy());
            ObjectNode position = row.putObject("initiator_position");
            JsonNode direct = candidate.required("positions").required("USER");
            for (String field : List.of(
                    "stance", "position_summary", "asserted_value", "source_refs")) {
                position.set(field, direct.required(field).deepCopy());
            }
            row.set("truth_status", candidate.required("truth_status").deepCopy());
        }
        legacy.put("content_hash", ContractJson.sha256Hex(legacy));
        return legacy;
    }

    private ObjectNode dossierWithInitiatorMatrix() throws Exception {
        return merger.merge(
                        JSON.createObjectNode(),
                        proposal(completeDossierPatch(), unilateralDraft()),
                        matrixAuthority(ActorRole.USER))
                .dossier();
    }

    private static JsonNode respondentDelta(String factId) {
        ObjectNode delta = JSON.createObjectNode();
        delta.put("schema_version", "case_fact_matrix.delta.v2");
        ObjectNode row = delta.putArray("fact_rows").addObject();
        row.put("fact_key", factId);
        row.put("category", "PRODUCT_PAGE");
        row.put("fact_target", "The listing included basic installation.");
        row.put("materiality", "CORE");
        row.put("stance", "CONFIRM");
        row.put("position_summary", "The merchant confirms the listing terms.");
        row.put("asserted_value", "Installation included");
        row.put("source_scope", "CURRENT_SOURCE");
        delta.putArray("summary_source_fact_keys").add(factId);
        delta.putObject("respondent_claim")
                .put("attitude", "DISAGREE")
                .put("position_summary", "The merchant disputes the requested refund.");
        return delta;
    }

    private static MatrixAuthority matrixAuthority(ActorRole actorRole) {
        boolean respondent = actorRole == ActorRole.MERCHANT;
        return new MatrixAuthority(
                "CASE_P4_SYNTHETIC_1",
                actorRole,
                ActorRole.USER,
                ActorRole.MERCHANT,
                respondent ? "MESSAGE_P4_MERCHANT_2" : "MESSAGE_P4_USER_2",
                (respondent ? "e" : "c").repeat(64));
    }

    private static IntakeTurnProposal proposal(JsonNode patch, JsonNode matrixPatch) {
        return proposal(
                patch,
                matrixPatch,
                IntakeTurnProposal.Readiness.INCOMPLETE,
                List.of("requested_resolution_detail"));
    }

    private static IntakeTurnProposal proposal(
            JsonNode patch,
            JsonNode matrixPatch,
            IntakeTurnProposal.Readiness readiness,
            List<String> missingFields) {
        IntakeTurnProposal.Recommendation recommendation =
                readiness == IntakeTurnProposal.Readiness.READY_TO_CONFIRM
                        ? IntakeTurnProposal.Recommendation.ACCEPTED
                        : IntakeTurnProposal.Recommendation.NEED_MORE_INFO;
        return proposal(patch, matrixPatch, readiness, missingFields, recommendation);
    }

    private static IntakeTurnProposal proposal(
            JsonNode patch,
            JsonNode matrixPatch,
            IntakeTurnProposal.Readiness readiness,
            List<String> missingFields,
            IntakeTurnProposal.Recommendation recommendation) {
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
                readiness,
                missingFields,
                recommendation,
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
