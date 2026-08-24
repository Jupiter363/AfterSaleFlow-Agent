package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class IntakeParallelAssemblyArtifactMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src",
            "main",
            "resources",
            "db",
            "migration",
            "V082__intake_parallel_assembly_artifacts.sql");

    @Test
    void freezesCanonicalProposalBytesAtTheExistingSixtyFourKibBoundary() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("canonical_proposal_bytes bytea")
                .contains("size_bytes between 2 and 65536")
                .contains("size_bytes = octet_length(canonical_proposal_bytes)")
                .contains("artifact_id = 'intake.proposal.' || left(proposal_sha256, 32)")
                .contains("artifact_uri = 'urn:target-e2e:proposal:intake:' || proposal_sha256");
    }

    @Test
    void createsAnAppendOnlyCanonicalGraphResultAndTargetEnvelopeArtifact() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("create table intake_parallel_graph_result_artifact")
                .contains("canonical_graph_result_bytes bytea not null")
                .contains("canonical_command_envelope_bytes bytea not null")
                .contains("canonical_proposal_source_bytes bytea not null")
                .contains("canonical_result_envelope_bytes bytea not null")
                .contains("result_ref = 'urn:target-e2e:result:intake:' || graph_result_sha256")
                .contains("trg_intake_parallel_graph_result_no_update")
                .contains("trg_intake_parallel_graph_result_no_truncate");
    }

    @Test
    void bindsReadyAssemblyToBothImmutableArtifactsAndFreezesThemAtCommit() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("graph_result_artifact_id varchar(128)")
                .contains("fk_intake_parallel_frame_set_graph_result")
                .contains("assembly_state = 'ready'")
                .contains("graph_result_artifact_id is not null")
                .contains("ready intake parallel assembly artifact authority is immutable")
                .contains("new.graph_result_artifact_id is distinct from old.graph_result_artifact_id")
                .contains("collecting intake parallel failure cannot invent artifact authority");
    }

    @Test
    void remainsTechnicalAndDoesNotClaimFormalOrTerminalWrites() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .doesNotContain("insert into room_message")
                .doesNotContain("insert into case_intake_dossier")
                .doesNotContain("insert into agent_execution_manifest")
                .doesNotContain("update agent_run set finalization_status")
                .doesNotContain("update case_command")
                .doesNotContain("insert into target_e2e_agent_run_finalization_receipt");
    }

    private static String normalizedSql() throws Exception {
        return Files.readString(MIGRATION)
                .replace("\r\n", "\n")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
