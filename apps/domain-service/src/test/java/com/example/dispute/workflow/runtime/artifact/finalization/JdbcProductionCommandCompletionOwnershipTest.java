package com.example.dispute.workflow.runtime.artifact.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.runtime.finalization.ProductionCommandCompletionWriter;
import com.example.dispute.workflow.runtime.finalization.ProductionExecutionLaneVerifier;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceipt;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.CommandCompletion;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewFinalizationAdapter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import javax.sql.DataSource;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Real ledger/pgjdbc transactions prove both orderings without weakening hash conflict checks. */
@Testcontainers
class JdbcProductionCommandCompletionOwnershipTest {
  @TempDir static Path classes;
  private static URLClassLoader loader;
  private static Class<?> writerType;
  private static final String FORMAL_HASH = "f".repeat(64);
  private static final String ADVISORY_HASH = "a".repeat(64);

  @Container
  private static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse(
      "public.ecr.aws/docker/library/postgres@sha256:e013e867e712fec275706a6c51c966f0bb0c93cfa8f51000f85a15f9865a28cb"))
      .withEnv("POSTGRES_USER", "ownership_test")
      .withEnv("POSTGRES_PASSWORD", "isolated_test_password")
      .withEnv("POSTGRES_DB", "ownership_test")
      .withExposedPorts(5432)
      .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

  @BeforeAll static void compileArtifact() throws Exception {
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertThat(compiler).isNotNull();
    assertThat(compiler.run(null, null, null, "-classpath", System.getProperty("java.class.path"),
        "-d", classes.toString(), "src/production-runtime/java/com/example/dispute/workflow/runtime/"
            + "artifact/finalization/JdbcProductionIntakeCommandCompletionWriter.java")).isZero();
    loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
        JdbcProductionCommandCompletionOwnershipTest.class.getClassLoader());
    writerType = loader.loadClass("com.example.dispute.workflow.runtime.artifact.finalization."
        + "JdbcProductionIntakeCommandCompletionWriter");
  }

  @AfterAll static void closeLoader() throws Exception { if (loader != null) loader.close(); }

  @Test void formalCompletionThenAdvisoryAndReplayPreserveTheFormalReceipt() throws Exception {
    try (var fixture = fixture()) {
      fixture.tx.executeWithoutResult(status -> fixture.formalComplete());
      var original = fixture.ledger.queryCommandAdmission("activation", "command").orElseThrow();
      fixture.tx.executeWithoutResult(status -> fixture.writer.complete(request(RoomType.REVIEW), receipt(RoomType.REVIEW)));
      fixture.tx.executeWithoutResult(status -> fixture.writer.complete(request(RoomType.REVIEW), receipt(RoomType.REVIEW)));
      assertThat(fixture.ledger.queryCommandAdmission("activation", "command").orElseThrow()).isEqualTo(original);
      assertThat(original.completionHash()).isEqualTo(FORMAL_HASH).isNotEqualTo(ADVISORY_HASH);
      assertThat(fixture.count()).isEqualTo(1);
    }
  }

  @Test void advisoryBeforeFormalCompletionNeverStealsTheHumanCommand() throws Exception {
    try (var fixture = fixture()) {
      fixture.tx.executeWithoutResult(status -> fixture.writer.complete(request(RoomType.REVIEW), receipt(RoomType.REVIEW)));
      assertThat(fixture.count()).isZero();
      fixture.tx.executeWithoutResult(status -> fixture.formalComplete());
      fixture.tx.executeWithoutResult(status -> fixture.writer.complete(request(RoomType.REVIEW), receipt(RoomType.REVIEW)));
      assertThat(fixture.ledger.queryCommandAdmission("activation", "command").orElseThrow().completionHash())
          .isEqualTo(FORMAL_HASH);
      assertThat(fixture.count()).isEqualTo(1);
    }
  }

  @Test void ordinaryRoomCompletionAndReplayStillWriteTheAgentReceiptAndRejectConflicts() throws Exception {
    try (var fixture = fixture()) {
      fixture.tx.executeWithoutResult(status -> fixture.writer.complete(request(RoomType.INTAKE), receipt(RoomType.INTAKE)));
      fixture.tx.executeWithoutResult(status -> fixture.writer.complete(request(RoomType.INTAKE), receipt(RoomType.INTAKE)));
      assertThat(fixture.ledger.queryCommandAdmission("activation", "command").orElseThrow().completionHash())
          .isEqualTo(ADVISORY_HASH);
      assertThatThrownBy(() -> fixture.tx.executeWithoutResult(status -> fixture.formalComplete()))
          .hasMessageContaining("different durable bindings");
      assertThat(fixture.count()).isEqualTo(1);
    }
  }

  @Test void reviewRequiresExactAdmissionAndMatchingReceiptRoom() throws Exception {
    try (var fixture = fixture()) {
      var request = request(RoomType.REVIEW);
      when(request.command().caseId()).thenReturn("foreign-case");
      assertThatThrownBy(() -> fixture.tx.executeWithoutResult(status ->
          fixture.writer.complete(request, receipt(RoomType.REVIEW))))
          .hasMessageContaining("admitted command identity");
      assertThatThrownBy(() -> fixture.tx.executeWithoutResult(status ->
          fixture.writer.complete(request(RoomType.REVIEW), receipt(RoomType.INTAKE))))
          .isInstanceOf(IllegalStateException.class);
      when(request.command().commandId()).thenReturn("missing-command");
      assertThatThrownBy(() -> fixture.tx.executeWithoutResult(status ->
          fixture.writer.complete(request, receipt(RoomType.REVIEW))))
          .hasMessageContaining("admission is absent");
      assertThat(fixture.count()).isZero();
    }
  }

  @Test void reviewRejectsUnknownOrConflictingGraphPinsBeforeAnyCompletionWrite() throws Exception {
    try (var fixture = fixture()) {
      for (int field = 0; field < 6; field++) {
        var request = request(RoomType.REVIEW);
        var receipt = receipt(RoomType.REVIEW);
        switch (field) {
          case 0 -> when(request.command().graphKey()).thenReturn("foreign-graph");
          case 1 -> when(request.command().graphVersion()).thenReturn("foreign-version");
          case 2 -> when(request.command().checkpointSchemaVersion()).thenReturn("foreign-schema");
          case 3 -> when(receipt.graphKey()).thenReturn("foreign-graph");
          case 4 -> when(receipt.graphVersion()).thenReturn("foreign-version");
          case 5 -> when(receipt.checkpointSchemaVersion()).thenReturn("foreign-schema");
        }
        assertThatThrownBy(() -> fixture.tx.executeWithoutResult(status -> fixture.writer.complete(request, receipt)))
            .isInstanceOf(IllegalStateException.class);
      }
      assertThat(fixture.count()).isZero();
    }
  }

  @Test void callerRollbackAndWritableTransactionBoundaryRemainIntact() throws Exception {
    try (var fixture = fixture()) {
      fixture.tx.executeWithoutResult(status -> {
        fixture.writer.complete(request(RoomType.INTAKE), receipt(RoomType.INTAKE));
        status.setRollbackOnly();
      });
      assertThat(fixture.count()).isZero();
      assertThatThrownBy(() -> fixture.writer.complete(request(RoomType.REVIEW), receipt(RoomType.REVIEW)))
          .hasMessageContaining("active writable Finalizer transaction");
      fixture.tx.setReadOnly(true);
      assertThatThrownBy(() -> fixture.tx.executeWithoutResult(status ->
          fixture.writer.complete(request(RoomType.REVIEW), receipt(RoomType.REVIEW))))
          .hasMessageContaining("active writable Finalizer transaction");
      assertThat(fixture.count()).isZero();
    }
  }

  private static ExecuteAgentRunRequest request(RoomType room) {
    var request = mock(ExecuteAgentRunRequest.class, RETURNS_DEEP_STUBS);
    when(request.command().tenantSurrogate()).thenReturn("tenant");
    when(request.command().caseId()).thenReturn("case");
    when(request.command().commandId()).thenReturn("command");
    when(request.command().roomEpoch()).thenReturn(0L);
    when(request.command().roomType()).thenReturn(room);
    when(request.command().graphKey()).thenReturn(TargetReviewFinalizationAdapter.TARGET_GRAPH_KEY);
    when(request.command().graphVersion()).thenReturn(ProductionExecutionLaneVerifier.GRAPH_VERSION);
    when(request.command().checkpointSchemaVersion()).thenReturn(ProductionExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION);
    return request;
  }

  private static ProductionFinalizationReceipt receipt(RoomType room) {
    var receipt = mock(ProductionFinalizationReceipt.class);
    when(receipt.activationId()).thenReturn("activation");
    when(receipt.tenantSurrogate()).thenReturn("tenant");
    when(receipt.caseId()).thenReturn("case");
    when(receipt.roomType()).thenReturn(room);
    when(receipt.roomEpoch()).thenReturn(0L);
    when(receipt.roomFencingToken()).thenReturn(1L);
    when(receipt.commandHash()).thenReturn("c".repeat(64));
    when(receipt.commandEnvelopeHash()).thenReturn("d".repeat(64));
    when(receipt.receiptHash()).thenReturn(ADVISORY_HASH);
    when(receipt.graphKey()).thenReturn(TargetReviewFinalizationAdapter.TARGET_GRAPH_KEY);
    when(receipt.graphVersion()).thenReturn(ProductionExecutionLaneVerifier.GRAPH_VERSION);
    when(receipt.checkpointSchemaVersion()).thenReturn(ProductionExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION);
    return receipt;
  }

  private static Fixture fixture() throws Exception {
    var connection = DriverManager.getConnection("jdbc:postgresql://" + POSTGRES.getHost() + ":"
        + POSTGRES.getMappedPort(5432) + "/ownership_test", "ownership_test", "isolated_test_password");
    try (var statement = connection.createStatement()) {
      statement.execute("""
          create temporary table production_runtime_command_admission (
            admission_id text primary key, activation_id text, activation_manifest_hash text,
            isolated_domain_db_binding_hash text, tenant_surrogate text, case_id text,
            command_id text, command_hash text, command_envelope_hash text, room_epoch bigint,
            room_fencing_token bigint, admitted_at timestamptz default clock_timestamp())
          """);
      statement.execute("""
          create temporary table production_runtime_command_completion (
            admission_id text primary key, activation_id text, command_id text, command_hash text,
            command_envelope_hash text, completion_hash text, completed_at timestamptz default clock_timestamp())
          """);
      statement.execute("""
          insert into production_runtime_command_admission
            (admission_id, activation_id, activation_manifest_hash, isolated_domain_db_binding_hash,
             tenant_surrogate, case_id, command_id, command_hash, command_envelope_hash, room_epoch, room_fencing_token)
          values ('admission', 'activation', repeat('e',64), repeat('b',64), 'tenant', 'case',
            'command', repeat('c',64), repeat('d',64), 0, 1)
          """);
    }
    var source = new SingleConnectionDataSource(connection, true);
    var ledger = new ProductionActivationLedger(source, Clock.systemUTC());
    var writer = (ProductionCommandCompletionWriter) writerType
        .getConstructor(DataSource.class, ProductionActivationLedger.class).newInstance(source, ledger);
    return new Fixture(connection, ledger, writer, new TransactionTemplate(new DataSourceTransactionManager(source)));
  }

  private record Fixture(Connection connection, ProductionActivationLedger ledger,
      ProductionCommandCompletionWriter writer, TransactionTemplate tx) implements AutoCloseable {
    void formalComplete() {
      ledger.completeCommand(connection, new CommandCompletion("admission", "activation", "command",
          "c".repeat(64), "d".repeat(64), FORMAL_HASH));
    }
    long count() throws Exception {
      try (var statement = connection.createStatement();
          var rows = statement.executeQuery("select count(*) from production_runtime_command_completion")) {
        rows.next(); return rows.getLong(1);
      }
    }
    @Override public void close() throws Exception { connection.close(); }
  }
}
