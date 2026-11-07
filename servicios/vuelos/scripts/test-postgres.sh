#!/usr/bin/env bash
set -euo pipefail

# No utiliza Compose, volúmenes persistentes ni credenciales de desarrollo.
SERVICE_DIR="$(dirname "$(dirname "$(realpath "${BASH_SOURCE[0]}")")")"
container_id=""

cleanup() {
    if [[ -n "$container_id" ]]; then
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
binding=$(docker port "$container_id" 5432/tcp)
port="${binding##*:}"

export VUELOS_POSTGRES_INTEGRATION=true
export VUELOS_TEST_DB_URL="jdbc:postgresql://127.0.0.1:$port/db_vuelos"
export VUELOS_TEST_DB_USER=vuelos_test
export VUELOS_TEST_DB_PASSWORD=vuelos_test_aislado
mvn -f "$SERVICE_DIR/pom.xml" test
