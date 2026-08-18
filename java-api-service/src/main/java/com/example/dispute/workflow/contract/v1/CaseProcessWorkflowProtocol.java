package com.example.dispute.workflow.contract.v1;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * CaseProcess 的跨进程 Temporal 协议常量与稳定 workflow identity 规则。
 *
 * <p>SDK update/provisioning gateway、房间子工作流、恢复 relay 和 CASE_CONTROL worker 都依赖这些名字；
 * 它们与 {@code CaseProcessWorkflow} 的注解一一对应，修改会影响已写入历史的 Update/Signal/Query 路由。
 * 下游 {@code caseWorkflowId}/{@code roomWorkflowId} 则为根流程及其房间 child 提供可重放的执行标识。
 */
public final class CaseProcessWorkflowProtocol {

  public static final String CASE_WORKFLOW_TYPE = "CaseProcessWorkflow";
  public static final String ROOM_WORKFLOW_TYPE = "RoomControlWorkflow";
  public static final String CASE_CONTROL_TASK_QUEUE = TemporalTaskQueues.CASE_CONTROL;
  public static final String ROOM_CONTROL_TASK_QUEUE = TemporalTaskQueues.ROOM_CONTROL;
  public static final String ACCEPT_COMMAND_UPDATE = "acceptCommand";
  public static final String PROVISION_ROOM_EPOCH_UPDATE = "provisionRoomEpoch";
  public static final String RECOVER_INTAKE_PROJECTION_COMPLETION_UPDATE =
      "recoverIntakeProjectionCompletion";
  public static final String RECOVER_EXPIRED_TARGET_EVIDENCE_TERMINAL_NO_COMMIT_UPDATE =
      "recoverExpiredTargetEvidenceTerminalNoCommit";
  public static final String DOMAIN_EVENT_SIGNAL = "domainEventCommitted";
  public static final String TARGET_ROOM_PROGRESS_SIGNAL = "targetRoomProgressed";
  public static final String TARGET_INTAKE_TERMINAL_NO_COMMIT_SIGNAL =
      "targetIntakeCommandTerminalNoCommit";
  public static final String RETRY_SEQUENCE_GAP_SIGNAL = "retrySequenceGap";
  public static final String REQUEST_CONTINUE_AS_NEW_SIGNAL = "requestContinueAsNew";
  public static final String PROCESS_STATE_QUERY = "processState";
  public static final String ROOM_COMMAND_SIGNAL = "roomCommandAccepted";
  public static final String ROOM_EVENT_SIGNAL = "roomDomainEventCommitted";
  public static final String ROOM_CLOSE_SIGNAL = "closeRoomControl";
  public static final String ROOM_STATE_QUERY = "roomControlState";
  public static final String ROOM_PROVISIONING_RECEIPT_QUERY = "roomProvisioningReceipt";
  public static final String ROOM_PROVISIONING_COMMITMENT_QUERY = "roomProvisioningCommitment";

  private CaseProcessWorkflowProtocol() {}

  /**
   * 根 CaseProcess workflowId 的唯一生成规则。命令网关、provisioning gateway、状态查询器及房间 child
   * 都通过它定位同一父流程；过长输入使用稳定 hash，避免不同调用端产生不一致的 Temporal execution。
   */
  public static String caseWorkflowId(String tenantSurrogate, String caseId) {
    requireText(tenantSurrogate, "tenantSurrogate");
    requireText(caseId, "caseId");
    String candidate = "case-process:" + tenantSurrogate + ":" + caseId;
    return candidate.length() <= 128 ? candidate : "case-process:" + sha256(candidate);
  }

  /**
   * 下游房间 child workflowId 的唯一生成规则。根流程在 provisioning/legacy 路径创建 child，房间切换和
   * Continue-As-New 恢复时以该标识重新绑定，从而把同一 case 的不同 epoch 隔离开。
   */
  public static String roomWorkflowId(String caseId, RoomType roomType, long roomEpoch) {
    requireText(caseId, "caseId");
    Objects.requireNonNull(roomType, "roomType must not be null");
    if (roomEpoch < 0) {
      throw new IllegalArgumentException("roomEpoch must not be negative");
    }
    String candidate = "room-workflow:" + caseId + ":" + roomType.name() + ":" + roomEpoch;
    return candidate.length() <= 128 ? candidate : "room-workflow:" + sha256(candidate);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
