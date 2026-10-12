#!/usr/bin/env bash
# Gera o arquivo .env com chaves e senhas novas para rodar o sistema localmente.
# Cada serviço recebe um par de chaves Ed25519; os outros recebem só a chave pública.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env ]; then
  echo "O arquivo .env já existe. Apague-o antes se quiser gerar chaves novas." >&2
  exit 1
fi

umask 077  # o .env fica legível só pelo seu usuário
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

b64() { base64 < "$1" | tr -d '\n'; }
random() { openssl rand -base64 48 | tr -d '\n/+=' | cut -c1-40; }

for service in order inventory payment; do
  openssl genpkey -algorithm ed25519 -outform DER -out "$tmp/$service.key"
  openssl pkey -inform DER -in "$tmp/$service.key" -pubout -outform DER -out "$tmp/$service.pub"
done

cat > .env <<ENV
# Gerado por scripts/gerar-chaves.sh. NÃO versione este arquivo.
ORDER_PRIVATE_KEY=$(b64 "$tmp/order.key")
INVENTORY_PRIVATE_KEY=$(b64 "$tmp/inventory.key")
PAYMENT_PRIVATE_KEY=$(b64 "$tmp/payment.key")
ORDER_PUBLIC_KEY=$(b64 "$tmp/order.pub")
INVENTORY_PUBLIC_KEY=$(b64 "$tmp/inventory.pub")
PAYMENT_PUBLIC_KEY=$(b64 "$tmp/payment.pub")
JWT_SECRET=$(random)$(random)
POSTGRES_PASSWORD=$(random)
ORDERS_DB_PASSWORD=$(random)
INVENTORY_DB_PASSWORD=$(random)
PAYMENTS_DB_PASSWORD=$(random)
ENV

echo "Arquivo .env criado com chaves novas."
