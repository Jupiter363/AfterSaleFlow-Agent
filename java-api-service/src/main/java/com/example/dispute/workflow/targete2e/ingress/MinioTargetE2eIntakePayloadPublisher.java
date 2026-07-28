package com.example.dispute.workflow.targete2e.ingress;

import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.minio.PutObjectArgs;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** Content-addressed publisher for the target-only browser Intake command payload. */
public final class MinioTargetE2eIntakePayloadPublisher
    implements IntakeImmutablePayloadPublisher {

  private static final Map<String, String> HASH_FIELDS =
      Map.of(
          "intake-domain-snapshot.v2", "snapshot_hash",
          "intake-turn-event.v2", "event_hash");
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final MinioClient minio;
  private final String bucket;
  private final String prefix;

  public MinioTargetE2eIntakePayloadPublisher(
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

  @Override
  public StoredPayload publish(PublishRequest request) {
    Objects.requireNonNull(request, "request");
    String hashField = HASH_FIELDS.get(request.schemaVersion());
    if (hashField == null) {
      throw new IllegalArgumentException("target Intake payload schema is invalid");
    }
    byte[] payload = request.canonicalPayload();
    requireCanonicalSelfHash(payload, hashField, request.contentSha256());
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
}
