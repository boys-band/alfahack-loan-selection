#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_container="alfa-disbursement-test-$(date +%s)-$$"
cleanup() { docker rm -fv "$test_container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run -d --name "$test_container" -e POSTGRES_PASSWORD=test-admin-only \
  -p 127.0.0.1::5432 \
  postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea >/dev/null
for attempt in {1..60}; do
  if docker exec "$test_container" pg_isready -U postgres >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec -i "$test_container" psql -X -v ON_ERROR_STOP=1 -U postgres -d postgres < tests/bootstrap.sql
{ printf 'SET ROLE disbursement_owner;\n'; cat migrations/001_initial.sql tests/resources/seed.sql tests/grants.sql; } | \
  docker exec -i "$test_container" psql -X -v ON_ERROR_STOP=1 -U postgres -d alfa_disbursement
test_port="$(docker port "$test_container" 5432/tcp | sed 's/.*://')"
export TEST_DATABASE_URL="jdbc:postgresql://127.0.0.1:${test_port}/alfa_disbursement?user=disbursement_app&password=test-app-only"
export TEST_ADMIN_DATABASE_URL="jdbc:postgresql://127.0.0.1:${test_port}/alfa_disbursement?user=postgres&password=test-admin-only"
./gradlew --no-daemon integrationTest "$@"
