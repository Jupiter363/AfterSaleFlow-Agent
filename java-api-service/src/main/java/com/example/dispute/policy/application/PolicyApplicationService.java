package com.example.dispute.policy.application;

import com.example.dispute.infrastructure.persistence.entity.PolicyRuleEntity;
import com.example.dispute.infrastructure.persistence.repository.PolicyRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyApplicationService {

    private final PolicyRuleRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PolicyApplicationService(
            PolicyRuleRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PolicyRuleView> findActive(String scope) {
        String normalizedScope =
                scope == null || scope.isBlank() ? null : scope.strip().toUpperCase();
        return repository.findActive(normalizedScope, OffsetDateTime.now(clock)).stream()
                .map(this::toView)
                .toList();
    }

    private PolicyRuleView toView(PolicyRuleEntity entity) {
        return new PolicyRuleView(
                entity.getId(),
                entity.getRuleCode(),
                entity.getRuleVersion(),
                entity.getRuleName(),
                entity.getRuleScope(),
                entity.getRuleStatus(),
                entity.getEffectiveFrom(),
                entity.getEffectiveTo(),
                entity.getPriority(),
                readMap(entity.getConditionJson()),
                readMap(entity.getOutcomeJson()),
                readMap(entity.getSourceDocumentJson()));
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted policy JSON", exception);
        }
    }
}
