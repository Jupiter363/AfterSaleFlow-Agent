package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class IntakeParallelFailureTerminalAuthorityMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src",
            "main",
            "resources",
            "db",
            "migration",
            "V091__intake_parallel_failure_terminal_authority.sql");

    @Test
    void freezesOneAppendOnlyGraphReceiptForEachFailedFrameSet() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("create table intake_parallel_failure_termination_receipt")
                .contains("frame_set_id varchar(128) not null unique")
                .contains("canonical_receipt_bytes bytea not null")
                .contains("receipt_size_bytes = octet_length(canonical_receipt_bytes)")
                .contains("fk_intake_parallel_failure_receipt_frame_set")
                .contains("trg_intake_parallel_failure_receipt_no_update")
                .contains("trg_intake_parallel_failure_receipt_no_truncate");
    }

    @Test
    void requiresTheReceiptByCommitForEveryNewFailedTransition() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("create constraint trigger trg_intake_parallel_failure_receipt_required")
                .contains("deferrable initially deferred")
                .contains("new.assembly_state = 'failed_uncommitted'")
                .contains("receipt.requested_failure_code = new.failure_code");
    }

    @Test
    void freezesVersionedAdmissionReceiptsBeforeFailureTermination() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("create table intake_parallel_admission_receipt_history")
                .contains("unique (frame_set_id, receipt_sha256)")
                .contains("canonical_receipt_bytes bytea not null")
                .contains("receipt_size_bytes = octet_length(canonical_receipt_bytes)")
                .contains("create table intake_parallel_admission_receipt_authority")
                .contains("current_receipt_generation bigint not null")
                .contains("new.current_receipt_generation <> old.current_receipt_generation + 1")
                .contains("fk_intake_parallel_failure_receipt_admission");
    }

    @Test
    void auditedPurgeDeletesTheReceiptBeforeItsParentFrameSet() throws Exception {
        String sql = normalizedSql();
        int receiptDelete = sql.lastIndexOf(
                "delete from intake_parallel_failure_termination_receipt");
        int authorityDelete = sql.lastIndexOf(
                "delete from intake_parallel_admission_receipt_authority");
        int historyDelete = sql.lastIndexOf(
                "delete from intake_parallel_admission_receipt_history");
        int frameSetDelete = sql.lastIndexOf(
                "delete from intake_parallel_frame_set where case_id = p_case_id");

        assertThat(receiptDelete).isGreaterThanOrEqualTo(0);
        assertThat(authorityDelete).isGreaterThan(receiptDelete);
        assertThat(historyDelete).isGreaterThan(authorityDelete);
        assertThat(frameSetDelete).isGreaterThan(historyDelete);
    }

    private static String normalizedSql() throws Exception {
        return Files.readString(MIGRATION)
                .replace("\r\n", "\n")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
