package com.example.dispute.workflow.config;

import com.example.dispute.config.AppProperties;
import com.example.dispute.workflow.application.CaseFulfillmentDisputeActivitiesImpl;
import com.example.dispute.workflow.temporal.CaseFulfillmentDisputeWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(
        name = "app.temporal.worker-enabled",
        havingValue = "true")
public class TemporalWorkerConfiguration {

    @Bean(destroyMethod = "shutdown")
    WorkerFactory caseWorkflowWorkerFactory(
            WorkflowClient workflowClient,
            AppProperties properties,
            CaseFulfillmentDisputeActivitiesImpl activities) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(properties.temporal().taskQueue());
        worker.registerWorkflowImplementationTypes(
                CaseFulfillmentDisputeWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        factory.start();
        return factory;
    }
}
