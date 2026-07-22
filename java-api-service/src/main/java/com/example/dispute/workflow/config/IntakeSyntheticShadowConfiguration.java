package com.example.dispute.workflow.config;

import com.example.dispute.workflow.activity.intake.IntakeSnapshotPublicationPort;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticGraphExecutionPort;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityObservationPort;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticWorkerRegistration;
import com.example.dispute.workflow.shadow.intake.JdbcIntakeSyntheticComparisonLedger;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeDriver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Explicit, fail-closed assembly for the comparison-only signed synthetic Intake path. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    IntakeEpochSelectionProperties.class,
    GraphCommandClientProperties.class,
    AgentRunV2Properties.class
})
@ConditionalOnProperty(
        name = "app.orchestration.intake-epoch-selection.signed-synthetic-shadow-enabled",
        havingValue = "true")
public class IntakeSyntheticShadowConfiguration {

    @Bean
    JdbcIntakeSyntheticComparisonLedger intakeSyntheticComparisonLedger(
            DataSource dataSource,
            ObjectMapper objectMapper,
            IntakeEpochSelectionProperties epochSelection,
            GraphCommandClientProperties graphClient,
            AgentRunV2Properties agentRunV2) {
        requireSyntheticShadow(epochSelection, graphClient, agentRunV2);
        return new JdbcIntakeSyntheticComparisonLedger(
                new NamedParameterJdbcTemplate(dataSource), objectMapper, Clock.systemUTC());
    }

    @Bean
    IntakeSyntheticWorkerRegistration intakeSyntheticWorkerRegistration(
            IntakeEpochSelectionProperties epochSelection,
            GraphCommandClientProperties graphClient,
            AgentRunV2Properties agentRunV2,
            ObjectProvider<IntakeSignedSyntheticAdmissionPort> admissionProvider,
            ObjectProvider<IntakeSnapshotPublicationPort> snapshotProvider,
            ObjectProvider<IntakeSignedSyntheticGraphExecutionPort> signedGraphProvider,
            ObjectProvider<IntakeSyntheticParityObservationPort> observationProvider,
            JdbcIntakeSyntheticComparisonLedger ledger) {
        requireSyntheticShadow(epochSelection, graphClient, agentRunV2);
        return new IntakeSyntheticWorkerRegistration(
                requireExactlyOneReal(
                        admissionProvider, IntakeSignedSyntheticAdmissionPort.class),
                requireExactlyOneReal(snapshotProvider, IntakeSnapshotPublicationPort.class),
                requireExactlyOneReal(
                        signedGraphProvider, IntakeSignedSyntheticGraphExecutionPort.class),
                requireExactlyOneReal(
                        observationProvider, IntakeSyntheticParityObservationPort.class),
                ledger);
    }

    @Bean
    SignedSyntheticIntakeDriver signedSyntheticIntakeDriver(
            IntakeSyntheticWorkerRegistration registration) {
        return registration.driver();
    }

    private static void requireSyntheticShadow(
            IntakeEpochSelectionProperties epochSelection,
            GraphCommandClientProperties graphClient,
            AgentRunV2Properties agentRunV2) {
        if (!epochSelection.shadowSelectionConfigured()) {
            throw new IllegalStateException(
                    "signed synthetic Intake requires a complete SHADOW epoch selection");
        }
        if (graphClient.mode() != GraphCommandClientProperties.Mode.SHADOW) {
            throw new IllegalStateException(
                    "signed synthetic Intake requires Graph client mode SHADOW");
        }
        if (agentRunV2.enabled()) {
            throw new IllegalStateException(
                    "signed synthetic Intake cannot run while AgentRunV2 is enabled");
        }
    }

    private static <T> T requireExactlyOneReal(ObjectProvider<T> provider, Class<T> type) {
        List<T> candidates = provider.stream().toList();
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                    "signed synthetic Intake requires exactly one real " + type.getName());
        }
        return candidates.getFirst();
    }
}
