#!/usr/bin/env bash
set -euo pipefail
schema_root="${SCHEMA_ROOT:-/schema}"
admin_user="${POSTGRES_USER:-postgres}"
for service_name in applications catalog verification decision agreements disbursement; do
  owner_role="${service_name}_owner"
  runtime_role="${service_name}_app"
  database_name="alfa_${service_name}"
  password_variable="${service_name^^}_DB_PASSWORD"
  runtime_password="${!password_variable:-demo-${service_name}-only}"
  migration_file="$schema_root/services/$service_name/migrations/001_initial.sql"
  migration_hash="$(sha256sum "$migration_file" | cut -d ' ' -f 1)"
  psql -X -v ON_ERROR_STOP=1 -U "$admin_user" -d postgres \
    -v owner_role="$owner_role" -v runtime_role="$runtime_role" \
    -v database_name="$database_name" -v runtime_password="$runtime_password" <<'SQL'
CREATE ROLE :"owner_role" NOLOGIN;
CREATE ROLE :"runtime_role" LOGIN PASSWORD :'runtime_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE DATABASE :"database_name" OWNER :"owner_role";
REVOKE CONNECT, TEMPORARY ON DATABASE :"database_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"database_name" TO :"runtime_role";
ALTER DATABASE :"database_name" SET timezone = 'UTC';
SQL
  psql -X -v ON_ERROR_STOP=1 -U "$admin_user" -d "$database_name" \
    -v owner_role="$owner_role" -v runtime_role="$runtime_role" \
    -v migration_file="$migration_file" -v migration_hash="$migration_hash" <<'SQL'
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SET ROLE :"owner_role";
\i :migration_file
CREATE TABLE schema_migrations (
    version text PRIMARY KEY,
    checksum varchar(64) NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    seeded_at timestamptz
);
INSERT INTO schema_migrations (version, checksum) VALUES ('001_initial', :'migration_hash');
RESET ROLE;
GRANT USAGE ON SCHEMA public TO :"runtime_role";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"runtime_role";
REVOKE ALL ON schema_migrations FROM :"runtime_role";
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO :"runtime_role";
SQL
  seed_file="$schema_root/database/seeds/$service_name.sql"
  if [[ -f "$seed_file" ]]; then
    psql -X -v ON_ERROR_STOP=1 -U "$admin_user" -d "$database_name" \
      -v owner_role="$owner_role" -v seed_file="$seed_file" <<'SQL'
SET ROLE :"owner_role";
\i :seed_file
SQL
  fi
  psql -X -v ON_ERROR_STOP=1 -U "$admin_user" -d "$database_name" <<'SQL'
UPDATE schema_migrations SET seeded_at = clock_timestamp() WHERE version = '001_initial';
SQL
  printf 'Initialized %s\n' "$database_name"
done
