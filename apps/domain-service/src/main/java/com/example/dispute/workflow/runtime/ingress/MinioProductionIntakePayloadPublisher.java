package com.example.dispute.workflow.runtime.ingress;

import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.authority.payload.IntakeBranchCommand;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.minio.BucketExistsArgs;
import io.minio.PutObjectArgs;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** Content-addressed publisher for the production-only browser Intake command payload. */
public final class MinioProductionIntakePayloadPublisher
    implements IntakeImmutablePayloadPublisher {

  private static final Map<String, String> HASH_FIELDS =
      Map.of(
          "intake-domain-snapshot.v2", "snapshot_hash",
          "intake-turn-event.v2", "event_hash");
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final MinioClient minio;
  private final String bucket;
  private final String prefix;
  private volatile PreparationState preparationState = PreparationState.NEW;
  private IllegalStateException preparationFailure;

  public MinioProductionIntakePayloadPublisher(
      MinioClient minio, String bucket, String prefix) {
    this.minio = Objects.requireNonNull(minio, "minio");
    if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
      throw new IllegalArgumentException("target Intake bucket is invalid");
    }
    if (prefix == null || !prefix.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")) {
      throw new IllegalArgumentException("target Intake object prefix is invalid");
    }
    this.bucket = bucket;
    this.prefix = prefix;
  }

  /** Performs the write-free target API readiness proof exactly once on this publisher. */
  public synchronized MinioProductionIntakePayloadPublisher prepare() {
    if (preparationState == PreparationState.READY) {
      return this;
    }
    if (preparationState == PreparationState.FAILED) {
      throw preparationFailure;
    }
    try {
      initializeCanonicalValidation();
      if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
        throw new IllegalStateException("target Intake payload bucket is missing");
      }
      preparationState = PreparationState.READY;
      return this;
    } catch (Exception failure) {
      preparationFailure =
          new IllegalStateException("target Intake payload readiness failed", failure);
      preparationState = PreparationState.FAILED;
      throw preparationFailure;
    }
  }

  @Override
  public StoredPayload publish(PublishRequest request) {
    if (preparationState != PreparationState.READY) {
      throw new IllegalStateException("target Intake payload publisher is not ready");
    }
    Objects.requireNonNull(request, "request");
    String hashField = HASH_FIELDS.get(request.schemaVersion());
    if (hashField == null && !IntakeBranchCommand.SCHEMA_VERSION.equals(request.schemaVersion())) {
      throw new IllegalArgumentException("target Intake payload schema is invalid");
    }
    byte[] payload = request.canonicalPayload();
    if (hashField == null) {
      requireCanonicalBranchHash(payload, request.contentSha256());
    } else {
      requireCanonicalSelfHash(payload, hashField, request.contentSha256());
    }
    String objectKey =
        prefix
            + "/"
            + request.schemaVersion()
            + "/"
            + request.artifactId()
            + "/"
            + request.contentSha256()
            + ".json";
    try (ByteArrayInputStream input = new ByteArrayInputStream(payload)) {
      minio.putObject(
          PutObjectArgs.builder()
              .bucket(bucket)
              .object(objectKey)
              .contentType("application/json")
              .userMetadata(
                  Map.of(
                      "artifact-id", request.artifactId(),
                      "schema-version", request.schemaVersion(),
                      "content-sha256", request.contentSha256()))
              .stream(input, payload.length, -1)
              .build());
    } catch (Exception failure) {
      throw new IllegalStateException("target Intake payload publication failed", failure);
    }
    return new StoredPayload(
        request.artifactId(),
        request.schemaVersion(),
        "minio://" + bucket + "/" + objectKey,
        request.contentSha256(),
        request.contentSha256(),
        payload.length);
  }

  private static void initializeCanonicalValidation() {
    ObjectNode selfHashed =
        MAPPER
            .createObjectNode()
            .put("schema_version", "intake-domain-snapshot.v2")
        .put("snapshot_hash", "0".repeat(64));
    String selfHash = IntakeContractHashes.canonicalHashExcluding(selfHashed, "snapshot_hash");
    selfHashed.put("snapshot_hash", selfHash);
    requireCanonicalSelfHash(
        ContractJson.canonicalize(selfHashed), "snapshot_hash", selfHash);

    ObjectNode branch =
        MAPPER.createObjectNode().put("schema_version", IntakeBranchCommand.SCHEMA_VERSION);
    requireCanonicalBranchHash(
        ContractJson.canonicalize(branch), ContractJson.sha256Hex(branch));
  }

  static void requireCanonicalSelfHash(byte[] payload, String hashField, String expectedHash) {
    try {
      JsonNode document = MAPPER.readTree(payload);
      JsonNode embeddedHash = document == null ? null : document.get(hashField);
      if (embeddedHash == null
          || !embeddedHash.isTextual()
          || !expectedHash.equals(embeddedHash.textValue())
          || !expectedHash.equals(IntakeContractHashes.canonicalHashExcluding(document, hashField))
          || !Arrays.equals(payload, ContractJson.canonicalize(document))) {
        throw new IllegalArgumentException("target Intake payload hash is invalid");
      }
    } catch (IOException | IllegalArgumentException failure) {
      if (failure instanceof IllegalArgumentException argumentFailure
          && "target Intake payload hash is invalid".equals(argumentFailure.getMessage())) {
        throw argumentFailure;
      }
      throw new IllegalArgumentException("target Intake payload hash is invalid", failure);
    }
  }

  static void requireCanonicalBranchHash(byte[] payload, String expectedHash) {
    try {
      JsonNode document = MAPPER.readTree(payload);
      if (document == null
          || !IntakeBranchCommand.SCHEMA_VERSION.equals(document.path("schema_version").asText())
          || !expectedHash.equals(ContractJson.sha256Hex(document))
          || !Arrays.equals(payload, ContractJson.canonicalize(document))) {
        throw new IllegalArgumentException("target Intake branch payload hash is invalid");
      }
    } catch (IOException | IllegalArgumentException failure) {
      if (failure instanceof IllegalArgumentException argumentFailure
          && "target Intake branch payload hash is invalid".equals(argumentFailure.getMessage())) {
        throw argumentFailure;
      }
      throw new IllegalArgumentException("target Intake branch payload hash is invalid", failure);
    }
  }

  private enum PreparationState {
    NEW,
    READY,
    FAILED
  }
}
