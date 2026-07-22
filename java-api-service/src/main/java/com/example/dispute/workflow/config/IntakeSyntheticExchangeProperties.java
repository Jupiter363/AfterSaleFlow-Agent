package com.example.dispute.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Isolated private object-store namespace for signed-synthetic Intake artifacts. */
@ConfigurationProperties(prefix = "app.orchestration.intake-synthetic-exchange")
public record IntakeSyntheticExchangeProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("intake-synthetic-private") String bucket,
        @DefaultValue("signed-synthetic/intake") String prefix) {

    public IntakeSyntheticExchangeProperties {
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw new IllegalArgumentException("Intake synthetic exchange bucket is invalid");
        }
        if (prefix == null
                || prefix.length() > 256
                || !prefix.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")) {
            throw new IllegalArgumentException("Intake synthetic exchange prefix is invalid");
        }
    }
}
