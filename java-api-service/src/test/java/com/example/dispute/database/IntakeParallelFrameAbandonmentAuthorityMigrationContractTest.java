package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class IntakeParallelFrameAbandonmentAuthorityMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src",
            "main",
            "resources",
            "db",
            "migration",
            "V092__intake_parallel_frame_abandonment_authority.sql");

    @Test
    void freezesOneExactAppendOnlyGraphAbandonmentPerAdmissionReceipt()
            throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("create table intake_parallel_frame_abandonment_receipt")
                .contains("unique (frame_set_id, admission_receipt_sha256)")
                .contains("fk_intake_parallel_frame_abandonment_frame_set")
                .contains("fk_intake_parallel_frame_abandonment_admission")
                .contains("canonical_graph_receipt_bytes bytea not null")
                .contains(
                        "receipt_size_bytes = octet_length(canonical_graph_receipt_bytes)")
                .contains("trg_intake_parallel_frame_abandonment_no_update")
                .contains("trg_intake_parallel_frame_abandonment_no_truncate");
    }

    @Test
    void bindsDeterministicGraphExecutionAndOnlyTheThreeRegisteredFrameTypes()
            throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains(
                        "abandonment_id = 'parallel-receipt-abandonment.' || left(admission_receipt_sha256, 24) || '.' || graph_fencing_token::text")
                .contains(
                        "graph_execution_id = 'parallel-receipt-execution.' || left(admission_receipt_sha256, 24) || '.' || graph_fencing_token::text")
                .contains("provider_call_count_after > provider_call_count_before")
                .contains(
                        "ambiguous_frame_types <@ '[ \"dialogue_frame\", \"dossier_frame\", \"quality_frame\" ]'::jsonb");
    }

    @Test
    void remainsTechnicalOnlyAndExtendsPhysicalPurgeBeforeItsParents()
            throws Exception {
        String sql = normalizedSql();
        int abandonmentDelete = sql.lastIndexOf(
                "delete from intake_parallel_frame_abandonment_receipt");
        int failureDelete = sql.lastIndexOf(
                "delete from intake_parallel_failure_termination_receipt");

        assertThat(abandonmentDelete).isGreaterThanOrEqualTo(0);
        assertThat(failureDelete).isGreaterThan(abandonmentDelete);
        assertThat(sql)
                .doesNotContain("insert into room_message")
                .doesNotContain("insert into case_intake_dossier")
                .doesNotContain("update case_process_projection")
                .doesNotContain("update case_command")
                .doesNotContain("assembly_state = 'committed'");
    }

    private static String normalizedSql() throws Exception {
        return Files.readString(MIGRATION)
                .replace("\r\n", "\n")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
