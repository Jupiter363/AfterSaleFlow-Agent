package com.example.dispute.hearing.infrastructure.persistence.repository;

import com.example.dispute.hearing.infrastructure.persistence.entity.AgentA2AMessageEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentA2AMessageRepository extends JpaRepository<AgentA2AMessageEntity, String> {

    List<AgentA2AMessageEntity>
            findAllByCaseIdAndToAgentAndRoundNoLessThanEqualOrderByRoundNoAscCreatedAtAsc(
                    String caseId, String toAgent, int roundNo);
}
