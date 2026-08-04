package com.example.dispute.workflow.targete2e.artifact;

import com.example.dispute.agentstream.application.AgentRunReconciledFinalStore;
import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.agentstream.application.AgentRunFormalResultCommitter;
import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
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
import com.example.dispute.workflow.config.GraphCommandClientProperties;
import com.example.dispute.workflow.config.TemporalWorkerProperties;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.infrastructure.security.MountedPemGraphEnvelopeKeySet;
import com.example.dispute.workflow.application.intake.IntakeAgentRunDomainResultCommitter;
import com.example.dispute.workflow.application.intake.IntakeGraphResultFinalizer;
import com.example.dispute.workflow.application.intake.IntakeTurnProposalLoader;
import com.example.dispute.workflow.targete2e.artifact.finalization.JdbcTargetE2eFinalizationAuthority;
import com.example.dispute.workflow.targete2e.artifact.finalization.JdbcTargetE2eIntakeCommandCompletionWriter;
import com.example.dispute.workflow.targete2e.artifact.finalization.ReconciledTargetE2eFinalizationEvidenceProvider;
import com.example.dispute.workflow.targete2e.artifact.finalization.TargetE2eIntakeDomainEventLiveRelay;
import com.example.dispute.workflow.targete2e.artifact.finalization.TargetE2eMultiRoomFinalizationGateway;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eGraphOutputSnapshotMaterializer;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eMultiRoomOuterFinalizer;
import com.example.dispute.workflow.targete2e.finalization.JdbcTargetE2eIntakeFinalizationStateReader;
import com.example.dispute.workflow.targete2e.finalization.MinioTargetE2eIntakeProposalStore;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eAgentRunV2FinalizationFactsProvider;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eAuthorizedIntakeFinalizationSource;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eExecutionLaneVerifier;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationBindingVerifier;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationEvidenceProvider;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider;
import com.example.dispute.workflow.targete2e.finalization.JdbcTargetE2eFinalizationReceiptLedger;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceiptLedger;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eCommandCompletionWriter;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eIntakeRoomFinalizationStrategy;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eRoomFinalizationStrategy;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eRoomFinalizationStrategyRegistry;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eIntakeFinalizationRequestResolver;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eIntakeFinalizationStateReader;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eIntakeProposalReader;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eIntakeProposalStore;
import com.example.dispute.workflow.targete2e.finalization.TemporalTargetE2eFinalizationRuntimeContextProvider;
import com.example.dispute.workflow.targete2e.graph.Es256TargetE2EGraphEnvelopeSigner;
import com.example.dispute.workflow.targete2e.graph.JdbcTargetE2EAgentSessionResolver;
import com.example.dispute.workflow.targete2e.graph.HttpTargetE2EGraphProposalClient;
import com.example.dispute.workflow.targete2e.graph.HttpTargetE2EGraphProposalSourceClient;
import com.example.dispute.workflow.targete2e.graph.HttpTargetE2EGraphReconciliationClient;
import com.example.dispute.workflow.targete2e.graph.JpaTargetE2EAgentRunIdentityResolver;
import com.example.dispute.workflow.targete2e.graph.TargetE2EAgentGraphCommandClient;
import com.example.dispute.workflow.targete2e.graph.TargetE2EAgentGraphReconciliationClient;
import com.example.dispute.workflow.targete2e.graph.TargetE2EAgentRunIdentityResolver;
import com.example.dispute.workflow.targete2e.graph.TargetE2EAgentSessionResolver;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeSigner;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphProposalClient;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore;
import com.example.dispute.workflow.targete2e.TargetE2eAgentDeploymentBinding;
import com.example.dispute.workflow.targete2e.lifecycle.TargetE2eActivationLifecycleControl;
import com.example.dispute.workflow.targete2e.lifecycle.TargetE2eActivationLifecycleControl.DeploymentBinding;
import com.example.dispute.workflow.targete2e.persistence.JdbcTargetE2eActivationStores;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
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

/** Target-only AgentRun and proposal Graph assembly, absent from the ordinary Java artifact. */
@Configuration(proxyBeanMethods = false)
@Profile(TargetE2eArtifactPrerequisites.REQUIRED_PROFILE)
@ConditionalOnProperty(
        name = TargetE2eArtifactPrerequisites.WORKER_ROLE_PROPERTY,
        havingValue = TargetE2eArtifactPrerequisites.AGENT_WORKER_ROLE)
public class TargetE2eArtifactConfiguration {

    @Bean
    TargetE2eArtifactMarker targetE2eArtifactMarker() {
        return new TargetE2eArtifactMarker(TargetE2eArtifactMarker.EXPECTED_VALUE);
    }

    @Bean
    @DependsOn("flyway")
    TargetE2eAgentDeploymentBinding targetE2eAgentDeploymentBinding(
            DataSource dataSource,
            GraphCommandClientProperties graphProperties,
            TemporalWorkerProperties workerProperties,
            Environment environment) {
        requireTargetMode(graphProperties);
        String activationId = required(environment, "target.e2e.activation.id");
        TargetE2eAgentDeploymentBinding configured =
                new TargetE2eAgentDeploymentBinding(
                        required(environment, "target.e2e.environment.id"),
                        requiredPositiveLong(
                                environment, "target.e2e.environment.generation"),
                        activationId,
                        required(environment, "target.e2e.activation.manifest-hash"),
                        workerProperties.buildId());
        configured.requireWorkerConfiguration(
                graphProperties.activationId(), workerProperties.buildId());
        List<TargetE2eAgentDeploymentBinding> registered =
                new JdbcTemplate(dataSource)
                        .query(
                                """
                                select environment_id, environment_generation,
                                       activation_id, manifest_hash, agent_build_id
                                  from target_e2e_activation
                                 where activation_id = ?
                                """,
                                (result, ignored) ->
                                        new TargetE2eAgentDeploymentBinding(
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
        return TargetE2eAgentDeploymentBinding.requireExact(
                configured, registered.getFirst());
    }

    @Bean
    TargetE2EGraphEnvelopeCodec targetE2EGraphEnvelopeCodec(ObjectMapper objectMapper) {
        return new TargetE2EGraphEnvelopeCodec(objectMapper);
    }

    @Bean
    TargetE2EAgentSessionResolver targetE2EAgentSessionResolver(
            DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcTargetE2EAgentSessionResolver(dataSource, objectMapper);
    }

    @Bean
    TargetE2EGraphEnvelopeSigner targetE2EGraphEnvelopeSigner(
            MountedPemGraphEnvelopeKeySet signingKeys,
            ObjectMapper objectMapper,
            TargetE2EAgentSessionResolver agentSessions) {
        return new Es256TargetE2EGraphEnvelopeSigner(
                signingKeys,
                objectMapper,
                Clock.systemUTC(),
                java.time.Duration.ofSeconds(60),
                () -> "target-command-" + java.util.UUID.randomUUID(),
                agentSessions);
    }

    @Bean
    TargetE2EAgentRunIdentityResolver targetE2EAgentRunIdentityResolver(
            AgentRunRepository runRepository,
            AgentRunAttemptRepository attemptRepository) {
        return new JpaTargetE2EAgentRunIdentityResolver(runRepository, attemptRepository);
    }

    @Bean
    HttpTargetE2EGraphProposalSourceClient targetE2EGraphProposalSourceClient(
            GraphTransportBundle transports,
            TargetE2EGraphEnvelopeCodec codec,
            GraphCommandClientProperties properties) {
        requireTargetMode(properties);
        return new HttpTargetE2EGraphProposalSourceClient(
                transports, codec, properties.baseUri(), properties.requestTimeout());
    }

    @Bean
    HttpTargetE2EGraphReconciliationClient targetE2EGraphReconciliationHttpClient(
            GraphTransportBundle transports,
            TargetE2EGraphEnvelopeCodec codec,
            HttpTargetE2EGraphProposalSourceClient proposalSource,
            ObjectMapper objectMapper,
            GraphCommandClientProperties properties) {
        requireTargetMode(properties);
        return new HttpTargetE2EGraphReconciliationClient(
                transports,
                codec,
                proposalSource,
                objectMapper,
                properties.baseUri(),
                properties.requestTimeout());
    }

    @Bean
    TargetE2EGraphProposalClient targetE2EGraphProposalClient(
            GraphTransportBundle transports,
            HttpTargetE2EGraphReconciliationClient reconciliationClient,
            ObjectMapper objectMapper,
            GraphCommandClientProperties properties) {
        requireTargetMode(properties);
        return new HttpTargetE2EGraphProposalClient(
                transports,
                reconciliationClient,
                objectMapper,
                properties.baseUri(),
                properties.requestTimeout());
    }

    @Bean
    AgentGraphCommandClient targetE2EAgentGraphCommandClient(
            TargetE2EAgentRunIdentityResolver identityResolver,
            TargetE2EGraphEnvelopeCodec codec,
            TargetE2EGraphEnvelopeSigner signer,
            TargetE2EGraphProposalClient proposalClient,
            GraphStreamVisibilityPolicy visibilityPolicy,
            GraphRegistryBindingPolicy registryBindingPolicy,
            TargetE2eAgentDeploymentBinding deploymentBinding,
            GraphCommandClientProperties properties) {
        requireTargetMode(properties);
        return new TargetE2EAgentGraphCommandClient(
                deploymentBinding.activationId(),
                identityResolver,
                codec,
                signer,
                proposalClient,
                visibilityPolicy,
                registryBindingPolicy);
    }

    @Bean
    AgentGraphReconciliationClient targetE2EAgentGraphReconciliationClient(
            TargetE2EAgentRunIdentityResolver identityResolver,
            TargetE2EGraphEnvelopeCodec codec,
            TargetE2EGraphEnvelopeSigner signer,
            HttpTargetE2EGraphReconciliationClient reconciliationClient,
            GraphRegistryBindingPolicy registryBindingPolicy,
            TargetE2eAgentDeploymentBinding deploymentBinding,
            GraphCommandClientProperties properties) {
        requireTargetMode(properties);
        return new TargetE2EAgentGraphReconciliationClient(
                deploymentBinding.activationId(),
                identityResolver,
                codec,
                signer,
                reconciliationClient,
                registryBindingPolicy);
    }

    @Bean
    AgentRunExecutionGateway targetE2EAgentRunExecutionGateway(
            AgentGraphCommandClient commandClient,
            AgentGraphReconciliationClient reconciliationClient,
            AgentRunV2StreamStore streamStore,
            AgentRunReconciledFinalStore reconciledFinalStore) {
        return new DurableAgentRunExecutionGateway(
                commandClient, reconciliationClient, streamStore, reconciledFinalStore);
    }

    @Bean
    JdbcTargetE2eFinalizationAuthority targetE2eFinalizationAuthority(
            DataSource dataSource,
            TargetE2eAgentDeploymentBinding deploymentBinding,
            Clock clock) {
        return new JdbcTargetE2eFinalizationAuthority(
                dataSource, deploymentBinding.activationId(), clock);
    }

    @Bean
    TargetE2eIntakeFinalizationStateReader targetE2eIntakeFinalizationStateReader(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        return new JdbcTargetE2eIntakeFinalizationStateReader(
                dataSource, transactionManager, objectMapper);
    }

    @Bean
    TargetE2eFinalizationRuntimeContextProvider targetE2eFinalizationRuntimeContextProvider(
            TargetE2eAgentDeploymentBinding deploymentBinding) {
        return new TemporalTargetE2eFinalizationRuntimeContextProvider(
                deploymentBinding.agentBuildId());
    }

    @Bean
    TargetE2eFinalizationEvidenceProvider targetE2eFinalizationEvidenceProvider(
            JdbcTargetE2eFinalizationAuthority authority,
            TargetE2EGraphEnvelopeCodec codec,
            TargetE2EGraphEnvelopeSigner signer,
            HttpTargetE2EGraphReconciliationClient reconciliation,
            HttpTargetE2EGraphProposalSourceClient proposalSource,
            GraphRegistryBindingPolicy registryBindings,
            ObjectMapper objectMapper) {
        return new ReconciledTargetE2eFinalizationEvidenceProvider(
                authority,
                codec,
                signer,
                reconciliation,
                proposalSource,
                registryBindings,
                objectMapper);
    }

    @Bean
    TargetE2eAuthorizedIntakeFinalizationSource targetE2eAuthorizedFinalizationSource(
            TargetE2eIntakeFinalizationStateReader stateReader,
            JdbcTargetE2eFinalizationAuthority authority,
            TargetE2eFinalizationRuntimeContextProvider runtimeContext,
            TargetE2eFinalizationEvidenceProvider evidenceProvider,
            ObjectMapper objectMapper,
            Clock clock) {
        return new TargetE2eAuthorizedIntakeFinalizationSource(
                stateReader,
                authority,
                runtimeContext,
                new TargetE2eExecutionLaneVerifier(clock),
                evidenceProvider,
                new TargetE2eFinalizationBindingVerifier(objectMapper));
    }

    @Bean
    TargetE2eAgentRunV2FinalizationFactsProvider targetE2eFinalizationFactsProvider(
            TargetE2eAuthorizedIntakeFinalizationSource source) {
        return new TargetE2eAgentRunV2FinalizationFactsProvider(source);
    }

    @Bean
    TargetE2eIntakeProposalStore targetE2eIntakeProposalStore(
            MinioClient minioClient, Environment environment) {
        return new MinioTargetE2eIntakeProposalStore(
                minioClient,
                environment.getProperty(
                        "app.target-e2e.finalization.intake-proposal-bucket",
                        "target-e2e-intake-activation"),
                environment.getProperty(
                        "app.target-e2e.finalization.intake-proposal-prefix",
                        "graph-proposals"));
    }

    @Bean
    TargetE2eIntakeProposalReader targetE2eIntakeProposalReader(
            TargetE2eIntakeProposalStore store) {
        return new TargetE2eIntakeProposalReader(store);
    }

    @Bean
    JdbcIntakeFormalCommitPort targetE2eIntakeFormalCommitPort(
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
    AgentRunDomainResultCommitter targetE2eIntakeDomainResultCommitter(
            TargetE2eAuthorizedIntakeFinalizationSource source,
            TargetE2eIntakeProposalReader proposalReader,
            JdbcIntakeFormalCommitPort commitPort) {
        var requestResolver = new TargetE2eIntakeFinalizationRequestResolver(
                source, proposalReader);
        var finalizer = new IntakeGraphResultFinalizer(
                new IntakeTurnProposalLoader(proposalReader),
                commitPort,
                commitPort,
                IntakeGraphResultFinalizer.TARGET_E2E_GRAPH_KEY);
        return new IntakeAgentRunDomainResultCommitter(
                requestResolver,
                finalizer,
                IntakeGraphResultFinalizer.TARGET_E2E_GRAPH_KEY);
    }

    @Bean
    TargetE2EActivationLedger targetE2eAgentActivationLedger(DataSource dataSource) {
        return new TargetE2EActivationLedger(dataSource, Clock.systemUTC());
    }

    @Bean
    TargetE2eActivationLifecycleStore targetE2eAgentLifecycleStore(
            DataSource dataSource, Clock clock) {
        return new JdbcTargetE2eActivationStores(dataSource, clock);
    }

    @Bean
    TargetE2eActivationLifecycleControl targetE2eActivationLifecycleControl(
            TargetE2eActivationLifecycleStore lifecycleStore,
            TargetE2eAgentDeploymentBinding deploymentBinding,
            Environment environment,
            Clock clock) {
        DeploymentBinding binding = new DeploymentBinding(
                environment.acceptsProfiles(Profiles.of("target-e2e")),
                deploymentBinding.environmentId(),
                deploymentBinding.environmentGeneration(),
                deploymentBinding.activationId(),
                deploymentBinding.manifestHash(),
                required(environment, "target.e2e.runtime-context-hash"));
        return TargetE2eActivationLifecycleControl.bind(lifecycleStore, binding, clock);
    }

    @Bean
    TargetE2eFinalizationReceiptLedger targetE2eFinalizationReceiptLedger(DataSource dataSource) {
        return new JdbcTargetE2eFinalizationReceiptLedger(dataSource);
    }

    @Bean
    TargetE2eRoomFinalizationStrategy targetE2eIntakeRoomFinalizationStrategy(
            TargetE2eAuthorizedIntakeFinalizationSource source,
            TargetE2eAgentRunV2FinalizationFactsProvider factsProvider) {
        return new TargetE2eIntakeRoomFinalizationStrategy(source, factsProvider);
    }

    @Bean
    TargetE2eRoomFinalizationStrategyRegistry targetE2eRoomFinalizationStrategyRegistry(
            List<TargetE2eRoomFinalizationStrategy> strategies) {
        return new TargetE2eRoomFinalizationStrategyRegistry(strategies);
    }

    @Bean
    TargetE2eMultiRoomOuterFinalizer targetE2eMultiRoomOuterFinalizer(
            PlatformTransactionManager transactionManager,
            TargetE2eGraphOutputSnapshotMaterializer outputMaterializer,
            TargetE2eRoomFinalizationStrategyRegistry strategies,
            AgentRunV2ManifestFactory manifestFactory,
            AgentRunFormalResultCommitter formalCommitter,
            TargetE2eFinalizationReceiptLedger receiptLedger,
            TargetE2eCommandCompletionWriter completionWriter) {
        var transactions = new org.springframework.transaction.support.TransactionTemplate(
                transactionManager);
        transactions.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED);
        transactions.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return new TargetE2eMultiRoomOuterFinalizer(
                transactions,
                outputMaterializer,
                strategies,
                manifestFactory,
                formalCommitter,
                receiptLedger,
                completionWriter);
    }

    @Bean
    TargetE2eIntakeDomainEventLiveRelay targetE2eIntakeDomainEventLiveRelay(
            DataSource dataSource,
            ObjectMapper objectMapper,
            CaseProcessLedgerActivities ledgerActivities,
            WorkflowClient workflowClient) {
        return new TargetE2eIntakeDomainEventLiveRelay(
                new NamedParameterJdbcTemplate(dataSource),
                objectMapper,
                ledgerActivities,
                workflowClient);
    }

    @Bean
    AgentRunFinalizationGateway targetE2eMultiRoomFinalizationGateway(
            TargetE2eMultiRoomOuterFinalizer outerFinalizer,
            TargetE2eIntakeDomainEventLiveRelay liveRelay) {
        return new TargetE2eMultiRoomFinalizationGateway(outerFinalizer, liveRelay);
    }

    /*
     * Keep the graph-output materializer adjacent to the single outer finalizer: it is invoked
     * inside that finalizer's transaction, never as a separately committed provenance write.
     */
    @Bean
    TargetE2eGraphOutputSnapshotMaterializer targetE2eGraphOutputSnapshotMaterializer(
            DataSource dataSource,
            AgentRunV2StreamStore streamStore,
            PlatformTransactionManager transactionManager) {
        return new TargetE2eGraphOutputSnapshotMaterializer(
                dataSource, streamStore, transactionManager);
    }

    /*
     * The completion writer is deliberately exposed through the shared contract so future room
     * strategies cannot replace the receipt/completion ordering.
     */
    @Bean
    TargetE2eCommandCompletionWriter targetE2eCommandCompletionWriter(
            DataSource dataSource, TargetE2EActivationLedger targetE2eAgentActivationLedger) {
        return new JdbcTargetE2eIntakeCommandCompletionWriter(
                dataSource, targetE2eAgentActivationLedger);
    }

    private static void requireTargetMode(GraphCommandClientProperties properties) {
        if (properties.mode() != GraphCommandClientProperties.Mode.TARGET_E2E_CANDIDATE) {
            throw new IllegalStateException(
                    "target-E2E artifact requires TARGET_E2E_CANDIDATE Graph mode");
        }
    }

    private static String required(Environment environment, String property) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "required target E2E property is absent: " + property);
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
                    "required target E2E property is not a positive safe integer: " + property,
                    failure);
        }
    }
}
