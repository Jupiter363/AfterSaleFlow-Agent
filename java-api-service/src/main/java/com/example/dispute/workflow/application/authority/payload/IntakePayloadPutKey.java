package com.example.dispute.workflow.application.authority.payload;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Objects;

/** Deterministic identity for a server-owned Intake payload put. */
public final class IntakePayloadPutKey {

    private static final String PREFIX = "iput.v1.";

    private IntakePayloadPutKey() {}

    public static String derive(
            String tenantSurrogate,
            String caseId,
            String commandId,
            IntakePayloadSourceKind sourceKind) {
        requireText(tenantSurrogate, "tenantSurrogate");
        requireText(caseId, "caseId");
        requireText(commandId, "commandId");
        Objects.requireNonNull(sourceKind, "sourceKind must not be null");
        if (!sourceKind.requiresPutReceipt()) {
            throw new IllegalArgumentException(
                    "existing private events do not have a server-owned put key");
        }
        var input = JsonNodeFactory.instance.objectNode();
        input.put("tenant_surrogate", tenantSurrogate);
        input.put("case_id", caseId);
        input.put("command_id", commandId);
        input.put("source_kind", sourceKind.name());
        return PREFIX + ContractJson.sha256Hex(input);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
