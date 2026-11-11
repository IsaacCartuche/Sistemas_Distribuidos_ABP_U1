# Reservas

Java 17, Spring Boot 3.2.0, PostgreSQL y RabbitMQ. Se conserva la creación REST, idempotencia de solicitudes y el Outbox existente. La Saga nueva confirma únicamente tras recibir bloqueo de asiento y pago exitoso; cancela y publica compensaciones ante fallos. Ver [contrato pendiente de acuerdo](CONTRATO_SAGA_PROPUESTO.md) antes de activar consumidores.

## API

- `POST /api/reservas`: crea en `PENDIENTE` y guarda `reserva.creada` en la misma transacción. Responde 201; una repetición responde 200. Una clave usada con otros datos responde 409; entradas inválidas, 400.
- `GET /api/reservas/{id}`: devuelve la reserva, o 404.

```bash
curl -i http://localhost:8082/api/reservas \
  -H 'Content-Type: application/json' \
  -d '{"vueloId":1,"usuarioId":1,"asiento":"14C","total":150.00,"idempotencyKey":"demo-sebastian-001"}'
```

El importe es positivo, máximo 99999999.99, con máximo dos decimales. No se redondea silenciosamente. No se almacena información de tarjetas. La autorización HTTP y validación de tarifa con Vuelos requieren integración externa.

## Persistencia y concurrencia

`reservas` conserva `@Version`, claves únicas de idempotencia/correlación/token y añade las señales `asiento_bloqueado` y `pago_exitoso`. El consumidor bloquea una reserva con `PESSIMISTIC_WRITE`. Inbox, señales, estado y evento de salida se guardan en la misma transacción PostgreSQL. La PK de `saga_inbox.event_id` elimina entregas repetidas; las transiciones también son idempotentes ante eventos equivalentes con otro ID. Una reserva desconocida produce un error reintentable; si no aparece antes de agotar los intentos queda en DLQ para revisión.

Se publica `reserva.creada`, `reserva.confirmada` y `reserva.cancelada` mediante Outbox. El publisher reclama lotes con `FOR UPDATE SKIP LOCKED`, libera la transacción, publica JSON persistente con confirmaciones y mandatory, y marca `PUBLICADO` solo con ACK sin devolución. Los errores conservan el evento pendiente; los reclamos abandonados se recuperan. Una caída tras enviar y antes de marcar puede duplicar el evento; los consumidores deben deduplicar por el mismo `eventId`. Nunca se promete exactly once de transporte.

La cancelación es terminal. El fallo tardío de un asiento/pago confirmado inicia compensación; la liberación real pertenece a Inventario y el reembolso a Pagos. No hay TTL local inventado; se procesa `asiento.expirado` de Inventario.

## Esquema y migraciones

BD nueva: ejecutar `sql/init.sql`, que ahora incluye los campos y tablas de V1–V4. BD existente: aplicar solo las migraciones pendientes, en orden, después de revisar su estado. No hay Flyway ni aplicación automática de SQL sobre bases existentes. Hibernate valida el esquema al arrancar y no lo modifica.

Ejemplo para una base existente donde V1–V3 ya se aplicaron:

```bash
PGPASSWORD=postgres psql -h 127.0.0.1 -p 5442 -U postgres -d db_reservas \
  -v ON_ERROR_STOP=1 -f servicios/reservas/sql/migrations/V4__saga_consumidores.sql
```

Este comando modifica el esquema: revisarlo y ejecutarlo deliberadamente. Durante este trabajo no se aplicó ninguna migración a la base de desarrollo. Las reservas históricas sin tokens no se rellenan ni se confirman automáticamente.

## Variables

| Variable | Valor por defecto |
|---|---|
| `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB` | localhost, 5432, db_reservas |
| `POSTGRES_USER`, `POSTGRES_PASSWORD` | postgres, postgres |
| `RABBITMQ_HOST`, `RABBITMQ_PORT` | localhost, 5672 |
| `RABBITMQ_USER`, `RABBITMQ_PASS` | guest, guest |
| `SAGA_HABILITADA` | false; contrato de Inventario pendiente |
| `OUTBOX_PUBLICACION_HABILITADA` | true |
| `OUTBOX_RECUPERACION_HABILITADA` | true |
| `SERVER_PORT` | 8082 |

Propiedades adicionales: `outbox.publicacion.intervalo-ms` (10000), `outbox.publicacion.espera-inicial-ms` (10000), `outbox.recuperacion.intervalo-ms` (60000), `outbox.recuperacion.espera-inicial-ms` (60000). El reclamo se considera abandonado tras cinco minutos (valor interno existente). Configurar mediante argumentos `--propiedad=valor` o variables Spring. `JAVA_OPTS` no se interpreta por el Dockerfile; usar `JAVA_TOOL_OPTIONS` si se necesitan opciones JVM en el contenedor.

## Ejecución

Desde la raíz, con el esquema actualizado y credenciales locales apropiadas:

```bash
POSTGRES_HOST=127.0.0.1 POSTGRES_PORT=5442 RABBITMQ_HOST=127.0.0.1 \
  mvn -f servicios/reservas/pom.xml spring-boot:run
```

Para levantar ambos servicios y bases **nuevas e independientes** del compose del equipo:

```bash
mvn -f servicios/reservas/pom.xml -DskipTests package
mvn -f servicios/pagos/pom.xml -DskipTests package
docker compose -f servicios/reservas/compose.saga.yml up -d --build
```

Este compose usa HTTP 8082/8083, PostgreSQL 55442/55443, AMQP 5572 y administración 15572, solo en loopback. Usa sus propios volúmenes. Los init SQL solo se ejecutan en volúmenes nuevos. No eliminar volúmenes para aplicar migraciones. Los consumidores están desactivados; activarlos con `SAGA_HABILITADA=true` en el comando de compose para ensayar el contrato propuesto. Inventario no está incluido: sin sus eventos las reservas seguirán pendientes.

## Pruebas

Consultar [procedimiento reproducible y resultados](PRUEBAS.md). Ejecutar exclusivamente contra recursos aislados: algunas pruebas históricas manipulan registros y las nuevas de fallo de persistencia alteran temporalmente restricciones en la BD de prueba. Nunca ejecutar la suite sobre la BD de desarrollo.

Se verifican solicitudes repetidas y conflictivas, concurrencia, rollback reserva/Outbox, reclamos concurrentes, recuperación, publicaciones reales y fallidas, confirmación con señales en ambos órdenes, cancelación terminal y tokens inválidos. Los schedulers se desactivan durante pruebas de persistencia salvo pruebas específicas.

## Operación y limitaciones

Revisar `outbox_eventos` pendientes/intentos/fecha de reclamo, las DLQ y los logs. Tras corregir el motivo de un fallo, reenviar desde DLQ preservando cuerpo, `eventId` y routing key original de `x-death`. No reenviar a ciegas; un fallo persistente volverá a DLQ. No se implementó retención/purga de Inbox y Outbox ni alertas automáticas.

La ruta integral con Inventario y el compose raíz no están validados. Las pruebas E2E sustituyen explícitamente a Inventario mediante eventos sintéticos. El soporte de prioridad y DLQ se declara en colas propias, pendiente de migración a las colas canónicas. Las colas clásicas y su dead-lettering estándar no garantizan por sí solos durabilidad absoluta ante fallos internos del broker; una operación más exigente necesita acordar políticas/quorum y monitorización con infraestructura.

## Revisión de la guía del jefe: modo de colas canónicas

La guía asigna resultados `pago.*` a `pagos.media` para **Reservas** y expiraciones a `reservas.alta`. La constitución describe `pagos.media` como entrada de Pagos; no son asignaciones equivalentes. Para conservar el comportamiento existente, Reservas admite `SAGA_TOPOLOGIA=aislada` (predeterminado) o `guia` cuando `SAGA_HABILITADA=true`.

En `guia`, Reservas consume `pagos.media` (`pago.exitoso`, `pago.fallido`, `pago.reembolsado`) y `reservas.alta` (las cuatro señales `asiento.*` ya soportadas). Este modo declara **bindings**, pero no declara ni cambia los argumentos de esas dos colas. Deben existir previamente y tener DLX/política hacia `dlq.reservas` con clave `reservas.saga`. Su ausencia impide el arranque del listener. El modo aislado mantiene `reservas.saga.v1` con su topología original. No se arrancan ambos modos a la vez.

Pagos conserva `pagos.saga.v1` como entrada de creación/bloqueo/cancelación. No debe consumir `pagos.media` simultáneamente con Reservas: consumidores de servicios distintos sobre una cola competirían por cada mensaje en vez de recibir una copia por servicio. Acordar esta distribución con Isaac y Cristian antes de habilitar `guia` en el broker compartido. La selección es una opción de despliegue probada en aislamiento, **no un acuerdo externo ya aprobado**.

Los bindings de `notificaciones.entrada` e `inventario.entrada` pertenecen a sus responsables; estos servicios no los declaran en producción. Las nuevas pruebas provisionan dobles de esas colas exclusivamente en un broker aislado y comprueban enrutamiento, mensajes persistentes, correlación y compensación solicitada. El ACK de un mensaje mandatory garantiza al menos una ruta, no que todos los destinatarios esperados existan.

La matriz actualizada y los requisitos exactos para cada integrante están en [REVISION_GUIA_JEFE.md](../reservas/REVISION_GUIA_JEFE.md). El informe de implementación previo se conserva como antecedente.

### Aplicar las migraciones existentes de Reservas

`sql/migrar.sh` reutiliza V1–V4; no copia ni reinventa sus sentencias. Ejecuta una transacción local con bloqueo asesor y `ON_ERROR_STOP`. Requiere que la tabla base `public.reservas` ya exista. Las migraciones actuales son idempotentes y pueden reaplicarse. No hay historial/checksums de Flyway: revisar antes de añadir migraciones futuras no idempotentes.

```bash
# Cliente psql en el host; ajustar las variables a la base elegida.
PGHOST=127.0.0.1 PGPORT=5442 PGUSER=postgres PGPASSWORD=postgres PGDATABASE=db_reservas \
  sh servicios/reservas/sql/migrar.sh

# Cliente psql dentro de PostgreSQL del compose compartido, sin montar nuevas rutas.
sh servicios/reservas/sql/migrar.sh --emitir-sql | \
  docker compose exec -T postgres-reservas psql -X -v ON_ERROR_STOP=1 -U postgres -d db_reservas
```

Son comandos de despliegue que modifican el esquema elegido: no se ejecutaron contra la base compartida durante esta revisión. `init.sql` solo inicializa volúmenes nuevos y ya incluye V1–V4; reiniciar PostgreSQL no migra un volumen existente. No borrar volúmenes para actualizar el esquema.

El compose propio permite elegir puertos host mediante `SAGA_RESERVAS_DB_PORT`, `SAGA_PAGOS_DB_PORT`, `SAGA_RABBIT_PORT`, `SAGA_RABBIT_ADMIN_PORT`, `SAGA_RESERVAS_HTTP_PORT` y `SAGA_PAGOS_HTTP_PORT`, conservando todos los valores anteriores como predeterminados. Esto permite ejecutar proyectos de prueba independientes con `docker compose -p <nombre>`.
