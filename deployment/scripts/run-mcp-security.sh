#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "${script_dir}/../../../.." && pwd)"
suite="stellar-enclosure-platform/stellar-enclosure-yuheng/stellar-enclosure-yuheng-test/stellar-enclosure-yuheng-test-suite"

cd "${repository_root}"
./mvnw \
  -pl "${suite}" \
  -am \
  -DskipITs=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='McpSecurityIT,McpHaRecoveryIT,McpCompleteReleaseIT,GatewayRuleWireCompatibilityTest,McpGatewayEngineContextTest' \
  test
