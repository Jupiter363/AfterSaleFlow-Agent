package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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
}
