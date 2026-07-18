package com.example.dispute.workflow.room;

import com.example.dispute.workflow.application.command.TenantAuthority;
import com.example.dispute.workflow.application.epoch.RoomEpochSelection;
import com.example.dispute.workflow.application.epoch.RoomEpochSelector;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import java.util.Locale;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class RoomEpochAllocatorTestConfiguration {

    @Bean
    RoomEpochSelector roomEpochSelector() {
        return roomType ->
                new RoomEpochSelection(
                        WriterMode.SHADOW,
                        "room-epoch-selection.v1",
                        "case-process-contract.v1",
                        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                        "service-integration-build-v1",
                        roomType.name().toLowerCase(Locale.ROOT) + ".service-integration",
                        "1.0.0",
                        "checkpoint.v1",
                        "agent-stream.v2");
    }

    @Bean
    TenantAuthority tenantAuthority() {
        return () -> "tenant-service-integration";
    }
}
