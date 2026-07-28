package com.example.dispute.workflow.targete2e.artifact;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.hearing.application.finalization.HearingFormalReceiptService;
import com.example.dispute.hearing.domain.HearingAuthorityLedger;
import com.example.dispute.hearing.infrastructure.persistence.JdbcHearingAuthorityLedger;
import com.example.dispute.hearing.infrastructure.persistence.JdbcHearingFormalFinalizer;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.targete2e.exchange.rooms.JdbcTargetE2eRoomObjectIndex;
import com.example.dispute.workflow.targete2e.exchange.rooms.JdbcTargetE2eRoomProposalPayloadReader;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomProposalPayloadReader;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eRoomFinalizationStrategy;
import com.example.dispute.workflow.targete2e.graph.HttpTargetE2EGraphReconciliationClient;
import com.example.dispute.workflow.targete2e.graph.HttpTargetE2EGraphProposalSourceClient;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeSigner;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
import com.example.dispute.workflow.targete2e.rooms.hearing.ReconciledTargetHearingFinalizationEvidenceResolver;
import com.example.dispute.workflow.targete2e.rooms.hearing.ReconciledTargetHearingFormalCommandMapper;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetE2eHearingArtifactRegistration;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingFinalizationEvidenceResolver;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingFormalCommandMapper;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingRegistrationBundle;
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
@Profile(TargetE2eArtifactPrerequisites.REQUIRED_PROFILE)
@ConditionalOnProperty(
    name = TargetE2eArtifactPrerequisites.WORKER_ROLE_PROPERTY,
    havingValue = TargetE2eArtifactPrerequisites.AGENT_WORKER_ROLE)
public class TargetE2eHearingArtifactConfiguration {

  @Bean
  TargetHearingCommandMaterialStore targetE2eHearingCommandMaterialStore(
      DataSource dataSource,
      TargetE2EActivationLedger targetE2eAgentActivationLedger,
      ObjectMapper objectMapper) {
    return new com.example.dispute.workflow.targete2e.rooms.hearing
        .JdbcTargetHearingCommandMaterialStore(
            dataSource, targetE2eAgentActivationLedger, objectMapper);
  }

  @Bean
  TargetHearingFinalizationEvidenceResolver targetE2eHearingFinalizationEvidenceResolver(
      DataSource dataSource,
      TargetE2EActivationLedger targetE2eAgentActivationLedger,
      TargetE2EGraphEnvelopeCodec codec,
      TargetE2EGraphEnvelopeSigner signer,
      HttpTargetE2EGraphReconciliationClient reconciliation,
      HttpTargetE2EGraphProposalSourceClient proposalSource,
      GraphRegistryBindingPolicy registryBindings,
      TargetE2eFinalizationRuntimeContextProvider runtime,
      ObjectMapper objectMapper) {
    return new ReconciledTargetHearingFinalizationEvidenceResolver(
        dataSource,
        targetE2eAgentActivationLedger,
        codec,
        signer,
        reconciliation,
        proposalSource,
        registryBindings,
        runtime,
        objectMapper);
  }

  @Bean
  TargetE2eRoomProposalPayloadReader targetE2eHearingProposalPayloadReader(
      DataSource dataSource, MinioClient minioClient, ObjectMapper objectMapper) {
    return new JdbcTargetE2eRoomProposalPayloadReader(
        new JdbcTargetE2eRoomObjectIndex(dataSource), minioClient, objectMapper);
  }

  @Bean
  TargetHearingFormalCommandMapper targetE2eHearingFormalCommandMapper(
      TargetHearingFinalizationEvidenceResolver evidenceResolver,
      TargetE2eRoomProposalPayloadReader proposalPayloadReader,
      ObjectMapper objectMapper) {
    return new ReconciledTargetHearingFormalCommandMapper(
        evidenceResolver, proposalPayloadReader, objectMapper);
  }

  @Bean
  HearingAuthorityLedger targetE2eHearingAuthorityLedger(
      DataSource dataSource, PlatformTransactionManager transactionManager) {
    return new JdbcHearingAuthorityLedger(
        new NamedParameterJdbcTemplate(dataSource), new TransactionTemplate(transactionManager));
  }

  @Bean
  HearingFormalReceiptService targetE2eHearingFormalReceiptService(
      DataSource dataSource, HearingAuthorityLedger targetE2eHearingAuthorityLedger) {
    return new HearingFormalReceiptService(
        new JdbcHearingFormalFinalizer(
            new NamedParameterJdbcTemplate(dataSource), targetE2eHearingAuthorityLedger));
  }

  @Bean
  TargetHearingRegistrationBundle targetE2eHearingRegistrationBundle(
      DataSource dataSource,
      TargetHearingCommandMaterialStore targetE2eHearingCommandMaterialStore,
      HearingFormalReceiptService targetE2eHearingFormalReceiptService,
      TargetHearingFormalCommandMapper targetE2eHearingFormalCommandMapper,
      TargetE2EActivationLedger targetE2eAgentActivationLedger,
      TargetE2eFinalizationActivationPort targetE2eFinalizationAuthority,
      TargetE2eFinalizationRuntimeContextProvider runtime,
      TargetE2EGraphEnvelopeCodec codec,
      TargetE2EGraphEnvelopeSigner signer,
      HttpTargetE2EGraphReconciliationClient reconciliation,
      HttpTargetE2EGraphProposalSourceClient proposalSource,
      GraphRegistryBindingPolicy registryBindings,
      ObjectMapper objectMapper) {
    return TargetE2eHearingArtifactRegistration.create(
        dataSource,
        targetE2eHearingCommandMaterialStore,
        targetE2eHearingFormalReceiptService,
        targetE2eHearingFormalCommandMapper,
        targetE2eAgentActivationLedger,
        targetE2eFinalizationAuthority,
        runtime,
        codec,
        signer,
        reconciliation,
        proposalSource,
        registryBindings,
        objectMapper);
  }

  @Bean
  TargetE2eRoomFinalizationStrategy targetE2eHearingRoomFinalizationStrategy(
      TargetHearingRegistrationBundle targetE2eHearingRegistrationBundle) {
    return targetE2eHearingRegistrationBundle.finalizationStrategy();
  }

  @Bean
  AgentRunDomainResultCommitter targetE2eHearingDomainResultCommitter(
      TargetHearingRegistrationBundle targetE2eHearingRegistrationBundle) {
    return targetE2eHearingRegistrationBundle.domainCommitter();
  }
}
