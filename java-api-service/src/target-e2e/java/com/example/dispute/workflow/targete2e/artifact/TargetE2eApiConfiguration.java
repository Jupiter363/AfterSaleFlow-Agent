package com.example.dispute.workflow.targete2e.artifact;

import com.example.dispute.casecore.application.ImportedCaseIdFactory;
import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeCanonicalPayloadValidator;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeService;
import com.example.dispute.workflow.infrastructure.objectstore.intake.IntakePrivateObjectStoreExchangeAdapter;
import com.example.dispute.workflow.infrastructure.objectstore.intake.MinioIntakeSyntheticExchangeStore;
import com.example.dispute.workflow.targete2e.artifact.exchange.TargetE2eIntakeExchangeAuthority;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeService;
import com.example.dispute.workflow.targete2e.exchange.rooms.JdbcTargetE2eRoomObjectIndex;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomObjectIndex;
import com.example.dispute.workflow.targete2e.finalization.MinioTargetE2eIntakeProposalStore;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eIntakeProposalStore;
import com.example.dispute.workflow.targete2e.ingress.CanonicalTargetTemporalIntakeIngress;
import com.example.dispute.workflow.targete2e.ingress.MinioTargetE2eIntakePayloadPublisher;
import com.example.dispute.workflow.targete2e.ingress.TargetTemporalIntakeIngress;
import com.example.dispute.workflow.targete2e.ingress.materialization.CanonicalTargetIntakeMaterializer;
import com.example.dispute.workflow.targete2e.ingress.materialization.TargetIntakeMaterializer;
import com.example.dispute.workflow.targete2e.ingress.materialization.TargetIntakeRuntimePins;
import com.example.dispute.workflow.targete2e.persistence.JdbcTargetE2eActivationStores;
import com.example.dispute.workflow.targete2e.persistence.JdbcTargetE2eApiAuthority;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
import com.example.dispute.workflow.targete2e.persistence.material.JdbcTargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.targete2e.ingress.rooms.CanonicalTargetRoomCommandMaterializer;
import com.example.dispute.workflow.targete2e.ingress.rooms.MinioTargetE2eRoomCommandPayloadPublisher;
import com.example.dispute.workflow.targete2e.ingress.rooms.TargetRoomCommandIngress;
import com.example.dispute.workflow.targete2e.ingress.rooms.TargetE2eHearingInvocationPublisher;
import com.example.dispute.workflow.targete2e.ingress.rooms.TargetE2eEvidenceManifestPublisher;
import com.example.dispute.workflow.targete2e.ingress.rooms.TargetE2eReviewInvocationPublisher;
import com.example.dispute.workflow.targete2e.ingress.rooms.JdbcTargetReviewInvocationFactsLoader;
import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKey;
import com.example.dispute.workflow.infrastructure.security.MountedPemGraphEnvelopeKeySet;
import com.example.dispute.workflow.targete2e.rooms.evidence.JdbcTargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.hearing.JdbcTargetHearingCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.review.JdbcTargetReviewCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewCommandMaterialStore;
import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher;
import com.example.dispute.workflow.application.intake.IntakeGraphBindingStore;
import com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrar;
import com.example.dispute.workflow.application.intake.IntakeTurnEventPublisher;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.AgentSessionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;

/** Target-artifact-only browser/API assembly backed by the verified activation ledger. */
@Configuration(proxyBeanMethods = false)
@Profile("target-e2e & api")
@ConditionalOnProperty(name = "app.target-e2e.enabled", havingValue = "true")
public class TargetE2eApiConfiguration {

  private static final String INTAKE_EXCHANGE_BUCKET = "target-e2e-intake-activation";
  private static final String INTAKE_EXCHANGE_PREFIX = "graph-proposals";

  @Bean
  JdbcTargetE2eActivationStores targetE2eApiActivationStores(
      DataSource dataSource, Clock clock) {
    return new JdbcTargetE2eActivationStores(dataSource, clock);
  }

  @Bean
  JdbcTargetE2eApiAuthority targetE2eApiAuthority(
      DataSource dataSource,
      JdbcTargetE2eActivationStores stores,
      Environment environment,
      Clock clock) {
    return new JdbcTargetE2eApiAuthority(
        dataSource, stores, required(environment, "target.e2e.activation.id"), clock);
  }

  @Bean
  ImportedCaseIdFactory targetE2eImportedCaseIdFactory(
      DataSource dataSource, Environment environment, Clock clock) {
    // The signed activation row is the authority for prefix, capacity, and fixture binding.
    // Keep the environment property mandatory as an early deployment wiring guard only.
    required(environment, "app.target-e2e.case-id-prefix");
    return new TargetE2eSyntheticCaseIdFactory(
        dataSource, required(environment, "target.e2e.activation.id"), clock);
  }

  @Bean
  IntakeImmutablePayloadPublisher targetE2eIntakePayloadPublisher(MinioClient minioClient) {
    return new MinioTargetE2eIntakePayloadPublisher(
        minioClient, "target-e2e-intake-activation", "browser-messages");
  }

  @Bean
  IntakeExchangeCanonicalPayloadValidator targetE2eIntakeExchangeCanonicalPayloadValidator() {
    return new IntakeExchangeCanonicalPayloadValidator();
  }

  @Bean
  IntakeExchangeAuthorityValidationPort targetE2eIntakeExchangeAuthority(
      DataSource dataSource, ObjectMapper objectMapper, Environment environment) {
    return new TargetE2eIntakeExchangeAuthority(
        dataSource, objectMapper, required(environment, "target.e2e.activation.id"));
  }

  @Bean
  MinioIntakeSyntheticExchangeStore targetE2eIntakeExchangeProposalPublisher(
      MinioClient minioClient, IntakeExchangeCanonicalPayloadValidator validator) {
    return new MinioIntakeSyntheticExchangeStore(
        minioClient, validator, INTAKE_EXCHANGE_BUCKET, INTAKE_EXCHANGE_PREFIX);
  }

  @Bean
  IntakeExchangeService targetE2eIntakeExchangeService(
      IntakeExchangeAuthorityValidationPort authority,
      MinioIntakeSyntheticExchangeStore proposalStore,
      IntakeExchangeCanonicalPayloadValidator validator) {
    return new IntakeExchangeService(
        authority,
        new IntakePrivateObjectStoreExchangeAdapter(proposalStore, proposalStore),
        validator);
  }

  /** Separate capability service for Evidence, Hearing and Review; it never shares Intake authority. */
  @Bean
  TargetE2eRoomExchangeService targetE2eRoomExchangeService(
      DataSource dataSource, ObjectMapper objectMapper, MinioClient minioClient,
      TargetE2eRoomObjectIndex objectIndex) {
    return new TargetE2eRoomExchangeService(dataSource, objectMapper, minioClient, objectIndex);
  }

  @Bean
  TargetE2eRoomObjectIndex targetE2eRoomObjectIndex(DataSource dataSource) {
    return new JdbcTargetE2eRoomObjectIndex(dataSource);
  }

  @Bean
  TargetE2eIntakeProposalStore targetE2eIntakeProposalStore(MinioClient minioClient) {
    return new MinioTargetE2eIntakeProposalStore(
        minioClient, INTAKE_EXCHANGE_BUCKET, INTAKE_EXCHANGE_PREFIX);
  }

  @Bean
  TargetIntakeCommandMaterialStore targetIntakeCommandMaterialStore(
      DataSource dataSource, Clock clock, ObjectMapper objectMapper) {
    return new JdbcTargetIntakeCommandMaterialStore(
        dataSource, new TargetE2EActivationLedger(dataSource, clock), objectMapper);
  }

  @Bean
  TargetEvidenceCommandMaterialStore targetEvidenceCommandMaterialStore(
      DataSource dataSource, Clock clock, ObjectMapper objectMapper) {
    return new JdbcTargetEvidenceCommandMaterialStore(
        dataSource, new TargetE2EActivationLedger(dataSource, clock), objectMapper);
  }

  @Bean
  TargetHearingCommandMaterialStore targetHearingCommandMaterialStore(
      DataSource dataSource, Clock clock, ObjectMapper objectMapper) {
    return new JdbcTargetHearingCommandMaterialStore(
        dataSource, new TargetE2EActivationLedger(dataSource, clock), objectMapper);
  }

  @Bean
  TargetReviewCommandMaterialStore targetReviewCommandMaterialStore(
      DataSource dataSource, Clock clock, ObjectMapper objectMapper) {
    return new JdbcTargetReviewCommandMaterialStore(
        dataSource, new TargetE2EActivationLedger(dataSource, clock), objectMapper);
  }

  @Bean
  MinioTargetE2eRoomCommandPayloadPublisher targetE2eRoomCommandPayloadPublisher(
      MinioClient minioClient, ObjectMapper objectMapper, TargetE2eRoomObjectIndex objectIndex) {
    return new MinioTargetE2eRoomCommandPayloadPublisher(
        minioClient, objectMapper, "target-e2e-intake-activation", "room-command-inputs", objectIndex);
  }

  /** Used only by the system-stage Hearing materializer, never browser fact commands. */
  @Bean
  TargetE2eHearingInvocationPublisher targetE2eHearingInvocationPublisher(
      MinioTargetE2eRoomCommandPayloadPublisher payloadPublisher,
      TargetE2eRoomObjectIndex objectIndex,
      ObjectMapper objectMapper) {
    return new TargetE2eHearingInvocationPublisher(payloadPublisher, objectIndex, objectMapper);
  }

  @Bean
  GraphEnvelopeSigningKey targetE2eApiGraphEnvelopeSigningKey(Environment environment) {
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
  TargetE2eEvidenceManifestPublisher targetE2eEvidenceManifestPublisher(
      MinioTargetE2eRoomCommandPayloadPublisher payloadPublisher,
      TargetE2eRoomObjectIndex objectIndex,
      GraphEnvelopeSigningKey activeGraphEnvelopeSigningKey,
      ObjectMapper objectMapper) {
    return new TargetE2eEvidenceManifestPublisher(payloadPublisher, objectIndex, activeGraphEnvelopeSigningKey, objectMapper);
  }

  @Bean
  TargetE2eReviewInvocationPublisher targetE2eReviewInvocationPublisher(
      MinioTargetE2eRoomCommandPayloadPublisher payloadPublisher,
      TargetE2eRoomObjectIndex objectIndex,
      ObjectMapper objectMapper) {
    return new TargetE2eReviewInvocationPublisher(payloadPublisher, objectIndex, objectMapper);
  }

  @Bean
  JdbcTargetReviewInvocationFactsLoader jdbcTargetReviewInvocationFactsLoader(
      DataSource dataSource, ObjectMapper objectMapper) {
    return new JdbcTargetReviewInvocationFactsLoader(dataSource, objectMapper);
  }

  @Bean
  TargetIntakeRuntimePins targetIntakeRuntimePins(Environment environment) {
    return new TargetIntakeRuntimePins(
        required(environment, "target.e2e.case-build-id"),
        required(environment, "target.e2e.agent-build-id"),
        required(environment, "target.e2e.graph-binding-hash"),
        required(environment, "target.e2e.graph-code-build-id"),
        required(environment, "target.e2e.isolated-domain-db-binding-hash"),
        required(environment, "target.e2e.intake.prompt-version"),
        required(environment, "target.e2e.intake.model-profile-id"),
        required(environment, "target.e2e.intake.policy-version"),
        required(environment, "target.e2e.intake.guardrail-version"),
        required(environment, "target.e2e.intake.tool-policy-version"),
        required(environment, "target.e2e.intake.memory-policy-version"),
        required(environment, "target.e2e.intake.envelope-key-id"));
  }

  @Bean
  TargetIntakeMaterializer targetIntakeMaterializer(
      @Qualifier("targetE2eIntakePayloadPublisher")
          IntakeImmutablePayloadPublisher payloadPublisher,
      IntakeGraphBindingStore bindingStore,
      AccessSessionResolver accessSessions,
      AgentSessionResolver agentSessions,
      AgentRunLedger ledger,
      TargetIntakeCommandMaterialStore materialStore,
      JdbcTargetE2eApiAuthority activationAuthority,
      TargetIntakeRuntimePins pins,
      ObjectMapper objectMapper,
      Clock clock) {
    return new CanonicalTargetIntakeMaterializer(
        accessSessions, agentSessions, new IntakePrivateThreadRegistrar(bindingStore),
        new IntakeDomainSnapshotPublisher(payloadPublisher, bindingStore),
        new IntakeTurnEventPublisher(payloadPublisher, bindingStore), new IntakeGraphCommandFactory(),
        new AgentRunCommandBindingFactory(objectMapper), ledger,
        new TargetE2EGraphEnvelopeCodec(objectMapper), materialStore, activationAuthority, pins, clock);
  }

  @Bean
  TargetTemporalIntakeIngress targetTemporalIntakeIngress(
      CaseCommandService commandService, TargetIntakeMaterializer materializer) {
    return new CanonicalTargetTemporalIntakeIngress(commandService, materializer);
  }

  @Bean
  TargetRoomCommandIngress targetRoomCommandIngress(
      CaseRoomEpochRepository epochs,
      JdbcTargetE2eApiAuthority authority,
      TargetIntakeRuntimePins pins,
      AgentRunLedger ledger,
      AgentRunCommandBindingFactory bindings,
      TargetEvidenceCommandMaterialStore evidence,
      TargetHearingCommandMaterialStore hearing,
      TargetReviewCommandMaterialStore review,
      MinioTargetE2eRoomCommandPayloadPublisher payloads,
      TargetE2eRoomObjectIndex objectIndex,
      TargetE2eEvidenceManifestPublisher evidenceManifestPublisher,
      TargetE2eReviewInvocationPublisher reviewInvocationPublisher,
      JdbcTargetReviewInvocationFactsLoader reviewFacts,
      ObjectMapper objectMapper,
      Clock clock) {
    return new CanonicalTargetRoomCommandMaterializer(
        epochs, authority, pins, ledger, bindings, new TargetE2EGraphEnvelopeCodec(objectMapper), payloads, objectIndex, evidenceManifestPublisher, reviewInvocationPublisher, reviewFacts,
        evidence, hearing, review, objectMapper, clock);
  }

  private static String required(Environment environment, String property) {
    String value = environment.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("required target E2E property is absent: " + property);
    }
    return value.trim();
  }
}
