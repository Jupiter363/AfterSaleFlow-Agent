package com.example.dispute.workflow.infrastructure.persistence.repository;

import com.example.dispute.workflow.infrastructure.persistence.entity.AgentExecutionManifestEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentExecutionManifestRepository
        extends JpaRepository<AgentExecutionManifestEntity, String> {

    Optional<AgentExecutionManifestEntity>
            findByTenantSurrogateAndCaseIdAndLogicalAgentRunId(
                    String tenantSurrogate, String caseId, String logicalAgentRunId);

    List<AgentExecutionManifestEntity> findAllByLogicalAgentRunId(String logicalAgentRunId);
}
