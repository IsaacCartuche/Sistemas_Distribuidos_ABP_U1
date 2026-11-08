# Inventario de Asientos

Núcleo REST de disponibilidad, retenciones temporales y confirmación de asientos.
Java **17**, Spring Boot **3.2.0**, JPA y PostgreSQL independiente **`db_inventario`**.
Puerto HTTP **8085**. Sin interfaz gráfica. Publicación fiable de `asiento.expirado` mediante outbox
y consumidor provisional de Reservas con Inbox, **deshabilitado por defecto** hasta acordar el contrato.

## Responsabilidades y estructura

Inventario es la fuente de verdad de disponibilidad. No consulta `db_vuelos` ni utiliza
`capacidadDisponible` de Vuelos para autorizar reservas. El contrato de Vuelos mantiene
`GET /api/v1/vuelos/{id}` y `capacidadTotal`, pero esta fase no hace llamadas a ese servicio:
la existencia del vuelo y la lista autorizada de asientos deben ser verificadas por el integrador.

```text
com.empresa.inventario
├── api           # REST, DTOs, ProblemDetail y validaciones
├── application   # Transacciones, retenciones y expiración programada
├── config        # Clock UTC, configuración validada y scheduling
├── domain        # Asiento, Retencion y estados
├── inbox         # Contrato provisional, Inbox, asociación de reserva y listener optativo
├── persistence   # Repositorios JPA y alta idempotente de asientos
└── outbox        # Registro transaccional, contexto pendiente, workers y Spring AMQP
```

## Persistencia y ampliación del esquema

`sql/init.sql` conserva los campos originales de `asientos`: ID SERIAL, vuelo, número,
estado, TIMESTAMP de vencimiento y versión INTEGER. Añade:

- `asientos.bloqueo_token UUID`: propietario de la retención actual (se conserva al confirmar).
- `retenciones`: token UUID como PK global, asiento asociado, vencimiento, estado y `@Version`.
- Índice parcial para expiración, estados válidos en tablas nuevas y versiones no nulas.

La tabla de retenciones es necesaria para recordar tokens terminales después de liberar y
rebloquear el asiento. Guardar únicamente el último token permitiría que una solicitud antigua
volviera a adquirir un asiento disponible. Las retenciones **no se eliminan** en esta fase.

Ambas entidades usan `@Version`. Los cambios de asiento y retención se confirman o revierten
en la **misma transacción**; el servicio fuerza `flush` antes de responder y la transacción
debe confirmar antes de que el controlador reciba el resultado. Sobre asientos/retenciones no se usan
locks pesimistas ni un mutex en memoria. La outbox usa locks de fila separados para coordinar workers.
La clave única `(vuelo_id, numero_asiento)` evita duplicados incluso
entre instancias y `INSERT ... ON CONFLICT DO NOTHING` permite inicializaciones simultáneas.

### Inicialización y migración

- Base nueva: inicializar expresamente con `sql/init.sql`.
- Base con el esquema original: revisar y aplicar manualmente `sql/migracion-retenciones.sql`,
  con el servicio detenido, sobre **su propia** `db_inventario` y con `psql -v ON_ERROR_STOP=1`.
  Es aditiva: no borra ni reescribe filas; aborta si encuentra bloqueos heredados sin identidad.
  Los `estado`/`version` nulos también impedirán aplicar NOT NULL y requieren reconciliación manual.
  Los reservados históricos permanecen reservados y sin retención nueva inventada.
- Modificar `init.sql` no actualiza volúmenes ya inicializados. No se borran volúmenes para migrar.
- Para añadir esta fase a una instalación con retenciones, aplicar **manualmente**
  `sql/migracion-outbox.sql` antes de desplegar el código nuevo, con `psql -v ON_ERROR_STOP=1`.
  Si parte del esquema original, aplicar primero `migracion-retenciones.sql`. La nueva migración
  solo crea la outbox y su índice: no cambia retenciones ni rellena eventos de expiraciones históricas.
  Su repetición se verifica en las bases temporales de prueba.
- Para esta fase de validación, aplicar después **`sql/migracion-outbox-validacion.sql`**, antes de
  desplegar el publicador actualizado. Amplía atómicamente el CHECK de estados para admitir
  `ERROR_DATOS`; no modifica filas, PK, FK, índices ni los demás CHECKs. No ejecuta reparaciones
  de mensajes. En una instalación nueva `init.sql` ya incluye el estado adicional.
- Para la fase Inbox aplicar después **`sql/migracion-inbox-reservas.sql`**, antes de desplegar el
  código actualizado, incluso si el listener permanece apagado: la expiración consulta la asociación
  local para obtener su contexto. Solo añade dos tablas, sin modificar filas, entidades de retención
  ni tablas anteriores. `init.sql` incluye todo para nuevas instalaciones. No se ejecutó en bases existentes.
- Hibernate usa **`ddl-auto=validate`**: no crea ni modifica tablas. La aplicación no ejecuta
  automáticamente ninguno de estos scripts. La migración no se ha aplicado a bases existentes.

Los timestamps se almacenan como `TIMESTAMP` sin zona, interpretados exclusivamente en **UTC**.
Java usa reloj UTC y precisión de microsegundos. Las instancias deben tener relojes sincronizados.

## Máquina de estados, TTL e idempotencia

Asiento: `DISPONIBLE → BLOQUEADO → RESERVADO`; cancelar o expirar `BLOQUEADO → DISPONIBLE`.
Retención: `ACTIVA → CONFIRMADA | LIBERADA | EXPIRADA`. Los estados terminales no se reabren.

El cliente genera un UUID aleatorio **antes** del primer intento y lo conserva como token de
idempotencia/propiedad de esa retención. Se utiliza el mismo token en bloqueo, confirmación,
liberación y reintentos. Un token solo puede asociarse a un asiento durante toda su vida.
No debe compartirse con otro solicitante ni aparecer en listados públicos.

| Operación/reintento | Resultado |
|--------------------|-----------|
| Bloquear disponible con token nuevo | 200, retención ACTIVA y TTL del servidor |
| Mismo bloqueo activo | 200, mismo vencimiento, sin renovar TTL ni versión |
| Bloquear con token ya confirmado | 200, devuelve CONFIRMADA; no vuelve a bloquear |
| Otro token sobre bloqueo vigente o reservado | 409 |
| Reutilizar token liberado/expirado o activo ya vencido | 409 |
| Confirmar retención propia vigente | 200, asiento RESERVADO |
| Repetir confirmación ya confirmada | 200, sin nueva acción |
| Confirmar cuando `expiraEn <= ahora` | 409; nunca confirma |
| Liberar retención propia ACTIVA | 200, LIBERADA o EXPIRADA según el reloj |
| Repetir liberación terminal | 200, sin tocar el bloqueo actual del asiento |
| Liberar una CONFIRMADA | 409; cancelar reservas confirmadas es otro contrato futuro |
| Token de otro asiento | 409 |
| Asiento o token desconocido | 404 |
| Conflicto optimista/colisión de token simultánea | 409, reconsultar/reintentar con el mismo token |

Un 409 no se reintenta indiscriminadamente: un conflicto transitorio puede resolverse consultando
y repitiendo el mismo token; uno terminal no. Si se pierde la respuesta de una operación confirmada,
su reintento devuelve el resultado conservado, sin crear otra reserva ni renovar el bloqueo.

Los vencidos cuentan como disponibles aunque el scheduler aún no los haya materializado como
DISPONIBLE. Un bloqueo nuevo puede recuperar un vencido transaccionalmente y marcar el token
antiguo EXPIRADA. El proceso programado toma hasta 100 candidatos por ciclo; vuelve a leer cada
uno en una transacción independiente y comprueba estado y vencimiento. Si cambió entre lectura
y escritura, `@Version` provoca rollback. Así no libera una reserva confirmada ni un bloqueo nuevo.
Un candidato sin modificar (nuevo TTL/RESERVADO) simplemente se omite.

Las consultas son instantáneas informativas: la autorización definitiva es la operación transaccional
de bloqueo. `estado` muestra el estado persistido; `disponible` aplica además el TTL al momento
de consultar. Por ello un asiento puede mostrar `BLOQUEADO` y `disponible: true` antes de la limpieza.

## Endpoints

Bases equivalentes:

- Versionada: `http://localhost:8085/api/v1/inventario`.
- Compatible con la ruta del Gateway: `http://localhost:8085/api/inventario`.

Todas las subrutas de la tabla siguiente funcionan con ambas bases mediante el mismo controlador,
sin redirecciones. Por ejemplo, `GET /api/inventario/vuelos/1/asientos?disponibles=true` equivale a
`GET /api/v1/inventario/vuelos/1/asientos?disponibles=true`. Las rutas versionadas se conservan;
ambas variantes aplican las mismas reglas de tokens, idempotencia, concurrencia y TTL.

| Método | Ruta | Uso |
|--------|------|-----|
| PUT | `/vuelos/{vueloId}/asientos` | Alta aditiva/idempotente; devuelve lista del vuelo |
| GET | `/vuelos/{vueloId}/asientos` | Todos los asientos, orden lexicográfico por número |
| GET | `/vuelos/{vueloId}/asientos?disponibles=true` | Disponibles reales según estado y TTL |
| GET | `/asientos/{id}` | Detalle, estado, disponibilidad y versión |
| PUT | `/asientos/{id}/bloqueos/{token}` | Adquirir/reintentar retención |
| POST | `/asientos/{id}/bloqueos/{token}/confirmacion` | Confirmar retención |
| DELETE | `/asientos/{id}/bloqueos/{token}` | Liberar/reintentar cancelación de retención |

Todas las operaciones correctas responden **200** con JSON. Los endpoints de retención devuelven
`token`, `asientoId`, `estado` de retención y `expiraEn` (UTC). El vencimiento original se conserva
como historial incluso después de confirmar o liberar. GET de vuelo desconocido/sin asientos devuelve `[]`.

Validaciones: IDs enteros positivos; UUID válido; inicialización con entre 1 y 500 números por solicitud,
de 1 a 10 caracteres alfanuméricos o guion, normalizados a mayúsculas y deduplicados. El alta añade
faltantes: no elimina, cambia capacidad ni reinicia los estados de los ya existentes. No se aceptan
estados, versiones ni TTL arbitrarios como entrada. Errores 400/404/409 usan `application/problem+json`.

### Ejemplo

```bash
BASE=http://localhost:8085/api/v1/inventario
TOKEN=aa1fe890-244f-4d29-94cf-441fa0a33701

curl -i -X PUT "$BASE/vuelos/1/asientos" \
  -H 'Content-Type: application/json' -d '{"numeros":["1A","1B","2A","2B"]}'
curl "$BASE/vuelos/1/asientos?disponibles=true"
# Usar el ID de asiento devuelto en la inicialización (1 es solo un ejemplo).
curl -i -X PUT "$BASE/asientos/1/bloqueos/$TOKEN"
curl -i -X POST "$BASE/asientos/1/bloqueos/$TOKEN/confirmacion"
# Alternativa a confirmar: DELETE con el mismo token, antes de confirmar.
# curl -i -X DELETE "$BASE/asientos/1/bloqueos/$TOKEN"
```

## Configuración y ejecución

| Variable | Defecto | Significado |
|----------|---------|-------------|
| `POSTGRES_HOST` | localhost | Servidor PostgreSQL propio |
| `POSTGRES_PORT` | 5432 | Puerto PostgreSQL accesible desde la aplicación |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | postgres / postgres | Credenciales de `db_inventario` |
| `INVENTARIO_TTL_SEGUNDOS` | 300 | TTL por retención, entre 1 y 86400 segundos |
| `INVENTARIO_INTERVALO_EXPIRACION_MS` | 10000 | Demora entre ciclos, mínimo 100 ms |
| `INVENTARIO_EXPIRACION_HABILITADA` | true | Activa limpieza programada |

Las variables RabbitMQ existentes configuran publicador y listener; este último es optativo y apagado por defecto.
El broker no interviene en las transacciones REST: su indisponibilidad deja publicaciones pendientes.
Maven no carga `.env` automáticamente. Desde esta carpeta:

```bash
mvn test
mvn package
# Tras inicializar o migrar exclusivamente la base propia:
POSTGRES_HOST=localhost POSTGRES_PORT=5432 mvn spring-boot:run
# O ejecutar el JAR:
java -Xms256m -Xmx512m -jar target/servicio-inventario-asientos-1.0.0.jar
```

El Dockerfile conserva Java 17, copia el JAR ya construido y declara 8085. Su contexto de construcción
local es esta carpeta (`docker build -t servicio-inventario-asientos .`). No se ha probado el despliegue
Compose ni modificado su contexto compartido. Para opciones JVM en contenedor se puede utilizar
`JAVA_TOOL_OPTIONS`; el ENTRYPOINT actual no expande `JAVA_OPTS`.

## Pruebas

```bash
# Sin conexión a bases/broker; integración optativa omitida:
mvn test
# Suite completa con PostgreSQL aislado (requiere Docker operativo y JDK 17):
bash scripts/test-postgres.sh
# PostgreSQL + RabbitMQ nuevos y aislados, con pruebas de confirms/returns/recuperación:
bash scripts/test-outbox.sh
```

El script crea un contenedor PostgreSQL nuevo, usa **db_inventario**, credenciales de prueba,
puerto host libre en `127.0.0.1` y datos temporales en `tmpfs`. Monta `init.sql`, la migración
y la fixture original en lectura, espera la conexión TCP y ejecuta `mvn verify` (pruebas y empaquetado). Al terminar detiene solo
el contenedor creado, eliminado automáticamente. No usa Compose ni volúmenes persistentes.

Antes de Maven crea otras dos bases temporales en el mismo contenedor para verificar la migración:
una migra datos originales DISPONIBLE/RESERVADO conservando filas y versión; la otra contiene
un bloqueo sin dueño y debe rechazar la migración, conservando los datos y el esquema original.

La integración se activa con `INVENTARIO_POSTGRES_INTEGRATION=true` y las variables
`INVENTARIO_TEST_DB_URL`, `INVENTARIO_TEST_DB_USER`, `INVENTARIO_TEST_DB_PASSWORD` que exporta
el script. No activarla contra bases existentes: inserta y modifica datos de prueba.

Cobertura:

- Unitarias: reglas de propiedad, TTL en límite exacto, transiciones, duplicados y tokens terminales.
- MockMvc: DTOs, IDs/UUID, rutas y respuestas 400/404/409.
  Ambas bases se comprueban para inicializar, listar, filtrar disponibles, consultar, bloquear,
  confirmar y liberar; también se verifican errores representativos 400/404/409 en las dos variantes.
- PostgreSQL/HTTP real: inicialización idempotente/concurrente, confirmación y liberación,
  expiración con reloj controlable (sin esperas por TTL), disponibilidad sin scheduler,
  reintentos, tokens cruzados y reserva confirmada protegida.
- Carreras HTTP reales de **2, 4 y 8 solicitantes** por un asiento: exactamente un 200 y el resto 409.
- Dos transacciones detenidas mediante barrera después de leer la misma versión: solo una confirma
  y la perdedora revierte también la retención insertada.
- Lecturas antiguas deliberadas: expirador frente a rebloqueo y confirmación frente a expiración;
  se comprueba rollback optimista e integridad entre asiento y retención.

### Resultado histórico del núcleo inicial (2026-11-07, antes de aliases/outbox)

| Grupo | Pruebas | Fallos | Errores | Omitidas |
|-------|---------|--------|---------|----------|
| Servicio/dominio | 20 | 0 | 0 | 0 |
| Controlador MockMvc | 17 | 0 | 0 | 0 |
| Integración PostgreSQL/HTTP/concurrencia | 17 | 0 | 0 | 0 |
| **Total final** | **54** | **0** | **0** | **0** |

Se ejecutó primero `mvn test` con 37 pruebas de servicio/controlador. Después, el script completo
pasó `mvn verify` con las 54 pruebas y generó el JAR ejecutable. Entorno: OpenJDK **17.0.20.1**,
Maven **3.9.12**, PostgreSQL **15.19**, Spring Boot **3.2.0**. Las dos verificaciones SQL de
migración también pasaron y se contabilizan aparte de JUnit. Los reportes están en
`target/surefire-reports/`.

Los conflictos deliberados pueden generar logs PostgreSQL `23505` por tokens duplicados:
son esperados en la prueba concurrente del mismo token, se traducen a HTTP 409 y no dejaron
filas parciales. No fueron fallos de la suite. La limpieza fue invocada de forma explícita en
integración; no se midió el temporizador de scheduling en tiempo de pared ni varias JVM.

## Publicación fiable de asiento.expirado

### Contexto de reserva: limitación explícita acordada para esta fase

Las retenciones manuales REST solo conocen token y asiento. **No se conoce su `reservaId` ni `correlationId`**.
No se deduce uno del otro, no se usa el token como reserva y no se generan identificadores ficticios.
Se eligió conservar estos eventos en **ESPERA_CONTEXTO**, sin enviarlos a RabbitMQ.

El JSON persistido incluye `eventId`, `timestamp` UTC, `tipo: asiento.expirado` y `payload` con
`vueloId`, `asientoId`, `asiento` (número) y `token`. Las claves `correlationId` y `payload.reservaId`
permanecen nulas **solo en el registro interno**, nunca se publican así.

`ContextoOutbox.completar` es un punto de integración **interno**, sin endpoint ni consumidor:
recibe datos auténticos suministrados por una integración futura autorizada, conserva el ID y timestamp,
y cambia una fila ESPERA_CONTEXTO a PENDIENTE de forma transaccional. Valida primero los campos originales
y comprueba que solamente falte el contexto previsto (ambas claves presentes y nulas). Si el JSON está
corrupto o contiene otro contexto, rechaza la operación sin sobrescribirlo ni cambiar el estado.
No reasigna filas ya habilitadas, enviadas o en ERROR_DATOS. `reservaId` se conserva como valor JSON escalar, sin fijar todavía si Sebastián utilizará
un entero o una cadena; `correlationId` debe ser un UUID independiente. Este método no busca ni verifica
reservas externas: su procedencia/asociación se debe resolver con Sebastián antes de conectarlo a producción.

En las pruebas reales se completa con **datos explícitos de fixtures**, no con información inventada
para las retenciones de desarrollo. Por eso las pruebas demuestran el transporte del contrato completo,
pero **las expiraciones REST actuales quedan retenidas hasta resolver el contexto de reserva**.

Las retenciones obtenidas por el consumidor provisional sí tienen una asociación durable recibida del
mensaje validado en `inventario_solicitudes`. Su expiración consulta esa asociación e inserta directamente
el evento completo en PENDIENTE en la misma transacción; no necesita completar contexto posteriormente.
Solo se utiliza una asociación que obtuvo realmente la retención para ese asiento. Conocer un token REST
preexistente no permite apropiarse de él ni convertirlo en una reserva asociada.

### Tabla inventario_outbox

| Columna | Uso |
|---------|-----|
| `event_id UUID PRIMARY KEY` | Identidad estable del hecho, generada al registrar la transición |
| `token UUID REFERENCES retenciones(token)` | Retención que expiró |
| `tipo VARCHAR(50)` | Restringido a `asiento.expirado` |
| `mensaje TEXT` | JSON serializado; idéntico en todos los intentos una vez completado el contexto |
| `correlation_id UUID` | Nulo mientras falta contexto; obligatorio al habilitar publicación |
| `estado` | ESPERA_CONTEXTO, PENDIENTE, ENVIADO o ERROR_DATOS |
| `intentos INTEGER` | Intentos cuya transacción de worker se confirmó |
| `creado_en`, `siguiente_intento`, `enviado_en` | TIMESTAMPTZ operativos del reloj PostgreSQL |
| `ultimo_error VARCHAR(100)` | Clasificación del fallo; sin cuerpos ni tokens en el log del worker |

`UNIQUE(tipo, token)` impide más de un evento de negocio por expiración. El índice parcial de
PENDIENTES evita recorrer el historial enviado. No se borran filas, ni se modifica el historial de retenciones.
La tabla se accede mediante JDBC en la misma conexión administrada por el gestor transaccional JPA.
`ddl-auto=validate` se conserva para las entidades JPA; esta tabla JDBC se instala mediante SQL explícito.

### Atomicidad y todos los caminos de expiración

`InventarioService.expirarActual` registra el evento dentro de la transacción que marca EXPIRADA y
libera el asiento. Lo usan el scheduler, el rebloqueo de un asiento vencido y una liberación recibida
después del TTL. No se registra al liberar una retención vigente, al confirmar, ni al repetir una
operación terminal. Un fallo de outbox o conflicto optimista revierte **estado y evento** conjuntamente.
La expiración antigua nunca se publica como una transición nueva sobre la reserva o bloqueo posterior.

### Publicador y concurrencia

Antes de consultar la conexión RabbitMQ, `ValidadorExpiracion` comprueba el JSON completo:

- Debe contener un único objeto JSON, sin claves duplicadas ni contenido adicional al final.
- `eventId`, `correlationId` y `payload.token`: cadenas UUID canónicas y coherentes con las columnas
  `event_id`, `correlation_id` y `token` de la fila outbox, respectivamente.
- `timestamp`: cadena ISO-8601 que identifica un instante (con zona/offset, no una fecha/hora local).
- `tipo`: exactamente `asiento.expirado`.
- `payload`: objeto; `reservaId`: entero JSON o cadena no vacía, conservando el contrato previo.
- `vueloId` y `asientoId`: enteros JSON positivos dentro del rango INTEGER de los modelos actuales.
- `asiento`: cadena de 1 a 10 caracteres alfanuméricos o guion, como el número de asiento de la API.
- Todos esos campos son obligatorios y no admiten nulos. No se convierten cadenas a números,
  no se rellenan valores, no se corrigen mensajes ni se vuelve a serializar el cuerpo al publicarlo.

La validación comprueba estructura e integridad, no la existencia de una reserva externa: esa asociación
sigue siendo responsabilidad de la integración futura con Sebastián. No añade campos ni cambia la routing key.

Un error permanente de validación deja la fila **ERROR_DATOS**, incrementa el contador y conserva el
cuerpo y el eventId originales con un código diagnóstico en `ultimo_error`. No contacta RabbitMQ,
no marca `enviado_en` y no programa otro intento automático. Los workers solo seleccionan PENDIENTE.
Se requiere revisión explícita de la causa; no hay reparación ni reactivación automática de ERROR_DATOS.
Las filas ESPERA_CONTEXTO siguen intactas y excluidas de la publicación.

Cada ciclo selecciona una fila PENDIENTE mediante **`FOR UPDATE SKIP LOCKED`**, en una transacción
independiente. Mantiene ese lock únicamente sobre la fila outbox durante el envío y espera del confirm;
no mantiene locks sobre el asiento. Un segundo worker omite la fila. No hay estado EN_PROCESO ni lease
que rescatar: si cae el proceso, PostgreSQL libera el lock y la fila sigue pendiente tras el rollback.
El diseño usa una conexión por envío y procesa un evento por ciclo; es deliberadamente sencillo,
no un publicador de alto rendimiento.

Publicación fija: exchange **`vuelos.events`**, routing key **`asiento.expirado`**, prioridad 10,
mensaje persistente, `messageId = eventId`, `correlationId` real y contenido JSON UTF-8.
La aplicación **no declara exchanges, colas ni bindings**: la topología corresponde a Isaac.

Spring AMQP usa publisher confirms **correlated**, publisher returns y **mandatory**. La fila solo
pasa a ENVIADO con ACK correlacionado y sin return. Un NACK, timeout, desconexión o NO_ROUTE deja
PENDIENTE, actualiza el motivo y programa espera exponencial hasta 300 segundos. Se reintenta sin
generar otro evento y sin renovar el TTL del asiento. El timeout del confirm es configurable y la
conexión AMQP tiene timeout; el timeout transaccional del worker es 40 segundos.

ENVIADO significa aceptación del broker sin devolución; **no prueba consumo por Reservas ni valida
qué cola recibió la publicación**. El binding correcto debe comprobarse en el despliegue. Si el broker
acepta y se pierde el confirm, o el proceso cae antes del commit local, puede haber una reentrega con
el mismo eventId. La garantía es **al menos una vez**, no exactamente una vez; Reservas deberá deduplicar.

Los tokens se publican únicamente por la ruta operativa acordada, no por rutas de notificación.
Isaac debe evitar bindings amplios de Notificaciones/WebSocket que copien `asiento.*`: un exchange
topic distribuye a todas las colas coincidentes, y el publicador no puede ocultar campos según destinatario.
La prueba aislada verifica que el binding específico de notificación no recibe estos mensajes.

### Configuración adicional

| Variable | Defecto | Uso |
|----------|---------|-----|
| `INVENTARIO_OUTBOX_HABILITADA` | true | Activa el polling, no desactiva el registro de expiraciones |
| `INVENTARIO_OUTBOX_INTERVALO_MS` | 5000 | Demora entre ciclos, mínimo 100 ms |
| `INVENTARIO_OUTBOX_CONFIRM_TIMEOUT_MS` | 5000 | Espera del confirm entre 100 y 30000 ms |
| `INVENTARIO_OUTBOX_REINTENTO_SEGUNDOS` | 5 | Base del backoff, entre 1 y 300 segundos |

El publicador outbox no utiliza DLQ. Sus fallos temporales de publicación se
conservan PENDIENTE y se reintentan; los errores de datos se conservan ERROR_DATOS sin reintento.
La consulta de esos estados y ESPERA_CONTEXTO permite detectar problemas diferenciados sin borrar eventos.

### Infraestructura compartida inspeccionada (sin modificar)

En el broker `rabbitmq_broker`, vhost `/`, las consultas `rabbitmqctl list_exchanges`, `list_queues`
y `list_bindings` no mostraron `vuelos.events`, `reservas.alta` ni el binding `asiento.expirado`.
`infra/rabbitmq/definitions.json` conserva dobles llaves inválidas y el binding de `reservas.alta`
usa `reserva.alta`, distinto a la guía. Compose monta el archivo pero no muestra configuración explícita
de importación. Esa infraestructura **no está preparada según la guía aclarada de Isaac**.
No se aplicaron declaraciones, mensajes ni migraciones sobre esos servicios existentes.

### Pruebas de esta fase

`test-outbox.sh` reutiliza las pruebas/migraciones PostgreSQL e incorpora un RabbitMQ **3-management**
temporal, con usuario propio, puerto libre en localhost y datos tmpfs. Limita schedulers Erlang solo
para este broker de prueba y espera antes del CLI para evitar interferir con su inicialización.
Declara la topología exclusivamente en él. Una prueba pausa y reanuda **solo** el contenedor con ID
y etiqueta de integración verificados, para probar un timeout real. La limpieza retira únicamente los
contenedores creados; no usa Compose ni elimina volúmenes existentes.

Se prueban transacciones PostgreSQL, JSON persistido, rollback, duplicados, expiración concurrente,
ausencia de eventos para confirmadas, recuperación al reconstruir el publicador, confirms reales,
returns reales al quitar el binding, reintento con mismo ID, dos workers con lock concurrente y
duplicación de transporte al simular caída después del ACK y antes del commit. La reconstrucción
del publicador se prueba como componente nuevo; no se afirma haber reiniciado toda la JVM.

### Resultado histórico de la primera fase outbox (2026-11-07)

Ejecución completa: **`bash scripts/test-outbox.sh` → `mvn verify` → BUILD SUCCESS**.
OpenJDK **17.0.20.1**, Maven **3.9.12**, PostgreSQL **15.19**, RabbitMQ **3.13.7** y Spring Boot **3.2.0**.

| Suite | Aprobadas | Fallos | Errores | Omitidas |
|-------|-----------|--------|---------|----------|
| InventarioControllerTest (ambas bases REST) | 29 | 0 | 0 | 0 |
| InventarioServiceTest | 20 | 0 | 0 | 0 |
| OutboxWorkerTest | 4 | 0 | 0 | 0 |
| RabbitPublicadorExpiracionTest | 7 | 0 | 0 | 0 |
| InventarioPostgresTest | 21 | 0 | 0 | 0 |
| OutboxRabbitPostgresTest | 8 | 0 | 0 | 0 |
| **Total** | **89** | **0** | **0** | **0** |

Se verificaron también la migración aditiva repetida de outbox y la conservación del esquema/datos
previos en bases temporales. El JAR ejecutable se empaquetó correctamente. Reportes:
`target/surefire-reports/`.

Antes de la ejecución final hubo un fallo de aserción: para comprobar persistencia en un mensaje
recibido de RabbitMQ, Spring usa `receivedDeliveryMode`, no el campo de publicación `deliveryMode`.
Se corrigió la prueba y se ejecutó de nuevo la suite completa. También se corrigió el arranque/limpieza
del broker temporal para no invocar el CLI demasiado pronto y no dejar PostgreSQL si el broker termina.
Los logs esperados `SIN_DESTINO`, `CONFIRM_TIMEOUT` y las colisiones de tokens de las carreras
no son fallos de la ejecución final.

**Publicación real comprobada en infraestructura temporal**, no solo mocks. **No comprobada ni
habilitada de extremo a extremo con Reservas del equipo**: faltan contexto real y topología compartida.

### Resultado actual del refuerzo de validación (2026-11-07)

Se ejecutó primero `mvn test`: **121 aprobadas**, sin fallos/errores; las suites optativas de integración
estaban desactivadas (Surefire reportó 27 elementos omitidos, antes de expandir los casos parametrizados).
Después se ejecutó **`bash scripts/test-outbox.sh`**, con PostgreSQL **15.19** y RabbitMQ **3.13.7**
temporales, Java **17.0.20.1** y Maven **3.9.12**. Resultado de `mvn verify`: **BUILD SUCCESS**.

| Suite | Aprobadas | Fallos | Errores | Omitidas |
|-------|-----------|--------|---------|----------|
| InventarioControllerTest | 29 | 0 | 0 | 0 |
| InventarioServiceTest | 20 | 0 | 0 | 0 |
| OutboxWorkerTest | 5 | 0 | 0 | 0 |
| RabbitPublicadorExpiracionTest | 67 | 0 | 0 | 0 |
| InventarioPostgresTest | 21 | 0 | 0 | 0 |
| OutboxRabbitPostgresTest | 20 | 0 | 0 | 0 |
| **Total final** | **162** | **0** | **0** | **0** |

La cobertura nueva comprueba cada campo ausente, nulo o de tipo incorrecto; JSON corrupto, claves
duplicadas, tokens/IDs incoherentes, timestamp inválido y ausencia de llamadas al broker ante errores.
La integración real comprueba persistencia en ERROR_DATOS, conservación del mensaje, exclusión del
polling posterior, contexto corrupto no reparado y envío normal de eventos válidos. Siguen pasando las
pruebas de broker temporalmente inaccesible, confirms/returns, múltiples workers, concurrencia de asientos,
TTL e idempotencia REST. Los logs ERROR_DATOS de los casos corruptos son resultados esperados.

El script también aplica dos veces la migración nueva sobre la versión anterior de outbox con fixtures
en ESPERA_CONTEXTO, PENDIENTE y ENVIADO; verifica que filas, estados previos y mensajes se conservan.
No se aplicó ninguna migración sobre la base existente del proyecto. La inicialización nueva y el
empaquetado del JAR también se comprobaron; reportes en `target/surefire-reports/`.

## Consumo provisional de Reservas con Inbox

### Inspección y decisiones pendientes

Se inspeccionaron las referencias remotas disponibles con `git show`/`git grep`, sin cambiar de rama.
`origin/sebastian` contiene el esqueleto de Reservas/Pagos y SQL de reservas con ID SERIAL, pero no
publicadores ni payloads documentados para estos eventos o `asiento.no_disponible`. Tampoco se halló
un contrato en los directorios de documentación revisados de las otras referencias accesibles.

`origin/isaac:infra/rabbitmq/definitions.json` sí contempla `inventario.entrada` con bindings
`reserva.creada` y `reserva.cancelada`, a diferencia del archivo local inválido. El broker existente
no mostró esas colas/bindings en el vhost `/` durante la inspección. No se hizo fetch, así que se informa
solo sobre las referencias que ya estaban disponibles localmente, no sobre cambios remotos no descargados.

Se implementó el siguiente **contrato provisional para pruebas**, no un acuerdo final de equipo:

```json
{
  "eventId": "10000000-0000-0000-0000-000000000001",
  "correlationId": "20000000-0000-0000-0000-000000000001",
  "timestamp": "2026-11-07T21:00:00Z",
  "tipo": "reserva.creada",
  "payload": {
    "reservaId": 123,
    "vueloId": 1,
    "asiento": "12B",
    "token": "30000000-0000-0000-0000-000000000001"
  }
}
```

La cancelación requiere los mismos identificadores y `tipo: reserva.cancelada`. No se equiparan reserva,
correlación y token. Se acepta reserva numérica positiva o cadena no vacía mientras se acuerda su tipo;
`123` y `"123"` se conservan como identidades distintas. El número de asiento debe coincidir exactamente
con el almacenado (la API de inicialización normaliza a mayúsculas). JSON, UUID, tipos, timestamp y campos
son obligatorios; se rechazan duplicados de claves y discrepancias entre routing key y `tipo`.

Política conservadora provisional: **una reserva se asocia a un solo asiento, token y correlación**.
Los identificadores no pueden reasignarse con otro mensaje. Cambios de asiento, varios asientos por
reserva o una nueva retención para una reserva cancelada requieren acordar otro protocolo; actualmente
se rechazan y nunca provocan una liberación/bloqueo inesperado.

### Arquitectura y transacción

```text
inventario.entrada → ReservasListener → validación estricta → ProcesadorReservas (@Transactional)
  → INSERT Inbox con clave única
  → asociación durable / marca de cancelación
  → InventarioService.bloquear o liberar (misma transacción)
  → resultado Inbox → COMMIT → retorno listener → ACK automático del contenedor
```

El consumidor no publica directamente a RabbitMQ. El único evento de salida añadido al flujo es la
expiración mediante la outbox existente. `asiento.bloqueado` y `asiento.no_disponible` **no se publican**:
sus payloads y destinatarios/resultados siguen pendientes de Sebastián. El resultado local no sustituye
esa respuesta distribuida; por eso esta implementación no constituye todavía una Saga operativa completa.

### Tablas nuevas

**`inventario_inbox`**: clave primaria `(consumidor, event_id)`, tipo, huella SHA-256 de los campos
conocidos normalizados del evento, resultado y fechas de recepción/procesamiento. El consumidor lógico
es `inventario.reservas.v1`, idéntico para todas sus instancias. `INSERT ... ON CONFLICT DO NOTHING`
resuelve duplicados simultáneos; no es una comprobación previa sin protección. Si otra transacción
confirma primero, se compara su huella y se devuelve el resultado. Reutilizar eventId con otros datos
es error permanente. No se confunde la huella con firma/autenticación del emisor.

La inserción, resultado, asociación y cambios JPA se confirman juntos. Un fallo revierte también la Inbox.
No se guarda una fila completada antes de conocer el resultado. Perder el ACK después del commit provoca
una reentrega que encuentra el registro y no renueva TTL ni repite acciones. No se purga la Inbox.

**`inventario_solicitudes`**: clave de reserva como JSON escalar canónico, correlación, token globalmente
único, vuelo/número, asiento interno nullable, estado y fecha. Conserva las asociaciones y cancelaciones
anticipadas aunque todavía no exista una retención o el mapa del vuelo. No tiene FK hacia retenciones
porque la cancelación puede llegar primero; sí tiene FK nullable al asiento. La retención y sus estados
originales no se modificaron estructuralmente.

Solo se bloquea la fila de asociación con `FOR UPDATE` para serializar mensajes de la misma reserva.
Las reservas diferentes siguen compitiendo por el asiento mediante **@Version**, sin lock pesimista de
asientos. La expiración solo lee la asociación y mantiene sus propias reglas optimistas.

### Resultados y mensajes desordenados

| Caso | Resultado y efecto |
|------|--------------------|
| Creación con asiento libre | ASIENTO_BLOQUEADO; TTL del servicio y asociación durable |
| Asiento ocupado | ASIENTO_NO_DISPONIBLE; resultado confirmado, sin reintentos infinitos |
| Asiento inexistente | ASIENTO_INEXISTENTE; solicitud rechazada, no crea asientos |
| Mismo eventId y contenido | Devuelve resultado guardado, sin efectos |
| Mismo token con otro eventId, misma asociación vigente | Idempotencia del servicio, sin renovar TTL |
| Token liberado/expirado | RETENCION_TERMINAL; no se reactiva |
| Token ya confirmado | YA_CONFIRMADA; no se vuelve a bloquear |
| Token de otra reserva o asociación alterada | Rechazo local sin cambiar el asiento |
| Token REST sin asociación previa | No se adopta ni libera por conocerlo |
| Cancelación de retención propia | Reutiliza liberar; los reintentos no vuelven a tocar el asiento |
| Cancelación antes de creación | Marca CANCELADA durable; creación retrasada no bloquea |
| Cancelación antigua tras otro bloqueo | Usa el token antiguo; no libera el nuevo |
| Cancelación de confirmada | RESERVADO_NO_LIBERADO; conserva reserva y registra cancelación pendiente de compensación |

Las solicitudes rechazadas no se reactivan automáticamente cuando cambia la disponibilidad o se crea
el mapa después. Ese tratamiento de reintentos de negocio debe acordarse con Reservas. Una cancelación
sí puede quedar registrada para impedir cualquier creación tardía de la misma identidad.

### Activación y errores

`INVENTARIO_CONSUMIDOR_HABILITADO=false` es el valor predeterminado. En ese estado ni el listener ni su
container factory específicos se crean. No se declara topología compartida desde la aplicación. Antes
de activar se necesitan contrato validado, migración aplicada, permisos de acceso y una DLQ verificada.

La factory usa AUTO ACK **después** del retorno exitoso del procesador transaccional, prefetch 1 y
`defaultRequeueRejected=false`. Errores permanentes de mensaje no se reintentan; fallos temporales,
incluidos conflictos optimistas o fallos PostgreSQL, tienen hasta **3 intentos totales**, cada uno con
transacción nueva y espera de 100/200 ms. Agotados los intentos se rechaza sin requeue. Los resultados
de negocio esperados se guardan y ACKean, no se convierten en errores técnicos.

La DLQ depende de los argumentos/políticas del broker. **Sin dead-lettering correcto, un rechazo podría
descartarse**; no habilitar hasta verificarlo con Isaac. En su referencia se usa `vuelos.dlx`, distinto
del DLX constitucional `dlq.reservas`, y los bindings DLQ mostrados no enlazan ese DLX a una cola de
Inventario: debe acordarse/corregirse por su responsable. Tampoco deben copiarse tokens a Notificaciones:
esa referencia enlaza `reserva.creada/cancelada` con `notificaciones.entrada`, por lo que el payload
propuesto requiere revisar su proyección pública antes del despliegue.

El límite de intentos no sustituye timeouts de red JDBC. En la prueba de PostgreSQL caído se usan
`socketTimeout`/`connectTimeout` explícitos solo en la URL temporal; conviene acordar sus valores para
el despliegue. No se alteró la URL existente de la base del proyecto.

### Pruebas de consumo

`bash scripts/test-outbox.sh` también ejecuta la nueva suite de Inbox. Crea otra base **temporal**
`db_inventario_inbox` para aislarla de las suites anteriores; las demás siguen usando `db_inventario`.
La topología de prueba se declara solo en RabbitMQ temporal e incluye una DLQ funcional. La prueba de
fallo PostgreSQL pausa/reanuda exclusivamente el contenedor cuyo ID y etiqueta de integración se verifican.
Nunca se pausan ni detienen los contenedores existentes del proyecto.

Se comprueban recepción RabbitMQ, Inbox y asociación, ocupación concurrente, duplicados simultáneos,
tokens reutilizados, cancelaciones cruzadas/anticipadas, confirmadas protegidas, expiración con contexto,
publicación real de ese contexto, rollback conjunto, reentrega real después de commit sin ACK,
rearranque del container listener y recuperación/reproceso desde DLQ tras indisponibilidad PostgreSQL.
El rearranque probado es del listener, no de toda la JVM. La migración se aplica dos veces en la base
temporal de migraciones y verifica que no modifica los datos anteriores.

### Resultado comprobado de esta fase

Ejecución final: **`bash scripts/test-outbox.sh` → `mvn verify` → BUILD SUCCESS**.
Java **17.0.20.1**, Maven **3.9.12**, Spring Boot **3.2.0**, PostgreSQL **15.19** y RabbitMQ **3.13.7**.

| Suite | Aprobadas | Fallos | Errores | Omitidas |
|-------|-----------|--------|---------|----------|
| InventarioControllerTest | 29 | 0 | 0 | 0 |
| InventarioServiceTest | 20 | 0 | 0 | 0 |
| OutboxWorkerTest | 5 | 0 | 0 | 0 |
| RabbitPublicadorExpiracionTest | 67 | 0 | 0 | 0 |
| InventarioPostgresTest | 21 | 0 | 0 | 0 |
| OutboxRabbitPostgresTest | 20 | 0 | 0 | 0 |
| ValidadorReservaTest | 40 | 0 | 0 | 0 |
| ReservasListenerTest | 2 | 0 | 0 | 0 |
| ConsumidorDeshabilitadoTest | 1 | 0 | 0 | 0 |
| InboxReservasPostgresRabbitTest | 23 | 0 | 0 | 0 |
| **Total** | **228** | **0** | **0** | **0** |

Los 64 casos de integración usan PostgreSQL y, en las suites AMQP, RabbitMQ reales; los demás comprueban servicio, REST,
validación, listener y configuración. Las trazas de conexión rota y rechazo sin requeue son esperadas
en las pruebas de indisponibilidad y mensajes inválidos; no representan fallos de la suite.
La prueba de pérdida de ACK confirma primero el negocio y fuerza una reentrega real mediante NACK/requeue
en el cliente de prueba, comprobando la marca redelivered y un solo cambio persistido.
El JAR se empaquetó correctamente. Reportes: `target/surefire-reports/`.

No se aplicaron migraciones ni mensajes sobre el entorno existente del equipo. El listener está probado
en aislamiento, pero **no está habilitado en la configuración por defecto ni integrado con Reservas real**.

## Integraciones pendientes

- Acordar con Vuelos cómo validar existencia y autorizar la inicialización del mapa de asientos,
  y cómo tratar cambios posteriores de capacidad/cancelación de vuelos.
- Acordar con Reservas el UUID persistido por cada intento; podría derivarse de una identificación
  de reserva si es única y no se reutiliza tras terminar. No se implementa aún un `reserva_id` externo.
- Con Sebastián: origen, tipo y asociación real de `reservaId` y `correlationId` por token,
  incluida su persistencia antes de la expiración y el tratamiento de eventos ESPERA_CONTEXTO.
- Con Isaac: instalar/verificar exchange topic durable, cola durable `reservas.alta` y binding exacto
  `asiento.expirado`, sin propagar tokens a colas de notificación. No se corrigieron archivos compartidos.
- RabbitMQ/Saga: validar y habilitar el consumidor provisional; acordar los demás eventos
  (`asiento.bloqueado`, `asiento.liberado`, `asiento.no_disponible`) y el protocolo final de confirmación.
- La cancelación de un asiento ya RESERVADO necesita un contrato específico de compensación;
  no se habilita mediante una liberación de retención duplicada.
- Autenticación/autorización del gateway y exposición de operaciones de administración pendientes.
  El token es una credencial de posesión; no sustituye identidad/autorización y debe protegerse en logs.
- Sin paginación ni purga de historial: borrar tokens sin una política de idempotencia permitiría
  reactivar solicitudes antiguas. Mapas grandes/carga sostenida requieren medir límites.
- Despliegue real con Compose, gateway, otras versiones de PostgreSQL y varias instancias pendiente.
