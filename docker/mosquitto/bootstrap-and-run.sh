#!/bin/sh
# 02-add-fleet-auth (tasks 5.1/5.2): the dynamic-security plugin cannot
# create its own first admin identity -- there is no way to send it a
# $CONTROL/dynamic-security/v1 command without already being an
# authenticated admin -- so this script bootstraps one locally via
# `mosquitto_ctrl dynsec init` before the broker ever starts, then
# provisions a second "internal-services" identity (broad pub/sub, used by
# this repo's own health checks and processor heartbeat, which the admin
# identity itself cannot do: admin's role only grants it access to
# $CONTROL/dynamic-security/# and $SYS/#, not ordinary application topics)
# over the running broker's own control topic, since that step has no
# local/file-based equivalent.
#
# mosquitto_ctrl and mosquitto both ship in the same stock
# eclipse-mosquitto:2 image that runs the broker -- this stays a plain
# command override bind-mounted next to mosquitto.conf, not a custom image.
set -e

CONFIG_FILE=/mosquitto/data/dynamic-security.json
FIRST_RUN=false
if [ ! -f "$CONFIG_FILE" ]; then
    FIRST_RUN=true
    mosquitto_ctrl dynsec init "$CONFIG_FILE" "$MOSQUITTO_DYNSEC_ADMIN_USERNAME" "$MOSQUITTO_DYNSEC_ADMIN_PASSWORD"
    # mosquitto_ctrl runs as root here and creates the file owned by root,
    # group-readable only; the broker itself drops privileges to the
    # "mosquitto" user internally and cannot read a root-owned file --
    # without this, the plugin silently discards our bootstrap and
    # regenerates its own (admin-less) default, and every connection,
    # including the backend's own, is then rejected as unauthorised.
    chown mosquitto:mosquitto "$CONFIG_FILE"
fi

mosquitto -c /mosquitto/config/mosquitto.conf &
BROKER_PID=$!

if [ "$FIRST_RUN" = true ]; then
    i=0
    until mosquitto_sub -h localhost -p 1883 -u "$MOSQUITTO_DYNSEC_ADMIN_USERNAME" -P "$MOSQUITTO_DYNSEC_ADMIN_PASSWORD" -t '$SYS/broker/version' -C 1 -W 1 >/dev/null 2>&1; do
        i=$((i + 1))
        if [ "$i" -ge 30 ]; then
            echo "mosquitto did not become ready for dynsec provisioning" >&2
            break
        fi
        sleep 0.5
    done

    mosquitto_ctrl -h localhost -p 1883 -u "$MOSQUITTO_DYNSEC_ADMIN_USERNAME" -P "$MOSQUITTO_DYNSEC_ADMIN_PASSWORD" dynsec createClient "$MOSQUITTO_DYNSEC_SERVICE_USERNAME" -p "$MOSQUITTO_DYNSEC_SERVICE_PASSWORD"
    mosquitto_ctrl -h localhost -p 1883 -u "$MOSQUITTO_DYNSEC_ADMIN_USERNAME" -P "$MOSQUITTO_DYNSEC_ADMIN_PASSWORD" dynsec createRole internal-services
    mosquitto_ctrl -h localhost -p 1883 -u "$MOSQUITTO_DYNSEC_ADMIN_USERNAME" -P "$MOSQUITTO_DYNSEC_ADMIN_PASSWORD" dynsec addRoleACL internal-services publishClientSend '#' allow
    mosquitto_ctrl -h localhost -p 1883 -u "$MOSQUITTO_DYNSEC_ADMIN_USERNAME" -P "$MOSQUITTO_DYNSEC_ADMIN_PASSWORD" dynsec addRoleACL internal-services subscribePattern '#' allow
    mosquitto_ctrl -h localhost -p 1883 -u "$MOSQUITTO_DYNSEC_ADMIN_USERNAME" -P "$MOSQUITTO_DYNSEC_ADMIN_PASSWORD" dynsec addClientRole "$MOSQUITTO_DYNSEC_SERVICE_USERNAME" internal-services
fi

wait "$BROKER_PID"
