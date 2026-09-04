package com.example.dispute.workflow.runtime.rooms.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;
import com.example.dispute.room.application.EvidenceAgentTurnCommand;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.CommandAdmission;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceTurnProposalLoader.LoadedProposal;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TargetEvidenceFinalizationRequestResolverTest {

  @Test
  void carriesTheMaterialFenceAndBothFrozenRevisionsIntoTheFormalRequest() {
    ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build()
        .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    RoomGraphCommand graph = graph();
    String commandHash = ContractJson.sha256Hex(
        mapper.copy()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .valueToTree(graph));
    assertThat(mapper.valueToTree(graph).has("room_id")).isFalse();
    String envelopeHash = "b".repeat(64);
    String manifestHash = "c".repeat(64);
    String databaseHash = "d".repeat(64);
    String caseCommandRequestHash = "9".repeat(64);
    String activationId = "p9act.v1." + "1".repeat(32);
    String admissionId = "p9cmd.v1." + "2".repeat(32);
    long fence = 19;
    long expectedRoomRevision = 23;

    ExecuteAgentRunRequest execution = mock(ExecuteAgentRunRequest.class);
    when(execution.command()).thenReturn(graph);
    when(execution.agentRunId()).thenReturn(graph.logicalRunId());
    ExecuteAgentRunResult result = mock(ExecuteAgentRunResult.class);
    RoomGraphResult graphResult = mock(RoomGraphResult.class);
    String resultHash = "e".repeat(64);
    when(result.outcome()).thenReturn(ExecuteAgentRunResult.Outcome.COMPLETED);
    when(result.resultHash()).thenReturn(resultHash);
    when(result.graphResult()).thenReturn(graphResult);
    when(graphResult.outputHash()).thenReturn(resultHash);
    when(graphResult.commandId()).thenReturn(graph.commandId());
    when(graphResult.graphKey()).thenReturn(graph.graphKey());
    when(graphResult.graphVersion()).thenReturn(graph.graphVersion());
    AgentExecutionManifest manifest = mock(AgentExecutionManifest.class);
    when(manifest.fencingToken()).thenReturn(fence);

    CommandAdmission admission =
        new CommandAdmission(
            activationId,
            manifestHash,
            databaseHash,
            graph.tenantSurrogate(),
            graph.caseId(),
            graph.commandId(),
            commandHash,
            envelopeHash,
            graph.roomEpoch(),
            fence);
    TargetEvidenceCommandMaterial material = mock(TargetEvidenceCommandMaterial.class);
    when(material.schemaVersion()).thenReturn(TargetEvidenceCommandMaterial.SCHEMA_VERSION);
    when(material.executionLane()).thenReturn(TargetEvidenceCommandMaterial.TARGET_LANE);
    when(material.activationId()).thenReturn(activationId);
    when(material.activationManifestHash()).thenReturn(manifestHash);
    when(material.roomFencingToken()).thenReturn(fence);
    when(material.expectedProcessRevision()).thenReturn(graph.processRevision());
    when(material.expectedRoomRevision()).thenReturn(expectedRoomRevision);
    when(material.commandHash()).thenReturn(commandHash);
    when(material.commandEnvelopeHash()).thenReturn(envelopeHash);
    when(material.caseCommandRequestHash()).thenReturn(caseCommandRequestHash);
    when(material.request()).thenReturn(execution);
    when(material.evidenceAgentTurnCommand()).thenReturn(mock(EvidenceAgentTurnCommand.class));
    TargetEvidenceCommandMaterialStore store = mock(TargetEvidenceCommandMaterialStore.class);
    var snapshot =
        new TargetEvidenceCommandMaterialStore.MaterialSnapshot(
            admissionId, admission, material, "f".repeat(64), Instant.EPOCH);
    when(store.readByRoute(
            new TargetEvidenceCommandMaterialStore.CommandLookup(
                graph.tenantSurrogate(),
                graph.caseId(),
                graph.commandId(),
                graph.roomEpoch(),
                fence)))
        .thenReturn(Optional.of(snapshot));
    CommitCommand command = new CommitCommand(execution, result, manifest);
    TargetEvidenceTurnProposalLoader proposalLoader = mock(TargetEvidenceTurnProposalLoader.class);
    LoadedProposal proposal = mock(LoadedProposal.class);
    TargetEvidenceTurnResultV2 turnResult = mock(TargetEvidenceTurnResultV2.class);
    String roomUtterance = "guarded Evidence Clerk message";
    when(turnResult.roomUtterance()).thenReturn(roomUtterance);
    when(proposal.proposalHash()).thenReturn("7".repeat(64));
    when(proposal.commandId()).thenReturn(graph.commandId());
    when(proposal.logicalRunId()).thenReturn(graph.logicalRunId());
    when(proposal.attemptId()).thenReturn(graph.attemptId());
    when(proposal.tenantSurrogate()).thenReturn(graph.tenantSurrogate());
    when(proposal.caseId()).thenReturn(graph.caseId());
    when(proposal.roomEpoch()).thenReturn(graph.roomEpoch());
    when(proposal.fencingToken()).thenReturn(fence);
    when(proposal.threadId()).thenReturn(graph.threadId());
    when(proposal.actorId()).thenReturn(graph.actorScope().actorId());
    when(proposal.actorRole()).thenReturn(graph.actorScope().actorRole().name());
    when(proposal.inputHash()).thenReturn(graph.domainSnapshotRef().sha256());
    when(proposal.roomUtterance()).thenReturn(roomUtterance);
    when(proposal.evidenceTurnResult()).thenReturn(turnResult);
    when(proposalLoader.load(command, snapshot)).thenReturn(proposal);

    TargetEvidenceFinalizationRequest resolved =
        new TargetEvidenceFinalizationRequestResolver(store, proposalLoader, mapper).resolve(command);

    assertThat(resolved.activationId()).isEqualTo(activationId);
    assertThat(resolved.activationManifestHash()).isEqualTo(manifestHash);
    assertThat(resolved.isolatedDomainDbBindingHash()).isEqualTo(databaseHash);
    assertThat(resolved.admissionId()).isEqualTo(admissionId);
    assertThat(resolved.roomFencingToken()).isEqualTo(fence);
    assertThat(resolved.expectedProcessRevision()).isEqualTo(graph.processRevision());
    assertThat(resolved.expectedRoomRevision()).isEqualTo(expectedRoomRevision);
    assertThat(resolved.caseCommandRequestHash()).isEqualTo(caseCommandRequestHash);
    assertThat(resolved.command()).isSameAs(command);
  }

  private static RoomGraphCommand graph() {
    return new RoomGraphCommand(
        "room-graph-command.v1",
        "evidence-submit:EVIDENCE_BATCH_1",
        "target-evidence-run:RUN_1",
        "target-evidence-run:RUN_1:1",
        "tenant-evidence",
        "CASE_EVIDENCE_1",
        RoomType.EVIDENCE,
        2,
        "all-rooms.production-runtime.v2",
        "production-runtime-graph.2026-08-18.1",
        "production-runtime-checkpoint.v2",
        "target-evidence-thread:USER_1",
        new RoomGraphCommand.ActorScope(
            "USER_1", ActorRole.USER, Audience.USER, List.of("case:evidence:submit")),
        11,
        "EVIDENCE_SEAL",
        11,
        new RoomGraphCommand.SnapshotRef(
            "EVIDENCE_MANIFEST_1",
            "production-runtime-evidence-manifest.v1",
            "urn:production-runtime:evidence-manifest:1",
            "3".repeat(64),
            400),
        new RoomGraphCommand.SnapshotRef(
            "EVENT_EVIDENCE_1",
            "production-runtime-evidence-submission.v1",
            "urn:production-runtime:timeline-event:EVENT_EVIDENCE_1",
            "4".repeat(64),
            321),
        new RoomGraphCommand.InvocationContext(
            "all-rooms-agent.production-runtime.v1",
            "all-rooms-prompt.production-runtime.v2",
            "production-runtime.contract-blocked",
            "production-runtime-room-proposal-source.v2",
            "all-rooms-policy.production-runtime.v1",
            "all-rooms-guardrail.production-runtime.v1",
            List.of(),
            "target-envelope-key",
            "target-envelope-nonce"),
        new RoomGraphCommand.RetryBudget(2, 3, 1),
        Instant.parse("2030-01-01T00:00:00Z"),
        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        "a".repeat(64));
  }
}
