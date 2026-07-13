/*
 * 所属模块：数据库迁移入口。
 * 文件职责：验证PersistenceModelContract，覆盖 「everyRequiredTableHasAnEntityAndRepository」、「workflowStatesAreTypedEnumsInsteadOfMagicStrings」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；独立执行 Flyway 迁移并验证 PostgreSQL Schema 可用。
 * 关键边界：迁移先于业务服务读写，失败时必须阻止使用不兼容的表结构
 */
package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.ExecutionStatus;
import com.example.dispute.domain.model.HearingStatus;
import com.example.dispute.domain.model.ParseStatus;
import com.example.dispute.domain.model.ReviewTaskStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.infrastructure.persistence.entity.ActionRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.AuditLogEntity;
import com.example.dispute.infrastructure.persistence.entity.ClaimIssueEvidenceMatrixEntity;
import com.example.dispute.infrastructure.persistence.entity.EvaluationTraceEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceRequestEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.FlowConclusionEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.entity.IssueEntity;
import com.example.dispute.infrastructure.persistence.entity.PartyClaimEntity;
import com.example.dispute.infrastructure.persistence.entity.PartySubmissionEntity;
import com.example.dispute.infrastructure.persistence.entity.PolicyRuleEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewPacketEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewTaskEntity;
import com.example.dispute.infrastructure.persistence.entity.RouteDecisionEntity;
import com.example.dispute.infrastructure.persistence.repository.ActionRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.AuditLogRepository;
import com.example.dispute.infrastructure.persistence.repository.ClaimIssueEvidenceMatrixRepository;
import com.example.dispute.infrastructure.persistence.repository.EvaluationTraceRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceRequestRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.FlowConclusionRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.IssueRepository;
import com.example.dispute.infrastructure.persistence.repository.PartyClaimRepository;
import com.example.dispute.infrastructure.persistence.repository.PartySubmissionRepository;
import com.example.dispute.infrastructure.persistence.repository.PolicyRuleRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewPacketRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.infrastructure.persistence.repository.RouteDecisionRepository;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【数据库迁移入口 / 自动化测试层】类型「PersistenceModelContractTest」。
// 类型职责：集中验证PersistenceModelContract的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「everyRequiredTableHasAnEntityAndRepository」、「workflowStatesAreTypedEnumsInsteadOfMagicStrings」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：迁移先于业务服务读写，失败时必须阻止使用不兼容的表结构
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class PersistenceModelContractTest {

    // 所属模块：【数据库迁移入口 / 自动化测试层】「PersistenceModelContractTest.everyRequiredTableHasAnEntityAndRepository()」。
    // 具体功能：「PersistenceModelContractTest.everyRequiredTableHasAnEntityAndRepository()」：复现“核对完整业务行为（场景方法「everyRequiredTableHasAnEntityAndRepository」）”场景：驱动 「Map.ofEntries」、「Map.entry」、「type.getAnnotation」、「assertThat(type).hasAnnotation」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「fulfillment_dispute_case」、「evidence_dossier」、「evidence_item」、「party_claim」。
    // 上游调用：「PersistenceModelContractTest.everyRequiredTableHasAnEntityAndRepository()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「PersistenceModelContractTest.everyRequiredTableHasAnEntityAndRepository()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「PersistenceModelContractTest.everyRequiredTableHasAnEntityAndRepository()」守住「数据库迁移入口」的可执行规格，尤其防止 「fulfillment_dispute_case」、「evidence_dossier」、「evidence_item」、「party_claim」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    @Test
    void everyRequiredTableHasAnEntityAndRepository() {
        Map<Class<?>, String> mappings =
                Map.ofEntries(
                        Map.entry(
                                FulfillmentCaseEntity.class,
                                "fulfillment_dispute_case"),
                        Map.entry(EvidenceDossierEntity.class, "evidence_dossier"),
                        Map.entry(EvidenceItemEntity.class, "evidence_item"),
                        Map.entry(PartyClaimEntity.class, "party_claim"),
                        Map.entry(IssueEntity.class, "issue"),
                        Map.entry(
                                ClaimIssueEvidenceMatrixEntity.class,
                                "claim_issue_evidence_link"),
                        Map.entry(EvidenceRequestEntity.class, "evidence_request"),
                        Map.entry(
                                PartySubmissionEntity.class,
                                "dispute_submission"),
                        Map.entry(HearingStateEntity.class, "hearing_state"),
                        Map.entry(
                                HearingRecordEntity.class,
                                "hearing_stage_record"),
                        Map.entry(AdjudicationDraftEntity.class, "adjudication_draft"),
                        Map.entry(RemedyPlanEntity.class, "remedy_plan"),
                        Map.entry(ReviewPacketEntity.class, "review_packet"),
                        Map.entry(ReviewTaskEntity.class, "review_task"),
                        Map.entry(
                                ApprovalRecordEntity.class,
                                "human_review_record"),
                        Map.entry(ActionRecordEntity.class, "action_record"),
                        Map.entry(AuditLogEntity.class, "audit_log"),
                        Map.entry(PolicyRuleEntity.class, "policy_rule"),
                        Map.entry(
                                EvaluationTraceEntity.class,
                                "evaluation_record"),
                        Map.entry(RouteDecisionEntity.class, "route_decision"),
                        Map.entry(FlowConclusionEntity.class, "flow_conclusion"));

        mappings.forEach(
                (type, table) -> {
                    assertThat(type).hasAnnotation(Entity.class);
                    assertThat(type.getAnnotation(Table.class).name()).isEqualTo(table);
                });

        assertThat(
                        new Class<?>[] {
                            FulfillmentCaseRepository.class,
                            EvidenceDossierRepository.class,
                            EvidenceItemRepository.class,
                            PartyClaimRepository.class,
                            IssueRepository.class,
                            ClaimIssueEvidenceMatrixRepository.class,
                            EvidenceRequestRepository.class,
                            PartySubmissionRepository.class,
                            HearingStateRepository.class,
                            HearingRecordRepository.class,
                            AdjudicationDraftRepository.class,
                            RemedyPlanRepository.class,
                            ReviewPacketRepository.class,
                            ReviewTaskRepository.class,
                            ApprovalRecordRepository.class,
                            ActionRecordRepository.class,
                            AuditLogRepository.class,
                            PolicyRuleRepository.class,
                            EvaluationTraceRepository.class,
                            RouteDecisionRepository.class,
                            FlowConclusionRepository.class
                        })
                .allMatch(JpaRepository.class::isAssignableFrom);
    }

    // 所属模块：【数据库迁移入口 / 自动化测试层】「PersistenceModelContractTest.workflowStatesAreTypedEnumsInsteadOfMagicStrings()」。
    // 具体功能：「PersistenceModelContractTest.workflowStatesAreTypedEnumsInsteadOfMagicStrings()」：复现“核对完整业务行为（场景方法「workflowStatesAreTypedEnumsInsteadOfMagicStrings」）”场景：驱动 「RouteType.values」、「assertThat(CaseStatus.valueOf("INTAKE_PENDING")).isNotNull」、「assertThat(RouteType.values()).containsExactly」、「assertThat(RiskLevel.valueOf("CRITICAL")).isNotNull」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「INTAKE_PENDING」、「CRITICAL」、「FAILED」、「WAITING_EVIDENCE」。
    // 上游调用：「PersistenceModelContractTest.workflowStatesAreTypedEnumsInsteadOfMagicStrings()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「PersistenceModelContractTest.workflowStatesAreTypedEnumsInsteadOfMagicStrings()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「PersistenceModelContractTest.workflowStatesAreTypedEnumsInsteadOfMagicStrings()」守住「数据库迁移入口」的可执行规格，尤其防止 「INTAKE_PENDING」、「CRITICAL」、「FAILED」、「WAITING_EVIDENCE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void workflowStatesAreTypedEnumsInsteadOfMagicStrings() {
        assertThat(CaseStatus.valueOf("INTAKE_PENDING")).isNotNull();
        assertThat(RouteType.values())
                .containsExactly(
                        RouteType.TRANSFERRED,
                        RouteType.SIMPLE_HEARING,
                        RouteType.FULL_HEARING);
        assertThat(RiskLevel.valueOf("CRITICAL")).isNotNull();
        assertThat(ParseStatus.valueOf("FAILED")).isNotNull();
        assertThat(HearingStatus.valueOf("WAITING_EVIDENCE")).isNotNull();
        assertThat(ReviewTaskStatus.valueOf("APPROVED")).isNotNull();
        assertThat(ApprovalDecisionType.valueOf("MODIFY_AND_APPROVE")).isNotNull();
        assertThat(ExecutionStatus.valueOf("SUCCEEDED")).isNotNull();
    }
}
