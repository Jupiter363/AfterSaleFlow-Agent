package com.example.dispute.workflow.targete2e.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TargetE2eGraphOutputSnapshotMaterializerTest {

    private final TargetE2eFinalizationFixture.Fixture fixture = TargetE2eFinalizationFixture.valid();
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:target_output_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table agent_run (id varchar(128) primary key)");
        jdbc.update("insert into agent_run (id) values (?)", fixture.request().agentRunId());
        jdbc.execute("""
                create table case_intake_graph_thread_binding (
                    registration_id varchar(128) primary key,
                    tenant_surrogate varchar(128) not null,
                    case_id varchar(64) not null,
                    room_type varchar(32) not null,
                    room_epoch bigint not null,
                    thread_id varchar(128) not null,
                    actor_id varchar(128) not null,
                    actor_role varchar(32) not null,
                    audience varchar(32) not null,
                    actor_scope_hash varchar(64) not null,
                    graph_key varchar(128) not null,
                    graph_version varchar(128) not null,
                    checkpoint_schema_version varchar(128) not null,
                    registration_status varchar(24) not null,
                    registered_at timestamp with time zone
                )
                """);
        jdbc.execute("""
                create table immutable_payload_snapshot (
                    id varchar(64) primary key,
                    tenant_surrogate varchar(128) not null,
                    case_id varchar(64) not null,
                    room_type varchar(32), snapshot_type varchar(64) not null,
                    source_type varchar(64) not null, source_id varchar(128) not null,
                    schema_version varchar(128) not null, object_uri varchar(1024) not null,
                    object_version varchar(128), content_sha256 varchar(64) not null,
                    size_bytes bigint not null, content_type varchar(128), visibility varchar(32) not null,
                    legal_hold boolean not null, created_at timestamp with time zone not null,
                    created_by varchar(128) not null,
                    unique (tenant_surrogate, source_type, source_id)
                )
                """);
        insertBinding("PENDING");
    }

    @Test
    void materializesDurableFinalBeforeTheFinalizerWithoutAPreseededOutputRow() {
        String resultRef = "minio://target-e2e-intake-activation/results/" + fixture.result().resultHash();
        var materializer = materializer(resultRef, fixture.result().resultHash());
        AtomicInteger finalizerCalls = new AtomicInteger();

        String result = materializer.materializeThen(
                fixture.request(),
                fixture.result(),
                () -> {
                    finalizerCalls.incrementAndGet();
                    assertThat(countSnapshots()).isEqualTo(1);
                    return "committed";
                });

        assertThat(result).isEqualTo("committed");
        assertThat(finalizerCalls).hasValue(1);
        assertThat(jdbc.queryForObject(
                        "select object_uri from immutable_payload_snapshot", String.class))
                .isEqualTo(resultRef);
        assertThat(jdbc.queryForObject(
                        "select content_sha256 from immutable_payload_snapshot", String.class))
                .isEqualTo(fixture.result().resultHash());
        assertThat(registrationStatus()).isEqualTo("REGISTERED");
        OffsetDateTime registeredAt = registeredAt();
        assertThat(registeredAt).isNotNull();

        materializer.materializeThen(fixture.request(), fixture.result(), () -> "replayed");
        assertThat(countSnapshots()).isEqualTo(1);
        assertThat(registrationStatus()).isEqualTo("REGISTERED");
        assertThat(registeredAt()).isEqualTo(registeredAt);
    }

    @Test
    void rejectsDriftForTheSameAgentRun() {
        String resultRef = "urn:target-e2e:result:" + fixture.result().resultHash();
        materializer(resultRef, fixture.result().resultHash())
                .materializeThen(fixture.request(), fixture.result(), () -> "first");

        assertThatThrownBy(() -> materializer(
                        "urn:target-e2e:result:other",
                        fixture.result().resultHash())
                .materializeThen(fixture.request(), fixture.result(), () -> "drift"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts with replay");
    }

    @Test
    void rejectsFailedOrRetiredBindingsAndRollsBackTheOutputSnapshot() {
        jdbc.update(
                "update case_intake_graph_thread_binding set registration_status = 'FAILED' where registration_id = ?",
                registrationId());

        assertThatThrownBy(() -> materializer(
                        "urn:target-e2e:result:" + fixture.result().resultHash(),
                        fixture.result().resultHash())
                .materializeThen(fixture.request(), fixture.result(), () -> "unexpected"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not pending for final registration");

        assertThat(countSnapshots()).isZero();
        assertThat(registrationStatus()).isEqualTo("FAILED");
    }

    private TargetE2eGraphOutputSnapshotMaterializer materializer(
            String resultRef, String resultHash) {
        AgentStreamEvent terminal = new AgentStreamEvent(
                "agent-stream.v3",
                fixture.request().agentRunId(),
                fixture.request().attemptId(),
                fixture.result().lastSequenceNo(),
                StreamEventType.FINAL,
                fixture.request().command().actorScope().audience(),
                Instant.now(),
                new AgentStreamEvent.Payload(
                        null, null, null, null, null, null, resultRef, resultHash, null, null));
        AgentRunV2StreamStore streamStore = new AgentRunV2StreamStore() {
            @Override
            public AppendReceipt append(AgentStreamEvent event) {
                throw new UnsupportedOperationException();
            }

            @Override
            public BatchAppendReceipt appendBatch(List<AgentStreamEvent> events) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<AgentStreamEvent> replay(
                    String runId, String attemptId, long afterSequence, int limit) {
                assertThat(afterSequence).isEqualTo(fixture.result().lastSequenceNo() - 1);
                assertThat(limit).isEqualTo(2);
                return List.of(terminal);
            }
        };
        return new TargetE2eGraphOutputSnapshotMaterializer(
                dataSource, streamStore, new DataSourceTransactionManager(dataSource));
    }

    private long countSnapshots() {
        return jdbc.queryForObject("select count(*) from immutable_payload_snapshot", Long.class);
    }

    private void insertBinding(String status) {
        var command = fixture.request().command();
        var scope = new IntakePrivateThreadRegistration.ActorScope(
                command.actorScope().actorId(),
                command.actorScope().actorRole(),
                command.actorScope().audience(),
                command.actorScope().capabilities());
        jdbc.update("""
                insert into case_intake_graph_thread_binding (
                    registration_id, tenant_surrogate, case_id, room_type, room_epoch,
                    thread_id, actor_id, actor_role, audience, actor_scope_hash,
                    graph_key, graph_version, checkpoint_schema_version,
                    registration_status, registered_at
                ) values (?, ?, ?, 'INTAKE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null)
                """,
                registrationId(),
                command.tenantSurrogate(),
                command.caseId(),
                command.roomEpoch(),
                command.threadId(),
                command.actorScope().actorId(),
                command.actorScope().actorRole().name(),
                command.actorScope().audience().name(),
                IntakeContractHashes.actorScopeHash(scope),
                command.graphKey(),
                command.graphVersion(),
                command.checkpointSchemaVersion(),
                status);
    }

    private String registrationId() {
        return "target-registration-" + fixture.request().command().threadId();
    }

    private String registrationStatus() {
        return jdbc.queryForObject(
                "select registration_status from case_intake_graph_thread_binding where registration_id = ?",
                String.class,
                registrationId());
    }

    private OffsetDateTime registeredAt() {
        return jdbc.queryForObject(
                "select registered_at from case_intake_graph_thread_binding where registration_id = ?",
                OffsetDateTime.class,
                registrationId());
    }
}
