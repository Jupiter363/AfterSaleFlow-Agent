package com.example.dispute.hearing.infrastructure.persistence.entity;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.domain.HearingRoundSubmissionSource;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "hearing_round_party_submission")
public class HearingRoundPartySubmissionEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "round_id", length = 64, nullable = false)
    private String roundId;
    @Column(name = "round_no", nullable = false)
    private int roundNo;
    @Enumerated(EnumType.STRING)
    @Column(name = "participant_role", length = 32, nullable = false)
    private ActorRole participantRole;
    @Column(name = "participant_id", length = 128, nullable = false)
    private String participantId;
    @Enumerated(EnumType.STRING)
    @Column(name = "submission_source", length = 32, nullable = false)
    private HearingRoundSubmissionSource submissionSource;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "submission_json", nullable = false, columnDefinition = "jsonb")
    private String submissionJson;
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;
    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected HearingRoundPartySubmissionEntity() {}

    private HearingRoundPartySubmissionEntity(String id) {
        super(id);
    }

    public static HearingRoundPartySubmissionEntity submit(
            String id,
            String caseId,
            String roundId,
            int roundNo,
            ActorRole participantRole,
            String participantId,
            HearingRoundSubmissionSource submissionSource,
            String submissionJson,
            Instant now) {
        if (participantRole != ActorRole.USER && participantRole != ActorRole.MERCHANT) {
            throw new IllegalArgumentException("hearing round submission requires case party role");
        }
        HearingRoundPartySubmissionEntity entity =
                new HearingRoundPartySubmissionEntity(id);
        entity.caseId = caseId;
        entity.roundId = roundId;
        entity.roundNo = roundNo;
        entity.participantRole = participantRole;
        entity.participantId = participantId;
        entity.submissionSource = submissionSource;
        entity.submissionJson =
                submissionJson == null || submissionJson.isBlank()
                        ? "{}"
                        : submissionJson;
        entity.submittedAt = now;
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.createdBy = participantId;
        entity.updatedBy = participantId;
        return entity;
    }

    public ActorRole getParticipantRole() {
        return participantRole;
    }

    public HearingRoundSubmissionSource getSubmissionSource() {
        return submissionSource;
    }

    public String getSubmissionJson() {
        return submissionJson;
    }
}
