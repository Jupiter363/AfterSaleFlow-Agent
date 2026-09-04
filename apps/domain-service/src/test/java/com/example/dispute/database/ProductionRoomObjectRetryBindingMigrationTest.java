package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ProductionRoomObjectRetryBindingMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src", "main", "resources", "db", "migration",
            "V062__production_runtime_room_object_retry_binding.sql");
    private static final Path V049 = Path.of(
            "src", "main", "resources", "db", "migration",
            "V049__production_runtime_intake_command_material.sql");

    @Test
    void preservesEveryHistoricalIntakeAdmissionAndAuthorityBinding() throws Exception {
        String function = intakeGuard();

        assertThat(function)
                .contains("from production_runtime_command_admission")
                .contains("where admission_id = new.admission_id")
                .contains("for share")
                .contains("admission_row.activation_id is distinct from new.activation_id")
                .contains("admission_row.activation_manifest_hash is distinct from new.activation_manifest_hash")
                .contains("admission_row.execution_lane is distinct from new.execution_lane")
                .contains("admission_row.isolated_domain_db_binding_hash is distinct from new.isolated_domain_db_binding_hash")
                .contains("admission_row.tenant_surrogate is distinct from new.tenant_surrogate")
                .contains("admission_row.case_id is distinct from new.case_id")
                .contains("admission_row.command_id is distinct from new.command_id")
                .contains("admission_row.command_hash is distinct from new.command_hash")
                .contains("admission_row.command_envelope_hash is distinct from new.command_envelope_hash")
                .contains("admission_row.room_epoch is distinct from new.room_epoch")
                .contains("admission_row.room_fencing_token is distinct from new.room_fencing_token")
                .contains("{targetagentrun,activationid}")
                .contains("{targetagentrun,activationmanifesthash}")
                .contains("{targetagentrun,roomfencingtoken}")
                .contains("{targetagentrun,commandhash}")
                .contains("{targetagentrun,commandenvelopehash}")
                .contains("{targetagentrun,request,command,tenant_surrogate}")
                .contains("{targetagentrun,request,command,case_id}")
                .contains("{targetagentrun,request,command,command_id}")
                .contains("{targetagentrun,request,command,room_type}")
                .contains("{targetagentrun,request,command,room_epoch}");
    }

    @Test
    void admitsOnlyV1InitialMaterialOrV2AdjacentRetryMaterial() throws Exception {
        String function = intakeGuard();

        assertThat(function)
                .contains("if context_attempt_no = 1 then")
                .contains("context_target_schema is distinct from 'intake-target-agent-run-context.v1'")
                .contains("context_previous_attempt_id is not null")
                .contains("context_target_schema is distinct from 'intake-target-agent-run-context.v2'")
                .contains("context_previous_attempt_id is null")
                .contains("run.id = context_logical_run_id")
                .contains("run.protocol = 'agent-stream.v2'")
                .contains("run.executor_kind = 'temporal_activity'")
                .contains("run.tenant_surrogate = new.tenant_surrogate")
                .contains("run.case_id = new.case_id")
                .contains("run.room_type = 'intake'")
                .contains("run.room_epoch = new.room_epoch")
                .contains("run.fencing_token = new.room_fencing_token")
                .contains("attempt.id = context_attempt_id")
                .contains("attempt.attempt_no = context_attempt_no")
                .contains("attempt.command_id = new.command_id")
                .contains("attempt.previous_attempt_id is not distinct from context_previous_attempt_id")
                .contains("predecessor.id = context_previous_attempt_id")
                .contains("predecessor.agent_run_id = context_logical_run_id")
                .contains("predecessor.attempt_no = context_attempt_no - 1")
                .contains("for key share of run, attempt")
                .contains("for key share;")
                .doesNotContain("context_target_schema <>");
    }

    @Test
    void leavesTheHistoricalV049MigrationByteForByteUntouched() throws Exception {
        byte[] bytes = Files.readAllBytes(V049);

        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .isEqualTo("f90adf5b8afb85c43f7bce573829265af436f52fe1ec5cb41c76f089a0276d4f");
    }

    private static String intakeGuard() throws Exception {
        String sql = Files.readString(MIGRATION)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        String start = "create or replace function enforce_production_runtime_intake_command_material()";
        assertThat(sql.indexOf(start)).isGreaterThanOrEqualTo(0);
        return sql.substring(sql.indexOf(start));
    }
}
