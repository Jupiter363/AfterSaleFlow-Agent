package com.example.dispute.workflow.activity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.CommandStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.ProcessReconciliationIssueRepository;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRouted;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CaseProcessLedgerActivitiesImplTest {

    private static final String TENANT = "tenant-routing";
    private static final String CASE_ID = "CASE_ROUTING";
    private static final String COMMAND_ID = "CMD_ROUTING";

    @Test
    void routingLocksTheCommandByTenantAndCommandIdInOneRepositoryCall() {
        CaseCommandRepository commandRepository = mock(CaseCommandRepository.class);
        CaseCommandEntity command = mock(CaseCommandEntity.class);
        when(commandRepository.findByTenantSurrogateAndCommandIdForUpdate(TENANT, COMMAND_ID))
                .thenReturn(Optional.of(command));
        when(command.getCaseId()).thenReturn(CASE_ID);
        when(command.getCaseCommandSequence()).thenReturn(1L);
        when(command.getRequestHash()).thenReturn("request-hash");
        when(command.getRoomType()).thenReturn(RoomType.EVIDENCE);
        when(command.getRoomEpoch()).thenReturn(7L);
        when(command.getCommandStatus()).thenReturn(CommandStatus.APPLIED);

        CaseProcessLedgerActivitiesImpl activities =
                new CaseProcessLedgerActivitiesImpl(
                        commandRepository,
                        mock(CaseTimelineEventRepository.class),
                        mock(CaseRoomRepository.class),
                        mock(CaseRoomEpochRepository.class),
                        mock(CaseProcessProjectionRepository.class),
                        mock(ProcessReconciliationIssueRepository.class),
                        new ObjectMapper(),
                        Clock.systemUTC());

        var result = activities.recordCaseCommandRouted(routingRequest());

        assertThat(result.outcome()).isEqualTo(CommandLifecycleOutcome.ALREADY_APPLIED);
        verify(commandRepository).findByTenantSurrogateAndCommandIdForUpdate(TENANT, COMMAND_ID);
        verify(commandRepository, never()).findByTenantSurrogateAndCommandId(anyString(), anyString());
        verify(commandRepository, never()).findByIdForUpdate(anyString());
    }

    private static RecordCaseCommandRouted routingRequest() {
        return new RecordCaseCommandRouted(
                "record-case-command-routed.v1",
                TENANT,
                CASE_ID,
                COMMAND_ID,
                1,
                "request-hash",
                RoomType.EVIDENCE,
                7,
                Instant.parse("2026-07-29T00:00:00Z"),
                "case-process:" + TENANT + ":" + CASE_ID,
                "run-routing");
    }
}
