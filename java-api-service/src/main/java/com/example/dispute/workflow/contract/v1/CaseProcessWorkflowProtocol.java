package com.example.dispute.workflow.contract.v1;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class CaseProcessWorkflowProtocol {

    public static final String CASE_WORKFLOW_TYPE = "CaseProcessWorkflow";
    public static final String ROOM_WORKFLOW_TYPE = "RoomControlWorkflow";
    public static final String CASE_CONTROL_TASK_QUEUE = "case-control";
    public static final String ROOM_CONTROL_TASK_QUEUE = "room-control";
    public static final String ACCEPT_COMMAND_UPDATE = "acceptCommand";
    public static final String DOMAIN_EVENT_SIGNAL = "domainEventCommitted";
    public static final String RETRY_SEQUENCE_GAP_SIGNAL = "retrySequenceGap";
    public static final String REQUEST_CONTINUE_AS_NEW_SIGNAL = "requestContinueAsNew";
    public static final String PROCESS_STATE_QUERY = "processState";
    public static final String ROOM_COMMAND_SIGNAL = "roomCommandAccepted";
    public static final String ROOM_EVENT_SIGNAL = "roomDomainEventCommitted";
    public static final String ROOM_CLOSE_SIGNAL = "closeRoomControl";
    public static final String ROOM_STATE_QUERY = "roomControlState";

    private CaseProcessWorkflowProtocol() {}

    public static String caseWorkflowId(String tenantSurrogate, String caseId) {
        requireText(tenantSurrogate, "tenantSurrogate");
        requireText(caseId, "caseId");
        String candidate = "case-process:" + tenantSurrogate + ":" + caseId;
        return candidate.length() <= 128 ? candidate : "case-process:" + sha256(candidate);
    }

    public static String roomWorkflowId(String caseId, RoomType roomType, long roomEpoch) {
        requireText(caseId, "caseId");
        Objects.requireNonNull(roomType, "roomType must not be null");
        if (roomEpoch < 0) {
            throw new IllegalArgumentException("roomEpoch must not be negative");
        }
        String candidate =
                "room-workflow:" + caseId + ":" + roomType.name() + ":" + roomEpoch;
        return candidate.length() <= 128 ? candidate : "room-workflow:" + sha256(candidate);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
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
