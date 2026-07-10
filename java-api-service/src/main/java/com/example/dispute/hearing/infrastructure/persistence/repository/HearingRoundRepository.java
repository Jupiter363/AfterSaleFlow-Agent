package com.example.dispute.hearing.infrastructure.persistence.repository;

import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HearingRoundRepository extends JpaRepository<HearingRoundEntity, String> {
    Optional<HearingRoundEntity> findTopByCaseIdOrderByRoundNoDesc(String caseId);
    Optional<HearingRoundEntity> findByCaseIdAndRoundNo(String caseId, int roundNo);
    List<HearingRoundEntity> findAllByCaseIdOrderByRoundNoAsc(String caseId);
    @Query("""
            select hearingRound
              from HearingRoundEntity hearingRound
             where hearingRound.roundNo = :roundNo
               and hearingRound.roundStatus in :statuses
               and not exists (
                   select draft.id
                     from AdjudicationDraftEntity draft
                    where draft.caseId = hearingRound.caseId
                      and draft.draftVersion = :draftVersion
               )
             order by hearingRound.closedAt asc
            """)
    List<HearingRoundEntity> findFinalRoundsWithoutDraft(
            @Param("roundNo") int roundNo,
            @Param("draftVersion") int draftVersion,
            @Param("statuses") List<HearingRoundStatus> statuses,
            Pageable pageable);
    List<HearingRoundEntity> findAllByRoundStatusInAndRoundDeadlineAtLessThanEqualOrderByRoundDeadlineAtAsc(
            List<HearingRoundStatus> statuses, Instant deadlineAt);
}
