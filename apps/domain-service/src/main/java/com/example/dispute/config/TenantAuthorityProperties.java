package com.example.dispute.config;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.tenant-authority")
public record TenantAuthorityProperties(String surrogate) {

    private static final Pattern SAFE_SURROGATE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    public TenantAuthorityProperties {
        if (surrogate == null || !SAFE_SURROGATE.matcher(surrogate).matches()) {
            throw new IllegalArgumentException(
                    "app.security.tenant-authority.surrogate must be a safe opaque identifier");
        }
    }
}
