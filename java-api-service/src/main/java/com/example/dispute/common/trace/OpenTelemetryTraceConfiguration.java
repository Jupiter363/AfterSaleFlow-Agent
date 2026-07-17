package com.example.dispute.common.trace;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenTelemetryTraceConfiguration {

    @Bean
    Tracer disputeOpenTelemetryTracer(ObjectProvider<OpenTelemetry> openTelemetry) {
        OpenTelemetry telemetry =
                openTelemetry.getIfAvailable(GlobalOpenTelemetry::get);
        return telemetry.getTracer("com.example.dispute.temporal-control", "1.0");
    }
}
