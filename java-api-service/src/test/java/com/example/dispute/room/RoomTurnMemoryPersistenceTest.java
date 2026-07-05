package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
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
class RoomTurnMemoryPersistenceTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_room_memory")
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
                                + "/dispute_room_memory");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private RoomTurnMemoryRepository repository;
    @Autowired private FulfillmentCaseRepository caseRepository;

    @Test
    void persistsAgentScrollSnapshotAndFindsLatestByCaseAndRoom() {
        caseRepository.saveAndFlush(
                FulfillmentCaseEntity.create(
                        "CASE_roommemory",
                        "ORDER_roommemory",
                        null,
                        "user-roommemory",
                        "merchant-roommemory",
                        "idem-roommemory",
                        "FULFILLMENT_CONFLICT",
                        "签收未收到",
                        "物流显示签收但用户未收到",
                        RiskLevel.MEDIUM,
                        "user-roommemory"));
        repository.saveAndFlush(
                RoomTurnMemoryEntity.agentTurn(
                        "TURN_MEMORY_1",
                        "CASE_roommemory",
                        RoomType.INTAKE,
                        1,
                        "system",
                        "dispute-intake-officer",
                        "请补充物流签收凭证。",
                        "{\"requested_outcome\":\"RESHIP\"}",
                        "{\"cards\":[{\"key\":\"requested_outcome\",\"value\":\"RESHIP\"}]}",
                        "[{\"type\":\"UPSERT_CARD\",\"target_key\":\"requested_outcome\"}]",
                        "RUN_roommemory_1"));
        repository.saveAndFlush(
                RoomTurnMemoryEntity.agentTurn(
                        "TURN_MEMORY_2",
                        "CASE_roommemory",
                        RoomType.INTAKE,
                        2,
                        "system",
                        "dispute-intake-officer",
                        "已更新为退款诉求。",
                        "{\"requested_outcome\":\"REFUND\"}",
                        "{\"cards\":[{\"key\":\"requested_outcome\",\"value\":\"REFUND\"}]}",
                        "[{\"type\":\"UPSERT_CARD\",\"target_key\":\"requested_outcome\"}]",
                        "RUN_roommemory_2"));

        RoomTurnMemoryEntity latest =
                repository
                        .findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc(
                                "CASE_roommemory", RoomType.INTAKE)
                        .orElseThrow();

        assertThat(latest.getTurnNo()).isEqualTo(2);
        assertThat(latest.getScrollSnapshotJson()).contains("REFUND");
        assertThat(latest.getCanvasOperationsJson()).contains("UPSERT_CARD");
    }
}
