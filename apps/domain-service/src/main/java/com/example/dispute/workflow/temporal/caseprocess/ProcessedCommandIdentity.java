package com.example.dispute.workflow.temporal.caseprocess;

/**
 * 已受理命令写入父工作流近期命令窗口的最小身份三元组。
 *
 * <p>上游命令接入保存 {@code commandId}、序号和请求哈希；Temporal 重放及恢复路径据此判定同一命令
 * 是否已处理，避免重复命令再次产生业务副作用。
 */
public record ProcessedCommandIdentity(
        String commandId, long caseCommandSequence, String requestHash) {

    public ProcessedCommandIdentity {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be blank");
        }
        if (caseCommandSequence < 1) {
            throw new IllegalArgumentException("caseCommandSequence must be positive");
        }
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash is invalid");
        }
    }
}
