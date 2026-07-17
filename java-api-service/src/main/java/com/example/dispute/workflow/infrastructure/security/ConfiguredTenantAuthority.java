package com.example.dispute.workflow.infrastructure.security;

import com.example.dispute.config.TenantAuthorityProperties;
import com.example.dispute.workflow.application.command.TenantAuthority;
import org.springframework.stereotype.Component;

@Component
public final class ConfiguredTenantAuthority implements TenantAuthority {

    private final String tenantSurrogate;

    public ConfiguredTenantAuthority(TenantAuthorityProperties properties) {
        this.tenantSurrogate = properties.surrogate();
    }

    @Override
    public String tenantSurrogate() {
        return tenantSurrogate;
    }
}
