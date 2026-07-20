package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Objects;

/** Canonical NON_NULL wire codec for intake-finalization-receipt.v1. */
public final class IntakeFinalizationReceiptCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    static {
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private IntakeFinalizationReceiptCodec() {}

    public static byte[] canonicalBytes(IntakeFinalizationReceipt receipt) {
        return ContractJson.canonicalize(toTree(receipt));
    }

    public static JsonNode toTree(IntakeFinalizationReceipt receipt) {
        return MAPPER.valueToTree(Objects.requireNonNull(receipt, "receipt"));
    }

    static String receiptHash(IntakeFinalizationReceipt receipt) {
        return IntakeContractHashes.canonicalHashExcluding(toTree(receipt), "receipt_hash");
    }
}
