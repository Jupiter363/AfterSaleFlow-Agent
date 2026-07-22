package com.example.dispute.workflow.config;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.workflow.activity.agent.AgentGraphCommandClient;
import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeActivitiesAdapter;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort;
import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import com.example.dispute.workflow.activity.intake.IntakeSnapshotPublicationPort;
import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher;
import com.example.dispute.workflow.application.intake.IntakeGraphBindingStore;
import com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeCanonicalPayloadValidator;
import com.example.dispute.workflow.infrastructure.objectstore.intake.IntakeRuntimeMaterialObjectStore;
import com.example.dispute.workflow.infrastructure.objectstore.intake.MinioIntakeRuntimeMaterialObjectStore;
import com.example.dispute.workflow.infrastructure.objectstore.intake.MinioIntakeSyntheticExchangeStore;
import com.example.dispute.workflow.infrastructure.objectstore.intake.PrivateObjectStoreIntakeSyntheticRuntimeMaterialSource;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator;
import com.example.dispute.workflow.shadow.intake.IntakeRuntimeMaterialManifestReferenceSource;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticGraphExecutionPort;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticAdmissionReader;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticGraphMaterialSource;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityMaterialSource;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityObservationAdapter;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityObservationPort;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticSignedGraphExecutionAdapter;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticSnapshotMaterialSource;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticSnapshotPublicationAdapter;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticWorkerRegistration;
import com.example.dispute.workflow.shadow.intake.JdbcIntakeSyntheticAdmissionReader;
import com.example.dispute.workflow.shadow.intake.JdbcIntakeSyntheticComparisonLedger;
import com.example.dispute.workflow.shadow.intake.JdbcIntakeSyntheticRuntimeSource;
import com.example.dispute.workflow.shadow.intake.MountedIntakeRuntimeMaterialManifestReferenceSource;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeBridgeReadPortDecorator;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeCommandAdmissionLookup;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeDriver;
import com.example.dispute.workflow.shadow.intake.admission.Es256IntakeSyntheticAdmissionVerifier;
import com.example.dispute.workflow.shadow.intake.admission.IntakeSyntheticAdmissionTrustSet;
import com.example.dispute.workflow.shadow.intake.admission.JdbcIntakeSignedSyntheticAdmissionPort;
import com.example.dispute.workflow.shadow.intake.admission.MountedPemIntakeSyntheticAdmissionTrustSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit, fail-closed assembly for the comparison-only signed synthetic Intake path. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    IntakeEpochSelectionProperties.class,
    GraphCommandClientProperties.class,
    AgentRunV2Properties.class,
    IntakeSyntheticAdmissionTrustProperties.class,
    IntakeSyntheticExchangeProperties.class,
    IntakeSyntheticRuntimeMaterialProperties.class
})
@ConditionalOnProperty(
        name = "app.orchestration.intake-epoch-selection.signed-synthetic-shadow-enabled",
        havingValue = "true")
public class IntakeSyntheticShadowConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "app.temporal.worker.role",
            havingValue = "AGENT")
    JdbcIntakeSyntheticComparisonLedger intakeSyntheticComparisonLedger(
            DataSource dataSource,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            IntakeEpochSelectionProperties epochSelection,
            GraphCommandClientProperties graphClient,
            AgentRunV2Properties agentRunV2) {
        requireSyntheticAgentRuntime(epochSelection, graphClient, agentRunV2);
        return new JdbcIntakeSyntheticComparisonLedger(
                new NamedParameterJdbcTemplate(dataSource),
                objectMapper,
                Clock.systemUTC(),
                transactionManager);
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.temporal.worker.role",
            havingValue = "AGENT")
    IntakeSyntheticWorkerRegistration intakeSyntheticWorkerRegistration(
            IntakeEpochSelectionProperties epochSelection,
            GraphCommandClientProperties graphClient,
            AgentRunV2Properties agentRunV2,
            ObjectProvider<IntakeSignedSyntheticAdmissionPort> admissionProvider,
            ObjectProvider<IntakeSnapshotPublicationPort> snapshotProvider,
            ObjectProvider<IntakeSignedSyntheticGraphExecutionPort> signedGraphProvider,
            ObjectProvider<IntakeSyntheticParityObservationPort> observationProvider,
            JdbcIntakeSyntheticComparisonLedger ledger) {
        requireSyntheticAgentRuntime(epochSelection, graphClient, agentRunV2);
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
    @ConditionalOnProperty(
            name = "app.orchestration.intake-synthetic-admission-trust.enabled",
            havingValue = "true")
    IntakeSyntheticAdmissionTrustSet intakeSyntheticAdmissionTrustSet(
            IntakeSyntheticAdmissionTrustProperties properties) {
        return MountedPemIntakeSyntheticAdmissionTrustSet.load(properties.requireConfigured());
    }

    @Bean
    @ConditionalOnMissingBean({
        Es256IntakeSyntheticAdmissionVerifier.class,
        IntakeSignedSyntheticAdmissionPort.class
    })
    Es256IntakeSyntheticAdmissionVerifier intakeSyntheticAdmissionVerifier(
            IntakeSyntheticAdmissionTrustSet trustSet) {
        return new Es256IntakeSyntheticAdmissionVerifier(trustSet, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(IntakeSignedSyntheticAdmissionPort.class)
    JdbcIntakeSignedSyntheticAdmissionPort intakeSignedSyntheticAdmissionPort(
            Es256IntakeSyntheticAdmissionVerifier verifier,
            DataSource dataSource,
            PlatformTransactionManager transactionManager) {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        return new JdbcIntakeSignedSyntheticAdmissionPort(
                verifier,
                jdbc,
                new TransactionTemplate(transactionManager),
                new EpochAuthorityLockCoordinator(jdbc),
                Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.orchestration.intake-synthetic-runtime-material.enabled",
            havingValue = "true")
    @ConditionalOnProperty(
            name = "app.temporal.worker.role",
            havingValue = "AGENT")
    @ConditionalOnMissingBean(IntakeSyntheticAdmissionReader.class)
    JdbcIntakeSyntheticAdmissionReader intakeSyntheticAdmissionReader() {
        return new JdbcIntakeSyntheticAdmissionReader();
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.orchestration.intake-synthetic-runtime-material.enabled",
            havingValue = "true")
    @ConditionalOnProperty(
            name = "app.temporal.worker.role",
            havingValue = "AGENT")
    @ConditionalOnMissingBean(IntakeRuntimeMaterialManifestReferenceSource.class)
    MountedIntakeRuntimeMaterialManifestReferenceSource
            intakeRuntimeMaterialManifestReferenceSource(
                    ObjectMapper objectMapper,
                    IntakeSyntheticRuntimeMaterialProperties properties,
                    IntakeEpochSelectionProperties epochSelection,
                    GraphCommandClientProperties graphClient,
                    AgentRunV2Properties agentRunV2) {
        requireSyntheticAgentRuntime(epochSelection, graphClient, agentRunV2);
        return MountedIntakeRuntimeMaterialManifestReferenceSource.load(
                properties.requireManifestReferenceIndexPath(), objectMapper);
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.orchestration.intake-synthetic-runtime-material.enabled",
            havingValue = "true")
    @ConditionalOnProperty(
            name = "app.temporal.worker.role",
            havingValue = "AGENT")
    @ConditionalOnMissingBean(IntakeRuntimeMaterialObjectStore.class)
    MinioIntakeRuntimeMaterialObjectStore intakeRuntimeMaterialObjectStore(
            MinioClient minioClient,
            IntakeSyntheticRuntimeMaterialProperties properties,
            IntakeEpochSelectionProperties epochSelection,
            GraphCommandClientProperties graphClient,
            AgentRunV2Properties agentRunV2) {
        requireSyntheticAgentRuntime(epochSelection, graphClient, agentRunV2);
        return new MinioIntakeRuntimeMaterialObjectStore(
                minioClient, properties.bucket(), properties.prefix());
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.orchestration.intake-synthetic-runtime-material.enabled",
            havingValue = "true")
    @ConditionalOnProperty(
            name = "app.temporal.worker.role",
            havingValue = "AGENT")
    @ConditionalOnMissingBean({
        IntakeSyntheticSnapshotMaterialSource.class,
        IntakeSyntheticGraphMaterialSource.class,
        IntakeSyntheticParityMaterialSource.class
    })
    PrivateObjectStoreIntakeSyntheticRuntimeMaterialSource
            intakeSyntheticRuntimeMaterialSourceProvider(
                    ObjectMapper objectMapper,
                    IntakeRuntimeMaterialManifestReferenceSource referenceSource,
                    IntakeRuntimeMaterialObjectStore objectStore,
                    IntakeEpochSelectionProperties epochSelection,
                    GraphCommandClientProperties graphClient,
                    AgentRunV2Properties agentRunV2) {
        requireSyntheticAgentRuntime(epochSelection, graphClient, agentRunV2);
        return new PrivateObjectStoreIntakeSyntheticRuntimeMaterialSource(
                objectMapper, referenceSource, objectStore);
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.orchestration.intake-synthetic-runtime-material.enabled",
            havingValue = "true")
    @ConditionalOnProperty(
            name = "app.temporal.worker.role",
            havingValue = "AGENT")
    @ConditionalOnMissingBean(IntakeImmutablePayloadPublisher.class)
    MinioIntakeSyntheticExchangeStore intakeSyntheticRuntimePayloadPublisher(
            MinioClient minioClient,
            IntakeSyntheticExchangeProperties properties,
            IntakeEpochSelectionProperties epochSelection,
            GraphCommandClientProperties graphClient,
            AgentRunV2Properties agentRunV2) {
        requireSyntheticAgentRuntime(epochSelection, graphClient, agentRunV2);
        return new MinioIntakeSyntheticExchangeStore(
                minioClient,
                new IntakeExchangeCanonicalPayloadValidator(),
                properties.bucket(),
                properties.prefix());
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.orchestration.intake-synthetic-runtime-material.enabled",
            havingValue = "true")
    @ConditionalOnProperty(
            name = "app.temporal.worker.role",
            havingValue = "AGENT")
    @ConditionalOnMissingBean(IntakeSyntheticRuntimeSource.class)
    JdbcIntakeSyntheticRuntimeSource intakeSyntheticRuntimeSource(
            DataSource dataSource,
            IntakeSyntheticAdmissionReader admissionReader,
            IntakeSyntheticSnapshotMaterialSource snapshotMaterialSource,
            IntakeSyntheticGraphMaterialSource graphMaterialSource,
            IntakeSyntheticParityMaterialSource parityMaterialSource) {
        return new JdbcIntakeSyntheticRuntimeSource(
                dataSource,
                admissionReader,
                snapshotMaterialSource,
                graphMaterialSource,
                parityMaterialSource);
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.orchestration.intake-synthetic-runtime-material.enabled",
            havingValue = "true")
    @ConditionalOnProperty(
            name = "app.temporal.worker.role",
            havingValue = "AGENT")
    @ConditionalOnMissingBean(IntakeSnapshotPublicationPort.class)
    IntakeSyntheticSnapshotPublicationAdapter intakeSyntheticSnapshotPublicationAdapter(
            IntakeSyntheticRuntimeSource source,
            IntakeImmutablePayloadPublisher payloadPublisher,
            IntakeGraphBindingStore bindingStore) {
        return new IntakeSyntheticSnapshotPublicationAdapter(
                source, new IntakeDomainSnapshotPublisher(payloadPublisher, bindingStore));
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.orchestration.intake-synthetic-runtime-material.enabled",
            havingValue = "true")
    @ConditionalOnProperty(
            name = "app.temporal.worker.role",
            havingValue = "AGENT")
    @ConditionalOnMissingBean(IntakeSignedSyntheticGraphExecutionPort.class)
    IntakeSyntheticSignedGraphExecutionAdapter intakeSyntheticSignedGraphExecutionAdapter(
            IntakeSyntheticRuntimeSource source,
            ObjectMapper objectMapper,
            AgentGraphCommandClient commandClient,
            AgentGraphReconciliationClient reconciliationClient) {
        return new IntakeSyntheticSignedGraphExecutionAdapter(
                source,
                new IntakeGraphCommandFactory(),
                new AgentRunCommandBindingFactory(objectMapper),
                commandClient,
                reconciliationClient);
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.orchestration.intake-synthetic-runtime-material.enabled",
            havingValue = "true")
    @ConditionalOnProperty(
            name = "app.temporal.worker.role",
            havingValue = "AGENT")
    @ConditionalOnMissingBean(IntakeSyntheticParityObservationPort.class)
    IntakeSyntheticParityObservationAdapter intakeSyntheticParityObservationAdapter(
            IntakeSyntheticRuntimeSource source) {
        return new IntakeSyntheticParityObservationAdapter(source);
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.temporal.worker.role",
            havingValue = "CONTROL")
    IntakeAuthorityWorkerRegistration signedSyntheticIntakeAuthorityWorkerRegistration(
            IntakeChildBridgeReadPort readPort,
            SignedSyntheticIntakeCommandAdmissionLookup admissions,
            IntakeEpochSelectionProperties epochSelection,
            AgentRunV2Properties agentRunV2) {
        requireSignedSyntheticSelection(epochSelection, agentRunV2);
        return IntakeAuthorityWorkerRegistration.fromAdapter(
                new IntakeChildBridgeActivitiesAdapter(
                        new SignedSyntheticIntakeBridgeReadPortDecorator(
                                readPort, admissions, Clock.systemUTC()),
                        true));
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.temporal.worker.enabled",
            havingValue = "false",
            matchIfMissing = true)
    SignedSyntheticIntakeDriver signedSyntheticIntakeDriver(
            IntakeSignedSyntheticAdmissionPort admission,
            IntakeEpochSelectionProperties epochSelection,
            AgentRunV2Properties agentRunV2) {
        requireSignedSyntheticSelection(epochSelection, agentRunV2);
        return new SignedSyntheticIntakeDriver(admission);
    }

    private static void requireSyntheticAgentRuntime(
            IntakeEpochSelectionProperties epochSelection,
            GraphCommandClientProperties graphClient,
            AgentRunV2Properties agentRunV2) {
        requireSignedSyntheticSelection(epochSelection, agentRunV2);
        if (graphClient.mode() != GraphCommandClientProperties.Mode.SHADOW) {
            throw new IllegalStateException(
                    "signed synthetic Intake requires Graph client mode SHADOW");
        }
    }

    private static void requireSignedSyntheticSelection(
            IntakeEpochSelectionProperties epochSelection,
            AgentRunV2Properties agentRunV2) {
        if (!epochSelection.shadowSelectionConfigured()) {
            throw new IllegalStateException(
                    "signed synthetic Intake requires a complete SHADOW epoch selection");
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
