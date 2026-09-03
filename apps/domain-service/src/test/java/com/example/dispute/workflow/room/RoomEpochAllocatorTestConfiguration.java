package com.example.dispute.workflow.room;

import com.example.dispute.workflow.application.command.TenantAuthority;
import com.example.dispute.workflow.application.epoch.ConfiguredRoomEpochSelector;
import com.example.dispute.workflow.application.epoch.RoomEpochSelector;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class RoomEpochAllocatorTestConfiguration {

    @Bean
    RoomEpochSelector roomEpochSelector() {
        return ConfiguredRoomEpochSelector::terminalLegacySelection;
    }

    @Bean
    TenantAuthority tenantAuthority() {
        return () -> "tenant-service-integration";
    }
}
