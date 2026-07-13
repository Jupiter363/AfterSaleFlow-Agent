/*
 * 所属模块：争议路由应用层。
 * 文件职责：验证RouterApiIntegration，覆盖 「routesAllThreePathsPersistsConclusionsAndQueriesVersionedPolicies」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；汇集案件、证据和规则上下文并选择常规、规则或听证路线。
 * 关键边界：路由只决定下一条处理路径，不拥有终审或工具执行权限
 */
package com.example.dispute.router;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.config.HeaderAuthenticationFilter;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.AuditLogRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FlowConclusionRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.RouteDecisionRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// 所属模块：【争议路由应用层 / 自动化测试层】类型「RouterApiIntegrationTest」。
// 类型职责：集中验证RouterApiIntegration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「databaseProperties」、「seedCases」、「routesAllThreePathsPersistsConclusionsAndQueriesVersionedPolicies」、「seedCase」、「route」、「actorHeaders」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：路由只决定下一条处理路径，不拥有终审或工具执行权限
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.data.redis.repositories.enabled=false",
            "management.health.redis.enabled=false",
            "management.health.elasticsearch.enabled=false"
        })
@Testcontainers
@SuppressWarnings({"rawtypes", "unchecked"})
class RouterApiIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_router_api")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    // 所属模块：【争议路由应用层 / 自动化测试层】「RouterApiIntegrationTest.databaseProperties(DynamicPropertyRegistry)」。
    // 具体功能：「RouterApiIntegrationTest.databaseProperties(DynamicPropertyRegistry)」：作为测试辅助方法为“核对完整业务行为（场景方法「databaseProperties」）”组装或读取「POSTGRESQL.getHost」、「POSTGRESQL.getMappedPort」，供本测试类的场景方法复用。
    // 上游调用：「RouterApiIntegrationTest.databaseProperties(DynamicPropertyRegistry)」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「RouterApiIntegrationTest.databaseProperties(DynamicPropertyRegistry)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RouterApiIntegrationTest.databaseProperties(DynamicPropertyRegistry)」守住「争议路由应用层」的可执行规格，尤其防止 「spring.datasource.url」、「:」、「spring.datasource.username」、「dispute_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/dispute_router_api");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private FulfillmentCaseRepository caseRepository;
    @Autowired private EvidenceDossierRepository dossierRepository;
    @Autowired private RouteDecisionRepository decisionRepository;
    @Autowired private FlowConclusionRepository conclusionRepository;
    @Autowired private AuditLogRepository auditRepository;

    // 所属模块：【争议路由应用层 / 自动化测试层】「RouterApiIntegrationTest.seedCases()」。
    // 具体功能：「RouterApiIntegrationTest.seedCases()」：在每个测试场景运行前创建「seedCase」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「RouterApiIntegrationTest.seedCases()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「RouterApiIntegrationTest.seedCases()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RouterApiIntegrationTest.seedCases()」守住「争议路由应用层」的可执行规格，尤其防止 「CASE_regularapi」、「LOGISTICS_QUERY」、「CASE_ruleapi」、「UNSHIPPED_CANCEL」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void seedCases() {
        seedCase("CASE_regularapi", "LOGISTICS_QUERY", null, RiskLevel.LOW, 0);
        seedCase("CASE_ruleapi", "UNSHIPPED_CANCEL", null, RiskLevel.MEDIUM, 1);
        seedCase(
                "CASE_hearingapi",
                "UNSHIPPED_CANCEL",
                "FULFILLMENT_CONFLICT",
                RiskLevel.HIGH,
                1);
    }

    // 所属模块：【争议路由应用层 / 自动化测试层】「RouterApiIntegrationTest.routesAllThreePathsPersistsConclusionsAndQueriesVersionedPolicies()」。
    // 具体功能：「RouterApiIntegrationTest.routesAllThreePathsPersistsConclusionsAndQueriesVersionedPolicies()」：复现“核对完整业务行为（场景方法「routesAllThreePathsPersistsConclusionsAndQueriesVersionedPolicies」）”场景：驱动 「decisionRepository.count」、「conclusionRepository.count」、「auditRepository.findAll」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_regularapi」、「ROUTE_regular_api」、「CASE_ruleapi」、「ROUTE_rule_api」。
    // 上游调用：「RouterApiIntegrationTest.routesAllThreePathsPersistsConclusionsAndQueriesVersionedPolicies()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RouterApiIntegrationTest.routesAllThreePathsPersistsConclusionsAndQueriesVersionedPolicies()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RouterApiIntegrationTest.routesAllThreePathsPersistsConclusionsAndQueriesVersionedPolicies()」守住「争议路由应用层」的可执行规格，尤其防止 「CASE_regularapi」、「ROUTE_regular_api」、「CASE_ruleapi」、「ROUTE_rule_api」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void routesAllThreePathsPersistsConclusionsAndQueriesVersionedPolicies() {
        ResponseEntity<Map> regular = route("CASE_regularapi", "ROUTE_regular_api");
        ResponseEntity<Map> rule = route("CASE_ruleapi", "ROUTE_rule_api");
        ResponseEntity<Map> hearing = route("CASE_hearingapi", "ROUTE_hearing_api");
        ResponseEntity<Map> replay = route("CASE_ruleapi", "ROUTE_rule_api");
        ResponseEntity<Map> conflict = route("CASE_ruleapi", "ROUTE_other_key");

        assertThat(regular.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(regular).get("route_type"))
                .isEqualTo("TRANSFERRED");
        Map<String, Object> regularConclusion =
                (Map<String, Object>) data(regular).get("conclusion");
        assertThat(regularConclusion)
                .containsEntry("requires_remedy_planning", true)
                .containsEntry("requires_human_review", true);

        assertThat(data(rule).get("route_type"))
                .isEqualTo("SIMPLE_HEARING");
        assertThat(data(rule).get("policy_rule_id"))
                .isEqualTo("POLICY_UNSHIPPED_CANCEL_V1");
        assertThat(((Map<?, ?>) data(rule).get("conclusion")).get("policy_version"))
                .isEqualTo(1);
        assertThat(data(replay).get("id")).isEqualTo(data(rule).get("id"));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("code")).isEqualTo("IDEMPOTENCY_CONFLICT");

        assertThat(data(hearing).get("route_type"))
                .isEqualTo("FULL_HEARING");
        assertThat(data(hearing).get("conclusion")).isNull();

        assertThat(decisionRepository.count()).isEqualTo(3);
        assertThat(conclusionRepository.count()).isEqualTo(2);
        assertThat(auditRepository.findAll())
                .extracting(audit -> audit.getAction())
                .contains("ROUTE_DECIDED");

        ResponseEntity<Map> policies =
                restTemplate.exchange(
                        url("/api/reviews/policies?scope=UNSHIPPED_CANCEL"),
                        HttpMethod.GET,
                                new HttpEntity<>(reviewerHeaders()),
                        Map.class);
        assertThat(policies.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> policyData =
                (List<Map<String, Object>>) policies.getBody().get("data");
        assertThat(policyData).singleElement()
                .satisfies(
                        policy -> {
                            assertThat(policy.get("rule_code"))
                                    .isEqualTo("UNSHIPPED_CANCEL");
                            assertThat(policy.get("rule_version")).isEqualTo(1);
                        });

        ResponseEntity<Map> allPolicies =
                restTemplate.exchange(
                        url("/api/reviews/policies"),
                        HttpMethod.GET,
                                new HttpEntity<>(reviewerHeaders()),
                        Map.class);
        assertThat((List<?>) allPolicies.getBody().get("data")).hasSize(2);
    }

    // 所属模块：【争议路由应用层 / 自动化测试层】「RouterApiIntegrationTest.seedCase(String,String,String,RiskLevel,int)」。
    // 具体功能：「RouterApiIntegrationTest.seedCase(String,String,String,RiskLevel,int)」：作为测试辅助方法为“核对完整业务行为（场景方法「seedCase」）”组装或读取「caseRepository.saveAndFlush」、「dossierRepository.saveAndFlush」、「FulfillmentCaseEntity.create」、「EvidenceDossierEntity.firstBuild」，供本测试类的场景方法复用。
    // 上游调用：「RouterApiIntegrationTest.seedCase(String,String,String,RiskLevel,int)」由本测试类中的 「RouterApiIntegrationTest.seedCases」 调用。
    // 下游影响：「RouterApiIntegrationTest.seedCase(String,String,String,RiskLevel,int)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RouterApiIntegrationTest.seedCase(String,String,String,RiskLevel,int)」守住「争议路由应用层」的可执行规格，尤其防止 「ORDER_」、「user-route-api」、「merchant-route-api」、「IDEMPOTENCY_」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private void seedCase(
            String caseId,
            String caseType,
            String disputeType,
            RiskLevel riskLevel,
            int evidenceCount) {
        FulfillmentCaseEntity entity =
                FulfillmentCaseEntity.create(
                        caseId,
                        "ORDER_" + caseId,
                        null,
                        "user-route-api",
                        "merchant-route-api",
                        "IDEMPOTENCY_" + caseId,
                        caseType,
                        "Routing integration",
                        "Routing integration description",
                        riskLevel,
                        "user-route-api");
        entity.completeIntake(
                disputeType,
                CaseStatus.INTAKE_COMPLETED,
                riskLevel,
                "{}",
                "user-route-api");
        entity.markDossierBuilt("user-route-api");
        caseRepository.saveAndFlush(entity);
        dossierRepository.saveAndFlush(
                EvidenceDossierEntity.firstBuild(
                        "DOSSIER_" + caseId,
                        caseId,
                        "user-route-api",
                        "{\"evidence_count\":"
                                + evidenceCount
                                + ",\"pending_parse_count\":0}",
                        "[]",
                        "[]"));
    }

    // 所属模块：【争议路由应用层 / 自动化测试层】「RouterApiIntegrationTest.route(String,String)」。
    // 具体功能：「RouterApiIntegrationTest.route(String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「route」）”组装或读取「HttpEntity」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「RouterApiIntegrationTest.route(String,String)」由本测试类中的 「RouterApiIntegrationTest.routesAllThreePathsPersistsConclusionsAndQueriesVersionedPolicies」 调用。
    // 下游影响：「RouterApiIntegrationTest.route(String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RouterApiIntegrationTest.route(String,String)」守住「争议路由应用层」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private ResponseEntity<Map> route(String caseId, String idempotencyKey) {
        HttpHeaders headers = actorHeaders(idempotencyKey);
        return restTemplate.exchange(
                url("/api/disputes/" + caseId + "/route"),
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Map.class);
    }

    // 所属模块：【争议路由应用层 / 自动化测试层】「RouterApiIntegrationTest.actorHeaders(String)」。
    // 具体功能：「RouterApiIntegrationTest.actorHeaders(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「actorHeaders」）”组装或读取「HttpHeaders」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「RouterApiIntegrationTest.actorHeaders(String)」由本测试类中的 「RouterApiIntegrationTest.route」 调用。
    // 下游影响：「RouterApiIntegrationTest.actorHeaders(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RouterApiIntegrationTest.actorHeaders(String)」守住「争议路由应用层」的可执行规格，尤其防止 「user-route-api」、「USER」、「Idempotency-Key」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private HttpHeaders actorHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, "user-route-api");
        headers.set(HeaderAuthenticationFilter.ROLE_HEADER, "USER");
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    // 所属模块：【争议路由应用层 / 自动化测试层】「RouterApiIntegrationTest.reviewerHeaders()」。
    // 具体功能：「RouterApiIntegrationTest.reviewerHeaders()」：作为测试辅助方法为“核对完整业务行为（场景方法「reviewerHeaders」）”组装或读取「HttpHeaders」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「RouterApiIntegrationTest.reviewerHeaders()」由本测试类中的 「RouterApiIntegrationTest.routesAllThreePathsPersistsConclusionsAndQueriesVersionedPolicies」 调用。
    // 下游影响：「RouterApiIntegrationTest.reviewerHeaders()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RouterApiIntegrationTest.reviewerHeaders()」守住「争议路由应用层」的可执行规格，尤其防止 「reviewer-route-api」、「PLATFORM_REVIEWER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private HttpHeaders reviewerHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, "reviewer-route-api");
        headers.set(HeaderAuthenticationFilter.ROLE_HEADER, "PLATFORM_REVIEWER");
        return headers;
    }

    // 所属模块：【争议路由应用层 / 自动化测试层】「RouterApiIntegrationTest.data(ResponseEntity)」。
    // 具体功能：「RouterApiIntegrationTest.data(ResponseEntity)」：作为测试辅助方法为“核对完整业务行为（场景方法「data」）”组装或读取「response.getBody」，供本测试类的场景方法复用。
    // 上游调用：「RouterApiIntegrationTest.data(ResponseEntity)」由本测试类中的 「RouterApiIntegrationTest.routesAllThreePathsPersistsConclusionsAndQueriesVersionedPolicies」 调用。
    // 下游影响：「RouterApiIntegrationTest.data(ResponseEntity)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RouterApiIntegrationTest.data(ResponseEntity)」守住「争议路由应用层」的可执行规格，尤其防止 「data」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static Map<String, Object> data(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    // 所属模块：【争议路由应用层 / 自动化测试层】「RouterApiIntegrationTest.url(String)」。
    // 具体功能：「RouterApiIntegrationTest.url(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「url」）”组装或读取测试夹具数据，供本测试类的场景方法复用。
    // 上游调用：「RouterApiIntegrationTest.url(String)」由本测试类中的 「RouterApiIntegrationTest.routesAllThreePathsPersistsConclusionsAndQueriesVersionedPolicies」、「RouterApiIntegrationTest.route」 调用。
    // 下游影响：「RouterApiIntegrationTest.url(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RouterApiIntegrationTest.url(String)」守住「争议路由应用层」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
