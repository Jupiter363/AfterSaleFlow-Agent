package com.example.dispute.workflow.contract.v1;

import static com.example.dispute.workflow.contract.v1.ContractTypes.immutableList;
import static com.example.dispute.workflow.contract.v1.ContractTypes.required;
import static com.example.dispute.workflow.contract.v1.ContractTypes.version;

import com.example.dispute.workflow.contract.v1.ContractTypes.ParentRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.Visibility;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ArtifactRef(
        String schemaVersion,
        String artifactId,
        String artifactType,
        String contentSchemaVersion,
        String storageRef,
        String contentHash,
        long sizeBytes,
        List<ParentRef> parentRefs,
        Visibility visibility,
        String createdByRunId,
        Instant createdAt) {

    public ArtifactRef {
        schemaVersion = version(schemaVersion, "artifact-ref.v1");
        required(artifactId, "artifactId");
        required(artifactType, "artifactType");
        required(contentSchemaVersion, "contentSchemaVersion");
        required(storageRef, "storageRef");
        required(contentHash, "contentHash");
        parentRefs = immutableList(parentRefs, "parentRefs");
        required(visibility, "visibility");
        required(createdByRunId, "createdByRunId");
        required(createdAt, "createdAt");
    }
}
