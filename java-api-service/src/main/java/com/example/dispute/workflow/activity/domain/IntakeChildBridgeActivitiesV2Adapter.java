package com.example.dispute.workflow.activity.domain;

import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivitiesV2;
import java.util.Objects;

/** Delegates the authority-backed v2 Activity names to the read-only bridge implementation. */
public final class IntakeChildBridgeActivitiesV2Adapter implements IntakeChildBridgeActivitiesV2 {

    private final IntakeChildBridgeActivitiesAdapter delegate;

    public IntakeChildBridgeActivitiesV2Adapter(IntakeChildBridgeActivitiesAdapter delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
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
