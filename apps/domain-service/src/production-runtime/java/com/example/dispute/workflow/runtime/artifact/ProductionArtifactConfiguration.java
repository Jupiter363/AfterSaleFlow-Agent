package com.example.dispute.workflow.runtime.artifact;

import com.example.dispute.agentstream.application.AgentRunReconciledFinalStore;
import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.agentstream.application.AgentRunFormalResultCommitter;
import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.agentstream.application.AgentRunTransientStreamPublisher;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.room.infrastructure.persistence.JdbcIntakeFormalCommitPort;
import com.example.dispute.workflow.activity.agent.AgentGraphCommandClient;
import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.AgentRunFinalizationGateway;
import com.example.dispute.workflow.activity.agent.DurableAgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.activity.agent.ProfileSelectingAgentRunExecutionGateway;
import com.example.dispute.workflow.config.GraphCommandClientProperties;
import com.example.dispute.workflow.config.TemporalWorkerProperties;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.infrastructure.security.MountedPemGraphEnvelopeKeySet;
import com.example.dispute.workflow.application.intake.IntakeAgentRunDomainResultCommitter;
import com.example.dispute.workflow.application.intake.IntakeGraphResultFinalizer;
import com.example.dispute.workflow.application.intake.IntakeTurnProposalLoader;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyContextResolver;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameAdmissionAuthorityResolver;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameAssembler;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameExecutionClient;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelRunTerminalStore;
import com.example.dispute.workflow.infrastructure.persistence.intake.parallel.JdbcProductionIntakeParallelAssemblyFinalizationPort;
import com.example.dispute.workflow.runtime.artifact.finalization.JdbcProductionFinalizationAuthority;
import com.example.dispute.workflow.runtime.artifact.finalization.JdbcProductionIntakeCommandCompletionWriter;
import com.example.dispute.workflow.runtime.artifact.finalization.ReconciledProductionFinalizationEvidenceProvider;
import com.example.dispute.workflow.runtime.artifact.finalization.ProductionIntakeDomainEventLiveRelay;
import com.example.dispute.workflow.runtime.artifact.finalization.ProductionMultiRoomFinalizationGateway;
import com.example.dispute.workflow.runtime.finalization.ProductionGraphOutputSnapshotMaterializer;
import com.example.dispute.workflow.runtime.finalization.IntakeParallelV4DurableFinalAuthorityResolver;
import com.example.dispute.workflow.runtime.finalization.JdbcProductionV4FinalAuthoritySource;
import com.example.dispute.workflow.runtime.finalization.RoutingProductionDurableFinalAuthorityResolver;
import com.example.dispute.workflow.runtime.finalization.ProductionDurableFinalAuthorityResolver;
import com.example.dispute.workflow.runtime.finalization.ProductionMultiRoomOuterFinalizer;
import com.example.dispute.workflow.runtime.finalization.V3ProductionDurableFinalAuthorityResolver;
import com.example.dispute.workflow.runtime.finalization.JdbcProductionIntakeFinalizationStateReader;
import com.example.dispute.workflow.runtime.finalization.MinioProductionIntakeProposalStore;
import com.example.dispute.workflow.runtime.finalization.ProductionAgentRunV2FinalizationFactsProvider;
import com.example.dispute.workflow.runtime.finalization.ProductionAuthorizedIntakeFinalizationSource;
import com.example.dispute.workflow.runtime.finalization.ProductionExecutionLaneVerifier;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationBindingVerifier;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationEvidenceProvider;
import com.example.dispute.workflow.runtime.finalization.ReadyAssemblyProductionFinalizationEvidenceProvider;
import com.example.dispute.workflow.runtime.finalization.RoutingProductionFinalizationEvidenceProvider;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationRuntimeContextProvider;
import com.example.dispute.workflow.runtime.finalization.JdbcProductionFinalizationReceiptLedger;
import com.example.dispute.workflow.runtime.finalization.JdbcIntakeParallelProposalStore;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceiptLedger;
import com.example.dispute.workflow.runtime.finalization.ProductionCommandCompletionWriter;
import com.example.dispute.workflow.runtime.finalization.ProductionIntakeRoomFinalizationStrategy;
import com.example.dispute.workflow.runtime.finalization.ProductionRoomFinalizationStrategy;
import com.example.dispute.workflow.runtime.finalization.ProductionRoomFinalizationStrategyRegistry;
import com.example.dispute.workflow.runtime.finalization.ProductionIntakeFinalizationRequestResolver;
import com.example.dispute.workflow.runtime.finalization.ProductionIntakeFinalizationStateReader;
import com.example.dispute.workflow.runtime.finalization.ProductionIntakeParallelAssemblyFinalizationPort;
import com.example.dispute.workflow.runtime.finalization.ProductionIntakeProposalReader;
import com.example.dispute.workflow.runtime.finalization.ProductionIntakeProposalStore;
import com.example.dispute.workflow.runtime.finalization.RoutingProductionIntakeProposalStore;
import com.example.dispute.workflow.runtime.finalization.TemporalProductionFinalizationRuntimeContextProvider;
import com.example.dispute.workflow.runtime.graph.Es256ProductionGraphEnvelopeSigner;
import com.example.dispute.workflow.runtime.graph.JdbcProductionAgentSessionResolver;
import com.example.dispute.workflow.runtime.graph.HttpProductionGraphProposalClient;
import com.example.dispute.workflow.runtime.graph.HttpProductionGraphProposalSourceClient;
import com.example.dispute.workflow.runtime.graph.HttpProductionGraphReconciliationClient;
import com.example.dispute.workflow.runtime.graph.HttpProductionIntakeParallelFrameExecutionClient;
import com.example.dispute.workflow.runtime.graph.JpaProductionAgentRunIdentityResolver;
import com.example.dispute.workflow.runtime.graph.MaterializedIntakeParallelAssemblyContextResolver;
import com.example.dispute.workflow.runtime.graph.ProductionAgentGraphCommandClient;
import com.example.dispute.workflow.runtime.graph.ProductionAgentGraphReconciliationClient;
import com.example.dispute.workflow.runtime.graph.ProductionAgentRunIdentityResolver;
import com.example.dispute.workflow.runtime.graph.ProductionAgentSessionResolver;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeCodec;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeSigner;
import com.example.dispute.workflow.runtime.graph.ProductionGraphProposalClient;
import com.example.dispute.workflow.runtime.graph.ProductionIntakeParallelAssemblyCoordinator;
import com.example.dispute.workflow.runtime.graph.ProductionIntakeParallelExecutionGateway;
import com.example.dispute.workflow.runtime.graph.ProductionIntakeParallelGraphReconciliationClient;
import com.example.dispute.workflow.runtime.ProductionActivationLifecycleStore;
import com.example.dispute.workflow.runtime.ProductionAgentDeploymentBinding;
import com.example.dispute.workflow.runtime.lifecycle.ProductionActivationLifecycleControl;
import com.example.dispute.workflow.runtime.lifecycle.ProductionActivationLifecycleControl.DeploymentBinding;
import com.example.dispute.workflow.runtime.persistence.JdbcProductionActivationStores;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger;
import com.example.dispute.workflow.runtime.persistence.material.JdbcTargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.runtime.persistence.material.TargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.minio.MinioClient;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Production-only AgentRun and proposal Graph assembly, absent from the ordinary Java artifact. */
@Configuration(proxyBeanMethods = false)
@Profile(ProductionArtifactPrerequisites.REQUIRED_PROFILE)
@ConditionalOnProperty(
        name = ProductionArtifactPrerequisites.WORKER_ROLE_PROPERTY,
        havingValue = ProductionArtifactPrerequisites.AGENT_WORKER_ROLE)
public class ProductionArtifactConfiguration {

    @Bean
    ProductionArtifactMarker productionArtifactMarker() {
        return new ProductionArtifactMarker(ProductionArtifactMarker.EXPECTED_VALUE);
    }

    @Bean
    @DependsOn("flyway")
    ProductionAgentDeploymentBinding productionAgentDeploymentBinding(
            DataSource dataSource,
            GraphCommandClientProperties graphProperties,
            TemporalWorkerProperties workerProperties,
            Environment environment) {
        requireTargetMode(graphProperties);
        String activationId = required(environment, "production.runtime.activation.id");
        ProductionAgentDeploymentBinding configured =
                new ProductionAgentDeploymentBinding(
                        required(environment, "production.runtime.environment.id"),
                        requiredPositiveLong(
                                environment, "production.runtime.environment.generation"),
                        activationId,
                        required(environment, "production.runtime.activation.manifest-hash"),
                        workerProperties.buildId());
        configured.requireWorkerConfiguration(
                graphProperties.activationId(), workerProperties.buildId());
        List<ProductionAgentDeploymentBinding> registered =
                new JdbcTemplate(dataSource)
                        .query(
                                """
                                select environment_id, environment_generation,
                                       activation_id, manifest_hash, agent_build_id
                                  from production_runtime_activation
                                 where activation_id = ?
                                """,
                                (result, ignored) ->
                                        new ProductionAgentDeploymentBinding(
                                                result.getString("environment_id"),
                                                result.getLong("environment_generation"),
                                                result.getString("activation_id"),
                                                result.getString("manifest_hash"),
                                                result.getString("agent_build_id")),
                                activationId);
        if (registered.size() != 1) {
            throw new IllegalStateException(
                    "target AGENT activation registration is absent or ambiguous");
        }
        return ProductionAgentDeploymentBinding.requireExact(
                configured, registered.getFirst());
    }

    @Bean
    ProductionGraphEnvelopeCodec productionGraphEnvelopeCodec(ObjectMapper objectMapper) {
        return new ProductionGraphEnvelopeCodec(objectMapper);
    }

    @Bean
    ProductionAgentSessionResolver productionAgentSessionResolver(
            DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcProductionAgentSessionResolver(dataSource, objectMapper);
    }

    @Bean
    ProductionGraphEnvelopeSigner productionGraphEnvelopeSigner(
            MountedPemGraphEnvelopeKeySet signingKeys,
            ObjectMapper objectMapper,
            ProductionAgentSessionResolver agentSessions) {
        return new Es256ProductionGraphEnvelopeSigner(
                signingKeys,
                objectMapper,
                Clock.systemUTC(),
                java.time.Duration.ofSeconds(60),
                () -> "target-command-" + java.util.UUID.randomUUID(),
                agentSessions);
    }

    @Bean
    ProductionAgentRunIdentityResolver productionAgentRunIdentityResolver(
            AgentRunRepository runRepository,
            AgentRunAttemptRepository attemptRepository) {
        return new JpaProductionAgentRunIdentityResolver(runRepository, attemptRepository);
    }

    @Bean
    HttpProductionGraphProposalSourceClient productionGraphProposalSourceClient(
            GraphTransportBundle transports,
            ProductionGraphEnvelopeCodec codec,
            GraphCommandClientProperties properties) {
        requireTargetMode(properties);
        return new HttpProductionGraphProposalSourceClient(
                transports, codec, properties.baseUri(), properties.requestTimeout());
    }

    @Bean
    HttpProductionGraphReconciliationClient productionGraphReconciliationHttpClient(
            GraphTransportBundle transports,
            ProductionGraphEnvelopeCodec codec,
            HttpProductionGraphProposalSourceClient proposalSource,
            ObjectMapper objectMapper,
            GraphCommandClientProperties properties) {
        requireTargetMode(properties);
        return new HttpProductionGraphReconciliationClient(
                transports,
                codec,
                proposalSource,
                objectMapper,
                properties.baseUri(),
                properties.requestTimeout());
    }

    @Bean
    ProductionGraphProposalClient productionGraphProposalClient(
            GraphTransportBundle transports,
            HttpProductionGraphReconciliationClient reconciliationClient,
            ObjectMapper objectMapper,
            GraphCommandClientProperties properties) {
        requireTargetMode(properties);
        return new HttpProductionGraphProposalClient(
                transports,
                reconciliationClient,
                objectMapper,
                properties.baseUri(),
                properties.requestTimeout());
    }

    @Bean
    AgentGraphCommandClient productionAgentGraphCommandClient(
            ProductionAgentRunIdentityResolver identityResolver,
            ProductionGraphEnvelopeCodec codec,
            ProductionGraphEnvelopeSigner signer,
            ProductionGraphProposalClient proposalClient,
            GraphStreamVisibilityPolicy visibilityPolicy,
            GraphRegistryBindingPolicy registryBindingPolicy,
            ProductionAgentDeploymentBinding deploymentBinding,
            GraphCommandClientProperties properties) {
        requireTargetMode(properties);
        return new ProductionAgentGraphCommandClient(
                deploymentBinding.activationId(),
                identityResolver,
                codec,
                signer,
                proposalClient,
                visibilityPolicy,
                registryBindingPolicy);
    }

    @Bean
    AgentGraphReconciliationClient productionAgentGraphReconciliationClient(
            ProductionAgentRunIdentityResolver identityResolver,
            ProductionGraphEnvelopeCodec codec,
            ProductionGraphEnvelopeSigner signer,
            HttpProductionGraphReconciliationClient reconciliationClient,
            GraphRegistryBindingPolicy registryBindingPolicy,
            ProductionAgentDeploymentBinding deploymentBinding,
            GraphCommandClientProperties properties) {
        requireTargetMode(properties);
        return new ProductionAgentGraphReconciliationClient(
                deploymentBinding.activationId(),
                identityResolver,
                codec,
                signer,
                reconciliationClient,
                registryBindingPolicy);
    }

    @Bean
    IntakeParallelFrameExecutionClient productionIntakeParallelFrameExecutionClient(
            GraphTransportBundle transports,
            ProductionAgentRunIdentityResolver identityResolver,
            ProductionGraphEnvelopeCodec codec,
            ProductionGraphEnvelopeSigner signer,
            GraphRegistryBindingPolicy registryBindingPolicy,
            IntakeParallelFrameAdmissionAuthorityResolver admissionAuthorityResolver,
            IntakeParallelFrameStagingPort staging,
            ObjectMapper objectMapper,
            ProductionAgentDeploymentBinding deploymentBinding,
            GraphCommandClientProperties properties) {
        requireTargetMode(properties);
        return new HttpProductionIntakeParallelFrameExecutionClient(
                deploymentBinding.activationId(),
                transports,
                identityResolver,
                codec,
                signer,
                registryBindingPolicy,
                admissionAuthorityResolver,
                staging,
                objectMapper,
                properties.baseUri(),
                properties.requestTimeout());
    }

    @Bean
    IntakeParallelAssemblyContextResolver productionIntakeParallelAssemblyContextResolver(
            TargetIntakeCommandMaterialStore materialStore, ObjectMapper objectMapper) {
        return new MaterializedIntakeParallelAssemblyContextResolver(
                materialStore, objectMapper);
    }

    @Bean
    IntakeParallelFrameAssembler productionIntakeParallelFrameAssembler() {
        return new IntakeParallelFrameAssembler();
    }

    @Bean
    ProductionIntakeParallelAssemblyCoordinator productionIntakeParallelAssemblyCoordinator(
            ProductionAgentRunIdentityResolver identityResolver,
            GraphRegistryBindingPolicy registryBindingPolicy,
            IntakeParallelAssemblyContextResolver contextResolver,
            IntakeParallelAssemblyStore assemblyStore,
            IntakeParallelFrameAssembler assembler,
            ProductionGraphEnvelopeCodec envelopeCodec,
            ObjectMapper objectMapper,
            ProductionAgentDeploymentBinding deploymentBinding) {
        return new ProductionIntakeParallelAssemblyCoordinator(
                deploymentBinding.activationId(),
                identityResolver,
                registryBindingPolicy,
                contextResolver,
                assemblyStore,
                assembler,
                envelopeCodec,
                objectMapper);
    }

    @Bean
    AgentRunExecutionGateway productionAgentRunExecutionGateway(
            AgentGraphCommandClient commandClient,
            AgentGraphReconciliationClient reconciliationClient,
            AgentRunV2StreamStore streamStore,
            AgentRunReconciledFinalStore reconciledFinalStore,
            AgentRunTransientStreamPublisher transientPublisher,
            IntakeParallelFrameExecutionClient frameExecutionClient,
            ProductionIntakeParallelAssemblyCoordinator assemblyCoordinator,
            IntakeParallelRunTerminalStore terminalStore) {
        AgentRunExecutionGateway legacy = new DurableAgentRunExecutionGateway(
                commandClient,
                reconciliationClient,
                streamStore,
                reconciledFinalStore,
                transientPublisher);
        AgentRunExecutionGateway parallel = new ProductionIntakeParallelExecutionGateway(
                frameExecutionClient,
                assemblyCoordinator,
                new ProductionIntakeParallelGraphReconciliationClient(assemblyCoordinator),
                terminalStore);
        return new ProfileSelectingAgentRunExecutionGateway(legacy, parallel);
    }

    @Bean
    JdbcProductionFinalizationAuthority productionFinalizationAuthority(
            DataSource dataSource,
            ProductionAgentDeploymentBinding deploymentBinding,
            Clock clock) {
        return new JdbcProductionFinalizationAuthority(
                dataSource, deploymentBinding.activationId(), clock);
    }

    @Bean
    ProductionIntakeFinalizationStateReader productionIntakeFinalizationStateReader(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        return new JdbcProductionIntakeFinalizationStateReader(
                dataSource, transactionManager, objectMapper);
    }

    @Bean
    ProductionFinalizationRuntimeContextProvider productionFinalizationRuntimeContextProvider(
            ProductionAgentDeploymentBinding deploymentBinding, Environment environment) {
        return new TemporalProductionFinalizationRuntimeContextProvider(
                deploymentBinding.agentBuildId(),
                deploymentBinding.activationId(),
                deploymentBinding.manifestHash(),
                required(environment, "production.runtime.isolated-domain-db-binding-hash"));
    }

    @Bean
    ProductionFinalizationEvidenceProvider productionFinalizationEvidenceProvider(
            JdbcProductionFinalizationAuthority authority,
            ProductionGraphEnvelopeCodec codec,
            ProductionGraphEnvelopeSigner signer,
            HttpProductionGraphReconciliationClient reconciliation,
            HttpProductionGraphProposalSourceClient proposalSource,
            GraphRegistryBindingPolicy registryBindings,
            IntakeParallelAssemblyStore assemblyStore,
            ObjectMapper objectMapper) {
        ProductionFinalizationEvidenceProvider legacy =
                new ReconciledProductionFinalizationEvidenceProvider(
                authority,
                codec,
                signer,
                reconciliation,
                proposalSource,
                registryBindings,
                objectMapper);
        ProductionFinalizationEvidenceProvider parallel =
                new ReadyAssemblyProductionFinalizationEvidenceProvider(
                        assemblyStore, authority, objectMapper);
        return new RoutingProductionFinalizationEvidenceProvider(legacy, parallel);
    }

    @Bean
    ProductionAuthorizedIntakeFinalizationSource productionAuthorizedFinalizationSource(
            ProductionIntakeFinalizationStateReader stateReader,
            JdbcProductionFinalizationAuthority authority,
            ProductionFinalizationRuntimeContextProvider runtimeContext,
            ProductionFinalizationEvidenceProvider evidenceProvider,
            ObjectMapper objectMapper,
            Clock clock) {
        return new ProductionAuthorizedIntakeFinalizationSource(
                stateReader,
                authority,
                runtimeContext,
                new ProductionExecutionLaneVerifier(clock),
                evidenceProvider,
                new ProductionFinalizationBindingVerifier(objectMapper));
    }

    @Bean
    ProductionAgentRunV2FinalizationFactsProvider productionFinalizationFactsProvider(
            ProductionAuthorizedIntakeFinalizationSource source) {
        return new ProductionAgentRunV2FinalizationFactsProvider(source);
    }

    @Bean
    ProductionIntakeProposalStore productionIntakeProposalStore(
            MinioClient minioClient, DataSource dataSource, Environment environment) {
        var minio = new MinioProductionIntakeProposalStore(
                minioClient,
                environment.getProperty(
                        "app.production-runtime.finalization.intake-proposal-bucket",
                        "production-runtime-intake-activation"),
                environment.getProperty(
                        "app.production-runtime.finalization.intake-proposal-prefix",
                        "graph-proposals"));
        var parallel = new JdbcIntakeParallelProposalStore(
                new NamedParameterJdbcTemplate(dataSource));
        return new RoutingProductionIntakeProposalStore(minio, parallel);
    }

    @Bean
    ProductionIntakeProposalReader productionIntakeProposalReader(
            ProductionIntakeProposalStore store) {
        return new ProductionIntakeProposalReader(store);
    }

    @Bean
    JdbcIntakeFormalCommitPort productionIntakeFormalCommitPort(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            Clock clock) {
        return new JdbcIntakeFormalCommitPort(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource),
                transactionManager,
                objectMapper,
                clock);
    }

    @Bean
    AgentRunDomainResultCommitter productionIntakeDomainResultCommitter(
            ProductionAuthorizedIntakeFinalizationSource source,
            ProductionIntakeProposalReader proposalReader,
            JdbcIntakeFormalCommitPort commitPort) {
        var requestResolver = new ProductionIntakeFinalizationRequestResolver(
                source, proposalReader);
        var finalizer = new IntakeGraphResultFinalizer(
                new IntakeTurnProposalLoader(proposalReader),
                commitPort,
                commitPort,
                IntakeGraphResultFinalizer.PRODUCTION_RUNTIME_GRAPH_KEY);
        return new IntakeAgentRunDomainResultCommitter(
                requestResolver,
                finalizer,
                IntakeGraphResultFinalizer.PRODUCTION_RUNTIME_GRAPH_KEY);
    }

    @Bean
    ProductionActivationLedger productionAgentActivationLedger(DataSource dataSource) {
        return new ProductionActivationLedger(dataSource, Clock.systemUTC());
    }

    @Bean
    TargetIntakeCommandMaterialStore productionAgentIntakeCommandMaterialStore(
            DataSource dataSource,
            ProductionActivationLedger productionAgentActivationLedger,
            ObjectMapper objectMapper) {
        return new JdbcTargetIntakeCommandMaterialStore(
                dataSource, productionAgentActivationLedger, objectMapper);
    }

    @Bean
    ProductionActivationLifecycleStore productionAgentLifecycleStore(
            DataSource dataSource, Clock clock) {
        return new JdbcProductionActivationStores(dataSource, clock);
    }

    @Bean
    ProductionActivationLifecycleControl productionActivationLifecycleControl(
            ProductionActivationLifecycleStore lifecycleStore,
            ProductionAgentDeploymentBinding deploymentBinding,
            Environment environment,
            Clock clock) {
        DeploymentBinding binding = new DeploymentBinding(
                environment.acceptsProfiles(Profiles.of("production-runtime")),
                deploymentBinding.environmentId(),
                deploymentBinding.environmentGeneration(),
                deploymentBinding.activationId(),
                deploymentBinding.manifestHash(),
                required(environment, "production.runtime.runtime-context-hash"));
        return ProductionActivationLifecycleControl.bind(lifecycleStore, binding, clock);
    }

    @Bean
    ProductionFinalizationReceiptLedger productionFinalizationReceiptLedger(DataSource dataSource) {
        return new JdbcProductionFinalizationReceiptLedger(dataSource);
    }

    @Bean
    ProductionIntakeParallelAssemblyFinalizationPort
            productionIntakeParallelAssemblyFinalizationPort(
                    IntakeParallelAssemblyStore assemblyStore, DataSource dataSource) {
        return new JdbcProductionIntakeParallelAssemblyFinalizationPort(
                assemblyStore, new NamedParameterJdbcTemplate(dataSource));
    }

    @Bean
    ProductionRoomFinalizationStrategy productionIntakeRoomFinalizationStrategy(
            ProductionAuthorizedIntakeFinalizationSource source,
            ProductionAgentRunV2FinalizationFactsProvider factsProvider,
            ProductionIntakeParallelAssemblyFinalizationPort parallelFinalization) {
        return new ProductionIntakeRoomFinalizationStrategy(
                source, factsProvider, parallelFinalization);
    }

    @Bean
    ProductionRoomFinalizationStrategyRegistry productionRoomFinalizationStrategyRegistry(
            List<ProductionRoomFinalizationStrategy> strategies) {
        return new ProductionRoomFinalizationStrategyRegistry(strategies);
    }

    @Bean
    ProductionMultiRoomOuterFinalizer productionMultiRoomOuterFinalizer(
            PlatformTransactionManager transactionManager,
            ProductionGraphOutputSnapshotMaterializer outputMaterializer,
            ProductionRoomFinalizationStrategyRegistry strategies,
            AgentRunV2ManifestFactory manifestFactory,
            AgentRunFormalResultCommitter formalCommitter,
            ProductionFinalizationReceiptLedger receiptLedger,
            ProductionCommandCompletionWriter completionWriter) {
        var transactions = new org.springframework.transaction.support.TransactionTemplate(
                transactionManager);
        transactions.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED);
        transactions.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return new ProductionMultiRoomOuterFinalizer(
                transactions,
                outputMaterializer,
                strategies,
                manifestFactory,
                formalCommitter,
                receiptLedger,
                completionWriter);
    }

    @Bean
    ProductionIntakeDomainEventLiveRelay productionIntakeDomainEventLiveRelay(
            DataSource dataSource,
            ObjectMapper objectMapper,
            CaseProcessLedgerActivities ledgerActivities,
            WorkflowClient workflowClient) {
        return new ProductionIntakeDomainEventLiveRelay(
                new NamedParameterJdbcTemplate(dataSource),
                objectMapper,
                ledgerActivities,
                workflowClient);
    }

    @Bean
    AgentRunFinalizationGateway productionMultiRoomFinalizationGateway(
            ProductionMultiRoomOuterFinalizer outerFinalizer,
            ProductionIntakeDomainEventLiveRelay liveRelay) {
        return new ProductionMultiRoomFinalizationGateway(outerFinalizer, liveRelay);
    }

    /*
     * Keep the graph-output materializer adjacent to the single outer finalizer: it is invoked
     * inside that finalizer's transaction, never as a separately committed provenance write.
     */
    @Bean
    ProductionGraphOutputSnapshotMaterializer productionGraphOutputSnapshotMaterializer(
            DataSource dataSource,
            ProductionDurableFinalAuthorityResolver durableFinalAuthority,
            PlatformTransactionManager transactionManager) {
        return new ProductionGraphOutputSnapshotMaterializer(
                dataSource, durableFinalAuthority, transactionManager);
    }

    @Bean
    ProductionDurableFinalAuthorityResolver productionDurableFinalAuthorityResolver(
            DataSource dataSource,
            AgentRunV2StreamStore streamStore,
            IntakeParallelAssemblyStore assemblyStore,
            ObjectMapper objectMapper) {
        return new RoutingProductionDurableFinalAuthorityResolver(
                new V3ProductionDurableFinalAuthorityResolver(streamStore),
                new IntakeParallelV4DurableFinalAuthorityResolver(
                        new JdbcProductionV4FinalAuthoritySource(dataSource, objectMapper),
                        assemblyStore,
                        objectMapper));
    }

    /*
     * The completion writer is deliberately exposed through the shared contract so future room
     * strategies cannot replace the receipt/completion ordering.
     */
    @Bean
    ProductionCommandCompletionWriter productionCommandCompletionWriter(
            DataSource dataSource, ProductionActivationLedger productionAgentActivationLedger) {
        return new JdbcProductionIntakeCommandCompletionWriter(
                dataSource, productionAgentActivationLedger);
    }

    private static void requireTargetMode(GraphCommandClientProperties properties) {
        if (properties.mode() != GraphCommandClientProperties.Mode.PRODUCTION) {
            throw new IllegalStateException(
                    "production-runtime artifact requires PRODUCTION Graph mode");
        }
    }

    private static String required(Environment environment, String property) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "required production runtime property is absent: " + property);
        }
        return value.trim();
    }

    private static long requiredPositiveLong(Environment environment, String property) {
        String value = required(environment, property);
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1 || parsed > 9_007_199_254_740_991L) {
                throw new NumberFormatException("outside positive safe integer range");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalStateException(
                    "required production runtime property is not a positive safe integer: " + property,
                    failure);
        }
    }
}
