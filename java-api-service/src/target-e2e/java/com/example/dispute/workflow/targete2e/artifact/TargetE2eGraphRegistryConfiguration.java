package com.example.dispute.workflow.targete2e.artifact;

import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.config.GraphShadowRegistryProperties;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
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

  @Bean
  GraphStreamVisibilityPolicy targetE2eGraphStreamVisibilityPolicy(
      GraphShadowRegistryProperties properties) {
    var template = template(properties);
    Map<GraphStreamVisibilityPolicy.Binding, Map<String, Set<String>>> catalog =
        new HashMap<>();
    for (Audience audience : EnumSet.allOf(Audience.class)) {
      catalog.put(
          binding(template, audience),
          GraphStreamVisibilityPolicy.immutablePolicy(template.visibleFieldsByNode()));
    }
    Map<GraphStreamVisibilityPolicy.Binding, Map<String, Set<String>>> snapshot =
        Map.copyOf(catalog);
    return binding -> snapshot.get(binding);
  }

  @Bean
  GraphRegistryBindingPolicy targetE2EGraphRegistryBindingPolicy(
      GraphShadowRegistryProperties properties) {
    var template = template(properties);
    Map<GraphStreamVisibilityPolicy.Binding, GraphRegistryBindingPolicy.ExpectedBinding> catalog =
        new HashMap<>();
    for (Audience audience : EnumSet.allOf(Audience.class)) {
      catalog.put(binding(template, audience), template.expectedBinding());
    }
    return GraphRegistryBindingPolicy.immutable(catalog);
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
      GraphShadowRegistryProperties.BindingEntry template, Audience audience) {
    return new GraphStreamVisibilityPolicy.Binding(
        template.graphKey(),
        template.graphVersion(),
        template.checkpointSchemaVersion(),
        template.agentProfileId(),
        template.promptProfileId(),
        template.modelProfileId(),
        template.outputSchemaVersion(),
        template.policyVersion(),
        template.guardrailVersion(),
        audience);
  }
}
