#!/usr/bin/env bash
set -euo pipefail
SERVICE_DIR="$(dirname "$(dirname "$(realpath "${BASH_SOURCE[0]}")")")"
ROOT_DIR="$(dirname "$(dirname "$SERVICE_DIR")")"
network_id=""; pg_id=""; app_id=""
cleanup() {
    if [[ -n "$app_id" ]] && docker inspect "$app_id" >/dev/null 2>&1; then docker stop "$app_id" >/dev/null; fi
    if [[ -n "$pg_id" ]] && docker inspect "$pg_id" >/dev/null 2>&1; then docker stop "$pg_id" >/dev/null; fi
    if [[ -n "$network_id" ]]; then docker network rm "$network_id" >/dev/null; fi
}
trap cleanup EXIT
image="vuelos-verificacion-$$:temporal"
docker build --progress=plain --file "$SERVICE_DIR/Dockerfile" --tag "$image" "$ROOT_DIR"
network_id=$(docker network create --label proyecto=vuelos-docker-prueba "vuelos-docker-prueba-$$")
pg_id=$(docker run --detach --rm --label proyecto=vuelos-docker-prueba --network "$network_id" --network-alias vuelos-db-prueba \
    --env POSTGRES_DB=db_vuelos --env POSTGRES_USER=vuelos_test --env POSTGRES_PASSWORD=vuelos_test_aislado \
    --tmpfs /var/lib/postgresql/data \
    --mount "type=bind,source=$SERVICE_DIR/sql/init.sql,target=/docker-entrypoint-initdb.d/init.sql,readonly" postgres:15-alpine)
ready=false
for ((n = 0; n < 60; n++)); do
    if docker exec "$pg_id" pg_isready -h 127.0.0.1 -U vuelos_test -d db_vuelos >/dev/null 2>&1; then ready=true; break; fi
    sleep 1
done
if [[ "$ready" != true ]]; then docker logs "$pg_id"; exit 1; fi
app_id=$(docker run --detach --rm --label proyecto=vuelos-docker-prueba --network "$network_id" --publish 127.0.0.1::8081 \
    --env POSTGRES_HOST=vuelos-db-prueba --env POSTGRES_PORT=5432 --env POSTGRES_USER=vuelos_test \
    --env POSTGRES_PASSWORD=vuelos_test_aislado --env RABBITMQ_HOST=broker-inaccesible.invalid \
    --env VUELOS_OUTBOX_INTERVALO_MS=500 --env 'JAVA_OPTS=-Xms128m -Xmx256m -Dverificacion.vuelos=true' "$image")
binding=$(docker port "$app_id" 8081/tcp)
python3 "$SERVICE_DIR/scripts/verificar-docker.py" "${binding##*:}" "$app_id" "$pg_id"
