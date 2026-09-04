package com.example.dispute.workflow.runtime.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.runtime.ProductionActivationCaseLedger.Action;
import com.example.dispute.workflow.runtime.ProductionActivationCaseLedger.Reservation;
import com.example.dispute.workflow.runtime.ProductionActivationCaseLedger.ReservationResult;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.BuildBindings;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.DatabaseIdentities;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.DatabaseIdentity;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.GraphBinding;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.ImageDigests;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.IsolatedSyntheticNewCases;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.MeasuredAuthorityFacts;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.RoomType;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.SyntheticFixtureDeployment;
import com.example.dispute.workflow.runtime.ProductionActivationLifecycleStore.ActivationIdentity;
import com.example.dispute.workflow.runtime.ProductionActivationLifecycleStore.DrainCompletionProof;
import com.example.dispute.workflow.runtime.ProductionActivationLifecycleStore.LifecycleState;
import com.example.dispute.workflow.runtime.ProductionActivationLifecycleStore.TransitionResult;
import com.example.dispute.workflow.runtime.ProductionActivationReplayStore.BindingSnapshot;
import com.example.dispute.workflow.runtime.ProductionActivationReplayStore.Registration;
import com.example.dispute.workflow.runtime.ProductionActivationReplayStore.RegistrationResult;
import com.example.dispute.workflow.runtime.ProductionIsolatedDomainDbBinding;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class JdbcProductionActivationStoresIntegrationTest {

  private static final String FIXTURE_SET_ID = "p9-synthetic-all-rooms-001";
  private static final String FIXTURE_HASH = "f".repeat(64);
  private static final String CASE_PREFIX = "CASE_P9_SYNTHETIC_";

  @Container
  static final GenericContainer<?> POSTGRES =
      new GenericContainer<>(
              DockerImageName.parse("public.ecr.aws/docker/library/postgres:16-alpine"))
          .withEnv("POSTGRES_DB", "production_runtime_stores")
          .withEnv("POSTGRES_USER", "target_test")
          .withEnv("POSTGRES_PASSWORD", "target_test")
          .withExposedPorts(5432)
          .waitingFor(Wait.forListeningPort());

  private static JdbcProductionActivationStores stores;
  private static DriverManagerDataSource dataSource;

  @BeforeAll
  static void migrate() {
    dataSource =
        new DriverManagerDataSource(
            "jdbc:postgresql://"
                + POSTGRES.getHost()
                + ':'
                + POSTGRES.getMappedPort(5432)
                + "/production_runtime_stores",
            "target_test",
            "target_test");
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    stores = new JdbcProductionActivationStores(dataSource, Clock.systemUTC());
  }

  @Test
  void replayCaseReservationAndLifecycleRemainFailClosed() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Registration registration =
        registration("1", "environment-store-a", 1, now.minusSeconds(10), now.plusSeconds(600));

    assertThat(stores.registerOrAttach(registration)).isEqualTo(RegistrationResult.REGISTERED);
    assertThat(stores.registerOrAttach(registration))
        .isEqualTo(RegistrationResult.ATTACHED_EXISTING);
    ActivationIdentity identity = identity(registration);
    assertThat(stores.refresh(identity, registration.expiresAt(), now).state())
        .isEqualTo(LifecycleState.ACTIVE);

    Reservation reservation = reservation(registration, 2, CASE_PREFIX + "2");
    assertThat(stores.apply(Action.RESERVE_BEFORE_EPOCH_SELECTION, reservation))
        .isEqualTo(ReservationResult.RESERVED);
    assertThat(stores.apply(Action.REQUIRE_EXISTING, reservation))
        .isEqualTo(ReservationResult.ALREADY_RESERVED_IDENTICALLY);
    assertThat(
            stores.apply(
                Action.REQUIRE_EXISTING, reservation(registration, 1, CASE_PREFIX + "1")))
        .isEqualTo(ReservationResult.NOT_RESERVED);
    assertThat(
            stores.apply(
                Action.RESERVE_BEFORE_EPOCH_SELECTION,
                reservation(registration, 2, CASE_PREFIX + "different")))
        .isEqualTo(ReservationResult.SLOT_CONFLICT);

    Registration otherEnvironment =
        registration("2", "environment-store-b", 1, now.minusSeconds(10), now.plusSeconds(600));
    assertThat(stores.registerOrAttach(otherEnvironment))
        .isEqualTo(RegistrationResult.REGISTERED);
    assertThat(
            stores.refresh(identity(otherEnvironment), otherEnvironment.expiresAt(), now).state())
        .isEqualTo(LifecycleState.ACTIVE);
    assertThat(
            stores.apply(
                Action.RESERVE_BEFORE_EPOCH_SELECTION,
                reservation(otherEnvironment, 1, reservation.caseId())))
        .isEqualTo(ReservationResult.GENERATED_CASE_ID_GLOBAL_CONFLICT);

    assertThat(
            stores.refresh(identity, registration.expiresAt(), registration.expiresAt()).state())
        .isEqualTo(LifecycleState.DRAIN_ONLY);
    Instant completedAt = registration.expiresAt().plusSeconds(1).plusNanos(789);
    assertThat(stores.markDrained(identity, drainProof(1, 0, true, completedAt)))
        .isEqualTo(TransitionResult.REJECTED_UNRESOLVED_WORK);
    assertThat(stores.markDrained(identity, drainProof(0, 1, true, completedAt)))
        .isEqualTo(TransitionResult.REJECTED_REPLICAS_ATTACHED);
    assertThat(stores.markDrained(identity, drainProof(0, 0, false, completedAt)))
        .isEqualTo(TransitionResult.REJECTED_EVIDENCE_NOT_SEALED);
    assertThat(stores.markDrained(identity, drainProof(0, 0, true, completedAt)))
        .isEqualTo(TransitionResult.TRANSITIONED);
    assertThat(stores.markDrained(identity, drainProof(0, 0, true, completedAt)))
        .isEqualTo(TransitionResult.ALREADY_IN_TARGET_STATE);
    assertThat(
            stores.markDrained(
                identity,
                drainProof(0, 0, true, completedAt.plusNanos(1_000))))
        .isEqualTo(TransitionResult.REJECTED_TIMESTAMP_ORDER);
    assertThat(stores.revokeTerminal(identity, completedAt))
        .isEqualTo(TransitionResult.REJECTED_TIMESTAMP_ORDER);
    Instant revokedAt = completedAt.plusSeconds(1).plusNanos(456);
    assertThat(stores.revokeTerminal(identity, revokedAt))
        .isEqualTo(TransitionResult.TRANSITIONED);
    assertThat(stores.revokeTerminal(identity, revokedAt))
        .isEqualTo(TransitionResult.ALREADY_IN_TARGET_STATE);
    assertThat(stores.revokeTerminal(identity, revokedAt.plusNanos(1_000)))
        .isEqualTo(TransitionResult.REJECTED_TIMESTAMP_ORDER);
    assertThat(
            stores
                .refresh(identity, registration.expiresAt(), completedAt.plusSeconds(2))
                .state())
        .isEqualTo(LifecycleState.REVOKED_TERMINAL);
  }

  @Test
  void drainAttachCannotCreateAndOlderGenerationCannotReattach() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Registration first =
        registration("3", "environment-store-c", 1, now.minusSeconds(10), now.plusSeconds(600));
    Registration second =
        registration("4", "environment-store-c", 2, now.minusSeconds(10), now.plusSeconds(600));
    Registration absent =
        registration("5", "environment-store-d", 1, now.minusSeconds(10), now.plusSeconds(600));

    assertThat(stores.attachExistingForDrain(absent)).isEqualTo(RegistrationResult.CONFLICT);
    assertThat(stores.registerOrAttach(first)).isEqualTo(RegistrationResult.REGISTERED);
    assertThat(stores.attachExistingForDrain(first))
        .isEqualTo(RegistrationResult.ATTACHED_EXISTING);
    assertThat(stores.registerOrAttach(second)).isEqualTo(RegistrationResult.REGISTERED);
    assertThat(stores.attachExistingForDrain(first))
        .isEqualTo(RegistrationResult.ENVIRONMENT_GENERATION_STALE);
  }

  @Test
  void storedDomainBindingHashMatchesTheFinalizerEvidenceDocument() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Registration registration =
        registration("6", "environment-store-e", 3, now.minusSeconds(10), now.plusSeconds(600));

    assertThat(stores.registerOrAttach(registration)).isEqualTo(RegistrationResult.REGISTERED);

    String storedHash =
        new JdbcTemplate(dataSource)
            .queryForObject(
                """
                select isolated_domain_db_binding_hash
                  from production_runtime_activation
                 where activation_id = ?
                """,
                String.class,
                registration.activationId());
    DatabaseIdentity domain = registration.bindings().databaseIdentities().domain();
    String evidenceHash =
        ProductionIsolatedDomainDbBinding.document(
                registration.environmentId(),
                registration.environmentGeneration(),
                registration.activationId(),
                domain.clusterIdentity(),
                domain.databaseIdentity(),
                domain.runtimePrincipalIdentity())
            .required("binding_hash")
            .textValue();

    assertThat(storedHash).isEqualTo(evidenceHash);
  }

  private static Registration registration(
      String discriminator,
      String environmentId,
      long generation,
      Instant issuedAt,
      Instant expiresAt) {
    IsolatedSyntheticNewCases caseScope =
        new IsolatedSyntheticNewCases(
            CASE_PREFIX, 4, FIXTURE_SET_ID, FIXTURE_HASH, false, false);
    BindingSnapshot bindings =
        new BindingSnapshot(
            "a".repeat(40),
            "tenant-production-runtime",
            caseScope,
            Set.of(RoomType.values()),
            new BuildBindings("case-build", "control-build", "agent-build"),
            new GraphBinding(
                "all-rooms.production-runtime.v1",
                "graph-version",
                "checkpoint-v1",
                "b".repeat(64),
                "graph-code-build"),
            new ImageDigests(
                "sha256:" + "1".repeat(64),
                "sha256:" + "2".repeat(64),
                "sha256:" + "3".repeat(64),
                "sha256:" + "4".repeat(64),
                "sha256:" + "5".repeat(64)),
            "production-runtime-namespace",
            new DatabaseIdentities(
                new DatabaseIdentity("domain-cluster", "domain-database", "domain-runtime"),
                new DatabaseIdentity("graph-cluster", "graph-database", "graph-runtime")),
            Optional.of(
                new SyntheticFixtureDeployment(
                    FIXTURE_SET_ID, "/run/production-runtime/fixtures/set.json", FIXTURE_HASH)),
            new MeasuredAuthorityFacts(
                true,
                "ISOLATED_PREPRODUCTION",
                "PROPOSAL_ONLY",
                false,
                false,
                false,
                "JAVA_FINALIZER_ONLY",
                true,
                false,
                false,
                false,
                false,
                "LEGACY",
                "DISABLED"));
    return new Registration(
        environmentId,
        generation,
        "p9act.v1." + discriminator.repeat(32),
        "nonce-" + discriminator.repeat(32),
        discriminator.repeat(64),
        bindings,
        issuedAt,
        expiresAt);
  }

  private static ActivationIdentity identity(Registration registration) {
    return new ActivationIdentity(
        registration.environmentId(),
        registration.environmentGeneration(),
        registration.activationId(),
        registration.manifestHash());
  }

  private static DrainCompletionProof drainProof(
      long unresolved, long replicas, boolean sealed, Instant completedAt) {
    return new DrainCompletionProof(
        unresolved,
        replicas,
        sealed,
        completedAt,
        "a".repeat(64),
        "b".repeat(64),
        "c".repeat(64),
        "d".repeat(64));
  }

  private static Reservation reservation(
      Registration registration, int slot, String caseId) {
    return new Reservation(
        registration.environmentId(),
        registration.environmentGeneration(),
        registration.activationId(),
        slot,
        caseId,
        CASE_PREFIX,
        4,
        FIXTURE_SET_ID,
        FIXTURE_HASH);
  }
}
