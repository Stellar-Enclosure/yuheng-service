#!/usr/bin/env bash
set -euo pipefail

url="${1:-}"
timeout_seconds="${2:-120}"
pattern='^https?://[A-Za-z0-9][A-Za-z0-9.-]*(:[0-9]{1,5})?/actuator/health/readiness$'
if [[ $# -gt 2 || ! "$url" =~ $pattern || ! "$timeout_seconds" =~ ^[1-9][0-9]{0,3}$ ]]; then
  printf '%s\n' 'Provide the explicit management readiness URL and optional timeout in seconds.' >&2
  exit 2
fi

deadline="$((SECONDS + timeout_seconds))"
while ((SECONDS < deadline)); do
  if status="$(curl --disable --proto '=http,https' --silent --max-time 3 --output /dev/null --write-out '%{http_code}' --url "$url")" && [[ "$status" == 200 ]]; then
    exit 0
  fi
  sleep 1
done
printf '%s\n' 'Timed out waiting for the management readiness endpoint.' >&2
exit 1
