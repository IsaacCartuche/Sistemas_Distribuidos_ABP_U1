# Sistemas Distribuidos - ABP U1
Plataforma de reservas de vuelos basada en microservicios.

## Servicios
- API Gateway: puerto 8080
- WebSocket tiempo real: puerto 8090
- Frontend React: puerto 3000
- Vuelos: puerto 8081
- Reservas: puerto 8082
- Pagos: puerto 8083
- Usuarios: puerto 8084
- Inventario de asientos: puerto 8085
- Notificaciones: puerto 8086

Cada microservicio tiene su propia base de datos PostgreSQL. La comunicacion entre servicios usa RabbitMQ.

## Tecnologias
Java 17, Spring Boot, Spring Cloud Gateway, RabbitMQ, PostgreSQL, Docker, Docker Compose, React.

## Requisitos
- Docker y Docker Compose

## Despliegue local
cp .env.example .env
# Configurar JWT_SECRET en .env
docker-compose --profile all up --build

Para detener y eliminar contenedores:
docker-compose down

## Variables de entorno obligatorias
- JWT_SECRET: clave para firmar tokens de autenticación

## Acceso
- Frontend: http://localhost:3000
- API Gateway: http://localhost:8080
- Consola RabbitMQ: http://localhost:15672
