package com.example.dispute.workflow.application.intake;

import java.util.Optional;

/** Domain PostgreSQL port. Implementations must atomically replay exact hashes and reject drift. */
public interface IntakeGraphBindingStore {

    Optional<IntakeGraphThreadBinding> findRegistration(String registrationId);

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
}
