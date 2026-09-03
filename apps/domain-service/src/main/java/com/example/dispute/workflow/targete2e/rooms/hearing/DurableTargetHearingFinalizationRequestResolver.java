package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;
import java.util.Objects;

/**
 * Concrete resolver which starts from the admitted Hearing command row, not an untrusted Graph
 * callback. The supplied mapper is a pure proposal decoder; its result is re-bound here before it
 * reaches the formal port.
 */
public final class DurableTargetHearingFinalizationRequestResolver
    implements TargetHearingFinalizationRequestResolver {
  private final TargetHearingCommandMaterialStore materialStore;
  private final TargetHearingFormalCommandMapper mapper;
  private final JdbcTargetHearingFormalAuthorityLoader authorityLoader;

  public DurableTargetHearingFinalizationRequestResolver(
      TargetHearingCommandMaterialStore materialStore, TargetHearingFormalCommandMapper mapper,
      JdbcTargetHearingFormalAuthorityLoader authorityLoader) {
    this.materialStore = Objects.requireNonNull(materialStore, "materialStore");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.authorityLoader = Objects.requireNonNull(authorityLoader, "authorityLoader");
  }

  @Override
  public TargetHearingFinalizationRequest resolve(CommitCommand command) {
    command = Objects.requireNonNull(command, "command");
    var graph = command.request().command();
    var material = materialStore.readByCommand(new TargetHearingCommandMaterialStore.CommandRoute(
        graph.tenantSurrogate(), graph.caseId(), graph.commandId(), graph.roomEpoch()))
        .orElseThrow(() -> new IllegalStateException("target Hearing finalization material is absent"));
    if (!material.material().request().equals(command.request())) {
      throw new IllegalStateException("target Hearing formal resolver material is not the AgentRun request");
    }
    TargetHearingFinalizationRequest request = Objects.requireNonNull(
        mapper.map(command, material, authorityLoader.load(command, material)),
        "target Hearing formal mapper result");
    if (request.material() != material && !request.material().equals(material)) {
      throw new IllegalStateException("target Hearing formal mapper changed durable material");
    }
    return request;
  }
}
