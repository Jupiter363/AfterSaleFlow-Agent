package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.config.ActorRole;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.repository.AgentConversationSessionRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseAccessSessionRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import java.util.List;
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
    @Autowired private CaseAccessSessionRepository accessSessionRepository;
    @Autowired private AgentConversationSessionRepository agentSessionRepository;

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

    @Test
    void allowsSameAgentTurnNumberForDifferentAgentSessions() {
        caseRepository.saveAndFlush(
                FulfillmentCaseEntity.create(
                        "CASE_memory_sessions",
                        "ORDER_memory_sessions",
                        null,
                        "user-memory-session",
                        "merchant-memory-session",
                        "idem-memory-session",
                        "FULFILLMENT_CONFLICT",
                        "签收争议",
                        "双方需要分别提交证据并与证据书记官私聊",
                        RiskLevel.HIGH,
                        "user-memory-session"));
        CaseAccessSessionEntity userAccess =
                accessSessionRepository.saveAndFlush(
                        CaseAccessSessionEntity.create(
                                "ACCESS_memory_user",
                                "default",
                                "CASE_memory_sessions",
                                "user-memory-session",
                                ActorRole.USER,
                                PermissionLevel.PARTY_USER,
                                "user-memory-session"));
        CaseAccessSessionEntity merchantAccess =
                accessSessionRepository.saveAndFlush(
                        CaseAccessSessionEntity.create(
                                "ACCESS_memory_merchant",
                                "default",
                                "CASE_memory_sessions",
                                "merchant-memory-session",
                                ActorRole.MERCHANT,
                                PermissionLevel.PARTY_MERCHANT,
                                "merchant-memory-session"));
        AgentConversationSessionEntity userAgentSession =
                agentSessionRepository.saveAndFlush(
                        AgentConversationSessionEntity.create(
                                "AGENT_MEMORY_USER",
                                userAccess,
                                RoomType.EVIDENCE,
                                "EVIDENCE_CLERK",
                                "evidence-clerk:user",
                                "memeo-short-5-summary-10",
                                "user-memory-session"));
        AgentConversationSessionEntity merchantAgentSession =
                agentSessionRepository.saveAndFlush(
                        AgentConversationSessionEntity.create(
                                "AGENT_MEMORY_MERCHANT",
                                merchantAccess,
                                RoomType.EVIDENCE,
                                "EVIDENCE_CLERK",
                                "evidence-clerk:merchant",
                                "memeo-short-5-summary-10",
                                "merchant-memory-session"));

        repository.saveAndFlush(
                RoomTurnMemoryEntity.agentTurn(
                        "TURN_MEMORY_USER_AGENT",
                        "CASE_memory_sessions",
                        RoomType.EVIDENCE,
                        1,
                        "evidence-clerk",
                        "EVIDENCE_CLERK",
                        "用户证据书记官回复",
                        "{}",
                        "{\"owner\":\"user-memory-session\"}",
                        "[]",
                        "RUN_memory_user",
                        userAgentSession,
                        userAccess,
                        "{\"short_window_turns\":5}"));
        repository.saveAndFlush(
                RoomTurnMemoryEntity.agentTurn(
                        "TURN_MEMORY_MERCHANT_AGENT",
                        "CASE_memory_sessions",
                        RoomType.EVIDENCE,
                        1,
                        "evidence-clerk",
                        "EVIDENCE_CLERK",
                        "商家证据书记官回复",
                        "{}",
                        "{\"owner\":\"merchant-memory-session\"}",
                        "[]",
                        "RUN_memory_merchant",
                        merchantAgentSession,
                        merchantAccess,
                        "{\"short_window_turns\":5}"));

        List<RoomTurnMemoryEntity> userTurns =
                repository.findTop10ByAgentSessionIdOrderByTurnNoDesc("AGENT_MEMORY_USER");
        List<RoomTurnMemoryEntity> merchantTurns =
                repository.findTop10ByAgentSessionIdOrderByTurnNoDesc("AGENT_MEMORY_MERCHANT");

        assertThat(userTurns).hasSize(1);
        assertThat(userTurns.get(0).getAgentResponse()).contains("用户证据书记官回复");
        assertThat(merchantTurns).hasSize(1);
        assertThat(merchantTurns.get(0).getAgentResponse()).contains("商家证据书记官回复");
    }
}
