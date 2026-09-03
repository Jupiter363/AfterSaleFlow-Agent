package com.example.dispute.agentstream.application;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory.Binding;
import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory.Context;
import com.example.dispute.agentstream.application.AgentRunLedger.AttemptAllocation;
import com.example.dispute.agentstream.application.AgentRunLedger.RecoveryState;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.InvocationContext;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.RetryBudget;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Builds a deterministic, independently hashed command for the next public attempt. */
@Component
public final class AgentRunV2NextAttemptFactory {

    private final ObjectMapper objectMapper;
    private final AgentRunCommandBindingFactory bindingFactory;

    public AgentRunV2NextAttemptFactory(
            ObjectMapper objectMapper, AgentRunCommandBindingFactory bindingFactory) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.bindingFactory = Objects.requireNonNull(bindingFactory, "bindingFactory");
    }

    public AttemptAllocation next(RecoveryState state) {
        RoomGraphCommand previous = verifiedCommand(state);
        long attemptNo = state.latestAttempt().attemptNo() + 1;
        String token = identityToken(state, attemptNo);
        InvocationContext invocation = previous.invocationContext();
        InvocationContext nextInvocation = new InvocationContext(
                invocation.agentProfileId(),
                invocation.promptProfileId(),
                invocation.modelProfileId(),
                invocation.outputSchemaVersion(),
                invocation.policyVersion(),
                invocation.guardrailVersion(),
                invocation.toolCapabilities(),
                invocation.envelopeKeyId(),
                "agent-attempt-nonce:" + token);
        RetryBudget previousBudget = previous.retryBudget();
        if (previousBudget.providerAttemptsRemaining() < 1) {
            throw new IllegalStateException(
                    "next AgentRun attempt has no residual provider retry budget");
        }
        RetryBudget residual = new RetryBudget(
                previousBudget.providerAttemptsRemaining() - 1,
                previousBudget.activityAttemptsRemaining(),
                previousBudget.repairsRemaining());
        RoomGraphCommand provisional = new RoomGraphCommand(
                previous.schemaVersion(),
                "agent-command:" + token,
                previous.logicalRunId(),
                "agent-attempt:" + token,
                previous.tenantSurrogate(),
                previous.caseId(),
                previous.roomType(),
                previous.roomEpoch(),
                previous.graphKey(),
                previous.graphVersion(),
                previous.checkpointSchemaVersion(),
                previous.threadId(),
                previous.actorScope(),
                previous.processRevision(),
                previous.stageCode(),
                previous.stageSequence(),
                previous.domainSnapshotRef(),
                previous.eventRef(),
                nextInvocation,
                residual,
                previous.deadlineAt(),
                previous.traceparent(),
                "0".repeat(64));
        ObjectNode body = objectMapper.valueToTree(provisional);
        body.remove("request_hash");
        String requestHash = ContractJson.sha256Hex(body);
        RoomGraphCommand next = new RoomGraphCommand(
                provisional.schemaVersion(),
                provisional.commandId(),
                provisional.logicalRunId(),
                provisional.attemptId(),
                provisional.tenantSurrogate(),
                provisional.caseId(),
                provisional.roomType(),
                provisional.roomEpoch(),
                provisional.graphKey(),
                provisional.graphVersion(),
                provisional.checkpointSchemaVersion(),
                provisional.threadId(),
                provisional.actorScope(),
                provisional.processRevision(),
                provisional.stageCode(),
                provisional.stageSequence(),
                provisional.domainSnapshotRef(),
                provisional.eventRef(),
                provisional.invocationContext(),
                provisional.retryBudget(),
                provisional.deadlineAt(),
                provisional.traceparent(),
                requestHash);
        Binding binding = bindingFactory.bind(context(state), next);
        if (!state.logicalRun().logicalInputHash().equals(binding.logicalInputHash())) {
            throw new IllegalStateException("next attempt changed the logical AgentRun input");
        }
        return new AttemptAllocation(attemptNo, next, binding);
    }

    public RoomGraphCommand verifiedCommand(RecoveryState state) {
        try {
            RoomGraphCommand command = objectMapper.readValue(
                    state.latestAttempt().canonicalCommandJson(), RoomGraphCommand.class);
            Binding binding = bindingFactory.bind(context(state), command);
            if (!command.logicalRunId().equals(state.logicalRun().agentRunId())
                    || !command.attemptId().equals(state.latestAttempt().attemptId())
                    || !command.commandId().equals(state.latestAttempt().commandId())
                    || !command.requestHash().equals(state.latestAttempt().commandRequestHash())
                    || !binding.logicalInputHash().equals(state.latestAttempt().logicalInputHash())
                    || !binding.logicalInputHash()
                            .equals(state.logicalRun().logicalInputHash())
                    || !ContractJson.canonicalString(objectMapper.valueToTree(command))
                            .equals(state.latestAttempt().canonicalCommandJson())) {
                throw new IllegalStateException(
                        "persisted latest AgentRun command conflicts with its lineage");
            }
            return command;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "persisted latest AgentRun command cannot be decoded", failure);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "persisted latest AgentRun command conflicts with its lineage", failure);
        }
    }

    private String identityToken(RecoveryState state, long attemptNo) {
        ObjectNode identity = objectMapper.createObjectNode();
        identity.put("schema_version", "agent-run-next-attempt-identity.v1");
        identity.put("logical_run_id", state.logicalRun().agentRunId());
        identity.put("attempt_no", attemptNo);
        identity.put("previous_attempt_id", state.latestAttempt().attemptId());
        identity.put("previous_command_hash", state.latestAttempt().commandRequestHash());
        return ContractJson.sha256Hex(identity).substring(0, 32);
    }

    private static Context context(RecoveryState state) {
        return new Context(
                state.roomId(),
                state.logicalRun().roomEpochId(),
                state.operation(),
                state.logicalIdempotencyKey());
    }
}
