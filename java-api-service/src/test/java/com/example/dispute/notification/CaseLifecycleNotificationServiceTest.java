package com.example.dispute.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.notification.application.NotificationCommand;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.domain.NotificationType;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CaseLifecycleNotificationServiceTest {

    private final NotificationService notificationService =
            mock(NotificationService.class);
    private final CaseLifecycleNotificationService service =
            new CaseLifecycleNotificationService(notificationService);

    @Test
    void sendsEveryLifecycleTemplateToBothCasePartiesWithInternalDeepLinks() {
        FulfillmentCaseEntity dispute = mock(FulfillmentCaseEntity.class);
        when(dispute.getId()).thenReturn("CASE_1");
        when(dispute.getUserId()).thenReturn("user-local");
        when(dispute.getMerchantId()).thenReturn("merchant-local");
        OffsetDateTime deadline =
                OffsetDateTime.parse("2026-07-03T02:00:00Z");

        service.evidenceRoomOpened(dispute, deadline);
        service.evidenceDeadlineWarning(dispute, deadline);
        service.supplementRequested(dispute, "round-2");
        service.reviewPending(dispute, "REVIEW_1");
        service.finalDecision(dispute, "APPROVE");
        service.executionCompleted(dispute);
        service.manualHandoff(dispute, "REVIEW_REJECTED");

        ArgumentCaptor<NotificationCommand> commands =
                ArgumentCaptor.forClass(NotificationCommand.class);
        verify(notificationService, org.mockito.Mockito.times(14))
                .send(commands.capture());

        assertThat(commands.getAllValues())
                .extracting(NotificationCommand::notificationType)
                .containsOnly(
                        NotificationType.EVIDENCE_ROOM_OPENED,
                        NotificationType.EVIDENCE_DEADLINE_WARNING,
                        NotificationType.SUPPLEMENT_REQUESTED,
                        NotificationType.REVIEW_PENDING,
                        NotificationType.FINAL_DECISION,
                        NotificationType.EXECUTION_COMPLETED,
                        NotificationType.MANUAL_HANDOFF);
        assertThat(commands.getAllValues())
                .extracting(NotificationCommand::recipientId)
                .containsOnly("user-local", "merchant-local");
        assertThat(commands.getAllValues())
                .allSatisfy(
                        command -> {
                            assertThat(command.caseId()).isEqualTo("CASE_1");
                            assertThat(command.deepLink())
                                    .startsWith("/disputes/CASE_1/");
                            assertThat(command.businessEventKey())
                                    .startsWith("CASE_1:");
                            assertThat(command.payloadJson()).isNotBlank();
                        });
        assertThat(commands.getAllValues())
                .filteredOn(
                        command ->
                                command.notificationType()
                                        == NotificationType.EVIDENCE_DEADLINE_WARNING)
                .allSatisfy(
                        command ->
                                assertThat(command.payloadJson())
                                        .contains(deadline.toString()));
    }
}
