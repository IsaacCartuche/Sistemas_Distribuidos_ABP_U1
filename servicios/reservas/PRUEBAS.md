# Pruebas aisladas de Reservas y Pagos

Ejecutar desde la raíz del repositorio. **No apuntar las suites a bases o colas de desarrollo**. Las pruebas históricas asumen una base de pruebas sin reservas ajenas; las nuevas incluyen un cambio temporal de restricción para provocar un rollback real. No se usa `Thread.sleep` para coordinar hilos.

## Recursos utilizados en esta sesión

- `sebastian-saga-tests-20261110`: PostgreSQL 15, loopback 55432, dos BD nuevas `db_reservas` y `db_pagos`, contraseña exclusiva `saga_test`.
- `sebastian-saga-rabbit-tests-20261110`: RabbitMQ 3, loopback AMQP 55672.
- No se conectaron las pruebas a PostgreSQL 5442 ni a RabbitMQ 5672.
- La instancia Java E2E de Reservas en 18082 se detuvo al terminar su prueba.
- Los contenedores de pruebas se conservan para inspección; no se borraron volúmenes ni bases existentes. La prueba E2E deja sus propias filas de auditoría; para ejecutar nuevamente la suite histórica de Reservas es preferible crear infraestructura nueva o limpiar exclusivamente esas filas identificadas por `idempotency_key` con prefijo `e2e-`.

## Preparación reproducible

Usar nombres libres y puertos libres; los comandos no deben ejecutarse de nuevo si ya existen esos contenedores. No borrar recursos existentes para liberar nombres.

```bash
docker run -d --name sebastian-saga-tests-20261110 \
  -p 127.0.0.1:55432:5432 \
  -e POSTGRES_PASSWORD=saga_test -e POSTGRES_DB=db_reservas \
  -v "$PWD/servicios/reservas/sql/init.sql:/docker-entrypoint-initdb.d/01-reservas.sql:ro" \
  postgres:15-alpine

docker run -d --name sebastian-saga-rabbit-tests-20261110 \
  -p 127.0.0.1:55672:5672 rabbitmq:3-management-alpine
```

Esperar a que las comprobaciones siguientes indiquen disponibilidad antes de continuar:

```bash
docker exec sebastian-saga-tests-20261110 pg_isready -U postgres -d db_reservas
docker exec sebastian-saga-rabbit-tests-20261110 rabbitmq-diagnostics -q check_port_connectivity
```

Preparar Pagos únicamente dentro del contenedor nuevo:

```bash
docker exec sebastian-saga-tests-20261110 psql -U postgres -c 'CREATE DATABASE db_pagos'
docker exec -i sebastian-saga-tests-20261110 psql -U postgres -d db_pagos \
  -v ON_ERROR_STOP=1 < servicios/pagos/sql/init.sql
```

## Suites

```bash
POSTGRES_HOST=127.0.0.1 POSTGRES_PORT=55432 POSTGRES_PASSWORD=saga_test \
  RABBITMQ_PORT=55672 OUTBOX_PUBLICACION_HABILITADA=false OUTBOX_RECUPERACION_HABILITADA=false \
  mvn -f servicios/reservas/pom.xml clean test

POSTGRES_HOST=127.0.0.1 POSTGRES_PORT=55432 POSTGRES_PASSWORD=saga_test \
  RABBITMQ_PORT=55672 OUTBOX_PUBLICACION_HABILITADA=false OUTBOX_RECUPERACION_HABILITADA=false \
  mvn -f servicios/pagos/pom.xml clean test
```

En Pagos la prueba E2E se omite deliberadamente en la suite normal. Requiere Reservas en marcha y habilitación explícita.

## E2E con Inventario simulado por eventos

Ejecutar después de las suites. Empaquetar Reservas y arrancar en una terminal:

```bash
mvn -f servicios/reservas/pom.xml -DskipTests package
POSTGRES_HOST=127.0.0.1 POSTGRES_PORT=55432 POSTGRES_PASSWORD=saga_test \
  RABBITMQ_PORT=55672 SAGA_HABILITADA=true \
  java -jar servicios/reservas/target/servicio-reservas-1.0.0.jar \
  --server.port=18082 --outbox.publicacion.intervalo-ms=100 \
  --outbox.publicacion.espera-inicial-ms=100 --outbox.recuperacion.habilitada=false
```

Cuando Reservas indique que arrancó, ejecutar desde otra terminal:

```bash
POSTGRES_HOST=127.0.0.1 POSTGRES_PORT=55432 POSTGRES_PASSWORD=saga_test \
  RABBITMQ_PORT=55672 SAGA_E2E=true \
  mvn -f servicios/pagos/pom.xml -Dtest=SagaEndToEndTest test
```

La prueba inicia Pagos en contexto Spring, crea una reserva por HTTP, publica un bloqueo sintético, espera `CONFIRMADA` y `EXITOSO`, publica expiración sintética y espera `CANCELADA` y `REEMBOLSADO`, dos operaciones financieras simuladas y ambos eventos de pago `PUBLICADO`. Usa esperas por condición con límite, no pausas arbitrarias. Al terminar detener solo la instancia Java de prueba (Ctrl+C en su terminal).

Es normal que una confirmación quede pendiente en Outbox si ningún consumidor declara su binding; mandatory exige una ruta. En una integración completa Notificaciones debe declararla.

## Resultados

Los resultados finales y el alcance exacto están registrados en [INFORME_IMPLEMENTACION.md](INFORME_IMPLEMENTACION.md). Los reportes Maven locales se generan en `target/surefire-reports`; no se versionan. Los primeros intentos desde sandbox fallaron por conectividad restringida y attach de Mockito; las verificaciones efectivas se ejecutaron con acceso a los recursos aislados. La repetición tras E2E detectó contaminación de pruebas por colas antiguas y por una restricción demasiado amplia; se corrigió con colas temporales únicas y una restricción limitada a la correlación de prueba.

## E2E exclusivamente entre contenedores

También se verificó el compose propio con sus BD nuevas y consumidores reales en Docker. Para reproducir, empaquetar ambos servicios y ejecutar `SAGA_HABILITADA=true docker compose -f servicios/reservas/compose.saga.yml up -d --build --wait --wait-timeout 120`. Después:

```bash
POSTGRES_HOST=127.0.0.1 POSTGRES_PORT=55443 POSTGRES_PASSWORD=postgres \
  RABBITMQ_PORT=5572 RABBITMQ_USER=saga RABBITMQ_PASS=saga_dev \
  RESERVAS_TEST_URL=http://127.0.0.1:8082 SAGA_E2E=true SAGA_E2E_CONSUMIDOR=false \
  mvn -f servicios/pagos/pom.xml -Dtest=SagaEndToEndTest test
```

`SAGA_E2E_CONSUMIDOR=false` desactiva consumidores y publicador del contexto de pruebas: el cobro, las transiciones, publicaciones y reembolso deben efectuarlos los contenedores. El contexto de prueba solo usa HTTP, publica las señales sintéticas de Inventario y consulta resultados. Este compose es también infraestructura exclusiva de ensayo; no es el compose compartido del equipo. Las credenciales incluidas son locales de desarrollo.

## Revisión posterior de la guía

Los resultados y recursos de la sesión anterior se conservan arriba como antecedente. La revisión actual añade pruebas de las colas canónicas, destinos de publicación y un segundo escenario E2E de pago rechazado. Las dos E2E se omiten en la suite normal y se ejecutan explícitamente contra Docker. Consultar la matriz, los comandos actuales y la evidencia de migración en [REVISION_GUIA_JEFE.md](../reservas/REVISION_GUIA_JEFE.md). No ejecutar las nuevas fixtures de colas contra el broker compartido.
