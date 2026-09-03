/**
 * 案件主流程的 Temporal 控制面。
 *
 * <p>入口链路：{@code SdkTemporalUpdateGateway} 以 Update-with-Start 投递案件命令，
 * {@code SdkRoomEpochProvisioningGateway} 以同一机制创建或切换房间 epoch；各房间子工作流再通过
 * 外部 Workflow stub 回传领域事件、目标房间进度和 Intake 的终态未提交回执。
 *
 * <p>处理链路：{@link com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl}
 * 在工作流内按序消费命令、事件和 provisioning 请求，并把耐久化读写交给活动实现，把实际业务执行
 * 委托给 Room Control、Intake 或 target typed room 子工作流。
 *
 * <p>观察与恢复链路：{@code SdkTemporalAuthoritativeProcessStateReader} 查询快照和 provisioning
 * commitment；{@code CaseDomainEventRecoveryRelay} 可以按至少一次语义重放领域事件。携带状态和
 * {@code Workflow.getVersion(...)} 共同保证 Continue-As-New 与历史 replay 不改变既有执行语义。
 */
package com.example.dispute.workflow.temporal.caseprocess;
