/*
 * 所属模块：共享小法庭。
 * 文件职责：声明AgentA2A消息在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「existsByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageType」、「findFirstByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageTypeOrderByCreatedAtAsc」、「findAllByCaseIdAndToAgentAndRoundNoLessThanEqualOrderByRoundNoAscCreatedAtAsc」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.infrastructure.persistence.repository;

import com.example.dispute.hearing.infrastructure.persistence.entity.AgentA2AMessageEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【共享小法庭 / 仓储接口层】类型「AgentA2AMessageRepository」。
// 类型职责：声明AgentA2A消息在 PostgreSQL 中的查询与写入契约；本类型显式提供 「existsByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageType」、「findFirstByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageTypeOrderByCreatedAtAsc」、「findAllByCaseIdAndToAgentAndRoundNoLessThanEqualOrderByRoundNoAscCreatedAtAsc」。
// 协作关系：主要由 「AgentA2AMessageService.findForJudge」、「AgentA2AMessageService.findFormalJuryReviewReport」、「AgentA2AMessageService.hasFormalJuryReviewReport」、「AgentA2AMessageServiceTest.checksTheExactFormalJuryReportForTheFinalRound」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface AgentA2AMessageRepository extends JpaRepository<AgentA2AMessageEntity, String> {

    // 所属模块：【共享小法庭 / 仓储接口层】「AgentA2AMessageRepository.existsByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageType(String,int,String,String,String)」。
    // 具体功能：「AgentA2AMessageRepository.existsByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageType(String,int,String,String,String)」：声明按案件标识、轮次编号、FromAgent、Agent、消息类型访问AgentA2A消息的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「boolean」返回。
    // 上游调用：「AgentA2AMessageRepository.existsByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageType(String,int,String,String,String)」的上游调用点包括 「AgentA2AMessageService.hasFormalJuryReviewReport」、「AgentA2AMessageServiceTest.checksTheExactFormalJuryReportForTheFinalRound」。
    // 下游影响：「AgentA2AMessageRepository.existsByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageType(String,int,String,String,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentA2AMessageRepository.existsByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageType(String,int,String,String,String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    boolean existsByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageType(
            String caseId,
            int roundNo,
            String fromAgent,
            String toAgent,
            String messageType);

    // 所属模块：【共享小法庭 / 仓储接口层】「AgentA2AMessageRepository.findFirstByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageTypeOrderByCreatedAtAsc(String,int,String,String,String)」。
    // 具体功能：「AgentA2AMessageRepository.findFirstByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageTypeOrderByCreatedAtAsc(String,int,String,String,String)」：声明按案件标识、轮次编号、FromAgent、Agent、消息类型访问AgentA2A消息的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<AgentA2AMessageEntity>」返回。
    // 上游调用：「AgentA2AMessageRepository.findFirstByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageTypeOrderByCreatedAtAsc(String,int,String,String,String)」的上游调用点包括 「AgentA2AMessageService.findFormalJuryReviewReport」、「AgentA2AMessageServiceTest.loadsTheFormalJuryReportSoRepairCanReuseItsExactPayload」。
    // 下游影响：「AgentA2AMessageRepository.findFirstByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageTypeOrderByCreatedAtAsc(String,int,String,String,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentA2AMessageRepository.findFirstByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageTypeOrderByCreatedAtAsc(String,int,String,String,String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<AgentA2AMessageEntity>
            findFirstByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageTypeOrderByCreatedAtAsc(
                    String caseId,
                    int roundNo,
                    String fromAgent,
                    String toAgent,
                    String messageType);

    // 所属模块：【共享小法庭 / 仓储接口层】「AgentA2AMessageRepository.findAllByCaseIdAndToAgentAndRoundNoLessThanEqualOrderByRoundNoAscCreatedAtAsc(String,String,int)」。
    // 具体功能：「AgentA2AMessageRepository.findAllByCaseIdAndToAgentAndRoundNoLessThanEqualOrderByRoundNoAscCreatedAtAsc(String,String,int)」：声明按案件标识、Agent、轮次编号LessThanEqual访问AgentA2A消息的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<AgentA2AMessageEntity>」返回。
    // 上游调用：「AgentA2AMessageRepository.findAllByCaseIdAndToAgentAndRoundNoLessThanEqualOrderByRoundNoAscCreatedAtAsc(String,String,int)」的上游调用点包括 「AgentA2AMessageService.findForJudge」、「AgentA2AMessageServiceTest.recordsJurySilentNotesAndFindsThemForLaterJudgeRounds」、「HearingPersistenceIntegrationTest.juryRepairUsesTheNextLockedRoomSequenceWhenASequenceBlockerAlreadyExists」。
    // 下游影响：「AgentA2AMessageRepository.findAllByCaseIdAndToAgentAndRoundNoLessThanEqualOrderByRoundNoAscCreatedAtAsc(String,String,int)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentA2AMessageRepository.findAllByCaseIdAndToAgentAndRoundNoLessThanEqualOrderByRoundNoAscCreatedAtAsc(String,String,int)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<AgentA2AMessageEntity>
            findAllByCaseIdAndToAgentAndRoundNoLessThanEqualOrderByRoundNoAscCreatedAtAsc(
                    String caseId, String toAgent, int roundNo);
}
