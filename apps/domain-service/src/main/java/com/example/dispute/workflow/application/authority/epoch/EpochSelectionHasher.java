package com.example.dispute.workflow.application.authority.epoch;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Objects;

/** RFC 8785 hash for the room-epoch-selection.v2 wire object excluding selection_hash. */
public final class EpochSelectionHasher {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private EpochSelectionHasher() {}

    public static String hash(SelectionHashInput input) {
        return ContractJson.sha256Hex(MAPPER.valueToTree(Objects.requireNonNull(input, "input")));
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SelectionHashInput(
            String schemaVersion,
            RoomType roomType,
            WriterMode writerMode,
            String caseWorkflowType,
            String caseWorkflowBuildId,
            String roomWorkflowType,
            String roomWorkflowBuildId,
            String processContractVersion,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String stateSchemaVersion,
            String streamProtocol,
            String promptVersion,
            String modelProfileId,
            String outputSchemaVersion,
            String policyVersion,
            String guardrailVersion,
            String toolPolicyVersion,
            String cohortPolicyVersion) {

        public SelectionHashInput {
            if (!"room-epoch-selection.v2".equals(schemaVersion)) {
                throw new IllegalArgumentException("schemaVersion must be room-epoch-selection.v2");
            }
            Objects.requireNonNull(roomType, "roomType must not be null");
            Objects.requireNonNull(writerMode, "writerMode must not be null");
            required(caseWorkflowType, "caseWorkflowType");
            required(caseWorkflowBuildId, "caseWorkflowBuildId");
            required(roomWorkflowType, "roomWorkflowType");
            required(roomWorkflowBuildId, "roomWorkflowBuildId");
            required(processContractVersion, "processContractVersion");
            required(graphKey, "graphKey");
            required(graphVersion, "graphVersion");
            required(checkpointSchemaVersion, "checkpointSchemaVersion");
            required(stateSchemaVersion, "stateSchemaVersion");
            required(streamProtocol, "streamProtocol");
            required(promptVersion, "promptVersion");
            required(modelProfileId, "modelProfileId");
            required(outputSchemaVersion, "outputSchemaVersion");
            required(policyVersion, "policyVersion");
            required(guardrailVersion, "guardrailVersion");
            required(toolPolicyVersion, "toolPolicyVersion");
            required(cohortPolicyVersion, "cohortPolicyVersion");
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value;
        }
    }
}
