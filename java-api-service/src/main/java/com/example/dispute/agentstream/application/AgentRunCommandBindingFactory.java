package com.example.dispute.agentstream.application;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Creates the exact per-command binding and the stable cross-attempt logical input hash. */
@Component
public final class AgentRunCommandBindingFactory {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern TRACEPARENT =
            Pattern.compile("^00-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");

    private final ObjectMapper mapper;

    public AgentRunCommandBindingFactory(ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public Binding bind(Context context, RoomGraphCommand command) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(command, "command");
        ObjectNode commandJson = mapper.valueToTree(command);
        requireCommandSelfHash(commandJson, command.requestHash());

        ObjectNode logicalInput = commandJson.deepCopy();
        logicalInput.remove("command_id");
        logicalInput.remove("attempt_id");
        logicalInput.remove("retry_budget");
        logicalInput.remove("traceparent");
        logicalInput.remove("request_hash");
        JsonNode invocationNode = logicalInput.required("invocation_context");
        if (!(invocationNode instanceof ObjectNode invocation)) {
            throw new IllegalArgumentException("invocation_context must be an object");
        }
        invocation.remove("envelope_key_id");
        invocation.remove("envelope_nonce");
        logicalInput.put("logical_trace_id", traceId(command.traceparent()));
        logicalInput.put("room_id", context.roomId());
        logicalInput.put("room_epoch_id", context.roomEpochId());
        logicalInput.put("operation", context.operation());
        logicalInput.put("logical_idempotency_key", context.logicalIdempotencyKey());

        return new Binding(
                ContractJson.sha256Hex(logicalInput),
                command.requestHash(),
                ContractJson.canonicalString(commandJson));
    }

    private static void requireCommandSelfHash(ObjectNode commandJson, String requestHash) {
        if (requestHash == null || !SHA256.matcher(requestHash).matches()) {
            throw new IllegalArgumentException("command requestHash must be lowercase SHA-256");
        }
        ObjectNode unhashed = commandJson.deepCopy();
        JsonNode removed = unhashed.remove("request_hash");
        if (removed == null || !removed.isTextual()) {
            throw new IllegalArgumentException("serialized command has no request_hash");
        }
        String actual = ContractJson.sha256Hex(unhashed);
        if (!MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("command requestHash does not bind its body");
        }
    }

    private static String traceId(String traceparent) {
        Matcher matcher = TRACEPARENT.matcher(
                Objects.requireNonNull(traceparent, "traceparent"));
        if (!matcher.matches() || "0".repeat(32).equals(matcher.group(1))) {
            throw new IllegalArgumentException("traceparent has no valid logical trace id");
        }
        return matcher.group(1);
    }

    public record Context(
            String roomId,
            String roomEpochId,
            String operation,
            String logicalIdempotencyKey) {

        public Context {
            roomId = required(roomId, "roomId");
            roomEpochId = required(roomEpochId, "roomEpochId");
            operation = required(operation, "operation");
            logicalIdempotencyKey =
                    required(logicalIdempotencyKey, "logicalIdempotencyKey");
        }
    }

    public record Binding(
            String logicalInputHash,
            String commandRequestHash,
            String canonicalCommandJson) {

        public Binding {
            if (logicalInputHash == null
                    || !SHA256.matcher(logicalInputHash).matches()
                    || commandRequestHash == null
                    || !SHA256.matcher(commandRequestHash).matches()
                    || canonicalCommandJson == null
                    || canonicalCommandJson.isBlank()) {
                throw new IllegalArgumentException("AgentRun command binding is invalid");
            }
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
