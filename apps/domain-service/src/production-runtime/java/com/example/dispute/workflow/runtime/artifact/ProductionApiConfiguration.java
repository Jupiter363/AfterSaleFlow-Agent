package com.example.dispute.workflow.runtime.artifact;

import com.example.dispute.casecore.application.ImportedCaseIdFactory;
import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeCanonicalPayloadValidator;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeService;
import com.example.dispute.workflow.infrastructure.objectstore.intake.IntakePrivateObjectStoreExchangeAdapter;
import com.example.dispute.workflow.infrastructure.objectstore.intake.MinioIntakeSyntheticExchangeStore;
import com.example.dispute.workflow.runtime.artifact.exchange.ProductionIntakeExchangeAuthority;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomExchangeService;
import com.example.dispute.workflow.runtime.exchange.rooms.JdbcProductionRoomObjectIndex;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomObjectIndex;
import com.example.dispute.workflow.runtime.finalization.MinioProductionIntakeProposalStore;
import com.example.dispute.workflow.runtime.finalization.ProductionIntakeProposalStore;
import com.example.dispute.workflow.runtime.ingress.CanonicalTargetTemporalIntakeIngress;
import com.example.dispute.workflow.runtime.ingress.MinioProductionIntakePayloadPublisher;
import com.example.dispute.workflow.runtime.ingress.TargetTemporalIntakeIngress;
import com.example.dispute.workflow.runtime.ingress.branch.CanonicalTargetIntakeBranchIngress;
import com.example.dispute.workflow.runtime.ingress.branch.TargetIntakeBranchIngress;
import com.example.dispute.workflow.runtime.ingress.materialization.CanonicalTargetIntakeMaterializer;
import com.example.dispute.workflow.runtime.ingress.materialization.TargetIntakeMaterializer;
import com.example.dispute.workflow.runtime.ingress.materialization.TargetIntakeRuntimePins;
import com.example.dispute.workflow.runtime.persistence.JdbcProductionActivationStores;
import com.example.dispute.workflow.runtime.persistence.JdbcProductionApiAuthority;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger;
import com.example.dispute.workflow.runtime.persistence.material.JdbcTargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.runtime.persistence.material.TargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.runtime.ingress.rooms.CanonicalTargetRoomCommandMaterializer;
import com.example.dispute.workflow.runtime.ingress.rooms.MinioProductionRoomCommandPayloadPublisher;
import com.example.dispute.workflow.runtime.ingress.rooms.TargetRoomCommandIngress;
import com.example.dispute.workflow.runtime.ingress.rooms.ProductionHearingInvocationPublisher;
import com.example.dispute.workflow.runtime.ingress.rooms.ProductionEvidenceManifestPublisher;
import com.example.dispute.workflow.runtime.ingress.rooms.ProductionEvidenceTurnInvocationPublisher;
import com.example.dispute.workflow.runtime.ingress.rooms.ProductionReviewInvocationPublisher;
import com.example.dispute.workflow.runtime.ingress.rooms.JdbcTargetReviewInvocationFactsLoader;
import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKey;
import com.example.dispute.workflow.infrastructure.security.MountedPemGraphEnvelopeKeySet;
import com.example.dispute.workflow.runtime.rooms.evidence.JdbcTargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.evidence.JdbcTargetEvidenceCompletionCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCompletionCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.hearing.JdbcTargetHearingCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.hearing.TargetHearingCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.review.JdbcTargetReviewCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewCommandMaterialStore;
import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunV2RetryPreparation;
import com.example.dispute.evidence.application.EvidenceContentAuthorityLookup;
import com.example.dispute.workflow.runtime.artifact.recovery.ProductionAgentRunV2RetryPreparation;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeCodec;
import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher;
import com.example.dispute.workflow.application.intake.IntakeGraphBindingStore;
import com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrar;
import com.example.dispute.workflow.application.intake.IntakeTurnEventPublisher;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.AgentSessionResolver;
import com.example.dispute.room.application.IntakeInfrastructurePreparationService;
import com.example.dispute.room.application.IntakeInfrastructurePreparationService.TargetPreparation;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import io.temporal.client.WorkflowClient;
import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;

/** Target-artifact-only browser/API assembly backed by the verified activation ledger. */
@Configuration(proxyBeanMethods = false)
@Profile("production-runtime & api")
@ConditionalOnProperty(name = "app.production-runtime.enabled", havingValue = "true")
public class ProductionApiConfiguration {

  private static final String INTAKE_EXCHANGE_BUCKET = "production-runtime-intake-activation";
  private static final String INTAKE_EXCHANGE_PAYLOAD_PREFIX = "browser-messages";
  private static final String INTAKE_EXCHANGE_PROPOSAL_PREFIX = "graph-proposals";

  @Bean
  JdbcProductionActivationStores productionApiActivationStores(
      DataSource dataSource, Clock clock) {
    return new JdbcProductionActivationStores(dataSource, clock);
  }

  @Bean
  JdbcProductionApiAuthority productionApiAuthority(
      DataSource dataSource,
      JdbcProductionActivationStores stores,
      Environment environment,
      Clock clock) {
    return new JdbcProductionApiAuthority(
        dataSource, stores, required(environment, "production.runtime.activation.id"), clock);
  }

  @Bean
  TargetPreparation productionIntakeInfrastructurePreparation(
      WorkflowClient workflowClient) {
    return IntakeInfrastructurePreparationService.temporal(workflowClient);
  }

  @Bean
  ImportedCaseIdFactory productionImportedCaseIdFactory(
      DataSource dataSource, Environment environment, Clock clock) {
    // The signed activation row is the authority for prefix, capacity, and fixture binding.
    // Keep the environment property mandatory as an early deployment wiring guard only.
    required(environment, "app.production-runtime.case-id-prefix");
    return new ProductionSyntheticCaseIdFactory(
        dataSource, required(environment, "production.runtime.activation.id"), clock);
  }

  @Bean
  @Lazy(false)
  IntakeImmutablePayloadPublisher productionIntakePayloadPublisher(MinioClient minioClient) {
    MinioProductionIntakePayloadPublisher publisher =
        new MinioProductionIntakePayloadPublisher(
            minioClient, INTAKE_EXCHANGE_BUCKET, INTAKE_EXCHANGE_PAYLOAD_PREFIX);
    return publisher.prepare();
  }

  @Bean
  IntakeExchangeCanonicalPayloadValidator productionIntakeExchangeCanonicalPayloadValidator() {
    return new IntakeExchangeCanonicalPayloadValidator();
  }

  @Bean
  IntakeExchangeAuthorityValidationPort productionIntakeExchangeAuthority(
      DataSource dataSource, ObjectMapper objectMapper, Environment environment) {
    return new ProductionIntakeExchangeAuthority(
        dataSource, objectMapper, required(environment, "production.runtime.activation.id"));
  }

  @Bean
  MinioIntakeSyntheticExchangeStore productionIntakeExchangePayloadReader(
      MinioClient minioClient, IntakeExchangeCanonicalPayloadValidator validator) {
    return new MinioIntakeSyntheticExchangeStore(
        minioClient, validator, INTAKE_EXCHANGE_BUCKET, INTAKE_EXCHANGE_PAYLOAD_PREFIX);
  }

  @Bean
  MinioIntakeSyntheticExchangeStore productionIntakeExchangeProposalPublisher(
      MinioClient minioClient, IntakeExchangeCanonicalPayloadValidator validator) {
    return new MinioIntakeSyntheticExchangeStore(
        minioClient, validator, INTAKE_EXCHANGE_BUCKET, INTAKE_EXCHANGE_PROPOSAL_PREFIX);
  }

  @Bean
  IntakeExchangeService productionIntakeExchangeService(
      IntakeExchangeAuthorityValidationPort authority,
      @Qualifier("productionIntakeExchangePayloadReader")
          MinioIntakeSyntheticExchangeStore payloadReader,
      @Qualifier("productionIntakeExchangeProposalPublisher")
          MinioIntakeSyntheticExchangeStore proposalStore,
      IntakeExchangeCanonicalPayloadValidator validator) {
    return new IntakeExchangeService(
        authority,
        new IntakePrivateObjectStoreExchangeAdapter(payloadReader, proposalStore),
        validator);
  }

  /** Separate capability service for Evidence, Hearing and Review; it never shares Intake authority. */
  @Bean
  ProductionRoomExchangeService productionRoomExchangeService(
      DataSource dataSource, ObjectMapper objectMapper, MinioClient minioClient,
      ProductionRoomObjectIndex objectIndex) {
    return new ProductionRoomExchangeService(dataSource, objectMapper, minioClient, objectIndex);
  }

  @Bean
  ProductionRoomObjectIndex productionRoomObjectIndex(DataSource dataSource) {
    return new JdbcProductionRoomObjectIndex(dataSource);
  }

  @Bean
  ProductionIntakeProposalStore productionIntakeProposalStore(MinioClient minioClient) {
    return new MinioProductionIntakeProposalStore(
        minioClient, INTAKE_EXCHANGE_BUCKET, INTAKE_EXCHANGE_PROPOSAL_PREFIX);
  }

  @Bean
  TargetIntakeCommandMaterialStore targetIntakeCommandMaterialStore(
      DataSource dataSource, Clock clock, ObjectMapper objectMapper) {
    return new JdbcTargetIntakeCommandMaterialStore(
        dataSource, new ProductionActivationLedger(dataSource, clock), objectMapper);
  }

  @Bean
  TargetEvidenceCommandMaterialStore targetEvidenceCommandMaterialStore(
      DataSource dataSource, Clock clock, ObjectMapper objectMapper) {
    return new JdbcTargetEvidenceCommandMaterialStore(
        dataSource, new ProductionActivationLedger(dataSource, clock), objectMapper);
  }

  @Bean
  TargetEvidenceCompletionCommandMaterialStore targetEvidenceCompletionCommandMaterialStore(
      DataSource dataSource, Clock clock, ObjectMapper objectMapper) {
    return new JdbcTargetEvidenceCompletionCommandMaterialStore(
        dataSource, new ProductionActivationLedger(dataSource, clock), objectMapper);
  }

  @Bean
  TargetHearingCommandMaterialStore targetHearingCommandMaterialStore(
      DataSource dataSource, Clock clock, ObjectMapper objectMapper) {
    return new JdbcTargetHearingCommandMaterialStore(
        dataSource, new ProductionActivationLedger(dataSource, clock), objectMapper);
  }

  @Bean
  TargetReviewCommandMaterialStore targetReviewCommandMaterialStore(
      DataSource dataSource, Clock clock, ObjectMapper objectMapper) {
    return new JdbcTargetReviewCommandMaterialStore(
        dataSource, new ProductionActivationLedger(dataSource, clock), objectMapper);
  }

  @Bean
  AgentRunV2RetryPreparation productionAgentRunV2RetryPreparation(
      ObjectMapper objectMapper,
      TargetIntakeCommandMaterialStore intake,
      TargetEvidenceCommandMaterialStore evidence,
      TargetHearingCommandMaterialStore hearing,
      TargetReviewCommandMaterialStore review,
      ProductionRoomObjectIndex objectIndex) {
    return new ProductionAgentRunV2RetryPreparation(
        objectMapper,
        new ProductionGraphEnvelopeCodec(objectMapper),
        intake,
        evidence,
        hearing,
        review,
        objectIndex);
  }

  @Bean
  MinioProductionRoomCommandPayloadPublisher productionRoomCommandPayloadPublisher(
      MinioClient minioClient, ObjectMapper objectMapper, ProductionRoomObjectIndex objectIndex) {
    return new MinioProductionRoomCommandPayloadPublisher(
        minioClient, objectMapper, "production-runtime-intake-activation", "room-command-inputs", objectIndex);
  }

  /** Used only by the system-stage Hearing materializer, never browser fact commands. */
  @Bean
  ProductionHearingInvocationPublisher productionHearingInvocationPublisher(
      MinioProductionRoomCommandPayloadPublisher payloadPublisher,
      ProductionRoomObjectIndex objectIndex,
      ObjectMapper objectMapper) {
    return new ProductionHearingInvocationPublisher(payloadPublisher, objectIndex, objectMapper);
  }

  @Bean
  GraphEnvelopeSigningKey productionApiGraphEnvelopeSigningKey(Environment environment) {
    Path keyDirectory =
        Path.of(
                required(
                    environment,
                    "app.agent-run-v2.graph-client.signing.key-directory"))
            .toAbsolutePath()
            .normalize();
    return MountedPemGraphEnvelopeKeySet.load(keyDirectory)
        .resolve(
            required(
                environment,
                "app.agent-run-v2.graph-client.signing.active-key-id"));
  }

  @Bean
  ProductionEvidenceManifestPublisher productionEvidenceManifestPublisher(
      MinioProductionRoomCommandPayloadPublisher payloadPublisher,
      ProductionRoomObjectIndex objectIndex,
      GraphEnvelopeSigningKey activeGraphEnvelopeSigningKey,
      ObjectMapper objectMapper) {
    return new ProductionEvidenceManifestPublisher(payloadPublisher, objectIndex, activeGraphEnvelopeSigningKey, objectMapper);
  }

  @Bean
  ProductionEvidenceTurnInvocationPublisher productionEvidenceTurnInvocationPublisher(
      MinioProductionRoomCommandPayloadPublisher payloadPublisher,
      ProductionRoomObjectIndex objectIndex,
      ObjectMapper objectMapper,
      EvidenceContentAuthorityLookup contentAuthorityLookup) {
    return new ProductionEvidenceTurnInvocationPublisher(
        payloadPublisher, objectIndex, objectMapper, contentAuthorityLookup);
  }

  @Bean
  ProductionReviewInvocationPublisher productionReviewInvocationPublisher(
      MinioProductionRoomCommandPayloadPublisher payloadPublisher,
      ProductionRoomObjectIndex objectIndex,
      ObjectMapper objectMapper) {
    return new ProductionReviewInvocationPublisher(payloadPublisher, objectIndex, objectMapper);
  }

  @Bean
  JdbcTargetReviewInvocationFactsLoader jdbcTargetReviewInvocationFactsLoader(
      DataSource dataSource, ObjectMapper objectMapper) {
    return new JdbcTargetReviewInvocationFactsLoader(dataSource, objectMapper);
  }

  @Bean
  @Lazy(false)
  TargetIntakeRuntimePins targetIntakeRuntimePins(Environment environment) {
    return new TargetIntakeRuntimePins(
        required(environment, "production.runtime.case-build-id"),
        required(environment, "production.runtime.agent-build-id"),
        required(environment, "production.runtime.graph-binding-hash"),
        required(environment, "production.runtime.graph-code-build-id"),
        required(environment, "production.runtime.isolated-domain-db-binding-hash"),
        required(environment, "production.runtime.intake.agent-profile-id"),
        required(environment, "production.runtime.intake.prompt-version"),
        required(environment, "production.runtime.intake.model-profile-id"),
        required(environment, "production.runtime.intake.execution-provider-id"),
        required(environment, "production.runtime.intake.policy-version"),
        required(environment, "production.runtime.intake.guardrail-version"),
        required(environment, "production.runtime.intake.tool-policy-version"),
        required(environment, "production.runtime.intake.memory-policy-version"),
        required(environment, "production.runtime.intake.envelope-key-id"));
  }

  @Bean
  @Lazy(false)
  TargetIntakeMaterializer targetIntakeMaterializer(
      @Qualifier("productionIntakePayloadPublisher")
          IntakeImmutablePayloadPublisher payloadPublisher,
      IntakeGraphBindingStore bindingStore,
      AccessSessionResolver accessSessions,
      AgentSessionResolver agentSessions,
      ParticipantService participants,
      AgentRunLedger ledger,
      TargetIntakeCommandMaterialStore materialStore,
      JdbcProductionApiAuthority activationAuthority,
      FulfillmentCaseRepository cases,
      CaseIntakeDossierRepository dossiers,
      CaseRoomEpochRepository epochs,
      CaseProcessProjectionRepository projections,
      TargetIntakeRuntimePins pins,
      ObjectMapper objectMapper,
      Clock clock) {
    return new CanonicalTargetIntakeMaterializer(
        accessSessions, agentSessions, participants, new IntakePrivateThreadRegistrar(bindingStore),
        new IntakeDomainSnapshotPublisher(payloadPublisher, bindingStore),
        new IntakeTurnEventPublisher(payloadPublisher, bindingStore), new IntakeGraphCommandFactory(),
        new AgentRunCommandBindingFactory(objectMapper), ledger,
        new ProductionGraphEnvelopeCodec(objectMapper), materialStore, activationAuthority, cases, dossiers,
        epochs, projections, pins, objectMapper, clock);
  }

  @Bean
  @Lazy(false)
  TargetTemporalIntakeIngress targetTemporalIntakeIngress(
      CaseCommandService commandService, TargetIntakeMaterializer materializer) {
    return new CanonicalTargetTemporalIntakeIngress(commandService, materializer);
  }

  @Bean
  TargetIntakeBranchIngress targetIntakeBranchIngress(
      CaseCommandService commandService,
      @Qualifier("productionIntakePayloadPublisher") IntakeImmutablePayloadPublisher payloadPublisher,
      ObjectMapper objectMapper) {
    return new CanonicalTargetIntakeBranchIngress(commandService, payloadPublisher, objectMapper);
  }

  @Bean
  TargetRoomCommandIngress targetRoomCommandIngress(
      CaseRoomEpochRepository epochs,
      JdbcProductionApiAuthority authority,
      TargetIntakeRuntimePins pins,
      AgentRunLedger ledger,
      AgentRunCommandBindingFactory bindings,
      TargetEvidenceCommandMaterialStore evidence,
      TargetEvidenceCompletionCommandMaterialStore evidenceCompletion,
      TargetHearingCommandMaterialStore hearing,
      TargetReviewCommandMaterialStore review,
      MinioProductionRoomCommandPayloadPublisher payloads,
      ProductionRoomObjectIndex objectIndex,
      ProductionEvidenceManifestPublisher evidenceManifestPublisher,
      ProductionEvidenceTurnInvocationPublisher evidenceTurnInvocationPublisher,
      ProductionReviewInvocationPublisher reviewInvocationPublisher,
      JdbcTargetReviewInvocationFactsLoader reviewFacts,
      ObjectMapper objectMapper,
      Clock clock) {
    return new CanonicalTargetRoomCommandMaterializer(
        epochs, authority, pins, ledger, bindings, new ProductionGraphEnvelopeCodec(objectMapper), payloads, objectIndex, evidenceManifestPublisher, evidenceTurnInvocationPublisher, reviewInvocationPublisher, reviewFacts,
        evidence, evidenceCompletion, hearing, review, objectMapper, clock);
  }

  private static String required(Environment environment, String property) {
    String value = environment.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("required production runtime property is absent: " + property);
    }
    return value.trim();
  }
}
