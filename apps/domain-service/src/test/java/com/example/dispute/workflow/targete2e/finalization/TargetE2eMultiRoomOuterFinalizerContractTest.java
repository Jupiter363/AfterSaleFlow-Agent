package com.example.dispute.workflow.targete2e.finalization;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class TargetE2eMultiRoomOuterFinalizerContractTest {

    private static final Path SOURCE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "example",
            "dispute",
            "workflow",
            "targete2e",
            "finalization",
            "TargetE2eMultiRoomOuterFinalizer.java");

    @Test
    void bindsParallelAssemblyToTheRealReceiptBeforeCommandCompletion() throws Exception {
        String source = Files.readString(SOURCE)
                .replace("\r\n", "\n")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);

        int formalCommit = source.indexOf("formalcommitter.commit(");
        int technicalLock = source.indexOf("strategy.locktechnicalauthority(");
        int receiptAppend = source.indexOf("receiptledger.append(append)");
        int technicalCommit = source.indexOf("strategy.committechnicalauthority(");
        int commandCompletion = source.indexOf("completionwriter.complete(");

        assertThat(formalCommit).isGreaterThanOrEqualTo(0);
        assertThat(technicalLock).isGreaterThan(formalCommit);
        assertThat(receiptAppend).isGreaterThan(technicalLock);
        assertThat(technicalCommit).isGreaterThan(receiptAppend);
        assertThat(commandCompletion).isGreaterThan(technicalCommit);
        assertThat(source)
                .contains("transactions.execute(")
                .contains("propagation_required")
                .contains("isolation_repeatable_read");
    }
}
