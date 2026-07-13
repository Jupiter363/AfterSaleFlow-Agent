/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：验证Minio证据存储Integration，覆盖 「storesOriginalObjectInPrivateEvidenceBucket」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
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

// 所属模块：【证据与版本化卷宗 / 自动化测试层】类型「MinioEvidenceStorageIntegrationTest」。
// 类型职责：集中验证Minio证据存储Integration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「storesOriginalObjectInPrivateEvidenceBucket」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
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

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「MinioEvidenceStorageIntegrationTest.storesOriginalObjectInPrivateEvidenceBucket()」。
    // 具体功能：「MinioEvidenceStorageIntegrationTest.storesOriginalObjectInPrivateEvidenceBucket()」：复现“核对完整业务行为（场景方法「storesOriginalObjectInPrivateEvidenceBucket」）”场景：驱动 「client.makeBucket」、「storage.storeOriginal」、「client.statObject」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「:」、「test」、「java-secret」、「secret」。
    // 上游调用：「MinioEvidenceStorageIntegrationTest.storesOriginalObjectInPrivateEvidenceBucket()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「MinioEvidenceStorageIntegrationTest.storesOriginalObjectInPrivateEvidenceBucket()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「MinioEvidenceStorageIntegrationTest.storesOriginalObjectInPrivateEvidenceBucket()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「:」、「test」、「java-secret」、「secret」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
