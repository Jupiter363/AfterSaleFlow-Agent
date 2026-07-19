package com.example.dispute.agentstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory.Context;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentRunCommandBindingFactoryTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURE = Path.of(
            "..",
            "contracts",
            "agent-platform",
            "v1",
            "fixtures",
            "valid",
            "room-graph-command-valid.json");
    private static final Context CONTEXT =
            new Context("room-001", "epoch-001", "INTAKE_MESSAGE", "logical-key-001");

    private final AgentRunCommandBindingFactory factory =
            new AgentRunCommandBindingFactory(MAPPER);

    @Test
    void mutableAttemptAndDeliveryFieldsDoNotChangeTheLogicalInputHash() throws Exception {
        ObjectNode original = commandJson();
        RoomGraphCommand first = command(original);
        ObjectNode retry = original.deepCopy();
        retry.put("command_id", "graph-cmd-002");
        retry.put("attempt_id", "attempt-002");
        retry.withObject("retry_budget").put("provider_attempts_remaining", 1);
        retry.withObject("retry_budget").put("activity_attempts_remaining", 2);
        retry.withObject("invocation_context").put("envelope_key_id", "rotated-key-2");
        retry.withObject("invocation_context").put("envelope_nonce", "nonce-002");
        retry.put(
                "traceparent",
                "00-0123456789abcdef0123456789abcdef-fedcba9876543210-01");
        RoomGraphCommand second = command(rehash(retry));

        var firstBinding = factory.bind(CONTEXT, first);
        var secondBinding = factory.bind(CONTEXT, second);

        assertThat(secondBinding.logicalInputHash())
                .isEqualTo(firstBinding.logicalInputHash());
        assertThat(secondBinding.commandRequestHash())
                .isNotEqualTo(firstBinding.commandRequestHash());
        assertThat(secondBinding.canonicalCommandJson())
                .isEqualTo(ContractJson.canonicalString(retry));
    }

    @Test
    void stablePolicyOrSnapshotMutationChangesTheLogicalInputHash() throws Exception {
        ObjectNode original = commandJson();
        RoomGraphCommand first = command(original);
        ObjectNode changed = original.deepCopy();
        changed.withObject("invocation_context").put("model_profile_id", "other-model.v2");
        RoomGraphCommand second = command(rehash(changed));

        assertThat(factory.bind(CONTEXT, second).logicalInputHash())
                .isNotEqualTo(factory.bind(CONTEXT, first).logicalInputHash());
    }

    @Test
    void rejectsACommandWhoseSelfHashDoesNotBindItsBody() throws Exception {
        ObjectNode changed = commandJson();
        changed.put("stage_sequence", 99);

        assertThatThrownBy(() -> factory.bind(CONTEXT, command(changed)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not bind");
    }

    private static ObjectNode commandJson() throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURE.toFile());
        return (ObjectNode) wrapper.required("instance").deepCopy();
    }

    private static ObjectNode rehash(ObjectNode command) {
        ObjectNode unhashed = command.deepCopy();
        unhashed.remove("request_hash");
        command.put("request_hash", ContractJson.sha256Hex(unhashed));
        return command;
    }

    private static RoomGraphCommand command(ObjectNode node) throws Exception {
        return MAPPER.treeToValue(node, RoomGraphCommand.class);
    }
}
