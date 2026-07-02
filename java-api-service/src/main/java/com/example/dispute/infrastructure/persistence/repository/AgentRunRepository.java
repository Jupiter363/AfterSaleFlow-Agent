package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, String> {
    List<AgentRunEntity> findAllByCaseIdOrderByCreatedAtAsc(String caseId);
}
