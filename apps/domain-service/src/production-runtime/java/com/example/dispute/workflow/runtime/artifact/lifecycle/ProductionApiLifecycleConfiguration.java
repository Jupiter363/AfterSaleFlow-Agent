package com.example.dispute.workflow.runtime.artifact.lifecycle;

import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKey;
import com.example.dispute.workflow.runtime.lifecycle.ProductionActivationLifecycleControl;
import com.example.dispute.workflow.runtime.lifecycle.ProductionActivationLifecycleControl.DeploymentBinding;
import com.example.dispute.workflow.runtime.lifecycle.ProductionActivationLifecycleReceiptSigner;
import com.example.dispute.workflow.runtime.lifecycle.ProductionDrainCompletionAttestationVerifier;
import com.example.dispute.workflow.runtime.persistence.JdbcProductionActivationStores;
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
 * Production-only API assembly for the harness lifecycle capability.
 *
 * <p>The ordinary AgentRun artifact assembly is worker-only.  Keeping this small configuration
 * separate makes the lifecycle controller reachable from the API process without adding any
 * lifecycle capability to the ordinary or control-worker profiles.
 */
@Configuration(proxyBeanMethods = false)
@Profile("production-runtime & api")
@ConditionalOnProperty(name = "app.production-runtime.enabled", havingValue = "true")
public class ProductionApiLifecycleConfiguration {

  @Bean
  DeploymentBinding productionApiLifecycleDeploymentBinding(Environment environment) {
    return new DeploymentBinding(
        environment.acceptsProfiles(Profiles.of("production-runtime")),
        required(environment, "production.runtime.environment.id"),
        requiredPositiveLong(environment, "production.runtime.environment.generation"),
        required(environment, "production.runtime.activation.id"),
        required(environment, "production.runtime.activation.manifest-hash"),
        required(environment, "production.runtime.runtime-context-hash"));
  }

  @Bean
  ProductionActivationLifecycleControl productionApiLifecycleControl(
      JdbcProductionActivationStores store, DeploymentBinding binding, Clock clock) {
    return ProductionActivationLifecycleControl.bind(store, binding, clock);
  }

  @Bean
  ProductionActivationLifecycleReceiptSigner productionApiLifecycleReceiptSigner(
      GraphEnvelopeSigningKey activeGraphEnvelopeSigningKey,
      ObjectMapper objectMapper,
      Clock clock) {
    return new ProductionActivationLifecycleReceiptSigner(
        activeGraphEnvelopeSigningKey, objectMapper, clock);
  }

  @Bean
  ProductionDrainCompletionAttestationVerifier productionDrainCompletionAttestationVerifier(
      DeploymentBinding binding, Environment environment, Clock clock) {
    Path publicKeyPath =
        Path.of(required(environment, "production.runtime.lifecycle.harness-public-key-path"));
    return new ProductionDrainCompletionAttestationVerifier(
        ProductionDrainCompletionAttestationVerifier.loadPublicKey(publicKeyPath),
        required(environment, "production.runtime.lifecycle.harness-key-id"),
        required(environment, "production.runtime.lifecycle.harness-public-key-sha256"),
        binding,
        clock);
  }

  private static String required(Environment environment, String property) {
    String value = environment.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("required production runtime property is absent: " + property);
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
          "required production runtime property is not a positive safe integer: " + property, failure);
    }
  }
}
