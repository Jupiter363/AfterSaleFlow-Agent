package com.example.dispute.workflow.targete2e.artifact;

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
import com.example.dispute.workflow.targete2e.TargetE2eActivationAuthority;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
import com.example.dispute.workflow.targete2e.persistence.material.JdbcTargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.targete2e.temporal.TargetTemporalWorkerRegistration;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomCaseProcessDispatcher;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import com.example.dispute.workflow.targete2e.temporal.intake.TargetIntakeCommandBridgeActivity;
import com.example.dispute.workflow.targete2e.temporal.intake.JdbcTargetIntakeBranchContextSource;
import com.example.dispute.workflow.targete2e.temporal.intake.JdbcTargetIntakePartyScopeSource;
import com.example.dispute.workflow.targete2e.temporal.intake.TargetIntakeBranchContextSource;
import com.example.dispute.workflow.targete2e.temporal.intake.TargetIntakePartyScopeSource;
import com.example.dispute.workflow.targete2e.rooms.intake.TargetE2eIntakeFormalBranchCommandResolver;
import com.example.dispute.workflow.targete2e.rooms.intake.TargetE2eIntakeRoomActivities;
import com.example.dispute.workflow.targete2e.temporal.intake.finalizationread.JdbcTargetIntakeAgentRunFinalizationReceiptReadPort;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadActivities;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReadActivitiesAdapter;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReceiptReadPort;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomActivities;
import com.example.dispute.workflow.targete2e.rooms.evidence.JdbcTargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandBridgeActivities;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandBridgeActivity;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.evidence.JdbcTargetEvidenceTerminalActivities;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceTerminalActivities;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceParticipantBindingActivities;
import com.example.dispute.workflow.targete2e.rooms.evidence.JdbcTargetEvidenceParticipantBindingActivities;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidencePartyCompletionActivities;
import com.example.dispute.workflow.targete2e.rooms.evidence.JdbcTargetEvidencePartyCompletionActivities;
import com.example.dispute.workflow.targete2e.rooms.hearing.JdbcTargetHearingCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingCommandBridgeActivities;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingCommandBridgeActivitiesImpl;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.hearing.JdbcTargetHearingAgentStageInputFactory;
import com.example.dispute.workflow.targete2e.rooms.hearing.JdbcTargetHearingFormalizationActivities;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingFormalCompletion;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingFormalizationActivities;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingInternalStageMaterializer;
import com.example.dispute.workflow.targete2e.temporal.room.hearing.JdbcTargetHearingBootstrapActivities;
import com.example.dispute.workflow.targete2e.temporal.room.hearing.TargetHearingBootstrapActivities;
import com.example.dispute.workflow.targete2e.ingress.materialization.TargetIntakeRuntimePins;
import com.example.dispute.workflow.targete2e.ingress.rooms.MinioTargetE2eRoomCommandPayloadPublisher;
import com.example.dispute.workflow.targete2e.ingress.rooms.TargetE2eHearingInvocationPublisher;
import com.example.dispute.workflow.targete2e.exchange.rooms.JdbcTargetE2eRoomObjectIndex;
import com.example.dispute.workflow.targete2e.persistence.JdbcTargetE2eActivationStores;
import com.example.dispute.workflow.targete2e.persistence.JdbcTargetE2eApiAuthority;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.rooms.review.JdbcTargetReviewCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewCommandBridgeActivities;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewCommandBridgeActivity;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.review.JdbcTargetReviewOutcomeHandoffStore;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewOutcomeHandoffActivities;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewOutcomeHandoffActivity;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewOutcomeHandoffStore;
import com.example.dispute.workflow.targete2e.rooms.review.JdbcTargetReviewOutcomeStartBindingPort;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewOutcomeStartBindingActivities;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewOutcomeStartBindingActivity;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewOutcomeStartBindingPort;
import com.example.dispute.workflow.targete2e.rooms.review.JdbcTargetReviewNonExecutionActivities;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewNonExecutionActivities;
import com.example.dispute.workflow.targete2e.rooms.outcome.JdbcTargetOutcomeCompletionActivities;
import com.example.dispute.workflow.targete2e.rooms.outcome.JdbcTargetTemporalOutcomeBindingResolver;
import com.example.dispute.workflow.targete2e.rooms.outcome.TargetDeterministicEvaluationAgentClient;
import com.example.dispute.workflow.targete2e.rooms.outcome.TargetOutcomeCompletionActivities;
import com.example.dispute.evaluation.application.CaseClosureService;
import com.example.dispute.evaluation.application.EvaluationAgentClient;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger;
import com.example.dispute.executor.infrastructure.persistence.JdbcOutcomeOperationLedger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import io.minio.MinioClient;
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
@Profile("target-e2e")
@ConditionalOnProperty(
    name = "app.temporal.worker.role", havingValue = "CONTROL")
public class TargetE2eControlConfiguration {

  @Bean
  TargetE2EActivationLedger targetE2eControlActivationLedger(DataSource dataSource) {
    return new TargetE2EActivationLedger(dataSource, Clock.systemUTC());
  }

  @Bean
  JdbcTargetE2eApiAuthority targetRoomEpochSelectionAuthority(
      DataSource dataSource, Environment environment) {
    Clock clock = Clock.systemUTC();
    return new JdbcTargetE2eApiAuthority(
        dataSource,
        new JdbcTargetE2eActivationStores(dataSource, clock),
        required(environment, "target.e2e.activation.id"),
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
      TargetE2EActivationLedger targetE2eControlActivationLedger) {
    return new JdbcTargetOutcomeCompletionActivities(
        dataSource,
        new TransactionTemplate(transactionManager),
        objectMapper,
        Clock.systemUTC(),
        caseClosureService,
        targetOutcomeOperationLedger,
        targetTemporalOutcomeBindingResolver,
        targetE2eControlActivationLedger);
  }

  @Bean
  TargetIntakeCommandMaterialStore targetIntakeCommandMaterialStore(
      DataSource dataSource, TargetE2EActivationLedger targetE2eControlActivationLedger, ObjectMapper objectMapper) {
    return new JdbcTargetIntakeCommandMaterialStore(
        dataSource, targetE2eControlActivationLedger, objectMapper);
  }

  @Bean
  TargetIntakeBranchContextSource targetIntakeBranchContextSource(
      MinioClient minioClient, ObjectMapper objectMapper, DataSource dataSource) {
    return new JdbcTargetIntakeBranchContextSource(
        minioClient,
        objectMapper,
        dataSource,
        TargetE2eIntakeFormalBranchCommandResolver.TARGET_INTAKE_BUCKET,
        TargetE2eIntakeFormalBranchCommandResolver.TARGET_INTAKE_PREFIX);
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
  IntakeFormalBranchCommandResolver targetE2eIntakeFormalBranchCommandResolver(
      MinioClient minioClient, ObjectMapper objectMapper) {
    return new TargetE2eIntakeFormalBranchCommandResolver(
        minioClient,
        objectMapper,
        TargetE2eIntakeFormalBranchCommandResolver.TARGET_INTAKE_BUCKET,
        TargetE2eIntakeFormalBranchCommandResolver.TARGET_INTAKE_PREFIX);
  }

  @Bean
  IntakeBranchDomainService targetE2eIntakeBranchDomainService(
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
  IntakeFormalBranchCommitPort targetE2eIntakeFormalBranchCommitPort(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      FulfillmentCaseRepository caseRepository,
      CaseRoomRepository roomRepository,
      IntakeBranchDomainService targetE2eIntakeBranchDomainService,
      IntakeFormalBranchCommandResolver targetE2eIntakeFormalBranchCommandResolver,
      RoomEpochAllocator roomEpochAllocator,
      ObjectMapper objectMapper) {
    return new JdbcIntakeFormalBranchCommitPort(
        new NamedParameterJdbcTemplate(dataSource),
        transactionManager,
        caseRepository,
        roomRepository,
        targetE2eIntakeBranchDomainService,
        targetE2eIntakeFormalBranchCommandResolver,
        roomEpochAllocator,
        "all-rooms.target-e2e.v1",
        objectMapper,
        Clock.systemUTC());
  }

  @Bean
  IntakeRoomActivities targetE2eIntakeRoomActivities(
      IntakeFormalBranchCommitPort targetE2eIntakeFormalBranchCommitPort) {
    return new TargetE2eIntakeRoomActivities(targetE2eIntakeFormalBranchCommitPort);
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
      TargetE2EActivationLedger targetE2eControlActivationLedger,
      ObjectMapper objectMapper) {
    return new JdbcTargetEvidenceCommandMaterialStore(
        dataSource, targetE2eControlActivationLedger, objectMapper);
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
      EvidenceDossierFreezer evidenceDossierFreezer,
      RoomEpochAllocator roomEpochAllocator,
      ObjectMapper objectMapper) {
    return new JdbcTargetEvidenceTerminalActivities(
        dataSource, new TransactionTemplate(transactionManager), evidenceDossierFreezer,
        roomEpochAllocator, objectMapper, Clock.systemUTC());
  }

  @Bean
  TargetHearingCommandMaterialStore targetHearingCommandMaterialStore(
      DataSource dataSource,
      TargetE2EActivationLedger targetE2eControlActivationLedger,
      ObjectMapper objectMapper) {
    return new JdbcTargetHearingCommandMaterialStore(
        dataSource, targetE2eControlActivationLedger, objectMapper);
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
      AppProperties properties) {
    return new JdbcTargetHearingBootstrapActivities(
        dataSource, new TransactionTemplate(transactionManager), properties.temporal().namespace());
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
      JdbcTargetE2eApiAuthority targetRoomEpochSelectionAuthority,
      AgentRunLedger agentRunLedger,
      TargetHearingCommandMaterialStore targetHearingCommandMaterialStore,
      MinioClient minioClient,
      ObjectMapper objectMapper) {
    Clock clock = Clock.systemUTC();
    var objectIndex = new JdbcTargetE2eRoomObjectIndex(dataSource);
    var payloadPublisher = new MinioTargetE2eRoomCommandPayloadPublisher(
        minioClient, objectMapper, "target-e2e-intake-activation", "room-command-inputs", objectIndex);
    return new TargetHearingInternalStageMaterializer(
        targetRoomEpochSelectionAuthority,
        targetHearingRuntimePins(environment),
        agentRunLedger,
        new AgentRunCommandBindingFactory(objectMapper),
        new TargetE2EGraphEnvelopeCodec(objectMapper),
        payloadPublisher,
        new TargetE2eHearingInvocationPublisher(payloadPublisher, objectIndex, objectMapper),
        targetHearingCommandMaterialStore,
        new JdbcTargetHearingAgentStageInputFactory(dataSource, objectMapper),
        objectMapper,
        clock);
  }

  @Bean
  TargetHearingFormalizationActivities targetHearingFormalizationActivities(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      TargetHearingFormalCompletion targetHearingFormalCompletion,
      TargetHearingInternalStageMaterializer targetHearingInternalStageMaterializer,
      HearingAuthorityLedger targetHearingAuthorityLedger,
      ObjectMapper objectMapper,
      RoomEpochAllocator roomEpochAllocator) {
    return new JdbcTargetHearingFormalizationActivities(
        dataSource,
        new TransactionTemplate(transactionManager),
        targetHearingFormalCompletion,
        targetHearingInternalStageMaterializer,
        targetHearingAuthorityLedger,
        objectMapper,
        roomEpochAllocator);
  }

  @Bean
  TargetReviewCommandMaterialStore targetReviewCommandMaterialStore(
      DataSource dataSource,
      TargetE2EActivationLedger targetE2eControlActivationLedger,
      ObjectMapper objectMapper) {
    return new JdbcTargetReviewCommandMaterialStore(
        dataSource, targetE2eControlActivationLedger, objectMapper);
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
  TargetReviewNonExecutionActivities targetReviewNonExecutionActivities(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      RoomEpochAllocator roomEpochAllocator,
      TargetE2EActivationLedger targetE2eControlActivationLedger,
      ObjectMapper objectMapper,
      DisputeProperties disputeProperties) {
    return new JdbcTargetReviewNonExecutionActivities(
        dataSource,
        new TransactionTemplate(transactionManager),
        roomEpochAllocator,
        targetE2eControlActivationLedger,
        objectMapper,
        Clock.systemUTC(),
        disputeProperties);
  }

  @Bean
  TargetTemporalWorkerRegistration targetTemporalWorkerRegistration(
      Environment environment,
      TemporalWorkerProperties workerProperties,
      ObjectProvider<TargetE2eActivationAuthority> targetE2eActivationAuthorityProvider,
      TargetIntakeCommandBridgeActivity targetIntakeCommandBridgeActivity,
      TargetIntakePartyScopeSource targetIntakePartyScopeSource,
      IntakeRoomActivities targetE2eIntakeRoomActivities,
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
      TargetReviewNonExecutionActivities targetReviewNonExecutionActivities,
      TargetOutcomeCompletionActivities targetOutcomeCompletionActivities) {
    requireArmedActivationAuthorityIfEnabled(
        environment, targetE2eActivationAuthorityProvider);
    String activationId = required(environment, "target.e2e.activation.id");
    TargetTemporalWorkerRegistration.Registration registration =
        new TargetTemporalWorkerRegistration.Registration(
            "target-e2e",
            "TARGET_E2E_CANDIDATE",
            activationId,
            workerProperties.buildId(),
            TargetE2eCaseProcessWorkflow.class,
            TargetTypedRoomProtocol.additionalWorkflowImplementations(),
             List.of(
                 targetIntakeCommandBridgeActivity,
                 targetIntakePartyScopeSource,
                 targetE2eIntakeRoomActivities,
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
                targetReviewNonExecutionActivities,
                targetOutcomeCompletionActivities),
            List.of());
    return () -> registration;
  }

  private static void requireArmedActivationAuthorityIfEnabled(
      Environment environment,
      ObjectProvider<TargetE2eActivationAuthority> targetE2eActivationAuthorityProvider) {
    boolean activationEnabled =
        environment.getProperty("app.target-e2e.enabled", Boolean.class, false);
    if (activationEnabled && targetE2eActivationAuthorityProvider.getIfUnique() == null) {
      throw new IllegalStateException(
          "target E2E CONTROL registration requires an armed activation authority");
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
        required(environment, "target.e2e.case-build-id"),
        required(environment, "target.e2e.agent-build-id"),
        required(environment, "target.e2e.graph-binding-hash"),
        required(environment, "target.e2e.graph-code-build-id"),
        required(environment, "target.e2e.isolated-domain-db-binding-hash"),
        required(environment, "target.e2e.intake.agent-profile-id"),
        required(environment, "target.e2e.intake.prompt-version"),
        required(environment, "target.e2e.intake.model-profile-id"),
        required(environment, "target.e2e.intake.policy-version"),
        required(environment, "target.e2e.intake.guardrail-version"),
        required(environment, "target.e2e.intake.tool-policy-version"),
        required(environment, "target.e2e.intake.memory-policy-version"),
        required(environment, "target.e2e.intake.envelope-key-id"));
  }

  /** The only concrete target-capable CaseProcess implementation in the packaged application. */
  public static final class TargetE2eCaseProcessWorkflow
      extends TargetTypedRoomCaseProcessDispatcher {

    @Override
    protected boolean targetArtifactPresent() {
      return true;
    }
  }
}
