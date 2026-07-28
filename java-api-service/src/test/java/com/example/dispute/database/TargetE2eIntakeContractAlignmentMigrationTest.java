package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class TargetE2eIntakeContractAlignmentMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src", "main", "resources", "db", "migration",
            "V057__target_e2e_intake_contract_alignment.sql");
    private static final Path V048 = Path.of(
            "src", "main", "resources", "db", "migration",
            "V048__target_e2e_intake_thread_binding.sql");

    @Test
    void replacesTheConstraintOnlyAfterTheStrongerContractHasValidated()
            throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("add constraint ck_intake_graph_thread_constants_v057")
                .contains("not valid")
                .contains("validate constraint ck_intake_graph_thread_constants_v057")
                .contains("drop constraint ck_intake_graph_thread_constants")
                .contains(
                        "rename constraint ck_intake_graph_thread_constants_v057 to "
                                + "ck_intake_graph_thread_constants");
        assertThat(sql.indexOf("add constraint ck_intake_graph_thread_constants_v057"))
                .isLessThan(sql.indexOf("validate constraint ck_intake_graph_thread_constants_v057"));
        assertThat(sql.indexOf("validate constraint ck_intake_graph_thread_constants_v057"))
                .isLessThan(sql.indexOf("drop constraint ck_intake_graph_thread_constants"));
        assertThat(sql.indexOf("drop constraint ck_intake_graph_thread_constants"))
                .isLessThan(sql.indexOf("rename constraint ck_intake_graph_thread_constants_v057"));
    }

    @Test
    void permitsOnlyTheFrozenLegacyTupleOrTheFullyPinnedTemporalTargetTuple()
            throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("schema_version = 'graph-private-thread-registration.v1'")
                .contains("room_type = 'intake'")
                .contains("state_schema_version = 'intake-graph-state.v2'")
                .contains("graph_key = 'intake.v2'")
                .contains("graph_key = 'all-rooms.target-e2e.v1'")
                .contains("writer_mode = 'temporal'")
                .contains("graph_version = 'target-e2e-graph.2026-07-27.1'")
                .contains("checkpoint_schema_version = 'target-e2e-checkpoint.v1'")
                .contains("output_schema_version in (")
                .contains("'intake-turn-proposal.v2'")
                .contains("'target-e2e-room-proposal-source.v1'");

        int legacyBranch = sql.indexOf("graph_key = 'intake.v2'");
        int targetBranch = sql.indexOf("graph_key = 'all-rooms.target-e2e.v1'");
        assertThat(sql.substring(legacyBranch, targetBranch))
                .contains("output_schema_version = 'intake-turn-proposal.v2'")
                .doesNotContain("target-e2e-room-proposal-source.v1");
        assertThat(sql.substring(targetBranch))
                .contains("graph_version = 'target-e2e-graph.2026-07-27.1'")
                .contains("checkpoint_schema_version = 'target-e2e-checkpoint.v1'");
    }

    @Test
    void leavesTheHistoricalV048MigrationByteForByteUntouched() throws Exception {
        byte[] bytes = Files.readAllBytes(V048);

        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .isEqualTo("d7465e5174455dde59293ac2c74a15a914d85361de0bf7e73db6d8910545a835");
    }

    private static String normalizedSql() throws Exception {
        return Files.readString(MIGRATION)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
