package com.example.dispute.workflow.runtime.rooms.evidence;

import io.temporal.failure.ApplicationFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import javax.sql.DataSource;

/** JDBC authority read for the immutable Evidence party binding. */
public final class JdbcTargetEvidenceParticipantBindingActivities
    implements TargetEvidenceParticipantBindingActivities {
  public static final String BINDING_INVALID = "TARGET_EVIDENCE_PARTICIPANT_BINDING_INVALID";

  private final DataSource dataSource;

  public JdbcTargetEvidenceParticipantBindingActivities(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public Binding bind(Request request) {
    try (Connection connection = dataSource.getConnection()) {
      boolean originalAutoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        Request route = Objects.requireNonNull(request, "request");
        lockEpoch(connection, route);
        CaseParties parties = caseParties(connection, route.caseId());
        String initiator = parties.initiatorId();
        String respondent = parties.respondentId();
        Binding result =
            new Binding(
                route.tenantSurrogate(),
                route.caseId(),
                route.roomEpoch(),
                route.fencingToken(),
                initiator,
                respondent,
                hash(route, initiator, respondent));
        connection.commit();
        return result;
      } catch (RuntimeException | SQLException failure) {
        connection.rollback();
        throw failure;
      } finally {
        connection.setAutoCommit(originalAutoCommit);
      }
    } catch (ApplicationFailure failure) {
      throw failure;
    } catch (IllegalArgumentException | IllegalStateException | SQLException failure) {
      throw ApplicationFailure.newNonRetryableFailure(failure.getMessage(), BINDING_INVALID);
    }
  }

  private static void lockEpoch(Connection connection, Request request) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            select fencing_token, lifecycle_status, provisioning_status, writer_mode,
                   room_temporal_run_id
              from case_room_epoch
             where tenant_surrogate = ? and case_id = ? and room_type = 'EVIDENCE'
               and room_epoch = ? and fencing_token = ?
             for update
            """)) {
      statement.setString(1, request.tenantSurrogate());
      statement.setString(2, request.caseId());
      statement.setLong(3, request.roomEpoch());
      statement.setLong(4, request.fencingToken());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()
            || row.getLong(1) != request.fencingToken()
            || !allowedEpochState(row.getString(2), row.getString(3), row.getString(5))
            || !"TEMPORAL".equals(row.getString(4))
            || row.next()) {
          throw new IllegalStateException("target Evidence epoch authority is invalid");
        }
      }
    }
  }

  static boolean allowedEpochState(
      String lifecycleStatus, String provisioningStatus, String roomRunId) {
    boolean runAbsent = roomRunId == null || roomRunId.isBlank();
    return ("PROVISIONING".equals(lifecycleStatus)
            && "PROVISIONING".equals(provisioningStatus)
            && runAbsent)
        || ("ACTIVE".equals(lifecycleStatus)
            && "READY".equals(provisioningStatus)
            && !runAbsent);
  }

  private static CaseParties caseParties(Connection connection, String caseId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            select dispute.user_id, dispute.merchant_id,
                   dispute.initiator_id, dispute.initiator_role,
                   dispute.respondent_id, dispute.respondent_role
              from fulfillment_dispute_case dispute
              join case_participant initiator
                on initiator.case_id = dispute.id
               and initiator.actor_id = dispute.initiator_id
               and initiator.participant_role = dispute.initiator_role
               and initiator.participant_status = 'ACTIVE'
              join case_participant respondent
                on respondent.case_id = dispute.id
               and respondent.actor_id = dispute.respondent_id
               and respondent.participant_role = dispute.respondent_role
               and respondent.participant_status = 'ACTIVE'
             where dispute.id = ?
             for update of dispute, initiator, respondent
            """)) {
      statement.setString(1, caseId);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          throw new IllegalStateException("target Evidence case parties are absent");
        }
        CaseParties parties =
            exactCaseParties(
                row.getString(1),
                row.getString(2),
                row.getString(3),
                row.getString(4),
                row.getString(5),
                row.getString(6));
        if (row.next()) {
          throw new IllegalStateException("target Evidence case parties are ambiguous");
        }
        return parties;
      }
    }
  }

  static CaseParties exactCaseParties(
      String userId,
      String merchantId,
      String initiatorId,
      String initiatorRole,
      String respondentId,
      String respondentRole) {
    boolean userInitiated =
        "USER".equals(initiatorRole)
            && Objects.equals(userId, initiatorId)
            && "MERCHANT".equals(respondentRole)
            && Objects.equals(merchantId, respondentId);
    boolean merchantInitiated =
        "MERCHANT".equals(initiatorRole)
            && Objects.equals(merchantId, initiatorId)
            && "USER".equals(respondentRole)
            && Objects.equals(userId, respondentId);
    if (userId == null
        || userId.isBlank()
        || merchantId == null
        || merchantId.isBlank()
        || userId.equals(merchantId)
        || initiatorId == null
        || respondentId == null
        || initiatorId.equals(respondentId)
        || (!userInitiated && !merchantInitiated)) {
      throw new IllegalStateException("target Evidence case party assignment is invalid");
    }
    return new CaseParties(initiatorId, respondentId);
  }

  private static String hash(Request request, String initiator, String respondent) {
    String canonical =
        String.join(
            "\n",
            "production-runtime-evidence-participant-binding.v1",
            request.tenantSurrogate(),
            request.caseId(),
            Long.toString(request.roomEpoch()),
            Long.toString(request.fencingToken()),
            "INITIATOR",
            initiator,
            "RESPONDENT",
            respondent);
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  record CaseParties(String initiatorId, String respondentId) {}
}
