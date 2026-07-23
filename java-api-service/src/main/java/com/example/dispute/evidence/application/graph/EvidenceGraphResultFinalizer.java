package com.example.dispute.evidence.application.graph;

import com.example.dispute.evidence.application.graph.EvidenceAssetAuthorization.ActualLoadReceipt;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationLedger.ActualLoadRequirement;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationLedger.CommitRequest;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationLedger.Lookup;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationReceipt.BatchMergeBinding;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Validates a complete Evidence proposal before touching the isolated receipt ledger. */
public final class EvidenceGraphResultFinalizer {

  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
  private static final Pattern EVIDENCE_ID =
      Pattern.compile("^EVIDENCE_[A-Za-z0-9][A-Za-z0-9._:-]{0,118}$");
  private static final Pattern FACT_ID = Pattern.compile("^FACT_[A-Za-z0-9_:-]{1,123}$");
  private static final Pattern THREAD_ID = Pattern.compile("^grt[.]v1[.][0-9a-f]{32}$");

  private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
  private static final Set<String> TERMINAL_FIELDS =
      Set.of(
          "schema_version",
          "proposal_hash",
          "execution_scope",
          "writer_mode",
          "formal_sink_eligible",
          "command_id",
          "logical_run_id",
          "attempt_id",
          "thread_id",
          "manifest_id",
          "manifest_hash",
          "item_count",
          "ordered_item_keys",
          "assessment_refs",
          "coverage_status",
          "proposed_fact_links",
          "proposed_review_items",
          "profile_versions",
          "completed_at");
  private static final Set<String> ASSESSMENT_REF_FIELDS =
      Set.of("evidence_id", "assessment_status", "assessment_hash");
  private static final Set<String> ASSESSMENT_FIELDS =
      Set.of(
          "schema_version",
          "assessment_hash",
          "execution_scope",
          "formal_sink_eligible",
          "command_id",
          "logical_run_id",
          "attempt_id",
          "thread_id",
          "manifest_id",
          "manifest_hash",
          "evidence_id",
          "item_hash",
          "formal_evidence_revision",
          "actor_scope_hash",
          "profile_versions",
          "assessment_status",
          "authenticity_score",
          "authenticity_reason_codes",
          "relevance_score",
          "relevance_reason_codes",
          "completeness_score",
          "confidence",
          "candidate_fact_links",
          "source_refs",
          "inspected_modalities",
          "asset_load_status",
          "asset_load_receipt_ref",
          "asset_load_receipt_hash",
          "limitations",
          "review_reasons");
  private static final Set<String> FACT_LINK_FIELDS =
      Set.of("fact_id", "evidence_ids", "source_refs");
  private static final Set<String> ITEM_FACT_LINK_FIELDS = Set.of("fact_id", "source_refs");
  private static final Set<String> REVIEW_FIELDS =
      Set.of("review_key", "evidence_id", "reason_codes", "priority");
  private static final Set<String> PROFILE_FIELDS =
      Set.of(
          "graph_version",
          "checkpoint_schema_version",
          "state_schema_version",
          "prompt_version",
          "model_profile_id",
          "assessment_output_schema_version",
          "terminal_output_schema_version",
          "policy_version",
          "guardrail_version",
          "tool_policy_version");

  static {
    MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  private final EvidenceFinalizationLedger ledger;
  private final AuthorityResolver authorityResolver;
  private final Clock clock;

  public EvidenceGraphResultFinalizer(
      EvidenceFinalizationLedger ledger, AuthorityResolver authorityResolver, Clock clock) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.authorityResolver = Objects.requireNonNull(authorityResolver, "authorityResolver");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public EvidenceFinalizationReceipt finalizeResult(FinalizationRequest request) {
    Objects.requireNonNull(request, "request");
    EvidenceGraphCommandFactory.requireCurrentAuthority(
        request.manifest(), request.binding(), request.currentAuthority(), false);
    validateCommand(request);
    requireEqual(request.requestHash(), canonicalRequestHash(request), "finalization request hash");

    String proposalHash = requiredHash(request.terminalProposal(), "proposal_hash");
    BatchMergeBinding operationBinding =
        new BatchMergeBinding(
            request.manifest().manifestHash(),
            request.manifest().number("dossier_target_version"),
            proposalHash,
            request.command().logicalRunId(),
            request.command().commandId(),
            request.command().attemptId(),
            request.command().threadId());
    String operationKey =
        operationBinding.operationKey(
            request.currentAuthority().caseId(), request.currentAuthority().roomEpoch());
    Lookup lookup = new Lookup(request.currentAuthority().tenantSurrogate(), operationKey);
    var committed = ledger.findCommitted(lookup);
    if (committed.isPresent()) {
      EvidenceFinalizationReceipt existing = committed.orElseThrow();
      EvidenceFinalizationLedger.requireExactReplay(existing, operationKey, request.requestHash());
      validateReceipt(request, operationBinding, existing);
      return existing;
    }

    validateTerminalSchema(request.terminalProposal());
    validateAssessmentSchemas(request.itemAssessments());
    AuthoritySnapshot snapshot =
        Objects.requireNonNull(
            authorityResolver.resolve(
                new AuthorityLookup(request.authoritySnapshotHash(), request.currentAuthority())),
            "authority resolver returned no snapshot");
    validateAuthoritySnapshot(request, snapshot);
    validateTerminalProposal(request, snapshot);
    List<ActualLoadRequirement> loadRequirements = validateAssessmentsAndLoads(request, snapshot);

    EvidenceFinalizationReceipt candidate =
        EvidenceFinalizationReceipt.committedSyntheticBatchMerge(
            "RECEIPT_" + request.requestHash().substring(0, 32),
            request.requestHash(),
            request.resultHash(),
            request.currentAuthority().tenantSurrogate(),
            request.currentAuthority().caseId(),
            request.currentAuthority().roomEpoch(),
            request.currentAuthority().javaRoomFencingToken(),
            request.currentAuthority().sourceRevision(),
            request.currentAuthority().processRevision(),
            request.currentAuthority().roomRevision(),
            operationBinding,
            clock.instant());
    EvidenceFinalizationReceipt receipt =
        Objects.requireNonNull(
            ledger.commitOrReplay(
                new CommitRequest(candidate, snapshot.toCommitRequirement(loadRequirements))),
            "ledger returned no receipt");
    EvidenceFinalizationLedger.requireExactReplay(receipt, operationKey, request.requestHash());
    validateReceipt(request, operationBinding, receipt);
    return receipt;
  }

  private static void validateCommand(FinalizationRequest request) {
    RoomGraphCommand command = request.command();
    EvidenceGraphBinding binding = request.binding();
    EvidenceGraphCommandFactory.CurrentAuthority current = request.currentAuthority();
    if (command.roomType() != RoomType.EVIDENCE || command.eventRef() != null) {
      throw rejected("EVIDENCE_COMMAND_NOT_MANIFEST_ONLY");
    }
    requireEqual(command.tenantSurrogate(), current.tenantSurrogate(), "command tenant");
    requireEqual(command.caseId(), current.caseId(), "command case");
    requireEqual(command.roomEpoch(), current.roomEpoch(), "command room epoch");
    requireEqual(command.processRevision(), current.processRevision(), "command process revision");
    requireEqual(command.graphKey(), binding.graphKey(), "command graph key");
    requireEqual(command.graphVersion(), binding.graphVersion(), "command graph version");
    requireEqual(
        command.checkpointSchemaVersion(),
        binding.checkpointSchemaVersion(),
        "command checkpoint schema");
    requireEqual(command.threadId(), binding.threadId(), "command thread");
    requireEqual(command.actorScope().actorId(), current.actorId(), "command actor");
    requireEqual(
        command.actorScope().actorRole().name(), current.actorRole(), "command actor role");
    requireEqual(command.actorScope().audience().name(), current.actorRole(), "command audience");
    requireEqual(
        command.actorScope().capabilities(),
        command.invocationContext().toolCapabilities(),
        "command capabilities");
    requireEqual(
        ContractJson.sha256Hex(MAPPER.valueToTree(command.actorScope())),
        current.actorScopeHash(),
        "command actor scope hash");

    RoomGraphCommand.SnapshotRef ref = command.domainSnapshotRef();
    requireEqual(ref.artifactId(), binding.manifestId(), "command manifest id");
    requireEqual(
        ref.schemaVersion(), EvidenceBatchManifest.SCHEMA_VERSION, "command manifest schema");
    requireEqual(ref.uri(), binding.manifestPayloadUri(), "command manifest URI");
    requireEqual(ref.sha256(), binding.manifestPayloadSha256(), "command manifest payload hash");
    requireEqual(
        ref.sizeBytes(), binding.manifestPayloadSizeBytes(), "command manifest payload size");

    ObjectNode profile = request.manifest().profileVersions();
    requireEqual(
        command.invocationContext().agentProfileId(),
        EvidenceGraphCommandFactory.AGENT_PROFILE_ID,
        "command agent profile");
    requireEqual(
        command.invocationContext().promptProfileId(),
        requiredText(profile, "prompt_version"),
        "command prompt version");
    requireEqual(
        command.invocationContext().modelProfileId(),
        requiredText(profile, "model_profile_id"),
        "command model profile");
    requireEqual(
        command.invocationContext().outputSchemaVersion(),
        EvidenceBatchManifest.TERMINAL_OUTPUT_SCHEMA_VERSION,
        "command terminal output schema");
    requireEqual(
        command.invocationContext().policyVersion(),
        requiredText(profile, "policy_version"),
        "command policy version");
    requireEqual(
        command.invocationContext().guardrailVersion(),
        requiredText(profile, "guardrail_version"),
        "command guardrail version");

    ObjectNode commandBinding =
        (ObjectNode) request.manifest().document().required("command_binding");
    requireEqual(command.commandId(), requiredText(commandBinding, "command_id"), "command id");
    requireEqual(
        command.logicalRunId(), requiredText(commandBinding, "logical_run_id"), "logical run id");
    requireEqual(command.attemptId(), requiredText(commandBinding, "attempt_id"), "attempt id");
    requireEqual(
        command.deadlineAt().toString(),
        requiredText(commandBinding, "deadline_at"),
        "command deadline");
    ObjectNode preimage = MAPPER.valueToTree(command);
    preimage.remove("request_hash");
    requireEqual(command.requestHash(), ContractJson.sha256Hex(preimage), "command request hash");
  }

  private static void validateTerminalSchema(JsonNode rawProposal) {
    ObjectNode proposal = requiredObject(rawProposal, "terminal proposal");
    requireExactFields(proposal, TERMINAL_FIELDS, "terminal proposal");
    requireEncodedSize(proposal, 262_144, "terminal proposal");
    requireEqual(
        requiredText(proposal, "schema_version"),
        EvidenceBatchManifest.TERMINAL_OUTPUT_SCHEMA_VERSION,
        "terminal schema");
    requireEqual(
        requiredText(proposal, "execution_scope"), "SIGNED_SYNTHETIC_ONLY", "execution scope");
    requireEqual(requiredText(proposal, "writer_mode"), "PROPOSAL_ONLY", "writer mode");
    requireFalse(proposal, "formal_sink_eligible");
    requireCanonicalSelfHash(proposal, "proposal_hash", "terminal proposal");
    for (String field : List.of("command_id", "logical_run_id", "attempt_id", "manifest_id")) {
      requirePattern(proposal, field, IDENTIFIER, "identifier");
    }
    requirePattern(proposal, "thread_id", THREAD_ID, "thread id");
    requiredHash(proposal, "manifest_hash");
    requireDateTime(proposal, "completed_at");
    long itemCount = requiredSafeLong(proposal, "item_count", 1);
    if (!Set.of(1L, 8L, 100L).contains(itemCount)) {
      throw rejected("EVIDENCE_TERMINAL_ITEM_COUNT_NOT_ADMITTED");
    }
    List<String> keys = textList(proposal.required("ordered_item_keys"), "ordered item keys");
    requireCardinality(keys, 1, 100, "ordered item keys");
    keys.forEach(value -> requirePattern(value, EVIDENCE_ID, "ordered evidence id"));
    if (new HashSet<>(keys).size() != keys.size()) {
      throw rejected("EVIDENCE_DUPLICATE_ORDERED_ITEM_KEY");
    }
    requireEqual(keys.size(), Math.toIntExact(itemCount), "ordered item count");

    ArrayNode refs = requiredArray(proposal, "assessment_refs");
    requireCardinality(refs, 1, 100, "assessment refs");
    requireEqual(refs.size(), Math.toIntExact(itemCount), "assessment ref count");
    for (JsonNode candidate : refs) {
      ObjectNode ref = requiredObject(candidate, "assessment ref");
      requireExactFields(ref, ASSESSMENT_REF_FIELDS, "assessment ref");
      requirePattern(ref, "evidence_id", EVIDENCE_ID, "evidence id");
      requiredHash(ref, "assessment_hash");
      if (!Set.of("COMPLETED", "NEEDS_REVIEW").contains(requiredText(ref, "assessment_status"))) {
        throw rejected("EVIDENCE_ASSESSMENT_STATUS_INVALID");
      }
    }
    requireProfilePins(
        proposal.required("profile_versions"),
        requiredObject(proposal.required("profile_versions"), "profile versions"));
    validateTerminalNestedSchema(proposal);
  }

  private static void validateTerminalNestedSchema(ObjectNode proposal) {
    ArrayNode links = requiredArray(proposal, "proposed_fact_links");
    requireCardinality(links, 0, 100, "proposed fact links");
    for (JsonNode candidate : links) {
      ObjectNode link = requiredObject(candidate, "terminal fact link");
      requireExactFields(link, FACT_LINK_FIELDS, "terminal fact link");
      requirePattern(link, "fact_id", FACT_ID, "fact id");
      List<String> evidenceIds = textList(link.required("evidence_ids"), "fact evidence ids");
      requireCardinality(evidenceIds, 1, 100, "fact evidence ids");
      evidenceIds.forEach(value -> requirePattern(value, EVIDENCE_ID, "fact evidence id"));
      requireUnique(evidenceIds, "fact evidence ids");
      List<String> sourceRefs = textList(link.required("source_refs"), "fact source refs");
      requireCardinality(sourceRefs, 1, 64, "fact source refs");
      sourceRefs.forEach(value -> requirePattern(value, IDENTIFIER, "fact source ref"));
      requireUnique(sourceRefs, "fact source refs");
    }
    ArrayNode reviews = requiredArray(proposal, "proposed_review_items");
    requireCardinality(reviews, 0, 100, "proposed review items");
    for (JsonNode candidate : reviews) {
      ObjectNode review = requiredObject(candidate, "review item");
      requireExactFields(review, REVIEW_FIELDS, "review item");
      requirePattern(review, "review_key", IDENTIFIER, "review key");
      requirePattern(review, "evidence_id", EVIDENCE_ID, "review evidence id");
      List<String> reasons = textList(review.required("reason_codes"), "review reasons");
      requireCardinality(reasons, 1, 16, "review reasons");
      reasons.forEach(value -> requirePattern(value, IDENTIFIER, "review reason"));
      requireUnique(reasons, "review reasons");
      if (!Set.of("LOW", "MEDIUM", "HIGH").contains(requiredText(review, "priority"))) {
        throw rejected("EVIDENCE_REVIEW_PRIORITY_INVALID");
      }
    }
  }

  private static void validateAssessmentSchemas(List<JsonNode> assessments) {
    requireCardinality(assessments, 1, 100, "item assessments");
    assessments.forEach(EvidenceGraphResultFinalizer::validateAssessmentSchema);
  }

  private static void validateAssessmentSchema(JsonNode rawAssessment) {
    ObjectNode assessment = requiredObject(rawAssessment, "item assessment");
    requireExactFields(assessment, ASSESSMENT_FIELDS, "item assessment");
    requireEncodedSize(assessment, 65_536, "item assessment");
    requireEqual(
        requiredText(assessment, "schema_version"),
        EvidenceBatchManifest.ASSESSMENT_OUTPUT_SCHEMA_VERSION,
        "assessment schema");
    requireEqual(
        requiredText(assessment, "execution_scope"),
        "SIGNED_SYNTHETIC_ONLY",
        "assessment execution scope");
    requireFalse(assessment, "formal_sink_eligible");
    requireCanonicalSelfHash(assessment, "assessment_hash", "item assessment");
    for (String field : List.of("command_id", "logical_run_id", "attempt_id", "manifest_id")) {
      requirePattern(assessment, field, IDENTIFIER, "identifier");
    }
    requirePattern(assessment, "thread_id", THREAD_ID, "thread id");
    requirePattern(assessment, "evidence_id", EVIDENCE_ID, "evidence id");
    requiredHash(assessment, "manifest_hash");
    requiredHash(assessment, "item_hash");
    requiredHash(assessment, "actor_scope_hash");
    requiredSafeLong(assessment, "formal_evidence_revision", 1);
    requireProfilePins(
        assessment.required("profile_versions"),
        requiredObject(assessment.required("profile_versions"), "profile versions"));
    String assessmentStatus = requiredText(assessment, "assessment_status");
    if (!Set.of("COMPLETED", "NEEDS_REVIEW").contains(assessmentStatus)) {
      throw rejected("EVIDENCE_ASSESSMENT_STATUS_INVALID");
    }
    for (String field :
        List.of("authenticity_score", "relevance_score", "completeness_score", "confidence")) {
      JsonNode value = assessment.required(field);
      if (!value.isNumber() || value.doubleValue() < 0 || value.doubleValue() > 1) {
        throw rejected("EVIDENCE_SCORE_OUT_OF_RANGE:" + field);
      }
    }
    validateIdentifierArray(assessment, "authenticity_reason_codes", 0, 16);
    validateIdentifierArray(assessment, "relevance_reason_codes", 0, 16);
    validateIdentifierArray(assessment, "source_refs", 1, 64);
    validateIdentifierArray(assessment, "limitations", 0, 16);
    List<String> reviewReasons = validateIdentifierArray(assessment, "review_reasons", 0, 16);
    if ("NEEDS_REVIEW".equals(assessmentStatus) && reviewReasons.isEmpty()) {
      throw rejected("EVIDENCE_REVIEW_REASON_REQUIRED");
    }
    ArrayNode factLinks = requiredArray(assessment, "candidate_fact_links");
    requireCardinality(factLinks, 0, 64, "candidate fact links");
    for (JsonNode candidate : factLinks) {
      ObjectNode link = requiredObject(candidate, "item fact link");
      requireExactFields(link, ITEM_FACT_LINK_FIELDS, "item fact link");
      requirePattern(link, "fact_id", FACT_ID, "fact id");
      validateIdentifierArray(link, "source_refs", 1, 16);
    }
    List<String> modalities =
        textList(assessment.required("inspected_modalities"), "inspected modalities");
    requireCardinality(modalities, 0, 4, "inspected modalities");
    requireUnique(modalities, "inspected modalities");
    if (!Set.of("TEXT", "IMAGE_PIXELS", "PDF_METADATA", "OCR").containsAll(modalities)) {
      throw rejected("EVIDENCE_INSPECTED_MODALITY_INVALID");
    }
    String loadStatus = requiredText(assessment, "asset_load_status");
    if (!Set.of("NOT_REQUIRED", "LOADED", "METADATA_ONLY", "REJECTED").contains(loadStatus)) {
      throw rejected("EVIDENCE_ASSET_LOAD_STATE_INVALID");
    }
    JsonNode receiptRef = assessment.required("asset_load_receipt_ref");
    JsonNode receiptHash = assessment.required("asset_load_receipt_hash");
    if ("LOADED".equals(loadStatus)) {
      if (!receiptRef.isTextual() || !receiptHash.isTextual() || modalities.isEmpty()) {
        throw rejected("EVIDENCE_ACTUAL_LOAD_RECEIPT_REQUIRED");
      }
      requirePattern(receiptRef.textValue(), IDENTIFIER, "load receipt ref");
      hash(receiptHash.textValue(), "assetLoadReceiptHash");
    } else if (!receiptRef.isNull() || !receiptHash.isNull()) {
      throw rejected("EVIDENCE_ASSET_LOAD_STATE_INVALID");
    }
    if (("NOT_REQUIRED".equals(loadStatus) || "REJECTED".equals(loadStatus))
        && !modalities.isEmpty()) {
      throw rejected("EVIDENCE_UNLOADED_ASSET_CLAIMS_INSPECTION");
    }
    if ("METADATA_ONLY".equals(loadStatus)
        && modalities.stream().anyMatch(value -> !"PDF_METADATA".equals(value))) {
      throw rejected("EVIDENCE_METADATA_ONLY_MODALITY_INVALID");
    }
  }

  private static void validateTerminalProposal(
      FinalizationRequest request, AuthoritySnapshot snapshot) {
    ObjectNode proposal = requiredObject(request.terminalProposal(), "terminal proposal");
    requireExactFields(proposal, TERMINAL_FIELDS, "terminal proposal");
    requireEncodedSize(proposal, 262_144, "terminal proposal");
    requireEqual(
        requiredText(proposal, "schema_version"),
        EvidenceBatchManifest.TERMINAL_OUTPUT_SCHEMA_VERSION,
        "terminal schema");
    requireEqual(
        requiredText(proposal, "execution_scope"), "SIGNED_SYNTHETIC_ONLY", "execution scope");
    requireEqual(requiredText(proposal, "writer_mode"), "PROPOSAL_ONLY", "writer mode");
    requireFalse(proposal, "formal_sink_eligible");
    requireCanonicalSelfHash(proposal, "proposal_hash", "terminal proposal");
    for (String field : List.of("command_id", "logical_run_id", "attempt_id", "manifest_id")) {
      requirePattern(proposal, field, IDENTIFIER, "identifier");
    }
    requirePattern(proposal, "thread_id", THREAD_ID, "thread id");
    requirePattern(proposal, "manifest_hash", Pattern.compile("^[0-9a-f]{64}$"), "hash");
    requireDateTime(proposal, "completed_at");
    requireEqual(
        requiredText(proposal, "command_id"), request.command().commandId(), "proposal command");
    requireEqual(
        requiredText(proposal, "logical_run_id"),
        request.command().logicalRunId(),
        "proposal logical run");
    requireEqual(
        requiredText(proposal, "attempt_id"), request.command().attemptId(), "proposal attempt");
    requireEqual(
        requiredText(proposal, "thread_id"), request.binding().threadId(), "proposal thread");
    requireEqual(
        requiredText(proposal, "manifest_id"),
        request.manifest().manifestId(),
        "proposal manifest id");
    requireEqual(
        requiredText(proposal, "manifest_hash"),
        request.manifest().manifestHash(),
        "proposal manifest hash");
    long itemCount = requiredSafeLong(proposal, "item_count", 1);
    if (!Set.of(1L, 8L, 100L).contains(itemCount)) {
      throw rejected("EVIDENCE_TERMINAL_ITEM_COUNT_NOT_ADMITTED");
    }
    requireEqual(itemCount, (long) request.manifest().orderedItemKeys().size(), "item count");
    List<String> orderedKeys =
        textList(proposal.required("ordered_item_keys"), "ordered_item_keys");
    requireCardinality(orderedKeys, 1, 100, "ordered item keys");
    orderedKeys.forEach(value -> requirePattern(value, EVIDENCE_ID, "evidence id"));
    if (new HashSet<>(orderedKeys).size() != orderedKeys.size()) {
      throw rejected("EVIDENCE_DUPLICATE_ORDERED_ITEM_KEY");
    }
    requireEqual(orderedKeys, request.manifest().orderedItemKeys(), "ordered item keys");
    requireEqual(requiredText(proposal, "coverage_status"), "COMPLETE", "coverage status");
    requireProfilePins(proposal.required("profile_versions"), request.manifest().profileVersions());

    Map<String, AssessmentRef> references = new LinkedHashMap<>();
    ArrayNode assessmentRefs = requiredArray(proposal, "assessment_refs");
    requireCardinality(assessmentRefs, 1, 100, "assessment refs");
    requireEqual(assessmentRefs.size(), Math.toIntExact(itemCount), "assessment ref count");
    for (JsonNode candidate : assessmentRefs) {
      ObjectNode ref = requiredObject(candidate, "assessment ref");
      requireExactFields(ref, ASSESSMENT_REF_FIELDS, "assessment ref");
      String evidenceId = requiredText(ref, "evidence_id");
      requirePattern(evidenceId, EVIDENCE_ID, "assessment evidence id");
      String status = requiredText(ref, "assessment_status");
      if (!Set.of("COMPLETED", "NEEDS_REVIEW").contains(status)) {
        throw rejected("EVIDENCE_ASSESSMENT_STATUS_INVALID");
      }
      AssessmentRef previous =
          references.put(
              evidenceId, new AssessmentRef(status, requiredHash(ref, "assessment_hash")));
      if (previous != null) {
        throw rejected("EVIDENCE_DUPLICATE_ASSESSMENT_REF");
      }
    }
    requireEqual(
        new ArrayList<>(references.keySet()),
        request.manifest().orderedItemKeys(),
        "assessment ref coverage");
    ArrayNode proposedFactLinks = requiredArray(proposal, "proposed_fact_links");
    requireCardinality(proposedFactLinks, 0, 100, "proposed fact links");
    validateTerminalFactLinks(
        proposedFactLinks,
        request.manifest().orderedItemKeys(),
        snapshot.currentFactIds(),
        snapshot.currentSourceRefs());
    ArrayNode reviewItems = requiredArray(proposal, "proposed_review_items");
    requireCardinality(reviewItems, 0, 100, "proposed review items");
    Map<String, Set<String>> assessmentReviewReasons = new HashMap<>();
    for (JsonNode candidate : request.itemAssessments()) {
      ObjectNode assessment = requiredObject(candidate, "item assessment");
      assessmentReviewReasons.put(
          requiredText(assessment, "evidence_id"),
          textSet(assessment.required("review_reasons"), "review reasons"));
    }
    validateReviewItems(
        reviewItems, request.manifest().orderedItemKeys(), references, assessmentReviewReasons);
  }

  private List<ActualLoadRequirement> validateAssessmentsAndLoads(
      FinalizationRequest request, AuthoritySnapshot snapshot) {
    ObjectNode terminal = requiredObject(request.terminalProposal(), "terminal proposal");
    Map<String, AssessmentRef> refs = new HashMap<>();
    for (JsonNode candidate : requiredArray(terminal, "assessment_refs")) {
      ObjectNode ref = requiredObject(candidate, "assessment ref");
      refs.put(
          requiredText(ref, "evidence_id"),
          new AssessmentRef(
              requiredText(ref, "assessment_status"), requiredHash(ref, "assessment_hash")));
    }

    Set<String> consumedLoads = new HashSet<>();
    List<ActualLoadRequirement> loadRequirements = new ArrayList<>();
    List<String> assessmentOrder = new ArrayList<>();
    Set<String> assessmentHashes = new HashSet<>();
    for (JsonNode candidate : request.itemAssessments()) {
      ObjectNode assessment = requiredObject(candidate, "item assessment");
      requireExactFields(assessment, ASSESSMENT_FIELDS, "item assessment");
      requireEqual(
          requiredText(assessment, "schema_version"),
          EvidenceBatchManifest.ASSESSMENT_OUTPUT_SCHEMA_VERSION,
          "assessment schema");
      requireEqual(
          requiredText(assessment, "execution_scope"),
          "SIGNED_SYNTHETIC_ONLY",
          "assessment execution scope");
      requireFalse(assessment, "formal_sink_eligible");
      requireCanonicalSelfHash(assessment, "assessment_hash", "item assessment");
      String assessmentHash = requiredHash(assessment, "assessment_hash");
      if (!assessmentHashes.add(assessmentHash)) {
        throw rejected("EVIDENCE_CONFLICTING_ASSESSMENT_HASH");
      }
      String evidenceId = requiredText(assessment, "evidence_id");
      assessmentOrder.add(evidenceId);
      ObjectNode manifestItem = request.manifest().requireItem(evidenceId);
      AssessmentRef ref = refs.get(evidenceId);
      if (ref == null) {
        throw rejected("EVIDENCE_FOREIGN_ITEM_ASSESSMENT");
      }
      requireEqual(assessmentHash, ref.assessmentHash(), "assessment reference hash");
      requireEqual(
          requiredText(assessment, "assessment_status"),
          ref.status(),
          "assessment reference status");
      requireAssessmentAuthority(request, assessment, manifestItem);
      requireProfilePins(
          assessment.required("profile_versions"), request.manifest().profileVersions());
      validateScoresAndReasons(assessment);
      validateItemFactLinks(
          requiredArray(assessment, "candidate_fact_links"),
          snapshot.currentFactIds(),
          snapshot.currentSourceRefs());
      requireAllowedReferences(
          textSet(assessment.required("source_refs"), "source_refs"),
          snapshot.currentSourceRefs(),
          "assessment source");
      validateLoadReceipt(
          request, assessment, manifestItem, snapshot, consumedLoads, loadRequirements);
    }
    requireEqual(assessmentOrder, request.manifest().orderedItemKeys(), "assessment coverage");
    return loadRequirements.stream()
        .sorted(java.util.Comparator.comparing(ActualLoadRequirement::evidenceId))
        .toList();
  }

  private static void requireAssessmentAuthority(
      FinalizationRequest request, ObjectNode assessment, ObjectNode manifestItem) {
    requireEqual(
        requiredText(assessment, "command_id"),
        request.command().commandId(),
        "assessment command");
    requireEqual(
        requiredText(assessment, "logical_run_id"),
        request.command().logicalRunId(),
        "assessment logical run");
    requireEqual(
        requiredText(assessment, "attempt_id"),
        request.command().attemptId(),
        "assessment attempt");
    requireEqual(
        requiredText(assessment, "thread_id"), request.binding().threadId(), "assessment thread");
    requireEqual(
        requiredText(assessment, "manifest_id"),
        request.manifest().manifestId(),
        "assessment manifest id");
    requireEqual(
        requiredText(assessment, "manifest_hash"),
        request.manifest().manifestHash(),
        "assessment manifest hash");
    requireEqual(
        requiredText(assessment, "item_hash"),
        requiredText(manifestItem, "item_hash"),
        "assessment item hash");
    requireEqual(
        requiredLong(assessment, "formal_evidence_revision"),
        requiredLong(manifestItem, "formal_evidence_revision"),
        "formal Evidence revision");
    requireEqual(
        requiredText(assessment, "actor_scope_hash"),
        request.currentAuthority().actorScopeHash(),
        "assessment actor scope");
  }

  private static void validateScoresAndReasons(ObjectNode assessment) {
    for (String field :
        List.of("authenticity_score", "relevance_score", "completeness_score", "confidence")) {
      JsonNode value = assessment.required(field);
      if (!value.isNumber() || value.doubleValue() < 0 || value.doubleValue() > 1) {
        throw rejected("EVIDENCE_SCORE_OUT_OF_RANGE:" + field);
      }
    }
    Set<String> authenticity =
        textSet(assessment.required("authenticity_reason_codes"), "authenticity reasons");
    Set<String> relevance =
        textSet(assessment.required("relevance_reason_codes"), "relevance reasons");
    if (authenticity.stream().anyMatch(EvidenceGraphResultFinalizer::isRelevanceReason)
        || relevance.stream().anyMatch(EvidenceGraphResultFinalizer::isForgeryReason)) {
      throw rejected("EVIDENCE_AUTHENTICITY_RELEVANCE_CONFLATED");
    }
    String status = requiredText(assessment, "assessment_status");
    Set<String> reviewReasons = textSet(assessment.required("review_reasons"), "review reasons");
    if ("NEEDS_REVIEW".equals(status) && reviewReasons.isEmpty()) {
      throw rejected("EVIDENCE_REVIEW_REASON_REQUIRED");
    }
    if ("COMPLETED".equals(status) && !reviewReasons.isEmpty()) {
      throw rejected("EVIDENCE_COMPLETED_REVIEW_REASON_FORBIDDEN");
    }
    if (assessment.required("relevance_score").doubleValue() < 0.50
        && (!"NEEDS_REVIEW".equals(status) || !reviewReasons.contains("LOW_RELEVANCE_SCORE"))) {
      throw rejected("EVIDENCE_LOW_RELEVANCE_REVIEW_REQUIRED");
    }
    if (assessment.required("authenticity_score").doubleValue() < 0.50
        && (!"NEEDS_REVIEW".equals(status)
            || !reviewReasons.contains("LOW_AUTHENTICITY_SUSPECTED_FORGERY"))) {
      throw rejected("EVIDENCE_LOW_AUTHENTICITY_REVIEW_REQUIRED");
    }
    textSet(assessment.required("limitations"), "limitations");
  }

  private static boolean isRelevanceReason(String code) {
    String normalized = code.toUpperCase();
    return normalized.contains("RELEVAN") || normalized.contains("IRRELEVAN");
  }

  private static boolean isForgeryReason(String code) {
    String normalized = code.toUpperCase();
    return normalized.contains("FORGER")
        || normalized.contains("FABRICAT")
        || normalized.contains("TAMPER")
        || normalized.contains("AUTHENTIC");
  }

  private void validateLoadReceipt(
      FinalizationRequest request,
      ObjectNode assessment,
      ObjectNode manifestItem,
      AuthoritySnapshot snapshot,
      Set<String> consumedLoads,
      List<ActualLoadRequirement> loadRequirements) {
    String status = requiredText(assessment, "asset_load_status");
    Set<String> inspected =
        textSet(assessment.required("inspected_modalities"), "inspected modalities");
    JsonNode receiptRefNode = assessment.required("asset_load_receipt_ref");
    JsonNode receiptHashNode = assessment.required("asset_load_receipt_hash");
    if (!"LOADED".equals(status)) {
      if (!Set.of("NOT_REQUIRED", "METADATA_ONLY", "REJECTED").contains(status)
          || !receiptRefNode.isNull()
          || !receiptHashNode.isNull()) {
        throw rejected("EVIDENCE_ASSET_LOAD_STATE_INVALID");
      }
      if (("NOT_REQUIRED".equals(status) || "REJECTED".equals(status)) && !inspected.isEmpty()) {
        throw rejected("EVIDENCE_UNLOADED_ASSET_CLAIMS_INSPECTION");
      }
      if ("METADATA_ONLY".equals(status)
          && inspected.stream().anyMatch(value -> !"PDF_METADATA".equals(value))) {
        throw rejected("EVIDENCE_METADATA_ONLY_MODALITY_INVALID");
      }
      return;
    }
    if (!receiptRefNode.isTextual() || !receiptHashNode.isTextual() || inspected.isEmpty()) {
      throw rejected("EVIDENCE_ACTUAL_LOAD_RECEIPT_REQUIRED");
    }
    LoadReceiptLookup lookup =
        new LoadReceiptLookup(
            snapshot.snapshotHash(),
            request.currentAuthority().tenantSurrogate(),
            request.currentAuthority().caseId(),
            request.currentAuthority().roomEpoch(),
            request.currentAuthority().javaRoomFencingToken(),
            request.manifest().manifestId(),
            request.manifest().manifestHash(),
            requiredText(manifestItem, "evidence_id"),
            requiredText(manifestItem, "item_hash"),
            receiptRefNode.textValue(),
            receiptHashNode.textValue());
    ActualLoadReceipt receipt =
        authorityResolver
            .findActualLoadReceipt(lookup)
            .orElseThrow(() -> rejected("EVIDENCE_ACTUAL_LOAD_RECEIPT_NOT_AUTHORIZED"));
    if (!receipt.receiptId().equals(receiptRefNode.textValue())
        || !receipt.receiptHash().equals(receiptHashNode.textValue())) {
      throw rejected("EVIDENCE_ACTUAL_LOAD_RECEIPT_MISMATCH");
    }
    for (String identifier :
        List.of(
            receipt.receiptId(),
            receipt.capabilityId(),
            receipt.capabilityNonce(),
            receipt.manifestId())) {
      requirePattern(identifier, IDENTIFIER, "load receipt identifier");
    }
    requirePattern(receipt.evidenceId(), EVIDENCE_ID, "load receipt evidence id");
    for (String digest :
        List.of(
            receipt.receiptHash(),
            receipt.capabilityHash(),
            receipt.manifestHash(),
            receipt.itemHash(),
            receipt.objectSha256())) {
      hash(digest, "actualLoadReceiptHash");
    }
    if (!consumedLoads.add(receipt.receiptId())) {
      throw rejected("EVIDENCE_ACTUAL_LOAD_RECEIPT_REUSED");
    }
    requireEqual(receipt.manifestId(), request.manifest().manifestId(), "load manifest id");
    requireEqual(receipt.manifestHash(), request.manifest().manifestHash(), "load manifest hash");
    requireEqual(
        receipt.evidenceId(), requiredText(manifestItem, "evidence_id"), "load evidence id");
    requireEqual(receipt.itemHash(), requiredText(manifestItem, "item_hash"), "load item hash");
    requireEqual(receipt.objectRef(), requiredText(manifestItem, "object_ref"), "load object ref");
    requireEqual(
        receipt.immutableObjectVersion(),
        requiredText(manifestItem, "immutable_object_version"),
        "load immutable object version");
    requireEqual(
        receipt.objectSha256(), requiredText(manifestItem, "object_sha256"), "load object hash");
    requireEqual(
        receipt.contentType(), requiredText(manifestItem, "content_type"), "load content type");
    requireEqual(receipt.byteSize(), requiredLong(manifestItem, "byte_size"), "load byte size");
    requireEqual(
        receipt.javaRoomFencingToken(),
        request.currentAuthority().javaRoomFencingToken(),
        "load Java room fence");
    if (receipt.graphLeaseFencingToken() == receipt.javaRoomFencingToken()) {
      throw rejected("EVIDENCE_ROOM_AND_GRAPH_FENCES_CONFLATED");
    }
    Set<String> permitted =
        textSet(manifestItem.required("permitted_modalities"), "permitted modalities");
    if (receipt.loadedModalities().size() > 4
        || receipt.loadedModalities().size()
            != receipt.loadedModalities().stream().distinct().count()) {
      throw rejected("EVIDENCE_ACTUAL_LOAD_MODALITIES_INVALID");
    }
    if (!permitted.containsAll(receipt.loadedModalities())
        || !new HashSet<>(receipt.loadedModalities()).containsAll(inspected)) {
      throw rejected("EVIDENCE_INSPECTED_MODALITY_NOT_ACTUALLY_LOADED");
    }
    loadRequirements.add(
        new ActualLoadRequirement(
            receipt.evidenceId(),
            receipt.itemHash(),
            receipt.receiptId(),
            receipt.receiptHash(),
            receipt.manifestHash(),
            receipt.javaRoomFencingToken()));
  }

  private static void validateTerminalFactLinks(
      ArrayNode links,
      List<String> manifestItems,
      Set<String> currentFactIds,
      Set<String> currentSourceRefs) {
    Set<String> manifestSet = Set.copyOf(manifestItems);
    Set<String> seen = new HashSet<>();
    for (JsonNode candidate : links) {
      ObjectNode link = requiredObject(candidate, "terminal fact link");
      requireExactFields(link, FACT_LINK_FIELDS, "terminal fact link");
      String factId = requiredText(link, "fact_id");
      if (!seen.add(factId)) {
        throw rejected("EVIDENCE_DUPLICATE_FACT_LINK");
      }
      requireAllowedReferences(
          textSet(link.required("evidence_ids"), "fact evidence ids"),
          manifestSet,
          "fact evidence");
      requireAllowedReferences(Set.of(factId), currentFactIds, "fact");
      requireAllowedReferences(
          textSet(link.required("source_refs"), "fact source refs"),
          currentSourceRefs,
          "fact source");
    }
  }

  private static void validateItemFactLinks(
      ArrayNode links, Set<String> currentFactIds, Set<String> currentSourceRefs) {
    Set<String> seen = new HashSet<>();
    for (JsonNode candidate : links) {
      ObjectNode link = requiredObject(candidate, "item fact link");
      requireExactFields(link, ITEM_FACT_LINK_FIELDS, "item fact link");
      String factId = requiredText(link, "fact_id");
      if (!seen.add(factId)) {
        throw rejected("EVIDENCE_DUPLICATE_ITEM_FACT_LINK");
      }
      requireAllowedReferences(Set.of(factId), currentFactIds, "item fact");
      requireAllowedReferences(
          textSet(link.required("source_refs"), "item fact source refs"),
          currentSourceRefs,
          "item fact source");
    }
  }

  private static void validateReviewItems(
      ArrayNode reviews,
      List<String> manifestItems,
      Map<String, AssessmentRef> assessmentRefs,
      Map<String, Set<String>> assessmentReviewReasons) {
    Set<String> manifestSet = Set.copyOf(manifestItems);
    Set<String> reviewKeys = new HashSet<>();
    Set<String> reviewedEvidence = new HashSet<>();
    for (JsonNode candidate : reviews) {
      ObjectNode review = requiredObject(candidate, "review item");
      requireExactFields(review, REVIEW_FIELDS, "review item");
      if (!reviewKeys.add(requiredText(review, "review_key"))) {
        throw rejected("EVIDENCE_DUPLICATE_REVIEW_KEY");
      }
      String evidenceId = requiredText(review, "evidence_id");
      if (!reviewedEvidence.add(evidenceId)) {
        throw rejected("EVIDENCE_DUPLICATE_REVIEW_EVIDENCE");
      }
      requireAllowedReferences(Set.of(evidenceId), manifestSet, "review evidence");
      Set<String> reasons = textSet(review.required("reason_codes"), "review reasons");
      if (reasons.isEmpty()) {
        throw rejected("EVIDENCE_REVIEW_ITEM_INVALID");
      }
      AssessmentRef assessmentRef = assessmentRefs.get(evidenceId);
      if (assessmentRef == null || !"NEEDS_REVIEW".equals(assessmentRef.status())) {
        throw rejected("EVIDENCE_COMPLETED_ITEM_REVIEW_FORBIDDEN");
      }
      requireEqual(
          reasons,
          assessmentReviewReasons.getOrDefault(evidenceId, Set.of()),
          "review reason linkage");
      requireEqual(
          requiredText(review, "priority"), reviewPriority(reasons), "review priority policy");
    }
    Set<String> requiredReviews =
        assessmentRefs.entrySet().stream()
            .filter(entry -> "NEEDS_REVIEW".equals(entry.getValue().status()))
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
    requireEqual(reviewedEvidence, requiredReviews, "review item coverage");
  }

  private static String reviewPriority(Set<String> reasons) {
    // A1's frozen reducer emits MEDIUM for every bounded NEEDS_REVIEW proposal.
    return "MEDIUM";
  }

  private static void validateReceipt(
      FinalizationRequest request,
      BatchMergeBinding expectedBinding,
      EvidenceFinalizationReceipt receipt) {
    requireEqual(
        receipt.operationType(),
        EvidenceFinalizationReceipt.OperationType.BATCH_MERGE,
        "receipt operation");
    requireEqual(
        receipt.tenantSurrogate(), request.currentAuthority().tenantSurrogate(), "receipt tenant");
    requireEqual(receipt.caseId(), request.currentAuthority().caseId(), "receipt case");
    requireEqual(receipt.roomEpoch(), request.currentAuthority().roomEpoch(), "receipt room epoch");
    requireEqual(
        receipt.fencingToken(),
        request.currentAuthority().javaRoomFencingToken(),
        "receipt Java room fence");
    requireEqual(
        receipt.sourceRevision(),
        request.currentAuthority().sourceRevision(),
        "receipt source revision");
    requireEqual(
        receipt.processRevision(),
        request.currentAuthority().processRevision(),
        "receipt process revision");
    requireEqual(
        receipt.roomRevision(), request.currentAuthority().roomRevision(), "receipt room revision");
    requireEqual(receipt.resultHash(), request.resultHash(), "receipt result hash");
    requireEqual(receipt.operationBinding(), expectedBinding, "receipt operation binding");
    if (!EvidenceFinalizationReceipt.ISOLATED_SYNTHETIC_LEDGER.equals(receipt.commitScope())
        || receipt.formalDomainWrite()
        || receipt.formalSinkEligible()
        || receipt.mergeCount() != 0
        || !receipt.domainEventIds().isEmpty()
        || !receipt.outboxIds().isEmpty()
        || receipt.hearingOpened()) {
      throw rejected("EVIDENCE_RECEIPT_FORMAL_EFFECT_FORBIDDEN");
    }
  }

  public static FinalizationRequest createRequest(
      String resultHash,
      EvidenceBatchManifest manifest,
      EvidenceGraphBinding binding,
      EvidenceGraphCommandFactory.CurrentAuthority currentAuthority,
      RoomGraphCommand command,
      JsonNode terminalProposal,
      List<JsonNode> itemAssessments,
      AuthoritySnapshot authoritySnapshot) {
    Objects.requireNonNull(authoritySnapshot, "authoritySnapshot");
    FinalizationRequest unsigned =
        new FinalizationRequest(
            "0".repeat(64),
            resultHash,
            authoritySnapshot.snapshotHash(),
            manifest,
            binding,
            currentAuthority,
            command,
            terminalProposal,
            itemAssessments);
    return new FinalizationRequest(
        canonicalRequestHash(unsigned),
        resultHash,
        authoritySnapshot.snapshotHash(),
        manifest,
        binding,
        currentAuthority,
        command,
        terminalProposal,
        itemAssessments);
  }

  public static String canonicalRequestHash(FinalizationRequest request) {
    ObjectNode value = JsonNodeFactory.instance.objectNode();
    value.put("result_hash", request.resultHash());
    value.put("authority_snapshot_hash", request.authoritySnapshotHash());
    value.put("binding_hash", request.binding().bindingHash());
    value.put("manifest_hash", request.manifest().manifestHash());
    value.put("manifest_payload_sha256", request.manifest().payloadSha256());
    value.set("current_authority", MAPPER.valueToTree(request.currentAuthority()));
    value.put("command_request_hash", request.command().requestHash());
    value.put("proposal_hash", requiredHash(request.terminalProposal(), "proposal_hash"));
    ArrayNode assessments = value.putArray("assessment_hashes");
    request
        .itemAssessments()
        .forEach(item -> assessments.add(requiredHash(item, "assessment_hash")));
    return ContractJson.sha256Hex(value);
  }

  public record FinalizationRequest(
      String requestHash,
      String resultHash,
      String authoritySnapshotHash,
      EvidenceBatchManifest manifest,
      EvidenceGraphBinding binding,
      EvidenceGraphCommandFactory.CurrentAuthority currentAuthority,
      RoomGraphCommand command,
      JsonNode terminalProposal,
      List<JsonNode> itemAssessments) {
    public FinalizationRequest {
      hash(requestHash, "requestHash");
      hash(resultHash, "resultHash");
      hash(authoritySnapshotHash, "authoritySnapshotHash");
      Objects.requireNonNull(manifest, "manifest");
      Objects.requireNonNull(binding, "binding");
      Objects.requireNonNull(currentAuthority, "currentAuthority");
      Objects.requireNonNull(command, "command");
      terminalProposal = Objects.requireNonNull(terminalProposal, "terminalProposal").deepCopy();
      itemAssessments = itemAssessments.stream().map(item -> (JsonNode) item.deepCopy()).toList();
    }

    @Override
    public JsonNode terminalProposal() {
      return terminalProposal.deepCopy();
    }

    @Override
    public List<JsonNode> itemAssessments() {
      return itemAssessments.stream().map(item -> (JsonNode) item.deepCopy()).toList();
    }
  }

  /** Trusted Java authority and reference snapshot used for one finalization attempt. */
  public record AuthoritySnapshot(
      EvidenceGraphCommandFactory.CurrentAuthority currentAuthority,
      String agentProfileId,
      Set<String> currentFactIds,
      Set<String> currentSourceRefs,
      String snapshotHash) {
    public AuthoritySnapshot {
      Objects.requireNonNull(currentAuthority, "currentAuthority");
      requirePattern(agentProfileId, IDENTIFIER, "agent profile id");
      currentFactIds = Set.copyOf(currentFactIds);
      currentSourceRefs = Set.copyOf(currentSourceRefs);
      currentFactIds.forEach(value -> requirePattern(value, FACT_ID, "authority fact id"));
      currentSourceRefs.forEach(value -> requirePattern(value, IDENTIFIER, "authority source ref"));
      hash(snapshotHash, "snapshotHash");
      if (!snapshotHash.equals(
          canonicalSnapshotHash(
              currentAuthority, agentProfileId, currentFactIds, currentSourceRefs))) {
        throw new IllegalArgumentException("snapshotHash is not canonical");
      }
    }

    public static AuthoritySnapshot create(
        EvidenceGraphCommandFactory.CurrentAuthority currentAuthority,
        String agentProfileId,
        Set<String> currentFactIds,
        Set<String> currentSourceRefs) {
      Set<String> facts = Set.copyOf(currentFactIds);
      Set<String> sources = Set.copyOf(currentSourceRefs);
      return new AuthoritySnapshot(
          currentAuthority,
          agentProfileId,
          facts,
          sources,
          canonicalSnapshotHash(currentAuthority, agentProfileId, facts, sources));
    }

    private ObjectNode toHashJson() {
      ObjectNode value =
          snapshotPreimage(currentAuthority, agentProfileId, currentFactIds, currentSourceRefs);
      value.put("snapshot_hash", snapshotHash);
      return value;
    }

    private EvidenceFinalizationLedger.AuthorityRequirement toCommitRequirement(
        List<ActualLoadRequirement> loadRequirements) {
      EvidenceGraphCommandFactory.CurrentAuthority current = currentAuthority;
      return new EvidenceFinalizationLedger.AuthorityRequirement(
          snapshotHash,
          current.runtimeMode().name(),
          agentProfileId,
          current.tenantSurrogate(),
          current.caseId(),
          current.roomId(),
          current.roomEpoch(),
          current.javaRoomFencingToken(),
          current.actorId(),
          current.actorRole(),
          current.participantId(),
          current.actorScopeHash(),
          current.agentSessionId(),
          current.sourceRevision(),
          current.processRevision(),
          current.roomRevision(),
          new TreeSet<>(currentFactIds).stream().toList(),
          new TreeSet<>(currentSourceRefs).stream().toList(),
          loadRequirements);
    }
  }

  public interface AuthorityResolver {
    AuthoritySnapshot resolve(AuthorityLookup lookup);

    Optional<ActualLoadReceipt> findActualLoadReceipt(LoadReceiptLookup lookup);
  }

  public record AuthorityLookup(
      String expectedSnapshotHash, EvidenceGraphCommandFactory.CurrentAuthority expectedAuthority) {
    public AuthorityLookup {
      hash(expectedSnapshotHash, "expectedSnapshotHash");
      Objects.requireNonNull(expectedAuthority, "expectedAuthority");
    }
  }

  public record LoadReceiptLookup(
      String authoritySnapshotHash,
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      long javaRoomFencingToken,
      String manifestId,
      String manifestHash,
      String evidenceId,
      String itemHash,
      String receiptId,
      String receiptHash) {
    public LoadReceiptLookup {
      hash(authoritySnapshotHash, "authoritySnapshotHash");
      requirePattern(tenantSurrogate, IDENTIFIER, "tenant surrogate");
      requirePattern(caseId, IDENTIFIER, "case id");
      requirePattern(manifestId, IDENTIFIER, "manifest id");
      hash(manifestHash, "manifestHash");
      requirePattern(evidenceId, EVIDENCE_ID, "evidence id");
      hash(itemHash, "itemHash");
      requirePattern(receiptId, IDENTIFIER, "receipt id");
      hash(receiptHash, "receiptHash");
      if (roomEpoch < 0
          || roomEpoch > MAX_SAFE_INTEGER
          || javaRoomFencingToken < 1
          || javaRoomFencingToken > MAX_SAFE_INTEGER) {
        throw new IllegalArgumentException("load lookup epoch or fence is invalid");
      }
    }
  }

  private static void validateAuthoritySnapshot(
      FinalizationRequest request, AuthoritySnapshot snapshot) {
    requireEqual(
        snapshot.snapshotHash(), request.authoritySnapshotHash(), "authority snapshot hash");
    requireEqual(
        snapshot.currentAuthority(), request.currentAuthority(), "trusted current authority");
    EvidenceGraphCommandFactory.requireCurrentAuthority(
        request.manifest(), request.binding(), snapshot.currentAuthority(), false);
    requireEqual(
        request.command().invocationContext().agentProfileId(),
        snapshot.agentProfileId(),
        "trusted agent profile");
  }

  private static String canonicalSnapshotHash(
      EvidenceGraphCommandFactory.CurrentAuthority currentAuthority,
      String agentProfileId,
      Set<String> currentFactIds,
      Set<String> currentSourceRefs) {
    return ContractJson.sha256Hex(
        snapshotPreimage(currentAuthority, agentProfileId, currentFactIds, currentSourceRefs));
  }

  private static ObjectNode snapshotPreimage(
      EvidenceGraphCommandFactory.CurrentAuthority currentAuthority,
      String agentProfileId,
      Set<String> currentFactIds,
      Set<String> currentSourceRefs) {
    ObjectNode value = JsonNodeFactory.instance.objectNode();
    value.set("current_authority", MAPPER.valueToTree(currentAuthority));
    value.put("agent_profile_id", agentProfileId);
    ArrayNode facts = value.putArray("current_fact_ids");
    new TreeSet<>(currentFactIds).forEach(facts::add);
    ArrayNode sources = value.putArray("current_source_refs");
    new TreeSet<>(currentSourceRefs).forEach(sources::add);
    return value;
  }

  private record AssessmentRef(String status, String assessmentHash) {}

  private static void requireProfilePins(JsonNode actual, ObjectNode expected) {
    ObjectNode actualObject = requiredObject(actual, "profile versions");
    requireExactFields(actualObject, PROFILE_FIELDS, "profile versions");
    PROFILE_FIELDS.forEach(
        field -> requirePattern(actualObject, field, IDENTIFIER, "profile version"));
    if (!actualObject.equals(expected)) {
      throw rejected("EVIDENCE_PROFILE_VERSION_PIN_MISMATCH");
    }
    requireEqual(
        requiredText(actualObject, "state_schema_version"),
        "evidence-graph-state.v2",
        "state schema pin");
    requireEqual(
        requiredText(actualObject, "assessment_output_schema_version"),
        EvidenceBatchManifest.ASSESSMENT_OUTPUT_SCHEMA_VERSION,
        "assessment schema pin");
    requireEqual(
        requiredText(actualObject, "terminal_output_schema_version"),
        EvidenceBatchManifest.TERMINAL_OUTPUT_SCHEMA_VERSION,
        "terminal schema pin");
  }

  private static void requireCanonicalSelfHash(ObjectNode value, String field, String kind) {
    String expected = requiredHash(value, field);
    ObjectNode preimage = value.deepCopy();
    preimage.remove(field);
    if (!expected.equals(ContractJson.sha256Hex(preimage))) {
      throw rejected("EVIDENCE_" + kind.toUpperCase().replace(' ', '_') + "_HASH_MISMATCH");
    }
  }

  private static void requireExactFields(ObjectNode value, Set<String> fields, String kind) {
    Set<String> actual = new HashSet<>();
    value.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(fields)) {
      throw rejected("EVIDENCE_" + kind.toUpperCase().replace(' ', '_') + "_FIELDS_INVALID");
    }
  }

  private static void requireAllowedReferences(
      Set<String> actual, Set<String> allowed, String kind) {
    if (actual.isEmpty() || !allowed.containsAll(actual)) {
      throw rejected("EVIDENCE_FOREIGN_" + kind.toUpperCase().replace(' ', '_') + "_REFERENCE");
    }
  }

  private static List<String> validateIdentifierArray(
      JsonNode value, String field, int minimum, int maximum) {
    List<String> values = textList(value.required(field), field);
    requireCardinality(values, minimum, maximum, field);
    values.forEach(item -> requirePattern(item, IDENTIFIER, field));
    requireUnique(values, field);
    return values;
  }

  private static void requireEncodedSize(JsonNode value, int maximum, String kind) {
    if (ContractJson.canonicalize(value).length > maximum) {
      throw rejected("EVIDENCE_" + kind.toUpperCase().replace(' ', '_') + "_TOO_LARGE");
    }
  }

  private static void requireDateTime(JsonNode value, String field) {
    String timestamp = requiredText(value, field);
    try {
      OffsetDateTime.parse(timestamp);
    } catch (DateTimeParseException failure) {
      throw rejected("EVIDENCE_" + field.toUpperCase() + "_INVALID");
    }
  }

  private static long requiredSafeLong(JsonNode value, String field, long minimum) {
    long result = requiredLong(value, field);
    if (result < minimum || result > MAX_SAFE_INTEGER) {
      throw rejected("EVIDENCE_" + field.toUpperCase() + "_OUT_OF_RANGE");
    }
    return result;
  }

  private static void requirePattern(JsonNode value, String field, Pattern pattern, String kind) {
    requirePattern(requiredText(value, field), pattern, kind);
  }

  private static void requirePattern(String value, Pattern pattern, String kind) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw rejected("EVIDENCE_" + kind.toUpperCase().replace(' ', '_') + "_INVALID");
    }
  }

  private static void requireCardinality(
      java.util.Collection<?> values, int minimum, int maximum, String kind) {
    if (values.size() < minimum || values.size() > maximum) {
      throw rejected("EVIDENCE_" + kind.toUpperCase().replace(' ', '_') + "_COUNT_INVALID");
    }
  }

  private static void requireCardinality(ArrayNode values, int minimum, int maximum, String kind) {
    if (values.size() < minimum || values.size() > maximum) {
      throw rejected("EVIDENCE_" + kind.toUpperCase().replace(' ', '_') + "_COUNT_INVALID");
    }
  }

  private static void requireUnique(List<String> values, String kind) {
    if (new HashSet<>(values).size() != values.size()) {
      throw rejected("EVIDENCE_DUPLICATE_" + kind.toUpperCase().replace(' ', '_'));
    }
  }

  private static ObjectNode requiredObject(JsonNode value, String kind) {
    if (!(value instanceof ObjectNode object)) {
      throw rejected("EVIDENCE_" + kind.toUpperCase().replace(' ', '_') + "_NOT_OBJECT");
    }
    return object;
  }

  private static ArrayNode requiredArray(JsonNode value, String field) {
    JsonNode node = value.required(field);
    if (!(node instanceof ArrayNode array)) {
      throw rejected("EVIDENCE_" + field.toUpperCase() + "_NOT_ARRAY");
    }
    return array;
  }

  private static String requiredText(JsonNode value, String field) {
    JsonNode node = value.required(field);
    if (!node.isTextual() || node.textValue().isBlank()) {
      throw rejected("EVIDENCE_" + field.toUpperCase() + "_INVALID");
    }
    return node.textValue();
  }

  private static String requiredHash(JsonNode value, String field) {
    String hash = requiredText(value, field);
    hash(hash, field);
    return hash;
  }

  private static long requiredLong(JsonNode value, String field) {
    JsonNode node = value.required(field);
    if (!node.isIntegralNumber() || node.longValue() < 0) {
      throw rejected("EVIDENCE_" + field.toUpperCase() + "_INVALID");
    }
    return node.longValue();
  }

  private static Set<String> textSet(JsonNode value, String kind) {
    List<String> values = textList(value, kind);
    Set<String> set = new HashSet<>(values);
    if (set.size() != values.size()) {
      throw rejected("EVIDENCE_DUPLICATE_" + kind.toUpperCase().replace(' ', '_'));
    }
    return Set.copyOf(set);
  }

  private static List<String> textList(JsonNode value, String kind) {
    if (!(value instanceof ArrayNode array)) {
      throw rejected("EVIDENCE_" + kind.toUpperCase().replace(' ', '_') + "_NOT_ARRAY");
    }
    List<String> result = new ArrayList<>();
    for (JsonNode item : array) {
      if (!item.isTextual() || item.textValue().isBlank()) {
        throw rejected("EVIDENCE_" + kind.toUpperCase().replace(' ', '_') + "_INVALID");
      }
      result.add(item.textValue());
    }
    return List.copyOf(result);
  }

  private static void requireFalse(JsonNode value, String field) {
    JsonNode node = value.required(field);
    if (!node.isBoolean() || node.booleanValue()) {
      throw rejected("EVIDENCE_" + field.toUpperCase() + "_MUST_BE_FALSE");
    }
  }

  private static void hash(String value, String field) {
    if (value == null || !value.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }

  private static void requireEqual(Object actual, Object expected, String field) {
    if (!Objects.equals(actual, expected)) {
      throw rejected("EVIDENCE_FINALIZATION_MISMATCH:" + field);
    }
  }

  private static FinalizationRejectedException rejected(String code) {
    return new FinalizationRejectedException(code);
  }

  public static final class FinalizationRejectedException extends IllegalArgumentException {
    public FinalizationRejectedException(String code) {
      super(code);
    }
  }
}
