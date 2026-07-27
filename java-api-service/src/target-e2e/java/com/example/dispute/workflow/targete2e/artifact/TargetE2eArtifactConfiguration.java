package com.example.dispute.workflow.targete2e.artifact;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Target-only integration seam. Formal writer beans are deliberately not assembled yet. */
@Configuration(proxyBeanMethods = false)
@Profile(TargetE2eArtifactPrerequisites.REQUIRED_PROFILE)
public class TargetE2eArtifactConfiguration {

    @Bean
    TargetE2eArtifactMarker targetE2eArtifactMarker() {
        return new TargetE2eArtifactMarker(TargetE2eArtifactMarker.EXPECTED_VALUE);
    }
}
