package com.example.dispute.hearing.application;

import com.example.dispute.hearing.infrastructure.persistence.entity.AgentA2AMessageEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.AgentA2AMessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentA2AMessageService {

    public static final String PRESIDING_JUDGE = "PRESIDING_JUDGE";

    private final AgentA2AMessageRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AgentA2AMessageService(
            AgentA2AMessageRepository repository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public AgentA2AMessageView record(AgentA2ACommand command) {
        AgentA2AMessageEntity saved =
                repository.save(
                        AgentA2AMessageEntity.create(
                                "A2A_" + compactUuid(),
                                command.caseId(),
                                command.roundNo(),
                                command.fromAgent(),
                                command.toAgent(),
                                command.messageType(),
                                json(command.inputRefs()),
                                json(command.payload()),
                                command.visibility(),
                                command.agentRunId(),
                                clock.instant(),
                                command.fromAgent()));
        return view(saved);
    }

    @Transactional(readOnly = true)
    public List<AgentA2AMessageView> findForJudge(String caseId, int roundNo) {
        return repository
                .findAllByCaseIdAndToAgentAndRoundNoLessThanEqualOrderByRoundNoAscCreatedAtAsc(
                        caseId,
                        PRESIDING_JUDGE,
                        roundNo)
                .stream()
                .map(AgentA2AMessageService::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean hasFormalJuryReviewReport(String caseId, int roundNo) {
        return repository.existsByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageType(
                caseId,
                roundNo,
                "JURY_PANEL",
                PRESIDING_JUDGE,
                "JURY_REVIEW_REPORT");
    }

    @Transactional(readOnly = true)
    public Optional<AgentA2AMessageView> findFormalJuryReviewReport(
            String caseId, int roundNo) {
        return repository
                .findFirstByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageTypeOrderByCreatedAtAsc(
                        caseId,
                        roundNo,
                        "JURY_PANEL",
                        PRESIDING_JUDGE,
                        "JURY_REVIEW_REPORT")
                .map(AgentA2AMessageService::view);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize A2A message", exception);
        }
    }

    private static AgentA2AMessageView view(AgentA2AMessageEntity entity) {
        return new AgentA2AMessageView(
                entity.getId(),
                entity.getCaseId(),
                entity.getRoundNo(),
                entity.getFromAgent(),
                entity.getToAgent(),
                entity.getMessageType(),
                entity.getInputRefsJson(),
                entity.getPayloadJson(),
                entity.getVisibility(),
                entity.getAgentRunId(),
                entity.getCreatedAt());
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
