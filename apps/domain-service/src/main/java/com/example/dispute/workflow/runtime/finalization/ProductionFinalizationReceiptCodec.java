package com.example.dispute.workflow.runtime.finalization;

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
public final class ProductionFinalizationReceiptCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    static {
        MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private ProductionFinalizationReceiptCodec() {}

    public static byte[] canonicalBytes(ProductionFinalizationReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        receipt.requireCanonicalHash();
        return ContractJson.canonicalize(toTree(receipt));
    }

    public static ProductionFinalizationReceipt decodeCanonical(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < 2 || bytes.length > 65_536) {
            throw rejected("PRODUCTION_RUNTIME_RECEIPT_BYTES_INVALID", "receipt byte length is invalid");
        }
        try {
            JsonNode tree = MAPPER.readTree(bytes);
            if (!Arrays.equals(bytes, ContractJson.canonicalize(tree))) {
                throw rejected(
                        "PRODUCTION_RUNTIME_RECEIPT_BYTES_NON_CANONICAL",
                        "stored receipt bytes are not RFC 8785 canonical bytes");
            }
            ProductionFinalizationReceipt receipt = MAPPER.treeToValue(
                    tree, ProductionFinalizationReceipt.class);
            receipt.requireCanonicalHash();
            return receipt;
        } catch (ProductionFinalizationRejectedException failure) {
            throw failure;
        } catch (IOException | IllegalArgumentException failure) {
            throw new ProductionFinalizationRejectedException(
                    "PRODUCTION_RUNTIME_RECEIPT_BYTES_INVALID",
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
                    "PRODUCTION_RUNTIME_AGENT_MANIFEST_HASH_MISMATCH",
                    "agent_run_manifest_hash is not the full validated manifest hash");
        }
        return actual;
    }

    static JsonNode toTree(ProductionFinalizationReceipt receipt) {
        return MAPPER.valueToTree(Objects.requireNonNull(receipt, "receipt"));
    }

    static String receiptHash(ProductionFinalizationReceipt receipt) {
        ObjectNode preimage = toTree(receipt).deepCopy();
        preimage.remove("receipt_hash");
        return ContractJson.sha256Hex(preimage);
    }

    private static ProductionFinalizationRejectedException rejected(
            String code, String message) {
        return new ProductionFinalizationRejectedException(code, message);
    }
}
