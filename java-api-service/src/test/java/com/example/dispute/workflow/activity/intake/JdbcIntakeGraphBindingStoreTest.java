package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.room.infrastructure.persistence.JdbcIntakeGraphBindingStore;
import com.example.dispute.workflow.application.intake.IntakeEventReference;
import com.example.dispute.workflow.application.intake.IntakeGraphBindingConflictException;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        verify(jdbc, org.mockito.Mockito.times(2))
                .queryForObject(
                        sql.capture(), any(MapSqlParameterSource.class), eq(Integer.class));
        String authoritySql = String.join(" ", sql.getAllValues())
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        assertThat(authoritySql)
                .contains("selection_schema_version = 'room-epoch-selection.v2'")
                .contains("lifecycle_status = 'active'")
                .contains("tenant_id = :tenantsurrogate")
                .contains("actor_id = :actorid")
                .contains("actor_role = :actorrole")
                .contains("prompt_profile_id = :promptversion")
                .contains("status = 'active'");
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

    private void stubLockedThread(IntakeGraphThreadBinding binding) {
        when(jdbc.query(
                        contains("for update"),
                        anyMap(),
                        org.mockito.ArgumentMatchers.<RowMapper<IntakeGraphThreadBinding>>any()))
                .thenReturn(List.of(binding));
    }
}
