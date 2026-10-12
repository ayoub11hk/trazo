#!/bin/sh
# Runs the backend tests on a throwaway local PostgreSQL database.
# Usage: supabase/tests/run.sh   (needs createdb/psql access, e.g. as the postgres user)
set -e
DIR=$(cd "$(dirname "$0")/.." && pwd)
DB=${TRAZO_TEST_DB:-trazo_test}
dropdb --if-exists "$DB"
createdb "$DB"
psql -q -v ON_ERROR_STOP=1 -d "$DB" -f "$DIR/tests/00_supabase_shim.sql" 2>&1 | grep -v -E 'wal_level|HINT' || true
for f in "$DIR"/migrations/*.sql; do psql -q -v ON_ERROR_STOP=1 -d "$DB" -f "$f"; done
psql -v ON_ERROR_STOP=1 -d "$DB" -f "$DIR/tests/10_access_rules.sql" 2>&1 | grep -E 'ok -|FAILED|PASSED|ERROR'
