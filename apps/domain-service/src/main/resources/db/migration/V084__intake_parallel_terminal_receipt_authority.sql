-- Bind a COMMITTED parallel Intake assembly to the immutable target receipt
-- produced inside the same caller-owned formal transaction.

alter table intake_parallel_frame_set
    add constraint fk_intake_parallel_frame_set_terminal_receipt
        foreign key (terminal_receipt_id)
        references production_runtime_finalization_receipt(receipt_id)
        deferrable initially deferred;
