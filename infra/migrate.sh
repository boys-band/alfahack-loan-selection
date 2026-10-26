#!/usr/bin/env bash
set -euo pipefail
for service in applications catalog verification decision; do
  for migration in /schema/services/"$service"/migrations/[0-9][0-9][0-9]_*.sql; do
    [ -f "$migration" ] || continue
    version="$(basename "$migration" .sql)"
    case "$version" in 000_*|001_*) continue ;; esac
    checksum="$(sha256sum "$migration" | cut -d ' ' -f 1)"
    # Existing migrations own BEGIN/COMMIT. Move their statements into one
    # transaction with the migration record and the necessary DML grants.
    sed '/^BEGIN;$/d; /^COMMIT;$/d' "$migration" > /tmp/migration.sql
    psql -X -v ON_ERROR_STOP=1 -h postgres -U postgres -d "alfa_$service" \
      -v owner="${service}_owner" -v runtime="${service}_app" \
      -v version="$version" -v checksum="$checksum" <<'SQL'
BEGIN;
SET LOCAL ROLE :"owner";
SELECT pg_advisory_xact_lock(2048001002);
SELECT NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version=:'version') AS apply_migration \gset
\if :apply_migration
\i /tmp/migration.sql
INSERT INTO schema_migrations(version,checksum,seeded_at) VALUES(:'version',:'checksum',clock_timestamp());
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO :"runtime";
REVOKE ALL ON schema_migrations FROM :"runtime";
\else
SELECT checksum=:'checksum' AS checksum_matches FROM schema_migrations WHERE version=:'version' \gset
\if :checksum_matches
\else
\echo 'Migration checksum mismatch'
\quit 1
\endif
\endif
COMMIT;
SQL
  done
done
