package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class AgentRunV4AttemptBaselineMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src", "main", "resources", "db", "migration",
            "V083__agent_run_v4_attempt_baseline.sql");

    @Test
    void reservesMinusOneOnlyForTheEmptyFirstV4AttemptAndFreezesProtocol() throws Exception {
        String sql = Files.readString(MIGRATION)
                .replace("\r\n", "\n")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("check (last_sequence_no >= -1)")
                .contains("parent_protocol = 'agent-stream.v4'")
                .contains("new.attempt_no <> 1")
                .contains("new.previous_attempt_id is not null")
                .contains("new.reset_required")
                .contains("new.public_sequence_offset <> 0")
                .contains("new.last_sequence_no = -1")
                .contains("new.public_output_emitted")
                .contains("new.final_frame_observed")
                .contains("only agent-stream.v4 may persist an empty attempt sequence baseline")
                .contains("agentrun protocol cannot change after attempt admission");
    }
}
