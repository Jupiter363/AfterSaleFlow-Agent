package com.example.dispute.workflow.targete2e.artifact;

import com.example.dispute.agentstream.application.AgentRunReconciledFinalStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.activity.agent.AgentGraphCommandClient;
import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.DurableAgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.config.GraphCommandClientProperties;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.infrastructure.security.MountedPemGraphEnvelopeKeySet;
import com.example.dispute.workflow.targete2e.graph.Es256TargetE2EGraphEnvelopeSigner;
import com.example.dispute.workflow.targete2e.graph.HttpTargetE2EGraphProposalClient;
import com.example.dispute.workflow.targete2e.graph.HttpTargetE2EGraphProposalSourceClient;
import com.example.dispute.workflow.targete2e.graph.HttpTargetE2EGraphReconciliationClient;
import com.example.dispute.workflow.targete2e.graph.JpaTargetE2EAgentRunIdentityResolver;
import com.example.dispute.workflow.targete2e.graph.TargetE2EAgentGraphCommandClient;
import com.example.dispute.workflow.targete2e.graph.TargetE2EAgentGraphReconciliationClient;
import com.example.dispute.workflow.targete2e.graph.TargetE2EAgentRunIdentityResolver;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeSigner;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphProposalClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Target-only AgentRun and proposal Graph assembly, absent from the ordinary Java artifact. */
@Configuration(proxyBeanMethods = false)
@Profile(TargetE2eArtifactPrerequisites.REQUIRED_PROFILE)
public class TargetE2eArtifactConfiguration {

    @Bean
    TargetE2eArtifactMarker targetE2eArtifactMarker() {
        return new TargetE2eArtifactMarker(TargetE2eArtifactMarker.EXPECTED_VALUE);
    }

    @Bean
    TargetE2EGraphEnvelopeCodec targetE2EGraphEnvelopeCodec(ObjectMapper objectMapper) {
        return new TargetE2EGraphEnvelopeCodec(objectMapper);
    }

    @Bean
    TargetE2EGraphEnvelopeSigner targetE2EGraphEnvelopeSigner(
            MountedPemGraphEnvelopeKeySet signingKeys, ObjectMapper objectMapper) {
        return new Es256TargetE2EGraphEnvelopeSigner(
                signingKeys, objectMapper, Clock.systemUTC());
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
            GraphCommandClientProperties properties) {
        requireTargetMode(properties);
        return new TargetE2EAgentGraphCommandClient(
                properties.activationId(),
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
            GraphCommandClientProperties properties) {
        requireTargetMode(properties);
        return new TargetE2EAgentGraphReconciliationClient(
                properties.activationId(),
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

    private static void requireTargetMode(GraphCommandClientProperties properties) {
        if (properties.mode() != GraphCommandClientProperties.Mode.TARGET_E2E_CANDIDATE) {
            throw new IllegalStateException(
                    "target-E2E artifact requires TARGET_E2E_CANDIDATE Graph mode");
        }
    }
}
