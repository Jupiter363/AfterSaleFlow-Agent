package com.example.dispute.workflow.shadow;

import static com.example.dispute.workflow.shadow.IntakeSyntheticTestFixtures.signedAttempt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeDriver;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SignedSyntheticIntakeDriverTest {

    @Test
    void admitsOnlyAnExactlyVerifiedSignedSyntheticMessage() {
        IntakeSyntheticTestFixtures.Admission admission =
                new IntakeSyntheticTestFixtures.Admission();
        SignedSyntheticIntakeDriver driver = new SignedSyntheticIntakeDriver(admission);
        var command = IntakeSyntheticTestFixtures.inertCommand(
                "CMD_SYNTHETIC_MESSAGE", IntakeCommandType.INTAKE_MESSAGE);
        var attempt = signedAttempt(TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC);

        var admitted = driver.admit(attempt, command);

        assertThat(admitted).isSameAs(command);
        assertThat(admitted.executionContext()).isNull();
        assertThat(admission.admissions).hasValue(1);
        assertThat(admission.lastAttempt).isSameAs(attempt);
        assertThat(admission.lastAttempt.compactJws()).isEqualTo(attempt.compactJws());
        assertThat(Arrays.stream(SignedSyntheticIntakeDriver.class.getDeclaredMethods())
                        .map(method -> method.getName()))
                .doesNotContain("dispatch");
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

    @Test
    void verifiedAdmissionMustMatchThePersistedRequestHashExactly() {
        IntakeSyntheticTestFixtures.Admission admission =
                new IntakeSyntheticTestFixtures.Admission();
        admission.verifiedRequestHashOverride = IntakeSyntheticTestFixtures.hash(63);
        SignedSyntheticIntakeDriver driver = new SignedSyntheticIntakeDriver(admission);
        var command = IntakeSyntheticTestFixtures.inertCommand(
                "CMD_SYNTHETIC_MESSAGE", IntakeCommandType.INTAKE_MESSAGE);

        assertThatThrownBy(() -> driver.admit(
                        signedAttempt(TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC), command))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("exact command tuple");
    }
}
