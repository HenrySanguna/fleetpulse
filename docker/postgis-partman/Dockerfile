# postgis/postgis does not bundle pg_partman. The PGDG apt repo it already
# configures to install PostGIS also carries postgresql-<major>-partman, so
# we install it from there instead of pulling in a second registry.
#
# Major version pinned to match the real target: Neon (project.md) runs
# PostgreSQL 18. Testcontainers-based tests and local dev must run the
# same major version the production database actually uses, not whatever
# was current when this file was first written.
FROM postgis/postgis:18-3.6

RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-18-partman \
    && rm -rf /var/lib/apt/lists/*
