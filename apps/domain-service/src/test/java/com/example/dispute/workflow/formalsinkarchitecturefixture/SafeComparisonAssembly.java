package com.example.dispute.workflow.formalsinkarchitecturefixture;

import com.example.dispute.workflow.formalsinkarchitecturefixture.SafeComparisonActivities.ComparisonSink;
import io.temporal.worker.Worker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@FormalSinkArchitectureFixture
class SafeComparisonAssembly {

    @Bean
    SafeComparisonActivities comparisonActivities(ComparisonSink comparisons) {
        return new SafeComparisonActivities(comparisons);
    }

    @Bean
    Object typedProviderLookup(ObjectProvider<SafeComparisonActivities> provider) {
        return provider.getIfUnique();
    }

    void register(Worker worker, SafeComparisonActivities activities) {
        Object contractAlias = activities;
        var registrationAlias = contractAlias;
        worker.registerActivitiesImplementations(registrationAlias);
    }
}
