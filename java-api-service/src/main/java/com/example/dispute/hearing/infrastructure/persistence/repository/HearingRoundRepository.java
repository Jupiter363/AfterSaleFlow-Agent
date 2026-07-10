package com.example.dispute.hearing.infrastructure.persistence.repository;

import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HearingRoundRepository extends JpaRepository<HearingRoundEntity, String> {
    Optional<HearingRoundEntity> findTopByCaseIdOrderByRoundNoDesc(String caseId);
    Optional<HearingRoundEntity> findByCaseIdAndRoundNo(String caseId, int roundNo);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select hearingRound
              from HearingRoundEntity hearingRound
             where hearingRound.caseId = :caseId
               and hearingRound.roundNo = :roundNo
            """)
    Optional<HearingRoundEntity> findByCaseIdAndRoundNoForUpdate(
            @Param("caseId") String caseId, @Param("roundNo") int roundNo);
    List<HearingRoundEntity> findAllByCaseIdOrderByRoundNoAsc(String caseId);
    @Query("""
            select hearingRound
              from HearingRoundEntity hearingRound
             where hearingRound.roundNo = :roundNo
               and hearingRound.roundStatus in :statuses
               and hearingRound.closedAt is not null
               and (
                   :afterClosedAt is null
                   or hearingRound.closedAt > :afterClosedAt
                   or (
                       hearingRound.closedAt = :afterClosedAt
                       and hearingRound.id > :afterId
                   )
               )
               and not exists (
                   select draft.id
                     from AdjudicationDraftEntity draft
                    where draft.caseId = hearingRound.caseId
                      and draft.draftVersion = :draftVersion
               )
             order by hearingRound.closedAt asc, hearingRound.id asc
            """)
    List<HearingRoundEntity> findFinalRoundsWithoutDraftAfter(
            @Param("roundNo") int roundNo,
            @Param("draftVersion") int draftVersion,
            @Param("statuses") List<HearingRoundStatus> statuses,
            @Param("afterClosedAt") Instant afterClosedAt,
            @Param("afterId") String afterId,
            Pageable pageable);
    List<HearingRoundEntity> findAllByRoundStatusInAndRoundDeadlineAtLessThanEqualOrderByRoundDeadlineAtAsc(
            List<HearingRoundStatus> statuses, Instant deadlineAt);
}
