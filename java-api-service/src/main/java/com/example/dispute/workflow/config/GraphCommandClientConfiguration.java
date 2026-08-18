package com.example.dispute.workflow.config;

import com.example.dispute.agentstream.application.AgentRunReconciledFinalStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.agentstream.application.AgentRunTransientStreamPublisher;
import com.example.dispute.workflow.activity.agent.AgentGraphCommandClient;
import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.DurableAgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.GraphCommandEnvelopeSigner;
import com.example.dispute.workflow.activity.agent.GraphReconciliationEnvelopeSigner;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.contract.v1.AgentPlatformContractCodec;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportSecurityProof;
import com.example.dispute.workflow.infrastructure.agent.HttpAgentGraphCommandClient;
import com.example.dispute.workflow.infrastructure.agent.HttpAgentGraphReconciliationClient;
import com.example.dispute.workflow.infrastructure.security.Es256GraphCommandEnvelopeSigner;
import com.example.dispute.workflow.infrastructure.security.Es256GraphReconciliationEnvelopeSigner;
import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKey;
import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Assembles the signed Graph client only for the Phase 3 synthetic shadow runtime. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    GraphCommandClientProperties.class,
    AgentRunV2Properties.class
})
@ConditionalOnProperty(
        name = "app.agent-run-v2.graph-client.mode",
        havingValue = "SHADOW")
public class GraphCommandClientConfiguration {

    @Bean
    AgentPlatformContractCodec agentPlatformContractCodec() {
        return new AgentPlatformContractCodec();
    }

    @Bean
    GraphCommandEnvelopeSigner graphCommandEnvelopeSigner(
            GraphEnvelopeSigningKeyResolver retainedSigningKeys,
            ObjectMapper objectMapper) {
        return new Es256GraphCommandEnvelopeSigner(
                retainedSigningKeys, objectMapper, Clock.systemUTC());
    }

    @Bean
    GraphReconciliationEnvelopeSigner graphReconciliationEnvelopeSigner(
            GraphEnvelopeSigningKey activeSigningKey,
            ObjectMapper objectMapper) {
        return new Es256GraphReconciliationEnvelopeSigner(
                activeSigningKey, objectMapper, Clock.systemUTC());
    }

    @Bean
    AgentGraphReconciliationClient agentGraphReconciliationClient(
            GraphTransportBundle transports,
            GraphReconciliationEnvelopeSigner envelopeSigner,
            GraphRegistryBindingPolicy registryBindingPolicy,
            AgentPlatformContractCodec codec,
            ObjectMapper objectMapper,
            GraphCommandClientProperties properties,
            Environment environment) {
        requireTransportSecurity(transports, properties, environment);
        return new HttpAgentGraphReconciliationClient(
                transports.reconciliationTransport(),
                envelopeSigner,
                registryBindingPolicy,
                codec,
                objectMapper,
                properties.baseUri(),
                properties.requestTimeout(),
                properties.allowPlaintextTransport());
    }

    @Bean
    AgentGraphCommandClient agentGraphCommandClient(
            GraphTransportBundle transports,
            GraphCommandEnvelopeSigner envelopeSigner,
            AgentGraphReconciliationClient reconciliationClient,
            GraphStreamVisibilityPolicy visibilityPolicy,
            GraphRegistryBindingPolicy registryBindingPolicy,
            AgentPlatformContractCodec codec,
            ObjectMapper objectMapper,
            GraphCommandClientProperties properties,
            AgentRunV2Properties agentRunV2Properties,
            Environment environment) {
        requireSyntheticShadow(agentRunV2Properties);
        requireTransportSecurity(transports, properties, environment);
        return new HttpAgentGraphCommandClient(
                transports.commandTransport(),
                envelopeSigner,
                reconciliationClient,
                visibilityPolicy,
                registryBindingPolicy,
                codec,
                objectMapper,
                properties.baseUri(),
                properties.requestTimeout(),
                properties.allowPlaintextTransport());
    }

    @Bean
    AgentRunExecutionGateway agentRunExecutionGateway(
            AgentGraphCommandClient commandClient,
            AgentGraphReconciliationClient reconciliationClient,
            AgentRunV2StreamStore streamStore,
            AgentRunReconciledFinalStore reconciledFinalStore,
            AgentRunTransientStreamPublisher transientPublisher) {
        return new DurableAgentRunExecutionGateway(
                commandClient,
                reconciliationClient,
                streamStore,
                reconciledFinalStore,
                transientPublisher);
    }

    private static void requireSyntheticShadow(AgentRunV2Properties properties) {
        if (properties.enabled()) {
            throw new IllegalStateException(
                    "Phase 3 SHADOW Graph execution cannot back the formal AgentRun writer");
        }
    }

    private static void requireTransportSecurity(
            GraphTransportBundle transports,
            GraphCommandClientProperties properties,
            Environment environment) {
        boolean localOrTest = environment.acceptsProfiles(Profiles.of("local", "test"));
        if (properties.allowPlaintextTransport() && !localOrTest) {
            throw new IllegalStateException(
                    "plaintext Graph transport is restricted to local or test profiles");
        }
        GraphTransportSecurityProof proof = transports.transportProof();
        if (proof.mode() == GraphTransportSecurityProof.Mode.LOCAL_PLAINTEXT && !localOrTest) {
            throw new IllegalStateException(
                    "local Graph transport proof is restricted to local or test profiles");
        }
        boolean plaintextEndpoint =
                "http".equalsIgnoreCase(properties.baseUri().getScheme());
        boolean valid = plaintextEndpoint
                ? properties.allowPlaintextTransport()
                        && proof.mode() == GraphTransportSecurityProof.Mode.LOCAL_PLAINTEXT
                : proof.trustedMutualTls();
        if (!valid) {
            throw new IllegalStateException(
                    "Graph command transport security does not match the configured endpoint");
        }
    }
}
