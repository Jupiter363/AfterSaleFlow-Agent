package com.example.dispute.workflow.runtime.rooms.hearing;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;
import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingFlowActionType;
import com.example.dispute.hearing.domain.HearingFormalFinalizer;
import com.example.dispute.hearing.domain.HearingFormalRequestHash;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomProposalPayloadReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/** Fail-closed mapper from reconciled, indexed Hearing proposal bytes to Java formal facts. */
public final class ReconciledTargetHearingFormalCommandMapper implements TargetHearingFormalCommandMapper {
  private final TargetHearingFinalizationEvidenceResolver evidenceResolver;
  private final ProductionRoomProposalPayloadReader payloadReader;
  private final ObjectMapper mapper;
  private final TargetHearingFormalPayloadFactory payloads;

  public ReconciledTargetHearingFormalCommandMapper(
      TargetHearingFinalizationEvidenceResolver evidenceResolver,
      ProductionRoomProposalPayloadReader payloadReader,
      ObjectMapper mapper) {
    this.evidenceResolver = Objects.requireNonNull(evidenceResolver, "evidenceResolver");
    this.payloadReader = Objects.requireNonNull(payloadReader, "payloadReader");
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    this.payloads = new TargetHearingFormalPayloadFactory(mapper);
  }

  @Override public TargetHearingFinalizationRequest map(CommitCommand command,
      TargetHearingCommandMaterialStore.Snapshot material,
      JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding authority) {
    Objects.requireNonNull(command, "command"); Objects.requireNonNull(material, "material");
    Objects.requireNonNull(authority, "authority");
    var evidence = Objects.requireNonNull(evidenceResolver.resolve(command.request(), command.result(), material), "evidence");
    verifyEvidence(command, material, evidence, authority);
    authority = authority.withPartyStageDeadline(
        material.material().partyStageAuthority(), evidence.committedAt());
    var loaded = Objects.requireNonNull(payloadReader.load(evidence.proposalDescriptor(), material), "loaded proposal");
    verifyLoaded(evidence, loaded);
    JsonNode proposal;
    try { proposal = mapper.readTree(loaded.canonicalPayload()); }
    catch (Exception failure) { throw new IllegalStateException("target Hearing proposal is not JSON", failure); }
    if (proposal == null || !MessageDigest.isEqual(loaded.canonicalPayload(), ContractJson.canonicalize(proposal))) {
      throw new IllegalStateException("target Hearing proposal is not canonical");
    }
    String operation = operation(loaded.payloadSchemaVersion());
    verifyOperation(command, authority, proposal, operation);
    String formalId = switch (operation) {
      case "intake_questions" -> requiredText(
          proposal.path("question_set"), "question_set_id", "V4 question set");
      case "intake_synthesis" -> requiredText(
          proposal.path("issue_state_set"), "issue_state_set_id", "V4 issue state set");
      default -> stable(command.request().command().commandId(), evidence.proposalHash(),
          authority.authority().stageSequence(), operation);
    };
    var formal = payloads.project(operation, proposal, formalId, authority);
    var transition = authority.transitionFor(formal.stageOutputJson());
    var result = switch (operation) {
      case "intake_questions" -> action(command, authority, transition, formal, formalId, HearingFlowActionType.QUESTION_SET, evidence.committedAt());
      case "evidence_requests" -> action(command, authority, transition, formal, formalId, HearingFlowActionType.EVIDENCE_REQUEST_SET, evidence.committedAt());
      case "intake_synthesis" -> matrix(command, authority, transition, formal, HearingFormalFinalizer.MatrixKind.INTAKE, evidence.committedAt());
      case "evidence_synthesis" -> matrix(command, authority, transition, formal, HearingFormalFinalizer.MatrixKind.EVIDENCE, evidence.committedAt());
      case "judge_v1" -> decision(command, authority, transition, formal, formalId, HearingArtifactType.JUDGE_PROPOSAL, evidence.committedAt());
      case "jury_review" -> decision(command, authority, transition, formal, formalId, HearingArtifactType.JURY_REVIEW_REPORT, evidence.committedAt());
      case "judge_v2" -> decision(command, authority, transition, formal, formalId, HearingArtifactType.ADJUDICATION_DRAFT, evidence.committedAt());
      default -> throw new IllegalStateException("unreachable Hearing operation");
    };
    return new TargetHearingFinalizationRequest(material, result, formalId,
        command.request().command().stageCode(), command.request().command().stageSequence(),
        authority.actorId(), command.request().command().actorScope().actorRole(),
        command.request().command().actorScope().audience(), command);
  }

  private TargetHearingFinalizationRequest.GeneratedAction action(CommitCommand command,
      JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding binding,
      com.example.dispute.hearing.domain.HearingFormalTransition transition,
      TargetHearingFormalPayloadFactory.FormalPayload payload, String id, HearingFlowActionType type, java.time.Instant committedAt) {
    String requestHash = HearingFormalRequestHash.compute("ACTION", binding.authority(), transition, id, type,
        type.schemaVersion(), null, null, null, payload.contentHash(), command.request().agentRunId(),
        command.result().resultHash(), null, binding.actorId());
    var commit = commit(binding, requestHash, type.name(), committedAt);
    return new TargetHearingFinalizationRequest.GeneratedAction(new HearingFormalFinalizer.ActionCommand(commit,
        transition, id, type, type.schemaVersion(), null, null, null, payload.json(), payload.contentHash(),
        command.request().agentRunId(), command.result().resultHash(), null, binding.actorId()));
  }

  private TargetHearingFinalizationRequest.MatrixSynthesis matrix(CommitCommand command,
      JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding binding,
      com.example.dispute.hearing.domain.HearingFormalTransition transition,
      TargetHearingFormalPayloadFactory.FormalPayload payload, HearingFormalFinalizer.MatrixKind kind, java.time.Instant committedAt) {
    String requestHash = HearingFormalRequestHash.compute("MATRIX_SYNTHESIS", binding.authority(), transition, kind,
        payload.contentHash(), command.request().agentRunId(), command.result().resultHash(), binding.actorId());
    var commit = commit(binding, requestHash, kind.schemaVersion(), committedAt);
    return new TargetHearingFinalizationRequest.MatrixSynthesis(new HearingFormalFinalizer.MatrixSynthesisCommand(
        commit, transition, kind, payload.json(), payload.contentHash(), command.request().agentRunId(),
        command.result().resultHash(), binding.actorId()));
  }

  private TargetHearingFinalizationRequest.Decision decision(CommitCommand command,
      JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding binding,
      com.example.dispute.hearing.domain.HearingFormalTransition transition,
      TargetHearingFormalPayloadFactory.FormalPayload payload, String id, HearingArtifactType type, java.time.Instant committedAt) {
    var parents = binding.parents(); var dossier = required(parents.dossier(), "dossier");
    String proposalId = type == HearingArtifactType.JUDGE_PROPOSAL ? null : required(parents.proposal(), "proposal").id();
    String proposalHash = type == HearingArtifactType.JUDGE_PROPOSAL ? null : parents.proposal().hash();
    String reportId = type == HearingArtifactType.ADJUDICATION_DRAFT ? required(parents.report(), "report").id() : null;
    String reportHash = type == HearingArtifactType.ADJUDICATION_DRAFT ? parents.report().hash() : null;
    String requestHash = HearingFormalRequestHash.compute("DECISION", binding.authority(), transition, type, id,
        payload.contentHash(), dossier.id(), dossier.hash(), proposalId, proposalHash, reportId, reportHash,
        command.request().agentRunId(), command.result().resultHash(), binding.actorId());
    var commit = commit(binding, requestHash, type.schemaVersion(), committedAt);
    return new TargetHearingFinalizationRequest.Decision(new HearingFormalFinalizer.DecisionCommand(commit,
        transition, type, id, payload.contentHash(), dossier.id(), dossier.hash(), proposalId, proposalHash,
        reportId, reportHash, payload.json(), command.request().agentRunId(), command.result().resultHash(), binding.actorId()));
  }

  private static HearingAuthorityCommit commit(JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding binding,
      String requestHash, String suffix, java.time.Instant committedAt) {
    var authority = binding.authority();
    return new HearingAuthorityCommit(HearingAuthorityCommit.SCHEMA_VERSION, authority,
        HearingAuthorityCommit.OperationType.FINALIZE, "hearing.finalize:" + authority.tenantSurrogate() + ':'
        + authority.caseId() + ':' + authority.roomEpoch() + ':' + authority.stageSequence() + ':' + suffix + ':' + requestHash,
        requestHash, null, committedAt);
  }

  private static void verifyEvidence(CommitCommand command, TargetHearingCommandMaterialStore.Snapshot material,
      TargetHearingFinalizationEvidence evidence, JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding authority) {
    var graph = command.request().command(); var proposal = evidence.proposalDescriptor();
    require(evidence.roomFencingToken() == material.admission().roomFencingToken()
        && evidence.commandHash().equals(material.admission().commandHash())
        && evidence.commandEnvelopeHash().equals(material.admission().commandEnvelopeHash())
        && evidence.materialSha256().equals(material.materialSha256())
        && evidence.manifestFacts().fencingToken() == material.admission().roomFencingToken()
        && proposal.commandId().equals(graph.commandId()) && proposal.logicalRunId().equals(graph.logicalRunId())
        && proposal.attemptId().equals(graph.attemptId()) && !proposal.formalAuthority()
        && authority.authority().fencingToken() == material.admission().roomFencingToken(), "reconciled evidence binding");
  }
  private static void verifyLoaded(TargetHearingFinalizationEvidence evidence, ProductionRoomProposalPayloadReader.LoadedProposal loaded) {
    var descriptor = evidence.proposalDescriptor();
    require(loaded.proposalId().equals(descriptor.proposalId()) && loaded.payloadSchemaVersion().equals(descriptor.payloadSchemaVersion())
        && loaded.payloadRef().equals(descriptor.payloadRef()) && loaded.sha256().equals(descriptor.payloadHash())
        && loaded.sha256().equals(evidence.proposal().sha256()) && loaded.sizeBytes() == loaded.canonicalPayload().length,
        "indexed proposal binding");
  }
  private static void verifyOperation(CommitCommand command, JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding authority,
      JsonNode proposal, String operation) {
    var graph = command.request().command();
    String expectedStage = switch (operation) { case "intake_questions" -> "INTAKE_QUESTIONS_GENERATING"; case "intake_synthesis" -> "INTAKE_SYNTHESIZING"; case "evidence_requests" -> "EVIDENCE_REQUESTS_GENERATING"; case "evidence_synthesis" -> "EVIDENCE_SYNTHESIZING"; case "judge_v1" -> "JUDGE_V1_GENERATING"; case "jury_review" -> "JURY_REVIEWING"; case "judge_v2" -> "JUDGE_V2_GENERATING"; default -> throw new IllegalArgumentException("operation"); };
    require(expectedStage.equals(graph.stageCode()) && authority.authority().stage().name().equals(expectedStage)
        && proposal.path("case_id").asText().equals(graph.caseId())
        && proposal.path("workflow_id").asText().equals(authority.authority().flowInstanceId())
        && proposal.path("stage_sequence").asLong() == graph.stageSequence(),
        "operation stage binding");
  }
  private static String operation(String schema) { return switch (schema) { case "hearing_intake_questions.v5" -> "intake_questions"; case "hearing_intake_synthesis.v5" -> "intake_synthesis"; case "hearing_evidence_requests.v1" -> "evidence_requests"; case "hearing_evidence_synthesis.v1" -> "evidence_synthesis"; case "hearing_judge_v1.v2" -> "judge_v1"; case "hearing_jury_review.v1" -> "jury_review"; case "hearing_judge_v2.v2" -> "judge_v2"; default -> throw new IllegalArgumentException("unsupported target Hearing payload schema"); }; }
  private static String requiredText(JsonNode source, String field, String label) {
    if (!source.isObject() || !source.path(field).isTextual()
        || source.path(field).asText().isBlank()) {
      throw new IllegalArgumentException("target Hearing " + label + " identity is invalid");
    }
    return source.path(field).asText();
  }
  private static JdbcTargetHearingFormalAuthorityLoader.Ref required(JdbcTargetHearingFormalAuthorityLoader.Ref value, String label) { if (value == null) throw new IllegalStateException("target Hearing " + label + " parent is absent"); return value; }
  private static String stable(String commandId, String proposalHash, int stage, String operation) { return "hearing-" + operation + '-' + ContractJson.sha256Hex(new ObjectMapper().valueToTree(List.of(commandId, proposalHash, stage, operation))).substring(0, 32); }
  private static void require(boolean condition, String label) { if (!condition) throw new IllegalStateException("target Hearing " + label + " drifted"); }
}
