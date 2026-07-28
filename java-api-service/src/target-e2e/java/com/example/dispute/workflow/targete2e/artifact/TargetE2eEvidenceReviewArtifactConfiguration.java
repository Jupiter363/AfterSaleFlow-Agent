package com.example.dispute.workflow.targete2e.artifact;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationActivationPort;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eRoomFinalizationStrategy;
import com.example.dispute.workflow.targete2e.graph.HttpTargetE2EGraphReconciliationClient;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeSigner;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
import com.example.dispute.workflow.targete2e.rooms.evidence.JdbcTargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.evidence.JdbcTargetEvidenceFormalCommitPort;
import com.example.dispute.workflow.targete2e.rooms.evidence.ReconciledTargetEvidenceFinalizationEvidenceSource;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetE2eEvidenceRoomFinalizationStrategy;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceAgentRunDomainResultCommitter;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandBridgeActivity;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandBridgeActivities;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceFinalizationAdapter;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceFinalizationEvidenceSource;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceFinalizationRequestResolver;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceFormalCommitPort;
import com.example.dispute.workflow.targete2e.rooms.review.JdbcTargetReviewAdvisoryProjectionPort;
import com.example.dispute.workflow.targete2e.rooms.review.JdbcTargetReviewCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.review.JdbcTargetReviewFinalizationFactsProvider;
import com.example.dispute.workflow.targete2e.rooms.review.JdbcTargetReviewOutcomeHandoffStore;
import com.example.dispute.workflow.targete2e.rooms.review.TargetE2eReviewRoomFinalizationStrategy;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewAdvisoryFormalCommitPort;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewAdvisoryProjectionPort;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewAgentRunDomainResultCommitter;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewCommandBridgeActivity;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewCommandBridgeActivities;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewFinalizationAdapter;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewFinalizationFactsProvider;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewFinalizationRequestResolver;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewFormalCommitPort;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewOutcomeHandoffActivity;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewOutcomeHandoffStore;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceRoomRegistration;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewRoomRegistration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Target-only Evidence and Review finalization composition for the AGENT worker.
 *
 * <p>The registrations deliberately reuse the durable material stores and source verifiers. The
 * shared outer finalizer remains the only owner of target receipt append and command completion.
 */
@Configuration(proxyBeanMethods = false)
@Profile(TargetE2eArtifactPrerequisites.REQUIRED_PROFILE)
@ConditionalOnProperty(
        name = TargetE2eArtifactPrerequisites.WORKER_ROLE_PROPERTY,
        havingValue = TargetE2eArtifactPrerequisites.AGENT_WORKER_ROLE)
public class TargetE2eEvidenceReviewArtifactConfiguration {

    @Bean
    TargetEvidenceRoomRegistration targetE2eEvidenceRoomRegistration(
            DataSource dataSource,
            TargetE2EActivationLedger activationLedger,
            ObjectMapper objectMapper,
            TargetE2EGraphEnvelopeCodec codec,
            TargetE2EGraphEnvelopeSigner signer,
            HttpTargetE2EGraphReconciliationClient reconciliation,
            GraphRegistryBindingPolicy registryBindings,
            TargetE2eFinalizationActivationPort targetE2eFinalizationAuthority,
            TargetE2eFinalizationRuntimeContextProvider runtime) {
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
        TargetE2eRoomFinalizationStrategy strategy = new TargetE2eEvidenceRoomFinalizationStrategy(
                materialStore, evidenceSource, targetE2eFinalizationAuthority, runtime);
        var resolver = new TargetEvidenceFinalizationRequestResolver(materialStore, objectMapper);
        TargetEvidenceFormalCommitPort formalCommitPort =
                new JdbcTargetEvidenceFormalCommitPort(objectMapper);
        AgentRunDomainResultCommitter committer = new TargetEvidenceAgentRunDomainResultCommitter(
                dataSource,
                resolver,
                new TargetEvidenceFinalizationAdapter(formalCommitPort));
        TargetEvidenceCommandBridgeActivities bridge =
                new TargetEvidenceCommandBridgeActivity(materialStore);
        return new TargetEvidenceRoomRegistration(materialStore, bridge, committer, strategy);
    }

    @Bean
    TargetE2eRoomFinalizationStrategy targetE2eEvidenceRoomFinalizationStrategy(
            TargetEvidenceRoomRegistration registration) {
        return registration.finalizationStrategy();
    }

    @Bean
    AgentRunDomainResultCommitter targetE2eEvidenceDomainResultCommitter(
            TargetEvidenceRoomRegistration registration) {
        return registration.domainCommitter();
    }

    @Bean
    TargetReviewRoomRegistration targetE2eReviewRoomRegistration(
            DataSource dataSource,
            TargetE2EActivationLedger activationLedger,
            ObjectMapper objectMapper,
            TargetE2eFinalizationRuntimeContextProvider runtime) {
        TargetReviewCommandMaterialStore materialStore =
                new JdbcTargetReviewCommandMaterialStore(dataSource, activationLedger, objectMapper);
        TargetReviewOutcomeHandoffStore handoffStore =
                new JdbcTargetReviewOutcomeHandoffStore(dataSource, objectMapper);
        var resolver = new TargetReviewFinalizationRequestResolver(materialStore, handoffStore);
        TargetReviewFinalizationFactsProvider factsProvider =
                new JdbcTargetReviewFinalizationFactsProvider(dataSource, runtime);
        TargetReviewAdvisoryProjectionPort projectionPort =
                new JdbcTargetReviewAdvisoryProjectionPort(objectMapper, Clock.systemUTC());
        TargetReviewFormalCommitPort formalCommitPort =
                new TargetReviewAdvisoryFormalCommitPort(handoffStore, projectionPort);
        TargetE2eRoomFinalizationStrategy strategy = new TargetE2eReviewRoomFinalizationStrategy(
                resolver, factsProvider);
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
    TargetE2eRoomFinalizationStrategy targetE2eReviewRoomFinalizationStrategy(
            TargetReviewRoomRegistration registration) {
        return registration.finalizationStrategy();
    }

    @Bean
    AgentRunDomainResultCommitter targetE2eReviewDomainResultCommitter(
            TargetReviewRoomRegistration registration) {
        return registration.domainCommitter();
    }
}
