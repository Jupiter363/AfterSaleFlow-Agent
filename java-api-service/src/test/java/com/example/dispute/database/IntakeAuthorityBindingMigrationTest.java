package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class IntakeAuthorityBindingMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src", "main", "resources", "db", "migration", "V043_1__intake_authority_bindings.sql");

    @Test
    void createsAllFrozenAuthorityTablesAndCandidateKeys() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("create table case_intake_epoch_selection_binding")
                .contains("create table case_intake_epoch_party_authority")
                .contains("create table case_intake_command_payload_authority")
                .contains("create table case_intake_command_authority")
                .contains("uq_r15_case_room_epoch_selection")
                .contains("uq_r15_graph_thread_authority")
                .contains("uq_r15_access_session_authority")
                .contains("uq_r15_agent_session_authority")
                .contains("uq_r15_snapshot_event_route")
                .contains("uq_r15_case_command_authority")
                .contains("fk_r15_selection_epoch")
                .contains("fk_r15_party_graph_registration")
                .contains("fk_r15_party_access_session")
                .contains("fk_r15_party_agent_session")
                .contains("fk_r15_payload_event")
                .contains("fk_r15_command_case_command")
                .contains("fk_r15_command_payload");
        assertThat(count(sql, "create table case_intake_")).isEqualTo(4);
    }

    @Test
    void pinsSelectionPartyAndPayloadConstantsAndSourceShapes() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("room_workflow_type = 'intakeroomworkflow'")
                .contains("graph_key = 'intake.v2'")
                .contains("state_schema_version = 'intake-graph-state.v2'")
                .contains("output_schema_version = 'intake-turn-proposal.v2'")
                .contains("writer_mode = 'shadow'")
                .contains("agent_key = 'dispute_intake_officer'")
                .contains("agent_session_profile_version = 'agent-session-profile.v1'")
                .contains("memory_policy_id = 'graph_private_no_memory_frame_v1'")
                .contains("party in ('initiator', 'respondent')")
                .contains("actor_role = 'user' and permission_level = 'party_user'")
                .contains("actor_role = 'merchant' and permission_level = 'party_merchant'")
                .contains("source_kind = 'existing_private_event'")
                .contains("schema_version = 'intake-turn-event.v2'")
                .contains("source_kind = 'server_minted_human_input'")
                .contains("schema_version = 'intake-human-input-command.v1'")
                .contains("source_kind = 'server_canonical_branch'")
                .contains("schema_version = 'intake-branch-command.v1'")
                .contains("size_bytes between 1 and 32768")
                .contains("size_bytes between 1 and 16384")
                .contains("put_receipt_schema_version = 'intake-command-payload-put-receipt.v1'")
                .contains("put_receipt_stored_at_epoch_micros between 0 and 9007199254740991");
    }

    @Test
    void enforcesAppendOnlyBootstrapStatusAndExactRouteProofs() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("create or replace function reject_r15_authority_mutation()")
                .contains("before update or delete on case_intake_epoch_selection_binding")
                .contains("before update or delete on case_intake_epoch_party_authority")
                .contains("before update or delete on case_intake_command_payload_authority")
                .contains("before update or delete on case_intake_command_authority")
                .contains("create constraint trigger trg_r15_exact_two_party_bootstrap")
                .contains("deferrable initially deferred")
                .contains("row_count <> 2")
                .contains("party = 'initiator'")
                .contains("party = 'respondent'")
                .contains("create or replace function enforce_r15_live_status()")
                .contains("access_status is distinct from 'active'")
                .contains("agent_status is distinct from 'active'")
                .contains("registration_status is distinct from 'registered'")
                .contains("create constraint trigger trg_r15_existing_private_event_assertion")
                .contains("binding_type = 'event'")
                .contains("visibility = 'private'")
                .contains("actor_audience is distinct from new.actor_role")
                .contains("object_uri is distinct from new.object_uri")
                .contains("content_sha256 is distinct from new.content_sha256")
                .contains("create constraint trigger trg_r15_command_exact_comparison")
                .contains("payload_uri is distinct from payload_row.object_uri")
                .contains("payload_sha256 is distinct from payload_row.content_sha256");
    }

    @Test
    void provesMigrationOrderingAndKeepsPayloadUriOutOfIndexes() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("-- v043_1 order proof")
                .contains("to_regclass('case_intake_graph_thread_binding')")
                .contains("to_regclass('case_intake_snapshot_binding')")
                .contains("rfc_8785")
                .contains("server-owned authority tuples")
                .doesNotContain("payload_json")
                .doesNotContain("snapshot_body");
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
