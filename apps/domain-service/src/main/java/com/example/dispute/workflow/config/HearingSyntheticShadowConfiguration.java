package com.example.dispute.workflow.config;

import com.example.dispute.workflow.shadow.hearing.Es256HearingSyntheticAdmissionVerifier;
import com.example.dispute.workflow.shadow.hearing.HearingNoFormalSinkGuard;
import com.example.dispute.workflow.shadow.hearing.HearingSignedSyntheticAdmissionService;
import com.example.dispute.workflow.shadow.hearing.HearingSyntheticAdmissionTrustSet;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fail-closed comparison-only assembly; it intentionally declares no resolver or finalizer bean. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HearingEpochSelectionProperties.class)
public class HearingSyntheticShadowConfiguration {

    @Bean
    @ConditionalOnMissingBean
    HearingEpochSelector hearingEpochSelector(HearingEpochSelectionProperties properties) {
        return new HearingEpochSelector(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    HearingNoFormalSinkGuard hearingNoFormalSinkGuard() {
        return new HearingNoFormalSinkGuard();
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.orchestration.hearing-epoch-selection.signed-synthetic-shadow-enabled",
            havingValue = "true")
    @ConditionalOnMissingBean
    Es256HearingSyntheticAdmissionVerifier hearingSyntheticAdmissionVerifier(
            HearingEpochSelectionProperties properties,
            ObjectProvider<HearingSyntheticAdmissionTrustSet> trustSetProvider) {
        properties.requireSignedSyntheticSelectionConfigured();
        HearingSyntheticAdmissionTrustSet trustSet = trustSetProvider.getIfUnique();
        if (trustSet == null) {
            throw new IllegalStateException(
                    "exactly one Hearing synthetic admission trust set is required");
        }
        return new Es256HearingSyntheticAdmissionVerifier(trustSet, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.orchestration.hearing-epoch-selection.signed-synthetic-shadow-enabled",
            havingValue = "true")
    @ConditionalOnMissingBean
    HearingSignedSyntheticAdmissionService hearingSignedSyntheticAdmissionService(
            HearingEpochSelector selector,
            Es256HearingSyntheticAdmissionVerifier verifier,
            HearingNoFormalSinkGuard guard) {
        return new HearingSignedSyntheticAdmissionService(selector, verifier, guard);
    }
}
