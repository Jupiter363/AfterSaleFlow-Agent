package com.example.dispute.workflow.config;

import com.example.dispute.agentstream.application.AgentRunReconciledFinalStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.workflow.activity.agent.AgentGraphCommandClient;
import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.DurableAgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.GraphCommandEnvelopeSigner;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.contract.v1.AgentPlatformContractCodec;
import com.example.dispute.workflow.infrastructure.agent.GraphCommandHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.HttpAgentGraphCommandClient;
import com.example.dispute.workflow.infrastructure.security.Es256GraphCommandEnvelopeSigner;
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
    AgentGraphCommandClient agentGraphCommandClient(
            GraphCommandHttpTransport transport,
            GraphCommandEnvelopeSigner envelopeSigner,
            AgentGraphReconciliationClient reconciliationClient,
            GraphStreamVisibilityPolicy visibilityPolicy,
            AgentPlatformContractCodec codec,
            ObjectMapper objectMapper,
            GraphCommandClientProperties properties,
            AgentRunV2Properties agentRunV2Properties,
            Environment environment) {
        requireSyntheticShadow(agentRunV2Properties);
        requireTransportSecurity(transport, properties, environment);
        return new HttpAgentGraphCommandClient(
                transport,
                envelopeSigner,
                reconciliationClient,
                visibilityPolicy,
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
            AgentRunReconciledFinalStore reconciledFinalStore) {
        return new DurableAgentRunExecutionGateway(
                commandClient,
                reconciliationClient,
                streamStore,
                reconciledFinalStore);
    }

    private static void requireSyntheticShadow(AgentRunV2Properties properties) {
        if (properties.enabled()) {
            throw new IllegalStateException(
                    "Phase 3 SHADOW Graph execution cannot back the formal AgentRun writer");
        }
    }

    private static void requireTransportSecurity(
            GraphCommandHttpTransport transport,
            GraphCommandClientProperties properties,
            Environment environment) {
        boolean localOrTest = environment.acceptsProfiles(Profiles.of("local", "test"));
        if (properties.allowPlaintextTransport() && !localOrTest) {
            throw new IllegalStateException(
                    "plaintext Graph transport is restricted to local or test profiles");
        }
        boolean plaintextEndpoint =
                "http".equalsIgnoreCase(properties.baseUri().getScheme());
        GraphCommandHttpTransport.TransportSecurity expected = plaintextEndpoint
                ? GraphCommandHttpTransport.TransportSecurity.LOCAL_PLAINTEXT
                : GraphCommandHttpTransport.TransportSecurity.MUTUAL_TLS;
        if (transport.transportSecurity() != expected) {
            throw new IllegalStateException(
                    "Graph command transport security does not match the configured endpoint");
        }
    }
}
