package com.example.dispute.workflow.targete2e.exchange.rooms;

import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeContract.Authority;
import java.util.Optional;

/** Durable Java-only resolution index for opaque target room objects. */
public interface TargetE2eRoomObjectIndex {
  void bindInput(Authority authority, RoomGraphCommand command, StoredObject object, Kind kind);

  /** Rebinds immutable inputs to a later command in the same logical AgentRun. */
  void rebindInputs(
      Authority sourceAuthority,
      RoomGraphCommand sourceCommand,
      Authority targetAuthority,
      RoomGraphCommand targetCommand);

  StoredObject recordProposal(Authority authority, RoomGraphCommand command, ProposalIdentity proposal,
      String storageBucket, String storageKey);

  Optional<StoredObject> findAdmitted(Authority authority, RoomGraphCommand command,
      TargetE2eRoomExchangeContract.ObjectRef reference);

  Optional<StoredObject> findProposal(ProposalLookup lookup);

  enum Kind { COMMAND_INPUT, MANIFEST_ASSET, PROPOSAL }

  record StoredObject(String objectRef, String artifactId, String schemaVersion, String sha256,
      long sizeBytes, String storageBucket, String storageKey) {
    public StoredObject {
      if (objectRef == null || !objectRef.matches("urn:target-e2e:(object|proposal):.{1,480}")
          || artifactId == null || !artifactId.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
          || schemaVersion == null || !schemaVersion.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
          || sha256 == null || !sha256.matches("[0-9a-f]{64}") || sizeBytes < 1 || sizeBytes > 524288
          || storageBucket == null || storageBucket.isBlank() || storageKey == null || storageKey.isBlank()) {
        throw new IllegalArgumentException("target room object index entry is invalid");
      }
    }
  }

  record ProposalIdentity(String proposalId, String schemaVersion, String sha256, long sizeBytes,
      String checkpointNs, String checkpointId, long cognitiveRevision) {
    public ProposalIdentity {
      if (proposalId == null || !proposalId.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
          || schemaVersion == null || !schemaVersion.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
          || sha256 == null || !sha256.matches("[0-9a-f]{64}") || sizeBytes < 1 || sizeBytes > 65536
          || checkpointNs == null || checkpointNs.length() > 128 || checkpointId == null
          || checkpointId.isBlank() || checkpointId.length() > 128 || cognitiveRevision < 1) {
        throw new IllegalArgumentException("target room proposal identity is invalid");
      }
    }
  }

  record ProposalLookup(String activationId, String tenantSurrogate, String caseId, String roomType,
      long roomEpoch, long roomFencingToken, String commandId, String logicalRunId, String attemptId,
      String proposalId, String payloadSchemaVersion, String payloadRef, String payloadHash) {
    public ProposalLookup {
      if (activationId == null || !activationId.matches("p9act\\.v1\\.[0-9a-f]{32}")
          || tenantSurrogate == null || tenantSurrogate.isBlank() || caseId == null || caseId.isBlank()
          || !"HEARING".equals(roomType) || roomEpoch < 0 || roomFencingToken < 1
          || commandId == null || commandId.isBlank() || logicalRunId == null || logicalRunId.isBlank()
          || attemptId == null || attemptId.isBlank() || proposalId == null || proposalId.isBlank()
          || payloadSchemaVersion == null || payloadSchemaVersion.isBlank()
          || payloadRef == null || !payloadRef.matches("urn:target-e2e:proposal:.{1,488}")
          || payloadHash == null || !payloadHash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("target room proposal lookup is invalid");
      }
    }
  }
}
