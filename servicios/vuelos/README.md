# Servicio de Vuelos

Primera fase funcional del catálogo de vuelos, rutas y horarios.

## Tecnología y alcance

- Java 17 y Spring Boot **3.2.0**.
- Spring Web, Spring Data JPA, Jakarta Validation y PostgreSQL.
- Base de datos propia: **`db_vuelos`**. Tabla `vuelos`, compatible con `sql/init.sql`.
- Puerto HTTP: **8081**.
- Las dependencias y propiedades AMQP preexistentes se conservan; esta fase no publica ni consume eventos.

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
└── persistence      # Repositorio Spring Data JPA
```

## Configuración y ejecución local

Requisitos: JDK 17, Maven y una base PostgreSQL `db_vuelos` previamente inicializada con
`sql/init.sql` en un entorno de desarrollo. El servicio utiliza `ddl-auto: validate`:
valida el esquema existente y no crea ni modifica tablas. No ejecute el SQL sobre bases compartidas
sin coordinación; el arranque no lo ejecuta automáticamente.

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

Las propiedades `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER` y `RABBITMQ_PASS` permanecen
configuradas para fases posteriores. No se necesita RabbitMQ para las pruebas de esta fase.
El Dockerfile preexistente no se ha adaptado en esta entrega; la construcción con el contexto raíz
de Compose y la ejecución de `JAVA_OPTS` siguen pendientes de la fase de despliegue.

## API REST

Base: `http://localhost:8081/api/v1/vuelos`.

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
```

- `VueloServiceTest`: normalización, precisión decimal, validaciones de ruta/capacidad,
  consultas y filtros, actualización, conservación de identidad y recursos inexistentes.
- `VueloControllerTest`: contrato HTTP con MockMvc, `Location`, DTOs, validaciones,
  filtros y fechas, errores 400/404/409 y rechazo de JSON inválido.

Las dos clases anteriores usan mocks: no abren conexiones PostgreSQL/RabbitMQ ni modifican bases de datos.
Las pruebas PostgreSQL son optativas y se omiten si `VUELOS_POSTGRES_INTEGRATION` no vale `true`.
No se incluyen Saga, autenticación, bloqueo de asientos o TTL en esta fase.

### Integración con PostgreSQL real y aislado

Desde `servicios/vuelos/`, con JDK 17, Maven y Docker funcionando:

```bash
bash scripts/test-postgres.sh
```

El script crea un contenedor nuevo `postgres:15-alpine`, con `db_vuelos` y credenciales exclusivas
de prueba, datos en `tmpfs` y puerto host libre asignado por Docker, accesible solo por `127.0.0.1`.
Monta `sql/init.sql` en modo lectura para inicializar únicamente esa base nueva. Espera a que
PostgreSQL acepte conexiones TCP y ejecuta `mvn test`. Al terminar, incluso si Maven falla,
detiene exclusivamente el contenedor creado, que se elimina automáticamente con sus datos temporales.
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

### Resultado comprobado de la verificación (2026-11-07)

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

**Estado:** catálogo REST listo para comenzar la integración funcional y desarrollar inventario.
No equivale todavía a cumplir toda la definición de listo de la constitución:

- No se probaron la imagen de Vuelos, el despliegue con Compose ni el acceso a través del gateway.
- El Dockerfile declara `EXPOSE 8080`, mientras el servicio escucha en 8081; es metadato que debe
  alinearse en la fase de despliegue. Su `ENTRYPOINT` tampoco expande `JAVA_OPTS`.
- La validación HTTP usa puerto aleatorio; no certifica que 8081 esté disponible en otro entorno.
  La prueba usa credenciales temporales, no valida credenciales de instalaciones existentes.
- No se probaron integración RabbitMQ, eventos, idempotencia de mensajes ni Saga.
- Persisten los límites del catálogo: sin paginación, control de actualizaciones concurrentes
  ni gestión de zonas horarias. El SQL no incluye CHECKs de precio/capacidad/ruta ni restringe
  los valores de estado; esas reglas se validan en la API, no frente a escrituras SQL externas.
- `capacidadDisponible` sigue siendo informativa y no autoriza reservas.
