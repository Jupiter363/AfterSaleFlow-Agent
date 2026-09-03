package com.example.dispute.workflow.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.shadow.intake.IntakeReliabilityHarness;
import com.example.dispute.workflow.shadow.intake.IntakeReliabilityHarness.FailureClassification;
import com.example.dispute.workflow.shadow.intake.IntakeReliabilityHarness.FaultBoundary;
import com.example.dispute.workflow.shadow.intake.IntakeReliabilityHarness.Invariant;
import com.example.dispute.workflow.shadow.intake.IntakeReliabilityHarness.Observation;
import com.example.dispute.workflow.shadow.intake.IntakeReliabilityHarness.Outcome;
import com.example.dispute.workflow.shadow.intake.IntakeReliabilityHarness.Scenario;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IntakeCutoverRollbackTest {

  private static final Set<String> FORMAL_REFS =
      Set.of("message://CASE_ROLLBACK/17", "dossier://CASE_ROLLBACK/9");
  private static final Set<String> INITIATOR_EFFECTS =
      Set.of("invitation://CASE_ROLLBACK/respondent", "summons://CASE_ROLLBACK/respondent");
  private static final String EVIDENCE_RECEIPT = "operation://CASE_ROLLBACK/open-evidence/1";
  private static final String CANDIDATE = "f3be4633690c121891b5080fda1babdf4faa6cf9";
  private static final String FIXTURE_HASH = "d".repeat(64);

  @Test
  void preTerminalRollbackCreatesHigherFencedLegacyEpochAndRejectsStaleWriter() {
    RollbackHarness harness =
        RollbackHarness.syntheticIntake(Boundary.READY_TO_CONFIRM, FORMAL_REFS, Set.of());
    WriterEpoch staleWriter = harness.activeWriter();

    RollbackOutcome outcome = harness.rollback(staleWriter.roomEpoch(), staleWriter.fencingToken());

    assertThat(outcome.action()).isEqualTo(RecoveryAction.NEW_LEGACY_EPOCH);
    assertThat(outcome.writer().writerMode()).isEqualTo(WriterMode.LEGACY);
    assertThat(outcome.writer().roomEpoch()).isGreaterThan(staleWriter.roomEpoch());
    assertThat(outcome.writer().fencingToken()).isGreaterThan(staleWriter.fencingToken());
    assertThat(outcome.formalRefs()).isEqualTo(FORMAL_REFS);
    assertThat(outcome.resumableParties()).containsExactly(IntakeParty.INITIATOR);
    assertThat(harness.activeWriterCount()).isOne();

    assertThatThrownBy(
            () -> harness.rollback(staleWriter.roomEpoch(), staleWriter.fencingToken()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stale epoch or fence");
    assertThat(harness.activeWriterCount()).isOne();
  }

  @Test
  void postInitiatorRollbackPreservesEffectsAndResumesRespondentOnly() {
    RollbackHarness harness =
        RollbackHarness.syntheticIntake(
            Boundary.WAITING_PARTY, FORMAL_REFS, INITIATOR_EFFECTS);
    WriterEpoch priorWriter = harness.activeWriter();

    RollbackOutcome outcome = harness.rollback(priorWriter.roomEpoch(), priorWriter.fencingToken());

    assertThat(outcome.action()).isEqualTo(RecoveryAction.NEW_LEGACY_EPOCH);
    assertThat(outcome.resumableParties()).containsExactly(IntakeParty.RESPONDENT);
    assertThat(outcome.preservedEffects()).isEqualTo(INITIATOR_EFFECTS);
    assertThat(outcome.emittedEffects()).isEmpty();
    assertThat(harness.durableEffects()).isEqualTo(INITIATOR_EFFECTS);
    assertThat(harness.activeWriterCount()).isOne();
  }

  @Test
  void postEvidenceRollbackReusesReceiptAndNeverReopensIntake() {
    RollbackHarness harness = RollbackHarness.evidenceOpen(INITIATOR_EFFECTS, EVIDENCE_RECEIPT);
    WriterEpoch evidenceWriter = harness.activeWriter();
    int epochCount = harness.epochHistory().size();

    RollbackOutcome outcome =
        harness.rollback(evidenceWriter.roomEpoch(), evidenceWriter.fencingToken());

    assertThat(outcome.action()).isEqualTo(RecoveryAction.RECONCILE_FORWARD);
    assertThat(outcome.operationReceipt()).isEqualTo(EVIDENCE_RECEIPT);
    assertThat(outcome.writer()).isEqualTo(evidenceWriter);
    assertThat(harness.epochHistory()).hasSize(epochCount);
    assertThat(harness.epochHistory())
        .noneMatch(epoch -> epoch.active() && epoch.roomType() == RoomType.INTAKE);
    assertThat(harness.durableEffects()).isEqualTo(INITIATOR_EFFECTS);
    assertThat(harness.activeWriterCount()).isOne();
  }

  @Test
  void everyRollbackBoundaryRetainsExactlyOneActiveWriter() {
    List<RollbackHarness> harnesses =
        List.of(
            RollbackHarness.syntheticIntake(Boundary.OPEN, FORMAL_REFS, Set.of()),
            RollbackHarness.syntheticIntake(
                Boundary.WAITING_PARTY, FORMAL_REFS, INITIATOR_EFFECTS),
            RollbackHarness.evidenceOpen(INITIATOR_EFFECTS, EVIDENCE_RECEIPT));

    for (RollbackHarness harness : harnesses) {
      assertThat(harness.activeWriterCount()).isOne();
      WriterEpoch writer = harness.activeWriter();
      harness.rollback(writer.roomEpoch(), writer.fencingToken());
      assertThat(harness.activeWriterCount()).isOne();
    }
  }

  @Test
  void rollbackBoundariesAreClassifiedAndReproducibleFromFreshState() {
    IntakeReliabilityHarness reliability = new IntakeReliabilityHarness();
    List<FaultBoundary> boundaries =
        List.of(
            FaultBoundary.ROLLBACK_PRE_TERMINAL,
            FaultBoundary.ROLLBACK_AFTER_INITIATOR,
            FaultBoundary.ROLLBACK_AFTER_EVIDENCE);

    for (FaultBoundary boundary : boundaries) {
      Scenario scenario =
          new Scenario(
              IntakeReliabilityHarness.SCENARIO_V1,
              boundary,
              FailureClassification.INFRA,
              CANDIDATE,
              FIXTURE_HASH,
              20260722,
              3);

      var report = reliability.requirePassing(scenario, ignored -> observeRollback(boundary));

      assertThat(report.failureClassification()).isEqualTo(FailureClassification.INFRA);
      assertThat(report.observationHashes())
          .hasSize(3)
          .containsOnly(report.observationHashes().getFirst());
      assertThat(report.missingInvariants()).isEmpty();
    }
  }

  private static Observation observeRollback(FaultBoundary boundary) {
    return switch (boundary) {
      case ROLLBACK_PRE_TERMINAL ->
          observeNewLegacyEpochRollback(
              boundary,
              RollbackHarness.syntheticIntake(Boundary.READY_TO_CONFIRM, FORMAL_REFS, Set.of()),
              false);
      case ROLLBACK_AFTER_INITIATOR ->
          observeNewLegacyEpochRollback(
              boundary,
              RollbackHarness.syntheticIntake(
                  Boundary.WAITING_PARTY, FORMAL_REFS, INITIATOR_EFFECTS),
              true);
      case ROLLBACK_AFTER_EVIDENCE -> observeEvidenceForwardRecovery();
      default -> throw new IllegalArgumentException("rollback probe requires a rollback boundary");
    };
  }

  private static Observation observeNewLegacyEpochRollback(
      FaultBoundary boundary, RollbackHarness harness, boolean respondentOnly) {
    WriterEpoch prior = harness.activeWriter();
    RollbackOutcome outcome = harness.rollback(prior.roomEpoch(), prior.fencingToken());
    EnumSet<Invariant> satisfied = EnumSet.noneOf(Invariant.class);
    if (harness.activeWriterCount() == 1) {
      satisfied.add(Invariant.ONE_ACTIVE_WRITER);
    }
    if (outcome.writer().roomEpoch() > prior.roomEpoch()
        && outcome.writer().fencingToken() > prior.fencingToken()) {
      satisfied.add(Invariant.HIGHER_FENCE_SELECTED);
    }
    if (outcome.formalRefs().equals(prior.formalRefs())) {
      satisfied.add(Invariant.FORMAL_REFS_PRESERVED);
    }
    try {
      harness.rollback(prior.roomEpoch(), prior.fencingToken());
    } catch (IllegalStateException expected) {
      satisfied.add(Invariant.STALE_FENCE_REJECTED);
    }
    if (respondentOnly
        && outcome.preservedEffects().equals(INITIATOR_EFFECTS)
        && harness.durableEffects().equals(INITIATOR_EFFECTS)) {
      satisfied.add(Invariant.INITIATOR_EFFECTS_PRESERVED);
    }
    if (respondentOnly && outcome.resumableParties().equals(Set.of(IntakeParty.RESPONDENT))) {
      satisfied.add(Invariant.RESPONDENT_ONLY_RESUME);
    }
    return observation(boundary, Outcome.RECOVERED, outcome, harness, satisfied);
  }

  private static Observation observeEvidenceForwardRecovery() {
    RollbackHarness harness = RollbackHarness.evidenceOpen(INITIATOR_EFFECTS, EVIDENCE_RECEIPT);
    WriterEpoch prior = harness.activeWriter();
    int epochCount = harness.epochHistory().size();
    RollbackOutcome outcome = harness.rollback(prior.roomEpoch(), prior.fencingToken());
    EnumSet<Invariant> satisfied = EnumSet.noneOf(Invariant.class);
    if (harness.activeWriterCount() == 1) {
      satisfied.add(Invariant.ONE_ACTIVE_WRITER);
    }
    if (EVIDENCE_RECEIPT.equals(outcome.operationReceipt())) {
      satisfied.add(Invariant.COMMITTED_RECEIPT_REUSED);
    }
    if (harness.epochHistory().size() == epochCount
        && harness.epochHistory().stream()
            .noneMatch(epoch -> epoch.active() && epoch.roomType() == RoomType.INTAKE)) {
      satisfied.add(Invariant.FORWARD_ONLY_AFTER_EVIDENCE);
    }
    return observation(
        FaultBoundary.ROLLBACK_AFTER_EVIDENCE,
        Outcome.RECONCILED_FORWARD,
        outcome,
        harness,
        satisfied);
  }

  private static Observation observation(
      FaultBoundary boundary,
      Outcome outcome,
      RollbackOutcome rollback,
      RollbackHarness harness,
      Set<Invariant> satisfied) {
    ObjectNode state = JsonNodeFactory.instance.objectNode();
    state.put("boundary", boundary.name());
    state.put("action", rollback.action().name());
    state.put("active_writer_count", harness.activeWriterCount());
    state.put("room_type", rollback.writer().roomType().name());
    state.put("writer_mode", rollback.writer().writerMode().name());
    state.put("room_epoch", rollback.writer().roomEpoch());
    state.put("fencing_token", rollback.writer().fencingToken());
    state.put("formal_ref_count", rollback.formalRefs().size());
    state.put("preserved_effect_count", rollback.preservedEffects().size());
    state.put("resumable_party_count", rollback.resumableParties().size());
    state.put("has_operation_receipt", rollback.operationReceipt() != null);
    String stateHash = ContractJson.sha256Hex(state);

    ObjectNode trace = JsonNodeFactory.instance.objectNode();
    trace.put("boundary", boundary.name());
    trace.put("state_hash", stateHash);
    trace.put("epoch_history_size", harness.epochHistory().size());
    return new Observation(outcome, satisfied, stateHash, ContractJson.sha256Hex(trace));
  }

  private enum Boundary {
    OPEN,
    READY_TO_CONFIRM,
    WAITING_PARTY,
    EVIDENCE_OPEN
  }

  private enum RecoveryAction {
    NEW_LEGACY_EPOCH,
    RECONCILE_FORWARD
  }

  private record WriterEpoch(
      RoomType roomType,
      long roomEpoch,
      long fencingToken,
      WriterMode writerMode,
      boolean signedSynthetic,
      boolean active,
      Set<String> formalRefs) {

    private WriterEpoch {
      Objects.requireNonNull(roomType, "roomType");
      Objects.requireNonNull(writerMode, "writerMode");
      formalRefs = Set.copyOf(formalRefs);
      if (roomEpoch < 1 || fencingToken < 1) {
        throw new IllegalArgumentException("epoch and fence must be positive");
      }
      if (writerMode == WriterMode.TEMPORAL) {
        throw new IllegalArgumentException("the engineering rollback harness forbids TEMPORAL");
      }
      if (writerMode == WriterMode.SHADOW && !signedSynthetic) {
        throw new IllegalArgumentException("SHADOW must be signed synthetic");
      }
    }

    WriterEpoch deactivate() {
      return new WriterEpoch(
          roomType, roomEpoch, fencingToken, writerMode, signedSynthetic, false, formalRefs);
    }
  }

  private record RollbackOutcome(
      RecoveryAction action,
      WriterEpoch writer,
      Set<IntakeParty> resumableParties,
      Set<String> formalRefs,
      Set<String> preservedEffects,
      Set<String> emittedEffects,
      String operationReceipt) {

    private RollbackOutcome {
      resumableParties = Set.copyOf(resumableParties);
      formalRefs = Set.copyOf(formalRefs);
      preservedEffects = Set.copyOf(preservedEffects);
      emittedEffects = Set.copyOf(emittedEffects);
    }
  }

  private static final class RollbackHarness {
    private static final long INITIAL_EPOCH = 7;
    private static final long INITIAL_FENCE = 41;

    private final Boundary boundary;
    private final Set<String> durableEffects;
    private final String evidenceReceipt;
    private List<WriterEpoch> epochHistory;

    private RollbackHarness(
        Boundary boundary,
        Set<String> durableEffects,
        String evidenceReceipt,
        WriterEpoch activeWriter) {
      this.boundary = Objects.requireNonNull(boundary, "boundary");
      this.durableEffects = Set.copyOf(durableEffects);
      this.evidenceReceipt = evidenceReceipt;
      this.epochHistory = List.of(activeWriter);
      requireOneActiveWriter();
    }

    static RollbackHarness syntheticIntake(
        Boundary boundary, Set<String> formalRefs, Set<String> durableEffects) {
      if (boundary == Boundary.EVIDENCE_OPEN) {
        throw new IllegalArgumentException("Evidence-open recovery uses the committed receipt");
      }
      return new RollbackHarness(
          boundary,
          durableEffects,
          null,
          new WriterEpoch(
              RoomType.INTAKE,
              INITIAL_EPOCH,
              INITIAL_FENCE,
              WriterMode.SHADOW,
              true,
              true,
              formalRefs));
    }

    static RollbackHarness evidenceOpen(Set<String> durableEffects, String receipt) {
      return new RollbackHarness(
          Boundary.EVIDENCE_OPEN,
          durableEffects,
          Objects.requireNonNull(receipt, "receipt"),
          new WriterEpoch(
              RoomType.EVIDENCE,
              INITIAL_EPOCH + 1,
              INITIAL_FENCE + 1,
              WriterMode.LEGACY,
              false,
              true,
              Set.of()));
    }

    RollbackOutcome rollback(long expectedEpoch, long expectedFence) {
      WriterEpoch current = activeWriter();
      if (current.roomEpoch() != expectedEpoch || current.fencingToken() != expectedFence) {
        throw new IllegalStateException("stale epoch or fence cannot recover Intake");
      }
      if (boundary == Boundary.EVIDENCE_OPEN) {
        return new RollbackOutcome(
            RecoveryAction.RECONCILE_FORWARD,
            current,
            Set.of(),
            Set.of(),
            durableEffects,
            Set.of(),
            evidenceReceipt);
      }

      Set<IntakeParty> resumableParties =
          boundary == Boundary.WAITING_PARTY
              ? EnumSet.of(IntakeParty.RESPONDENT)
              : EnumSet.of(IntakeParty.INITIATOR);
      long nextEpoch =
          epochHistory.stream().mapToLong(WriterEpoch::roomEpoch).max().orElseThrow() + 1;
      long nextFence =
          epochHistory.stream().mapToLong(WriterEpoch::fencingToken).max().orElseThrow() + 1;
      WriterEpoch recoveryWriter =
          new WriterEpoch(
              RoomType.INTAKE,
              nextEpoch,
              nextFence,
              WriterMode.LEGACY,
              false,
              true,
              current.formalRefs());

      List<WriterEpoch> replacement = new ArrayList<>(epochHistory.size() + 1);
      epochHistory.forEach(epoch -> replacement.add(epoch.active() ? epoch.deactivate() : epoch));
      replacement.add(recoveryWriter);
      epochHistory = List.copyOf(replacement);
      requireOneActiveWriter();

      return new RollbackOutcome(
          RecoveryAction.NEW_LEGACY_EPOCH,
          recoveryWriter,
          resumableParties,
          current.formalRefs(),
          durableEffects,
          Set.of(),
          null);
    }

    WriterEpoch activeWriter() {
      return epochHistory.stream().filter(WriterEpoch::active).findFirst().orElseThrow();
    }

    long activeWriterCount() {
      return epochHistory.stream().filter(WriterEpoch::active).count();
    }

    Set<String> durableEffects() {
      return durableEffects;
    }

    List<WriterEpoch> epochHistory() {
      return epochHistory;
    }

    private void requireOneActiveWriter() {
      if (activeWriterCount() != 1) {
        throw new IllegalStateException("rollback must retain exactly one active writer");
      }
    }
  }
}
