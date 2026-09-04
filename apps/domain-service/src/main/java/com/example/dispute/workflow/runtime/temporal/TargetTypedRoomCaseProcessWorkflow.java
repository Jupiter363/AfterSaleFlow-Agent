package com.example.dispute.workflow.runtime.temporal;

import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildDescriptor;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;

/**
 * Mandatory production-only extension point for dynamic typed-room child creation and replay restore.
 * Concrete implementations live outside the default source set and must provide both operations.
 */
public abstract class TargetTypedRoomCaseProcessWorkflow extends CaseProcessWorkflowImpl {

    @Override
    protected abstract TargetTypedRoomChildHandle startTargetTypedRoomChild(
            ProvisionRoomEpoch request, String provisioningHash);

    @Override
    protected abstract TargetTypedRoomChildHandle restoreTargetTypedRoomChild(
            ActiveChildDescriptor descriptor);
}
