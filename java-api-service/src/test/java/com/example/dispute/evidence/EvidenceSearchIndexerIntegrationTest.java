/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：验证证据检索索引Integration，覆盖 「indexesEvidenceMetadataIntoSearchableEvidenceIndex」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.evidence.application.EvidenceView;
import com.example.dispute.evidence.infrastructure.RestClientEvidenceSearchIndexer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// 所属模块：【证据与版本化卷宗 / 自动化测试层】类型「EvidenceSearchIndexerIntegrationTest」。
// 类型职责：集中验证证据检索索引Integration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「indexesEvidenceMetadataIntoSearchableEvidenceIndex」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Testcontainers
class EvidenceSearchIndexerIntegrationTest {

    @Container
    private static final GenericContainer<?> ELASTICSEARCH =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "docker.elastic.co/elasticsearch/elasticsearch:8.13.4"))
                    .withEnv("discovery.type", "single-node")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("xpack.security.enrollment.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m")
                    .withExposedPorts(9200)
                    .waitingFor(
                            Wait.forHttp("/_cluster/health")
                                    .forPort(9200)
                                    .forStatusCode(200)
                                    .withStartupTimeout(java.time.Duration.ofMinutes(2)));

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceSearchIndexerIntegrationTest.indexesEvidenceMetadataIntoSearchableEvidenceIndex()」。
    // 具体功能：「EvidenceSearchIndexerIntegrationTest.indexesEvidenceMetadataIntoSearchableEvidenceIndex()」：复现“核对完整业务行为（场景方法「indexesEvidenceMetadataIntoSearchableEvidenceIndex」）”场景：驱动 「HttpClient.newHttpClient」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「:」、「EVIDENCE_search」、「CASE_search」、「LOGISTICS_PROOF」。
    // 上游调用：「EvidenceSearchIndexerIntegrationTest.indexesEvidenceMetadataIntoSearchableEvidenceIndex()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceSearchIndexerIntegrationTest.indexesEvidenceMetadataIntoSearchableEvidenceIndex()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceSearchIndexerIntegrationTest.indexesEvidenceMetadataIntoSearchableEvidenceIndex()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「:」、「EVIDENCE_search」、「CASE_search」、「LOGISTICS_PROOF」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void indexesEvidenceMetadataIntoSearchableEvidenceIndex() throws Exception {
        String endpoint =
                "http://"
                        + ELASTICSEARCH.getHost()
                        + ":"
                        + ELASTICSEARCH.getMappedPort(9200);
        RestClientEvidenceSearchIndexer indexer =
                new RestClientEvidenceSearchIndexer(
                        RestClient.builder().baseUrl(endpoint).build());
        EvidenceView evidence =
                new EvidenceView(
                        "EVIDENCE_search",
                        "CASE_search",
                        "LOGISTICS_PROOF",
                        "USER_UPLOAD",
                        "evidence-original",
                        "CASE_search/EVIDENCE_search/proof.txt",
                        "hash-search",
                        "proof.txt",
                        "text/plain",
                        4,
                        "PENDING",
                        "PARTIES",
                        false,
                        null,
                        null);

        indexer.indexMetadata(evidence);
        HttpRequest request =
                HttpRequest.newBuilder(
                                URI.create(
                                        endpoint
                                                + "/evidence_index/_doc/EVIDENCE_search?refresh=true"))
                        .GET()
                        .build();
        HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"found\":true")
                .contains("\"case_id\":\"CASE_search\"")
                .contains("\"file_hash\":\"hash-search\"");
    }
}
