package com.example.dispute.workflow.infrastructure.persistence.repository;

import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.CommandStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseCommandRepository extends JpaRepository<CaseCommandEntity, String> {

    Optional<CaseCommandEntity> findByTenantSurrogateAndCommandId(
            String tenantSurrogate, String commandId);

    Optional<CaseCommandEntity> findFirstByCaseIdOrderByCaseCommandSequenceDesc(String caseId);

    boolean existsByCaseIdAndExpectedProcessRevisionAndCommandStatusIn(
            String caseId,
            long expectedProcessRevision,
            Collection<CommandStatus> commandStatuses);

    List<CaseCommandEntity>
            findByTenantSurrogateAndCaseIdAndCaseCommandSequenceBetweenOrderByCaseCommandSequenceAsc(
                    String tenantSurrogate,
                    String caseId,
                    long fromSequenceInclusive,
                    long toSequenceInclusive);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select command from CaseCommandEntity command where command.id = :id")
    Optional<CaseCommandEntity> findByIdForUpdate(@Param("id") String id);

    @Query(
            value =
                    "select pg_advisory_xact_lock(hashtextextended(concat(:tenantSurrogate, ':', :commandId), 0))",
            nativeQuery = true)
    void lockTenantCommandId(
            @Param("tenantSurrogate") String tenantSurrogate,
            @Param("commandId") String commandId);
}
