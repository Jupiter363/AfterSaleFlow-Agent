package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.contract.v1.AgentExecutionManifest;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/** RFC 8785 codec and exact hash-source checks for the target finalization receipt. */
public final class TargetE2eFinalizationReceiptCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    static {
        MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private TargetE2eFinalizationReceiptCodec() {}

    public static byte[] canonicalBytes(TargetE2eFinalizationReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        receipt.requireCanonicalHash();
        return ContractJson.canonicalize(toTree(receipt));
    }

    public static TargetE2eFinalizationReceipt decodeCanonical(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < 2 || bytes.length > 65_536) {
            throw rejected("TARGET_E2E_RECEIPT_BYTES_INVALID", "receipt byte length is invalid");
        }
        try {
            JsonNode tree = MAPPER.readTree(bytes);
            if (!Arrays.equals(bytes, ContractJson.canonicalize(tree))) {
                throw rejected(
                        "TARGET_E2E_RECEIPT_BYTES_NON_CANONICAL",
                        "stored receipt bytes are not RFC 8785 canonical bytes");
            }
            TargetE2eFinalizationReceipt receipt = MAPPER.treeToValue(
                    tree, TargetE2eFinalizationReceipt.class);
            receipt.requireCanonicalHash();
            return receipt;
        } catch (TargetE2eFinalizationRejectedException failure) {
            throw failure;
        } catch (IOException | IllegalArgumentException failure) {
            throw new TargetE2eFinalizationRejectedException(
                    "TARGET_E2E_RECEIPT_BYTES_INVALID",
                    "stored receipt bytes cannot be decoded",
                    failure);
        }
    }

    public static String requireManifestHash(
            AgentExecutionManifest manifest, String expectedHash) {
        Objects.requireNonNull(manifest, "manifest");
        String actual = ContractJson.sha256Hex(MAPPER.valueToTree(manifest));
        if (!actual.equals(expectedHash)) {
            throw rejected(
                    "TARGET_E2E_AGENT_MANIFEST_HASH_MISMATCH",
                    "agent_run_manifest_hash is not the full validated manifest hash");
        }
        return actual;
    }

    static JsonNode toTree(TargetE2eFinalizationReceipt receipt) {
        return MAPPER.valueToTree(Objects.requireNonNull(receipt, "receipt"));
    }

    static String receiptHash(TargetE2eFinalizationReceipt receipt) {
        ObjectNode preimage = toTree(receipt).deepCopy();
        preimage.remove("receipt_hash");
        return ContractJson.sha256Hex(preimage);
    }

    private static TargetE2eFinalizationRejectedException rejected(
            String code, String message) {
        return new TargetE2eFinalizationRejectedException(code, message);
    }
}
