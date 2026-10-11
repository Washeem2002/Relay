#!/usr/bin/env bash
set -euo pipefail

ADMIN_URL="${ADMIN_URL:-http://localhost:9090}"
ADMIN_TOKEN="${SLUICE_ADMIN_TOKEN:?export SLUICE_ADMIN_TOKEN first}"

response=$(curl -fsS -X POST -H "X-Admin-Token: ${ADMIN_TOKEN}" "${ADMIN_URL}/admin/tenants/demo/keys")
api_key=$(printf '%s' "${response}" | sed -n 's/.*"apiKey":"\([^"]*\)".*/\1/p')

echo "demo api key (shown once): ${api_key}"
echo "export SLUICE_LOAD_API_KEY=${api_key}"
