package com.example.dispute.workflow.application.authority.payload;

import com.example.dispute.workflow.application.authority.epoch.EpochPartyAuthority.Party;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import java.util.Objects;

/** Canonical server-minted human Intake message payload. Browser fields never establish its route. */
public record IntakeHumanInputCommand(
        String schemaVersion,
        String commandId,
        String tenantSurrogate,
        String caseId,
        long roomEpoch,
        Party party,
        String actorId,
        ActorRole actorRole,
        String accessSessionId,
        String registrationId,
        String messageId,
        String text,
        long occurredAtEpochMicros) {

    public static final String SCHEMA_VERSION = "intake-human-input-command.v1";

    public IntakeHumanInputCommand {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
        }
        identifier(commandId, "commandId", 128);
        identifier(tenantSurrogate, "tenantSurrogate", 128);
        identifier(caseId, "caseId", 64);
        if (roomEpoch < 0
                || roomEpoch > 9_007_199_254_740_991L
                || occurredAtEpochMicros < 0
                || occurredAtEpochMicros > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("room epoch and occurred time must be JCS-safe");
        }
        Objects.requireNonNull(party, "party must not be null");
        identifier(actorId, "actorId", 128);
        Objects.requireNonNull(actorRole, "actorRole must not be null");
        if (actorRole != ActorRole.USER && actorRole != ActorRole.MERCHANT) {
            throw new IllegalArgumentException("actorRole must be a party role");
        }
        identifier(accessSessionId, "accessSessionId", 64);
        identifier(registrationId, "registrationId", 128);
        identifier(messageId, "messageId", 128);
        if (text == null || text.isBlank() || text.length() > 8_192) {
            throw new IllegalArgumentException("text must be nonblank and bounded");
        }
    }

    public void requireRoute(IntakeAuthorityRoute route) {
        Objects.requireNonNull(route, "route must not be null");
        if (!tenantSurrogate.equals(route.tenantSurrogate())
                || !caseId.equals(route.caseId())
                || roomEpoch != route.roomEpoch()
                || party != route.party()
                || !actorId.equals(route.actorId())
                || actorRole != route.actorRole()
                || !accessSessionId.equals(route.accessSessionId())
                || !registrationId.equals(route.registrationId())) {
            throw new IllegalArgumentException("human input does not match the server-resolved route");
        }
    }

    private static void identifier(String value, String field, int maximumLength) {
        if (value == null
                || value.length() > maximumLength
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0," + (maximumLength - 1) + "}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
    }
}
