/*
 * 所属模块：共享小法庭。
 * 文件职责：声明庭审轮次在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findTopByCaseIdOrderByRoundNoDesc」、「findByCaseIdAndRoundNo」、「findByCaseIdAndRoundNoForUpdate」、「findAllByCaseIdOrderByRoundNoAsc」、「findFinalRoundsWithoutDraft」、「findFinalRoundsWithoutDraftAfter」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
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

// 所属模块：【共享小法庭 / 仓储接口层】类型「HearingRoundRepository」。
// 类型职责：声明庭审轮次在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findTopByCaseIdOrderByRoundNoDesc」、「findByCaseIdAndRoundNo」、「findByCaseIdAndRoundNoForUpdate」、「findAllByCaseIdOrderByRoundNoAsc」、「findFinalRoundsWithoutDraft」、「findFinalRoundsWithoutDraftAfter」。
// 协作关系：主要由 「ActiveCourtroomContextAssembler.validatedSealedRounds」、「HearingCourtOrchestrator.persistJudgeTurn」、「HearingCourtOrchestrator.prepareJudgeTurn」、「HearingFinalDraftService.adoptExistingDraftForFinalSealedRound」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface HearingRoundRepository extends JpaRepository<HearingRoundEntity, String> {
    // 所属模块：【共享小法庭 / 仓储接口层】「HearingRoundRepository.findTopByCaseIdOrderByRoundNoDesc(String)」。
    // 具体功能：「HearingRoundRepository.findTopByCaseIdOrderByRoundNoDesc(String)」：声明按案件标识访问庭审轮次的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<HearingRoundEntity>」返回。
    // 上游调用：「HearingRoundRepository.findTopByCaseIdOrderByRoundNoDesc(String)」的上游调用点包括 「HearingRoundService.ensureInitialRoundOpen」、「HearingRoundService.completeNext」、「HearingRoundService.expire」、「HearingRoundService.status」。
    // 下游影响：「HearingRoundRepository.findTopByCaseIdOrderByRoundNoDesc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HearingRoundRepository.findTopByCaseIdOrderByRoundNoDesc(String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<HearingRoundEntity> findTopByCaseIdOrderByRoundNoDesc(String caseId);
    // 所属模块：【共享小法庭 / 仓储接口层】「HearingRoundRepository.findByCaseIdAndRoundNo(String,int)」。
    // 具体功能：「HearingRoundRepository.findByCaseIdAndRoundNo(String,int)」：声明按案件标识、轮次编号访问庭审轮次的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<HearingRoundEntity>」返回。
    // 上游调用：「HearingRoundRepository.findByCaseIdAndRoundNo(String,int)」的上游调用点包括 「HearingCourtOrchestrator.prepareJudgeTurn」、「HearingFinalDraftService.adoptExistingDraftForFinalSealedRound」、「HearingRoundService.recordPartyMessageSubmission」、「HearingRoundService.openNextRound」。
    // 下游影响：「HearingRoundRepository.findByCaseIdAndRoundNo(String,int)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HearingRoundRepository.findByCaseIdAndRoundNo(String,int)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<HearingRoundEntity> findByCaseIdAndRoundNo(String caseId, int roundNo);
    // 所属模块：【共享小法庭 / 仓储接口层】「HearingRoundRepository.findByCaseIdAndRoundNoForUpdate(String,int)」。
    // 具体功能：「HearingRoundRepository.findByCaseIdAndRoundNoForUpdate(String,int)」：声明按案件标识、轮次编号面向更新访问庭审轮次的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<HearingRoundEntity>」返回。
    // 上游调用：「HearingRoundRepository.findByCaseIdAndRoundNoForUpdate(String,int)」的上游调用点包括 「HearingCourtOrchestrator.persistJudgeTurn」、「HearingCourtOrchestratorTest.setUp」。
    // 下游影响：「HearingRoundRepository.findByCaseIdAndRoundNoForUpdate(String,int)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HearingRoundRepository.findByCaseIdAndRoundNoForUpdate(String,int)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
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
    // 所属模块：【共享小法庭 / 仓储接口层】「HearingRoundRepository.findAllByCaseIdOrderByRoundNoAsc(String)」。
    // 具体功能：「HearingRoundRepository.findAllByCaseIdOrderByRoundNoAsc(String)」：声明按案件标识访问庭审轮次的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<HearingRoundEntity>」返回。
    // 上游调用：「HearingRoundRepository.findAllByCaseIdOrderByRoundNoAsc(String)」的上游调用点包括 「ActiveCourtroomContextAssembler.validatedSealedRounds」、「HearingRoundService.list」、「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsMissingRoundInRequiredSequence」、「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsOpenRound」。
    // 下游影响：「HearingRoundRepository.findAllByCaseIdOrderByRoundNoAsc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HearingRoundRepository.findAllByCaseIdOrderByRoundNoAsc(String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<HearingRoundEntity> findAllByCaseIdOrderByRoundNoAsc(String caseId);
    // 所属模块：【共享小法庭 / 仓储接口层】「HearingRoundRepository.findFinalRoundsWithoutDraft(int,int,List,Pageable)」。
    // 具体功能：「HearingRoundRepository.findFinalRoundsWithoutDraft(int,int,List,Pageable)」：声明按终态Rounds缺少草案访问庭审轮次的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<HearingRoundEntity>」返回。
    // 上游调用：「HearingRoundRepository.findFinalRoundsWithoutDraft(int,int,List,Pageable)」的上游调用点包括 「HearingFinalRoundRecoveryService.recoverFinalRoundsWithoutDraft」、「HearingFinalRoundRecoveryServiceTest.repairsFormalJuryReportBeforeResignalingFinalRound」、「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenFormalJuryReportStillCannotBePersisted」、「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenOnlyTheJuryRoomCardExistsWithoutFormalA2A」。
    // 下游影响：「HearingRoundRepository.findFinalRoundsWithoutDraft(int,int,List,Pageable)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HearingRoundRepository.findFinalRoundsWithoutDraft(int,int,List,Pageable)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @Query("""
            select hearingRound
              from HearingRoundEntity hearingRound
             where hearingRound.roundNo = :roundNo
               and hearingRound.roundStatus in :statuses
               and hearingRound.closedAt is not null
               and not exists (
                   select draft.id
                     from AdjudicationDraftEntity draft
                    where draft.caseId = hearingRound.caseId
                      and draft.draftVersion = :draftVersion
               )
             order by hearingRound.closedAt asc, hearingRound.id asc
            """)
    List<HearingRoundEntity> findFinalRoundsWithoutDraft(
            @Param("roundNo") int roundNo,
            @Param("draftVersion") int draftVersion,
            @Param("statuses") List<HearingRoundStatus> statuses,
            Pageable pageable);

    // 所属模块：【共享小法庭 / 仓储接口层】「HearingRoundRepository.findFinalRoundsWithoutDraftAfter(int,int,List,Instant,String,Pageable)」。
    // 具体功能：「HearingRoundRepository.findFinalRoundsWithoutDraftAfter(int,int,List,Instant,String,Pageable)」：声明按终态Rounds缺少草案之后访问庭审轮次的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<HearingRoundEntity>」返回。
    // 上游调用：「HearingRoundRepository.findFinalRoundsWithoutDraftAfter(int,int,List,Instant,String,Pageable)」的上游调用点包括 「HearingFinalRoundRecoveryService.recoverFinalRoundsWithoutDraft」、「HearingFinalRoundRecoveryServiceTest.rotatesTheKeysetCursorSoAPermanentFailureCannotStarveTheNextCandidate」。
    // 下游影响：「HearingRoundRepository.findFinalRoundsWithoutDraftAfter(int,int,List,Instant,String,Pageable)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HearingRoundRepository.findFinalRoundsWithoutDraftAfter(int,int,List,Instant,String,Pageable)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @Query("""
            select hearingRound
              from HearingRoundEntity hearingRound
             where hearingRound.roundNo = :roundNo
               and hearingRound.roundStatus in :statuses
               and hearingRound.closedAt is not null
               and (
                   hearingRound.closedAt > :afterClosedAt
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
    // 所属模块：【共享小法庭 / 仓储接口层】「HearingRoundRepository.findAllByRoundStatusInAndRoundDeadlineAtLessThanEqualOrderByRoundDeadlineAtAsc(List,Instant)」。
    // 具体功能：「HearingRoundRepository.findAllByRoundStatusInAndRoundDeadlineAtLessThanEqualOrderByRoundDeadlineAtAsc(List,Instant)」：声明按轮次状态In、轮次截止时间AtLessThanEqual访问庭审轮次的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<HearingRoundEntity>」返回。
    // 上游调用：「HearingRoundRepository.findAllByRoundStatusInAndRoundDeadlineAtLessThanEqualOrderByRoundDeadlineAtAsc(List,Instant)」的上游调用点包括 「HearingRoundService.expireDueRounds」。
    // 下游影响：「HearingRoundRepository.findAllByRoundStatusInAndRoundDeadlineAtLessThanEqualOrderByRoundDeadlineAtAsc(List,Instant)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HearingRoundRepository.findAllByRoundStatusInAndRoundDeadlineAtLessThanEqualOrderByRoundDeadlineAtAsc(List,Instant)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<HearingRoundEntity> findAllByRoundStatusInAndRoundDeadlineAtLessThanEqualOrderByRoundDeadlineAtAsc(
            List<HearingRoundStatus> statuses, Instant deadlineAt);
}
