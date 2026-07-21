package com.example.dispute.workflow.temporal.caseprocess;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Authority-backed successor to {@link IntakeChildBridgeActivities}.
 *
 * <p>The request and response payloads intentionally remain wire-compatible with v1. Distinct
 * Temporal Activity names keep scheduled v1 Activity tasks pinned to the old worker build.
 */
@ActivityInterface
public interface IntakeChildBridgeActivitiesV2 {

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
