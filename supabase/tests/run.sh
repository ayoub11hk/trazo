#!/usr/bin/env bash
# Runs the backend tests on a throwaway local PostgreSQL database.
# Usage: supabase/tests/run.sh   (needs createdb/psql access, e.g. as the postgres user)
# Exits non-zero if any migration or check fails.
set -euo pipefail
DIR=$(cd "$(dirname "$0")/.." && pwd)
DB=${TRAZO_TEST_DB:-trazo_test}
dropdb --if-exists "$DB"
createdb "$DB"
psql -q -v ON_ERROR_STOP=1 -d "$DB" -f "$DIR/tests/00_supabase_shim.sql" 2>&1 | { grep -v -E 'wal_level|HINT' || true; }
for f in "$DIR"/migrations/*.sql; do psql -q -v ON_ERROR_STOP=1 -d "$DB" -f "$f"; done
OUT=$(psql -v ON_ERROR_STOP=1 -d "$DB" -f "$DIR/tests/10_access_rules.sql" 2>&1) || { echo "$OUT" | grep -E 'ok -|FAILED|ERROR' ; exit 1; }
echo "$OUT" | grep -E 'ok -|FAILED|PASSED|ERROR' || true
if echo "$OUT" | grep -qE 'FAILED|ERROR'; then exit 1; fi
echo "$OUT" | grep -q 'ALL ACCESS-RULE TESTS PASSED' || { echo "no final PASSED line"; exit 1; }
OUT=$(psql -v ON_ERROR_STOP=1 -d "$DB" -f "$DIR/tests/20_live_profiles.sql" 2>&1) || { echo "$OUT" | grep -E 'ok -|FAILED|ERROR' ; exit 1; }
echo "$OUT" | grep -E 'ok -|FAILED|PASSED|ERROR' || true
if echo "$OUT" | grep -qE 'FAILED|ERROR'; then exit 1; fi
echo "$OUT" | grep -q 'ALL LIVE-PROFILE TESTS PASSED' || { echo "no final PASSED line"; exit 1; }
