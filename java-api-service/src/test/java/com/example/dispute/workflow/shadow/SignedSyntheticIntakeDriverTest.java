package com.example.dispute.workflow.shadow;

import static com.example.dispute.workflow.shadow.IntakeSyntheticTestFixtures.signedAttempt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeDriver;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SignedSyntheticIntakeDriverTest {

    @Test
    void admitsOnlyAnExactlyVerifiedSignedSyntheticMessage() {
        IntakeSyntheticTestFixtures.Admission admission =
                new IntakeSyntheticTestFixtures.Admission();
        SignedSyntheticIntakeDriver driver = new SignedSyntheticIntakeDriver(admission);
        var command = IntakeSyntheticTestFixtures.inertCommand(
                "CMD_SYNTHETIC_MESSAGE", IntakeCommandType.INTAKE_MESSAGE);
        AtomicReference<Object> dispatched = new AtomicReference<>();

        var admitted = driver.dispatch(
                signedAttempt(TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC),
                command,
                dispatched::set);

        assertThat(admitted.executionContext()).isNotNull();
        assertThat(admitted.executionContext().threadId())
                .isEqualTo(IntakeSyntheticTestFixtures.THREAD_ID);
        assertThat(dispatched.get()).isSameAs(admitted);
        assertThat(admission.admissions).hasValue(1);
    }

    @Test
    void unsignedRealAndBranchCommandsFailBeforeAdmission() {
        IntakeSyntheticTestFixtures.Admission admission =
                new IntakeSyntheticTestFixtures.Admission();
        SignedSyntheticIntakeDriver driver = new SignedSyntheticIntakeDriver(admission);
        var message = IntakeSyntheticTestFixtures.inertCommand(
                "CMD_SYNTHETIC_MESSAGE", IntakeCommandType.INTAKE_MESSAGE);
        var unsigned = new com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort.AdmissionAttempt(
                "intake-signed-synthetic-admission-attempt.v1",
                TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC,
                null,
                null,
                null,
                IntakeSyntheticTestFixtures.THREAD_ID,
                IntakeSyntheticTestFixtures.AGENT_SESSION,
                Long.MAX_VALUE,
                IntakeSyntheticTestFixtures.retryBudget());

        assertThatThrownBy(() -> driver.admit(unsigned, message))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("evidence");
        assertThatThrownBy(() -> driver.admit(
                        signedAttempt(TrafficSource.AUTHENTICATED_REAL_CASE), message))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("real-case");
        assertThatThrownBy(() -> driver.admit(
                        signedAttempt(TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC),
                        IntakeSyntheticTestFixtures.inertCommand(
                                "CMD_SYNTHETIC_BRANCH", IntakeCommandType.INTAKE_CONFIRM)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("message commands only");
        assertThat(admission.admissions).hasValue(0);
    }
}
