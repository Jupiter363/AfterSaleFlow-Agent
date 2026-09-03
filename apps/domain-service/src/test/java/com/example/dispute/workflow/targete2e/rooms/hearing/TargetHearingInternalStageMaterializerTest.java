package com.example.dispute.workflow.targete2e.rooms.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunLedger.CreateLogicalRun;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunStreamProjection;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetHearingInternalStageMaterializerTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .findAndRegisterModules()
          .setSerializationInclusion(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          .disable(
              com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  void internalStagesUseDistinctReplayStableGraphThreads() {
    HearingRoomStart start = hearingStart();

    var first =
        TargetHearingInternalStageMaterializer.materializeIdentity(
            start, HearingWorkflowStage.INTAKE_QUESTIONS_GENERATING);
    var replay =
        TargetHearingInternalStageMaterializer.materializeIdentity(
            start, HearingWorkflowStage.INTAKE_QUESTIONS_GENERATING);
    var next =
        TargetHearingInternalStageMaterializer.materializeIdentity(
            start, HearingWorkflowStage.EVIDENCE_REQUESTS_GENERATING);

    assertThat(replay).isEqualTo(first);
    assertThat(replay.commandId()).isEqualTo(first.commandId());
    assertThat(replay.logicalRunId()).isEqualTo(first.logicalRunId());
    assertThat(replay.attemptId()).isEqualTo(first.attemptId());
    assertThat(replay.threadId()).isEqualTo(first.threadId());
    assertThat(next.commandId()).isNotEqualTo(first.commandId());
    assertThat(next.logicalRunId()).isNotEqualTo(first.logicalRunId());
    assertThat(next.attemptId()).isNotEqualTo(first.attemptId());
    assertThat(next.threadId()).isNotEqualTo(first.threadId());

    assertThat(first.commandId()).matches("hearing-stage:[0-9]+:[0-9a-f]{32}");
    assertThat(first.logicalRunId()).matches("target-hearing-run:[0-9a-f]{32}");
    assertThat(first.attemptId()).isEqualTo(first.logicalRunId() + ":1");
    assertThat(first.threadId()).matches("grt\\.v1\\.[0-9a-f]{32}");
    assertThat(next.threadId()).matches("grt\\.v1\\.[0-9a-f]{32}");
  }

  @Test
  void internalAgentRunBindsTheExactDurableHearingEpochIdentity() {
    HearingRoomStart start = hearingStart();

    String first = TargetHearingInternalStageMaterializer.authoritativeRoomEpochId(start);
    String replay = TargetHearingInternalStageMaterializer.authoritativeRoomEpochId(start);

    assertThat(first).isEqualTo(start.epochId());
    assertThat(replay).isEqualTo(first);
    assertThat(first).isNotEqualTo(start.caseId() + ':' + start.roomEpoch());
  }

  @Test
  void evidenceSynthesisBudgetCoversEveryModelInvocationAndIsReplayStable() {
    ObjectNode request = evidenceSynthesisRequest(1, 1);

    int first =
        TargetHearingInternalStageMaterializer.providerAttemptBudget(
            "evidence_synthesis", request);
    int replay =
        TargetHearingInternalStageMaterializer.providerAttemptBudget(
            "evidence_synthesis", request.deepCopy());

    assertThat(first).isEqualTo(6);
    assertThat(replay).isEqualTo(first);
    assertThat(
            TargetHearingInternalStageMaterializer.providerAttemptBudget(
                "judge_v1", MAPPER.createObjectNode()))
        .isEqualTo(RoomGraphCommand.MODEL_INVOCATION_PROVIDER_ATTEMPT_LIMIT);
  }

  @Test
  void evidenceSynthesisBudgetFailsClosedWithoutTwoBoundPartyBatches() {
    ObjectNode missingBatches = MAPPER.createObjectNode();
    ObjectNode missingEvidence = MAPPER.createObjectNode();
    var partyBatches = missingEvidence.putArray("party_batches");
    partyBatches.addObject().putArray("evidence");
    partyBatches.addObject();

    assertThatThrownBy(
            () ->
                TargetHearingInternalStageMaterializer.providerAttemptBudget(
                    "evidence_synthesis", missingBatches))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("party batches");
    assertThatThrownBy(
            () ->
                TargetHearingInternalStageMaterializer.providerAttemptBudget(
                    "evidence_synthesis", missingEvidence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("batch evidence");
  }

  @Test
  void aggregateProviderBudgetIsBoundOnlyToHearingEvidenceSynthesis() {
    assertThat(
            command(
                    Instant.parse("2026-08-16T04:35:00Z"),
                    RoomType.HEARING,
                    "EVIDENCE_SYNTHESIZING",
                    6)
                .retryBudget()
                .providerAttemptsRemaining())
        .isEqualTo(6);

    assertThatThrownBy(
            () ->
                command(
                    Instant.parse("2026-08-16T04:35:00Z"),
                    RoomType.HEARING,
                    "JUDGE_V1_GENERATING",
                    6))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reserved");
    assertThatThrownBy(
            () ->
                command(
                    Instant.parse("2026-08-16T04:35:00Z"),
                    RoomType.EVIDENCE,
                    "EVIDENCE_SYNTHESIZING",
                    6))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reserved");
  }

  @Test
  void nonMicrosecondClockCanonicalizesInternalStageAuthorityBeforeHashing() {
    Instant rawNow = Instant.parse("2026-08-16T04:30:00.226116600Z");
    Instant canonicalNow = rawNow.truncatedTo(ChronoUnit.MICROS);
    Instant laterHearingDeadline = rawNow.plusSeconds(3_600).plusNanos(177);

    var authority =
        TargetHearingInternalStageMaterializer.authorityTimes(rawNow, laterHearingDeadline);
    var replay =
        TargetHearingInternalStageMaterializer.authorityTimes(rawNow, laterHearingDeadline);

    assertThat(authority).isEqualTo(replay);
    assertThat(authority.startedAt()).isEqualTo(canonicalNow);
    assertThat(authority.deadlineAt()).isEqualTo(canonicalNow.plusSeconds(300));
    assertThat(authority.startedAt().getNano() % 1_000).isZero();
    assertThat(authority.deadlineAt().getNano() % 1_000).isZero();

    Instant earlierHearingDeadline = rawNow.plusSeconds(120).plusNanos(199);
    var clamped =
        TargetHearingInternalStageMaterializer.authorityTimes(rawNow, earlierHearingDeadline);
    assertThat(clamped.startedAt()).isEqualTo(canonicalNow);
    assertThat(clamped.deadlineAt())
        .isEqualTo(earlierHearingDeadline.truncatedTo(ChronoUnit.MICROS));

    RoomGraphCommand command = command(authority.deadlineAt());
    AgentRunCommandBindingFactory.Binding binding =
        new AgentRunCommandBindingFactory(MAPPER).bind(
            new AgentRunCommandBindingFactory.Context(
                "ROOM_HEARING_TIME",
                "CASE_HEARING_TIME:0",
                "HEARING_INTAKE_QUESTIONS",
                command.commandId()),
            command);
    ExecuteAgentRunRequest request = new ExecuteAgentRunRequest(
        ExecuteAgentRunRequest.SCHEMA_VERSION,
        command.logicalRunId(),
        1,
        3,
        "agent-stream.v3",
        binding.logicalInputHash(),
        null,
        false,
        0,
        command);
    CreateLogicalRun logical = new CreateLogicalRun(
        command.logicalRunId(),
        command.tenantSurrogate(),
        command.caseId(),
        "ROOM_HEARING_TIME",
        "HEARING_INTAKE_QUESTIONS",
        command.commandId(),
        AgentRunProtocol.V3,
        AgentRunExecutorKind.TEMPORAL_ACTIVITY,
        "CASE_HEARING_TIME:0",
        RoomType.HEARING,
        0,
        command.processRevision(),
        3,
        command.requestHash(),
        binding.logicalInputHash(),
        3,
        command.deadlineAt(),
        authority.startedAt(),
        AgentRunStreamProjection.CASE_PARTICIPANTS);

    assertThat(request.command().deadlineAt()).isEqualTo(authority.deadlineAt());
    assertThat(logical.deadlineAt()).isEqualTo(authority.deadlineAt());
    assertThat(logical.createdAt()).isEqualTo(authority.startedAt());
    assertThat(logical.streamProjection())
        .isEqualTo(AgentRunStreamProjection.CASE_PARTICIPANTS);
    assertThat(binding.commandRequestHash()).isEqualTo(command.requestHash());
    assertThat(command(authority.deadlineAt())).isEqualTo(command);

    RoomGraphCommand nonCanonical = command(rawNow.plusSeconds(300));
    assertThat(nonCanonical.deadlineAt()).isNotEqualTo(command.deadlineAt());
    assertThat(nonCanonical.requestHash()).isNotEqualTo(command.requestHash());
  }

  private static RoomGraphCommand command(Instant deadlineAt) {
    return command(deadlineAt, RoomType.HEARING, "INTAKE_QUESTIONS_GENERATING", 2);
  }

  private static RoomGraphCommand command(
      Instant deadlineAt, RoomType roomType, String stageCode, int providerAttemptBudget) {
    RoomGraphCommand provisional = new RoomGraphCommand(
        "room-graph-command.v1",
        "hearing-stage:4:time-canonicalization",
        "target-hearing-run:time-canonicalization",
        "target-hearing-run:time-canonicalization:1",
        "legacy-default",
        "CASE_HEARING_TIME",
        roomType,
        0,
        "all-rooms.target-e2e.v1",
        "target-e2e-graph.2026-07-27.1",
        "target-e2e-checkpoint.v1",
        "grt.v1.0123456789abcdef0123456789abcdef",
        new RoomGraphCommand.ActorScope(
            "hearing-control",
            ActorRole.SYSTEM,
            Audience.SYSTEM,
            List.of("hearing:INTAKE_QUESTIONS_GENERATING")),
        14,
        stageCode,
        4,
        new RoomGraphCommand.SnapshotRef(
            "HEARING_STATE_TIME", "hearing-state.v1", "minio://hearing/state", hash('1'), 1),
        new RoomGraphCommand.SnapshotRef(
            "HEARING_EVENT_TIME", "hearing-event.v1", "minio://hearing/event", hash('2'), 1),
        new RoomGraphCommand.InvocationContext(
            "agent-hearing", "prompt-hearing", "model-hearing",
            "target-e2e-room-proposal-source.v1", "policy-hearing", "guardrail-hearing",
            List.of(), "key-hearing", "nonce-hearing"),
        new RoomGraphCommand.RetryBudget(providerAttemptBudget, 3, 1),
        deadlineAt,
        "00-0123456789abcdef0123456789abcdef-0000000000000001-01",
        hash('0'));
    var body = MAPPER.valueToTree(provisional);
    ((com.fasterxml.jackson.databind.node.ObjectNode) body).remove("request_hash");
    String requestHash = ContractJson.sha256Hex(body);
    return new RoomGraphCommand(
        provisional.schemaVersion(), provisional.commandId(), provisional.logicalRunId(),
        provisional.attemptId(), provisional.tenantSurrogate(), provisional.caseId(),
        provisional.roomType(), provisional.roomEpoch(), provisional.graphKey(),
        provisional.graphVersion(), provisional.checkpointSchemaVersion(), provisional.threadId(),
        provisional.actorScope(), provisional.processRevision(), provisional.stageCode(),
        provisional.stageSequence(), provisional.domainSnapshotRef(), provisional.eventRef(),
        provisional.invocationContext(), provisional.retryBudget(), provisional.deadlineAt(),
        provisional.traceparent(), requestHash);
  }

  private static String hash(char value) {
    return String.valueOf(value).repeat(64);
  }

  private static ObjectNode evidenceSynthesisRequest(
      int userEvidenceItems, int merchantEvidenceItems) {
    ObjectNode request = MAPPER.createObjectNode();
    var partyBatches = request.putArray("party_batches");
    var userEvidence = partyBatches.addObject().putArray("evidence");
    for (int index = 0; index < userEvidenceItems; index++) {
      userEvidence.addObject().put("evidence_id", "USER_EVIDENCE_" + index);
    }
    var merchantEvidence = partyBatches.addObject().putArray("evidence");
    for (int index = 0; index < merchantEvidenceItems; index++) {
      merchantEvidence.addObject().put("evidence_id", "MERCHANT_EVIDENCE_" + index);
    }
    return request;
  }

  private static HearingRoomStart hearingStart() {
    Instant openedAt = Instant.parse("2026-08-15T00:00:00Z");
    return new HearingRoomStart(
        "hearing-room-start.v1",
        "tenant-hearing-thread",
        "CASE_HEARING_THREAD_IDENTITY",
        "ROOM_HEARING_THREAD_IDENTITY",
        "FLOW_HEARING_THREAD_IDENTITY",
        "EPOCH_HEARING_THREAD_IDENTITY",
        HearingWriterMode.TEMPORAL,
        3,
        7,
        "user-hearing-thread",
        "merchant-hearing-thread",
        openedAt,
        openedAt.plusSeconds(3_600),
        300,
        11,
        5,
        "hearing-room-thread-test.v1");
  }
}
