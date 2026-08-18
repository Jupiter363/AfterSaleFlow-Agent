package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 案件控制面的公开 Temporal Workflow 合同。
 *
 * <p>上游由命令 Update 网关、房间工作流和恢复调用方持有；下游由
 * {@link CaseProcessWorkflowImpl} 协调命令账本、房间子工作流与恢复 Activity。接口名和方法名
 * 是已写入 Temporal 历史的兼容边界，不能以普通 Java API 的方式随意改写。
 */
@WorkflowInterface
public interface CaseProcessWorkflow {

  /**
   * 上游：案件工作流启动方提供已验证的 {@link CaseProcessCarryState}。
   *
   * <p>Temporal 角色：主 WorkflowMethod；下游：实现恢复控制面状态，并继续驱动命令账本、房间子工作流
   * 和 Continue-As-New/replay 边界。
   */
  @WorkflowMethod(name = CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE)
  void run(CaseProcessCarryState carryState);

  /**
   * 上游：{@code SdkTemporalUpdateGateway} 提交已入账的案件命令。
   *
   * <p>Temporal 角色：同步 Update；下游：实现将命令纳入顺序处理并路由到当前房间，随后调用命令生命周期
   * Activity 和子工作流；重复 Update 由工作流的命令/replay 状态收敛。
   */
  @UpdateMethod(name = CaseProcessWorkflowProtocol.ACCEPT_COMMAND_UPDATE)
  void acceptCommand(CaseCommandRef command);

  /**
   * 上游：与 {@link #acceptCommand(CaseCommandRef)} 相同的命令 Update 调用方。
   *
   * <p>Temporal 角色：UpdateValidator；下游边界：拒绝的命令不会进入工作流 inbox，也不会触发路由
   * Activity 或子工作流副作用。
   */
  @UpdateValidatorMethod(updateName = CaseProcessWorkflowProtocol.ACCEPT_COMMAND_UPDATE)
  void validateAcceptCommand(CaseCommandRef command);

  /**
   * 上游：{@code SdkRoomEpochProvisioningGateway} 请求切换/创建房间 epoch。
   *
   * <p>Temporal 角色：同步 Update；下游：实现发布 epoch receipt/commitment 并启动或恢复匹配的房间
   * 子工作流，receipt 同时构成后续重放和补偿的锚点。
   */
  @UpdateMethod(name = CaseProcessWorkflowProtocol.PROVISION_ROOM_EPOCH_UPDATE)
  ProvisionRoomEpochReceipt provisionRoomEpoch(ProvisionRoomEpoch request);

  /**
   * 上游：与 {@link #provisionRoomEpoch(ProvisionRoomEpoch)} 相同的房间 epoch 调用方。
   *
   * <p>Temporal 角色：UpdateValidator；下游边界：只有通过 epoch、序列和现有子工作流权威校验的请求
   * 才能进入 provisioning 切换或产生可重放的 commitment。
   */
  @UpdateValidatorMethod(updateName = CaseProcessWorkflowProtocol.PROVISION_ROOM_EPOCH_UPDATE)
  void validateProvisionRoomEpoch(ProvisionRoomEpoch request);

  /**
   * 上游：持有 Intake 投影失败恢复权限的 CaseProcess 调用方。
   *
   * <p>Temporal 角色：恢复 Update；下游：实现仅在精确的 Intake 事件、子工作流、revision 与投影命令
   * 对齐时确认已消费投影，并把结果记为可幂等重放的恢复 commitment。
   */
  @UpdateMethod(
      name = CaseProcessWorkflowProtocol.RECOVER_INTAKE_PROJECTION_COMPLETION_UPDATE)
  default CaseProcessIntakeProjectionRecoveryResult recoverIntakeProjectionCompletion(
      CaseProcessIntakeProjectionRecoveryRequest request) {
    throw new UnsupportedOperationException(
        "Intake projection completion recovery is not supported by this CaseProcess workflow");
  }

  /**
   * 上游：与 {@link #recoverIntakeProjectionCompletion(CaseProcessIntakeProjectionRecoveryRequest)} 相同的恢复
   * 调用方。
   *
   * <p>Temporal 角色：恢复 UpdateValidator；下游边界：校验失败时不会消费缓冲领域事件、清除协议错误或
   * 调用投影完成路径。
   */
  @UpdateValidatorMethod(
      updateName = CaseProcessWorkflowProtocol.RECOVER_INTAKE_PROJECTION_COMPLETION_UPDATE)
  default void validateRecoverIntakeProjectionCompletion(
      CaseProcessIntakeProjectionRecoveryRequest request) {
    throw new UnsupportedOperationException(
        "Intake projection completion recovery is not supported by this CaseProcess workflow");
  }

  /**
   * 上游：持有过期 Evidence terminal-no-commit 恢复权限的 CaseProcess 调用方。
   *
   * <p>Temporal 角色：恢复 Update；下游：实现调用命令生命周期 Activity 固化精确 terminal receipt，
   * 清除对应 COMMAND 协议错误，并以 recoveryId 作为幂等重放锚点。
   */
  @UpdateMethod(
      name =
          CaseProcessWorkflowProtocol
              .RECOVER_EXPIRED_TARGET_EVIDENCE_TERMINAL_NO_COMMIT_UPDATE)
  default CaseProcessExpiredTargetEvidenceTerminalRecoveryResult
      recoverExpiredTargetEvidenceTerminalNoCommit(
          CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest request) {
    throw new UnsupportedOperationException(
        "expired target Evidence terminal recovery is not supported by this CaseProcess workflow");
  }

  /**
   * 上游：与 {@code recoverExpiredTargetEvidenceTerminalNoCommit} 相同的恢复调用方。
   *
   * <p>Temporal 角色：恢复 UpdateValidator；下游边界：只允许与当前 Evidence child、命令游标和失败
   * 原因完全一致的恢复请求进入 Activity 持久化路径。
   */
  @UpdateValidatorMethod(
      updateName =
          CaseProcessWorkflowProtocol
              .RECOVER_EXPIRED_TARGET_EVIDENCE_TERMINAL_NO_COMMIT_UPDATE)
  default void validateRecoverExpiredTargetEvidenceTerminalNoCommit(
      CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest request) {
    throw new UnsupportedOperationException(
        "expired target Evidence terminal recovery is not supported by this CaseProcess workflow");
  }

  /**
   * 上游：{@code CaseDomainEventRecoveryRelay} 等已提交领域事件 relay。
   *
   * <p>Temporal 角色：异步 Signal；下游：实现校验并缓存事件，按 sequence 驱动当前房间子工作流；越界
   * 或 inbox 饱和会留下可恢复的协议错误，而不是直接伪造投影结果。
   */
  @SignalMethod(name = CaseProcessWorkflowProtocol.DOMAIN_EVENT_SIGNAL)
  void domainEventCommitted(CaseDomainEventRef event);

  /**
   * 上游：Target Evidence/Hearing 房间工作流向父 CaseProcess 报告的进度 receipt。
   *
   * <p>Temporal 角色：异步 Signal；下游：实现只在 room epoch、fencing token 和 revision 均经校验且严格递增时更新
   * active-child 描述符，重复 receipt 不改变 replay 状态。
   */
  @SignalMethod(name = CaseProcessWorkflowProtocol.TARGET_ROOM_PROGRESS_SIGNAL)
  void targetRoomProgressed(TargetRoomProgressReceipt receipt);

  /**
   * 上游：{@code IntakeRoomWorkflowImpl} 在 Target Intake terminal-no-commit 时发送的权威对象。
   *
   * <p>Temporal 角色：异步 Signal；下游：实现把对象放入命令收敛队列，随后由命令生命周期 Activity 写入
   * command/projection receipt；无效或拥塞对象停在恢复边界而不生成成功终态。
   */
  @SignalMethod(name = CaseProcessWorkflowProtocol.TARGET_INTAKE_TERMINAL_NO_COMMIT_SIGNAL)
  default void targetIntakeCommandTerminalNoCommit(
      TargetIntakeCommandTerminalNoCommit authority) {
    throw new UnsupportedOperationException(
        "target Intake terminal-no-commit convergence is not supported");
  }

  /**
   * 上游：发现命令或领域事件 sequence 缺口的控制面恢复调用方。
   *
   * <p>Temporal 角色：异步 Signal；下游：实现请求下一轮账本重读/缺口处理，具体持久化问题由
   * {@code CaseProcessLedgerActivities.reportSequenceGap} 记录。
   */
  @SignalMethod(name = CaseProcessWorkflowProtocol.RETRY_SEQUENCE_GAP_SIGNAL)
  void retrySequenceGap();

  /**
   * 上游：需要截断工作流历史的控制面调用方。
   *
   * <p>Temporal 角色：异步 Signal；下游：实现只在没有活动恢复时设置 Continue-As-New 边界，并将 carry
   * state 带入下一次执行以保持重放坐标。
   */
  @SignalMethod(name = CaseProcessWorkflowProtocol.REQUEST_CONTINUE_AS_NEW_SIGNAL)
  void requestContinueAsNew();

  /**
   * 上游：{@code SdkTemporalAuthoritativeProcessStateReader} 等控制面读取方。
   *
   * <p>Temporal 角色：只读 Query；下游边界：返回当前内存中的流程权威快照，不写仓库、不改变工作流
   * 历史，也不触发子工作流。
   */
  @QueryMethod(name = CaseProcessWorkflowProtocol.PROCESS_STATE_QUERY)
  CaseProcessSnapshot state();

  /**
   * 上游：房间 provisioning/观测读取方。
   *
   * <p>Temporal 角色：只读 Query；下游边界：暴露最后确认的 epoch receipt，供调用方判断是否可安全重放
   * provisioning Update，不产生新的 child 或持久化副作用。
   */
  @QueryMethod(name = CaseProcessWorkflowProtocol.ROOM_PROVISIONING_RECEIPT_QUERY)
  ProvisionRoomEpochReceipt provisioningReceipt();

  /**
   * 上游：房间 provisioning/观测读取方。
   *
   * <p>Temporal 角色：只读 Query；下游边界：返回当前 commitment 及其 replay/补偿锚点，不改变流程或
   * 仓库状态。
   */
  @QueryMethod(name = CaseProcessWorkflowProtocol.ROOM_PROVISIONING_COMMITMENT_QUERY)
  ProvisioningCommitment provisioningCommitment();
}
