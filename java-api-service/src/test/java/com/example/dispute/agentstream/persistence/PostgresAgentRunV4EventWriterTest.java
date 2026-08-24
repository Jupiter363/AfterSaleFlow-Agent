package com.example.dispute.agentstream.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV4EventWriter;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV4EventWriter.EventWriteCommand;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PostgresAgentRunV4EventWriterTest {

    @AfterEach
    void clearTransactionMarker() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void rejectsAnAppendWithoutTheCallerTechnicalTransaction() {
        PostgresAgentRunV4EventWriter writer = new PostgresAgentRunV4EventWriter(
                mock(NamedParameterJdbcTemplate.class), new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(() -> writer.appendInCurrentTransaction(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("caller technical transaction");
    }

    @Test
    void writesOneV4SourceAndDeliveryEventInsideTheCallerTransaction() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        when(jdbc.query(
                        anyString(),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getBoolean("was_inserted")).thenReturn(true);
                    when(resultSet.getLong("highest_contiguous_sequence_no")).thenReturn(4L);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        TransactionSynchronizationManager.setActualTransactionActive(true);
        PostgresAgentRunV4EventWriter writer = new PostgresAgentRunV4EventWriter(
                jdbc, new ObjectMapper().findAndRegisterModules());

        var receipt = writer.appendInCurrentTransaction(command());

        assertThat(receipt.eventId()).isEqualTo("ARSE4_EVENT_1");
        assertThat(receipt.eventSha256()).matches("[0-9a-f]{64}");
        assertThat(receipt.canonicalEventJson()).contains("agent-stream.v4");
        assertThat(receipt.durableHighWatermark()).isEqualTo(4L);
        verify(jdbc).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void rejectsASequenceThatWasAlreadyClaimedBeforeIngressAdmission() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        PostgresAgentRunV4EventWriter writer = new PostgresAgentRunV4EventWriter(
                jdbc, new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(() -> writer.appendInCurrentTransaction(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already bound");
    }

    @Test
    void returnsTheExactPersistedTerminalReceiptOnActivityReplay() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        when(jdbc.query(
                        anyString(),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getBoolean("was_inserted")).thenReturn(false);
                    when(resultSet.getLong("highest_contiguous_sequence_no")).thenReturn(5L);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        TransactionSynchronizationManager.setActualTransactionActive(true);
        PostgresAgentRunV4EventWriter writer = new PostgresAgentRunV4EventWriter(
                jdbc, new ObjectMapper().findAndRegisterModules());

        var receipt = writer.appendOrLoadExactTerminalInCurrentTransaction(finalCommand());

        assertThat(receipt.inserted()).isFalse();
        assertThat(receipt.eventId()).isEqualTo("ARSE4_FINAL_1");
        assertThat(receipt.durableHighWatermark()).isEqualTo(5L);
        assertThat(receipt.canonicalEventJson())
                .contains("final_receipt_id")
                .contains("final_result_hash");
        verify(jdbc, times(1)).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void rejectsTerminalReplayWhenTheDurableWatermarkHasAdvanced() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        when(jdbc.query(
                        anyString(),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getBoolean("was_inserted")).thenReturn(false);
                    when(resultSet.getLong("highest_contiguous_sequence_no")).thenReturn(6L);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        TransactionSynchronizationManager.setActualTransactionActive(true);
        PostgresAgentRunV4EventWriter writer = new PostgresAgentRunV4EventWriter(
                jdbc, new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(() -> writer.appendOrLoadExactTerminalInCurrentTransaction(
                        finalCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact durable high-watermark");
    }

    private static EventWriteCommand command() {
        return new EventWriteCommand(
                "ARSE4_EVENT_1",
                "RUN_1",
                "ATTEMPT_1",
                4,
                AgentStreamEventV4.EventType.PUBLIC_FRAME_START,
                Audience.USER,
                Instant.parse("2026-08-24T08:00:00Z"),
                startPayload(),
                "user-local",
                "[\"user-local\"]");
    }

    private static EventWriteCommand finalCommand() {
        return new EventWriteCommand(
                "ARSE4_FINAL_1",
                "RUN_1",
                "ATTEMPT_1",
                5,
                AgentStreamEventV4.EventType.FINAL,
                Audience.USER,
                Instant.parse("2026-08-24T08:00:05Z"),
                AgentStreamEventV4.Payload.finalPayload(
                        "IPFTR_1", "f".repeat(64)),
                "user-local",
                "[\"user-local\"]");
    }

    private static AgentStreamEventV4.Payload startPayload() {
        return new AgentStreamEventV4.Payload(
                "FRAME_DIALOGUE_1",
                AgentStreamEventV4.FrameType.DIALOGUE_FRAME,
                1,
                "FRAME_SET_RECEIPT_1",
                "intake-projection-registry.v1",
                AgentStreamEventV4.DeliveryClass.DURABLE_CONTROL,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
