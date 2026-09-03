package com.example.dispute.workflow.infrastructure.persistence.intake.parallel;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class JdbcIntakeParallelFailureFinalizationStoreContractTest {

    private static final Path STORE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "example",
            "dispute",
            "workflow",
            "infrastructure",
            "persistence",
            "intake",
            "parallel",
            "JdbcIntakeParallelFailureFinalizationStore.java");
    private static final Path COMMITTER = Path.of(
            "src",
            "main",
            "java",
            "com",
            "example",
            "dispute",
            "workflow",
            "activity",
            "agent",
            "TransactionalAgentRunTerminalFailureCommitter.java");

    @Test
    void remainsProxyableAndJoinsTheCallerOwnedFailureTransaction() throws Exception {
        assertThat(Modifier.isFinal(
                        JdbcIntakeParallelFailureFinalizationStore.class.getModifiers()))
                .isFalse();

        assertThat(normalized(STORE))
                .contains("@transactional(propagation = propagation.mandatory)")
                .contains("for update of frame_set")
                .contains("join intake_parallel_admission_receipt_authority admission")
                .contains("receipt.admissionreceiptsha256() .equals(text(row, \"current_receipt_sha256\"))")
                .contains("agent-stream.v4")
                .contains("uncommitted");
    }

    @Test
    void persistsTheImmutableReceiptBeforeTheSingleFailedStateCas() throws Exception {
        String source = normalized(STORE);
        int insert = source.indexOf("insert into intake_parallel_failure_termination_receipt");
        int failCas = source.indexOf("set assembly_state = 'failed_uncommitted'");

        assertThat(insert).isGreaterThanOrEqualTo(0);
        assertThat(failCas).isGreaterThan(insert);
        assertThat(source)
                .contains("on conflict do nothing")
                .contains("requirestoredreceipt(receipt, parameters)")
                .contains("and assembly_state in ('collecting', 'ready')")
                .contains("and version = :expectedversion")
                .doesNotContain("insert into room_message")
                .doesNotContain("insert into case_intake_dossier")
                .doesNotContain("insert into agent_execution_manifest");
    }

    @Test
    void oneOuterTransactionWritesAgentRunBeforeTechnicalFailureAuthority() throws Exception {
        String source = normalized(COMMITTER);
        int ledger = source.indexOf("ledger.recordattemptfailureresult(status, result)");
        int parallel = source.indexOf("parallelfailureport.commit(new failurecommitcommand");

        assertThat(source).contains("@transactional(isolation = isolation.read_committed)");
        assertThat(ledger).isGreaterThanOrEqualTo(0);
        assertThat(parallel).isGreaterThan(ledger);
    }

    private static String normalized(Path path) throws Exception {
        return Files.readString(path)
                .replace("\r\n", "\n")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
