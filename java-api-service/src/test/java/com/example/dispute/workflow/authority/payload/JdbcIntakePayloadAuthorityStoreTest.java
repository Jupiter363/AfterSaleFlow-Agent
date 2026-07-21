package com.example.dispute.workflow.authority.payload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.authority.epoch.EpochPartyAuthority.Party;
import com.example.dispute.workflow.application.authority.payload.IntakeAuthorityRoute;
import com.example.dispute.workflow.application.authority.payload.IntakeCommandAuthority;
import com.example.dispute.workflow.application.authority.payload.IntakeCommandOutboxBinding;
import com.example.dispute.workflow.application.authority.payload.IntakePayloadAuthority;
import com.example.dispute.workflow.application.authority.payload.IntakePayloadAuthorityStore.Acceptance;
import com.example.dispute.workflow.application.authority.payload.IntakePayloadSourceKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator.LockRequest;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator.LockedRow;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator.LockedRows;
import com.example.dispute.workflow.infrastructure.persistence.authority.payload.IntakePayloadAuthorityConflictException;
import com.example.dispute.workflow.infrastructure.persistence.authority.payload.JdbcIntakePayloadAuthorityStore;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcIntakePayloadAuthorityStoreTest {

    private static final String HASH = "a".repeat(64);
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 22, 0, 0, 0, 0, ZoneOffset.UTC);

    private NamedParameterJdbcTemplate jdbc;
    private JdbcIntakePayloadAuthorityStore store;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        EpochAuthorityLockCoordinator locks = mock(EpochAuthorityLockCoordinator.class);
        when(locks.lockForShare(any(LockRequest.class))).thenReturn(new LockedRows(
                List.of(new LockedRow("ACCESS-1", "ACTIVE")),
                List.of(new LockedRow("AGENT-1", "ACTIVE")),
                List.of(new LockedRow("REG-1", "REGISTERED")),
                false));
        when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(1);
        when(jdbc.queryForObject(
                        any(String.class), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(1);
        store = new JdbcIntakePayloadAuthorityStore(jdbc, locks);
    }

    @Test
    void bindsTheCompletePayloadReferenceAndWritesTheThreeRowsInOrder() {
        store.accept(acceptance());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, times(4)).queryForObject(sql.capture(), parameters.capture(), eq(Integer.class));

        int shapeQuery = indexOf(sql.getAllValues(), "payload_schema_version = :payloadSchemaVersion");
        MapSqlParameterSource shapeParameters = parameters.getAllValues().get(shapeQuery);
        IntakePayloadAuthority payload = acceptance().payload();
        assertThat(shapeParameters.getValue("payloadSchemaVersion")).isEqualTo(payload.schemaVersion());
        assertThat(shapeParameters.getValue("payloadUri")).isEqualTo(payload.objectUri());
        assertThat(shapeParameters.getValue("payloadSha256")).isEqualTo(payload.contentSha256());
        assertThat(shapeParameters.getValue("payloadSizeBytes")).isEqualTo(payload.sizeBytes());

        String outboxReplayQuery = sql.getAllValues().get(indexOf(sql.getAllValues(), "from case_command_outbox"));
        assertThat(outboxReplayQuery).doesNotContain("available_at = :availableAt");

        InOrder writes = inOrder(jdbc);
        writes.verify(jdbc).update(
                contains("insert into case_intake_command_payload_authority"),
                any(MapSqlParameterSource.class));
        writes.verify(jdbc).update(
                contains("insert into case_intake_command_authority"), any(MapSqlParameterSource.class));
        writes.verify(jdbc).update(
                contains("insert into case_command_outbox"), any(MapSqlParameterSource.class));
    }

    @Test
    void caseCommandPayloadMismatchPreventsAuthorityAndOutboxWrites() {
        when(jdbc.queryForObject(
                        contains("payload_schema_version = :payloadSchemaVersion"),
                        any(MapSqlParameterSource.class),
                        eq(Integer.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> store.accept(acceptance()))
                .isInstanceOf(IntakePayloadAuthorityConflictException.class)
                .hasMessageContaining("case_command");

        verify(jdbc, never()).update(
                contains("insert into case_intake_command_payload_authority"),
                any(MapSqlParameterSource.class));
        verify(jdbc, never()).update(
                contains("insert into case_intake_command_authority"), any(MapSqlParameterSource.class));
        verify(jdbc, never()).update(
                contains("insert into case_command_outbox"), any(MapSqlParameterSource.class));
    }

    private static int indexOf(List<String> values, String needle) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).contains(needle)) {
                return index;
            }
        }
        throw new AssertionError("SQL statement was not captured: " + needle);
    }

    private static Acceptance acceptance() {
        IntakePayloadAuthority payload = new IntakePayloadAuthority(
                "PAYLOAD-1",
                "COMMAND-1",
                route(),
                IntakePayloadSourceKind.EXISTING_PRIVATE_EVENT,
                "EVENT-1",
                "ARTIFACT-1",
                IntakePayloadSourceKind.EXISTING_PRIVATE_EVENT.schemaVersion(),
                "urn:payload:1",
                "VERSION-1",
                HASH,
                1,
                null,
                NOW);
        IntakeCommandAuthority command = new IntakeCommandAuthority(
                "CASE-COMMAND-1",
                "COMMAND-1",
                1,
                CommandType.INTAKE_MESSAGE,
                route(),
                "PAYLOAD-1",
                HASH,
                0,
                IntakeCommandAuthority.ExecutionDisposition.INERT_EXTERNAL_EVENT,
                NOW);
        return new Acceptance(
                payload,
                command,
                new IntakeCommandOutboxBinding(
                        "OUTBOX-1",
                        "CASE-WORKFLOW-1",
                        "CaseProcessWorkflow",
                        "CASE_CONTROL",
                        "COMMAND-1",
                        NOW));
    }

    private static IntakeAuthorityRoute route() {
        return new IntakeAuthorityRoute(
                "PARTY-AUTH-1",
                "EPOCH-1",
                "ACCESS-1",
                "REG-1",
                "TENANT-1",
                "CASE-1",
                0,
                1,
                "grt.v1." + "b".repeat(32),
                "ACTOR-1",
                ActorRole.USER,
                HASH,
                "AGENT-1",
                Party.INITIATOR);
    }
}
