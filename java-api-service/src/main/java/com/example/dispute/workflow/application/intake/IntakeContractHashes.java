package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** RFC 8785/SHA-256 helpers shared by every Java-owned Intake boundary. */
public final class IntakeContractHashes {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    static {
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private IntakeContractHashes() {}

    public static String canonicalHashExcluding(JsonNode value, String hashField) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("contract value must be an object");
        }
        ObjectNode copy = ((ObjectNode) value).deepCopy();
        if (copy.remove(IntakeContractSupport.identifier(hashField, "hashField")) == null) {
            throw new IllegalArgumentException("contract value does not contain " + hashField);
        }
        return ContractJson.sha256Hex(copy);
    }

    public static String actorScopeHash(IntakePrivateThreadRegistration.ActorScope actorScope) {
        return ContractJson.sha256Hex(toTree(actorScope));
    }

    public static String registrationHash(IntakePrivateThreadRegistration registration) {
        return canonicalHashExcluding(toTree(registration), "registration_hash");
    }

    public static String graphCommandHash(Object command) {
        return canonicalHashExcluding(toTree(command), "request_hash");
    }

    /** Returns the canonical self-hash carried by a room-graph-result.v1 envelope. */
    public static String graphResultHash(RoomGraphResult result) {
        return canonicalHashExcluding(toTree(result), "output_hash");
    }

    /**
     * Computes the idempotency hash for the complete trusted finalization request.
     *
     * <p>The request hash is deliberately derived from every request member except the hash
     * itself.  This keeps the ledger key collision-safe when an Activity retries with a changed
     * authority, reference, or graph envelope, while leaving the request type framework-free.
     */
    public static String finalizationRequestHash(IntakeGraphFinalizationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        return canonicalHashExcluding(toTree(request), "requestHash");
    }

    static JsonNode toTree(Object value) {
        return MAPPER.valueToTree(value);
    }

    static byte[] canonicalBytes(Object value) {
        return ContractJson.canonicalize(toTree(value));
    }
}
