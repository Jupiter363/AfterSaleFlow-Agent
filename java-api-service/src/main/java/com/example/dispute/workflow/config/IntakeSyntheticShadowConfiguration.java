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
import com.example.dispute.workflow.infrastructure.objectstore.intake.IntakeRuntimeMaterialObjectStore;
import com.example.dispute.workflow.infrastructure.objectstore.intake.MinioIntakeRuntimeMaterialObjectStore;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
    IntakeSyntheticRuntimeMaterialProperties.class
})
@ConditionalOnProperty(
        name = "app.orchestration.intake-epoch-selection.signed-synthetic-shadow-enabled",
        havingValue = "true")
public class IntakeSyntheticShadowConfiguration {

    @Bean
    JdbcIntakeSyntheticComparisonLedger intakeSyntheticComparisonLedger(
            DataSource dataSource,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            IntakeEpochSelectionProperties epochSelection,
            GraphCommandClientProperties graphClient,
            AgentRunV2Properties agentRunV2) {
        requireSyntheticShadow(epochSelection, graphClient, agentRunV2);
        return new JdbcIntakeSyntheticComparisonLedger(
                new NamedParameterJdbcTemplate(dataSource),
                objectMapper,
                Clock.systemUTC(),
                transactionManager);
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
    @ConditionalOnProperty(
            name = "app.orchestration.intake-synthetic-admission-trust.enabled",
            havingValue = "true")
    IntakeSyntheticAdmissionTrustSet intakeSyntheticAdmissionTrustSet(
            IntakeSyntheticAdmissionTrustProperties properties) {
        return MountedPemIntakeSyntheticAdmissionTrustSet.load(properties.requireConfigured());
    }

    @Bean
    @ConditionalOnBean(IntakeSyntheticAdmissionTrustSet.class)
    @ConditionalOnMissingBean
    Es256IntakeSyntheticAdmissionVerifier intakeSyntheticAdmissionVerifier(
            IntakeSyntheticAdmissionTrustSet trustSet) {
        return new Es256IntakeSyntheticAdmissionVerifier(trustSet, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnBean(Es256IntakeSyntheticAdmissionVerifier.class)
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
    @ConditionalOnBean(IntakeSyntheticAdmissionTrustSet.class)
    @ConditionalOnMissingBean(IntakeSyntheticAdmissionReader.class)
    JdbcIntakeSyntheticAdmissionReader intakeSyntheticAdmissionReader() {
        return new JdbcIntakeSyntheticAdmissionReader();
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.orchestration.intake-synthetic-runtime-material.enabled",
            havingValue = "true")
    @ConditionalOnBean(IntakeSyntheticAdmissionTrustSet.class)
    @ConditionalOnMissingBean(IntakeRuntimeMaterialManifestReferenceSource.class)
    MountedIntakeRuntimeMaterialManifestReferenceSource
            intakeRuntimeMaterialManifestReferenceSource(
                    ObjectMapper objectMapper,
                    IntakeSyntheticRuntimeMaterialProperties properties,
                    IntakeEpochSelectionProperties epochSelection,
                    GraphCommandClientProperties graphClient,
                    AgentRunV2Properties agentRunV2) {
        requireSyntheticShadow(epochSelection, graphClient, agentRunV2);
        return MountedIntakeRuntimeMaterialManifestReferenceSource.load(
                properties.requireManifestReferenceIndexPath(), objectMapper);
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.orchestration.intake-synthetic-runtime-material.enabled",
            havingValue = "true")
    @ConditionalOnBean(IntakeSyntheticAdmissionTrustSet.class)
    @ConditionalOnMissingBean(IntakeRuntimeMaterialObjectStore.class)
    MinioIntakeRuntimeMaterialObjectStore intakeRuntimeMaterialObjectStore(
            MinioClient minioClient,
            IntakeSyntheticRuntimeMaterialProperties properties,
            IntakeEpochSelectionProperties epochSelection,
            GraphCommandClientProperties graphClient,
            AgentRunV2Properties agentRunV2) {
        requireSyntheticShadow(epochSelection, graphClient, agentRunV2);
        return new MinioIntakeRuntimeMaterialObjectStore(
                minioClient, properties.bucket(), properties.prefix());
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.orchestration.intake-synthetic-runtime-material.enabled",
            havingValue = "true")
    @ConditionalOnBean({
        IntakeSyntheticAdmissionTrustSet.class,
        IntakeRuntimeMaterialManifestReferenceSource.class,
        IntakeRuntimeMaterialObjectStore.class
    })
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
        requireSyntheticShadow(epochSelection, graphClient, agentRunV2);
        return new PrivateObjectStoreIntakeSyntheticRuntimeMaterialSource(
                objectMapper, referenceSource, objectStore);
    }

    @Bean
    @ConditionalOnBean({
        IntakeSyntheticAdmissionTrustSet.class,
        IntakeSyntheticAdmissionReader.class,
        IntakeSyntheticSnapshotMaterialSource.class,
        IntakeSyntheticGraphMaterialSource.class,
        IntakeSyntheticParityMaterialSource.class
    })
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
    @ConditionalOnBean({
        IntakeSyntheticRuntimeSource.class,
        IntakeImmutablePayloadPublisher.class,
        IntakeGraphBindingStore.class
    })
    @ConditionalOnMissingBean(IntakeSnapshotPublicationPort.class)
    IntakeSyntheticSnapshotPublicationAdapter intakeSyntheticSnapshotPublicationAdapter(
            IntakeSyntheticRuntimeSource source,
            IntakeImmutablePayloadPublisher payloadPublisher,
            IntakeGraphBindingStore bindingStore) {
        return new IntakeSyntheticSnapshotPublicationAdapter(
                source, new IntakeDomainSnapshotPublisher(payloadPublisher, bindingStore));
    }

    @Bean
    @ConditionalOnBean({
        IntakeSyntheticRuntimeSource.class,
        AgentGraphCommandClient.class,
        AgentGraphReconciliationClient.class
    })
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
    @ConditionalOnBean(IntakeSyntheticRuntimeSource.class)
    @ConditionalOnMissingBean(IntakeSyntheticParityObservationPort.class)
    IntakeSyntheticParityObservationAdapter intakeSyntheticParityObservationAdapter(
            IntakeSyntheticRuntimeSource source) {
        return new IntakeSyntheticParityObservationAdapter(source);
    }

    @Bean
    @ConditionalOnBean({
        IntakeChildBridgeReadPort.class,
        SignedSyntheticIntakeCommandAdmissionLookup.class
    })
    IntakeAuthorityWorkerRegistration signedSyntheticIntakeAuthorityWorkerRegistration(
            IntakeChildBridgeReadPort readPort,
            SignedSyntheticIntakeCommandAdmissionLookup admissions) {
        return IntakeAuthorityWorkerRegistration.fromAdapter(
                new IntakeChildBridgeActivitiesAdapter(
                        new SignedSyntheticIntakeBridgeReadPortDecorator(
                                readPort, admissions, Clock.systemUTC()),
                        true));
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
