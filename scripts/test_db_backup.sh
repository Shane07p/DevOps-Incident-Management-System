#!/usr/bin/env bash
# Checks db-backup.sh and db-restore.sh against a throwaway PostgreSQL + pgvector container:
# the real Flyway migrations and the dev seed build the schema, then a backup is restored into a
# second database and compared table by table. Needs Docker. Run: bash scripts/test_db_backup.sh

set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

root="$(cd "$(dirname "$0")/.." && pwd)"
migrations="$root/backend/src/main/resources/db/migration"
seed="$root/backend/src/main/resources/db/devdata/R__dev_seed.sql"
image="pgvector/pgvector:0.8.6-pg16-bookworm"   # the same image as compose.yaml
name="settle-backup-test-$$"
password="backup-test-$$"
work="$(mktemp -d)"
failures=0

cleanup() {
	docker rm -f "$name" >/dev/null 2>&1 || true
	rm -rf "$work"
}
trap cleanup EXIT

pass() { echo "PASS  $1"; }
fail() {
	echo "FAIL  $1"
	failures=$((failures + 1))
}
sql() { # sql DB QUERY -> single value
	docker exec "$name" psql -U postgres -d "$1" -X -A -t -q -c "$2" | tr -d '\r'
}
row_counts() { # every table in schema incident with its row count, one per line
	sql "$1" "select string_agg(t.table_name || '=' || (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from incident.%I', t.table_name), false, true, '')))[1]::text, E'\n' order by t.table_name) from information_schema.tables t where t.table_schema = 'incident' and t.table_type = 'BASE TABLE'"
}

docker run -d --name "$name" -e POSTGRES_PASSWORD="$password" -e POSTGRES_DB=source -p 127.0.0.1::5432 "$image" >/dev/null
for _ in $(seq 1 60); do
	docker exec "$name" pg_isready -U postgres -q 2>/dev/null && sql source "select 1" >/dev/null 2>&1 && break
	sleep 1
done
port="$(docker port "$name" 5432/tcp | head -n 1 | sed 's/.*://')"

# Build the schema the way Flyway does: schema incident, every migration in version order, then the seed.
sql source "create schema incident" >/dev/null
for f in $(ls "$migrations" | sort -t V -k 2 -n); do
	docker exec -i -e PGOPTIONS='-c search_path=incident' "$name" psql -U postgres -d source -X -q -v ON_ERROR_STOP=1 \
		<"$migrations/$f" >/dev/null || { echo "migration $f failed"; exit 1; }
done
docker exec -i -e PGOPTIONS='-c search_path=incident' "$name" psql -U postgres -d source -X -q -v ON_ERROR_STOP=1 \
	<"$seed" >/dev/null
# vector data must survive the round trip, and public must never be touched
sql source "create table incident.backup_probe (id int primary key, v incident.vector(3)); insert into incident.backup_probe values (1, '[1,2,3]'), (2, '[0.5,0.25,0.125]')" >/dev/null
echo "Source database: $(sql source "select count(*) from information_schema.tables where table_schema = 'incident'") tables in incident (port $port)"

export DB_USERNAME=postgres DB_PASSWORD="$password" BACKUP_DIR="$work"
src_url="jdbc:postgresql://localhost:${port}/source"
dst_url="jdbc:postgresql://localhost:${port}/restored"

# 1. backup
if out="$(DB_URL="$src_url" bash "$root/scripts/db-backup.sh" 2>&1)"; then
	dump="$(echo "$out" | sed -n 's/^Backup written: //p')"
	[[ -s "$dump" ]] && pass "backup writes a dump ($(basename "$dump"))" || fail "backup reported no file: $out"
else
	fail "backup failed: $out"
	exit 1
fi
[[ "$(ls "$work" | grep -c '\.partial$' || true)" == "0" ]] && pass "no partial file left behind" || fail "partial file left behind"

# 2. the restore refuses without the right confirmation, and refuses a remote target without --production
if DB_URL="$dst_url" bash "$root/scripts/db-restore.sh" "$dump" >/dev/null 2>&1; then fail "restore ran without --confirm"; else pass "restore refuses without --confirm"; fi
if DB_URL="$dst_url" bash "$root/scripts/db-restore.sh" "$dump" --confirm source >/dev/null 2>&1; then fail "restore ran with the wrong --confirm"; else pass "restore refuses a --confirm that names another database"; fi
if DB_URL="jdbc:postgresql://db.example.supabase.co:5432/postgres" bash "$root/scripts/db-restore.sh" "$dump" --confirm postgres >/dev/null 2>&1; then
	fail "restore to a remote host ran without --production"
else
	pass "restore refuses a remote target without --production"
fi

# 3. restore into an empty database: identical row counts and vector data
docker exec "$name" createdb -U postgres restored
sql restored "create table public.keep_me (x int); insert into public.keep_me values (42)" >/dev/null
if DB_URL="$dst_url" bash "$root/scripts/db-restore.sh" "$dump" --confirm restored >/dev/null; then
	pass "restore into an empty database succeeds"
else
	fail "restore into an empty database failed"
fi
[[ "$(row_counts source)" == "$(row_counts restored)" ]] && pass "every table has the same row count ($(row_counts source | wc -l | tr -d ' ') tables)" || fail "row counts differ"
[[ "$(sql restored "select string_agg(v::text, ' ' order by id) from incident.backup_probe")" == "[1,2,3] [0.5,0.25,0.125]" ]] &&
	pass "vector values round-trip" || fail "vector values differ"
[[ "$(sql restored "select format_type(atttypid, atttypmod) from pg_attribute where attrelid = 'incident.incident'::regclass and attname = 'embedding'")" == "incident.vector(768)" ]] &&
	pass "incident.embedding is still vector(768)" || fail "incident.embedding type changed"

# 4. restore over changed data puts it back exactly, and leaves public alone
sql restored "insert into incident.backup_probe values (3, '[9,9,9]'); create table incident.left_over (x int)" >/dev/null
DB_URL="$dst_url" bash "$root/scripts/db-restore.sh" "$dump" --confirm restored >/dev/null
[[ "$(row_counts source)" == "$(row_counts restored)" ]] && pass "restoring again returns to the backup's exact state" || fail "second restore differs"
[[ "$(sql restored "select to_regclass('incident.left_over') is null")" == "t" ]] && pass "tables not in the backup are removed" || fail "left_over table survived"
[[ "$(sql restored "select x from public.keep_me")" == "42" ]] && pass "public schema untouched" || fail "public schema changed"

# 5. a failing restore changes nothing (the schema is dropped and rebuilt in one transaction)
head -c 2000 "$dump" >"$work/broken.dump"
before="$(row_counts restored)"
if DB_URL="$dst_url" bash "$root/scripts/db-restore.sh" "$work/broken.dump" --confirm restored >/dev/null 2>&1; then
	fail "a truncated backup was restored"
else
	pass "a truncated backup is refused"
fi
[[ "$(row_counts restored)" == "$before" ]] && pass "the database is unchanged after a failed restore" || fail "failed restore changed data"

# 6. a restore that fails AFTER dropping the schema rolls the drop back too. Here pgvector already
#    lives in public, so CREATE EXTENSION IF NOT EXISTS is a no-op and the first vector column fails.
docker exec "$name" createdb -U postgres failing
sql failing "create extension vector schema public; create schema incident; create table incident.marker (x int); insert into incident.marker values (7)" >/dev/null
if DB_URL="jdbc:postgresql://localhost:${port}/failing" bash "$root/scripts/db-restore.sh" "$dump" --confirm failing >/dev/null 2>&1; then
	fail "restore succeeded where it should have failed"
else
	pass "a restore that fails part-way reports failure"
fi
[[ "$(sql failing "select x from incident.marker")" == "7" ]] && pass "the drop was rolled back: the old schema and its data are still there" ||
	fail "the old schema was lost after a failed restore"

echo
if [[ "$failures" -eq 0 ]]; then
	echo "All database backup checks passed."
else
	echo "$failures check(s) failed."
	exit 1
fi
