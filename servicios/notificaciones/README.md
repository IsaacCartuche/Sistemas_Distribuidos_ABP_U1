# Servicio de Notificaciones

Servicio responsable de recibir eventos de negocio vía RabbitMQ, persistirlos en su propia base de datos (`db_notificaciones`) y reenviarlos a la cola `notificaciones.baja` para entrega en tiempo real vía WebSocket.

## Tecnología
- Java 17 + Spring Boot 3.2
- Spring Data JPA + PostgreSQL (BD propia: `db_notificaciones`)
- Spring AMQP + RabbitMQ
- REST para consulta del frontend

## Puerto
8086

## Colas RabbitMQ
- Consume: `notificaciones.entrada` (eventos de negocio: reserva creada, pago exitoso, etc.)
- Publica: `vuelos.events` con routing key `notificacion.baja` (cola `notificaciones.baja` para WebSocket)

## Endpoints REST
- `GET /notificaciones/usuario/{usuarioId}` — lista notificaciones de un usuario
- `PATCH /notificaciones/{id}/leida?usuarioId=xxx` — marca como leída

## Variables de entorno
- `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_USER`, `POSTGRES_PASSWORD`
- `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASS`
- `JAVA_OPTS`

## Construir y ejecutar localmente
```bash
./mvnw clean package
docker build -t servicio-notificaciones .
docker run -p 8086:8086 --env-file .env servicio-notificaciones
```

## Pruebas
```bash
./mvnw test
```
