package com.example.dispute.workflow.targete2e.artifact.lifecycle;

import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKey;
import com.example.dispute.workflow.targete2e.lifecycle.TargetE2eActivationLifecycleControl;
import com.example.dispute.workflow.targete2e.lifecycle.TargetE2eActivationLifecycleControl.DeploymentBinding;
import com.example.dispute.workflow.targete2e.lifecycle.TargetE2eActivationLifecycleReceiptSigner;
import com.example.dispute.workflow.targete2e.lifecycle.TargetE2eDrainCompletionAttestationVerifier;
import com.example.dispute.workflow.targete2e.persistence.JdbcTargetE2eActivationStores;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Target-only API assembly for the harness lifecycle capability.
 *
 * <p>The ordinary AgentRun artifact assembly is worker-only.  Keeping this small configuration
 * separate makes the lifecycle controller reachable from the API process without adding any
 * lifecycle capability to the ordinary or control-worker profiles.
 */
@Configuration(proxyBeanMethods = false)
@Profile("target-e2e & api")
@ConditionalOnProperty(name = "app.target-e2e.enabled", havingValue = "true")
public class TargetE2eApiLifecycleConfiguration {

  @Bean
  DeploymentBinding targetE2eApiLifecycleDeploymentBinding(Environment environment) {
    return new DeploymentBinding(
        environment.acceptsProfiles(Profiles.of("target-e2e")),
        required(environment, "target.e2e.environment.id"),
        requiredPositiveLong(environment, "target.e2e.environment.generation"),
        required(environment, "target.e2e.activation.id"),
        required(environment, "target.e2e.activation.manifest-hash"),
        required(environment, "target.e2e.runtime-context-hash"));
  }

  @Bean
  TargetE2eActivationLifecycleControl targetE2eApiLifecycleControl(
      JdbcTargetE2eActivationStores store, DeploymentBinding binding, Clock clock) {
    return TargetE2eActivationLifecycleControl.bind(store, binding, clock);
  }

  @Bean
  TargetE2eActivationLifecycleReceiptSigner targetE2eApiLifecycleReceiptSigner(
      GraphEnvelopeSigningKey activeGraphEnvelopeSigningKey,
      ObjectMapper objectMapper,
      Clock clock) {
    return new TargetE2eActivationLifecycleReceiptSigner(
        activeGraphEnvelopeSigningKey, objectMapper, clock);
  }

  @Bean
  TargetE2eDrainCompletionAttestationVerifier targetE2eDrainCompletionAttestationVerifier(
      DeploymentBinding binding, Environment environment, Clock clock) {
    Path publicKeyPath =
        Path.of(required(environment, "target.e2e.lifecycle.harness-public-key-path"));
    return new TargetE2eDrainCompletionAttestationVerifier(
        TargetE2eDrainCompletionAttestationVerifier.loadPublicKey(publicKeyPath),
        required(environment, "target.e2e.lifecycle.harness-key-id"),
        required(environment, "target.e2e.lifecycle.harness-public-key-sha256"),
        binding,
        clock);
  }

  private static String required(Environment environment, String property) {
    String value = environment.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("required target E2E property is absent: " + property);
    }
    return value.trim();
  }

  private static long requiredPositiveLong(Environment environment, String property) {
    String value = required(environment, property);
    try {
      long parsed = Long.parseLong(value);
      if (parsed < 1 || parsed > 9_007_199_254_740_991L) {
        throw new NumberFormatException("outside positive safe integer range");
      }
      return parsed;
    } catch (NumberFormatException failure) {
      throw new IllegalStateException(
          "required target E2E property is not a positive safe integer: " + property, failure);
    }
  }
}
