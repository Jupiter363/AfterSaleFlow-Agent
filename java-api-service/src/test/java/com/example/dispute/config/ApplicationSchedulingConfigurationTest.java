package com.example.dispute.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ApplicationSchedulingConfigurationTest {

    @Test
    void scheduledJobsHaveCapacityWhenOneExternalDeliveryBlocks() throws IOException {
        var environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        var loaded = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        for (int index = loaded.size() - 1; index >= 0; index--) {
            sources.addFirst(loaded.get(index));
        }

        assertThat(environment.getProperty("spring.task.scheduling.pool.size", Integer.class))
                .isEqualTo(4);
        assertThat(environment.getProperty("spring.task.scheduling.thread-name-prefix"))
                .isEqualTo("java-api-scheduling-");
    }
}
