package com.example.dispute.room.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum PermissionLevel {
    PARTY_USER(
            PermissionScope.CASE_READ,
            PermissionScope.ROOM_MESSAGE_READ,
            PermissionScope.ROOM_MESSAGE_WRITE,
            PermissionScope.INTAKE_PRIVATE_READ,
            PermissionScope.INTAKE_PARTICIPATE,
            PermissionScope.EVIDENCE_READ,
            PermissionScope.EVIDENCE_SUBMIT,
            PermissionScope.EVIDENCE_PRIVATE_READ,
            PermissionScope.HEARING_READ,
            PermissionScope.HEARING_PARTICIPATE,
            PermissionScope.OUTCOME_READ,
            PermissionScope.NOTIFICATION_READ,
            PermissionScope.AGENT_SESSION_READ,
            PermissionScope.AGENT_SESSION_WRITE),
    PARTY_MERCHANT(
            PermissionScope.CASE_READ,
            PermissionScope.ROOM_MESSAGE_READ,
            PermissionScope.ROOM_MESSAGE_WRITE,
            PermissionScope.INTAKE_PRIVATE_READ,
            PermissionScope.INTAKE_PARTICIPATE,
            PermissionScope.EVIDENCE_READ,
            PermissionScope.EVIDENCE_SUBMIT,
            PermissionScope.EVIDENCE_PRIVATE_READ,
            PermissionScope.HEARING_READ,
            PermissionScope.HEARING_PARTICIPATE,
            PermissionScope.OUTCOME_READ,
            PermissionScope.NOTIFICATION_READ,
            PermissionScope.AGENT_SESSION_READ,
            PermissionScope.AGENT_SESSION_WRITE),
    SERVICE_ASSIST(
            PermissionScope.CASE_READ,
            PermissionScope.ROOM_MESSAGE_READ,
            PermissionScope.ROOM_MESSAGE_WRITE,
            PermissionScope.INTAKE_PRIVATE_READ,
            PermissionScope.INTAKE_PARTICIPATE,
            PermissionScope.EVIDENCE_READ,
            PermissionScope.HEARING_READ,
            PermissionScope.OUTCOME_READ,
            PermissionScope.NOTIFICATION_READ,
            PermissionScope.AGENT_SESSION_READ,
            PermissionScope.AGENT_SESSION_WRITE),
    REVIEWER_ALL(
            PermissionScope.CASE_READ,
            PermissionScope.ROOM_MESSAGE_READ,
            PermissionScope.ROOM_MESSAGE_WRITE,
            PermissionScope.INTAKE_PRIVATE_READ,
            PermissionScope.EVIDENCE_READ,
            PermissionScope.EVIDENCE_SUBMIT,
            PermissionScope.EVIDENCE_PRIVATE_READ,
            PermissionScope.HEARING_READ,
            PermissionScope.HEARING_PARTICIPATE,
            PermissionScope.REVIEW_READ,
            PermissionScope.REVIEW_DECIDE,
            PermissionScope.OUTCOME_READ,
            PermissionScope.NOTIFICATION_READ,
            PermissionScope.AGENT_SESSION_READ,
            PermissionScope.AGENT_SESSION_WRITE),
    ADMIN_ALL(PermissionScope.values()),
    SYSTEM_ALL(PermissionScope.values());

    private final Set<PermissionScope> defaultScopes;

    PermissionLevel(PermissionScope... scopes) {
        if (scopes.length == PermissionScope.values().length) {
            this.defaultScopes = Collections.unmodifiableSet(EnumSet.allOf(PermissionScope.class));
            return;
        }
        EnumSet<PermissionScope> set = EnumSet.noneOf(PermissionScope.class);
        Collections.addAll(set, scopes);
        this.defaultScopes = Collections.unmodifiableSet(set);
    }

    public Set<PermissionScope> defaultScopes() {
        return defaultScopes;
    }

    public boolean privileged() {
        return this == REVIEWER_ALL || this == ADMIN_ALL || this == SYSTEM_ALL;
    }
}
