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

class SessionPermissionServiceTest {

    private final SessionPermissionService service = new SessionPermissionService();

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
