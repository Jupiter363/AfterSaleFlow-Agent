package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.config.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;

class WorkerProfileWebIsolationTest {

    @Test
    void nonWebWorkerContextDoesNotRequireServletSecurityInfrastructure() {
        new ApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
                        });
    }
}
