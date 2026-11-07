#!/usr/bin/env bash
set -euo pipefail
SERVICE_DIR="$(dirname "$(dirname "$(realpath "${BASH_SOURCE[0]}")")")"
container_id=""
cleanup() {
    if [[ -n "$container_id" ]]; then docker stop "$container_id" >/dev/null; fi
}
trap cleanup EXIT
docker info >/dev/null
java -version
mvn -version
container_id=$(docker run --detach --rm \
    --label proyecto=inventario-integracion \
    --env POSTGRES_DB=db_inventario \
    --env POSTGRES_USER=inventario_test \
    --env POSTGRES_PASSWORD=inventario_test_aislado \
    --publish 127.0.0.1::5432 \
    --tmpfs /var/lib/postgresql/data \
    --mount "type=bind,source=$SERVICE_DIR/sql/init.sql,target=/docker-entrypoint-initdb.d/init.sql,readonly" \
    --mount "type=bind,source=$SERVICE_DIR/sql/migracion-retenciones.sql,target=/verificacion/migracion.sql,readonly" \
    --mount "type=bind,source=$SERVICE_DIR/src/test/resources/sql/esquema-original.sql,target=/verificacion/original.sql,readonly" \
    postgres:15-alpine)
ready=false
for ((attempt = 0; attempt < 60; attempt++)); do
    if docker exec "$container_id" pg_isready -h 127.0.0.1 -U inventario_test -d db_inventario >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 1
done
if [[ "$ready" != true ]]; then docker logs "$container_id"; exit 1; fi
docker exec "$container_id" psql -U inventario_test -d db_inventario \
    -v ON_ERROR_STOP=1 -c 'SELECT version();' -c '\d asientos' -c '\d retenciones'

# Migración sobre DOS bases adicionales nuevas del mismo contenedor temporal.
docker exec "$container_id" psql -U inventario_test -d db_inventario -v ON_ERROR_STOP=1 \
    -c 'CREATE DATABASE db_inventario_migracion;' -c 'CREATE DATABASE db_inventario_legacy_bloqueado;'
docker exec "$container_id" psql -U inventario_test -d db_inventario_migracion -v ON_ERROR_STOP=1 \
    -f /verificacion/original.sql -f /verificacion/migracion.sql \
    -c "DO \$\$ BEGIN
        IF (SELECT count(*) FROM asientos) <> 2
           OR (SELECT estado FROM asientos WHERE numero_asiento = '1B') <> 'RESERVADO'
           OR (SELECT version FROM asientos WHERE numero_asiento = '1B') <> 3
           OR NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'asientos' AND column_name = 'bloqueo_token')
        THEN RAISE EXCEPTION 'La migración no conservó el esquema/datos esperados'; END IF;
        END \$\$;"
docker exec "$container_id" psql -U inventario_test -d db_inventario_legacy_bloqueado -v ON_ERROR_STOP=1 \
    -f /verificacion/original.sql -c "UPDATE asientos SET estado = 'BLOQUEADO' WHERE numero_asiento = '1A';"
if migration_error=$(docker exec "$container_id" psql -U inventario_test -d db_inventario_legacy_bloqueado \
    -v ON_ERROR_STOP=1 -f /verificacion/migracion.sql 2>&1); then
    exit 1 # Debía rechazar la migración de bloqueos sin dueño.
fi
[[ "$migration_error" == *"Hay bloqueos heredados"* ]]
docker exec "$container_id" psql -U inventario_test -d db_inventario_legacy_bloqueado -v ON_ERROR_STOP=1 \
    -c "DO \$\$ BEGIN
        IF (SELECT count(*) FROM asientos) <> 2
           OR (SELECT estado FROM asientos WHERE numero_asiento = '1A') <> 'BLOQUEADO'
           OR EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'asientos' AND column_name = 'bloqueo_token')
        THEN RAISE EXCEPTION 'La migración rechazada modificó datos/esquema'; END IF;
        END \$\$;"

binding=$(docker port "$container_id" 5432/tcp)
export INVENTARIO_POSTGRES_INTEGRATION=true
export INVENTARIO_TEST_DB_URL="jdbc:postgresql://127.0.0.1:${binding##*:}/db_inventario"
export INVENTARIO_TEST_DB_USER=inventario_test
export INVENTARIO_TEST_DB_PASSWORD=inventario_test_aislado
mvn -f "$SERVICE_DIR/pom.xml" verify
