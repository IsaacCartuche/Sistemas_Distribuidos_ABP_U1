# Sistemas Distribuidos - ABP U1
Plataforma de Reservas de Vuelos - Arquitectura de Microservicios

## Servicios
| Carpeta | Servicio | Puerto | Tecnología |
|---------|----------|--------|------------|
| `gateway/` | API Gateway | 8080 | Spring Cloud Gateway |
| `websocket-server/` | WebSocket tiempo real | 8090 | Spring Boot WebSocket |
| `frontend/` | Interfaz web | 3000 | React |
| `servicios/vuelos/` | Catálogo de vuelos | 8081 | Java + Spring Boot |
| `servicios/reservas/` | Reservas y Saga | 8082 | Java + Spring Boot |
| `servicios/pagos/` | Procesamiento de pagos | 8083 | Java + Spring Boot |
| `servicios/usuarios/` | Auth, JWT y perfiles | 8084 | Java + Spring Boot |
| `servicios/inventario-asientos/` | Disponibilidad de asientos | 8085 | Java + Spring Boot |
| `servicios/notificaciones/` | Emails y eventos | 8086 | Java + Spring Boot |

## Requisitos
- Docker y Docker Compose
- Java 17+
- Maven

## Levantamiento local (una sola máquina)
```bash
cp .env.example .env
# Editar .env si es necesario
docker-compose up
```

## Desarrollo distribuido (varias computadoras en misma red)
1. Una máquina actúa como host de RabbitMQ y PostgreSQL.
2. En cada computadora, copiar `.env.example` a `.env` y configurar:
   - `POSTGRES_HOST` con la IP del host de BD
   - `RABBITMQ_HOST` con la IP del host de RabbitMQ
3. Cada integrante levanta solo sus servicios asignados.

## Organización del equipo (4 integrantes)
- Integrante 1: `gateway/`, `usuarios/`, seguridad JWT
- Integrante 2: `vuelos/`, `inventario-asientos/`
- Integrante 3: `reservas/`, `pagos/` (Saga e idempotencia)
- Integrante 4: `notificaciones/`, `websocket-server/`, `frontend/`

Cada uno trabaja en su propia rama y hace Pull Request a `main`.
