-- Apply as verification_owner; do not grant schema_migrations to verification_app.
BEGIN;
ALTER TABLE verification_results
    ADD COLUMN source_payloads jsonb NOT NULL DEFAULT '{}'
        CHECK (jsonb_typeof(source_payloads) = 'object'),
    ADD COLUMN result_payload jsonb
        CHECK (result_payload IS NULL OR jsonb_typeof(result_payload) = 'object');
-- Existing 001 results remain readable via their original typed columns.
COMMIT;
