# Inventario de Asientos

Núcleo REST de disponibilidad, retenciones temporales y confirmación de asientos.
Java **17**, Spring Boot **3.2.0**, JPA y PostgreSQL independiente **`db_inventario`**.
Puerto HTTP **8085**. Sin interfaz gráfica. RabbitMQ y Saga corresponden a una fase posterior.

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
└── persistence   # Repositorios JPA y alta idempotente de asientos
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
debe confirmar antes de que el controlador reciba el resultado. No se usan locks pesimistas
ni un mutex en memoria. La clave única `(vuelo_id, numero_asiento)` evita duplicados incluso
entre instancias y `INSERT ... ON CONFLICT DO NOTHING` permite inicializaciones simultáneas.

### Inicialización y migración

- Base nueva: inicializar expresamente con `sql/init.sql`.
- Base con el esquema original: revisar y aplicar manualmente `sql/migracion-retenciones.sql`,
  con el servicio detenido, sobre **su propia** `db_inventario` y con `psql -v ON_ERROR_STOP=1`.
  Es aditiva: no borra ni reescribe filas; aborta si encuentra bloqueos heredados sin identidad.
  Los `estado`/`version` nulos también impedirán aplicar NOT NULL y requieren reconciliación manual.
  Los reservados históricos permanecen reservados y sin retención nueva inventada.
- Modificar `init.sql` no actualiza volúmenes ya inicializados. No se borran volúmenes para migrar.
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

Las propiedades RabbitMQ existentes se conservan, pero no hay publicadores/consumidores y no se
necesita broker para este núcleo. Maven no carga `.env` automáticamente. Desde esta carpeta:

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

### Resultado comprobado (2026-11-07)

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

## Integraciones pendientes

- Acordar con Vuelos cómo validar existencia y autorizar la inicialización del mapa de asientos,
  y cómo tratar cambios posteriores de capacidad/cancelación de vuelos.
- Acordar con Reservas el UUID persistido por cada intento; podría derivarse de una identificación
  de reserva si es única y no se reutiliza tras terminar. No se implementa aún un `reserva_id` externo.
- RabbitMQ/Saga: añadir contratos idempotentes para `asiento.bloqueado`, `asiento.liberado`,
  `asiento.expirado` y `asiento.no_disponible`, junto con `eventId`, `correlationId` y `timestamp`.
  Publicar de forma consistente con la transacción requerirá definir outbox/reintentos.
- La cancelación de un asiento ya RESERVADO necesita un contrato específico de compensación;
  no se habilita mediante una liberación de retención duplicada.
- Autenticación/autorización del gateway y exposición de operaciones de administración pendientes.
  El token es una credencial de posesión; no sustituye identidad/autorización y debe protegerse en logs.
- Sin paginación ni purga de historial: borrar tokens sin una política de idempotencia permitiría
  reactivar solicitudes antiguas. Mapas grandes/carga sostenida requieren medir límites.
- Despliegue real con Compose, gateway, otras versiones de PostgreSQL y varias instancias pendiente.
