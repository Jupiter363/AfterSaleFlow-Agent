package com.example.dispute.workflow.application.intake.parallel;

import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal.ConversationAction;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal.KnowledgeAnswerMode;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal.ProfileVersions;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal.Readiness;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal.Recommendation;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameType;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ContractTypes.GraphStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.contract.v1.RoomGraphResult.ArtifactOperation;
import com.example.dispute.workflow.contract.v1.RoomGraphResult.ExecutionMetadata;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministically folds the exact three sealed Intake Frames into the existing proposal. */
public final class IntakeParallelFrameAssembler {

    public static final String PROPOSAL_SCHEMA = "intake-turn-proposal.v2";
    public static final String TARGET_RESULT_SCHEMA = "target-e2e-room-proposal-source.v2";
    public static final String EXECUTION_PROFILE = "PARALLEL_FRAMES_V1";
    private static final int QUALITY_THRESHOLD = 85;
    private static final Pattern IDENTIFIER =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> ACTOR_ROLES = Set.of("USER", "MERCHANT");
    private static final Map<String, Integer> QUALITY_MAXIMA = Map.ofEntries(
            Map.entry("references", 15),
            Map.entry("event_story", 20),
            Map.entry("party_positions", 20),
            Map.entry("requested_resolution", 15),
            Map.entry("risk_and_conflicts", 15),
            Map.entry("next_action_clarity", 15));
    private static final Map<String, String> DIMENSION_FIELDS = Map.ofEntries(
            Map.entry("REFERENCES", "references"),
            Map.entry("EVENT_STORY", "event_story"),
            Map.entry("PARTY_POSITIONS", "party_positions"),
            Map.entry("REQUESTED_RESOLUTION", "requested_resolution"),
            Map.entry("RISK_AND_CONFLICTS", "risk_and_conflicts"),
            Map.entry("NEXT_ACTION_CLARITY", "next_action_clarity"));
    private static final List<String> QUALITY_DIMENSION_ORDER = List.of(
            "REFERENCES",
            "EVENT_STORY",
            "PARTY_POSITIONS",
            "REQUESTED_RESOLUTION",
            "RISK_AND_CONFLICTS",
            "NEXT_ACTION_CLARITY");
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    static {
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public AssemblyOutput assemble(AssemblyCommand command) {
        Objects.requireNonNull(command, "command");
        Map<FrameType, ParsedFrame> frames = parseFrames(command.frames());
        ObjectNode previous = requireObject(command.previousDossier(), "previousDossier").deepCopy();
        ObjectNode actorEntry = previousActorEntry(previous, command.actorRole());
        String previousPhase = actorEntry.path("handoff_notes")
                .path("remark_status")
                .asText("NOT_READY");
        ConversationAction currentAction = currentAction(
                previousPhase,
                frames.get(FrameType.DIALOGUE_FRAME).document().path("dialogue"));
        requireDialogueAuthority(
                frames.get(FrameType.DIALOGUE_FRAME).document(),
                actorEntry,
                currentAction);

        QualityOutcome proposedQuality = quality(
                frames.get(FrameType.QUALITY_FRAME).document(), command.actorRole());
        DossierOutcome dossier = dossier(frames.get(FrameType.DOSSIER_FRAME).document());
        QualityOutcome quality = reconcileQualityGaps(proposedQuality, dossier.matrixPatch());
        StateOutcome state = nextState(
                previousPhase,
                currentAction,
                quality,
                actorEntry,
                command.actorRole(),
                command.sourceMessageId(),
                command.currentMessageText());
        ObjectNode dossierPatch = dossier.dossierPatch().deepCopy();
        dossierPatch.set(
                "party_intake_state",
                partyState(previous, command.actorRole(), state.actorEntry()));
        String roomUtterance = roomUtterance(
                frames.get(FrameType.DIALOGUE_FRAME).document(), currentAction, state.questions());

        IntakeTurnProposal proposal = proposal(
                command,
                roomUtterance,
                currentAction,
                dossierPatch,
                dossier.matrixPatch(),
                state);
        byte[] proposalBytes = canonicalBytes(proposal);
        String inputSetSha256 = inputSetSha256(command, frames, previous);
        String proposalHashPrefix = proposal.proposalHash().substring(0, 32);
        String artifactId = "intake.proposal." + proposalHashPrefix;
        String artifactUri = "urn:target-e2e:proposal:intake:" + proposal.proposalHash();
        String checkpointId = "IPCK_" + inputSetSha256.substring(0, 32);
        RoomGraphResult graphResult = graphResult(
                command,
                proposal,
                artifactId,
                artifactUri,
                checkpointId,
                aggregateUsage(frames));
        return new AssemblyOutput(
                inputSetSha256,
                artifactId,
                artifactUri,
                proposal.proposalHash(),
                proposalBytes,
                proposal,
                graphResult,
                canonicalBytes(graphResult));
    }

    private static Map<FrameType, ParsedFrame> parseFrames(Map<FrameType, SealedFrame> supplied) {
        Objects.requireNonNull(supplied, "frames");
        if (!supplied.keySet().equals(Set.of(FrameType.values()))) {
            throw invalid("assembly requires exactly one sealed result for every Frame type");
        }
        Map<FrameType, ParsedFrame> parsed = new EnumMap<>(FrameType.class);
        for (FrameType frameType : FrameType.values()) {
            SealedFrame frame = Objects.requireNonNull(supplied.get(frameType), frameType.name());
            if (frame.frameType() != frameType) {
                throw invalid("sealed Frame map key does not match its result authority");
            }
            JsonNode document = parseCanonical(frame.canonicalResultJson(), frame.resultSha256());
            requireText(document, "frame_type", frameType.name());
            requireText(document, "schema_version", switch (frameType) {
                case DIALOGUE_FRAME -> "intake.dialogue-frame.v1";
                case DOSSIER_FRAME -> "intake.dossier-frame.v1";
                case QUALITY_FRAME -> "intake.quality-frame.v1";
            });
            ArrayNode publicItems = requireArray(document.path("public_projection_items"),
                    "public_projection_items");
            ArrayNode slots = switch (frameType) {
                case DIALOGUE_FRAME -> requireArray(
                        document.path("dialogue").path("public_projection_slots"),
                        "dialogue.public_projection_slots");
                case DOSSIER_FRAME -> requireArray(
                        document.path("dossier_delta").path("public_projection_slots"),
                        "dossier_delta.public_projection_slots");
                case QUALITY_FRAME -> requireArray(
                        document.path("quality").path("public_projection_slots"),
                        "quality.public_projection_slots");
            };
            requireProjectionSlots(publicItems, slots, frame.nextLocalIndex());
            parsed.put(frameType, new ParsedFrame(frame, document));
        }
        return Map.copyOf(parsed);
    }

    private static void requireProjectionSlots(
            ArrayNode publicItems, ArrayNode slots, long nextLocalIndex) {
        if (publicItems.size() != slots.size() || publicItems.size() != nextLocalIndex) {
            throw invalid("Frame final projection slots do not match the durable prefix length");
        }
        Set<String> observed = new LinkedHashSet<>();
        for (int index = 0; index < publicItems.size(); index++) {
            String itemSlot = identifier(
                    publicItems.get(index).path("provider_slot_id").asText(null),
                    "provider_slot_id");
            String finalSlot = identifier(slots.get(index).asText(null), "public_projection_slot");
            if (!itemSlot.equals(finalSlot) || !observed.add(itemSlot)) {
                throw invalid("Frame projection slots are missing, reordered, or repeated");
            }
        }
    }

    private static void requireDialogueAuthority(
            JsonNode dialogueFrame, ObjectNode previousActorEntry, ConversationAction action) {
        requireExactFields(
                dialogueFrame,
                Set.of("public_projection_items", "frame_type", "schema_version", "dialogue"),
                "Dialogue Frame root");
        JsonNode dialogue = requireObject(dialogueFrame.path("dialogue"), "dialogue");
        requireExactFields(
                dialogue,
                Set.of("action_binding", "public_projection_slots", "language"),
                "dialogue");
        requireText(dialogue, "language", "zh-CN");
        JsonNode binding = requireObject(dialogue.path("action_binding"), "action_binding");
        requireExactFields(binding, Set.of("action", "phase_source_sha256"), "action_binding");
        requireText(binding, "action", action.name());
        String expectedPhaseHash = ContractJson.sha256Hex(previousActorEntry);
        requireText(binding, "phase_source_sha256", expectedPhaseHash);

        ArrayNode items = requireArray(dialogueFrame.path("public_projection_items"),
                "dialogue public_projection_items");
        if (items.isEmpty() || items.size() > 4) {
            throw invalid("Dialogue Frame requires 1..4 bounded public segments");
        }
        for (JsonNode item : items) {
            requireExactFields(
                    item,
                    Set.of("schema_version", "provider_slot_id", "segment_kind", "candidate_text"),
                    "Dialogue projection item");
            requireText(item, "schema_version", "intake.dialogue-public-segment-proposal.v1");
            String kind = item.path("segment_kind").asText("");
            if (!Set.of("ACKNOWLEDGEMENT", "TRANSITION", "REMARK_ACKNOWLEDGEMENT")
                    .contains(kind)) {
                throw invalid("Dialogue segment kind is not allowlisted");
            }
            String text = boundedText(item.path("candidate_text").asText(null), 500,
                    "candidate_text");
            if (text.contains("?") || text.contains("？")) {
                throw invalid("Dialogue segments cannot create questions");
            }
        }
    }

    private static DossierOutcome dossier(JsonNode frame) {
        requireExactFields(
                frame,
                Set.of("public_projection_items", "frame_type", "schema_version", "dossier_delta"),
                "Dossier Frame root");
        JsonNode delta = requireObject(frame.path("dossier_delta"), "dossier_delta");
        requireExactFields(
                delta,
                Set.of("matrix_patch", "public_projection_slots"),
                "dossier_delta");
        JsonNode matrixPatch = delta.get("matrix_patch");
        if (matrixPatch != null && !matrixPatch.isNull() && !matrixPatch.isObject()) {
            throw invalid("matrix_patch must be an object or null");
        }
        ObjectNode dossierPatch = materializeDossierPatch(
                requireArray(
                        frame.path("public_projection_items"),
                        "dossier public_projection_items"),
                matrixPatch);
        return new DossierOutcome(
                dossierPatch,
                matrixPatch == null || matrixPatch.isNull() ? null : matrixPatch.deepCopy());
    }

    private static ObjectNode materializeDossierPatch(ArrayNode items, JsonNode matrixPatch) {
        List<JsonNode> expectedRows = currentSourceFactRows(matrixPatch);
        if (items.size() != expectedRows.size()) {
            throw invalid("Dossier public facts differ from the typed matrix delta");
        }
        ObjectNode patch = JsonNodeFactory.instance.objectNode();
        if (items.isEmpty()) {
            return patch;
        }
        List<String> summaries = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            JsonNode item = items.get(index);
            requireExactFields(
                    item,
                    Set.of(
                            "schema_version",
                            "provider_slot_id",
                            "projection_kind",
                            "projection_path_id",
                            "source_row",
                            "candidate_value"),
                    "Dossier projection item");
            requireText(
                    item,
                    "schema_version",
                    "intake.dossier-public-patch-proposal.v1");
            identifier(item.path("provider_slot_id").asText(null), "provider_slot_id");
            requireText(item, "projection_kind", "CURRENT_FACT");
            requireText(item, "projection_path_id", "case_story.one_sentence_summary");
            JsonNode sourceRow = requireObject(item.path("source_row"), "source_row");
            if (!sourceRow.equals(expectedRows.get(index))) {
                throw invalid("Dossier public fact source row differs from the typed matrix delta");
            }
            String sourceScope = sourceRow.path("source_scope").asText("");
            String stance = sourceRow.path("stance").asText("");
            if (!("CURRENT_SOURCE".equals(sourceScope)
                            || "PREVIOUS_AND_CURRENT_SOURCE".equals(sourceScope))
                    || "NOT_ADDRESSED".equals(stance)) {
                throw invalid("Dossier public fact has no substantive current-source authority");
            }
            String positionSummary = preservedBoundedText(
                    sourceRow.path("position_summary"),
                    20_000,
                    "source_row.position_summary");
            String candidate = preservedBoundedText(
                    item.path("candidate_value"),
                    20_000,
                    "candidate_value");
            if (!candidate.equals(positionSummary)) {
                throw invalid("Dossier public fact differs from its typed source row");
            }
            summaries.add(candidate);
        }
        String summary = preservedBoundedText(
                String.join("；", summaries),
                20_000,
                "matrix summary");
        patch.putObject("case_story").put("one_sentence_summary", summary);
        return patch;
    }

    private static List<JsonNode> currentSourceFactRows(JsonNode matrixPatch) {
        if (matrixPatch == null || matrixPatch.isNull()) {
            return List.of();
        }
        requireText(matrixPatch, "schema_version", "case_fact_matrix.delta.v2");
        ArrayNode rows = requireArray(matrixPatch.path("fact_rows"), "matrix_patch.fact_rows");
        List<JsonNode> selected = new ArrayList<>();
        for (JsonNode row : rows) {
            if (isSubstantiveCurrentSourceRow(row)) {
                selected.add(row);
            }
        }
        return List.copyOf(selected);
    }

    private static QualityOutcome quality(JsonNode frame, String actorRole) {
        requireExactFields(
                frame,
                Set.of("public_projection_items", "frame_type", "schema_version", "quality"),
                "Quality Frame root");
        JsonNode quality = requireObject(frame.path("quality"), "quality");
        requireExactFields(
                quality,
                Set.of(
                        "scores",
                        "gap_proposals",
                        "assessment_reasoning",
                        "public_projection_slots"),
                "quality");
        JsonNode scores = requireObject(quality.path("scores"), "quality.scores");
        requireExactFields(scores, QUALITY_MAXIMA.keySet(), "quality.scores");
        Map<String, Integer> normalizedScores = new LinkedHashMap<>();
        int total = 0;
        for (String field : QUALITY_MAXIMA.keySet().stream().sorted().toList()) {
            JsonNode value = scores.path(field);
            if (!value.isIntegralNumber()
                    || !value.canConvertToInt()
                    || value.intValue() < 0
                    || value.intValue() > QUALITY_MAXIMA.get(field)) {
                throw invalid("quality score is outside the bounded dimension range");
            }
            normalizedScores.put(field, value.intValue());
            total += value.intValue();
        }
        String reasoning = boundedText(
                quality.path("assessment_reasoning").asText(null),
                2_000,
                "assessment_reasoning");
        ArrayNode gaps = requireArray(quality.path("gap_proposals"), "gap_proposals");
        if (gaps.size() > 6) {
            throw invalid("Quality Frame may propose at most one gap per dimension");
        }
        List<Gap> normalizedGaps = new ArrayList<>();
        Set<String> observedDimensions = new HashSet<>();
        for (JsonNode gap : gaps) {
            requireExactFields(
                    gap,
                    Set.of("dimension", "question", "source_role", "linked_fact_keys"),
                    "gap proposal");
            String dimension = gap.path("dimension").asText("");
            String scoreField = DIMENSION_FIELDS.get(dimension);
            if (scoreField == null || !observedDimensions.add(dimension)) {
                throw invalid("gap dimension is unknown or repeated");
            }
            if (normalizedScores.get(scoreField).equals(QUALITY_MAXIMA.get(scoreField))) {
                throw invalid("a full-score dimension cannot remain blocking");
            }
            requireText(gap, "source_role", actorRole);
            String question = boundedText(gap.path("question").asText(null), 1_000, "gap question");
            if (!question.endsWith("？")) {
                throw invalid("gap question must be one concrete Chinese question");
            }
            ArrayNode linked = requireArray(gap.path("linked_fact_keys"), "linked_fact_keys");
            if (linked.size() > 16) {
                throw invalid("gap fact binding exceeds the bounded limit");
            }
            List<String> factKeys = new ArrayList<>();
            Set<String> unique = new HashSet<>();
            for (JsonNode value : linked) {
                String factKey = identifier(value.asText(null), "linked_fact_key");
                if (!unique.add(factKey)) {
                    throw invalid("gap repeats a linked fact key");
                }
                factKeys.add(factKey);
            }
            String gapId = "GAP_" + ContractJson.sha256Hex(MAPPER.valueToTree(Map.of(
                            "dimension", dimension,
                            "question", question,
                            "source_role", actorRole,
                            "linked_fact_keys", factKeys)))
                    .substring(0, 24);
            normalizedGaps.add(new Gap(gapId, dimension, question, List.copyOf(factKeys)));
        }
        requireQualityProjectionItems(
                requireArray(frame.path("public_projection_items"), "quality public items"),
                normalizedScores,
                normalizedGaps,
                actorRole);
        return new QualityOutcome(Map.copyOf(normalizedScores), total, List.copyOf(normalizedGaps), reasoning);
    }

    private static void requireQualityProjectionItems(
            ArrayNode items,
            Map<String, Integer> scores,
            List<Gap> gaps,
            String actorRole) {
        if (items.size() != QUALITY_DIMENSION_ORDER.size() + gaps.size()) {
            throw invalid("Quality public trace must contain six scores and every sealed gap");
        }
        for (int index = 0; index < QUALITY_DIMENSION_ORDER.size(); index++) {
            JsonNode item = items.get(index);
            requireExactFields(
                    item,
                    Set.of(
                            "schema_version",
                            "provider_slot_id",
                            "projection_kind",
                            "dimension",
                            "candidate_score",
                            "linked_fact_keys"),
                    "Quality projection item");
            requireText(item, "schema_version", "intake.quality-public-metric-proposal.v1");
            requireText(item, "projection_kind", "DIMENSION_SCORE");
            String dimension = item.path("dimension").asText("");
            String expectedDimension = QUALITY_DIMENSION_ORDER.get(index);
            String scoreField = DIMENSION_FIELDS.get(dimension);
            if (!expectedDimension.equals(dimension) || scoreField == null) {
                throw invalid("Quality public scores differ from the fixed dimension order");
            }
            if (!item.path("candidate_score").isIntegralNumber()
                    || item.path("candidate_score").intValue() != scores.get(scoreField)) {
                throw invalid("Quality public trace differs from the sealed score map");
            }
            requireArray(item.path("linked_fact_keys"), "quality linked_fact_keys");
        }
        for (int gapIndex = 0; gapIndex < gaps.size(); gapIndex++) {
            JsonNode item = items.get(QUALITY_DIMENSION_ORDER.size() + gapIndex);
            Gap gap = gaps.get(gapIndex);
            requireExactFields(
                    item,
                    Set.of(
                            "schema_version",
                            "provider_slot_id",
                            "projection_kind",
                            "dimension",
                            "question",
                            "source_role",
                            "linked_fact_keys"),
                    "Quality gap projection item");
            requireText(item, "schema_version", "intake.quality-public-gap-proposal.v1");
            requireText(item, "projection_kind", "BLOCKING_GAP");
            requireText(item, "dimension", gap.dimension());
            requireText(item, "question", gap.question());
            requireText(item, "source_role", actorRole);
            ArrayNode factKeys = requireArray(
                    item.path("linked_fact_keys"), "quality gap linked_fact_keys");
            if (factKeys.size() != gap.factKeys().size()) {
                throw invalid("Quality public gap fact binding differs from sealed authority");
            }
            for (int factIndex = 0; factIndex < factKeys.size(); factIndex++) {
                if (!gap.factKeys().get(factIndex).equals(factKeys.get(factIndex).asText(null))) {
                    throw invalid("Quality public gap fact binding differs from sealed authority");
                }
            }
        }
    }

    private static QualityOutcome reconcileQualityGaps(
            QualityOutcome quality, JsonNode matrixPatch) {
        if (quality.gaps().isEmpty()) {
            return quality;
        }
        Map<String, JsonNode> matrixRows = matrixRowsByFactKey(matrixPatch);
        List<Gap> unresolved = new ArrayList<>();
        for (Gap gap : quality.gaps()) {
            if (gap.factKeys().isEmpty()) {
                unresolved.add(gap);
                continue;
            }
            boolean everyBindingCoveredByCurrentSource = true;
            for (String factKey : gap.factKeys()) {
                JsonNode row = matrixRows.get(factKey);
                if (row == null) {
                    throw invalid("Quality gap references a fact outside the Dossier matrix authority");
                }
                if (!isSubstantiveCurrentSourceRow(row)) {
                    everyBindingCoveredByCurrentSource = false;
                }
            }
            if (!everyBindingCoveredByCurrentSource) {
                unresolved.add(gap);
            }
        }
        return new QualityOutcome(
                quality.scores(),
                quality.total(),
                List.copyOf(unresolved),
                quality.reasoning());
    }

    private static Map<String, JsonNode> matrixRowsByFactKey(JsonNode matrixPatch) {
        if (matrixPatch == null || matrixPatch.isNull()) {
            return Map.of();
        }
        requireText(matrixPatch, "schema_version", "case_fact_matrix.delta.v2");
        ArrayNode rows = requireArray(matrixPatch.path("fact_rows"), "matrix_patch.fact_rows");
        Map<String, JsonNode> rowsByFactKey = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String factKey = identifier(row.path("fact_key").asText(null), "matrix fact_key");
            if (rowsByFactKey.putIfAbsent(factKey, row) != null) {
                throw invalid("Dossier matrix repeats a fact key");
            }
        }
        return Map.copyOf(rowsByFactKey);
    }

    private static boolean isSubstantiveCurrentSourceRow(JsonNode row) {
        String sourceScope = row.path("source_scope").asText("");
        String stance = row.path("stance").asText("");
        return ("CURRENT_SOURCE".equals(sourceScope)
                        || "PREVIOUS_AND_CURRENT_SOURCE".equals(sourceScope))
                && !"NOT_ADDRESSED".equals(stance);
    }

    private static StateOutcome nextState(
            String previousPhase,
            ConversationAction currentAction,
            QualityOutcome quality,
            ObjectNode previousActorEntry,
            String actorRole,
            String sourceMessageId,
            String currentMessageText) {
        if (!"NOT_READY".equals(previousPhase)) {
            ObjectNode carried = previousActorEntry.deepCopy();
            ObjectNode handoff = requireObject(carried.path("handoff_notes"), "handoff_notes");
            String nextPhase = switch (previousPhase) {
                case "READY_PENDING_REMARK_INVITE" -> "WAITING_FOR_REMARK";
                case "WAITING_FOR_REMARK" -> currentAction == ConversationAction.ACK_REMARK
                        ? "HAS_REMARKS"
                        : "NO_EXTRA_REMARKS";
                default -> throw invalid("terminal Intake remark phase cannot accept another ROOM_MESSAGE");
            };
            handoff.put("remark_status", nextPhase);
            handoff.put("phase_source_message_id", sourceMessageId);
            handoff.put("instruction", instruction(nextPhase));
            if ("HAS_REMARKS".equals(nextPhase)) {
                String remark = boundedText(currentMessageText, 8_192, "currentMessageText");
                handoff.put("latest_remark", remark);
                ArrayNode remarks = handoff.withArray("remarks");
                ObjectNode entry = remarks.addObject();
                entry.put("role", actorRole);
                entry.put("text", remark);
                entry.put("source_message_id", sourceMessageId);
                entry.put("turn_source", "ROOM_MESSAGE");
            } else {
                handoff.put(
                        "latest_remark",
                        "NO_EXTRA_REMARKS".equals(nextPhase) ? "无额外备注。" : "");
                handoff.remove("remarks");
                handoff.putArray("remarks");
            }
            JsonNode missing = carried.path("missing_information");
            List<String> questions = textList(missing.path("next_questions"));
            List<String> ids = textList(missing.path("blocking_gaps"));
            return new StateOutcome(carried, true, ids, questions,
                    Recommendation.ACCEPTED, confidence(carried));
        }

        boolean ready = quality.total() >= QUALITY_THRESHOLD && quality.gaps().isEmpty();
        String nextPhase = ready ? "READY_PENDING_REMARK_INVITE" : "NOT_READY";
        ObjectNode actorEntry = previousActorEntry.deepCopy();
        ObjectNode qualityNode = actorEntry.putObject("intake_quality");
        qualityNode.put("score", quality.total());
        qualityNode.put("threshold", QUALITY_THRESHOLD);
        qualityNode.put("ready_for_next_step", ready);
        ObjectNode breakdown = qualityNode.putObject("score_breakdown");
        quality.scores().forEach(breakdown::put);
        qualityNode.put("improvement_reason", quality.reasoning());
        ObjectNode missing = actorEntry.putObject("missing_information");
        ArrayNode blocking = missing.putArray("blocking_gaps");
        ArrayNode questions = missing.putArray("next_questions");
        List<String> gapIds = new ArrayList<>();
        List<String> questionTexts = new ArrayList<>();
        for (Gap gap : quality.gaps()) {
            blocking.add(gap.question());
            questions.add(gap.question());
            gapIds.add(gap.gapId());
            questionTexts.add(gap.question());
        }
        missing.putArray("nice_to_have_gaps");
        ObjectNode previousHandoff = previousActorEntry.path("handoff_notes").isObject()
                ? (ObjectNode) previousActorEntry.path("handoff_notes")
                : defaultActorEntry().with("handoff_notes");
        ObjectNode handoff = actorEntry.putObject("handoff_notes");
        handoff.put("remark_status", nextPhase);
        String previousSource = previousHandoff.path("phase_source_message_id").asText("");
        handoff.put("phase_source_message_id", ready ? sourceMessageId : previousSource);
        handoff.put("latest_remark", "");
        handoff.putArray("remarks");
        handoff.put("instruction", instruction(nextPhase));
        Recommendation recommendation = ready ? Recommendation.ACCEPTED : Recommendation.NEED_MORE_INFO;
        ObjectNode admission = actorEntry.putObject("admission");
        admission.put("recommendation", recommendation.name());
        admission.put("reasoning", quality.reasoning());
        BigDecimal confidence = BigDecimal.valueOf(quality.total())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        admission.put("confidence", confidence);
        return new StateOutcome(
                actorEntry,
                ready,
                List.copyOf(gapIds),
                List.copyOf(questionTexts),
                recommendation,
                confidence);
    }

    private static ConversationAction currentAction(String phase, JsonNode dialogue) {
        String proposed = dialogue.path("action_binding").path("action").asText("");
        return switch (phase) {
            case "NOT_READY" -> ConversationAction.ASK_SUBSTANTIVE;
            case "READY_PENDING_REMARK_INVITE" -> ConversationAction.INVITE_OPTIONAL_REMARK;
            case "WAITING_FOR_REMARK" -> {
                if (!"ACK_REMARK".equals(proposed) && !"ACK_NO_REMARK".equals(proposed)) {
                    throw invalid("WAITING_FOR_REMARK requires one bounded acknowledgement action");
                }
                yield ConversationAction.valueOf(proposed);
            }
            default -> throw invalid("persisted Intake phase cannot produce a ROOM_MESSAGE action");
        };
    }

    private static String roomUtterance(
            JsonNode dialogueFrame, ConversationAction action, List<String> questions) {
        List<String> parts = new ArrayList<>();
        for (JsonNode item : dialogueFrame.path("public_projection_items")) {
            parts.add(item.path("candidate_text").asText());
        }
        if (action == ConversationAction.ASK_SUBSTANTIVE) {
            parts.addAll(questions);
        } else if (action == ConversationAction.INVITE_OPTIONAL_REMARK) {
            parts.add("案情信息已达到交接条件，请问您是否还有补充备注？");
        } else if (action == ConversationAction.ACK_NO_REMARK) {
            parts.add("已记录您没有额外备注，案情将进入下一阶段。");
        } else if (action == ConversationAction.ACK_REMARK) {
            parts.add("已记录您的补充备注，案情将进入下一阶段。");
        }
        return boundedText(String.join(" ", parts), 20_000, "roomUtterance");
    }

    private static ObjectNode partyState(
            ObjectNode previous, String actorRole, ObjectNode actorEntry) {
        ObjectNode state = previous.path("party_intake_state").isObject()
                ? ((ObjectNode) previous.path("party_intake_state")).deepCopy()
                : JsonNodeFactory.instance.objectNode();
        state.put("schema_version", "party-intake-state.v1");
        String otherRole = "USER".equals(actorRole) ? "MERCHANT" : "USER";
        if (!state.path(otherRole).isObject()) {
            state.set(otherRole, defaultActorEntry());
        }
        state.set(actorRole, actorEntry.deepCopy());
        return state;
    }

    private static ObjectNode previousActorEntry(ObjectNode previous, String actorRole) {
        JsonNode entry = previous.path("party_intake_state").path(actorRole);
        return entry.isObject() ? ((ObjectNode) entry).deepCopy() : defaultActorEntry();
    }

    private static ObjectNode defaultActorEntry() {
        ObjectNode entry = JsonNodeFactory.instance.objectNode();
        ObjectNode quality = entry.putObject("intake_quality");
        quality.put("score", 0);
        quality.put("threshold", QUALITY_THRESHOLD);
        quality.put("ready_for_next_step", false);
        ObjectNode breakdown = quality.putObject("score_breakdown");
        QUALITY_MAXIMA.keySet().stream().sorted().forEach(field -> breakdown.put(field, 0));
        quality.put("improvement_reason", "等待当前参与方补充案情。");
        ObjectNode missing = entry.putObject("missing_information");
        missing.putArray("blocking_gaps");
        missing.putArray("nice_to_have_gaps");
        missing.putArray("next_questions");
        ObjectNode handoff = entry.putObject("handoff_notes");
        handoff.put("remark_status", "NOT_READY");
        handoff.put("phase_source_message_id", "");
        handoff.put("latest_remark", "");
        handoff.putArray("remarks");
        handoff.put("instruction", instruction("NOT_READY"));
        ObjectNode admission = entry.putObject("admission");
        admission.put("recommendation", "NEED_MORE_INFO");
        admission.put("reasoning", "");
        admission.put("confidence", BigDecimal.ZERO);
        return entry;
    }

    private static String instruction(String phase) {
        return switch (phase) {
            case "NOT_READY" -> "请继续补充当前缺失的核心案情。";
            case "READY_PENDING_REMARK_INVITE" -> "下一轮由接待官邀请补充交接备注。";
            case "WAITING_FOR_REMARK" -> "请确认是否还有补充备注。";
            case "HAS_REMARKS", "NO_EXTRA_REMARKS" -> "案情接待已完成，可进入下一阶段。";
            default -> throw invalid("unknown Intake phase");
        };
    }

    private static BigDecimal confidence(ObjectNode actorEntry) {
        JsonNode value = actorEntry.path("admission").path("confidence");
        return value.isNumber() ? value.decimalValue() : BigDecimal.ZERO;
    }

    private static IntakeTurnProposal proposal(
            AssemblyCommand command,
            String roomUtterance,
            ConversationAction action,
            ObjectNode dossierPatch,
            JsonNode matrixPatch,
            StateOutcome state) {
        ObjectNode unsigned = MAPPER.createObjectNode();
        unsigned.put("schema_version", PROPOSAL_SCHEMA);
        unsigned.put("command_id", command.commandId());
        unsigned.put("logical_run_id", command.logicalRunId());
        unsigned.put("attempt_id", command.attemptId());
        unsigned.put("case_id", command.caseId());
        unsigned.put("room_epoch", command.roomEpoch());
        unsigned.put("thread_id", command.threadId());
        unsigned.put("actor_scope_hash", command.actorScopeHash());
        unsigned.put("agent_session_id", command.agentSessionId());
        unsigned.put("cognitive_revision", command.cognitiveRevision());
        unsigned.put("source_snapshot_hash", command.sourceSnapshotHash());
        if (command.sourceEventHash() != null) {
            unsigned.put("source_event_hash", command.sourceEventHash());
        }
        unsigned.put("room_utterance", roomUtterance);
        unsigned.put("conversation_action", action.name());
        unsigned.set("dossier_patch", dossierPatch);
        if (matrixPatch != null) {
            unsigned.set("matrix_patch", matrixPatch);
        }
        unsigned.put("readiness", state.ready() ? "READY_TO_CONFIRM" : "INCOMPLETE");
        ArrayNode missing = unsigned.putArray("missing_fields");
        state.missingFieldIds().forEach(missing::add);
        unsigned.put("recommendation", state.recommendation().name());
        unsigned.put("knowledge_answer_mode", "NONE");
        unsigned.put("confidence", state.confidence());
        unsigned.set("profile_versions", MAPPER.valueToTree(command.profileVersions()));
        unsigned.put("proposal_hash", "0".repeat(64));
        String proposalHash = IntakeContractHashes.canonicalHashExcluding(unsigned, "proposal_hash");
        unsigned.put("proposal_hash", proposalHash);
        try {
            return MAPPER.treeToValue(unsigned, IntakeTurnProposal.class);
        } catch (JsonProcessingException failure) {
            throw new AssemblyRejectedException(
                    "INTAKE_PARALLEL_PROPOSAL_INVALID",
                    "assembled Intake proposal cannot be decoded",
                    failure);
        }
    }

    private static RoomGraphResult graphResult(
            AssemblyCommand command,
            IntakeTurnProposal proposal,
            String artifactId,
            String artifactUri,
            String checkpointId,
            Usage usage) {
        ArtifactPointer pointer = new ArtifactPointer(
                artifactId, PROPOSAL_SCHEMA, artifactUri, proposal.proposalHash());
        ExecutionMetadata metadata = new ExecutionMetadata(
                command.profileVersions().promptVersion(),
                command.profileVersions().modelProfileId(),
                command.executionOutputSchemaVersion(),
                command.profileVersions().policyVersion(),
                command.profileVersions().guardrailVersion());
        RoomGraphResult unsigned = new RoomGraphResult(
                "room-graph-result.v1",
                command.commandId(),
                command.logicalRunId(),
                command.attemptId(),
                command.graphKey(),
                command.profileVersions().graphVersion(),
                checkpointId,
                command.cognitiveRevision(),
                GraphStatus.COMPLETED,
                List.of(),
                List.of(new ArtifactOperation(ArtifactOperationType.PROPOSE_PATCH, pointer)),
                null,
                null,
                null,
                "0".repeat(64),
                usage,
                metadata);
        String outputHash = IntakeContractHashes.graphResultHash(unsigned);
        return new RoomGraphResult(
                unsigned.schemaVersion(),
                unsigned.commandId(),
                unsigned.logicalRunId(),
                unsigned.attemptId(),
                unsigned.graphKey(),
                unsigned.graphVersion(),
                unsigned.checkpointId(),
                unsigned.cognitiveRevision(),
                unsigned.status(),
                unsigned.publicEventProposals(),
                unsigned.artifactOperations(),
                unsigned.needsInput(),
                unsigned.needsReview(),
                unsigned.error(),
                outputHash,
                unsigned.usage(),
                unsigned.executionMetadata());
    }

    private static Usage aggregateUsage(Map<FrameType, ParsedFrame> frames) {
        long input = 0;
        long output = 0;
        for (ParsedFrame parsed : frames.values()) {
            input = Math.addExact(input, parsed.frame().inputTokens());
            output = Math.addExact(output, parsed.frame().outputTokens());
        }
        return new Usage(input, output, Math.addExact(input, output));
    }

    private static String inputSetSha256(
            AssemblyCommand command,
            Map<FrameType, ParsedFrame> frames,
            ObjectNode previousDossier) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("schema_version", "intake.parallel-assembly-input.v1");
        input.put("command_id", command.commandId());
        input.put("logical_run_id", command.logicalRunId());
        input.put("attempt_id", command.attemptId());
        input.put("case_id", command.caseId());
        input.put("thread_id", command.threadId());
        input.put("event_binding_id", command.eventBindingId());
        input.put("binding_generation", command.bindingGeneration());
        input.put("authority_version", command.authorityVersion());
        input.put("context_envelope_sha256", command.contextEnvelopeSha256());
        input.put("model_context_view_sha256", command.modelContextViewSha256());
        input.put("execution_profile_id", command.executionProfileId());
        input.put("previous_dossier_sha256", ContractJson.sha256Hex(previousDossier));
        ArrayNode frameSet = input.putArray("frames");
        for (FrameType type : FrameType.values()) {
            SealedFrame frame = frames.get(type).frame();
            ObjectNode item = frameSet.addObject();
            item.put("frame_type", type.name());
            item.put("generation", frame.generation());
            item.put("frame_id", frame.frameId());
            item.put("result_sha256", frame.resultSha256());
            item.put("public_projection_sha256", frame.publicProjectionSha256());
        }
        return ContractJson.sha256Hex(input);
    }

    private static JsonNode parseCanonical(String canonicalJson, String expectedSha256) {
        try {
            JsonNode document = MAPPER.readTree(canonicalJson);
            if (document == null || !document.isObject()) {
                throw invalid("sealed Frame result must be one JSON object");
            }
            if (!ContractJson.canonicalString(document).equals(canonicalJson)
                    || !ContractJson.sha256Hex(document).equals(expectedSha256)) {
                throw invalid("sealed Frame result bytes or hash are not canonical");
            }
            return document;
        } catch (JsonProcessingException failure) {
            throw new AssemblyRejectedException(
                    "INTAKE_PARALLEL_FRAME_RESULT_INVALID",
                    "sealed Frame result is not valid JSON",
                    failure);
        }
    }

    private static byte[] canonicalBytes(Object value) {
        return ContractJson.canonicalize(MAPPER.valueToTree(value));
    }

    private static void requireExactFields(JsonNode value, Set<String> expected, String field) {
        requireObject(value, field);
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw invalid(field + " fields differ from the frozen contract");
        }
    }

    private static ObjectNode requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw invalid(field + " must be an object");
        }
        return (ObjectNode) value;
    }

    private static ArrayNode requireArray(JsonNode value, String field) {
        if (value == null || !value.isArray()) {
            throw invalid(field + " must be an array");
        }
        return (ArrayNode) value;
    }

    private static void requireText(JsonNode object, String field, String expected) {
        if (!expected.equals(object.path(field).asText(null))) {
            throw invalid(field + " differs from trusted authority");
        }
    }

    private static String boundedText(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw invalid(field + " must contain 1.." + maximum + " characters");
        }
        return value.strip();
    }

    private static String preservedBoundedText(JsonNode value, int maximum, String field) {
        if (value == null || !value.isTextual()) {
            throw invalid(field + " must be a text value");
        }
        return preservedBoundedText(value.textValue(), maximum, field);
    }

    private static String preservedBoundedText(String value, int maximum, String field) {
        if (value == null
                || value.isBlank()
                || value.codePointCount(0, value.length()) > maximum) {
            throw invalid(field + " must contain 1.." + maximum + " Unicode characters");
        }
        return value;
    }

    private static String identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw invalid(field + " must be a bounded identifier");
        }
        return value;
    }

    private static String sha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
        return value;
    }

    private static List<String> textList(JsonNode value) {
        if (!value.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (item.isTextual() && !item.textValue().isBlank()) {
                result.add(item.textValue());
            }
        });
        return List.copyOf(result);
    }

    private static AssemblyRejectedException invalid(String message) {
        return new AssemblyRejectedException("INTAKE_PARALLEL_ASSEMBLY_INVALID", message);
    }

    public record AssemblyCommand(
            String commandId,
            String logicalRunId,
            String attemptId,
            String caseId,
            long roomEpoch,
            String threadId,
            String actorRole,
            String actorScopeHash,
            String agentSessionId,
            long cognitiveRevision,
            String sourceSnapshotHash,
            String sourceEventHash,
            String sourceMessageId,
            String currentMessageText,
            String eventBindingId,
            long bindingGeneration,
            long authorityVersion,
            String contextEnvelopeSha256,
            String modelContextViewSha256,
            String executionProfileId,
            String graphKey,
            String executionOutputSchemaVersion,
            ProfileVersions profileVersions,
            JsonNode previousDossier,
            Map<FrameType, SealedFrame> frames) {

        public AssemblyCommand {
            commandId = identifier(commandId, "commandId");
            logicalRunId = identifier(logicalRunId, "logicalRunId");
            attemptId = identifier(attemptId, "attemptId");
            caseId = identifier(caseId, "caseId");
            if (roomEpoch < 0 || cognitiveRevision < 1 || bindingGeneration < 1
                    || authorityVersion < 0) {
                throw new IllegalArgumentException("assembly authority numbers are invalid");
            }
            if (threadId == null || !threadId.matches("^grt\\.v1\\.[0-9a-f]{32}$")) {
                throw new IllegalArgumentException("threadId is invalid");
            }
            if (!ACTOR_ROLES.contains(actorRole)) {
                throw new IllegalArgumentException("actorRole must be USER or MERCHANT");
            }
            actorScopeHash = sha256(actorScopeHash, "actorScopeHash");
            agentSessionId = identifier(agentSessionId, "agentSessionId");
            sourceSnapshotHash = sha256(sourceSnapshotHash, "sourceSnapshotHash");
            sourceEventHash = sha256(sourceEventHash, "sourceEventHash");
            sourceMessageId = identifier(sourceMessageId, "sourceMessageId");
            currentMessageText = boundedText(currentMessageText, 8_192, "currentMessageText");
            eventBindingId = identifier(eventBindingId, "eventBindingId");
            contextEnvelopeSha256 = sha256(contextEnvelopeSha256, "contextEnvelopeSha256");
            modelContextViewSha256 = sha256(modelContextViewSha256, "modelContextViewSha256");
            if (!EXECUTION_PROFILE.equals(executionProfileId)) {
                throw new IllegalArgumentException("parallel assembly requires PARALLEL_FRAMES_V1");
            }
            graphKey = identifier(graphKey, "graphKey");
            executionOutputSchemaVersion = identifier(
                    executionOutputSchemaVersion, "executionOutputSchemaVersion");
            if (!TARGET_RESULT_SCHEMA.equals(executionOutputSchemaVersion)) {
                throw new IllegalArgumentException("parallel assembly requires target result schema");
            }
            profileVersions = Objects.requireNonNull(profileVersions, "profileVersions");
            previousDossier = requireObject(previousDossier, "previousDossier").deepCopy();
            frames = Map.copyOf(Objects.requireNonNull(frames, "frames"));
        }

        @Override
        public JsonNode previousDossier() {
            return previousDossier.deepCopy();
        }
    }

    public record SealedFrame(
            FrameType frameType,
            long generation,
            String frameId,
            String canonicalResultJson,
            String resultSha256,
            String publicProjectionSha256,
            long nextLocalIndex,
            long inputTokens,
            long outputTokens) {

        public SealedFrame {
            frameType = Objects.requireNonNull(frameType, "frameType");
            if (generation < 1 || nextLocalIndex < 0 || inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("sealed Frame counters are invalid");
            }
            frameId = identifier(frameId, "frameId");
            if (canonicalResultJson == null || canonicalResultJson.isBlank()) {
                throw new IllegalArgumentException("canonicalResultJson is required");
            }
            resultSha256 = sha256(resultSha256, "resultSha256");
            publicProjectionSha256 = sha256(
                    publicProjectionSha256, "publicProjectionSha256");
        }
    }

    public record AssemblyOutput(
            String inputSetSha256,
            String artifactId,
            String artifactUri,
            String proposalSha256,
            byte[] canonicalProposalBytes,
            IntakeTurnProposal proposal,
            RoomGraphResult graphResult,
            byte[] canonicalGraphResultBytes) {

        public AssemblyOutput {
            inputSetSha256 = sha256(inputSetSha256, "inputSetSha256");
            artifactId = identifier(artifactId, "artifactId");
            if (artifactUri == null
                    || !artifactUri.equals("urn:target-e2e:proposal:intake:" + proposalSha256)) {
                throw new IllegalArgumentException("artifactUri is not the canonical Intake proposal URN");
            }
            proposalSha256 = sha256(proposalSha256, "proposalSha256");
            canonicalProposalBytes = canonicalProposalBytes.clone();
            proposal = Objects.requireNonNull(proposal, "proposal");
            graphResult = Objects.requireNonNull(graphResult, "graphResult");
            canonicalGraphResultBytes = canonicalGraphResultBytes.clone();
        }

        @Override
        public byte[] canonicalProposalBytes() {
            return canonicalProposalBytes.clone();
        }

        @Override
        public byte[] canonicalGraphResultBytes() {
            return canonicalGraphResultBytes.clone();
        }
    }

    public static final class AssemblyRejectedException extends IllegalStateException {
        private final String code;

        public AssemblyRejectedException(String code, String message) {
            super(message);
            this.code = identifier(code, "code");
        }

        public AssemblyRejectedException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = identifier(code, "code");
        }

        public String code() {
            return code;
        }
    }

    private record ParsedFrame(SealedFrame frame, JsonNode document) {}

    private record DossierOutcome(ObjectNode dossierPatch, JsonNode matrixPatch) {}

    private record Gap(String gapId, String dimension, String question, List<String> factKeys) {}

    private record QualityOutcome(
            Map<String, Integer> scores, int total, List<Gap> gaps, String reasoning) {}

    private record StateOutcome(
            ObjectNode actorEntry,
            boolean ready,
            List<String> missingFieldIds,
            List<String> questions,
            Recommendation recommendation,
            BigDecimal confidence) {}
}
