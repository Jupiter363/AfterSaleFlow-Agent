package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class IntakeGraphBindingMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src", "main", "resources", "db", "migration", "V043__intake_graph_bindings.sql");

    @Test
    void addsOnlyTheTwoFrozenIntakeBindingTablesAndV2ChildPins() throws Exception {
        String sql = Files.readString(MIGRATION).toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("create table case_intake_graph_thread_binding")
                .contains("create table case_intake_snapshot_binding")
                .contains("room_workflow_type varchar(128)")
                .contains("room_workflow_build_id varchar(128)")
                .contains("ck_case_room_epoch_v2_room_workflow_binding")
                .contains("selection_schema_version = 'room-epoch-selection.v2'")
                .contains("uq_intake_graph_thread_private_tuple")
                .contains("uq_intake_snapshot_initialization")
                .contains("uq_intake_event_sequence")
                .contains("create function reject_case_room_epoch_child_selection_rewrite()")
                .contains("create trigger trg_case_room_epoch_child_selection_immutable")
                .doesNotContain(
                        "create or replace function reject_case_room_epoch_selection_rewrite()");
        assertThat(count(sql, "create table case_intake_")).isEqualTo(2);
    }

    @Test
    void expandsTheExistingOperationLedgerForTheFrozenFinalizationKey() throws Exception {
        String sql = normalizedSql();
        Path receiptFixture = Path.of(
                "..",
                "..",
                "contracts",
                "agent-platform",
                "intake",
                "v2",
                "fixtures",
                "valid",
                "intake-finalization-receipt-valid.json");
        String operationKey = JsonMapper.builder()
                .build()
                .readTree(receiptFixture.toFile())
                .required("operation_key")
                .asText();

        assertThat(sql)
                .contains(
                        "alter table domain_operation alter column operation_key type varchar(512)");
        assertThat(operationKey.length()).isGreaterThan(128).isLessThanOrEqualTo(512);
    }

    @Test
    void locksEveryPrivateReferenceDimensionAndStableIdentifierInTheDatabase()
            throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains(
                        "foreign key ( thread_registration_id, tenant_surrogate, case_id, "
                                + "room_type, room_epoch, fencing_token, thread_id, "
                                + "actor_scope_hash, agent_session_id, actor_audience ) "
                                + "references case_intake_graph_thread_binding( registration_id, "
                                + "tenant_surrogate, case_id, room_type, room_epoch, "
                                + "fencing_token, thread_id, actor_scope_hash, agent_session_id, "
                                + "audience )")
                .contains("and audience = actor_audience")
                .contains(
                        "create unique index uq_intake_snapshot_artifact on "
                                + "case_intake_snapshot_binding(tenant_surrogate, artifact_id)")
                .contains(
                        "create unique index uq_intake_event_id on "
                                + "case_intake_snapshot_binding(tenant_surrogate, event_id)")
                .contains(
                        "create unique index uq_intake_event_message on "
                                + "case_intake_snapshot_binding(tenant_surrogate, message_id)");
    }

    @Test
    void appliesPerContractSizeBoundsAndMonotonicStatusTimeShapes() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("binding_type = 'initial' and size_bytes between 1 and 262144")
                .contains("binding_type = 'event' and size_bytes between 1 and 32768")
                .contains(
                        "registration_status in ('pending', 'failed') and registered_at is null "
                                + "and retired_at is null")
                .contains(
                        "registration_status = 'registered' and registered_at is not null "
                                + "and registered_at >= created_at and retired_at is null")
                .contains(
                        "registration_status = 'retired' and retired_at is not null "
                                + "and retired_at >= coalesce(registered_at, created_at)")
                .contains(
                        "binding_type = 'initial' and schema_version = "
                                + "'intake-domain-snapshot.v2' and initialization_marker and "
                                + "room_revision is not null and projection_revision is not null "
                                + "and initial_last_sequence is not null and "
                                + "initial_last_sequence >= 0")
                .contains(
                        "binding_type = 'event' and schema_version = 'intake-turn-event.v2' "
                                + "and not initialization_marker and room_revision is null and "
                                + "projection_revision is null and initial_last_sequence is null")
                .doesNotContain("created_at >= issued_at");
    }

    @Test
    void persistsOnlyBindingsHashesVersionsAndOrderedReferences() throws Exception {
        String sql = Files.readString(MIGRATION).toLowerCase(Locale.ROOT);
        String snapshotTable = sql.substring(
                sql.indexOf("create table case_intake_snapshot_binding"),
                sql.indexOf("create unique index uq_intake_snapshot_initialization"));

        assertThat(snapshotTable)
                .contains("object_uri")
                .contains("object_version")
                .contains("content_sha256")
                .contains("event_id")
                .contains("message_id")
                .contains("event_sequence")
                .contains("initial_last_sequence")
                .doesNotContain("payload_json")
                .doesNotContain("snapshot_body")
                .doesNotContain("message_text")
                .doesNotContain("memory_frame")
                .doesNotContain("checkpoint_state")
                .doesNotContain("prompt_text");
    }

    private static int count(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static String normalizedSql() throws Exception {
        return Files.readString(MIGRATION)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
