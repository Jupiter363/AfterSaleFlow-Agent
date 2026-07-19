package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentRunAttemptRepository
        extends JpaRepository<AgentRunAttemptEntity, String> {

    Optional<AgentRunAttemptEntity> findByAgentRunIdAndAttemptNo(
            String agentRunId, long attemptNo);

    Optional<AgentRunAttemptEntity> findByAgentRunIdAndCommandId(
            String agentRunId, String commandId);

    List<AgentRunAttemptEntity> findAllByAgentRunIdOrderByAttemptNoAsc(String agentRunId);

    long countByAgentRunId(String agentRunId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select attempt from AgentRunAttemptEntity attempt where attempt.id = :id")
    Optional<AgentRunAttemptEntity> findByIdForUpdate(@Param("id") String id);

    @Query("select coalesce(max(attempt.attemptNo), 0) from AgentRunAttemptEntity attempt where attempt.agentRunId = :runId")
    long findMaxAttemptNoByAgentRunId(@Param("runId") String runId);
}
