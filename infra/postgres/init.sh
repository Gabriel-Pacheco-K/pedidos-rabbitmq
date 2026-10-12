#!/usr/bin/env bash
# Um banco e um usuário por serviço: cada serviço só enxerga os próprios dados.
set -euo pipefail

create() {
  local db="$1" user="$2" password="$3"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<SQL
CREATE USER $user WITH PASSWORD '$password';
CREATE DATABASE $db OWNER $user;
REVOKE ALL ON DATABASE $db FROM PUBLIC;
SQL
}

create orders order_svc "$ORDERS_DB_PASSWORD"
create inventory inventory_svc "$INVENTORY_DB_PASSWORD"
create payments payment_svc "$PAYMENTS_DB_PASSWORD"
