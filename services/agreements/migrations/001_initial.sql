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

CREATE TABLE agreements (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL,
    data_version bigint NOT NULL CHECK (data_version > 0),
    customer_id uuid NOT NULL,
    company_id uuid NOT NULL,
    offer_id uuid NOT NULL,
    offer_version bigint NOT NULL CHECK (offer_version > 0),
    offer_snapshot jsonb NOT NULL CHECK (jsonb_typeof(offer_snapshot) = 'object'),
    accepted_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    sign_by timestamptz NOT NULL,
    document_version bigint NOT NULL DEFAULT 1 CHECK (document_version > 0),
    document_content text NOT NULL CHECK (length(document_content) > 0),
    document_sha256 varchar(64) NOT NULL CHECK (document_sha256 ~ '^[0-9a-f]{64}$'),
    status text NOT NULL DEFAULT 'READY' CHECK (status IN ('READY','SIGNED','CLOSED')),
    closure_reason text CHECK (closure_reason IN ('CANCELLED','EXPIRED','SUPERSEDED')),
    closed_at timestamptz,
    signature_id uuid,
    request_payload jsonb NOT NULL CHECK (jsonb_typeof(request_payload) = 'object'),
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    UNIQUE (application_id, data_version),
    UNIQUE (id, customer_id, document_version, document_sha256),
    UNIQUE (id, signature_id),
    CHECK (created_at >= accepted_at AND sign_by > created_at),
    CHECK ((status = 'SIGNED') = (signature_id IS NOT NULL)),
    CHECK ((status = 'CLOSED' AND closure_reason IS NOT NULL AND closed_at IS NOT NULL) OR
           (status <> 'CLOSED' AND closure_reason IS NULL AND closed_at IS NULL)),
    CHECK (COALESCE(offer_snapshot->>'id' = offer_id::text AND
        (offer_snapshot->>'version')::bigint = offer_version AND
        offer_snapshot->>'applicationId' = application_id::text AND
        (offer_snapshot->>'dataVersion')::bigint = data_version AND
        accepted_at < (offer_snapshot->>'expiresAt')::timestamptz, false))
);
CREATE FUNCTION set_document_hash() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.document_sha256 := encode(sha256(convert_to(NEW.document_content, 'UTF8')), 'hex');
    RETURN NEW;
END;
$$;
CREATE TRIGGER document_hash_guard BEFORE INSERT ON agreements
    FOR EACH ROW EXECUTE FUNCTION set_document_hash();
CREATE TABLE signatures (
    id uuid PRIMARY KEY,
    agreement_id uuid NOT NULL UNIQUE,
    customer_id uuid NOT NULL,
    document_version bigint NOT NULL CHECK (document_version > 0),
    document_sha256 varchar(64) NOT NULL CHECK (document_sha256 ~ '^[0-9a-f]{64}$'),
    signed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    method text NOT NULL DEFAULT 'DEMO_CONSENT' CHECK (method = 'DEMO_CONSENT'),
    UNIQUE (agreement_id, id),
    FOREIGN KEY (agreement_id, customer_id, document_version, document_sha256)
        REFERENCES agreements (id, customer_id, document_version, document_sha256),
    FOREIGN KEY (agreement_id, id) REFERENCES agreements (id, signature_id)
        DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE agreements ADD CONSTRAINT agreement_signature_fk
    FOREIGN KEY (id, signature_id) REFERENCES signatures (agreement_id, id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX agreement_signature_deadline_idx ON agreements (sign_by, id) WHERE status = 'READY';

CREATE FUNCTION guard_agreement_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(NEW) - ARRAY['status','closure_reason','closed_at','signature_id'])
       IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['status','closure_reason','closed_at','signature_id']) THEN
        RAISE EXCEPTION 'agreement terms and document are immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'READY' OR NEW.status NOT IN ('SIGNED','CLOSED') THEN
        RAISE EXCEPTION 'agreement can only move READY -> SIGNED/CLOSED' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'SIGNED' AND clock_timestamp() >= OLD.sign_by THEN
        RAISE EXCEPTION 'signature deadline expired' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER agreement_update_guard BEFORE UPDATE ON agreements
    FOR EACH ROW EXECUTE FUNCTION guard_agreement_update();
CREATE TRIGGER agreement_no_delete BEFORE DELETE ON agreements
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE FUNCTION guard_signature_time() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE agreement_row agreements%ROWTYPE;
BEGIN
    SELECT * INTO STRICT agreement_row FROM agreements WHERE id = NEW.agreement_id FOR UPDATE;
    IF agreement_row.status <> 'READY' OR clock_timestamp() >= agreement_row.sign_by OR
       NEW.signed_at < agreement_row.created_at OR NEW.signed_at >= agreement_row.sign_by THEN
        RAISE EXCEPTION 'agreement is not ready for timely signature' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER signature_time_guard BEFORE INSERT ON signatures
    FOR EACH ROW EXECUTE FUNCTION guard_signature_time();

CREATE TRIGGER signatures_immutable BEFORE UPDATE OR DELETE ON signatures
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

COMMIT;
