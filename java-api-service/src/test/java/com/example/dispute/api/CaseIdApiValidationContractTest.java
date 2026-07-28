package com.example.dispute.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.agentstream.api.CaseAgentRunController;
import com.example.dispute.agentstream.api.InternalAgentRunRequest;
import com.example.dispute.audit.api.AuditController;
import com.example.dispute.casecore.api.DemoCasePurgeController;
import com.example.dispute.casecore.api.DisputeController;
import com.example.dispute.evaluation.api.ClosureController;
import com.example.dispute.evidence.api.EvidenceController;
import com.example.dispute.evidence.api.InternalEvidenceController;
import com.example.dispute.hearing.api.HearingFlowController;
import com.example.dispute.outcome.api.CaseOutcomeController;
import com.example.dispute.remedy.api.RemedyController;
import com.example.dispute.room.api.CaseEventController;
import com.example.dispute.room.api.IntakeRoomController;
import com.example.dispute.room.api.RoomController;
import com.example.dispute.room.api.RoomTurnMemoryController;
import com.example.dispute.router.api.RouterController;
import com.example.dispute.workflow.api.CaseCommandController;
import com.example.dispute.workflow.api.SignedSyntheticIntakeIngressController;
import com.example.dispute.workflow.api.mig001.Mig001ScenarioController;
import jakarta.validation.constraints.Pattern;
import java.lang.reflect.AnnotatedElement;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CaseIdApiValidationContractTest {

    private static final String CASE_ID_REGEX = "CASE_[A-Za-z0-9_]{1,59}";
    private static final String TARGET_SYNTHETIC_CASE_ID = "CASE_P9_SYNTHETIC_0123456789abcdef";

    @Test
    void browserVisibleCaseIdValidatorsAcceptTargetSyntheticIdsOnlyWithinTheExistingBoundary() {
        List<String> caseIdPatterns =
                apiTypes().flatMap(CaseIdApiValidationContractTest::validationElements)
                        .map(element -> element.getAnnotation(Pattern.class))
                        .filter(pattern -> pattern != null && pattern.regexp().startsWith("CASE_"))
                        .map(Pattern::regexp)
                        .toList();

        assertThat(caseIdPatterns).isNotEmpty().containsOnly(CASE_ID_REGEX);
        assertThat(java.util.regex.Pattern.matches(CASE_ID_REGEX, TARGET_SYNTHETIC_CASE_ID)).isTrue();
        assertThat(java.util.regex.Pattern.matches(CASE_ID_REGEX, "CASE_P9-SYNTHETIC_01"))
                .isFalse();
        assertThat(java.util.regex.Pattern.matches(CASE_ID_REGEX, "CASE_" + "a".repeat(60)))
                .isFalse();
    }

    private static Stream<Class<?>> apiTypes() {
        return Stream.of(
                CaseAgentRunController.class,
                InternalAgentRunRequest.class,
                AuditController.class,
                DemoCasePurgeController.class,
                DisputeController.class,
                ClosureController.class,
                EvidenceController.class,
                InternalEvidenceController.class,
                HearingFlowController.class,
                CaseOutcomeController.class,
                RemedyController.class,
                CaseEventController.class,
                IntakeRoomController.class,
                RoomController.class,
                RoomTurnMemoryController.class,
                RouterController.class,
                CaseCommandController.class,
                SignedSyntheticIntakeIngressController.class,
                Mig001ScenarioController.class);
    }

    private static Stream<AnnotatedElement> validationElements(Class<?> type) {
        Stream<AnnotatedElement> parameters =
                Stream.of(type.getDeclaredMethods()).flatMap(method -> Stream.of(method.getParameters()));
        Stream<AnnotatedElement> recordComponents =
                type.isRecord()
                        ? Stream.of(type.getRecordComponents())
                        : Stream.empty();
        return Stream.concat(parameters, recordComponents);
    }
}
