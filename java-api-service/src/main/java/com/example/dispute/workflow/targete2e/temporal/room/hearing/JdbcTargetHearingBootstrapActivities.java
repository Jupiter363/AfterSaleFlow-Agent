package com.example.dispute.workflow.targete2e.temporal.room.hearing;

import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import io.temporal.failure.ApplicationFailure;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC implementation of the target Hearing start fence. */
public final class JdbcTargetHearingBootstrapActivities
    implements TargetHearingBootstrapActivities {

  public static final String BOOTSTRAP_INVALID = "TARGET_HEARING_BOOTSTRAP_INVALID";
  private static final String FLOW_SCHEMA = "hearing_flow.v2";
  private static final String CONTROL_ACTOR = "target-e2e-hearing-bootstrap";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final String temporalNamespace;

  public JdbcTargetHearingBootstrapActivities(
      DataSource dataSource, TransactionTemplate transactions, String temporalNamespace) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    if (temporalNamespace == null || temporalNamespace.isBlank() || temporalNamespace.length() > 128) {
      throw new IllegalArgumentException("Temporal namespace is required for Hearing bootstrap");
    }
    this.temporalNamespace = temporalNamespace;
  }

  @Override
  public Binding bootstrap(ProvisionRoomEpoch provision) {
    ProvisionRoomEpoch route = TargetHearingBootstrapActivities.requireHearing(provision);
    try {
      return Objects.requireNonNull(
          transactions.execute(ignored -> bootstrapLocked(route)),
          "target Hearing bootstrap transaction returned null");
    } catch (ApplicationFailure failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw ApplicationFailure.newNonRetryableFailure(failure.getMessage(), BOOTSTRAP_INVALID);
    }
  }

  @Override
  public void awaitActivation(ActivationRequest request) {
    Objects.requireNonNull(request, "request");
    try {
      List<ActivationRow> rows =
          jdbc.query(
              """
              select epoch.lifecycle_status, epoch.provisioning_status,
                     epoch.temporal_run_id as case_workflow_run_id,
                     epoch.room_temporal_run_id as room_workflow_run_id,
                     process_projection.writer_activation_status,
                     process_projection.temporal_run_id as projection_case_run_id,
                     hearing_projection.temporal_run_id as hearing_room_run_id
                from case_room_epoch epoch
                join case_process_projection process_projection
                  on process_projection.case_id = epoch.case_id
                 and process_projection.tenant_surrogate = epoch.tenant_surrogate
                 and process_projection.current_room = 'HEARING'
                 and process_projection.writer_mode = 'TEMPORAL'
                 and process_projection.room_epoch = epoch.room_epoch
                 and process_projection.fencing_token = epoch.fencing_token
                 and process_projection.process_revision = epoch.process_revision
                join hearing_temporal_projection hearing_projection
                  on hearing_projection.flow_instance_id = epoch.room_id
                 and hearing_projection.case_id = epoch.case_id
                 and hearing_projection.tenant_surrogate = epoch.tenant_surrogate
                 and hearing_projection.epoch_id = epoch.id
                 and hearing_projection.room_type = 'HEARING'
                 and hearing_projection.hearing_epoch = epoch.room_epoch
                 and hearing_projection.fencing_token = epoch.fencing_token
                 and hearing_projection.process_revision = epoch.process_revision
                 and hearing_projection.room_revision = epoch.room_revision
                 and hearing_projection.writer_mode = 'TEMPORAL'
               where epoch.id = ? and epoch.tenant_surrogate = ? and epoch.case_id = ?
                 and epoch.room_id = ? and epoch.room_type = 'HEARING'
                 and epoch.room_epoch = ? and epoch.fencing_token = ?
                 and epoch.process_revision = ? and epoch.room_revision = ?
                 and epoch.writer_mode = 'TEMPORAL'
                 and epoch.room_temporal_workflow_id = ?
                 and epoch.room_workflow_build_id = ?
              """,
              (row, ignored) ->
                  new ActivationRow(
                      row.getString("lifecycle_status"),
                      row.getString("provisioning_status"),
                      row.getString("case_workflow_run_id"),
                      row.getString("room_workflow_run_id"),
                      row.getString("writer_activation_status"),
                      row.getString("projection_case_run_id"),
                      row.getString("hearing_room_run_id")),
              request.epochId(),
              request.tenantSurrogate(),
              request.caseId(),
              request.flowInstanceId(),
              request.roomEpoch(),
              request.fencingToken(),
              request.processRevision(),
              request.roomRevision(),
              request.roomWorkflowId(),
              request.roomWorkflowBuildId());
      ActivationRow state = exactlyOne(rows, "target Hearing activation authority drifted");
      ActivationPhase phase = activationPhase(request, state);
      if (phase == ActivationPhase.READY) {
        return;
      }
      if (phase == ActivationPhase.PENDING) {
        throw ApplicationFailure.newFailure(
            "target Hearing activation is not committed yet", ACTIVATION_PENDING);
      }
      throw ApplicationFailure.newNonRetryableFailure(
          "target Hearing activation authority is invalid", ACTIVATION_INVALID);
    } catch (ApplicationFailure failure) {
      throw failure;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      throw ApplicationFailure.newNonRetryableFailure(failure.getMessage(), ACTIVATION_INVALID);
    } catch (RuntimeException failure) {
      throw ApplicationFailure.newFailure(
          "target Hearing activation read is temporarily unavailable", ACTIVATION_PENDING);
    }
  }

  private Binding bootstrapLocked(ProvisionRoomEpoch provision) {
    CaseRow caseRow = lockCase(provision.caseId());
    EpochRow epoch = lockEpoch(provision);
    lockCaseProjection(provision, epoch);
    Participants participants = lockParticipants(provision.caseId(), caseRow);

    ProjectionRow projection = lockProjection(provision.caseId(), epoch.epochId());
    if (projection == null) {
      createInitialFlowAndProjection(provision, epoch);
      projection = lockProjection(provision.caseId(), epoch.epochId());
    }
    require(projection != null, "Hearing projection was not created");
    requireExactReplay(provision, epoch, projection);
    requireInitialFlow(provision.caseId(), provision.roomId());
    return new Binding(
        projection.flowInstanceId(),
        epoch.epochId(),
        epoch.roomEpoch(),
        epoch.fencingToken(),
        epoch.processRevision(),
        epoch.roomRevision(),
        projection.stageCode(),
        projection.stageSequence(),
        participants.initiatorId(),
        participants.respondentId());
  }

  private CaseRow lockCase(String caseId) {
    List<CaseRow> rows =
        jdbc.query(
            """
            select id, user_id, merchant_id, initiator_id, initiator_role,
                   respondent_id, respondent_role, current_room
              from fulfillment_dispute_case
             where id = ?
             for update
            """,
            (row, ignored) ->
                new CaseRow(
                    row.getString("id"),
                    row.getString("user_id"),
                    row.getString("merchant_id"),
                    row.getString("initiator_id"),
                    row.getString("initiator_role"),
                    row.getString("respondent_id"),
                    row.getString("respondent_role"),
                    row.getString("current_room")),
            caseId);
    return exactlyOne(rows, "target Hearing case is absent or ambiguous");
  }

  private EpochRow lockEpoch(ProvisionRoomEpoch provision) {
    List<EpochRow> rows =
        jdbc.query(
            """
            select id, tenant_surrogate, case_id, room_id, room_epoch, fencing_token,
                   process_revision, room_revision, room_temporal_workflow_id,
                   room_temporal_run_id, room_workflow_build_id, lifecycle_status,
                   provisioning_status, writer_mode
              from case_room_epoch
             where id = ? and tenant_surrogate = ? and case_id = ? and room_id = ?
               and room_type = 'HEARING' and room_epoch = ? and fencing_token = ?
             for update
            """,
            (row, ignored) ->
                new EpochRow(
                    row.getString("id"),
                    row.getLong("room_epoch"),
                    row.getLong("fencing_token"),
                    row.getLong("process_revision"),
                    row.getLong("room_revision"),
                    row.getString("room_temporal_workflow_id"),
                    row.getString("room_temporal_run_id"),
                    row.getString("room_workflow_build_id"),
                    row.getString("lifecycle_status"),
                    row.getString("provisioning_status"),
                    row.getString("writer_mode")),
            provision.epochId(),
            provision.tenantSurrogate(),
            provision.caseId(),
            provision.roomId(),
            provision.roomEpoch(),
            provision.fencingToken());
    EpochRow value = exactlyOne(rows, "target Hearing epoch authority is absent or ambiguous");
    require(
        allowedEpochState(
                value.lifecycleStatus(), value.provisioningStatus(), value.roomRunId())
            && "TEMPORAL".equals(value.writerMode())
            && provision.initialProcessRevision() == value.processRevision()
            && provision.initialRoomRevision() == value.roomRevision()
            && provision.roomWorkflowId().equals(value.roomWorkflowId())
            && provision.roomWorkflowBuildId().equals(value.roomWorkflowBuildId()),
        "target Hearing epoch authority drifted");
    return value;
  }

  private void lockCaseProjection(ProvisionRoomEpoch provision, EpochRow epoch) {
    List<CaseProjectionRow> rows =
        jdbc.query(
            """
            select writer_activation_status, temporal_run_id
              from case_process_projection
             where case_id = ? and tenant_surrogate = ? and current_room = 'HEARING'
               and writer_mode = 'TEMPORAL' and room_epoch = ? and fencing_token = ?
               and process_revision = ?
             for update
            """,
            (row, ignored) ->
                new CaseProjectionRow(
                    row.getString("writer_activation_status"),
                    row.getString("temporal_run_id")),
            provision.caseId(),
            provision.tenantSurrogate(),
            provision.roomEpoch(),
            provision.fencingToken(),
            provision.initialProcessRevision());
    CaseProjectionRow projection =
        exactlyOne(rows, "target Hearing case projection drifted");
    boolean provisioning =
        "PROVISIONING".equals(epoch.lifecycleStatus())
            && "PROVISIONING".equals(epoch.provisioningStatus())
            && "PROVISIONING".equals(projection.activationStatus())
            && !nonBlank(projection.temporalRunId());
    boolean ready =
        "ACTIVE".equals(epoch.lifecycleStatus())
            && "READY".equals(epoch.provisioningStatus())
            && "READY".equals(projection.activationStatus())
            && nonBlank(projection.temporalRunId());
    require(provisioning || ready, "target Hearing case projection activation drifted");
  }

  private Participants lockParticipants(String caseId, CaseRow caseRow) {
    require("HEARING".equals(caseRow.currentRoom()), "target Hearing case is not in HEARING");
    Participants parties = exactCaseParties(caseRow);
    participant(caseId, parties.initiatorId(), caseRow.initiatorRole());
    participant(caseId, parties.respondentId(), caseRow.respondentRole());
    return parties;
  }

  private String participant(String caseId, String actorId, String role) {
    List<String> rows =
        jdbc.query(
            """
            select actor_id
              from case_participant
             where case_id = ? and actor_id = ? and participant_role = ?
               and participant_status = 'ACTIVE'
             for update
            """,
            (row, ignored) -> row.getString("actor_id"),
            caseId,
            actorId,
            role);
    String resolvedActorId =
        exactlyOne(rows, "target Hearing " + role + " participant is absent or ambiguous");
    require(nonBlank(resolvedActorId), "target Hearing " + role + " actor is invalid");
    return resolvedActorId;
  }

  static boolean allowedEpochState(
      String lifecycleStatus, String provisioningStatus, String roomRunId) {
    boolean runAbsent = !nonBlank(roomRunId);
    return ("PROVISIONING".equals(lifecycleStatus)
            && "PROVISIONING".equals(provisioningStatus)
            && runAbsent)
        || ("ACTIVE".equals(lifecycleStatus)
            && "READY".equals(provisioningStatus)
            && !runAbsent);
  }

  static Participants exactCaseParties(CaseRow caseRow) {
    boolean userInitiated =
        "USER".equals(caseRow.initiatorRole())
            && Objects.equals(caseRow.userId(), caseRow.initiatorId())
            && "MERCHANT".equals(caseRow.respondentRole())
            && Objects.equals(caseRow.merchantId(), caseRow.respondentId());
    boolean merchantInitiated =
        "MERCHANT".equals(caseRow.initiatorRole())
            && Objects.equals(caseRow.merchantId(), caseRow.initiatorId())
            && "USER".equals(caseRow.respondentRole())
            && Objects.equals(caseRow.userId(), caseRow.respondentId());
    require(
        nonBlank(caseRow.userId())
            && nonBlank(caseRow.merchantId())
            && !caseRow.userId().equals(caseRow.merchantId())
            && nonBlank(caseRow.initiatorId())
            && nonBlank(caseRow.respondentId())
            && !caseRow.initiatorId().equals(caseRow.respondentId())
            && (userInitiated || merchantInitiated),
        "target Hearing case participants drifted");
    return new Participants(caseRow.initiatorId(), caseRow.respondentId());
  }

  private ProjectionRow lockProjection(String caseId, String epochId) {
    List<ProjectionRow> rows =
        jdbc.query(
            """
            select flow_instance_id, hearing_epoch, fencing_token, process_revision, room_revision,
                   writer_mode, current_stage, stage_sequence, temporal_namespace,
                   temporal_workflow_id, temporal_run_id, temporal_build_or_deployment
              from hearing_temporal_projection
             where case_id = ? and epoch_id = ?
             for update
            """,
            (row, ignored) ->
                new ProjectionRow(
                    row.getString("flow_instance_id"),
                    row.getLong("hearing_epoch"),
                    row.getLong("fencing_token"),
                    row.getLong("process_revision"),
                    row.getLong("room_revision"),
                    row.getString("writer_mode"),
                    row.getString("current_stage"),
                    row.getInt("stage_sequence"),
                    row.getString("temporal_namespace"),
                    row.getString("temporal_workflow_id"),
                    row.getString("temporal_run_id"),
                    row.getString("temporal_build_or_deployment")),
            caseId,
            epochId);
    if (rows.isEmpty()) {
      return null;
    }
    return exactlyOne(rows, "target Hearing projection is ambiguous");
  }

  private void createInitialFlowAndProjection(ProvisionRoomEpoch provision, EpochRow epoch) {
    String hearingStateId =
        exactlyOne(
            jdbc.query(
                "select id from hearing_state where case_id = ? for update",
                (row, ignored) -> row.getString("id"),
                provision.caseId()),
            "target Hearing state is absent or ambiguous");
    List<String> existingFlows =
        jdbc.query(
            "select id from hearing_flow_instance where case_id = ? for update",
            (row, ignored) -> row.getString("id"),
            provision.caseId());
    require(existingFlows.isEmpty(), "target Hearing flow exists without an exact projection");

    jdbc.queryForObject(
        "select set_config('app.hearing_authority_commit', 'on', true)", String.class);
    jdbc.queryForObject(
        "select set_config('app.hearing_temporal_namespace', ?, true)",
        String.class,
        temporalNamespace);
    jdbc.queryForObject(
        "select set_config('app.hearing_epoch_id', ?, true)",
        String.class,
        provision.epochId());
    String stageId = stableId("hearing-stage-", provision.epochId() + ':' + provision.roomId());
    int inserted =
        jdbc.update(
            """
            insert into hearing_flow_instance (
                id, case_id, hearing_state_id, schema_version, current_stage, stage_sequence,
                flow_status, shared_deadline_at, created_at, updated_at, created_by, updated_by
            ) values (?, ?, ?, ?, 'COURT_PREPARING', 1, 'ACTIVE', null, now(), now(), ?, ?)
            """,
            provision.roomId(),
            provision.caseId(),
            hearingStateId,
            FLOW_SCHEMA,
            CONTROL_ACTOR,
            CONTROL_ACTOR);
    require(inserted == 1, "target Hearing flow creation failed");
    inserted =
        jdbc.update(
            """
            insert into hearing_flow_stage (
                id, flow_instance_id, case_id, stage_code, stage_sequence, processor_role,
                stage_status, shared_deadline_at, input_json, output_json, agent_run_id,
                started_at, completed_at, created_at, updated_at, created_by, updated_by
            ) values (?, ?, ?, 'COURT_PREPARING', 1, 'SYSTEM', 'PENDING', null,
                '{}'::jsonb, '{}'::jsonb, null, now(), null, now(), now(), ?, ?)
            """,
            stageId,
            provision.roomId(),
            provision.caseId(),
            CONTROL_ACTOR,
            CONTROL_ACTOR);
    require(inserted == 1, "target Hearing initial stage creation failed");
  }

  private void requireExactReplay(
      ProvisionRoomEpoch provision, EpochRow epoch, ProjectionRow projection) {
    require(
        provision.roomId().equals(projection.flowInstanceId())
            && epoch.roomEpoch() == projection.roomEpoch()
            && epoch.fencingToken() == projection.fencingToken()
            && epoch.processRevision() == projection.processRevision()
            && epoch.roomRevision() == projection.roomRevision()
            && "TEMPORAL".equals(projection.writerMode())
            && "COURT_PREPARING".equals(projection.stageCode())
            && projection.stageSequence() == 1
            && temporalNamespace.equals(projection.temporalNamespace())
            && epoch.roomWorkflowId().equals(projection.temporalWorkflowId())
            && expectedProjectionRunId(epoch).equals(projection.temporalRunId())
            && epoch.roomWorkflowBuildId().equals(projection.temporalBuild()),
        "target Hearing projection replay drifted");
  }

  private void requireInitialFlow(String caseId, String flowInstanceId) {
    List<String> rows =
        jdbc.query(
            """
            select stage.id
              from hearing_flow_instance flow
              join hearing_flow_stage stage
                on stage.flow_instance_id = flow.id and stage.case_id = flow.case_id
             where flow.id = ? and flow.case_id = ? and flow.schema_version = ?
               and flow.current_stage = 'COURT_PREPARING' and flow.stage_sequence = 1
               and flow.flow_status = 'ACTIVE' and flow.shared_deadline_at is null
               and stage.stage_code = 'COURT_PREPARING' and stage.stage_sequence = 1
               and stage.processor_role = 'SYSTEM' and stage.stage_status = 'PENDING'
               and stage.shared_deadline_at is null and stage.completed_at is null
             for update of flow, stage
            """,
            (row, ignored) -> row.getString("id"),
            flowInstanceId,
            caseId,
            FLOW_SCHEMA);
    exactlyOne(rows, "target Hearing flow is not the exact COURT_PREPARING opening");
  }

  private static <T> T exactlyOne(List<T> rows, String message) {
    if (rows.size() != 1) {
      throw new IllegalStateException(message);
    }
    return rows.getFirst();
  }

  private static String stableId(String prefix, String seed) {
    return prefix
        + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
  }

  private static boolean nonBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String expectedProjectionRunId(EpochRow epoch) {
    return nonBlank(epoch.roomRunId())
        ? epoch.roomRunId()
        : TargetHearingProvisioningRunIds.provisional(epoch.epochId());
  }

  static ActivationPhase activationPhase(ActivationRequest request, ActivationRow state) {
    String provisional = TargetHearingProvisioningRunIds.provisional(request.epochId());
    boolean pending =
        "PROVISIONING".equals(state.lifecycleStatus())
            && "PROVISIONING".equals(state.provisioningStatus())
            && !nonBlank(state.caseWorkflowRunId())
            && !nonBlank(state.roomWorkflowRunId())
            && "PROVISIONING".equals(state.projectionActivationStatus())
            && !nonBlank(state.projectionCaseRunId())
            && provisional.equals(state.hearingRoomRunId());
    if (pending) {
      return ActivationPhase.PENDING;
    }
    boolean ready =
        "ACTIVE".equals(state.lifecycleStatus())
            && "READY".equals(state.provisioningStatus())
            && nonBlank(state.caseWorkflowRunId())
            && request.roomWorkflowRunId().equals(state.roomWorkflowRunId())
            && "READY".equals(state.projectionActivationStatus())
            && state.caseWorkflowRunId().equals(state.projectionCaseRunId())
            && request.roomWorkflowRunId().equals(state.hearingRoomRunId());
    return ready ? ActivationPhase.READY : ActivationPhase.INVALID;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  record CaseRow(
      String caseId,
      String userId,
      String merchantId,
      String initiatorId,
      String initiatorRole,
      String respondentId,
      String respondentRole,
      String currentRoom) {}

  private record EpochRow(
      String epochId,
      long roomEpoch,
      long fencingToken,
      long processRevision,
      long roomRevision,
      String roomWorkflowId,
      String roomRunId,
      String roomWorkflowBuildId,
      String lifecycleStatus,
      String provisioningStatus,
      String writerMode) {}

  record Participants(String initiatorId, String respondentId) {}

  private record CaseProjectionRow(String activationStatus, String temporalRunId) {}

  enum ActivationPhase {
    PENDING,
    READY,
    INVALID
  }

  record ActivationRow(
      String lifecycleStatus,
      String provisioningStatus,
      String caseWorkflowRunId,
      String roomWorkflowRunId,
      String projectionActivationStatus,
      String projectionCaseRunId,
      String hearingRoomRunId) {}

  private record ProjectionRow(
      String flowInstanceId,
      long roomEpoch,
      long fencingToken,
      long processRevision,
      long roomRevision,
      String writerMode,
      String stageCode,
      int stageSequence,
      String temporalNamespace,
      String temporalWorkflowId,
      String temporalRunId,
      String temporalBuild) {}
}
