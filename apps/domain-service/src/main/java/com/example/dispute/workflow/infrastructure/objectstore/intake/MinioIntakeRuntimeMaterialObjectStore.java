package com.example.dispute.workflow.infrastructure.objectstore.intake;

import com.example.dispute.workflow.infrastructure.objectstore.intake.IntakeRuntimeMaterialObjectStore.ReadRequest;
import com.example.dispute.workflow.infrastructure.objectstore.intake.IntakeRuntimeMaterialObjectStore.StoredObject;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.net.URI;
import java.util.Objects;

/** MinIO exact-version reader restricted to one configured private bucket and key prefix. */
public final class MinioIntakeRuntimeMaterialObjectStore
        implements IntakeRuntimeMaterialObjectStore {

    private final MinioClient minio;
    private final String bucket;
    private final String prefix;

    public MinioIntakeRuntimeMaterialObjectStore(
            MinioClient minio, String bucket, String prefix) {
        this.minio = Objects.requireNonNull(minio, "minio must not be null");
        this.bucket = requireBucket(bucket);
        this.prefix = requirePrefix(prefix);
    }

    @Override
    public StoredObject readExact(ReadRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        URI uri = URI.create(request.uri());
        String key = requireConfiguredLocation(uri);
        byte[] content;
        try (var input = minio.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .versionId(request.objectVersion())
                .build())) {
            content = input.readNBytes(Math.toIntExact(request.sizeBytes()) + 1);
        } catch (Exception failure) {
            throw new IllegalStateException("synthetic runtime material object load failed", failure);
        }
        if (content.length != request.sizeBytes()) {
            throw new IllegalStateException(
                    "synthetic runtime material object size differs from its immutable reference");
        }
        return new StoredObject(
                request.artifactId(),
                request.schemaVersion(),
                request.uri(),
                request.objectVersion(),
                request.contentHash(),
                request.sizeBytes(),
                content);
    }

    private String requireConfiguredLocation(URI uri) {
        if (!"minio".equals(uri.getScheme()) || !bucket.equals(uri.getRawAuthority())) {
            throw new SecurityException(
                    "synthetic runtime material URI is outside the configured private bucket");
        }
        String path = uri.getRawPath();
        String key = path == null || path.length() < 2 ? "" : path.substring(1);
        if (!key.startsWith(prefix + "/")) {
            throw new SecurityException(
                    "synthetic runtime material URI is outside the configured private prefix");
        }
        return key;
    }

    private static String requireBucket(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw new IllegalArgumentException("runtime material MinIO bucket is invalid");
        }
        return value;
    }

    private static String requirePrefix(String value) {
        if (value == null
                || value.length() > 256
                || !value.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")) {
            throw new IllegalArgumentException("runtime material MinIO prefix is invalid");
        }
        return value;
    }
}
