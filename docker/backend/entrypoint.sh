#!/bin/sh
# Selects which of the two backend jars to run. One image, two jars: see
# openspec/changes/00-bootstrap-monorepo/design.md, "Dos procesos, un
# artefacto" -- api and processor must always run the exact same build of
# domain/geo-core, which two independently built images could not guarantee.
#
# FLEETPULSE_PROCESS=api|processor selects the jar explicitly. When unset,
# falls back to FLY_PROCESS_GROUP, which Fly.io injects automatically for the
# process group a machine belongs to (see fly.toml [processes]), so the same
# image works unmodified as either Fly process group.
set -eu

process="${FLEETPULSE_PROCESS:-${FLY_PROCESS_GROUP:-}}"

case "$process" in
  api)
    exec java -jar /app/api.jar
    ;;
  processor)
    exec java -jar /app/processor.jar
    ;;
  *)
    echo "entrypoint.sh: FLEETPULSE_PROCESS (or FLY_PROCESS_GROUP) must be 'api' or 'processor', got '${process}'" >&2
    exit 1
    ;;
esac
