package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.evidence.application.EvidenceDossierFreezer;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.RoomEpochAllocation;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TransitionRoomEpoch;
import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.ActivationIdentity;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.activity.Activity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Arrays;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only target-lane writer which can turn two Evidence completion facts into a Hearing-open
 * domain transition. It deliberately has no browser, legacy-agent, or Hearing-runtime dependency.
 */
public final class JdbcTargetEvidenceTerminalActivities implements TargetEvidenceTerminalActivities {
  private static final String WRITER = "target-e2e-evidence-terminal";
  private static final Duration HEARING_WINDOW = Duration.ofHours(3);

  private final DataSource dataSource;
  private final TransactionTemplate transaction;
  private final TargetE2eActivationLifecycleStore activationLifecycleStore;
  private final EvidenceDossierFreezer dossierFreezer;
  private final RoomEpochAllocator roomEpochAllocator;
  private final ObjectMapper mapper;
  private final Clock clock;

  public JdbcTargetEvidenceTerminalActivities(
      DataSource dataSource,
      TransactionTemplate transaction,
      TargetE2eActivationLifecycleStore activationLifecycleStore,
      EvidenceDossierFreezer dossierFreezer,
      RoomEpochAllocator roomEpochAllocator,
      ObjectMapper mapper,
      Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.transaction = Objects.requireNonNull(transaction, "transaction");
    this.activationLifecycleStore =
        Objects.requireNonNull(activationLifecycleStore, "activationLifecycleStore");
    this.dossierFreezer = Objects.requireNonNull(dossierFreezer, "dossierFreezer");
    this.roomEpochAllocator = Objects.requireNonNull(roomEpochAllocator, "roomEpochAllocator");
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public TerminalResult finalizeTerminal(TerminalRequest request) {
    var activityInfo = Activity.getExecutionContext().getInfo();
    return finalizeTerminal(request, activityInfo.getWorkflowId(), activityInfo.getRunId());
  }

  TerminalResult finalizeTerminal(
      TerminalRequest request, String actualWorkflowId, String actualWorkflowRunId) {
    WorkflowIdentity workflowIdentity =
        requireWorkflowIdentity(request, actualWorkflowId, actualWorkflowRunId);
    BoundActivation boundActivation =
        loadBoundActivationAuthority(request, workflowIdentity);
    Objects.requireNonNull(
        activationLifecycleStore.refresh(
            boundActivation.identity(), boundActivation.expiresAt(), clock.instant()),
        "target Evidence bound activation refresh result");
    return transaction.execute(status -> finalizeInTransaction(request));
  }

  private TerminalResult finalizeInTransaction(TerminalRequest request) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      requireTransaction(connection);
      var activityInfo = Activity.getExecutionContext().getInfo();
      WorkflowIdentity workflowIdentity =
          requireWorkflowIdentity(
              request, activityInfo.getWorkflowId(), activityInfo.getRunId());
      // Match the party-completion transaction's command -> participant -> epoch lock order.
      long terminalCommandSequence = requirePersistedCompletionFacts(connection, request);
      Authority authority = lockAuthority(connection, request, workflowIdentity);
      Stored stored = readStored(connection, request, true);
      requireAuthorityCoordinates(authority, request, stored != null);
      if (stored != null) {
        requireStoredReplay(request, stored);
        return result(request, stored.receiptId(), stored.receiptHash());
      }

      requireAgentRunReceipts(connection, request);

      int targetVersion = dossierFreezer.targetVersion(request.start().caseId());
      EvidenceDossierEntity frozen = dossierFreezer.freeze(request.start().caseId(), targetVersion, WRITER);
      if (frozen.getDossierVersion() != targetVersion) {
        throw new IllegalStateException("target Evidence terminal froze an unexpected dossier version");
      }

      Instant committedAt = canonicalTerminalInstant(clock);
      String requestHash = hash(request);
      String receiptId = "EVDTERM_" + ContractJson.sha256Hex(mapper.valueToTree(
          List.of(request.start().caseId(), request.start().roomEpoch(), requestHash))).substring(0, 32);
      String hearingRoomId = "ROOM_HEARING_" + ContractJson.sha256Hex(mapper.valueToTree(
          List.of(request.start().caseId(), request.start().roomEpoch(), requestHash))).substring(0, 28);
      Instant hearingDeadline = committedAt.plus(HEARING_WINDOW);
      long terminalProcessRevision = Math.incrementExact(request.expectedProcessRevision());
      long terminalRoomRevision = Math.incrementExact(request.expectedRoomRevision());
      String receiptHash = receiptHash(request, frozen, hearingRoomId, hearingDeadline,
          terminalProcessRevision, terminalRoomRevision);
      sealEvidenceAndOpenHearing(connection, request, hearingRoomId, hearingDeadline);
      appendEventAndOutbox(connection, request, frozen, hearingRoomId, hearingDeadline, receiptHash);
      long terminalEventSequence =
          lockTerminalEventSequence(connection, request, frozen, receiptHash);
      RoomEpochAllocation hearingEpoch = roomEpochAllocator.transition(new TransitionRoomEpoch(
          request.start().caseId(), RoomType.EVIDENCE, hearingRoomId, RoomType.HEARING,
          "HEARING_OPEN", "PROVISIONING", OffsetDateTime.ofInstant(hearingDeadline, ZoneOffset.UTC),
          OffsetDateTime.ofInstant(committedAt, ZoneOffset.UTC), null, null,
          terminalCommandSequence, terminalEventSequence));
      validateHearingAllocation(
          connection,
          request,
          hearingRoomId,
          hearingDeadline,
          hearingEpoch,
          terminalCommandSequence,
          terminalEventSequence);
      insertReceipt(connection, receiptId, receiptHash, requestHash, request, frozen, hearingRoomId,
          hearingDeadline, terminalProcessRevision, terminalRoomRevision, committedAt);
      return result(request, receiptId, receiptHash);
    } catch (SQLException failure) {
      throw new IllegalStateException("target Evidence terminal transition failed", failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  private TerminalResult result(TerminalRequest request, String receiptId, String receiptHash) {
    return new TerminalResult(new TargetRoomProgressReceipt(RoomType.EVIDENCE, request.start().roomEpoch(),
        request.start().fencingToken(), Math.incrementExact(request.expectedProcessRevision()),
        Math.incrementExact(request.expectedRoomRevision()), receiptId, receiptHash));
  }

  static Instant canonicalTerminalInstant(Clock clock) {
    return Objects.requireNonNull(clock, "clock").instant().truncatedTo(ChronoUnit.MICROS);
  }

  static WorkflowIdentity requireWorkflowIdentity(
      TerminalRequest request, String actualWorkflowId, String actualWorkflowRunId) {
    Objects.requireNonNull(request, "request");
    String canonicalWorkflowId =
        CaseProcessWorkflowProtocol.roomWorkflowId(
            request.start().caseId(), RoomType.EVIDENCE, request.start().roomEpoch());
    if (actualWorkflowId == null
        || actualWorkflowRunId == null
        || !canonicalWorkflowId.equals(actualWorkflowId)
        || actualWorkflowRunId.isBlank()) {
      throw new IllegalStateException(
          "target Evidence terminal caller is not the canonical room workflow");
    }
    if (request.carriesWorkflowIdentity()) {
      if (!request.start().targetE2eCandidate()
          || !actualWorkflowId.equals(request.workflowId())
          || !actualWorkflowRunId.equals(request.workflowRunId())) {
        throw new IllegalStateException(
            "target Evidence terminal workflow identity drifted");
      }
    } else if (!request.start().legacyTargetBuildMarker()) {
      throw new IllegalStateException(
          "legacy target Evidence terminal request has no target build marker");
    }
    String roomAuthorityRunId =
        request.carriesDurableWorkflowAuthority()
            ? request.durableWorkflowRunId()
            : actualWorkflowRunId;
    return new WorkflowIdentity(
        actualWorkflowId, actualWorkflowRunId, roomAuthorityRunId);
  }

  BoundActivation loadBoundActivationAuthority(
      TerminalRequest request, WorkflowIdentity workflowIdentity) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                select activation.environment_id,
                       activation.environment_generation,
                       binding.activation_id,
                       binding.activation_manifest_hash,
                       activation.expires_at
                  from case_room_epoch epoch
                  join target_e2e_room_epoch_binding binding
                    on binding.epoch_id = epoch.id
                   and binding.tenant_surrogate = epoch.tenant_surrogate
                   and binding.case_id = epoch.case_id
                   and binding.room_type = epoch.room_type
                   and binding.room_epoch = epoch.room_epoch
                   and binding.room_fencing_token = epoch.fencing_token
                  join target_e2e_activation activation
                    on activation.activation_id = binding.activation_id
                   and activation.manifest_hash = binding.activation_manifest_hash
                   and activation.execution_lane = binding.execution_lane
                   and activation.isolated_domain_db_binding_hash =
                       binding.isolated_domain_db_binding_hash
                   and activation.tenant_surrogate = epoch.tenant_surrogate
                   and activation.control_build_id = epoch.room_workflow_build_id
                 where epoch.tenant_surrogate = ?
                   and epoch.case_id = ?
                   and epoch.room_id = ?
                   and epoch.room_type = 'EVIDENCE'
                   and epoch.room_epoch = ?
                   and epoch.fencing_token = ?
                   and epoch.writer_mode = 'TEMPORAL'
                   and epoch.provisioning_status = 'READY'
                   and epoch.lifecycle_status in ('ACTIVE', 'TERMINAL')
                   and epoch.room_temporal_workflow_id = ?
                   and epoch.room_temporal_run_id = ?
                   and epoch.room_workflow_build_id = ?
                   and binding.execution_lane = 'TARGET_E2E_CANDIDATE'
                """)) {
      int index = 1;
      statement.setString(index++, request.start().tenantSurrogate());
      statement.setString(index++, request.start().caseId());
      statement.setString(index++, request.start().roomId());
      statement.setLong(index++, request.start().roomEpoch());
      statement.setLong(index++, request.start().fencingToken());
      statement.setString(index++, workflowIdentity.workflowId());
      statement.setString(index++, workflowIdentity.roomAuthorityRunId());
      statement.setString(index, request.start().workflowBuildId());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          throw new IllegalStateException(
              "target Evidence terminal has no exact bound activation authority");
        }
        OffsetDateTime expiresAt = row.getObject(5, OffsetDateTime.class);
        BoundActivation authority =
            new BoundActivation(
                new ActivationIdentity(
                    row.getString(1), row.getLong(2), row.getString(3), row.getString(4)),
                expiresAt == null ? null : expiresAt.toInstant());
        if (row.next()) {
          throw new IllegalStateException(
              "target Evidence terminal bound activation authority is ambiguous");
        }
        return authority;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "target Evidence terminal activation authority lookup failed", failure);
    }
  }

  static Authority lockAuthority(
      Connection connection, TerminalRequest request, WorkflowIdentity workflowIdentity)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select epoch.id, epoch.lifecycle_status, epoch.process_revision, epoch.room_revision,
               binding.activation_id, activation.lifecycle_status,
               (activation.lifecycle_status = 'DRAIN_ONLY'
                 or (activation.lifecycle_status = 'ACTIVE'
                     and activation.expires_at > clock_timestamp())) as accepts_new_write
          from case_room_epoch epoch
          join target_e2e_room_epoch_binding binding
            on binding.epoch_id = epoch.id
           and binding.tenant_surrogate = epoch.tenant_surrogate
           and binding.case_id = epoch.case_id
           and binding.room_type = epoch.room_type
           and binding.room_epoch = epoch.room_epoch
           and binding.room_fencing_token = epoch.fencing_token
          join target_e2e_activation activation
            on activation.activation_id = binding.activation_id
           and activation.manifest_hash = binding.activation_manifest_hash
           and activation.execution_lane = binding.execution_lane
           and activation.isolated_domain_db_binding_hash =
               binding.isolated_domain_db_binding_hash
           and activation.tenant_surrogate = binding.tenant_surrogate
         where epoch.tenant_surrogate = ?
           and epoch.case_id = ?
           and epoch.room_id = ?
           and epoch.room_type = 'EVIDENCE'
           and epoch.room_epoch = ?
           and epoch.fencing_token = ?
           and epoch.writer_mode = 'TEMPORAL'
           and epoch.provisioning_status = 'READY'
           and epoch.lifecycle_status in ('ACTIVE', 'TERMINAL')
           and epoch.room_temporal_workflow_id = ?
           and epoch.room_temporal_run_id = ?
           and epoch.room_workflow_build_id = ?
           and binding.execution_lane = 'TARGET_E2E_CANDIDATE'
           and activation.execution_lane = 'TARGET_E2E_CANDIDATE'
           and activation.lifecycle_status in (
               'ACTIVE', 'DRAIN_ONLY', 'DRAINED', 'REVOKED_TERMINAL')
         for update of epoch
        """)) {
      int index = 1;
      statement.setString(index++, request.start().tenantSurrogate());
      statement.setString(index++, request.start().caseId());
      statement.setString(index++, request.start().roomId());
      statement.setLong(index++, request.start().roomEpoch());
      statement.setLong(index++, request.start().fencingToken());
      statement.setString(index++, workflowIdentity.workflowId());
      statement.setString(index++, workflowIdentity.roomAuthorityRunId());
      statement.setString(index, request.start().workflowBuildId());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          throw new IllegalStateException(
              "target Evidence terminal has no exact active room authority");
        }
        Authority authority =
            new Authority(
                row.getString(1),
                row.getString(2),
                row.getLong(3),
                row.getLong(4),
                row.getString(5),
                row.getString(6),
                row.getBoolean(7));
        if (row.next()
            || authority.activationId() == null
            || authority.activationId().isBlank()) {
          throw new IllegalStateException(
              "target Evidence terminal room authority is ambiguous");
        }
        return authority;
      }
    }
  }

  static void requireAuthorityCoordinates(
      Authority authority, TerminalRequest request, boolean storedReplay) {
    long expectedProcessRevision =
        storedReplay
            ? Math.incrementExact(request.expectedProcessRevision())
            : request.expectedProcessRevision();
    long expectedRoomRevision =
        storedReplay
            ? Math.incrementExact(request.expectedRoomRevision())
            : request.expectedRoomRevision();
    String expectedLifecycle = storedReplay ? "TERMINAL" : "ACTIVE";
    if (!expectedLifecycle.equals(authority.lifecycleStatus())
        || authority.activationId() == null
        || authority.activationId().isBlank()
        || !validActivationLifecycle(authority.activationLifecycleStatus(), storedReplay)
        || (!storedReplay && !authority.activationAcceptsNewWrite())
        || authority.processRevision() != expectedProcessRevision
        || authority.roomRevision() != expectedRoomRevision) {
      throw new IllegalStateException(
          "target Evidence terminal epoch coordinates drifted");
    }
  }

  static boolean validActivationLifecycle(String lifecycle, boolean storedReplay) {
    if ("ACTIVE".equals(lifecycle) || "DRAIN_ONLY".equals(lifecycle)) {
      return true;
    }
    return storedReplay
        && ("DRAINED".equals(lifecycle) || "REVOKED_TERMINAL".equals(lifecycle));
  }

  long requirePersistedCompletionFacts(Connection connection, TerminalRequest request)
      throws SQLException {
    long terminalCommandSequence = lockCompletionCommands(connection, request);
    requirePersistedCompletionFactsAfterCommandLock(connection, request);
    return terminalCommandSequence;
  }

  static long lockCompletionCommands(Connection connection, TerminalRequest request)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select command_id, case_command_sequence
          from case_command
         where tenant_surrogate = ?
           and case_id = ?
           and room_type = 'EVIDENCE'
           and room_epoch = ?
           and command_type = 'PARTY_EVIDENCE_COMPLETE'
           and command_status = 'APPLIED'
           and command_id in (?, ?)
         order by command_id
         for update
        """)) {
      statement.setString(1, request.start().tenantSurrogate());
      statement.setString(2, request.start().caseId());
      statement.setLong(3, request.start().roomEpoch());
      statement.setString(4, request.initiatorCompletionId());
      statement.setString(5, request.respondentCompletionId());
      java.util.Set<String> locked = new java.util.HashSet<>(2);
      long terminalCommandSequence = 0;
      try (ResultSet row = statement.executeQuery()) {
        while (row.next()) {
          locked.add(row.getString(1));
          terminalCommandSequence = Math.max(terminalCommandSequence, row.getLong(2));
        }
      }
      if (!locked.equals(
          java.util.Set.of(
              request.initiatorCompletionId(), request.respondentCompletionId()))) {
        throw new IllegalStateException(
            "target Evidence terminal completion commands are absent or ambiguous");
      }
      return terminalCommandSequence;
    }
  }

  void requirePersistedCompletionFactsAfterCommandLock(
      Connection connection, TerminalRequest request) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select completion.id, command_row.command_id, completion.dossier_version,
               completion.participant_role, completion.participant_id,
               completion.completion_status, completion.created_by,
               command_row.request_hash, command_row.expected_process_revision,
               command_row.result_sha256
          from evidence_party_completion completion
          join case_participant participant
            on participant.case_id = completion.case_id
           and participant.actor_id = completion.participant_id
           and participant.participant_role = completion.participant_role
           and participant.participant_status = 'ACTIVE'
          join case_command command_row
            on command_row.tenant_surrogate = ?
           and command_row.case_id = completion.case_id
           and command_row.command_id = 'evidence-complete:' || completion.id
           and command_row.command_type = 'PARTY_EVIDENCE_COMPLETE'
           and command_row.room_type = 'EVIDENCE'
           and command_row.room_epoch = ?
           and command_row.actor_id = completion.participant_id
           and command_row.actor_role = completion.participant_role
           and command_row.command_status = 'APPLIED'
           and command_row.result_uri = ? || completion.id
         where completion.case_id = ?
           and command_row.command_id in (?, ?)
         for update of completion, command_row, participant
        """)) {
      statement.setString(1, request.start().tenantSurrogate());
      statement.setLong(2, request.start().roomEpoch());
      statement.setString(3, JdbcTargetEvidencePartyCompletionActivities.RESULT_URI_PREFIX);
      statement.setString(4, request.start().caseId());
      statement.setString(5, request.initiatorCompletionId());
      statement.setString(6, request.respondentCompletionId());
      List<CompletionFact> facts = new java.util.ArrayList<>(2);
      try (ResultSet row = statement.executeQuery()) {
        while (row.next()) {
          facts.add(
              new CompletionFact(
                   row.getString(1),
                   row.getString(2),
                   row.getInt(3),
                   row.getString(4),
                   row.getString(5),
                   row.getString(6),
                   row.getString(7),
                   row.getString(8),
                   row.getLong(9),
                   row.getString(10)));
        }
      }
      if (facts.size() != 2) {
        throw new IllegalStateException(
            "target Evidence terminal requires two durable party completions");
      }
      CompletionFact initiator =
          requireCompletionFact(
              facts,
              request,
              request.initiatorCompletionId(),
              request.start().initiatorParticipantId());
      CompletionFact respondent =
          requireCompletionFact(
              facts,
              request,
              request.respondentCompletionId(),
              request.start().respondentParticipantId());
      if (initiator.participantRole().equals(respondent.participantRole())
          || !(List.of("USER", "MERCHANT").contains(initiator.participantRole())
              && List.of("USER", "MERCHANT").contains(respondent.participantRole()))
          || initiator.dossierVersion() != respondent.dossierVersion()
          || initiator.dossierVersion()
              != dossierFreezer.targetVersion(request.start().caseId())) {
        throw new IllegalStateException(
            "target Evidence terminal requires opposing durable party roles");
      }
    }
  }

  private CompletionFact requireCompletionFact(
      List<CompletionFact> facts,
      TerminalRequest request,
      String commandId,
      String participantId) {
    CompletionFact match = null;
    for (CompletionFact fact : facts) {
      if (commandId.equals(fact.commandId())) {
        if (match != null) {
          throw new IllegalStateException(
              "target Evidence durable party completion is ambiguous");
        }
        match = fact;
      }
    }
    long coordinateDelta =
        Math.subtractExact(
            match == null ? request.start().initialProcessRevision() : match.expectedProcessRevision(),
            request.start().initialProcessRevision());
    long expectedRoomRevision =
        Math.addExact(request.start().initialRoomRevision(), coordinateDelta);
    String expectedResultHash =
        match == null
            ? null
            : ContractJson.sha256Hex(
                mapper.valueToTree(
                    List.of(
                        "target-e2e-evidence-party-receipt.v1",
                        match.id(),
                        match.requestHash(),
                        match.expectedProcessRevision(),
                        expectedRoomRevision)));
    if (match == null
        || !("evidence-complete:" + match.id()).equals(match.commandId())
        || !participantId.equals(match.participantId())
        || !"COMPLETED".equals(match.status())
        || !match.participantId().equals(match.createdBy())
        || match.expectedProcessRevision() < request.start().initialProcessRevision()
        || match.expectedProcessRevision() >= request.expectedProcessRevision()
        || expectedRoomRevision < request.start().initialRoomRevision()
        || expectedRoomRevision >= request.expectedRoomRevision()
        || !expectedResultHash.equals(match.resultHash())) {
      throw new IllegalStateException(
          "target Evidence durable party completion drifted");
    }
    return match;
  }

  void requireAgentRunReceipts(Connection connection, TerminalRequest request) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        with scoped_material as (
          select material.*,
                 material.material_canonical_json::jsonb #>> '{request,agent_run_id}' as logical_run_id,
                 material.material_canonical_json::jsonb #>> '{request,command,attempt_id}' as attempt_id,
                 material.material_canonical_json::jsonb #>> '{request,command,request_hash}' as request_hash,
                 material.material_canonical_json::jsonb #>> '{request,command,process_revision}' as process_revision,
                 material.material_canonical_json::jsonb #>> '{request,command,stage_sequence}' as stage_sequence,
                 material.material_canonical_json::jsonb #>> '{request,command,graph_key}' as graph_key,
                 material.material_canonical_json::jsonb #>> '{request,command,graph_version}' as graph_version,
                 material.material_canonical_json::jsonb #>> '{request,command,checkpoint_schema_version}' as checkpoint_schema_version
            from target_e2e_evidence_command_material material
           where material.tenant_surrogate = ? and material.case_id = ? and material.room_epoch = ?
        ), logical_run_scope as (
          select logical_run_id, count(*) as material_count
            from scoped_material
           group by logical_run_id
        )
        select scope.logical_run_id,
               run.committed_attempt_id,
               case when run.id is not null
                          and run.protocol = 'agent-stream.v3'
                          and run.executor_kind = 'TEMPORAL_ACTIVITY'
                          and run.run_status = 'COMPLETED'
                          and run.finalization_status = 'COMMITTED'
                          and run.result_ready_attempt_id = run.committed_attempt_id
                          and run.final_result_hash is not null
                          and run.finalized_at is not null
                          and run.tenant_surrogate = ? and run.case_id = ?
                          and run.room_type = 'EVIDENCE' and run.room_epoch = ?
                          and not exists (
                            select 1 from scoped_material binding
                             where binding.logical_run_id is not distinct from scope.logical_run_id
                               and (binding.execution_lane <> 'TARGET_E2E_CANDIDATE'
                                    or binding.tenant_surrogate <> run.tenant_surrogate
                                    or binding.case_id <> run.case_id
                                    or binding.room_type <> run.room_type
                                    or binding.room_epoch <> run.room_epoch
                                    or binding.room_fencing_token <> run.fencing_token)
                          )
                    then true else false end as run_binding_exact,
               scope.material_count,
               (select count(*) from agent_run_attempt winner
                 where winner.agent_run_id = run.id
                   and winner.id = run.committed_attempt_id
                   and winner.attempt_status = 'COMPLETED'
                   and winner.executor_kind = 'TEMPORAL_ACTIVITY'
                   and winner.completed_at is not null
                   and winner.final_frame_observed
                   and winner.result_hash = run.final_result_hash) as winner_attempt_count,
               (select count(*) from agent_run_attempt candidate
                 where candidate.agent_run_id = run.id
                   and candidate.attempt_status in ('RESULT_READY', 'COMPLETED')) as winner_candidate_count,
               (select count(*) from scoped_material winner_material
                 where winner_material.logical_run_id is not distinct from scope.logical_run_id
                   and winner_material.attempt_id = run.committed_attempt_id) as winner_material_count,
               (select count(*) from target_e2e_finalization_receipt receipt
                 where receipt.logical_run_id is not distinct from scope.logical_run_id) as receipt_count,
               (select count(*)
                  from scoped_material material
                  join agent_run_attempt winner
                    on winner.agent_run_id = run.id and winner.id = run.committed_attempt_id
                  join target_e2e_finalization_receipt receipt
                    on receipt.logical_run_id = scope.logical_run_id
                   and receipt.attempt_id = run.committed_attempt_id
                   and receipt.execution_lane = material.execution_lane
                   and receipt.activation_id = material.activation_id
                   and receipt.activation_manifest_hash = material.activation_manifest_hash
                   and receipt.isolated_domain_db_binding_hash = material.isolated_domain_db_binding_hash
                   and receipt.tenant_surrogate = material.tenant_surrogate
                   and receipt.case_id = material.case_id
                   and receipt.room_type = material.room_type
                   and receipt.room_epoch = material.room_epoch
                   and receipt.room_fencing_token = material.room_fencing_token
                   and receipt.command_hash = material.command_hash
                   and receipt.command_envelope_hash = material.command_envelope_hash
                   and receipt.process_revision::text = material.process_revision
                   and receipt.stage_sequence::text = material.stage_sequence
                   and receipt.graph_key = material.graph_key
                   and receipt.graph_version = material.graph_version
                   and receipt.checkpoint_schema_version = material.checkpoint_schema_version
                   and receipt.result_hash = winner.result_hash
                   and receipt.formal_writer = 'JAVA_FINALIZER_ONLY'
                   and receipt.domain_commit_status = 'COMMITTED'
                 where material.logical_run_id is not distinct from scope.logical_run_id
                   and material.attempt_id = run.committed_attempt_id
                   and winner.request_hash = material.request_hash
                   and winner.graph_key = material.graph_key
                   and winner.graph_version = material.graph_version
                   and winner.checkpoint_schema_version = material.checkpoint_schema_version) as exact_receipt_count,
               (select count(*) from scoped_material candidate_material
                 where candidate_material.logical_run_id is not distinct from scope.logical_run_id
                   and not exists (
                     select 1
                       from agent_run_attempt attempt
                       join agent_run_attempt winner
                         on winner.agent_run_id = run.id and winner.id = run.committed_attempt_id
                      where attempt.agent_run_id = run.id
                        and attempt.id = candidate_material.attempt_id
                        and (
                          (attempt.id = winner.id
                           and attempt.attempt_status = 'COMPLETED'
                           and attempt.completed_at is not null)
                          or
                          (attempt.id <> winner.id
                           and attempt.attempt_no < winner.attempt_no
                           and attempt.attempt_status in ('ABORTED', 'FAILED', 'CANCELLED')
                           and attempt.executor_kind = 'TEMPORAL_ACTIVITY'
                           and attempt.termination_code = 'CREATE_NEXT_ATTEMPT'
                           and attempt.completed_at is not null
                           and nullif(trim(attempt.error_code), '') is not null
                           and attempt.error_retryable
                           and attempt.result_hash is null
                           and not attempt.final_frame_observed)
                        )
                   )) as invalid_lineage_count
          from logical_run_scope scope
          left join agent_run run on run.id = scope.logical_run_id
         order by scope.logical_run_id nulls first
        """)) {
      statement.setString(1, request.start().tenantSurrogate());
      statement.setString(2, request.start().caseId());
      statement.setLong(3, request.start().roomEpoch());
      statement.setString(4, request.start().tenantSurrogate());
      statement.setString(5, request.start().caseId());
      statement.setLong(6, request.start().roomEpoch());
      try (ResultSet row = statement.executeQuery()) {
        boolean found = false;
        while (row.next()) {
          found = true;
          requireAgentRunReceiptAuthority(
              new AgentRunReceiptAuthority(
                  row.getString(1),
                  row.getString(2),
                  row.getBoolean(3),
                  row.getLong(4),
                  row.getLong(5),
                  row.getLong(6),
                  row.getLong(7),
                  row.getLong(8),
                  row.getLong(9),
                  row.getLong(10)));
        }
        if (!found) rejectAgentRunReceiptAuthority();
      }
    }
  }

  static void requireAgentRunReceiptAuthority(AgentRunReceiptAuthority authority) {
    if (authority == null
        || authority.logicalRunId() == null
        || authority.logicalRunId().isBlank()
        || authority.committedAttemptId() == null
        || authority.committedAttemptId().isBlank()
        || !authority.runBindingExact()
        || authority.materialCount() < 1
        || authority.winnerAttemptCount() != 1
        || authority.winnerCandidateCount() != 1
        || authority.winnerMaterialCount() != 1
        || authority.receiptCount() != 1
        || authority.exactReceiptCount() != 1
        || authority.invalidLineageCount() != 0) {
      rejectAgentRunReceiptAuthority();
    }
  }

  private static void rejectAgentRunReceiptAuthority() {
    throw new IllegalStateException(
        "all EVIDENCE_SUBMIT AgentRun formal receipts are required");
  }

  record AgentRunReceiptAuthority(
      String logicalRunId,
      String committedAttemptId,
      boolean runBindingExact,
      long materialCount,
      long winnerAttemptCount,
      long winnerCandidateCount,
      long winnerMaterialCount,
      long receiptCount,
      long exactReceiptCount,
      long invalidLineageCount) {}

  private void sealEvidenceAndOpenHearing(Connection connection, TerminalRequest request,
      String hearingRoomId, Instant hearingDeadline) throws SQLException {
    execute(connection, "update case_room set room_status = 'SEALED', sealed_at = coalesce(sealed_at, now()), updated_at = now(), updated_by = ?, version = version + 1 where id = ? and case_id = ? and room_type = 'EVIDENCE' and room_status in ('OPEN', 'WAITING', 'SEALED')", WRITER, request.start().roomId(), request.start().caseId());
    execute(connection, "update case_phase_clock set clock_status = 'COMPLETED_EARLY', completed_at = coalesce(completed_at, now()), completion_reason = 'BOTH_PARTIES_COMPLETED', updated_at = now(), updated_by = ?, version = version + 1 where case_id = ? and room_id = ? and clock_type = 'EVIDENCE_SUBMISSION' and clock_status in ('RUNNING', 'COMPLETED_EARLY')", WRITER, request.start().caseId(), request.start().roomId());
    execute(connection, "insert into case_room (id, case_id, room_type, room_status, opened_at, metadata_json, created_by, updated_by) values (?, ?, 'HEARING', 'OPEN', now(), '{}'::jsonb, ?, ?) on conflict (case_id, room_type) do nothing", hearingRoomId, request.start().caseId(), WRITER, WRITER);
    execute(connection, "update case_room set room_status = 'OPEN', opened_at = coalesce(opened_at, now()), updated_at = now(), updated_by = ? where case_id = ? and room_type = 'HEARING' and room_status in ('LOCKED', 'OPEN')", WRITER, request.start().caseId());
    execute(connection, "insert into case_phase_clock (id, case_id, room_id, clock_type, clock_status, started_at, deadline_at, temporal_workflow_id, created_by, updated_by) values (?, ?, ?, 'HEARING', 'RUNNING', now(), ?, ?, ?, ?) on conflict (case_id, clock_type) do nothing", "CLOCK_HEARING_" + hearingRoomId.substring("ROOM_HEARING_".length()), request.start().caseId(), hearingRoomId, java.sql.Timestamp.from(hearingDeadline), "target-e2e-hearing-provision-pending", WRITER, WRITER);
    execute(connection, "update fulfillment_dispute_case set case_status = 'HEARING_OPEN', current_room = 'HEARING', current_deadline_at = ?, updated_by = ? where id = ? and case_status in ('EVIDENCE_OPEN', 'EVIDENCE_SEALED', 'HEARING_OPEN')", java.sql.Timestamp.from(hearingDeadline), WRITER, request.start().caseId());
  }

  private void validateHearingAllocation(Connection connection, TerminalRequest request, String hearingRoomId,
      Instant hearingDeadline, RoomEpochAllocation allocation, long terminalCommandSequence,
      long terminalEventSequence) throws SQLException {
    if (allocation == null || !request.start().tenantSurrogate().equals(allocation.tenantSurrogate())
        || !request.start().caseId().equals(allocation.caseId()) || !hearingRoomId.equals(allocation.roomId())
        || allocation.roomType() != RoomType.HEARING || allocation.writerMode() != WriterMode.TEMPORAL
        || allocation.roomRevision() != 0
        || allocation.processRevision() != Math.incrementExact(request.expectedProcessRevision())
        || allocation.fencingToken() != Math.incrementExact(request.start().fencingToken())
        || allocation.lifecycleStatus()
            != com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus.PREPARING
        || allocation.temporalWorkflowId() == null || allocation.temporalWorkflowId().isBlank()
        || allocation.temporalRunId() != null) {
      throw new IllegalStateException("target Hearing epoch allocation drifted");
    }
    var selection = allocation.selection();
    if (selection == null || selection.writerMode() != WriterMode.TEMPORAL
        || !TargetTypedRoomProtocol.SELECTION_SCHEMA_VERSION.equals(selection.selectionSchemaVersion())
        || !TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION.equals(selection.processContractVersion())
        || !TargetTypedRoomProtocol.CASE_WORKFLOW_TYPE.equals(selection.caseWorkflowType())
        || !TargetTypedRoomProtocol.HEARING_WORKFLOW_TYPE.equals(selection.roomWorkflowType())
        || !TargetTypedRoomProtocol.GRAPH_KEY.equals(selection.graphKey())
        || !TargetTypedRoomProtocol.GRAPH_VERSION.equals(selection.graphVersion())
        || !TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION.equals(selection.checkpointSchemaVersion())
        || !TargetTypedRoomProtocol.STREAM_PROTOCOL.equals(selection.streamProtocol())) {
      throw new IllegalStateException("target Hearing epoch selection pins drifted");
    }
    try (PreparedStatement statement = connection.prepareStatement("""
        select macro_phase, current_room, room_phase, writer_mode, process_revision, room_epoch,
               fencing_token, last_command_sequence, last_case_event_sequence, projected_deadline_at
          from case_process_projection where case_id = ?
        """)) {
      statement.setString(1, request.start().caseId());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          throw new IllegalStateException("target Hearing projection allocation drifted");
        }
        String macroPhase = row.getString(1);
        String currentRoom = row.getString(2);
        String roomPhase = row.getString(3);
        String writerMode = row.getString(4);
        long processRevision = row.getLong(5);
        long roomEpoch = row.getLong(6);
        long fencingToken = row.getLong(7);
        long lastCommandSequence = row.getLong(8);
        long lastCaseEventSequence = row.getLong(9);
        java.sql.Timestamp deadline = row.getTimestamp(10);
        if (row.next() || !"HEARING_OPEN".equals(macroPhase)
            || !"HEARING".equals(currentRoom) || !"PROVISIONING".equals(roomPhase)
            || !"TEMPORAL".equals(writerMode) || processRevision != allocation.processRevision()
            || roomEpoch != allocation.roomEpoch() || fencingToken != allocation.fencingToken()
            || lastCommandSequence != terminalCommandSequence
            || lastCaseEventSequence != terminalEventSequence
            || deadline == null || !hearingDeadline.equals(deadline.toInstant())) {
          throw new IllegalStateException("target Hearing projection allocation drifted");
        }
      }
    }
    requireTerminalEvidenceEpoch(connection, request);
    requireTargetBindingPreserved(connection, request, allocation);
  }

  private static void requireTerminalEvidenceEpoch(Connection connection, TerminalRequest request)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select lifecycle_status, writer_mode, process_revision, room_revision, fencing_token
          from case_room_epoch
         where case_id = ? and room_type = 'EVIDENCE' and room_epoch = ?
        """)) {
      statement.setString(1, request.start().caseId());
      statement.setLong(2, request.start().roomEpoch());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          throw new IllegalStateException("target Evidence terminal epoch drifted");
        }
        String lifecycle = row.getString(1);
        String writerMode = row.getString(2);
        long processRevision = row.getLong(3);
        long roomRevision = row.getLong(4);
        long fencingToken = row.getLong(5);
        if (row.next() || !"TERMINAL".equals(lifecycle) || !"TEMPORAL".equals(writerMode)
            || processRevision != Math.incrementExact(request.expectedProcessRevision())
            || roomRevision != Math.incrementExact(request.expectedRoomRevision())
            || fencingToken != request.start().fencingToken()) {
          throw new IllegalStateException("target Evidence terminal epoch drifted");
        }
      }
    }
  }

  private static void requireTargetBindingPreserved(Connection connection, TerminalRequest request,
      RoomEpochAllocation allocation) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select next.activation_id, next.activation_manifest_hash, next.execution_lane,
               next.isolated_domain_db_binding_hash
          from target_e2e_room_epoch_binding source
          join target_e2e_room_epoch_binding next
            on next.activation_id = source.activation_id
           and next.activation_manifest_hash = source.activation_manifest_hash
           and next.execution_lane = source.execution_lane
           and next.isolated_domain_db_binding_hash = source.isolated_domain_db_binding_hash
         where source.case_id = ? and source.room_type = 'EVIDENCE' and source.room_epoch = ?
           and source.room_fencing_token = ? and next.epoch_id = ?
           and next.case_id = ? and next.room_type = 'HEARING' and next.room_epoch = ?
           and next.room_fencing_token = ?
        """)) {
      statement.setString(1, request.start().caseId());
      statement.setLong(2, request.start().roomEpoch());
      statement.setLong(3, request.start().fencingToken());
      statement.setString(4, allocation.epochId());
      statement.setString(5, allocation.caseId());
      statement.setLong(6, allocation.roomEpoch());
      statement.setLong(7, allocation.fencingToken());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          throw new IllegalStateException("target Hearing epoch activation binding drifted");
        }
        String activationId = row.getString(1);
        String manifestHash = row.getString(2);
        String executionLane = row.getString(3);
        String databaseBindingHash = row.getString(4);
        if (row.next() || activationId == null || activationId.isBlank()
            || !"TARGET_E2E_CANDIDATE".equals(executionLane)
            || !isSha256(manifestHash) || !isSha256(databaseBindingHash)) {
          throw new IllegalStateException("target Hearing epoch activation binding drifted");
        }
      }
    }
  }

  private void appendEventAndOutbox(Connection connection, TerminalRequest request, EvidenceDossierEntity frozen,
      String hearingRoomId, Instant deadline, String receiptHash) throws SQLException {
    String key = "target-e2e-hearing-open:" + request.start().caseId() + ":" + request.start().roomEpoch();
    String eventId = "EVT_HEARING_" + receiptHash.substring(0, 32);
    String payload = ContractJson.canonicalString(mapper.valueToTree(Map.of("schema_version", "target-e2e-evidence-terminal.v1",
        "receipt_hash", receiptHash, "dossier_id", frozen.getId(), "dossier_version", frozen.getDossierVersion(),
        "hearing_room_id", hearingRoomId, "deadline_at", deadline.toString(), "provisioning", "REQUIRED")));
    execute(connection, "insert into case_timeline_event (id, case_id, dossier_id, event_type, event_time, source_refs_json, event_json, sequence_no, room_id, audience_json, event_key, created_by) values (?, ?, ?, 'HEARING_OPENED', now(), '[]'::jsonb, ?::jsonb, (select coalesce(max(sequence_no), 0) + 1 from case_timeline_event where case_id = ?), ?, '[]'::jsonb, ?, ?) on conflict (case_id, event_key) where event_key is not null do nothing", eventId, request.start().caseId(), frozen.getId(), payload, request.start().caseId(), hearingRoomId, key, WRITER);
  }

  static long lockTerminalEventSequence(Connection connection, TerminalRequest request,
      EvidenceDossierEntity frozen, String receiptHash) throws SQLException {
    String key =
        "target-e2e-hearing-open:"
            + request.start().caseId()
            + ":"
            + request.start().roomEpoch();
    String eventId = "EVT_HEARING_" + receiptHash.substring(0, 32);
    try (PreparedStatement statement = connection.prepareStatement("""
        select sequence_no
          from case_timeline_event
         where id = ? and case_id = ? and dossier_id = ?
           and event_type = 'HEARING_OPENED' and event_key = ?
           and event_json #>> '{receipt_hash}' = ?
         for update
        """)) {
      statement.setString(1, eventId);
      statement.setString(2, request.start().caseId());
      statement.setString(3, frozen.getId());
      statement.setString(4, key);
      statement.setString(5, receiptHash);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          throw new IllegalStateException(
              "target Evidence terminal timeline authority is missing");
        }
        long sequence = row.getLong(1);
        if (sequence < 1 || row.next()) {
          throw new IllegalStateException(
              "target Evidence terminal timeline authority is ambiguous");
        }
        return sequence;
      }
    }
  }

  private void insertReceipt(Connection connection, String receiptId, String receiptHash, String requestHash,
      TerminalRequest request, EvidenceDossierEntity frozen, String hearingRoomId, Instant deadline,
      long processRevision, long roomRevision, Instant committedAt) throws SQLException {
    byte[] bytes = receiptCanonical(
        receiptId, receiptHash, requestHash, request, frozen.getId(), frozen.getDossierVersion(),
        hearingRoomId, deadline, processRevision, roomRevision, committedAt);
    try (PreparedStatement statement = connection.prepareStatement("""
        insert into target_e2e_evidence_terminal_receipt (
          receipt_id, receipt_hash, request_hash, tenant_surrogate, case_id, room_epoch, fencing_token,
          initiator_completion_id, respondent_completion_id, dossier_id, dossier_version, hearing_room_id,
          hearing_deadline_at, process_revision, room_revision, receipt_canonical_bytes, committed_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)) {
      int i = 1; statement.setString(i++, receiptId); statement.setString(i++, receiptHash); statement.setString(i++, requestHash);
      statement.setString(i++, request.start().tenantSurrogate()); statement.setString(i++, request.start().caseId());
      statement.setLong(i++, request.start().roomEpoch()); statement.setLong(i++, request.start().fencingToken());
      statement.setString(i++, request.initiatorCompletionId()); statement.setString(i++, request.respondentCompletionId());
      statement.setString(i++, frozen.getId()); statement.setInt(i++, frozen.getDossierVersion()); statement.setString(i++, hearingRoomId);
      statement.setTimestamp(i++, java.sql.Timestamp.from(deadline)); statement.setLong(i++, processRevision); statement.setLong(i++, roomRevision);
      statement.setBytes(i++, bytes); statement.setTimestamp(i, java.sql.Timestamp.from(committedAt)); statement.executeUpdate();
    }
  }

  private Stored readStored(Connection connection, TerminalRequest request, boolean lock) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select receipt.receipt_id, receipt.receipt_hash, receipt.request_hash,
               receipt.tenant_surrogate, receipt.case_id, receipt.room_epoch,
               receipt.fencing_token, receipt.initiator_completion_id,
               receipt.respondent_completion_id, receipt.dossier_id,
               receipt.dossier_version, receipt.hearing_room_id,
               receipt.hearing_deadline_at, receipt.process_revision,
               receipt.room_revision, receipt.receipt_canonical_bytes,
               receipt.committed_at
          from target_e2e_evidence_terminal_receipt receipt
          join evidence_dossier dossier
            on dossier.id = receipt.dossier_id and dossier.case_id = receipt.case_id
           and dossier.dossier_version = receipt.dossier_version
           and dossier.dossier_status = 'FROZEN' and dossier.deleted_at is null
          join case_room hearing
            on hearing.id = receipt.hearing_room_id and hearing.case_id = receipt.case_id
           and hearing.room_type = 'HEARING' and hearing.room_status = 'OPEN'
         where receipt.case_id = ? and receipt.room_epoch = ?
        """ + (lock ? " for update of receipt" : ""))) {
      statement.setString(1, request.start().caseId()); statement.setLong(2, request.start().roomEpoch());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) return null;
        Stored stored = new Stored(
            row.getString(1), row.getString(2), row.getString(3), row.getString(4),
            row.getString(5), row.getLong(6), row.getLong(7), row.getString(8),
            row.getString(9), row.getString(10), row.getInt(11), row.getString(12),
            row.getTimestamp(13).toInstant(), row.getLong(14), row.getLong(15),
            row.getBytes(16), row.getTimestamp(17).toInstant());
        if (row.next()) throw new IllegalStateException("target Evidence terminal replay is ambiguous");
        return stored;
      }
    }
  }

  void requireStoredReplay(TerminalRequest request, Stored stored) {
    String requestHash = hash(request);
    String seed = ContractJson.sha256Hex(mapper.valueToTree(
        List.of(request.start().caseId(), request.start().roomEpoch(), requestHash)));
    String expectedReceiptId = "EVDTERM_" + seed.substring(0, 32);
    String expectedHearingRoomId = "ROOM_HEARING_" + seed.substring(0, 28);
    long expectedProcess = Math.incrementExact(request.expectedProcessRevision());
    long expectedRoom = Math.incrementExact(request.expectedRoomRevision());
    String expectedReceiptHash = receiptHash(
        request, stored.dossierId(), stored.dossierVersion(), stored.hearingRoomId(),
        stored.hearingDeadline(), stored.processRevision(), stored.roomRevision());
    byte[] expectedCanonical = receiptCanonical(
        stored.receiptId(), stored.receiptHash(), stored.requestHash(), request,
        stored.dossierId(), stored.dossierVersion(), stored.hearingRoomId(),
        stored.hearingDeadline(), stored.processRevision(), stored.roomRevision(),
        stored.committedAt());
    if (!expectedReceiptId.equals(stored.receiptId())
        || !expectedHearingRoomId.equals(stored.hearingRoomId())
        || !requestHash.equals(stored.requestHash())
        || !request.start().tenantSurrogate().equals(stored.tenantSurrogate())
        || !request.start().caseId().equals(stored.caseId())
        || request.start().roomEpoch() != stored.roomEpoch()
        || request.start().fencingToken() != stored.fencingToken()
        || !request.initiatorCompletionId().equals(stored.initiatorCompletionId())
        || !request.respondentCompletionId().equals(stored.respondentCompletionId())
        || stored.dossierId() == null || stored.dossierId().isBlank()
        || stored.dossierVersion() < 1
        || expectedProcess != stored.processRevision()
        || expectedRoom != stored.roomRevision()
        || !stored.committedAt().plus(HEARING_WINDOW).equals(stored.hearingDeadline())
        || !expectedReceiptHash.equals(stored.receiptHash())
        || !Arrays.equals(expectedCanonical, stored.canonicalBytes())) {
      throw new IllegalStateException("target Evidence terminal replay drifted");
    }
  }

  byte[] receiptCanonical(
      String receiptId, String receiptHash, String requestHash, TerminalRequest request,
      String dossierId, int dossierVersion, String hearingRoomId, Instant hearingDeadline,
      long processRevision, long roomRevision, Instant committedAt) {
    Map<String, Object> canonical = new LinkedHashMap<>();
    canonical.put("schema_version", "target-e2e-evidence-terminal-receipt.v1");
    canonical.put("receipt_id", receiptId);
    canonical.put("receipt_hash", receiptHash);
    canonical.put("request_hash", requestHash);
    canonical.put("tenant_surrogate", request.start().tenantSurrogate());
    canonical.put("case_id", request.start().caseId());
    canonical.put("room_epoch", request.start().roomEpoch());
    canonical.put("fencing_token", request.start().fencingToken());
    canonical.put("initiator_completion_id", request.initiatorCompletionId());
    canonical.put("respondent_completion_id", request.respondentCompletionId());
    canonical.put("dossier_id", dossierId);
    canonical.put("dossier_version", dossierVersion);
    canonical.put("hearing_room_id", hearingRoomId);
    canonical.put("hearing_deadline_at", hearingDeadline.toString());
    canonical.put("process_revision", processRevision);
    canonical.put("room_revision", roomRevision);
    canonical.put("committed_at", committedAt.toString());
    return ContractJson.canonicalize(mapper.valueToTree(canonical));
  }

  String hash(TerminalRequest request) {
    List<?> material =
        request.carriesDurableWorkflowAuthority()
            ? List.of(
                request.start(),
                request.expectedProcessRevision(),
                request.expectedRoomRevision(),
                request.initiatorCompletionId(),
                request.respondentCompletionId(),
                request.workflowId(),
                request.workflowRunId(),
                request.durableWorkflowRunId())
            : request.carriesWorkflowIdentity()
            ? List.of(
                request.start(),
                request.expectedProcessRevision(),
                request.expectedRoomRevision(),
                request.initiatorCompletionId(),
                request.respondentCompletionId(),
                request.workflowId(),
                request.workflowRunId())
            : List.of(
                request.start(),
                request.expectedProcessRevision(),
                request.expectedRoomRevision(),
                request.initiatorCompletionId(),
                request.respondentCompletionId());
    return ContractJson.sha256Hex(mapper.valueToTree(material));
  }
  private String receiptHash(TerminalRequest request, EvidenceDossierEntity frozen, String roomId, Instant deadline, long processRevision, long roomRevision) { return receiptHash(request, frozen.getId(), frozen.getDossierVersion(), roomId, deadline, processRevision, roomRevision); }
  String receiptHash(TerminalRequest request, String dossierId, int dossierVersion, String roomId, Instant deadline, long processRevision, long roomRevision) { return ContractJson.sha256Hex(mapper.valueToTree(List.of("target-e2e-evidence-terminal-receipt.v1", hash(request), dossierId, dossierVersion, roomId, deadline.toString(), processRevision, roomRevision))); }
  private static boolean isSha256(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
  private static void requireTransaction(Connection connection) throws SQLException { if (connection.getAutoCommit()) throw new IllegalStateException("target Evidence terminal requires a transaction"); }
  private static void execute(Connection connection, String sql, Object... values) throws SQLException { try (PreparedStatement s = connection.prepareStatement(sql)) { for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]); s.executeUpdate(); } }
  record WorkflowIdentity(
      String workflowId, String workflowRunId, String roomAuthorityRunId) {}
  record BoundActivation(ActivationIdentity identity, Instant expiresAt) {
    BoundActivation {
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(expiresAt, "expiresAt");
    }
  }
  record Authority(
      String epochId,
      String lifecycleStatus,
      long processRevision,
      long roomRevision,
      String activationId,
      String activationLifecycleStatus,
      boolean activationAcceptsNewWrite) {}
  private record CompletionFact(
      String id,
      String commandId,
      int dossierVersion,
      String participantRole,
      String participantId,
      String status,
      String createdBy,
      String requestHash,
      long expectedProcessRevision,
      String resultHash) {}
  record Stored(
      String receiptId,
      String receiptHash,
      String requestHash,
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      long fencingToken,
      String initiatorCompletionId,
      String respondentCompletionId,
      String dossierId,
      int dossierVersion,
      String hearingRoomId,
      Instant hearingDeadline,
      long processRevision,
      long roomRevision,
      byte[] canonicalBytes,
      Instant committedAt) {}
}
