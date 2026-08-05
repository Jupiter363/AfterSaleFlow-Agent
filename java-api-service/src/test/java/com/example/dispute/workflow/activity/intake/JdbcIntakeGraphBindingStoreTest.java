package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.room.infrastructure.persistence.JdbcIntakeGraphBindingStore;
import com.example.dispute.workflow.application.intake.IntakeEventReference;
import com.example.dispute.workflow.application.intake.IntakeGraphBindingConflictException;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.application.intake.IntakeTurnEventPublisher.SourceType;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

@ExtendWith(MockitoExtension.class)
class JdbcIntakeGraphBindingStoreTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private JdbcIntakeGraphBindingStore store;

    @BeforeEach
    void setUp() {
        store = new JdbcIntakeGraphBindingStore(jdbc);
    }

    @Test
    void newRegistrationRequiresActiveEpochAndExactPrivateSessionAuthority() {
        when(jdbc.queryForObject(
                        anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(1);
        when(jdbc.update(
                        contains("insert into case_intake_graph_thread_binding"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(1);

        assertThat(store.register(IntakeTestFixtures.binding()).created()).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2))
                .queryForObject(
                        sql.capture(), any(MapSqlParameterSource.class), eq(Integer.class));
        String authoritySql = String.join(" ", sql.getAllValues())
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        assertThat(authoritySql)
                .contains("selection_schema_version = 'room-epoch-selection.v2'")
                .contains("lifecycle_status = 'active'")
                .contains("join case_access_session access")
                .contains("access.id = session.access_session_id")
                .contains("session.tenant_id = access.tenant_id")
                .doesNotContain("session.tenant_id = :tenantsurrogate")
                .doesNotContain("access.tenant_id = :tenantsurrogate")
                .contains("access.case_id = :caseid")
                .contains("access.actor_id = :actorid")
                .contains("access.actor_role = :actorrole")
                .contains("access.status = 'active'")
                .contains("when :actorrole = 'user' then 'party_user'")
                .contains("when :actorrole = 'merchant' then 'party_merchant'")
                .contains("access.permission_scopes_json @> cast(:requiredaccessscopes as jsonb)")
                .contains("session.prompt_profile_id = :promptversion")
                .contains("session.status = 'active'");
    }

    @Test
    void revokedOrInactiveAccessSessionCannotIssueAPrivateThread() {
        when(jdbc.queryForObject(
                        anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenAnswer(
                        invocation ->
                                invocation.<String>getArgument(0)
                                                .contains("join case_access_session access")
                                        ? 0
                                        : 1);

        assertThatThrownBy(() -> store.register(IntakeTestFixtures.binding()))
                .isInstanceOf(IntakeGraphBindingConflictException.class)
                .hasMessageContaining("session");
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void exactInitialReferenceReplaysWithoutAnotherBinding() {
        IntakeGraphThreadBinding binding = IntakeTestFixtures.binding();
        IntakeSnapshotReference snapshot = IntakeTestFixtures.snapshot(binding);
        stubLockedThread(binding);
        when(jdbc.update(
                        contains("insert into case_intake_snapshot_binding"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(0);
        when(jdbc.query(
                        contains("binding_type = 'INITIAL'"),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<IntakeSnapshotReference>>any()))
                .thenReturn(List.of(snapshot));

        var receipt = store.bindInitialSnapshot(snapshot);

        assertThat(receipt.created()).isFalse();
        assertThat(receipt.value()).isEqualTo(snapshot);
    }

    @Test
    void rejectsCrossScopeSnapshotBeforeAttemptingTheInsert() {
        IntakeGraphThreadBinding binding = IntakeTestFixtures.binding();
        IntakeSnapshotReference valid = IntakeTestFixtures.snapshot(binding);
        IntakeSnapshotReference crossScope = new IntakeSnapshotReference(
                valid.bindingId(),
                valid.threadRegistrationId(),
                valid.tenantSurrogate(),
                valid.caseId(),
                valid.roomEpoch(),
                valid.fencingToken(),
                valid.threadId(),
                "a".repeat(64),
                valid.agentSessionId(),
                valid.payloadRef(),
                valid.objectVersion(),
                valid.domainRevision(),
                valid.roomRevision(),
                valid.projectionRevision(),
                valid.initialLastSequence(),
                valid.createdAt());
        stubLockedThread(binding);

        assertThatThrownBy(() -> store.bindInitialSnapshot(crossScope))
                .isInstanceOf(IntakeGraphBindingConflictException.class)
                .hasMessageContaining("scope");
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void rejectsCrossAudienceEventBeforeAttemptingTheInsert() {
        IntakeGraphThreadBinding binding = IntakeTestFixtures.binding();
        IntakeEventReference valid = IntakeTestFixtures.event(binding);
        IntakeEventReference crossAudience = new IntakeEventReference(
                valid.bindingId(),
                valid.threadRegistrationId(),
                valid.eventId(),
                valid.messageId(),
                valid.tenantSurrogate(),
                valid.caseId(),
                valid.roomEpoch(),
                valid.fencingToken(),
                valid.threadId(),
                valid.actorScopeHash(),
                valid.agentSessionId(),
                valid.payloadRef(),
                valid.objectVersion(),
                valid.sequenceNo(),
                valid.domainRevision(),
                Audience.MERCHANT,
                valid.occurredAt(),
                valid.createdAt());
        stubLockedThread(binding);

        assertThatThrownBy(() -> store.bindEvent(crossAudience))
                .isInstanceOf(IntakeGraphBindingConflictException.class)
                .hasMessageContaining("audience");
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 3})
    void firstEventRejectsDuplicateOrGapAgainstTheInitialSequenceWatermark(long sequence) {
        IntakeGraphThreadBinding binding = IntakeTestFixtures.binding();
        IntakeEventReference event = eventAtSequence(binding, sequence);
        stubLockedThread(binding);
        stubInitialSequence(1);

        assertThatThrownBy(() -> store.bindEvent(event))
                .isInstanceOf(IntakeGraphBindingConflictException.class)
                .hasMessageContaining("next ordered reference");
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void nextFirstEventInsertsOnceAndThenReplaysExactly() {
        IntakeGraphThreadBinding binding = IntakeTestFixtures.binding();
        IntakeEventReference event = eventAtSequence(binding, 2);
        stubLockedThread(binding);
        stubInitialSequence(1);
        when(jdbc.query(
                        contains("binding_type = 'EVENT'"),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<IntakeEventReference>>any()))
                .thenReturn(List.of())
                .thenReturn(List.of(event));
        when(jdbc.update(
                        contains("insert into case_intake_snapshot_binding"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(1);

        var created = store.bindEvent(event);
        var replayed = store.bindEvent(event);

        assertThat(created.created()).isTrue();
        assertThat(replayed.created()).isFalse();
        assertThat(replayed.value()).isEqualTo(event);
        verify(jdbc, times(1))
                .update(
                        contains("insert into case_intake_snapshot_binding"),
                        any(MapSqlParameterSource.class));
    }

    @Test
    void respondentOpeningSourceTypeIsWrittenSelectedAndReplayedExactly() {
        IntakeGraphThreadBinding binding = IntakeTestFixtures.binding();
        IntakeEventReference event =
                eventAtSequence(binding, 2, SourceType.RESPONDENT_OPENING);
        stubLockedThread(binding);
        stubInitialSequence(1);
        when(jdbc.query(
                        contains("binding_type = 'EVENT'"),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<IntakeEventReference>>any()))
                .thenReturn(List.of())
                .thenReturn(List.of(event));
        when(jdbc.update(
                        contains("insert into case_intake_snapshot_binding"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(1);

        var created = store.bindEvent(event);
        var replayed = store.bindEvent(event);

        assertThat(created.created()).isTrue();
        assertThat(replayed.created()).isFalse();
        assertThat(replayed.value()).isEqualTo(event);
        assertThat(sourceTypeOf(replayed.value())).isEqualTo(SourceType.RESPONDENT_OPENING);

        ArgumentCaptor<String> insertSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> insertParameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc)
                .update(
                        insertSql.capture(),
                        insertParameters.capture());
        assertThat(insertSql.getValue()).contains("event_source_type");
        assertThat(insertParameters.getValue().getValue("eventSourceType"))
                .isEqualTo(SourceType.RESPONDENT_OPENING.name());

        ArgumentCaptor<String> selectSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2))
                .query(
                        selectSql.capture(),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<IntakeEventReference>>any());
        assertThat(selectSql.getAllValues())
                .allSatisfy(sql -> assertThat(sql).contains("event_source_type"));
    }

    @Test
    void legacyEventAndInitialSnapshotPersistNoOpeningAuthority() {
        IntakeGraphThreadBinding binding = IntakeTestFixtures.binding();
        assertThat(sourceTypeOf(eventAtSequence(binding, 2))).isNull();

        IntakeSnapshotReference snapshot = IntakeTestFixtures.snapshot(binding);
        stubLockedThread(binding);
        when(jdbc.update(
                        contains("insert into case_intake_snapshot_binding"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(1);

        assertThat(store.bindInitialSnapshot(snapshot).created()).isTrue();

        ArgumentCaptor<String> insertSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> insertParameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(insertSql.capture(), insertParameters.capture());
        assertThat(insertSql.getValue()).doesNotContain(SourceType.RESPONDENT_OPENING.name());
        if (insertParameters.getValue().hasValue("eventSourceType")) {
            assertThat(insertParameters.getValue().getValue("eventSourceType")).isNull();
        }
    }

    @Test
    void existingEventLedgerRejectsALaterGap() {
        IntakeGraphThreadBinding binding = IntakeTestFixtures.binding();
        IntakeEventReference gap = eventAtSequence(binding, 4);
        stubLockedThread(binding);
        stubInitialSequence(1);
        when(jdbc.queryForObject(
                        contains("select max(event_sequence)"), anyMap(), eq(Long.class)))
                .thenReturn(2L);

        assertThatThrownBy(() -> store.bindEvent(gap))
                .isInstanceOf(IntakeGraphBindingConflictException.class)
                .hasMessageContaining("next ordered reference");
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void emptySnapshotWatermarkAcceptsSequenceOne() {
        IntakeGraphThreadBinding binding = IntakeTestFixtures.binding();
        IntakeEventReference event = eventAtSequence(binding, 1);
        stubLockedThread(binding);
        stubInitialSequence(0);
        when(jdbc.update(
                        contains("insert into case_intake_snapshot_binding"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(1);

        assertThat(store.bindEvent(event).created()).isTrue();
    }

    @Test
    void lockedThreadStateReturnsTheOneExistingInitialSnapshotWithoutCreatingAnother() {
        IntakeGraphThreadBinding binding = IntakeTestFixtures.binding();
        IntakeSnapshotReference snapshot = IntakeTestFixtures.snapshot(binding);
        stubLockedThread(binding);
        when(jdbc.query(
                        contains("binding_type = 'INITIAL'"),
                        anyMap(),
                        org.mockito.ArgumentMatchers.<RowMapper<IntakeSnapshotReference>>any()))
                .thenReturn(List.of(snapshot));

        var state = store.lockThreadSnapshotState(binding.registration().registrationId());

        assertThat(state.thread()).isEqualTo(binding);
        assertThat(state.initialSnapshot()).contains(snapshot);
    }

    @Test
    void eventAllocationReplaysTheSameMessageOrAllocatesTheNextSequenceUnderThreadLock() {
        IntakeGraphThreadBinding binding = IntakeTestFixtures.binding();
        IntakeEventReference replayed = eventAtSequence(binding, 2);
        stubLockedThread(binding);
        stubInitialSequence(1);
        when(jdbc.query(
                        contains("binding_type = 'EVENT'"),
                        anyMap(),
                        org.mockito.ArgumentMatchers.<RowMapper<IntakeEventReference>>any()))
                .thenReturn(List.of(replayed))
                .thenReturn(List.of());
        when(jdbc.queryForObject(
                        contains("select max(event_sequence)"), anyMap(), eq(Long.class)))
                .thenReturn(2L);

        var sameMessage = store.allocateEvent(
                binding.registration().registrationId(), replayed.eventId(), replayed.messageId());
        var nextMessage = store.allocateEvent(
                binding.registration().registrationId(), "EVENT_P4_SEQUENCE_3", "MESSAGE_P4_SEQUENCE_3");

        assertThat(sameMessage.sequenceNo()).isEqualTo(2);
        assertThat(sameMessage.existing()).contains(replayed);
        assertThat(nextMessage.sequenceNo()).isEqualTo(3);
        assertThat(nextMessage.existing()).isEmpty();
        verify(jdbc, times(2)).query(
                contains("for update"), anyMap(),
                org.mockito.ArgumentMatchers.<RowMapper<IntakeGraphThreadBinding>>any());
    }

    private void stubLockedThread(IntakeGraphThreadBinding binding) {
        when(jdbc.query(
                        contains("for update"),
                        anyMap(),
                        org.mockito.ArgumentMatchers.<RowMapper<IntakeGraphThreadBinding>>any()))
                .thenReturn(List.of(binding));
    }

    private void stubInitialSequence(long sequence) {
        when(jdbc.queryForList(
                        contains("select initial_last_sequence"),
                        anyMap(),
                        eq(Long.class)))
                .thenReturn(List.of(sequence));
    }

    private static IntakeEventReference eventAtSequence(
            IntakeGraphThreadBinding binding, long sequence) {
        var registration = binding.registration();
        String eventId = "EVENT_P4_SEQUENCE_" + sequence;
        return new IntakeEventReference(
                eventId,
                registration.registrationId(),
                eventId,
                "MESSAGE_P4_SEQUENCE_" + sequence,
                registration.tenantSurrogate(),
                registration.caseId(),
                registration.roomEpoch(),
                binding.fencingToken(),
                registration.threadId(),
                registration.actorScopeHash(),
                registration.agentSessionId(),
                new RoomGraphCommand.SnapshotRef(
                        eventId,
                        "intake-turn-event.v2",
                        "urn:intake:event:" + eventId,
                        "5".repeat(64),
                        512),
                "version-1",
                sequence,
                5,
                Audience.USER,
                IntakeTestFixtures.ISSUED_AT.plusSeconds(sequence * 60),
                IntakeTestFixtures.ISSUED_AT.plusSeconds(sequence * 60 + 1));
    }

    private static IntakeEventReference eventAtSequence(
            IntakeGraphThreadBinding binding, long sequence, SourceType sourceType) {
        return withSourceType(eventAtSequence(binding, sequence), sourceType);
    }

    private static IntakeEventReference withSourceType(
            IntakeEventReference legacy, SourceType sourceType) {
        return new IntakeEventReference(
                legacy.bindingId(),
                legacy.threadRegistrationId(),
                legacy.eventId(),
                legacy.messageId(),
                legacy.tenantSurrogate(),
                legacy.caseId(),
                legacy.roomEpoch(),
                legacy.fencingToken(),
                legacy.threadId(),
                legacy.actorScopeHash(),
                legacy.agentSessionId(),
                legacy.payloadRef(),
                legacy.objectVersion(),
                legacy.sequenceNo(),
                legacy.domainRevision(),
                legacy.audience(),
                legacy.occurredAt(),
                legacy.createdAt(),
                sourceType);
    }

    private static SourceType sourceTypeOf(IntakeEventReference reference) {
        return reference.sourceType();
    }
}
