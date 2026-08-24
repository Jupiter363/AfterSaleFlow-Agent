package com.example.dispute.workflow.infrastructure.persistence.intake.parallel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class JdbcIntakeParallelAssemblyStoreContractTest {

    private static final Path SOURCE = Path.of(
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
            "JdbcIntakeParallelAssemblyStore.java");

    @Test
    void publishesReadyOnlyInsideOneTechnicalTransactionAfterLockingExactThree() throws Exception {
        String source = normalizedSource();

        assertThat(source)
                .contains("implements intakeparallelassemblystore")
                .contains("propagation = propagation.requires_new")
                .contains("for update of frame_set, attempt, slot, generation, authority")
                .contains("requireexactthree(rows")
                .contains("requireselectedproofs(command.selectedframes(), rows)")
                .contains("insert into intake_parallel_proposal_artifact")
                .contains("insert into intake_parallel_graph_result_artifact")
                .contains("set assembly_state = 'ready'")
                .contains("and assembly_state = 'collecting'")
                .contains("and version = :expectedversion");
    }

    @Test
    void exactReadyReplayRunsBeforeTheLiveAttemptAndV080NewWorkGate() throws Exception {
        String source = normalizedSource();
        int state = source.indexOf("if (state == assemblystate.ready || state == assemblystate.committed)");
        int running = source.indexOf("requirerunningauthority(command, rows)");

        assertThat(state).isGreaterThanOrEqualTo(0);
        assertThat(running).isGreaterThan(state);
        assertThat(source)
                .contains("requireexactartifact(command.artifact(), stored)")
                .contains("current_binding_generation")
                .contains("current_authority_version")
                .contains("intake_parallel_assembly_event_authority_superseded");
    }

    @Test
    void revalidatesEveryCanonicalByteAndKeepsFormalWritesOut() throws Exception {
        String source = normalizedSource();

        assertThat(source)
                .contains("envelopecodec.decodecommand")
                .contains("envelopecodec.validateproposalsource")
                .contains("envelopecodec.decoderesult")
                .contains("intakecontracthashes.graphresulthash")
                .contains("intakecontracthashes.canonicalhashexcluding")
                .contains("messagedigest.isequal")
                .doesNotContain("insert into room_message")
                .doesNotContain("insert into case_intake_dossier")
                .doesNotContain("insert into agent_execution_manifest")
                .doesNotContain("update agent_run set finalization_status")
                .doesNotContain("update case_command");
    }

    @Test
    void terminalReaderJoinsTheCallerTransactionAndLocksCurrentV080Authority()
            throws Exception {
        String source = normalizedSource();

        assertThat(source)
                .contains("public readyauthority lockreadyforterminal")
                .contains("@transactional(propagation = propagation.mandatory)")
                .contains("for update of frame_set, authority")
                .contains("current_binding_generation")
                .contains("current_authority_version")
                .contains("intake_parallel_ready_authority_stale");
    }

    private static String normalizedSource() throws Exception {
        return Files.readString(SOURCE)
                .replace("\r\n", "\n")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
