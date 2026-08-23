package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reconstructs the exact durable HearingRoomStart needed by the Agent formal commit transaction. */
final class JdbcTargetHearingRoomStartLoader {

  private final JdbcTemplate jdbc;

  JdbcTargetHearingRoomStartLoader(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
  }

  HearingRoomStart load(TargetHearingFinalizationRequest request) {
    Objects.requireNonNull(request, "request");
    HearingAuthorityExpectation authority =
        request.formalCommand().authorityCommit().authority();
    TargetHearingCommandMaterial.PartyStageAuthority stageAuthority =
        Objects.requireNonNull(
            request.material().material().partyStageAuthority(),
            "Target Hearing stage authority is absent");
    if (!stageAuthority.tenantSurrogate().equals(authority.tenantSurrogate())
        || !stageAuthority.caseId().equals(authority.caseId())
        || stageAuthority.roomEpoch() != authority.roomEpoch()
        || stageAuthority.fencingToken() != authority.fencingToken()) {
      throw new IllegalStateException("Target Hearing stage authority drifted");
    }
    List<StartFact> rows = jdbc.query(
        """
        select epoch.room_id, dispute.initiator_id, dispute.respondent_id,
               flow.created_at, epoch.temporal_build_id
          from case_room_epoch epoch
          join fulfillment_dispute_case dispute on dispute.id = epoch.case_id
          join case_room room
            on room.id = epoch.room_id
           and room.case_id = epoch.case_id
           and room.room_type = epoch.room_type
          join hearing_flow_instance flow
            on flow.id = ? and flow.case_id = epoch.case_id
         where epoch.id = ? and epoch.tenant_surrogate = ? and epoch.case_id = ?
           and epoch.room_type = 'HEARING' and epoch.room_epoch = ?
           and epoch.writer_mode = 'TEMPORAL' and epoch.fencing_token = ?
           and epoch.temporal_build_id is not null
         for update of epoch, dispute, room, flow
        """,
        (row, ignored) -> new StartFact(
            row.getString(1),
            row.getString(2),
            row.getString(3),
            row.getObject(4, OffsetDateTime.class),
            row.getString(5)),
        authority.flowInstanceId(),
        authority.epochId(),
        authority.tenantSurrogate(),
        authority.caseId(),
        authority.roomEpoch(),
        authority.fencingToken());
    if (rows.size() != 1) {
      throw new IllegalStateException("Target Hearing room start is absent or ambiguous");
    }
    StartFact fact = rows.getFirst();
    return new HearingRoomStart(
        "hearing-room-start.v1",
        authority.tenantSurrogate(),
        authority.caseId(),
        fact.roomId(),
        authority.flowInstanceId(),
        authority.epochId(),
        HearingWriterMode.TEMPORAL,
        authority.roomEpoch(),
        authority.fencingToken(),
        fact.initiatorId(),
        fact.respondentId(),
        fact.openedAt().toInstant(),
        stageAuthority.hearingDeadlineAt(),
        stageAuthority.partyStageWindowSeconds(),
        authority.processRevision(),
        authority.roomRevision(),
        fact.workflowBuildId());
  }

  private record StartFact(
      String roomId,
      String initiatorId,
      String respondentId,
      OffsetDateTime openedAt,
      String workflowBuildId) {}
}
