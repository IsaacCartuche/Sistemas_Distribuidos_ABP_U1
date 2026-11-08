#!/usr/bin/env bash
set -euo pipefail

# No utiliza Compose, volúmenes persistentes ni credenciales de desarrollo.
SERVICE_DIR="$(dirname "$(dirname "$(realpath "${BASH_SOURCE[0]}")")")"
container_id=""
rabbit_id=""

cleanup() {
    if [[ -n "$rabbit_id" ]] && docker inspect "$rabbit_id" >/dev/null 2>&1; then
        if [[ "$(docker inspect --format '{{.State.Paused}}' "$rabbit_id")" == true ]]; then docker unpause "$rabbit_id" >/dev/null; fi
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
    --label proyecto=vuelos-integracion \
    --env POSTGRES_DB=db_vuelos \
    --env POSTGRES_USER=vuelos_test \
    --env POSTGRES_PASSWORD=vuelos_test_aislado \
    --publish 127.0.0.1::5432 \
    --tmpfs /var/lib/postgresql/data \
    --mount "type=bind,source=$SERVICE_DIR/sql/init.sql,target=/docker-entrypoint-initdb.d/init.sql,readonly" \
    --mount "type=bind,source=$SERVICE_DIR/sql/migracion-version-outbox.sql,target=/verificacion/migracion.sql,readonly" \
    --mount "type=bind,source=$SERVICE_DIR/src/test/resources/sql/esquema-anterior.sql,target=/verificacion/anterior.sql,readonly" \
    postgres:15-alpine)

ready=false
for ((attempt = 0; attempt < 60; attempt++)); do
    if docker exec "$container_id" pg_isready -h 127.0.0.1 -U vuelos_test -d db_vuelos >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 1
done
if [[ "$ready" != true ]]; then
    docker logs "$container_id"
    exit 1
fi

docker exec "$container_id" psql -U vuelos_test -d db_vuelos \
    -v ON_ERROR_STOP=1 -c 'SELECT version();' -c '\d vuelos'
docker exec "$container_id" psql -U vuelos_test -d db_vuelos -v ON_ERROR_STOP=1 \
    -c 'CREATE DATABASE db_vuelos_migracion;'
docker exec "$container_id" psql -U vuelos_test -d db_vuelos_migracion -v ON_ERROR_STOP=1 \
    -f /verificacion/anterior.sql -f /verificacion/migracion.sql \
    -c "INSERT INTO vuelos_outbox(event_id, correlation_id, vuelo_id, revision, tipo, mensaje)
        VALUES ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
        1, 1, 'vuelos.actualizados', '{\"fixture\":\"conservar\"}');" \
    -f /verificacion/migracion.sql \
    -c "DO \$\$ BEGIN
        IF (SELECT count(*) FROM vuelos) <> 1 OR (SELECT precio FROM vuelos WHERE id = 1) <> 125.50
           OR (SELECT version FROM vuelos WHERE id = 1) <> 0 OR (SELECT count(*) FROM vuelos_outbox) <> 1
           OR (SELECT mensaje FROM vuelos_outbox) <> '{\"fixture\":\"conservar\"}'
        THEN RAISE EXCEPTION 'La migración no conservó los datos'; END IF;
        END \$\$;"
binding=$(docker port "$container_id" 5432/tcp)
port="${binding##*:}"

export VUELOS_POSTGRES_INTEGRATION=true
export VUELOS_TEST_DB_URL="jdbc:postgresql://127.0.0.1:$port/db_vuelos"
export VUELOS_TEST_DB_USER=vuelos_test
export VUELOS_TEST_DB_PASSWORD=vuelos_test_aislado
if [[ "${VUELOS_RABBIT_INTEGRATION:-false}" == true ]]; then
    docker exec "$container_id" psql -U vuelos_test -d db_vuelos -v ON_ERROR_STOP=1 -c 'CREATE DATABASE db_vuelos_outbox;'
    docker exec "$container_id" psql -U vuelos_test -d db_vuelos_outbox -v ON_ERROR_STOP=1 -f /docker-entrypoint-initdb.d/init.sql
    export VUELOS_TEST_OUTBOX_DB_URL="jdbc:postgresql://127.0.0.1:$port/db_vuelos_outbox"
    rabbit_id=$(docker run --detach --label proyecto=vuelos-outbox-integracion \
        --env RABBITMQ_DEFAULT_USER=vuelos_test --env RABBITMQ_DEFAULT_PASS=vuelos_test_aislado \
        --env 'RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS=+S 2:2' --publish 127.0.0.1::5672 \
        --tmpfs /var/lib/rabbitmq:uid=999,gid=999,mode=1777 rabbitmq:3-management)
    sleep 5
    ready=false
    for ((attempt = 0; attempt < 60; attempt++)); do
        if docker exec --env 'RABBITMQ_CTL_ERL_ARGS=+S 1:1' "$rabbit_id" rabbitmq-diagnostics -q check_port_connectivity >/dev/null 2>&1; then
            ready=true; break
        fi
        sleep 1
    done
    if [[ "$ready" != true ]]; then docker logs "$rabbit_id"; exit 1; fi
    docker exec --env 'RABBITMQ_CTL_ERL_ARGS=+S 1:1' "$rabbit_id" rabbitmqctl version
    binding=$(docker port "$rabbit_id" 5672/tcp)
    export VUELOS_TEST_RABBIT_PORT="${binding##*:}"
    export VUELOS_TEST_RABBIT_CONTAINER="$rabbit_id"
fi
mvn -f "$SERVICE_DIR/pom.xml" verify
