#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! -f "$1" || ! -r "$1" ]]; then
  printf '%s\n' 'Provide a readable file containing an issued OAuth bearer token.' >&2
  exit 2
fi
token="$(<"$1")"
if [[ ! "$token" =~ ^[A-Za-z0-9._~+/-]+=*$ ]]; then
  printf '%s\n' 'Bearer token file has an invalid format.' >&2
  exit 2
fi
printf 'Authorization: Bearer %s\n' "$token"
