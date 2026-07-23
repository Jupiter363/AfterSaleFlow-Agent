package com.example.dispute.evidence.application.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.evidence.application.graph.EvidenceAssetAuthorization.ActualLoadReceipt;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationLedger.CommitRequest;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationLedger.IdempotencyConflictException;
import com.example.dispute.evidence.application.graph.EvidenceFinalizationLedger.Lookup;
import com.example.dispute.evidence.application.graph.EvidenceGraphResultFinalizer.AuthorityLookup;
import com.example.dispute.evidence.application.graph.EvidenceGraphResultFinalizer.AuthorityResolver;
import com.example.dispute.evidence.application.graph.EvidenceGraphResultFinalizer.AuthoritySnapshot;
import com.example.dispute.evidence.application.graph.EvidenceGraphResultFinalizer.FinalizationRequest;
import com.example.dispute.evidence.application.graph.EvidenceGraphResultFinalizer.LoadReceiptLookup;
import com.example.dispute.room.application.EvidenceAgentTurnService;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EvidenceGraphResultFinalizerTest {

  private static final String RESULT_HASH = "d".repeat(64);
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-22T12:06:00Z"), ZoneOffset.UTC);

  @Test
  void validatesCompleteCoverageAndCommitsOnlyAZeroWriteSyntheticReceipt() throws Exception {
    var fixture = EvidenceGraphCommandFactoryTest.fixture();
    RecordingLedger ledger = new RecordingLedger(fixture.authoritySnapshot());
    RecordingAuthorityResolver resolver =
        new RecordingAuthorityResolver(fixture.authoritySnapshot(), fixture.actualLoadReceipt());
    EvidenceFinalizationReceipt receipt =
        finalizer(ledger, resolver).finalizeResult(request(fixture));

    assertThat(ledger.commitCalls).hasValue(1);
    assertThat(receipt.commitScope())
        .isEqualTo(EvidenceFinalizationReceipt.ISOLATED_SYNTHETIC_LEDGER);
    assertThat(receipt.formalDomainWrite()).isFalse();
    assertThat(receipt.formalSinkEligible()).isFalse();
    assertThat(receipt.mergeCount()).isZero();
    assertThat(receipt.domainEventIds()).isEmpty();
    assertThat(receipt.outboxIds()).isEmpty();
    assertThat(receipt.hearingOpened()).isFalse();
    assertThat(resolver.snapshotLookups).hasValue(1);
    assertThat(resolver.loadLookups).hasValue(1);
  }

  @Test
  void commitResponseLossReturnsTheSameReceiptWithoutRevalidatingProposalBytes() throws Exception {
    var fixture = EvidenceGraphCommandFactoryTest.fixture();
    RecordingLedger ledger = new RecordingLedger(fixture.authoritySnapshot());
    FinalizationRequest original = request(fixture);
    EvidenceFinalizationReceipt committed = finalizer(ledger, fixture).finalizeResult(original);
    ObjectNode referenceOnly = EvidenceGraphCommandFactoryTest.MAPPER.createObjectNode();
    referenceOnly.put("proposal_hash", fixture.terminal().required("proposal_hash").textValue());
    FinalizationRequest retry =
        new FinalizationRequest(
            original.requestHash(),
            original.resultHash(),
            original.authoritySnapshotHash(),
            original.manifest(),
            original.binding(),
            original.currentAuthority(),
            original.command(),
            referenceOnly,
            original.itemAssessments());

    EvidenceFinalizationReceipt replay = finalizer(ledger, fixture).finalizeResult(retry);

    assertThat(replay).isEqualTo(committed);
    assertThat(ledger.commitCalls).hasValue(1);
  }

  @Test
  void sameSemanticKeyWithAnotherCanonicalRequestIsAnIdempotencyConflict() throws Exception {
    var fixture = EvidenceGraphCommandFactoryTest.fixture();
    RecordingLedger ledger = new RecordingLedger(fixture.authoritySnapshot());
    finalizer(ledger, fixture).finalizeResult(request(fixture));
    FinalizationRequest conflict = request(fixture, "e".repeat(64));

    assertThatThrownBy(() -> finalizer(ledger, fixture).finalizeResult(conflict))
        .isInstanceOf(IdempotencyConflictException.class)
        .hasMessageContaining("different canonical request");
    assertThat(ledger.commitCalls).hasValue(1);
  }

  @Test
  void missingDuplicateAndForeignItemsFailBeforeTheLedgerCommit() throws Exception {
    var fixture = EvidenceGraphCommandFactoryTest.fixture();

    RecordingLedger missingLedger = new RecordingLedger(fixture.authoritySnapshot());
    FinalizationRequest missing =
        EvidenceGraphResultFinalizer.createRequest(
            RESULT_HASH,
            fixture.manifest(),
            fixture.binding(),
            fixture.authority(),
            fixture.command(),
            fixture.terminal(),
            List.of(),
            fixture.authoritySnapshot());
    assertThatThrownBy(() -> finalizer(missingLedger, fixture).finalizeResult(missing))
        .hasMessage("EVIDENCE_ITEM_ASSESSMENTS_COUNT_INVALID");
    assertThat(missingLedger.commitCalls).hasValue(0);

    RecordingLedger duplicateLedger = new RecordingLedger(fixture.authoritySnapshot());
    FinalizationRequest duplicate =
        EvidenceGraphResultFinalizer.createRequest(
            RESULT_HASH,
            fixture.manifest(),
            fixture.binding(),
            fixture.authority(),
            fixture.command(),
            fixture.terminal(),
            List.of(fixture.assessment(), fixture.assessment()),
            fixture.authoritySnapshot());
    assertThatThrownBy(() -> finalizer(duplicateLedger, fixture).finalizeResult(duplicate))
        .hasMessage("EVIDENCE_CONFLICTING_ASSESSMENT_HASH");
    assertThat(duplicateLedger.commitCalls).hasValue(0);

    ObjectNode foreignAssessment = fixture.assessment().deepCopy();
    foreignAssessment.put("evidence_id", "EVIDENCE_FOREIGN_001");
    foreignAssessment = rehash(foreignAssessment, "assessment_hash");
    ObjectNode foreignTerminal = terminalForAssessment(fixture.terminal(), foreignAssessment);
    RecordingLedger foreignLedger = new RecordingLedger(fixture.authoritySnapshot());
    FinalizationRequest foreign =
        EvidenceGraphResultFinalizer.createRequest(
            RESULT_HASH,
            fixture.manifest(),
            fixture.binding(),
            fixture.authority(),
            fixture.command(),
            foreignTerminal,
            List.of(foreignAssessment),
            fixture.authoritySnapshot());
    assertThatThrownBy(() -> finalizer(foreignLedger, fixture).finalizeResult(foreign))
        .hasMessage("EVIDENCE_FINALIZATION_MISMATCH:ordered item keys");
    assertThat(foreignLedger.commitCalls).hasValue(0);
  }

  @Test
  void actualLoadReceiptHashOrVersionCannotBeSubstituted() throws Exception {
    var fixture = EvidenceGraphCommandFactoryTest.fixture();
    ObjectNode changedAssessment = fixture.assessment().deepCopy();
    changedAssessment.put("asset_load_receipt_hash", "f".repeat(64));
    ObjectNode assessment = rehash(changedAssessment, "assessment_hash");
    ObjectNode terminal = terminalForAssessment(fixture.terminal(), assessment);
    RecordingLedger ledger = new RecordingLedger(fixture.authoritySnapshot());

    assertThatThrownBy(
            () -> finalizer(ledger, fixture).finalizeResult(request(fixture, terminal, assessment)))
        .hasMessage("EVIDENCE_ACTUAL_LOAD_RECEIPT_NOT_AUTHORIZED");
    assertThat(ledger.commitCalls).hasValue(0);
  }

  @Test
  void lowRelevanceDoesNotBecomeSuspectedForgery() throws Exception {
    var fixture = EvidenceGraphCommandFactoryTest.fixture();
    ObjectNode changedLowRelevance = fixture.assessment().deepCopy();
    changedLowRelevance.put("relevance_score", 0.05);
    changedLowRelevance.put("assessment_status", "NEEDS_REVIEW");
    ((ArrayNode) changedLowRelevance.required("relevance_reason_codes"))
        .removeAll()
        .add("LOW_RELEVANCE");
    ((ArrayNode) changedLowRelevance.required("review_reasons"))
        .removeAll()
        .add("LOW_RELEVANCE_SCORE");
    ObjectNode lowRelevance = rehash(changedLowRelevance, "assessment_hash");
    ObjectNode lowTerminal = terminalForAssessment(fixture.terminal(), lowRelevance);
    RecordingLedger acceptedLedger = new RecordingLedger(fixture.authoritySnapshot());

    EvidenceFinalizationReceipt accepted =
        finalizer(acceptedLedger, fixture)
            .finalizeResult(request(fixture, lowTerminal, lowRelevance));

    assertThat(accepted.requestHash()).isNotBlank();
    assertThat(acceptedLedger.commitCalls).hasValue(1);

    ObjectNode changedConflated = lowRelevance.deepCopy();
    ((ArrayNode) changedConflated.required("relevance_reason_codes"))
        .removeAll()
        .add("SUSPECTED_FORGERY");
    ObjectNode conflated = rehash(changedConflated, "assessment_hash");
    ObjectNode conflatedTerminal = terminalForAssessment(fixture.terminal(), conflated);
    RecordingLedger rejectedLedger = new RecordingLedger(fixture.authoritySnapshot());
    assertThatThrownBy(
            () ->
                finalizer(rejectedLedger, fixture)
                    .finalizeResult(request(fixture, conflatedTerminal, conflated)))
        .hasMessage("EVIDENCE_AUTHENTICITY_RELEVANCE_CONFLATED");
    assertThat(rejectedLedger.commitCalls).hasValue(0);
  }

  @Test
  void schemaInvalidPayloadFailsBeforeTrustedResolutionOrCommit() throws Exception {
    var fixture = EvidenceGraphCommandFactoryTest.fixture();
    ObjectNode invalidTime = fixture.terminal().deepCopy();
    invalidTime.put("completed_at", "not-rfc3339");
    invalidTime = rehash(invalidTime, "proposal_hash");
    FinalizationRequest request = request(fixture, invalidTime, fixture.assessment());
    RecordingLedger ledger = new RecordingLedger(fixture.authoritySnapshot());
    RecordingAuthorityResolver resolver =
        new RecordingAuthorityResolver(fixture.authoritySnapshot(), fixture.actualLoadReceipt());

    assertThatThrownBy(() -> finalizer(ledger, resolver).finalizeResult(request))
        .hasMessage("EVIDENCE_COMPLETED_AT_INVALID");
    assertThat(resolver.snapshotLookups).hasValue(0);
    assertThat(resolver.loadLookups).hasValue(0);
    assertThat(ledger.commitCalls).hasValue(0);

    ObjectNode changedOversizedAssessment = fixture.assessment().deepCopy();
    ArrayNode limitations = (ArrayNode) changedOversizedAssessment.required("limitations");
    limitations.removeAll();
    java.util.stream.IntStream.range(0, 17).forEach(index -> limitations.add("LIMIT_" + index));
    ObjectNode oversizedAssessment = rehash(changedOversizedAssessment, "assessment_hash");
    ObjectNode oversizedTerminal = terminalForAssessment(fixture.terminal(), oversizedAssessment);
    RecordingLedger oversizedLedger = new RecordingLedger(fixture.authoritySnapshot());
    RecordingAuthorityResolver oversizedResolver =
        new RecordingAuthorityResolver(fixture.authoritySnapshot(), fixture.actualLoadReceipt());
    assertThatThrownBy(
            () ->
                finalizer(oversizedLedger, oversizedResolver)
                    .finalizeResult(request(fixture, oversizedTerminal, oversizedAssessment)))
        .hasMessage("EVIDENCE_LIMITATIONS_COUNT_INVALID");
    assertThat(oversizedResolver.snapshotLookups).hasValue(0);
    assertThat(oversizedLedger.commitCalls).hasValue(0);
  }

  @Test
  void lowAuthenticityRequiresIndependentForgeryReview() throws Exception {
    var fixture = EvidenceGraphCommandFactoryTest.fixture();
    ObjectNode assessment = fixture.assessment().deepCopy();
    assessment.put("authenticity_score", 0.49);
    assessment.put("assessment_status", "NEEDS_REVIEW");
    ((ArrayNode) assessment.required("authenticity_reason_codes"))
        .removeAll()
        .add("LOW_AUTHENTICITY_SUSPECTED_FORGERY");
    ((ArrayNode) assessment.required("review_reasons"))
        .removeAll()
        .add("LOW_AUTHENTICITY_SUSPECTED_FORGERY");
    assessment = rehash(assessment, "assessment_hash");
    ObjectNode terminal = terminalForAssessment(fixture.terminal(), assessment);
    RecordingLedger ledger = new RecordingLedger(fixture.authoritySnapshot());

    EvidenceFinalizationReceipt receipt =
        finalizer(ledger, fixture).finalizeResult(request(fixture, terminal, assessment));

    assertThat(receipt.formalDomainWrite()).isFalse();
    assertThat(ledger.commitCalls).hasValue(1);
  }

  @Test
  void staleAuthorityAndTakeoverBeforeCommitBothFailClosed() throws Exception {
    var fixture = EvidenceGraphCommandFactoryTest.fixture();
    var changedCurrent =
        new EvidenceGraphCommandFactory.CurrentAuthority(
            fixture.authority().runtimeMode(),
            fixture.authority().tenantSurrogate(),
            fixture.authority().caseId(),
            fixture.authority().roomId(),
            fixture.authority().roomEpoch(),
            fixture.authority().javaRoomFencingToken(),
            fixture.authority().actorId(),
            fixture.authority().actorRole(),
            fixture.authority().participantId(),
            fixture.authority().actorScopeHash(),
            fixture.authority().agentSessionId(),
            fixture.authority().sourceRevision(),
            fixture.authority().processRevision() + 1,
            fixture.authority().roomRevision() + 1);
    AuthoritySnapshot changedSnapshot =
        AuthoritySnapshot.create(
            changedCurrent,
            EvidenceGraphCommandFactory.AGENT_PROFILE_ID,
            Set.of("FACT_ORDER_DAMAGE"),
            Set.of("SOURCE_SYNTHETIC_001"));

    RecordingLedger precheckLedger = new RecordingLedger(changedSnapshot);
    RecordingAuthorityResolver staleResolver =
        new RecordingAuthorityResolver(changedSnapshot, fixture.actualLoadReceipt());
    assertThatThrownBy(
            () -> finalizer(precheckLedger, staleResolver).finalizeResult(request(fixture)))
        .hasMessage("EVIDENCE_FINALIZATION_MISMATCH:authority snapshot hash");
    assertThat(precheckLedger.commitCalls).hasValue(0);

    RecordingLedger takeoverLedger = new RecordingLedger(changedSnapshot);
    RecordingAuthorityResolver originalResolver =
        new RecordingAuthorityResolver(fixture.authoritySnapshot(), fixture.actualLoadReceipt());
    assertThatThrownBy(
            () -> finalizer(takeoverLedger, originalResolver).finalizeResult(request(fixture)))
        .hasMessage("EVIDENCE_AUTHORITY_CHANGED_BEFORE_COMMIT");
    assertThat(takeoverLedger.commitCalls).hasValue(1);
    assertThat(takeoverLedger.receipts).isEmpty();
    var requirement = takeoverLedger.lastRequirement;
    assertThat(requirement.runtimeMode()).isEqualTo("SIGNED_SYNTHETIC_SHADOW");
    assertThat(requirement.agentProfileId()).isEqualTo("evidence-clerk.v2");
    assertThat(requirement.tenantSurrogate()).isEqualTo(fixture.authority().tenantSurrogate());
    assertThat(requirement.caseId()).isEqualTo(fixture.authority().caseId());
    assertThat(requirement.roomEpoch()).isEqualTo(fixture.authority().roomEpoch());
    assertThat(requirement.javaRoomFencingToken())
        .isEqualTo(fixture.authority().javaRoomFencingToken());
    assertThat(requirement.actorScopeHash()).isEqualTo(fixture.authority().actorScopeHash());
    assertThat(requirement.agentSessionId()).isEqualTo(fixture.authority().agentSessionId());
    assertThat(requirement.processRevision()).isEqualTo(fixture.authority().processRevision());
    assertThat(requirement.currentFactIds()).containsExactly("FACT_ORDER_DAMAGE");
    assertThat(requirement.currentSourceRefs()).containsExactly("SOURCE_SYNTHETIC_001");
    assertThat(requirement.actualLoadRequirements())
        .singleElement()
        .extracting(EvidenceFinalizationLedger.ActualLoadRequirement::receiptId)
        .isEqualTo(fixture.actualLoadReceipt().receiptId());
  }

  @Test
  void callerCannotWhitelistForeignFactsOrSelfMintALoadReceipt() throws Exception {
    var fixture = EvidenceGraphCommandFactoryTest.fixture();
    ObjectNode changedForeign = fixture.assessment().deepCopy();
    ObjectNode itemFact = (ObjectNode) changedForeign.required("candidate_fact_links").required(0);
    itemFact.put("fact_id", "FACT_FOREIGN_1");
    ObjectNode foreign = rehash(changedForeign, "assessment_hash");
    ObjectNode terminal = terminalForAssessment(fixture.terminal(), foreign);
    RecordingLedger ledger = new RecordingLedger(fixture.authoritySnapshot());

    assertThatThrownBy(
            () -> finalizer(ledger, fixture).finalizeResult(request(fixture, terminal, foreign)))
        .hasMessage("EVIDENCE_FOREIGN_ITEM_FACT_REFERENCE");
    assertThat(ledger.commitCalls).hasValue(0);

    ObjectNode changedForgedLoad = fixture.assessment().deepCopy();
    changedForgedLoad.put("asset_load_receipt_ref", "SELF_MINTED_LOAD_RECEIPT");
    changedForgedLoad.put("asset_load_receipt_hash", "f".repeat(64));
    ObjectNode forgedLoad = rehash(changedForgedLoad, "assessment_hash");
    ObjectNode forgedTerminal = terminalForAssessment(fixture.terminal(), forgedLoad);
    RecordingLedger loadLedger = new RecordingLedger(fixture.authoritySnapshot());
    RecordingAuthorityResolver resolver =
        new RecordingAuthorityResolver(fixture.authoritySnapshot(), fixture.actualLoadReceipt());
    assertThatThrownBy(
            () ->
                finalizer(loadLedger, resolver)
                    .finalizeResult(request(fixture, forgedTerminal, forgedLoad)))
        .hasMessage("EVIDENCE_ACTUAL_LOAD_RECEIPT_NOT_AUTHORIZED");
    assertThat(resolver.loadLookups).hasValue(1);
    assertThat(loadLedger.commitCalls).hasValue(0);

    assertThatThrownBy(() -> new EvidenceGraphResultFinalizer(loadLedger, null, CLOCK))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("authorityResolver");
  }

  @Test
  void reviewQueueExactlyMatchesAssessmentStatusAndReasons() throws Exception {
    var fixture = EvidenceGraphCommandFactoryTest.fixture();
    ObjectNode changedReviewAssessment = fixture.assessment().deepCopy();
    changedReviewAssessment.put("assessment_status", "NEEDS_REVIEW");
    ((ArrayNode) changedReviewAssessment.required("review_reasons"))
        .removeAll()
        .add("LOW_CONFIDENCE_REVIEW");
    ObjectNode reviewAssessment = rehash(changedReviewAssessment, "assessment_hash");
    ObjectNode validTerminal = terminalForAssessment(fixture.terminal(), reviewAssessment);

    ObjectNode changedMissing = validTerminal.deepCopy();
    ((ArrayNode) changedMissing.required("proposed_review_items")).removeAll();
    ObjectNode missing = rehash(changedMissing, "proposal_hash");
    RecordingLedger missingLedger = new RecordingLedger(fixture.authoritySnapshot());
    assertThatThrownBy(
            () ->
                finalizer(missingLedger, fixture)
                    .finalizeResult(request(fixture, missing, reviewAssessment)))
        .hasMessage("EVIDENCE_FINALIZATION_MISMATCH:review item coverage");

    ObjectNode changedWrongReasons = validTerminal.deepCopy();
    ObjectNode review =
        (ObjectNode) changedWrongReasons.required("proposed_review_items").required(0);
    ((ArrayNode) review.required("reason_codes")).removeAll().add("OTHER_REVIEW_REASON");
    ObjectNode wrongReasons = rehash(changedWrongReasons, "proposal_hash");
    RecordingLedger reasonLedger = new RecordingLedger(fixture.authoritySnapshot());
    assertThatThrownBy(
            () ->
                finalizer(reasonLedger, fixture)
                    .finalizeResult(request(fixture, wrongReasons, reviewAssessment)))
        .hasMessage("EVIDENCE_FINALIZATION_MISMATCH:review reason linkage");

    ObjectNode changedDuplicate = validTerminal.deepCopy();
    ObjectNode duplicateReview =
        ((ArrayNode) changedDuplicate.required("proposed_review_items")).addObject();
    duplicateReview.put("review_key", "REVIEW_DUPLICATE");
    duplicateReview.put("evidence_id", "EVIDENCE_SYNTH_001");
    duplicateReview.putArray("reason_codes").add("LOW_CONFIDENCE_REVIEW");
    duplicateReview.put("priority", "MEDIUM");
    ObjectNode duplicate = rehash(changedDuplicate, "proposal_hash");
    RecordingLedger duplicateLedger = new RecordingLedger(fixture.authoritySnapshot());
    assertThatThrownBy(
            () ->
                finalizer(duplicateLedger, fixture)
                    .finalizeResult(request(fixture, duplicate, reviewAssessment)))
        .hasMessage("EVIDENCE_DUPLICATE_REVIEW_EVIDENCE");

    ObjectNode changedCompletedExtra = fixture.terminal().deepCopy();
    ObjectNode extraReview =
        ((ArrayNode) changedCompletedExtra.required("proposed_review_items")).addObject();
    extraReview.put("review_key", "REVIEW_COMPLETED_EXTRA");
    extraReview.put("evidence_id", "EVIDENCE_SYNTH_001");
    extraReview.putArray("reason_codes").add("UNREQUESTED_REVIEW");
    extraReview.put("priority", "MEDIUM");
    ObjectNode completedExtra = rehash(changedCompletedExtra, "proposal_hash");
    RecordingLedger completedLedger = new RecordingLedger(fixture.authoritySnapshot());
    assertThatThrownBy(
            () ->
                finalizer(completedLedger, fixture)
                    .finalizeResult(request(fixture, completedExtra, fixture.assessment())))
        .hasMessage("EVIDENCE_COMPLETED_ITEM_REVIEW_FORBIDDEN");
  }

  @Test
  void legacyTurnServiceCannotDiscoverTheGraphFinalizerOrLedger() {
    assertThat(
            Arrays.stream(EvidenceAgentTurnService.class.getDeclaredFields()).map(Field::getType))
        .doesNotContain(EvidenceGraphResultFinalizer.class, EvidenceFinalizationLedger.class);
    assertThat(
            Arrays.stream(FinalizationRequest.class.getRecordComponents())
                .map(component -> component.getType())
                .noneMatch(ActualLoadReceipt.class::isAssignableFrom))
        .isTrue();
  }

  private static EvidenceGraphResultFinalizer finalizer(
      RecordingLedger ledger, EvidenceGraphCommandFactoryTest.Fixture fixture) {
    return finalizer(
        ledger,
        new RecordingAuthorityResolver(fixture.authoritySnapshot(), fixture.actualLoadReceipt()));
  }

  private static EvidenceGraphResultFinalizer finalizer(
      RecordingLedger ledger, RecordingAuthorityResolver resolver) {
    return new EvidenceGraphResultFinalizer(ledger, resolver, CLOCK);
  }

  private static FinalizationRequest request(EvidenceGraphCommandFactoryTest.Fixture fixture) {
    return request(fixture, RESULT_HASH);
  }

  private static FinalizationRequest request(
      EvidenceGraphCommandFactoryTest.Fixture fixture, String resultHash) {
    return request(fixture, fixture.terminal(), fixture.assessment(), resultHash);
  }

  private static FinalizationRequest request(
      EvidenceGraphCommandFactoryTest.Fixture fixture, ObjectNode terminal, ObjectNode assessment) {
    return request(fixture, terminal, assessment, RESULT_HASH);
  }

  private static FinalizationRequest request(
      EvidenceGraphCommandFactoryTest.Fixture fixture,
      ObjectNode terminal,
      ObjectNode assessment,
      String resultHash) {
    return EvidenceGraphResultFinalizer.createRequest(
        resultHash,
        fixture.manifest(),
        fixture.binding(),
        fixture.authority(),
        fixture.command(),
        terminal,
        List.of(assessment),
        fixture.authoritySnapshot());
  }

  private static ObjectNode terminalForAssessment(ObjectNode source, ObjectNode assessment) {
    ObjectNode terminal = source.deepCopy();
    ObjectNode ref = (ObjectNode) terminal.required("assessment_refs").required(0);
    ref.put("evidence_id", assessment.required("evidence_id").textValue());
    ref.put("assessment_status", assessment.required("assessment_status").textValue());
    ref.put("assessment_hash", assessment.required("assessment_hash").textValue());
    ((ArrayNode) terminal.required("ordered_item_keys"))
        .removeAll()
        .add(assessment.required("evidence_id").textValue());
    ArrayNode reviews = (ArrayNode) terminal.required("proposed_review_items");
    reviews.removeAll();
    if ("NEEDS_REVIEW".equals(assessment.required("assessment_status").textValue())) {
      ObjectNode review = reviews.addObject();
      review.put("review_key", "REVIEW_" + assessment.required("evidence_id").textValue());
      review.put("evidence_id", assessment.required("evidence_id").textValue());
      review.set("reason_codes", assessment.required("review_reasons").deepCopy());
      review.put("priority", "MEDIUM");
    }
    return rehash(terminal, "proposal_hash");
  }

  private static ObjectNode rehash(ObjectNode value, String field) {
    ObjectNode result = value.deepCopy();
    result.remove(field);
    result.put(field, ContractJson.sha256Hex(result));
    return result;
  }

  private static final class RecordingLedger implements EvidenceFinalizationLedger {
    private final Map<String, EvidenceFinalizationReceipt> receipts = new ConcurrentHashMap<>();
    private final AtomicInteger commitCalls = new AtomicInteger();
    private volatile AuthoritySnapshot authoritativeSnapshot;
    private volatile EvidenceFinalizationLedger.AuthorityRequirement lastRequirement;

    private RecordingLedger(AuthoritySnapshot authoritativeSnapshot) {
      this.authoritativeSnapshot = authoritativeSnapshot;
    }

    @Override
    public Optional<EvidenceFinalizationReceipt> findCommitted(Lookup lookup) {
      return Optional.ofNullable(
          receipts.get(lookup.tenantSurrogate() + ":" + lookup.operationKey()));
    }

    @Override
    public EvidenceFinalizationReceipt commitOrReplay(CommitRequest request) {
      commitCalls.incrementAndGet();
      lastRequirement = request.authorityRequirement();
      if (!request
          .authorityRequirement()
          .authoritySnapshotHash()
          .equals(authoritativeSnapshot.snapshotHash())) {
        throw new IllegalStateException("EVIDENCE_AUTHORITY_CHANGED_BEFORE_COMMIT");
      }
      EvidenceFinalizationReceipt candidate = request.candidate();
      return receipts.compute(
          candidate.tenantSurrogate() + ":" + candidate.operationKey(),
          (ignored, existing) -> {
            if (existing == null) {
              return candidate;
            }
            EvidenceFinalizationLedger.requireExactReplay(
                existing, candidate.operationKey(), candidate.requestHash());
            return existing;
          });
    }
  }

  private static final class RecordingAuthorityResolver implements AuthorityResolver {
    private final AuthoritySnapshot snapshot;
    private final ActualLoadReceipt receipt;
    private final AtomicInteger snapshotLookups = new AtomicInteger();
    private final AtomicInteger loadLookups = new AtomicInteger();

    private RecordingAuthorityResolver(AuthoritySnapshot snapshot, ActualLoadReceipt receipt) {
      this.snapshot = snapshot;
      this.receipt = receipt;
    }

    @Override
    public AuthoritySnapshot resolve(AuthorityLookup lookup) {
      snapshotLookups.incrementAndGet();
      return snapshot;
    }

    @Override
    public Optional<ActualLoadReceipt> findActualLoadReceipt(LoadReceiptLookup lookup) {
      loadLookups.incrementAndGet();
      if (lookup.authoritySnapshotHash().equals(snapshot.snapshotHash())
          && lookup.receiptId().equals(receipt.receiptId())
          && lookup.receiptHash().equals(receipt.receiptHash())
          && lookup.evidenceId().equals(receipt.evidenceId())
          && lookup.itemHash().equals(receipt.itemHash())
          && lookup.manifestHash().equals(receipt.manifestHash())) {
        return Optional.of(receipt);
      }
      return Optional.empty();
    }
  }
}
