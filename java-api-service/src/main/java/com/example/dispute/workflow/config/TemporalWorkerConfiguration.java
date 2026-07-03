package com.example.dispute.workflow.config;

import com.example.dispute.config.AppProperties;
import com.example.dispute.workflow.application.CaseFulfillmentDisputeActivitiesImpl;
import com.example.dispute.workflow.application.FinalWorkflowActivitiesAdapter;
import com.example.dispute.workflow.application.EvidenceWindowActivitiesAdapter;
import com.example.dispute.workflow.temporal.CaseFulfillmentDisputeWorkflowImpl;
import com.example.dispute.workflow.temporal.DeliberationPanelWorkflowImpl;
import com.example.dispute.workflow.temporal.DisputeHearingWorkflowImpl;
import com.example.dispute.workflow.temporal.ExecutionWorkflowImpl;
import com.example.dispute.workflow.temporal.EvidenceWindowWorkflowImpl;
import com.example.dispute.workflow.temporal.FulfillmentDisputeWorkflowImpl;
import com.example.dispute.workflow.temporal.HumanReviewWorkflowImpl;
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
            CaseFulfillmentDisputeActivitiesImpl legacyActivities,
            FinalWorkflowActivitiesAdapter finalActivities,
            EvidenceWindowActivitiesAdapter evidenceWindowActivities) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(properties.temporal().taskQueue());
        worker.registerWorkflowImplementationTypes(
                CaseFulfillmentDisputeWorkflowImpl.class,
                FulfillmentDisputeWorkflowImpl.class,
                DisputeHearingWorkflowImpl.class,
                DeliberationPanelWorkflowImpl.class,
                HumanReviewWorkflowImpl.class,
                ExecutionWorkflowImpl.class,
                EvidenceWindowWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                legacyActivities, finalActivities, evidenceWindowActivities);
        factory.start();
        return factory;
    }
}
