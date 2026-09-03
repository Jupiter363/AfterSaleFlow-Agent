package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.util.Objects;

/**
 * 一份 Java formal target-room 回执回传给父工作流时使用的有界、重放稳定值对象。
 *
 * <p>上游子房间执行产生回执并通过父工作流回调送达；父流程把最近回执放入携带状态，以 epoch、fence、
 * revision 和回执哈希抵御重复回调，供后续房间推进和投影观察消费。
 */
public record TargetRoomProgressReceipt(
    RoomType roomType,
    long roomEpoch,
    long fencingToken,
    long processRevision,
    long roomRevision,
    String javaReceiptId,
    String javaReceiptHash) {
  public TargetRoomProgressReceipt {
    roomType = Objects.requireNonNull(roomType, "roomType");
    if (roomEpoch < 0 || fencingToken < 1 || processRevision < 0 || roomRevision < 0
        || javaReceiptId == null || javaReceiptId.isBlank()
        || javaReceiptHash == null || !javaReceiptHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("target room progress receipt is invalid");
    }
  }
}
