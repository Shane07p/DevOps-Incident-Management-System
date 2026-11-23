#!/usr/bin/env bash
# Restore a backup made by db-backup.sh into the database the environment points at.
#
#   scripts/db-restore.sh backups/settle-incident-supabase-20261123T143000Z.dump --confirm <database-name>
#   ... --production      also required when the target is not on this machine (e.g. Supabase)
#
# The schema (incident) is dropped and rebuilt from the backup in ONE transaction: if anything fails,
# the database is left exactly as it was. Nothing outside the schema is touched. Stop the backend (or
# expect a few failed requests) while it runs; on its next start Flyway checks the restored history.

source "$(dirname "$0")/db-common.sh"

usage() {
	echo "usage: $0 <backup.dump> --confirm <database-name> [--production]" >&2
	exit 2
}

file=""
confirm=""
production=0
while [[ $# -gt 0 ]]; do
	case "$1" in
		--confirm)
			[[ $# -ge 2 ]] || usage
			confirm="$2"
			shift 2
			;;
		--production)
			production=1
			shift
			;;
		-h | --help) usage ;;
		-*) usage ;;
		*)
			[[ -z "$file" ]] || usage
			file="$1"
			shift
			;;
	esac
done
[[ -n "$file" ]] || usage
[[ -f "$file" ]] || die "no such file: $file"

resolve_connection
[[ -n "$confirm" ]] || die "this replaces schema ${DB_SCHEMA} on $(where); add --confirm ${CONN_DB} to go ahead"
[[ "$confirm" == "$CONN_DB" ]] || die "--confirm ${confirm} does not match the target database ${CONN_DB}"
if ! is_local_host && [[ "$production" -ne 1 ]]; then
	die "$(where) is not on this machine; add --production if you mean to restore there"
fi

dump_major="$(pg_run "$PG_PROBE_IMAGE" 'pg_restore --list' <"$file" |
	sed -n 's/^;[[:space:]]*Dumped by pg_dump version: \([0-9]*\).*/\1/p' | head -n 1)" ||
	die "$file is not a backup made by db-backup.sh"
[[ "$dump_major" =~ ^[0-9]+$ ]] || die "$file is not a pg_dump custom-format backup"

echo "Restoring $(basename "$file") into $(where)"
target_major="$(server_major)"
major=$((dump_major > target_major ? dump_major : target_major))
image="$(client_image "$major")"

# The whole restore script is written out first, so psql only ever runs a complete one; psql then runs
# it as a single transaction that stops at the first error (and rolls everything back).
# shellcheck disable=SC2016  # expanded inside the container
pg_run "$image" '
	set -e
	cat > /tmp/settle.dump
	# The extension comment belongs to the extension owner (not the app user on Supabase): skip it.
	pg_restore --list /tmp/settle.dump | grep -v "COMMENT - EXTENSION" > /tmp/settle.list
	pg_restore --use-list=/tmp/settle.list --no-owner --no-privileges --file=/tmp/body.sql /tmp/settle.dump
	{
		echo "SET client_min_messages = warning;"
		echo "SET lock_timeout = '"'"'30s'"'"';"
		echo "DROP SCHEMA IF EXISTS \"$DB_SCHEMA\" CASCADE;"
		cat /tmp/body.sql
	} > /tmp/restore.sql
	psql "$PGURL" -X -q --single-transaction -v ON_ERROR_STOP=1 -f /tmp/restore.sql >/dev/null
' <"$file" || die "restore failed; the database was not changed"

tables="$(psql_value "select count(*) from information_schema.tables where table_schema = '${DB_SCHEMA}' and table_type = 'BASE TABLE'")"
echo "Restored: ${tables} tables in ${DB_SCHEMA} on $(where)"
