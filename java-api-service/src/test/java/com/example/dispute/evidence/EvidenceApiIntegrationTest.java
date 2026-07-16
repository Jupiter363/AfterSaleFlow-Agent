/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：验证证据ApiIntegration，覆盖 「uploadsMetadataAndAcceptsTrustedOcrCallbackWhenOcrIsDown」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.example.dispute.config.HeaderAuthenticationFilter;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.evidence.application.EvidenceSearchIndexer;
import com.example.dispute.evidence.application.EvidenceStorage;
import com.example.dispute.evidence.application.OcrTaskClient;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// 所属模块：【证据与版本化卷宗 / 自动化测试层】类型「EvidenceApiIntegrationTest」。
// 类型职责：集中验证证据ApiIntegration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「databaseProperties」、「seedCase」、「uploadsMetadataAndAcceptsTrustedOcrCallbackWhenOcrIsDown」、「getFilename」、「actorHeaders」、「systemHeaders」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.data.redis.repositories.enabled=false",
            "management.health.redis.enabled=false",
            "management.health.elasticsearch.enabled=false",
            "app.ocr.service-secret=test-ocr-callback-secret"
        })
@Testcontainers
@SuppressWarnings({"rawtypes", "unchecked"})
class EvidenceApiIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_evidence_api")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApiIntegrationTest.databaseProperties(DynamicPropertyRegistry)」。
    // 具体功能：「EvidenceApiIntegrationTest.databaseProperties(DynamicPropertyRegistry)」：作为测试辅助方法为“核对完整业务行为（场景方法「databaseProperties」）”组装或读取「POSTGRESQL.getHost」、「POSTGRESQL.getMappedPort」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceApiIntegrationTest.databaseProperties(DynamicPropertyRegistry)」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「EvidenceApiIntegrationTest.databaseProperties(DynamicPropertyRegistry)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceApiIntegrationTest.databaseProperties(DynamicPropertyRegistry)」守住「证据与版本化卷宗」的可执行规格，尤其防止 「spring.datasource.url」、「:」、「spring.datasource.username」、「dispute_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/dispute_evidence_api");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private FulfillmentCaseRepository caseRepository;
    @Autowired private EvidenceItemRepository evidenceRepository;
    @MockitoBean private EvidenceStorage storage;
    @MockitoBean private OcrTaskClient ocrTaskClient;
    @MockitoBean private EvidenceSearchIndexer searchIndexer;

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApiIntegrationTest.seedCase()」。
    // 具体功能：「EvidenceApiIntegrationTest.seedCase()」：在每个测试场景运行前创建「caseRepository.existsById」、「caseRepository.saveAndFlush」、「FulfillmentCaseEntity.create」、「entity.completeIntake」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「EvidenceApiIntegrationTest.seedCase()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「EvidenceApiIntegrationTest.seedCase()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceApiIntegrationTest.seedCase()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_evidenceapi」、「order-evidence-api」、「user-evidence-api」、「merchant-evidence-api」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void seedCase() {
        if (!caseRepository.existsById("CASE_evidenceapi")) {
            FulfillmentCaseEntity entity =
                    FulfillmentCaseEntity.create(
                            "CASE_evidenceapi",
                            "order-evidence-api",
                            null,
                            "user-evidence-api",
                            "merchant-evidence-api",
                            "idem-evidence-api",
                            "DISPUTE",
                            "物流争议",
                            "签收状态存在争议",
                            RiskLevel.HIGH,
                            "user-evidence-api");
            entity.completeIntake(
                    "NON_RECEIPT",
                    CaseStatus.INTAKE_COMPLETED,
                    RiskLevel.HIGH,
                    """
                    {"potentialDispute":true,"missingSlots":[],"agentDegraded":false,\
"analyzedAt":"2026-06-28T00:00:00Z"}
                    """,
                    "user-evidence-api");
            caseRepository.saveAndFlush(entity);
        }
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApiIntegrationTest.uploadsMetadataAndAcceptsTrustedOcrCallbackWhenOcrIsDown()」。
    // 具体功能：「EvidenceApiIntegrationTest.uploadsMetadataAndAcceptsTrustedOcrCallbackWhenOcrIsDown()」：复现“核对完整业务行为（场景方法「uploadsMetadataAndAcceptsTrustedOcrCallbackWhenOcrIsDown」）”场景：驱动 「storage.storeOriginal」、「evidenceRepository.count」、「evidenceRepository.findById」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「evidence-original」、「file」、「evidence_type」、「LOGISTICS_PROOF」。
    // 上游调用：「EvidenceApiIntegrationTest.uploadsMetadataAndAcceptsTrustedOcrCallbackWhenOcrIsDown()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceApiIntegrationTest.uploadsMetadataAndAcceptsTrustedOcrCallbackWhenOcrIsDown()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceApiIntegrationTest.uploadsMetadataAndAcceptsTrustedOcrCallbackWhenOcrIsDown()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「evidence-original」、「file」、「evidence_type」、「LOGISTICS_PROOF」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void uploadsMetadataAndAcceptsTrustedOcrCallbackWhenOcrIsDown() {
        when(storage.storeOriginal(any(), any(), any(), any(), any()))
                .thenReturn(
                        new EvidenceStorage.StoredObject(
                                "evidence-original",
                                "CASE_evidenceapi/evidence/proof.png"));
        doThrow(new IllegalStateException("ocr down"))
                .when(ocrTaskClient)
                .createParseTask(any());
        LinkedMultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
        multipart.add(
                "file",
                new ByteArrayResource(
                        new byte[] {
                            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
                        }) {
                    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApiIntegrationTest.getFilename()」。
                    // 具体功能：「EvidenceApiIntegrationTest.getFilename()」：作为「EvidenceApiIntegrationTest」测试替身实现「getFilename」：返回预设值 「"proof.png"」，让被测编排能够观察到确定、可断言的协作者行为。
                    // 上游调用：「EvidenceApiIntegrationTest.getFilename()」由 JUnit 生命周期或本测试类的场景方法调用。
                    // 下游影响：「EvidenceApiIntegrationTest.getFilename()」下游仅修改测试内存状态或返回桩值：返回预设值 「"proof.png"」；场景结束后由外层测试读取这些记录完成断言。
                    // 系统意义：「EvidenceApiIntegrationTest.getFilename()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「proof.png」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
                    // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
                    @Override
                    public String getFilename() {
                        return "proof.png";
                    }
                });
        multipart.add("evidence_type", "LOGISTICS_PROOF");
        multipart.add("source_type", "USER_UPLOAD");
        multipart.add("visibility", "PARTIES");
        multipart.add("claimed_fact", "物流截图用于证明包裹签收状态");
        multipart.add("truth_attested", "true");
        multipart.add("occurred_at", "2026-07-14T10:05:00+08:00");
        HttpHeaders headers = actorHeaders(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<Map> uploaded =
                restTemplate.exchange(
                        url("/api/disputes/CASE_evidenceapi/evidence"),
                        HttpMethod.POST,
                        new HttpEntity<>(multipart, headers),
                        Map.class);

        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> evidence = data(uploaded);
        assertThat(evidence)
                .containsEntry("content_type", "image/png")
                .containsEntry("parse_status", "PENDING")
                .containsEntry("desensitized", false)
                .containsEntry("occurred_at", "2026-07-14T02:05:00Z");
        assertThat(evidenceRepository.count()).isEqualTo(1);

        ResponseEntity<Map> callback =
                restTemplate.exchange(
                        url(
                                "/internal/evidence/"
                                        + evidence.get("id")
                                        + "/parse-result"),
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of(
                                        "status", "SUCCEEDED",
                                        "text", "签收证明解析文本",
                                        "metadata", Map.of("engine", "paddleocr")),
                                systemHeaders()),
                        Map.class);
        assertThat(callback.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(
                        evidenceRepository
                                .findById(evidence.get("id").toString())
                                .orElseThrow()
                                .getParsedText())
                .isEqualTo("签收证明解析文本");

    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApiIntegrationTest.actorHeaders(MediaType)」。
    // 具体功能：「EvidenceApiIntegrationTest.actorHeaders(MediaType)」：作为测试辅助方法为“核对完整业务行为（场景方法「actorHeaders」）”组装或读取「HttpHeaders」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「EvidenceApiIntegrationTest.actorHeaders(MediaType)」由本测试类中的 「EvidenceApiIntegrationTest.uploadsMetadataAndAcceptsTrustedOcrCallbackWhenOcrIsDown」 调用。
    // 下游影响：「EvidenceApiIntegrationTest.actorHeaders(MediaType)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceApiIntegrationTest.actorHeaders(MediaType)」守住「证据与版本化卷宗」的可执行规格，尤其防止 「user-evidence-api」、「USER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private HttpHeaders actorHeaders(MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, "user-evidence-api");
        headers.set(HeaderAuthenticationFilter.ROLE_HEADER, "USER");
        return headers;
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApiIntegrationTest.systemHeaders()」。
    // 具体功能：「EvidenceApiIntegrationTest.systemHeaders()」：作为测试辅助方法为“核对完整业务行为（场景方法「systemHeaders」）”组装或读取「HttpHeaders」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「EvidenceApiIntegrationTest.systemHeaders()」由本测试类中的 「EvidenceApiIntegrationTest.uploadsMetadataAndAcceptsTrustedOcrCallbackWhenOcrIsDown」 调用。
    // 下游影响：「EvidenceApiIntegrationTest.systemHeaders()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceApiIntegrationTest.systemHeaders()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「ocr-parser-service」、「X-Service-Secret」、「test-ocr-callback-secret」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private HttpHeaders systemHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(
                HeaderAuthenticationFilter.SERVICE_IDENTITY_HEADER,
                "ocr-parser-service");
        headers.set("X-Service-Secret", "test-ocr-callback-secret");
        return headers;
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApiIntegrationTest.data(ResponseEntity)」。
    // 具体功能：「EvidenceApiIntegrationTest.data(ResponseEntity)」：作为测试辅助方法为“核对完整业务行为（场景方法「data」）”组装或读取「response.getBody」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceApiIntegrationTest.data(ResponseEntity)」由本测试类中的 「EvidenceApiIntegrationTest.uploadsMetadataAndAcceptsTrustedOcrCallbackWhenOcrIsDown」 调用。
    // 下游影响：「EvidenceApiIntegrationTest.data(ResponseEntity)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceApiIntegrationTest.data(ResponseEntity)」守住「证据与版本化卷宗」的可执行规格，尤其防止 「data」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static Map<String, Object> data(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApiIntegrationTest.url(String)」。
    // 具体功能：「EvidenceApiIntegrationTest.url(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「url」）”组装或读取测试夹具数据，供本测试类的场景方法复用。
    // 上游调用：「EvidenceApiIntegrationTest.url(String)」由本测试类中的 「EvidenceApiIntegrationTest.uploadsMetadataAndAcceptsTrustedOcrCallbackWhenOcrIsDown」 调用。
    // 下游影响：「EvidenceApiIntegrationTest.url(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceApiIntegrationTest.url(String)」守住「证据与版本化卷宗」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
