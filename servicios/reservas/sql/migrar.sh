#!/bin/sh
# Uso local: ./sql/migrar.sh [opciones psql]
# Uso remoto: ./sql/migrar.sh --emitir-sql | docker compose exec -T postgres-reservas psql ...
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
emitir() {
    cat <<'SQL'
\set ON_ERROR_STOP on
BEGIN;
SELECT pg_advisory_xact_lock(20261110, 8082);
DO $$ BEGIN
  IF to_regclass('public.reservas') IS NULL THEN
    RAISE EXCEPTION 'No existe public.reservas: inicializar una base nueva con init.sql';
  END IF;
END $$;
SQL
    for migration in "$script_dir"/migrations/V*.sql; do
        cat "$migration"
        printf '\n'
    done
    printf 'COMMIT;\n'
}
if [ "${1:-}" = "--emitir-sql" ]; then
    [ "$#" -eq 1 ] || exit 2
    emitir
else
    emitir | psql -X -v ON_ERROR_STOP=1 "$@"
fi
