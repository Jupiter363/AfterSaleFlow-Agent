package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ProductionIntakeV4MaterialBindingMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src", "main", "resources", "db", "migration",
            "V085__production_runtime_intake_v4_material_binding.sql");

    @Test
    void bindsV3OrTheExactParallelV4MaterialToThePersistedRunProtocol() throws Exception {
        String sql = Files.readString(MIGRATION)
                .replace("\r\n", "\n")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("context_stream_protocol not in ('agent-stream.v3', 'agent-stream.v4')")
                .contains("run.protocol = context_stream_protocol")
                .contains("context_stream_protocol = 'agent-stream.v4'")
                .contains("context_attempt_no <> 1")
                .contains("attempt_limit}' is distinct from '1'")
                .contains("reset_required}' is distinct from 'false'")
                .contains("public_sequence_offset}' is distinct from '0'")
                .contains("dispute-intake-officer.parallel-frames.v1")
                .contains("production-runtime-room-proposal-source.v2")
                .contains("command,room_id}")
                .contains("jsonb_typeof(context_document #> '{targetagentrun,request,command,event_ref}')")
                .contains("command,event_ref}")
                .contains("actor_scope,actor_role}")
                .contains("not in ('user', 'merchant')")
                .contains("actor_scope,audience}")
                .contains("retry_budget,provider_attempts_remaining}")
                .contains("!~ '^[3-6]$'")
                .contains("agent-stream.v4 intake material requires the exact parallel execution authority")
                .contains("attempt.previous_attempt_id is not distinct from context_previous_attempt_id");
    }
}
