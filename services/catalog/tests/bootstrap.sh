#!/usr/bin/env bash
set -euo pipefail
psql -X -v ON_ERROR_STOP=1 -U postgres -d postgres \
  -v runtime_password="${CATALOG_DB_PASSWORD:?Set CATALOG_DB_PASSWORD}" <<'SQL'
CREATE ROLE catalog_owner NOLOGIN;
CREATE ROLE catalog_app LOGIN PASSWORD :'runtime_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE DATABASE alfa_catalog OWNER catalog_owner;
REVOKE CONNECT, TEMPORARY ON DATABASE alfa_catalog FROM PUBLIC;
GRANT CONNECT ON DATABASE alfa_catalog TO catalog_app;
ALTER DATABASE alfa_catalog SET timezone = 'UTC';
SQL
migration_hash="$(sha256sum /schema/migrations/001_initial.sql | cut -d ' ' -f 1)"
publication_hash="$(sha256sum /schema/migrations/002_admin_publication.sql | cut -d ' ' -f 1)"
program_hash="$(sha256sum /schema/migrations/003_program_lifecycle.sql | cut -d ' ' -f 1)"
psql -X -v ON_ERROR_STOP=1 -U postgres -d alfa_catalog -v migration_hash="$migration_hash" -v publication_hash="$publication_hash" -v program_hash="$program_hash" <<'SQL'
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SET ROLE catalog_owner;
\i /schema/migrations/001_initial.sql
CREATE TABLE schema_migrations (
    version text PRIMARY KEY,
    checksum varchar(64) NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    seeded_at timestamptz
);
INSERT INTO schema_migrations(version, checksum) VALUES ('001_initial', :'migration_hash');
\i /schema/seed.sql
UPDATE schema_migrations SET seeded_at=clock_timestamp() WHERE version='001_initial';
\i /schema/migrations/002_admin_publication.sql
INSERT INTO schema_migrations(version,checksum,seeded_at)
    VALUES('002_admin_publication', :'publication_hash', clock_timestamp());
\i /schema/migrations/003_program_lifecycle.sql
INSERT INTO schema_migrations(version,checksum,seeded_at)
    VALUES('003_program_lifecycle', :'program_hash', clock_timestamp());
RESET ROLE;
GRANT USAGE ON SCHEMA public TO catalog_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO catalog_app;
REVOKE ALL ON schema_migrations FROM catalog_app;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO catalog_app;
SQL
