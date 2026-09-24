#!/bin/sh
# Runs seed-demo-fleet.sql against the same database api/processor use in
# prod, via the `demo-seed` one-shot compose service (profile `demo`).
# Reuses SPRING_DATASOURCE_URL/USERNAME/PASSWORD exactly -- api's own connection
# env, not a separate demo-only credential -- per the task's "same DB
# connection env as api" requirement.
#
# SPRING_DATASOURCE_URL is a JDBC URL (jdbc:postgresql://host:port/db?params),
# not a libpq URI; psql only understands the latter. Stripping the leading
# "jdbc:" is enough -- the rest (postgresql://host:port/db?params, including
# Neon's sslmode) is already valid libpq URI syntax. Username/password are
# passed as PG* env vars rather than embedded in the URI since the JDBC URL
# never carries them.
set -eu

pg_uri="${SPRING_DATASOURCE_URL#jdbc:}"

PGUSER="$SPRING_DATASOURCE_USERNAME" PGPASSWORD="$SPRING_DATASOURCE_PASSWORD" \
  psql "$pg_uri" -v ON_ERROR_STOP=1 -f /seed/seed-demo-fleet.sql
