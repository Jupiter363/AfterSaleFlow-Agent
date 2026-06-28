package com.example.dispute.ruleflow.application;

import com.example.dispute.infrastructure.persistence.entity.PolicyRuleEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RuleFlowService {

    private final ObjectMapper objectMapper;

    public RuleFlowService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RuleFlowConclusion conclude(PolicyRuleEntity policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        JsonNode outcome = readTree(policy.getOutcomeJson());
        String code = outcome.path("conclusion_code").asText();
        if (code.isBlank()) {
            throw new IllegalStateException("policy outcome has no conclusion_code");
        }
        List<String> actions =
                objectMapper.convertValue(
                        outcome.path("recommended_actions"),
                        objectMapper
                                .getTypeFactory()
                                .constructCollectionType(List.class, String.class));
        return new RuleFlowConclusion(
                code,
                "Policy "
                        + policy.getRuleCode()
                        + " version "
                        + policy.getRuleVersion()
                        + " matched.",
                List.copyOf(actions));
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid policy outcome JSON", exception);
        }
    }
}
