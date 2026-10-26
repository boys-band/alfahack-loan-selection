BEGIN;
SET LOCAL search_path = public, pg_catalog;

CREATE TABLE program_lifecycle_events (
    event_id uuid PRIMARY KEY,
    product_id varchar(64) NOT NULL,
    action text NOT NULL CHECK (action IN ('CREATED', 'ARCHIVED')),
    actor_id uuid NOT NULL,
    reason text NOT NULL CHECK (length(reason) BETWEEN 10 AND 2000),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    version text NOT NULL,
    request_fingerprint char(64),
    FOREIGN KEY (product_id, version) REFERENCES product_versions(product_id, version),
    CHECK ((action = 'CREATED' AND request_fingerprint IS NOT NULL) OR
           (action = 'ARCHIVED' AND request_fingerprint IS NULL))
);
CREATE UNIQUE INDEX program_lifecycle_one_creation
    ON program_lifecycle_events(product_id) WHERE action = 'CREATED';
CREATE UNIQUE INDEX program_lifecycle_one_archive
    ON program_lifecycle_events(product_id) WHERE action = 'ARCHIVED';
CREATE TRIGGER program_lifecycle_events_immutable BEFORE UPDATE OR DELETE ON program_lifecycle_events
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

COMMIT;
