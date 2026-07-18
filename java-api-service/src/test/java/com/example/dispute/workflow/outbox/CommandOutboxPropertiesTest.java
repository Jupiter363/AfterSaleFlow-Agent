package com.example.dispute.workflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.config.CommandOutboxProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class CommandOutboxPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsKeepDeliveryDisabledAndBounded() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    CommandOutboxProperties properties =
                            context.getBean(CommandOutboxProperties.class);
                    assertThat(properties.enabled()).isFalse();
                    assertThat(properties.batchSize()).isEqualTo(32);
                    assertThat(properties.leaseDuration()).isEqualTo(Duration.ofMinutes(1));
                    assertThat(properties.baseBackoff()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.maxBackoff()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(properties.pollInterval()).isEqualTo(Duration.ofSeconds(5));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CommandOutboxProperties.class)
    static class PropertiesConfiguration {}
}
