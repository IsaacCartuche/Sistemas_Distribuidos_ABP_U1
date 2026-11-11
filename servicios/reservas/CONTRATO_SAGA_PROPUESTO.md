# Contrato de integración propuesto — pendiente de acuerdo

Fecha de auditoría: 2026-11-10. Este documento **no representa un acuerdo con otros integrantes**.

## Lo verificado

CONSTITUCION.md define el exchange topic durable `vuelos.events`, las claves de eventos y la responsabilidad de compensación de cada servicio. Exige idempotencia y que todos los pasos requeridos tengan éxito antes de confirmar.

En este checkout, Inventario y Vuelos contienen únicamente clases de arranque y configuración: no existen productores, consumidores ni DTO públicos de eventos. Sus README tampoco definen payloads. Por ello no se puede afirmar compatibilidad integral con Inventario.

El evento existente `reserva.creada` contiene `eventId`, `correlationId`, `timestamp`, `tipo` y un payload con `reservaId`, `vueloId`, `asiento`, `token`, `asientoId` (puede ser null). Se añade `total` numérico como campo aditivo; no se retira ni renombra ningún campo. Pagos no inventa un importe cuando recibe eventos históricos sin `total`.

`infra/rabbitmq/definitions.json` contiene llaves dobles que no forman JSON válido; declara prioridades, pero sus bindings son `reserva.alta`/`pago.media`, diferentes de las claves funcionales. No define el DLX `dlq.reservas`. No se modificó infraestructura compartida.

## Propuesta implementada y desactivada por defecto

Todos los eventos usan `eventId` UUID estable en cada reentrega, `correlationId` UUID de la Saga (el ya generado por Reservas), `timestamp` ISO 8601 y `payload` objeto. Se admite el timestamp local existente y timestamps con offset. La routing key AMQP determina el tipo; no se exige `tipo` en eventos entrantes. El consumidor ignora campos JSON adicionales.

| Routing key | Productor | Payload requerido por estos consumidores |
|---|---|---|
| `reserva.creada` | Reservas | `reservaId` entero positivo, `token` UUID, `total` numérico positivo DECIMAL(10,2); conserva los campos anteriores |
| `asiento.bloqueado` | Inventario (pendiente) | `reservaId`, `token` original de la reserva |
| `asiento.no_disponible`, `asiento.expirado`, `asiento.liberado` | Inventario (pendiente) | `reservaId`, `token` original de la reserva |
| `pago.exitoso`, `pago.fallido`, `pago.reembolsado` | Pagos | `reservaId`, `total`, `simulado: true` |
| `reserva.confirmada`, `reserva.cancelada` | Reservas | `reservaId`, `vueloId`, `asiento`, `token` |

Los identificadores y tokens deben coincidir, incluso si los mensajes llegan fuera de orden. No se utiliza `asientoId` para decidir la Saga porque actualmente la API de creación no conoce ese identificador.

Pagos conserva por separado `reserva.creada` y `asiento.bloqueado`; cobra solo cuando recibió ambos. Una cancelación previa deja una marca permanente que impide cobrar aunque las otras señales lleguen después. No consume `pago.*`: es su productor. Un pago rechazado es resultado de negocio y no debe reintentarse como nuevo cobro. Los errores técnicos de consumo sí se reintentan.

Reservas conserva ambas señales positivas, exige ambas para confirmar y cancela ante cualquiera de las señales negativas. Una pérdida tardía de asiento o reversión de pago puede llevar `CONFIRMADA` a `CANCELADA` como compensación conservadora. `CANCELADA` es terminal. Los éxitos tardíos nunca la reabren. Pagos consume `reserva.cancelada` y reembolsa si había cobrado, incluso si la cancelación llega antes de que Reservas reciba `pago.exitoso`. Inventario deberá liberar su propio bloqueo al consumir la cancelación; Reservas no publica `asiento.liberado` en su nombre.

No se fija TTL arbitrario: Inventario debe definir la duración del bloqueo y la semántica de `asiento.expirado`. Reservas consume esa expiración. El vencimiento local de pendientes sin mensajes requiere un acuerdo funcional adicional; no hay un scheduler local de expiración.

## Topología temporal propia

Para evitar `PRECONDITION_FAILED` al redeclarar colas compartidas con argumentos DLX distintos, se usan colas nuevas:

| Cola | Prioridad máxima | Bindings |
|---|---:|---|
| `reservas.saga.v1` | 10 | `asiento.bloqueado`, `asiento.no_disponible`, `asiento.expirado`, `asiento.liberado`, `pago.exitoso`, `pago.fallido`, `pago.reembolsado` |
| `pagos.saga.v1` | 5 | `reserva.creada`, `asiento.bloqueado`, `reserva.cancelada` |

Ambas son durables y usan DLX topic durable `dlq.reservas`, routing keys `reservas.saga`/`pagos.saga` y colas durables `reservas.saga.dlq`/`pagos.saga.dlq`. Hay cuatro intentos totales con espera exponencial de 200, 400 y 800 ms. El mensaje se confirma solo tras terminar la transacción. Tras agotar intentos se rechaza sin requeue y el broker lo envía a DLQ. No hay bucle de reenvío automático desde DLQ. Las colas soportan prioridades, pero esta implementación no asigna prioridades de negocio a los mensajes salientes.

`SAGA_HABILITADA=false` impide declarar esa topología y arrancar los consumidores; la creación REST y el Outbox siguen disponibles. Habilitar con `SAGA_HABILITADA=true` únicamente en pruebas aisladas o tras acordar este contrato. El modo aislado no consume las colas canónicas. El modo guía opcional descrito al final sí consume `reservas.alta` y `pagos.media`, después de su aprovisionamiento coordinado con Isaac; no se activan ambos modos simultáneamente.

## Acuerdos necesarios

- **Cristian:** confirmar payloads, token, instante de cobro, TTL y tratamiento de bloqueos cuando llega `reserva.confirmada`. El asiento confirmado no debe expirar como si siguiera siendo provisional. Confirmar también si la política conservadora de cancelación tardía es la deseada.
- **Cristian:** implementar consumo de `reserva.creada`/`reserva.cancelada` y publicación durable de las señales de Inventario. Resolver cómo obtiene `asientoId` desde vuelo y etiqueta.
- **Isaac:** corregir definitions, adoptar o migrar las colas canónicas con prioridades y DLX, y revisar hosts internos/build contexts del compose raíz. Una cola existente no puede cambiar sus argumentos sin una migración coordinada; no borrarla para forzar cambios.
- **Equipo:** confirmar el campo `total`, moneda y fuente confiable de tarifa. Actualmente se conserva el importe proporcionado al crear la reserva; no existe aún verificación con el catálogo de Vuelos.
- **Isabel/Isaac:** integrar rutas/autorización y consumidores de confirmaciones/notificaciones. Sin un binding de destino, el Outbox conserva el evento pendiente por devolución mandatory.

No se contactó a otros integrantes ni se modificaron sus archivos.

## Revisión de la guía del jefe: modo de colas canónicas

La guía asigna resultados `pago.*` a `pagos.media` para **Reservas** y expiraciones a `reservas.alta`. La constitución describe `pagos.media` como entrada de Pagos; no son asignaciones equivalentes. Para conservar el comportamiento existente, Reservas admite `SAGA_TOPOLOGIA=aislada` (predeterminado) o `guia` cuando `SAGA_HABILITADA=true`.

En `guia`, Reservas consume `pagos.media` (`pago.exitoso`, `pago.fallido`, `pago.reembolsado`) y `reservas.alta` (las cuatro señales `asiento.*` ya soportadas). Este modo declara **bindings**, pero no declara ni cambia los argumentos de esas dos colas. Deben existir previamente y tener DLX/política hacia `dlq.reservas` con clave `reservas.saga`. Su ausencia impide el arranque del listener. El modo aislado mantiene `reservas.saga.v1` con su topología original. No se arrancan ambos modos a la vez.

Pagos conserva `pagos.saga.v1` como entrada de creación/bloqueo/cancelación. No debe consumir `pagos.media` simultáneamente con Reservas: consumidores de servicios distintos sobre una cola competirían por cada mensaje en vez de recibir una copia por servicio. Acordar esta distribución con Isaac y Cristian antes de habilitar `guia` en el broker compartido. La selección es una opción de despliegue probada en aislamiento, **no un acuerdo externo ya aprobado**.

Los bindings de `notificaciones.entrada` e `inventario.entrada` pertenecen a sus responsables; estos servicios no los declaran en producción. Las nuevas pruebas provisionan dobles de esas colas exclusivamente en un broker aislado y comprueban enrutamiento, mensajes persistentes, correlación y compensación solicitada. El ACK de un mensaje mandatory garantiza al menos una ruta, no que todos los destinatarios esperados existan.

La matriz actualizada y los requisitos exactos para cada integrante están en [REVISION_GUIA_JEFE.md](../reservas/REVISION_GUIA_JEFE.md). El informe de implementación previo se conserva como antecedente.
