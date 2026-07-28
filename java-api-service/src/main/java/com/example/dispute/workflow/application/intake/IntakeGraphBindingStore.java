package com.example.dispute.workflow.application.intake;

import java.util.Optional;

/** Domain PostgreSQL port. Implementations must atomically replay exact hashes and reject drift. */
public interface IntakeGraphBindingStore {

    Optional<IntakeGraphThreadBinding> findRegistration(String registrationId);

    /**
     * Locks the private thread row for the caller transaction and returns its one initial
     * snapshot when it has already been established. Target Intake uses this to ensure that
     * exactly one caller publishes the initialization material for an actor thread.
     */
    default ThreadSnapshotState lockThreadSnapshotState(String registrationId) {
        throw new UnsupportedOperationException("thread snapshot locking is not configured");
    }

    /**
     * Allocates the next event sequence while the private thread row remains locked until the
     * surrounding transaction completes. Exact event/message replays return the original event.
     */
    default EventAllocation allocateEvent(String registrationId, String eventId, String messageId) {
        throw new UnsupportedOperationException("event allocation is not configured");
    }

    WriteReceipt<IntakeGraphThreadBinding> register(IntakeGraphThreadBinding binding);

    WriteReceipt<IntakeSnapshotReference> bindInitialSnapshot(IntakeSnapshotReference reference);

    WriteReceipt<IntakeEventReference> bindEvent(IntakeEventReference reference);

    record WriteReceipt<T>(T value, boolean created) {
        public WriteReceipt {
            if (value == null) {
                throw new IllegalArgumentException("value must not be null");
            }
        }

        public static <T> WriteReceipt<T> created(T value) {
            return new WriteReceipt<>(value, true);
        }

        public static <T> WriteReceipt<T> replayed(T value) {
            return new WriteReceipt<>(value, false);
        }
    }

    record ThreadSnapshotState(
            IntakeGraphThreadBinding thread, Optional<IntakeSnapshotReference> initialSnapshot) {
        public ThreadSnapshotState {
            if (thread == null || initialSnapshot == null) {
                throw new IllegalArgumentException("thread and initialSnapshot must not be null");
            }
        }
    }

    record EventAllocation(long sequenceNo, Optional<IntakeEventReference> existing) {
        public EventAllocation {
            if (sequenceNo <= 0 || existing == null) {
                throw new IllegalArgumentException("sequenceNo must be positive and existing must not be null");
            }
            existing.ifPresent(event -> {
                if (event.sequenceNo() != sequenceNo) {
                    throw new IllegalArgumentException("replayed event sequence differs from allocation");
                }
            });
        }

        public boolean replayed() {
            return existing.isPresent();
        }
    }
}
