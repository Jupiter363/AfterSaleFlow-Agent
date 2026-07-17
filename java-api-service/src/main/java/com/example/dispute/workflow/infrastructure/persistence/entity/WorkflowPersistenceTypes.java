package com.example.dispute.workflow.infrastructure.persistence.entity;

public final class WorkflowPersistenceTypes {

    private WorkflowPersistenceTypes() {}

    public enum CommandStatus {
        PENDING_ORCHESTRATION,
        ORCHESTRATION_ACCEPTED,
        APPLIED,
        REJECTED,
        FAILED,
        EXPIRED
    }

    public enum DeliveryKind {
        UPDATE_WITH_START,
        UPDATE,
        SIGNAL
    }

    public enum OutboxStatus {
        PENDING,
        CLAIMED,
        RETRY,
        DELIVERED,
        DEAD_LETTER
    }

    public enum EpochLifecycleStatus {
        ACTIVE,
        TERMINAL
    }

    public enum OperationStatus {
        STARTED,
        COMPLETED,
        FAILED,
        COMPENSATION_REQUIRED,
        COMPENSATED
    }

    public enum ReconciliationScope {
        SHADOW,
        PROJECTION,
        COMMAND,
        OPERATION
    }

    public enum ReconciliationSeverity {
        INFO,
        WARNING,
        ERROR,
        CRITICAL
    }

    public enum ReconciliationStatus {
        OPEN,
        ACKNOWLEDGED,
        RESOLVED
    }

    public enum ManifestTerminalStatus {
        COMPLETED,
        FAILED,
        ABORTED,
        LEGACY_IMPORTED
    }
}
