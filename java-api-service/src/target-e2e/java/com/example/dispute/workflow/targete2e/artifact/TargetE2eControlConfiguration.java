package com.example.dispute.workflow.targete2e.artifact;

import com.example.dispute.workflow.config.TemporalWorkerProperties;
import com.example.dispute.workflow.targete2e.temporal.TargetTemporalWorkerRegistration;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomCaseProcessDispatcher;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Target-artifact composition for the isolated all-room Temporal control lane. */
@Configuration(proxyBeanMethods = false)
@Profile("target-e2e")
@ConditionalOnProperty(
    name = "app.temporal.worker.role", havingValue = "CONTROL")
public class TargetE2eControlConfiguration {

  @Bean
  TargetTemporalWorkerRegistration targetTemporalWorkerRegistration(
      Environment environment, TemporalWorkerProperties workerProperties) {
    String activationId = required(environment, "target.e2e.activation.id");
    TargetTemporalWorkerRegistration.Registration registration =
        new TargetTemporalWorkerRegistration.Registration(
            "target-e2e",
            "TARGET_E2E_CANDIDATE",
            activationId,
            workerProperties.buildId(),
            TargetE2eCaseProcessWorkflow.class,
            TargetTypedRoomProtocol.additionalWorkflowImplementations(),
            List.of(),
            List.of());
    return () -> registration;
  }

  private static String required(Environment environment, String property) {
    String value = environment.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(property + " is required by the target Temporal lane");
    }
    return value;
  }

  /** The only concrete target-capable CaseProcess implementation in the packaged application. */
  public static final class TargetE2eCaseProcessWorkflow
      extends TargetTypedRoomCaseProcessDispatcher {

    @Override
    protected boolean targetArtifactPresent() {
      return true;
    }
  }
}
