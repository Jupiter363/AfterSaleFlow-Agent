package com.example.dispute.workflow.runtime.artifact;

import com.example.dispute.workflow.config.TemporalWorkerProperties;
import com.example.dispute.config.AppProperties;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.evidence.application.EvidenceDossierFreezer;
import com.example.dispute.hearing.application.finalization.HearingFormalReceiptService;
import com.example.dispute.hearing.domain.HearingAuthorityLedger;
import com.example.dispute.hearing.infrastructure.persistence.JdbcHearingAuthorityLedger;
import com.example.dispute.hearing.infrastructure.persistence.JdbcHearingFormalFinalizer;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.IntakeBranchDomainService;
import com.example.dispute.room.application.IntakeProgressService;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.infrastructure.persistence.JdbcIntakeFormalBranchCommitPort;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator;
import com.example.dispute.workflow.application.intake.IntakeFormalBranchCommandResolver;
import com.example.dispute.workflow.application.intake.IntakeFormalBranchCommitPort;
import com.example.dispute.workflow.runtime.ProductionActivationAuthority;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger;
import com.example.dispute.workflow.runtime.persistence.material.JdbcTargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.runtime.persistence.material.TargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.runtime.temporal.ProductionTemporalWorkerRegistration;
import com.example.dispute.workflow.runtime.temporal.TargetTypedRoomCaseProcessDispatcher;
import com.example.dispute.workflow.runtime.temporal.TargetTypedRoomProtocol;
import com.example.dispute.workflow.runtime.temporal.intake.TargetIntakeCommandBridgeActivity;
import com.example.dispute.workflow.runtime.temporal.intake.JdbcTargetIntakeBranchContextSource;
import com.example.dispute.workflow.runtime.temporal.intake.JdbcTargetIntakePartyScopeSource;
import com.example.dispute.workflow.runtime.temporal.intake.TargetIntakeBranchContextSource;
import com.example.dispute.workflow.runtime.temporal.intake.TargetIntakePartyScopeSource;
import com.example.dispute.workflow.runtime.rooms.intake.ProductionIntakeFormalBranchCommandResolver;
import com.example.dispute.workflow.runtime.rooms.intake.ProductionIntakeRoomActivities;
import com.example.dispute.workflow.runtime.temporal.intake.finalizationread.JdbcTargetIntakeAgentRunFinalizationReceiptReadPort;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadActivities;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadActivitiesAdapter;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReceiptReadPort;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomActivities;
import com.example.dispute.workflow.runtime.rooms.evidence.JdbcTargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCommandBridgeActivities;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCommandBridgeActivity;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.evidence.JdbcTargetEvidenceTerminalActivities;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceTerminalActivities;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceParticipantBindingActivities;
import com.example.dispute.workflow.runtime.rooms.evidence.JdbcTargetEvidenceParticipantBindingActivities;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidencePartyCompletionActivities;
import com.example.dispute.workflow.runtime.rooms.evidence.JdbcTargetEvidencePartyCompletionActivities;
import com.example.dispute.workflow.runtime.rooms.hearing.JdbcTargetHearingCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.hearing.TargetHearingCommandBridgeActivities;
import com.example.dispute.workflow.runtime.rooms.hearing.TargetHearingCommandBridgeActivitiesImpl;
import com.example.dispute.workflow.runtime.rooms.hearing.TargetHearingCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.hearing.JdbcTargetHearingAgentStageInputFactory;
import com.example.dispute.workflow.runtime.rooms.hearing.JdbcTargetHearingAgentRunStartedPublisher;
import com.example.dispute.workflow.runtime.rooms.hearing.JdbcTargetHearingFormalizationActivities;
import com.example.dispute.workflow.runtime.rooms.hearing.JdbcTargetHearingPublicTranscriptCommitter;
import com.example.dispute.workflow.runtime.rooms.hearing.TargetHearingFormalCompletion;
import com.example.dispute.workflow.runtime.rooms.hearing.TargetHearingFormalizationActivities;
import com.example.dispute.workflow.runtime.rooms.hearing.TargetHearingInternalStageMaterializer;
import com.example.dispute.workflow.runtime.temporal.room.hearing.JdbcTargetHearingBootstrapActivities;
import com.example.dispute.workflow.runtime.temporal.room.hearing.TargetHearingBootstrapActivities;
import com.example.dispute.workflow.runtime.ingress.materialization.TargetIntakeRuntimePins;
import com.example.dispute.workflow.runtime.ingress.rooms.MinioProductionRoomCommandPayloadPublisher;
import com.example.dispute.workflow.runtime.ingress.rooms.ProductionHearingInvocationPublisher;
import com.example.dispute.workflow.runtime.exchange.rooms.JdbcProductionRoomObjectIndex;
import com.example.dispute.workflow.runtime.persistence.JdbcProductionActivationStores;
import com.example.dispute.workflow.runtime.ProductionActivationLifecycleStore;
import com.example.dispute.workflow.runtime.persistence.JdbcProductionApiAuthority;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeCodec;
import com.example.dispute.workflow.runtime.rooms.review.JdbcTargetReviewCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewCommandBridgeActivities;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewCommandBridgeActivity;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.review.JdbcTargetReviewOutcomeHandoffStore;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewOutcomeHandoffActivities;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewOutcomeHandoffActivity;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewOutcomeHandoffStore;
import com.example.dispute.workflow.runtime.rooms.review.JdbcTargetReviewOutcomeStartBindingPort;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewOutcomeStartBindingActivities;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewOutcomeStartBindingActivity;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewOutcomeStartBindingPort;
import com.example.dispute.workflow.runtime.rooms.review.JdbcTargetReviewNonExecutionActivities;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewNonExecutionActivities;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewOutcomeChildUpdateActivities;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewOutcomeChildUpdateActivity;
import com.example.dispute.workflow.runtime.rooms.outcome.JdbcTargetOutcomeCompletionActivities;
import com.example.dispute.workflow.runtime.rooms.outcome.JdbcTargetTemporalOutcomeBindingResolver;
import com.example.dispute.workflow.runtime.rooms.outcome.TargetDeterministicEvaluationAgentClient;
import com.example.dispute.workflow.runtime.rooms.outcome.TargetOutcomeCompletionActivities;
import com.example.dispute.evaluation.application.CaseClosureService;
import com.example.dispute.evaluation.application.EvaluationAgentClient;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger;
import com.example.dispute.executor.infrastructure.persistence.JdbcOutcomeOperationLedger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import io.minio.MinioClient;
import io.temporal.client.WorkflowClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Target-artifact composition for the isolated all-room Temporal control lane. */
@Configuration(proxyBeanMethods = false)
@Profile("production-runtime")
@ConditionalOnProperty(
    name = "app.temporal.worker.role", havingValue = "CONTROL")
public class ProductionControlConfiguration {

  @Bean
  ProductionActivationLedger productionControlActivationLedger(DataSource dataSource) {
    return new ProductionActivationLedger(dataSource, Clock.systemUTC());
  }

  @Bean
  JdbcProductionActivationStores productionControlActivationStores(
      DataSource dataSource) {
    return new JdbcProductionActivationStores(dataSource, Clock.systemUTC());
  }

  @Bean
  JdbcProductionApiAuthority targetRoomEpochSelectionAuthority(
      DataSource dataSource, Environment environment,
      JdbcProductionActivationStores productionControlActivationStores) {
    Clock clock = Clock.systemUTC();
    return new JdbcProductionApiAuthority(
        dataSource,
        productionControlActivationStores,
        required(environment, "production.runtime.activation.id"),
        clock);
  }

  @Bean
  @Primary
  EvaluationAgentClient targetDeterministicEvaluationAgentClient(ObjectMapper objectMapper) {
    return new TargetDeterministicEvaluationAgentClient(objectMapper);
  }

  @Bean
  OutcomeOperationLedger targetOutcomeOperationLedger(
      DataSource dataSource, PlatformTransactionManager transactionManager) {
    return new JdbcOutcomeOperationLedger(
        new NamedParameterJdbcTemplate(dataSource), new TransactionTemplate(transactionManager));
  }

  @Bean
  JdbcTargetTemporalOutcomeBindingResolver targetTemporalOutcomeBindingResolver(
      DataSource dataSource, PlatformTransactionManager transactionManager) {
    return new JdbcTargetTemporalOutcomeBindingResolver(
        dataSource, new TransactionTemplate(transactionManager), Clock.systemUTC());
  }

  @Bean
  TargetOutcomeCompletionActivities targetOutcomeCompletionActivities(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      ObjectMapper objectMapper,
      CaseClosureService caseClosureService,
      OutcomeOperationLedger targetOutcomeOperationLedger,
      JdbcTargetTemporalOutcomeBindingResolver targetTemporalOutcomeBindingResolver,
      ProductionActivationLedger productionControlActivationLedger) {
    return new JdbcTargetOutcomeCompletionActivities(
        dataSource,
        new TransactionTemplate(transactionManager),
        objectMapper,
        Clock.systemUTC(),
        caseClosureService,
        targetOutcomeOperationLedger,
        targetTemporalOutcomeBindingResolver,
        productionControlActivationLedger);
  }

  @Bean
  TargetIntakeCommandMaterialStore targetIntakeCommandMaterialStore(
      DataSource dataSource, ProductionActivationLedger productionControlActivationLedger, ObjectMapper objectMapper) {
    return new JdbcTargetIntakeCommandMaterialStore(
        dataSource, productionControlActivationLedger, objectMapper);
  }

  @Bean
  TargetIntakeBranchContextSource targetIntakeBranchContextSource(
      MinioClient minioClient, ObjectMapper objectMapper, DataSource dataSource) {
    return new JdbcTargetIntakeBranchContextSource(
        minioClient,
        objectMapper,
        dataSource,
        ProductionIntakeFormalBranchCommandResolver.TARGET_INTAKE_BUCKET,
        ProductionIntakeFormalBranchCommandResolver.TARGET_INTAKE_PREFIX);
  }

  @Bean
  TargetIntakePartyScopeSource targetIntakePartyScopeSource(DataSource dataSource) {
    return new JdbcTargetIntakePartyScopeSource(dataSource);
  }

  @Bean
  TargetIntakeCommandBridgeActivity targetIntakeCommandBridgeActivity(
      TargetIntakeCommandMaterialStore targetIntakeCommandMaterialStore,
      ObjectMapper objectMapper,
      TargetIntakePartyScopeSource targetIntakePartyScopeSource,
      TargetIntakeBranchContextSource targetIntakeBranchContextSource) {
    return new TargetIntakeCommandBridgeActivity(
        targetIntakeCommandMaterialStore,
        objectMapper,
        targetIntakePartyScopeSource,
        targetIntakeBranchContextSource);
  }

  @Bean
  IntakeFormalBranchCommandResolver productionIntakeFormalBranchCommandResolver(
      MinioClient minioClient, ObjectMapper objectMapper) {
    return new ProductionIntakeFormalBranchCommandResolver(
        minioClient,
        objectMapper,
        ProductionIntakeFormalBranchCommandResolver.TARGET_INTAKE_BUCKET,
        ProductionIntakeFormalBranchCommandResolver.TARGET_INTAKE_PREFIX);
  }

  @Bean
  IntakeBranchDomainService productionIntakeBranchDomainService(
      FulfillmentCaseRepository caseRepository,
      CaseRoomRepository roomRepository,
      CasePhaseClockRepository phaseClockRepository,
      CaseIntakeDossierRepository intakeDossierRepository,
      IntakeProgressService intakeProgressService,
      ParticipantService participantService,
      NotificationService notificationService,
      CaseLifecycleNotificationService lifecycleNotifications,
      EvidenceWindowCoordinator evidenceWindowCoordinator,
      CaseEventService caseEventService,
      DisputeProperties disputeProperties,
      ObjectMapper objectMapper) {
    return new IntakeBranchDomainService(
        caseRepository,
        roomRepository,
        phaseClockRepository,
        intakeDossierRepository,
        intakeProgressService,
        participantService,
        notificationService,
        lifecycleNotifications,
        evidenceWindowCoordinator,
        caseEventService,
        disputeProperties,
        objectMapper);
  }

  @Bean
  IntakeFormalBranchCommitPort productionIntakeFormalBranchCommitPort(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      FulfillmentCaseRepository caseRepository,
      CaseRoomRepository roomRepository,
      IntakeBranchDomainService productionIntakeBranchDomainService,
      IntakeFormalBranchCommandResolver productionIntakeFormalBranchCommandResolver,
      RoomEpochAllocator roomEpochAllocator,
      ObjectMapper objectMapper) {
    return new JdbcIntakeFormalBranchCommitPort(
        new NamedParameterJdbcTemplate(dataSource),
        transactionManager,
        caseRepository,
        roomRepository,
        productionIntakeBranchDomainService,
        productionIntakeFormalBranchCommandResolver,
        roomEpochAllocator,
        "all-rooms.production-runtime.v2",
        objectMapper,
        Clock.systemUTC());
  }

  @Bean
  IntakeRoomActivities productionIntakeRoomActivities(
      IntakeFormalBranchCommitPort productionIntakeFormalBranchCommitPort) {
    return new ProductionIntakeRoomActivities(productionIntakeFormalBranchCommitPort);
  }

  @Bean
  IntakeAgentRunFinalizationReceiptReadPort targetIntakeAgentRunFinalizationReceiptReadPort(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      ObjectMapper objectMapper) {
    return new JdbcTargetIntakeAgentRunFinalizationReceiptReadPort(
        dataSource, transactionManager, objectMapper);
  }

  @Bean
  IntakeAgentRunFinalizationReadActivities targetIntakeAgentRunFinalizationReadActivities(
      IntakeAgentRunFinalizationReceiptReadPort readPort) {
    return new IntakeAgentRunFinalizationReadActivitiesAdapter(readPort);
  }

  @Bean
  TargetEvidenceCommandMaterialStore targetEvidenceCommandMaterialStore(
      DataSource dataSource,
      ProductionActivationLedger productionControlActivationLedger,
      ObjectMapper objectMapper) {
    return new JdbcTargetEvidenceCommandMaterialStore(
        dataSource, productionControlActivationLedger, objectMapper);
  }

  @Bean
  TargetEvidenceCommandBridgeActivities targetEvidenceCommandBridgeActivity(
      TargetEvidenceCommandMaterialStore targetEvidenceCommandMaterialStore) {
    return new TargetEvidenceCommandBridgeActivity(targetEvidenceCommandMaterialStore);
  }

  @Bean
  TargetEvidenceParticipantBindingActivities targetEvidenceParticipantBindingActivities(
      DataSource dataSource) {
    return new JdbcTargetEvidenceParticipantBindingActivities(dataSource);
  }

  @Bean
  TargetEvidencePartyCompletionActivities targetEvidencePartyCompletionActivities(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      EvidenceDossierFreezer evidenceDossierFreezer) {
    return new JdbcTargetEvidencePartyCompletionActivities(
        dataSource, new TransactionTemplate(transactionManager), evidenceDossierFreezer);
  }

  @Bean
  TargetEvidenceTerminalActivities targetEvidenceTerminalActivities(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      ProductionActivationLifecycleStore productionControlActivationStores,
      EvidenceDossierFreezer evidenceDossierFreezer,
      RoomEpochAllocator roomEpochAllocator,
      ObjectMapper objectMapper) {
    return new JdbcTargetEvidenceTerminalActivities(
        dataSource, new TransactionTemplate(transactionManager),
        productionControlActivationStores, evidenceDossierFreezer,
        roomEpochAllocator, objectMapper, Clock.systemUTC());
  }

  @Bean
  TargetHearingCommandMaterialStore targetHearingCommandMaterialStore(
      DataSource dataSource,
      ProductionActivationLedger productionControlActivationLedger,
      ObjectMapper objectMapper) {
    return new JdbcTargetHearingCommandMaterialStore(
        dataSource, productionControlActivationLedger, objectMapper);
  }

  @Bean
  TargetHearingCommandBridgeActivities targetHearingCommandBridgeActivity(
      TargetHearingCommandMaterialStore targetHearingCommandMaterialStore) {
    return new TargetHearingCommandBridgeActivitiesImpl(targetHearingCommandMaterialStore);
  }

  @Bean
  TargetHearingBootstrapActivities targetHearingBootstrapActivities(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      AppProperties properties,
      DisputeProperties disputeProperties) {
    return new JdbcTargetHearingBootstrapActivities(
        dataSource,
        new TransactionTemplate(transactionManager),
        properties.temporal().namespace(),
        disputeProperties.hearingPartyStageWindow());
  }

  @Bean
  HearingAuthorityLedger targetHearingAuthorityLedger(
      DataSource dataSource, PlatformTransactionManager transactionManager) {
    return new JdbcHearingAuthorityLedger(
        new NamedParameterJdbcTemplate(dataSource), new TransactionTemplate(transactionManager));
  }

  @Bean
  HearingFormalReceiptService targetHearingFormalReceiptService(
      DataSource dataSource, HearingAuthorityLedger targetHearingAuthorityLedger) {
    return new HearingFormalReceiptService(
        new JdbcHearingFormalFinalizer(
            new NamedParameterJdbcTemplate(dataSource), targetHearingAuthorityLedger));
  }

  @Bean
  TargetHearingFormalCompletion targetHearingFormalCompletion(
      HearingFormalReceiptService targetHearingFormalReceiptService) {
    return new TargetHearingFormalCompletion(targetHearingFormalReceiptService);
  }

  @Bean
  TargetHearingInternalStageMaterializer targetHearingInternalStageMaterializer(
      DataSource dataSource,
      Environment environment,
      JdbcProductionApiAuthority targetRoomEpochSelectionAuthority,
      AgentRunLedger agentRunLedger,
      TargetHearingCommandMaterialStore targetHearingCommandMaterialStore,
      MinioClient minioClient,
      ObjectMapper objectMapper,
      CaseEventService caseEventService) {
    Clock clock = Clock.systemUTC();
    var objectIndex = new JdbcProductionRoomObjectIndex(dataSource);
    var payloadPublisher = new MinioProductionRoomCommandPayloadPublisher(
        minioClient, objectMapper, "production-runtime-intake-activation", "room-command-inputs", objectIndex);
    return new TargetHearingInternalStageMaterializer(
        targetRoomEpochSelectionAuthority,
        targetHearingRuntimePins(environment),
        agentRunLedger,
        new AgentRunCommandBindingFactory(objectMapper),
        new ProductionGraphEnvelopeCodec(objectMapper),
        payloadPublisher,
        new ProductionHearingInvocationPublisher(payloadPublisher, objectIndex, objectMapper),
        targetHearingCommandMaterialStore,
        new JdbcTargetHearingAgentStageInputFactory(dataSource, objectMapper),
        objectMapper,
        clock,
        new JdbcTargetHearingAgentRunStartedPublisher(
            dataSource, objectMapper, caseEventService::wakeUp));
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
  TargetHearingFormalizationActivities targetHearingFormalizationActivities(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      TargetHearingFormalCompletion targetHearingFormalCompletion,
      TargetHearingInternalStageMaterializer targetHearingInternalStageMaterializer,
      HearingAuthorityLedger targetHearingAuthorityLedger,
      ObjectMapper objectMapper,
      RoomEpochAllocator roomEpochAllocator,
      JdbcTargetHearingPublicTranscriptCommitter targetHearingPublicTranscriptCommitter) {
    return new JdbcTargetHearingFormalizationActivities(
        dataSource,
        new TransactionTemplate(transactionManager),
        targetHearingFormalCompletion,
        targetHearingInternalStageMaterializer,
        targetHearingAuthorityLedger,
        objectMapper,
        roomEpochAllocator,
        targetHearingPublicTranscriptCommitter);
  }

  @Bean
  TargetReviewCommandMaterialStore targetReviewCommandMaterialStore(
      DataSource dataSource,
      ProductionActivationLedger productionControlActivationLedger,
      ObjectMapper objectMapper) {
    return new JdbcTargetReviewCommandMaterialStore(
        dataSource, productionControlActivationLedger, objectMapper);
  }

  @Bean
  TargetReviewCommandBridgeActivities targetReviewCommandBridgeActivity(
      TargetReviewCommandMaterialStore targetReviewCommandMaterialStore) {
    return new TargetReviewCommandBridgeActivity(targetReviewCommandMaterialStore);
  }

  @Bean
  TargetReviewOutcomeHandoffStore targetReviewOutcomeHandoffStore(
      DataSource dataSource, ObjectMapper objectMapper) {
    return new JdbcTargetReviewOutcomeHandoffStore(dataSource, objectMapper);
  }

  @Bean
  TargetReviewOutcomeHandoffActivities targetReviewOutcomeHandoffRelayActivity(
      TargetReviewOutcomeHandoffStore targetReviewOutcomeHandoffStore) {
    return new TargetReviewOutcomeHandoffActivity(targetReviewOutcomeHandoffStore);
  }

  @Bean
  TargetReviewOutcomeStartBindingPort targetReviewOutcomeStartBindingPort(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      ObjectMapper objectMapper) {
    return new JdbcTargetReviewOutcomeStartBindingPort(
        dataSource, new TransactionTemplate(transactionManager), objectMapper);
  }

  @Bean
  TargetReviewOutcomeStartBindingActivities targetReviewOutcomeStartBindingActivity(
      TargetReviewOutcomeStartBindingPort targetReviewOutcomeStartBindingPort) {
    return new TargetReviewOutcomeStartBindingActivity(targetReviewOutcomeStartBindingPort);
  }

  @Bean
  TargetReviewOutcomeChildUpdateActivities targetReviewOutcomeChildUpdateActivity(
      WorkflowClient workflowClient) {
    return new TargetReviewOutcomeChildUpdateActivity(workflowClient);
  }

  @Bean
  TargetReviewNonExecutionActivities targetReviewNonExecutionActivities(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      RoomEpochAllocator roomEpochAllocator,
      ProductionActivationLedger productionControlActivationLedger,
      ObjectMapper objectMapper,
      DisputeProperties disputeProperties) {
    return new JdbcTargetReviewNonExecutionActivities(
        dataSource,
        new TransactionTemplate(transactionManager),
        roomEpochAllocator,
        productionControlActivationLedger,
        objectMapper,
        Clock.systemUTC(),
        disputeProperties);
  }

  @Bean
  ProductionTemporalWorkerRegistration productionTemporalWorkerRegistration(
      Environment environment,
      TemporalWorkerProperties workerProperties,
      ObjectProvider<ProductionActivationAuthority> productionActivationAuthorityProvider,
      TargetIntakeCommandBridgeActivity targetIntakeCommandBridgeActivity,
      TargetIntakePartyScopeSource targetIntakePartyScopeSource,
      IntakeRoomActivities productionIntakeRoomActivities,
      IntakeAgentRunFinalizationReadActivities targetIntakeAgentRunFinalizationReadActivities,
      TargetEvidenceCommandBridgeActivities targetEvidenceCommandBridgeActivity,
      TargetEvidenceParticipantBindingActivities targetEvidenceParticipantBindingActivities,
      TargetEvidencePartyCompletionActivities targetEvidencePartyCompletionActivities,
      TargetEvidenceTerminalActivities targetEvidenceTerminalActivities,
      TargetHearingCommandBridgeActivities targetHearingCommandBridgeActivity,
      TargetHearingBootstrapActivities targetHearingBootstrapActivities,
      TargetHearingFormalizationActivities targetHearingFormalizationActivities,
      TargetReviewCommandBridgeActivities targetReviewCommandBridgeActivity,
      TargetReviewOutcomeHandoffActivities targetReviewOutcomeHandoffRelayActivity,
      TargetReviewOutcomeStartBindingActivities targetReviewOutcomeStartBindingActivity,
      TargetReviewOutcomeChildUpdateActivities targetReviewOutcomeChildUpdateActivity,
      TargetReviewNonExecutionActivities targetReviewNonExecutionActivities,
      TargetOutcomeCompletionActivities targetOutcomeCompletionActivities) {
    requireArmedActivationAuthorityIfEnabled(
        environment, productionActivationAuthorityProvider);
    String activationId = required(environment, "production.runtime.activation.id");
    ProductionTemporalWorkerRegistration.Registration registration =
        new ProductionTemporalWorkerRegistration.Registration(
            "production-runtime",
            "PRODUCTION",
            activationId,
            workerProperties.buildId(),
            ProductionCaseProcessWorkflow.class,
            TargetTypedRoomProtocol.additionalWorkflowImplementations(),
             List.of(
                 targetIntakeCommandBridgeActivity,
                 targetIntakePartyScopeSource,
                 productionIntakeRoomActivities,
                targetIntakeAgentRunFinalizationReadActivities,
                targetEvidenceCommandBridgeActivity,
                targetEvidenceParticipantBindingActivities,
                targetEvidencePartyCompletionActivities,
                targetEvidenceTerminalActivities,
                targetHearingCommandBridgeActivity,
                targetHearingBootstrapActivities,
                targetHearingFormalizationActivities,
                targetReviewCommandBridgeActivity,
                targetReviewOutcomeHandoffRelayActivity,
                targetReviewOutcomeStartBindingActivity,
                 targetReviewOutcomeChildUpdateActivity,
                 targetReviewNonExecutionActivities,
                 targetOutcomeCompletionActivities),
            List.of(targetOutcomeCompletionActivities));
    return () -> registration;
  }

  private static void requireArmedActivationAuthorityIfEnabled(
      Environment environment,
      ObjectProvider<ProductionActivationAuthority> productionActivationAuthorityProvider) {
    boolean activationEnabled =
        environment.getProperty("app.production-runtime.enabled", Boolean.class, false);
    if (activationEnabled && productionActivationAuthorityProvider.getIfUnique() == null) {
      throw new IllegalStateException(
          "production runtime CONTROL registration requires an armed activation authority");
    }
  }

  private static String required(Environment environment, String property) {
    String value = environment.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(property + " is required by the target Temporal lane");
    }
    return value;
  }

  private static TargetIntakeRuntimePins targetHearingRuntimePins(Environment environment) {
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

  /** The only concrete target-capable CaseProcess implementation in the packaged application. */
  public static final class ProductionCaseProcessWorkflow
      extends TargetTypedRoomCaseProcessDispatcher {

    @Override
    protected boolean targetArtifactPresent() {
      return true;
    }
  }
}
