package com.example.dispute.room.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Immutable;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "case_timeline_event")
@Immutable
public class CaseTimelineEventEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "dossier_id", length = 64)
    private String dossierId;
    @Column(name = "sequence_no", nullable = false)
    private long sequenceNo;
    @Column(name = "room_id", length = 64)
    private String roomId;
    @Column(name = "event_type", length = 64, nullable = false)
    private String eventType;
    @Column(name = "event_time", nullable = false)
    private Instant eventTime;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_refs_json", nullable = false, columnDefinition = "jsonb")
    private String sourceRefsJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_json", nullable = false, columnDefinition = "jsonb")
    private String eventJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audience_json", nullable = false, columnDefinition = "jsonb")
    private String audienceJson;
    @Column(name = "event_key", length = 128)
    private String eventKey;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected CaseTimelineEventEntity() {}
    private CaseTimelineEventEntity(String id) { super(id); }

    public static CaseTimelineEventEntity create(
            String id, String caseId, String roomId, long sequenceNo, String eventType,
            Instant eventTime, String sourceRefsJson, String eventJson, String audienceJson,
            String eventKey, String createdBy) {
        CaseTimelineEventEntity entity = new CaseTimelineEventEntity(id);
        entity.caseId = caseId;
        entity.roomId = roomId;
        entity.sequenceNo = sequenceNo;
        entity.eventType = eventType;
        entity.eventTime = eventTime;
        entity.sourceRefsJson = sourceRefsJson;
        entity.eventJson = eventJson;
        entity.audienceJson = audienceJson;
        entity.eventKey = eventKey;
        entity.createdAt = eventTime;
        entity.createdBy = createdBy;
        return entity;
    }

    public long getSequenceNo() { return sequenceNo; }
    public String getEventType() { return eventType; }
    public String getRoomId() { return roomId; }
    public String getEventJson() { return eventJson; }
    public String getAudienceJson() { return audienceJson; }
    public Instant getEventTime() { return eventTime; }
}
