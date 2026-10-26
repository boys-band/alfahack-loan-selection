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

CREATE TABLE assessments (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL,
    data_version bigint NOT NULL CHECK (data_version > 0),
    verification_id uuid NOT NULL,
    verification_snapshot jsonb NOT NULL CHECK (jsonb_typeof(verification_snapshot) = 'object'),
    input_snapshot jsonb NOT NULL CHECK (jsonb_typeof(input_snapshot) = 'object'),
    policy_version text NOT NULL CHECK (length(policy_version) > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (application_id, data_version),
    UNIQUE (id, application_id, data_version),
    CHECK (COALESCE(verification_snapshot->>'outcome' = 'COMPLETE' AND
        verification_snapshot->>'confirmed' = 'true' AND
        verification_snapshot->>'id' = verification_id::text AND
        verification_snapshot->>'applicationId' = application_id::text AND
        (verification_snapshot->>'dataVersion')::bigint = data_version, false))
);
CREATE FUNCTION factor_sum(factors jsonb) RETURNS bigint LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT sum((item->>'points')::bigint) FROM jsonb_array_elements(factors) AS item;
$$;
CREATE TABLE evaluated_terms (
    id uuid PRIMARY KEY,
    assessment_id uuid NOT NULL REFERENCES assessments (id),
    ordinal smallint NOT NULL CHECK (ordinal > 0),
    amount_rub bigint NOT NULL CHECK (amount_rub BETWEEN 1 AND 1000000000),
    term_months smallint NOT NULL CHECK (term_months BETWEEN 1 AND 120),
    annual_rate_bps integer NOT NULL CHECK (annual_rate_bps BETWEEN 0 AND 10000),
    monthly_payment_rub bigint NOT NULL CHECK (monthly_payment_rub BETWEEN 1 AND 1000000000),
    risk_snapshot jsonb NOT NULL CHECK (jsonb_typeof(risk_snapshot) = 'object'),
    risk_score integer GENERATED ALWAYS AS ((risk_snapshot->>'score')::integer) STORED,
    risk_grade text GENERATED ALWAYS AS (risk_snapshot->>'grade') STORED,
    eligible boolean NOT NULL,
    reasons jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(reasons) = 'array'),
    UNIQUE (assessment_id, ordinal),
    UNIQUE (assessment_id, id),
    CHECK (COALESCE(risk_score BETWEEN 300 AND 900 AND
        risk_grade = CASE WHEN risk_score >= 800 THEN 'LOW'
            WHEN risk_score >= 600 THEN 'MEDIUM' ELSE 'HIGH' END AND
        jsonb_array_length(risk_snapshot->'factors') = 4 AND
        risk_score = factor_sum(risk_snapshot->'factors'), false))
);
CREATE TABLE decisions (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL,
    data_version bigint NOT NULL CHECK (data_version > 0),
    kind text NOT NULL CHECK (kind IN ('AUTO','MANUAL')),
    outcome text NOT NULL CHECK (outcome IN ('APPROVED','REJECTED','NEEDS_INFO','MANUAL_REVIEW')),
    policy_version text NOT NULL CHECK (length(policy_version) > 0),
    assessment_id uuid,
    selected_terms_id uuid,
    reasons jsonb NOT NULL CHECK (jsonb_typeof(reasons) = 'array' AND jsonb_array_length(reasons) > 0),
    requirements jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(requirements) = 'array'),
    offer_id uuid,
    offer_version bigint CHECK (offer_version > 0),
    staff_id uuid,
    staff_action_id uuid UNIQUE,
    staff_reason text,
    evaluated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    request_payload jsonb NOT NULL CHECK (jsonb_typeof(request_payload) = 'object'),
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    UNIQUE (application_id, data_version, kind),
    UNIQUE (id, application_id, data_version),
    UNIQUE (id, application_id, data_version, assessment_id, selected_terms_id, offer_id, offer_version),
    UNIQUE (id, staff_action_id),
    FOREIGN KEY (assessment_id, application_id, data_version)
        REFERENCES assessments (id, application_id, data_version),
    FOREIGN KEY (assessment_id, selected_terms_id) REFERENCES evaluated_terms (assessment_id, id),
    CHECK ((assessment_id IS NULL) = (selected_terms_id IS NULL)),
    CHECK ((outcome = 'APPROVED' AND offer_id IS NOT NULL AND offer_version IS NOT NULL AND assessment_id IS NOT NULL) OR
           (outcome <> 'APPROVED' AND offer_id IS NULL AND offer_version IS NULL)),
    CHECK ((kind = 'MANUAL' AND staff_id IS NOT NULL AND staff_action_id IS NOT NULL AND staff_reason IS NOT NULL AND length(btrim(staff_reason)) >= 10) OR
           (kind = 'AUTO' AND staff_id IS NULL AND staff_action_id IS NULL AND staff_reason IS NULL)),
    CHECK (outcome <> 'NEEDS_INFO' OR jsonb_array_length(requirements) > 0)
);
CREATE TABLE offers (
    id uuid PRIMARY KEY,
    version bigint NOT NULL CHECK (version > 0),
    decision_id uuid NOT NULL UNIQUE,
    application_id uuid NOT NULL,
    data_version bigint NOT NULL CHECK (data_version > 0),
    assessment_id uuid NOT NULL,
    terms_id uuid NOT NULL,
    product_id varchar(64) NOT NULL,
    product_version text NOT NULL CHECK (product_version ~ '^[1-9][0-9]*$'),
    type text NOT NULL CHECK (type IN ('REQUESTED','COUNTER')),
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > issued_at),
    UNIQUE (id, version, decision_id),
    FOREIGN KEY (decision_id, application_id, data_version, assessment_id, terms_id, id, version)
        REFERENCES decisions (id, application_id, data_version, assessment_id, selected_terms_id, offer_id, offer_version)
        DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE decisions ADD CONSTRAINT decision_offer_fk
    FOREIGN KEY (offer_id, offer_version, id) REFERENCES offers (id, version, decision_id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE manual_review_cases (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL,
    data_version bigint NOT NULL CHECK (data_version > 0),
    verification_id uuid NOT NULL,
    assessment_id uuid,
    automatic_decision_id uuid,
    reason text NOT NULL CHECK (length(btrim(reason)) > 0),
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED','CLOSED')),
    resolved_action_id uuid,
    closure_reason text CHECK (closure_reason IN ('CANCELLED','EXPIRED','SUPERSEDED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    resolved_at timestamptz,
    request_payload jsonb NOT NULL CHECK (jsonb_typeof(request_payload) = 'object'),
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    UNIQUE (application_id, data_version),
    UNIQUE (id, application_id, data_version),
    UNIQUE (id, resolved_action_id),
    FOREIGN KEY (assessment_id, application_id, data_version) REFERENCES assessments (id, application_id, data_version),
    FOREIGN KEY (automatic_decision_id, application_id, data_version) REFERENCES decisions (id, application_id, data_version),
    CHECK ((assessment_id IS NULL) = (automatic_decision_id IS NULL)),
    CHECK ((status = 'RESOLVED') = (resolved_action_id IS NOT NULL)),
    CHECK ((status = 'OPEN' AND resolved_at IS NULL AND closure_reason IS NULL) OR
           (status = 'RESOLVED' AND resolved_at IS NOT NULL AND closure_reason IS NULL) OR
           (status = 'CLOSED' AND resolved_at IS NOT NULL AND closure_reason IS NOT NULL))
);
CREATE TABLE manual_review_actions (
    id uuid PRIMARY KEY,
    case_id uuid NOT NULL UNIQUE,
    application_id uuid NOT NULL,
    data_version bigint NOT NULL CHECK (data_version > 0),
    staff_id uuid NOT NULL,
    action text NOT NULL CHECK (action IN ('APPROVE','REJECT','REQUEST_INFO','AMEND_DATA')),
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 10 AND 2000),
    terms_id uuid REFERENCES evaluated_terms (id),
    requirements jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(requirements) = 'array'),
    changes jsonb CHECK (changes IS NULL OR jsonb_typeof(changes) = 'object'),
    facts_resolution jsonb CHECK (facts_resolution IS NULL OR jsonb_typeof(facts_resolution) = 'object'),
    decision_id uuid UNIQUE,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (case_id, id),
    UNIQUE (decision_id, id),
    FOREIGN KEY (case_id, application_id, data_version) REFERENCES manual_review_cases (id, application_id, data_version),
    FOREIGN KEY (case_id, id) REFERENCES manual_review_cases (id, resolved_action_id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (decision_id, id) REFERENCES decisions (id, staff_action_id) DEFERRABLE INITIALLY DEFERRED,
    CHECK ((action IN ('APPROVE','REJECT')) = (decision_id IS NOT NULL)),
    CHECK ((action = 'APPROVE') = (terms_id IS NOT NULL)),
    CHECK ((action = 'REQUEST_INFO' AND jsonb_array_length(requirements) > 0) OR
           (action <> 'REQUEST_INFO' AND requirements = '[]')),
    CHECK ((action = 'AMEND_DATA' AND (changes IS NOT NULL OR facts_resolution IS NOT NULL)) OR
           (action <> 'AMEND_DATA' AND changes IS NULL AND facts_resolution IS NULL))
);
ALTER TABLE manual_review_cases ADD CONSTRAINT case_resolution_fk
    FOREIGN KEY (id, resolved_action_id) REFERENCES manual_review_actions (case_id, id)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE decisions ADD CONSTRAINT manual_decision_action_fk
    FOREIGN KEY (id, staff_action_id) REFERENCES manual_review_actions (decision_id, id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX manual_review_queue_idx ON manual_review_cases (created_at DESC, id DESC) WHERE status = 'OPEN';
CREATE FUNCTION guard_decision_approval() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE candidate evaluated_terms%ROWTYPE;
BEGIN
    IF NEW.outcome = 'APPROVED' THEN
        IF NEW.assessment_id IS NULL OR NEW.selected_terms_id IS NULL THEN
            RAISE EXCEPTION 'approval requires a completed assessment' USING ERRCODE = '23514';
        END IF;
        SELECT * INTO STRICT candidate FROM evaluated_terms WHERE id = NEW.selected_terms_id;
        IF NOT candidate.eligible OR candidate.risk_score < (CASE WHEN NEW.kind = 'AUTO' THEN 600 ELSE 500 END) THEN
            RAISE EXCEPTION 'terms do not allow approval' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER decision_approval_guard BEFORE INSERT ON decisions FOR EACH ROW EXECUTE FUNCTION guard_decision_approval();
CREATE FUNCTION guard_manual_action() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE review manual_review_cases%ROWTYPE;
DECLARE candidate evaluated_terms%ROWTYPE;
BEGIN
    SELECT * INTO STRICT review FROM manual_review_cases WHERE id = NEW.case_id FOR UPDATE;
    IF review.status <> 'OPEN' THEN
        RAISE EXCEPTION 'manual case is not open' USING ERRCODE = '23514';
    END IF;
    IF NEW.action = 'APPROVE' THEN
        SELECT * INTO STRICT candidate FROM evaluated_terms WHERE id = NEW.terms_id;
        IF review.assessment_id IS NULL OR candidate.assessment_id <> review.assessment_id OR
           NOT candidate.eligible OR candidate.risk_score < 500 THEN
            RAISE EXCEPTION 'current scored eligible terms are required' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER manual_action_guard BEFORE INSERT ON manual_review_actions FOR EACH ROW EXECUTE FUNCTION guard_manual_action();
CREATE FUNCTION guard_case_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'OPEN' OR NEW.status NOT IN ('RESOLVED','CLOSED') OR
       (to_jsonb(NEW) - ARRAY['status','resolved_action_id','closure_reason','resolved_at']) IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['status','resolved_action_id','closure_reason','resolved_at']) THEN
        RAISE EXCEPTION 'only an open case can be resolved or closed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER case_update_guard BEFORE UPDATE ON manual_review_cases FOR EACH ROW EXECUTE FUNCTION guard_case_update();
CREATE TRIGGER case_no_delete BEFORE DELETE ON manual_review_cases FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE FUNCTION check_manual_decision_link() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE recorded_action manual_review_actions%ROWTYPE;
BEGIN
    IF NEW.kind = 'MANUAL' THEN
        SELECT * INTO STRICT recorded_action FROM manual_review_actions WHERE id = NEW.staff_action_id;
        IF recorded_action.decision_id IS DISTINCT FROM NEW.id OR
           recorded_action.application_id <> NEW.application_id OR recorded_action.data_version <> NEW.data_version OR
           recorded_action.staff_id <> NEW.staff_id OR recorded_action.reason <> NEW.staff_reason OR
           NOT ((recorded_action.action = 'APPROVE' AND NEW.outcome = 'APPROVED' AND recorded_action.terms_id = NEW.selected_terms_id) OR
                (recorded_action.action = 'REJECT' AND NEW.outcome = 'REJECTED')) THEN
            RAISE EXCEPTION 'manual decision must match the audited staff action' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER manual_decision_link_guard AFTER INSERT ON decisions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_manual_decision_link();

CREATE TRIGGER assessments_immutable BEFORE UPDATE OR DELETE ON assessments
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER evaluated_terms_immutable BEFORE UPDATE OR DELETE ON evaluated_terms
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER decisions_immutable BEFORE UPDATE OR DELETE ON decisions
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER offers_immutable BEFORE UPDATE OR DELETE ON offers
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER manual_review_actions_immutable BEFORE UPDATE OR DELETE ON manual_review_actions
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

COMMIT;
