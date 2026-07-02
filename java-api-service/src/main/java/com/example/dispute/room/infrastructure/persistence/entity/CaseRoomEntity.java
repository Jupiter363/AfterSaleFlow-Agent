package com.example.dispute.room.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "case_room")
public class CaseRoomEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 32, nullable = false)
    private RoomType roomType;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_status", length = 32, nullable = false)
    private RoomStatus roomStatus;

    @Column(name = "opened_at")
    private OffsetDateTime openedAt;

    @Column(name = "sealed_at")
    private OffsetDateTime sealedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    private String metadataJson;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected CaseRoomEntity() {}

    private CaseRoomEntity(String id) {
        super(id);
    }

    public static CaseRoomEntity open(
            String id,
            String caseId,
            RoomType roomType,
            OffsetDateTime now,
            String actorId) {
        return create(id, caseId, roomType, RoomStatus.OPEN, now, null, actorId);
    }

    public static CaseRoomEntity closed(
            String id,
            String caseId,
            RoomType roomType,
            OffsetDateTime now,
            String actorId) {
        return create(id, caseId, roomType, RoomStatus.CLOSED, now, now, actorId);
    }

    private static CaseRoomEntity create(
            String id,
            String caseId,
            RoomType roomType,
            RoomStatus roomStatus,
            OffsetDateTime openedAt,
            OffsetDateTime closedAt,
            String actorId) {
        CaseRoomEntity entity = new CaseRoomEntity(required(id, "id"));
        entity.caseId = required(caseId, "caseId");
        entity.roomType = Objects.requireNonNull(roomType, "roomType must not be null");
        entity.roomStatus = Objects.requireNonNull(roomStatus, "roomStatus must not be null");
        entity.openedAt = openedAt;
        entity.closedAt = closedAt;
        entity.metadataJson = "{}";
        entity.createdBy = required(actorId, "actorId");
        entity.updatedBy = actorId;
        return entity;
    }

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getCaseId() {
        return caseId;
    }

    public RoomType getRoomType() {
        return roomType;
    }

    public RoomStatus getRoomStatus() {
        return roomStatus;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
