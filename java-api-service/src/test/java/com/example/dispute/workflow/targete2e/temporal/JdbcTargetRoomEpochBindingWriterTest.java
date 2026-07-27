package com.example.dispute.workflow.targete2e.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.epoch.RoomEpochSelection;
import com.example.dispute.workflow.application.epoch.RoomEpochSelection.TargetActivationBinding;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.targete2e.temporal.TargetRoomEpochBindingWriter.BindingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JdbcTargetRoomEpochBindingWriterTest {

    @Test
    void isRegisteredAsTheTransactionalTargetBindingWriter() {
        assertThat(JdbcTargetRoomEpochBindingWriter.class).hasAnnotation(Repository.class);
    }

    @AfterEach
    void clearTransactionMarker() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void writesTheExactActivationAndEpochTupleInsideTheAllocationTransaction() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(contains("insert into target_e2e_room_epoch_binding"),
                        isA(MapSqlParameterSource.class)))
                .thenReturn(1);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

        new JdbcTargetRoomEpochBindingWriter(jdbc).persist(context());

        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(
                contains("insert into target_e2e_room_epoch_binding"), parameters.capture());
        assertThat(parameters.getValue().getValue("epochId")).isEqualTo("epoch-1");
        assertThat(parameters.getValue().getValue("activationId"))
                .isEqualTo("p9act.v1.0123456789abcdef0123456789abcdef");
        assertThat(parameters.getValue().getValue("roomType")).isEqualTo("HEARING");
        assertThat(parameters.getValue().getValue("roomEpoch")).isEqualTo(2L);
        assertThat(parameters.getValue().getValue("roomFencingToken")).isEqualTo(7L);
    }

    @Test
    void rejectsCallsOutsideTheWritableAllocationTransaction() {
        JdbcTargetRoomEpochBindingWriter writer =
                new JdbcTargetRoomEpochBindingWriter(mock(NamedParameterJdbcTemplate.class));

        assertThatThrownBy(() -> writer.persist(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "target room epoch binding requires the active writable allocation transaction");

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> writer.persist(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "target room epoch binding requires the active writable allocation transaction");
    }

    @Test
    void rejectsAnInsertThatDoesNotPersistExactlyOneImmutableBinding() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(contains("insert into target_e2e_room_epoch_binding"),
                        isA(MapSqlParameterSource.class)))
                .thenReturn(0);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> new JdbcTargetRoomEpochBindingWriter(jdbc).persist(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "target room epoch activation binding was not persisted exactly once");
    }

    private static BindingContext context() {
        return new BindingContext(
                "epoch-1",
                "tenant-target",
                "CASE_TARGET_0001",
                RoomType.HEARING,
                2,
                7,
                new RoomEpochSelection(
                        WriterMode.TEMPORAL,
                        RoomEpochSelection.V2,
                        "case-process-contract.v1",
                        "CaseProcessWorkflow",
                        "p9-case-build",
                        TargetTypedRoomProtocol.workflowType(RoomType.HEARING),
                        "p9-control-build",
                        "all-rooms.target-e2e.v1",
                        TargetTypedRoomProtocol.GRAPH_VERSION,
                        "target-e2e-checkpoint.v1",
                        "agent-stream.v2",
                        new TargetActivationBinding(
                                "p9act.v1.0123456789abcdef0123456789abcdef",
                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                "TARGET_E2E_CANDIDATE",
                                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")));
    }
}
