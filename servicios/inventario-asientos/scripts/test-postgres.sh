#!/usr/bin/env bash
set -euo pipefail
SERVICE_DIR="$(dirname "$(dirname "$(realpath "${BASH_SOURCE[0]}")")")"
container_id=""
rabbit_id=""
cleanup() {
    if [[ -n "$rabbit_id" ]] && docker inspect "$rabbit_id" >/dev/null 2>&1; then
        if [[ "$(docker inspect --format '{{.State.Paused}}' "$rabbit_id")" == true ]]; then
            docker unpause "$rabbit_id" >/dev/null
        fi
        docker stop "$rabbit_id" >/dev/null
        docker rm "$rabbit_id" >/dev/null
    fi
    if [[ -n "$container_id" ]] && docker inspect "$container_id" >/dev/null 2>&1; then
        docker stop "$container_id" >/dev/null
    fi
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
    --mount "type=bind,source=$SERVICE_DIR/sql/migracion-outbox.sql,target=/verificacion/outbox.sql,readonly" \
    --mount "type=bind,source=$SERVICE_DIR/sql/migracion-outbox-validacion.sql,target=/verificacion/validacion.sql,readonly" \
    --mount "type=bind,source=$SERVICE_DIR/sql/migracion-inbox-reservas.sql,target=/verificacion/inbox.sql,readonly" \
    --mount "type=bind,source=$SERVICE_DIR/sql/migracion-saga-inventario.sql,target=/verificacion/saga.sql,readonly" \
    --mount "type=bind,source=$SERVICE_DIR/src/test/resources/sql/outbox-antes-validacion.sql,target=/verificacion/fixtures-outbox.sql,readonly" \
    --mount "type=bind,source=$SERVICE_DIR/src/test/resources/sql/saga-antes-migracion.sql,target=/verificacion/fixtures-saga.sql,readonly" \
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
    -f /verificacion/original.sql -f /verificacion/migracion.sql -f /verificacion/outbox.sql -f /verificacion/outbox.sql \
    -c "DO \$\$ BEGIN
        IF (SELECT count(*) FROM asientos) <> 2
           OR (SELECT estado FROM asientos WHERE numero_asiento = '1B') <> 'RESERVADO'
           OR (SELECT version FROM asientos WHERE numero_asiento = '1B') <> 3
           OR NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'asientos' AND column_name = 'bloqueo_token')
           OR NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'inventario_outbox')
           OR (SELECT count(*) FROM inventario_outbox) <> 0
        THEN RAISE EXCEPTION 'La migración no conservó el esquema/datos esperados'; END IF;
        END \$\$;"
docker exec "$container_id" psql -U inventario_test -d db_inventario_migracion -v ON_ERROR_STOP=1 \
    -f /verificacion/fixtures-outbox.sql -f /verificacion/validacion.sql -f /verificacion/validacion.sql \
    -f /verificacion/inbox.sql -f /verificacion/inbox.sql \
    -f /verificacion/fixtures-saga.sql -f /verificacion/saga.sql -f /verificacion/saga.sql \
    -c "DO \$\$ BEGIN
        IF (SELECT count(*) FROM inventario_outbox) <> 3
           OR (SELECT count(DISTINCT estado) FROM inventario_outbox) <> 3
           OR (SELECT count(*) FROM inventario_outbox WHERE mensaje = '{\"fixture\":\"conservar\"}') <> 3
           OR (SELECT count(*) FROM retenciones) <> 3
           OR (SELECT count(*) FROM asientos) <> 2
           OR (SELECT count(*) FROM inventario_inbox) <> 1
           OR (SELECT resultado FROM inventario_inbox) <> 'ASIENTO_BLOQUEADO'
           OR (SELECT huella FROM inventario_inbox) <> repeat('a', 64)
           OR (SELECT count(*) FROM inventario_solicitudes) <> 1
           OR (SELECT reserva_clave FROM inventario_solicitudes) <> '123'
           OR (SELECT estado FROM inventario_solicitudes) <> 'BLOQUEADA'
           OR NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_outbox_retencion_tipo')
           OR NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_outbox_resultado_negocio')
           OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'inventario_outbox'::regclass
                         AND conname = 'inventario_outbox_estado_check'
                         AND pg_get_constraintdef(oid) LIKE '%ERROR_DATOS%')
        THEN RAISE EXCEPTION 'La migración de validación alteró datos o no amplió el estado'; END IF;
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
export INVENTARIO_TEST_POSTGRES_CONTAINER="$container_id"
if [[ "${INVENTARIO_RABBIT_INTEGRATION:-false}" == true ]]; then
    docker exec "$container_id" psql -U inventario_test -d db_inventario -v ON_ERROR_STOP=1 \
        -c 'CREATE DATABASE db_inventario_inbox;'
    docker exec "$container_id" psql -U inventario_test -d db_inventario_inbox -v ON_ERROR_STOP=1 \
        -f /docker-entrypoint-initdb.d/init.sql
    export INVENTARIO_TEST_INBOX_DB_URL="jdbc:postgresql://127.0.0.1:${binding##*:}/db_inventario_inbox?socketTimeout=1&connectTimeout=1"
    rabbit_id=$(docker run --detach \
        --label proyecto=inventario-outbox-integracion \
        --env RABBITMQ_DEFAULT_USER=inventario_test \
        --env RABBITMQ_DEFAULT_PASS=inventario_test_aislado \
        --env 'RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS=+S 2:2' \
        --publish 127.0.0.1::5672 \
        --tmpfs /var/lib/rabbitmq:uid=999,gid=999,mode=1777 \
        rabbitmq:3-management)
    ready=false
    # Evita que el CLI cree la cookie Erlang antes de que termine el entrypoint del broker.
    sleep 5
    for ((attempt = 0; attempt < 60; attempt++)); do
        if docker exec --env 'RABBITMQ_CTL_ERL_ARGS=+S 1:1' "$rabbit_id" rabbitmq-diagnostics -q check_port_connectivity >/dev/null 2>&1; then
            ready=true
            break
        fi
        sleep 1
    done
    if [[ "$ready" != true ]]; then docker logs "$rabbit_id"; exit 1; fi
    docker exec --env 'RABBITMQ_CTL_ERL_ARGS=+S 1:1' "$rabbit_id" rabbitmqctl version
    binding=$(docker port "$rabbit_id" 5672/tcp)
    export INVENTARIO_TEST_RABBIT_PORT="${binding##*:}"
    export INVENTARIO_TEST_RABBIT_CONTAINER="$rabbit_id"
fi
mvn -f "$SERVICE_DIR/pom.xml" verify
