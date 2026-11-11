# Pagos — simulación académica

Java 17 y Spring Boot 3.2.0. Procesa cobros y reembolsos **simulados**, sin transacciones financieras reales ni datos de tarjetas. La integración de Inventario todavía no está implementada en este checkout; los consumidores nuevos están desactivados por defecto. Leer [contrato propuesto](CONTRATO_SAGA_PROPUESTO.md) antes de activarlos.

## Dominio y persistencia

Se consume `reserva.creada`, `asiento.bloqueado` y `reserva.cancelada`. Se publica `pago.exitoso`, `pago.fallido` y `pago.reembolsado` mediante Outbox transaccional. Pagos es productor de `pago.*`, no consumidor de sus propios resultados.

`pago_saga` tiene una fila por correlación y un `reserva_id` único. Conserva señales independientes de creación y bloqueo: se cobra solo cuando ambas existen. La cancelación puede llegar primero y deja una marca persistente que impide cobrar después. Token, ID e importe inconsistentes se rechazan. La PK de `saga_inbox` deduplica el evento, y el estado del agregado evita cobrar o reembolsar otra vez aunque un productor emita IDs diferentes para el mismo hecho.

Estados: `PENDIENTE`, `EXITOSO`, `FALLIDO`, `CANCELADO` (sin cobro), `REEMBOLSADO`. Una cancelación tras `EXITOSO` genera un único reembolso. Un rechazo de negocio es definitivo para esta reserva; no existe un endpoint de reintento financiero que pudiera generar otro cargo. Una nueva operación de pago tras un rechazo exige un contrato de intentos distinto.

`pago_intentos` registra operación `COBRO`/`REEMBOLSO`, resultado y fecha, con restricción única por correlación y operación. Los reintentos técnicos fallidos de transacciones se observan en logs/reintentos RabbitMQ; no se confunden con intentos financieros confirmados.

El esquema usa `INSERT ... ON CONFLICT` y `SELECT ... FOR UPDATE` mediante JdbcTemplate para serializar también la creación concurrente de la primera fila. El agregado tiene entidad JPA `Pago` y repositorio para lectura; las mutaciones se realizan exclusivamente en el servicio transaccional JDBC. JPA Outbox participa en esa misma transacción y conexión gestionada por Spring. Una prueba de error real de BD verifica la reversión conjunta de pago, Inbox, intento y Outbox.

La tabla `pagos` inicial del repositorio se conserva sin borrar ni transformar datos; el nuevo agregado se llama `pago_saga`. Si hubiese pagos históricos reales en esa tabla, deben reconciliarse antes de habilitar esta implementación; no se migran automáticamente.

## Simulador

`PasarelaSimulada` está separado del procesamiento y es determinista: acepta importes positivos hasta el límite configurado (10000.00 por defecto); por encima publica `pago.fallido`. Los reembolsos simulados siempre tienen éxito. No realiza I/O: es seguro ejecutarlo dentro de la transacción local.

No sustituir directamente este componente por un cliente HTTP bancario. Una pasarela real necesita persistir operaciones, liberar la transacción antes de la llamada y reconciliar resultados usando claves de idempotencia externas por cobro y por reembolso. La integración de un proveedor no está autorizada ni implementada.

## RabbitMQ y Outbox

Exchange topic durable `vuelos.events`. Cola propia `pagos.saga.v1`, prioridad máxima 5. Cuatro intentos de consumo con backoff; rechazo posterior hacia DLX `dlq.reservas` y cola `pagos.saga.dlq`. Se usan nombres propios para no cambiar argumentos de `pagos.media` existente; su adopción definitiva depende de Isaac. La topología se declara solo cuando `SAGA_HABILITADA=true`.

El Outbox reutiliza en este servicio el protocolo de Reservas: lotes `SKIP LOCKED`, reclamo con propietario, envío fuera de la transacción de BD, confirmaciones correlacionadas, mensajes persistentes y mandatory. ACK sin devolución permite marcar `PUBLICADO`; fallo o falta de ruta conserva el pendiente. Un reclamo abandonado durante cinco minutos se recupera. Puede haber duplicados después de una caída; el `eventId` se conserva. No se garantiza exactly once de transporte ni durabilidad absoluta del dead-lettering clásico ante fallos internos del broker.

## API

No se añaden endpoints de cobro: la entrada autorizada de la Saga es RabbitMQ. El servicio escucha HTTP en 8083 como aplicación Spring, pero no tiene endpoints de negocio REST. La consulta operativa se realiza mediante tablas y eventos en el entorno de desarrollo. Esto evita introducir una segunda entrada de cobro sin requisitos de autenticación o contratos de API acordados.

## Esquema

BD nueva: `sql/init.sql` incluye la tabla inicial y el esquema nuevo completo. BD existente: aplicar deliberadamente `sql/migrations/V1__saga_pagos.sql` después de revisar el esquema. No se ejecuta Flyway ni se altera automáticamente una base al iniciar. Hibernate usa `ddl-auto=validate`.

```bash
PGPASSWORD=postgres psql -h 127.0.0.1 -p 5433 -U postgres -d db_pagos \
  -v ON_ERROR_STOP=1 -f servicios/pagos/sql/migrations/V1__saga_pagos.sql
```

No se ejecutó este comando contra ninguna BD de desarrollo durante la implementación.

## Configuración

| Variable | Por defecto |
|---|---|
| `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB` | localhost, 5432, db_pagos |
| `POSTGRES_USER`, `POSTGRES_PASSWORD` | postgres, postgres |
| `RABBITMQ_HOST`, `RABBITMQ_PORT` | localhost, 5672 |
| `RABBITMQ_USER`, `RABBITMQ_PASS` | guest, guest |
| `SAGA_HABILITADA` | false |
| `OUTBOX_PUBLICACION_HABILITADA` | true |
| `OUTBOX_RECUPERACION_HABILITADA` | true |
| `PAGOS_SIMULADOR_LIMITE` | 10000.00 |
| `SERVER_PORT` | 8083 |

Propiedades de scheduler: `outbox.publicacion.intervalo-ms` y `espera-inicial-ms` (10000); `outbox.recuperacion.intervalo-ms` y `espera-inicial-ms` (60000). El plazo de reclamos abandonados es de cinco minutos. El Dockerfile no evalúa `JAVA_OPTS`; usar `JAVA_TOOL_OPTIONS` para opciones JVM del contenedor.

## Ejecución

Desde la raíz, con esquema preparado:

```bash
POSTGRES_HOST=127.0.0.1 POSTGRES_PORT=5433 RABBITMQ_HOST=127.0.0.1 \
  mvn -f servicios/pagos/pom.xml spring-boot:run
```

Para infraestructura exclusiva y ambos servicios:

```bash
mvn -f servicios/reservas/pom.xml -DskipTests package
mvn -f servicios/pagos/pom.xml -DskipTests package
docker compose -f servicios/reservas/compose.saga.yml up -d --build
```

Ver detalles de puertos y activación de la propuesta en [README de Reservas](../reservas/README.md). Sin señal de Inventario no se cobra, incluso si se recibe `reserva.creada`.

## Pruebas y operación

Ver [pruebas reproducibles](PRUEBAS.md). Incluyen eventos duplicados y fuera de orden, cancelación anticipada, fallos de negocio, reembolsos únicos, concurrencia real, rollback ante fallo SQL, publicación persistente confirmada, mandatory sin ruta, DLQ tras reintentos y broker inaccesible.

La prueba E2E usa Reservas real, Pagos, PostgreSQL y RabbitMQ, y emite señales sintéticas de Inventario. No valida compatibilidad con una implementación real de Inventario.

Monitorizar pendientes e intentos en `outbox_eventos`, filas en `pago_intentos`, logs y `pagos.saga.dlq`. El reenvío de DLQ es manual tras corregir la causa y debe preservar `eventId` y routing key original. No hay purga/retención automática ni monitorización productiva implementada. Los acuerdos externos pendientes están detallados en el contrato propuesto.
