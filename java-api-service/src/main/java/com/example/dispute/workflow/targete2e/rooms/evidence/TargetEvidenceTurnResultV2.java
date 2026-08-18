package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict Java authority for {@code evidence-turn-result.v2}. */
public final class TargetEvidenceTurnResultV2 {

  public static final String SCHEMA_VERSION = "evidence-turn-result.v2";
  public static final String FRAME_SCHEMA_VERSION = "evidence-turn-frame.v2";
  private static final Pattern IDENTIFIER =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern FRAME_ID = Pattern.compile("EFRM_[0-9A-F]{24}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final int MAX_FRAMES = 128;
  private static final int MAX_PUBLIC_TEXT_CHARS = 100_000;
  private static final Set<String> RESULT_FIELDS = Set.of(
      "schema_version", "frame_authority_schema", "frame_manifest",
      "frame_manifest_sha256", "room_utterance", "referenced_evidence_ids",
      "observation_graph", "evidence_assessments", "evidence_requests",
      "human_review_tasks", "room_readiness");
  private static final Set<String> FRAME_FIELDS = Set.of(
      "frame_id", "frame_sequence", "frame_type", "header", "header_sha256",
      "public_text", "public_text_sha256", "public_text_length", "frame_sha256");
  private static final Set<String> HEADER_BASE = Set.of("frame_sequence", "frame_type");
  private static final Set<String> FRAME_TYPES = Set.of(
      "ROOM_WELCOME", "OPENING_ORIENTATION", "MATERIAL_RECEIPT",
      "TEXT_FOLLOWUP_REPLY", "EVIDENCE_OBSERVATION", "EVIDENCE_ASSESSMENT",
      "EVIDENCE_REQUEST", "HUMAN_REVIEW_TASK", "ROOM_READINESS");
  private static final Map<String, Set<String>> HEADER_FIELDS = Map.ofEntries(
      Map.entry("ROOM_WELCOME", HEADER_BASE),
      Map.entry("OPENING_ORIENTATION", union(HEADER_BASE, Set.of("focus_fact_ids"))),
      Map.entry("MATERIAL_RECEIPT",
          union(HEADER_BASE, Set.of("evidence_ids", "focus_fact_ids"))),
      Map.entry("TEXT_FOLLOWUP_REPLY", HEADER_BASE),
      Map.entry("EVIDENCE_OBSERVATION", union(HEADER_BASE, Set.of(
          "observation_slot", "source_unit_id", "binding_status", "fact_bindings",
          "candidate_fact_ids", "binding_reason", "observation_kind",
          "epistemic_status"))),
      Map.entry("EVIDENCE_ASSESSMENT", union(HEADER_BASE, Set.of(
          "evidence_id", "observation_slots", "relevance", "source_chain_status",
          "formation_time_status", "integrity_status", "readability",
          "cross_source_consistency", "authenticity_status", "capability_status",
          "limitations", "conflict_findings"))),
      Map.entry("EVIDENCE_REQUEST", union(HEADER_BASE, Set.of(
          "request_slot", "target_fact_ids", "gap_codes", "requested_material_kind",
          "priority", "reason"))),
      Map.entry("HUMAN_REVIEW_TASK", union(HEADER_BASE, Set.of(
          "evidence_id", "trigger_code", "review_target", "review_instruction",
          "priority"))),
      Map.entry("ROOM_READINESS", union(HEADER_BASE, Set.of(
          "core_fact_coverage", "source_chain_coverage", "time_integrity_coverage",
          "unresolved_conflicts", "remaining_core_fact_ids", "human_review_status",
          "overall_readiness", "readiness_reasons"))));

  private final ObjectNode document;
  private final List<Frame> frames;
  private final String frameManifestSha256;
  private final String roomUtterance;
  private final List<String> referencedEvidenceIds;
  private final List<JsonNode> observationGraph;
  private final List<JsonNode> evidenceAssessments;
  private final List<JsonNode> evidenceRequests;
  private final List<JsonNode> humanReviewTasks;
  private final JsonNode roomReadiness;

  private TargetEvidenceTurnResultV2(
      ObjectNode document,
      List<Frame> frames,
      String frameManifestSha256,
      String roomUtterance,
      List<String> referencedEvidenceIds,
      List<JsonNode> observationGraph,
      List<JsonNode> evidenceAssessments,
      List<JsonNode> evidenceRequests,
      List<JsonNode> humanReviewTasks,
      JsonNode roomReadiness) {
    this.document = document.deepCopy();
    this.frames = List.copyOf(frames);
    this.frameManifestSha256 = frameManifestSha256;
    this.roomUtterance = roomUtterance;
    this.referencedEvidenceIds = List.copyOf(referencedEvidenceIds);
    this.observationGraph = immutableNodes(observationGraph);
    this.evidenceAssessments = immutableNodes(evidenceAssessments);
    this.evidenceRequests = immutableNodes(evidenceRequests);
    this.humanReviewTasks = immutableNodes(humanReviewTasks);
    this.roomReadiness = roomReadiness.deepCopy();
  }

  public static TargetEvidenceTurnResultV2 parse(ObjectMapper mapper, JsonNode raw) {
    Objects.requireNonNull(mapper, "mapper");
    require(raw != null && raw.isObject(), "Evidence V2 result is not an object");
    ObjectNode document = (ObjectNode) raw;
    require(fieldNames(document).equals(RESULT_FIELDS),
        "Evidence V2 result fields are not exact");
    require(SCHEMA_VERSION.equals(requiredText(document, "schema_version", false))
            && FRAME_SCHEMA_VERSION.equals(
                requiredText(document, "frame_authority_schema", false)),
        "Evidence V2 result schema is invalid");

    ArrayNode manifest = requiredArray(document, "frame_manifest", 1, MAX_FRAMES);
    String manifestHash = requiredSha(document, "frame_manifest_sha256");
    require(manifestHash.equals(ContractJson.sha256Hex(manifest)),
        "Evidence V2 frame manifest hash differs");
    List<Frame> frames = new ArrayList<>();
    List<JsonNode> observations = new ArrayList<>();
    List<JsonNode> assessments = new ArrayList<>();
    List<JsonNode> requests = new ArrayList<>();
    List<JsonNode> reviews = new ArrayList<>();
    List<String> publicParts = new ArrayList<>();
    Set<String> frameIds = new HashSet<>();
    for (int index = 0; index < manifest.size(); index++) {
      Frame frame = parseFrame(manifest.get(index), index + 1);
      require(frameIds.add(frame.frameId()), "Evidence V2 frame id is duplicated");
      frames.add(frame);
      if (frame.publicText() != null) publicParts.add(frame.publicText());
      switch (frame.frameType()) {
        case "EVIDENCE_OBSERVATION" -> observations.add(frame.header());
        case "EVIDENCE_ASSESSMENT" -> assessments.add(frame.header());
        case "EVIDENCE_REQUEST" -> requests.add(frame.header());
        case "HUMAN_REVIEW_TASK" -> reviews.add(frame.header());
        default -> { }
      }
    }
    require("ROOM_READINESS".equals(frames.getLast().frameType()),
        "Evidence V2 readiness frame is not last");
    String roomUtterance = requiredText(document, "room_utterance", true);
    require(roomUtterance.codePointCount(0, roomUtterance.length()) <= MAX_PUBLIC_TEXT_CHARS,
        "Evidence V2 room utterance exceeds its budget");
    require(roomUtterance.equals(String.join("\n\n", publicParts)),
        "Evidence V2 room utterance differs from public frames");

    List<String> referencedEvidenceIds = identifiers(
        requiredArray(document, "referenced_evidence_ids", 0, 50),
        "referenced_evidence_ids");
    require(new LinkedHashSet<>(referencedEvidenceIds).size() == referencedEvidenceIds.size(),
        "Evidence V2 referenced evidence ids are duplicated");
    List<JsonNode> suppliedObservations = nodes(
        requiredArray(document, "observation_graph", 0, 50));
    List<JsonNode> suppliedAssessments = nodes(
        requiredArray(document, "evidence_assessments", 0, 50));
    List<JsonNode> suppliedRequests = nodes(
        requiredArray(document, "evidence_requests", 0, 3));
    List<JsonNode> suppliedReviews = nodes(
        requiredArray(document, "human_review_tasks", 0, 50));
    JsonNode readiness = document.get("room_readiness");
    require(readiness != null && readiness.isObject(),
        "Evidence V2 room readiness is invalid");
    require(suppliedObservations.equals(observations)
            && suppliedAssessments.equals(assessments)
            && suppliedRequests.equals(requests)
            && suppliedReviews.equals(reviews)
            && readiness.equals(frames.getLast().header()),
        "Evidence V2 derived projections differ from the frame manifest");
    return new TargetEvidenceTurnResultV2(
        document, frames, manifestHash, roomUtterance, referencedEvidenceIds,
        observations, assessments, requests, reviews, readiness);
  }

  public void requireCommandBinding(String commandId, String attemptId) {
    require(validIdentifier(commandId) && validIdentifier(attemptId),
        "Evidence V2 command identity is invalid");
    for (Frame frame : frames) {
      String expected = frameId(commandId, attemptId, frame.frameSequence(), frame.frameType());
      require(MessageDigest.isEqual(
              expected.getBytes(StandardCharsets.US_ASCII),
              frame.frameId().getBytes(StandardCharsets.US_ASCII)),
          "Evidence V2 frame identity differs from command authority");
    }
  }

  public void requireFormalScope(
      String eventType, List<String> attachmentRefs, Set<String> allowedFactIds) {
    attachmentRefs = List.copyOf(Objects.requireNonNull(attachmentRefs, "attachmentRefs"));
    allowedFactIds = Set.copyOf(Objects.requireNonNull(allowedFactIds, "allowedFactIds"));
    Set<String> observationSlots = new LinkedHashSet<>();
    Set<String> sourceUnits = new LinkedHashSet<>();
    Set<String> assessmentEvidenceIds = new LinkedHashSet<>();
    Set<String> assessedObservationSlots = new LinkedHashSet<>();
    List<String> assessmentEvidenceOrder = new ArrayList<>();
    int requestCount = 0;
    int phase = 0;
    for (Frame frame : frames) {
      ObjectNode header = (ObjectNode) frame.header();
      requireKnownFacts(header, allowedFactIds);
      switch (frame.frameType()) {
        case "EVIDENCE_OBSERVATION" -> {
          require(phase <= 1, "Evidence V2 observation frame is out of order");
          phase = 1;
          require(observationSlots.add(requiredIdentifier(header, "observation_slot"))
                  && sourceUnits.add(requiredIdentifier(header, "source_unit_id")),
              "Evidence V2 observation source or slot is duplicated");
        }
        case "EVIDENCE_ASSESSMENT" -> {
          require(phase <= 2, "Evidence V2 assessment frame is out of order");
          phase = 2;
          String evidenceId = requiredIdentifier(header, "evidence_id");
          require(attachmentRefs.contains(evidenceId)
                  && assessmentEvidenceIds.add(evidenceId),
              "Evidence V2 assessment is outside the current attachment scope");
          assessmentEvidenceOrder.add(evidenceId);
          for (String slot : optionalIdentifiers(header, "observation_slots", 20)) {
            require(observationSlots.contains(slot) && assessedObservationSlots.add(slot),
                "Evidence V2 assessment references an unknown observation");
          }
        }
        case "EVIDENCE_REQUEST" -> {
          require(phase <= 3, "Evidence V2 request frame is out of order");
          phase = 3;
          requestCount++;
        }
        case "HUMAN_REVIEW_TASK" -> {
          require(phase <= 4, "Evidence V2 human-review frame is out of order");
          phase = 4;
          require(attachmentRefs.contains(requiredIdentifier(header, "evidence_id")),
              "Evidence V2 review task is outside the current attachment scope");
        }
        case "ROOM_READINESS" -> phase = 5;
        default -> { }
      }
    }
    require(requestCount <= 3, "Evidence V2 request count exceeds the contract");
    if ("ROOM_OPENING".equals(eventType)) {
      require(attachmentRefs.isEmpty()
              && referencedEvidenceIds.isEmpty()
              && observationSlots.isEmpty()
              && assessmentEvidenceIds.isEmpty()
              && frames.size() >= 5
              && "ROOM_WELCOME".equals(frames.get(0).frameType())
              && "OPENING_ORIENTATION".equals(frames.get(1).frameType())
              && requestCount >= 2
              && requestCount <= 3,
          "Evidence V2 opening frame sequence is invalid");
      for (int index = 2; index < frames.size() - 1; index++) {
        require("EVIDENCE_REQUEST".equals(frames.get(index).frameType()),
            "Evidence V2 opening contains an invalid frame type");
      }
      return;
    }
    require(new LinkedHashSet<>(attachmentRefs).size() == attachmentRefs.size(),
        "Evidence V2 material attachment scope is duplicated");
    require("PARTY_MESSAGE".equals(eventType)
            && !attachmentRefs.isEmpty()
            && frames.size() >= 3
            && "MATERIAL_RECEIPT".equals(frames.getFirst().frameType())
            && "ROOM_READINESS".equals(frames.getLast().frameType())
            && referencedEvidenceIds.equals(attachmentRefs)
            && assessmentEvidenceOrder.equals(attachmentRefs)
            && assessmentEvidenceIds.equals(new LinkedHashSet<>(attachmentRefs))
            && assessedObservationSlots.equals(observationSlots),
        "Evidence V2 material-review frame scope is invalid");
    int index = 1;
    while (index < frames.size() - 1
        && "EVIDENCE_OBSERVATION".equals(frames.get(index).frameType())) {
      index++;
    }
    for (String evidenceId : attachmentRefs) {
      require(index < frames.size() - 1
              && "EVIDENCE_ASSESSMENT".equals(frames.get(index).frameType())
              && evidenceId.equals(requiredIdentifier(
                  frames.get(index).header(), "evidence_id")),
          "Evidence V2 material assessment order is invalid");
      index++;
    }
    while (index < frames.size() - 1
        && "EVIDENCE_REQUEST".equals(frames.get(index).frameType())) {
      index++;
    }
    while (index < frames.size() - 1
        && "HUMAN_REVIEW_TASK".equals(frames.get(index).frameType())) {
      index++;
    }
    require(index == frames.size() - 1,
        "Evidence V2 material frame sequence is invalid");
  }

  private static void requireKnownFacts(ObjectNode header, Set<String> allowedFactIds) {
    for (String field : List.of(
        "focus_fact_ids", "candidate_fact_ids", "target_fact_ids",
        "remaining_core_fact_ids")) {
      for (String factId : optionalIdentifiers(header, field, 50)) {
        require(allowedFactIds.contains(factId),
            "Evidence V2 frame references an unknown formal fact");
      }
    }
    ArrayNode bindings = optionalArray(header, "fact_bindings", 20);
    if (bindings != null) {
      for (JsonNode binding : bindings) {
        require(allowedFactIds.contains(requiredIdentifier(binding, "fact_id")),
            "Evidence V2 fact binding references an unknown formal fact");
      }
    }
  }

  public JsonNode document() { return document.deepCopy(); }
  public List<Frame> frames() { return frames; }
  public String frameManifestSha256() { return frameManifestSha256; }
  public String roomUtterance() { return roomUtterance; }
  public List<String> referencedEvidenceIds() { return referencedEvidenceIds; }
  public List<JsonNode> observationGraph() { return immutableNodes(observationGraph); }
  public List<JsonNode> evidenceAssessments() { return immutableNodes(evidenceAssessments); }
  public List<JsonNode> evidenceRequests() { return immutableNodes(evidenceRequests); }
  public List<JsonNode> humanReviewTasks() { return immutableNodes(humanReviewTasks); }
  public JsonNode roomReadiness() { return roomReadiness.deepCopy(); }

  private static Frame parseFrame(JsonNode raw, int expectedSequence) {
    require(raw != null && raw.isObject(), "Evidence V2 frame is not an object");
    ObjectNode frame = (ObjectNode) raw;
    require(fieldNames(frame).equals(FRAME_FIELDS), "Evidence V2 frame fields are not exact");
    String frameId = requiredText(frame, "frame_id", false);
    require(FRAME_ID.matcher(frameId).matches(), "Evidence V2 frame id is invalid");
    int sequence = requiredInt(frame, "frame_sequence", 1, MAX_FRAMES);
    require(sequence == expectedSequence, "Evidence V2 frame sequence is not contiguous");
    String frameType = requiredText(frame, "frame_type", false);
    require(FRAME_TYPES.contains(frameType), "Evidence V2 frame type is invalid");
    JsonNode rawHeader = frame.get("header");
    require(rawHeader != null && rawHeader.isObject(), "Evidence V2 frame header is invalid");
    ObjectNode header = ((ObjectNode) rawHeader).deepCopy();
    validateHeader(header, sequence, frameType);
    String headerHash = requiredSha(frame, "header_sha256");
    require(headerHash.equals(ContractJson.sha256Hex(header)),
        "Evidence V2 frame header hash differs");
    JsonNode textNode = frame.get("public_text");
    require(textNode != null && (textNode.isNull() || textNode.isTextual()),
        "Evidence V2 frame public text is invalid");
    String publicText = textNode.isNull() ? null : textNode.textValue();
    boolean internal = "HUMAN_REVIEW_TASK".equals(frameType);
    require(internal == (publicText == null),
        "Evidence V2 frame visibility differs from frame type");
    if (publicText != null) {
      require(publicText.codePointCount(0, publicText.length()) <= MAX_PUBLIC_TEXT_CHARS,
          "Evidence V2 frame public text exceeds its budget");
    }
    String publicTextHash = requiredSha(frame, "public_text_sha256");
    String textValue = publicText == null ? "" : publicText;
    require(publicTextHash.equals(sha256(textValue.getBytes(StandardCharsets.UTF_8))),
        "Evidence V2 frame public text hash differs");
    int textLength = requiredInt(frame, "public_text_length", 0, MAX_PUBLIC_TEXT_CHARS);
    require(textLength == textValue.codePointCount(0, textValue.length()),
        "Evidence V2 frame public text length differs");
    String frameHash = requiredSha(frame, "frame_sha256");
    ObjectNode hashPreimage = frame.deepCopy();
    hashPreimage.remove("frame_sha256");
    require(frameHash.equals(ContractJson.sha256Hex(hashPreimage)),
        "Evidence V2 frame hash differs");
    return new Frame(
        frameId, sequence, frameType, header, headerHash, publicText,
        publicTextHash, textLength, frameHash);
  }

  private static void validateHeader(ObjectNode header, int sequence, String frameType) {
    Set<String> allowed = HEADER_FIELDS.get(frameType);
    require(allowed != null && allowed.containsAll(fieldNames(header))
            && fieldNames(header).containsAll(HEADER_BASE),
        "Evidence V2 frame header fields are invalid");
    require(requiredInt(header, "frame_sequence", 1, MAX_FRAMES) == sequence
            && frameType.equals(requiredText(header, "frame_type", false)),
        "Evidence V2 frame header identity differs");
    switch (frameType) {
      case "ROOM_WELCOME", "TEXT_FOLLOWUP_REPLY" ->
          require(fieldNames(header).equals(HEADER_BASE),
              "Evidence V2 text-only header contains authority fields");
      case "OPENING_ORIENTATION" ->
          require(!identifiers(requiredArray(header, "focus_fact_ids", 1, 20),
                  "focus_fact_ids").isEmpty(),
              "Evidence V2 orientation has no facts");
      case "MATERIAL_RECEIPT" -> {
        identifiers(requiredArray(header, "evidence_ids", 1, 50), "evidence_ids");
        optionalIdentifiers(header, "focus_fact_ids", 20);
      }
      case "EVIDENCE_OBSERVATION" -> validateObservationHeader(header);
      case "EVIDENCE_ASSESSMENT" -> validateAssessmentHeader(header);
      case "EVIDENCE_REQUEST" -> validateRequestHeader(header);
      case "HUMAN_REVIEW_TASK" -> {
        requiredIdentifier(header, "evidence_id");
        requiredIdentifier(header, "trigger_code");
        boundedText(header, "review_target", 1_000);
        boundedText(header, "review_instruction", 1_000);
        enumText(header, "priority", Set.of("LOW", "MEDIUM", "HIGH"));
      }
      case "ROOM_READINESS" -> validateReadinessHeader(header);
      default -> throw new IllegalStateException("Evidence V2 frame type is unreachable");
    }
  }

  private static void validateObservationHeader(ObjectNode header) {
    requiredIdentifier(header, "observation_slot");
    requiredIdentifier(header, "source_unit_id");
    String status = enumText(header, "binding_status", Set.of("BOUND", "UNRELATED", "AMBIGUOUS"));
    ArrayNode bindings = optionalArray(header, "fact_bindings", 20);
    List<String> candidates = optionalIdentifiers(header, "candidate_fact_ids", 20);
    if ("BOUND".equals(status)) require(bindings != null && !bindings.isEmpty(),
        "Evidence V2 bound observation has no fact bindings");
    if ("UNRELATED".equals(status)) require(bindings == null || bindings.isEmpty(),
        "Evidence V2 unrelated observation has fact bindings");
    if ("AMBIGUOUS".equals(status)) require(!candidates.isEmpty(),
        "Evidence V2 ambiguous observation has no candidate facts");
    if (bindings != null) {
      Set<String> factIds = new HashSet<>();
      for (JsonNode raw : bindings) {
        require(raw.isObject() && fieldNames((ObjectNode) raw).equals(
                Set.of("fact_id", "relation", "reason")),
            "Evidence V2 fact binding fields are invalid");
        String factId = requiredIdentifier(raw, "fact_id");
        require(factIds.add(factId), "Evidence V2 fact binding is duplicated");
        enumText(raw, "relation", Set.of(
            "CONTENT_SUPPORTS", "CONTENT_CONTRADICTS", "CONTEXT_ONLY", "INCONCLUSIVE"));
        boundedText(raw, "reason", 500);
      }
    }
    optionalBoundedText(header, "binding_reason", 500);
    enumText(header, "observation_kind", Set.of(
        "PARSED_RECORD", "PARSED_PARTY_STATEMENT", "PARSED_TRANSACTION_STATUS",
        "OCR_TEXT", "IMAGE_PIXELS", "PLATFORM_RECORD"));
    enumText(header, "epistemic_status", Set.of("PENDING_VERIFICATION", "PROVISIONAL"));
  }

  private static void validateAssessmentHeader(ObjectNode header) {
    requiredIdentifier(header, "evidence_id");
    optionalIdentifiers(header, "observation_slots", 20);
    enumText(header, "relevance", Set.of(
        "DIRECT", "PARTIAL", "CONTEXTUAL", "UNRELATED", "UNAVAILABLE"));
    enumText(header, "source_chain_status", Set.of(
        "TRACEABLE", "PARTIAL", "UNTRACEABLE", "UNAVAILABLE"));
    enumText(header, "formation_time_status", Set.of(
        "CONFIRMED", "PARTIAL", "UNKNOWN", "CONFLICTING"));
    enumText(header, "integrity_status", Set.of(
        "INTACT", "PARTIAL", "ANOMALY_DETECTED", "UNAVAILABLE"));
    enumText(header, "readability", Set.of("CLEAR", "PARTIAL", "UNREADABLE", "UNAVAILABLE"));
    enumText(header, "cross_source_consistency", Set.of(
        "CONSISTENT", "MIXED", "CONFLICTING", "NOT_ASSESSED"));
    enumText(header, "authenticity_status", Set.of(
        "UNVERIFIED", "PROVISIONALLY_CONSISTENT", "ANOMALY_DETECTED", "UNAVAILABLE",
        "REQUIRES_HUMAN_REVIEW"));
    enumText(header, "capability_status", Set.of(
        "FULL_CONTENT", "TEXT_ONLY", "OCR_ONLY", "PIXELS_LOADED", "PARTIAL",
        "UNAVAILABLE"));
    optionalStrings(header, "limitations", 20, 1_000);
    optionalStrings(header, "conflict_findings", 20, 1_000);
  }

  private static void validateRequestHeader(ObjectNode header) {
    requiredIdentifier(header, "request_slot");
    List<String> facts = optionalIdentifiers(header, "target_fact_ids", 20);
    List<String> gaps = optionalIdentifiers(header, "gap_codes", 10);
    require(!facts.isEmpty() || !gaps.isEmpty(),
        "Evidence V2 request has no fact or gap target");
    boundedText(header, "requested_material_kind", 1_000);
    enumText(header, "priority", Set.of("LOW", "MEDIUM", "HIGH"));
    optionalBoundedText(header, "reason", 500);
  }

  private static void validateReadinessHeader(ObjectNode header) {
    Set<String> coverage = Set.of("COMPLETE", "PARTIAL", "NONE", "UNKNOWN");
    enumText(header, "core_fact_coverage", coverage);
    enumText(header, "source_chain_coverage", coverage);
    enumText(header, "time_integrity_coverage", coverage);
    optionalStrings(header, "unresolved_conflicts", 20, 1_000);
    optionalIdentifiers(header, "remaining_core_fact_ids", 50);
    enumText(header, "human_review_status", Set.of("NONE", "PENDING", "REQUIRED"));
    enumText(header, "overall_readiness", Set.of("READY", "PARTIAL", "NOT_READY", "UNKNOWN"));
    optionalStrings(header, "readiness_reasons", 20, 1_000);
  }

  public static String frameId(
      String commandId, String attemptId, int frameSequence, String frameType) {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode preimage = mapper.createObjectNode();
    preimage.put("command_id", commandId);
    preimage.put("attempt_id", attemptId);
    preimage.put("frame_sequence", frameSequence);
    preimage.put("frame_type", frameType);
    return "EFRM_" + ContractJson.sha256Hex(preimage).substring(0, 24).toUpperCase();
  }

  private static ArrayNode requiredArray(
      JsonNode node, String field, int minItems, int maxItems) {
    JsonNode value = node.get(field);
    require(value != null && value.isArray()
            && value.size() >= minItems && value.size() <= maxItems,
        "Evidence V2 " + field + " is invalid");
    return (ArrayNode) value;
  }

  private static ArrayNode optionalArray(JsonNode node, String field, int maxItems) {
    JsonNode value = node.get(field);
    if (value == null) return null;
    require(value.isArray() && value.size() <= maxItems,
        "Evidence V2 " + field + " is invalid");
    return (ArrayNode) value;
  }

  private static List<String> optionalIdentifiers(JsonNode node, String field, int maxItems) {
    ArrayNode values = optionalArray(node, field, maxItems);
    return values == null ? List.of() : identifiers(values, field);
  }

  private static List<String> identifiers(ArrayNode values, String field) {
    List<String> result = new ArrayList<>();
    Set<String> unique = new HashSet<>();
    for (JsonNode value : values) {
      require(value.isTextual() && validIdentifier(value.textValue())
              && unique.add(value.textValue()),
          "Evidence V2 " + field + " contains an invalid identifier");
      result.add(value.textValue());
    }
    return List.copyOf(result);
  }

  private static void optionalStrings(
      JsonNode node, String field, int maxItems, int maxChars) {
    ArrayNode values = optionalArray(node, field, maxItems);
    if (values == null) return;
    for (JsonNode value : values) {
      require(value.isTextual() && !value.textValue().isBlank()
              && value.textValue().codePointCount(0, value.textValue().length()) <= maxChars,
          "Evidence V2 " + field + " contains invalid text");
    }
  }

  private static String requiredIdentifier(JsonNode node, String field) {
    String value = requiredText(node, field, false);
    require(validIdentifier(value), "Evidence V2 " + field + " is not an identifier");
    return value;
  }

  private static boolean validIdentifier(String value) {
    return value != null && IDENTIFIER.matcher(value).matches();
  }

  private static String enumText(JsonNode node, String field, Set<String> allowed) {
    String value = requiredText(node, field, false);
    require(allowed.contains(value), "Evidence V2 " + field + " is outside its enum");
    return value;
  }

  private static String boundedText(JsonNode node, String field, int maxChars) {
    String value = requiredText(node, field, false);
    require(value.codePointCount(0, value.length()) <= maxChars,
        "Evidence V2 " + field + " exceeds its budget");
    return value;
  }

  private static void optionalBoundedText(JsonNode node, String field, int maxChars) {
    if (node.get(field) != null) boundedText(node, field, maxChars);
  }

  private static String requiredText(JsonNode node, String field, boolean allowEmpty) {
    JsonNode value = node.get(field);
    require(value != null && value.isTextual()
            && (allowEmpty || !value.textValue().isBlank()),
        "Evidence V2 " + field + " is invalid");
    return value.textValue();
  }

  private static String requiredSha(JsonNode node, String field) {
    String value = requiredText(node, field, false);
    require(SHA256.matcher(value).matches(), "Evidence V2 " + field + " is invalid");
    return value;
  }

  private static int requiredInt(
      JsonNode node, String field, int minimum, int maximum) {
    JsonNode value = node.get(field);
    require(value != null && value.isIntegralNumber() && value.canConvertToInt(),
        "Evidence V2 " + field + " is invalid");
    int result = value.intValue();
    require(result >= minimum && result <= maximum,
        "Evidence V2 " + field + " is outside its range");
    return result;
  }

  private static List<JsonNode> nodes(ArrayNode array) {
    List<JsonNode> result = new ArrayList<>();
    array.forEach(value -> result.add(value.deepCopy()));
    return List.copyOf(result);
  }

  private static List<JsonNode> immutableNodes(List<JsonNode> values) {
    return values.stream().map(value -> (JsonNode) value.deepCopy()).toList();
  }

  private static Set<String> fieldNames(ObjectNode node) {
    Set<String> result = new HashSet<>();
    node.fieldNames().forEachRemaining(result::add);
    return Set.copyOf(result);
  }

  private static Set<String> union(Set<String> left, Set<String> right) {
    Set<String> result = new HashSet<>(left);
    result.addAll(right);
    return Set.copyOf(result);
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  public record Frame(
      String frameId,
      int frameSequence,
      String frameType,
      JsonNode header,
      String headerSha256,
      String publicText,
      String publicTextSha256,
      int publicTextLength,
      String frameSha256) {
    public Frame {
      header = Objects.requireNonNull(header, "header").deepCopy();
    }

    @Override
    public JsonNode header() {
      return header.deepCopy();
    }
  }
}
