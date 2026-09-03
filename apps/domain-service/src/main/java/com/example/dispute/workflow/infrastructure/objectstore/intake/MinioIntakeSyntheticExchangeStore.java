package com.example.dispute.workflow.infrastructure.objectstore.intake;

import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeCanonicalPayloadValidator;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangePayloadObjectStoreGateway;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeUris;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

/** Private content-addressed MinIO store for the signed-synthetic Intake lane. */
public final class MinioIntakeSyntheticExchangeStore
        implements IntakeImmutablePayloadPublisher, IntakeExchangePayloadObjectStoreGateway {

    private final MinioClient minio;
    private final IntakeExchangeCanonicalPayloadValidator validator;
    private final String bucket;
    private final String prefix;

    public MinioIntakeSyntheticExchangeStore(
            MinioClient minio,
            IntakeExchangeCanonicalPayloadValidator validator,
            String bucket,
            String prefix) {
        this.minio = Objects.requireNonNull(minio, "minio");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.bucket = requireBucket(bucket);
        this.prefix = requirePrefix(prefix);
    }

    @Override
    public IntakeImmutablePayloadPublisher.StoredPayload publish(PublishRequest request) {
        Objects.requireNonNull(request, "request");
        byte[] payload = request.canonicalPayload();
        validator.requireValid(
                request.schemaVersion(),
                request.contentSha256(),
                payload.length,
                payload);
        String objectKey = objectKey(
                request.schemaVersion(), request.artifactId(), request.contentSha256());
        try (ByteArrayInputStream input = new ByteArrayInputStream(payload)) {
            minio.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .contentType("application/json")
                    .userMetadata(metadata(request))
                    .stream(input, payload.length, -1)
                    .build());
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "signed synthetic Intake object publication failed", failure);
        }
        return new IntakeImmutablePayloadPublisher.StoredPayload(
                request.artifactId(),
                request.schemaVersion(),
                uri(objectKey),
                request.contentSha256(),
                request.contentSha256(),
                payload.length);
    }

    @Override
    public IntakeExchangePayloadObjectStoreGateway.StoredPayload readExact(ReadRequest request) {
        Objects.requireNonNull(request, "request");
        String objectKey = objectKey(request.schemaVersion(), request.artifactId(), request.sha256());
        String expectedUri = uri(objectKey);
        if (!expectedUri.equals(request.uri()) || !request.sha256().equals(request.objectVersion())) {
            throw new SecurityException(
                    "signed synthetic Intake object reference is outside its content address");
        }
        byte[] payload;
        try (var input = minio.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            payload = input.readNBytes(Math.toIntExact(request.sizeBytes()) + 1);
        } catch (Exception failure) {
            throw new IllegalStateException("signed synthetic Intake object load failed", failure);
        }
        if (payload.length != request.sizeBytes()) {
            throw new IllegalStateException(
                    "signed synthetic Intake object size differs from its immutable reference");
        }
        validator.requireValid(
                request.schemaVersion(), request.sha256(), request.sizeBytes(), payload);
        return new IntakeExchangePayloadObjectStoreGateway.StoredPayload(
                request.artifactId(),
                request.schemaVersion(),
                request.uri(),
                request.objectVersion(),
                request.sha256(),
                request.sizeBytes(),
                payload);
    }

    private Map<String, String> metadata(PublishRequest request) {
        return Map.of(
                "artifact-id", request.artifactId(),
                "schema-version", request.schemaVersion(),
                "content-sha256", request.contentSha256(),
                "object-version", request.contentSha256());
    }

    private String objectKey(String schemaVersion, String artifactId, String sha256) {
        return prefix + "/" + schemaVersion + "/" + artifactId + "/" + sha256 + ".json";
    }

    private String uri(String objectKey) {
        return IntakeExchangeUris.requireCanonical("minio://" + bucket + "/" + objectKey);
    }

    private static String requireBucket(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw new IllegalArgumentException("Intake synthetic MinIO bucket is invalid");
        }
        return value;
    }

    private static String requirePrefix(String value) {
        if (value == null
                || value.length() > 256
                || !value.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")) {
            throw new IllegalArgumentException("Intake synthetic MinIO prefix is invalid");
        }
        URI.create("minio://bucket/" + value);
        return value;
    }
}
