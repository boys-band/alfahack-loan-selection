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

CREATE TABLE account_fixtures (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    label text NOT NULL CHECK (length(label) > 0),
    status text NOT NULL CHECK (status IN ('ACTIVE','INVALID')),
    UNIQUE (company_id, id)
);
CREATE TABLE payout_checks (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL,
    data_version bigint NOT NULL CHECK (data_version > 0),
    payout_version bigint NOT NULL CHECK (payout_version > 0),
    agreement_id uuid NOT NULL,
    company_id uuid NOT NULL,
    settlement_account_id uuid NOT NULL,
    outcome text NOT NULL CHECK (outcome IN ('READY','NEEDS_CORRECTION')),
    reason_codes text[] NOT NULL DEFAULT '{}'
        CHECK (reason_codes <@ ARRAY['ACCOUNT_NOT_OWNED','ACCOUNT_INVALID']::text[]),
    checked_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    request_payload jsonb NOT NULL CHECK (jsonb_typeof(request_payload) = 'object'),
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    UNIQUE (application_id, data_version, payout_version),
    UNIQUE (id, application_id, data_version, payout_version, agreement_id, company_id, settlement_account_id, outcome),
    CHECK ((outcome = 'READY' AND cardinality(reason_codes) = 0) OR
           (outcome = 'NEEDS_CORRECTION' AND cardinality(reason_codes) > 0))
);
CREATE TABLE disbursements (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    application_id uuid NOT NULL UNIQUE,
    data_version bigint NOT NULL CHECK (data_version > 0),
    agreement_id uuid NOT NULL UNIQUE,
    payout_version bigint NOT NULL CHECK (payout_version > 0),
    payout_check_id uuid NOT NULL,
    company_id uuid NOT NULL,
    settlement_account_id uuid NOT NULL,
    payout_outcome text NOT NULL DEFAULT 'READY' CHECK (payout_outcome = 'READY'),
    amount_rub bigint NOT NULL CHECK (amount_rub BETWEEN 1 AND 1000000000),
    currency text NOT NULL DEFAULT 'RUB' CHECK (currency = 'RUB'),
    status text NOT NULL DEFAULT 'EXECUTED' CHECK (status = 'EXECUTED'),
    credit_entry_id uuid NOT NULL UNIQUE,
    executed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    agreement_snapshot jsonb NOT NULL CHECK (jsonb_typeof(agreement_snapshot) = 'object'),
    request_payload jsonb NOT NULL CHECK (jsonb_typeof(request_payload) = 'object'),
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    UNIQUE (id, credit_entry_id, settlement_account_id, amount_rub, currency),
    FOREIGN KEY (payout_check_id, application_id, data_version, payout_version,
        agreement_id, company_id, settlement_account_id, payout_outcome)
        REFERENCES payout_checks (id, application_id, data_version, payout_version,
            agreement_id, company_id, settlement_account_id, outcome),
    CHECK (COALESCE(agreement_snapshot->>'status' = 'SIGNED' AND
        jsonb_typeof(agreement_snapshot->'signature') = 'object' AND
        agreement_snapshot->>'id' = agreement_id::text AND
        agreement_snapshot->>'applicationId' = application_id::text AND
        (agreement_snapshot->>'dataVersion')::bigint = data_version AND
        agreement_snapshot->>'companyId' = company_id::text AND
        (agreement_snapshot->'offer'->>'amountRub')::bigint = amount_rub, false))
);
CREATE TABLE credit_entries (
    id uuid PRIMARY KEY,
    disbursement_id uuid NOT NULL UNIQUE,
    settlement_account_id uuid NOT NULL,
    amount_rub bigint NOT NULL CHECK (amount_rub BETWEEN 1 AND 1000000000),
    currency text NOT NULL DEFAULT 'RUB' CHECK (currency = 'RUB'),
    posted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (id, disbursement_id),
    FOREIGN KEY (disbursement_id, id, settlement_account_id, amount_rub, currency)
        REFERENCES disbursements (id, credit_entry_id, settlement_account_id, amount_rub, currency)
        DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE disbursements ADD CONSTRAINT disbursement_credit_fk
    FOREIGN KEY (credit_entry_id, id) REFERENCES credit_entries (id, disbursement_id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX payout_company_idx ON account_fixtures (company_id, id);
CREATE INDEX disbursement_history_idx ON disbursements (company_id, executed_at DESC, id DESC);
CREATE FUNCTION guard_ready_account() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.outcome = 'READY' AND NOT EXISTS (
        SELECT 1 FROM account_fixtures WHERE id = NEW.settlement_account_id
            AND company_id = NEW.company_id AND status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'READY requires an active account of the company' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER payout_ready_guard BEFORE INSERT ON payout_checks
    FOR EACH ROW EXECUTE FUNCTION guard_ready_account();

CREATE TRIGGER payout_checks_immutable BEFORE UPDATE OR DELETE ON payout_checks
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER disbursements_immutable BEFORE UPDATE OR DELETE ON disbursements
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER credit_entries_immutable BEFORE UPDATE OR DELETE ON credit_entries
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

COMMIT;
