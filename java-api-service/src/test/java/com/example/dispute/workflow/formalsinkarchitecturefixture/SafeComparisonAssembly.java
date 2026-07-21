package com.example.dispute.workflow.formalsinkarchitecturefixture;

import com.example.dispute.workflow.formalsinkarchitecturefixture.SafeComparisonActivities.ComparisonSink;
import io.temporal.worker.Worker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class SafeComparisonAssembly {

    @Bean
    SafeComparisonActivities comparisonActivities(ComparisonSink comparisons) {
        return new SafeComparisonActivities(comparisons);
    }

    void register(Worker worker, SafeComparisonActivities activities) {
        Object contractAlias = activities;
        var registrationAlias = contractAlias;
        worker.registerActivitiesImplementations(registrationAlias);
    }
}
