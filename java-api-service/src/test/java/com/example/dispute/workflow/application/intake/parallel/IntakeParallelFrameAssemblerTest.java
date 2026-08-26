package com.example.dispute.workflow.application.intake.parallel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.intake.IntakeTurnProposal;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal.ProfileVersions;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameAssembler.AssemblyCommand;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameAssembler.AssemblyRejectedException;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameAssembler.SealedFrame;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameType;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class IntakeParallelFrameAssemblerTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);
    private static final String SOURCE_MESSAGE = "MESSAGE_123";
    private final IntakeParallelFrameAssembler assembler = new IntakeParallelFrameAssembler();

    @Test
    void assemblesTheSameProposalForAllSixFrameArrivalOrders() {
        ObjectNode previous = previousDossier("NOT_READY", 0, false);
        List<List<FrameType>> orders = List.of(
                List.of(FrameType.DIALOGUE_FRAME, FrameType.DOSSIER_FRAME, FrameType.QUALITY_FRAME),
                List.of(FrameType.DIALOGUE_FRAME, FrameType.QUALITY_FRAME, FrameType.DOSSIER_FRAME),
                List.of(FrameType.DOSSIER_FRAME, FrameType.DIALOGUE_FRAME, FrameType.QUALITY_FRAME),
                List.of(FrameType.DOSSIER_FRAME, FrameType.QUALITY_FRAME, FrameType.DIALOGUE_FRAME),
                List.of(FrameType.QUALITY_FRAME, FrameType.DIALOGUE_FRAME, FrameType.DOSSIER_FRAME),
                List.of(FrameType.QUALITY_FRAME, FrameType.DOSSIER_FRAME, FrameType.DIALOGUE_FRAME));

        var outputs = orders.stream()
                .map(order -> assembler.assemble(command(previous, orderedFrames(
                        order,
                        dialogue(previous, "ASK_SUBSTANTIVE"),
                        dossier(),
                        quality(Map.of(
                                "references", 15,
                                "event_story", 20,
                                "party_positions", 20,
                                "requested_resolution", 15,
                                "risk_and_conflicts", 15,
                                "next_action_clarity", 15),
                                List.of())))))
                .toList();

        assertThat(outputs)
                .extracting(output -> output.inputSetSha256())
                .containsOnly(outputs.getFirst().inputSetSha256());
        assertThat(outputs)
                .extracting(output -> output.proposalSha256())
                .containsOnly(outputs.getFirst().proposalSha256());
        assertThat(outputs)
                .extracting(output -> output.graphResult().outputHash())
                .containsOnly(outputs.getFirst().graphResult().outputHash());
        var first = outputs.getFirst();
        assertThat(first.artifactId())
                .isEqualTo("intake.proposal." + first.proposalSha256().substring(0, 32));
        assertThat(first.artifactUri())
                .isEqualTo("urn:target-e2e:proposal:intake:" + first.proposalSha256());
        assertThat(first.proposal().schemaVersion()).isEqualTo("intake-turn-proposal.v2");
        assertThat(first.proposal().dossierPatch().at("/case_story/one_sentence_summary").asText())
                .isEqualTo("本轮补充了核心事实");
    }

    @Test
    void previousPhaseOwnsCurrentActionWhileQualityOwnsOnlyTheNextState() {
        ObjectNode previous = previousDossier("NOT_READY", 0, false);
        var output = assembler.assemble(command(previous, frames(
                dialogue(previous, "ASK_SUBSTANTIVE"),
                dossier(),
                quality(Map.of(
                        "references", 15,
                        "event_story", 20,
                        "party_positions", 20,
                        "requested_resolution", 15,
                        "risk_and_conflicts", 15,
                        "next_action_clarity", 15),
                        List.of()))));

        IntakeTurnProposal proposal = output.proposal();
        assertThat(proposal.conversationAction())
                .isEqualTo(IntakeTurnProposal.ConversationAction.ASK_SUBSTANTIVE);
        assertThat(proposal.readiness())
                .isEqualTo(IntakeTurnProposal.Readiness.READY_TO_CONFIRM);
        assertThat(proposal.missingFields()).isEmpty();
        assertThat(proposal.dossierPatch()
                        .at("/party_intake_state/USER/handoff_notes/remark_status")
                        .asText())
                .isEqualTo("READY_PENDING_REMARK_INVITE");
        assertThat(proposal.dossierPatch()
                        .at("/party_intake_state/USER/intake_quality/score")
                        .asInt())
                .isEqualTo(100);
        assertThat(proposal.roomUtterance()).doesNotContain("total_score");
    }

    @Test
    void readyPendingRemarkCannotRegressEvenWhenTheCurrentQualityFrameIsLower() {
        ObjectNode previous = previousDossier("READY_PENDING_REMARK_INVITE", 90, true);
        var output = assembler.assemble(command(previous, frames(
                dialogue(previous, "INVITE_OPTIONAL_REMARK"),
                dossier(),
                quality(Map.of(
                        "references", 1,
                        "event_story", 1,
                        "party_positions", 1,
                        "requested_resolution", 1,
                        "risk_and_conflicts", 1,
                        "next_action_clarity", 1),
                        List.of(gap("EVENT_STORY", "请补充事件经过？"))))));

        assertThat(output.proposal().conversationAction())
                .isEqualTo(IntakeTurnProposal.ConversationAction.INVITE_OPTIONAL_REMARK);
        assertThat(output.proposal().readiness())
                .isEqualTo(IntakeTurnProposal.Readiness.READY_TO_CONFIRM);
        assertThat(output.proposal().dossierPatch()
                        .at("/party_intake_state/USER/intake_quality/score")
                        .asInt())
                .isEqualTo(90);
        assertThat(output.proposal().dossierPatch()
                        .at("/party_intake_state/USER/handoff_notes/remark_status")
                        .asText())
                .isEqualTo("WAITING_FOR_REMARK");
    }

    @Test
    void rejectsGapAgainstAFullScoreDimension() {
        ObjectNode previous = previousDossier("NOT_READY", 0, false);
        assertThatThrownBy(() -> assembler.assemble(command(previous, frames(
                        dialogue(previous, "ASK_SUBSTANTIVE"),
                        dossier(),
                        quality(Map.of(
                                "references", 15,
                                "event_story", 20,
                                "party_positions", 20,
                                "requested_resolution", 15,
                                "risk_and_conflicts", 15,
                                "next_action_clarity", 15),
                                List.of(gap("EVENT_STORY", "请补充事件经过？")))))))
                .isInstanceOf(AssemblyRejectedException.class)
                .hasMessageContaining("full-score dimension");
    }

    @Test
    void rejectsProviderAuthoredQualityGapSourceRole() {
        ObjectNode previous = previousDossier("NOT_READY", 0, false);
        ObjectNode quality = quality(Map.of(
                        "references", 10,
                        "event_story", 20,
                        "party_positions", 20,
                        "requested_resolution", 15,
                        "risk_and_conflicts", 15,
                        "next_action_clarity", 15),
                List.of(gap("REFERENCES", "请补充第三方检测报告？")));
        ((ObjectNode) quality.at("/public_projection_items/6"))
                .put("source_role", "MERCHANT");

        assertThatThrownBy(() -> assembler.assemble(command(previous, frames(
                        dialogue(previous, "ASK_SUBSTANTIVE"),
                        dossier(),
                        quality))))
                .isInstanceOf(AssemblyRejectedException.class)
                .hasMessageContaining("Quality gap item fields differ");
    }

    @Test
    void removesGapCoveredByTheCurrentDossierRowWithoutChangingItsScore() {
        ObjectNode previous = previousDossier("NOT_READY", 0, false);

        var output = assembler.assemble(command(previous, frames(
                dialogue(previous, "ASK_SUBSTANTIVE"),
                dossier(),
                quality(Map.of(
                                "references", 10,
                                "event_story", 20,
                                "party_positions", 20,
                                "requested_resolution", 15,
                                "risk_and_conflicts", 15,
                                "next_action_clarity", 15),
                        List.of(gap(
                                "REFERENCES",
                                "请补充本轮核心事实的来源？",
                                "FACT_01"))))));

        assertThat(output.proposal().readiness())
                .isEqualTo(IntakeTurnProposal.Readiness.READY_TO_CONFIRM);
        assertThat(output.proposal().missingFields()).isEmpty();
        assertThat(output.proposal().dossierPatch()
                        .at("/party_intake_state/USER/intake_quality/score")
                        .asInt())
                .isEqualTo(95);
        assertThat(output.proposal().dossierPatch()
                        .at("/party_intake_state/USER/intake_quality/score_breakdown/references")
                        .asInt())
                .isEqualTo(10);
    }

    @Test
    void rejectsGapBindingThatIsAbsentFromTheCompleteDossierMatrixAuthority() {
        ObjectNode previous = previousDossier("NOT_READY", 0, false);

        assertThatThrownBy(() -> assembler.assemble(command(previous, frames(
                        dialogue(previous, "ASK_SUBSTANTIVE"),
                        dossier(),
                        quality(Map.of(
                                "references", 10,
                                "event_story", 20,
                                "party_positions", 20,
                                "requested_resolution", 15,
                                "risk_and_conflicts", 15,
                                "next_action_clarity", 15),
                                List.of(gap(
                                        "REFERENCES",
                                        "请补充未知事实的来源？",
                                        "FACT_UNKNOWN")))))))
                .isInstanceOf(AssemblyRejectedException.class)
                .hasMessageContaining("outside the Dossier matrix authority");
    }

    @Test
    void rejectsParallelFramesThatExceedTheSmallOutputContract() {
        ObjectNode previous = previousDossier("NOT_READY", 0, false);
        ObjectNode dialogue = dialogue(previous, "ASK_SUBSTANTIVE");
        dialogue.withArray("public_projection_items").add(
                dialogue.at("/public_projection_items/0").deepCopy());
        assertThatThrownBy(() -> assembler.assemble(command(previous, frames(
                        dialogue,
                        dossier(),
                        quality(Map.of(
                                        "references", 15,
                                        "event_story", 20,
                                        "party_positions", 20,
                                        "requested_resolution", 15,
                                        "risk_and_conflicts", 15,
                                        "next_action_clarity", 15),
                                List.of())))))
                .isInstanceOf(AssemblyRejectedException.class)
                .hasMessageContaining("exactly one reply segment");

        ObjectNode dossier = dossier();
        for (int index = 2; index <= 7; index++) {
            ObjectNode repeated = ((ObjectNode) dossier.at("/public_projection_items/0"))
                    .deepCopy();
            repeated.with("source_row")
                    .put("fact_key", "NEW_" + "C".repeat(24) + "_FACT_" + index);
            dossier.withArray("public_projection_items").add(repeated);
        }
        assertThatThrownBy(() -> assembler.assemble(command(previous, frames(
                        dialogue(previous, "ASK_SUBSTANTIVE"),
                        dossier,
                        quality(Map.of(
                                        "references", 15,
                                        "event_story", 20,
                                        "party_positions", 20,
                                        "requested_resolution", 15,
                                        "risk_and_conflicts", 15,
                                        "next_action_clarity", 15),
                                List.of())))))
                .isInstanceOf(AssemblyRejectedException.class)
                .hasMessageContaining("durable prefix length");
    }

    @Test
    void rejectsLegacyV2DialogueAndDossierProviderShapes() {
        ObjectNode previous = previousDossier("NOT_READY", 0, false);
        ObjectNode legacyDialogue = dialogue(previous, "ASK_SUBSTANTIVE");
        legacyDialogue.put("frame_type", "DIALOGUE_FRAME");
        legacyDialogue.put("schema_version", "intake.dialogue-frame.v2");
        ((ObjectNode) legacyDialogue.at("/public_projection_items/0"))
                .put("provider_slot_id", "DSEG_01");
        assertThatThrownBy(() -> assembler.assemble(command(previous, frames(
                        legacyDialogue,
                        dossier(),
                        quality(Map.of(
                                        "references", 15,
                                        "event_story", 20,
                                        "party_positions", 20,
                                        "requested_resolution", 15,
                                        "risk_and_conflicts", 15,
                                        "next_action_clarity", 15),
                                List.of())))))
                .isInstanceOf(AssemblyRejectedException.class)
                .hasMessageContaining("Dialogue Frame root fields differ");

        ObjectNode legacyDossier = dossier();
        legacyDossier.put("frame_type", "DOSSIER_FRAME");
        legacyDossier.put("schema_version", "intake.dossier-frame.v2");
        ((ObjectNode) legacyDossier.at("/public_projection_items/0"))
                .put("projection_path_id", "case_story.one_sentence_summary");
        assertThatThrownBy(() -> assembler.assemble(command(previous, frames(
                        dialogue(previous, "ASK_SUBSTANTIVE"),
                        legacyDossier,
                        quality(Map.of(
                                        "references", 15,
                                        "event_story", 20,
                                        "party_positions", 20,
                                        "requested_resolution", 15,
                                        "risk_and_conflicts", 15,
                                        "next_action_clarity", 15),
                                List.of())))))
                .isInstanceOf(AssemblyRejectedException.class)
                .hasMessageContaining("Dossier Frame root fields differ");
    }

    @Test
    void carriesEveryUnchangedFormalFactBeforeAppendingCurrentNewFacts() {
        ObjectNode previous = previousDossier("NOT_READY", 0, false);
        ObjectNode secondPrior = ((ObjectNode) previous.at("/case_fact_matrix/fact_rows/0"))
                .deepCopy();
        secondPrior.put("fact_id", "FACT_02");
        secondPrior.put("fact_target", "历史物流状态");
        ((ObjectNode) secondPrior.at("/positions/USER"))
                .put("position_summary", "上一轮已记录物流状态")
                .put("asserted_value", "已发货");
        previous.with("case_fact_matrix").withArray("fact_rows").add(secondPrior);

        ObjectNode dossier = dossier();
        ObjectNode newRow = ((ObjectNode) dossier.at("/public_projection_items/0/source_row"))
                .deepCopy();
        newRow.put("fact_key", "NEW_" + "C".repeat(24) + "_CURRENT");
        newRow.put("fact_target", "本轮新增物流事实");
        ObjectNode newItem = dossier.withArray("public_projection_items").addObject();
        newItem.set("source_row", newRow);

        var output = assembler.assemble(command(previous, frames(
                dialogue(previous, "ASK_SUBSTANTIVE"),
                dossier,
                quality(Map.of(
                                "references", 15,
                                "event_story", 20,
                                "party_positions", 20,
                                "requested_resolution", 15,
                                "risk_and_conflicts", 15,
                                "next_action_clarity", 15),
                        List.of()))));

        JsonNode matrix = output.proposal().matrixPatch();
        assertThat(matrix.path("fact_rows").size()).isEqualTo(3);
        assertThat(matrix.at("/fact_rows/0/fact_key").asText()).isEqualTo("FACT_01");
        assertThat(matrix.at("/fact_rows/1/fact_key").asText()).isEqualTo("FACT_02");
        assertThat(matrix.at("/fact_rows/1/source_scope").asText())
                .isEqualTo("PREVIOUS_MATRIX");
        assertThat(matrix.at("/fact_rows/1/stance").asText()).isEqualTo("NOT_ADDRESSED");
        assertThat(matrix.at("/fact_rows/2/fact_key").asText())
                .isEqualTo("NEW_" + "C".repeat(24) + "_CURRENT");
    }

    @Test
    void rejectsForeignFactNamespacesAndInitiatorAuthoredRespondentClaims() {
        ObjectNode previous = previousDossier("NOT_READY", 0, false);
        ObjectNode unknownFact = dossier();
        ((ObjectNode) unknownFact.at("/public_projection_items/0/source_row"))
                .put("fact_key", "FACT_UNKNOWN");
        assertThatThrownBy(() -> assembler.assemble(command(previous, frames(
                        dialogue(previous, "ASK_SUBSTANTIVE"),
                        unknownFact,
                        quality(Map.of(
                                        "references", 15,
                                        "event_story", 20,
                                        "party_positions", 20,
                                        "requested_resolution", 15,
                                        "risk_and_conflicts", 15,
                                        "next_action_clarity", 15),
                                List.of())))))
                .isInstanceOf(AssemblyRejectedException.class)
                .hasMessageContaining("unknown formal FACT_ key");

        ObjectNode foreignNamespace = dossier();
        ((ObjectNode) foreignNamespace.at("/public_projection_items/0/source_row"))
                .put("fact_key", "NEW_" + "D".repeat(24) + "_FOREIGN");
        assertThatThrownBy(() -> assembler.assemble(command(previous, frames(
                        dialogue(previous, "ASK_SUBSTANTIVE"),
                        foreignNamespace,
                        quality(Map.of(
                                        "references", 15,
                                        "event_story", 20,
                                        "party_positions", 20,
                                        "requested_resolution", 15,
                                        "risk_and_conflicts", 15,
                                        "next_action_clarity", 15),
                                List.of())))))
                .isInstanceOf(AssemblyRejectedException.class)
                .hasMessageContaining("outside the issued namespace");

        ObjectNode initiatorClaim = dossier();
        initiatorClaim.with("dossier_delta")
                .putObject("respondent_claim")
                .put("attitude", "DISAGREE")
                .put("position_summary", "不同意该诉求")
                .putNull("alternative_proposal");
        assertThatThrownBy(() -> assembler.assemble(command(previous, frames(
                        dialogue(previous, "ASK_SUBSTANTIVE"),
                        initiatorClaim,
                        quality(Map.of(
                                        "references", 15,
                                        "event_story", 20,
                                        "party_positions", 20,
                                        "requested_resolution", 15,
                                        "risk_and_conflicts", 15,
                                        "next_action_clarity", 15),
                                List.of())))))
                .isInstanceOf(AssemblyRejectedException.class)
                .hasMessageContaining("Java-authorized respondent actor");
    }

    @Test
    void keepsGapBoundOnlyToAnUnaddressedPreviousMatrixRow() {
        ObjectNode previous = previousDossier("NOT_READY", 0, false);
        ObjectNode dossier = dossier();
        dossier.withArray("public_projection_items").removeAll();

        var output = assembler.assemble(command(previous, frames(
                dialogue(previous, "ASK_SUBSTANTIVE"),
                dossier,
                quality(Map.of(
                                "references", 10,
                                "event_story", 20,
                                "party_positions", 20,
                                "requested_resolution", 15,
                                "risk_and_conflicts", 15,
                                "next_action_clarity", 15),
                        List.of(gap(
                                "REFERENCES",
                                "请补充此前事实的来源？",
                                "FACT_01"))))));

        assertThat(output.proposal().readiness())
                .isEqualTo(IntakeTurnProposal.Readiness.INCOMPLETE);
        assertThat(output.proposal().missingFields()).hasSize(1);
        assertThat(output.proposal().dossierPatch()
                        .at("/party_intake_state/USER/missing_information/next_questions/0")
                        .asText())
                .isEqualTo("请补充此前事实的来源？");
    }

    @Test
    void rejectsDossierFrameWritingAnUnregisteredPublicPath() {
        ObjectNode previous = previousDossier("NOT_READY", 0, false);
        ObjectNode dossier = dossier();
        ((ObjectNode) dossier.at("/public_projection_items/0"))
                .put("projection_path_id", "intake_quality.score");
        assertThatThrownBy(() -> assembler.assemble(command(previous, frames(
                        dialogue(previous, "ASK_SUBSTANTIVE"),
                        dossier,
                        quality(Map.of(
                                "references", 15,
                                "event_story", 20,
                                "party_positions", 20,
                                "requested_resolution", 15,
                                "risk_and_conflicts", 15,
                                "next_action_clarity", 15),
                                List.of())))))
                .isInstanceOf(AssemblyRejectedException.class)
                .hasMessageContaining("Dossier projection item fields differ");
    }

    @Test
    void rejectsProviderAuthoredDossierSourceScope() {
        ObjectNode previous = previousDossier("NOT_READY", 0, false);
        ObjectNode dossier = dossier();
        ((ObjectNode) dossier.at("/public_projection_items/0/source_row"))
                .put("source_scope", "CURRENT_SOURCE");
        assertThatThrownBy(() -> assembler.assemble(command(previous, frames(
                        dialogue(previous, "ASK_SUBSTANTIVE"),
                        dossier,
                        quality(Map.of(
                                "references", 15,
                                "event_story", 20,
                                "party_positions", 20,
                                "requested_resolution", 15,
                                "risk_and_conflicts", 15,
                                "next_action_clarity", 15),
                                List.of())))))
                .isInstanceOf(AssemblyRejectedException.class)
                .hasMessageContaining("Dossier fact draft fields differ");
    }

    @Test
    void rejectsRepeatedDossierFactKeys() {
        ObjectNode previous = previousDossier("NOT_READY", 0, false);
        ObjectNode dossier = dossier();
        ObjectNode overlapping = ((ObjectNode) dossier.at("/public_projection_items/0"))
                .deepCopy();
        ((ArrayNode) dossier.path("public_projection_items")).add(overlapping);

        assertThatThrownBy(() -> assembler.assemble(command(previous, frames(
                        dialogue(previous, "ASK_SUBSTANTIVE"),
                        dossier,
                        quality(Map.of(
                                "references", 15,
                                "event_story", 20,
                                "party_positions", 20,
                                "requested_resolution", 15,
                                "risk_and_conflicts", 15,
                                "next_action_clarity", 15),
                                List.of())))))
                .isInstanceOf(AssemblyRejectedException.class)
                .hasMessageContaining("repeat a fact key");
    }

    @Test
    void derivesTheExistingCaseStorySummaryFromCurrentMatrixRowsInOrder() {
        ObjectNode previous = previousDossier("NOT_READY", 0, false);
        ObjectNode dossier = dossier();
        ObjectNode second = ((ObjectNode) dossier.at("/public_projection_items/0/source_row"))
                .deepCopy();
        second.put("fact_key", "NEW_" + "C".repeat(24) + "_SECOND");
        second.put("fact_target", "补充事实");
        second.put("position_summary", "第二项当前事实");
        second.put("asserted_value", "第二项当前事实");
        ObjectNode secondItem = dossier.withArray("public_projection_items").addObject();
        secondItem.set("source_row", second.deepCopy());

        var output = assembler.assemble(command(previous, frames(
                dialogue(previous, "ASK_SUBSTANTIVE"),
                dossier,
                quality(Map.of(
                        "references", 15,
                        "event_story", 20,
                        "party_positions", 20,
                        "requested_resolution", 15,
                        "risk_and_conflicts", 15,
                        "next_action_clarity", 15),
                        List.of()))));

        assertThat(output.proposal().dossierPatch()
                        .at("/case_story/one_sentence_summary").asText())
                .isEqualTo("本轮补充了核心事实；第二项当前事实");
        assertThat(output.proposal().matrixPatch().path("fact_rows").size()).isEqualTo(2);
        assertThat(output.proposal().matrixPatch()
                        .at("/summary_source_fact_keys/1").asText())
                .isEqualTo("NEW_" + "C".repeat(24) + "_SECOND");
    }

    @Test
    void preservesAuthoritativeDossierWhitespaceAcrossAssembly() {
        ObjectNode previous = previousDossier("NOT_READY", 0, false);
        ObjectNode dossier = dossier();
        ObjectNode row = (ObjectNode) dossier.at("/public_projection_items/0/source_row");
        row.put("position_summary", "  本轮补充了核心事实  ");

        var output = assembler.assemble(command(previous, frames(
                dialogue(previous, "ASK_SUBSTANTIVE"),
                dossier,
                quality(Map.of(
                        "references", 15,
                        "event_story", 20,
                        "party_positions", 20,
                        "requested_resolution", 15,
                        "risk_and_conflicts", 15,
                        "next_action_clarity", 15),
                        List.of()))));

        assertThat(output.proposal().dossierPatch()
                        .at("/case_story/one_sentence_summary").asText())
                .isEqualTo("  本轮补充了核心事实  ");
    }

    @Test
    void rejectsRemarkDispositionOutsideTheWaitingPhase() {
        ObjectNode previous = previousDossier("READY_PENDING_REMARK_INVITE", 90, true);
        assertThatThrownBy(() -> assembler.assemble(command(previous, frames(
                        dialogue(previous, "ACK_REMARK"),
                        dossier(),
                        quality(Map.of(
                                "references", 15,
                                "event_story", 20,
                                "party_positions", 20,
                                "requested_resolution", 15,
                                "risk_and_conflicts", 15,
                                "next_action_clarity", 15),
                                List.of())))))
                .isInstanceOf(AssemblyRejectedException.class)
                .hasMessageContaining("remark invitation cannot carry a remark disposition");
    }

    @Test
    void waitingRemarkDispositionSelectsTheExistingAcknowledgementActions() {
        ObjectNode previous = previousDossier("WAITING_FOR_REMARK", 90, true);
        var withRemark = assembler.assemble(command(previous, frames(
                dialogue(previous, "ACK_REMARK"),
                dossier(),
                quality(Map.of(
                        "references", 15,
                        "event_story", 20,
                        "party_positions", 20,
                        "requested_resolution", 15,
                        "risk_and_conflicts", 15,
                        "next_action_clarity", 15),
                        List.of()))));
        var withoutRemark = assembler.assemble(command(previous, frames(
                dialogue(previous, "ACK_NO_REMARK"),
                dossier(),
                quality(Map.of(
                        "references", 15,
                        "event_story", 20,
                        "party_positions", 20,
                        "requested_resolution", 15,
                        "risk_and_conflicts", 15,
                        "next_action_clarity", 15),
                        List.of()))));

        assertThat(withRemark.proposal().conversationAction())
                .isEqualTo(IntakeTurnProposal.ConversationAction.ACK_REMARK);
        assertThat(withRemark.proposal().dossierPatch()
                        .at("/party_intake_state/USER/handoff_notes/remark_status")
                        .asText())
                .isEqualTo("HAS_REMARKS");
        assertThat(withoutRemark.proposal().conversationAction())
                .isEqualTo(IntakeTurnProposal.ConversationAction.ACK_NO_REMARK);
        assertThat(withoutRemark.proposal().dossierPatch()
                        .at("/party_intake_state/USER/handoff_notes/remark_status")
                        .asText())
                .isEqualTo("NO_EXTRA_REMARKS");
    }

    private static AssemblyCommand command(
            ObjectNode previous, Map<FrameType, SealedFrame> frames) {
        return new AssemblyCommand(
                "COMMAND_1",
                "RUN_1",
                "ATTEMPT_1",
                "CASE_1",
                1,
                "grt.v1." + "1".repeat(32),
                "USER",
                SHA_A,
                "SESSION_1",
                2,
                SHA_B,
                SHA_C,
                SOURCE_MESSAGE,
                "本轮补充了核心事实。",
                "BINDING_1",
                1,
                0,
                "d".repeat(64),
                "e".repeat(64),
                "PARALLEL_FRAMES_V1",
                "all-rooms.target-e2e.v2",
                "target-e2e-room-proposal-source.v2",
                new ProfileVersions(
                        "graph.v1",
                        "checkpoint.v1",
                        "prompt.parallel.v1",
                        "qwen3.7-max",
                        "intake-turn-proposal.v2",
                        "policy.v1",
                        "guardrail.v1",
                        "tools.none.v1"),
                previous,
                frames);
    }

    private static Map<FrameType, SealedFrame> frames(
            ObjectNode dialogue, ObjectNode dossier, ObjectNode quality) {
        return orderedFrames(
                List.of(FrameType.DIALOGUE_FRAME, FrameType.DOSSIER_FRAME, FrameType.QUALITY_FRAME),
                dialogue,
                dossier,
                quality);
    }

    private static Map<FrameType, SealedFrame> orderedFrames(
            List<FrameType> order,
            ObjectNode dialogue,
            ObjectNode dossier,
            ObjectNode quality) {
        Map<FrameType, ObjectNode> documents = Map.of(
                FrameType.DIALOGUE_FRAME, dialogue,
                FrameType.DOSSIER_FRAME, dossier,
                FrameType.QUALITY_FRAME, quality);
        Map<FrameType, SealedFrame> result = new LinkedHashMap<>();
        for (FrameType frameType : order) {
            ObjectNode document = documents.get(frameType);
            String canonical = ContractJson.canonicalString(document);
            result.put(frameType, new SealedFrame(
                    frameType,
                    1,
                    "FRAME_" + frameType.name(),
                    canonical,
                    ContractJson.sha256Hex(document),
                    switch (frameType) {
                        case DIALOGUE_FRAME -> SHA_A;
                        case DOSSIER_FRAME -> SHA_B;
                        case QUALITY_FRAME -> SHA_C;
                    },
                    document.path("public_projection_items").size(),
                    100,
                    50));
        }
        return result;
    }

    private static ObjectNode dialogue(ObjectNode previous, String action) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode items = root.putArray("public_projection_items");
        ObjectNode item = items.addObject();
        item.put("segment_kind", "ACKNOWLEDGEMENT");
        item.put("candidate_text", "已记录您本轮补充的信息。");
        ObjectNode dialogue = root.putObject("dialogue");
        if ("ACK_REMARK".equals(action)) {
            dialogue.put("remark_disposition", "REMARK");
        } else if ("ACK_NO_REMARK".equals(action)) {
            dialogue.put("remark_disposition", "NO_REMARK");
        } else {
            dialogue.putNull("remark_disposition");
        }
        return root;
    }

    private static ObjectNode dossier() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode item = root.putArray("public_projection_items").addObject();
        ObjectNode delta = root.putObject("dossier_delta");
        ObjectNode row = MAPPER.createObjectNode();
        row.put("fact_key", "FACT_01");
        row.put("category", "OTHER");
        row.put("fact_target", "本轮核心事实");
        row.put("materiality", "CORE");
        row.put("stance", "CONFIRM");
        row.put("position_summary", "本轮补充了核心事实");
        row.put("asserted_value", "本轮补充了核心事实");
        item.set("source_row", row.deepCopy());
        delta.putNull("respondent_claim");
        return root;
    }

    private static ObjectNode quality(
            Map<String, Integer> scores, List<ObjectNode> gaps) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode items = root.putArray("public_projection_items");
        ObjectNode quality = root.putObject("quality");
        List<Map.Entry<String, String>> dimensions = List.of(
                Map.entry("references", "REFERENCES"),
                Map.entry("event_story", "EVENT_STORY"),
                Map.entry("party_positions", "PARTY_POSITIONS"),
                Map.entry("requested_resolution", "REQUESTED_RESOLUTION"),
                Map.entry("risk_and_conflicts", "RISK_AND_CONFLICTS"),
                Map.entry("next_action_clarity", "NEXT_ACTION_CLARITY"));
        dimensions.forEach(dimension -> {
            String field = dimension.getKey();
            ObjectNode item = items.addObject();
            item.put("projection_kind", "DIMENSION_SCORE");
            item.put("dimension", dimension.getValue());
            item.put("candidate_score", scores.get(field));
        });
        for (int index = 0; index < gaps.size(); index++) {
            ObjectNode gap = gaps.get(index);
            ObjectNode publicGap = items.addObject();
            publicGap.put("projection_kind", "BLOCKING_GAP");
            publicGap.put("dimension", gap.path("dimension").asText());
            publicGap.put("question", gap.path("question").asText());
            publicGap.set("linked_fact_keys", gap.path("linked_fact_keys").deepCopy());
        }
        quality.put("assessment_reasoning", "依据当前消息形成六项评分。");
        return reorderRoot(root, "public_projection_items", "quality");
    }

    private static ObjectNode gap(String dimension, String question, String... factKeys) {
        ObjectNode gap = MAPPER.createObjectNode();
        gap.put("dimension", dimension);
        gap.put("question", question);
        ArrayNode linked = gap.putArray("linked_fact_keys");
        Stream.of(factKeys).forEach(linked::add);
        return gap;
    }

    private static ObjectNode previousDossier(String phase, int score, boolean ready) {
        ObjectNode dossier = MAPPER.createObjectNode();
        dossier.put("schema_version", "intake-dossier.v2");
        ObjectNode state = dossier.putObject("party_intake_state");
        state.put("schema_version", "party-intake-state.v1");
        state.set("USER", actorEntry(phase, score, ready));
        state.set("MERCHANT", actorEntry("NOT_READY", 0, false));
        dossier.set("case_fact_matrix", formalMatrix());
        return dossier;
    }

    private static ObjectNode formalMatrix() {
        ObjectNode matrix = MAPPER.createObjectNode();
        matrix.put("matrix_kind", "INITIATOR_FROZEN");
        matrix.putObject("party_map")
                .put("initiator_role", "USER")
                .put("respondent_role", "MERCHANT");
        ObjectNode row = matrix.putArray("fact_rows").addObject();
        row.put("fact_id", "FACT_01");
        row.put("category", "OTHER");
        row.put("fact_target", "本轮核心事实");
        row.put("materiality", "CORE");
        ObjectNode positions = row.putObject("positions");
        positions.putObject("USER")
                .put("stance", "CONFIRM")
                .put("position_summary", "上一轮已记录核心事实")
                .put("asserted_value", "上一轮核心事实")
                .put("source_type", "DIRECT_PARTY_STATEMENT")
                .putArray("source_refs")
                .add("MESSAGE_PREVIOUS");
        positions.putObject("MERCHANT")
                .put("stance", "NOT_ADDRESSED")
                .put("position_summary", "该方尚未直接陈述。")
                .putNull("asserted_value")
                .put("source_type", "NO_DIRECT_POSITION")
                .putArray("source_refs");
        row.putObject("party_alignment").put("status", "NOT_COMPUTED");
        return matrix;
    }

    private static ObjectNode actorEntry(String phase, int score, boolean ready) {
        ObjectNode entry = MAPPER.createObjectNode();
        ObjectNode quality = entry.putObject("intake_quality");
        quality.put("score", score);
        quality.put("threshold", 85);
        quality.put("ready_for_next_step", ready);
        ObjectNode breakdown = quality.putObject("score_breakdown");
        int remaining = score;
        for (Map.Entry<String, Integer> component : Map.ofEntries(
                        Map.entry("references", 15),
                        Map.entry("event_story", 20),
                        Map.entry("party_positions", 20),
                        Map.entry("requested_resolution", 15),
                        Map.entry("risk_and_conflicts", 15),
                        Map.entry("next_action_clarity", 15))
                .entrySet()) {
            int value = Math.min(remaining, component.getValue());
            breakdown.put(component.getKey(), value);
            remaining -= value;
        }
        quality.put("improvement_reason", ready ? "案情已完整。" : "等待补充案情。");
        ObjectNode missing = entry.putObject("missing_information");
        missing.putArray("blocking_gaps");
        missing.putArray("nice_to_have_gaps");
        missing.putArray("next_questions");
        ObjectNode handoff = entry.putObject("handoff_notes");
        handoff.put("remark_status", phase);
        handoff.put("phase_source_message_id", "NOT_READY".equals(phase) ? "" : "MESSAGE_PREVIOUS");
        handoff.put("latest_remark", "");
        handoff.putArray("remarks");
        handoff.put("instruction", "等待后续阶段。");
        ObjectNode admission = entry.putObject("admission");
        admission.put("recommendation", ready ? "ACCEPTED" : "NEED_MORE_INFO");
        admission.put("reasoning", ready ? "信息充分。" : "信息不足。");
        admission.put("confidence", ready ? new BigDecimal("0.90") : BigDecimal.ZERO);
        return entry;
    }

    private static ObjectNode reorderRoot(ObjectNode source, String... fields) {
        ObjectNode ordered = MAPPER.createObjectNode();
        Stream.of(fields).forEach(field -> ordered.set(field, source.get(field)));
        return ordered;
    }
}
