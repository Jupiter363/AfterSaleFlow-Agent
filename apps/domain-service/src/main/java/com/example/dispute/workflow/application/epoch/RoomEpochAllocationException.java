package com.example.dispute.workflow.application.epoch;

public final class RoomEpochAllocationException extends IllegalStateException {

    private final String reasonCode;

    public RoomEpochAllocationException(String reasonCode, String message) {
        super(message);
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
