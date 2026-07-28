package com.example.dispute.workflow.targete2e.ingress;

import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import io.minio.PutObjectArgs;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** Content-addressed publisher for the target-only browser Intake command payload. */
public final class MinioTargetE2eIntakePayloadPublisher
    implements IntakeImmutablePayloadPublisher {

  private static final java.util.Set<String> SUPPORTED_SCHEMAS =
      java.util.Set.of("intake-domain-snapshot.v2", "intake-turn-event.v2");

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
    if (!SUPPORTED_SCHEMAS.contains(request.schemaVersion())) {
      throw new IllegalArgumentException("target Intake payload schema is invalid");
    }
    byte[] payload = request.canonicalPayload();
    if (!request.contentSha256().equals(sha256(payload))) {
      throw new IllegalArgumentException("target Intake payload hash is invalid");
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

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }
}
