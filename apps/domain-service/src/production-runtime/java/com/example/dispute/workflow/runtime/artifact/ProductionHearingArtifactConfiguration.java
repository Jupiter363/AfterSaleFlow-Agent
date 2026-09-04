package com.example.dispute.workflow.runtime.artifact;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.hearing.application.finalization.HearingFormalReceiptService;
import com.example.dispute.hearing.domain.HearingAuthorityLedger;
import com.example.dispute.hearing.infrastructure.persistence.JdbcHearingAuthorityLedger;
import com.example.dispute.hearing.infrastructure.persistence.JdbcHearingFormalFinalizer;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.runtime.exchange.rooms.JdbcProductionRoomObjectIndex;
import com.example.dispute.workflow.runtime.exchange.rooms.JdbcProductionRoomProposalPayloadReader;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomProposalPayloadReader;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationRuntimeContextProvider;
import com.example.dispute.workflow.runtime.finalization.ProductionRoomFinalizationStrategy;
import com.example.dispute.workflow.runtime.graph.HttpProductionGraphReconciliationClient;
import com.example.dispute.workflow.runtime.graph.HttpProductionGraphProposalSourceClient;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeCodec;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeSigner;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger;
import com.example.dispute.workflow.runtime.rooms.hearing.ReconciledTargetHearingFinalizationEvidenceResolver;
import com.example.dispute.workflow.runtime.rooms.hearing.ReconciledTargetHearingFormalCommandMapper;
import com.example.dispute.workflow.runtime.rooms.hearing.ProductionHearingArtifactRegistration;
import com.example.dispute.workflow.runtime.rooms.hearing.TargetHearingCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.hearing.TargetHearingFinalizationEvidenceResolver;
import com.example.dispute.workflow.runtime.rooms.hearing.TargetHearingFormalCommandMapper;
import com.example.dispute.workflow.runtime.rooms.hearing.TargetHearingRegistrationBundle;
import com.example.dispute.workflow.runtime.rooms.hearing.JdbcTargetHearingPublicTranscriptCommitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit AGENT-side Hearing assembly. The room strategy and domain committer are intentionally
 * registered independently so the existing multi-room outer finalizer remains the sole gateway.
 */
@Configuration(proxyBeanMethods = false)
@Profile(ProductionArtifactPrerequisites.REQUIRED_PROFILE)
@ConditionalOnProperty(
    name = ProductionArtifactPrerequisites.WORKER_ROLE_PROPERTY,
    havingValue = ProductionArtifactPrerequisites.AGENT_WORKER_ROLE)
public class ProductionHearingArtifactConfiguration {

  @Bean
  TargetHearingCommandMaterialStore productionHearingCommandMaterialStore(
      DataSource dataSource,
      ProductionActivationLedger productionAgentActivationLedger,
      ObjectMapper objectMapper) {
    return new com.example.dispute.workflow.runtime.rooms.hearing
        .JdbcTargetHearingCommandMaterialStore(
            dataSource, productionAgentActivationLedger, objectMapper);
  }

  @Bean
  TargetHearingFinalizationEvidenceResolver productionHearingFinalizationEvidenceResolver(
      DataSource dataSource,
      ProductionActivationLedger productionAgentActivationLedger,
      ProductionGraphEnvelopeCodec codec,
      ProductionGraphEnvelopeSigner signer,
      HttpProductionGraphReconciliationClient reconciliation,
      HttpProductionGraphProposalSourceClient proposalSource,
      GraphRegistryBindingPolicy registryBindings,
      ProductionFinalizationRuntimeContextProvider runtime,
      ObjectMapper objectMapper) {
    return new ReconciledTargetHearingFinalizationEvidenceResolver(
        dataSource,
        productionAgentActivationLedger,
        codec,
        signer,
        reconciliation,
        proposalSource,
        registryBindings,
        runtime,
        objectMapper);
  }

  @Bean
  ProductionRoomProposalPayloadReader productionHearingProposalPayloadReader(
      DataSource dataSource, MinioClient minioClient, ObjectMapper objectMapper) {
    return new JdbcProductionRoomProposalPayloadReader(
        new JdbcProductionRoomObjectIndex(dataSource), minioClient, objectMapper);
  }

  @Bean
  TargetHearingFormalCommandMapper productionHearingFormalCommandMapper(
      TargetHearingFinalizationEvidenceResolver evidenceResolver,
      ProductionRoomProposalPayloadReader proposalPayloadReader,
      ObjectMapper objectMapper) {
    return new ReconciledTargetHearingFormalCommandMapper(
        evidenceResolver, proposalPayloadReader, objectMapper);
  }

  @Bean
  HearingAuthorityLedger productionHearingAuthorityLedger(
      DataSource dataSource, PlatformTransactionManager transactionManager) {
    return new JdbcHearingAuthorityLedger(
        new NamedParameterJdbcTemplate(dataSource), new TransactionTemplate(transactionManager));
  }

  @Bean
  HearingFormalReceiptService productionHearingFormalReceiptService(
      DataSource dataSource, HearingAuthorityLedger productionHearingAuthorityLedger) {
    return new HearingFormalReceiptService(
        new JdbcHearingFormalFinalizer(
            new NamedParameterJdbcTemplate(dataSource), productionHearingAuthorityLedger));
  }

  @Bean
  JdbcTargetHearingPublicTranscriptCommitter targetHearingPublicTranscriptCommitter(
      DataSource dataSource,
      ObjectMapper objectMapper,
      CaseEventService caseEventService) {
    return new JdbcTargetHearingPublicTranscriptCommitter(
        dataSource, objectMapper, caseEventService::wakeUp);
  }

  @Bean
  TargetHearingRegistrationBundle productionHearingRegistrationBundle(
      DataSource dataSource,
      TargetHearingCommandMaterialStore productionHearingCommandMaterialStore,
      HearingFormalReceiptService productionHearingFormalReceiptService,
      TargetHearingFormalCommandMapper productionHearingFormalCommandMapper,
      ProductionActivationLedger productionAgentActivationLedger,
      ProductionFinalizationActivationPort productionFinalizationAuthority,
      ProductionFinalizationRuntimeContextProvider runtime,
      ProductionGraphEnvelopeCodec codec,
      ProductionGraphEnvelopeSigner signer,
      HttpProductionGraphReconciliationClient reconciliation,
      HttpProductionGraphProposalSourceClient proposalSource,
      GraphRegistryBindingPolicy registryBindings,
      ObjectMapper objectMapper,
      JdbcTargetHearingPublicTranscriptCommitter targetHearingPublicTranscriptCommitter) {
    return ProductionHearingArtifactRegistration.create(
        dataSource,
        productionHearingCommandMaterialStore,
        productionHearingFormalReceiptService,
        productionHearingFormalCommandMapper,
        productionAgentActivationLedger,
        productionFinalizationAuthority,
        runtime,
        codec,
        signer,
        reconciliation,
        proposalSource,
        registryBindings,
        objectMapper,
        targetHearingPublicTranscriptCommitter);
  }

  @Bean
  ProductionRoomFinalizationStrategy productionHearingRoomFinalizationStrategy(
      TargetHearingRegistrationBundle productionHearingRegistrationBundle) {
    return productionHearingRegistrationBundle.finalizationStrategy();
  }

  @Bean
  AgentRunDomainResultCommitter productionHearingDomainResultCommitter(
      TargetHearingRegistrationBundle productionHearingRegistrationBundle) {
    return productionHearingRegistrationBundle.domainCommitter();
  }
}
