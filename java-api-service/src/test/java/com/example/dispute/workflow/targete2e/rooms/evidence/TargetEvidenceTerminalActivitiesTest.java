package com.example.dispute.workflow.targete2e.rooms.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.evidence.application.EvidenceDossierFreezer;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceDossierItemEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceDossierItemRepository;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceVerificationRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochProvisioningMapper;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.ActivationIdentity;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.LifecycleObservation;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.LifecycleState;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart.ExecutionLane;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

class TargetEvidenceTerminalActivitiesTest {

  private static final String WORKFLOW_RUN_ID = "evidence-run-1";
  private static final String RESET_CURRENT_RUN_ID = "evidence-reset-current-run";
  private static final String DURABLE_ROOM_RUN_ID = "evidence-durable-first-run";
  private static final String USER_COMPLETION_ID = "EVIDENCE_COMPLETE_USER";
  private static final String MERCHANT_COMPLETION_ID = "EVIDENCE_COMPLETE_MERCHANT";
  private static final String USER_COMMAND_ID = "evidence-complete:" + USER_COMPLETION_ID;
  private static final String MERCHANT_COMMAND_ID =
      "evidence-complete:" + MERCHANT_COMPLETION_ID;

  @Test
  void terminalTransitionCanonicalizesSubMicrosecondClockBeforeHearingProjectionValidation() {
    Instant clockValue = Instant.parse("2026-08-15T09:49:41.123456789Z");

    Instant committedAt =
        JdbcTargetEvidenceTerminalActivities.canonicalTerminalInstant(
            Clock.fixed(clockValue, java.time.ZoneOffset.UTC));
    Instant hearingDeadline = committedAt.plus(java.time.Duration.ofHours(3));

    assertThat(committedAt)
        .isEqualTo(clockValue.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    assertThat(hearingDeadline)
        .isEqualTo(Instant.parse("2026-08-15T12:49:41.123456Z"));
    assertThat(hearingDeadline.plusNanos(1_000)).isNotEqualTo(hearingDeadline);
  }

  @Test
  void frozenDossierAggregateIsVisibleToSameTransactionTerminalJdbcWrites() {
    try (DossierVisibilityFixture fixture = new DossierVisibilityFixture()) {
      byte[] committed = fixture.finalizeOnce();
      byte[] replayed = fixture.finalizeOnce();

      assertThat(replayed).containsExactly(committed);
      assertThat(fixture.count("evidence_dossier")).isEqualTo(1);
      assertThat(fixture.dossierStatus()).isEqualTo("FROZEN");
      assertThat(fixture.count("evidence_dossier_item")).isEqualTo(1);
      assertThat(fixture.count("case_timeline_event")).isEqualTo(1);
      assertThat(fixture.count("target_e2e_evidence_terminal_receipt")).isEqualTo(1);
      assertThat(fixture.count("case_room")).isEqualTo(1);

      assertThatThrownBy(fixture::appendTimelineForMissingDossier)
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
      assertThat(fixture.count("evidence_dossier")).isEqualTo(1);
      assertThat(fixture.count("case_timeline_event")).isEqualTo(1);
      assertThat(fixture.count("target_e2e_evidence_terminal_receipt")).isEqualTo(1);
      assertThat(fixture.count("case_room")).isEqualTo(1);
    }
  }

  @Test
  void bothPartyCompletionsFinalizeAgainstPartialTimelineEventKeyIndexExactlyOnce()
      throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    JdbcTargetEvidenceTerminalActivities subject = subject();
    EvidenceDossierEntity frozen =
        EvidenceDossierEntity.frozen(
            "DOSSIER_E2E", "CASE_E2E", 3, "test", "{}", "[]", "[]");
    var appendEventAndOutbox =
        JdbcTargetEvidenceTerminalActivities.class.getDeclaredMethod(
            "appendEventAndOutbox",
            Connection.class,
            TargetEvidenceTerminalActivities.TerminalRequest.class,
            EvidenceDossierEntity.class,
            String.class,
            Instant.class,
            String.class);
    appendEventAndOutbox.setAccessible(true);

    for (int replay = 0; replay < 2; replay++) {
      appendEventAndOutbox.invoke(
          subject,
          connection,
          request(),
          frozen,
          "ROOM_HEARING_E2E",
          Instant.parse("2026-08-15T12:49:41.123456Z"),
          "a".repeat(64));
    }

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(connection, times(2)).prepareStatement(sqlCaptor.capture());
    assertThat(sqlCaptor.getAllValues()).hasSize(2).allMatch(sqlCaptor.getValue()::equals);
    String normalizedSql = sqlCaptor.getValue().replaceAll("\\s+", " ").trim().toLowerCase();
    assertThat(normalizedSql)
        .endsWith(
            "on conflict (case_id, event_key) where event_key is not null do nothing")
        .doesNotContain("on conflict on constraint")
        .doesNotContain("on conflict (event_key)");
    verify(statement, times(2))
        .setObject(7, "target-e2e-hearing-open:CASE_E2E:4");
    verify(statement, times(2)).executeUpdate();
    assertThat(
            JdbcTargetEvidenceTerminalActivities.canonicalTerminalInstant(
                Clock.fixed(
                    Instant.parse("2026-08-15T09:49:41.123456789Z"),
                    java.time.ZoneOffset.UTC)))
        .isEqualTo(Instant.parse("2026-08-15T09:49:41.123456Z"));
  }

  @Test
  void terminalSealCarriesEvidenceHighWaterMarksIntoHearingProvision() throws Exception {
    OffsetDateTime sourceProjectedAt =
        OffsetDateTime.ofInstant(Instant.parse("2026-08-15T09:00:00Z"), ZoneOffset.UTC);
    OffsetDateTime terminalAt =
        OffsetDateTime.ofInstant(Instant.parse("2026-08-15T09:49:41.123456Z"), ZoneOffset.UTC);
    String caseWorkflowId =
        CaseProcessWorkflowProtocol.caseWorkflowId("tenant-e2e", "CASE_E2E");
    CaseProcessProjectionEntity projection =
        CaseProcessProjectionEntity.initialize(
            "CASE_E2E",
            "tenant-e2e",
            "EVIDENCE_OPEN",
            "EVIDENCE",
            "OPEN",
            WriterMode.TEMPORAL,
            11,
            4,
            9,
            terminalAt,
            caseWorkflowId,
            WORKFLOW_RUN_ID,
            "local-control-build",
            sourceProjectedAt);
    var commandCursor =
        CaseProcessProjectionEntity.class.getDeclaredField("lastCommandSequence");
    commandCursor.setAccessible(true);
    commandCursor.setLong(projection, 6);
    var eventCursor =
        CaseProcessProjectionEntity.class.getDeclaredField("lastCaseEventSequence");
    eventCursor.setAccessible(true);
    eventCursor.setLong(projection, 12);

    Connection commandConnection = mock(Connection.class);
    PreparedStatement commandStatement = mock(PreparedStatement.class);
    ResultSet commandRows = mock(ResultSet.class);
    when(commandConnection.prepareStatement(anyString())).thenReturn(commandStatement);
    when(commandStatement.executeQuery()).thenReturn(commandRows);
    when(commandRows.next()).thenReturn(true, true, false);
    when(commandRows.getString(1)).thenReturn(USER_COMMAND_ID, MERCHANT_COMMAND_ID);
    when(commandRows.getLong(2)).thenReturn(10L, 11L);
    long terminalCommandSequence =
        JdbcTargetEvidenceTerminalActivities.lockCompletionCommands(
            commandConnection, request());
    EvidenceDossierEntity frozen =
        EvidenceDossierEntity.frozen(
            "DOSSIER_E2E", "CASE_E2E", 3, "test", "{}", "[]", "[]");
    Connection eventConnection = mock(Connection.class);
    PreparedStatement eventStatement = mock(PreparedStatement.class);
    ResultSet eventRow = mock(ResultSet.class);
    when(eventConnection.prepareStatement(anyString())).thenReturn(eventStatement);
    when(eventStatement.executeQuery()).thenReturn(eventRow);
    when(eventRow.next()).thenReturn(true, false);
    when(eventRow.getLong(1)).thenReturn(20L);
    long terminalEventSequence =
        JdbcTargetEvidenceTerminalActivities.lockTerminalEventSequence(
            eventConnection, request(), frozen, "a".repeat(64));

    assertThatThrownBy(
            () ->
                new RoomEpochAllocator.TransitionRoomEpoch(
                    "CASE_E2E",
                    RoomType.EVIDENCE,
                    "ROOM_HEARING_E2E",
                    RoomType.HEARING,
                    "HEARING_OPEN",
                    "PROVISIONING",
                    terminalAt.plusHours(3),
                    terminalAt,
                    null,
                    null,
                    terminalCommandSequence,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must both be absent or present");
    assertThatThrownBy(() -> projection.advanceSequenceHighWater(3, 9, 11, 20))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("room epoch or fence is stale");
    assertThatThrownBy(() -> projection.advanceSequenceHighWater(4, 9, 5, 20))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot move backward");

    var transition =
        new RoomEpochAllocator.TransitionRoomEpoch(
            "CASE_E2E",
            RoomType.EVIDENCE,
            "ROOM_HEARING_E2E",
            RoomType.HEARING,
            "HEARING_OPEN",
            "PROVISIONING",
            terminalAt.plusHours(3),
            terminalAt,
            null,
            null,
            terminalCommandSequence,
            terminalEventSequence);
    projection.advanceSequenceHighWater(
        4,
        9,
        transition.lastCommandSequence(),
        transition.lastCaseEventSequence());
    projection.switchTo(
        4,
        9,
        "HEARING_OPEN",
        "HEARING",
        "PROVISIONING",
        WriterMode.TEMPORAL,
        12,
        0,
        10,
        terminalAt.plusHours(3),
        caseWorkflowId,
        null,
        "local-control-build",
        terminalAt);

    CaseRoomEpochEntity hearingEpoch = mock(CaseRoomEpochEntity.class);
    when(hearingEpoch.getId()).thenReturn("epoch-hearing-0");
    when(hearingEpoch.getTenantSurrogate()).thenReturn("tenant-e2e");
    when(hearingEpoch.getCaseId()).thenReturn("CASE_E2E");
    when(hearingEpoch.getRoomId()).thenReturn("ROOM_HEARING_E2E");
    when(hearingEpoch.getRoomType()).thenReturn(RoomType.HEARING);
    when(hearingEpoch.getRoomEpoch()).thenReturn(0L);
    when(hearingEpoch.getProcessRevision()).thenReturn(12L);
    when(hearingEpoch.getRoomRevision()).thenReturn(0L);
    when(hearingEpoch.getFencingToken()).thenReturn(10L);
    when(hearingEpoch.getWriterMode()).thenReturn(WriterMode.TEMPORAL);
    when(hearingEpoch.getTemporalWorkflowId()).thenReturn(caseWorkflowId);
    when(hearingEpoch.getRoomTemporalWorkflowId())
        .thenReturn(CaseProcessWorkflowProtocol.roomWorkflowId("CASE_E2E", RoomType.HEARING, 0));
    when(hearingEpoch.getSelectionSchemaVersion())
        .thenReturn(TargetTypedRoomProtocol.SELECTION_SCHEMA_VERSION);
    when(hearingEpoch.getProcessContractVersion())
        .thenReturn(TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION);
    when(hearingEpoch.getWorkflowType()).thenReturn(TargetTypedRoomProtocol.CASE_WORKFLOW_TYPE);
    when(hearingEpoch.getTemporalBuildId()).thenReturn("local-control-build");
    when(hearingEpoch.getRoomWorkflowType())
        .thenReturn(TargetTypedRoomProtocol.HEARING_WORKFLOW_TYPE);
    when(hearingEpoch.getRoomWorkflowBuildId()).thenReturn("local-control-build");
    when(hearingEpoch.getGraphKey()).thenReturn(TargetTypedRoomProtocol.GRAPH_KEY);
    when(hearingEpoch.getGraphVersion()).thenReturn(TargetTypedRoomProtocol.GRAPH_VERSION);
    when(hearingEpoch.getCheckpointSchemaVersion())
        .thenReturn(TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION);
    when(hearingEpoch.getStreamProtocol()).thenReturn(TargetTypedRoomProtocol.STREAM_PROTOCOL);
    RoomEpochProvisioningMapper provisioningMapper =
        new RoomEpochProvisioningMapper(new ObjectMapper().findAndRegisterModules());

    ProvisionRoomEpoch provision =
        provisioningMapper.fromLockedState(hearingEpoch, projection, terminalAt);

    assertThat(provision.lastCommandSequence()).isEqualTo(11);
    assertThat(provision.lastCaseEventSequence()).isEqualTo(20);
    assertThat(provision.firstCommandSequence()).isEqualTo(12);
    assertThat(provision.firstCaseEventSequence()).isEqualTo(21);
    ProvisionRoomEpoch replay =
        provisioningMapper.fromLockedState(hearingEpoch, projection, terminalAt);
    assertThat(provisioningMapper.toJson(replay))
        .isEqualTo(provisioningMapper.toJson(provision));
    assertThat(replay.payloadSha256()).isEqualTo(provision.payloadSha256());

    Connection timelineConnection = mock(Connection.class);
    PreparedStatement timelineStatement = mock(PreparedStatement.class);
    when(timelineConnection.prepareStatement(anyString())).thenReturn(timelineStatement);
    var appendEventAndOutbox =
        JdbcTargetEvidenceTerminalActivities.class.getDeclaredMethod(
            "appendEventAndOutbox",
            Connection.class,
            TargetEvidenceTerminalActivities.TerminalRequest.class,
            EvidenceDossierEntity.class,
            String.class,
            Instant.class,
            String.class);
    appendEventAndOutbox.setAccessible(true);
    appendEventAndOutbox.invoke(
        subject(),
        timelineConnection,
        request(),
        frozen,
        "ROOM_HEARING_E2E",
        terminalAt.plusHours(3).toInstant(),
        "a".repeat(64));
    ArgumentCaptor<String> timelineSql = ArgumentCaptor.forClass(String.class);
    verify(timelineConnection).prepareStatement(timelineSql.capture());
    assertThat(timelineSql.getValue().replaceAll("\\s+", " ").trim().toLowerCase())
        .endsWith(
            "on conflict (case_id, event_key) where event_key is not null do nothing");
    assertThat(
            JdbcTargetEvidenceTerminalActivities.canonicalTerminalInstant(
                Clock.fixed(
                    Instant.parse("2026-08-15T09:49:41.123456789Z"), ZoneOffset.UTC)))
        .isEqualTo(terminalAt.toInstant());

    RoomEpochAllocator replayAllocator = mock(RoomEpochAllocator.class);
    EvidenceDossierFreezer replayFreezer = mock(EvidenceDossierFreezer.class);
    JdbcTargetEvidenceTerminalActivities replaySubject =
        new JdbcTargetEvidenceTerminalActivities(
            mock(javax.sql.DataSource.class),
            mock(TransactionTemplate.class),
            mock(TargetE2eActivationLifecycleStore.class),
            replayFreezer,
            replayAllocator,
            new ObjectMapper().findAndRegisterModules(),
            Clock.systemUTC());
    var terminalRequest = request();
    String terminalRequestHash = replaySubject.hash(terminalRequest);
    String seed =
        ContractJson.sha256Hex(
            new ObjectMapper()
                .findAndRegisterModules()
                .valueToTree(
                    java.util.List.of(
                        terminalRequest.start().caseId(),
                        terminalRequest.start().roomEpoch(),
                        terminalRequestHash)));
    String receiptId = "EVDTERM_" + seed.substring(0, 32);
    String hearingRoomId = "ROOM_HEARING_" + seed.substring(0, 28);
    Instant deadline = terminalAt.plusHours(3).toInstant();
    String stableReceiptHash =
        replaySubject.receiptHash(
            terminalRequest, frozen.getId(), frozen.getDossierVersion(), hearingRoomId, deadline, 9, 6);
    byte[] canonical =
        replaySubject.receiptCanonical(
            receiptId,
            stableReceiptHash,
            terminalRequestHash,
            terminalRequest,
            frozen.getId(),
            frozen.getDossierVersion(),
            hearingRoomId,
            deadline,
            9,
            6,
            terminalAt.toInstant());
    var stored =
        new JdbcTargetEvidenceTerminalActivities.Stored(
            receiptId,
            stableReceiptHash,
            terminalRequestHash,
            terminalRequest.start().tenantSurrogate(),
            terminalRequest.start().caseId(),
            terminalRequest.start().roomEpoch(),
            terminalRequest.start().fencingToken(),
            terminalRequest.initiatorCompletionId(),
            terminalRequest.respondentCompletionId(),
            frozen.getId(),
            frozen.getDossierVersion(),
            hearingRoomId,
            deadline,
            9,
            6,
            canonical,
            terminalAt.toInstant());
    assertThatCode(() -> replaySubject.requireStoredReplay(terminalRequest, stored))
        .doesNotThrowAnyException();
    assertThatCode(() -> replaySubject.requireStoredReplay(terminalRequest, stored))
        .doesNotThrowAnyException();
    verifyNoInteractions(replayAllocator);
  }

  @Test
  void terminalRequestRejectsMissingOrUnfencedPartyCompletionCoordinates() {
    EvidenceRoomStart start = start();

    assertThatThrownBy(() -> new TargetEvidenceTerminalActivities.TerminalRequest(
        start, 10, 7, "", "COMPLETE_MERCHANT"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TargetRoomProgressReceipt(
        null, 4, 9, 11, 8, "receipt", "a".repeat(64)))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void terminalActivationLifecycleIsQueryOnlyAfterDrainOrRevocation() {
    assertThat(JdbcTargetEvidenceTerminalActivities.validActivationLifecycle("ACTIVE", false))
        .isTrue();
    assertThat(
            JdbcTargetEvidenceTerminalActivities.validActivationLifecycle("DRAIN_ONLY", false))
        .isTrue();
    assertThat(JdbcTargetEvidenceTerminalActivities.validActivationLifecycle("DRAINED", true))
        .isTrue();
    assertThat(
            JdbcTargetEvidenceTerminalActivities.validActivationLifecycle(
                "REVOKED_TERMINAL", true))
        .isTrue();
    assertThat(JdbcTargetEvidenceTerminalActivities.validActivationLifecycle("DRAINED", false))
        .isFalse();
  }

  @Test
  void expiredSupersededBoundActivationRefreshesToDrainOnlyBeforeTerminalAuthorization()
      throws Exception {
    Instant supersededAt = Instant.parse("2026-08-15T00:45:00Z");
    Instant expiresAt = Instant.parse("2026-08-15T00:51:51Z");
    Instant terminalNow = Instant.parse("2026-08-15T01:00:00Z");
    assertThat(supersededAt).isBefore(expiresAt);
    assertThat(terminalNow).isAfter(expiresAt);

    ActivationIdentity boundIdentity =
        new ActivationIdentity(
            "environment-a",
            7,
            "p9act.v1." + "a".repeat(32),
            "b".repeat(64));
    ActivationIdentity unrelatedCurrentIdentity =
        new ActivationIdentity(
            "environment-a",
            8,
            "p9act.v1." + "c".repeat(32),
            "d".repeat(64));
    var terminalRequest = resetRequest(RESET_CURRENT_RUN_ID, DURABLE_ROOM_RUN_ID);
    var terminalReceipt =
        new TargetEvidenceTerminalActivities.TerminalResult(
            new TargetRoomProgressReceipt(
                RoomType.EVIDENCE,
                terminalRequest.start().roomEpoch(),
                terminalRequest.start().fencingToken(),
                Math.incrementExact(terminalRequest.expectedProcessRevision()),
                Math.incrementExact(terminalRequest.expectedRoomRevision()),
                "target-evidence-expired-bound-terminal-receipt",
                "e".repeat(64)));

    var callOrder = new ArrayList<String>();
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet row = mock(ResultSet.class);
    when(dataSource.getConnection())
        .thenAnswer(
            ignored -> {
              callOrder.add("load-bound-activation");
              return connection;
            });
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
    when(row.next()).thenReturn(true, false, true, false);
    when(row.getString(1)).thenReturn(boundIdentity.environmentId());
    when(row.getLong(2)).thenReturn(boundIdentity.environmentGeneration());
    when(row.getString(3)).thenReturn(boundIdentity.activationId());
    when(row.getString(4)).thenReturn(boundIdentity.manifestHash());
    when(row.getObject(5, OffsetDateTime.class))
        .thenReturn(OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));

    TargetE2eActivationLifecycleStore lifecycleStore =
        mock(TargetE2eActivationLifecycleStore.class);
    when(lifecycleStore.refresh(eq(boundIdentity), eq(expiresAt), eq(terminalNow)))
        .thenAnswer(
            ignored -> {
              callOrder.add("refresh-bound-activation");
              return new LifecycleObservation(LifecycleState.DRAIN_ONLY, expiresAt);
            });
    TransactionTemplate transaction = mock(TransactionTemplate.class);
    AtomicReference<TargetEvidenceTerminalActivities.TerminalResult> storedReceipt =
        new AtomicReference<>();
    AtomicInteger durableTerminalWrites = new AtomicInteger();
    when(transaction.execute(any()))
        .thenAnswer(
            ignored -> {
              callOrder.add("terminal-transaction");
              TargetEvidenceTerminalActivities.TerminalResult stored = storedReceipt.get();
              if (stored != null) {
                return stored;
              }
              durableTerminalWrites.incrementAndGet();
              storedReceipt.set(terminalReceipt);
              return terminalReceipt;
            });
    JdbcTargetEvidenceTerminalActivities subject =
        new JdbcTargetEvidenceTerminalActivities(
            dataSource,
            transaction,
            lifecycleStore,
            mock(EvidenceDossierFreezer.class),
            mock(RoomEpochAllocator.class),
            new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(terminalNow, ZoneOffset.UTC));

    var first =
        subject.finalizeTerminal(
            terminalRequest, canonicalWorkflowId(), RESET_CURRENT_RUN_ID);
    var replay =
        subject.finalizeTerminal(
            terminalRequest, canonicalWorkflowId(), RESET_CURRENT_RUN_ID);

    assertThat(first).isEqualTo(terminalReceipt);
    assertThat(replay).isEqualTo(first);
    assertThat(durableTerminalWrites).hasValue(1);
    assertThat(callOrder)
        .containsExactly(
            "load-bound-activation",
            "refresh-bound-activation",
            "terminal-transaction",
            "load-bound-activation",
            "refresh-bound-activation",
            "terminal-transaction");
    verify(lifecycleStore, times(2)).refresh(boundIdentity, expiresAt, terminalNow);
    verify(lifecycleStore, never()).refresh(eq(unrelatedCurrentIdentity), any(), any());
    verify(transaction, times(2)).execute(any());
    verify(statement, times(2)).setString(6, canonicalWorkflowId());
    verify(statement, times(2)).setString(7, DURABLE_ROOM_RUN_ID);
    verify(connection, times(2))
        .prepareStatement(
            argThat(
                sql ->
                    sql.contains("from case_room_epoch epoch")
                        && sql.contains("join target_e2e_room_epoch_binding binding")
                        && sql.contains("join target_e2e_activation activation")
                        && sql.contains("activation.environment_id")
                        && sql.contains("activation.environment_generation")
                        && sql.contains("binding.activation_id")
                        && sql.contains("binding.activation_manifest_hash")
                        && sql.contains("activation.expires_at")
                        && sql.contains("epoch.room_temporal_workflow_id = ?")
                        && sql.contains("epoch.room_temporal_run_id = ?")
                        && sql.contains("epoch.room_workflow_build_id = ?")
                        && sql.contains("epoch.fencing_token = ?")
                        && sql.contains("activation.manifest_hash = binding.activation_manifest_hash")
                        && sql.contains("activation.control_build_id = epoch.room_workflow_build_id")
                        && !sql.contains("clock_timestamp()")));

    assertThatCode(
            () ->
                JdbcTargetEvidenceTerminalActivities.requireAuthorityCoordinates(
                    new JdbcTargetEvidenceTerminalActivities.Authority(
                        "epoch-evidence-expired-bound",
                        "ACTIVE",
                        8,
                        5,
                        boundIdentity.activationId(),
                        "DRAIN_ONLY",
                        true),
                    terminalRequest,
                    false))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                JdbcTargetEvidenceTerminalActivities.requireAuthorityCoordinates(
                    new JdbcTargetEvidenceTerminalActivities.Authority(
                        "epoch-evidence-unexpired-bound",
                        "ACTIVE",
                        8,
                        5,
                        boundIdentity.activationId(),
                        "ACTIVE",
                        true),
                    terminalRequest,
                    false))
        .doesNotThrowAnyException();
    for (String terminalLifecycle : java.util.List.of("DRAINED", "REVOKED_TERMINAL")) {
      var terminalAuthority =
          new JdbcTargetEvidenceTerminalActivities.Authority(
              "epoch-evidence-terminal-bound",
              "TERMINAL",
              9,
              6,
              boundIdentity.activationId(),
              terminalLifecycle,
              false);
      assertThatThrownBy(
              () ->
                  JdbcTargetEvidenceTerminalActivities.requireAuthorityCoordinates(
                      terminalAuthority, terminalRequest, false))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("epoch coordinates drifted");
      assertThatCode(
              () ->
                  JdbcTargetEvidenceTerminalActivities.requireAuthorityCoordinates(
                      terminalAuthority, terminalRequest, true))
          .doesNotThrowAnyException();
    }

    DataSource driftDataSource = mock(DataSource.class);
    Connection driftConnection = mock(Connection.class);
    PreparedStatement driftStatement = mock(PreparedStatement.class);
    ResultSet noExactBinding = mock(ResultSet.class);
    when(driftDataSource.getConnection()).thenReturn(driftConnection);
    when(driftConnection.prepareStatement(anyString())).thenReturn(driftStatement);
    when(driftStatement.executeQuery()).thenReturn(noExactBinding);
    when(noExactBinding.next()).thenReturn(false);
    TargetE2eActivationLifecycleStore driftLifecycleStore =
        mock(TargetE2eActivationLifecycleStore.class);
    TransactionTemplate driftTransaction = mock(TransactionTemplate.class);
    JdbcTargetEvidenceTerminalActivities driftSubject =
        new JdbcTargetEvidenceTerminalActivities(
            driftDataSource,
            driftTransaction,
            driftLifecycleStore,
            mock(EvidenceDossierFreezer.class),
            mock(RoomEpochAllocator.class),
            new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(terminalNow, ZoneOffset.UTC));
    assertThatThrownBy(
            () ->
                driftSubject.finalizeTerminal(
                    terminalRequest, canonicalWorkflowId(), RESET_CURRENT_RUN_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no exact bound activation authority");
    verifyNoInteractions(driftLifecycleStore, driftTransaction);
  }

  @Test
  void rejectsNonCanonicalWorkflowIdAndFrozenRunIdDriftBeforeJdbcAuthority() {
    var request = request();
    String canonical = canonicalWorkflowId();

    assertThatThrownBy(
            () ->
                JdbcTargetEvidenceTerminalActivities.requireWorkflowIdentity(
                    request, "forged-evidence-workflow", WORKFLOW_RUN_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("canonical room workflow");
    assertThatThrownBy(
            () ->
                JdbcTargetEvidenceTerminalActivities.requireWorkflowIdentity(
                    request, canonical, "wrong-run"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("identity drifted");
  }

  @Test
  void resetTerminalRequestSeparatesCurrentCallerFromDurableEpochAuthority()
      throws Exception {
    var resetRequest = resetRequest(RESET_CURRENT_RUN_ID, DURABLE_ROOM_RUN_ID);
    assertThat(durableWorkflowRunId(resetRequest)).isEqualTo(DURABLE_ROOM_RUN_ID);

    var resetIdentity =
        JdbcTargetEvidenceTerminalActivities.requireWorkflowIdentity(
            resetRequest, canonicalWorkflowId(), RESET_CURRENT_RUN_ID);
    assertThat(resetIdentity.workflowId()).isEqualTo(canonicalWorkflowId());
    assertThat(resetIdentity.workflowRunId()).isEqualTo(RESET_CURRENT_RUN_ID);
    assertThat(roomAuthorityRunId(resetIdentity)).isEqualTo(DURABLE_ROOM_RUN_ID);

    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet row = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
    when(row.next()).thenReturn(true, false);
    when(row.getString(1)).thenReturn("epoch-evidence-reset");
    when(row.getString(2)).thenReturn("ACTIVE");
    when(row.getLong(3)).thenReturn(8L);
    when(row.getLong(4)).thenReturn(5L);
    when(row.getString(5)).thenReturn("p9act.v1." + "a".repeat(32));
    when(row.getString(6)).thenReturn("ACTIVE");
    when(row.getBoolean(7)).thenReturn(true);

    assertThat(
            JdbcTargetEvidenceTerminalActivities.lockAuthority(
                connection, resetRequest, resetIdentity))
        .extracting(JdbcTargetEvidenceTerminalActivities.Authority::epochId)
        .isEqualTo("epoch-evidence-reset");
    verify(statement).setString(6, canonicalWorkflowId());
    verify(statement).setString(7, DURABLE_ROOM_RUN_ID);

    assertThatThrownBy(
            () ->
                JdbcTargetEvidenceTerminalActivities.requireWorkflowIdentity(
                    resetRequest(RESET_CURRENT_RUN_ID + "-forged", DURABLE_ROOM_RUN_ID),
                    canonicalWorkflowId(),
                    RESET_CURRENT_RUN_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("identity drifted");
    assertThatThrownBy(() -> resetRequest(RESET_CURRENT_RUN_ID, " "))
        .isInstanceOf(IllegalArgumentException.class);

    var wrongAuthorityRequest =
        resetRequest(RESET_CURRENT_RUN_ID, DURABLE_ROOM_RUN_ID + "-forged");
    var wrongAuthorityIdentity =
        JdbcTargetEvidenceTerminalActivities.requireWorkflowIdentity(
            wrongAuthorityRequest, canonicalWorkflowId(), RESET_CURRENT_RUN_ID);
    Connection noAuthorityConnection = mock(Connection.class);
    PreparedStatement noAuthorityStatement = mock(PreparedStatement.class);
    ResultSet noAuthorityRow = mock(ResultSet.class);
    when(noAuthorityConnection.prepareStatement(anyString())).thenReturn(noAuthorityStatement);
    when(noAuthorityStatement.executeQuery()).thenReturn(noAuthorityRow);
    when(noAuthorityRow.next()).thenReturn(false);
    assertThatThrownBy(
            () ->
                JdbcTargetEvidenceTerminalActivities.lockAuthority(
                    noAuthorityConnection, wrongAuthorityRequest, wrongAuthorityIdentity))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no exact active room authority");
    verify(noAuthorityStatement).setString(7, DURABLE_ROOM_RUN_ID + "-forged");

    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    String legacyJson = mapper.writeValueAsString(request());
    assertThat(legacyJson).doesNotContain("durableWorkflowRunId");
    var legacyRequest =
        mapper.readValue(
            legacyJson, TargetEvidenceTerminalActivities.TerminalRequest.class);
    var legacyIdentity =
        JdbcTargetEvidenceTerminalActivities.requireWorkflowIdentity(
            legacyRequest, canonicalWorkflowId(), WORKFLOW_RUN_ID);
    assertThat(roomAuthorityRunId(legacyIdentity)).isEqualTo(WORKFLOW_RUN_ID);
    assertThat(mapper.writeValueAsBytes(resetRequest))
        .isEqualTo(
            mapper.writeValueAsBytes(
                resetRequest(RESET_CURRENT_RUN_ID, DURABLE_ROOM_RUN_ID)));
    assertThat(subject().hash(resetRequest))
        .isEqualTo(
            subject().hash(
                resetRequest(RESET_CURRENT_RUN_ID, DURABLE_ROOM_RUN_ID)));
  }

  @Test
  void locksExactTargetEpochAuthorityBeforeTerminalWrites() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet row = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
    when(row.next()).thenReturn(true, false);
    when(row.getString(1)).thenReturn("epoch-evidence-4");
    when(row.getString(2)).thenReturn("ACTIVE");
    when(row.getLong(3)).thenReturn(8L);
    when(row.getLong(4)).thenReturn(5L);
    when(row.getString(5)).thenReturn("p9act.v1." + "a".repeat(32));
    when(row.getString(6)).thenReturn("ACTIVE");
    when(row.getBoolean(7)).thenReturn(true);
    var request = request();
    var identity =
        JdbcTargetEvidenceTerminalActivities.requireWorkflowIdentity(
            request, canonicalWorkflowId(), WORKFLOW_RUN_ID);

    var authority =
        JdbcTargetEvidenceTerminalActivities.lockAuthority(connection, request, identity);

    assertThat(authority.lifecycleStatus()).isEqualTo("ACTIVE");
    assertThatCode(
            () ->
                JdbcTargetEvidenceTerminalActivities.requireAuthorityCoordinates(
                    authority, request, false))
        .doesNotThrowAnyException();
    verify(connection)
        .prepareStatement(
            argThat(
                sql ->
                    sql.contains("epoch.room_temporal_workflow_id = ?")
                        && sql.contains("epoch.room_temporal_run_id = ?")
                        && sql.contains("epoch.room_workflow_build_id = ?")
                        && sql.contains("binding.execution_lane = 'TARGET_E2E_CANDIDATE'")
                        && sql.contains("activation.execution_lane = 'TARGET_E2E_CANDIDATE'")
                        && sql.contains("activation.lifecycle_status in (")
                        && sql.contains("'ACTIVE', 'DRAIN_ONLY', 'DRAINED', 'REVOKED_TERMINAL'")
                        && sql.contains("as accepts_new_write")
                        && sql.contains("for update of epoch")));
  }

  @Test
  void locksBothAppliedCommandsInDeterministicOrderBeforeEpochAuthority() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet row = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
    when(row.next()).thenReturn(true, true, false);
    when(row.getString(1)).thenReturn(MERCHANT_COMMAND_ID, USER_COMMAND_ID);

    assertThatCode(
            () ->
                JdbcTargetEvidenceTerminalActivities.lockCompletionCommands(
                    connection, request()))
        .doesNotThrowAnyException();
    verify(connection)
        .prepareStatement(
            argThat(
                sql ->
                    sql.contains("command_status = 'APPLIED'")
                        && sql.contains("order by command_id")
                        && sql.contains("for update")));
  }

  @Test
  void storedReplayAllowsExpiredActiveActivationButRequiresExactTerminalCoordinates() {
    var request = request();

    assertThatCode(
            () ->
                JdbcTargetEvidenceTerminalActivities.requireAuthorityCoordinates(
                    new JdbcTargetEvidenceTerminalActivities.Authority(
                        "epoch-evidence-4",
                        "TERMINAL",
                        9,
                        6,
                        "p9act.v1." + "a".repeat(32),
                        "ACTIVE",
                        false),
                    request,
                    true))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                JdbcTargetEvidenceTerminalActivities.requireAuthorityCoordinates(
                    new JdbcTargetEvidenceTerminalActivities.Authority(
                        "epoch-evidence-4",
                        "ACTIVE",
                        9,
                        6,
                        "p9act.v1." + "a".repeat(32),
                        "ACTIVE",
                        false),
                    request,
                    true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("coordinates drifted");
  }

  @Test
  void expiredActiveActivationAllowsOnlyStoredTerminalReplay() {
    var request = request();
    var expiredActiveNewWrite =
        new JdbcTargetEvidenceTerminalActivities.Authority(
            "epoch-evidence-4",
            "ACTIVE",
            request.expectedProcessRevision(),
            request.expectedRoomRevision(),
            "p9act.v1." + "a".repeat(32),
            "ACTIVE",
            false);

    assertThatThrownBy(
            () ->
                JdbcTargetEvidenceTerminalActivities.requireAuthorityCoordinates(
                    expiredActiveNewWrite, request, false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("coordinates drifted");

    assertThatCode(
            () ->
                JdbcTargetEvidenceTerminalActivities.requireAuthorityCoordinates(
                    new JdbcTargetEvidenceTerminalActivities.Authority(
                        "epoch-evidence-4",
                        "ACTIVE",
                        request.expectedProcessRevision(),
                        request.expectedRoomRevision(),
                        "p9act.v1." + "a".repeat(32),
                        "DRAIN_ONLY",
                        true),
                    request,
                    false))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesTerminalizationWithoutTwoCommandAppliedCompletionFacts() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet row = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
    when(row.next()).thenReturn(false);
    JdbcTargetEvidenceTerminalActivities subject = subject();

    assertThatThrownBy(
            () ->
                subject.requirePersistedCompletionFactsAfterCommandLock(
                    connection, request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("two durable party completions");
    verify(connection, never())
        .prepareStatement(argThat(sql -> sql.toLowerCase().contains("insert")));
  }

  @Test
  void acceptsOnlyExactDurablePartyCompletionRowsFromTheCommandLane() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet row = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
    when(row.next()).thenReturn(true, true, false);
    when(row.getString(1))
        .thenReturn(USER_COMPLETION_ID, MERCHANT_COMPLETION_ID);
    when(row.getString(2)).thenReturn(USER_COMMAND_ID, MERCHANT_COMMAND_ID);
    when(row.getInt(3)).thenReturn(3, 3);
    when(row.getString(4)).thenReturn("USER", "MERCHANT");
    when(row.getString(5)).thenReturn("USER_E2E", "MERCHANT_E2E");
    when(row.getString(6)).thenReturn("COMPLETED", "COMPLETED");
    when(row.getString(7)).thenReturn("USER_E2E", "MERCHANT_E2E");
    when(row.getString(8)).thenReturn("a".repeat(64), "b".repeat(64));
    when(row.getLong(9)).thenReturn(6L, 7L);
    when(row.getString(10))
        .thenReturn(
            partyCompletionResultHash(USER_COMPLETION_ID, "a".repeat(64), 6, 3),
            partyCompletionResultHash(MERCHANT_COMPLETION_ID, "b".repeat(64), 7, 4));

    assertThatCode(
            () ->
                subject()
                    .requirePersistedCompletionFactsAfterCommandLock(
                        connection, request()))
        .doesNotThrowAnyException();
    verify(connection)
        .prepareStatement(
            argThat(
                sql ->
                    sql.contains("join case_command command_row")
                        && sql.contains(
                            "command_row.command_id = 'evidence-complete:' || completion.id")
                        && sql.contains("command_row.command_status = 'APPLIED'")
                        && sql.contains("command_row.result_uri = ? || completion.id")
                        && sql.contains("for update of completion, command_row, participant")
                        && !sql.toLowerCase().contains("insert into evidence_party_completion")));
    verify(statement)
        .setString(3, JdbcTargetEvidencePartyCompletionActivities.RESULT_URI_PREFIX);
  }

  @Test
  void rejectsParticipantOrIdempotencyDriftInDurableCompletionFacts() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet row = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
    when(row.next()).thenReturn(true, true, false);
    when(row.getString(1))
        .thenReturn(USER_COMPLETION_ID, MERCHANT_COMPLETION_ID);
    when(row.getString(2)).thenReturn(USER_COMMAND_ID, MERCHANT_COMMAND_ID);
    when(row.getInt(3)).thenReturn(3, 3);
    when(row.getString(4)).thenReturn("USER", "MERCHANT");
    when(row.getString(5)).thenReturn("FORGED_USER", "MERCHANT_E2E");
    when(row.getString(6)).thenReturn("COMPLETED", "COMPLETED");
    when(row.getString(7)).thenReturn("USER_E2E", "MERCHANT_E2E");
    when(row.getString(8)).thenReturn("a".repeat(64), "b".repeat(64));
    when(row.getLong(9)).thenReturn(6L, 7L);
    when(row.getString(10))
        .thenReturn(
            partyCompletionResultHash(USER_COMPLETION_ID, "a".repeat(64), 6, 3),
            partyCompletionResultHash(MERCHANT_COMPLETION_ID, "b".repeat(64), 7, 4));

    assertThatThrownBy(
            () ->
                subject()
                    .requirePersistedCompletionFactsAfterCommandLock(
                        connection, request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("completion drifted");
  }

  @Test
  void rejectsTamperedPartyCommandResultHash() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet row = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
    when(row.next()).thenReturn(true, true, false);
    when(row.getString(1))
        .thenReturn(USER_COMPLETION_ID, MERCHANT_COMPLETION_ID);
    when(row.getString(2)).thenReturn(USER_COMMAND_ID, MERCHANT_COMMAND_ID);
    when(row.getInt(3)).thenReturn(3, 3);
    when(row.getString(4)).thenReturn("USER", "MERCHANT");
    when(row.getString(5)).thenReturn("USER_E2E", "MERCHANT_E2E");
    when(row.getString(6)).thenReturn("COMPLETED", "COMPLETED");
    when(row.getString(7)).thenReturn("USER_E2E", "MERCHANT_E2E");
    when(row.getString(8)).thenReturn("a".repeat(64), "b".repeat(64));
    when(row.getLong(9)).thenReturn(6L, 7L);
    when(row.getString(10))
        .thenReturn(
            "f".repeat(64),
            partyCompletionResultHash(MERCHANT_COMPLETION_ID, "b".repeat(64), 7, 4));

    assertThatThrownBy(
            () ->
                subject()
                    .requirePersistedCompletionFactsAfterCommandLock(
                        connection, request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("completion drifted");
  }

  @Test
  void rejectsIdempotencyKeyDriftInDurableCompletionFacts() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet row = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(row);
    when(row.next()).thenReturn(true, true, false);
    when(row.getString(1))
        .thenReturn(USER_COMPLETION_ID, MERCHANT_COMPLETION_ID);
    when(row.getString(2)).thenReturn("evidence-complete:FORGED", MERCHANT_COMMAND_ID);
    when(row.getInt(3)).thenReturn(3, 3);
    when(row.getString(4)).thenReturn("USER", "MERCHANT");
    when(row.getString(5)).thenReturn("USER_E2E", "MERCHANT_E2E");
    when(row.getString(6)).thenReturn("COMPLETED", "COMPLETED");
    when(row.getString(7)).thenReturn("USER_E2E", "MERCHANT_E2E");
    when(row.getString(8)).thenReturn("a".repeat(64), "b".repeat(64));
    when(row.getLong(9)).thenReturn(6L, 7L);
    when(row.getString(10))
        .thenReturn(
            partyCompletionResultHash(USER_COMPLETION_ID, "a".repeat(64), 6, 3),
            partyCompletionResultHash(MERCHANT_COMPLETION_ID, "b".repeat(64), 7, 4));

    assertThatThrownBy(
            () ->
                subject()
                    .requirePersistedCompletionFactsAfterCommandLock(
                        connection, request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("completion drifted");
  }

  @Test
  void terminalReceiptGateCountsEveryAdmittedEvidenceMaterialAndFailsClosed() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getLong(1)).thenReturn(1L);
    JdbcTargetEvidenceTerminalActivities subject = subject();
    var request = new TargetEvidenceTerminalActivities.TerminalRequest(
        start(), 8, 5, USER_COMMAND_ID, MERCHANT_COMMAND_ID);

    assertThatThrownBy(() -> subject.requireAgentRunReceipts(connection, request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("all EVIDENCE_SUBMIT AgentRun formal receipts are required");
    verify(connection).prepareStatement(argThat(sql ->
        !sql.contains("command_type")
            && sql.contains("receipt.attempt_id")
            && sql.contains("receipt.command_hash = material.command_hash")
            && sql.contains("receipt.command_envelope_hash = material.command_envelope_hash")
            && sql.contains("receipt.checkpoint_schema_version")));
  }

  @Test
  void terminalReceiptGateAcceptsCommittedRecoveryWinnerAndRejectsMissingWinnerReceipt()
      throws Exception {
    var accepted =
        new JdbcTargetEvidenceTerminalActivities.AgentRunReceiptAuthority(
            "target-evidence-run:recovered",
            "agent-attempt:winner",
            true,
            2,
            1,
            1,
            1,
            1,
            1,
            0);
    assertThatCode(
            () -> JdbcTargetEvidenceTerminalActivities.requireAgentRunReceiptAuthority(accepted))
        .doesNotThrowAnyException();

    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true, false, true, false);
    when(result.getString(1)).thenReturn(accepted.logicalRunId());
    when(result.getString(2)).thenReturn(accepted.committedAttemptId());
    when(result.getBoolean(3)).thenReturn(true);
    when(result.getLong(4)).thenReturn(2L);
    when(result.getLong(5)).thenReturn(1L);
    when(result.getLong(6)).thenReturn(1L);
    when(result.getLong(7)).thenReturn(1L);
    when(result.getLong(8)).thenReturn(1L);
    when(result.getLong(9)).thenReturn(1L);
    when(result.getLong(10)).thenReturn(0L);
    JdbcTargetEvidenceTerminalActivities subject = subject();

    assertThatCode(() -> subject.requireAgentRunReceipts(connection, request()))
        .doesNotThrowAnyException();
    assertThatCode(() -> subject.requireAgentRunReceipts(connection, request()))
        .doesNotThrowAnyException();
    verify(statement, times(2)).executeQuery();
    verify(statement, never()).executeUpdate();
    verify(connection, times(2))
        .prepareStatement(
            argThat(
                sql ->
                    sql.contains("run.committed_attempt_id")
                        && sql.contains("winner_material.attempt_id = run.committed_attempt_id")
                        && sql.contains("receipt.attempt_id = run.committed_attempt_id")
                        && sql.contains("receipt.command_hash = material.command_hash")
                        && sql.contains(
                            "receipt.command_envelope_hash = material.command_envelope_hash")
                        && sql.contains("receipt.result_hash = winner.result_hash")
                        && sql.contains("attempt.id <> winner.id")
                        && sql.contains("attempt.attempt_no < winner.attempt_no")
                        && sql.contains("'ABORTED', 'FAILED', 'CANCELLED'")
                        && sql.contains("attempt.executor_kind = 'TEMPORAL_ACTIVITY'")
                        && sql.contains(
                            "attempt.termination_code = 'CREATE_NEXT_ATTEMPT'")
                        && sql.contains("attempt.completed_at is not null")
                        && sql.contains("attempt.error_retryable")
                        && sql.contains("attempt.result_hash is null")
                        && sql.contains("not attempt.final_frame_observed")));

    java.util.List.of(
            new JdbcTargetEvidenceTerminalActivities.AgentRunReceiptAuthority(
                accepted.logicalRunId(), null, false, 2, 0, 1, 0, 0, 0, 2),
            new JdbcTargetEvidenceTerminalActivities.AgentRunReceiptAuthority(
                accepted.logicalRunId(), "agent-attempt:wrong", true, 2, 0, 1, 0, 1, 0, 2),
            new JdbcTargetEvidenceTerminalActivities.AgentRunReceiptAuthority(
                accepted.logicalRunId(), accepted.committedAttemptId(), true, 2, 1, 1, 1, 0, 0, 0),
            new JdbcTargetEvidenceTerminalActivities.AgentRunReceiptAuthority(
                accepted.logicalRunId(), accepted.committedAttemptId(), true, 2, 1, 1, 1, 1, 0, 0),
            new JdbcTargetEvidenceTerminalActivities.AgentRunReceiptAuthority(
                accepted.logicalRunId(), accepted.committedAttemptId(), true, 2, 1, 1, 1, 2, 2, 0),
            new JdbcTargetEvidenceTerminalActivities.AgentRunReceiptAuthority(
                accepted.logicalRunId(), accepted.committedAttemptId(), true, 2, 1, 2, 1, 1, 1, 0),
            new JdbcTargetEvidenceTerminalActivities.AgentRunReceiptAuthority(
                accepted.logicalRunId(), accepted.committedAttemptId(), true, 2, 1, 1, 1, 1, 1, 1),
            new JdbcTargetEvidenceTerminalActivities.AgentRunReceiptAuthority(
                "target-evidence-run:unrelated",
                "agent-attempt:unrelated",
                false,
                1,
                0,
                0,
                0,
                0,
                0,
                1))
        .forEach(
            invalid ->
                assertThatThrownBy(
                        () ->
                            JdbcTargetEvidenceTerminalActivities
                                .requireAgentRunReceiptAuthority(invalid))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                        "all EVIDENCE_SUBMIT AgentRun formal receipts are required"));
  }

  @Test
  void storedTerminalReplayRebuildsEveryDurableFieldAndCanonicalBytes() {
    JdbcTargetEvidenceTerminalActivities subject = subject();
    var request = request();
    Instant committedAt = Instant.parse("2026-07-30T00:30:00Z");
    Instant deadline = committedAt.plus(java.time.Duration.ofHours(3));
    String requestHash = subject.hash(request);
    String seed = ContractJson.sha256Hex(new ObjectMapper().findAndRegisterModules().valueToTree(
        java.util.List.of(request.start().caseId(), request.start().roomEpoch(), requestHash)));
    String receiptId = "EVDTERM_" + seed.substring(0, 32);
    String hearingRoomId = "ROOM_HEARING_" + seed.substring(0, 28);
    String receiptHash = subject.receiptHash(
        request, "DOSSIER_E2E", 3, hearingRoomId, deadline, 9, 6);
    byte[] canonical = subject.receiptCanonical(
        receiptId, receiptHash, requestHash, request, "DOSSIER_E2E", 3,
        hearingRoomId, deadline, 9, 6, committedAt);
    var stored = new JdbcTargetEvidenceTerminalActivities.Stored(
        receiptId, receiptHash, requestHash, request.start().tenantSurrogate(),
        request.start().caseId(), request.start().roomEpoch(), request.start().fencingToken(),
        request.initiatorCompletionId(), request.respondentCompletionId(), "DOSSIER_E2E", 3,
        hearingRoomId, deadline, 9, 6, canonical, committedAt);

    assertThatCode(() -> subject.requireStoredReplay(request, stored)).doesNotThrowAnyException();

    var drifted = new JdbcTargetEvidenceTerminalActivities.Stored(
        receiptId, "f".repeat(64), requestHash, request.start().tenantSurrogate(),
        request.start().caseId(), request.start().roomEpoch(), request.start().fencingToken(),
        request.initiatorCompletionId(), request.respondentCompletionId(), "DOSSIER_E2E", 3,
        hearingRoomId, deadline, 9, 6, canonical, committedAt);
    assertThatThrownBy(() -> subject.requireStoredReplay(request, drifted))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("replay drifted");
  }

  private static final class DossierVisibilityFixture implements AutoCloseable {

    private static final String CASE_ID = "CASE_DOSSIER_VISIBILITY";
    private static final String EVIDENCE_ID = "EVIDENCE_DOSSIER_VISIBILITY";
    private static final String ACTOR_ID = "target-e2e-terminal";

    private final JdbcTemplate jdbc;
    private final EntityManagerFactory entityManagerFactory;
    private final TransactionTemplate transaction;
    private final EvidenceDossierFreezer freezer;

    private DossierVisibilityFixture() {
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(
              "jdbc:h2:mem:evidence_dossier_visibility_"
                  + System.nanoTime()
                  + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
              "sa",
              "");
      this.jdbc = new JdbcTemplate(dataSource);
      createSchema();

      HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
      vendorAdapter.setGenerateDdl(false);
      LocalContainerEntityManagerFactoryBean factory =
          new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(dataSource);
      factory.setJpaVendorAdapter(vendorAdapter);
      factory.setPackagesToScan(
          EvidenceDossierEntity.class.getPackageName(),
          EvidenceDossierItemEntity.class.getPackageName());
      factory.setPersistenceUnitName("evidence-dossier-visibility");
      factory.setJpaPropertyMap(
          java.util.Map.of(
              "hibernate.hbm2ddl.auto", "none",
              "hibernate.dialect", "org.hibernate.dialect.H2Dialect",
              "hibernate.show_sql", "false"));
      factory.afterPropertiesSet();
      this.entityManagerFactory = factory.getObject();
      this.transaction =
          new TransactionTemplate(new JpaTransactionManager(entityManagerFactory));

      var entityManager =
          SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
      JpaRepositoryFactory repositories = new JpaRepositoryFactory(entityManager);
      EvidenceDossierRepository dossierRepository =
          repositories.getRepository(EvidenceDossierRepository.class);
      EvidenceDossierItemRepository dossierItemRepository =
          repositories.getRepository(EvidenceDossierItemRepository.class);
      EvidenceItemRepository evidenceRepository = mock(EvidenceItemRepository.class);
      EvidenceVerificationRepository verificationRepository =
          mock(EvidenceVerificationRepository.class);

      EvidenceItemEntity evidence =
          EvidenceItemEntity.uploaded(
              EVIDENCE_ID,
              CASE_ID,
              "DOSSIER_COLLECTING",
              "PAYMENT_RECORD",
              "USER_UPLOAD",
              "USER",
              "USER_DOSSIER_VISIBILITY",
              "evidence",
              "visibility/payment-record.png",
              "a".repeat(64),
              "退款记录.png",
              "image/png",
              128L,
              "PARTIES",
              OffsetDateTime.ofInstant(
                  Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));
      evidence.markSubmittedForParties(
          "EVIDENCE_BATCH_VISIBILITY",
          OffsetDateTime.ofInstant(
              Instant.parse("2026-08-16T00:01:00Z"), ZoneOffset.UTC),
          ACTOR_ID);
      when(evidenceRepository
              .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(CASE_ID))
          .thenReturn(java.util.List.of(evidence));
      when(verificationRepository
              .findTopByEvidenceIdOrderByVerificationVersionDesc(EVIDENCE_ID))
          .thenReturn(java.util.Optional.empty());
      this.freezer =
          new EvidenceDossierFreezer(
              dossierRepository,
              dossierItemRepository,
              evidenceRepository,
              verificationRepository,
              new ObjectMapper().findAndRegisterModules(),
              Clock.fixed(Instant.parse("2026-08-16T00:02:00Z"), ZoneOffset.UTC));
    }

    private byte[] finalizeOnce() {
      return transaction.execute(
          ignored -> {
            EvidenceDossierEntity frozen = freezer.freeze(CASE_ID, 1, ACTOR_ID);
            byte[] receipt =
                ("target-e2e-evidence-terminal:" + frozen.getId())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(
                    jdbc.queryForObject(
                        "select count(*) from evidence_dossier_item where dossier_id = ?",
                        Long.class,
                        frozen.getId()))
                .isEqualTo(1L);
            jdbc.update(
                "merge into case_timeline_event key(id) values (?, ?, ?)",
                "EVENT_DOSSIER_VISIBILITY",
                CASE_ID,
                frozen.getId());
            jdbc.update(
                "merge into target_e2e_evidence_terminal_receipt key(id) values (?, ?, ?)",
                "RECEIPT_DOSSIER_VISIBILITY",
                frozen.getId(),
                receipt);
            jdbc.update(
                "merge into case_room key(id) values (?, ?, ?)",
                "ROOM_HEARING_DOSSIER_VISIBILITY",
                CASE_ID,
                "HEARING");
            return receipt;
          });
    }

    private void appendTimelineForMissingDossier() {
      transaction.executeWithoutResult(
          ignored ->
              jdbc.update(
                  "insert into case_timeline_event (id, case_id, dossier_id) values (?, ?, ?)",
                  "EVENT_MISSING_DOSSIER",
                  CASE_ID,
                  "DOSSIER_MISSING"));
    }

    private long count(String table) {
      return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private String dossierStatus() {
      return jdbc.queryForObject(
          "select dossier_status from evidence_dossier where case_id = ?",
          String.class,
          CASE_ID);
    }

    private void createSchema() {
      jdbc.execute(
          """
          create table evidence_dossier (
              id varchar(64) primary key,
              case_id varchar(64) not null,
              dossier_status varchar(32) not null,
              dossier_version integer not null,
              summary_json json not null,
              timeline_json json not null,
              matrix_summary_json json not null,
              built_at timestamp with time zone,
              created_at timestamp with time zone not null,
              updated_at timestamp with time zone not null,
              deleted_at timestamp with time zone,
              created_by varchar(128) not null,
              updated_by varchar(128) not null,
              unique (case_id, dossier_version)
          )
          """);
      jdbc.execute(
          """
          create table evidence_dossier_item (
              id varchar(64) primary key,
              case_id varchar(64) not null,
              dossier_id varchar(64) not null,
              evidence_id varchar(64) not null,
              sequence_no integer not null,
              evidence_snapshot_json json not null,
              created_at timestamp with time zone not null,
              created_by varchar(128) not null,
              constraint fk_dossier_item_dossier
                  foreign key (dossier_id) references evidence_dossier(id)
          )
          """);
      jdbc.execute(
          """
          create table case_timeline_event (
              id varchar(64) primary key,
              case_id varchar(64) not null,
              dossier_id varchar(64) not null,
              constraint fk_timeline_dossier
                  foreign key (dossier_id) references evidence_dossier(id)
          )
          """);
      jdbc.execute(
          """
          create table target_e2e_evidence_terminal_receipt (
              id varchar(64) primary key,
              dossier_id varchar(64) not null,
              receipt_bytes varbinary not null,
              constraint fk_terminal_receipt_dossier
                  foreign key (dossier_id) references evidence_dossier(id)
          )
          """);
      jdbc.execute(
          """
          create table case_room (
              id varchar(64) primary key,
              case_id varchar(64) not null,
              room_type varchar(32) not null,
              unique (case_id, room_type)
          )
          """);
    }

    @Override
    public void close() {
      entityManagerFactory.close();
    }
  }

  private static EvidenceRoomStart start() {
    return new EvidenceRoomStart(
        "evidence-room-start.v1", "tenant-e2e", "CASE_E2E", "ROOM_EVIDENCE", 4, 9,
        "USER_E2E", "MERCHANT_E2E", Instant.parse("2026-07-28T00:00:00Z"),
        Instant.parse("2026-07-28T01:00:00Z"), 1, 6, 3, "local-control-build",
        ExecutionLane.TARGET_E2E_CANDIDATE);
  }

  private static TargetEvidenceTerminalActivities.TerminalRequest request() {
    return new TargetEvidenceTerminalActivities.TerminalRequest(
        start(),
        8,
        5,
        USER_COMMAND_ID,
        MERCHANT_COMMAND_ID,
        canonicalWorkflowId(),
        WORKFLOW_RUN_ID);
  }

  private static TargetEvidenceTerminalActivities.TerminalRequest resetRequest(
      String currentRunId, String durableRunId) {
    try {
      var constructor =
          TargetEvidenceTerminalActivities.TerminalRequest.class.getDeclaredConstructor(
              EvidenceRoomStart.class,
              long.class,
              long.class,
              String.class,
              String.class,
              String.class,
              String.class,
              String.class);
      return constructor.newInstance(
          start(),
          8L,
          5L,
          USER_COMMAND_ID,
          MERCHANT_COMMAND_ID,
          canonicalWorkflowId(),
          currentRunId,
          durableRunId);
    } catch (NoSuchMethodException ignored) {
      return new TargetEvidenceTerminalActivities.TerminalRequest(
          start(),
          8,
          5,
          USER_COMMAND_ID,
          MERCHANT_COMMAND_ID,
          canonicalWorkflowId(),
          durableRunId);
    } catch (java.lang.reflect.InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException runtimeFailure) {
        throw runtimeFailure;
      }
      throw new AssertionError("reset terminal request construction failed", failure);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("reset terminal request construction failed", failure);
    }
  }

  private static String durableWorkflowRunId(
      TargetEvidenceTerminalActivities.TerminalRequest request) {
    try {
      return (String)
          request.getClass().getMethod("durableWorkflowRunId").invoke(request);
    } catch (NoSuchMethodException ignored) {
      return request.workflowRunId();
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("durable workflow run identity lookup failed", failure);
    }
  }

  private static String roomAuthorityRunId(
      JdbcTargetEvidenceTerminalActivities.WorkflowIdentity identity) {
    try {
      return (String)
          identity.getClass().getDeclaredMethod("roomAuthorityRunId").invoke(identity);
    } catch (NoSuchMethodException ignored) {
      return identity.workflowRunId();
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("room authority run identity lookup failed", failure);
    }
  }

  private static String canonicalWorkflowId() {
    return CaseProcessWorkflowProtocol.roomWorkflowId("CASE_E2E", RoomType.EVIDENCE, 4);
  }

  private static String partyCompletionResultHash(
      String completionId,
      String requestHash,
      long expectedProcessRevision,
      long expectedRoomRevision) {
    return ContractJson.sha256Hex(
        new ObjectMapper()
            .valueToTree(
                java.util.List.of(
                    "target-e2e-evidence-party-receipt.v1",
                    completionId,
                    requestHash,
                    expectedProcessRevision,
                    expectedRoomRevision)));
  }

  private static JdbcTargetEvidenceTerminalActivities subject() {
    EvidenceDossierFreezer dossierFreezer = mock(EvidenceDossierFreezer.class);
    when(dossierFreezer.targetVersion("CASE_E2E")).thenReturn(3);
    return new JdbcTargetEvidenceTerminalActivities(
        mock(javax.sql.DataSource.class),
        mock(TransactionTemplate.class),
        mock(TargetE2eActivationLifecycleStore.class),
        dossierFreezer,
        mock(RoomEpochAllocator.class),
        new ObjectMapper().findAndRegisterModules(),
        Clock.systemUTC());
  }
}
