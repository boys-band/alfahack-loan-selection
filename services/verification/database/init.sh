#!/usr/bin/env bash
set -euo pipefail
sql_root="${VERIFICATION_SQL_ROOT:-/verification}"
admin_user="${POSTGRES_USER:-postgres}"
psql -X -v ON_ERROR_STOP=1 -U "$admin_user" -d alfa_verification \
  -v app_password="${VERIFICATION_DB_PASSWORD:-demo-verification-only}" <<'SQL'
CREATE ROLE verification_owner NOLOGIN;
CREATE ROLE verification_app LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
ALTER DATABASE alfa_verification OWNER TO verification_owner;
ALTER DATABASE alfa_verification SET timezone = 'UTC';
REVOKE CONNECT,TEMPORARY ON DATABASE alfa_verification FROM PUBLIC;
GRANT CONNECT ON DATABASE alfa_verification TO verification_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SET ROLE verification_owner;
CREATE TABLE schema_migrations (
  version text PRIMARY KEY,
  checksum varchar(64) NOT NULL,
  applied_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  seeded_at timestamptz
);
SQL
for migration in "$sql_root"/migrations/*.sql; do
  version="$(basename "$migration" .sql)"
  checksum="$(sha256sum "$migration" | cut -d ' ' -f 1)"
  psql -X -v ON_ERROR_STOP=1 -U "$admin_user" -d alfa_verification \
    -v migration_file="$migration" -v version="$version" -v checksum="$checksum" <<'SQL'
SET ROLE verification_owner;
\i :migration_file
INSERT INTO schema_migrations(version,checksum) VALUES(:'version',:'checksum');
SQL
done
psql -X -v ON_ERROR_STOP=1 -U "$admin_user" -d alfa_verification -v seed_file="$sql_root/seed.sql" <<'SQL'
SET ROLE verification_owner;
\i :seed_file
UPDATE schema_migrations SET seeded_at=clock_timestamp();
RESET ROLE;
GRANT USAGE ON SCHEMA public TO verification_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO verification_app;
REVOKE ALL ON schema_migrations FROM verification_app;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO verification_app;
SQL
