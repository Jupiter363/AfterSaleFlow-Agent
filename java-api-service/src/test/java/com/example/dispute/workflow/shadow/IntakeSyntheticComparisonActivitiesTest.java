package com.example.dispute.workflow.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.shadow.intake.IntakeSyntheticWorkerRegistration;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocationMode;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomActivities;
import io.temporal.failure.ApplicationFailure;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

class IntakeSyntheticComparisonActivitiesTest {

    @Test
    void registrationIsExplicitNonDiscoverableAndHasNoFormalDependency() {
        IntakeSyntheticTestFixtures.Admission admission =
                new IntakeSyntheticTestFixtures.Admission();
        IntakeSyntheticTestFixtures.InMemoryLedger ledger =
                new IntakeSyntheticTestFixtures.InMemoryLedger();
        IntakeSyntheticWorkerRegistration registration =
                new IntakeSyntheticWorkerRegistration(
                        admission,
                        IntakeSyntheticTestFixtures::snapshotReceipt,
                        IntakeSyntheticTestFixtures::graphReceipt,
                        ignored -> new com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityObservationPort.Observation(
                                IntakeSyntheticTestFixtures.paritySnapshot(),
                                IntakeSyntheticTestFixtures.paritySnapshot(),
                                com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType.TURN_READY_TO_CONFIRM),
                        ledger);

        assertThat(registration.activityContract()).isEqualTo(IntakeRoomActivities.class);
        assertThat(registration.activityImplementation().getClass().isAnnotationPresent(Component.class))
                .isFalse();
        assertThat(registration.activityImplementation().getClass().isAnnotationPresent(Service.class))
                .isFalse();
        assertThat(registration.getClass().isAnnotationPresent(Configuration.class)).isFalse();
        assertThat(Arrays.stream(
                                registration.activityImplementation()
                                        .getClass()
                                        .getDeclaredFields())
                        .map(field -> field.getType().getName()))
                .noneMatch(name -> name.contains("Formal") || name.contains("Finalizer"));
    }

    @Test
    void everyBranchActivityIsPermanentlyClosed() {
        IntakeSyntheticWorkerRegistration registration =
                new IntakeSyntheticWorkerRegistration(
                        new IntakeSyntheticTestFixtures.Admission(),
                        IntakeSyntheticTestFixtures::snapshotReceipt,
                        IntakeSyntheticTestFixtures::graphReceipt,
                        ignored -> new com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityObservationPort.Observation(
                                IntakeSyntheticTestFixtures.paritySnapshot(),
                                IntakeSyntheticTestFixtures.paritySnapshot(),
                                com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType.TURN_READY_TO_CONFIRM),
                        new IntakeSyntheticTestFixtures.InMemoryLedger());
        IntakeRoomActivities activities = registration.activityImplementation();

        assertThatThrownBy(() -> activities.acceptInitiator(null))
                .isInstanceOf(ApplicationFailure.class)
                .extracting(failure -> ((ApplicationFailure) failure).getType())
                .isEqualTo("INTAKE_SYNTHETIC_BRANCH_FORBIDDEN");
        assertThatThrownBy(() -> activities.rejectInitiator(null))
                .isInstanceOf(ApplicationFailure.class);
        assertThatThrownBy(() -> activities.cancelIntake(null))
                .isInstanceOf(ApplicationFailure.class);
        assertThatThrownBy(() -> activities.confirmRespondent(null))
                .isInstanceOf(ApplicationFailure.class);
    }

    @Test
    void missingAdmissionOrChangedRequestHashCannotReachSnapshotStorage() {
        IntakeSyntheticTestFixtures.Admission admission =
                new IntakeSyntheticTestFixtures.Admission();
        var inert = IntakeSyntheticTestFixtures.inertCommand(
                "CMD_SYNTHETIC_MESSAGE",
                com.example.dispute.workflow.temporal.room.intake.IntakeCommandType.INTAKE_MESSAGE);
        new com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeDriver(admission)
                .admit(
                        IntakeSyntheticTestFixtures.signedAttempt(
                                com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC),
                        inert);
        AtomicInteger snapshotCalls = new AtomicInteger();
        IntakeSyntheticWorkerRegistration registration =
                new IntakeSyntheticWorkerRegistration(
                        admission,
                        request -> {
                            snapshotCalls.incrementAndGet();
                            return IntakeSyntheticTestFixtures.snapshotReceipt(request);
                        },
                        IntakeSyntheticTestFixtures::graphReceipt,
                        ignored -> new com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityObservationPort.Observation(
                                IntakeSyntheticTestFixtures.paritySnapshot(),
                                IntakeSyntheticTestFixtures.paritySnapshot(),
                                com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType.TURN_READY_TO_CONFIRM),
                        new IntakeSyntheticTestFixtures.InMemoryLedger());

        admission.activityAuthorized = false;
        assertThatThrownBy(() -> registration.activityImplementation()
                        .publishSnapshot(snapshotRequest(inert.requestHash())))
                .isInstanceOf(ApplicationFailure.class)
                .extracting(failure -> ((ApplicationFailure) failure).getType())
                .isEqualTo("INTAKE_SYNTHETIC_AUTHORIZATION");

        admission.activityAuthorized = true;
        assertThatThrownBy(() -> registration.activityImplementation()
                        .publishSnapshot(snapshotRequest(IntakeSyntheticTestFixtures.hash(63))))
                .isInstanceOf(ApplicationFailure.class)
                .extracting(failure -> ((ApplicationFailure) failure).getType())
                .isEqualTo("INTAKE_SYNTHETIC_AUTHORIZATION");
        assertThat(snapshotCalls).hasValue(0);
    }

    private static SnapshotPublicationRequest snapshotRequest(String requestHash) {
        ActivityEnvelope envelope =
                new ActivityEnvelope(
                        "intake-activity-envelope.v1",
                        IntakeSyntheticTestFixtures.TENANT,
                        IntakeSyntheticTestFixtures.CASE_ID,
                        IntakeSyntheticTestFixtures.EPOCH,
                        IntakeSyntheticTestFixtures.FENCE,
                        "CMD_SYNTHETIC_MESSAGE",
                        1,
                        com.example.dispute.workflow.temporal.room.intake.IntakeCommandType.INTAKE_MESSAGE,
                        IntakeParty.INITIATOR,
                        IntakeSyntheticTestFixtures.ACTOR_SCOPE,
                        "urn:after-sale-flow:intake-command:CMD_SYNTHETIC_MESSAGE",
                        IntakeSyntheticTestFixtures.hash(1),
                        0,
                        0,
                        Long.MAX_VALUE,
                        new RetryBudget("intake-retry-budget.v1", 2, 1, 1),
                        new PinnedVersions(
                                "intake-pinned-versions.v1",
                                "intake-workflow.synthetic.v1",
                                "2.0.0",
                                "intake-checkpoint.v2",
                                "intake-prompt.v2",
                                "intake-model.synthetic.v1",
                                "intake-turn-proposal.v2",
                                "intake-policy.v2",
                                "intake-guardrail.v2",
                                "no-tools.v1"),
                        new ActivityInvocation(
                                "intake-activity-invocation.v1",
                                ActivityInvocationMode.FIRST_EXECUTION,
                                2));
        String operationKey = IntakeOperationKeys.snapshotPublish(
                IntakeSyntheticTestFixtures.CASE_ID,
                IntakeSyntheticTestFixtures.EPOCH,
                IntakeSyntheticTestFixtures.ACTOR_SCOPE,
                0);
        return new SnapshotPublicationRequest(
                "intake-snapshot-publication-request.v1",
                envelope,
                IntakeSyntheticTestFixtures.THREAD_ID,
                IntakeSyntheticTestFixtures.AGENT_SESSION,
                0,
                operationKey,
                requestHash);
    }
}
