#!/usr/bin/env bash
# Gera um token JWT de teste para a API de pedidos.
# Uso: scripts/gerar-token.sh [cliente] [validade-em-segundos]
# Em produção, os tokens viriam de um provedor de identidade; este script é só para testar.
set -euo pipefail
cd "$(dirname "$0")/.."

# shellcheck disable=SC1091
source .env
customer="${1:-cliente-demo}"
ttl="${2:-3600}"
now="$(date +%s)"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

header='{"alg":"HS256","typ":"JWT"}'
payload="{\"sub\":\"$customer\",\"iss\":\"pedidos-auth\",\"aud\":\"pedidos-api\",\"scope\":\"orders:write orders:read\",\"iat\":$now,\"exp\":$((now + ttl))}"
unsigned="$(printf '%s' "$header" | b64url).$(printf '%s' "$payload" | b64url)"
signature="$(printf '%s' "$unsigned" | openssl dgst -sha256 -hmac "$JWT_SECRET" -binary | b64url)"

echo "$unsigned.$signature"
