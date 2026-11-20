#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository="$(cd "$script_dir/../.." && pwd)"
stable_url="${1:-}"
rc_url="${2:-}"
output_root="${3:-$repository/stellar-enclosure-yuheng-test/stellar-enclosure-yuheng-test-suite/target/mcp-conformance}"

pattern='^https://[A-Za-z0-9][A-Za-z0-9.-]*(:[0-9]{1,5})?/mcp/[A-Za-z0-9][A-Za-z0-9._-]*$'
if [[ $# -gt 3 || ! "$stable_url" =~ $pattern || ! "$rc_url" =~ $pattern ]]; then
  printf '%s\n' 'Provide both published Cilium HTTPS MCP URLs and an optional evidence directory.' >&2
  exit 2
fi

mkdir -p "$output_root/stable" "$output_root/rc"
npx --yes @modelcontextprotocol/conformance@0.1.16 server \
  --url "$stable_url" --suite active --spec-version 2025-11-25 --output-dir "$output_root/stable"
npx --yes @modelcontextprotocol/conformance@0.2.0-alpha.10 server \
  --url "$rc_url" --suite draft --spec-version 2026-07-28 --output-dir "$output_root/rc"
