package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentExecutionManifestStore;
import com.example.dispute.agentstream.application.AgentExecutionManifestStore.ManifestCommit;
import com.example.dispute.agentstream.application.AgentRunDomainResultCommitterRegistry;
import com.example.dispute.agentstream.application.AgentRunFormalResultCommitter;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunV2FinalizationFactsProvider;
import com.example.dispute.agentstream.application.AgentRunV2FinalizationGateway;
import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.agentstream.infrastructure.persistence.JpaAgentExecutionManifestStore;
import com.example.dispute.agentstream.infrastructure.persistence.JpaAgentRunLedger;
import com.example.dispute.room.infrastructure.persistence.JdbcIntakeFormalCommitPort;
import com.example.dispute.workflow.activity.intake.IntakeOuterCommitIntegrationFixture.Scenario;
import com.example.dispute.workflow.application.intake.IntakeAgentRunDomainResultCommitter;
import com.example.dispute.workflow.application.intake.IntakeAgentRunFinalizationRequestResolver;
import com.example.dispute.workflow.application.intake.IntakeGraphResultFinalizer;
import com.example.dispute.workflow.application.intake.IntakeImmutableProposalReader;
import com.example.dispute.workflow.application.intake.IntakeProposalReference;
import com.example.dispute.workflow.application.intake.IntakeTurnProposalLoader;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({
    JpaAgentRunLedger.class,
    JpaAgentExecutionManifestStore.class,
    AgentRunFormalResultCommitter.class,
    IntakeOuterCommitAcidIntegrationTest.OuterTransactionConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IntakeOuterCommitAcidIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "intake_outer_acid")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ':'
                                + POSTGRESQL.getMappedPort(5432)
                                + "/intake_outer_acid");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AgentRunV2FinalizationGateway gateway;
    @Autowired private AgentRunFormalResultCommitter outerCommitter;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private FixtureRegistry fixtures;
    @Autowired private ManifestFailureSwitch manifestFailures;

    @BeforeEach
    void resetFailureInjection() {
        manifestFailures.clear();
    }

    @Test
    void successAndCompletionLossReplayCommitEveryFormalEffectExactlyOnce() {
        Scenario fixture = scenario("SUCCESS");

        AgentRunFinalizationReceipt committed = gateway.finalizeResult(
                fixture.executionRequest(), fixture.executionResult());
        AgentRunFinalizationReceipt replayed = gateway.finalizeResult(
                fixture.executionRequest(), fixture.executionResult());

        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        assertThat(AopUtils.isAopProxy(outerCommitter)).isTrue();
        assertThat(committed.commitStatus()).isEqualTo(CommitStatus.COMMITTED);
        assertThat(replayed.commitStatus()).isEqualTo(CommitStatus.ALREADY_COMMITTED);
        assertSameReceiptIdentity(replayed, committed);
        assertFormalCounts(fixture, 1);
        assertThat(value(
                        "select run_status from agent_run where id = ?",
                        fixture.executionRequest().agentRunId()))
                .isEqualTo("COMPLETED");
        assertThat(value(
                        "select finalization_status from agent_run where id = ?",
                        fixture.executionRequest().agentRunId()))
                .isEqualTo("COMMITTED");
        assertThat(value(
                        "select attempt_status from agent_run_attempt where id = ?",
                        fixture.executionRequest().attemptId()))
                .isEqualTo("COMPLETED");
    }

    @Test
    void failureBeforeManifestAppendRollsBackTheDomainCommit() {
        assertManifestFailureRollsBack(ManifestFailurePoint.BEFORE_APPEND);
    }

    @Test
    void failureAfterManifestTerminalizationRollsBackTheEntireOuterTransaction() {
        assertManifestFailureRollsBack(ManifestFailurePoint.AFTER_APPEND);
    }

    private void assertManifestFailureRollsBack(ManifestFailurePoint failurePoint) {
        Scenario fixture = scenario(failurePoint.name());
        manifestFailures.arm(failurePoint);

        try {
            assertThatThrownBy(() -> gateway.finalizeResult(
                            fixture.executionRequest(), fixture.executionResult()))
                    .isInstanceOf(InjectedManifestFailure.class)
                    .hasMessageContaining(failurePoint.name());
        } finally {
            manifestFailures.clear();
        }

        assertFormalCounts(fixture, 0);
        assertThat(value(
                        "select run_status from agent_run where id = ?",
                        fixture.executionRequest().agentRunId()))
                .isEqualTo("RESULT_READY");
        assertThat(value(
                        "select finalization_status from agent_run where id = ?",
                        fixture.executionRequest().agentRunId()))
                .isEqualTo("UNCOMMITTED");
        assertThat(value(
                        "select attempt_status from agent_run_attempt where id = ?",
                        fixture.executionRequest().attemptId()))
                .isEqualTo("RESULT_READY");
    }

    private Scenario scenario(String label) {
        Scenario fixture = IntakeOuterCommitIntegrationFixture.create(
                objectMapper, label + '_' + SEQUENCE.incrementAndGet());
        IntakeOuterCommitIntegrationFixture.insert(jdbc, objectMapper, fixture);
        fixtures.register(fixture);
        return fixture;
    }

    private void assertFormalCounts(Scenario fixture, int expected) {
        String caseId = fixture.caseId();
        assertThat(count("select count(*) from domain_operation where case_id = ?", caseId))
                .isEqualTo(expected);
        assertThat(count("select count(*) from case_intake_dossier where case_id = ?", caseId))
                .isEqualTo(expected);
        assertThat(count("select count(*) from room_message where case_id = ?", caseId))
                .isEqualTo(expected);
        assertThat(count("select count(*) from case_timeline_event where case_id = ?", caseId))
                .isEqualTo(expected);
        assertThat(count("select count(*) from notification_outbox where case_id = ?", caseId))
                .isEqualTo(expected);
        assertThat(count("select count(*) from audit_log where case_id = ?", caseId))
                .isEqualTo(expected);
        assertThat(count("select count(*) from audit_log where resource_id = ?", caseId))
                .isEqualTo(expected);
        assertThat(count(
                        "select count(*) from agent_execution_manifest where case_id = ?",
                        caseId))
                .isEqualTo(expected);
        assertThat(count(
                        "select count(*) from agent_run where id = ?",
                        fixture.executionRequest().agentRunId()))
                .isEqualTo(1);
        assertThat(count(
                        "select count(*) from agent_run_attempt where id = ?",
                        fixture.executionRequest().attemptId()))
                .isEqualTo(1);
    }

    private long count(String sql, String value) {
        return jdbc.queryForObject(sql, Long.class, value);
    }

    private String value(String sql, String value) {
        return jdbc.queryForObject(sql, String.class, value);
    }

    private static void assertSameReceiptIdentity(
            AgentRunFinalizationReceipt actual, AgentRunFinalizationReceipt expected) {
        assertThat(actual.agentRunId()).isEqualTo(expected.agentRunId());
        assertThat(actual.logicalRunId()).isEqualTo(expected.logicalRunId());
        assertThat(actual.attemptId()).isEqualTo(expected.attemptId());
        assertThat(actual.attemptNo()).isEqualTo(expected.attemptNo());
        assertThat(actual.fencingToken()).isEqualTo(expected.fencingToken());
        assertThat(actual.finalResultHash()).isEqualTo(expected.finalResultHash());
        assertThat(actual.manifestId()).isEqualTo(expected.manifestId());
        assertThat(actual.manifestHash()).isEqualTo(expected.manifestHash());
        assertThat(actual.finalStreamSequenceNo())
                .isEqualTo(expected.finalStreamSequenceNo());
        assertThat(actual.committedAt()).isEqualTo(expected.committedAt());
    }

    @TestConfiguration
    static class OuterTransactionConfiguration {

        @Bean
        @Primary
        ObjectMapper outerObjectMapper() {
            return JsonMapper.builder().findAndAddModules().build();
        }

        @Bean
        Clock outerCommitClock() {
            return Clock.fixed(IntakeOuterCommitIntegrationFixture.NOW, ZoneOffset.UTC);
        }

        @Bean
        FixtureRegistry fixtureRegistry() {
            return new FixtureRegistry();
        }

        @Bean
        ManifestFailureSwitch manifestFailureSwitch() {
            return new ManifestFailureSwitch();
        }

        @Bean
        IntakeImmutableProposalReader proposalReader(FixtureRegistry registry) {
            return registry::loadProposal;
        }

        @Bean
        IntakeTurnProposalLoader proposalLoader(IntakeImmutableProposalReader reader) {
            return new IntakeTurnProposalLoader(reader);
        }

        @Bean
        JdbcIntakeFormalCommitPort intakeFormalCommitPort(
                DataSource dataSource,
                PlatformTransactionManager transactionManager,
                ObjectMapper objectMapper,
                Clock clock) {
            return new JdbcIntakeFormalCommitPort(
                    new NamedParameterJdbcTemplate(dataSource),
                    transactionManager,
                    objectMapper,
                    clock);
        }

        @Bean
        IntakeGraphResultFinalizer intakeGraphResultFinalizer(
                IntakeTurnProposalLoader loader, JdbcIntakeFormalCommitPort port) {
            return new IntakeGraphResultFinalizer(loader, port);
        }

        @Bean
        IntakeAgentRunFinalizationRequestResolver intakeRequestResolver(
                FixtureRegistry registry) {
            return registry::resolveFinalizationRequest;
        }

        @Bean
        IntakeAgentRunDomainResultCommitter intakeDomainCommitter(
                IntakeAgentRunFinalizationRequestResolver resolver,
                IntakeGraphResultFinalizer finalizer) {
            return new IntakeAgentRunDomainResultCommitter(resolver, finalizer);
        }

        @Bean
        AgentRunDomainResultCommitterRegistry domainCommitterRegistry(
                IntakeAgentRunDomainResultCommitter intake) {
            return new AgentRunDomainResultCommitterRegistry(java.util.List.of(intake));
        }

        @Bean
        @Primary
        AgentExecutionManifestStore faultInjectingManifestStore(
                JpaAgentExecutionManifestStore delegate,
                ManifestFailureSwitch failures) {
            return new FaultInjectingManifestStore(delegate, failures);
        }

        @Bean
        AgentRunV2ManifestFactory manifestFactory(ObjectMapper objectMapper) {
            return new AgentRunV2ManifestFactory(objectMapper);
        }

        @Bean
        AgentRunV2FinalizationFactsProvider finalizationFactsProvider(
                FixtureRegistry registry) {
            return registry::resolveFacts;
        }

        @Bean
        AgentRunV2FinalizationGateway finalizationGateway(
                AgentRunLedger ledger,
                AgentRunV2FinalizationFactsProvider factsProvider,
                AgentRunV2ManifestFactory manifestFactory,
                AgentRunFormalResultCommitter committer) {
            return new AgentRunV2FinalizationGateway(
                    ledger, factsProvider, manifestFactory, committer);
        }
    }

    static final class FixtureRegistry {

        private final Map<String, Scenario> byRunId = new ConcurrentHashMap<>();
        private final Map<String, Scenario> byProposalUri = new ConcurrentHashMap<>();

        void register(Scenario fixture) {
            byRunId.put(fixture.executionRequest().agentRunId(), fixture);
            byProposalUri.put(fixture.storedProposal().uri(), fixture);
        }

        com.example.dispute.workflow.application.intake.IntakeGraphFinalizationRequest
                resolveFinalizationRequest(
                        com.example.dispute.agentstream.application.AgentRunDomainResultCommitter
                                .CommitCommand command) {
            Scenario fixture = require(command.request().agentRunId());
            if (!fixture.executionRequest().equals(command.request())
                    || !fixture.executionResult().equals(command.result())) {
                throw new IllegalStateException(
                        "outer command differs from the registered fixture");
            }
            return fixture.finalizationRequest();
        }

        com.example.dispute.workflow.application.intake.IntakeImmutableProposalReader.StoredProposal
                loadProposal(IntakeProposalReference reference) {
            Scenario fixture = Optional.ofNullable(byProposalUri.get(reference.uri()))
                    .orElseThrow(() -> new IllegalStateException("proposal fixture was not found"));
            return fixture.storedProposal();
        }

        AgentRunV2ManifestFactory.FinalizationFacts resolveFacts(
                ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
            Scenario fixture = require(request.agentRunId());
            if (!fixture.executionRequest().equals(request)
                    || !fixture.executionResult().equals(result)) {
                throw new IllegalStateException("finalization facts request differs from fixture");
            }
            return fixture.facts();
        }

        private Scenario require(String runId) {
            return Optional.ofNullable(byRunId.get(runId))
                    .orElseThrow(() -> new IllegalStateException("outer fixture was not found"));
        }
    }

    enum ManifestFailurePoint {
        NONE,
        BEFORE_APPEND,
        AFTER_APPEND
    }

    static final class ManifestFailureSwitch {

        private final AtomicReference<ManifestFailurePoint> point =
                new AtomicReference<>(ManifestFailurePoint.NONE);

        void arm(ManifestFailurePoint next) {
            point.set(next);
        }

        void clear() {
            point.set(ManifestFailurePoint.NONE);
        }

        void failIf(ManifestFailurePoint expected) {
            if (point.get() == expected) {
                throw new InjectedManifestFailure(expected);
            }
        }
    }

    static final class InjectedManifestFailure extends RuntimeException {

        InjectedManifestFailure(ManifestFailurePoint point) {
            super("injected manifest failure at " + point.name());
        }
    }

    static final class FaultInjectingManifestStore implements AgentExecutionManifestStore {

        private final JpaAgentExecutionManifestStore delegate;
        private final ManifestFailureSwitch failures;

        FaultInjectingManifestStore(
                JpaAgentExecutionManifestStore delegate, ManifestFailureSwitch failures) {
            this.delegate = delegate;
            this.failures = failures;
        }

        @Override
        public AgentRunFinalizationReceipt append(ManifestCommit commit) {
            failures.failIf(ManifestFailurePoint.BEFORE_APPEND);
            AgentRunFinalizationReceipt receipt = delegate.append(commit);
            failures.failIf(ManifestFailurePoint.AFTER_APPEND);
            return receipt;
        }

        @Override
        public Optional<AgentRunFinalizationReceipt> findCommitted(String logicalRunId) {
            return delegate.findCommitted(logicalRunId);
        }
    }
}
