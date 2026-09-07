#!/bin/sh
# Docker HEALTHCHECK for the shared api/processor image. The two processes
# need different checks because processor deliberately exposes no HTTP
# server (see design.md, "Salud": "processor no expone HTTP; si muere,
# ninguna pagina se rompe"). Its heartbeat freshness is observed externally,
# via api's own /actuator/health processorHeartbeat indicator (task 5.3),
# not from inside this container.
set -eu

# See entrypoint.sh: no provider-injected fallback since the Oracle Cloud
# migration -- FLEETPULSE_PROCESS is always set explicitly by the caller.
process="${FLEETPULSE_PROCESS:-}"

if [ "$process" = "processor" ]; then
  # PID 1 is the java process itself (entrypoint.sh execs it); if it is gone,
  # the container is not healthy.
  kill -0 1 2>/dev/null
else
  # Spring Boot Actuator maps a DOWN/OUT_OF_SERVICE health status to HTTP 503
  # by default, so a plain 2xx check is enough to catch a degraded api too
  # (e.g. mqttBroker or processorHeartbeat DOWN), not just a crashed process.
  wget -q -O /dev/null "http://localhost:${SERVER_PORT:-8080}/actuator/health"
fi
