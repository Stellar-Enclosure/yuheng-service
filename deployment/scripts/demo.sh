#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
deployment_dir="$(cd "$script_dir/.." && pwd)"

usage() {
  cat <<'USAGE'
Local fixtures
  demo.sh config|build|up|logs|down [compose arguments]

Review environment
  demo.sh verify <https-url> <expected-http-status>
  demo.sh exposure-get <http-admin-service-origin> <exposure-id>
  demo.sh exposure-state <http-admin-service-origin> <exposure-id> <strong-etag> <idempotency-key> <state-json>

Provide YUHENG_DEMO_BEARER_FILE with the issued OAuth token for the selected endpoint.
USAGE
}

fail_usage() {
  printf '%s\n' "$1" >&2
  exit 2
}

require_https() {
  local authority='[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?'
  local pattern="^https://${authority}(/[A-Za-z0-9._~%/-]*)?$"
  [[ "$1" =~ $pattern ]] || fail_usage 'Provide an explicit HTTPS URL without credentials, query or fragment.'
}

request() {
  local protocol="$1"
  shift
  local header
  header="$("$script_dir/demo-token.sh" "${YUHENG_DEMO_BEARER_FILE:-}")" || return "$?"
  curl --disable --noproxy '*' --proto "=$protocol" --max-time 15 --silent --show-error --header @- "$@" <<< "$header"
}

exposure_url() {
  local origin="${1%/}"
  local label='[a-z]([a-z0-9-]*[a-z0-9])?'
  local pattern="^http://$label\.$label\.svc\.cluster\.local:([1-9][0-9]{0,4})$"
  [[ "$origin" =~ $pattern ]] || fail_usage 'Provide the reviewed internal management Service origin.'
  local port="${BASH_REMATCH[3]}"
  ((port <= 65535 && port != 19099)) || fail_usage 'Provide the approved management port.'
  [[ "$2" =~ ^[1-9][0-9]{0,18}$ ]] || fail_usage 'Provide a positive exposure ID.'
  printf '%s/api/v1/yuheng/admin/exposures/%s\n' "$origin" "$2"
}

case "${1:---help}" in
  -h|--help)
    usage
    ;;
  config|build|up|logs|down)
    docker compose --project-directory "$deployment_dir" -f "$deployment_dir/compose.yml" -f "$deployment_dir/compose.demo.yml" "$@"
    ;;
  verify)
    [[ $# -eq 3 && "$3" =~ ^[1-5][0-9][0-9]$ ]] || fail_usage 'Provide a URL and expected HTTP status.'
    require_https "$2"
    actual="$(request https --url "$2" --output /dev/null --write-out '%{http_code}')"
    if [[ "$actual" != "$3" ]]; then
      printf 'Expected HTTP %s, received HTTP %s\n' "$3" "$actual" >&2
      exit 1
    fi
    printf 'HTTP %s\n' "$actual"
    ;;
  exposure-get)
    [[ $# -eq 3 ]] || fail_usage 'Provide an admin origin and exposure ID.'
    url="$(exposure_url "$2" "$3")"
    request http --url "$url" --include --fail-with-body
    ;;
  exposure-state)
    [[ $# -eq 6 ]] || fail_usage 'Provide an admin origin, exposure ID, ETag, idempotency key and a JSON file.'
    url="$(exposure_url "$2" "$3")"
    etag_pattern="^\"exposure-$3-v(0|[1-9][0-9]{0,18})\"$"
    [[ "$4" =~ $etag_pattern ]] || fail_usage 'The strong ETag must belong to the requested exposure.'
    [[ "$5" =~ ^[A-Za-z0-9._-]{1,128}$ && -f "$6" && -r "$6" ]] || fail_usage 'Provide a valid idempotency key and readable JSON file.'
    request http --url "$url/state" --request PUT --header "If-Match: $4" --header "Idempotency-Key: $5" \
      --header 'Content-Type: application/json' --data-binary "@$6" --include --fail-with-body
    ;;
  *)
    fail_usage 'Unknown command. Run demo.sh --help.'
    ;;
esac
