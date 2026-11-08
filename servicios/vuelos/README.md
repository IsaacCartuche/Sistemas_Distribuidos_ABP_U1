# Servicio de Vuelos

Catálogo de vuelos, rutas y horarios con control optimista y publicación transaccional de `vuelos.actualizados`.

## Tecnología y alcance

- Java 17 y Spring Boot **3.2.0**.
- Spring Web, Spring Data JPA, Jakarta Validation y PostgreSQL.
- Base de datos propia: **`db_vuelos`**. Tabla `vuelos`, compatible con `sql/init.sql`.
- Puerto HTTP: **8081**.
- Spring AMQP publica mediante una outbox propia. No hay listeners ni Inbox: no existen contratos de entrada acordados para Vuelos.

**La disponibilidad real pertenece a `inventario-asientos`.** `capacidadDisponible` es únicamente
un dato informativo del catálogo, editable en esta fase para mantener compatibilidad con la tabla.
Puede estar desactualizado: no debe utilizarse para autorizar reservas. Este servicio no bloquea,
vende ni confirma asientos y no consulta la base de datos de inventario.

## Estructura

```text
com.empresa.vuelos
├── api              # Controlador, DTOs y errores HTTP
├── application      # Lógica de negocio y excepciones
├── domain           # Vuelo y EstadoVuelo
├── persistence      # Repositorio Spring Data JPA
└── outbox           # Snapshot transaccional, validación, publicador y worker
```

## Configuración y ejecución local

Requisitos: JDK 17, Maven y una base PostgreSQL `db_vuelos` previamente inicializada con
`sql/init.sql` en un entorno de desarrollo. El servicio utiliza `ddl-auto: validate`:
valida el esquema existente y no crea ni modifica tablas. No ejecute el SQL sobre bases compartidas
sin coordinación; el arranque no lo ejecuta automáticamente.

Para una base existente, aplicar manualmente **`sql/migracion-version-outbox.sql` antes de desplegar
esta versión**, con respaldo, servicio detenido y `psql -v ON_ERROR_STOP=1`. Añade `version INTEGER
NOT NULL DEFAULT 0` a vuelos y crea `vuelos_outbox`; no elimina vuelos ni cambia los datos previos del
catálogo. No genera eventos históricos. El DEFAULT inicializa la revisión técnica de los vuelos anteriores.
La migración se comprueba repetida en una base temporal; no se aplicó sobre la base local del proyecto.
`IF NOT EXISTS` no valida objetos preexistentes con una estructura distinta: revisar el esquema antes de aplicar.

Variables de conexión:

| Variable | Valor por defecto |
|----------|-------------------|
| `POSTGRES_HOST` | `localhost` |
| `POSTGRES_PORT` | `5432` |
| `POSTGRES_USER` | `postgres` |
| `POSTGRES_PASSWORD` | `postgres` |

Las siguientes instrucciones se ejecutan desde `servicios/vuelos/`:

```bash
mvn test
mvn package
POSTGRES_HOST=localhost POSTGRES_PORT=5431 mvn spring-boot:run
```

El puerto `5431` corresponde al PostgreSQL de vuelos publicado por el Compose existente.
Si usa otra instalación de PostgreSQL, configure su host y puerto. Dentro de la red de Compose,
la dirección correspondiente sería `postgres-vuelos:5432`; `localhost` no apunta a otro contenedor.
Maven no carga automáticamente el archivo `.env`.

También puede ejecutar el JAR con opciones JVM explícitas:

```bash
POSTGRES_PORT=5431 java -Xmx512m -Xms256m -jar target/servicio-vuelos-1.0.0.jar
```

Las propiedades `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER` y `RABBITMQ_PASS` configuran el
publicador. Su desconexión no impide arrancar ni confirmar operaciones REST: el evento permanece en
PostgreSQL para reintento. Los tests unitarios/MockMvc no requieren broker.

## API REST

Bases equivalentes:

- Versionada: `http://localhost:8081/api/v1/vuelos`.
- Compatible con la ruta del Gateway: `http://localhost:8081/api/vuelos`.

Ambas bases admiten todos los endpoints y filtros de la tabla siguiente mediante el mismo
controlador, sin redirecciones. Por ejemplo, `GET /api/vuelos/1` equivale a
`GET /api/v1/vuelos/1`. Las rutas versionadas se conservan. Al crear un vuelo, la cabecera
`Location` conserva la variante utilizada en la solicitud (`/api/vuelos/{id}` o `/api/v1/vuelos/{id}`).

| Método | Ruta | Resultado |
|--------|------|-----------|
| POST | `/api/v1/vuelos` | Crear: `201 Created`, cuerpo y cabecera `Location` |
| GET | `/api/v1/vuelos/{id}` | Consultar: `200 OK` o `404 Not Found` |
| GET | `/api/v1/vuelos` | Listar: `200 OK`, array JSON |
| GET | `/api/v1/vuelos?origen=Quito&destino=Loja&fecha=2027-05-10` | Buscar: `200 OK`, array JSON |
| PUT | `/api/v1/vuelos/{id}` | Actualizar todos los campos editables: `200 OK` o `404 Not Found` |

Los filtros son opcionales e independientes; los presentes se combinan con AND.
Origen y destino se comparan por igualdad sin distinguir mayúsculas/minúsculas y se recortan
los espacios de los extremos. Un filtro presente pero vacío se rechaza con `400`.
`fecha` filtra `fechaSalida` exactamente y usa `yyyy-MM-dd`.
Los resultados se ordenan por fecha, hora e ID; sin coincidencias se devuelve `[]`.
Esta primera fase no incluye paginación.

### Crear o actualizar

Todos los campos siguientes son obligatorios tanto en POST como en PUT:

```json
{
  "origen": "Quito",
  "destino": "Loja",
  "fechaSalida": "2027-05-10",
  "horaSalida": "09:30:00",
  "precio": 125.50,
  "estado": "ACTIVO",
  "capacidadTotal": 100,
  "capacidadDisponible": 80
}
```

Reglas:

- Origen y destino no pueden estar vacíos, admiten hasta 100 caracteres y deben ser distintos,
  incluso al ignorar mayúsculas y espacios de los extremos.
- Fecha y hora son obligatorias; conservan los tipos locales `DATE` y `TIME` del SQL actual.
  Esta fase no interpreta zonas horarias ni exige fechas futuras.
- Precio usa `BigDecimal`, debe ser al menos `0.01` y admite hasta 8 dígitos enteros y 2 decimales,
  de acuerdo con `DECIMAL(10,2)`; no se redondean importes inválidos silenciosamente.
- Estados admitidos por esta API: `ACTIVO`, `INACTIVO` y `CANCELADO`.
- `capacidadTotal` debe ser positiva.
- `capacidadDisponible` debe estar entre cero y `capacidadTotal`; es informativa.
- Los identificadores deben ser enteros positivos.
- `id` y `fechaCreacion` son generados por el servidor y no forman parte del DTO de entrada.
- PUT no crea vuelos inexistentes y conserva ID y fecha de creación.
- PUT idéntico al valor persistido no escribe ni genera evento. Se comparan ciudades recortadas y precio
  numérico (125.5 equivale a 125.50). Para hora se compara la representación de java.sql.Time del mapper
  Hibernate 6.3 (redondeada a milisegundos), evitando duplicados por fracciones que el mapper no conserva.
- `@Version` impide que dos transacciones que leyeron la misma versión sobrescriban silenciosamente sus
  cambios: la perdedora recibe 409. La versión se utiliza como `payload.revision` del evento, sin añadir
  campos obligatorios a los DTOs REST existentes. No se cambia POST ni se impone unicidad por ruta/fecha.

Ejemplos:

```bash
curl -i -X POST http://localhost:8081/api/v1/vuelos \
  -H 'Content-Type: application/json' \
  -d '{"origen":"Quito","destino":"Loja","fechaSalida":"2027-05-10","horaSalida":"09:30:00","precio":125.50,"estado":"ACTIVO","capacidadTotal":100,"capacidadDisponible":80}'

curl http://localhost:8081/api/v1/vuelos/1

curl --get http://localhost:8081/api/v1/vuelos \
  --data-urlencode 'origen=Quito' --data-urlencode 'destino=Loja' --data-urlencode 'fecha=2027-05-10'

curl -i -X PUT http://localhost:8081/api/v1/vuelos/1 \
  -H 'Content-Type: application/json' \
  -d '{"origen":"Quito","destino":"Loja","fechaSalida":"2027-05-10","horaSalida":"10:00:00","precio":130.00,"estado":"ACTIVO","capacidadTotal":100,"capacidadDisponible":80}'
```

### Errores

Se utiliza `application/problem+json` con `status`, `title`, `detail` e `instance`.
Los errores de validación de campos incluyen además el mapa `errores`.

- `400 Bad Request`: campos inválidos, reglas de negocio, JSON o parámetros incorrectos.
- `404 Not Found`: vuelo inexistente.
- `409 Conflict`: conflicto con restricciones de persistencia, sin exponer SQL interno.
  También actualización optimista concurrente; debe consultarse el estado antes de reintentar.

Ejemplo de regla de negocio:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "El origen y el destino deben ser distintos",
  "instance": "/api/v1/vuelos"
}
```

## Pruebas

```bash
mvn test
# Suite completa con PostgreSQL y RabbitMQ temporales:
bash scripts/test-outbox.sh
# Construcción del Dockerfile y smoke test de su imagen con PostgreSQL temporal, sin broker:
bash scripts/test-docker.sh
```

- `VueloServiceTest`: normalización, precisión decimal, validaciones de ruta/capacidad,
  consultas y filtros, actualización, conservación de identidad y recursos inexistentes.
- `VueloControllerTest`: contrato HTTP con MockMvc, `Location`, DTOs, validaciones,
  filtros y fechas, errores 400/404/409 y rechazo de JSON inválido.
  Verifica ambas bases para crear, consultar, listar, buscar y actualizar, además de errores
  representativos 400/404/409 y la cabecera `Location` correspondiente a cada variante.

Las dos clases anteriores usan mocks: no abren conexiones PostgreSQL/RabbitMQ ni modifican bases de datos.
Las pruebas PostgreSQL son optativas y se omiten si `VUELOS_POSTGRES_INTEGRATION` no vale `true`.
La suite AMQP se activa con `VUELOS_RABBIT_INTEGRATION=true`, configurada por `test-outbox.sh`.
No se incluyen Saga de reservas, autenticación, bloqueo de asientos o TTL en Vuelos.

### Integración con PostgreSQL real y aislado

Desde `servicios/vuelos/`, con JDK 17, Maven y Docker funcionando:

```bash
bash scripts/test-postgres.sh
```

El script crea un contenedor nuevo `postgres:15-alpine`, con `db_vuelos` y credenciales exclusivas
de prueba, datos en `tmpfs` y puerto host libre asignado por Docker, accesible solo por `127.0.0.1`.
Monta `sql/init.sql` en modo lectura para inicializar únicamente esa base nueva. Espera a que
PostgreSQL acepte conexiones TCP y ejecuta `mvn verify`. Comprueba la migración dos veces sobre otra
base temporal con filas previas y una fila outbox de fixture, preservando valores y mensajes.
Al terminar, incluso si Maven falla, retira exclusivamente los contenedores creados y sus datos temporales.
No utiliza Compose ni volúmenes persistentes.

`VueloPostgresIntegrationTest` arranca el contexto completo de Spring Boot y un servidor HTTP
en un puerto aleatorio. Usa JPA, JDBC y PostgreSQL reales, sin mocks. Hibernate mantiene
`ddl-auto=validate` y Spring no ejecuta scripts SQL al arrancar.

Las variables `VUELOS_TEST_DB_URL`, `VUELOS_TEST_DB_USER` y `VUELOS_TEST_DB_PASSWORD` son
exclusivas de esta prueba y las configura el script; no cambian `application.yml` ni `.env`.
La clase inserta y actualiza datos: su activación manual debe apuntar únicamente a una base aislada
de prueba previamente inicializada, nunca a desarrollo compartido o producción.

Cobertura de los **13 casos de integración**:

- Crear con HTTP 201, `Location`, ID generado y fecha de creación; consultar por ID.
- Comprobar persistencia decimal, fecha, hora, estado y nombre de base `db_vuelos`.
- Listar y buscar las ocho combinaciones posibles de origen/destino/fecha, incluidos filtros
  ausentes, comparación sin distinguir mayúsculas, combinación AND y orden de resultados.
- Actualizar y volver a consultar todos los campos editables, incluido el precio máximo
  `99999999.99`; conservar identidad y fecha de creación.
- Búsqueda sin coincidencias (`[]`), consulta/actualización inexistente (404),
  y solicitudes inválidas (400) sin insertar filas.

### Resultado histórico de la primera verificación (2026-11-07, antes de aliases/outbox)

| Grupo | Ejecutadas | Fallidas/errores | Omitidas |
|-------|------------|-----------------|----------|
| Servicio | 18 | 0 | 0 |
| Controlador con MockMvc | 39 | 0 | 0 |
| Integración PostgreSQL/HTTP real | 13 | 0 | 0 |
| **Total final** | **70** | **0** | **0** |

Entorno: OpenJDK **17.0.20.1**, Maven **3.9.12**, Spring Boot **3.2.0**, PostgreSQL **15.19**,
Docker **29.9.3** y Compose **v5.7.0**. Las 57 pruebas originales también se ejecutaron primero
con `mvn test`, correctamente, sin propiedades experimentales de Byte Buddy.

La primera ejecución contra PostgreSQL detectó **8 errores de integración** en listados/búsquedas:
la consulta JPQL con parámetros opcionales nulos provocaba `lower(bytea)` y SQLState `42P18`.
Un intento intermedio con casts redujo el problema a 4 errores, pero no resolvió el parámetro fecha
nulo (`bytea` a `date`). La solución final utiliza `JpaSpecificationExecutor`/Criteria para añadir
solo los filtros presentes, con parámetros tipados y el mismo contrato REST. La ejecución final
del script, contra otro contenedor nuevo, pasó los 70 casos sin errores.

Compatibilidad confirmada: `Integer`/`SERIAL`, `BigDecimal`/`NUMERIC(10,2)`,
`LocalDate`/`DATE`, `LocalTime`/`TIME`, enum textual/`VARCHAR(20)` y
`LocalDateTime`/`TIMESTAMP`. PostgreSQL conserva microsegundos en el timestamp, frente a los
nanosegundos que puede entregar el reloj Java en la respuesta inicial; la prueba considera esa precisión.

## Evento vuelos.actualizados

Exchange **`vuelos.events`**, tipo **topic**, durable según la constitución; routing key exacta
**`vuelos.actualizados`**. La aplicación no declara el exchange ni colas/bindings. No se añaden consumidores
ni Inbox a Vuelos. En las referencias accesibles del equipo solo se encontró el nombre constitucional,
no consumidores o bindings definitivos. Su definición corresponde al acuerdo con Isaac y destinatarios.

Se registra por cada actualización real persistida, **no** en GET, PUT idéntico ni rollback.
**POST no emite eventos**: la emisión al crear un vuelo permanece pendiente de acuerdo y no se activa.
Cambiar únicamente `capacidadDisponible` sigue siendo un cambio persistido y produce una revisión nueva,
pero ese contador **no aparece** en el mensaje ni permite autorizar reservas.

Ejemplo ilustrativo (los IDs son de ejemplo, no datos suplidos en producción):

```json
{
  "eventId": "10000000-0000-0000-0000-000000000001",
  "correlationId": "20000000-0000-0000-0000-000000000001",
  "timestamp": "2026-11-07T21:00:00Z",
  "tipo": "vuelos.actualizados",
  "payload": {
    "vueloId": 1,
    "origen": "Quito",
    "destino": "Loja",
    "fechaSalida": "2027-05-10",
    "horaSalida": "09:30",
    "precio": 130.00,
    "estado": "ACTIVO",
    "capacidadTotal": 100,
    "revision": 1
  }
}
```

`eventId` identifica el hecho y `correlationId` identifica la operación de actualización del catálogo:
se generan una sola vez al registrar el evento y se conservan en reintentos. No representan una reserva,
no se equiparan entre sí y no se inventa reservaId/token. El timestamp es un instante UTC del reloj del
servicio. Fecha/hora de salida conservan sus tipos locales; no se les agrega zona ni se reinterpretan como UTC.
No se añade moneda, mapa de asientos, disponibilidad real, usuarios o pagos que Vuelos no conoce.

El servicio hace flush del UPDATE optimista antes de registrar el evento y refresca la entidad para
serializar los valores realmente persistidos (precisión de TIME/NUMERIC incluida). La revisión del
mensaje coincide con `vuelos.version` en esa transacción. Un conflicto o un fallo al registrar la outbox
revierte **vuelo, versión y evento**. No se envían mensajes directamente desde esa transacción.

### Tabla vuelos_outbox y entrega

| Columna | Uso |
|---------|-----|
| event_id UUID PK | Identidad estable del evento |
| correlation_id UUID | Traza de la operación del catálogo |
| vuelo_id INTEGER FK | Referencia a la base propia de Vuelos |
| revision INTEGER | Versión de la actualización, mayor que cero |
| tipo | Solo vuelos.actualizados |
| mensaje TEXT | Sobre JSON persistido, no se reconstruye al reintentar |
| estado | PENDIENTE, ENVIADO o ERROR_DATOS |
| intentos / ultimo_error | Intentos confirmados del worker y motivo diagnóstico |
| creado_en / siguiente_intento / enviado_en | Tiempos operativos TIMESTAMPTZ de PostgreSQL |

`UNIQUE(vuelo_id, revision)` impide múltiples hechos para una misma revisión. El índice parcial de
PENDIENTES evita recorrer todo el historial. La tabla JDBC participa en la misma conexión/transacción
que JPA; Hibernate validate no crea ni valida esta tabla, por eso se instala con el SQL explícito.

El worker reclama una fila mediante **FOR UPDATE SKIP LOCKED**, en una transacción independiente.
Mantiene solo su lock de fila outbox durante el envío y espera del confirm, no locks del catálogo.
Dos workers no reclaman simultáneamente la misma fila. Si un proceso cae, PostgreSQL libera el lock
y revierte la marca; no hay lease o estado EN_PROCESO abandonado que rescatar.

Se usan mensajes persistentes, UTF-8/JSON, messageId=eventId, confirms correlacionados, returns y
**mandatory=true**. Solo ACK del broker sin return permite marcar ENVIADO. NACK, timeout, desconexión,
exchange inexistente o falta de binding dejan PENDIENTE y un backoff exponencial hasta 300 segundos.
El proceso sigue arrancando y atendiendo REST aunque el broker falle. Un error de persistencia al
marcar ENVIADO se propaga para rollback: puede haber una reentrega con el mismo ID.

La garantía es **al menos una vez**, no exactamente una vez. ENVIADO significa aceptación del broker
sin devolución, no procesamiento por un destinatario concreto. Los consumidores futuros deben deduplicar
eventId y proteger sus proyecciones frente a revisiones antiguas: varios workers/reintentos pueden alterar
el orden. No se garantiza orden global ni entrega a un consumidor que todavía no tiene una cola enlazada.

Antes de contactar RabbitMQ se valida JSON estricto, claves únicas y campos exactos; UUID/IDs/revisión
deben coincidir con la fila, timestamp UTC, precio, estado, ruta, capacidad y horarios deben ser válidos.
No se permiten campos ajenos como capacidadDisponible. Un fallo permanente conserva cuerpo e identidad
en ERROR_DATOS, sin reintento automático ni reparación. Las caídas del broker siguen siendo temporales.

| Variable propia | Defecto |
|----------------|---------|
| VUELOS_OUTBOX_HABILITADA | true |
| VUELOS_OUTBOX_INTERVALO_MS | 5000 |
| VUELOS_OUTBOX_CONFIRM_TIMEOUT_MS | 5000 |
| VUELOS_OUTBOX_REINTENTO_SEGUNDOS | 5 |

Deshabilitar polling no desactiva el registro transaccional. No se purgan filas de outbox en esta fase.

## Concurrencia y límites conservados

@Version detecta transacciones solapadas que leyeron la misma versión. No equivale a un If-Match de
cliente: un PUT secuencial construido desde datos antiguos puede seguir reemplazando campos, porque
se preservó el contrato REST sin revisión obligatoria. Precondiciones de cliente son una ampliación
posterior si se necesitan; no se simula esa garantía. POST mantiene su semántica actual y no adquiere
una unicidad artificial por origen/destino/fecha. No hay deduplicación de POST por clave de cliente.

## Docker desde el contexto real de Compose

Desde la raíz del repositorio:

```bash
docker build -f servicios/vuelos/Dockerfile -t servicio-vuelos .
```

El Dockerfile multietapa compila con Maven 3.9.12/Java 17 y ejecuta con JRE 17 Alpine, puerto 8081.
Solo copia POM, src y entrypoint propios. `Dockerfile.dockerignore` excluye target, archivos ajenos y
JARs locales: la construcción no depende de una compilación previa ni de archivos no versionados.
El build empaqueta con `-DskipTests`; las pruebas se ejecutan separadamente con Maven/script completo.

El entrypoint hace exec de Java, separa las opciones convencionales de JAVA_OPTS sin eval ni glob,
y conserva argumentos de aplicación. También admite JAVA_TOOL_OPTIONS (interpretado por la JVM).
Usar flags válidos, por ejemplo **JAVA_OPTS=-Xmx512m -Xms256m**. Para valores con espacios/quoting
complejo se recomienda JAVA_TOOL_OPTIONS; el shell no reinterpreta comillas dentro de JAVA_OPTS.

**Pendientes de Isaac, no aplicados en archivos compartidos**:

- Dentro de Docker, POSTGRES_HOST debe apuntar al servicio propio (`postgres-vuelos`), POSTGRES_PORT
  a 5432, y RABBITMQ_HOST al servicio `rabbitmq` de la misma red, salvo un despliegue externo explícito.
  Los defaults localhost que Compose envía actualmente no apuntan a otros contenedores.
- El fallback de JAVA_OPTS mostrado en Compose es `Xmx512m -Xms256m`: falta el guion inicial de Xmx.
  Al respetar ahora esa variable, ese valor es inválido para Java. Isaac debe corregir el valor efectivo
  o usar JAVA_TOOL_OPTIONS; este servicio no lo "repara" silenciosamente.
- Preparar/importar definitions válido y acordar colas/bindings para vuelos.actualizados.
  No se inventan destinos definitivos; la cola vuelos.actualizados.pruebas existe solo en tests aislados.
- Acordar con Isabel permisos de escritura y comprobar Location detrás del Gateway. Se conservan
  ambos prefijos del servicio, pero no se modifican rutas ni filtros del Gateway.

## Resultado comprobado de esta fase

`bash scripts/test-outbox.sh` ejecutó **mvn verify → BUILD SUCCESS** con Java **17.0.20.1**,
Maven **3.9.12**, PostgreSQL **15.19** y RabbitMQ **3.13.7** temporales:

| Suite | Aprobadas | Fallos | Errores | Omitidas |
|-------|-----------|--------|---------|----------|
| VueloControllerTest | 49 | 0 | 0 | 0 |
| VueloServiceTest | 20 | 0 | 0 | 0 |
| PublicadorVuelosTest | 69 | 0 | 0 | 0 |
| VuelosOutboxWorkerTest | 5 | 0 | 0 | 0 |
| VueloPostgresIntegrationTest | 13 | 0 | 0 | 0 |
| VuelosOutboxPostgresRabbitTest | 18 | 0 | 0 | 0 |
| **Total** | **174** | **0** | **0** | **0** |

Las 78 pruebas anteriores siguen incluidas, ampliadas con concurrencia/409, no-op, escalas decimales,
precisión del mapper TIME, snapshot/revisión, rollback, validación, errores permanentes, ACK y return
reales, ausencia de binding, pausa/reanudación del broker temporal, recuperación al reconstruir el
publicador, reentrega tras rollback posterior al ACK y dos workers concurrentes. NACK se indujo en una
prueba unitaria; no se afirma haber provocado un NACK real del broker. La reconstrucción se probó como
componentes nuevos, no como reinicio de toda la JVM. Los logs SIN_DESTINO/CONFIRM_TIMEOUT/ERROR_DATOS
son fallos esperados inducidos por las pruebas, no fallos de la suite final.

`bash scripts/test-docker.sh` construyó y arrancó la imagen final sobre una red y PostgreSQL temporales.
Comprobó JAVA_OPTS en el proceso Java, puerto 8081, ambas rutas, POST/GET/PUT, PUT idéntico, revisión 1,
un evento persistido pendiente y servicio HTTP disponible con RabbitMQ inaccesible. Este smoke test
se contabiliza aparte de JUnit. Un fallo inicial de su comprobación docker top se corrigió incluyendo
PID en la salida; el script completo final pasó. Se excluyeron JARs locales del contexto en todo momento.

Las migraciones se ejecutaron solo en bases temporales, preservando los datos previos. No se tocó la
base local, Inventario ni contenedores existentes. Los tests retiran sus contenedores/redes temporales;
las imágenes de verificación y caché de build pueden permanecer localmente.

**No se comprobó el despliegue completo con el Compose compartido ni recepción por servicios reales**:
faltan los ajustes de conexión/topología de Isaac y contratos de consumidores. Tampoco se activó
emisión al crear vuelos. El servicio no interpreta vuelos.actualizados como orden de inicializar,
redimensionar o liberar asientos ni ejecuta compensaciones de reservas.
