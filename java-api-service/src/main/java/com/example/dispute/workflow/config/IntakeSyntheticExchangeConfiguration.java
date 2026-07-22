package com.example.dispute.workflow.config;

import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeCanonicalPayloadValidator;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangePayloadObjectStoreGateway;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeService;
import com.example.dispute.workflow.infrastructure.objectstore.intake.IntakePrivateObjectStoreExchangeAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fail-closed assembly for the private signed-synthetic Intake exchange. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    IntakeEpochSelectionProperties.class,
    GraphCommandClientProperties.class,
    AgentRunV2Properties.class
})
@ConditionalOnProperty(
        name = "app.orchestration.intake-epoch-selection.signed-synthetic-shadow-enabled",
        havingValue = "true")
public class IntakeSyntheticExchangeConfiguration {

    @Bean
    @ConditionalOnBean({
        IntakeExchangeAuthorityValidationPort.class,
        IntakeExchangePayloadObjectStoreGateway.class,
        IntakeImmutablePayloadPublisher.class
    })
    @ConditionalOnMissingBean(IntakeExchangeService.class)
    IntakeExchangeService intakeExchangeService(
            IntakeExchangeAuthorityValidationPort authority,
            IntakeExchangePayloadObjectStoreGateway payloadStore,
            IntakeImmutablePayloadPublisher proposalPublisher,
            IntakeEpochSelectionProperties epochSelection,
            GraphCommandClientProperties graphClient,
            AgentRunV2Properties agentRunV2) {
        requireSyntheticShadow(epochSelection, graphClient, agentRunV2);
        return new IntakeExchangeService(
                authority,
                new IntakePrivateObjectStoreExchangeAdapter(payloadStore, proposalPublisher),
                new IntakeExchangeCanonicalPayloadValidator());
    }

    private static void requireSyntheticShadow(
            IntakeEpochSelectionProperties epochSelection,
            GraphCommandClientProperties graphClient,
            AgentRunV2Properties agentRunV2) {
        if (!epochSelection.shadowSelectionConfigured()) {
            throw new IllegalStateException(
                    "Intake exchange requires a complete signed synthetic SHADOW selection");
        }
        if (graphClient.mode() != GraphCommandClientProperties.Mode.SHADOW) {
            throw new IllegalStateException("Intake exchange requires Graph client mode SHADOW");
        }
        if (agentRunV2.enabled()) {
            throw new IllegalStateException(
                    "Intake exchange cannot run while the formal AgentRunV2 path is enabled");
        }
    }
}
