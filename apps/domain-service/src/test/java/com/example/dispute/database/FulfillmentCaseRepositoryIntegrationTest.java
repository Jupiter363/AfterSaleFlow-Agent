/*
 * 所属模块：数据库迁移入口。
 * 文件职责：验证履约案件Integration，覆盖 「savesAndFindsACaseByIdempotencyKey」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；独立执行 Flyway 迁移并验证 PostgreSQL Schema 可用。
 * 关键边界：迁移先于业务服务读写，失败时必须阻止使用不兼容的表结构
 */
package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.caseintake.application.CaseVisibilitySpecification;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakePartyCompletionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakePartyCompletionRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// 所属模块：【数据库迁移入口 / 自动化测试层】类型「FulfillmentCaseRepositoryIntegrationTest」。
// 类型职责：集中验证履约案件Integration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「databaseProperties」、「savesAndFindsACaseByIdempotencyKey」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：迁移先于业务服务读写，失败时必须阻止使用不兼容的表结构
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Testcontainers
class FulfillmentCaseRepositoryIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_repository")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    // 所属模块：【数据库迁移入口 / 自动化测试层】「FulfillmentCaseRepositoryIntegrationTest.databaseProperties(DynamicPropertyRegistry)」。
    // 具体功能：「FulfillmentCaseRepositoryIntegrationTest.databaseProperties(DynamicPropertyRegistry)」：作为测试辅助方法为“核对完整业务行为（场景方法「databaseProperties」）”组装或读取「POSTGRESQL.getHost」、「POSTGRESQL.getMappedPort」，供本测试类的场景方法复用。
    // 上游调用：「FulfillmentCaseRepositoryIntegrationTest.databaseProperties(DynamicPropertyRegistry)」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「FulfillmentCaseRepositoryIntegrationTest.databaseProperties(DynamicPropertyRegistry)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「FulfillmentCaseRepositoryIntegrationTest.databaseProperties(DynamicPropertyRegistry)」守住「数据库迁移入口」的可执行规格，尤其防止 「spring.datasource.url」、「:」、「spring.datasource.username」、「dispute_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/dispute_repository");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private FulfillmentCaseRepository repository;
    @Autowired private CaseIntakePartyCompletionRepository intakeCompletionRepository;

    // 所属模块：【数据库迁移入口 / 自动化测试层】「FulfillmentCaseRepositoryIntegrationTest.savesAndFindsACaseByIdempotencyKey()」。
    // 具体功能：「FulfillmentCaseRepositoryIntegrationTest.savesAndFindsACaseByIdempotencyKey()」：复现“核对完整业务行为（场景方法「savesAndFindsACaseByIdempotencyKey」）”场景：驱动 「repository.saveAndFlush」、「repository.findByCreationIdempotencyKey」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「case-repository-1」、「order-1」、「user-1」、「merchant-1」。
    // 上游调用：「FulfillmentCaseRepositoryIntegrationTest.savesAndFindsACaseByIdempotencyKey()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「FulfillmentCaseRepositoryIntegrationTest.savesAndFindsACaseByIdempotencyKey()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「FulfillmentCaseRepositoryIntegrationTest.savesAndFindsACaseByIdempotencyKey()」守住「数据库迁移入口」的可执行规格，尤其防止 「case-repository-1」、「order-1」、「user-1」、「merchant-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void savesAndFindsACaseByIdempotencyKey() {
        FulfillmentCaseEntity entity =
                FulfillmentCaseEntity.create(
                        "case-repository-1",
                        "order-1",
                        null,
                        "user-1",
                        "merchant-1",
                        "idem-1",
                        "FULFILLMENT",
                        "包裹未收到",
                        "用户反馈物流已签收但本人未收到",
                        RiskLevel.MEDIUM,
                        "user-1");

        repository.saveAndFlush(entity);

        assertThat(repository.findByCreationIdempotencyKey("idem-1"))
                .hasValueSatisfying(
                        saved -> {
                            assertThat(saved.getId()).isEqualTo("case-repository-1");
                            assertThat(saved.getCaseStatus()).isEqualTo(CaseStatus.INTAKE_PENDING);
                        });
    }

    @Test
    void unlocksAUserInitiatedCaseForTheMerchantOnlyAfterExactInitiatorCompletion() {
        FulfillmentCaseEntity dispute =
                intakeCompletedCase(
                        "case-user-initiated-visibility",
                        "user-visibility",
                        "merchant-visibility",
                        ActorRole.USER);
        repository.saveAndFlush(dispute);

        assertThat(visibleCaseIds(new AuthenticatedActor("user-visibility", ActorRole.USER)))
                .containsExactly(dispute.getId());
        assertThat(visibleCaseIds(
                        new AuthenticatedActor("merchant-visibility", ActorRole.MERCHANT)))
                .isEmpty();
        assertThat(visibleCaseIds(
                        new AuthenticatedActor("user-visibility", ActorRole.MERCHANT)))
                .isEmpty();

        intakeCompletionRepository.saveAndFlush(
                CaseIntakePartyCompletionEntity.terminal(
                        "completion-user-visibility",
                        dispute.getId(),
                        ActorRole.USER,
                        "user-visibility",
                        "COMPLETED",
                        Instant.parse("2026-07-16T01:00:00Z"),
                        "user-visibility"));

        assertThat(visibleCaseIds(
                        new AuthenticatedActor("merchant-visibility", ActorRole.MERCHANT)))
                .containsExactly(dispute.getId());
    }

    @Test
    void unlocksAMerchantInitiatedCaseForTheUserOnlyAfterExactInitiatorCompletion() {
        FulfillmentCaseEntity dispute =
                intakeCompletedCase(
                        "case-merchant-initiated-visibility",
                        "user-counterparty",
                        "merchant-initiator",
                        ActorRole.MERCHANT);
        repository.saveAndFlush(dispute);

        assertThat(visibleCaseIds(
                        new AuthenticatedActor("merchant-initiator", ActorRole.MERCHANT)))
                .containsExactly(dispute.getId());
        assertThat(visibleCaseIds(
                        new AuthenticatedActor("user-counterparty", ActorRole.USER)))
                .isEmpty();

        intakeCompletionRepository.saveAndFlush(
                CaseIntakePartyCompletionEntity.terminal(
                        "completion-merchant-visibility",
                        dispute.getId(),
                        ActorRole.MERCHANT,
                        "merchant-initiator",
                        "COMPLETED",
                        Instant.parse("2026-07-16T01:00:00Z"),
                        "merchant-initiator"));

        assertThat(visibleCaseIds(
                        new AuthenticatedActor("user-counterparty", ActorRole.USER)))
                .containsExactly(dispute.getId());
    }

    @Test
    void doesNotUnlockTheRespondentForACompletionWithTheWrongParticipantId() {
        FulfillmentCaseEntity dispute =
                intakeCompletedCase(
                        "case-wrong-completion-identity",
                        "user-correct",
                        "merchant-locked",
                        ActorRole.USER);
        repository.saveAndFlush(dispute);
        intakeCompletionRepository.saveAndFlush(
                CaseIntakePartyCompletionEntity.terminal(
                        "completion-wrong-identity",
                        dispute.getId(),
                        ActorRole.USER,
                        "different-user",
                        "COMPLETED",
                        Instant.parse("2026-07-16T01:00:00Z"),
                        "different-user"));

        assertThat(visibleCaseIds(
                        new AuthenticatedActor("merchant-locked", ActorRole.MERCHANT)))
                .isEmpty();
    }

    private java.util.List<String> visibleCaseIds(AuthenticatedActor actor) {
        return repository.findAll(CaseVisibilitySpecification.visibleTo(actor)).stream()
                .map(FulfillmentCaseEntity::getId)
                .toList();
    }

    private static FulfillmentCaseEntity intakeCompletedCase(
            String caseId,
            String userId,
            String merchantId,
            ActorRole initiatorRole) {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.create(
                        caseId,
                        "order-" + caseId,
                        null,
                        null,
                        userId,
                        merchantId,
                        initiatorRole,
                        "idempotency-" + caseId,
                        "DISPUTE",
                        "接待顺序测试",
                        "验证发起方完成后才向被发起方展示。",
                        RiskLevel.MEDIUM,
                        initiatorRole == ActorRole.USER ? userId : merchantId);
        dispute.completeIntake(
                "FULFILLMENT_CONFLICT",
                CaseStatus.INTAKE_COMPLETED,
                RiskLevel.MEDIUM,
                "{}",
                initiatorRole == ActorRole.USER ? userId : merchantId);
        return dispute;
    }
}
