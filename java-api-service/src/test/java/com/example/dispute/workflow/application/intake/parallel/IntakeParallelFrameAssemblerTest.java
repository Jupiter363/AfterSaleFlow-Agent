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
    void rejectsDossierFrameWritingServerOwnedQualityState() {
        ObjectNode previous = previousDossier("NOT_READY", 0, false);
        ObjectNode dossier = dossier();
        ((ObjectNode) dossier.at("/dossier_delta/dossier_patch"))
                .putObject("intake_quality")
                .put("score", 100);
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
                .hasMessageContaining("server-owned branch");
    }

    @Test
    void rejectsDialogueActionThatDoesNotMatchThePersistedPhase() {
        ObjectNode previous = previousDossier("READY_PENDING_REMARK_INVITE", 90, true);
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
                                List.of())))))
                .isInstanceOf(AssemblyRejectedException.class)
                .hasMessageContaining("action differs from trusted authority");
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
        item.put("schema_version", "intake.dialogue-public-segment-proposal.v1");
        item.put("provider_slot_id", "DSEG_01");
        item.put("segment_kind", "ACKNOWLEDGEMENT");
        item.put("candidate_text", "已记录您本轮补充的信息。");
        root.put("frame_type", "DIALOGUE_FRAME");
        root.put("schema_version", "intake.dialogue-frame.v1");
        ObjectNode dialogue = root.putObject("dialogue");
        ObjectNode binding = dialogue.putObject("action_binding");
        binding.put("action", action);
        binding.put(
                "phase_source_sha256",
                ContractJson.sha256Hex(previous.at("/party_intake_state/USER")));
        dialogue.putArray("public_projection_slots").add("DSEG_01");
        dialogue.put("language", "zh-CN");
        return root;
    }

    private static ObjectNode dossier() {
        ObjectNode root = MAPPER.createObjectNode();
        root.putArray("public_projection_items");
        root.put("frame_type", "DOSSIER_FRAME");
        root.put("schema_version", "intake.dossier-frame.v1");
        ObjectNode delta = root.putObject("dossier_delta");
        ObjectNode patch = delta.putObject("dossier_patch");
        patch.put("schema_version", "intake-dossier.v2");
        patch.putObject("case_story").put("summary", "本轮补充了核心事实");
        delta.putNull("matrix_patch");
        delta.putArray("public_projection_slots");
        return root;
    }

    private static ObjectNode quality(
            Map<String, Integer> scores, List<ObjectNode> gaps) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode items = root.putArray("public_projection_items");
        ObjectNode quality = root.putObject("quality");
        ObjectNode scoreNode = quality.putObject("scores");
        Map<String, String> dimensions = Map.ofEntries(
                Map.entry("references", "REFERENCES"),
                Map.entry("event_story", "EVENT_STORY"),
                Map.entry("party_positions", "PARTY_POSITIONS"),
                Map.entry("requested_resolution", "REQUESTED_RESOLUTION"),
                Map.entry("risk_and_conflicts", "RISK_AND_CONFLICTS"),
                Map.entry("next_action_clarity", "NEXT_ACTION_CLARITY"));
        dimensions.keySet().stream().sorted().forEach(field -> {
            scoreNode.put(field, scores.get(field));
            ObjectNode item = items.addObject();
            item.put("schema_version", "intake.quality-public-metric-proposal.v1");
            item.put("provider_slot_id", "QMETRIC_" + dimensions.get(field));
            item.put("projection_kind", "DIMENSION_SCORE");
            item.put("dimension", dimensions.get(field));
            item.put("candidate_score", scores.get(field));
            item.putArray("linked_fact_keys");
        });
        ArrayNode gapArray = quality.putArray("gap_proposals");
        gaps.forEach(gapArray::add);
        quality.put("assessment_reasoning", "依据当前消息形成六项评分。");
        ArrayNode slots = quality.putArray("public_projection_slots");
        items.forEach(item -> slots.add(item.path("provider_slot_id").asText()));
        root.put("frame_type", "QUALITY_FRAME");
        root.put("schema_version", "intake.quality-frame.v1");
        return reorderRoot(root, "public_projection_items", "frame_type", "schema_version", "quality");
    }

    private static ObjectNode gap(String dimension, String question) {
        ObjectNode gap = MAPPER.createObjectNode();
        gap.put("dimension", dimension);
        gap.put("question", question);
        gap.put("source_role", "USER");
        gap.putArray("linked_fact_keys");
        return gap;
    }

    private static ObjectNode previousDossier(String phase, int score, boolean ready) {
        ObjectNode dossier = MAPPER.createObjectNode();
        dossier.put("schema_version", "intake-dossier.v2");
        ObjectNode state = dossier.putObject("party_intake_state");
        state.put("schema_version", "party-intake-state.v1");
        state.set("USER", actorEntry(phase, score, ready));
        state.set("MERCHANT", actorEntry("NOT_READY", 0, false));
        return dossier;
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
