#!/usr/bin/env bash
set -euo pipefail
# The common DB profile owns initialization and 001. Apply this service's subsequent migrations.
: "${MIGRATION_DATABASE_URL:?Set an administrator/owner-capable PostgreSQL connection URL}"
service_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
for migration_path in "$service_root"/migrations/00[23]_*.sql; do
migration_version="$(basename "$migration_path" .sql)"
migration_hash="$(sha256sum "$migration_path" | cut -d ' ' -f 1)"
psql "$MIGRATION_DATABASE_URL" -X -v ON_ERROR_STOP=1 \
    -v migration_path="$migration_path" -v migration_hash="$migration_hash" -v migration_version="$migration_version" <<'SQL'
BEGIN;
SET LOCAL ROLE applications_owner;
SELECT pg_advisory_xact_lock(2048001002);
SELECT NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version=:'migration_version') AS apply_migration \gset
\if :apply_migration
\i :migration_path
INSERT INTO schema_migrations(version,checksum) VALUES(:'migration_version', :'migration_hash');
\else
SELECT checksum = :'migration_hash' AS checksum_matches FROM schema_migrations WHERE version=:'migration_version' \gset
\if :checksum_matches
\else
\echo 'Migration checksum mismatch'
\quit 1
\endif
\endif
COMMIT;
SQL
done
