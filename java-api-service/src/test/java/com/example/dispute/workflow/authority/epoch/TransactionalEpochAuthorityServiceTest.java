package com.example.dispute.workflow.authority.epoch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.casecore.domain.CasePartyAssignment;
import com.example.dispute.config.ActorRole;
import com.example.dispute.workflow.application.authority.epoch.EpochAuthorityException;
import com.example.dispute.workflow.application.authority.epoch.AgentSessionProfileRegistry;
import com.example.dispute.workflow.application.authority.epoch.EpochAuthorityService.BindRequest;
import com.example.dispute.workflow.application.authority.epoch.EpochBootstrapOutbox;
import com.example.dispute.workflow.application.authority.epoch.EpochBootstrapOutboxPublisher;
import com.example.dispute.workflow.application.authority.epoch.EpochPartyAuthority;
import com.example.dispute.workflow.application.authority.epoch.EpochPartyAuthority.Party;
import com.example.dispute.workflow.application.authority.epoch.EpochSelectionBinding;
import com.example.dispute.workflow.application.authority.epoch.EpochSelectionHasher;
import com.example.dispute.workflow.application.authority.epoch.TransactionalEpochAuthorityService;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator.LockRequest;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator.LockedRow;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator.LockedRows;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class TransactionalEpochAuthorityServiceTest {

    private static final String HASH = "a".repeat(64);
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 7, 22, 0, 0, 0, 0, ZoneOffset.UTC);

    private NamedParameterJdbcTemplate jdbc;
    private EpochAuthorityLockCoordinator locks;
    private EpochBootstrapOutboxPublisher publisher;
    private TransactionalEpochAuthorityService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        locks = mock(EpochAuthorityLockCoordinator.class);
        publisher = mock(EpochBootstrapOutboxPublisher.class);
        service = new TransactionalEpochAuthorityService(jdbc, locks, publisher);
        when(locks.lockForShare(any())).thenReturn(new LockedRows(
                List.of(new LockedRow("ACCESS-I", "ACTIVE"), new LockedRow("ACCESS-R", "ACTIVE")),
                List.of(new LockedRow("AGENT-I", "ACTIVE"), new LockedRow("AGENT-R", "ACTIVE")),
                List.of(new LockedRow("REG-I", "REGISTERED"), new LockedRow("REG-R", "REGISTERED")),
                false));
        when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(1);
        when(jdbc.queryForObject(
                        contains("select authority_id"),
                        any(MapSqlParameterSource.class),
                        eq(String.class)))
                .thenReturn("AUTH-I", "AUTH-R");
        when(jdbc.queryForList(contains("select party"), anyMap(), eq(String.class)))
                .thenReturn(List.of("INITIATOR", "RESPONDENT"));
        when(publisher.publish(any())).thenReturn("OUTBOX-1");
    }

    @Test
    void publishesBootstrapOnlyAfterExactBilateralAssertion() {
        when(jdbc.queryForObject(contains("select count"), anyMap(), eq(Integer.class)))
                .thenReturn(2);

        var receipt = service.bind(request());

        assertThat(receipt.created()).isTrue();
        assertThat(receipt.parties()).extracting(EpochPartyAuthority::party)
                .containsExactly(Party.INITIATOR, Party.RESPONDENT);
        InOrder order = inOrder(jdbc, publisher);
        order.verify(jdbc).queryForObject(contains("select count"), anyMap(), eq(Integer.class));
        order.verify(jdbc).queryForList(contains("select party"), anyMap(), eq(String.class));
        order.verify(publisher).publish(any(EpochBootstrapOutbox.class));
    }

    @Test
    void cardinalityFailurePreventsBootstrapPublication() {
        when(jdbc.queryForObject(contains("select count"), anyMap(), eq(Integer.class)))
                .thenReturn(1);

        assertThatThrownBy(() -> service.bind(request()))
                .isInstanceOfSatisfying(
                        EpochAuthorityException.class,
                        exception -> assertThat(exception.reasonCode())
                                .isEqualTo("AUTHORITY_PARTY_CARDINALITY"));

        verify(publisher, never()).publish(any());
    }

    private static BindRequest request() {
        EpochSelectionBinding selection = new EpochSelectionBinding(
                "EPOCH-1",
                "TENANT-1",
                "CASE-1",
                RoomType.INTAKE,
                0,
                1,
                selectionHash(),
                WriterMode.SHADOW,
                "CaseProcessWorkflow",
                "case-build-v1",
                "IntakeRoomWorkflow",
                "room-build-v1",
                "process-contract-v1",
                "intake.v2",
                "graph-v2",
                "checkpoint-v2",
                "intake-graph-state.v2",
                "stream-v2",
                "prompt-v2",
                "model-v2",
                "intake-turn-proposal.v2",
                "policy-v2",
                "guardrail-v2",
                "tool-policy-v2",
                "cohort-v1",
                "DISPUTE_INTAKE_OFFICER",
                "agent-session-profile.v1",
                "GRAPH_PRIVATE_NO_MEMORY_FRAME_V1");
        List<EpochPartyAuthority> parties = List.of(
                party(Party.INITIATOR, "AUTH-I", "USER-1", ActorRole.USER,
                        "ACCESS-I", "AGENT-I", "REG-I", "PARTY_USER"),
                party(Party.RESPONDENT, "AUTH-R", "MERCHANT-1", ActorRole.MERCHANT,
                        "ACCESS-R", "AGENT-R", "REG-R", "PARTY_MERCHANT"));
        EpochBootstrapOutbox outbox = new EpochBootstrapOutbox(
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
                HASH,
                NOW);
        LockRequest lockRequest = new LockRequest(
                List.of("ACCESS-I", "ACCESS-R"),
                List.of("AGENT-I", "AGENT-R"),
                List.of("REG-I", "REG-R"));
        return new BindRequest(
                selection,
                new CasePartyAssignment(
                        "USER-1", ActorRole.USER, "MERCHANT-1", ActorRole.MERCHANT),
                parties,
                outbox,
                lockRequest);
    }

    private static EpochPartyAuthority party(
            Party party,
            String authorityId,
            String actorId,
            ActorRole actorRole,
            String accessId,
            String agentId,
            String registrationId,
            String permission) {
        return new EpochPartyAuthority(
                authorityId,
                "EPOCH-1",
                party,
                "TENANT-1",
                "CASE-1",
                "TENANT-1",
                "CASE-1",
                RoomType.INTAKE,
                0,
                1,
                registrationId,
                HASH,
                "grt.v1." + "b".repeat(32),
                actorId,
                actorRole,
                actorRole,
                HASH,
                accessId,
                permission,
                agentId,
                "DISPUTE_INTAKE_OFFICER",
                "prompt-v2",
                "agent-session-profile.v1",
                AgentSessionProfileRegistry.profileId(actorRole, "prompt-v2"),
                "GRAPH_PRIVATE_NO_MEMORY_FRAME_V1",
                NOW);
    }

    private static String selectionHash() {
        return EpochSelectionHasher.hash(new EpochSelectionHasher.SelectionHashInput(
                "room-epoch-selection.v2",
                RoomType.INTAKE,
                WriterMode.SHADOW,
                "CaseProcessWorkflow",
                "case-build-v1",
                "IntakeRoomWorkflow",
                "room-build-v1",
                "process-contract-v1",
                "intake.v2",
                "graph-v2",
                "checkpoint-v2",
                "intake-graph-state.v2",
                "stream-v2",
                "prompt-v2",
                "model-v2",
                "intake-turn-proposal.v2",
                "policy-v2",
                "guardrail-v2",
                "tool-policy-v2",
                "cohort-v1"));
    }
}
