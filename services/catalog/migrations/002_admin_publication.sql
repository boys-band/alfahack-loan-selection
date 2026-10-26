BEGIN;
SET LOCAL search_path = public, pg_catalog;

CREATE TABLE product_publications (
    product_id varchar(64) NOT NULL,
    version text NOT NULL,
    actor_id uuid NOT NULL,
    reason text NOT NULL CHECK (length(reason) BETWEEN 10 AND 2000),
    published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (product_id, version),
    FOREIGN KEY (product_id, version) REFERENCES product_versions (product_id, version)
);
CREATE TRIGGER product_publications_immutable BEFORE UPDATE OR DELETE ON product_publications
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TABLE catalog_idempotency_records (
    caller_id uuid NOT NULL,
    method text NOT NULL,
    path text NOT NULL,
    key text NOT NULL,
    fingerprint char(64) NOT NULL,
    response_status smallint NOT NULL,
    response_body text NOT NULL,
    response_etag text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (caller_id, method, path, key)
);
CREATE TRIGGER catalog_idempotency_immutable BEFORE UPDATE OR DELETE ON catalog_idempotency_records
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

COMMIT;
