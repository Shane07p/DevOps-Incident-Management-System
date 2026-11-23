#!/usr/bin/env bash
# Back up Settle's database: the application schema (incident) and the pgvector extension it uses.
# Supabase's own schemas and public are not included, so a backup restores cleanly anywhere.
#
#   DB_URL=jdbc:postgresql://host:5432/postgres?sslmode=require DB_USERNAME=... DB_PASSWORD=... \
#     scripts/db-backup.sh
#
# Writes backups/settle-incident-<where>-<UTC time>.dump (PostgreSQL custom format). Run it before a
# migration, before loading demo data, and before a demo. Dumps hold users' emails and password hashes:
# keep them out of git (backups/ is ignored) and off shared drives.

source "$(dirname "$0")/db-common.sh"

resolve_connection
root="$(cd "$(dirname "$0")/.." && pwd)"
out_dir="${BACKUP_DIR:-$root/backups}"
mkdir -p "$out_dir"

echo "Backing up $(where)"
major="$(server_major)"
image="$(client_image "$major")"

found="$(psql_value "select count(*) from information_schema.schemata where schema_name = '${DB_SCHEMA}'")"
[[ "$found" == "1" ]] || die "schema ${DB_SCHEMA} not found on $(where)"

case "$CONN_HOST" in
	*supabase*) label="supabase" ;;
	localhost | 127.0.0.1 | "[::1]" | host.docker.internal) label="local" ;;
	*) label="${CONN_HOST%%.*}" ;;
esac
file="$out_dir/settle-${DB_SCHEMA}-${label}-$(date -u +%Y%m%dT%H%M%SZ).dump"
partial="$file.partial"
trap 'rm -f "$partial"' EXIT

pg_run "$image" \
	'pg_dump "$PGURL" --format=custom --schema="$DB_SCHEMA" --extension=vector --no-owner --no-privileges' \
	>"$partial" || die "pg_dump failed"

# Read the dump back: it must open and list the schema's tables.
tables="$(pg_run "$PG_PROBE_IMAGE" 'pg_restore --list' <"$partial" | grep -c " TABLE DATA ")" ||
	die "the dump could not be read back"
[[ "$tables" -gt 0 ]] || die "the dump has no table data"

mv "$partial" "$file"
trap - EXIT
chmod 600 "$file" 2>/dev/null || true
size="$(wc -c <"$file" | tr -d ' ')"
echo "Backup written: $file"
echo "  ${tables} tables, ${size} bytes, pg_dump ${major} (postgres:${major}-alpine)"
