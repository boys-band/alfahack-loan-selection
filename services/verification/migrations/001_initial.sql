-- PostgreSQL 16+. Initial schema; apply once to an empty service database.
-- UUIDs that belong to another service deliberately have no foreign keys.
BEGIN;
SET LOCAL search_path = public, pg_catalog;

CREATE FUNCTION reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '23514';
END;
$$;

-- Only committed successful responses are stored. Insert with the business change.
CREATE TABLE idempotency_records (
    caller varchar(128) NOT NULL,
    method varchar(8) NOT NULL CHECK (method IN ('POST', 'PATCH')),
    path varchar(512) NOT NULL CHECK (path LIKE '/v2/%'),
    idempotency_key varchar(128) NOT NULL
        CHECK (idempotency_key ~ '^[A-Za-z0-9:_.-]{8,128}$'),
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    response_status smallint NOT NULL CHECK (response_status BETWEEN 200 AND 299),
    response_headers jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(response_headers) = 'object'),
    response_body jsonb NOT NULL CHECK (jsonb_typeof(response_body) = 'object'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz CHECK (expires_at IS NULL OR expires_at > created_at),
    PRIMARY KEY (caller, method, path, idempotency_key)
);
CREATE INDEX idempotency_expiry_idx ON idempotency_records (expires_at)
    WHERE expires_at IS NOT NULL;
CREATE TRIGGER idempotency_no_update BEFORE UPDATE ON idempotency_records
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE FUNCTION delete_expired_idempotency() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.expires_at IS NULL OR OLD.expires_at > clock_timestamp() THEN
        RAISE EXCEPTION 'idempotency record has not expired' USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER idempotency_expiry_guard BEFORE DELETE ON idempotency_records
    FOR EACH ROW EXECUTE FUNCTION delete_expired_idempotency();

CREATE TABLE source_fixtures (
    company_id uuid NOT NULL,
    source text NOT NULL CHECK (source IN ('REGISTRY','BKI','FNS')),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (company_id, source)
);
CREATE TABLE verification_results (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL,
    data_version bigint NOT NULL CHECK (data_version > 0),
    outcome text NOT NULL CHECK (outcome IN ('COMPLETE','NEEDS_INFO','MANUAL_REVIEW')),
    as_of timestamptz NOT NULL,
    completed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    raw_facts jsonb CHECK (raw_facts IS NULL OR jsonb_typeof(raw_facts) = 'object'),
    facts jsonb CHECK (facts IS NULL OR jsonb_typeof(facts) = 'object'),
    confirmed boolean NOT NULL,
    requirements jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(requirements) = 'array'),
    findings jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(findings) = 'array'),
    request_payload jsonb NOT NULL CHECK (jsonb_typeof(request_payload) = 'object'),
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    UNIQUE (application_id, data_version),
    CHECK ((outcome = 'COMPLETE' AND confirmed AND facts IS NOT NULL AND requirements = '[]') OR
           (outcome <> 'COMPLETE' AND NOT confirmed)),
    CHECK (outcome <> 'NEEDS_INFO' OR jsonb_array_length(requirements) > 0),
    CHECK (completed_at >= as_of),
    CHECK (COALESCE(request_payload->>'applicationId' = application_id::text AND
        (request_payload->>'dataVersion')::bigint = data_version, false))
);
CREATE INDEX verification_company_idx ON verification_results
    ((request_payload->'data'->'company'->>'id'), completed_at DESC);

CREATE TRIGGER verification_results_immutable BEFORE UPDATE OR DELETE ON verification_results
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

COMMIT;
