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

CREATE FUNCTION valid_input(payload jsonb, complete boolean) RETURNS boolean
LANGUAGE sql IMMUTABLE STRICT AS $$
SELECT COALESCE(
    jsonb_typeof(payload) = 'object' AND payload ?& ARRAY[
        'company','product','amountRub','termMonths','purpose','financialData','additionalInfo','consents','staffAttestation'] AND
    jsonb_typeof(payload->'company') = 'object' AND payload->'company' ? 'id' AND
    jsonb_typeof(payload->'product') = 'object' AND payload->'product' ?& ARRAY['id','version'] AND
    jsonb_typeof(payload->'additionalInfo') = 'object' AND
    jsonb_typeof(payload->'staffAttestation') IN ('object','null') AND
    (jsonb_typeof(payload->'amountRub') = 'number' OR (NOT complete AND payload->'amountRub' = 'null')) AND
    (jsonb_typeof(payload->'termMonths') = 'number' OR (NOT complete AND payload->'termMonths' = 'null')) AND
    (payload->>'purpose' IN ('WORKING_CAPITAL','EQUIPMENT','REFINANCING') OR
        (NOT complete AND payload->'purpose' = 'null')) AND
    ((NOT complete AND jsonb_typeof(payload->'financialData') IN ('object','null')) OR
        (jsonb_typeof(payload->'financialData') = 'object' AND
        payload->'financialData' ?& ARRAY['monthlyRevenueRub','monthlyExpensesRub','existingMonthlyDebtPaymentsRub'] AND
        jsonb_typeof(payload->'financialData'->'monthlyRevenueRub') = 'number' AND
        jsonb_typeof(payload->'financialData'->'monthlyExpensesRub') = 'number' AND
        jsonb_typeof(payload->'financialData'->'existingMonthlyDebtPaymentsRub') = 'number')) AND
    ((NOT complete AND jsonb_typeof(payload->'consents') IN ('object','null')) OR
        (payload->'consents'->>'personalDataConsent' = 'true' AND
         payload->'consents'->>'creditBureauConsent' = 'true' AND payload->'consents' ? 'acceptedAt')),
false);
$$;
CREATE FUNCTION matches_result(payload jsonb, app uuid, revision bigint) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
SELECT payload IS NULL OR COALESCE(jsonb_typeof(payload) = 'object' AND
    payload->>'applicationId' = app::text AND (payload->>'dataVersion')::bigint = revision, false);
$$;
CREATE TABLE companies (
    id uuid PRIMARY KEY,
    legal_name text NOT NULL CHECK (length(legal_name) > 0),
    inn varchar(12) NOT NULL CHECK (inn ~ '^([0-9]{10}|[0-9]{12})$'),
    legal_form text NOT NULL CHECK (legal_form IN ('IE','LLC')),
    registered_at date NOT NULL,
    settlement_account_id uuid NOT NULL
);
CREATE TABLE customer_companies (
    customer_id uuid NOT NULL,
    company_id uuid NOT NULL REFERENCES companies (id),
    PRIMARY KEY (customer_id, company_id)
);
CREATE TABLE applications (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    data_version bigint NOT NULL DEFAULT 0 CHECK (data_version >= 0),
    submitted_data_version bigint GENERATED ALWAYS AS (NULLIF(data_version, 0)) STORED,
    status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SUBMITTED','VALIDATION','IN_REVIEW',
        'NEEDS_INFO','MANUAL_REVIEW','OFFER_READY','OFFER_ACCEPTED','AWAITING_SIGNATURE','SIGNED',
        'DISBURSING','DISBURSED','REJECTED','CANCELLED','EXPIRED')),
    review_stage text CHECK (review_stage IN ('ENRICHMENT','SCORING')),
    input_data jsonb NOT NULL CHECK (valid_input(input_data, false)),
    company_id uuid GENERATED ALWAYS AS ((input_data->'company'->>'id')::uuid) STORED NOT NULL,
    amount_rub bigint GENERATED ALWAYS AS ((input_data->>'amountRub')::bigint) STORED
        CHECK (amount_rub BETWEEN 1 AND 1000000000),
    term_months smallint GENERATED ALWAYS AS ((input_data->>'termMonths')::smallint) STORED
        CHECK (term_months BETWEEN 1 AND 120),
    monthly_revenue_rub bigint GENERATED ALWAYS AS ((input_data->'financialData'->>'monthlyRevenueRub')::bigint) STORED
        CHECK (monthly_revenue_rub BETWEEN 0 AND 1000000000000),
    monthly_expenses_rub bigint GENERATED ALWAYS AS ((input_data->'financialData'->>'monthlyExpensesRub')::bigint) STORED
        CHECK (monthly_expenses_rub BETWEEN 0 AND 1000000000000),
    existing_debt_payments_rub bigint GENERATED ALWAYS AS ((input_data->'financialData'->>'existingMonthlyDebtPaymentsRub')::bigint) STORED
        CHECK (existing_debt_payments_rub BETWEEN 0 AND 1000000000000),
    verification_result jsonb CHECK (matches_result(verification_result, id, data_version)),
    decision_result jsonb CHECK (matches_result(decision_result, id, data_version)),
    manual_review_snapshot jsonb CHECK (matches_result(manual_review_snapshot, id, data_version)),
    agreement_summary jsonb CHECK (agreement_summary IS NULL OR jsonb_typeof(agreement_summary) = 'object'),
    payout_check_result jsonb CHECK (matches_result(payout_check_result, id, data_version)),
    disbursement_result jsonb CHECK (matches_result(disbursement_result, id, data_version)),
    current_information_request_id uuid,
    current_acceptance_id uuid,
    current_payout_version bigint CHECK (current_payout_version > 0),
    current_job_id uuid,
    deadline_kind text CHECK (deadline_kind IN ('INFO','OFFER','SIGNATURE')),
    action_deadline timestamptz,
    closure_reason text CHECK (closure_reason IN ('INFO_TIMEOUT','OFFER_TIMEOUT','SIGNATURE_TIMEOUT','CUSTOMER_CANCELLED','CREDIT_REJECTED')),
    technical_error jsonb CHECK (technical_error IS NULL OR jsonb_typeof(technical_error) = 'object'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (customer_id, company_id) REFERENCES customer_companies (customer_id, company_id),
    CHECK ((status = 'IN_REVIEW' AND review_stage IS NOT NULL) OR
           (status <> 'IN_REVIEW' AND review_stage IS NULL)),
    CHECK ((status = 'DRAFT' AND data_version = 0) OR
           (status = 'CANCELLED') OR (status <> 'DRAFT' AND data_version > 0)),
    CHECK (COALESCE((status = 'NEEDS_INFO' AND deadline_kind = 'INFO' AND action_deadline IS NOT NULL AND current_information_request_id IS NOT NULL) OR
           (status = 'OFFER_READY' AND deadline_kind = 'OFFER' AND action_deadline IS NOT NULL) OR
           (status = 'AWAITING_SIGNATURE' AND deadline_kind = 'SIGNATURE' AND action_deadline IS NOT NULL) OR
           (status NOT IN ('NEEDS_INFO','OFFER_READY','AWAITING_SIGNATURE') AND deadline_kind IS NULL AND action_deadline IS NULL), false)),
    CHECK (status NOT IN ('DISBURSED','REJECTED','CANCELLED','EXPIRED') OR
        (current_job_id IS NULL AND technical_error IS NULL)),
    CHECK (status <> 'MANUAL_REVIEW' OR manual_review_snapshot IS NOT NULL),
    CHECK (status NOT IN ('OFFER_READY','OFFER_ACCEPTED','AWAITING_SIGNATURE','SIGNED','DISBURSING','DISBURSED') OR
        COALESCE(decision_result->>'outcome' = 'APPROVED' AND verification_result->>'outcome' = 'COMPLETE' AND
            verification_result->>'confirmed' = 'true', false)),
    CHECK (NOT (status = 'IN_REVIEW' AND review_stage = 'SCORING') OR
        COALESCE(verification_result->>'outcome' = 'COMPLETE' AND verification_result->>'confirmed' = 'true', false)),
    CHECK (status NOT IN ('OFFER_ACCEPTED','AWAITING_SIGNATURE','SIGNED','DISBURSING','DISBURSED') OR current_acceptance_id IS NOT NULL),
    CHECK (status NOT IN ('SIGNED','DISBURSING','DISBURSED') OR
        COALESCE(agreement_summary->>'status' = 'SIGNED' AND current_payout_version IS NOT NULL, false)),
    CHECK (status <> 'AWAITING_SIGNATURE' OR agreement_summary IS NOT NULL),
    CHECK (status <> 'DISBURSED' OR disbursement_result IS NOT NULL)
);
CREATE TABLE application_data_versions (
    application_id uuid NOT NULL REFERENCES applications (id) DEFERRABLE INITIALLY DEFERRED,
    data_version bigint NOT NULL CHECK (data_version > 0),
    payload jsonb NOT NULL CHECK (valid_input(payload, true)),
    origin text NOT NULL CHECK (origin IN ('INITIAL_SUBMISSION','CUSTOMER_INFO','CUSTOMER_TERMS','STAFF_AMENDMENT')),
    actor_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (application_id, data_version),
    CHECK ((data_version = 1) = (origin = 'INITIAL_SUBMISSION'))
);
ALTER TABLE applications ADD CONSTRAINT application_current_data_fk
    FOREIGN KEY (id, submitted_data_version) REFERENCES application_data_versions (application_id, data_version)
    DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE information_requests (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL,
    data_version bigint NOT NULL CHECK (data_version > 0),
    source text NOT NULL CHECK (source IN ('VALIDATION','ENRICHMENT','SCORING','STAFF')),
    requirements jsonb NOT NULL CHECK (jsonb_typeof(requirements) = 'array' AND jsonb_array_length(requirements) > 0),
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ANSWERED','CLOSED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    deadline timestamptz NOT NULL CHECK (deadline > created_at),
    completed_at timestamptz,
    UNIQUE (application_id, data_version, id),
    FOREIGN KEY (application_id, data_version) REFERENCES application_data_versions (application_id, data_version),
    CHECK ((status = 'OPEN') = (completed_at IS NULL))
);
CREATE UNIQUE INDEX one_open_information_request_idx ON information_requests (application_id) WHERE status = 'OPEN';
CREATE INDEX information_deadline_idx ON information_requests (deadline, id) WHERE status = 'OPEN';
CREATE TABLE offer_acceptances (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL,
    data_version bigint NOT NULL CHECK (data_version > 0),
    offer_id uuid NOT NULL,
    offer_version bigint NOT NULL CHECK (offer_version > 0),
    offer_snapshot jsonb NOT NULL CHECK (jsonb_typeof(offer_snapshot) = 'object'),
    accepted_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','VOIDED')),
    UNIQUE (application_id, data_version),
    UNIQUE (application_id, data_version, id),
    FOREIGN KEY (application_id, data_version) REFERENCES application_data_versions (application_id, data_version),
    CHECK (COALESCE(offer_snapshot->>'id' = offer_id::text AND
        (offer_snapshot->>'version')::bigint = offer_version AND
        offer_snapshot->>'applicationId' = application_id::text AND
        (offer_snapshot->>'dataVersion')::bigint = data_version AND
        accepted_at < (offer_snapshot->>'expiresAt')::timestamptz, false))
);
CREATE UNIQUE INDEX one_active_acceptance_idx ON offer_acceptances (application_id) WHERE status = 'ACTIVE';
CREATE TABLE payout_instructions (
    application_id uuid NOT NULL,
    data_version bigint NOT NULL CHECK (data_version > 0),
    payout_version bigint NOT NULL CHECK (payout_version > 0),
    settlement_account_id uuid NOT NULL,
    operation_id uuid UNIQUE,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (application_id, data_version, payout_version),
    FOREIGN KEY (application_id, data_version) REFERENCES application_data_versions (application_id, data_version)
);
CREATE TABLE workflow_jobs (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES applications (id),
    data_version bigint NOT NULL CHECK (data_version > 0),
    step text NOT NULL CHECK (step IN ('VALIDATION','ENRICHMENT','SCORING','MANUAL_CASE','MANUAL_ACTION',
        'AGREEMENT','SIGNATURE','PAYOUT_VALIDATION','DISBURSEMENT','CLOSURE')),
    logical_key text NOT NULL CHECK (length(logical_key) > 0),
    operation_id uuid NOT NULL UNIQUE,
    request_payload jsonb NOT NULL CHECK (jsonb_typeof(request_payload) = 'object'),
    idempotency_key varchar(128) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9:_.-]{8,128}$'),
    expected_status text NOT NULL,
    expected_review_stage text,
    expected_payout_version bigint CHECK (expected_payout_version > 0),
    state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','RUNNING','PAUSED','SUCCEEDED','CANCELLED')),
    is_cleanup boolean NOT NULL DEFAULT false,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    total_attempt_count bigint NOT NULL DEFAULT 0 CHECK (total_attempt_count >= attempt_count),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_token uuid,
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    UNIQUE (application_id, step, logical_key),
    UNIQUE (application_id, data_version, id),
    FOREIGN KEY (application_id, data_version) REFERENCES application_data_versions (application_id, data_version),
    CHECK ((state = 'RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR
           (state <> 'RUNNING' AND lease_token IS NULL AND lease_until IS NULL)),
    CHECK ((state IN ('SUCCEEDED','CANCELLED')) = (finished_at IS NOT NULL)),
    CHECK (COALESCE((expected_status = 'IN_REVIEW' AND expected_review_stage IN ('ENRICHMENT','SCORING')) OR
           (expected_status <> 'IN_REVIEW' AND expected_review_stage IS NULL), false))
);
CREATE UNIQUE INDEX one_active_workflow_idx ON workflow_jobs (application_id)
    WHERE NOT is_cleanup AND state IN ('PENDING','RUNNING','PAUSED');
CREATE INDEX workflow_ready_idx ON workflow_jobs (next_attempt_at, id) WHERE state = 'PENDING';
CREATE INDEX workflow_expired_lease_idx ON workflow_jobs (lease_until, id) WHERE state = 'RUNNING';
CREATE TABLE workflow_attempts (
    id uuid PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES workflow_jobs (id),
    attempt_no bigint NOT NULL CHECK (attempt_no > 0),
    lease_token uuid NOT NULL,
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    outcome text NOT NULL DEFAULT 'IN_PROGRESS' CHECK (outcome IN ('IN_PROGRESS','SUCCEEDED','RETRYABLE_ERROR','PERMANENT_ERROR','LEASE_LOST')),
    error_code text,
    error_message text,
    UNIQUE (job_id, attempt_no),
    CHECK ((outcome = 'IN_PROGRESS') = (finished_at IS NULL)),
    CHECK (finished_at IS NULL OR finished_at >= started_at)
);
CREATE TABLE application_timeline (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES applications (id),
    sequence bigint NOT NULL CHECK (sequence > 0),
    aggregate_version bigint NOT NULL CHECK (aggregate_version > 0),
    data_version bigint NOT NULL CHECK (data_version >= 0),
    from_status text,
    from_review_stage text CHECK (from_review_stage IN ('ENRICHMENT','SCORING')),
    status text NOT NULL,
    review_stage text CHECK (review_stage IN ('ENRICHMENT','SCORING')),
    actor_type text NOT NULL CHECK (actor_type IN ('CUSTOMER','STAFF','SYSTEM')),
    actor_id uuid,
    event_type text NOT NULL CHECK (event_type IN ('DRAFT_CREATED','DRAFT_UPDATED','STATE_CHANGED','DATA_VERSION_CREATED',
        'TECHNICAL_FAILURE','PROCESSING_RETRIED','COMMAND_ACCEPTED','PAYOUT_UPDATED')),
    reason text NOT NULL CHECK (length(btrim(reason)) > 0),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (application_id, sequence),
    CHECK ((actor_type = 'SYSTEM' AND actor_id IS NULL) OR (actor_type <> 'SYSTEM' AND actor_id IS NOT NULL)),
    CHECK ((status = 'IN_REVIEW' AND review_stage IS NOT NULL) OR (status <> 'IN_REVIEW' AND review_stage IS NULL))
);
ALTER TABLE applications ADD CONSTRAINT application_information_fk FOREIGN KEY (id, data_version, current_information_request_id)
    REFERENCES information_requests (application_id, data_version, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE applications ADD CONSTRAINT application_acceptance_fk FOREIGN KEY (id, data_version, current_acceptance_id)
    REFERENCES offer_acceptances (application_id, data_version, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE applications ADD CONSTRAINT application_payout_fk FOREIGN KEY (id, data_version, current_payout_version)
    REFERENCES payout_instructions (application_id, data_version, payout_version) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE applications ADD CONSTRAINT application_job_fk FOREIGN KEY (id, data_version, current_job_id)
    REFERENCES workflow_jobs (application_id, data_version, id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX application_customer_idx ON applications (customer_id, created_at DESC, id DESC);
CREATE INDEX application_deadline_idx ON applications (action_deadline, id) WHERE action_deadline IS NOT NULL;
CREATE INDEX application_manual_queue_idx ON applications (created_at DESC, id DESC) WHERE status = 'MANUAL_REVIEW';
CREATE INDEX timeline_version_idx ON application_timeline (application_id, aggregate_version);

CREATE FUNCTION guard_application_update() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE allowed boolean;
BEGIN
    IF NEW.id <> OLD.id OR NEW.customer_id <> OLD.customer_id OR NEW.created_at <> OLD.created_at OR
       NEW.input_data->'company' IS DISTINCT FROM OLD.input_data->'company' OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'identity is immutable; each change requires version + 1' USING ERRCODE = '23514';
    END IF;
    IF NEW.data_version <> OLD.data_version THEN
        IF NEW.data_version <> OLD.data_version + 1 OR NOT (
            (OLD.status = 'DRAFT' AND NEW.status = 'SUBMITTED') OR
            (OLD.status = 'NEEDS_INFO' AND NEW.status = 'SUBMITTED') OR
            (OLD.status IN ('MANUAL_REVIEW','OFFER_READY') AND NEW.status = 'VALIDATION')) THEN
            RAISE EXCEPTION 'invalid data version change' USING ERRCODE = '23514';
        END IF;
        IF NEW.verification_result IS NOT NULL OR NEW.decision_result IS NOT NULL OR
           NEW.manual_review_snapshot IS NOT NULL OR NEW.agreement_summary IS NOT NULL OR
           NEW.current_acceptance_id IS NOT NULL OR NEW.current_information_request_id IS NOT NULL OR
           NEW.payout_check_result IS NOT NULL OR NEW.disbursement_result IS NOT NULL THEN
            RAISE EXCEPTION 'new input version cannot retain old results' USING ERRCODE = '23514';
        END IF;
    ELSIF OLD.data_version > 0 AND NEW.input_data IS DISTINCT FROM OLD.input_data THEN
        RAISE EXCEPTION 'submitted data requires a new immutable version' USING ERRCODE = '23514';
    END IF;
    IF OLD.status IN ('DISBURSED','REJECTED','CANCELLED','EXPIRED') AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'terminal application cannot be reopened' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.status, NEW.review_stage) IS DISTINCT FROM ROW(OLD.status, OLD.review_stage) THEN
        allowed := CASE OLD.status
            WHEN 'DRAFT' THEN NEW.status IN ('SUBMITTED','CANCELLED')
            WHEN 'SUBMITTED' THEN NEW.status IN ('VALIDATION','CANCELLED')
            WHEN 'VALIDATION' THEN NEW.status IN ('IN_REVIEW','NEEDS_INFO','CANCELLED')
            WHEN 'IN_REVIEW' THEN CASE OLD.review_stage
                WHEN 'ENRICHMENT' THEN NEW.status IN ('NEEDS_INFO','MANUAL_REVIEW','CANCELLED') OR
                    (NEW.status = 'IN_REVIEW' AND NEW.review_stage = 'SCORING')
                ELSE NEW.status IN ('NEEDS_INFO','MANUAL_REVIEW','REJECTED','OFFER_READY','CANCELLED') END
            WHEN 'NEEDS_INFO' THEN NEW.status IN ('SUBMITTED','CANCELLED','EXPIRED')
            WHEN 'MANUAL_REVIEW' THEN NEW.status IN ('VALIDATION','NEEDS_INFO','REJECTED','OFFER_READY','CANCELLED')
            WHEN 'OFFER_READY' THEN NEW.status IN ('VALIDATION','OFFER_ACCEPTED','CANCELLED','EXPIRED')
            WHEN 'OFFER_ACCEPTED' THEN NEW.status IN ('AWAITING_SIGNATURE','CANCELLED')
            WHEN 'AWAITING_SIGNATURE' THEN NEW.status IN ('SIGNED','CANCELLED','EXPIRED')
            WHEN 'SIGNED' THEN NEW.status = 'DISBURSING'
            WHEN 'DISBURSING' THEN NEW.status = 'DISBURSED'
            ELSE false END;
        IF NOT COALESCE(allowed, false) THEN
            RAISE EXCEPTION 'invalid application state transition' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.status = 'IN_REVIEW' AND OLD.status <> 'IN_REVIEW' AND NEW.review_stage <> 'ENRICHMENT' THEN
        RAISE EXCEPTION 'review must start with enrichment' USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER application_update_guard BEFORE UPDATE ON applications FOR EACH ROW EXECUTE FUNCTION guard_application_update();
CREATE FUNCTION check_application_commit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.data_version > 0 AND NOT EXISTS (SELECT 1 FROM application_data_versions
        WHERE application_id = NEW.id AND data_version = NEW.data_version AND payload = NEW.input_data) THEN
        RAISE EXCEPTION 'current input must match its immutable snapshot' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM application_timeline WHERE application_id = NEW.id
        AND aggregate_version = NEW.version AND status = NEW.status
        AND review_stage IS NOT DISTINCT FROM NEW.review_stage AND data_version = NEW.data_version) THEN
        RAISE EXCEPTION 'application update requires an atomic timeline event' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'NEEDS_INFO' AND NOT EXISTS (SELECT 1 FROM information_requests
        WHERE id = NEW.current_information_request_id AND status = 'OPEN' AND deadline = NEW.action_deadline) THEN
        RAISE EXCEPTION 'current open information request and deadline are required' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER application_commit_guard AFTER INSERT OR UPDATE ON applications
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_application_commit();
CREATE FUNCTION guard_job_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(NEW) - ARRAY['state','attempt_count','total_attempt_count','next_attempt_at','lease_token','lease_until','finished_at'])
       IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['state','attempt_count','total_attempt_count','next_attempt_at','lease_token','lease_until','finished_at']) THEN
        RAISE EXCEPTION 'job identity and exact request are immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.state IN ('SUCCEEDED','CANCELLED') THEN
        RAISE EXCEPTION 'finished job cannot be restarted' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER job_update_guard BEFORE UPDATE ON workflow_jobs FOR EACH ROW EXECUTE FUNCTION guard_job_update();
CREATE FUNCTION guard_payout_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.operation_id IS NOT NULL OR NEW.operation_id IS NULL OR
       (to_jsonb(NEW) - 'operation_id') IS DISTINCT FROM (to_jsonb(OLD) - 'operation_id') THEN
        RAISE EXCEPTION 'only a one-time operation binding is allowed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER payout_update_guard BEFORE UPDATE ON payout_instructions FOR EACH ROW EXECUTE FUNCTION guard_payout_update();
CREATE TRIGGER application_no_delete BEFORE DELETE ON applications FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER job_no_delete BEFORE DELETE ON workflow_jobs FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER payout_no_delete BEFORE DELETE ON payout_instructions FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE FUNCTION guard_information_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'OPEN' OR NEW.status NOT IN ('ANSWERED','CLOSED') OR
       (to_jsonb(NEW) - ARRAY['status','completed_at']) IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['status','completed_at']) THEN
        RAISE EXCEPTION 'information request can only be answered or closed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER information_update_guard BEFORE UPDATE ON information_requests
    FOR EACH ROW EXECUTE FUNCTION guard_information_update();
CREATE TRIGGER information_no_delete BEFORE DELETE ON information_requests
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE FUNCTION guard_acceptance_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'ACTIVE' OR NEW.status <> 'VOIDED' OR
       (to_jsonb(NEW) - 'status') IS DISTINCT FROM (to_jsonb(OLD) - 'status') THEN
        RAISE EXCEPTION 'acceptance can only be voided without changing terms' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER acceptance_update_guard BEFORE UPDATE ON offer_acceptances
    FOR EACH ROW EXECUTE FUNCTION guard_acceptance_update();
CREATE TRIGGER acceptance_no_delete BEFORE DELETE ON offer_acceptances
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE FUNCTION guard_attempt_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.outcome <> 'IN_PROGRESS' OR NEW.outcome = 'IN_PROGRESS' OR
       (to_jsonb(NEW) - ARRAY['outcome','finished_at','error_code','error_message']) IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['outcome','finished_at','error_code','error_message']) THEN
        RAISE EXCEPTION 'attempt can only record its final outcome once' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER attempt_update_guard BEFORE UPDATE ON workflow_attempts
    FOR EACH ROW EXECUTE FUNCTION guard_attempt_update();
CREATE TRIGGER attempt_no_delete BEFORE DELETE ON workflow_attempts
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER application_data_versions_immutable BEFORE UPDATE OR DELETE ON application_data_versions
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER application_timeline_immutable BEFORE UPDATE OR DELETE ON application_timeline
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

COMMIT;
