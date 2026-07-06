package com.example.dispute.room.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.room.domain.PermissionScope;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SessionPermissionService {

    public void require(CaseAccessSessionEntity session, PermissionScope scope) {
        if (session == null || !session.has(scope)) {
            throw new ForbiddenException("access session missing permission " + scope.name());
        }
    }

    public void requireCaseRead(CaseAccessSessionEntity session) {
        require(session, PermissionScope.CASE_READ);
    }

    public void requireRoomRead(CaseAccessSessionEntity session, RoomType roomType) {
        requireCaseRead(session);
        switch (roomType) {
            case INTAKE -> require(session, PermissionScope.INTAKE_PRIVATE_READ);
            case EVIDENCE -> require(session, PermissionScope.EVIDENCE_READ);
            case HEARING -> require(session, PermissionScope.HEARING_READ);
            case REVIEW -> require(session, PermissionScope.REVIEW_READ);
        }
    }

    public void requirePartyPrivateSessionRead(
            CaseAccessSessionEntity session, String ownerActorId, ActorRole ownerRole) {
        require(session, PermissionScope.INTAKE_PRIVATE_READ);
        if (session.privileged()) {
            return;
        }
        if (session.getActorRole() == ownerRole && session.getActorId().equals(ownerActorId)) {
            return;
        }
        throw new ForbiddenException("actor cannot read another party private session");
    }

    public void requireEvidenceSubmit(CaseAccessSessionEntity session) {
        require(session, PermissionScope.EVIDENCE_SUBMIT);
    }

    public void requireHearingParticipate(CaseAccessSessionEntity session) {
        require(session, PermissionScope.HEARING_PARTICIPATE);
    }

    public void requireReviewRead(CaseAccessSessionEntity session) {
        require(session, PermissionScope.REVIEW_READ);
    }

    public void requireReviewDecision(CaseAccessSessionEntity session) {
        require(session, PermissionScope.REVIEW_DECIDE);
    }

    public boolean canReadActorAudience(
            CaseAccessSessionEntity session, List<String> audienceActorIds) {
        if (session == null) {
            return false;
        }
        if (audienceActorIds == null || audienceActorIds.isEmpty()) {
            return true;
        }
        return session.privileged()
                || session.getActorRole() == ActorRole.CUSTOMER_SERVICE
                || audienceActorIds.contains(session.getActorId());
    }
}
