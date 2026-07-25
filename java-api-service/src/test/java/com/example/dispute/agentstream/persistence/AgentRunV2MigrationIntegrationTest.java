package com.example.dispute.agentstream.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class AgentRunV2MigrationIntegrationTest {

    private static final String DATABASE_NAME = "agent_run_v2_migration";
    private static final String USERNAME = "dispute_test";
    private static final String PASSWORD = "local_test_password";

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", DATABASE_NAME)
                    .withEnv("POSTGRES_USER", USERNAME)
                    .withEnv("POSTGRES_PASSWORD", PASSWORD)
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forListeningPort());

    @Test
    void expandsAndBackfillsLegacyRowsWithoutRewritingTheirManifest() throws SQLException {
        String jdbcUrl =
                "jdbc:postgresql://"
                        + POSTGRESQL.getHost()
                        + ':'
                        + POSTGRESQL.getMappedPort(5432)
                        + '/'
                        + DATABASE_NAME;
        Flyway beforeV041 =
                Flyway.configure()
                        .dataSource(jdbcUrl, USERNAME, PASSWORD)
                        .locations("classpath:db/migration")
                        .target("40.4")
                        .load();
        beforeV041.migrate();

        String manifestBefore;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD)) {
            insertLegacyFixture(connection);
            manifestBefore = scalar(connection, "select to_jsonb(manifest)::text from agent_execution_manifest manifest where id = 'MANIFEST_LEGACY_V2'");
        }

        Flyway latest =
                Flyway.configure()
                        .dataSource(jdbcUrl, USERNAME, PASSWORD)
                        .locations("classpath:db/migration")
                        .load();
        assertThat(latest.migrate().migrationsExecuted).isPositive();
        assertThat(latest.migrate().migrationsExecuted).isZero();

        try (Connection connection = DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD)) {
            assertThat(
                            scalar(
                                    connection,
                                    """
                                    select protocol || ':' || logical_idempotency_key || ':' ||
                                           finalization_status || ':' || committed_attempt_id
                                    from agent_run where id = 'RUN_LEGACY_V2'
                                    """))
                    .isEqualTo("agent_stream.v1:legacy-stream-key:LEGACY_COMMITTED:RUN_LEGACY_V2");
            assertThat(
                            scalar(
                                    connection,
                                    """
                                    select id || ':' || attempt_no || ':' || attempt_status || ':' ||
                                           executor_kind || ':' || coalesce(total_tokens, 0)
                                    from agent_run_attempt where agent_run_id = 'RUN_LEGACY_V2'
                                    """))
                    .isEqualTo("RUN_LEGACY_V2:1:COMPLETED:LEGACY_WORKER:21");
            assertThat(
                            scalar(
                                    connection,
                                    """
                                    select agent_run_attempt_id || ':' || stream_protocol
                                    from agent_run_stream_event where id = 'EVENT_LEGACY_V2'
                                    """))
                    .isEqualTo("RUN_LEGACY_V2:agent_stream.v1");
            assertThat(
                            scalar(
                                    connection,
                                    "select to_jsonb(manifest)::text from agent_execution_manifest manifest where id = 'MANIFEST_LEGACY_V2'"))
                    .isEqualTo(manifestBefore);
            assertThat(
                            scalar(
                                    connection,
                                    "select coalesce(lineage_schema_version, 'legacy') from agent_run_attempt where id = 'RUN_LEGACY_V2'"))
                    .isEqualTo("legacy");

            assertRollbackV1WriterCompatibility(connection);
            assertV2Uniqueness(connection);
            assertLineageConstraints(connection);
            assertV046AdditiveDeliverySchema(connection);
        }
    }

    private static void insertLegacyFixture(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into fulfillment_dispute_case (
                        id, user_id, merchant_id, creation_idempotency_key,
                        case_type, case_status, initiator_role, initiator_id,
                        respondent_role, respondent_id, risk_level,
                        title, description, current_room, created_by, updated_by
                    ) values (
                        'CASE_LEGACY_V2', 'user-v2', 'merchant-v2', 'legacy-v2-idem',
                        'DISPUTE', 'EVIDENCE_OPEN', 'USER', 'user-v2',
                        'MERCHANT', 'merchant-v2', 'MEDIUM', 'Legacy V2 migration',
                        'Legacy AgentRun row', 'EVIDENCE', 'migration-test', 'migration-test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into agent_run (
                        id, case_id, agent_id, agent_role, profile_version,
                        prompt_version, skill_version, ruleset_version, model,
                        run_status, token_usage, started_at, completed_at,
                        trace_id, created_by, stream_request_hash,
                        stream_result_json, stream_idempotency_key, updated_at
                    ) values (
                        'RUN_LEGACY_V2', 'CASE_LEGACY_V2', 'legacy-agent', 'SYSTEM',
                        'legacy-profile', 'legacy-prompt', 'legacy-skill', 'legacy-rules',
                        'legacy-model', 'COMPLETED', 21, now() - interval '1 second', now(),
                        'legacy-trace', 'migration-test', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        '{"status":"completed"}', 'legacy-stream-key', now()
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into agent_run_stream_event (
                        id, agent_run_id, sequence_no, event_type, payload_json, created_by
                    ) values (
                        'EVENT_LEGACY_V2', 'RUN_LEGACY_V2', 0, 'visible_delta',
                        '{"delta":"legacy"}', 'migration-test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into immutable_payload_snapshot (
                        id, tenant_surrogate, case_id, room_type, snapshot_type,
                        source_type, source_id, schema_version, object_uri,
                        content_sha256, size_bytes, visibility, created_by
                    ) values (
                        'SNAP_LEGACY_V2', 'legacy-default', 'CASE_LEGACY_V2', 'HEARING',
                        'AGENT_OUTPUT', 'TEST', 'RUN_LEGACY_V2', 'legacy-output.v1',
                        'urn:test:legacy-output',
                        'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                        1, 'INTERNAL', 'migration-test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into agent_execution_manifest (
                        id, schema_version, tenant_surrogate, case_id, room_type,
                        room_epoch, process_revision, fencing_token,
                        logical_agent_run_id, attempt_id, manifest_uri,
                        input_snapshot_refs_json, output_snapshot_id, output_sha256,
                        terminal_status, finalized_at
                    ) values (
                        'MANIFEST_LEGACY_V2', 'agent-execution-manifest.legacy-ref.v1',
                        'legacy-default', 'CASE_LEGACY_V2', 'HEARING', 0, 0, 0,
                        'RUN_LEGACY_V2', 'RUN_LEGACY_V2', 'urn:legacy:RUN_LEGACY_V2',
                        '[]', 'SNAP_LEGACY_V2',
                        'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                        'LEGACY_IMPORTED', now()
                    )
                    """);
        }
    }

    private static void assertRollbackV1WriterCompatibility(Connection connection)
            throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into agent_run (
                        id, case_id, agent_id, agent_role, profile_version,
                        prompt_version, skill_version, ruleset_version, run_status,
                        started_at, trace_id, created_by, stream_operation,
                        stream_idempotency_key, updated_at
                    ) values (
                        'RUN_ROLLBACK_V1', 'CASE_LEGACY_V2', 'rollback-agent', 'SYSTEM',
                        'v1', 'v1', 'v1', 'agent_stream.v1', 'PENDING', now(),
                        'rollback-trace', 'rollback-writer', 'EVIDENCE_ANALYZE',
                        'rollback-v1-key', now()
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into agent_run_stream_event (
                        id, agent_run_id, sequence_no, event_type, payload_json, created_by
                    ) values (
                        'EVENT_ROLLBACK_V1', 'RUN_ROLLBACK_V1', 0, 'start',
                        '{"status":"started"}', 'rollback-writer'
                    )
                    """);
            statement.executeUpdate(
                    """
                    update agent_run
                    set run_status = 'COMPLETED',
                        stream_result_json = '{"status":"completed"}',
                        completed_at = now(),
                        updated_at = now()
                    where id = 'RUN_ROLLBACK_V1'
                    """);
        }

        assertThat(
                        scalar(
                                connection,
                                """
                                select run.logical_idempotency_key || ':' || attempt.id || ':' ||
                                       attempt.attempt_status || ':' || run.finalization_status
                                from agent_run run
                                join agent_run_attempt attempt on attempt.agent_run_id = run.id
                                where run.id = 'RUN_ROLLBACK_V1'
                                """))
                .isEqualTo("rollback-v1-key:RUN_ROLLBACK_V1:COMPLETED:LEGACY_COMMITTED");
        assertThat(
                        scalar(
                                connection,
                                """
                                select agent_run_attempt_id || ':' || stream_protocol
                                from agent_run_stream_event where id = 'EVENT_ROLLBACK_V1'
                                """))
                .isEqualTo("RUN_ROLLBACK_V1:agent_stream.v1");
    }

    private static void assertV2Uniqueness(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into agent_run (
                        id, case_id, agent_id, agent_role, profile_version,
                        prompt_version, skill_version, ruleset_version, run_status,
                        started_at, trace_id, created_by, stream_operation,
                        stream_idempotency_key, tenant_surrogate, protocol,
                        logical_idempotency_key, executor_kind, finalization_status,
                        room_epoch_id, room_type, room_epoch, process_revision,
                        fencing_token, request_hash, attempt_limit, deadline_at
                    ) values (
                        'RUN_DB_V2', 'CASE_LEGACY_V2', 'agent-v2', 'SYSTEM', 'v2',
                        'v2', 'v2', 'agent-stream.v2', 'RUNNING', now(), 'trace-v2',
                        'migration-test', 'EVIDENCE_ANALYZE', 'db-v2-key',
                        'tenant-v2', 'agent-stream.v2', 'db-v2-key',
                        'TEMPORAL_ACTIVITY', 'UNCOMMITTED', 'EPOCH_DB_V2', 'EVIDENCE',
                        1, 1, 1,
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        2, now() + interval '10 minutes'
                    )
                    """);
            statement.executeUpdate(attemptInsert("ATTEMPT_DB_V2_1", 1));
            statement.executeUpdate(attemptInsert("ATTEMPT_DB_V2_2", 2));
            statement.executeUpdate(eventInsert("EVENT_DB_V2_1", "ATTEMPT_DB_V2_1", 0));
            statement.executeUpdate(eventInsert("EVENT_DB_V2_2", "ATTEMPT_DB_V2_2", 0));
        }

        assertUniqueViolation(
                connection,
                attemptInsert("ATTEMPT_DB_V2_DUPLICATE", 2),
                "uq_agent_run_attempt_number");
        assertUniqueViolation(
                connection,
                eventInsert("EVENT_DB_V2_DUPLICATE", "ATTEMPT_DB_V2_2", 0),
                "uq_agent_run_stream_event_attempt_sequence_v2");
        assertUniqueViolation(
                connection,
                """
                insert into agent_run (
                    id, case_id, agent_id, agent_role, profile_version,
                    prompt_version, skill_version, ruleset_version, run_status,
                    started_at, trace_id, created_by, logical_idempotency_key
                ) values (
                    'RUN_DB_LOGICAL_DUP', 'CASE_LEGACY_V2', 'legacy-agent', 'SYSTEM',
                    'v1', 'v1', 'v1', 'v1', 'PENDING', now(), 'trace-duplicate',
                    'migration-test', 'db-v2-key'
                )
                """,
                "uq_agent_run_logical_idempotency_v2");
        assertUniqueViolation(
                connection,
                """
                insert into agent_run_stream_event (
                    id, agent_run_id, sequence_no, event_type, payload_json, created_by
                ) values (
                    'EVENT_LEGACY_V2_DUP', 'RUN_LEGACY_V2', 0, 'visible_delta',
                    '{"delta":"duplicate"}', 'migration-test'
                )
                """,
                "uq_agent_run_stream_event_sequence");
    }

    private static String attemptInsert(String id, long attemptNo) {
        return """
                insert into agent_run_attempt (
                    id, agent_run_id, attempt_no, attempt_status, executor_kind,
                    graph_key, graph_version, checkpoint_schema_version,
                    model_profile_id, prompt_version, output_schema_version,
                    policy_version, guardrail_version, request_hash,
                    started_at, created_by
                ) values (
                    '%s', 'RUN_DB_V2', %d, 'RUNNING', 'TEMPORAL_ACTIVITY',
                    'evidence.graph', 'graph-v2', 'checkpoint-v2',
                    'model-v2', 'prompt-v2', 'result-v1', 'policy-v2', 'guardrail-v2',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    now(), 'migration-test'
                )
                """.formatted(id, attemptNo);
    }

    private static String eventInsert(String id, String attemptId, long sequenceNo) {
        return """
                insert into agent_run_stream_event (
                    id, agent_run_id, agent_run_attempt_id, sequence_no,
                    event_type, payload_json, stream_protocol, audience,
                    payload_hash, created_by
                ) values (
                    '%s', 'RUN_DB_V2', '%s', %d, 'attempt_started',
                    '{"attempt":1}', 'agent-stream.v2', 'USER',
                    'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                    'migration-test'
                )
                """.formatted(id, attemptId, sequenceNo);
    }

    private static void assertLineageConstraints(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    update agent_run
                    set lineage_schema_version = 'agent-run-lineage.v1',
                        logical_input_hash = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
                    where id = 'RUN_DB_V2'
                    """);
            statement.executeUpdate(
                    lineageAttemptInsert(
                            "ATTEMPT_DB_V2_3",
                            3,
                            "COMMAND_DB_V2_3",
                            "ATTEMPT_DB_V2_2",
                            true,
                            1));
        }

        assertUniqueViolation(
                connection,
                lineageAttemptInsert(
                        "ATTEMPT_DB_V2_4_DUP_COMMAND",
                        4,
                        "COMMAND_DB_V2_3",
                        "ATTEMPT_DB_V2_3",
                        false,
                        0),
                "uq_agent_run_attempt_command");
        assertCheckViolation(
                connection,
                lineageAttemptInsert(
                        "ATTEMPT_DB_V2_4_BAD_OFFSET",
                        4,
                        "COMMAND_DB_V2_4",
                        "ATTEMPT_DB_V2_3",
                        true,
                        0),
                "ck_agent_run_attempt_sequence_offset");
    }

    private static void assertV046AdditiveDeliverySchema(Connection connection)
            throws SQLException {
        assertThat(
                        scalar(
                                connection,
                                """
                                select string_agg(relname || ':' || relkind, ',' order by relname)
                                  from pg_class
                                 where relname in (
                                     'agent_run_stream_event',
                                     'agent_run_stream_event_identity',
                                     'agent_run_stream_event_delivery'
                                 )
                                """))
                .isEqualTo(
                        "agent_run_stream_event:r,agent_run_stream_event_delivery:p,"
                                + "agent_run_stream_event_identity:r");
        assertThat(
                        scalar(
                                connection,
                                """
                                select count(*)
                                  from pg_class child
                                  join pg_inherits inheritance
                                    on inheritance.inhrelid = child.oid
                                  join pg_class parent
                                    on parent.oid = inheritance.inhparent
                                 where parent.relname = 'agent_run_stream_event_delivery'
                                   and pg_get_expr(child.relpartbound, child.oid) = 'DEFAULT'
                                """))
                .isEqualTo("1");
        assertThat(
                        scalar(
                                connection,
                                """
                                select count(*)
                                  from pg_class
                                 where relkind = 'r'
                                   and relname in (
                                       'agent_run_stream_delivery_high_watermark',
                                       'agent_run_stream_backfill_cursor',
                                       'agent_run_stream_archive_manifest',
                                       'agent_run_stream_archive_receipt',
                                       'agent_run_stream_migration_receipt'
                                   )
                                """))
                .isEqualTo("5");
        assertThat(
                        scalar(
                                connection,
                                "select count(*) from agent_run_stream_event where agent_run_id = 'RUN_LEGACY_V2'"))
                .isEqualTo("1");

        assertThat(scalar(connection, legacyBackfillInsert()))
                .isEqualTo("true:0");
        assertThat(scalar(connection, deliveryInsert("TARGET_EVENT_2", 2, '2')))
                .isEqualTo("true:0");
        assertThat(scalar(connection, deliveryInsert("TARGET_EVENT_1", 1, '1')))
                .isEqualTo("true:2");
        assertThat(scalar(connection, deliveryInsert("TARGET_EVENT_1", 1, '1')))
                .isEqualTo("false:2");
        assertThat(scalar(connection, v2BackfillInsert()))
                .isEqualTo("true:0");
        assertThat(
                        scalar(
                                connection,
                                """
                                select highest_contiguous_sequence_no || ':' || watermark_version
                                  from agent_run_stream_delivery_high_watermark
                                 where agent_run_id = 'RUN_LEGACY_V2'
                                   and agent_run_attempt_id = 'RUN_LEGACY_V2'
                                """))
                .isEqualTo("2:2");

        assertCheckViolation(
                connection,
                deliveryInsert("TARGET_EVENT_1", 1, 'f'),
                "stream event identity or canonical payload hash conflicts");
        assertCheckViolation(
                connection,
                v2DeliveryInsert("EVENT_LEGACY_V2", 0, '0'),
                "stream event identity or canonical payload hash conflicts");
        assertUniqueViolation(
                connection,
                deliveryInsert("TARGET_EVENT_DUPLICATE_IDENTITY", 1, '1'),
                "uq_stream_event_identity_sequence");
        assertCheckViolation(
                connection,
                """
                update agent_run_stream_delivery_high_watermark
                   set highest_contiguous_sequence_no = 1,
                       highest_event_id = 'TARGET_EVENT_1',
                       highest_event_recorded_at = (
                           select recorded_at from agent_run_stream_event_identity
                            where event_id = 'TARGET_EVENT_1'
                       )
                 where agent_run_id = 'RUN_LEGACY_V2'
                   and agent_run_attempt_id = 'RUN_LEGACY_V2'
                """,
                "delivery high-watermark cannot regress");
        assertSqlState(
                connection,
                "update agent_run_stream_event_delivery_default set event_type = 'error' where event_id = 'TARGET_EVENT_1'",
                "55000",
                "agent_run_stream_event_delivery_default is append-only");

        try (var statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into agent_run_stream_backfill_cursor (
                        backfill_id, source_upper_bound_created_at,
                        source_upper_bound_event_id, created_by
                    )
                    select 'BACKFILL_V046_MISSING', created_at, id, 'migration-test'
                      from agent_run_stream_event
                     where id = 'EVENT_ROLLBACK_V1'
                    """);
        }
        assertCheckViolation(
                connection,
                """
                update agent_run_stream_backfill_cursor cursor
                   set last_source_created_at = source_event.created_at,
                       last_source_event_id = source_event.id,
                       processed_count = (
                           select count(*) from agent_run_stream_event candidate
                            where (candidate.created_at, candidate.id)
                                <= (source_event.created_at, source_event.id)
                       ),
                       cursor_status = 'RUNNING'
                  from agent_run_stream_event source_event
                 where cursor.backfill_id = 'BACKFILL_V046_MISSING'
                   and source_event.id = 'EVENT_ROLLBACK_V1'
                """,
                "backfill cursor requires matching immutable target delivery rows");

        try (var statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into agent_run_stream_backfill_cursor (
                        backfill_id, source_upper_bound_created_at,
                        source_upper_bound_event_id, created_by
                    )
                    select 'BACKFILL_V046', created_at, id, 'migration-test'
                      from agent_run_stream_event
                     where id = 'EVENT_LEGACY_V2'
                    """);
            statement.executeUpdate(
                    """
                    update agent_run_stream_backfill_cursor cursor
                       set last_source_created_at = source_event.created_at,
                           last_source_event_id = source_event.id,
                           processed_count = 1,
                           cursor_status = 'RUNNING'
                      from agent_run_stream_event source_event
                     where cursor.backfill_id = 'BACKFILL_V046'
                       and source_event.id = 'EVENT_LEGACY_V2'
                    """);
        }
        assertCheckViolation(
                connection,
                """
                update agent_run_stream_backfill_cursor
                   set last_source_created_at = '2000-01-01T00:00:00Z',
                       last_source_event_id = 'EVENT_A'
                 where backfill_id = 'BACKFILL_V046'
                """,
                "backfill cursor cannot regress");

        insertV046Receipts(connection);
        assertSqlState(
                connection,
                "update agent_run_stream_archive_manifest set event_count = 2 where manifest_id = 'ARCHIVE_V046'",
                "55000",
                "agent_run_stream_archive_manifest is append-only");
        assertSqlState(
                connection,
                "update agent_run_stream_archive_receipt set receipt_status = 'FAILED' where receipt_id = 'ARCHIVE_RECEIPT_V046'",
                "55000",
                "agent_run_stream_archive_receipt is append-only");
        assertSqlState(
                connection,
                "update agent_run_stream_migration_receipt set acceptance_status = 'ACCEPTED' where receipt_id = 'MIGRATION_RECEIPT_V046'",
                "55000",
                "agent_run_stream_migration_receipt is append-only");
        assertSqlState(
                connection,
                "truncate agent_run_stream_event_delivery_default",
                "55000",
                "agent_run_stream_event_delivery_default is append-only");
    }

    private static String legacyBackfillInsert() {
        return """
                select delivery.was_inserted::text || ':' ||
                       delivery.highest_contiguous_sequence_no
                  from agent_run_stream_event source_event
                  cross join lateral record_agent_run_stream_delivery(
                      source_event.id, source_event.stream_protocol,
                      source_event.agent_run_id, source_event.agent_run_attempt_id,
                      source_event.sequence_no, source_event.event_type,
                      source_event.payload_json, repeat('0', 64),
                      source_event.audience, null, '[]'::jsonb,
                      source_event.created_at, 'agent_run_stream_event',
                      'migration-test'
                  ) delivery
                 where source_event.id = 'EVENT_LEGACY_V2'
                """;
    }

    private static String deliveryInsert(String eventId, long sequenceNo, char hashCharacter) {
        String hash = String.valueOf(hashCharacter).repeat(64);
        return """
                select was_inserted::text || ':' || highest_contiguous_sequence_no
                  from record_agent_run_stream_delivery(
                      '%s', 'agent_stream.v1', 'RUN_LEGACY_V2', 'RUN_LEGACY_V2',
                      %d, 'visible_delta', '{"sequence":%d}'::jsonb, '%s',
                      'USER', 'user-v2', '["user-v2"]'::jsonb,
                      '2026-01-01T00:00:00Z'::timestamptz + interval '%d seconds',
                      'agent_run_stream_event', 'migration-test'
                  )
                """
                .formatted(eventId, sequenceNo, sequenceNo, hash, sequenceNo);
    }

    private static String v2DeliveryInsert(
            String eventId, long sequenceNo, char hashCharacter) {
        String hash = String.valueOf(hashCharacter).repeat(64);
        return """
                select was_inserted::text || ':' || highest_contiguous_sequence_no
                  from record_agent_run_stream_delivery(
                      '%s', 'agent-stream.v2', 'RUN_DB_V2', 'ATTEMPT_DB_V2_1',
                      %d, 'attempt_started', '{"sequence":%d}'::jsonb, '%s',
                      'USER', 'user-v2', '["user-v2"]'::jsonb,
                      '2026-01-01T00:00:00Z'::timestamptz + interval '%d seconds',
                      'DUAL_WRITE', 'migration-test'
                  )
                """
                .formatted(eventId, sequenceNo, sequenceNo, hash, sequenceNo);
    }

    private static String v2BackfillInsert() {
        return """
                select delivery.was_inserted::text || ':' ||
                       delivery.highest_contiguous_sequence_no
                  from agent_run_stream_event source_event
                  cross join lateral record_agent_run_stream_delivery(
                      source_event.id, source_event.stream_protocol,
                      source_event.agent_run_id, source_event.agent_run_attempt_id,
                      source_event.sequence_no, source_event.event_type,
                      source_event.payload_json, source_event.payload_hash,
                      source_event.audience, null, '[]'::jsonb,
                      source_event.created_at, 'agent_run_stream_event',
                      'migration-test'
                  ) delivery
                 where source_event.id = 'EVENT_DB_V2_1'
                """;
    }

    private static void insertV046Receipts(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into agent_run_stream_archive_manifest (
                        manifest_id, manifest_sha256, target_partition_name,
                        partition_range_start, partition_range_end, stream_protocol,
                        agent_run_id, agent_run_attempt_id, first_sequence_no,
                        last_sequence_no, event_count, canonical_events_sha256,
                        object_uri, object_version, object_sha256, created_by
                    ) values (
                        'ARCHIVE_V046', repeat('a', 64),
                        'agent_run_stream_event_delivery_default',
                        '2025-01-01T00:00:00Z', '2099-01-01T00:00:00Z',
                        'agent_stream.v1', 'RUN_LEGACY_V2', 'RUN_LEGACY_V2',
                        0, 2, 3, repeat('b', 64), 's3://archive/v046',
                        'version-1', repeat('c', 64), 'migration-test'
                    )
                    """);
        }
        assertCheckViolation(
                connection,
                archiveReceiptInsert("ARCHIVE_RECEIPT_V046_EMPTY_EVIDENCE", false),
                "ck_stream_archive_receipt_verified_evidence");
        assertCheckViolation(
                connection,
                """
                insert into agent_run_stream_migration_receipt (
                    receipt_id, candidate_sha, deployment_manifest_sha256,
                    step_id, attempt_id, operator_identity,
                    authorization_reference, started_at, ended_at, exit_status,
                    source_event_count, target_event_count,
                    source_canonical_sha256, target_canonical_sha256,
                    delivery_high_watermark, receipt_sha256
                ) values (
                    'MIGRATION_RECEIPT_V046_EMPTY_EVIDENCE', repeat('a', 40),
                    repeat('b', 64), 'PARITY_VALIDATE', 'attempt-v046-invalid',
                    'migration-test', 'urn:test:authorization:v046',
                    '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z',
                    'SUCCEEDED', 0, 0, repeat('a', 64), repeat('a', 64),
                    -1, repeat('f', 64)
                )
                """,
                "ck_stream_migration_receipt_success_parity");

        try (var statement = connection.createStatement()) {
            statement.executeUpdate(archiveReceiptInsert("ARCHIVE_RECEIPT_V046", true));
            statement.executeUpdate(
                    """
                    insert into agent_run_stream_migration_receipt (
                        receipt_id, candidate_sha, deployment_manifest_sha256,
                        step_id, attempt_id, operator_identity,
                        authorization_reference, started_at, ended_at, exit_status,
                        source_event_count, target_event_count,
                        source_canonical_sha256, target_canonical_sha256,
                        delivery_high_watermark, receipt_sha256
                    ) values (
                        'MIGRATION_RECEIPT_V046', repeat('a', 40), repeat('b', 64),
                        'EXPAND', 'attempt-v046', 'migration-test',
                        'urn:test:authorization:v046', '2026-01-01T00:00:00Z',
                        '2026-01-01T00:00:01Z', 'SUCCEEDED', 1, 3,
                        repeat('c', 64), repeat('d', 64), 2, repeat('e', 64)
                    )
                    """);
        }
        assertThat(
                        scalar(
                                connection,
                                """
                                select acceptance_status || ':' || authority_scope || ':' ||
                                       formal_business_authority
                                  from agent_run_stream_migration_receipt
                                 where receipt_id = 'MIGRATION_RECEIPT_V046'
                                """))
                .isEqualTo("PENDING_EXTERNAL:DELIVERY_STORAGE_ONLY:false");
    }

    private static String archiveReceiptInsert(String receiptId, boolean withEvidence) {
        String evidenceColumns =
                withEvidence
                        ? ", sequence_identity_validation_json, audience_cursor_validation_json"
                        : "";
        String evidenceValues =
                withEvidence
                        ? """
                        , '{"schema_version":"agent-stream-sequence-identity-validation.v1","status":"PASS","sequence_contiguous":true,"event_identity_exact":true}'::jsonb,
                          '{"schema_version":"agent-stream-audience-cursor-validation.v1","status":"PASS","audience_parity":true,"actor_id_parity":true,"cursor_parity":true}'::jsonb
                        """
                        : "";
        return """
                insert into agent_run_stream_archive_receipt (
                    receipt_id, receipt_sha256, manifest_id, manifest_sha256,
                    target_partition_name, stream_protocol, agent_run_id,
                    agent_run_attempt_id, first_sequence_no, last_sequence_no,
                    event_count, canonical_events_sha256, object_version,
                    object_sha256, object_readback_sha256,
                    delivery_high_watermark, hot_retention_started_at,
                    hot_retention_eligible_at, receipt_status, verified_at, verified_by%s
                ) values (
                    '%s', repeat('d', 64), 'ARCHIVE_V046', repeat('a', 64),
                    'agent_run_stream_event_delivery_default', 'agent_stream.v1',
                    'RUN_LEGACY_V2', 'RUN_LEGACY_V2', 0, 2, 3, repeat('b', 64),
                    'version-1', repeat('c', 64), repeat('c', 64), 2,
                    '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z',
                    'VERIFIED', '2026-01-02T00:00:00Z', 'migration-test'%s
                )
                """
                .formatted(evidenceColumns, receiptId, evidenceValues);
    }

    private static String lineageAttemptInsert(
            String id,
            long attemptNo,
            String commandId,
            String previousAttemptId,
            boolean resetRequired,
            int publicSequenceOffset) {
        return """
                insert into agent_run_attempt (
                    id, agent_run_id, attempt_no, attempt_status, executor_kind,
                    graph_key, graph_version, checkpoint_schema_version,
                    model_profile_id, prompt_version, output_schema_version,
                    policy_version, guardrail_version, request_hash,
                    lineage_schema_version, command_id, command_request_hash,
                    logical_input_hash, command_json, previous_attempt_id,
                    reset_required, public_sequence_offset, started_at, created_by
                ) values (
                    '%s', 'RUN_DB_V2', %d, 'RUNNING', 'TEMPORAL_ACTIVITY',
                    'evidence.graph', 'graph-v2', 'checkpoint-v2',
                    'model-v2', 'prompt-v2', 'result-v1', 'policy-v2', 'guardrail-v2',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'agent-run-attempt-lineage.v1', '%s',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    '{"command_id":"%s"}', '%s', %s, %d, now(), 'migration-test'
                )
                """.formatted(
                id,
                attemptNo,
                commandId,
                commandId,
                previousAttemptId,
                resetRequired,
                publicSequenceOffset);
    }

    private static void assertUniqueViolation(
            Connection connection, String sql, String constraint) {
        assertThatThrownBy(() -> connection.createStatement().executeUpdate(sql))
                .isInstanceOf(SQLException.class)
                .satisfies(
                        failure -> {
                            SQLException sqlFailure = (SQLException) failure;
                            assertThat(sqlFailure.getSQLState()).isEqualTo("23505");
                            assertThat(sqlFailure.getMessage()).contains(constraint);
                        });
    }

    private static void assertCheckViolation(
            Connection connection, String sql, String constraint) {
        assertThatThrownBy(() -> connection.createStatement().executeUpdate(sql))
                .isInstanceOf(SQLException.class)
                .satisfies(
                        failure -> {
                            SQLException sqlFailure = (SQLException) failure;
                            assertThat(sqlFailure.getSQLState()).isEqualTo("23514");
                            assertThat(sqlFailure.getMessage()).contains(constraint);
                        });
    }

    private static void assertSqlState(
            Connection connection, String sql, String sqlState, String message) {
        assertThatThrownBy(() -> connection.createStatement().executeUpdate(sql))
                .isInstanceOf(SQLException.class)
                .satisfies(
                        failure -> {
                            SQLException sqlFailure = (SQLException) failure;
                            assertThat(sqlFailure.getSQLState()).isEqualTo(sqlState);
                            assertThat(sqlFailure.getMessage()).contains(message);
                        });
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
