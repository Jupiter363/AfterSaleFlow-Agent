ALTER TABLE fulfillment_dispute_case
    ADD COLUMN IF NOT EXISTS import_request_fingerprint VARCHAR(64);
