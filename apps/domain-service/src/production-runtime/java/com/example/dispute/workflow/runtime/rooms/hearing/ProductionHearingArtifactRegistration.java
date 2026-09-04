package com.example.dispute.workflow.runtime.rooms.hearing;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.hearing.application.finalization.HearingFormalReceiptService;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationRuntimeContextProvider;
import com.example.dispute.workflow.runtime.finalization.ProductionRoomFinalizationStrategy;
import com.example.dispute.workflow.runtime.graph.HttpProductionGraphReconciliationClient;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeCodec;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeSigner;
import com.example.dispute.workflow.runtime.graph.ProductionGraphProposalPayloadSource;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;

/** Production runtime artifact factory. ProductionControlConfiguration must register {@link #controlActivity}. */
public final class ProductionHearingArtifactRegistration {
  private ProductionHearingArtifactRegistration() {}

  /**
   * Builds the complete production-only Hearing finalization graph. Every evidence input is a durable
   * common port; no caller may supply an in-memory result as proof.
   */
  public static TargetHearingRegistrationBundle create(
      DataSource dataSource,
      TargetHearingCommandMaterialStore store,
      HearingFormalReceiptService receiptService,
      TargetHearingFormalCommandMapper formalCommandMapper,
      ProductionActivationLedger activationLedger,
      ProductionFinalizationActivationPort activation,
      ProductionFinalizationRuntimeContextProvider runtime,
      ProductionGraphEnvelopeCodec codec,
      ProductionGraphEnvelopeSigner signer,
      HttpProductionGraphReconciliationClient reconciliation,
      ProductionGraphProposalPayloadSource proposalSource,
      GraphRegistryBindingPolicy registryBindings,
      ObjectMapper objectMapper,
      JdbcTargetHearingPublicTranscriptCommitter transcript) {
    var evidence = new ReconciledTargetHearingFinalizationEvidenceResolver(dataSource, activationLedger,
        codec, signer, reconciliation, proposalSource, registryBindings, runtime, objectMapper);
    ProductionRoomFinalizationStrategy strategy = new TargetHearingRoomFinalizationStrategy(store,
        evidence, activation, runtime, objectMapper);
    var completion = new TargetHearingFormalCompletion(receiptService);
    var resolver = new DurableTargetHearingFinalizationRequestResolver(
        store, formalCommandMapper, new JdbcTargetHearingFormalAuthorityLoader(dataSource));
    AgentRunDomainResultCommitter committer = new TargetHearingAgentRunDomainResultCommitter(dataSource,
        resolver, new HearingFormalReceiptTargetCommitPort(
            dataSource, completion, transcript, objectMapper));
    return new TargetHearingRegistrationBundle(new TargetHearingCommandBridgeActivitiesImpl(store),
        completion, strategy, committer);
  }
  public static Class<TargetHearingCommandBridgeActivities> controlActivity() {
    return TargetHearingCommandBridgeActivities.class;
  }
}
