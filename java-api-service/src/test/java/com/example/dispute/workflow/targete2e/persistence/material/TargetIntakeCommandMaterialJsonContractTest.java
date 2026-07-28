package com.example.dispute.workflow.targete2e.persistence.material;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetIntakeCommandMaterialJsonContractTest {

    @Test
    void materialJsonUsesCamelCaseForExecutionContextAndSnakeCaseForGraphCommand() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        JsonNode document = mapper.valueToTree(context());

        assertThat(document.path("schemaVersion").asText())
                .isEqualTo("intake-command-execution-context.v2");
        assertThat(document.path("targetAgentRun").path("activationId").asText())
                .isEqualTo("p9act.v1." + "a".repeat(32));
        assertThat(document.path("targetAgentRun").path("commandHash").asText())
                .isEqualTo(hash('b'));
        JsonNode command = document.path("targetAgentRun").path("request").path("command");
        assertThat(command.path("tenant_surrogate").asText()).isEqualTo("tenant-p9");
        assertThat(command.path("case_id").asText()).isEqualTo("CASE_P9_001");
        assertThat(command.path("command_id").asText()).isEqualTo("command-p9-001");
        assertThat(command.path("room_type").asText()).isEqualTo("INTAKE");
        assertThat(command.path("room_epoch").asLong()).isZero();
    }

    private static IntakeCommandExecutionContext context() {
        RoomGraphCommand command = new RoomGraphCommand(
                "room-graph-command.v1",
                "command-p9-001",
                "logical-run-p9-001",
                "attempt-p9-001",
                "tenant-p9",
                "CASE_P9_001",
                RoomType.INTAKE,
                0,
                "all-rooms.target-e2e.v1",
                "target-e2e-graph.2026-07-27.1",
                "target-e2e-checkpoint.v1",
                "grt.v1." + "1".repeat(32),
                new RoomGraphCommand.ActorScope(
                        "user-p9", ActorRole.USER, Audience.USER, List.of("INTAKE_MESSAGE")),
                0,
                "INTAKE_MESSAGE",
                1,
                new RoomGraphCommand.SnapshotRef(
                        "snapshot-p9",
                        "intake-domain-snapshot.v2",
                        "urn:after-sale-flow:intake-snapshot:p9",
                        hash('c'),
                        1),
                new RoomGraphCommand.SnapshotRef(
                        "event-p9",
                        "target-e2e-intake-message.v1",
                        "urn:after-sale-flow:intake-command:p9",
                        hash('d'),
                        1),
                new RoomGraphCommand.InvocationContext(
                        "agent-profile-p9",
                        "prompt-profile-p9",
                        "model-profile-p9",
                        "intake-turn-proposal.v2",
                        "policy-p9",
                        "guardrail-p9",
                        List.of(),
                        "envelope-key-p9",
                        "envelope-nonce-p9"),
                new RoomGraphCommand.RetryBudget(2, 2, 1),
                Instant.parse("2026-07-28T09:00:00Z"),
                "00-" + "e".repeat(32) + "-" + "f".repeat(16) + "-01",
                hash('1'));
        ExecuteAgentRunRequest request = new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                "logical-run-p9-001",
                1,
                2,
                "agent-stream.v2",
                hash('2'),
                null,
                false,
                0,
                command);
        IntakeTargetAgentRunContext target = new IntakeTargetAgentRunContext(
                "intake-target-agent-run-context.v1",
                IntakeTargetAgentRunContext.TARGET_LANE,
                "p9act.v1." + "a".repeat(32),
                hash('3'),
                1,
                0,
                0,
                "case-build-p9",
                "control-build-p9",
                "agent-build-p9",
                hash('4'),
                "graph-build-p9",
                hash('b'),
                hash('5'),
                request);
        return new IntakeCommandExecutionContext(
                "intake-command-execution-context.v2",
                "grt.v1." + "1".repeat(32),
                "agent-session-p9",
                Instant.parse("2026-07-28T09:00:00Z").toEpochMilli(),
                new RetryBudget("intake-retry-budget.v1", 2, 2, 1),
                null,
                target);
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
