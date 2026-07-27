package com.example.dispute.workflow.config;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.CASE_CONTROL;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.ROOM_CONTROL;

import com.example.dispute.workflow.activity.domain.IntakeChildBridgeActivitiesAdapter;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeActivitiesV2Adapter;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivitiesV2;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflow;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflowImpl;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflow;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflowImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Pure worker-registration descriptor for the authority-backed Intake child bridge.
 *
 * <p>This class deliberately does not create a Temporal {@code Worker}, {@code WorkerFactory}, or
 * client. The primary worker configuration owns runtime assembly and uses this descriptor to
 * validate its concrete registrations before it starts a worker.
 */
public final class IntakeAuthorityWorkerRegistration {

    public static final String VERSION_MARKER = "typed-intake-bridge-authority-v1";

    private static final List<Class<?>> CASE_CONTROL_WORKFLOW_TYPES =
            List.of(CaseProcessWorkflowImpl.class);
    private static final List<Class<?>> ROOM_CONTROL_WORKFLOW_TYPES =
            List.of(RoomControlWorkflowImpl.class, IntakeRoomWorkflowImpl.class);
    private static final List<String> FORBIDDEN_RUNTIME_TYPE_NAMES =
            List.of(
                    "com.example.dispute.workflow.activity.intake.IntakeRoomActivitiesAdapter",
                    "com.example.dispute.workflow.application.intake.IntakeFormalCommitPort",
                    "com.example.dispute.workflow.application.intake.IntakeFormalBranchCommitPort",
                    "com.example.dispute.workflow.application.intake.IntakeGraphResultFinalizer");
    private static final HistoryCompatibilityPolicy HISTORY_COMPATIBILITY =
            new HistoryCompatibilityPolicy(
                    VERSION_MARKER,
                    List.of(
                            "BindIntakeChildStart",
                            "BindIntakeChildCommand",
                            "BindIntakeChildDomainEvent"),
                    List.of(
                            "BindIntakeChildStartV2",
                            "BindIntakeChildCommandV2",
                            "BindIntakeChildDomainEventV2"),
                    CompletedV1ActivityPolicy.REPLAY_FROM_HISTORY,
                    ScheduledV1ActivityPolicy.PINNED_TO_OLD_WORKER_BUILD_UNTIL_DRAINED,
                    true,
                    true,
                    true);

    private final IntakeChildBridgeActivitiesAdapter bridgeActivities;

    private IntakeAuthorityWorkerRegistration(IntakeChildBridgeActivitiesAdapter bridgeActivities) {
        this.bridgeActivities = Objects.requireNonNull(bridgeActivities, "bridgeActivities");
        validateWorkflowCompatibility();
        requireNoForbiddenRuntimeTypes(List.of(bridgeActivities));
    }

    /**
     * Resolves one Spring-managed read port. Missing and ambiguous beans both fail closed.
     */
    public static IntakeAuthorityWorkerRegistration fromReadPortProvider(
            ObjectProvider<IntakeChildBridgeReadPort> readPortProvider) {
        if (readPortProvider == null) {
            throw missingOrAmbiguousReadPort();
        }
        return fromReadPort(readPortProvider.getIfUnique());
    }

    /**
     * Resolves one read port from a collection, which is convenient for pure unit tests.
     */
    public static IntakeAuthorityWorkerRegistration fromReadPorts(
            Collection<? extends IntakeChildBridgeReadPort> readPorts) {
        if (readPorts == null || readPorts.size() != 1) {
            throw missingOrAmbiguousReadPort();
        }
        return fromReadPort(readPorts.iterator().next());
    }

    /**
     * Reuses an already-constructed authority-backed bridge adapter.
     */
    public static IntakeAuthorityWorkerRegistration fromAdapter(
            IntakeChildBridgeActivitiesAdapter bridgeActivities) {
        if (bridgeActivities == null) {
            throw new IllegalStateException(
                    "Intake authority worker registration requires one IntakeChildBridgeActivitiesAdapter");
        }
        return new IntakeAuthorityWorkerRegistration(bridgeActivities);
    }

    private static IntakeAuthorityWorkerRegistration fromReadPort(IntakeChildBridgeReadPort readPort) {
        if (readPort == null) {
            throw missingOrAmbiguousReadPort();
        }
        return fromAdapter(new IntakeChildBridgeActivitiesAdapter(readPort));
    }

    public String caseControlTaskQueue() {
        return CASE_CONTROL;
    }

    public String roomControlTaskQueue() {
        return ROOM_CONTROL;
    }

    public List<Class<?>> caseControlWorkflowImplementationTypes() {
        return CASE_CONTROL_WORKFLOW_TYPES;
    }

    public List<Class<?>> roomControlWorkflowImplementationTypes() {
        return ROOM_CONTROL_WORKFLOW_TYPES;
    }

    public IntakeChildBridgeActivitiesAdapter bridgeActivities() {
        return bridgeActivities;
    }

    /**
     * Validates a primary-owned v2 facade that delegates to this registration's read-only bridge.
     */
    public V2BridgeActivityRegistration authorityBackedV2Activity(
            IntakeChildBridgeActivitiesV2Adapter v2ActivityImplementation,
            Class<IntakeChildBridgeActivitiesV2> v2ActivityContract) {
        if (v2ActivityImplementation == null || v2ActivityContract == null) {
            throw new IllegalStateException("CASE_CONTROL requires an authority-backed v2 bridge facade");
        }
        requireV2ActivityContract(v2ActivityContract);
        requireDelegate(v2ActivityImplementation, bridgeActivities);
        return new V2BridgeActivityRegistration(
                v2ActivityImplementation, v2ActivityContract, bridgeActivities);
    }

    /**
     * Returns existing CASE_CONTROL activities plus exactly one validated authority-backed v2
     * bridge facade. The v1 bridge interface is forbidden from this registration.
     */
    public List<Object> caseControlActivityImplementations(
            V2BridgeActivityRegistration v2BridgeActivity, Object... existingActivities) {
        requireV2BridgeRegistration(v2BridgeActivity);
        List<Object> activities = new ArrayList<>();
        if (existingActivities != null) {
            for (Object activity : existingActivities) {
                if (activity == null) {
                    throw new IllegalStateException("CASE_CONTROL activity implementation must not be null");
                }
                activities.add(activity);
            }
        }
        requireNoForbiddenRuntimeTypes(activities);
        if (activities.stream().anyMatch(IntakeChildBridgeActivities.class::isInstance)
                || activities.stream().anyMatch(this::hasV2BridgeActivityContract)) {
            throw new IllegalStateException(
                    "CASE_CONTROL accepts exactly one authority-backed v2 Intake child bridge facade");
        }
        activities.add(v2BridgeActivity.activityImplementation());
        validateCaseControlRegistration(
                CASE_CONTROL_WORKFLOW_TYPES, activities, v2BridgeActivity);
        return List.copyOf(activities);
    }

    /**
     * Validates the primary-owned CASE_CONTROL registration before it is applied to a worker.
     */
    public void validateCaseControlRegistration(
            Collection<Class<?>> workflowImplementationTypes,
            Collection<?> activityImplementations,
            V2BridgeActivityRegistration v2BridgeActivity) {
        requireV2BridgeRegistration(v2BridgeActivity);
        List<Class<?>> workflows = requiredValues(workflowImplementationTypes, "CASE_CONTROL workflow");
        List<?> activities = requiredValues(activityImplementations, "CASE_CONTROL activity");
        requireNoForbiddenRuntimeTypes(workflows);
        requireNoForbiddenRuntimeTypes(activities);
        requireRequiredWorkflowTypes(workflows, CASE_CONTROL_WORKFLOW_TYPES, CASE_CONTROL);

        if (activities.stream().anyMatch(IntakeChildBridgeActivities.class::isInstance)) {
            throw new IllegalStateException(
                    "CASE_CONTROL must not register legacy IntakeChildBridgeActivities on the v2 path");
        }
        List<Object> bridges =
                activities.stream()
                        .filter(this::hasV2BridgeActivityContract)
                        .map(Object.class::cast)
                        .toList();
        if (bridges.size() != 1 || bridges.getFirst() != v2BridgeActivity.activityImplementation()) {
            throw new IllegalStateException(
                    "CASE_CONTROL requires exactly one authority-backed v2 Intake child bridge facade");
        }
    }

    /**
     * Validates the primary-owned ROOM_CONTROL workflow and activity registration.
     */
    public void validateRoomControlRegistration(
            Collection<Class<?>> workflowImplementationTypes,
            Collection<?> activityImplementations) {
        List<Class<?>> workflows = requiredValues(workflowImplementationTypes, "ROOM_CONTROL workflow");
        List<?> activities = requiredValues(activityImplementations, "ROOM_CONTROL activity");
        requireNoForbiddenRuntimeTypes(workflows);
        requireNoForbiddenRuntimeTypes(activities);
        requireRequiredWorkflowTypes(workflows, ROOM_CONTROL_WORKFLOW_TYPES, ROOM_CONTROL);
    }

    public HistoryCompatibilityPolicy historyCompatibility() {
        return HISTORY_COMPATIBILITY;
    }

    public List<String> forbiddenRuntimeTypeNames() {
        return FORBIDDEN_RUNTIME_TYPE_NAMES;
    }

    /**
     * Rejects activity, workflow, port, and finalizer types that are unreachable at this gate.
     */
    public static void requireNoForbiddenRuntimeTypes(Collection<?> registrations) {
        if (registrations == null) {
            throw new IllegalStateException("worker registrations must not be null");
        }
        for (Object registration : registrations) {
            if (registration == null) {
                throw new IllegalStateException("worker registration must not be null");
            }
            Class<?> runtimeType =
                    registration instanceof Class<?> registeredType
                            ? registeredType
                            : registration.getClass();
            String forbiddenTypeName = forbiddenRuntimeTypeName(runtimeType);
            if (forbiddenTypeName != null) {
                throw new IllegalStateException(
                        "forbidden Intake runtime type cannot be registered: "
                                + simpleName(forbiddenTypeName));
            }
        }
    }

    private static String forbiddenRuntimeTypeName(Class<?> runtimeType) {
        if (FORBIDDEN_RUNTIME_TYPE_NAMES.contains(runtimeType.getName())) {
            return runtimeType.getName();
        }
        for (Class<?> interfaceType : runtimeType.getInterfaces()) {
            String forbiddenInterface = forbiddenRuntimeTypeName(interfaceType);
            if (forbiddenInterface != null) {
                return forbiddenInterface;
            }
        }
        Class<?> parentType = runtimeType.getSuperclass();
        return parentType == null ? null : forbiddenRuntimeTypeName(parentType);
    }

    private static String simpleName(String typeName) {
        return typeName.substring(typeName.lastIndexOf('.') + 1);
    }

    private void requireV2BridgeRegistration(V2BridgeActivityRegistration registration) {
        if (registration == null
                || registration.backingAdapter() != bridgeActivities
                || registration.activityContract() != IntakeChildBridgeActivitiesV2.class
                || registration.activityImplementation().delegate() != bridgeActivities) {
            throw new IllegalStateException("CASE_CONTROL requires an authority-backed v2 bridge facade");
        }
    }

    private static void requireV2ActivityContract(
            Class<IntakeChildBridgeActivitiesV2> activityContract) {
        if (activityContract != IntakeChildBridgeActivitiesV2.class) {
            throw new IllegalStateException(
                    "v2 bridge facade must declare exactly the authority-backed v2 Activity names");
        }
    }

    private boolean hasV2BridgeActivityContract(Object activityImplementation) {
        return activityImplementation instanceof IntakeChildBridgeActivitiesV2;
    }

    private static void requireDelegate(
            IntakeChildBridgeActivitiesV2Adapter v2ActivityImplementation,
            IntakeChildBridgeActivitiesAdapter expectedDelegate) {
        if (v2ActivityImplementation.delegate() != expectedDelegate) {
            throw invalidV2Delegate();
        }
    }

    private static IllegalStateException invalidV2Delegate() {
        return new IllegalStateException(
                "v2 bridge facade must expose the exact authority-backed IntakeChildBridgeActivitiesAdapter delegate");
    }

    public static void validateWorkflowCompatibility() {
        requireWorkflowContract(CaseProcessWorkflowImpl.class, CaseProcessWorkflow.class);
        requireWorkflowContract(RoomControlWorkflowImpl.class, RoomControlWorkflow.class);
        requireWorkflowContract(IntakeRoomWorkflowImpl.class, IntakeRoomWorkflow.class);
    }

    private static IllegalStateException missingOrAmbiguousReadPort() {
        return new IllegalStateException(
                "Intake authority worker registration requires exactly one IntakeChildBridgeReadPort");
    }

    private static void requireWorkflowContract(
            Class<?> implementationType, Class<?> workflowContractType) {
        if (!workflowContractType.isAssignableFrom(implementationType)) {
            throw new IllegalStateException(
                    implementationType.getSimpleName()
                            + " must implement "
                            + workflowContractType.getSimpleName());
        }
    }

    private static void requireRequiredWorkflowTypes(
            Collection<Class<?>> actualTypes, Collection<Class<?>> requiredTypes, String taskQueue) {
        boolean missingRequiredType = requiredTypes.stream()
                .anyMatch(requiredType -> actualTypes.stream()
                        .noneMatch(requiredType::isAssignableFrom));
        if (missingRequiredType) {
            throw new IllegalStateException(
                    taskQueue + " is missing required Intake workflow implementation types");
        }
    }

    private static <T> List<T> requiredValues(Collection<T> values, String label) {
        if (values == null || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException(label + " registrations must not be null");
        }
        return List.copyOf(values);
    }

    public enum CompletedV1ActivityPolicy {
        REPLAY_FROM_HISTORY
    }

    public enum ScheduledV1ActivityPolicy {
        PINNED_TO_OLD_WORKER_BUILD_UNTIL_DRAINED
    }

    /**
     * Immutable compatibility rules for the v1 bridge drain and authority-backed v2 path.
     */
    public record HistoryCompatibilityPolicy(
            String versionMarker,
            List<String> v1ActivityNames,
            List<String> v2ActivityNames,
            CompletedV1ActivityPolicy completedV1ActivityPolicy,
            ScheduledV1ActivityPolicy scheduledV1ActivityPolicy,
            boolean ambiguousV1BackfillForbidden,
            boolean missingV2AuthorityBindingFailsClosed,
            boolean inEpochPartyRebindingForbidden) {

        public HistoryCompatibilityPolicy {
            versionMarker = Objects.requireNonNull(versionMarker, "versionMarker");
            v1ActivityNames = List.copyOf(v1ActivityNames);
            v2ActivityNames = List.copyOf(v2ActivityNames);
            completedV1ActivityPolicy =
                    Objects.requireNonNull(completedV1ActivityPolicy, "completedV1ActivityPolicy");
            scheduledV1ActivityPolicy =
                    Objects.requireNonNull(scheduledV1ActivityPolicy, "scheduledV1ActivityPolicy");
            if (v1ActivityNames.isEmpty() || v2ActivityNames.isEmpty()) {
                throw new IllegalArgumentException("bridge activity names must not be empty");
            }
        }
    }

    /**
     * A checked, primary-owned v2 Activity facade suitable for CASE_CONTROL registration.
     */
    public static final class V2BridgeActivityRegistration {

        private final IntakeChildBridgeActivitiesV2Adapter activityImplementation;
        private final Class<IntakeChildBridgeActivitiesV2> activityContract;
        private final IntakeChildBridgeActivitiesAdapter backingAdapter;

        private V2BridgeActivityRegistration(
                IntakeChildBridgeActivitiesV2Adapter activityImplementation,
                Class<IntakeChildBridgeActivitiesV2> activityContract,
                IntakeChildBridgeActivitiesAdapter backingAdapter) {
            this.activityImplementation = activityImplementation;
            this.activityContract = activityContract;
            this.backingAdapter = backingAdapter;
        }

        public IntakeChildBridgeActivitiesV2Adapter activityImplementation() {
            return activityImplementation;
        }

        public Class<IntakeChildBridgeActivitiesV2> activityContract() {
            return activityContract;
        }

        public IntakeChildBridgeActivitiesAdapter backingAdapter() {
            return backingAdapter;
        }
    }
}
