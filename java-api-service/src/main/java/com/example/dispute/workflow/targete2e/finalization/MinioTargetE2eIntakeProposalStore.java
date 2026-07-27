package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeProposalLoadException;
import com.example.dispute.workflow.application.intake.IntakeProposalReference;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Objects;

/** Private content-addressed MinIO adapter for target-E2E proposal metadata and bytes. */
public final class MinioTargetE2eIntakeProposalStore implements TargetE2eIntakeProposalStore {

    private final MinioClient minio;
    private final String bucket;
    private final String prefix;

    public MinioTargetE2eIntakeProposalStore(
            MinioClient minio, String bucket, String prefix) {
        this.minio = Objects.requireNonNull(minio, "minio");
        this.bucket = requireBucket(bucket);
        this.prefix = requirePrefix(prefix);
    }

    @Override
    public ProposalMetadata resolve(ArtifactPointer pointer) {
        Objects.requireNonNull(pointer, "pointer");
        String key = requireExactLocation(pointer);
        try {
            var stat = minio.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(key).build());
            Map<String, String> metadata = stat.userMetadata();
            requireMetadata(metadata, "artifact-id", pointer.artifactId());
            requireMetadata(metadata, "schema-version", pointer.schemaVersion());
            requireMetadata(metadata, "content-sha256", pointer.sha256());
            requireMetadata(metadata, "object-version", pointer.sha256());
            return new ProposalMetadata(
                    pointer.artifactId(),
                    pointer.schemaVersion(),
                    pointer.uri(),
                    pointer.sha256(),
                    pointer.sha256(),
                    stat.size());
        } catch (Exception failure) {
            throw classify("proposal metadata load failed", failure);
        }
    }

    @Override
    public StoredProposal readExact(IntakeProposalReference reference) {
        Objects.requireNonNull(reference, "reference");
        String key = requireExactLocation(new ArtifactPointer(
                reference.artifactId(),
                reference.schemaVersion(),
                reference.uri(),
                reference.sha256()));
        if (!reference.sha256().equals(reference.objectVersion())) {
            throw rejected(
                    "INTAKE_PROPOSAL_OBJECT_VERSION_MISMATCH",
                    "target-E2E proposal version must equal its content address");
        }
        byte[] payload;
        try (var input = minio.getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            payload = input.readNBytes(Math.toIntExact(reference.sizeBytes()) + 1);
        } catch (Exception failure) {
            throw classify("proposal object load failed", failure);
        }
        return new StoredProposal(
                reference.artifactId(),
                reference.schemaVersion(),
                reference.uri(),
                reference.objectVersion(),
                reference.sha256(),
                reference.sizeBytes(),
                payload);
    }

    private String requireExactLocation(ArtifactPointer pointer) {
        URI uri;
        try {
            uri = URI.create(pointer.uri());
        } catch (IllegalArgumentException failure) {
            throw rejected("INTAKE_PROPOSAL_URI_FORBIDDEN", "proposal URI is invalid", failure);
        }
        String expectedKey = prefix
                + '/'
                + pointer.schemaVersion()
                + '/'
                + pointer.artifactId()
                + '/'
                + pointer.sha256()
                + ".json";
        String path = uri.getRawPath();
        String actualKey = path == null || path.length() < 2 ? "" : path.substring(1);
        if (!"minio".equals(uri.getScheme())
                || !bucket.equals(uri.getRawAuthority())
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !expectedKey.equals(actualKey)) {
            throw rejected(
                    "INTAKE_PROPOSAL_URI_FORBIDDEN",
                    "proposal URI is outside the configured content address");
        }
        return expectedKey;
    }

    private static void requireMetadata(
            Map<String, String> metadata, String name, String expected) {
        if (metadata == null || !expected.equals(metadata.get(name))) {
            throw rejected(
                    "INTAKE_PROPOSAL_OBJECT_METADATA_MISMATCH",
                    "proposal object metadata is not exact");
        }
    }

    private static RuntimeException classify(String message, Exception failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof ConnectException) {
                return new IntakeProposalLoadException(message, failure);
            }
            current = current.getCause();
        }
        if (failure instanceof ErrorResponseException response) {
            String code = response.errorResponse() == null
                    ? null
                    : response.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
                return rejected("INTAKE_PROPOSAL_OBJECT_NOT_FOUND", message, failure);
            }
            if ("AccessDenied".equals(code)) {
                return rejected("INTAKE_PROPOSAL_OBJECT_ACCESS_DENIED", message, failure);
            }
        }
        if (failure instanceof IOException) {
            return new IntakeProposalLoadException(message, failure);
        }
        return rejected("INTAKE_PROPOSAL_ACCESS_UNCLASSIFIED", message, failure);
    }

    private static String requireBucket(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw new IllegalArgumentException("target-E2E proposal bucket is invalid");
        }
        return value;
    }

    private static String requirePrefix(String value) {
        if (value == null
                || value.length() > 256
                || !value.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")) {
            throw new IllegalArgumentException("target-E2E proposal prefix is invalid");
        }
        return value;
    }

    private static IntakeFinalizationRejectedException rejected(
            String code, String message) {
        return new IntakeFinalizationRejectedException(code, message);
    }

    private static IntakeFinalizationRejectedException rejected(
            String code, String message, Throwable cause) {
        return new IntakeFinalizationRejectedException(code, message, cause);
    }
}
