/*
 * 所属模块：房间协作与权限。
 * 文件职责：验证会话权限，覆盖 「partySessionsCanReadCaseAndParticipateButCannotReview」、「reviewerSessionCanReadAllRoomsAndDecideReviews」、「partyPrivateSessionReadIsActorSpecificUnlessPrivileged」、「customScopedSessionCanGateFutureFeatureFlags」、「actorAudienceFilteringIsExactForPartiesAndOpenForPrivilegedSessions」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.domain.PermissionScope;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

// 所属模块：【房间协作与权限 / 自动化测试层】类型「SessionPermissionServiceTest」。
// 类型职责：集中验证会话权限的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「partySessionsCanReadCaseAndParticipateButCannotReview」、「reviewerSessionCanReadAllRoomsAndDecideReviews」、「partyPrivateSessionReadIsActorSpecificUnlessPrivileged」、「customScopedSessionCanGateFutureFeatureFlags」、「actorAudienceFilteringIsExactForPartiesAndOpenForPrivilegedSessions」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class SessionPermissionServiceTest {

    private final SessionPermissionService service = new SessionPermissionService();

    // 所属模块：【房间协作与权限 / 自动化测试层】「SessionPermissionServiceTest.partySessionsCanReadCaseAndParticipateButCannotReview()」。
    // 具体功能：「SessionPermissionServiceTest.partySessionsCanReadCaseAndParticipateButCannotReview()」：复现“核对完整业务行为（场景方法「partySessionsCanReadCaseAndParticipateButCannotReview」）”场景：驱动 「service.requireCaseRead」、「service.requireRoomRead」、「service.requireEvidenceSubmit」、「service.requireHearingParticipate」，再用 「assertThat」、「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ACCESS_USER」、「default」、「CASE_PERMISSION」、「user-local」。
    // 上游调用：「SessionPermissionServiceTest.partySessionsCanReadCaseAndParticipateButCannotReview()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SessionPermissionServiceTest.partySessionsCanReadCaseAndParticipateButCannotReview()」的下游是被测服务、仓储或外部客户端替身；「assertThat、assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SessionPermissionServiceTest.partySessionsCanReadCaseAndParticipateButCannotReview()」守住「房间协作与权限」的可执行规格，尤其防止 「ACCESS_USER」、「default」、「CASE_PERMISSION」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void partySessionsCanReadCaseAndParticipateButCannotReview() {
        CaseAccessSessionEntity userSession =
                CaseAccessSessionEntity.create(
                        "ACCESS_USER",
                        "default",
                        "CASE_PERMISSION",
                        "user-local",
                        ActorRole.USER,
                        PermissionLevel.PARTY_USER,
                        "system");
        CaseAccessSessionEntity merchantSession =
                CaseAccessSessionEntity.create(
                        "ACCESS_MERCHANT",
                        "default",
                        "CASE_PERMISSION",
                        "merchant-local",
                        ActorRole.MERCHANT,
                        PermissionLevel.PARTY_MERCHANT,
                        "system");

        service.requireCaseRead(userSession);
        service.requireRoomRead(userSession, RoomType.EVIDENCE);
        service.requireEvidenceSubmit(userSession);
        service.requireHearingParticipate(merchantSession);

        assertThat(userSession.permissionScopes())
                .contains(
                        PermissionScope.CASE_READ,
                        PermissionScope.INTAKE_PRIVATE_READ,
                        PermissionScope.EVIDENCE_SUBMIT,
                        PermissionScope.HEARING_PARTICIPATE)
                .doesNotContain(PermissionScope.REVIEW_DECIDE);
        assertThatThrownBy(() -> service.requireReviewRead(userSession))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("REVIEW_READ");
        assertThatThrownBy(() -> service.requireReviewDecision(merchantSession))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("REVIEW_DECIDE");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「SessionPermissionServiceTest.reviewerSessionCanReadAllRoomsAndDecideReviews()」。
    // 具体功能：「SessionPermissionServiceTest.reviewerSessionCanReadAllRoomsAndDecideReviews()」：复现“核对完整业务行为（场景方法「reviewerSessionCanReadAllRoomsAndDecideReviews」）”场景：驱动 「service.requireCaseRead」、「service.requireRoomRead」、「service.requireReviewRead」、「service.requireReviewDecision」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ACCESS_REVIEWER」、「default」、「CASE_PERMISSION」、「reviewer-local」。
    // 上游调用：「SessionPermissionServiceTest.reviewerSessionCanReadAllRoomsAndDecideReviews()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SessionPermissionServiceTest.reviewerSessionCanReadAllRoomsAndDecideReviews()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SessionPermissionServiceTest.reviewerSessionCanReadAllRoomsAndDecideReviews()」守住「房间协作与权限」的可执行规格，尤其防止 「ACCESS_REVIEWER」、「default」、「CASE_PERMISSION」、「reviewer-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void reviewerSessionCanReadAllRoomsAndDecideReviews() {
        CaseAccessSessionEntity reviewerSession =
                CaseAccessSessionEntity.create(
                        "ACCESS_REVIEWER",
                        "default",
                        "CASE_PERMISSION",
                        "reviewer-local",
                        ActorRole.PLATFORM_REVIEWER,
                        PermissionLevel.REVIEWER_ALL,
                        "system");

        service.requireCaseRead(reviewerSession);
        service.requireRoomRead(reviewerSession, RoomType.INTAKE);
        service.requireRoomRead(reviewerSession, RoomType.EVIDENCE);
        service.requireRoomRead(reviewerSession, RoomType.HEARING);
        service.requireReviewRead(reviewerSession);
        service.requireReviewDecision(reviewerSession);

        assertThat(reviewerSession.permissionScopes())
                .contains(
                        PermissionScope.CASE_READ,
                        PermissionScope.INTAKE_PRIVATE_READ,
                        PermissionScope.EVIDENCE_PRIVATE_READ,
                        PermissionScope.HEARING_READ,
                        PermissionScope.REVIEW_READ,
                        PermissionScope.REVIEW_DECIDE);
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「SessionPermissionServiceTest.partyPrivateSessionReadIsActorSpecificUnlessPrivileged()」。
    // 具体功能：「SessionPermissionServiceTest.partyPrivateSessionReadIsActorSpecificUnlessPrivileged()」：复现“核对完整业务行为（场景方法「partyPrivateSessionReadIsActorSpecificUnlessPrivileged」）”场景：驱动 「service.requirePartyPrivateSessionRead」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ACCESS_USER」、「default」、「CASE_PERMISSION」、「user-local」。
    // 上游调用：「SessionPermissionServiceTest.partyPrivateSessionReadIsActorSpecificUnlessPrivileged()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SessionPermissionServiceTest.partyPrivateSessionReadIsActorSpecificUnlessPrivileged()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SessionPermissionServiceTest.partyPrivateSessionReadIsActorSpecificUnlessPrivileged()」守住「房间协作与权限」的可执行规格，尤其防止 「ACCESS_USER」、「default」、「CASE_PERMISSION」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void partyPrivateSessionReadIsActorSpecificUnlessPrivileged() {
        CaseAccessSessionEntity userSession =
                CaseAccessSessionEntity.create(
                        "ACCESS_USER",
                        "default",
                        "CASE_PERMISSION",
                        "user-local",
                        ActorRole.USER,
                        PermissionLevel.PARTY_USER,
                        "system");
        CaseAccessSessionEntity reviewerSession =
                CaseAccessSessionEntity.create(
                        "ACCESS_REVIEWER",
                        "default",
                        "CASE_PERMISSION",
                        "reviewer-local",
                        ActorRole.PLATFORM_REVIEWER,
                        PermissionLevel.REVIEWER_ALL,
                        "system");

        service.requirePartyPrivateSessionRead(userSession, "user-local", ActorRole.USER);
        service.requirePartyPrivateSessionRead(reviewerSession, "user-local", ActorRole.USER);

        assertThatThrownBy(
                        () ->
                                service.requirePartyPrivateSessionRead(
                                        userSession, "merchant-local", ActorRole.MERCHANT))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("private session");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「SessionPermissionServiceTest.customScopedSessionCanGateFutureFeatureFlags()」。
    // 具体功能：「SessionPermissionServiceTest.customScopedSessionCanGateFutureFeatureFlags()」：复现“核对完整业务行为（场景方法「customScopedSessionCanGateFutureFeatureFlags」）”场景：驱动 「service.require」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ACCESS_CUSTOM」、「default」、「CASE_PERMISSION」、「service-local」。
    // 上游调用：「SessionPermissionServiceTest.customScopedSessionCanGateFutureFeatureFlags()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SessionPermissionServiceTest.customScopedSessionCanGateFutureFeatureFlags()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SessionPermissionServiceTest.customScopedSessionCanGateFutureFeatureFlags()」守住「房间协作与权限」的可执行规格，尤其防止 「ACCESS_CUSTOM」、「default」、「CASE_PERMISSION」、「service-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void customScopedSessionCanGateFutureFeatureFlags() {
        CaseAccessSessionEntity customSession =
                CaseAccessSessionEntity.create(
                        "ACCESS_CUSTOM",
                        "default",
                        "CASE_PERMISSION",
                        "service-local",
                        ActorRole.CUSTOMER_SERVICE,
                        PermissionLevel.SERVICE_ASSIST,
                        Set.of(PermissionScope.CASE_READ, PermissionScope.ROOM_MESSAGE_READ),
                        "system");

        service.require(customSession, PermissionScope.ROOM_MESSAGE_READ);

        assertThatThrownBy(() -> service.require(customSession, PermissionScope.REVIEW_DECIDE))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("REVIEW_DECIDE");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「SessionPermissionServiceTest.actorAudienceFilteringIsExactForPartiesAndOpenForPrivilegedSessions()」。
    // 具体功能：「SessionPermissionServiceTest.actorAudienceFilteringIsExactForPartiesAndOpenForPrivilegedSessions()」：复现“核对完整业务行为（场景方法「actorAudienceFilteringIsExactForPartiesAndOpenForPrivilegedSessions」）”场景：驱动 「service.canReadActorAudience」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ACCESS_USER」、「default」、「CASE_PERMISSION」、「user-local」。
    // 上游调用：「SessionPermissionServiceTest.actorAudienceFilteringIsExactForPartiesAndOpenForPrivilegedSessions()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SessionPermissionServiceTest.actorAudienceFilteringIsExactForPartiesAndOpenForPrivilegedSessions()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SessionPermissionServiceTest.actorAudienceFilteringIsExactForPartiesAndOpenForPrivilegedSessions()」守住「房间协作与权限」的可执行规格，尤其防止 「ACCESS_USER」、「default」、「CASE_PERMISSION」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void actorAudienceFilteringIsExactForPartiesAndOpenForPrivilegedSessions() {
        CaseAccessSessionEntity userSession =
                CaseAccessSessionEntity.create(
                        "ACCESS_USER",
                        "default",
                        "CASE_PERMISSION",
                        "user-local",
                        ActorRole.USER,
                        PermissionLevel.PARTY_USER,
                        "system");
        CaseAccessSessionEntity reviewerSession =
                CaseAccessSessionEntity.create(
                        "ACCESS_REVIEWER",
                        "default",
                        "CASE_PERMISSION",
                        "reviewer-local",
                        ActorRole.PLATFORM_REVIEWER,
                        PermissionLevel.REVIEWER_ALL,
                        "system");

        assertThat(service.canReadActorAudience(userSession, List.of())).isTrue();
        assertThat(service.canReadActorAudience(userSession, List.of("user-local"))).isTrue();
        assertThat(service.canReadActorAudience(userSession, List.of("user-other"))).isFalse();
        assertThat(service.canReadActorAudience(reviewerSession, List.of("user-local"))).isTrue();
    }
}
