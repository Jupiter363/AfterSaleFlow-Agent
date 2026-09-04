package com.example.dispute.workflow.runtime.artifact;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.room.application.EvidenceAgentTurnService;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationRuntimeContextProvider;
import com.example.dispute.workflow.runtime.finalization.ProductionRoomFinalizationStrategy;
import com.example.dispute.workflow.runtime.graph.HttpProductionGraphReconciliationClient;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeCodec;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeSigner;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger;
import com.example.dispute.workflow.runtime.rooms.evidence.JdbcTargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.evidence.JdbcTargetEvidenceFormalCommitPort;
import com.example.dispute.workflow.runtime.rooms.evidence.ReconciledTargetEvidenceFinalizationEvidenceSource;
import com.example.dispute.workflow.runtime.rooms.evidence.ProductionEvidenceRoomFinalizationStrategy;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceAgentRunDomainResultCommitter;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCommandBridgeActivity;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCommandBridgeActivities;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceFinalizationAdapter;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceFinalizationEvidenceSource;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceFinalizationRequestResolver;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceFormalCommitPort;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceTurnProposalLoader;
import com.example.dispute.workflow.runtime.rooms.review.JdbcTargetReviewAdvisoryProjectionPort;
import com.example.dispute.workflow.runtime.rooms.review.JdbcTargetReviewCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.review.JdbcTargetReviewFinalizationFactsProvider;
import com.example.dispute.workflow.runtime.rooms.review.JdbcTargetReviewOutcomeHandoffStore;
import com.example.dispute.workflow.runtime.rooms.review.ReconciledTargetReviewFinalizationEvidenceSource;
import com.example.dispute.workflow.runtime.rooms.review.ProductionReviewRoomFinalizationStrategy;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewAdvisoryFormalCommitPort;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewAdvisoryProjectionPort;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewAgentRunDomainResultCommitter;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewCommandBridgeActivity;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewCommandBridgeActivities;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewFinalizationAdapter;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewFinalizationFactsProvider;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewFinalizationRequestResolver;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewReconciledFinalizationEvidenceSource;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewFormalCommitPort;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewOutcomeHandoffActivity;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewOutcomeHandoffStore;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceRoomRegistration;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewRoomRegistration;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Production-only Evidence and Review finalization composition for the AGENT worker.
 *
 * <p>The registrations deliberately reuse the durable material stores and source verifiers. The
 * shared outer finalizer remains the only owner of target receipt append and command completion.
 */
@Configuration(proxyBeanMethods = false)
@Profile(ProductionArtifactPrerequisites.REQUIRED_PROFILE)
@ConditionalOnProperty(
        name = ProductionArtifactPrerequisites.WORKER_ROLE_PROPERTY,
        havingValue = ProductionArtifactPrerequisites.AGENT_WORKER_ROLE)
public class ProductionEvidenceReviewArtifactConfiguration {

    @Bean
    TargetEvidenceRoomRegistration productionEvidenceRoomRegistration(
            DataSource dataSource,
            MinioClient minioClient,
            EntityManager entityManager,
            EvidenceAgentTurnService evidenceAgentTurnService,
            ProductionActivationLedger activationLedger,
            ObjectMapper objectMapper,
            ProductionGraphEnvelopeCodec codec,
            ProductionGraphEnvelopeSigner signer,
            HttpProductionGraphReconciliationClient reconciliation,
            GraphRegistryBindingPolicy registryBindings,
            ProductionFinalizationActivationPort productionFinalizationAuthority,
            ProductionFinalizationRuntimeContextProvider runtime) {
        TargetEvidenceCommandMaterialStore materialStore =
                new JdbcTargetEvidenceCommandMaterialStore(dataSource, activationLedger, objectMapper);
        TargetEvidenceFinalizationEvidenceSource evidenceSource =
                new ReconciledTargetEvidenceFinalizationEvidenceSource(
                        dataSource,
                        activationLedger,
                        codec,
                        signer,
                        reconciliation,
                        registryBindings,
                        runtime);
        ProductionRoomFinalizationStrategy strategy = new ProductionEvidenceRoomFinalizationStrategy(
                materialStore, evidenceSource, productionFinalizationAuthority, runtime);
        var proposalLoader =
                new TargetEvidenceTurnProposalLoader(dataSource, minioClient, objectMapper);
        var resolver =
                new TargetEvidenceFinalizationRequestResolver(
                        materialStore, proposalLoader, objectMapper);
        TargetEvidenceFormalCommitPort formalCommitPort =
                new JdbcTargetEvidenceFormalCommitPort(objectMapper);
        AgentRunDomainResultCommitter committer = new TargetEvidenceAgentRunDomainResultCommitter(
                dataSource,
                entityManager,
                evidenceAgentTurnService,
                resolver,
                new TargetEvidenceFinalizationAdapter(formalCommitPort),
                objectMapper);
        TargetEvidenceCommandBridgeActivities bridge =
                new TargetEvidenceCommandBridgeActivity(materialStore);
        return new TargetEvidenceRoomRegistration(materialStore, bridge, committer, strategy);
    }

    @Bean
    ProductionRoomFinalizationStrategy productionEvidenceRoomFinalizationStrategy(
            TargetEvidenceRoomRegistration registration) {
        return registration.finalizationStrategy();
    }

    @Bean
    AgentRunDomainResultCommitter productionEvidenceDomainResultCommitter(
            TargetEvidenceRoomRegistration registration) {
        return registration.domainCommitter();
    }

    @Bean
    TargetReviewRoomRegistration productionReviewRoomRegistration(
            DataSource dataSource,
            ProductionActivationLedger activationLedger,
            ObjectMapper objectMapper,
            ProductionGraphEnvelopeCodec codec,
            ProductionGraphEnvelopeSigner signer,
            HttpProductionGraphReconciliationClient reconciliation,
            GraphRegistryBindingPolicy registryBindings,
            ProductionFinalizationActivationPort productionFinalizationAuthority,
            ProductionFinalizationRuntimeContextProvider runtime,
            AgentRunAttemptRepository attempts) {
        TargetReviewCommandMaterialStore materialStore =
                new JdbcTargetReviewCommandMaterialStore(dataSource, activationLedger, objectMapper);
        TargetReviewOutcomeHandoffStore handoffStore =
                new JdbcTargetReviewOutcomeHandoffStore(dataSource, objectMapper);
        TargetReviewReconciledFinalizationEvidenceSource evidenceSource =
                new ReconciledTargetReviewFinalizationEvidenceSource(
                        dataSource,
                        activationLedger,
                        codec,
                        signer,
                        reconciliation,
                        registryBindings);
        var resolver = new TargetReviewFinalizationRequestResolver(
                materialStore, handoffStore, evidenceSource, attempts, objectMapper);
        TargetReviewFinalizationFactsProvider factsProvider =
                new JdbcTargetReviewFinalizationFactsProvider(dataSource, runtime);
        TargetReviewAdvisoryProjectionPort projectionPort =
                new JdbcTargetReviewAdvisoryProjectionPort(objectMapper, Clock.systemUTC());
        TargetReviewFormalCommitPort formalCommitPort =
                new TargetReviewAdvisoryFormalCommitPort(handoffStore, projectionPort);
        ProductionRoomFinalizationStrategy strategy = new ProductionReviewRoomFinalizationStrategy(
                resolver, factsProvider, productionFinalizationAuthority);
        AgentRunDomainResultCommitter committer = new TargetReviewAgentRunDomainResultCommitter(
                dataSource,
                resolver,
                new TargetReviewFinalizationAdapter(formalCommitPort));
        TargetReviewCommandBridgeActivities bridge =
                new TargetReviewCommandBridgeActivity(materialStore);
        return new TargetReviewRoomRegistration(
                materialStore,
                bridge,
                committer,
                strategy,
                new TargetReviewOutcomeHandoffActivity(handoffStore));
    }

    @Bean
    ProductionRoomFinalizationStrategy productionReviewRoomFinalizationStrategy(
            TargetReviewRoomRegistration registration) {
        return registration.finalizationStrategy();
    }

    @Bean
    AgentRunDomainResultCommitter productionReviewDomainResultCommitter(
            TargetReviewRoomRegistration registration) {
        return registration.domainCommitter();
    }
}
