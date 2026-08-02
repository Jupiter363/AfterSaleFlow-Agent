package com.example.dispute.workflow.targete2e.artifact;

import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.config.GraphShadowRegistryProperties;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphStreamVisibility;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Expands the target registry template into exact bindings for every supported room audience. */
@Configuration(proxyBeanMethods = false)
@Profile(TargetE2eArtifactPrerequisites.REQUIRED_PROFILE)
@ConditionalOnProperty(
    name = TargetE2eArtifactPrerequisites.WORKER_ROLE_PROPERTY,
    havingValue = TargetE2eArtifactPrerequisites.AGENT_WORKER_ROLE)
public class TargetE2eGraphRegistryConfiguration {

  private static final Map<Audience, String> INTAKE_BASELINE_PROMPT_PROFILES =
      Map.of(
          Audience.USER, "DISPUTE_INTAKE_OFFICER:USER:v1",
          Audience.MERCHANT, "DISPUTE_INTAKE_OFFICER:MERCHANT:v1");

  @Bean
  GraphStreamVisibilityPolicy targetE2eGraphStreamVisibilityPolicy(
      GraphShadowRegistryProperties properties) {
    var bindings = bindings(template(properties));
    Map<GraphStreamVisibilityPolicy.Binding, Map<String, Set<String>>> catalog =
        new HashMap<>();
    for (var entry : bindings.entrySet()) {
      catalog.put(
          entry.getKey(),
          TargetE2EGraphStreamVisibility.requireExactPolicy(
              entry.getValue().visibleFieldsByNode()));
    }
    Map<GraphStreamVisibilityPolicy.Binding, Map<String, Set<String>>> snapshot =
        Map.copyOf(catalog);
    return binding -> snapshot.get(binding);
  }

  @Bean
  GraphRegistryBindingPolicy targetE2EGraphRegistryBindingPolicy(
      GraphShadowRegistryProperties properties) {
    var bindings = bindings(template(properties));
    Map<GraphStreamVisibilityPolicy.Binding, GraphRegistryBindingPolicy.ExpectedBinding> catalog =
        new HashMap<>();
    for (var entry : bindings.entrySet()) {
      catalog.put(entry.getKey(), entry.getValue().expectedBinding());
    }
    return GraphRegistryBindingPolicy.immutable(catalog);
  }

  /**
   * Expands the one activation-owned template into the only bindings this target artifact permits.
   *
   * <p>The room-level prompt is retained for every audience because Evidence, Hearing, and Review
   * still use that activation template. Intake private threads instead carry one of the two exact
   * baseline PromptComposer profiles. Both the stream visibility catalog and the expected registry
   * binding catalog consume this same map, so a new visible profile cannot accidentally lack the
   * corresponding registry pin.
   */
  private static Map<GraphStreamVisibilityPolicy.Binding, GraphShadowRegistryProperties.BindingEntry>
      bindings(GraphShadowRegistryProperties.BindingEntry template) {
    Map<GraphStreamVisibilityPolicy.Binding, GraphShadowRegistryProperties.BindingEntry> catalog =
        new HashMap<>();
    for (Audience audience : EnumSet.allOf(Audience.class)) {
      catalog.put(binding(template, template.promptProfileId(), audience), template);
    }
    for (var intakeProfile : INTAKE_BASELINE_PROMPT_PROFILES.entrySet()) {
      catalog.put(binding(template, intakeProfile.getValue(), intakeProfile.getKey()), template);
    }
    return Map.copyOf(catalog);
  }

  private static GraphShadowRegistryProperties.BindingEntry template(
      GraphShadowRegistryProperties properties) {
    properties.requireConfigured();
    if (properties.bindings().size() != 1
        || properties.bindings().getFirst().audience() != Audience.SYSTEM) {
      throw new IllegalStateException(
          "target E2E Graph registry requires one SYSTEM audience template");
    }
    return properties.bindings().getFirst();
  }

  private static GraphStreamVisibilityPolicy.Binding binding(
      GraphShadowRegistryProperties.BindingEntry template, String promptProfileId, Audience audience) {
    return new GraphStreamVisibilityPolicy.Binding(
        template.graphKey(),
        template.graphVersion(),
        template.checkpointSchemaVersion(),
        template.agentProfileId(),
        promptProfileId,
        template.modelProfileId(),
        template.outputSchemaVersion(),
        template.policyVersion(),
        template.guardrailVersion(),
        audience);
  }
}
