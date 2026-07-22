package com.example.dispute.workflow.config;

import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeCanonicalPayloadValidator;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangePayloadObjectStoreGateway;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeService;
import com.example.dispute.workflow.infrastructure.objectstore.intake.IntakePrivateObjectStoreExchangeAdapter;
import com.example.dispute.workflow.infrastructure.objectstore.intake.MinioIntakeSyntheticExchangeStore;
import com.example.dispute.workflow.infrastructure.persistence.authority.intake.JdbcSignedSyntheticIntakeExchangeAuthorityValidationPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import java.time.Clock;
import javax.sql.DataSource;
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
    AgentRunV2Properties.class,
    IntakeSyntheticExchangeProperties.class
})
@ConditionalOnProperty(
        name = "app.orchestration.intake-epoch-selection.signed-synthetic-shadow-enabled",
        havingValue = "true")
public class IntakeSyntheticExchangeConfiguration {

    @Bean
    @ConditionalOnMissingBean
    IntakeExchangeCanonicalPayloadValidator intakeExchangeCanonicalPayloadValidator() {
        return new IntakeExchangeCanonicalPayloadValidator();
    }

    @Bean
    @ConditionalOnMissingBean(IntakeExchangeAuthorityValidationPort.class)
    JdbcSignedSyntheticIntakeExchangeAuthorityValidationPort intakeExchangeAuthority(
            DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcSignedSyntheticIntakeExchangeAuthorityValidationPort(
                dataSource, objectMapper, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean({
        IntakeImmutablePayloadPublisher.class,
        IntakeExchangePayloadObjectStoreGateway.class
    })
    MinioIntakeSyntheticExchangeStore intakeSyntheticExchangeStore(
            MinioClient minioClient,
            IntakeExchangeCanonicalPayloadValidator validator,
            IntakeSyntheticExchangeProperties properties) {
        return new MinioIntakeSyntheticExchangeStore(
                minioClient, validator, properties.bucket(), properties.prefix());
    }

    @Bean
    @ConditionalOnMissingBean(IntakeExchangeService.class)
    IntakeExchangeService intakeExchangeService(
            IntakeExchangeAuthorityValidationPort authority,
            IntakeExchangePayloadObjectStoreGateway payloadStore,
            IntakeImmutablePayloadPublisher proposalPublisher,
            IntakeEpochSelectionProperties epochSelection,
            GraphCommandClientProperties graphClient,
            AgentRunV2Properties agentRunV2,
            IntakeExchangeCanonicalPayloadValidator validator) {
        requireSyntheticShadow(epochSelection, graphClient, agentRunV2);
        return new IntakeExchangeService(
                authority,
                new IntakePrivateObjectStoreExchangeAdapter(payloadStore, proposalPublisher),
                validator);
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
