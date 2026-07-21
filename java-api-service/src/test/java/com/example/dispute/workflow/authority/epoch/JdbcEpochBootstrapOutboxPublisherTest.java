package com.example.dispute.workflow.authority.epoch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.authority.epoch.EpochAuthorityException;
import com.example.dispute.workflow.application.authority.epoch.EpochBootstrapOutbox;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.JdbcEpochBootstrapOutboxPublisher;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcEpochBootstrapOutboxPublisherTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 22, 0, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void exactRetryReturnsTheOriginalOutboxId() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(0);
        when(jdbc.queryForObject(
                        contains("select count(*)"),
                        any(MapSqlParameterSource.class),
                        eq(Integer.class)))
                .thenReturn(1);

        String id = new JdbcEpochBootstrapOutboxPublisher(jdbc).publish(outbox());

        assertThat(id).isEqualTo("OUTBOX-1");
    }

    @Test
    void sameEpochWithChangedImmutableOutboxTupleConflicts() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(0);
        when(jdbc.queryForObject(
                        contains("select count(*)"),
                        any(MapSqlParameterSource.class),
                        eq(Integer.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> new JdbcEpochBootstrapOutboxPublisher(jdbc).publish(outbox()))
                .isInstanceOf(EpochAuthorityException.class)
                .extracting("reasonCode")
                .isEqualTo("AUTHORITY_BOOTSTRAP_CONFLICT");
    }

    private static EpochBootstrapOutbox outbox() {
        return new EpochBootstrapOutbox(
                "OUTBOX-1",
                "EPOCH-1",
                "TENANT-1",
                "CASE-1",
                RoomType.INTAKE,
                0,
                1,
                WriterMode.SHADOW,
                "CASE-WORKFLOW-1",
                "ROOM-WORKFLOW-1",
                "CaseProcessWorkflow",
                "CASE_CONTROL",
                "UPDATE-1",
                "{}",
                "a".repeat(64),
                NOW);
    }
}
