#!/bin/sh
# Prod demo service (odd/tasks/prod-demo-simulator.md, task 5.4): provisions
# a dedicated, publish-only dynsec identity for the simulator container --
# never the broad "internal-services" identity bootstrap-and-run.sh creates
# for api/processor's own health/heartbeat checks. Run against the already
# running broker, over the CI deploy pipeline:
#   docker compose exec -T mosquitto sh /mosquitto/provision-demo-simulator.sh
# (path is bind-mounted, same convention as bootstrap-and-run.sh).
#
# Idempotent: mosquitto_ctrl exits non-zero when a client/role/ACL/role
# assignment already exists ("already exists" on stderr), which would
# otherwise fail every redeploy after the first. Each mutating call is
# wrapped so only that specific already-exists case is tolerated --
# anything else still fails the script (and the deploy step calling it).
set -u

: "${MOSQUITTO_DYNSEC_ADMIN_USERNAME:?MOSQUITTO_DYNSEC_ADMIN_USERNAME is required}"
: "${MOSQUITTO_DYNSEC_ADMIN_PASSWORD:?MOSQUITTO_DYNSEC_ADMIN_PASSWORD is required}"
: "${MOSQUITTO_DEMO_SIMULATOR_PASSWORD:?MOSQUITTO_DEMO_SIMULATOR_PASSWORD is required}"
: "${SIMULATOR_ORG_ID:?SIMULATOR_ORG_ID is required}"

CLIENT_NAME=demo-simulator
ROLE_NAME=demo-simulator

ctrl() {
  mosquitto_ctrl -h localhost -p 1883 \
    -u "$MOSQUITTO_DYNSEC_ADMIN_USERNAME" -P "$MOSQUITTO_DYNSEC_ADMIN_PASSWORD" \
    dynsec "$@"
}

# Runs one dynsec mutation, tolerating only an "already exists" failure --
# any other non-zero exit (bad admin creds, broker unreachable, malformed
# command) still fails the script.
run_idempotent() {
  output=$(ctrl "$@" 2>&1)
  status=$?
  if [ $status -ne 0 ]; then
    case "$output" in
      *"already exists"*)
        echo "provision-demo-simulator.sh: '$*' already applied, skipping" >&2
        ;;
      *)
        echo "$output" >&2
        return $status
        ;;
    esac
  fi
  return 0
}

run_idempotent createClient "$CLIENT_NAME" -p "$MOSQUITTO_DEMO_SIMULATOR_PASSWORD"
run_idempotent createRole "$ROLE_NAME"
run_idempotent addRoleACL "$ROLE_NAME" publishClientSend "fleet/${SIMULATOR_ORG_ID}/vehicle/+/telemetry" allow
run_idempotent addRoleACL "$ROLE_NAME" publishClientSend "fleet/${SIMULATOR_ORG_ID}/vehicle/+/status" allow
run_idempotent addClientRole "$CLIENT_NAME" "$ROLE_NAME"

echo "provision-demo-simulator.sh: done"
