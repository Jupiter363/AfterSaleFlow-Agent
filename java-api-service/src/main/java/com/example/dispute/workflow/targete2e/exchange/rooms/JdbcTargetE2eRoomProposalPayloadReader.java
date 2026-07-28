package com.example.dispute.workflow.targete2e.exchange.rooms;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.graph.TargetE2ERoomProposalSource;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingCommandMaterialStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import java.security.MessageDigest;
import java.util.Objects;

/** Reads only the unique durable index row; no MinIO key is inferred from a proposal reference. */
public final class JdbcTargetE2eRoomProposalPayloadReader implements TargetE2eRoomProposalPayloadReader {
  private final TargetE2eRoomObjectIndex index;
  private final io.minio.MinioClient minio;
  private final ObjectMapper mapper;

  public JdbcTargetE2eRoomProposalPayloadReader(TargetE2eRoomObjectIndex index,
      io.minio.MinioClient minio, ObjectMapper mapper) {
    this.index = Objects.requireNonNull(index); this.minio = Objects.requireNonNull(minio);
    this.mapper = Objects.requireNonNull(mapper).copy();
  }

  @Override public LoadedProposal load(TargetE2ERoomProposalSource.Proposal proposal,
      TargetHearingCommandMaterialStore.Snapshot material) {
    Objects.requireNonNull(proposal); Objects.requireNonNull(material);
    RoomGraphCommand command = material.material().request().command();
    if (!"HEARING".equals(command.roomType().name()) || !proposal.commandId().equals(command.commandId())
        || !proposal.logicalRunId().equals(command.logicalRunId()) || !proposal.attemptId().equals(command.attemptId())
        || proposal.formalAuthority()) throw new IllegalArgumentException("proposal does not bind admitted Hearing command");
    var admission = material.admission();
    TargetE2eRoomObjectIndex.ProposalLookup lookup = new TargetE2eRoomObjectIndex.ProposalLookup(
        admission.activationId(), admission.tenantSurrogate(), admission.caseId(), "HEARING", admission.roomEpoch(),
        admission.roomFencingToken(), command.commandId(), command.logicalRunId(), command.attemptId(), proposal.proposalId(),
        proposal.payloadSchemaVersion(), proposal.payloadRef(), proposal.payloadHash());
    TargetE2eRoomObjectIndex.StoredObject stored = index.findProposal(lookup)
        .orElseThrow(() -> new IllegalArgumentException("durable Hearing proposal index row is absent"));
    byte[] payload = read(stored);
    if (payload.length != stored.sizeBytes() || !hash(payload).equals(stored.sha256())
        || !stored.sha256().equals(proposal.payloadHash())) throw new IllegalStateException("durable Hearing proposal bytes drifted");
    try {
      JsonNode document = mapper.readTree(payload);
      if (document == null || !MessageDigest.isEqual(payload, ContractJson.canonicalize(document))
          || !proposal.payloadSchemaVersion().equals(document.path("schema_version").asText())) {
        throw new IllegalStateException("durable Hearing proposal is not exact canonical schema");
      }
    } catch (Exception failure) {
      if (failure instanceof IllegalStateException rejected) throw rejected;
      throw new IllegalStateException("durable Hearing proposal is malformed", failure);
    }
    return new LoadedProposal(proposal.proposalId(), proposal.payloadSchemaVersion(), proposal.payloadRef(),
        proposal.payloadHash(), stored.sizeBytes(), payload);
  }

  private byte[] read(TargetE2eRoomObjectIndex.StoredObject object) {
    try (var input = minio.getObject(GetObjectArgs.builder().bucket(object.storageBucket()).object(object.storageKey()).build())) {
      return input.readNBytes(Math.toIntExact(object.sizeBytes()) + 1);
    } catch (Exception failure) { throw new IllegalStateException("durable Hearing proposal cannot be read", failure); }
  }
  private static String hash(byte[] value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (Exception failure) { throw new IllegalStateException(failure); } }
}
