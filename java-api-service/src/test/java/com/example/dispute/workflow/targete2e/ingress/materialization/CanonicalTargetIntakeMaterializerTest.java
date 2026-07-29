package com.example.dispute.workflow.targete2e.ingress.materialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.config.ActorRole;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import org.junit.jupiter.api.Test;

class CanonicalTargetIntakeMaterializerTest {

    private static final String TARGET_TENANT_SURROGATE = "tenant-target-activation";
    private static final String CASE_ID = "CASE_TARGET_001";
    private static final String ACTOR_ID = "user-local";

    @Test
    void convertsTheApplicationTraceIdToW3cTraceparent() {
        assertThat(CanonicalTargetIntakeMaterializer.traceparent(
                        "TRACE_ae3fa9df57c76361ca14af2948ddba85"))
                .isEqualTo("00-ae3fa9df57c76361ca14af2948ddba85-0000000000000001-01");
    }

    @Test
    void requiresAccessSessionToUseTheActivationTenantSurrogate() {
        CaseAccessSessionEntity access = access(TARGET_TENANT_SURROGATE, CASE_ID, ACTOR_ID, ActorRole.USER);

        CanonicalTargetIntakeMaterializer.requireActor(
                access, TARGET_TENANT_SURROGATE, CASE_ID, ACTOR_ID, ActorRole.USER);
    }

    @Test
    void rejectsARequestForAnotherCase() {
        CaseAccessSessionEntity access = access(TARGET_TENANT_SURROGATE, CASE_ID, ACTOR_ID, ActorRole.USER);

        assertRejected(access, TARGET_TENANT_SURROGATE, "CASE_TARGET_002", ACTOR_ID, ActorRole.USER);
    }

    @Test
    void rejectsAnotherActor() {
        CaseAccessSessionEntity access = access(TARGET_TENANT_SURROGATE, CASE_ID, ACTOR_ID, ActorRole.USER);

        assertRejected(access, TARGET_TENANT_SURROGATE, CASE_ID, "merchant-local", ActorRole.USER);
    }

    @Test
    void rejectsAnotherRole() {
        CaseAccessSessionEntity access = access(TARGET_TENANT_SURROGATE, CASE_ID, ACTOR_ID, ActorRole.USER);

        assertRejected(access, TARGET_TENANT_SURROGATE, CASE_ID, ACTOR_ID, ActorRole.MERCHANT);
    }

    @Test
    void rejectsAnAccessSessionFromAnotherTenant() {
        CaseAccessSessionEntity access = access("default", CASE_ID, ACTOR_ID, ActorRole.USER);

        assertRejected(access, TARGET_TENANT_SURROGATE, CASE_ID, ACTOR_ID, ActorRole.USER);
    }

    private static CaseAccessSessionEntity access(
            String domainTenant, String caseId, String actorId, ActorRole actorRole) {
        return CaseAccessSessionEntity.create(
                "access-1", domainTenant, caseId, actorId, actorRole,
                PermissionLevel.PARTY_USER, "test");
    }

    private static void assertRejected(
            CaseAccessSessionEntity access,
            String tenantId,
            String caseId,
            String actorId,
            ActorRole actorRole) {
        assertThatThrownBy(() -> CanonicalTargetIntakeMaterializer.requireActor(
                        access, tenantId, caseId, actorId, actorRole))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("target Intake access session does not match the active authority");
    }
}
