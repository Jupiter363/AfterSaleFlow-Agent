package com.example.dispute.evidence.infrastructure;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.ExternalServiceException;
import com.example.dispute.config.AppProperties;
import com.example.dispute.evidence.application.EvidenceStorage;
import io.minio.MinioClient;
import io.minio.GetObjectArgs;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MinioEvidenceStorage implements EvidenceStorage {

    private final MinioClient minioClient;
    private final String originalBucket;

    public MinioEvidenceStorage(MinioClient minioClient, AppProperties properties) {
        this.minioClient = minioClient;
        this.originalBucket = properties.minio().evidenceOriginalBucket();
    }

    @Override
    public StoredObject storeOriginal(
            String caseId,
            String evidenceId,
            String filename,
            String contentType,
            byte[] content) {
        String objectKey = caseId + "/" + evidenceId + "/" + filename;
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(originalBucket)
                            .object(objectKey)
                            .contentType(contentType)
                            .stream(input, content.length, -1)
                            .build());
            return new StoredObject(originalBucket, objectKey);
        } catch (Exception exception) {
            throw new ExternalServiceException(
                    ErrorCode.EVIDENCE_UPLOAD_FAILED,
                    "evidence object storage failed",
                    Map.of("case_id", caseId, "evidence_id", evidenceId));
        }
    }

    @Override
    public byte[] loadOriginal(String bucket, String objectKey) {
        try (var input =
                minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(bucket)
                                .object(objectKey)
                                .build())) {
            return input.readAllBytes();
        } catch (Exception exception) {
            throw new ExternalServiceException(
                    ErrorCode.EVIDENCE_NOT_FOUND,
                    "evidence object not found",
                    Map.of("bucket", bucket, "object_key", objectKey));
        }
    }
}
