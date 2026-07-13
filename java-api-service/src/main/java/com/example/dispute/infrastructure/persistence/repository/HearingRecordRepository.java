/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：声明庭审记录在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType」、「findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc」、「findAllByCaseIdOrderByCreatedAtAsc」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.HearingRecordEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【PostgreSQL 事实模型 / 仓储接口层】类型「HearingRecordRepository」。
// 类型职责：声明庭审记录在 PostgreSQL 中的查询与写入契约；本类型显式提供 「existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType」、「findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc」、「findAllByCaseIdOrderByCreatedAtAsc」。
// 协作关系：主要由 「ActiveCourtroomContextAssembler.assemble」、「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis」、「HearingCourtBootstrapService.recordSnapshotIfAbsent」、「ActiveCourtroomContextAssemblerTest.setUp」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface HearingRecordRepository extends JpaRepository<HearingRecordEntity, String> {
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「HearingRecordRepository.existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType(String,String,int,String)」。
    // 具体功能：「HearingRecordRepository.existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType(String,String,int,String)」：声明按标识、节点名称、轮次编号、记录类型访问庭审记录的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「boolean」返回。
    // 上游调用：「HearingRecordRepository.existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType(String,String,int,String)」的上游调用点包括 「HearingCourtBootstrapService.recordSnapshotIfAbsent」、「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis」、「HearingCourtBootstrapServiceTest.bootstrapsTheHearingByFreezingPriorDossiersAndAppendingOpeningMessages」、「HearingCourtBootstrapServiceTest.repeatedBootstrapDoesNotDuplicateSnapshotOrOpeningMessages」。
    // 下游影响：「HearingRecordRepository.existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType(String,String,int,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HearingRecordRepository.existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType(String,String,int,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    boolean existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType(
            String workflowId, String nodeName, int roundNo, String recordType);
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「HearingRecordRepository.findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(String,String,int,String)」。
    // 具体功能：「HearingRecordRepository.findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(String,String,int,String)」：声明按案件标识、节点名称、轮次编号、记录类型访问庭审记录的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<HearingRecordEntity>」返回。
    // 上游调用：「HearingRecordRepository.findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(String,String,int,String)」的上游调用点包括 「ActiveCourtroomContextAssembler.assemble」、「ActiveCourtroomContextAssemblerTest.setUp」、「HearingCourtOrchestratorTest.afterRoundOpenedAppendsOpeningJudgeMessage」、「HearingCourtOrchestratorTest.invokesTheRemoteCourtAgentOutsideAnyActiveCourtTransaction」。
    // 下游影响：「HearingRecordRepository.findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(String,String,int,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HearingRecordRepository.findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(String,String,int,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<HearingRecordEntity> findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(
            String caseId, String nodeName, int roundNo, String recordType);
    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「HearingRecordRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」。
    // 具体功能：「HearingRecordRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」：声明按案件标识访问庭审记录的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<HearingRecordEntity>」返回。
    // 上游调用：「HearingRecordRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」的上游调用点包括 「HearingPersistenceIntegrationTest.persistsStateAppendOnlyRecordsDraftAndPartySubmission」、「HearingPersistenceIntegrationTest.activityCallsAgentOutsideTransactionAndPersistsEveryNodeAndDraft」、「HearingPersistenceIntegrationTest.hearingAgentFailureDoesNotPersistASyntheticManualReviewDraft」。
    // 下游影响：「HearingRecordRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HearingRecordRepository.findAllByCaseIdOrderByCreatedAtAsc(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<HearingRecordEntity> findAllByCaseIdOrderByCreatedAtAsc(String caseId);
}
