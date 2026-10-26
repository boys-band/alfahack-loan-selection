-- PostgreSQL 16+. Initial schema; apply once to an empty service database.
-- UUIDs that belong to another service deliberately have no foreign keys.
BEGIN;
SET LOCAL search_path = public, pg_catalog;

CREATE FUNCTION reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '23514';
END;
$$;

CREATE TABLE product_versions (
    product_id varchar(64) NOT NULL CHECK (product_id ~ '^[a-z][a-z0-9-]{1,63}$'),
    version text NOT NULL CHECK (version ~ '^[1-9][0-9]*$'),
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    description text NOT NULL CHECK (length(btrim(description)) > 0),
    purpose_codes text[] NOT NULL CHECK (cardinality(purpose_codes) > 0 AND
        purpose_codes <@ ARRAY['WORKING_CAPITAL','EQUIPMENT','REFINANCING']::text[] AND
        array_position(purpose_codes, NULL) IS NULL),
    min_amount_rub bigint NOT NULL CHECK (min_amount_rub BETWEEN 1 AND 1000000000),
    max_amount_rub bigint NOT NULL CHECK (max_amount_rub BETWEEN min_amount_rub AND 1000000000),
    min_term_months smallint NOT NULL CHECK (min_term_months BETWEEN 1 AND 120),
    max_term_months smallint NOT NULL CHECK (max_term_months BETWEEN min_term_months AND 120),
    annual_rate_bps integer NOT NULL CHECK (annual_rate_bps BETWEEN 0 AND 10000),
    min_business_age_months integer NOT NULL CHECK (min_business_age_months >= 0),
    max_debt_service_ratio_bps integer NOT NULL CHECK (max_debt_service_ratio_bps BETWEEN 1 AND 10000),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (product_id, version)
);
CREATE TABLE current_products (
    product_id varchar(64) PRIMARY KEY,
    version text NOT NULL,
    active boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (product_id, version) REFERENCES product_versions (product_id, version)
);

CREATE TRIGGER product_versions_immutable BEFORE UPDATE OR DELETE ON product_versions
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

COMMIT;
