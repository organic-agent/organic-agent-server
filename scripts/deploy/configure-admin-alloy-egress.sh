#!/usr/bin/env bash
set -euo pipefail

# Restrict the dedicated Alloy Docker subnet to the configured Loki endpoint.
# The host runs this as root after Docker creates DOCKER-USER and before Alloy starts.

: "${ALLOY_EGRESS_SUBNET:?ALLOY_EGRESS_SUBNET must be set}"
: "${AWS_REGION:?AWS_REGION must be set}"

LOKI_PARAMETER="${LOKI_PARAMETER:-/wes/admin-api/prod/app.logging.loki-url}"
RESTART_STOPPED_ALLOY="${RESTART_STOPPED_ALLOY:-true}"
IMDS_ADDRESS="169.254.169.254/32"
CHAIN_NAME="WES_ALLOY_OUT"
HOST_CHAIN_NAME="WES_ALLOY_HOST"
READY_DIRECTORY="/run/wes-alloy-egress"
READY_FILE="$READY_DIRECTORY/ready"
LOKI_HOST_FILE="$READY_DIRECTORY/loki-host"
LOKI_PRIMARY_IP_FILE="$READY_DIRECTORY/loki-primary-ip"
LOKI_PORT_FILE="$READY_DIRECTORY/loki-port"

# A policy refresh is fail-closed. Revoke readiness first, then stop an existing
# Alloy before touching its live chains. A failed refresh intentionally leaves
# the collector stopped instead of exposing a partially-written rule set.
mkdir -p "$READY_DIRECTORY"
rm -f -- "$READY_FILE" "$LOKI_HOST_FILE" "$LOKI_PRIMARY_IP_FILE" "$LOKI_PORT_FILE"
alloy_was_running=false
alloy_previous_restart_policy=""
if systemctl is-active --quiet docker.service; then
  command -v docker >/dev/null 2>&1 || {
    echo "docker is active but its CLI is missing" >&2
    exit 1
  }
  if docker inspect wes-admin-alloy >/dev/null 2>&1; then
    alloy_previous_restart_policy="$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' wes-admin-alloy)"
    if [ "$(docker inspect --format '{{.State.Running}}' wes-admin-alloy)" = "true" ] ||
      [ "$(docker inspect --format '{{.State.Restarting}}' wes-admin-alloy)" = "true" ]; then
      alloy_was_running=true
    fi
    # Disable restart before stop so a backoff/restarting container cannot race
    # the live-chain refresh below.
    docker update --restart=no wes-admin-alloy >/dev/null
    docker stop --time 30 wes-admin-alloy >/dev/null
  fi
fi

for command_name in aws chmod getent install iptables python3 rm systemctl; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "required command is missing: $command_name" >&2
    exit 1
  }
done

# /run is empty after every boot. Alloy bind-mounts this directory read-only and
# waits for the marker, so a failed or disabled policy unit cannot start egress.
install -d -m 0755 "$READY_DIRECTORY"

if [ -z "${LOKI_URL:-}" ]; then
  LOKI_URL="$(
    aws ssm get-parameter \
      --region "$AWS_REGION" \
      --name "$LOKI_PARAMETER" \
      --query Parameter.Value \
      --output text
  )"
fi

mapfile -t loki_endpoint < <(
  python3 -c '
import sys
from urllib.parse import urlsplit

parsed = urlsplit(sys.argv[1])
if parsed.scheme not in {"http", "https"}:
    raise SystemExit("Loki URL must use http or https")
if not parsed.hostname or parsed.username or parsed.password:
    raise SystemExit("Loki URL must contain a host and no inline credentials")
if ":" in parsed.hostname:
    raise SystemExit("IPv6 Loki endpoints are not supported by this IPv4 firewall")
port = parsed.port or (443 if parsed.scheme == "https" else 80)
print(parsed.hostname)
print(port)
' "$LOKI_URL"
)

LOKI_HOST="${loki_endpoint[0]:-}"
LOKI_PORT="${loki_endpoint[1]:-}"
[ -n "$LOKI_HOST" ] && [ -n "$LOKI_PORT" ] || {
  echo "failed to parse Loki endpoint" >&2
  exit 1
}

mapfile -t loki_ipv4_addresses < <(
  getent ahostsv4 "$LOKI_HOST" | awk '{print $1}' | sort -u
)
[ "${#loki_ipv4_addresses[@]}" -gt 0 ] || {
  echo "Loki host has no IPv4 address" >&2
  exit 1
}
LOKI_PRIMARY_IP="${loki_ipv4_addresses[0]}"

# The boot unit runs before Docker so the rule exists before restart=always
# containers are restored. Docker reuses the pre-created DOCKER-USER chain.
iptables -w 5 -N DOCKER-USER 2>/dev/null || true
iptables -w 5 -N "$CHAIN_NAME" 2>/dev/null || true
iptables -w 5 -N "$HOST_CHAIN_NAME" 2>/dev/null || true
iptables -w 5 -F "$CHAIN_NAME"
iptables -w 5 -F "$HOST_CHAIN_NAME"

# Reject credentials first even if a future Loki DNS record is accidentally set to IMDS.
iptables -w 5 -A "$CHAIN_NAME" -d "$IMDS_ADDRESS" -j REJECT
for loki_ip in "${loki_ipv4_addresses[@]}"; do
  iptables -w 5 -A "$CHAIN_NAME" \
    -p tcp -d "$loki_ip/32" --dport "$LOKI_PORT" -j ACCEPT
done
# Fail closed: Alloy cannot reach arbitrary Internet, VPC, or link-local destinations.
iptables -w 5 -A "$CHAIN_NAME" -j REJECT

# DOCKER-USER only sees forwarded packets. Traffic to the bridge gateway or another
# host-local address follows INPUT, so reject that path independently as well.
iptables -w 5 -A "$HOST_CHAIN_NAME" -j REJECT

# A pre-existing jump after a broad ACCEPT is ineffective. Remove every exact
# duplicate and reinsert one canonical jump as rule 1 in each ingress chain.
while iptables -w 5 -C DOCKER-USER -s "$ALLOY_EGRESS_SUBNET" -j "$CHAIN_NAME" 2>/dev/null; do
  iptables -w 5 -D DOCKER-USER -s "$ALLOY_EGRESS_SUBNET" -j "$CHAIN_NAME"
done
iptables -w 5 -I DOCKER-USER 1 -s "$ALLOY_EGRESS_SUBNET" -j "$CHAIN_NAME"

while iptables -w 5 -C INPUT -s "$ALLOY_EGRESS_SUBNET" -j "$HOST_CHAIN_NAME" 2>/dev/null; do
  iptables -w 5 -D INPUT -s "$ALLOY_EGRESS_SUBNET" -j "$HOST_CHAIN_NAME"
done
iptables -w 5 -I INPUT 1 -s "$ALLOY_EGRESS_SUBNET" -j "$HOST_CHAIN_NAME"

iptables -w 5 -C "$CHAIN_NAME" -d "$IMDS_ADDRESS" -j REJECT >/dev/null
iptables -w 5 -C "$CHAIN_NAME" -j REJECT >/dev/null
iptables -w 5 -C "$HOST_CHAIN_NAME" -j REJECT >/dev/null
EXPECTED_FORWARD_JUMP="-A DOCKER-USER -s $ALLOY_EGRESS_SUBNET -j $CHAIN_NAME"
EXPECTED_HOST_JUMP="-A INPUT -s $ALLOY_EGRESS_SUBNET -j $HOST_CHAIN_NAME"
FIRST_FORWARD_RULE="$(iptables -w 5 -S DOCKER-USER | awk '$1 == "-A" { print; exit }')"
FIRST_HOST_RULE="$(iptables -w 5 -S INPUT | awk '$1 == "-A" { print; exit }')"
FORWARD_JUMP_COUNT="$(iptables -w 5 -S DOCKER-USER | awk -v expected="$EXPECTED_FORWARD_JUMP" '
  $0 == expected { count++ }
  END { print count + 0 }
')"
HOST_JUMP_COUNT="$(iptables -w 5 -S INPUT | awk -v expected="$EXPECTED_HOST_JUMP" '
  $0 == expected { count++ }
  END { print count + 0 }
')"
[ "$FIRST_FORWARD_RULE" = "$EXPECTED_FORWARD_JUMP" ] || {
  echo "Alloy DOCKER-USER jump is not rule 1: $FIRST_FORWARD_RULE" >&2
  exit 1
}
[ "$FIRST_HOST_RULE" = "$EXPECTED_HOST_JUMP" ] || {
  echo "Alloy INPUT jump is not rule 1: $FIRST_HOST_RULE" >&2
  exit 1
}
[ "$FORWARD_JUMP_COUNT" -eq 1 ] || {
  echo "Alloy DOCKER-USER jump count is not exactly one: $FORWARD_JUMP_COUNT" >&2
  exit 1
}
[ "$HOST_JUMP_COUNT" -eq 1 ] || {
  echo "Alloy INPUT jump count is not exactly one: $HOST_JUMP_COUNT" >&2
  exit 1
}
printf '%s\n' "$LOKI_HOST" > "$LOKI_HOST_FILE"
printf '%s\n' "$LOKI_PRIMARY_IP" > "$LOKI_PRIMARY_IP_FILE"
printf '%s\n' "$LOKI_PORT" > "$LOKI_PORT_FILE"
chmod 0444 "$LOKI_HOST_FILE" "$LOKI_PRIMARY_IP_FILE" "$LOKI_PORT_FILE"
install -m 0444 /dev/null "$READY_FILE"
if [ "$alloy_was_running" = "true" ] && [ "$RESTART_STOPPED_ALLOY" = "true" ]; then
  docker update --restart="${alloy_previous_restart_policy:-always}" wes-admin-alloy >/dev/null
  docker start wes-admin-alloy >/dev/null
elif [ -n "$alloy_previous_restart_policy" ] && [ "$RESTART_STOPPED_ALLOY" = "true" ]; then
  docker update --restart="$alloy_previous_restart_policy" wes-admin-alloy >/dev/null
fi
echo "Alloy egress is restricted to the configured Loki endpoint"
