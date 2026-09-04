package com.example.dispute.workflow.runtime.ingress.rooms;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.SnapshotRef;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomExchangeContract.Authority;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomObjectIndex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Objects;

/** Java-owned, content-addressed input document for a target non-Intake graph invocation. */
public final class MinioProductionRoomCommandPayloadPublisher {
    public static final String SCHEMA_VERSION = "production-runtime-room-command-input.v1";

    private final MinioClient minio;
    private final ObjectMapper mapper;
    private final String bucket;
    private final String prefix;
    private final ProductionRoomObjectIndex objectIndex;

    public MinioProductionRoomCommandPayloadPublisher(
            MinioClient minio, ObjectMapper mapper, String bucket, String prefix,
            ProductionRoomObjectIndex objectIndex) {
        this.minio = Objects.requireNonNull(minio, "minio");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.objectIndex = Objects.requireNonNull(objectIndex, "objectIndex");
    }

    public PublishedObject publish(String artifactId, String roomType, String commandId, String objectRole, JsonNode source) {
        if (artifactId == null || !artifactId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("target room artifactId is invalid");
        }
        ObjectNode document = mapper.createObjectNode();
        document.put("schema_version", SCHEMA_VERSION);
        document.put("room_type", roomType);
        document.put("command_id", commandId);
        document.put("object_role", objectRole);
        document.set("source", Objects.requireNonNull(source, "source").deepCopy());
        return publishCanonical(artifactId, roomType, document);
    }

    /** Publishes an exact canonical room document, used by specialized invocation builders. */
    public PublishedObject publishCanonical(String artifactId, String roomType, JsonNode document) {
        if (artifactId == null || !artifactId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("target room artifactId is invalid");
        }
        Objects.requireNonNull(document, "document");
        String schemaVersion = document.path("schema_version").asText(null);
        if (schemaVersion == null || !schemaVersion.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("target room canonical document schema is invalid");
        }
        byte[] body = ContractJson.canonicalize(document);
        String hash = ContractJson.sha256Hex(document);
        String key = prefix + "/" + roomType.toLowerCase() + "/" + hash + ".json";
        try (ByteArrayInputStream input = new ByteArrayInputStream(body)) {
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .contentType("application/json")
                    .userMetadata(Map.of("artifact-id", artifactId, "schema-version", SCHEMA_VERSION,
                            "content-sha256", hash))
                    .stream(input, body.length, -1).build());
        } catch (Exception failure) {
            throw new IllegalStateException("target room command input publication failed", failure);
        }
        SnapshotRef reference = new SnapshotRef(artifactId, schemaVersion,
                "urn:production-runtime:object:" + artifactId + ":" + hash, hash, body.length);
        return new PublishedObject(reference, bucket, key);
    }

    /** Opaque stable reference for a canonical document whose private body binds its own URI. */
    public PublishedObject publishCanonicalOpaque(String artifactId, String roomType, JsonNode document) {
        PublishedObject stored = publishCanonical(artifactId, roomType, document);
        return new PublishedObject(new SnapshotRef(artifactId, stored.reference().schemaVersion(),
                "urn:production-runtime:object:" + artifactId, stored.reference().sha256(), stored.reference().sizeBytes()),
                stored.bucket(), stored.key());
    }

    /** The caller binds the already-published opaque object after it has created the exact command. */
    public void bind(Authority authority, RoomGraphCommand command,
            PublishedObject object, ProductionRoomObjectIndex.Kind kind) {
        objectIndex.bindInput(authority, command, new ProductionRoomObjectIndex.StoredObject(
                object.reference().uri(), object.reference().artifactId(), object.reference().schemaVersion(),
                object.reference().sha256(), object.reference().sizeBytes(), object.bucket(), object.key()), kind);
    }

    public record PublishedObject(SnapshotRef reference, String bucket, String key) {}
}
