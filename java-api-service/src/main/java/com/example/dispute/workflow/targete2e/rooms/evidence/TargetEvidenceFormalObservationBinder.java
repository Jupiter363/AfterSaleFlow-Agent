package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Binds model-authored observation vocabulary to the canonical formal Evidence projection.
 *
 * <p>The immutable proposal remains unchanged. Only the derived formal projection uses the
 * persistence vocabulary. Unknown model vocabulary is retained as an inconclusive relationship
 * instead of rejecting an otherwise usable Evidence turn or inventing a stronger relationship.
 */
final class TargetEvidenceFormalObservationBinder {

  private TargetEvidenceFormalObservationBinder() {}

  static List<JsonNode> bind(List<JsonNode> observations) {
    Objects.requireNonNull(observations, "observations");
    List<JsonNode> bound = new ArrayList<>(observations.size());
    for (JsonNode observation : observations) {
      JsonNode copy = observation.deepCopy();
      if (copy instanceof ObjectNode object) {
        JsonNode rawBindings = object.get("fact_bindings");
        if (rawBindings instanceof ArrayNode bindings) {
          for (JsonNode rawBinding : bindings) {
            if (rawBinding instanceof ObjectNode binding) {
              binding.put("relation", canonicalRelation(binding.path("relation").asText("")));
            }
          }
        }
      }
      bound.add(copy);
    }
    return List.copyOf(bound);
  }

  static String canonicalRelation(String relation) {
    String normalized = relation == null ? "" : relation.strip().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "CONTENT_SUPPORTS", "SUPPORTS" -> "CONTENT_SUPPORTS";
      case "CONTENT_CONTRADICTS", "CONTRADICTS", "OPPOSES" -> "CONTENT_CONTRADICTS";
      case "CONTEXT_ONLY", "CONTEXTUALIZES" -> "CONTEXT_ONLY";
      case "INCONCLUSIVE" -> "INCONCLUSIVE";
      default -> "INCONCLUSIVE";
    };
  }
}
