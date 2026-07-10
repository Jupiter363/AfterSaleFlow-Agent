package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.evaluation.application.CaseClosureService;
import com.example.dispute.executor.application.ToolExecutorService;
import com.example.dispute.hearing.application.ActiveCourtroomContextAssembler;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.PartySubmissionRepository;
import com.example.dispute.infrastructure.persistence.repository.PolicyRuleRepository;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.remedy.application.RemedyApplicationService;
import com.example.dispute.review.application.ReviewApplicationService;
import com.example.dispute.workflow.application.CaseFulfillmentDisputeActivitiesImpl;
import com.example.dispute.workflow.application.HearingAgentClient;
import com.example.dispute.workflow.domain.HearingAnalysisActivityCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class CaseFulfillmentDisputeActivitiesImplTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private EvidenceItemRepository evidenceRepository;
    @Mock private PolicyRuleRepository policyRepository;
    @Mock private HearingStateRepository stateRepository;
    @Mock private HearingRecordRepository recordRepository;
    @Mock private AdjudicationDraftRepository draftRepository;
    @Mock private AgentRunRepository agentRunRepository;
    @Mock private PartySubmissionRepository submissionRepository;
    @Mock private ActiveCourtroomContextAssembler courtroomContextAssembler;
    @Mock private HearingAgentClient agentClient;
    @Mock private RemedyApplicationService remedyService;
    @Mock private ReviewApplicationService reviewService;
    @Mock private CaseLifecycleNotificationService lifecycleNotifications;
    @Mock private ToolExecutorService toolExecutorService;
    @Mock private CaseClosureService closureService;
    @Mock private AuditRecorder auditRecorder;
    @Mock private ObjectMapper objectMapper;
    @Mock private TransactionTemplate transactions;

    @InjectMocks private CaseFulfillmentDisputeActivitiesImpl activities;

    @Test
    void finalConvergenceCannotRunBeforeAnyHearingRoundIsSealed() {
        HearingAnalysisActivityCommand command =
                new HearingAnalysisActivityCommand(
                        "CASE_ZERO_ROUND",
                        "WORKFLOW_ZERO_ROUND",
                        0,
                        true,
                        false,
                        true,
                        3);

        assertThatThrownBy(() -> activities.analyzeHearing(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "final convergence requires sealed hearing rounds and formal jury review report");
    }
}
