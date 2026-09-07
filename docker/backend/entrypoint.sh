#!/bin/sh
# Selects which of the two backend jars to run. One image, two jars: see
# openspec/changes/00-bootstrap-monorepo/design.md, "Dos procesos, un
# artefacto" -- api and processor must always run the exact same build of
# domain/geo-core, which two independently built images could not guarantee.
#
# FLEETPULSE_PROCESS=api|processor selects the jar explicitly, set per
# service in docker-compose.prod.yml (Oracle VM) and in the
# docker-and-compose-smoke CI job. No provider-injected fallback: this used
# to also accept FLY_PROCESS_GROUP for Fly.io, retired when hosting moved to
# an Oracle Cloud Always Free VM (see openspec/project.md "Hosting" and
# openspec/changes/00-bootstrap-monorepo/tasks.md, task 6.5).
set -eu

process="${FLEETPULSE_PROCESS:-}"

case "$process" in
  api)
    exec java -jar /app/api.jar
    ;;
  processor)
    exec java -jar /app/processor.jar
    ;;
  *)
    echo "entrypoint.sh: FLEETPULSE_PROCESS must be 'api' or 'processor', got '${process}'" >&2
    exit 1
    ;;
esac
