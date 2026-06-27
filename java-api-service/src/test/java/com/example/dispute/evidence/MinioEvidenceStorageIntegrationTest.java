package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.config.AppProperties;
import com.example.dispute.evidence.application.EvidenceStorage;
import com.example.dispute.evidence.infrastructure.MinioEvidenceStorage;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class MinioEvidenceStorageIntegrationTest {

    private static final String ACCESS_KEY = "minio-test-user";
    private static final String SECRET_KEY = "minio-test-password";
    private static final String BUCKET = "evidence-original";

    @Container
    private static final GenericContainer<?> MINIO =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "quay.io/minio/minio:RELEASE.2024-06-13T22-53-53Z"))
                    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                    .withCommand("server", "/data")
                    .withExposedPorts(9000)
                    .waitingFor(
                            Wait.forHttp("/minio/health/ready")
                                    .forPort(9000)
                                    .forStatusCode(200));

    @Test
    void storesOriginalObjectInPrivateEvidenceBucket() throws Exception {
        String endpoint =
                "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        MinioClient client =
                MinioClient.builder()
                        .endpoint(endpoint)
                        .credentials(ACCESS_KEY, SECRET_KEY)
                        .build();
        client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        AppProperties properties =
                new AppProperties(
                        "test",
                        new AppProperties.Security("java-secret"),
                        new AppProperties.Integration("http://agent", "secret", 1000),
                        new AppProperties.Integration("http://ocr", "secret", 1000),
                        new AppProperties.Temporal("localhost:7233", "default", "queue"),
                        new AppProperties.Minio(
                                endpoint,
                                ACCESS_KEY,
                                SECRET_KEY,
                                BUCKET,
                                "evidence-desensitized"),
                        new AppProperties.Elasticsearch("http://elasticsearch"),
                        new AppProperties.Feature(true, true, true, true, true, true, false),
                        new AppProperties.Logging(true, true));
        MinioEvidenceStorage storage = new MinioEvidenceStorage(client, properties);

        EvidenceStorage.StoredObject stored =
                storage.storeOriginal(
                        "CASE_minio",
                        "EVIDENCE_minio",
                        "proof.txt",
                        "text/plain",
                        "immutable evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        var stat =
                client.statObject(
                        StatObjectArgs.builder()
                                .bucket(stored.bucket())
                                .object(stored.objectKey())
                                .build());
        assertThat(stored.bucket()).isEqualTo(BUCKET);
        assertThat(stored.objectKey())
                .isEqualTo("CASE_minio/EVIDENCE_minio/proof.txt");
        assertThat(stat.size()).isEqualTo(18);
        assertThat(stat.contentType()).isEqualTo("text/plain");
    }
}
