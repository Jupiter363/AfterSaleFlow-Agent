package com.example.dispute.workflow.targete2e.rooms.evidence;

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
        String initiator = participant(connection, route.caseId(), "USER");
        String respondent = participant(connection, route.caseId(), "MERCHANT");
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
            select fencing_token, lifecycle_status, writer_mode
              from case_room_epoch
             where case_id = ? and room_type = 'EVIDENCE' and room_epoch = ? for update
            """)) {
      statement.setString(1, request.caseId());
      statement.setLong(2, request.roomEpoch());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()
            || row.getLong(1) != request.fencingToken()
            || !"ACTIVE".equals(row.getString(2))
            || !"TEMPORAL".equals(row.getString(3))
            || row.next()) {
          throw new IllegalStateException("target Evidence epoch authority is invalid");
        }
      }
    }
  }

  private static String participant(Connection connection, String caseId, String role) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            select actor_id
              from case_participant
             where case_id = ? and participant_role = ? and participant_status = 'ACTIVE'
             for update
            """)) {
      statement.setString(1, caseId);
      statement.setString(2, role);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          throw new IllegalStateException("target Evidence " + role + " participant is absent");
        }
        String actorId = row.getString(1);
        if (row.next()) {
          throw new IllegalStateException("target Evidence " + role + " participant is ambiguous");
        }
        return actorId;
      }
    }
  }

  private static String hash(Request request, String initiator, String respondent) {
    String canonical =
        String.join(
            "\n",
            "target-e2e-evidence-participant-binding.v1",
            request.tenantSurrogate(),
            request.caseId(),
            Long.toString(request.roomEpoch()),
            Long.toString(request.fencingToken()),
            "USER",
            initiator,
            "MERCHANT",
            respondent);
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }
}
