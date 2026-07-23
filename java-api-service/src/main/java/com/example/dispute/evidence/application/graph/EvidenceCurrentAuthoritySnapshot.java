package com.example.dispute.evidence.application.graph;

import com.example.dispute.evidence.application.graph.EvidenceFinalizationLedger.AuthorityRequirement;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Durable Java view of the authority row locked while a new finalization receipt is committed.
 *
 * <p>This is not reconstructed from Temporal History. Implementations must load it from the Java
 * authority store and hold the row lock until the receipt and terminal summary commit.
 */
public record EvidenceCurrentAuthoritySnapshot(
    String authoritySnapshotHash,
    String graphBindingId,
    String runtimeMode,
    String agentProfileId,
    String tenantSurrogate,
    String caseId,
    String roomId,
    long roomEpoch,
    long javaRoomFencingToken,
    String actorId,
    String actorRole,
    String participantId,
    String actorScopeHash,
    String agentSessionId,
    long sourceRevision,
    long processRevision,
    long roomRevision,
    List<String> currentFactIds,
    List<String> currentSourceRefs) {

  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  public EvidenceCurrentAuthoritySnapshot {
    hash(authoritySnapshotHash, "authoritySnapshotHash");
    requireText(graphBindingId, "graphBindingId");
    requireText(runtimeMode, "runtimeMode");
    requireText(agentProfileId, "agentProfileId");
    requireText(tenantSurrogate, "tenantSurrogate");
    requireText(caseId, "caseId");
    requireText(roomId, "roomId");
    requireText(actorId, "actorId");
    requireText(actorRole, "actorRole");
    requireText(participantId, "participantId");
    hash(actorScopeHash, "actorScopeHash");
    requireText(agentSessionId, "agentSessionId");
    if (roomEpoch < 0
        || javaRoomFencingToken < 1
        || sourceRevision < 1
        || processRevision < 0
        || roomRevision < 0
        || roomEpoch > MAX_SAFE_INTEGER
        || javaRoomFencingToken > MAX_SAFE_INTEGER
        || sourceRevision > MAX_SAFE_INTEGER
        || processRevision > MAX_SAFE_INTEGER
        || roomRevision > MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException("authority epoch, fence, or revision is invalid");
    }
    currentFactIds = canonicalReferences(currentFactIds, "currentFactIds");
    currentSourceRefs = canonicalReferences(currentSourceRefs, "currentSourceRefs");
  }

  public static EvidenceCurrentAuthoritySnapshot from(
      AuthorityRequirement requirement, String graphBindingId) {
    Objects.requireNonNull(requirement, "requirement");
    return new EvidenceCurrentAuthoritySnapshot(
        requirement.authoritySnapshotHash(),
        graphBindingId,
        requirement.runtimeMode(),
        requirement.agentProfileId(),
        requirement.tenantSurrogate(),
        requirement.caseId(),
        requirement.roomId(),
        requirement.roomEpoch(),
        requirement.javaRoomFencingToken(),
        requirement.actorId(),
        requirement.actorRole(),
        requirement.participantId(),
        requirement.actorScopeHash(),
        requirement.agentSessionId(),
        requirement.sourceRevision(),
        requirement.processRevision(),
        requirement.roomRevision(),
        requirement.currentFactIds(),
        requirement.currentSourceRefs());
  }

  /** Exact comparison prevents a caller from widening the trusted fact/source allowlists. */
  public boolean exactlyMatches(AuthorityRequirement requirement) {
    Objects.requireNonNull(requirement, "requirement");
    return authoritySnapshotHash.equals(requirement.authoritySnapshotHash())
        && runtimeMode.equals(requirement.runtimeMode())
        && agentProfileId.equals(requirement.agentProfileId())
        && tenantSurrogate.equals(requirement.tenantSurrogate())
        && caseId.equals(requirement.caseId())
        && roomId.equals(requirement.roomId())
        && roomEpoch == requirement.roomEpoch()
        && javaRoomFencingToken == requirement.javaRoomFencingToken()
        && actorId.equals(requirement.actorId())
        && actorRole.equals(requirement.actorRole())
        && participantId.equals(requirement.participantId())
        && actorScopeHash.equals(requirement.actorScopeHash())
        && agentSessionId.equals(requirement.agentSessionId())
        && sourceRevision == requirement.sourceRevision()
        && processRevision == requirement.processRevision()
        && roomRevision == requirement.roomRevision()
        && currentFactIds.equals(requirement.currentFactIds())
        && currentSourceRefs.equals(requirement.currentSourceRefs());
  }

  private static List<String> canonicalReferences(List<String> values, String field) {
    Objects.requireNonNull(values, field);
    List<String> copy = List.copyOf(values);
    if (copy.stream().anyMatch(value -> value == null || value.isBlank())
        || !copy.equals(copy.stream().sorted().toList())
        || copy.size() != copy.stream().distinct().count()) {
      throw new IllegalArgumentException(field + " must be sorted, unique, and non-blank");
    }
    return copy;
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  private static void hash(String value, String field) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }

  /** Live Graph-DB authority boundary. Implementations must not cache or use a Java observation. */
  @FunctionalInterface
  public interface GraphLeaseAuthority {
    void requireCurrent(GraphLeaseRequirement requirement);
  }

  public record GraphLeaseRequirement(
      String authoritySnapshotHash,
      String tenantSurrogate,
      String caseId,
      String roomId,
      long roomEpoch,
      long javaRoomFencingToken,
      String graphThreadId,
      long graphLeaseFencingToken) {
    public GraphLeaseRequirement {
      hash(authoritySnapshotHash, "authoritySnapshotHash");
      requireText(tenantSurrogate, "tenantSurrogate");
      requireText(caseId, "caseId");
      requireText(roomId, "roomId");
      if (roomEpoch < 0
          || javaRoomFencingToken < 1
          || graphLeaseFencingToken < 1
          || javaRoomFencingToken == graphLeaseFencingToken) {
        throw new IllegalArgumentException("Graph lease requirement fences are invalid");
      }
      if (graphThreadId == null || !graphThreadId.matches("^grt[.]v1[.][0-9a-f]{32}$")) {
        throw new IllegalArgumentException("graphThreadId is invalid");
      }
    }
  }
}
