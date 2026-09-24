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
#
# FLEETPULSE_PROCESS=simulator is a third, opt-in value (prod demo service,
# profile `demo` in docker-compose.prod.yml): it runs the dev-only device
# simulator's own main() (DeviceSimulatorMain, task 5.3) from inside
# processor.jar instead of ProcessorApplication. DeviceSimulatorMain never
# boots a Spring context, so PropertiesLauncher's -Dloader.main override
# just points the fat jar's own classloader at a different plain class on
# the same BOOT-INF classpath -- no separate jar or image needed.
set -eu

process="${FLEETPULSE_PROCESS:-}"

case "$process" in
  api)
    exec java -jar /app/api.jar
    ;;
  processor)
    exec java -jar /app/processor.jar
    ;;
  simulator)
    exec java -cp /app/processor.jar \
      -Dloader.main=dev.fleetpulse.processor.simulator.DeviceSimulatorMain \
      org.springframework.boot.loader.launch.PropertiesLauncher
    ;;
  *)
    echo "entrypoint.sh: FLEETPULSE_PROCESS must be 'api', 'processor' or 'simulator', got '${process}'" >&2
    exit 1
    ;;
esac
