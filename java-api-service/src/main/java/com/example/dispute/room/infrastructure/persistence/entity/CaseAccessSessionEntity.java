package com.example.dispute.room.infrastructure.persistence.entity;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.domain.PermissionScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "case_access_session")
public class CaseAccessSessionEntity extends AbstractEntity {

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "actor_id", length = 128, nullable = false)
    private String actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", length = 64, nullable = false)
    private ActorRole actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission_level", length = 64, nullable = false)
    private PermissionLevel permissionLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permission_scopes_json", nullable = false, columnDefinition = "jsonb")
    private String permissionScopesJson;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Transient private Set<PermissionScope> explicitScopes;

    protected CaseAccessSessionEntity() {}

    private CaseAccessSessionEntity(String id) {
        super(required(id, "id"));
    }

    public static CaseAccessSessionEntity create(
            String id,
            String tenantId,
            String caseId,
            String actorId,
            ActorRole actorRole,
            PermissionLevel permissionLevel,
            String createdBy) {
        return create(
                id,
                tenantId,
                caseId,
                actorId,
                actorRole,
                permissionLevel,
                permissionLevel.defaultScopes(),
                createdBy);
    }

    public static CaseAccessSessionEntity create(
            String id,
            String tenantId,
            String caseId,
            String actorId,
            ActorRole actorRole,
            PermissionLevel permissionLevel,
            Set<PermissionScope> scopes,
            String createdBy) {
        CaseAccessSessionEntity entity = new CaseAccessSessionEntity(id);
        entity.tenantId = required(tenantId, "tenantId");
        entity.caseId = required(caseId, "caseId");
        entity.actorId = required(actorId, "actorId");
        entity.actorRole = Objects.requireNonNull(actorRole, "actorRole must not be null");
        entity.permissionLevel =
                Objects.requireNonNull(permissionLevel, "permissionLevel must not be null");
        entity.explicitScopes =
                Collections.unmodifiableSet(
                        scopes == null || scopes.isEmpty()
                                ? permissionLevel.defaultScopes()
                                : EnumSet.copyOf(scopes));
        entity.permissionScopesJson = renderScopes(entity.explicitScopes);
        entity.status = "ACTIVE";
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.createdBy = required(createdBy, "createdBy");
        return entity;
    }

    public Set<PermissionScope> permissionScopes() {
        if (explicitScopes != null) {
            return explicitScopes;
        }
        return permissionLevel.defaultScopes();
    }

    public boolean has(PermissionScope scope) {
        return permissionScopes().contains(scope);
    }

    public boolean privileged() {
        return permissionLevel.privileged();
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getActorId() {
        return actorId;
    }

    public ActorRole getActorRole() {
        return actorRole;
    }

    public PermissionLevel getPermissionLevel() {
        return permissionLevel;
    }

    public String getPermissionScopesJson() {
        return permissionScopesJson;
    }

    private static String renderScopes(Set<PermissionScope> scopes) {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (PermissionScope scope : scopes) {
            if (!first) {
                json.append(',');
            }
            json.append('"').append(scope.name()).append('"');
            first = false;
        }
        return json.append(']').toString();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
