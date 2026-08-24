package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class IntakeParallelFrameStagingMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src",
            "main",
            "resources",
            "db",
            "migration",
            "V081__intake_parallel_frame_staging.sql");

    @Test
    void addsV4WithoutWideningTheV3SingleFrameContract() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("'agent-stream.v3', 'agent-stream.v4'")
                .contains("uq_agent_run_stream_event_attempt_sequence_v4")
                .contains("where stream_protocol = 'agent-stream.v4'")
                .contains("'public_frame_projection_item'")
                .contains("'frame_generation_reset'")
                .contains("'public_frame_sealed'")
                .doesNotContain("drop index if exists uq_agent_run_stream_event_attempt_sequence_v3");
    }

    @Test
    void createsOnlyTechnicalStagingAndImmutableProposalAuthority() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("create table intake_parallel_frame_set")
                .contains("create table intake_parallel_frame_generation")
                .contains("create table intake_parallel_frame_slot")
                .contains("create table intake_parallel_frame_ingress")
                .contains("create table intake_parallel_frame_projection_item")
                .contains("create table intake_parallel_frame_result")
                .contains("create table intake_parallel_proposal_artifact")
                .contains("schema_version = 'intake-turn-proposal.v2'")
                .doesNotContain("insert into room_message")
                .doesNotContain("insert into case_dossier")
                .doesNotContain("insert into domain_operation")
                .doesNotContain("update case_process_projection")
                .doesNotContain("update case_command");
    }

    @Test
    void bindsEveryFrameSetToTheExactImmutableV080EventGeneration() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("event_binding_id varchar(128) not null")
                .contains("binding_generation bigint not null")
                .contains("authority_version bigint not null")
                .contains(
                        "event_binding_id, thread_registration_id, logical_sequence, binding_generation")
                .contains(
                        "binding_id, thread_registration_id, event_sequence, binding_generation")
                .contains("command_request_sha256 varchar(64) not null");
    }

    @Test
    void freezesExactThreeFrameTypesAndSeparatesLocalFromBindingGeneration()
            throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("'dialogue_frame', 'dossier_frame', 'quality_frame'")
                .contains("frame_generation bigint not null")
                .contains("binding_generation bigint not null")
                .contains("primary key (frame_set_id, frame_type, frame_generation)")
                .contains("primary key (frame_set_id, frame_type)")
                .contains("frame generation must stay or advance exactly once");
    }

    @Test
    void preservesExactReplayAndJavaAllocatedGlobalSequenceAuthority() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("ingress_identity varchar(256) not null")
                .contains("canonical_payload_sha256 varchar(64) not null")
                .contains("global_sequence bigint not null")
                .contains("uq_intake_parallel_frame_ingress_identity")
                .contains("uq_intake_parallel_frame_ingress_session_sequence")
                .contains("uq_intake_parallel_frame_ingress_global_sequence")
                .contains("fk_intake_parallel_frame_ingress_public_event");
    }

    @Test
    void keepsSealedResultsIngressProjectionAndProposalArtifactsAppendOnly()
            throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("trg_intake_parallel_ingress_no_update")
                .contains("trg_intake_parallel_projection_no_update")
                .contains("trg_intake_parallel_result_no_update")
                .contains("trg_intake_parallel_proposal_no_update")
                .contains("trg_intake_parallel_frame_generation_transition")
                .contains("trg_intake_parallel_frame_generation_no_delete")
                .contains("trg_intake_parallel_frame_slot_no_delete")
                .contains("terminal intake parallel frame generation is immutable")
                .contains("sealed intake parallel frame slot is immutable")
                .contains("terminal intake parallel frame-set is immutable");
    }

    @Test
    void persistsBoundedOneLaneRepairAuthorityWithoutWeakeningTerminalStates()
            throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("repair_code varchar(128)")
                .contains("validation_path varchar(1024)")
                .contains("frame_generation = 1 and repair_code is null")
                .contains("frame_generation > 1")
                .contains("failure_retryable boolean")
                .contains("staging_state in ('failed', 'ambiguous')")
                .contains("failure_retryable is not null")
                .contains("new.repair_code is distinct from old.repair_code")
                .contains("new.validation_path is distinct from old.validation_path");
    }

    @Test
    void onlyReadyOrCommittedAssemblyCanReferenceAProposal() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("assembly_state = 'collecting'")
                .contains("proposal_artifact_id is null")
                .contains("assembly_state = 'ready'")
                .contains("proposal_artifact_id is not null")
                .contains("assembly_state = 'committed'")
                .contains("terminal_receipt_id is not null")
                .contains("assembly_state = 'failed_uncommitted'")
                .contains("terminal_receipt_id is null");
    }

    private static String normalizedSql() throws Exception {
        return Files.readString(MIGRATION)
                .replace("\r\n", "\n")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
