# postgis/postgis does not bundle pg_partman. The PGDG apt repo it already
# configures to install PostGIS also carries postgresql-<major>-partman, so
# we install it from there instead of pulling in a second registry.
FROM postgis/postgis:17-3.5

RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-17-partman \
    && rm -rf /var/lib/apt/lists/*
