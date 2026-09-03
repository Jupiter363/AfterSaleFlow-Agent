package com.example.dispute.workflow.formalsinkarchitecturefixture;

import static com.example.dispute.workflow.formalsinkarchitecturefixture.FixtureFormalFactory.FORMAL_ACTIVITY;

import io.temporal.worker.Worker;

class StaticFieldAliasRegistrar {

    void register(Worker worker) {
        Object firstAlias = FORMAL_ACTIVITY;
        var secondAlias = firstAlias;
        worker.registerActivitiesImplementations(secondAlias);
    }
}
