package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.activity.domain.IntakeChildBridgeActivitiesAdapter;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflowImpl;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflowImpl;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntakeAuthorityWorkerRegistrationTest {

    @Test
    void declaresTheOnlyAuthorityBridgeAndRequiredWorkflowTypes() {
        IntakeAuthorityWorkerRegistration registration =
                IntakeAuthorityWorkerRegistration.fromReadPorts(List.of(new StubReadPort()));
        IntakeAuthorityWorkerRegistration.V2BridgeActivityRegistration v2Bridge =
                v2Bridge(registration);

        assertThat(registration.caseControlTaskQueue()).isEqualTo("case-control");
        assertThat(registration.roomControlTaskQueue()).isEqualTo("room-control");
        assertThat(registration.caseControlWorkflowImplementationTypes())
                .containsExactly(CaseProcessWorkflowImpl.class);
        assertThat(registration.roomControlWorkflowImplementationTypes())
                .containsExactly(RoomControlWorkflowImpl.class, IntakeRoomWorkflowImpl.class);
        assertThat(registration.caseControlActivityImplementations(v2Bridge))
                .containsExactly(v2Bridge.activityImplementation());
        assertThat(v2Bridge.backingAdapter()).isSameAs(registration.bridgeActivities());

        assertThatCode(
                        () ->
                                registration.validateCaseControlRegistration(
                                        registration.caseControlWorkflowImplementationTypes(),
                                        registration.caseControlActivityImplementations(v2Bridge),
                                        v2Bridge))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                registration.validateRoomControlRegistration(
                                        registration.roomControlWorkflowImplementationTypes(), List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void failsClosedForMissingOrAmbiguousReadPorts() {
        assertThatThrownBy(() -> IntakeAuthorityWorkerRegistration.fromReadPorts(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one IntakeChildBridgeReadPort");
        assertThatThrownBy(
                        () ->
                                IntakeAuthorityWorkerRegistration.fromReadPorts(
                                        List.of(new StubReadPort(), new StubReadPort())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one IntakeChildBridgeReadPort");
    }

    @Test
    void reusesAnExistingBridgeAdapterButRejectsAnySecondBridge() {
        IntakeAuthorityWorkerRegistration registration =
                IntakeAuthorityWorkerRegistration.fromReadPorts(List.of(new StubReadPort()));
        IntakeAuthorityWorkerRegistration.V2BridgeActivityRegistration v2Bridge =
                v2Bridge(registration);

        IntakeAuthorityWorkerRegistration reused =
                IntakeAuthorityWorkerRegistration.fromAdapter(registration.bridgeActivities());

        assertThat(reused.bridgeActivities()).isSameAs(registration.bridgeActivities());
        assertThatThrownBy(
                        () ->
                                registration.validateCaseControlRegistration(
                                        registration.caseControlWorkflowImplementationTypes(),
                                        List.of(
                                                v2Bridge.activityImplementation(),
                                                v2Bridge.activityImplementation()),
                                        v2Bridge))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one authority-backed");
    }

    @Test
    void rejectsTheLegacyBridgeContractFromTheV2Registration() {
        IntakeAuthorityWorkerRegistration registration =
                IntakeAuthorityWorkerRegistration.fromReadPorts(List.of(new StubReadPort()));
        IntakeAuthorityWorkerRegistration.V2BridgeActivityRegistration v2Bridge =
                v2Bridge(registration);

        assertThatThrownBy(
                        () ->
                                registration.validateCaseControlRegistration(
                                        registration.caseControlWorkflowImplementationTypes(),
                                        List.of(
                                                v2Bridge.activityImplementation(),
                                                registration.bridgeActivities()),
                                        v2Bridge))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not register legacy");
        assertThatThrownBy(
                        () ->
                                registration.caseControlActivityImplementations(
                                        v2Bridge, registration.bridgeActivities()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not register legacy");
        assertThat(TestV2BridgeActivities.class.isAssignableFrom(IntakeChildBridgeActivities.class))
                .isFalse();
    }

    @Test
    void v2FacadeUsesOnlyTheAuthorityBridgeActivityNames() throws NoSuchMethodException {
        Class<TestV2BridgeActivities> v2Contract = TestV2BridgeActivities.class;

        assertThat(
                        v2Contract
                                .getMethod(
                                        "bindStart",
                                        IntakeChildBridgeActivities.StartRequest.class)
                                .getAnnotation(ActivityMethod.class)
                                .name())
                .isEqualTo("BindIntakeChildStartV2");
        assertThat(
                        v2Contract
                                .getMethod(
                                        "bindCommand",
                                        IntakeChildBridgeActivities.CommandRequest.class)
                                .getAnnotation(ActivityMethod.class)
                                .name())
                .isEqualTo("BindIntakeChildCommandV2");
        assertThat(
                        v2Contract
                                .getMethod(
                                        "bindDomainEvent",
                                        IntakeChildBridgeActivities.DomainEventRequest.class)
                                .getAnnotation(ActivityMethod.class)
                                .name())
                .isEqualTo("BindIntakeChildDomainEventV2");
    }

    @Test
    void rejectsForbiddenFormalAndIntakeRuntimeTypes() throws ClassNotFoundException {
        IntakeAuthorityWorkerRegistration registration =
                IntakeAuthorityWorkerRegistration.fromReadPorts(List.of(new StubReadPort()));
        IntakeAuthorityWorkerRegistration.V2BridgeActivityRegistration v2Bridge =
                v2Bridge(registration);

        assertThat(registration.forbiddenRuntimeTypeNames())
                .containsExactly(
                        "com.example.dispute.workflow.activity.intake.IntakeRoomActivitiesAdapter",
                        "com.example.dispute.workflow.application.intake.IntakeFormalCommitPort",
                        "com.example.dispute.workflow.application.intake.IntakeFormalBranchCommitPort",
                        "com.example.dispute.workflow.application.intake.IntakeGraphResultFinalizer");
        for (String forbiddenTypeName : registration.forbiddenRuntimeTypeNames()) {
            Class<?> forbiddenType = Class.forName(forbiddenTypeName);
            assertThatThrownBy(
                            () ->
                                    registration.caseControlActivityImplementations(
                                            v2Bridge, forbiddenType))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(forbiddenType.getSimpleName());
        }
    }

    @Test
    void exposesThePinnedV1AndAuthorityBackedV2HistoryPolicy() {
        IntakeAuthorityWorkerRegistration.HistoryCompatibilityPolicy policy =
                IntakeAuthorityWorkerRegistration.fromReadPorts(List.of(new StubReadPort()))
                        .historyCompatibility();

        assertThat(policy.versionMarker()).isEqualTo("typed-intake-bridge-authority-v1");
        assertThat(policy.v1ActivityNames())
                .containsExactly(
                        "BindIntakeChildStart",
                        "BindIntakeChildCommand",
                        "BindIntakeChildDomainEvent");
        assertThat(policy.v2ActivityNames())
                .containsExactly(
                        "BindIntakeChildStartV2",
                        "BindIntakeChildCommandV2",
                        "BindIntakeChildDomainEventV2");
        assertThat(policy.completedV1ActivityPolicy())
                .isEqualTo(
                        IntakeAuthorityWorkerRegistration.CompletedV1ActivityPolicy
                                .REPLAY_FROM_HISTORY);
        assertThat(policy.scheduledV1ActivityPolicy())
                .isEqualTo(
                        IntakeAuthorityWorkerRegistration.ScheduledV1ActivityPolicy
                                .PINNED_TO_OLD_WORKER_BUILD_UNTIL_DRAINED);
        assertThat(policy.ambiguousV1BackfillForbidden()).isTrue();
        assertThat(policy.missingV2AuthorityBindingFailsClosed()).isTrue();
        assertThat(policy.inEpochPartyRebindingForbidden()).isTrue();
    }

    private static final class StubReadPort implements IntakeChildBridgeReadPort {

        @Override
        public StartSource readStart(
                com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.StartRequest
                        request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommandSource readCommand(
                com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.CommandRequest
                        request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DomainEventSource readDomainEvent(
                com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities
                                .DomainEventRequest
                        request) {
            throw new UnsupportedOperationException();
        }
    }

    private static IntakeAuthorityWorkerRegistration.V2BridgeActivityRegistration v2Bridge(
            IntakeAuthorityWorkerRegistration registration) {
        return registration.authorityBackedV2Activity(
                new TestV2BridgeActivitiesAdapter(registration.bridgeActivities()),
                TestV2BridgeActivities.class);
    }

    @ActivityInterface
    private interface TestV2BridgeActivities {

        @ActivityMethod(name = "BindIntakeChildStartV2")
        IntakeChildBridgeActivities.StartBinding bindStart(
                IntakeChildBridgeActivities.StartRequest request);

        @ActivityMethod(name = "BindIntakeChildCommandV2")
        IntakeChildBridgeActivities.CommandBinding bindCommand(
                IntakeChildBridgeActivities.CommandRequest request);

        @ActivityMethod(name = "BindIntakeChildDomainEventV2")
        IntakeChildBridgeActivities.DomainEventBinding bindDomainEvent(
                IntakeChildBridgeActivities.DomainEventRequest request);
    }

    static final class TestV2BridgeActivitiesAdapter implements TestV2BridgeActivities {

        private final IntakeChildBridgeActivitiesAdapter delegate;

        private TestV2BridgeActivitiesAdapter(IntakeChildBridgeActivitiesAdapter delegate) {
            this.delegate = delegate;
        }

        public IntakeChildBridgeActivitiesAdapter delegate() {
            return delegate;
        }

        @Override
        public IntakeChildBridgeActivities.StartBinding bindStart(
                IntakeChildBridgeActivities.StartRequest request) {
            return delegate.bindStart(request);
        }

        @Override
        public IntakeChildBridgeActivities.CommandBinding bindCommand(
                IntakeChildBridgeActivities.CommandRequest request) {
            return delegate.bindCommand(request);
        }

        @Override
        public IntakeChildBridgeActivities.DomainEventBinding bindDomainEvent(
                IntakeChildBridgeActivities.DomainEventRequest request) {
            return delegate.bindDomainEvent(request);
        }
    }
}
