package com.example.dispute.workflow.infrastructure.persistence.entity;

public final class WorkflowPersistenceTypes {

    private WorkflowPersistenceTypes() {}

    public enum CommandStatus {
        PENDING_ORCHESTRATION,
        ORCHESTRATION_ACCEPTED,
        APPLIED,
        SHADOW_COMPLETED,
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
        RECONCILED,
        DEAD_LETTER
    }

    public enum EpochLifecycleStatus {
        PREPARING,
        PROVISIONING,
        ACTIVE,
        PROVISIONING_FAILED,
        TERMINAL
    }

    public enum EpochProvisioningStatus {
        NOT_REQUIRED,
        PENDING,
        PROVISIONING,
        READY,
        FAILED
    }

    public enum WriterActivationStatus {
        PREPARING,
        PROVISIONING,
        READY,
        FAILED,
        TERMINAL
    }

    public enum BootstrapOutboxStatus {
        PENDING,
        CLAIMED,
        RETRY,
        DELIVERED,
        RECONCILED,
        DEAD_LETTER
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
