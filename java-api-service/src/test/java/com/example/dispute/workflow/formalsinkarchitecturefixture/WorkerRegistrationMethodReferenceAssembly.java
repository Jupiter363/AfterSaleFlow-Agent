package com.example.dispute.workflow.formalsinkarchitecturefixture;

import io.temporal.worker.Worker;
import java.util.function.Consumer;

class WorkerRegistrationMethodReferenceAssembly {

    void register(Worker worker, CrossFileFormalWrapper formalWrapper) {
        Object alias = formalWrapper;
        Consumer<Object[]> registration = worker::registerActivitiesImplementations;
        registration.accept(new Object[] {alias});
    }
}

final class UnresolvedWorkerDynamicAssembly {

    void register(
            Worker worker,
            SafeComparisonActivities safeActivities,
            String runtimeClassName) throws ClassNotFoundException {
        Class.forName(runtimeClassName);
        worker.registerActivitiesImplementations(safeActivities);
    }
}
