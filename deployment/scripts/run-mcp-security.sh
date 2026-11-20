#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository="$(cd "$script_dir/../.." && pwd)"
origin="${YUHENG_CILIUM_MCP_ORIGIN:-}"
pattern='^https://[A-Za-z][A-Za-z0-9.-]*(:443)?$'
if [[ ! "$origin" =~ $pattern ]]; then
  printf '%s\n' 'Set YUHENG_CILIUM_MCP_ORIGIN to the approved Cilium HTTPS origin.' >&2
  exit 2
fi

stable_path="${YUHENG_CILIUM_MCP_STABLE_PATH:-}"
rc_path="${YUHENG_CILIUM_MCP_RC_PATH:-}"
token_file="${YUHENG_CILIUM_MCP_TOKEN_FILE:-}"
published='^/mcp/[a-z0-9][a-z0-9._-]{0,127}$'
if [[ ! "$stable_path" =~ $published || ! "$rc_path" =~ $published || ! -f "$token_file" || ! -r "$token_file" ]]; then
  printf '%s\n' 'Set YUHENG_CILIUM_MCP_STABLE_PATH, YUHENG_CILIUM_MCP_RC_PATH and YUHENG_CILIUM_MCP_TOKEN_FILE.' >&2
  exit 2
fi

cd "$repository"
./mvnw -B -ntp -Pyuheng-live \
  -pl stellar-enclosure-yuheng-test/stellar-enclosure-yuheng-test-suite -am \
  -DskipTests=false -DskipITs=false -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dyuheng.live.cilium.mcp.enabled=true -Dyuheng.live.cilium.mcp.stable-url="$origin$stable_path" \
  -Dyuheng.live.cilium.mcp.rc-url="$origin$rc_path" -Dyuheng.live.cilium.mcp.token-file="$token_file" \
  -Dit.test=McpSecurityIT,McpHaRecoveryIT,McpCompleteReleaseIT,McpStableConformanceIT,McpRcConformanceIT \
  verify
