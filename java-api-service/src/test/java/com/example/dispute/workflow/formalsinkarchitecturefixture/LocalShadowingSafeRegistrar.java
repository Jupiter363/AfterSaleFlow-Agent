package com.example.dispute.workflow.formalsinkarchitecturefixture;

import static com.example.dispute.workflow.formalsinkarchitecturefixture.FixtureFormalFactory.FORMAL_ACTIVITY;

import io.temporal.worker.Worker;

class LocalShadowingSafeRegistrar {

    void register(Worker worker, SafeComparisonActivities safe) {
        Object FORMAL_ACTIVITY = safe;
        var alias = FORMAL_ACTIVITY;
        worker.registerActivitiesImplementations(alias);
    }
}
