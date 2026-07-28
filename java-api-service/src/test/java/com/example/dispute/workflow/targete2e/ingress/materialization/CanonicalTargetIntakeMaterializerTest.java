package com.example.dispute.workflow.targete2e.ingress.materialization;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.config.ActorRole;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import org.junit.jupiter.api.Test;

class CanonicalTargetIntakeMaterializerTest {

    private static final String DOMAIN_TENANT = "default";
    private static final String TARGET_TENANT_SURROGATE = "tenant-target-activation";
    private static final String CASE_ID = "CASE_TARGET_001";
    private static final String ACTOR_ID = "user-local";

    @Test
    void acceptsDomainAccessTenantThatDiffersFromTargetActivationTenantSurrogate() {
        CaseAccessSessionEntity access = access(DOMAIN_TENANT, CASE_ID, ACTOR_ID, ActorRole.USER);
        assertThat(access.getTenantId()).isNotEqualTo(TARGET_TENANT_SURROGATE);

        assertThatCode(() -> CanonicalTargetIntakeMaterializer.requireActor(
                        access, CASE_ID, ACTOR_ID, ActorRole.USER))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsARequestForAnotherCase() {
        CaseAccessSessionEntity access = access(DOMAIN_TENANT, CASE_ID, ACTOR_ID, ActorRole.USER);

        assertRejected(access, "CASE_TARGET_002", ACTOR_ID, ActorRole.USER);
    }

    @Test
    void rejectsAnotherActor() {
        CaseAccessSessionEntity access = access(DOMAIN_TENANT, CASE_ID, ACTOR_ID, ActorRole.USER);

        assertRejected(access, CASE_ID, "merchant-local", ActorRole.USER);
    }

    @Test
    void rejectsAnotherRole() {
        CaseAccessSessionEntity access = access(DOMAIN_TENANT, CASE_ID, ACTOR_ID, ActorRole.USER);

        assertRejected(access, CASE_ID, ACTOR_ID, ActorRole.MERCHANT);
    }

    private static CaseAccessSessionEntity access(
            String domainTenant, String caseId, String actorId, ActorRole actorRole) {
        return CaseAccessSessionEntity.create(
                "access-1", domainTenant, caseId, actorId, actorRole,
                PermissionLevel.PARTY_USER, "test");
    }

    private static void assertRejected(
            CaseAccessSessionEntity access, String caseId, String actorId, ActorRole actorRole) {
        assertThatThrownBy(() -> CanonicalTargetIntakeMaterializer.requireActor(
                        access, caseId, actorId, actorRole))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("target Intake access session does not match the active authority");
    }
}
