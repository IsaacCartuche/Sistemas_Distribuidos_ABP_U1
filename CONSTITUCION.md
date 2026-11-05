# CONSTITUCIÓN DE DESARROLLO — Reservas de Vuelos (ABP U1)

**Proyecto:** Plataforma de Reservas de Vuelos (Microservicios Java + Spring Boot + RabbitMQ + PostgreSQL + Docker)
**Equipo:** Isabel, Cristian, Sebastian, Isaac
**Objetivo:** Trabajar en paralelo sin pisar código ajeno, sin conflictos de servicio y con mínimos puntos de sincronización.

---

## 1. División de trabajo (un dueño por servicio)

Ningún servicio es propiedad de dos personas al mismo tiempo. Cada integrante es el único responsable de escribir, probar y desplegar sus servicios asignados.

| Integrante | Servicios | Área funcional |
|------------|-----------|----------------|
| **Isabel** | `gateway/`, `usuarios/` | Autenticación JWT, roles, perfiles, enrutado REST, punto de entrada único. |
| **Cristian** | `vuelos/`, `inventario-asientos/` | Catálogo de vuelos, rutas, horarios. Bloqueo de asientos con TTL y lock optimista. |
| **Sebastian** | `reservas/`, `pagos/` | Creación de reservas, Saga por coreografía, idempotencia. Procesamiento de pagos y reembolsos. |
| **Isaac** | `frontend/`, `websocket-server/`, `notificaciones/`, `docker-compose.yml`, `.env` | Interfaz React, WebSocket tiempo real, notificaciones, coordinación de Docker y red. |

**Regla de oro:** Si necesitas modificar un servicio que no es tuyo, abre un Issue, acuerda el cambio con el dueño y haz Pull Request. No edites directamente la carpeta de otro integrante.

---

## 2. Orden de implementación (fases para no bloquearse)

| Fase | Quién | Qué entrega | Dependencias |
|------|-------|-------------|--------------|
| **Fase 1** | Isabel | `usuarios/` (registro, login, JWT) + `gateway/` (enrutado base) | Ninguna |
| **Fase 2** | Cristian | `vuelos/` (catálogo) + `inventario-asientos/` (tablas, lock, TTL) | Ninguna |
| **Fase 3** | Sebastian | `reservas/` + `pagos/` (Saga, compensaciones, idempotency key) | Fase 1 y Fase 2 |
| **Fase 4** | Isaac | `notificaciones/`, `websocket-server/`, `frontend/` + ajuste de `docker-compose.yml` | Fase 3 |

Cada fase debe tener su servicio funcionando en `docker-compose up` antes de que la siguiente empiece a integrar.

---

## 3. Contratos técnicos obligatorios (acuerdos de equipo)

### 3.1 RabbitMQ y colas con prioridad
- Exchange: `vuelos.events` (tipo topic, durable).
- Colas y prioridades:
  - `reservas.alta` → prioridad 10 (expiración de asientos, pagos urgentes).
  - `pagos.media` → prioridad 5 (reservas creadas, bloqueo de inventario).
  - `notificaciones.baja` → prioridad 1 (emails, WebSocket push).
- Dead Letter Exchange: `dlq.reservas` para mensajes que fallan repetidamente.
- Cada consumidor debe ser **idempotente** (RabbitMQ entrega al menos una vez).

### 3.2 Eventos mínimos (payloads)
Definir antes de codificar. Ejemplo de estructura base:

```json
{
  "eventId": "uuid",
  "correlationId": "uuid-de-la-reserva",
  "timestamp": "ISO-8601",
  "payload": { ... }
}
```

Eventos obligatorios:
- `vuelos.actualizados`
- `reserva.creada` / `reserva.cancelada` / `reserva.confirmada`
- `pago.exitoso` / `pago.fallido` / `pago.reembolsado`
- `asiento.bloqueado` / `asiento.liberado` / `asiento.expirado`
- `usuario.registrado`
- `notificacion.enviada`

### 3.3 Variables de entorno (`.env.example`)
Cada máquina puede tener su propio `.env`. No commitear `.env`. Solo `.env.example`.

Claves mínimas:
- `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_USER`, `POSTGRES_PASSWORD`
- `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASS`
- `JAVA_OPTS=-Xmx512m -Xms256m`

### 3.4 Variables de conexión en cada servicio
Cada `application.yml` usa su propia BD:
- `db_vuelos`, `db_reservas`, `db_pagos`, `db_usuarios`, `db_inventario`, `db_notificaciones`.

Sintaxis correcta: `${VAR:valor_por_defecto}`. Nunca `{{ }}` ni `$$`.

### 3.5 Puertos
| Servicio | Puerto contenedor | Puerto host (docker-compose) |
|----------|-------------------|------------------------------|
| gateway | 8080 | 8080 |
| vuelos | 8081 | 8081 |
| reservas | 8082 | 8082 |
| pagos | 8083 | 8083 |
| usuarios | 8084 | 8084 |
| inventario-asientos | 8085 | 8085 |
| notificaciones | 8086 | 8086 |
| websocket-server | 8090 | 8090 |
| frontend | 3000 | 3000 |
| postgres-* | 5432 | 5431–5436 |

---

## 4. Reglas de concurrencia y sobreventa (no negociables)

### 4.1 Evitar sobreventa de asientos
- `inventario-asientos/`: tabla `asientos` con columna `version INTEGER DEFAULT 0` (lock optimista `@Version` en JPA).
- Bloqueo de asiento: marcar `estado = 'BLOQUEADO'` y `bloqueado_hasta = ahora + TTL` dentro de una transacción.
- Si dos peticiones llegan al mismo tiempo, solo una gana el `UPDATE ... WHERE version = X`. La otra falla y se reintenta o se rechaza.
- `reservas/`: columna `idempotency_key VARCHAR(100) UNIQUE` para evitar doble reserva por reintentos del cliente.

### 4.2 Transacciones distribuidas (Saga por coreografía)
No hay transacción ACID entre servicios. El flujo es:

1. `reservas/` crea reserva en estado `PENDIENTE` y publica `reserva.creada`.
2. `inventario-asientos/` consume, intenta bloquear asiento. Si falla, publica `asiento.no_disponible`.
3. `pagos/` consume, procesa pago. Si falla, publica `pago.fallido`.
4. Si todo OK, `reservas/` pasa a `CONFIRMADA`. Si cualquier paso falla, se publican eventos de compensación (`asiento.liberado`, `pago.reembolsado`, `reserva.cancelada`).

Cada servicio es dueño de su compensación.

---

## 5. Reglas de Git y ramas

- Rama principal: `main` (siempre estable, pasa `docker-compose up`).
- Cada integrante trabaja en su propia rama: `feature/isabel-usuarios`, `feature/cristian-vuelos`, etc.
- Nunca hagas push directo a `main`.
- Pull Request obligatorio para fusionar a `main`. Revisa al menos un compañero (no el dueño del servicio).
- Mensajes de commit: `feat(servicio): descripción` o `fix(servicio): descripción`.
- No commitear archivos locales: `.env`, `target/`, `node_modules/`, `.idea/`, `.vscode/`, `*.jar`.

---

## 6. Definición de listo (DoD) por servicio

Un servicio se considera terminado cuando:

1. Tiene su `Dockerfile` y su `sql/init.sql` en su carpeta.
2. Arranca con `docker-compose up --profile services` (o su perfil correspondiente).
3. Se conecta a su propia base de datos PostgreSQL sin tocar las de otros.
4. Publica y consume sus eventos de RabbitMQ según el contrato acordado.
5. Es idempotente (reenvío de mensajes no duplica acciones).
6. Tiene README propio explicando endpoints y variables de entorno.

---

## 7. Despliegue: local vs múltiples máquinas

### 7.1 Un solo comando (local)
```bash
cp .env.example .env   # dejar valores por defecto (localhost)
docker-compose up --profile all
```

### 7.2 Varias computadoras en la misma red
1. Una máquina es el "host": corre RabbitMQ y todas las PostgreSQL.
2. Las demás máquinas editan su `.env` con `POSTGRES_HOST` y `RABBITMQ_HOST` apuntando a la IP del host.
3. Cada integrante levanta solo sus servicios:
   ```bash
   docker-compose up --profile services   # si eres Cristian o Sebastian
   docker-compose up --profile gateway    # si eres Isabel
   docker-compose up --profile frontend   # si eres Isaac
   ```

---

## 8. Reglas de comunicación mínima (trabajo en paralelo)

- **Daily async:** cada uno avisa en el grupo de WhatsApp/Telegram qué terminó y qué bloquea (máximo 2 líneas).
- **Bloqueos:** si necesitas el contrato de un evento de otro integrante, pídelos una vez y documenta el acuerdo en `docs/`.
- **No se pide permiso para escribir dentro de tu propia carpeta.** Solo se avisa cuando algo cambia la API pública o los eventos de RabbitMQ.
- **Única excepción a la regla de no tocar código ajeno:** arreglos críticos en `docker-compose.yml` o `.env.example` (coordinado por Isaac).

---

## 9. Estructura de carpetas resumen

```
.
├── .env.example
├── .gitignore
├── CONSTITUCION.md
├── README.md
├── docker-compose.yml
├── docs/
├── infra/
│   └── rabbitmq/definitions.json
├── gateway/
├── websocket-server/
├── frontend/
└── servicios/
    ├── vuelos/          (Isabel? No, Cristian)
    ├── reservas/        (Sebastian)
    ├── pagos/           (Sebastian)
    ├── usuarios/        (Isabel)
    ├── inventario-asientos/ (Cristian)
    └── notificaciones/  (Isaac)
```

---

## 10. Contactos y responsabilidades finales

| Integrante | Servicios | Si algo falla en... | Pregúntale a |
|------------|-----------|---------------------|--------------|
| Isabel | gateway, usuarios | Auth, JWT, enrutado | Isabel |
| Cristian | vuelos, inventario-asientos | Catálogo, asientos, sobreventa | Cristian |
| Sebastian | reservas, pagos | Saga, reembolsos, idempotencia | Sebastian |
| Isaac | frontend, websocket, notificaciones, compose | UI, tiempo real, levantar en red | Isaac |

**Última regla:** Si no está escrito aquí ni en los README de cada servicio, no se asume. Pregunta, acuerda y documenta en `docs/`.
