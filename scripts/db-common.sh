#!/usr/bin/env bash
# Shared by db-backup.sh and db-restore.sh; not meant to be run on its own.
#
# The PostgreSQL client tools run in a Docker image that matches the server's major version, so
# nothing has to be installed locally. Connection details come from the environment only:
#   DATABASE_URL                      postgresql://user:password@host:port/db?sslmode=require
#   or the backend's own variables    DB_URL (jdbc:postgresql://host:port/db?...), DB_USERNAME, DB_PASSWORD
# They reach the container by name (docker run -e NAME), never on the command line, and are never printed.

set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'   # Git Bash on Windows: don't rewrite /paths in arguments

DB_SCHEMA="${DB_SCHEMA:-incident}"                   # Flyway's schema (spring.flyway.schemas)
PG_PROBE_IMAGE="${PG_PROBE_IMAGE:-postgres:17-alpine}"
export DB_SCHEMA

die() {
	echo "error: $*" >&2
	exit 1
}

command -v docker >/dev/null 2>&1 || die "docker is required (the PostgreSQL client runs in a container)"
[[ "$DB_SCHEMA" =~ ^[a-z_][a-z0-9_]*$ ]] || die "DB_SCHEMA must be a plain lower-case identifier"

# psql_value SQL: run one query on the target and print the single value it returns.
psql_value() {
	pg_run "$PG_PROBE_IMAGE" "psql \"\$PGURL\" -X -A -t -q -c \"$1\"" | tr -d '\r[:space:]'
}

# libpq accepts these URL parameters; JDBC-only ones (prepareThreshold, currentSchema, ...) are dropped.
LIBPQ_PARAMS=" sslmode sslrootcert sslcert sslkey connect_timeout application_name options target_session_attrs gssencmode "

# Sets PGURL (what the container connects with) plus CONN_HOST, CONN_PORT, CONN_DB for messages and checks.
resolve_connection() {
	local raw user="" password="" params="" kept="" scheme userinfo host port db
	if [[ -n "${DATABASE_URL:-}" ]]; then
		raw="$DATABASE_URL"
	elif [[ -n "${DB_URL:-}" ]]; then
		raw="${DB_URL#jdbc:}"
		user="${DB_USERNAME:-}"
		password="${DB_PASSWORD:-}"
	else
		die "set DATABASE_URL, or DB_URL with DB_USERNAME and DB_PASSWORD (the backend's variables)"
	fi

	local re='^(postgres|postgresql)://(([^@/]*)@)?(\[[^]]+\]|[^:/?]+)(:([0-9]+))?/([^?]+)(\?(.*))?$'
	[[ "$raw" =~ $re ]] || die "cannot read the database URL (expected postgresql://host:port/db or jdbc:postgresql://host:port/db)"
	scheme="${BASH_REMATCH[1]}"
	userinfo="${BASH_REMATCH[3]}"
	host="${BASH_REMATCH[4]}"
	port="${BASH_REMATCH[6]:-5432}"
	db="${BASH_REMATCH[7]}"
	params="${BASH_REMATCH[9]}"

	if [[ -n "$params" ]]; then
		local pair key value
		IFS='&' read -ra pairs <<<"$params"
		for pair in "${pairs[@]}"; do
			key="${pair%%=*}"
			value="${pair#*=}"
			case "$key" in
				user) user="$value" ;;
				password) password="$value" ;;
				ssl) [[ "$value" == "true" ]] && kept+="${kept:+&}sslmode=require" ;;
				*) [[ "$LIBPQ_PARAMS" == *" $key "* ]] && kept+="${kept:+&}$pair" ;;
			esac
		done
	fi

	CONN_HOST="$host"
	CONN_PORT="$port"
	CONN_DB="$db"
	# The client runs in a container, where "localhost" is the container itself. Docker Desktop
	# forwards host.docker.internal to this machine's loopback; a native Linux engine does not (it
	# points at the bridge, so a port bound to 127.0.0.1 refuses), so there the container shares
	# the host's network and keeps "localhost".
	local container_host="$host"
	DOCKER_NET=""
	case "$host" in
		localhost | 127.0.0.1 | "[::1]")
			if docker info --format '{{.OperatingSystem}}' 2>/dev/null | grep -qi 'docker desktop'; then
				container_host="host.docker.internal"
			else
				DOCKER_NET="host"
			fi
			;;
	esac

	PGURL="${scheme}://${userinfo:+${userinfo}@}${container_host}:${port}/${db}${kept:+?${kept}}"
	export PGURL
	[[ -n "$user" ]] && export PGUSER="$user"
	[[ -n "$password" ]] && export PGPASSWORD="$password"
	if [[ "$port" == "6543" ]]; then
		echo "warning: port 6543 is Supabase's transaction pooler; use the session pooler (port 5432) or the direct connection" >&2
	fi
	return 0
}

is_local_host() {
	case "$CONN_HOST" in
		localhost | 127.0.0.1 | "[::1]" | host.docker.internal) return 0 ;;
		*) return 1 ;;
	esac
}

where() {
	echo "${CONN_HOST}:${CONN_PORT}/${CONN_DB} (schema ${DB_SCHEMA})"
}

# pg_run IMAGE SCRIPT: run SCRIPT with sh in IMAGE; stdin and stdout pass through.
pg_run() {
	local image="$1" script="$2" net=(--add-host=host.docker.internal:host-gateway)
	[[ "${DOCKER_NET:-}" == "host" ]] && net=(--network=host)
	docker run --rm -i "${net[@]}" \
		-e PGURL -e PGUSER -e PGPASSWORD -e DB_SCHEMA -e PGCONNECT_TIMEOUT=15 \
		"$image" sh -c "$script"
}

# The server's major version, e.g. 16.
server_major() {
	local num
	num="$(pg_run "$PG_PROBE_IMAGE" 'psql "$PGURL" -X -A -t -q -c "show server_version_num"' | tr -d '\r[:space:]')" ||
		die "cannot connect to $(where)"
	[[ "$num" =~ ^[0-9]+$ ]] || die "unexpected server version from $(where): $num"
	echo $((num / 10000))
}

client_image() {
	echo "postgres:${1}-alpine"
}
