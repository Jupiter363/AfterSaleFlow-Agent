package com.example.dispute.workflow.targete2e.temporal;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Target-only source sets contribute the dynamic typed-room dispatcher and the three additional
 * typed room implementations through this registration. Intake remains part of the existing room
 * worker. The normal artifact has no provider and keeps its original workers.
 */
public interface TargetTemporalWorkerRegistration {

    Registration registration();

    record Registration(
            String profile,
            String executionLane,
            String activationId,
            String controlBuildId,
            Class<? extends TargetTypedRoomCaseProcessWorkflow>
                    caseProcessWorkflowImplementation,
            List<Class<?>> roomWorkflowImplementations,
            List<Object> caseControlActivities,
            List<Object> roomControlActivities) {

        private static final Pattern ACTIVATION_ID =
                Pattern.compile("p9act\\.v1\\.[0-9a-f]{32}");

        public Registration {
            requireExact(profile, TargetRoomEpochSelectionAuthority.PROFILE, "profile");
            requireExact(
                    executionLane,
                    TargetRoomEpochSelectionAuthority.EXECUTION_LANE,
                    "executionLane");
            if (activationId == null || !ACTIVATION_ID.matcher(activationId).matches()) {
                throw new IllegalArgumentException("activationId is invalid");
            }
            requireText(controlBuildId, "controlBuildId");
            Objects.requireNonNull(
                    caseProcessWorkflowImplementation,
                    "caseProcessWorkflowImplementation must not be null");
            if (Modifier.isAbstract(caseProcessWorkflowImplementation.getModifiers())) {
                throw new IllegalArgumentException(
                        "target registration requires a concrete target-only CaseProcess dispatcher");
            }
            roomWorkflowImplementations = List.copyOf(roomWorkflowImplementations);
            caseControlActivities = List.copyOf(caseControlActivities);
            roomControlActivities = List.copyOf(roomControlActivities);
            if (roomWorkflowImplementations.size() != 3) {
                throw new IllegalArgumentException(
                        "target registration requires exactly three additional typed room Workflow implementations");
            }
            if (roomWorkflowImplementations.stream().distinct().count()
                    != roomWorkflowImplementations.size()) {
                throw new IllegalArgumentException(
                        "target room Workflow implementation types must be unique");
            }
            if (roomWorkflowImplementations.stream()
                    .anyMatch(type -> Modifier.isAbstract(type.getModifiers()))) {
                throw new IllegalArgumentException(
                        "target room Workflow implementation types must be concrete");
            }
            if (!roomWorkflowImplementations.containsAll(
                            TargetTypedRoomProtocol.additionalWorkflowImplementations())
                    || !TargetTypedRoomProtocol.additionalWorkflowImplementations()
                            .containsAll(roomWorkflowImplementations)) {
                throw new IllegalArgumentException(
                        "target registration requires the exact Evidence, Hearing, and Outcome Workflow implementations");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireExact(String value, String expected, String field) {
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(field + " must be " + expected);
        }
    }
}
