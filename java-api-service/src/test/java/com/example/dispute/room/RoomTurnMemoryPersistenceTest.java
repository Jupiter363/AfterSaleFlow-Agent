/*
 * 所属模块：房间协作与权限。
 * 文件职责：验证房间轮次记忆Persistence，覆盖 「persistsAgentScrollSnapshotAndFindsLatestByCaseAndRoom」、「allowsSameAgentTurnNumberForDifferentAgentSessions」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
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

// 所属模块：【房间协作与权限 / 自动化测试层】类型「RoomTurnMemoryPersistenceTest」。
// 类型职责：集中验证房间轮次记忆Persistence的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「databaseProperties」、「persistsAgentScrollSnapshotAndFindsLatestByCaseAndRoom」、「allowsSameAgentTurnNumberForDifferentAgentSessions」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
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

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomTurnMemoryPersistenceTest.databaseProperties(DynamicPropertyRegistry)」。
    // 具体功能：「RoomTurnMemoryPersistenceTest.databaseProperties(DynamicPropertyRegistry)」：作为测试辅助方法为“核对完整业务行为（场景方法「databaseProperties」）”组装或读取「POSTGRESQL.getHost」、「POSTGRESQL.getMappedPort」，供本测试类的场景方法复用。
    // 上游调用：「RoomTurnMemoryPersistenceTest.databaseProperties(DynamicPropertyRegistry)」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「RoomTurnMemoryPersistenceTest.databaseProperties(DynamicPropertyRegistry)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RoomTurnMemoryPersistenceTest.databaseProperties(DynamicPropertyRegistry)」守住「房间协作与权限」的可执行规格，尤其防止 「spring.datasource.url」、「:」、「spring.datasource.username」、「dispute_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomTurnMemoryPersistenceTest.persistsAgentScrollSnapshotAndFindsLatestByCaseAndRoom()」。
    // 具体功能：「RoomTurnMemoryPersistenceTest.persistsAgentScrollSnapshotAndFindsLatestByCaseAndRoom()」：复现“持久化业务事实（场景方法「persistsAgentScrollSnapshotAndFindsLatestByCaseAndRoom」）”场景：驱动 「caseRepository.saveAndFlush」、「repository.saveAndFlush」、「repository.findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_roommemory」、「ORDER_roommemory」、「user-roommemory」、「merchant-roommemory」。
    // 上游调用：「RoomTurnMemoryPersistenceTest.persistsAgentScrollSnapshotAndFindsLatestByCaseAndRoom()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomTurnMemoryPersistenceTest.persistsAgentScrollSnapshotAndFindsLatestByCaseAndRoom()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomTurnMemoryPersistenceTest.persistsAgentScrollSnapshotAndFindsLatestByCaseAndRoom()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_roommemory」、「ORDER_roommemory」、「user-roommemory」、「merchant-roommemory」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
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

    // 所属模块：【房间协作与权限 / 自动化测试层】「RoomTurnMemoryPersistenceTest.allowsSameAgentTurnNumberForDifferentAgentSessions()」。
    // 具体功能：「RoomTurnMemoryPersistenceTest.allowsSameAgentTurnNumberForDifferentAgentSessions()」：复现“允许满足条件的操作（场景方法「allowsSameAgentTurnNumberForDifferentAgentSessions」）”场景：驱动 「caseRepository.saveAndFlush」、「accessSessionRepository.saveAndFlush」、「agentSessionRepository.saveAndFlush」、「repository.saveAndFlush」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_memory_sessions」、「ORDER_memory_sessions」、「user-memory-session」、「merchant-memory-session」。
    // 上游调用：「RoomTurnMemoryPersistenceTest.allowsSameAgentTurnNumberForDifferentAgentSessions()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RoomTurnMemoryPersistenceTest.allowsSameAgentTurnNumberForDifferentAgentSessions()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RoomTurnMemoryPersistenceTest.allowsSameAgentTurnNumberForDifferentAgentSessions()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_memory_sessions」、「ORDER_memory_sessions」、「user-memory-session」、「merchant-memory-session」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
