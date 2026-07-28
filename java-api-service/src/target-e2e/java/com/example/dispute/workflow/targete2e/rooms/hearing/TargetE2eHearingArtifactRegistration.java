package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.hearing.application.finalization.HearingFormalReceiptService;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eRoomFinalizationStrategy;
import com.example.dispute.workflow.targete2e.graph.HttpTargetE2EGraphReconciliationClient;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeSigner;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphProposalPayloadSource;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;

/** Target artifact factory. TargetE2eControlConfiguration must register {@link #controlActivity}. */
public final class TargetE2eHearingArtifactRegistration {
  private TargetE2eHearingArtifactRegistration() {}

  /**
   * Builds the complete target-only Hearing finalization graph. Every evidence input is a durable
   * common port; no caller may supply an in-memory result as proof.
   */
  public static TargetHearingRegistrationBundle create(
      DataSource dataSource,
      TargetHearingCommandMaterialStore store,
      HearingFormalReceiptService receiptService,
      TargetHearingFormalCommandMapper formalCommandMapper,
      TargetE2EActivationLedger activationLedger,
      TargetE2eFinalizationActivationPort activation,
      TargetE2eFinalizationRuntimeContextProvider runtime,
      TargetE2EGraphEnvelopeCodec codec,
      TargetE2EGraphEnvelopeSigner signer,
      HttpTargetE2EGraphReconciliationClient reconciliation,
      TargetE2EGraphProposalPayloadSource proposalSource,
      GraphRegistryBindingPolicy registryBindings,
      ObjectMapper objectMapper) {
    var evidence = new ReconciledTargetHearingFinalizationEvidenceResolver(dataSource, activationLedger,
        codec, signer, reconciliation, proposalSource, registryBindings, runtime, objectMapper);
    TargetE2eRoomFinalizationStrategy strategy = new TargetHearingRoomFinalizationStrategy(store,
        evidence, activation, runtime, objectMapper);
    var completion = new TargetHearingFormalCompletion(receiptService);
    var resolver = new DurableTargetHearingFinalizationRequestResolver(
        store, formalCommandMapper, new JdbcTargetHearingFormalAuthorityLoader(dataSource));
    AgentRunDomainResultCommitter committer = new TargetHearingAgentRunDomainResultCommitter(dataSource,
        resolver, new HearingFormalReceiptTargetCommitPort(completion));
    return new TargetHearingRegistrationBundle(new TargetHearingCommandBridgeActivitiesImpl(store),
        completion, strategy, committer);
  }
  public static Class<TargetHearingCommandBridgeActivities> controlActivity() {
    return TargetHearingCommandBridgeActivities.class;
  }
}
