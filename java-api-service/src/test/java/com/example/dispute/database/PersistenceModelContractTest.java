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

class PersistenceModelContractTest {

    @Test
    void everyRequiredTableHasAnEntityAndRepository() {
        Map<Class<?>, String> mappings =
                Map.ofEntries(
                        Map.entry(FulfillmentCaseEntity.class, "fulfillment_case"),
                        Map.entry(EvidenceDossierEntity.class, "evidence_dossier"),
                        Map.entry(EvidenceItemEntity.class, "evidence_item"),
                        Map.entry(PartyClaimEntity.class, "party_claim"),
                        Map.entry(IssueEntity.class, "issue"),
                        Map.entry(
                                ClaimIssueEvidenceMatrixEntity.class,
                                "claim_issue_evidence_matrix"),
                        Map.entry(EvidenceRequestEntity.class, "evidence_request"),
                        Map.entry(PartySubmissionEntity.class, "party_submission"),
                        Map.entry(HearingStateEntity.class, "hearing_state"),
                        Map.entry(HearingRecordEntity.class, "hearing_record"),
                        Map.entry(AdjudicationDraftEntity.class, "adjudication_draft"),
                        Map.entry(RemedyPlanEntity.class, "remedy_plan"),
                        Map.entry(ReviewPacketEntity.class, "review_packet"),
                        Map.entry(ReviewTaskEntity.class, "review_task"),
                        Map.entry(ApprovalRecordEntity.class, "approval_record"),
                        Map.entry(ActionRecordEntity.class, "action_record"),
                        Map.entry(AuditLogEntity.class, "audit_log"),
                        Map.entry(PolicyRuleEntity.class, "policy_rule"),
                        Map.entry(EvaluationTraceEntity.class, "evaluation_trace"),
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

    @Test
    void workflowStatesAreTypedEnumsInsteadOfMagicStrings() {
        assertThat(CaseStatus.valueOf("INTAKE_PENDING")).isNotNull();
        assertThat(RouteType.values())
                .containsExactly(
                        RouteType.REGULAR_FULFILLMENT,
                        RouteType.RULE_BASED_RESOLUTION,
                        RouteType.DISPUTE_HEARING);
        assertThat(RiskLevel.valueOf("CRITICAL")).isNotNull();
        assertThat(ParseStatus.valueOf("FAILED")).isNotNull();
        assertThat(HearingStatus.valueOf("WAITING_EVIDENCE")).isNotNull();
        assertThat(ReviewTaskStatus.valueOf("APPROVED")).isNotNull();
        assertThat(ApprovalDecisionType.valueOf("MODIFY_AND_APPROVE")).isNotNull();
        assertThat(ExecutionStatus.valueOf("SUCCEEDED")).isNotNull();
    }
}
