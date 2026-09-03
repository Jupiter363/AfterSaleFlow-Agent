package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Typed hash-source document whose {@code /proposal} value is the proposal hash preimage. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TargetE2ERoomProposalSource(
    String schemaVersion, RoomType roomType, Proposal proposal) {

  public static final String SCHEMA_VERSION = "target-e2e-room-proposal-source.v2";
  private static final Map<RoomType, String> PROPOSAL_VERSIONS =
      Map.of(
          RoomType.INTAKE, "target-e2e-intake-proposal.v1",
          RoomType.EVIDENCE, "target-e2e-evidence-proposal.v2",
          RoomType.HEARING, "target-e2e-hearing-proposal.v1",
          RoomType.REVIEW, "target-e2e-review-proposal.v1");

  public TargetE2ERoomProposalSource {
    TargetE2EGraphCommandEnvelope.requireConstant(schemaVersion, SCHEMA_VERSION, "schemaVersion");
    Objects.requireNonNull(roomType, "roomType");
    Objects.requireNonNull(proposal, "proposal");
    TargetE2EGraphCommandEnvelope.requireConstant(
        proposal.schemaVersion(), PROPOSAL_VERSIONS.get(roomType), "proposal.schemaVersion");
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Proposal(
      String schemaVersion,
      String proposalId,
      String commandId,
      String logicalRunId,
      String attemptId,
      String payloadSchemaVersion,
      String payloadRef,
      String payloadHash,
      TerminalClass terminalClass,
      boolean formalAuthority) {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    private static final Pattern PAYLOAD_REFERENCE =
        Pattern.compile("urn:target-e2e:proposal:.{1,488}");

    public Proposal {
      requireIdentifier(schemaVersion, "schemaVersion");
      requireIdentifier(proposalId, "proposalId");
      requireIdentifier(commandId, "commandId");
      requireIdentifier(logicalRunId, "logicalRunId");
      requireIdentifier(attemptId, "attemptId");
      requireIdentifier(payloadSchemaVersion, "payloadSchemaVersion");
      if (payloadRef == null
          || payloadRef.length() > 512
          || !PAYLOAD_REFERENCE.matcher(payloadRef).matches()) {
        throw new IllegalArgumentException("payloadRef is invalid");
      }
      TargetE2EGraphCommandEnvelope.requirePattern(
          payloadHash, TargetE2EGraphCommandEnvelope.SHA256, "payloadHash");
      Objects.requireNonNull(terminalClass, "terminalClass");
      if (formalAuthority) {
        throw new IllegalArgumentException("Graph proposal cannot carry formal authority");
      }
    }

    private static void requireIdentifier(String value, String field) {
      if (value == null || !IDENTIFIER.matcher(value).matches()) {
        throw new IllegalArgumentException(field + " is not a bounded identifier");
      }
    }
  }

  public enum TerminalClass {
    NEEDS_INPUT,
    COMPLETED,
    NEEDS_REVIEW
  }
}
