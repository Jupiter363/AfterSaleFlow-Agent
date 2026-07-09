package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.room.infrastructure.persistence.entity.CaseTimelineEventEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseTimelineEventRepository extends JpaRepository<CaseTimelineEventEntity, String> {
    List<CaseTimelineEventEntity> findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
            String caseId, long sequenceNo);

    List<CaseTimelineEventEntity> findAllByCaseIdOrderBySequenceNoAsc(String caseId);

    @Query("select coalesce(max(event.sequenceNo), 0) from CaseTimelineEventEntity event where event.caseId = :caseId")
    long findMaxSequenceByCaseId(@Param("caseId") String caseId);

    Optional<CaseTimelineEventEntity> findByCaseIdAndEventKey(
            String caseId, String eventKey);
}
