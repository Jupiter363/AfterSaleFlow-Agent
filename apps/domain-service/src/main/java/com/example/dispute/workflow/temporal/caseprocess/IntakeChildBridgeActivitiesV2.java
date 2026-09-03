package com.example.dispute.workflow.temporal.caseprocess;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * {@link IntakeChildBridgeActivities} 的 authority-backed 后继 Temporal Activity 合同。
 *
 * <p>上游：CaseProcessWorkflowImpl 在 bridge version 切换后调用；Temporal 角色：Activity；下游：
 * {@code IntakeChildBridgeActivitiesV2Adapter} 返回与 v1 wire-compatible 的 Intake child binding。独立的
 * Activity 名称让历史中已调度的 v1 task 保持在旧 worker build，形成版本切换的 replay 边界。
 */
@ActivityInterface
public interface IntakeChildBridgeActivitiesV2 {

    /**
     * 上游：CaseProcessWorkflowImpl 的 v2 provisioning 分支；Temporal 角色：Activity；下游：生成与 v1
     * 兼容的 {@link IntakeChildBridgeActivities.StartBinding}，供 child start/replay 使用。
     */
    @ActivityMethod(name = "BindIntakeChildStartV2")
    IntakeChildBridgeActivities.StartBinding bindStart(
            IntakeChildBridgeActivities.StartRequest request);

    /**
     * 上游：CaseProcessWorkflowImpl 的 v2 Intake 命令路由分支；Temporal 角色：Activity；下游：返回
     * hash/revision 绑定的 v1-compatible command payload，避免历史 v1 task 被错误重解释。
     */
    @ActivityMethod(name = "BindIntakeChildCommandV2")
    IntakeChildBridgeActivities.CommandBinding bindCommand(
            IntakeChildBridgeActivities.CommandRequest request);

    /**
     * 上游：CaseProcessWorkflowImpl 的 v2 Intake 领域事件派发分支；Temporal 角色：Activity；下游：返回
     * v1-compatible event binding，使 child event cursor 与 CaseProcess replay 坐标保持一致。
     */
    @ActivityMethod(name = "BindIntakeChildDomainEventV2")
    IntakeChildBridgeActivities.DomainEventBinding bindDomainEvent(
            IntakeChildBridgeActivities.DomainEventRequest request);
}
