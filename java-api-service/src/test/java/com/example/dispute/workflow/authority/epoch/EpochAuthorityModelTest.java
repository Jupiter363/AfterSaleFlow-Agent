package com.example.dispute.workflow.authority.epoch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.config.ActorRole;
import com.example.dispute.casecore.domain.CasePartyAssignment;
import com.example.dispute.workflow.application.authority.epoch.AgentSessionProfileRegistry;
import com.example.dispute.workflow.application.authority.epoch.EpochPartyAuthority;
import com.example.dispute.workflow.application.authority.epoch.EpochPartyAuthority.Party;
import com.example.dispute.workflow.application.authority.epoch.EpochAuthorityException;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator.LockRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class EpochAuthorityModelTest {

    private static final String HASH = "a".repeat(64);
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 7, 22, 0, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void lockRequestNormalizesEveryTableByAscendingIdentifier() {
        LockRequest request = new LockRequest(
                List.of("access-b", "access-a"),
                List.of("agent-b", "agent-a"),
                List.of("registration-b", "registration-a"));

        assertThat(request.accessSessionIds()).containsExactly("access-a", "access-b");
        assertThat(request.agentSessionIds()).containsExactly("agent-a", "agent-b");
        assertThat(request.registrationIds()).containsExactly("registration-a", "registration-b");
    }

    @Test
    void lockRequestRejectsDuplicateIdsBecauseTheLockSetMustBeDeterministic() {
        assertThatThrownBy(() -> new LockRequest(
                        List.of("access-a", "access-a"), List.of("agent-a"), List.of("registration-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique ids");
    }

    @Test
    void merchantInitiatedPartyAssignmentRemainsBilateral() {
        CasePartyAssignment assignment = new CasePartyAssignment(
                "merchant-1", ActorRole.MERCHANT, "user-1", ActorRole.USER);

        EpochPartyAuthority initiator = party(
                Party.INITIATOR, "merchant-1", ActorRole.MERCHANT, "PARTY_MERCHANT");
        EpochPartyAuthority respondent = party(
                Party.RESPONDENT, "user-1", ActorRole.USER, "PARTY_USER");

        assertThat(assignment.resolve(initiator.actorId(), initiator.actorRole())).contains(
                com.example.dispute.casecore.domain.CasePartyPosition.INITIATOR);
        assertThat(assignment.resolve(respondent.actorId(), respondent.actorRole())).contains(
                com.example.dispute.casecore.domain.CasePartyPosition.RESPONDENT);
    }

    @Test
    void partyAuthorityRejectsRolePermissionShortcuts() {
        assertThatThrownBy(() -> party(Party.INITIATOR, "merchant-1", ActorRole.MERCHANT, "PARTY_USER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permission");
    }

    @Test
    void authorityExceptionCarriesStableReasonCode() {
        EpochAuthorityException exception = new EpochAuthorityException("AUTHORITY_STATUS_REVOKED", "revoked");
        assertThat(exception.reasonCode()).isEqualTo("AUTHORITY_STATUS_REVOKED");
    }

    private static EpochPartyAuthority party(
            Party party, String actorId, ActorRole role, String permission) {
        return new EpochPartyAuthority(
                party.name() + "-AUTH",
                "EPOCH-1",
                party,
                "TENANT-1",
                "CASE-1",
                "TENANT-1",
                "CASE-1",
                com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.INTAKE,
                0,
                1,
                party.name() + "-REG",
                HASH,
                "grt.v1." + "b".repeat(32),
                actorId,
                role,
                role,
                HASH,
                party.name() + "-ACCESS",
                permission,
                party.name() + "-AGENT",
                "DISPUTE_INTAKE_OFFICER",
                "prompt.v1",
                "agent-session-profile.v1",
                AgentSessionProfileRegistry.profileId(role, "prompt.v1"),
                "GRAPH_PRIVATE_NO_MEMORY_FRAME_V1",
                NOW);
    }
}
