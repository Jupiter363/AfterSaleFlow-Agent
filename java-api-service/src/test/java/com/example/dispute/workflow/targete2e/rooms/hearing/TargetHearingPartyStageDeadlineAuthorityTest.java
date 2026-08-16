package com.example.dispute.workflow.targete2e.rooms.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TargetHearingPartyStageDeadlineAuthorityTest {

  @Test
  void partyWaitStartsFromDurableFormalCompletionAndUsesConfiguredWindow() {
    Instant runStartedAt = Instant.parse("2026-08-16T10:00:00Z");
    Instant graphDeadline = runStartedAt.plusSeconds(300);
    Instant durableCompletion = runStartedAt.plusSeconds(290);
    long partyWindowSeconds = 1_200;
    Instant hearingDeadline = runStartedAt.plusSeconds(2_000);
    HearingRoomStart start = new HearingRoomStart(
        "hearing-room-start.v1", "tenant-deadline", "case-deadline", "room-deadline",
        "flow-deadline", "epoch-deadline", HearingWriterMode.TEMPORAL, 0, 3,
        "user-deadline", "merchant-deadline", runStartedAt.minusSeconds(60), hearingDeadline,
        partyWindowSeconds, 17, 6, "hearing-build-deadline");
    String commandHash = "b".repeat(64);
    String envelopeHash = "c".repeat(64);
    CommandAdmission admission = new CommandAdmission(
        "p9act.v1." + "1".repeat(32), "d".repeat(64), "e".repeat(64),
        start.tenantSurrogate(), start.caseId(), "hearing-stage:4:deadline",
        commandHash, envelopeHash, start.roomEpoch(), start.fencingToken());
    RoomGraphCommand graph = mock(RoomGraphCommand.class);
    when(graph.roomType()).thenReturn(RoomType.HEARING);
    when(graph.tenantSurrogate()).thenReturn(start.tenantSurrogate());
    when(graph.caseId()).thenReturn(start.caseId());
    when(graph.commandId()).thenReturn(admission.commandId());
    when(graph.roomEpoch()).thenReturn(start.roomEpoch());
    when(graph.deadlineAt()).thenReturn(graphDeadline);
    ExecuteAgentRunRequest request = mock(ExecuteAgentRunRequest.class);
    when(request.command()).thenReturn(graph);

    TargetHearingCommandMaterial material = TargetHearingInternalStageMaterializer.commandMaterial(
        start, admission, request, commandHash, envelopeHash);
    var partyAuthority = material.partyStageAuthority();
    assertThat(partyAuthority.partyStageWindowSeconds()).isEqualTo(partyWindowSeconds);
    assertThat(partyAuthority.hearingDeadlineAt()).isEqualTo(hearingDeadline);
    assertThat(partyAuthority.tenantSurrogate()).isEqualTo(start.tenantSurrogate());
    assertThat(partyAuthority.caseId()).isEqualTo(start.caseId());
    assertThat(partyAuthority.roomEpoch()).isEqualTo(start.roomEpoch());
    assertThat(partyAuthority.fencingToken()).isEqualTo(start.fencingToken());
    assertThat(material.request().command().deadlineAt()).isEqualTo(graphDeadline);

    var answers = binding(
        start, HearingFlowStage.INTAKE_QUESTIONS_GENERATING,
        HearingFlowStage.PARTY_ANSWERS_OPEN, true);
    assertThatThrownBy(() -> answers.transitionFor("{}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("party deadline authority");
    var openedAnswers = answers.withPartyStageDeadline(partyAuthority, durableCompletion);
    var replayedAnswers = answers.withPartyStageDeadline(partyAuthority, durableCompletion);
    assertThat(openedAnswers).isEqualTo(replayedAnswers);
    assertThat(openedAnswers.transitionFor("{}").sharedDeadlineAt())
        .isEqualTo(runStartedAt.plusSeconds(1_490))
        .isNotEqualTo(graphDeadline);

    var evidence = binding(
        start, HearingFlowStage.EVIDENCE_REQUESTS_GENERATING,
        HearingFlowStage.PARTY_EVIDENCE_OPEN, true);
    assertThat(evidence.withPartyStageDeadline(partyAuthority, durableCompletion)
        .transitionFor("{}").sharedDeadlineAt())
        .isEqualTo(runStartedAt.plusSeconds(1_490));

    var clampedAuthority = partyAuthority(
        start, partyWindowSeconds, runStartedAt.plusSeconds(600));
    assertThat(answers.withPartyStageDeadline(clampedAuthority, durableCompletion)
        .transitionFor("{}").sharedDeadlineAt())
        .isEqualTo(runStartedAt.plusSeconds(600));

    var nonParty = binding(
        start, HearingFlowStage.JUDGE_V1_GENERATING,
        HearingFlowStage.JURY_REVIEWING, false);
    assertThat(nonParty.withPartyStageDeadline(null, durableCompletion)).isSameAs(nonParty);
    assertThat(nonParty.transitionFor("{}").sharedDeadlineAt()).isNull();

    assertThatThrownBy(() -> answers.withPartyStageDeadline(null, durableCompletion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("party deadline authority");
    var drifted = new TargetHearingCommandMaterial.PartyStageAuthority(
        TargetHearingCommandMaterial.PartyStageAuthority.SCHEMA_VERSION,
        start.tenantSurrogate(), "case-drift", start.roomEpoch(), start.fencingToken(),
        partyWindowSeconds, hearingDeadline);
    assertThatThrownBy(() -> answers.withPartyStageDeadline(drifted, durableCompletion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("party deadline coordinates");
    assertThatThrownBy(() -> partyAuthority(start, 0, hearingDeadline))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> partyAuthority(start, 1_201, hearingDeadline))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> answers.withPartyStageDeadline(
        partyAuthority(start, partyWindowSeconds, durableCompletion), durableCompletion))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already elapsed");

    TargetHearingCommandMaterial external = new TargetHearingCommandMaterial(
        TargetHearingCommandMaterial.SCHEMA_VERSION, admission, request, commandHash, envelopeHash);
    assertThat(external.partyStageAuthority()).isNull();
  }

  private static JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding binding(
      HearingRoomStart start, HearingFlowStage source, HearingFlowStage target,
      boolean matrixRequired) {
    var authority = new HearingAuthorityExpectation(
        start.tenantSurrogate(), start.caseId(), start.flowInstanceId(), start.epochId(),
        start.roomEpoch(), HearingWriterMode.TEMPORAL, source, source.ordinal() + 1,
        start.initialProcessRevision(), start.initialRoomRevision(), start.fencingToken());
    var matrix = matrixRequired
        ? new JdbcTargetHearingFormalAuthorityLoader.MatrixAuthority(1, "a".repeat(64))
        : null;
    return new JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding(
        authority, "stage-" + (source.ordinal() + 1), target, target.ordinal() + 1, null,
        "stage-" + (target.ordinal() + 1), "{}", "hearing-control", matrix,
        new JdbcTargetHearingFormalAuthorityLoader.Parents(null, null, null));
  }

  private static TargetHearingCommandMaterial.PartyStageAuthority partyAuthority(
      HearingRoomStart start, long partyWindowSeconds, Instant hearingDeadline) {
    return new TargetHearingCommandMaterial.PartyStageAuthority(
        TargetHearingCommandMaterial.PartyStageAuthority.SCHEMA_VERSION,
        start.tenantSurrogate(), start.caseId(), start.roomEpoch(), start.fencingToken(),
        partyWindowSeconds, hearingDeadline);
  }
}
